package com.lucrativeworm.seekframe.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Backups of the search index in a folder the user picks once. Backing up writes a new dated
 * file there (the newest [KEEP] are kept); restoring lists those files so the user taps one
 * instead of hunting for it. With auto backup on, a fresh file is written after indexing work.
 *
 * Android drops the folder grant when the app is uninstalled, so after a reinstall the user picks
 * the same folder once more and the old backups show up.
 */
class IndexBackups(
    private val context: Context,
    private val store: BoxStore,
    /** The model search runs on now, or null if none is loaded. */
    private val activeModel: () -> String?,
    private val soundsOn: () -> Boolean,
    /** Stops indexing and waits for it, so nothing writes vectors while a restore runs. */
    private val pauseIndexing: suspend () -> Unit,
    /** Brings the photo rows up to date, so a restore can match against everything on the phone. */
    private val syncSources: suspend () -> Unit,
) {
    data class BackupFile(
        val uri: Uri,
        val name: String,
        val bytes: Long,
        val header: IndexBackup.Header,
    )

    enum class Work { BACKING_UP, RESTORING, LISTING }

    data class State(
        val folderName: String? = null,
        /** A folder was set but its access is gone (e.g. revoked); it has to be picked again. */
        val lostAccess: Boolean = false,
        val auto: Boolean = true,
        val files: List<BackupFile> = emptyList(),
        val work: Work? = null,
        /** Outcome of the last action, in words for the screen. */
        val message: String? = null,
        val lastBackupAt: Long = 0,
    )

    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(
        State(
            folderName = prefs.getString(KEY_NAME, null),
            auto = prefs.getBoolean(KEY_AUTO, true),
            lastBackupAt = prefs.getLong(KEY_LAST_AT, 0),
        )
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private val folder: Uri? get() = prefs.getString(KEY_TREE, null)?.let(Uri::parse)

    /** The user picked a folder: keep read and write access to it, then show what's in it. */
    suspend fun setFolder(tree: Uri) = withContext(Dispatchers.IO) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val ok = runCatching { context.contentResolver.takePersistableUriPermission(tree, flags) }
            .onFailure { Log.w(TAG, "No lasting access to $tree", it) }.isSuccess
        if (!ok) {
            _state.update { it.copy(message = "That folder can't be used for backups. Try a folder in Documents or Download.") }
            return@withContext
        }
        folder?.takeIf { it != tree }?.let(::release)
        val name = displayName(tree)
        prefs.edit().putString(KEY_TREE, tree.toString()).putString(KEY_NAME, name).apply()
        _state.update { it.copy(folderName = name, lostAccess = false, message = null) }
        refresh()
    }

    fun setAuto(on: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO, on).apply()
        _state.update { it.copy(auto = on) }
    }

    /** Re-reads the folder's backups. */
    suspend fun refresh() = mutex.withLock {
        val tree = folder ?: return@withLock
        withContext(Dispatchers.IO) {
            if (!hasAccess(tree)) {
                _state.update { it.copy(lostAccess = true, files = emptyList()) }
                return@withContext
            }
            _state.update { it.copy(work = Work.LISTING) }
            val files = runCatching { list(tree) }
                .onFailure { Log.w(TAG, "Could not list backups", it) }
                .getOrDefault(emptyList())
            _state.update { it.copy(files = files, work = null) }
        }
    }

    suspend fun backupNow() = mutex.withLock { backup(auto = false) }

    /**
     * After indexing stops: back up if auto backup is on, the index changed since the last
     * backup, and either nothing is left to index or the last backup is a day old (so a library
     * that takes days to index still gets saved along the way).
     */
    suspend fun autoBackupIfDue(remaining: Int) = mutex.withLock {
        if (!_state.value.auto) return@withLock
        val tree = folder ?: return@withLock
        if (!hasAccess(tree)) return@withLock
        if (signature() == prefs.getString(KEY_SIGNATURE, null)) return@withLock
        val age = System.currentTimeMillis() - prefs.getLong(KEY_LAST_AT, 0)
        if (remaining > 0 && age < DAY_MS) return@withLock
        backup(auto = true)
    }

    private suspend fun backup(auto: Boolean) = withContext(Dispatchers.IO) {
        val tree = folder ?: return@withContext
        if (!hasAccess(tree)) {
            _state.update { it.copy(lostAccess = true) }
            return@withContext
        }
        _state.update { it.copy(work = Work.BACKING_UP, message = null) }
        val name = PREFIX + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + IndexBackup.EXTENSION
        var doc: Uri? = null
        try {
            val signature = signature()
            doc = DocumentsContract.createDocument(context.contentResolver, root(tree), MIME, name)
                ?: error("The folder refused a new file")
            val header = context.contentResolver.openOutputStream(doc, "wt")!!.use { IndexBackup.export(it) }
            val now = System.currentTimeMillis()
            prefs.edit().putString(KEY_SIGNATURE, signature).putLong(KEY_LAST_AT, now).apply()
            prune(tree)
            val files = list(tree)
            _state.update {
                it.copy(
                    files = files, work = null, lastBackupAt = now,
                    message = if (auto) null else "Backed up ${count(header)}.",
                )
            }
        } catch (c: CancellationException) {
            doc?.let(::delete)
            _state.update { it.copy(work = null) }
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "Backup failed", t)
            doc?.let(::delete)
            _state.update { it.copy(work = null, message = "Couldn't back up: ${t.message ?: "the folder isn't writable"}.") }
        }
    }

    /** Puts a backup's vectors back on the photos this phone has. Returns null if it failed. */
    suspend fun restore(file: BackupFile): IndexBackup.RestoreResult? = mutex.withLock {
        withContext(Dispatchers.IO) {
            _state.update { it.copy(work = Work.RESTORING, message = null) }
            try {
                pauseIndexing()
                syncSources()
                val result = context.contentResolver.openInputStream(file.uri)!!.use {
                    IndexBackup.restore(it, activeModel(), soundsOn())
                }
                _state.update { it.copy(work = null, message = describe(result)) }
                result
            } catch (c: CancellationException) {
                _state.update { it.copy(work = null) }
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "Restore failed", t)
                _state.update { it.copy(work = null, message = "Couldn't restore: ${t.message ?: "the file couldn't be read"}.") }
                null
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun list(tree: Uri): List<BackupFile> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val found = ArrayList<Triple<Uri, String, Long>>()
        context.contentResolver.query(children, PROJECTION, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1).orEmpty()
                if (!name.startsWith(PREFIX) || !name.endsWith(IndexBackup.EXTENSION)) continue
                found += Triple(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), name, c.getLong(2))
            }
        }
        return found.mapNotNull { (uri, name, size) ->
            runCatching {
                val header = context.contentResolver.openInputStream(uri)!!.use { IndexBackup.readHeader(it) }
                BackupFile(uri, name, size, header)
            }.onFailure { Log.i(TAG, "Skipping unreadable backup $name: ${it.message}") }.getOrNull()
        }.sortedByDescending { it.header.createdAt }
    }

    /** Keeps the newest [KEEP] backups this app wrote; anything else in the folder is untouched. */
    private fun prune(tree: Uri) {
        list(tree).drop(KEEP).forEach { delete(it.uri) }
    }

    /** Changes whenever vectors are added, replaced or removed. */
    private fun signature(): String {
        val vectors = store.boxFor<PhotoVector>()
        val moments = store.boxFor<VideoMoment>()
        val newest = vectors.query().orderDesc(PhotoVector_.id).build().use { it.findFirstId() }
        return "${vectors.count()}:${moments.count()}:$newest"
    }

    private fun root(tree: Uri) = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun hasAccess(tree: Uri) = context.contentResolver.persistedUriPermissions
        .any { it.uri == tree && it.isReadPermission && it.isWritePermission }

    private fun delete(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
    }

    private fun release(tree: Uri) {
        // Don't take a grant away from a photo folder that happens to be the same tree.
        if (store.boxFor<Folder>().query(Folder_.treeUri.equal(tree.toString())).build().use { it.count() } > 0) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    private fun displayName(tree: Uri): String =
        context.contentResolver.query(root(tree), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: tree.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
            ?: "Backup folder"

    companion object {
        private const val TAG = "IndexBackups"
        const val PREFIX = "aigallery-index-"
        private const val MIME = "application/octet-stream"
        private const val KEEP = 5
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val KEY_TREE = "tree"
        private const val KEY_NAME = "name"
        private const val KEY_AUTO = "auto"
        private const val KEY_SIGNATURE = "signature"
        private const val KEY_LAST_AT = "last_at"
        private val PROJECTION = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE)

        fun count(h: IndexBackup.Header): String = buildList {
            if (h.photos > 0 || h.videos == 0) add(plural(h.photos, "photo"))
            if (h.videos > 0) add(plural(h.videos, "video"))
        }.joinToString(" and ")

        fun describe(r: IndexBackup.RestoreResult): String = buildList {
            add(if (r.restored == 0) "Nothing new to restore" else "Restored ${plural(r.restored, "item")}")
            if (r.alreadyIndexed > 0) add("${r.alreadyIndexed} already searchable")
            if (r.notFound > 0) add("${r.notFound} not on this phone")
            if (r.otherModel > 0) add("${r.otherModel} made by a different search model")
        }.joinToString(", ") + "."

        private fun plural(n: Int, word: String) =
            "${java.text.NumberFormat.getIntegerInstance().format(n)} $word${if (n == 1) "" else "s"}"
    }
}
