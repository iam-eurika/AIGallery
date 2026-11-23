package com.lucrativeworm.seekframe.ml

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Loads the EmbeddingGemma 2 the user's model choice points at (Android's or the bundled one), if
 * it is ready. Shared by the UI and the background worker so whichever runs first pays the load
 * cost once per process. When the model changes, indexing pauses for the swap and photos indexed
 * by the old model are queued again: the two spaces don't mix.
 */
@Singleton
class EmbedderLoader @Inject constructor(
    private val models: ModelStore,
    private val embedder: Embedder,
    private val indexer: Indexer,
) {
    private val loading = Mutex()

    suspend fun ensureLoaded(): Boolean = loading.withLock {
        val source = models.locate()
        if (source == null) {
            // The chosen model isn't here (yet): don't keep searching with a different one.
            if (embedder.isReady) {
                indexer.stopAndJoin()
                embedder.close()
            }
            return@withLock false
        }
        if (embedder.isReady && embedder.modelId == source.id) return@withLock true
        return@withLock try {
            indexer.stopAndJoin()
            embedder.load(source)
            // Can touch every row in the library: never on the main thread.
            withContext(Dispatchers.IO) { indexer.invalidateOtherModels(source.id) }
            // Whether the model can hear changes how much work there is.
            indexer.refreshCounts()
            true
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "Could not load EmbeddingGemma 2 (${source.id})", t)
            false
        }
    }

    private companion object {
        const val TAG = "EmbedderLoader"
    }
}
