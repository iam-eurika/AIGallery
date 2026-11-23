package com.lucrativeworm.seekframe.ml

import android.content.Context
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.lucrativeworm.seekframe.data.PhotoVector
import com.lucrativeworm.seekframe.data.VideoMoment
import dagger.hilt.android.qualifiers.ApplicationContext
import io.objectbox.BoxStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Which EmbeddingGemma 2 the user wants search to run on. Stored, and picked in Settings. */
enum class ModelChoice {
    /** Android's own model when this phone offers it, otherwise the bundled download. */
    AUTO,
    /** Only Android's model (ML Kit GenAI on AICore). No app download, but needs a supported phone. */
    SYSTEM,
    /** Only the bundled LiteRT-LM model. A ~390 MB download that works on any phone. */
    BUNDLED,
}

/**
 * What this phone's Android offers through AICore, as far as we know. [UNREACHABLE] means AICore
 * didn't answer (it often fails to bind right after an app update or reboot): unknown, not "no".
 */
enum class SystemModelStatus { CHECKING, UNREACHABLE, UNSUPPORTED, DOWNLOADABLE, DOWNLOADING, AVAILABLE }

/**
 * The two downloadable EmbeddingGemma 2 files. They map text and images into the same kind of
 * space, but they're different files and we don't assume their vectors are interchangeable.
 */
enum class Variant(val fileName: String, val id: String, val repo: String, val sha256: String, val approxMb: Int, val audio: Boolean) {
    /** Text + images. What a gallery needs, and the smallest file that does it. */
    VISION(
        "embeddinggemma-2-text-vision-440m.litertlm", "embeddinggemma-2-text-vision-440m",
        "embeddinggemma-2-text-vision-440m-litert-lm",
        "92dcbea108899e5d6e30d919b0744f90d9967e80c67a4ab5503ac16d54f62eb0", 390, audio = false,
    ),
    /** Text + images + audio: also lets search find what is heard in videos. */
    FULL(
        "embeddinggemma-2-740m.litertlm", "embeddinggemma-2-740m",
        "embeddinggemma-2-740m-litert-lm",
        "e7a8a2204b91e0f96e92960e84a09a89212e1633dcb7575a9bf3378b4df77f4c", 485, audio = true,
    );

    val url get() = "https://huggingface.co/litert-community/$repo/resolve/main/$fileName"
}

/**
 * Vosk's small offline English speech-to-text model (Apache 2.0), for spoken words in videos. A zip
 * from alphacephei.com, unpacked into the models folder.
 */
object SpeechModel {
    const val NAME = "vosk-model-small-en-us-0.15"
    const val URL = "https://alphacephei.com/vosk/models/$NAME.zip"
    const val SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
    const val APPROX_MB = 41
}

/**
 * Finds or fetches EmbeddingGemma 2, either the copy Android manages through ML Kit GenAI
 * (AICore) or a bundled LiteRT-LM file, following the user's [ModelChoice] and whether they want
 * sounds in videos to be searchable ([sounds]). Only the bundled [Variant.FULL] file has the audio
 * encoder, so sound search needs it; without sounds the smaller [Variant.VISION] file is enough.
 *
 * Developers can skip the bundled download with:
 *   adb push embeddinggemma-2-text-vision-440m.litertlm /data/local/tmp/aigallery/
 *   adb push embeddinggemma-2-740m.litertlm /data/local/tmp/aigallery/
 */
