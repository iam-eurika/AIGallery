package com.lucrativeworm.aigallery.ml

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Decodes [uri] as a software ARGB_8888 bitmap no larger than [maxPx] on its longest side, so its
 * pixels can be read and handed to MediaPipe.
 */
suspend fun decodeArgb(resolver: ContentResolver, uri: Uri, maxPx: Int): Bitmap = withContext(Dispatchers.IO) {
    val src = ImageDecoder.createSource(resolver, uri)
    ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = max(info.size.width, info.size.height)
        if (longest > maxPx) {
            val k = maxPx / longest.toFloat()
            decoder.setTargetSize((info.size.width * k).toInt(), (info.size.height * k).toInt())
        }
    }.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }
}
