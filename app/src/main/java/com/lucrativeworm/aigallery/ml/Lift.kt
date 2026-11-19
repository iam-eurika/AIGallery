package com.lucrativeworm.aigallery.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An object lifted out of a photo.
 *
 * @param cutout the object on transparency, ready to show floating or copy as a sticker.
 * @param bounds where it sits in the source photo, normalized 0..1.
 * @param query a square, padded version on neutral grey for EmbeddingGemma to embed.
 * @param precise false when the model found nothing and we fell back to the circled region.
 */
class Lift(val cutout: Bitmap, val bounds: RectF, val query: Bitmap, val precise: Boolean)

object Lifter {

    /** Classifies a finger path: a dot, an open scribble, or a loop that encloses something. */
    fun gesture(points: List<Pair<Float, Float>>, aspect: Float): Gesture {
        val xs = points.map { it.first }
        val ys = points.map { it.second }
        val spanX = (xs.max() - xs.min())
        val spanY = (ys.max() - ys.min()) / aspect
        val span = max(spanX, spanY)
        if (points.size < 4 || span < 0.03f) return Gesture(listOf(points.last()), Gesture.Kind.Tap)
        val (sx, sy) = points.first()
        val (ex, ey) = points.last()
        val gap = kotlin.math.hypot(ex - sx, (ey - sy) / aspect)
        return if (gap < span * 0.35f) Gesture(points, Gesture.Kind.Lasso) else Gesture(points, Gesture.Kind.Scribble)
    }

    fun lift(source: Bitmap, mask: Mask, gesture: Gesture): Lift {
        val alpha = mask.alpha.copyOf()
        if (gesture.kind == Gesture.Kind.Lasso) clipToLasso(alpha, mask.width, mask.height, gesture.points)
        fillHoles(alpha, mask.width, mask.height)

        var minX = mask.width; var minY = mask.height; var maxX = -1; var maxY = -1; var count = 0
        for (y in 0 until mask.height) for (x in 0 until mask.width) {
            if (alpha[y * mask.width + x] > 0.5f) {
                count++
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        val precise = count > mask.width * mask.height * 0.0015f
        val bounds = if (precise) {
            RectF(minX / mask.width.toFloat(), minY / mask.height.toFloat(),
                (maxX + 1) / mask.width.toFloat(), (maxY + 1) / mask.height.toFloat())
        } else {
            // Nothing distinct under the circle (sky, texture): lift the circled region itself.
            val region = gestureBounds(gesture.points)
            val outline = if (gesture.points.size >= 3) gesture.points else listOf(
                region.left to region.top, region.right to region.top,
                region.right to region.bottom, region.left to region.bottom,
            )
            fillLasso(alpha, mask.width, mask.height, outline)
            region
        }
        val cutout = cut(source, alpha, mask.width, mask.height, bounds)
        return Lift(cutout, bounds, queryImage(cutout), precise)
    }

    /** Crops [source] to [bounds] and applies the mask as soft alpha. */
    private fun cut(source: Bitmap, alpha: FloatArray, mw: Int, mh: Int, bounds: RectF): Bitmap {
        val src = Rect(
            (bounds.left * source.width).toInt(), (bounds.top * source.height).toInt(),
            (bounds.right * source.width).roundToInt().coerceAtMost(source.width),
            (bounds.bottom * source.height).roundToInt().coerceAtMost(source.height),
        )
        val w = max(1, src.width()); val h = max(1, src.height())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(source, src, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))

        val maskBitmap = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(mw * mh) { i ->
            // Steepen the confidence ramp so edges stay soft but the body is fully opaque.
            val a = ((alpha[i] - 0.25f) / 0.5f).coerceIn(0f, 1f)
            Color.argb((a * 255).toInt(), 0, 0, 0)
        }
        maskBitmap.setPixels(pixels, 0, mw, 0, 0, mw, mh)
        val maskSrc = Rect(
            (bounds.left * mw).toInt(), (bounds.top * mh).toInt(),
            (bounds.right * mw).roundToInt().coerceAtMost(mw), (bounds.bottom * mh).roundToInt().coerceAtMost(mh),
        )
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        canvas.drawBitmap(maskBitmap, maskSrc, Rect(0, 0, w, h), paint)
        maskBitmap.recycle()
        return out
    }

    /** Centers the cutout on grey with breathing room, the way a product shot frames an object. */
    private fun queryImage(cutout: Bitmap): Bitmap {
        val side = (max(cutout.width, cutout.height) * 1.2f).toInt().coerceAtLeast(8)
        val scale = QUERY_PX / side.toFloat()
        val out = Bitmap.createBitmap(QUERY_PX, QUERY_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(128, 128, 128))
        val w = cutout.width * scale; val h = cutout.height * scale
        val left = (QUERY_PX - w) / 2f; val top = (QUERY_PX - h) / 2f
        canvas.drawBitmap(cutout, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /**
     * Makes the object solid: any low-confidence pocket fully enclosed by the object (a shadowed
     * rock face, a gap between fingers) is filled, so a sticker never has see-through blotches.
     * Pixels reachable from the image border without crossing the object stay transparent.
     */
    private fun fillHoles(alpha: FloatArray, w: Int, h: Int) {
        val outside = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0; var tail = 0
        fun seed(i: Int) { if (!outside[i] && alpha[i] < 0.5f) { outside[i] = true; queue[tail++] = i } }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w; val y = i / w
            if (x > 0) seed(i - 1); if (x < w - 1) seed(i + 1)
            if (y > 0) seed(i - w); if (y < h - 1) seed(i + w)
        }
        for (i in alpha.indices) if (!outside[i] && alpha[i] < 0.75f) alpha[i] = 1f
    }

    private fun clipToLasso(alpha: FloatArray, w: Int, h: Int, points: List<Pair<Float, Float>>) {
        // Allow the object to spill slightly past a hand-drawn loop.
        val inside = rasterize(points, w, h, strokeFraction = 0.04f)
        for (i in alpha.indices) if (inside[i] == 0) alpha[i] = 0f
    }

    private fun fillLasso(alpha: FloatArray, w: Int, h: Int, points: List<Pair<Float, Float>>) {
        val inside = rasterize(points, w, h, strokeFraction = 0f)
        for (i in alpha.indices) alpha[i] = if (inside[i] != 0) 1f else 0f
    }

    private fun rasterize(points: List<Pair<Float, Float>>, w: Int, h: Int, strokeFraction: Float): IntArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val path = Path().apply {
            points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * w, y * h) else lineTo(x * w, y * h) }
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        canvas.drawPath(path, paint)
        if (strokeFraction > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = min(w, h) * strokeFraction * 2
            paint.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(path, paint)
        }
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        return IntArray(px.size) { (px[it] ushr 24) }
    }

    private fun gestureBounds(points: List<Pair<Float, Float>>): RectF {
        if (points.size == 1) {
            val (x, y) = points[0]
            return RectF((x - 0.12f).coerceAtLeast(0f), (y - 0.12f).coerceAtLeast(0f),
                (x + 0.12f).coerceAtMost(1f), (y + 0.12f).coerceAtMost(1f))
        }
        return RectF(
            points.minOf { it.first }.coerceIn(0f, 1f), points.minOf { it.second }.coerceIn(0f, 1f),
            points.maxOf { it.first }.coerceIn(0f, 1f), points.maxOf { it.second }.coerceIn(0f, 1f),
        )
    }

    private const val QUERY_PX = 448
}
