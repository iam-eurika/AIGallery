package com.lucrativeworm.seekframe.ml

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Turns a lifted object into a transparent PNG other apps can paste or receive: messaging apps,
 * notes, docs, image editors. Files live in cache/lifts and are served through our FileProvider.
 */
object Stickers {

    private suspend fun save(context: Context, cutout: Bitmap): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "lifts").apply { mkdirs() }
        // Keep the folder small: only the latest few lifts are worth pasting.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(8)?.forEach { it.delete() }
        val file = File(dir, "lift-${System.currentTimeMillis()}.png")
        file.outputStream().use { cutout.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    /**
     * Puts the cutout on the clipboard as an image. Returns true when the system shows its own
     * "copied" confirmation (Android 13+), so the caller doesn't stack a second one on top.
     */
    suspend fun copy(context: Context, cutout: Bitmap): Boolean {
        val uri = save(context, cutout)
        val clip = ClipData(
            ClipDescription("Lifted from a photo", arrayOf("image/png")),
            ClipData.Item(uri),
        )
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)
            }
        }
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(clip)
        return Build.VERSION.SDK_INT >= 33
    }

    suspend fun share(context: Context, cutout: Bitmap) {
        val uri = save(context, cutout)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
