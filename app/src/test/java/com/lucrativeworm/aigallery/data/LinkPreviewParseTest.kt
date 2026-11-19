package com.lucrativeworm.aigallery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LinkPreviewParseTest {

    @Test
    fun `reads open graph tags`() {
        val html = """
            <html><head>
            <title>Fallback</title>
            <meta property="og:title" content="Fish &amp; Chips">
            <meta property='og:description' content='Best in town'>
            <meta property="og:image" content="/img/hero.jpg">
            <meta property="og:site_name" content="Harbour Café">
            </head></html>
        """.trimIndent()
        val p = LinkPreviews.parse("https://food.example.com/menu/today", html)
        assertEquals("https://food.example.com/menu/today", p.url)
        assertEquals("Fish & Chips", p.title)
        assertEquals("Best in town", p.description)
        assertEquals("https://food.example.com/img/hero.jpg", p.image)
        assertEquals("Harbour Café", p.siteName)
    }

    @Test
    fun `falls back to title tag and meta description`() {
        val html = """<head><TITLE>
            Plain page </TITLE><meta name="description" content="About us"></head>"""
        val p = LinkPreviews.parse("https://example.com", html)
        assertEquals("Plain page", p.title)
        assertEquals("About us", p.description)
        assertNull(p.image)
        assertNull(p.siteName)
    }

    @Test
    fun `twitter tags are used when og is missing`() {
        val html = """<meta name="twitter:title" content="Tweet title"><meta name="twitter:image" content="https://cdn.example.com/a.png">"""
        val p = LinkPreviews.parse("https://example.com", html)
        assertEquals("Tweet title", p.title)
        assertEquals("https://cdn.example.com/a.png", p.image)
    }

    @Test
    fun `non http images and empty values are dropped`() {
        val html = """<meta property="og:title" content="  "><meta property="og:image" content="data:image/png;base64,AAAA">"""
        val p = LinkPreviews.parse("https://example.com", html)
        assertNull(p.title)
        assertNull(p.image)
    }
}
