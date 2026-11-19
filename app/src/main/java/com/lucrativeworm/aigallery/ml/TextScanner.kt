package com.lucrativeworm.aigallery.ml

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import android.util.LruCache
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * One recognized word. [bounds] is normalized 0..1 over the upright photo, like [QrCode.bounds].
 * [line] and [block] are running ids, so words on the same line/paragraph share them.
 */
data class TextWord(val text: String, val bounds: RectF, val line: Int, val block: Int)

/** The words in a selection, back as text: spaces within a line, newlines between lines. */
fun List<TextWord>.joinAsText(): String = buildString {
    var lastLine = -1
    for (w in this@joinAsText) {
        if (lastLine != -1) append(if (w.line == lastLine) " " else "\n")
        append(w.text)
        lastLine = w.line
    }
}

/**
 * Finds text in gallery photos with ML Kit's bundled Latin recognizer, fully offline. Words come
 * back in reading order (paragraph, line, word) so a range of indices is a natural selection.
 */
object TextScanner {
    private const val TAG = "TextScanner"
    private val cache = LruCache<Long, List<TextWord>>(64)
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    fun cached(photoId: Long): List<TextWord>? = cache.get(photoId)

    suspend fun scan(context: Context, photoId: Long, uri: Uri): List<TextWord> {
        cache.get(photoId)?.let { return it }
        val found = runCatching {
            val bitmap = QrScanner.decode(context, uri)
            val text = recognize(InputImage.fromBitmap(bitmap, 0)) ?: return@runCatching emptyList()
            flatten(text, bitmap.width.toFloat(), bitmap.height.toFloat())
        }.getOrElse {
            Log.w(TAG, "Text scan failed for $uri", it)
            emptyList()
        }
        cache.put(photoId, found)
        return found
    }

    private suspend fun recognize(image: InputImage): Text? = suspendCancellableCoroutine { cont ->
        recognizer.process(image)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resume(null) }
    }

    private fun flatten(text: Text, w: Float, h: Float): List<TextWord> {
        val out = ArrayList<TextWord>()
        var lineId = 0
        text.textBlocks.forEachIndexed { b, block ->
            for (line in block.lines) {
                for (el in line.elements) {
                    val box = el.boundingBox ?: continue
                    out += TextWord(el.text, RectF(box.left / w, box.top / h, box.right / w, box.bottom / h), lineId, b)
                }
                lineId++
            }
        }
        return out
    }
}
