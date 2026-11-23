package com.lucrativeworm.seekframe.di

import android.content.Context
import com.lucrativeworm.seekframe.data.Db
import com.lucrativeworm.seekframe.data.IndexBackups
import com.lucrativeworm.seekframe.data.SourceSync
import com.lucrativeworm.seekframe.ml.Embedder
import com.lucrativeworm.seekframe.ml.Indexer
import com.lucrativeworm.seekframe.ml.ModelStore
import com.lucrativeworm.seekframe.ml.Speech
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.objectbox.BoxStore
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** A scope that lives as long as the process: work that must outlast any one screen. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Bindings that can't be expressed with an `@Inject` constructor: the database, the app-wide
 * coroutine scope, and the two services configured with callbacks into other services.
 * Everything else ([Embedder], [ModelStore], the libraries, search…) is constructor-injected.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Opened once per process. [Db] keeps a handle too, for the few top-level helpers that use it. */
    @Provides
    @Singleton
    fun boxStore(@ApplicationContext context: Context): BoxStore {
        Db.init(context)
        return Db.store
    }

    @Provides
    @Singleton
    fun indexer(
        @ApplicationContext context: Context,
        store: BoxStore,
        embedder: Embedder,
        @ApplicationScope scope: CoroutineScope,
        models: ModelStore,
        speech: Speech,
    ): Indexer = Indexer(
        context,
        store,
        embedder,
        scope,
        soundsWanted = { models.sounds.value == true },
        speech = speech,
    )

    @Provides
    @Singleton
    fun indexBackups(
        @ApplicationContext context: Context,
        store: BoxStore,
        embedder: Embedder,
        models: ModelStore,
        indexer: Indexer,
        sources: SourceSync,
    ): IndexBackups = IndexBackups(
        context,
        store,
        activeModel = { embedder.modelId },
        soundsOn = { models.sounds.value == true },
        pauseIndexing = { indexer.stopAndJoin() },
        syncSources = { sources.syncAll() },
    )
}
