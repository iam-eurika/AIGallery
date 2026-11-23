package com.lucrativeworm.seekframe.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything the details panel shows. Any field can be missing; the panel skips those rows. */
data class PhotoDetails(
    val name: String?,
    val sizeBytes: Long?,
    val width: Int,
    val height: Int,
    val mimeType: String?,
    val path: String?,
    val dateTaken: Long?,
    val dateModified: Long?,
    val make: String?,
    val model: String?,
    val lens: String?,
    val iso: Int?,
    val focalLength: Double?,
    val focal35: Int?,
    val aperture: Double?,
    val exposureSeconds: Double?,
    val flashFired: Boolean?,
    val software: String?,
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Double?,
    /** True when Android stripped GPS because we lack ACCESS_MEDIA_LOCATION. */
    val locationHidden: Boolean,
)

object PhotoDetailsLoader {
    private const val TAG = "PhotoDetails"

    fun canSeeLocation(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    suspend fun load(context: Context, photo: Photo): PhotoDetails = withContext(Dispatchers.IO) {
        val uri = Uri.parse(photo.uri)
        val resolver = context.contentResolver
        val isFolder = photo.source == Source.FOLDER

        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    size = c.getLong(1).takeIf { it > 0 }
                }
            }
        }

        // Modified time: MediaStore keeps seconds, documents keep milliseconds.
        val modified = runCatching {
            if (isFolder) resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            else resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) * 1000 else null }
        }.getOrNull()?.takeIf { it > 0 }

        // GPS is redacted from media-library photos unless we hold ACCESS_MEDIA_LOCATION and ask
        // for the original bytes; picked-folder files are read as they are.
        val located = !isFolder && canSeeLocation(context)
        val exifUri = if (located) runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri) else uri
        val exif = runCatching { resolver.openInputStream(exifUri)?.use { ExifInterface(it) } }
            .onFailure { Log.w(TAG, "No EXIF for $uri", it) }
            .getOrNull()

        fun str(tag: String) = exif?.getAttribute(tag)?.trim()?.takeIf { it.isNotEmpty() }
        fun dbl(tag: String) = exif?.getAttributeDouble(tag, -1.0)?.takeIf { it > 0 }
        fun int(tag: String) = exif?.getAttributeInt(tag, -1)?.takeIf { it > 0 }

        val latLong = exif?.latLong
        val flash = exif?.getAttributeInt(ExifInterface.TAG_FLASH, -1)?.takeIf { it >= 0 }
        PhotoDetails(
            name = name ?: photo.path?.substringAfterLast('/'),
            sizeBytes = size,
            width = photo.width,
            height = photo.height,
            mimeType = photo.mimeType.ifBlank { null },
            path = photo.path?.substringBeforeLast('/', "")?.ifBlank { null } ?: photo.album.ifBlank { null },
            dateTaken = exif?.dateTimeOriginal ?: photo.dateTaken.takeIf { it > 0 },
            dateModified = modified,
            make = str(ExifInterface.TAG_MAKE),
            model = str(ExifInterface.TAG_MODEL),
            lens = str(ExifInterface.TAG_LENS_MODEL),
            iso = int(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY),
            focalLength = dbl(ExifInterface.TAG_FOCAL_LENGTH),
            focal35 = int(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM),
            aperture = dbl(ExifInterface.TAG_F_NUMBER),
            exposureSeconds = dbl(ExifInterface.TAG_EXPOSURE_TIME),
            flashFired = flash?.let { it and 1 == 1 },
            software = str(ExifInterface.TAG_SOFTWARE),
            latitude = latLong?.getOrNull(0),
            longitude = latLong?.getOrNull(1),
            altitude = exif?.getAltitude(Double.NaN)?.takeIf { !it.isNaN() && latLong != null },
            locationHidden = !isFolder && !located,
        )
    }
}
