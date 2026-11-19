package com.lucrativeworm.aigallery.ui

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lucrativeworm.aigallery.GalleryApp
import android.net.Uri
import com.lucrativeworm.aigallery.data.Db
import com.lucrativeworm.aigallery.data.Folder
import com.lucrativeworm.aigallery.data.Folder_
import com.lucrativeworm.aigallery.data.Photo
import com.lucrativeworm.aigallery.data.Photo_
import com.lucrativeworm.aigallery.data.IndexState
import com.lucrativeworm.aigallery.data.asFlow
import com.lucrativeworm.aigallery.ml.Hit
import com.lucrativeworm.aigallery.ml.IndexSchedule
import com.lucrativeworm.aigallery.ml.ModelChoice
import com.lucrativeworm.aigallery.ml.ModelStore
import com.lucrativeworm.aigallery.ml.decodeArgb
import com.lucrativeworm.aigallery.ml.SystemModelStatus
import com.lucrativeworm.aigallery.work.IndexScheduler
import io.objectbox.kotlin.boxFor
import io.objectbox.query.QueryBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A photo attached to the search field: one from the library, or one picked with the system picker. */
sealed interface SearchPhoto {
    val key: String

    data class Library(val photo: Photo) : SearchPhoto {
        override val key get() = "photo:${photo.id}"
    }

    data class Picked(val uri: Uri) : SearchPhoto {
        override val key get() = "uri:$uri"
    }
}

sealed interface SearchInput {
    data object None : SearchInput

    /** Words, attached photos, or both: "photos like these, but at night". */
    data class Query(val text: String = "", val photos: List<SearchPhoto> = emptyList()) : SearchInput {
        val isEmpty get() = text.isBlank() && photos.isEmpty()
    }
}

sealed interface SearchUi {
    data object Idle : SearchUi
    data object NeedsModel : SearchUi
    data class Thinking(val input: SearchInput) : SearchUi
    data class Results(val input: SearchInput, val hits: List<Hit>, val millis: Long) : SearchUi
}

/** What the library grid shows: which kind of media, and in which order. */
enum class MediaKind { ALL, PHOTOS, VIDEOS }

enum class SortOrder { NEWEST, OLDEST }

data class LibraryFilter(val kind: MediaKind = MediaKind.ALL, val sort: SortOrder = SortOrder.NEWEST) {
    val isDefault get() = this == LibraryFilter()
}

/** A month of photos, pre-grouped off the main thread so the grid only lays out. */
data class Section(val key: String, val label: String, val photos: List<Photo>)

/** [photos] arrive newest first from the database. */
internal fun applyFilter(photos: List<Photo>, f: LibraryFilter): List<Photo> {
    val kept = when (f.kind) {
        MediaKind.ALL -> photos
        MediaKind.PHOTOS -> photos.filter { !it.isVideo }
        MediaKind.VIDEOS -> photos.filter { it.isVideo }
    }
    return if (f.sort == SortOrder.OLDEST) kept.asReversed() else kept
}

