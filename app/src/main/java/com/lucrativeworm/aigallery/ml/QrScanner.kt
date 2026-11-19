package com.lucrativeworm.aigallery.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import android.util.LruCache
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * A QR code found in a photo. [bounds] is in normalized 0..1 image coordinates (after EXIF
 * rotation), so it can be laid over the photo at whatever size it is displayed.
 */
data class QrCode(val raw: String, val url: String?, val bounds: RectF) {
    /** What the user sees as the code's label: the host for links, the text otherwise. */
    val label: String
        get() = url?.let { Uri.parse(it).host?.removePrefix("www.") } ?: raw.lineSequence().first().take(48)
}

/**
 * Finds QR codes in gallery photos with ML Kit's bundled barcode model. Runs fully offline;
 * results are cached per photo so swiping back and forth never rescans.
 */
object QrScanner {
    private const val TAG = "QrScanner"
    private const val MAX_PX = 1600

    private val cache = LruCache<Long, List<QrCode>>(256)
    private val scanner by lazy { newClient() }

    /** A fresh QR-only client; the live camera owns its own so closing it never affects photo scans. */
    fun newClient() = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    )

    /** Turns a live-camera detection into a [QrCode], with bounds normalized to a [w]×[h] frame. */
    fun fromBarcode(barcode: Barcode, w: Float, h: Float): QrCode? = barcode.toQr(w, h)

    fun cached(photoId: Long): List<QrCode>? = cache.get(photoId)

    suspend fun scan(context: Context, photoId: Long, uri: Uri): List<QrCode> {
        cache.get(photoId)?.let { return it }
        val found = runCatching {
            val bitmap = decode(context, uri)
            val codes = detect(bitmap)
            codes.mapNotNull { it.toQr(bitmap.width.toFloat(), bitmap.height.toFloat()) }
        }.getOrElse {
            Log.w(TAG, "Scan failed for $uri", it)
            emptyList()
        }
        cache.put(photoId, found)
        return found
    }

    private suspend fun detect(bitmap: Bitmap): List<Barcode> = suspendCancellableCoroutine { cont ->
        scanner.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resume(emptyList()) }
    }

    private fun Barcode.toQr(w: Float, h: Float): QrCode? {
        val raw = rawValue ?: return null
        val box = boundingBox ?: return null
        val url = url?.url?.takeIf { it.isNotBlank() } ?: raw.takeIf { looksLikeUrl(it) }
        return QrCode(
            raw = raw,
            url = url?.let(::normalizeUrl),
            bounds = RectF(box.left / w, box.top / h, box.right / w, box.bottom / h),
        )
    }

    internal fun looksLikeUrl(s: String): Boolean {
        val t = s.trim()
        if (t.contains(' ') || t.contains('\n')) return false
        return t.startsWith("http://", true) || t.startsWith("https://", true) ||
            Regex("^(www\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)+(/.*)?$", RegexOption.IGNORE_CASE).matches(t)
    }

    internal fun normalizeUrl(s: String): String {
        val t = s.trim()
        return if (Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(t)) t else "https://$t"
    }

    /** Decodes a photo upright (EXIF applied), capped at [MAX_PX]; shared with [TextScanner]. */
    internal suspend fun decode(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > MAX_PX) {
                val k = MAX_PX / longest.toFloat()
                decoder.setTargetSize((info.size.width * k).toInt(), (info.size.height * k).toInt())
            }
        }
    }
}
