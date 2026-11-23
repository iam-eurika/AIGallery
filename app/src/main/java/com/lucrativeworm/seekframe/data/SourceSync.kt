package com.lucrativeworm.seekframe.data

import android.util.Log
import com.lucrativeworm.seekframe.ml.Indexer
import javax.inject.Inject
import javax.inject.Singleton

/** Mirrors every source (media library + picked folders) into ObjectBox. */
@Singleton
class SourceSync @Inject constructor(
    private val library: MediaLibrary,
    private val folders: FolderLibrary,
    private val indexer: Indexer,
) {
    suspend fun syncAll() {
        runCatching { library.sync() }
            .onSuccess { Log.i(TAG, "Media sync: $it (access=${library.hasAccess()})") }
            .onFailure { Log.w(TAG, "Media sync failed", it) }
        runCatching { folders.syncAll() }.onFailure { Log.w(TAG, "Folder sync failed", it) }
        runCatching { folders.dedupe() }
        indexer.refreshCounts()
    }

    private companion object {
        const val TAG = "SourceSync"
    }
}
