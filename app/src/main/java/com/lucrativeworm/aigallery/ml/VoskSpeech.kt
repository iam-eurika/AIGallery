package com.lucrativeworm.aigallery.ml

import android.util.Log
import com.lucrativeworm.aigallery.data.AudioText
import com.lucrativeworm.aigallery.data.Photo
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The fallback speech-to-text engine: Vosk's offline recogniser with its small English model, a
 * separate ~41 MB download the user agrees to. Works on any phone, and gives word timings.
 */
class VoskSpeech(private val modelDir: () -> File?) {

    private var model: Model? = null
    private var loadedFrom: File? = null

    val isAvailable: Boolean get() = modelDir() != null

    @Synchronized
    private fun open(): Model? {
        val dir = modelDir() ?: return null
        if (model == null || loadedFrom != dir) {
            model?.close()
            runCatching { LibVosk.setLogLevel(LogLevel.WARNINGS) }
            model = Model(dir.path)
            loadedFrom = dir
        }
        return model
    }

    /** Frees the recogniser's memory between indexing passes; it reloads on the next one. */
    @Synchronized
    fun close() {
        model?.close()
        model = null
        loadedFrom = null
    }

    /**
     * Transcribes [video]'s soundtrack from [startMs] on, handing each utterance (speech between
     * pauses) to [onUtterance] as soon as the recogniser settles on it. [step] is called every few
     * seconds of audio so the caller can pace the work.
     *
     * @return false when the video has no audio track or the model isn't here.
     */
    suspend fun transcribe(
        video: Photo,
        startMs: Long,
        audio: VideoIndexer,
        step: suspend () -> Unit,
        onUtterance: (AudioText) -> Unit,
    ): Boolean {
        val m = open() ?: return false
        return Recognizer(m, RATE.toFloat()).use { rec ->
            rec.setWords(true)
            // Vosk times words from the first sample it's fed; this is when that sample is.
            var baseMs = -1L
            var shorts = ShortArray(8192)
            var sinceStep = 0

            fun emit(json: String) {
                val utterance = parse(json, video.id, baseMs) ?: return
                onUtterance(utterance)
            }

            val hasAudio = audio.streamAudio(video, startMs) { samples, count, chunkStartMs ->
                // Decoding resumes from the sync frame before [startMs]; skip what's already done.
                val skip = if (chunkStartMs < startMs) ((startMs - chunkStartMs) * RATE / 1000).toInt() else 0
                if (skip >= count) return@streamAudio
                if (baseMs < 0) baseMs = max(startMs, chunkStartMs)
                val n = count - skip
                if (shorts.size < n) shorts = ShortArray(n)
                for (k in 0 until n) {
                    shorts[k] = (samples[skip + k].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
                }
                if (rec.acceptWaveForm(shorts, n)) emit(rec.result)
                sinceStep += n
                if (sinceStep >= STEP_SAMPLES) {
                    sinceStep = 0
                    step()
                }
            }
            if (hasAudio) emit(rec.finalResult)
            hasAudio
        }
    }

    /** Vosk's result JSON -> one [AudioText], or null when nothing was said. */
    private fun parse(json: String, photoId: Long, baseMs: Long): AudioText? = try {
        val o = JSONObject(json)
        val text = o.optString("text").trim()
        if (text.isEmpty()) null
        else {
            val words = o.optJSONArray("result")
            val first = words?.optJSONObject(0)
            val last = words?.optJSONObject(words.length() - 1)
            val base = baseMs.coerceAtLeast(0)
            AudioText(
                photoId = photoId,
                startMs = base + ((first?.optDouble("start") ?: 0.0) * 1000).toLong(),
                endMs = base + ((last?.optDouble("end") ?: 0.0) * 1000).toLong(),
                text = text,
            )
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Unreadable recogniser result", t)
        null
    }

    private companion object {
        const val TAG = "VoskSpeech"
        const val RATE = VideoIndexer.RATE
        /** Let the indexer pace itself about every ten seconds of audio. */
        const val STEP_SAMPLES = RATE * 10
    }
}