internal fun groupByMonth(
    photos: List<Photo>,
    zone: ZoneId = ZoneId.systemDefault(),
    now: YearMonth = YearMonth.now(zone),
): List<Section> {
    val fmtYear = DateTimeFormatter.ofPattern("MMMM yyyy")
    val fmtMonth = DateTimeFormatter.ofPattern("MMMM")
    return photos.groupBy { YearMonth.from(Instant.ofEpochMilli(it.dateTaken).atZone(zone)) }
        .map { (ym, list) ->
            val label = when {
                ym == now -> "This month"
                ym == now.minusMonths(1) -> "Last month"
                ym.year == now.year -> ym.format(fmtMonth)
                else -> ym.format(fmtYear)
            }
            Section(ym.toString(), label, list)
        }
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = (app as GalleryApp).graph

    val modelState: StateFlow<ModelStore.State> = graph.models.state
    val modelChoice: StateFlow<ModelChoice> = graph.models.choice
    val systemModel: StateFlow<SystemModelStatus> = graph.models.systemStatus
    val embedderBackend: StateFlow<String?> = graph.embedder.model

    fun bundledDownloaded(): Boolean = graph.models.bundledDownloaded()

    /** Whether sounds in videos are searchable; null until the user has answered. */
    val sounds: StateFlow<Boolean?> = graph.models.sounds
    val soundModelMissing: StateFlow<Boolean> = graph.models.soundModelMissing
    val soundDownload: StateFlow<ModelStore.State?> = graph.models.soundDownload

    fun modelHears(): Boolean = graph.embedder.supportsAudio

    /** What saying yes to sounds would cost right now, in a few words for the question. */
    fun soundsCost(): String {
        val active = graph.embedder.modelId
        return when {
            graph.embedder.supportsAudio -> "Nothing to download: the model on this phone already hears."
            graph.models.bundledFile(com.lucrativeworm.aigallery.ml.Variant.FULL) != null -> "Nothing to download."
            active == null -> "The model download grows from about ${com.lucrativeworm.aigallery.ml.Variant.VISION.approxMb} MB to ${com.lucrativeworm.aigallery.ml.Variant.FULL.approxMb} MB."
            active.startsWith("mlkit:") -> "Android's built-in model can't hear, so the app downloads its own (about ${com.lucrativeworm.aigallery.ml.Variant.FULL.approxMb} MB) and prepares your library again with it."
            else -> "It replaces the downloaded model with a larger one (about ${com.lucrativeworm.aigallery.ml.Variant.FULL.approxMb} MB) and prepares your library again."
        }
    }

    /**
     * The user's answer to sounds. Yes fetches the model that can hear (unless it's already here
     * or the first download hasn't happened, which then fetches it directly); no forgets any
     * soundtrack vectors. Either way the answer sticks and can be changed in Settings.
     */
    fun setSounds(on: Boolean) {
        val before = graph.models.sounds.value
        graph.models.setSounds(on)
        viewModelScope.launch {
            if (!on && before == true) graph.indexer.forgetSounds()
            graph.models.locate()
            if (on && graph.models.soundModelMissing.value) downloadSoundModel()
            loadModel()
            graph.indexer.refreshCounts()
        }
    }

    private var soundJob: Job? = null

    fun downloadSoundModel() {
        if (soundJob?.isActive == true) return
        soundJob = viewModelScope.launch {
            runCatching { graph.models.downloadSoundModel() }
                .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Sound model download failed", it) }
            loadModel()
            graph.indexer.refreshCounts()
        }
    }

    /** Settings: switch models. Loads the new one if it's ready and re-indexes what it must. */
    fun setModelChoice(choice: ModelChoice) {
        if (graph.models.choice.value == choice) return
        graph.models.setChoice(choice)
        viewModelScope.launch { loadModel() }
    }
    val indexing = graph.indexer.progress
    val indexingPaused = graph.indexer.paused

    fun setIndexingPaused(paused: Boolean) {
        graph.indexer.setPaused(paused)
        if (!paused) startIndexing(now = true)
    }

    val indexSchedule: StateFlow<IndexSchedule?> = graph.indexer.schedule

    /**
     * True when indexing is ready to begin but the user hasn't said when it may run. Asked once,
     * right before the first pass, so it never starts in the middle of their day unannounced.
     */
    val needsIndexSchedule: StateFlow<Boolean> =
        combine(graph.indexer.schedule, graph.embedder.ready, graph.indexer.progress) { schedule, ready, progress ->
            schedule == null && ready && progress.remaining > 0
        }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setIndexSchedule(schedule: IndexSchedule) {
        if (graph.indexer.schedule.value == schedule) return
        graph.indexer.setSchedule(schedule)
        IndexScheduler.reschedule(getApplication())
        // Switching to "when idle" while a pass is running in the app: hand it to the idle job.
        if (schedule == IndexSchedule.WHEN_IDLE) graph.indexer.stop()
        startIndexing()
    }

    private val allPhotos = Db.store.boxFor<Photo>().query()
        .order(Photo_.dateTaken, QueryBuilder.DESCENDING)
        .build()
        .asFlow()
        // Shared: both the grid and the "not yet synced" badges read it.
        .shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    /**
     * Ids of photos/videos still waiting to be indexed, for the badge on their tiles. Kept apart
     * from [sections] (which ignores index-state changes) so a batch finishing only touches badges.
     */
    val unsynced: StateFlow<Set<Long>> = allPhotos
        .map { photos -> photos.filter { it.indexState == IndexState.PENDING }.mapTo(HashSet()) { it.id } as Set<Long> }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val uiPrefs = app.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)

    /** Library filter and sort, remembered across launches. */
    val filter = MutableStateFlow(
        LibraryFilter(
            kind = MediaKind.entries.getOrNull(uiPrefs.getInt(KEY_KIND, 0)) ?: MediaKind.ALL,
            sort = SortOrder.entries.getOrNull(uiPrefs.getInt(KEY_SORT, 0)) ?: SortOrder.NEWEST,
        )
    )

    fun setFilter(f: LibraryFilter) {
        filter.value = f
        uiPrefs.edit().putInt(KEY_KIND, f.kind.ordinal).putInt(KEY_SORT, f.sort.ordinal).apply()
    }

    /** null until the database has answered once. */
    private val loadedSections: StateFlow<List<Section>?> = combine(
        allPhotos
            // Indexing rewrites rows (index state, tint) without changing what the grid shows;
            // skip those so the whole library isn't regrouped and recomposed on every batch.
            .distinctUntilChanged { a, b ->
                a.size == b.size && a.indices.all { a[it].id == b[it].id && a[it].dateTaken == b[it].dateTaken && a[it].uri == b[it].uri && a[it].isVideo == b[it].isVideo }
            },
        filter,
    ) { photos, f -> groupByMonth(applyFilter(photos, f)) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val sections: StateFlow<List<Section>> = loadedSections
        .map { it.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The first walk of the media library and folders has finished. */
    private val synced = MutableStateFlow(false)

    /**
     * True while the library is still being read for the first time. An empty library only counts
     * as empty once the first sync is done; a stored library shows as soon as the database answers.
     */
    val libraryLoading: StateFlow<Boolean> = combine(loadedSections, synced) { s, done ->
        s == null || (s.isEmpty() && !done)
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** False until the model choice has been checked once, so "download the model" never flashes. */
    val modelChecked: StateFlow<Boolean> = graph.models.checked

    val input = MutableStateFlow<SearchInput>(SearchInput.None)

    private val readyModel = modelState.map { (it as? ModelStore.State.Ready)?.source?.id }.distinctUntilChanged()
    private val embedderReady = graph.embedder.ready

    val search: StateFlow<SearchUi> = combine(input, embedderReady) { i, ready -> i to ready }
        // Wait out typing; attaching or removing a photo searches right away.
        .debounce { (i, _) -> if (i is SearchInput.Query && i.text.isNotBlank()) 260L else 0L }
        .distinctUntilChanged()
        .transformLatest { (i, ready) ->
            val isEmpty = i !is SearchInput.Query || i.isEmpty
            when {
                isEmpty -> emit(SearchUi.Idle)
                !ready -> emit(SearchUi.NeedsModel)
                else -> {
                    emit(SearchUi.Thinking(i))
                    val start = SystemClock.elapsedRealtime()
                    val hits = try {
                        when (i) {
                            is SearchInput.Query -> {
                                val images = i.photos.mapNotNull { vectorFor(it) }
                                val exclude = i.photos.mapNotNullTo(HashSet()) { (it as? SearchPhoto.Library)?.photo?.id }
                                graph.search.searchMixed(images, i.text, exclude)
                            }
                            SearchInput.None -> emptyList()
                        }
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        Log.e(TAG, "Search failed", t)
                        emptyList()
                    }
                    emit(SearchUi.Results(i, hits, SystemClock.elapsedRealtime() - start))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchUi.Idle)

    private var started = false
    private var downloadJob: Job? = null

    /** Called once photo access is granted. Browsing works immediately; search lights up later. */
    fun start() {
        if (started) return
        started = true
        IndexScheduler.install(getApplication())
        viewModelScope.launch {
            graph.syncSources()
            synced.value = true
            loadModel()
        }
        viewModelScope.launch {
            graph.library.changes().debounce(1_200).collect {
                runCatching { graph.library.sync() }
                runCatching { graph.folders.dedupe() }
                graph.indexer.refreshCounts()
                startIndexing()
            }
        }
        viewModelScope.launch {
            readyModel.collect { id -> if (id != null && graph.embedder.modelId != id) loadModel() }
        }
    }

    val folders: StateFlow<List<Folder>> = Db.store.boxFor<Folder>().query()
        .order(Folder_.addedAt)
        .build()
        .asFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val scanningFolders = graph.folders.scanning

    /** The user picked a folder in the system picker: take the grant, walk it, index it. */
    fun addFolder(treeUri: Uri) {
        viewModelScope.launch {
            val folder = runCatching { graph.folders.add(treeUri) }
                .onFailure { Log.e(TAG, "Could not keep access to $treeUri", it) }
                .getOrNull() ?: return@launch
            runCatching { graph.folders.sync(folder) }
            graph.indexer.refreshCounts()
            startIndexing()
        }
    }

    fun removeFolder(folder: Folder) {
        viewModelScope.launch {
            graph.folders.remove(folder)
            graph.indexer.refreshCounts()
        }
    }

    /** Photo access was granted or revoked: bring the media-library side up to date. */
    fun onMediaAccessChanged() {
        if (!started) return
        viewModelScope.launch {
            runCatching { graph.library.sync() }
            runCatching { graph.folders.dedupe() }
            graph.indexer.refreshCounts()
            startIndexing()
        }
    }

    /** Re-walk folders on return to the app; files may have been added while we were away. */
    fun refreshFolders() {
        if (!started) return
        viewModelScope.launch {
            runCatching { graph.folders.syncAll() }
            graph.indexer.refreshCounts()
            startIndexing()
        }
    }

    fun downloadModel() {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            runCatching { graph.models.download() }
        }
    }

    private suspend fun loadModel() {
        if (graph.ensureEmbedder()) startIndexing()
    }

    /**
     * Index in-process right away, and hand the same pass to WorkManager so it survives leaving.
     * If the user chose "when idle", only queue the job: it runs once the phone is charging and
     * idle. Until they've chosen, nothing starts (the app asks first).
     *
     * [now] is the user tapping Resume / Index now: that runs a pass in the app right away even
     * in "when idle" mode, so choosing overnight never locks them out of indexing during the day.
     */
    private fun startIndexing(now: Boolean = false) {
        if (!graph.embedder.isReady) return
        when (graph.indexer.schedule.value) {
            null -> return
            IndexSchedule.WHEN_IDLE -> if (now) graph.indexer.start()
            IndexSchedule.ANYTIME -> graph.indexer.start()
        }
        if (graph.indexer.pendingCount() > 0) IndexScheduler.runNow(getApplication())
    }

    fun type(text: String) {
        val photos = (input.value as? SearchInput.Query)?.photos.orEmpty()
        setQuery(SearchInput.Query(text, photos))
    }

    /** Photos picked to search with; whatever has been typed stays. */
    fun attachPhotos(uris: List<Uri>) {
        val current = input.value as? SearchInput.Query ?: SearchInput.Query()
        val added = uris.map { SearchPhoto.Picked(it) }
        val photos = (current.photos + added).distinctBy { it.key }.take(MAX_ATTACHED)
        setQuery(current.copy(photos = photos))
    }

    fun detachPhoto(photo: SearchPhoto) {
        val current = input.value as? SearchInput.Query ?: return
        setQuery(current.copy(photos = current.photos.filterNot { it.key == photo.key }))
    }

    private fun setQuery(q: SearchInput.Query) {
        input.value = if (q.text.isEmpty() && q.photos.isEmpty()) SearchInput.None else q
    }

    /** Image vectors for attached photos, so typing more words never re-embeds them. */
    private val attachedVectors = HashMap<String, FloatArray>()

    private suspend fun vectorFor(photo: SearchPhoto): FloatArray? {
        val key = "${graph.embedder.modelId}:${photo.key}"
        attachedVectors[key]?.let { return it }
        // An indexed library photo already has its vector; anything else is embedded on the spot.
        val stored = (photo as? SearchPhoto.Library)?.let { graph.search.storedVector(it.photo.id) }
        val vector = stored ?: runCatching {
            val uri = when (photo) {
                is SearchPhoto.Library -> Uri.parse(photo.photo.uri)
                is SearchPhoto.Picked -> photo.uri
            }
            val bitmap = decodeArgb(getApplication<Application>().contentResolver, uri, EMBED_PX)
            graph.embedder.embedImage(bitmap)
        }.onFailure { if (it is CancellationException) throw it; Log.e(TAG, "Could not read attached photo", it) }
            .getOrNull() ?: return null
        if (attachedVectors.size > 32) attachedVectors.clear()
        attachedVectors[key] = vector
        return vector
    }

    /** Deletes picked-folder photos for good; returns how many couldn't be deleted. */
    suspend fun deleteFolderPhotos(list: List<Photo>): Int {
        val failed = graph.folders.delete(list)
        graph.indexer.refreshCounts()
        return failed
    }

    /** "Find similar": search with just this photo; words can be added after. */
    fun likePhoto(photo: Photo) {
        input.value = SearchInput.Query(photos = listOf(SearchPhoto.Library(photo)))
    }

    fun clearSearch() {
        input.value = SearchInput.None
    }

    suspend fun similar(photo: Photo): List<Hit> =
        if (graph.embedder.isReady) runCatching { graph.search.similarTo(photo.id) }.getOrDefault(emptyList()) else emptyList()

    private companion object {
        const val TAG = "GalleryVM"
        const val MAX_ATTACHED = 3
        const val EMBED_PX = 1024
        const val KEY_KIND = "filter_kind"
        const val KEY_SORT = "filter_sort"
    }
}
