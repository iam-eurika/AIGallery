package com.lucrativeworm.seekframe.ml

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.embedding.Content
import com.google.mlkit.genai.embedding.EmbeddingGeneration
import com.google.mlkit.genai.embedding.EmbeddingModel
import com.google.mlkit.genai.embedding.EmbeddingRequest
import com.google.mlkit.genai.embedding.ImagePart
import com.google.mlkit.genai.embedding.Modality
import com.google.mlkit.genai.embedding.TextPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.math.sqrt

/**
 * EmbeddingGemma 2 as Android ships it: ML Kit GenAI's Embeddings API on top of AICore. The
 * model lives in the system (shared, updated by Android, NPU-accelerated where available), so
 * the app downloads nothing itself. Only phones with AICore have it.
 */
class SystemModel private constructor(private val model: EmbeddingModel, signature: String) {

    /** Stored next to every vector; different signatures are different, incompatible spaces. */
    val id: String = "$ID_PREFIX$signature"

    suspend fun embedText(text: String): FloatArray =
        normalize(model.generateEmbedding(EmbeddingRequest(Content(listOf(TextPart(text))))).embedding.vector)

    suspend fun embedImage(bitmap: Bitmap): FloatArray {
        // ImagePart is AutoCloseable and may release its bitmap; hand it a copy the caller can't lose.
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        return try {
            ImagePart(copy).use { part ->
                normalize(model.generateEmbedding(EmbeddingRequest(Content(listOf(part)))).embedding.vector)
            }
        } finally {
            if (!copy.isRecycled) copy.recycle()
        }
    }

    suspend fun warmup() = runCatching { model.warmup() }.onFailure { Log.w(TAG, "Warmup failed", it) }

    fun close() = model.close()

    /** Finds out what AICore offers on this phone, and fetches the model through it. */
    class Probe(@Suppress("unused") private val context: Context) {
        private val _status = MutableStateFlow(SystemModelStatus.CHECKING)
        val status: StateFlow<SystemModelStatus> = _status.asStateFlow()

        private val mutex = Mutex()
        private var handle: EmbeddingModel? = null
        private var ready: SystemModel? = null
        private var unreachableAt = 0L

        /**
         * Asks AICore, remembering a definite answer (supported or not) for the rest of the
         * process. Failing to reach AICore at all is not an answer: it is retried a few times now
         * and asked again on the next call, so a hiccup never reads as "this phone has no model".
         */
        suspend fun check(): SystemModelStatus = mutex.withLock {
            val known = _status.value
            if (known == SystemModelStatus.UNSUPPORTED || known == SystemModelStatus.AVAILABLE) return known
            // Several callers ask at startup; one round of retries per cool-down is enough.
            if (known == SystemModelStatus.UNREACHABLE && SystemClock.elapsedRealtime() - unreachableAt < RETRY_COOLDOWN_MS) return known
            val status = withTimeoutOrNull(PROBE_TIMEOUT_MS) { probeWithRetries() } ?: SystemModelStatus.UNREACHABLE
            if (status == SystemModelStatus.UNREACHABLE) unreachableAt = SystemClock.elapsedRealtime()
            _status.value = status
            status
        }

        /** An explicit "try again": the next [check] asks AICore even inside the cool-down. */
        fun retrySoon() { unreachableAt = 0L }

        private suspend fun probeWithRetries(): SystemModelStatus {
            repeat(PROBE_ATTEMPTS) { attempt ->
                try {
                    return probe()
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    Log.i(TAG, "AICore didn't answer (attempt ${attempt + 1} of $PROBE_ATTEMPTS): ${t.message}")
                    if (attempt + 1 < PROBE_ATTEMPTS) delay(PROBE_RETRY_MS * (attempt + 1))
                }
            }
            return SystemModelStatus.UNREACHABLE
        }

        private suspend fun probe(): SystemModelStatus {
            val models = EmbeddingGeneration.getEmbeddingModels(SIGNATURE)
            // A photo gallery needs images in the same space as text: v1 (text only) won't do.
            // No metadata: trust the v2 signature we asked for, which is text + image.
            val model = models.firstOrNull { m -> m.getMetadata()?.supportedModalities?.contains(Modality.IMAGE) ?: true }
                ?: return SystemModelStatus.UNSUPPORTED
            handle = model
            return when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> SystemModelStatus.AVAILABLE
                FeatureStatus.DOWNLOADABLE -> SystemModelStatus.DOWNLOADABLE
                FeatureStatus.DOWNLOADING -> SystemModelStatus.DOWNLOADING
                else -> SystemModelStatus.UNSUPPORTED
            }
        }

        /** The ready model, created once and shared. */
        suspend fun model(): SystemModel? = mutex.withLock {
            ready ?: handle?.let { h -> SystemModel(h, h.getModelSignature()).also { ready = it } }
        }

        suspend fun download(onProgress: (bytes: Long, total: Long) -> Unit): SystemModel {
            if (check() == SystemModelStatus.AVAILABLE) return model() ?: throw IOException("Model went away")
            val h = handle ?: throw IOException(
                if (_status.value == SystemModelStatus.UNREACHABLE) UNREACHABLE_MESSAGE else "This phone's Android doesn't offer the model"
            )
            _status.value = SystemModelStatus.DOWNLOADING
            var total = 0L
            try {
                h.download().collect { s ->
                    when (s) {
                        is DownloadStatus.DownloadStarted -> { total = s.bytesToDownload; onProgress(0, total) }
                        is DownloadStatus.DownloadProgress -> onProgress(s.totalBytesDownloaded, total)
                        is DownloadStatus.DownloadFailed -> throw s.e
                        else -> Unit
                    }
                }
            } catch (t: Throwable) {
                _status.value = SystemModelStatus.DOWNLOADABLE
                throw t
            }
            _status.value = SystemModelStatus.AVAILABLE
            return model() ?: throw IOException("Model went away")
        }
    }

    companion object {
        private const val TAG = "SystemModel"
        const val SIGNATURE = "embedding_gemma_v2"
        const val ID_PREFIX = "mlkit:"
        private const val PROBE_TIMEOUT_MS = 10_000L
        private const val PROBE_ATTEMPTS = 3
        private const val PROBE_RETRY_MS = 1_500L
        private const val RETRY_COOLDOWN_MS = 60_000L
        const val UNREACHABLE_MESSAGE = "Android's model service didn't respond. Try again in a moment."

        /** ObjectBox searches by cosine anyway; unit length keeps both engines' vectors alike. */
        private fun normalize(v: FloatArray): FloatArray {
            var sum = 0.0
            for (x in v) sum += x * x
            val n = sqrt(sum).toFloat()
            if (n == 0f) return v
            for (i in v.indices) v[i] /= n
            return v
        }
    }
}
