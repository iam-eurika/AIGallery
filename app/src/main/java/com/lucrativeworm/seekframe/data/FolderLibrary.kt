package com.lucrativeworm.seekframe.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Folders the user hands us through the system folder picker (Storage Access Framework).
 *
 * This reaches images the media library never sees: folders with a `.nomedia` file, synced
 * cloud folders, other apps' exports. Access is per folder, read-only, granted in the system UI
 * and persisted until the user removes the folder here.
 */
@Singleton
class FolderLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: BoxStore,
) {

    private val folders = store.boxFor<Folder>()
    private val photos = store.boxFor<Photo>()
    private val mutex = Mutex()

    private val _scanning = MutableStateFlow<Set<Long>>(emptySet())
    /** Folder ids currently being walked, so the UI can show a live state on each chip. */
    val scanning: StateFlow<Set<Long>> = _scanning.asStateFlow()

    /** Takes the persisted grant and registers the folder. Returns it, or the existing one. */
    suspend fun add(treeUri: Uri): Folder = withContext(Dispatchers.IO) {
        // Write too when the picker granted it, so photos can be deleted from here; read-only otherwise.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val key = treeUri.toString()
        folders.query(Folder_.treeUri.equal(key)).build().use { it.findFirst() }
            ?: Folder(treeUri = key, name = displayName(treeUri), addedAt = System.currentTimeMillis())
                .also { folders.put(it) }
    }

    /** Forgets the folder, its photos and their vectors, and gives the grant back to the system. */
    suspend fun remove(folder: Folder) = withContext(Dispatchers.IO) {
        removeContents(folder)
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(folder.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(folder.treeUri), Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    suspend fun syncAll() {
        for (folder in folders.all) sync(folder)
    }

    /** Walks one folder tree and mirrors its images into ObjectBox (new → PENDING for the indexer). */
    suspend fun sync(folder: Folder) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val treeUri = Uri.parse(folder.treeUri)
            if (!hasAccess(treeUri)) {
                // The user revoked access in system settings; drop what we can no longer read.
                Log.i(TAG, "Lost access to ${folder.name}, removing it")
                removeContents(folder)
                return@withContext
            }
            _scanning.update { it + folder.id }
            try {
                walk(folder, treeUri)
            } finally {
                _scanning.update { it - folder.id }
            }
        }
    }

    private suspend fun walk(folder: Folder, treeUri: Uri) {
        val existing = photos.query(Photo_.folderId.equal(folder.id)).build().use { it.find() }
            .associateBy { it.uri }
        val seen = HashSet<String>(existing.size)
        val pending = ArrayList<Photo>()
        val stack = ArrayDeque<Pair<String, String>>() // documentId to its folder's display name
        stack += DocumentsContract.getTreeDocumentId(treeUri) to folder.name

        while (stack.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (parentId, parentName) = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            context.contentResolver.query(children, PROJECTION, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0)
                    val name = c.getString(1).orEmpty()
                    val mime = c.getString(2).orEmpty()
                    val modified = c.getLong(3)
                    if (mime == Document.MIME_TYPE_DIR) {
                        if (name !in SKIP_DIRS && !name.startsWith(".trashed")) stack += docId to name
                        continue
                    }
                    if (!mime.startsWith("image/") && !mime.startsWith("video/")) continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString()
                    seen += uri
                    val old = existing[uri]
                    if (old != null && old.dateModified == modified) continue
                    val path = storagePath(treeUri, docId)
                    if (path.isNotEmpty() && inMediaLibrary(path)) continue
                    pending += describe(old ?: Photo(), uri, path, mime, modified, folder, parentName)
                    // Land photos in small batches so the grid fills in while a big folder is walked.
                    if (pending.size >= 150) flush(pending)
                }
            }
        }
        flush(pending)

        val gone = existing.values.filter { it.uri !in seen }
        if (gone.isNotEmpty()) removePhotos(gone)
        folder.photoCount = photos.query(Photo_.folderId.equal(folder.id)).build().use { it.count().toInt() }
        folder.lastScan = System.currentTimeMillis()
        folders.put(folder)
    }

    private fun describe(
        photo: Photo, uri: String, path: String, mime: String, modified: Long, folder: Folder, album: String,
    ): Photo = photo.apply {
        this.uri = uri
        this.path = path
        mimeType = mime
        source = Source.FOLDER
        folderId = folder.id
        this.album = album
        dateModified = modified
        dateTaken = modified
        requeue()
        isVideo = mime.startsWith("video/")
        if (isVideo) {
            describeVideo(this)
            return@apply
        }
        // Prefer when the photo was taken over when the file was copied here.
        runCatching {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                val exif = ExifInterface(input)
                (exif.dateTimeOriginal ?: exif.dateTime)?.let { dateTaken = it }
                val w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                val h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                val sideways = exif.rotationDegrees % 180 != 0
                width = if (sideways) h else w
                height = if (sideways) w else h
            }
        }
    }

    /** Length, size and recording date from the video's own metadata. */
    private fun describeVideo(video: Photo) {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(context, Uri.parse(video.uri))
            fun meta(key: Int) = r.extractMetadata(key)
            video.durationMs = meta(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val w = meta(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = meta(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val sideways = (meta(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0) % 180 != 0
            video.width = if (sideways) h else w
            video.height = if (sideways) w else h
            // e.g. "20240512T101530.000Z"
            meta(android.media.MediaMetadataRetriever.METADATA_KEY_DATE)?.let { raw ->
                runCatching {
                    java.time.LocalDateTime.parse(raw.substringBefore('.').removeSuffix("Z"), java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                        .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
                }.getOrNull()?.takeIf { it > 0 }?.let { video.dateTaken = it }
            }
        } catch (_: Throwable) {
            // Unreadable metadata: keep the file dates; indexing will mark it failed if it can't decode.
        } finally {
            runCatching { r.release() }
        }
    }

    private fun flush(batch: MutableList<Photo>) {
        if (batch.isEmpty()) return
        photos.put(batch)
        batch.clear()
    }

    /**
     * Removes picked-folder copies of files the media library already shows. Runs after both
     * sources sync, e.g. when photo access is granted after a folder was added.
     */
    fun dedupe() {
        val folderPhotos = photos.query(Photo_.source.equal(Source.FOLDER.toLong())).build().use { it.find() }
            .filter { !it.path.isNullOrEmpty() && inMediaLibrary(it.path!!) }
        if (folderPhotos.isNotEmpty()) removePhotos(folderPhotos)
    }

    private fun inMediaLibrary(path: String): Boolean =
        photos.query(Photo_.path.equal(path).and(Source.isMediaStore()))
            .build().use { it.count() > 0 }

    private fun removeContents(folder: Folder) {
        store.runInTx {
            val own = photos.query(Photo_.folderId.equal(folder.id)).build().use { it.find() }
            removePhotos(own)
            folders.remove(folder)
        }
    }

    /**
     * Permanently deletes picked-folder photos from storage, then forgets them. Returns how many
     * could not be deleted (e.g. the folder was shared read-only).
     */
    suspend fun delete(list: List<Photo>): Int = withContext(Dispatchers.IO) {
        val done = list.filter { photo ->
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(photo.uri)) }.getOrDefault(false)
        }
        if (done.isNotEmpty()) removePhotos(done)
        list.groupBy { it.folderId }.keys.forEach { id ->
            folders.get(id)?.let { f ->
                f.photoCount = photos.query(Photo_.folderId.equal(id)).build().use { it.count().toInt() }
                folders.put(f)
            }
        }
        list.size - done.size
    }

    private fun removePhotos(list: List<Photo>) {
        val ids = list.map { it.id }.toLongArray()
        store.runInTx {
            removeVectorsOf(ids)
            photos.remove(list)
        }
    }

    private fun hasAccess(treeUri: Uri) =
        context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }

    private fun displayName(treeUri: Uri): String {
        val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        return context.contentResolver.query(root, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: treeUri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
            ?: "Folder"
    }

    /** "primary:Pictures/Trips/a.jpg" → "Pictures/Trips/a.jpg" for the device's own storage. */
    private fun storagePath(treeUri: Uri, docId: String): String =
        if (treeUri.authority == EXTERNAL_STORAGE && docId.startsWith("primary:")) docId.substringAfter(':') else ""

    private companion object {
        const val TAG = "FolderLibrary"
        const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
        val SKIP_DIRS = setOf(".thumbnails", ".Trash", ".trash", ".cache")
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
