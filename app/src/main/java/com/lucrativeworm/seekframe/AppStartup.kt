package com.lucrativeworm.seekframe

import android.util.Log
import com.lucrativeworm.seekframe.data.IndexBackups
import com.lucrativeworm.seekframe.di.ApplicationScope
import com.lucrativeworm.seekframe.ml.Indexer
import com.lucrativeworm.seekframe.ml.ModelStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Process-wide background work that starts with the app, run once from [GalleryApp.onCreate]. */
@Singleton
class AppStartup @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
    private val models: ModelStore,
    private val indexer: Indexer,
    private val backups: IndexBackups,
) {
    fun start() {
        // Whether Android offers its speech model decides the default speech engine.
        scope.launch {
            runCatching { models.checkAndroidSpeech() }
                .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Speech check failed", it) }
        }
        // Whenever an indexing pass stops (done, paused, or stopped by the system), save the
        // index if auto backup is on and there is something new.
        scope.launch {
            indexer.progress.map { it.running }.distinctUntilChanged().drop(1).filter { !it }.collect {
                runCatching { backups.autoBackupIfDue(indexer.pendingCount()) }
                    .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Auto backup failed", it) }
            }
        }
    }

    private companion object {
        const val TAG = "GalleryApp"
    }
}
