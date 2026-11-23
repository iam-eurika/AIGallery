package com.lucrativeworm.seekframe.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenterOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

/** A per-pixel "is this the object" confidence, 0..1, row-major. */
class Mask(val width: Int, val height: Int, val alpha: FloatArray)

/** One gesture on the photo, in normalized 0..1 image coordinates. */
class Gesture(val points: List<Pair<Float, Float>>, val kind: Kind) {
    enum class Kind { Tap, Scribble, Lasso }
}

/**
 * MediaPipe Interactive Segmenter (MagicTouch v2), the model behind Snapseed's object brush.
 * Given a tap, a scribble or a lasso it returns the mask of the single object you meant. It runs
 * fully on device and ships inside the APK (~30 MB), so it works before EmbeddingGemma is ready.
 */
class Segmenter(private val context: Context) {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val inference = Dispatchers.Default.limitedParallelism(1)
    private val mutex = Mutex()
    private var segmenter: InteractiveSegmenter? = null
    private var current: Bitmap? = null

    suspend fun segment(image: Bitmap, gesture: Gesture): Mask = mutex.withLock {
        withContext(inference) {
            val s = segmenter ?: create().also { segmenter = it }
            if (current !== image) {
                // Image features are computed once; every further gesture on it is cheap.
                s.setImage(BitmapImageBuilder(image).build())
                current = image
            }
            val keypoints = gesture.points.map { (x, y) -> NormalizedKeypoint.create(x, y) }
            val mode = when (gesture.kind) {
                Gesture.Kind.Lasso -> Stroke.BrushMode.LASSO
                else -> Stroke.BrushMode.POSITIVE
            }
            val stroke = Stroke.builder().setBrushMode(mode).setPoints(keypoints).setCompleted(true).build()
            val started = System.nanoTime()
            val out = s.segment(listOf(stroke))
            val buffer = ByteBufferExtractor.extract(out).order(ByteOrder.nativeOrder())
            buffer.rewind()
            val w = out.width
            val h = out.height
            val alpha = FloatArray(w * h)
            if (buffer.capacity() >= w * h * 4) {
                buffer.asFloatBuffer().get(alpha)
            } else {
                for (i in alpha.indices) alpha[i] = (buffer.get(i).toInt() and 0xFF) / 255f
            }
            Log.i(TAG, "Segmented ${gesture.kind} in ${(System.nanoTime() - started) / 1_000_000} ms (${w}x$h)")
            Mask(w, h, alpha)
        }
    }

    private fun create(): InteractiveSegmenter {
        val base = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(Delegate.CPU)
            .build()
        return InteractiveSegmenter.createFromOptions(
            context, InteractiveSegmenterOptions.builder().setBaseOptions(base).build(),
        )
    }

    /** Frees the image features; the next segment() call on a new photo sets it again. */
    suspend fun forget() = mutex.withLock { current = null }

    private companion object {
        const val TAG = "Segmenter"
        const val MODEL_ASSET = "interactive_segmentation.task"
    }
}
