package com.lucrativeworm.aigallery.ml

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Thin, thread-safe wrapper around EmbeddingGemma 2, run either by Android itself through ML Kit
 * GenAI ([SystemModel]) or by MediaPipe's UniversalEmbedder on the bundled LiteRT-LM file.
 *
 * All inference goes through one [Mutex]. Kotlin's mutex is fair, so a search query that arrives
 * while the indexer is busy waits for at most one image instead of the whole batch.
 */
class Embedder(private val context: Context) {

    enum class Backend { SYSTEM, GPU, CPU }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val inference = Dispatchers.Default.limitedParallelism(1)
    private val mutex = Mutex()
    private var embedder: UniversalEmbedder? = null
    private var system: SystemModel? = null

    private val _model = MutableStateFlow<String?>(null)
    /** The [ModelStore.Source.id] of the loaded model; tags every vector it produces. */
    val model: StateFlow<String?> = _model.asStateFlow()
    val modelId: String? get() = _model.value

    var backend: Backend? = null
        private set

    /** True when the loaded model has the audio encoder ([Variant.FULL]), so sounds can be embedded. */
    @Volatile var supportsAudio: Boolean = false
        private set

    private val _ready = MutableStateFlow(false)
    /** Observed by the UI so search lights up no matter who loaded the model (app or worker). */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()
    val isReady: Boolean get() = modelId != null

    /** Loads [source], swapping out whatever was loaded before if it was a different model. */
    suspend fun load(source: ModelStore.Source) = mutex.withLock {
        if (modelId == source.id) return@withLock
        closeLocked()
        when (source) {
            is ModelStore.Source.System -> {
                source.model.warmup()
                system = source.model
                backend = Backend.SYSTEM
            }
            is ModelStore.Source.Bundled -> {
                loadBundled(source.file, source.variant.audio)
                supportsAudio = source.variant.audio
            }
        }
        _model.value = source.id
        _ready.value = true
    }

    private suspend fun loadBundled(model: File, audio: Boolean) {
        withContext(inference) {
            if (isEmulator()) {
                // Emulators expose a software-rendered "GPU" that is far slower than XNNPack on CPU.
                embedder = create(model, Delegate.CPU, audio).also { backend = Backend.CPU }
                return@withContext
            }
            embedder = runCatching { create(model, Delegate.GPU, audio) }
                .onSuccess { backend = Backend.GPU }
                .recoverCatching {
                    Log.w(TAG, "GPU delegate unavailable, falling back to CPU", it)
                    create(model, Delegate.CPU, audio).also { backend = Backend.CPU }
                }
                .getOrThrow()
        }
    }

    private fun isEmulator(): Boolean =
        Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") ||
            Build.FINGERPRINT.contains("generic") || Build.PRODUCT.contains("sdk")

    private fun create(model: File, delegate: Delegate, audio: Boolean): UniversalEmbedder {
        val base = BaseOptions.builder()
            .setModelAssetPath(model.absolutePath)
            .setDelegate(delegate)
            .build()
        val options = UniversalEmbedderOptions.builder()
            .setBaseOptions(base)
            .setTextDelegate(delegate)
            .setVisionDelegate(delegate)
            .apply { if (audio) setAudioDelegate(delegate) }
            .setL2Normalize(true)
            // 70 soft tokens per image (vs 140) is the budget Google benchmarks on-device: roughly
            // half the vision cost, which matters when indexing thousands of photos.
            .setVisionTokensPerImage(VISION_TOKENS)
            .setCacheDir(context.cacheDir.absolutePath)
            .build()
        return UniversalEmbedder.createFromOptions(context, options)
    }

    suspend fun embedText(text: String): FloatArray = mutex.withLock {
        system?.let { return@withLock it.embedText(text) }
        val e = checkNotNull(embedder) { "Embedder not loaded" }
        withContext(inference) { e.embedText(text).embeddings().first().floatEmbedding() }
    }

    /**
     * @param background true for indexing: the calling thread drops to background priority for
     * the duration so a search query or the UI never waits behind it for CPU.
     */
    suspend fun embedImage(bitmap: Bitmap, background: Boolean = false): FloatArray =
        embedImageTagged(bitmap, background).first

    /** Like [embedImage], plus the id of the model that made the vector, read under the same lock. */
    suspend fun embedImageTagged(bitmap: Bitmap, background: Boolean = false): Pair<FloatArray, String> {
        val rgba = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        // Don't close the MPImage: that would recycle a bitmap the caller still owns.
        return mutex.withLock {
            val id = checkNotNull(modelId) { "Embedder not loaded" }
            system?.let { return@withLock it.embedImage(rgba) to id }
            val e = checkNotNull(embedder) { "Embedder not loaded" }
            val image = BitmapImageBuilder(rgba).build()
            withContext(inference) { embedBundled(e, image, background) } to id
        }
    }

    private fun embedBundled(e: UniversalEmbedder, image: MPImage, background: Boolean): FloatArray {
        if (!background) return e.embedImage(image).embeddings().first().floatEmbedding()
        val tid = android.os.Process.myTid()
        val before = android.os.Process.getThreadPriority(tid)
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        return try {
            e.embedImage(image).embeddings().first().floatEmbedding()
        } finally {
            android.os.Process.setThreadPriority(before)
        }
    }

    /**
     * Embeds a stretch of mono audio ([samples] in -1..1 at [sampleRate] Hz) into the same space
     * as text, so "dog barking" or words that were spoken can find it. Background priority, like
     * image indexing. Only works when [supportsAudio].
     */
    suspend fun embedAudioTagged(samples: FloatArray, sampleRate: Int): Pair<FloatArray, String> = mutex.withLock {
        val id = checkNotNull(modelId) { "Embedder not loaded" }
        check(supportsAudio) { "This model has no audio encoder" }
        val e = checkNotNull(embedder) { "Embedder not loaded" }
        withContext(inference) {
            val format = AudioData.AudioDataFormat.builder().setNumOfChannels(1).setSampleRate(sampleRate.toFloat()).build()
            val audio = AudioData.create(format, samples.size).apply { load(samples) }
            val tid = android.os.Process.myTid()
            val before = android.os.Process.getThreadPriority(tid)
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                e.embedAudio(audio).embeddings().first().floatEmbedding()
            } finally {
                android.os.Process.setThreadPriority(before)
            }
        } to id
    }

    suspend fun close() = mutex.withLock { closeLocked() }

    private fun closeLocked() {
        embedder?.close()
        embedder = null
        // The system model is shared through ModelStore and may be loaded again; leave it open.
        system = null
        backend = null
        supportsAudio = false
        _model.value = null
        _ready.value = false
    }

    private companion object {
        const val TAG = "Embedder"
        const val VISION_TOKENS = 70
    }
}
