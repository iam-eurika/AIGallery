package com.lucrativeworm.aigallery.ml

import com.lucrativeworm.aigallery.data.AudioText
import com.lucrativeworm.aigallery.data.Photo

/** Which speech-to-text the user wants for spoken words in videos. Stored, and picked in Settings. */
enum class SpeechChoice {
    /** Android's model where the phone offers it, otherwise the downloaded one. */
    AUTO,
    /** Only Android's model (ML Kit GenAI). Nothing for the app to store, but needs a supported phone. */
    ANDROID,
    /** Only the app's own downloaded model (Vosk, English). Works on any phone. */
    DOWNLOADED,
    /** Don't write down spoken words. */
    OFF,
}

/** The engine actually doing the work. */
enum class SpeechEngine { ANDROID, DOWNLOADED }

/**
 * Everything the app knows about speech-to-text right now: the user's [choice] (null until asked),
 * whether Android offers its model, whether the app's own model is downloaded, and any download
 * in progress.
 */
data class SpeechStatus(
    val choice: SpeechChoice? = null,
    val android: SystemModelStatus = SystemModelStatus.CHECKING,
    val downloaded: Boolean = false,
    val download: ModelStore.State? = null,
) {
    /** Android offers its model on this phone (ready, or one download away). */
    val androidOffered: Boolean
        get() = android == SystemModelStatus.AVAILABLE || android == SystemModelStatus.DOWNLOADABLE || android == SystemModelStatus.DOWNLOADING

    /** The engine that transcribes now, or null when the chosen one isn't ready (or speech is off). */
    val engine: SpeechEngine?
        get() = when (choice) {
            SpeechChoice.AUTO -> when {
                android == SystemModelStatus.AVAILABLE -> SpeechEngine.ANDROID
                downloaded -> SpeechEngine.DOWNLOADED
                else -> null
            }
            SpeechChoice.ANDROID -> SpeechEngine.ANDROID.takeIf { android == SystemModelStatus.AVAILABLE }
            SpeechChoice.DOWNLOADED -> SpeechEngine.DOWNLOADED.takeIf { downloaded }
            SpeechChoice.OFF, null -> null
        }

    /** What Download would fetch for the current choice, or null when there's nothing to fetch. */
    val toFetch: SpeechEngine?
        get() = if (engine != null) null else when (choice) {
            SpeechChoice.AUTO -> if (androidOffered) SpeechEngine.ANDROID else SpeechEngine.DOWNLOADED
            SpeechChoice.ANDROID -> SpeechEngine.ANDROID.takeIf { androidOffered }
            SpeechChoice.DOWNLOADED -> SpeechEngine.DOWNLOADED
            SpeechChoice.OFF, null -> null
        }
}

/**
 * Writes down what is said in videos, with whichever engine the user's [SpeechChoice] resolves to
 * ([ModelStore.speech]): Android's own model first where the phone has it, the app's downloaded
 * one otherwise. Nothing runs until the user has chosen and the model is here.
 *
 * Unlike the sound embeddings, which capture what something sounds like ("a dog barking"), this
 * produces the actual words, so a search for something someone said can match it exactly.
 */
class Speech(private val models: ModelStore) {

    private val vosk = VoskSpeech(models::speechModelDir)

    val isAvailable: Boolean get() = models.speech.value.engine != null

    suspend fun transcribe(
        video: Photo,
        startMs: Long,
        audio: VideoIndexer,
        step: suspend () -> Unit,
        onUtterance: (AudioText) -> Unit,
    ): Boolean = when (models.speech.value.engine) {
        SpeechEngine.ANDROID -> models.googleSpeech.transcribe(video, startMs, audio, step, onUtterance)
        SpeechEngine.DOWNLOADED -> vosk.transcribe(video, startMs, audio, step, onUtterance)
        null -> false
    }

    /** Frees the downloaded model's memory between indexing passes; it reloads on the next one. */
    fun close() = vosk.close()

    companion object {
        /** Lowercase words without punctuation, for matching typed searches against what was said. */
        fun words(query: String): List<String> =
            query.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).map { it.trim('\'') }.filter { it.isNotEmpty() }
    }
}
