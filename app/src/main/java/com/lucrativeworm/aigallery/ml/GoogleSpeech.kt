package com.lucrativeworm.aigallery.ml

import android.media.AudioFormat
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import com.lucrativeworm.aigallery.data.AudioText
import com.lucrativeworm.aigallery.data.Photo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The preferred speech-to-text engine where the phone offers it: Android's own recogniser through
 * ML Kit GenAI Speech Recognition. Android downloads and updates the model (Gemini Nano on phones
 * that have it, the platform's on-device model otherwise), it knows more languages than the
 * fallback, and it's generally more accurate. Needs Android 12+.
 *
 * Videos are transcribed in ONE_SHOT mode, which runs faster than real time but gives no word
 * timings. So the soundtrack is cut into chunks of [MIN_CHUNK_S]..[MAX_CHUNK_S] seconds at quiet
 * points, each recognised on its own, and what's said in a chunk is stamped with the chunk's time.
 */
class GoogleSpeech {

    private val lock = Mutex()
    private var client: SpeechRecognizer? = null

    /**
     * Whether Android offers the model, as an ML Kit [FeatureStatus]. Tries the phone's language
     * first, then US English (the one locale both modes support).
     */
    suspend fun status(): Int = lock.withLock {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@withLock FeatureStatus.UNAVAILABLE
        client?.let { c ->
            runCatching { c.checkStatus() }.getOrNull()?.let { if (it != FeatureStatus.UNAVAILABLE) return@withLock it }
            c.close()
            client = null
        }
        for (locale in listOf(Locale.getDefault(), Locale.US).distinct()) {
            val c = runCatching {
                SpeechRecognition.getClient(speechRecognizerOptions {
                    this.locale = locale
                    // The GenAI model where the phone has it; ML Kit falls back to basic otherwise.
                    preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED
                })
            }.onFailure { Log.w(TAG, "No speech client for $locale", it) }.getOrNull() ?: continue
            val status = runCatching { c.checkStatus() }
                .onFailure { Log.w(TAG, "Speech status check failed for $locale", it) }
                .getOrDefault(FeatureStatus.UNAVAILABLE)
            if (status != FeatureStatus.UNAVAILABLE) {
                client = c
                return@withLock status
            }
            c.close()
        }
        FeatureStatus.UNAVAILABLE
    }

    /** Asks Android to download its model. Call [status] first. */
    fun download(): Flow<DownloadStatus> = client?.download() ?: emptyFlow()

    /**
     * Transcribes [video]'s soundtrack from [startMs] on, one chunk at a time, handing each
     * chunk's words to [onUtterance]. [step] is called after every chunk so the caller can pace.
     *
     * @return false when the video has no audio track or Android's model isn't ready.
     */
    suspend fun transcribe(
        video: Photo,
        startMs: Long,
        audio: VideoIndexer,
        step: suspend () -> Unit,
        onUtterance: (AudioText) -> Unit,
    ): Boolean {
        val c = client ?: run { status(); client } ?: return false
        val chunk = ShortArray(MAX_CHUNK)
        var filled = 0
        var chunkStartMs = -1L

        suspend fun flush() {
            val n = filled
            val start = chunkStartMs
            filled = 0
            chunkStartMs = -1
            if (n < RATE / 2 || rms(chunk, 0, n) < SILENCE) return
            val text = recognize(c, chunk, n)
            if (text.isNotBlank()) {
                onUtterance(AudioText(photoId = video.id, startMs = start, endMs = start + n * 1000L / RATE, text = text))
            }
            step()
        }

        val hasAudio = audio.streamAudio(video, startMs) { samples, count, chunkMs ->
            // Decoding resumes from the sync frame before [startMs]; skip what's already done.
            var k = if (chunkMs < startMs) ((startMs - chunkMs) * RATE / 1000).toInt() else 0
            while (k < count) {
                if (chunkStartMs < 0) chunkStartMs = max(startMs, chunkMs + k * 1000L / RATE)
                // Fill up to the next frame boundary, then decide whether to cut there.
                val n = minOf(count - k, FRAME - filled % FRAME)
                for (i in 0 until n) chunk[filled + i] = (samples[k + i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
                filled += n
                k += n
                if (filled % FRAME == 0 &&
                    (filled >= MAX_CHUNK || (filled >= MIN_CHUNK && rms(chunk, filled - FRAME, filled) < QUIET))
                ) flush()
            }
        }
        if (hasAudio) flush()
        return hasAudio
    }

    /** Recognises one chunk of 16 kHz mono PCM, returning everything said in it. */
    private suspend fun recognize(c: SpeechRecognizer, pcm: ShortArray, n: Int): String = coroutineScope {
        val (read, write) = ParcelFileDescriptor.createPipe()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val source = AudioSource.fromPfd(read, AudioSource.Mode.ONE_SHOT, format)
        // ONE_SHOT reads as fast as we write; closing the write end marks the end of the audio.
        val writer = launch(Dispatchers.IO) {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                    val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                    bytes.asShortBuffer().put(pcm, 0, n)
                    out.write(bytes.array())
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // The recogniser stopped reading (an error, reported below): nothing to do here.
                Log.d(TAG, "Speech pipe closed early", t)
            }
        }
        val parts = ArrayList<String>()
        try {
            c.startRecognition(speechRecognizerRequest { audioSource = source })
                .takeWhile { it !is SpeechRecognizerResponse.CompletedResponse }
                .collect { r ->
                    when (r) {
                        is SpeechRecognizerResponse.FinalTextResponse -> r.text.trim().takeIf { it.isNotEmpty() }?.let(parts::add)
                        is SpeechRecognizerResponse.ErrorResponse -> throw r.e
                        else -> {}
                    }
                }
        } finally {
            runCatching { source.close() }
            writer.cancel()
        }
        parts.joinToString(" ")
    }

    private fun rms(x: ShortArray, from: Int, to: Int): Float {
        var s = 0.0
        for (i in from until to) { val v = x[i] / 32768.0; s += v * v }
        return sqrt(s / (to - from).coerceAtLeast(1)).toFloat()
    }

    private companion object {
        const val TAG = "GoogleSpeech"
        const val RATE = VideoIndexer.RATE
        /** Quiet is judged over 100 ms frames. */
        const val FRAME = RATE / 10
        const val MIN_CHUNK_S = 15
        const val MAX_CHUNK_S = 30
        const val MIN_CHUNK = RATE * MIN_CHUNK_S
        const val MAX_CHUNK = RATE * MAX_CHUNK_S
        /** A 100 ms frame this quiet (about -46 dBFS) is a pause between words: a safe place to cut. */
        const val QUIET = 0.005f
        /** A whole chunk this quiet has nobody speaking in it; don't bother the recogniser. */
        const val SILENCE = 0.003f
    }
}
