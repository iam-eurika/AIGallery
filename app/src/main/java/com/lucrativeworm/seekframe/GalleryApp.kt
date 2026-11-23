package com.lucrativeworm.seekframe

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * The app's singletons (database, model, indexer, search, backups…) live in Hilt's
 * SingletonComponent; see [com.lucrativeworm.seekframe.di.AppModule]. WorkManager is initialised
 * on demand with Hilt's factory so [com.lucrativeworm.seekframe.work.IndexWorker] gets the same
 * instances as the UI.
 */
@HiltAndroidApp
class GalleryApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var startup: AppStartup

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        startup.start()
    }
}
