package com.lucrativeworm.seekframe.ml

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.lucrativeworm.seekframe.data.MomentKind
import com.lucrativeworm.seekframe.data.Photo
import com.lucrativeworm.seekframe.data.VideoMoment
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns one video into [VideoMoment]s: what it shows ([indexFrames]) and, when the model has an
 * audio encoder, what it sounds like ([indexSound]). Embedding is the expensive part, so both
 * passes work hard to embed as little as possible:
 *  - frames are sampled cheaply (keyframes, ~1/s) and only embedded when the picture changes,
 *    with a floor of one every [MAX_GAP_MS] so slow pans are still covered;
 *  - silent stretches of the soundtrack are skipped without touching the model;
 *  - neighbouring moments that embed almost identically are merged into one longer moment;
 *  - long videos get coarser coverage instead of an unbounded number of embeddings.
 *
 * Work is handed to [Commit] every few moments, with how far the pass got, so a pass that's
 * killed resumes mid-video instead of starting a long recording over.
 */
class VideoIndexer(private val context: Context) {

    /** Persists [moments] (new or extended) and records that the pass got to [doneMs]. */
    fun interface Commit {
        fun save(moments: List<VideoMoment>, doneMs: Long)
    }

    /**
     * @param resumeFrom the last frame moment already stored for this video, if resuming.
     * @return false if the video couldn't be decoded at all.
     */
    suspend fun indexFrames(
        video: Photo,
        resumeFrom: VideoMoment?,
        pace: suspend () -> Unit,
        embed: suspend (Bitmap) -> Pair<FloatArray, String>,
        commit: Commit,
    ): Boolean {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(video.uri))
            val duration = video.durationMs.takeIf { it > 0 }
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: 0L
            if (duration <= 0L) {
                // A clip with no length: index its one frame so it's still findable.
                val frame = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB, THUMB) ?: return false
                pace()
                val (vector, model) = embed(frame)
                commit.save(listOf(VideoMoment(photoId = video.id, kind = MomentKind.FRAME, startMs = 0, endMs = 0, vector = vector, model = model)), 0)
                return true
            }
            val step = max(SAMPLE_MS, duration / MAX_CANDIDATES)
            val minGap = max(MIN_GAP_MS, duration / MAX_FRAMES)
            val maxGap = max(MAX_GAP_MS, minGap)

            var current = resumeFrom
            var lastPrint: FloatArray? = null
            var lastEmbedAt = Long.MIN_VALUE / 2
            val dirty = ArrayList<VideoMoment>()
            var decodedAny = false
            var t = video.framesDoneMs.coerceIn(0, duration)
            while (t < duration) {
                currentCoroutineContext().ensureActive()
                val frame = retriever.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB, THUMB)
                if (frame != null) {
                    decodedAny = true
                    val print = fingerprint(frame)
                    val changed = lastPrint == null || difference(print, lastPrint) > SCENE_CHANGE
                    val since = t - lastEmbedAt
                    if ((changed && since >= minGap) || since >= maxGap) {
                        pace()
                        val (vector, model) = embed(frame)
                        val prev = current
                        if (prev != null && prev.model == model && dot(prev.vector, vector) > FRAME_MERGE) {
                            prev.endMs = t + step
                        } else {
                            prev?.endMs = t
                            current = VideoMoment(photoId = video.id, kind = MomentKind.FRAME, startMs = t, endMs = t + step, vector = vector, model = model)
                        }
                        current?.let { if (it !in dirty) dirty += it }
                        if (prev != null && prev !in dirty) dirty += prev
                        lastPrint = print
                        lastEmbedAt = t
                    } else {
                        current?.let { it.endMs = t + step; if (it !in dirty) dirty += it }
                    }
                    frame.recycle()
                }
                t += step
                if (dirty.size >= COMMIT_EVERY) {
                    commit.save(dirty.toList(), t)
                    dirty.clear()
                }
            }
            current?.let { it.endMs = duration; if (it !in dirty) dirty += it }
            commit.save(dirty, duration)
            return decodedAny || resumeFrom != null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Embeds the soundtrack in [WINDOW_MS] windows. Returns false when the video has no audio
     * track (nothing to index, not a failure).
     */
    suspend fun indexSound(
        video: Photo,
        resumeFrom: VideoMoment?,
        pace: suspend () -> Unit,
        embed: suspend (FloatArray, Int) -> Pair<FloatArray, String>,
        commit: Commit,
    ): Boolean {
        val startMs = video.soundDoneMs.coerceAtLeast(0)
        val window = FloatArray(RATE * WINDOW_MS.toInt() / 1000)
        var filled = 0
        var windowStartMs = -1L
        var windowIndex = 0
        var every = 1
        var durationMs = 0L
        var current = resumeFrom
        val dirty = ArrayList<VideoMoment>()
        var lastDoneMs = startMs

        suspend fun finishWindow() {
            val start = windowStartMs
            val end = start + WINDOW_MS
            val take = windowIndex % every == 0
            windowIndex++
            filled = 0
            windowStartMs = -1
            lastDoneMs = end
            if (!take || rms(window) < SILENCE) return
            pace()
            val (vector, model) = embed(window.copyOf(), RATE)
            val prev = current
            if (prev != null && prev.model == model && prev.endMs >= start - 1 && dot(prev.vector, vector) > SOUND_MERGE) {
                prev.endMs = end
                if (prev !in dirty) dirty += prev
            } else {
                current = VideoMoment(photoId = video.id, kind = MomentKind.SOUND, startMs = start, endMs = end, vector = vector, model = model)
                    .also { dirty += it }
            }
            if (dirty.size >= COMMIT_EVERY) {
                commit.save(dirty.toList(), end)
                dirty.clear()
            }
        }

        val hasAudio = streamAudio(
            video, startMs,
            onStart = { duration ->
                durationMs = duration
                // Long recordings: embed every k-th window rather than all of them.
                val windows = ceil(duration / WINDOW_MS.toDouble()).toInt().coerceAtLeast(1)
                every = ceil(windows / MAX_SOUNDS.toDouble()).toInt().coerceAtLeast(1)
            },
        ) { out, count, chunkStartMs ->
            var k = 0
            while (k < count) {
                if (windowStartMs < 0) windowStartMs = max(startMs, chunkStartMs + k * 1000L / RATE)
                val n = minOf(count - k, window.size - filled)
                System.arraycopy(out, k, window, filled, n)
                filled += n
                k += n
                if (filled == window.size) finishWindow()
            }
        }
        if (!hasAudio) return false
        if (filled > window.size / 2 && windowStartMs >= 0) {
            window.fill(0f, filled)
            finishWindow()
        }
        commit.save(dirty, max(lastDoneMs, durationMs))
        return true
    }

    /**
     * Decodes [video]'s soundtrack from [startMs] on and hands it to [sink] as [RATE] Hz mono
     * floats in -1..1: `sink(samples, count, chunkStartMs)`, where [chunkStartMs] is when the
     * decoder chunk the samples came from starts (it may begin a little before [startMs]: decoding
     * resumes from the previous sync frame). [onStart] gets the soundtrack's length first.
     * Returns false when the video has no audio track.
     */
    suspend fun streamAudio(
        video: Photo,
        startMs: Long,
        onStart: (durationMs: Long) -> Unit = {},
        sink: suspend (FloatArray, Int, Long) -> Unit,
    ): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(video.uri), null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationMs = video.durationMs.takeIf { it > 0 }
                ?: (if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L)
            onStart(durationMs)
            if (startMs > 0) extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val c = MediaCodec.createDecoderByType(mime).also { codec = it }
            c.configure(format, null, null, 0)
            c.start()

            var inRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            var resampler = Resampler(inRate, RATE)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var mono = FloatArray(4096)
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = c.getInputBuffer(i)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        resampler = Resampler(inRate, RATE)
                    }
                    o >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        val buf = c.getOutputBuffer(o)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.order(ByteOrder.nativeOrder())
                            val frames = info.size / (if (floatPcm) 4 else 2) / channels
                            if (mono.size < frames) mono = FloatArray(frames)
                            // Downmix to mono.
                            if (floatPcm) {
                                val fb = buf.asFloatBuffer()
                                for (k in 0 until frames) {
                                    var sum = 0f
                                    for (ch in 0 until channels) sum += fb.get()
                                    mono[k] = sum / channels
                                }
                            } else {
                                val sb = buf.asShortBuffer()
                                for (k in 0 until frames) {
                                    var sum = 0f
                                    for (ch in 0 until channels) sum += sb.get() / 32768f
                                    mono[k] = sum / channels
                                }
                            }
                            val chunkStartMs = info.presentationTimeUs / 1000
                            // Samples from before the resume point (we seek to the previous sync frame).
                            if (chunkStartMs + frames * 1000L / inRate > startMs) {
                                val count = resampler.process(mono, frames)
                                if (count > 0) sink(resampler.out, count, chunkStartMs)
                            }
                        }
                        c.releaseOutputBuffer(o, false)
                    }
                }
            }
            return true
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** A 12×12 colour thumbnail: cheap to compare, and good enough to tell a new scene. */
    private fun fingerprint(frame: Bitmap): FloatArray {
        val small = Bitmap.createScaledBitmap(frame, PRINT, PRINT, true)
        val px = IntArray(PRINT * PRINT)
        small.getPixels(px, 0, PRINT, 0, 0, PRINT, PRINT)
        if (small !== frame) small.recycle()
        val out = FloatArray(px.size * 3)
        for (i in px.indices) {
            val p = px[i]
            out[i * 3] = ((p shr 16) and 0xff) / 255f
            out[i * 3 + 1] = ((p shr 8) and 0xff) / 255f
            out[i * 3 + 2] = (p and 0xff) / 255f
        }
        return out
    }

    private fun difference(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += abs(a[i] - b[i])
        return s / a.size
    }

    private fun rms(x: FloatArray): Float {
        var s = 0.0
        for (v in x) s += v * v
        return sqrt(s / x.size).toFloat()
    }

    /**
     * Streaming linear resampler with a crude box low-pass in front when downsampling, so 48 kHz
     * phone audio doesn't alias badly on its way to the 16 kHz the audio encoder expects.
     */
    private class Resampler(inRate: Int, private val outRate: Int) {
        private val ratio = inRate.toDouble() / outRate.coerceAtLeast(1)
        private val box = if (ratio > 1.5) ratio.roundToInt() else 1
        private val history = FloatArray(box)
        private var h = 0
        private var hSum = 0f
        private var t = 0.0
        private var prev = 0f
        private var filtered = FloatArray(0)
        /** Output of the last [process] call. */
        var out = FloatArray(0)
            private set

        /** Resamples [n] samples of [x] into [out]; returns how many were written. */
        fun process(x: FloatArray, n: Int): Int {
            if (n == 0) return 0
            if (filtered.size < n) filtered = FloatArray(n)
            val most = ceil(n / ratio).toInt() + 2
            if (out.size < most) out = FloatArray(most)
            var count = 0
            for (i in 0 until n) {
                hSum += x[i] - history[h]
                history[h] = x[i]
                h = (h + 1) % box
                filtered[i] = hSum / box
            }
            // Positions run over [-1, n-1]: index -1 is the last sample of the previous chunk.
            while (t < n - 1) {
                val i0 = floor(t).toInt()
                val frac = (t - i0).toFloat()
                val a = if (i0 < 0) prev else filtered[i0]
                val b = filtered[i0 + 1]
                out[count++] = a + (b - a) * frac
                t += ratio
            }
            t -= n
            prev = filtered[n - 1]
            return count
        }
    }

    companion object {
        private const val THUMB = 448
        private const val PRINT = 12
        /** How often a candidate frame is looked at (cheap: decode only). */
        private const val SAMPLE_MS = 1_000L
        private const val MAX_CANDIDATES = 900L
        /** Never embed two frames closer than this, however much the picture changes. */
        private const val MIN_GAP_MS = 1_500L
        /** Always embed at least this often, even if nothing seems to change. */
        private const val MAX_GAP_MS = 10_000L
        /** Upper bound on frames embedded per video; long videos space them out instead. */
        private const val MAX_FRAMES = 150L
        /** Mean per-channel difference of the 12×12 prints that counts as a new scene. */
        private const val SCENE_CHANGE = 0.08f
        /** Consecutive frame embeddings this similar are one moment. */
        private const val FRAME_MERGE = 0.94f

        const val RATE = 16_000
        const val WINDOW_MS = 5_000L
        private const val MAX_SOUNDS = 180
        /** RMS below this (about -40 dBFS) is treated as silence and not embedded. */
        private const val SILENCE = 0.01f
        private const val SOUND_MERGE = 0.92f

        private const val COMMIT_EVERY = 8
        private const val TIMEOUT_US = 10_000L

        fun dot(a: FloatArray?, b: FloatArray): Float {
            if (a == null || a.size != b.size) return -1f
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }
    }
}
