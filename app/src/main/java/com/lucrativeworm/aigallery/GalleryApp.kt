package com.lucrativeworm.aigallery

import android.app.Application
import android.util.Log
import com.lucrativeworm.aigallery.data.Db
import com.lucrativeworm.aigallery.data.FolderLibrary
import com.lucrativeworm.aigallery.data.MediaLibrary
import com.lucrativeworm.aigallery.ml.Embedder
import com.lucrativeworm.aigallery.ml.Indexer
import com.lucrativeworm.aigallery.ml.ModelStore
import com.lucrativeworm.aigallery.ml.SearchEngine
import com.lucrativeworm.aigallery.ml.Segmenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class GalleryApp : Application() {

    /** Hand-rolled graph; the app is small enough that a DI framework would only add weight. */
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        Db.init(this)
        graph = Graph(this)
    }

    class Graph(app: Application) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val library = MediaLibrary(app)
        val folders = FolderLibrary(app)
        val models = ModelStore(app)
        val embedder = Embedder(app)
        val indexer = Indexer(app, embedder, scope, soundsWanted = { models.sounds.value == true })
        val search = SearchEngine(embedder)
        val segmenter = Segmenter(app)

        /** Mirrors every source (media library + picked folders) into ObjectBox. */
        suspend fun syncSources() {
            runCatching { library.sync() }
                .onSuccess { Log.i("GalleryApp", "Media sync: $it (access=${library.hasAccess()})") }
                .onFailure { Log.w("GalleryApp", "Media sync failed", it) }
            runCatching { folders.syncAll() }.onFailure { Log.w("GalleryApp", "Folder sync failed", it) }
            runCatching { folders.dedupe() }
            indexer.refreshCounts()
        }

        private val loading = Mutex()

        /**
         * Loads the EmbeddingGemma 2 the user's model choice points at (Android's or the bundled
         * one), if it is ready. Shared by the UI and the background worker so whichever runs first
         * pays the load cost once per process. When the model changes, indexing pauses for the
         * swap and photos indexed by the old model are queued again: the two spaces don't mix.
         */
        suspend fun ensureEmbedder(): Boolean = loading.withLock {
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
                kotlinx.coroutines.withContext(Dispatchers.IO) { indexer.invalidateOtherModels(source.id) }
                // Whether the model can hear changes how much work there is.
                indexer.refreshCounts()
                true
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e("GalleryApp", "Could not load EmbeddingGemma 2 (${source.id})", t)
                false
            }
        }
    }
}
