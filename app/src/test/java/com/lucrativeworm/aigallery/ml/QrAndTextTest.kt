package com.lucrativeworm.aigallery.ml

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QrAndTextTest {

    private val box = RectF(0f, 0f, 1f, 1f)

    @Test
    fun `recognizes urls with and without scheme`() {
        assertTrue(QrScanner.looksLikeUrl("https://example.com/a?b=c"))
        assertTrue(QrScanner.looksLikeUrl("HTTP://EXAMPLE.COM"))
        assertTrue(QrScanner.looksLikeUrl("www.example.co.uk/menu"))
        assertTrue(QrScanner.looksLikeUrl("  shop.example.com  "))
    }

    @Test
    fun `rejects plain text, wifi configs and multi-line content`() {
        assertFalse(QrScanner.looksLikeUrl("hello world"))
        assertFalse(QrScanner.looksLikeUrl("WIFI:S:home;T:WPA;P:secret;;"))
        assertFalse(QrScanner.looksLikeUrl("example.com\nsecond line"))
        assertFalse(QrScanner.looksLikeUrl("justaword"))
    }

    @Test
    fun `normalize adds https only when no scheme`() {
        assertEquals("https://example.com", QrScanner.normalizeUrl(" example.com "))
        assertEquals("http://example.com", QrScanner.normalizeUrl("http://example.com"))
        assertEquals("market://details?id=x", QrScanner.normalizeUrl("market://details?id=x"))
    }

    @Test
    fun `label shows host without www for links`() {
        assertEquals("example.com", QrCode("x", "https://www.example.com/path", box).label)
        assertEquals("shop.example.com", QrCode("x", "https://shop.example.com", box).label)
    }

    @Test
    fun `label shows first line of text, capped`() {
        assertEquals("line one", QrCode("line one\nline two", null, box).label)
        assertEquals(48, QrCode("a".repeat(100), null, box).label.length)
    }

    @Test
    fun `selected words join with spaces and newlines`() {
        val words = listOf(
            TextWord("Total", box, line = 0, block = 0),
            TextWord("\$12.50", box, line = 0, block = 0),
            TextWord("Thank", box, line = 1, block = 0),
            TextWord("you", box, line = 1, block = 0),
            TextWord("Visit", box, line = 2, block = 1),
        )
        assertEquals("Total \$12.50\nThank you\nVisit", words.joinAsText())
        assertEquals("", emptyList<TextWord>().joinAsText())
        assertEquals("solo", listOf(TextWord("solo", box, 5, 0)).joinAsText())
    }
}
