package com.lucrativeworm.aigallery.data

import android.text.Html
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class LinkPreview(
    val url: String,
    val title: String?,
    val description: String?,
    val image: String?,
    val siteName: String?,
    /** The preview image's bytes, fetched here because Coil has no network fetcher in this app. */
    val imageBytes: ByteArray? = null,
)

/**
 * Fetches a page's title, description and Open Graph image. Only runs when the user taps a QR
 * code, reads at most the first 256 KB, and never executes anything from the page.
 */
object LinkPreviews {
    private const val MAX_BYTES = 256 * 1024
    private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
    private val cache = LruCache<String, LinkPreview>(64)

    suspend fun fetch(url: String): LinkPreview? {
        cache.get(url)?.let { return it }
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) LinkPreview")
                    setRequestProperty("Accept", "text/html,application/xhtml+xml")
                }
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    if (conn.contentType?.contains("html", true) == false) return@runCatching null
                    val bytes = conn.inputStream.use { input ->
                        val buf = ByteArray(MAX_BYTES)
                        var n = 0
                        while (n < MAX_BYTES) {
                            val r = input.read(buf, n, MAX_BYTES - n)
                            if (r < 0) break
                            n += r
                        }
                        buf.copyOf(n)
                    }
                    val page = parse(conn.url.toString(), String(bytes, Charsets.UTF_8))
                    page.copy(imageBytes = page.image?.let { img ->
                        runCatching { download(img, MAX_IMAGE_BYTES) }
                            .onFailure { android.util.Log.w("LinkPreview", "Image fetch failed: $img", it) }
                            .getOrNull()
                    })
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()?.also { cache.put(url, it) }
        }
    }

    private fun download(url: String, limit: Int): ByteArray? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) LinkPreview")
        }
        try {
            if (conn.responseCode !in 200..299) return null
            if (conn.contentType?.startsWith("image/", true) == false) return null
            return conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    if (out.size() > limit) return null
                }
                out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    internal fun parse(finalUrl: String, html: String): LinkPreview {
        val metas = Regex("<meta\\s[^>]*>", RegexOption.IGNORE_CASE).findAll(html).map { it.value }.toList()
        fun meta(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            metas.firstOrNull {
                Regex("(property|name)\\s*=\\s*[\"']${Regex.escape(key)}[\"']", RegexOption.IGNORE_CASE).containsMatchIn(it)
            }?.let { tag ->
                Regex("content\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE).find(tag)
                    ?.let { m -> m.groupValues[2].ifEmpty { m.groupValues[3] } }
            }?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }
        }
        val title = meta("og:title", "twitter:title")
            ?: Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(html)?.groupValues?.get(1)?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }
        val image = meta("og:image", "og:image:url", "twitter:image")?.let { resolve(finalUrl, it) }
        return LinkPreview(
            url = finalUrl,
            title = title,
            description = meta("og:description", "description", "twitter:description"),
            image = image?.takeIf { it.startsWith("http", true) },
            siteName = meta("og:site_name"),
        )
    }

    private fun resolve(base: String, href: String): String? = runCatching { URL(URL(base), href).toString() }.getOrNull()

    private fun unescape(s: String): String = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()
}