@Singleton
class ModelStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: BoxStore,
) {

    /** Where a ready model lives; [Embedder] loads whichever this names. */
    sealed interface Source {
        val id: String
        class System(val model: SystemModel) : Source { override val id get() = model.id }
        class Bundled(val file: File, val variant: Variant) : Source { override val id get() = variant.id }
    }

    enum class Target { SYSTEM, BUNDLED }

    sealed interface State {
        /** Nothing ready yet; [target] is what Download would fetch. */
        data class Missing(val target: Target) : State
        data class Downloading(val bytes: Long, val total: Long, val target: Target = Target.BUNDLED) : State {
            val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
        }
        data object Verifying : State
        data class Ready(val source: Source) : State
        /** The chosen model can't run on this phone (Android-only choice without AICore). */
        data object Unsupported : State
        data class Failed(val message: String, val target: Target = Target.BUNDLED) : State
    }

    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)
    private val _choice = MutableStateFlow(
        runCatching { ModelChoice.valueOf(prefs.getString(KEY_CHOICE, null) ?: "") }.getOrDefault(ModelChoice.AUTO)
    )
    val choice: StateFlow<ModelChoice> = _choice.asStateFlow()

    private val _state = MutableStateFlow<State>(State.Missing(Target.BUNDLED))
    val state: StateFlow<State> = _state.asStateFlow()

    /** False until [locate] has looked once; until then [state]'s "Missing" is only a placeholder. */
    private val _checked = MutableStateFlow(false)
    val checked: StateFlow<Boolean> = _checked.asStateFlow()

    private val system = SystemModel.Probe(context)
    val systemStatus: StateFlow<SystemModelStatus> = system.status

    private val modelDir = File(context.filesDir, "models").apply { mkdirs() }

    private val _sounds = MutableStateFlow(if (prefs.contains(KEY_SOUNDS)) prefs.getBoolean(KEY_SOUNDS, false) else null)
    /**
     * Whether sounds in videos should be searchable; null until the user has been asked. It decides
     * which model to download (and switching later re-indexes the whole library), so it's asked
     * before the first download, again on app open while unanswered, and in Settings.
     */
    val sounds: StateFlow<Boolean?> = _sounds.asStateFlow()

    fun setSounds(on: Boolean) {
        prefs.edit().putBoolean(KEY_SOUNDS, on).apply()
        _sounds.value = on
    }

    /** True when sounds are on but the model with the audio encoder hasn't been downloaded yet. */
    private val _soundModelMissing = MutableStateFlow(false)
    val soundModelMissing: StateFlow<Boolean> = _soundModelMissing.asStateFlow()

    /**
     * Progress of the separate download of [Variant.FULL] while search keeps running on the model
     * already loaded. Null when no such download is going on.
     */
    private val _soundDownload = MutableStateFlow<State?>(null)
    val soundDownload: StateFlow<State?> = _soundDownload.asStateFlow()

    /** Sound search is wanted and possible with the current choice (Android's model has no audio). */
    private fun wantsFull() = _sounds.value == true && _choice.value != ModelChoice.SYSTEM

    fun setChoice(choice: ModelChoice) {
        prefs.edit().putString(KEY_CHOICE, choice.name).apply()
        _choice.value = choice
    }

    /** The bundled file for [variant] on disk, if any. */
    fun bundledFile(variant: Variant): File? {
        val candidates = listOfNotNull(
            File("/data/local/tmp/aigallery", variant.fileName),
            context.getExternalFilesDir(null)?.let { File(it, variant.fileName) },
            File(modelDir, variant.fileName),
        )
        return candidates.firstOrNull { it.canRead() && it.length() > 0 }
    }

    /** The best bundled file on disk: the full one does everything the smaller one does. */
    fun bundledSource(): Source.Bundled? =
        bundledFile(Variant.FULL)?.let { Source.Bundled(it, Variant.FULL) }
            ?: if (wantsFull()) null else bundledFile(Variant.VISION)?.let { Source.Bundled(it, Variant.VISION) }

    fun bundledDownloaded(): Boolean = bundledFile(Variant.FULL) != null || bundledFile(Variant.VISION) != null

    /**
     * Works out which model to use for the current [choice] and publishes it as [state].
     * Returns the ready [Source], or null when something still has to be downloaded or the
     * choice can't run here.
     */
    suspend fun locate(): Source? {
        if (_state.value is State.Downloading || _state.value == State.Verifying) return null.also { _checked.value = true }
        // The downloaded model doesn't need AICore: don't hold up search waiting for it to answer.
        val status = if (_choice.value == ModelChoice.BUNDLED) system.status.value else system.check()
        // Android-only was picked, but this phone has no AICore: there's no such choice here (Settings
        // hides it), so fall back to Automatic instead of leaving search off.
        if (status == SystemModelStatus.UNSUPPORTED && _choice.value == ModelChoice.SYSTEM) setChoice(ModelChoice.AUTO)
        val full = bundledFile(Variant.FULL)?.let { State.Ready(Source.Bundled(it, Variant.FULL)) }
        // Until the sound model arrives, search keeps running on whatever model is already here.
        val fallback = bundledFile(Variant.VISION)?.let { State.Ready(Source.Bundled(it, Variant.VISION)) }
        val next: State = when (_choice.value) {
            ModelChoice.SYSTEM -> systemState(status)
                ?: if (status == SystemModelStatus.UNREACHABLE) State.Failed(SystemModel.UNREACHABLE_MESSAGE, Target.SYSTEM) else State.Unsupported
            ModelChoice.BUNDLED -> full ?: fallback ?: State.Missing(Target.BUNDLED)
            ModelChoice.AUTO -> {
                // Vectors from different models can't be compared, so changing model re-indexes
                // the whole library. Automatic therefore stays with whatever built the index.
                val indexed = indexedWith()
                val systemReady = systemState(status)?.takeIf { it is State.Ready }
                val kept = listOfNotNull(full, fallback, systemReady).firstOrNull { (it as State.Ready).source.id == indexed }
                when {
                    // Once the model that can hear is here, keep it even with sounds off: it does
                    // everything the others do, and switching away would re-index the whole library.
                    full != null -> full
                    kept != null -> kept
                    // The index is Android's, but AICore didn't answer this time: wait for it
                    // rather than throw that index away for a bundled file.
                    indexed?.startsWith(SystemModel.ID_PREFIX) == true && status == SystemModelStatus.UNREACHABLE ->
                        State.Failed(SystemModel.UNREACHABLE_MESSAGE, Target.SYSTEM)
                    wantsFull() -> systemReady ?: fallback ?: State.Missing(Target.BUNDLED)
                    systemReady != null -> systemReady
                    // A bundled file the user already fetched beats a second, system-side download.
                    else -> fallback ?: systemState(status) ?: State.Missing(Target.BUNDLED)
                }
            }
        }
        _state.value = next
        _soundModelMissing.value = wantsFull() && full == null
        _checked.value = true
        return (next as? State.Ready)?.source
    }

    /** The model id the existing index was built with, or null while nothing is indexed. */
    private suspend fun indexedWith(): String? = withContext(Dispatchers.IO) {
        store.boxFor(PhotoVector::class.java).query().build().use { it.findFirst() }?.model
            ?: store.boxFor(VideoMoment::class.java).query().build().use { it.findFirst() }?.model
    }

    private suspend fun systemState(status: SystemModelStatus): State? = when (status) {
        SystemModelStatus.AVAILABLE -> system.model()?.let { State.Ready(Source.System(it)) }
        SystemModelStatus.DOWNLOADABLE, SystemModelStatus.DOWNLOADING -> State.Missing(Target.SYSTEM)
        else -> null
    }

    /** Downloads whatever the current state says is missing, then becomes [State.Ready]. */
    suspend fun download(): Source {
        system.retrySoon()
        locate()?.let { return it }
        // AICore was just asked again and still didn't answer; asking a third time won't help.
        if (system.status.value == SystemModelStatus.UNREACHABLE && (_state.value as? State.Failed)?.target == Target.SYSTEM) {
            throw IOException(SystemModel.UNREACHABLE_MESSAGE)
        }
        return when ((_state.value as? State.Missing)?.target ?: (_state.value as? State.Failed)?.target) {
            Target.SYSTEM -> downloadSystem()
            else -> {
                val variant = if (wantsFull()) Variant.FULL else Variant.VISION
                Source.Bundled(downloadBundled(variant) { _state.value = it }, variant).also {
                    _state.value = State.Ready(it)
                    locate()
                }
            }
        }
    }

    /**
     * Fetches the model with the audio encoder while search keeps running on the current one.
     * Once it's in place, [locate] switches to it and the smaller file is deleted.
     */
    suspend fun downloadSoundModel() {
        if (_soundDownload.value is State.Downloading || _soundDownload.value == State.Verifying) return
        if (bundledFile(Variant.FULL) == null) {
            // A failure stays in [soundDownload] so Settings can offer to try again.
            downloadBundled(Variant.FULL) { _soundDownload.value = it }
        }
        _soundDownload.value = null
        File(modelDir, Variant.VISION.fileName).delete()
        locate()
    }

    private val speechDir = File(modelDir, SpeechModel.NAME)

    /** The unpacked speech-to-text model, or null until it's been downloaded. */
    fun speechModelDir(): File? = speechDir.takeIf { File(it, SPEECH_READY).exists() }

    /** Android's own speech-to-text (ML Kit GenAI), preferred where the phone offers it. */
    val googleSpeech = GoogleSpeech()

    private val _speech = MutableStateFlow(
        SpeechStatus(
            choice = prefs.getString(KEY_SPEECH, null)?.let { runCatching { SpeechChoice.valueOf(it) }.getOrNull() },
            downloaded = speechModelDir() != null,
        )
    )
    /**
     * Speech-to-text for spoken words in videos: the user's choice (null until asked), what this
     * phone offers, and any download. Nothing is downloaded until the user asks for it.
     */
    val speech: StateFlow<SpeechStatus> = _speech.asStateFlow()

    fun setSpeechChoice(choice: SpeechChoice) {
        prefs.edit().putString(KEY_SPEECH, choice.name).apply()
        _speech.update { it.copy(choice = choice) }
    }

    /** Asks Android whether it offers its speech model on this phone. */
    suspend fun checkAndroidSpeech() {
        val status = when (googleSpeech.status()) {
            FeatureStatus.AVAILABLE -> SystemModelStatus.AVAILABLE
            FeatureStatus.DOWNLOADABLE -> SystemModelStatus.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> SystemModelStatus.DOWNLOADING
            else -> SystemModelStatus.UNSUPPORTED
        }
        _speech.update { it.copy(android = status) }
    }

    /**
     * Downloads whatever the user's speech choice still needs: Android's model through ML Kit, or
     * the app's own. Only ever called from the user's own tap.
     */
    suspend fun downloadSpeech() {
        val s = _speech.value.download
        if (s is State.Downloading || s == State.Verifying) return
        when (_speech.value.toFetch) {
            SpeechEngine.ANDROID -> downloadAndroidSpeech()
            SpeechEngine.DOWNLOADED -> downloadSpeechModel()
            null -> {}
        }
    }

    private suspend fun downloadAndroidSpeech() {
        val publish: (State?) -> Unit = { st -> _speech.update { it.copy(download = st) } }
        try {
            var total = 0L
            publish(State.Downloading(0, 0, Target.SYSTEM))
            googleSpeech.download().collect { d ->
                when (d) {
                    is DownloadStatus.DownloadStarted -> { total = d.bytesToDownload; publish(State.Downloading(0, total, Target.SYSTEM)) }
                    is DownloadStatus.DownloadProgress -> publish(State.Downloading(d.totalBytesDownloaded, total, Target.SYSTEM))
                    is DownloadStatus.DownloadFailed -> throw d.e
                    else -> {}
                }
            }
            checkAndroidSpeech()
            publish(null)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            publish(State.Failed(t.message ?: "Android couldn't download its speech model", Target.SYSTEM))
            throw t
        }
    }

    /** Downloads and unpacks the app's own speech-to-text model. */
    private suspend fun downloadSpeechModel() {
        if (speechModelDir() != null) { _speech.update { it.copy(downloaded = true) }; return }
        val publish: (State) -> Unit = { st -> _speech.update { it.copy(download = st) } }
        val zip = downloadFile(SpeechModel.URL, "${SpeechModel.NAME}.zip", SpeechModel.SHA256, publish)
        try {
            publish(State.Verifying)
            withContext(Dispatchers.IO) { unpack(zip) }
        } catch (t: Throwable) {
            if (t !is kotlinx.coroutines.CancellationException) publish(State.Failed(t.message ?: "Couldn't unpack the speech model"))
            throw t
        } finally {
            zip.delete()
        }
        _speech.update { it.copy(downloaded = true, download = null) }
    }

    /** Unzips the model, dropping the archive's top folder, then moves it into place. */
    private fun unpack(zip: File) {
        val tmp = File(modelDir, "${SpeechModel.NAME}.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val root = tmp.canonicalPath + File.separator
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val name = entry.name.substringAfter('/', "")
                if (name.isEmpty()) continue
                val out = File(tmp, name)
                // Never write outside the target folder, whatever the archive says.
                if (!out.canonicalPath.startsWith(root)) throw IOException("Bad entry in speech model: ${entry.name}")
                if (entry.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                out.outputStream().use { input.copyTo(it) }
            }
        }
        if (!File(tmp, "am/final.mdl").exists()) throw IOException("The speech model download is incomplete")
        File(tmp, SPEECH_READY).writeText(SpeechModel.NAME)
        speechDir.deleteRecursively()
        if (!tmp.renameTo(speechDir)) throw IOException("Could not move the speech model into place")
    }

    private suspend fun downloadSystem(): Source {
        try {
            val model = system.download { bytes, total -> _state.value = State.Downloading(bytes, total, Target.SYSTEM) }
            return Source.System(model).also { _state.value = State.Ready(it) }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            _state.value = State.Failed(t.message ?: "Android couldn't download the model", Target.SYSTEM)
            throw t
        }
    }

    /** Resumable download straight from Hugging Face (Apache 2.0, ungated). */
    private suspend fun downloadBundled(variant: Variant, publish: (State) -> Unit): File {
        bundledFile(variant)?.let { return it }
        return downloadFile(variant.url, variant.fileName, variant.sha256, publish)
    }

    /** Resumable, checksummed download of [url] into the models folder as [fileName]. */
    private suspend fun downloadFile(url: String, fileName: String, sha256: String, publish: (State) -> Unit): File = withContext(Dispatchers.IO) {
        val target = File(modelDir, fileName)
        val partial = File(modelDir, "$fileName.part")
        try {
            var resumeFrom = partial.takeIf { it.exists() }?.length() ?: 0L
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 30_000
                if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) resumeFrom = 0L // server ignored Range
            else if (code != HttpURLConnection.HTTP_PARTIAL) throw IOException("HTTP $code")

            val total = resumeFrom + conn.contentLengthLong.coerceAtLeast(0)
            var bytes = resumeFrom
            var lastEmit = 0L
            publish(State.Downloading(bytes, total))
            conn.inputStream.use { input ->
                FileOutputStream(partial, resumeFrom > 0).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        bytes += n
                        if (bytes - lastEmit > 1_000_000) {
                            lastEmit = bytes
                            publish(State.Downloading(bytes, total))
                        }
                    }
                }
            }

            publish(State.Verifying)
            val digest = partial.sha256()
            if (digest != sha256) {
                partial.delete()
                throw IOException("Checksum mismatch, the download was corrupted")
            }
            if (!partial.renameTo(target)) throw IOException("Could not move model into place")
            target
        } catch (t: Throwable) {
            publish(State.Failed(t.message ?: "Download failed"))
            throw t
        }
    }

    private fun File.sha256(): String {
        val md = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_CHOICE = "choice"
        private const val KEY_SOUNDS = "sounds"
        private const val KEY_SPEECH = "speech_choice"
        private const val SPEECH_READY = ".ready"
    }
}
