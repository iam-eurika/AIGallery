package com.lucrativeworm.seekframe.ml

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class LifterGestureTest {

    private fun circle(cx: Float, cy: Float, r: Float, n: Int = 24, sweep: Double = 2 * PI) =
        (0 until n).map { i ->
            val a = sweep * i / (n - 1)
            (cx + r * cos(a).toFloat()) to (cy + r * sin(a).toFloat())
        }

    @Test
    fun `a single point is a tap at that point`() {
        val g = Lifter.gesture(listOf(0.4f to 0.6f), aspect = 1f)
        assertEquals(Gesture.Kind.Tap, g.kind)
        assertEquals(listOf(0.4f to 0.6f), g.points)
    }

    @Test
    fun `fewer than four points is a tap at the last point`() {
        val g = Lifter.gesture(listOf(0.1f to 0.1f, 0.5f to 0.5f, 0.9f to 0.9f), aspect = 1f)
        assertEquals(Gesture.Kind.Tap, g.kind)
        assertEquals(listOf(0.9f to 0.9f), g.points)
    }

    @Test
    fun `a tiny jitter is a tap`() {
        val jitter = listOf(0.5f to 0.5f, 0.501f to 0.502f, 0.505f to 0.499f, 0.51f to 0.51f, 0.508f to 0.503f)
        assertEquals(Gesture.Kind.Tap, Lifter.gesture(jitter, aspect = 1f).kind)
    }

    @Test
    fun `a closed loop is a lasso`() {
        val g = Lifter.gesture(circle(0.5f, 0.5f, 0.2f), aspect = 1f)
        assertEquals(Gesture.Kind.Lasso, g.kind)
        assertEquals(24, g.points.size)
    }

    @Test
    fun `an open line is a scribble`() {
        val line = (0..10).map { (0.1f + it * 0.07f) to 0.5f }
        assertEquals(Gesture.Kind.Scribble, Lifter.gesture(line, aspect = 1f).kind)
    }

    @Test
    fun `a half circle is a scribble, not a lasso`() {
        assertEquals(Gesture.Kind.Scribble, Lifter.gesture(circle(0.5f, 0.5f, 0.2f, sweep = PI), aspect = 1f).kind)
    }

    @Test
    fun `aspect ratio scales vertical span`() {
        // A short vertical stroke on a very tall image (aspect 10) shrinks below the tap threshold.
        val vertical = (0..5).map { 0.5f to (0.4f + it * 0.02f) }
        assertEquals(Gesture.Kind.Scribble, Lifter.gesture(vertical, aspect = 1f).kind)
        assertEquals(Gesture.Kind.Tap, Lifter.gesture(vertical, aspect = 10f).kind)
    }
}
