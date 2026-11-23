package com.lucrativeworm.seekframe.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real on-device models that ship in the APK: the MagicTouch segmenter (from assets) and
 * ML Kit's bundled text recognizer, plus the lift/cutout pipeline on real Android graphics.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceVisionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A white 400x400 image with a solid red square in the middle third. */
    private fun squareScene(): Bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply {
        val c = Canvas(this)
        c.drawColor(Color.WHITE)
        c.drawRect(140f, 140f, 260f, 260f, Paint().apply { color = Color.RED })
    }

    @Test
    fun segmenterFindsTheTappedObject() = runBlocking<Unit> {
        val segmenter = Segmenter(context)
        val mask = segmenter.segment(squareScene(), Gesture(listOf(0.5f to 0.5f), Gesture.Kind.Tap))
        assertEquals(mask.width * mask.height, mask.alpha.size)
        fun at(x: Float, y: Float) = mask.alpha[(y * mask.height).toInt() * mask.width + (x * mask.width).toInt()]
        assertTrue("centre is the object (${at(0.5f, 0.5f)})", at(0.5f, 0.5f) > 0.5f)
        assertTrue("corner is background (${at(0.05f, 0.05f)})", at(0.05f, 0.05f) < 0.5f)
        segmenter.forget()
    }

    @Test
    fun liftCutsTheMaskedObjectOut() {
        val w = 100; val h = 100
        // A confident square from 30..70 with a low-confidence hole in its middle.
        val alpha = FloatArray(w * h) { i ->
            val x = i % w; val y = i / w
            when {
                x in 45..54 && y in 45..54 -> 0.1f
                x in 30..69 && y in 30..69 -> 0.95f
                else -> 0f
            }
        }
        val gesture = Lifter.gesture(listOf(0.5f to 0.5f), aspect = 1f)
        val lift = Lifter.lift(squareScene(), Mask(w, h, alpha), gesture)

        assertTrue(lift.precise)
        assertEquals(0.30f, lift.bounds.left, 0.011f)
        assertEquals(0.70f, lift.bounds.right, 0.011f)
        assertEquals(448, lift.query.width)
        assertEquals(448, lift.query.height)
        // The enclosed hole was filled: the cutout is opaque in its centre.
        val cx = lift.cutout.width / 2; val cy = lift.cutout.height / 2
        assertEquals(255, Color.alpha(lift.cutout.getPixel(cx, cy)))
        // The query is framed on neutral grey.
        assertEquals(Color.rgb(128, 128, 128), lift.query.getPixel(1, 1))
    }

    @Test
    fun liftFallsBackToTheCircledRegionWhenNothingIsFound() {
        val empty = Mask(50, 50, FloatArray(2500))
        val loop = listOf(0.2f to 0.2f, 0.6f to 0.2f, 0.6f to 0.6f, 0.2f to 0.6f, 0.21f to 0.21f)
        val gesture = Lifter.gesture(loop, aspect = 1f)
        assertEquals(Gesture.Kind.Lasso, gesture.kind)
        val lift = Lifter.lift(squareScene(), empty, gesture)
        assertFalse(lift.precise)
        assertEquals(0.2f, lift.bounds.left, 1e-3f)
        assertEquals(0.6f, lift.bounds.bottom, 1e-3f)
    }

    @Test
    fun textScannerReadsTextOutOfAPhoto() = runBlocking<Unit> {
        val bmp = Bitmap.createBitmap(1200, 400, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 110f; typeface = Typeface.DEFAULT_BOLD }
            drawText("HELLO GALLERY", 60f, 170f, p)
            drawText("WIFI 4242", 60f, 330f, p)
        }
        val file = File(context.cacheDir, "text-scan-test.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val photoId = System.nanoTime()
        val words = TextScanner.scan(context, photoId, Uri.fromFile(file))
        val text = words.joinAsText().uppercase()
        assertTrue("got: $text", "HELLO" in text && "GALLERY" in text && "4242" in text)
        assertTrue("two lines: $text", text.contains('\n'))
        assertTrue(words.all { it.bounds.left in 0f..1f && it.bounds.bottom in 0f..1f })
        // Cached: a second look never rescans.
        assertEquals(words, TextScanner.cached(photoId))
        file.delete()
    }
}
