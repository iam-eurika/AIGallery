package com.lucrativeworm.seekframe.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lucrativeworm.seekframe.MainActivity
import com.lucrativeworm.seekframe.R
import com.lucrativeworm.seekframe.data.IndexBackups
import com.lucrativeworm.seekframe.data.SourceSync
import com.lucrativeworm.seekframe.di.AppEntryPoint
import com.lucrativeworm.seekframe.ml.EmbedderLoader
import com.lucrativeworm.seekframe.ml.IndexSchedule
import com.lucrativeworm.seekframe.ml.Indexer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.NumberFormat
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps indexing after the user leaves the app. It drives the same in-process [Indexer] the UI
 * uses (joining a pass that is already running), so there is never a second model in memory and
 * the grid's "searchable" count keeps moving whether the work started in the app or here.
 */
@HiltWorker
class IndexWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val indexer: Indexer,
    private val sources: SourceSync,
    private val embedderLoader: EmbedderLoader,
    private val backups: IndexBackups,
    private val scheduler: IndexScheduler,
) : CoroutineWorker(context, params) {
    private val notifications = context.getSystemService(NotificationManager::class.java)

    override suspend fun doWork(): Result {
        if (tags.contains(TAG_NEW_PHOTOS)) scheduler.watchForNewPhotos(rearm = true)
        sources.syncAll()
        if (indexer.pendingCount() == 0 || indexer.paused.value) return Result.success()
        // The user hasn't said when indexing may run yet; the app asks before the first pass.
        if (indexer.schedule.value == null) return Result.success()
        // No model yet means the user hasn't opted in to search; there is nothing to do.
        if (!embedderLoader.ensureLoaded()) return Result.success()

        promoteToForeground(indexer.progress.value)
        return try {
            coroutineScope {
                val updates = launch {
                    indexer.progress
                        .sample(1_000)
                        .distinctUntilChangedBy { it.indexed }
                        .collect { notifications.notify(NOTIFICATION_ID, buildNotification(it)) }
                }
                indexer.runUntilDone()
                updates.cancel()
                // Back up before the worker ends and the process may be let go.
                try {
                    backups.autoBackupIfDue(indexer.pendingCount())
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.w(TAG, "Auto backup failed", t)
                }
            }
            Result.success()
        } catch (c: CancellationException) {
            // Stopped by the system (unplugged, battery low, phone picked up, user tapped Pause).
            // Only halt the shared pass if nobody is looking; with the app open it keeps going as
            // before. "When idle" means exactly that, so then it always yields.
            if (!appVisible() || indexer.schedule.value == IndexSchedule.WHEN_IDLE) indexer.stop()
            throw c
        } finally {
            // A progress update can land just after the service stops; never leave it "ongoing".
            notifications.cancel(NOTIFICATION_ID)
        }
    }

    private suspend fun promoteToForeground(progress: Indexer.Progress) {
        try {
            setForeground(foregroundInfo(progress))
        } catch (t: Throwable) {
            // Android 12+ may refuse a foreground service started from the background. We still
            // get WorkManager's normal execution window, and the periodic job picks up the rest.
            Log.i(TAG, "Indexing without a foreground service: ${t.message}")
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(indexer.progress.value)

    private fun foregroundInfo(progress: Indexer.Progress): ForegroundInfo {
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        return ForegroundInfo(NOTIFICATION_ID, buildNotification(progress), type)
    }

    private fun buildNotification(p: Indexer.Progress): android.app.Notification {
        ensureChannel()
        val nf = NumberFormat.getIntegerInstance()
        val open = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Same pause as the button on the home screen: it holds until the user resumes.
        val pause = PendingIntent.getBroadcast(
            applicationContext, 1,
            Intent(applicationContext, PauseReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_index)
            .setContentTitle("Making your photos and videos searchable")
            .setContentText("${nf.format(p.indexed)} of ${nf.format(p.total)} · on this phone")
            .setProgress(p.total.coerceAtLeast(1), p.indexed, p.total == 0)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(open)
            .addAction(0, "Pause", pause)
            .build()
    }

    private fun ensureChannel() {
        if (notifications.getNotificationChannel(CHANNEL_ID) != null) return
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Photo indexing", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while photos are prepared for search"
                setShowBadge(false)
            }
        )
    }

    private suspend fun appVisible() = withContext(Dispatchers.Main) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    companion object {
        private const val TAG = "IndexWorker"
        private const val CHANNEL_ID = "indexing"
        private const val NOTIFICATION_ID = 7_001
        internal const val TAG_NEW_PHOTOS = "new-photos"
    }
}

/** All the ways background indexing gets started. Every entry point is idempotent. */
@Singleton
class IndexScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val indexer: Indexer,
) {
    private val workManager get() = WorkManager.getInstance(context)

    private fun whenIdle() = indexer.schedule.value == IndexSchedule.WHEN_IDLE

    /** Battery-not-low always; charging and device-idle too when the user chose "when idle". */
    private fun constraints(configure: Constraints.Builder.() -> Unit = {}): Constraints =
        Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .apply { if (whenIdle()) setRequiresCharging(true).setRequiresDeviceIdle(true) }
            .apply(configure)
            .build()

    /** Called once the gallery has a source (photo access or a folder): sets up the standing triggers. */
    fun install() = enqueueAll(replace = false)

    /** The user changed when indexing may run: re-issue every trigger with the new constraints. */
    fun reschedule() {
        // A run already going under the old rules is dropped; the right one is queued below.
        workManager.cancelUniqueWork(NOW)
        enqueueAll(replace = true)
    }

    private fun enqueueAll(replace: Boolean) {
        val idle = whenIdle()
        // Catch-up pass whenever the phone is charging, for big libraries and missed changes. In
        // "when idle" mode it checks hourly so it doesn't miss the overnight idle window.
        workManager.enqueueUniquePeriodicWork(
            PERIODIC,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<IndexWorker>(if (idle) 1L else 6L, TimeUnit.HOURS)
                .setConstraints(constraints { setRequiresCharging(true) })
                .build(),
        )
        enqueueNewPhotos(if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP)
    }

    /**
     * New photos (camera, screenshots, downloads) become searchable without opening the app.
     * Content-URI triggers fire once, so the worker re-arms the next one as it starts.
     */
    fun watchForNewPhotos(rearm: Boolean) =
        enqueueNewPhotos(if (rearm) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP)

    private fun enqueueNewPhotos(policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<IndexWorker>()
            .addTag(IndexWorker.TAG_NEW_PHOTOS)
            .setConstraints(
                constraints {
                    addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                    addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                    setTriggerContentUpdateDelay(30, TimeUnit.SECONDS)
                    setTriggerContentMaxDelay(5, TimeUnit.MINUTES)
                }
            )
            .build()
        workManager.enqueueUniqueWork(
            NEW_PHOTOS,
            policy,
            request,
        )
    }

    /**
     * Start (or keep) a run right now. Called while the app is visible so the worker can claim
     * its foreground service then, and carry on seamlessly after the user leaves. In "when idle"
     * mode this instead queues a run for the next time the phone is charging and idle.
     */
    fun runNow() {
        workManager.enqueueUniqueWork(
            NOW,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<IndexWorker>()
                .setConstraints(constraints())
                .build(),
        )
    }

    private companion object {
        const val NOW = "index-now"
        const val NEW_PHOTOS = "index-new-photos"
        const val PERIODIC = "index-periodic"
    }
}

/** Handles "Pause" on the progress notification. */
class PauseReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        EntryPointAccessors.fromApplication(context, AppEntryPoint::class.java).indexer().setPaused(true)
    }
}
