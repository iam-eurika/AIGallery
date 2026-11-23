package com.lucrativeworm.seekframe.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import io.objectbox.kotlin.boxFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Mirrors MediaStore's images and videos into ObjectBox. Additions become [IndexState.PENDING] so the
 * indexer picks them up; edits are re-queued; deletions drop the photo and its vector.
 */
class MediaLibrary(private val context: Context) {

    private val photos = Db.store.boxFor<Photo>()

    data class SyncReport(val added: Int, val updated: Int, val removed: Int)

    /**
     * Syncs are started from several places (app start, the change observer, permission changes,
     * the background worker). Run concurrently, each sees a new photo as missing and inserts it,
     * so the grid showed every photo twice. One at a time fixes that.
     */
    private val mutex = Mutex()

    /** True when the user granted full or partial (Android 14 "selected photos") access. */
    fun hasAccess(): Boolean {
        val perms = when {
            Build.VERSION.SDK_INT >= 34 -> listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return perms.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    suspend fun sync(): SyncReport = mutex.withLock { syncLocked() }

    private suspend fun syncLocked(): SyncReport = withContext(Dispatchers.IO) {
        if (!hasAccess()) {
            // Access was never given or has been revoked: we can no longer read these, so they
            // shouldn't linger as blank tiles. Granting again re-adds and re-indexes them.
            val stale = photos.query(Source.isMediaStore()).build().use { it.find() }
            if (stale.isNotEmpty()) {
                val ids = stale.map { it.id }.toLongArray()
                Db.store.runInTx {
                    removeVectorsOf(ids)
                    photos.remove(stale)
                }
            }
            return@withContext SyncReport(0, 0, stale.size)
        }
        val all = photos.query(Source.isMediaStore()).build().use { it.find() }
        // Heal rows doubled up by earlier overlapping syncs: keep one per MediaStore id,
        // preferring the copy that's already indexed so search doesn't redo work.
        val doubles = all.groupBy { it.mediaId }.values.filter { it.size > 1 }.flatMap { copies ->
            val keep = copies.minWith(compareBy<Photo>({ if (it.indexState == IndexState.DONE) 0 else 1 }, { it.id }))
            copies - keep
        }
        if (doubles.isNotEmpty()) {
            val ids = doubles.map { it.id }.toLongArray()
            Db.store.runInTx {
                removeVectorsOf(ids)
                photos.remove(doubles)
            }
        }
        val doubleIds = doubles.mapTo(HashSet()) { it.id }
        val existing = all.filter { it.id !in doubleIds }.associateBy { it.mediaId }
        val seen = HashSet<Long>(existing.size)
        val toPut = ArrayList<Photo>()
        var added = 0
        var updated = 0

        for (video in listOf(false, true)) {
            val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val projection = PROJECTION + if (video) arrayOf(MediaStore.MediaColumns.DURATION) else emptyArray()
            val cursor = context.contentResolver.query(
                collection, projection, null, null,
                "${MediaStore.MediaColumns.DATE_TAKEN} DESC",
            ) ?: continue
            cursor.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val takenCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                val addedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val modCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val wCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
                val hCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val relCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val durCol = if (video) c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION) else -1
                while (c.moveToNext()) {
                    // Images and videos share MediaStore's files table, so their ids never collide.
                    val mediaId = c.getLong(idCol)
                    seen += mediaId
                    val modified = c.getLong(modCol)
                    val taken = c.getLong(takenCol).takeIf { it > 0 } ?: (c.getLong(addedCol) * 1000)
                    val old = existing[mediaId]
                    if (old != null && old.dateModified == modified) continue
                    val photo = (old ?: Photo()).apply {
                        this.mediaId = mediaId
                        uri = ContentUris.withAppendedId(collection, mediaId).toString()
                        dateTaken = taken
                        dateModified = modified
                        width = c.getInt(wCol)
                        height = c.getInt(hCol)
                        album = c.getString(albumCol).orEmpty()
                        mimeType = c.getString(mimeCol).orEmpty()
                        source = Source.MEDIA_STORE
                        path = c.getString(relCol).orEmpty() + c.getString(nameCol).orEmpty()
                        isVideo = video
                        durationMs = if (video) c.getLong(durCol) else 0
                        requeue()
                    }
                    if (old == null) added++ else updated++
                    toPut += photo
                }
            }
        }

        val gone = existing.values.filter { it.mediaId !in seen }
        Db.store.runInTx {
            if (toPut.isNotEmpty()) photos.put(toPut)
            if (gone.isNotEmpty()) {
                val goneIds = gone.map { it.id }.toLongArray()
                removeVectorsOf(goneIds)
                photos.remove(gone)
            }
        }
        SyncReport(added, updated, gone.size + doubles.size)
    }

    private companion object {
        val PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME,
        )
    }

    /** Ticks whenever the device's image or video collection changes (new screenshot, deletion, edit). */
    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer,
        )
        context.contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer,
        )
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }
}
