package com.lucrativeworm.aigallery.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lucrativeworm.aigallery.GalleryApp
import com.lucrativeworm.aigallery.data.Photo
import com.lucrativeworm.aigallery.ml.Hit
import com.lucrativeworm.aigallery.ml.Lift
import com.lucrativeworm.aigallery.ml.Lifter
import com.lucrativeworm.aigallery.ml.Stickers
import com.lucrativeworm.aigallery.ml.decodeArgb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** What the user is circling in: a photo from the library, or a fresh camera snap. */
sealed interface CircleSource {
    val uri: Uri
    data class FromPhoto(val photo: Photo) : CircleSource { override val uri: Uri get() = Uri.parse(photo.uri) }
    data class FromSnap(override val uri: Uri) : CircleSource
}

sealed interface Finds {
    data object None : Finds
    data object NeedsModel : Finds
    data object Searching : Finds
    data class Found(val hits: List<Hit>, val millis: Long) : Finds
}

data class CircleState(
    val source: CircleSource? = null,
    val image: Bitmap? = null,
    val failed: Boolean = false,
    /** True while MagicTouch is working out what was circled. */
    val lifting: Boolean = false,
    val lift: Lift? = null,
    /** Bumped on every new lift so the UI can replay its "pop" even for the same object. */
    val liftId: Int = 0,
    val finds: Finds = Finds.None,
)

class CircleViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = (app as GalleryApp).graph
    private val _state = MutableStateFlow(CircleState())
    val state: StateFlow<CircleState> = _state.asStateFlow()
    private var work: Job? = null
    /** A smaller copy for MagicTouch: masks come back at input size, and 768 px is plenty. */
    private var segInput: Pair<Bitmap, Bitmap>? = null

    private fun segmenterInput(image: Bitmap): Bitmap {
        segInput?.let { (src, small) -> if (src === image) return small }
        val k = SEG_PX / max(image.width, image.height).toFloat()
        val small = if (k >= 1f) image
        else Bitmap.createScaledBitmap(image, (image.width * k).toInt(), (image.height * k).toInt(), true)
        segInput = image to small
        return small
    }

    fun open(source: CircleSource) {
        if (_state.value.source == source && _state.value.image != null) return
        work?.cancel()
        _state.value = CircleState(source = source)
        work = viewModelScope.launch {
            val image = runCatching { decode(source.uri) }
                .onFailure { Log.e(TAG, "Could not open ${source.uri}", it) }
                .getOrNull()
            _state.update { if (image == null) it.copy(failed = true) else it.copy(image = image) }
        }
    }

    /** A finger path in normalized image coordinates. */
    fun circle(points: List<Pair<Float, Float>>) {
        val image = _state.value.image ?: return
        if (points.isEmpty()) return
        work?.cancel()
        _state.update { it.copy(lifting = true) }
        work = viewModelScope.launch {
            try {
                val gesture = Lifter.gesture(points, image.width / image.height.toFloat())
                val mask = graph.segmenter.segment(segmenterInput(image), gesture)
                val lift = withContext(Dispatchers.Default) { Lifter.lift(image, mask, gesture) }
                _state.update { it.copy(lifting = false, lift = lift, liftId = it.liftId + 1) }
                find(lift.query)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "Lift failed", t)
                _state.update { it.copy(lifting = false) }
            }
        }
    }

    /** Skip circling: search with the whole picture. */
    fun searchWhole() {
        val image = _state.value.image ?: return
        work?.cancel()
        _state.update { it.copy(lift = null, lifting = false) }
        work = viewModelScope.launch { find(image) }
    }

    fun clearLift() {
        work?.cancel()
        _state.update { it.copy(lift = null, lifting = false, finds = Finds.None) }
    }

    private suspend fun find(query: Bitmap) {
        if (!graph.embedder.isReady && !graph.ensureEmbedder()) {
            _state.update { it.copy(finds = Finds.NeedsModel) }
            return
        }
        _state.update { it.copy(finds = Finds.Searching) }
        val start = SystemClock.elapsedRealtime()
        val hits = runCatching { graph.search.searchByImage(query) }
            .onFailure { if (it is CancellationException) throw it; Log.e(TAG, "Image search failed", it) }
            .getOrDefault(emptyList())
            // A photo is always its own best match; showing it back would be noise.
            .filter { hit -> (state.value.source as? CircleSource.FromPhoto)?.photo?.id != hit.photo.id }
        _state.update { it.copy(finds = Finds.Found(hits, SystemClock.elapsedRealtime() - start)) }
    }

    /** Returns true if the system already showed its own copy confirmation. */
    suspend fun copyLift(): Boolean? {
        val lift = _state.value.lift ?: return null
        return runCatching { Stickers.copy(getApplication(), lift.cutout) }
            .onFailure { Log.e(TAG, "Copy failed", it) }.getOrNull()
    }

    fun shareLift() {
        val lift = _state.value.lift ?: return
        viewModelScope.launch { runCatching { Stickers.share(getApplication(), lift.cutout) } }
    }

    fun close() {
        work?.cancel()
        _state.value = CircleState()
        viewModelScope.launch { graph.segmenter.forget() }
    }

    private suspend fun decode(uri: Uri): Bitmap =
        decodeArgb(getApplication<Application>().contentResolver, uri, MAX_PX)

    private companion object {
        const val TAG = "CircleSearch"
        const val MAX_PX = 1280
        const val SEG_PX = 768
    }
}
