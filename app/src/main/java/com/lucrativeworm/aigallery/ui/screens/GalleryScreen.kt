package com.lucrativeworm.aigallery.ui.screens

import androidx.compose.ui.draw.blur
import com.lucrativeworm.aigallery.ui.components.PeekActions
import com.lucrativeworm.aigallery.ui.components.PhotoPeek
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import com.lucrativeworm.aigallery.ui.components.PeriodMark
import com.lucrativeworm.aigallery.ui.components.PeriodScrubber
import com.lucrativeworm.aigallery.ui.components.SelectCheck
import com.lucrativeworm.aigallery.ui.components.shimmer
import com.lucrativeworm.aigallery.ui.components.SelectionActions
import com.lucrativeworm.aigallery.ui.components.SelectionTopBar
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.layout.layout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lucrativeworm.aigallery.ui.components.formatDuration
import com.lucrativeworm.aigallery.data.Folder
import com.lucrativeworm.aigallery.data.Photo
import com.lucrativeworm.aigallery.ml.Hit
import com.lucrativeworm.aigallery.ml.Indexer
import com.lucrativeworm.aigallery.ml.ModelStore
import com.lucrativeworm.aigallery.ui.SearchInput
import com.lucrativeworm.aigallery.ui.SearchPhoto
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import com.lucrativeworm.aigallery.ui.SearchUi
import com.lucrativeworm.aigallery.ui.Section
import com.lucrativeworm.aigallery.ui.components.Chip
import com.lucrativeworm.aigallery.ui.components.FilterSheet
import com.lucrativeworm.aigallery.ui.LibraryFilter
import com.lucrativeworm.aigallery.ui.MediaKind
import com.lucrativeworm.aigallery.ui.SortOrder
import com.lucrativeworm.aigallery.ui.components.FolderAccessSheet
import com.lucrativeworm.aigallery.ui.components.SourceChips
import com.lucrativeworm.aigallery.ui.components.Glyph
import com.lucrativeworm.aigallery.ui.components.GlyphIcon
import com.lucrativeworm.aigallery.ui.components.IconButton
import com.lucrativeworm.aigallery.ui.components.ProgressRing
import com.lucrativeworm.aigallery.ui.components.Txt
import com.lucrativeworm.aigallery.ui.components.lightLeak
import com.lucrativeworm.aigallery.ui.components.sharedPhoto
import com.lucrativeworm.aigallery.ui.components.squish
import com.lucrativeworm.aigallery.ui.components.thumbRequest
import com.lucrativeworm.aigallery.ui.theme.Motion
import com.lucrativeworm.aigallery.ui.theme.Theme
import kotlinx.coroutines.delay
import java.text.NumberFormat

private val SUGGESTIONS = listOf(
    "golden hour", "my dog", "food", "receipts", "snow", "people laughing",
    "city at night", "flowers", "whiteboard notes", "beach",
)

class GalleryUiState(
    val sections: List<Section>,
    val search: SearchUi,
    val input: SearchInput,
    val model: ModelStore.State,
    val indexing: Indexer.Progress,
    val mediaAccess: Boolean,
    val folders: List<Folder>,
    val scanning: Set<Long>,
    val indexingPaused: Boolean = false,
    val indexSchedule: com.lucrativeworm.aigallery.ml.IndexSchedule? = null,
    /** The library is still being read for the first time: show a skeleton, not "empty". */
    val loading: Boolean = false,
    /** The model has been looked for at least once; before that, its card would only flash. */
    val modelChecked: Boolean = true,
    /** What the library grid is narrowed to and how it's ordered. */
    val filter: LibraryFilter = LibraryFilter(),
    /** Photos/videos not yet indexed ("synced"), badged in the grid. */
    val unsynced: Set<Long> = emptySet(),
)

/** Everything the "where photos come from" chips can do. */
class SourceActions(
    val onAllowMedia: () -> Unit,
    val onPickFolder: () -> Unit,
    val onRemoveFolder: (Folder) -> Unit,
    val onPauseIndexing: (Boolean) -> Unit = {},
)

@Composable
fun GalleryScreen(
    state: GalleryUiState,
    libraryGrid: LazyGridState,
    columns: Int,
    onColumns: (Int) -> Unit,
    onOpen: (list: List<Photo>, index: Int) -> Unit,
    onLikePhoto: (Photo) -> Unit,
    onType: (String) -> Unit,
    onClearSearch: () -> Unit,
    onDownloadModel: () -> Unit,
    onAttachPhotos: (List<Uri>) -> Unit = {},
    onDetachPhoto: (SearchPhoto) -> Unit = {},
    sources: SourceActions,
    onOpenSettings: () -> Unit = {},
    askForFolder: Boolean = false,
    onAskedForFolder: () -> Unit = {},
    onSnap: () -> Unit = {},
    selection: Set<Long> = emptySet(),
    onSelectionChange: (Set<Long>) -> Unit = {},
    onShare: (List<Photo>) -> Unit = {},
    onDelete: (List<Photo>) -> Unit = {},
    onFilter: (LibraryFilter) -> Unit = {},
) {
    val c = Theme.colors
    val selecting = selection.isNotEmpty()
    // Long-press preview (iOS-style peek). Works with or without a selection.
    var peek by remember { mutableStateOf<Photo?>(null) }
    val blur by animateDpAsState(if (peek != null) 18.dp else 0.dp, tween(200), label = "peekBlur")
    var pillFocused by remember { mutableStateOf(false) }
    var folderSheet by remember { mutableStateOf(false) }
    var filterSheet by remember { mutableStateOf(false) }
    val filterScope = rememberCoroutineScope()
    LaunchedEffect(askForFolder) {
        if (askForFolder) {
            // Let the welcome → gallery transition settle first, so the tap that brought us here
            // can't land on the sheet's buttons, and the sheet rises onto a still screen.
            delay(320)
            folderSheet = true
            onAskedForFolder()
        }
    }
    val searching = state.search !is SearchUi.Idle
    val resultsGrid = rememberLazyGridState()
    val flat = remember(state.sections) { state.sections.flatMap { it.photos } }
    val alive = remember(flat) { flat.mapTo(HashSet()) { it.id } }
    var resultPhotos by remember { mutableStateOf<List<Photo>>(emptyList()) }
    // What selection acts on: the search results on screen, or the whole library.
    val pool = if (searching) resultPhotos else flat

    Box(Modifier.fillMaxSize().background(c.canvas)) {
        AnimatedContent(
            targetState = searching,
            modifier = Modifier.blur(blur),
            transitionSpec = {
                (fadeIn(tween(220, 60)) + scaleIn(initialScale = 0.985f, animationSpec = tween(260)))
                    .togetherWith(fadeOut(tween(140)))
            },
            label = "mode",
        ) { isSearching ->
            if (isSearching) {
                SearchResults(
                    state.search, state.indexing, resultsGrid, onOpen,
                    onPeek = { peek = it },
                    selection = selection,
                    onSelectionChange = onSelectionChange,
                    alive = alive,
                    onVisible = { resultPhotos = it },
                )
            } else {
                Library(state, libraryGrid, columns, onColumns, onOpen, onDownloadModel, sources, onOpenSettings, selection, onSelectionChange, onPeek = { peek = it }, onOpenFilter = { filterSheet = true }) { folderSheet = true }
            }
        }

        // Status-bar scrim so photos sliding underneath never fight with the clock.
        Box(
            Modifier.fillMaxWidth()
                .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp)
                .background(Brush.verticalGradient(listOf(c.canvas.copy(alpha = 0.92f), Color.Transparent)))
        )

        CompactHeader(
            visible = !searching && !selecting && libraryGrid.firstVisibleItemIndex > 0,
            count = state.sections.sumOf { it.photos.size },
            indexing = state.indexing,
            modelReady = state.model is ModelStore.State.Ready,
        )

        // Bottom fade + floating search, where the thumb already is.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(160.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, c.canvas.copy(alpha = 0.96f))))
        )
        AnimatedVisibility(
            visible = !selecting,
            enter = fadeIn(tween(160)) + slideInVertically { it / 2 },
            exit = fadeOut(tween(120)) + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            SearchDock(
                modifier = Modifier,
                input = state.input,
                ui = state.search,
                focused = pillFocused,
                onFocusChange = { pillFocused = it },
                onType = onType,
                onClear = onClearSearch,
                onSnap = onSnap,
                onAttach = onAttachPhotos,
                onDetach = onDetachPhoto,
            )
        }

        // Selecting: header and actions swap in for the search dock.
        val chosen = { pool.filter { it.id in selection } }
        AnimatedVisibility(
            visible = selecting,
            enter = fadeIn(tween(160)) + slideInVertically { -it },
            exit = fadeOut(tween(120)) + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            var shownCount by remember { mutableStateOf(selection.size) }
            if (selecting) shownCount = selection.size
            SelectionTopBar(
                count = shownCount,
                allSelected = pool.isNotEmpty() && selection.containsAll(pool.map { it.id }),
                onClose = { onSelectionChange(emptySet()) },
                onSelectAll = {
                    val ids = pool.map { it.id }
                    onSelectionChange(if (selection.containsAll(ids)) emptySet() else ids.toSet())
                },
            )
        }
        AnimatedVisibility(
            visible = selecting,
            enter = fadeIn(tween(160)) + slideInVertically(Motion.settle()) { it },
            exit = fadeOut(tween(120)) + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            SelectionActions(enabled = selecting, onShare = { onShare(chosen()) }, onDelete = { onDelete(chosen()) })
        }

        FilterSheet(
            visible = filterSheet,
            filter = state.filter,
            onChange = { onFilter(it); filterScope.launch { libraryGrid.scrollToItem(0) } },
            onDismiss = { filterSheet = false },
        )

        FolderAccessSheet(
            visible = folderSheet,
            onChoose = { folderSheet = false; sources.onPickFolder() },
            onDismiss = { folderSheet = false },
        )

        PhotoPeek(
            photo = peek,
            selected = peek?.id in selection,
            actions = PeekActions(
                onOpen = { p ->
                    peek = null
                    val i = pool.indexOfFirst { it.id == p.id }
                    if (i >= 0) onOpen(pool, i) else onOpen(listOf(p), 0)
                },
                onShare = { p -> peek = null; onShare(listOf(p)) },
                onSimilar = { p -> peek = null; onLikePhoto(p) },
                onDelete = { p -> peek = null; onDelete(listOf(p)) },
                onSelect = { p ->
                    peek = null
                    onSelectionChange(if (p.id in selection) selection - p.id else selection + p.id)
                },
            ),
            onDismiss = { peek = null },
        )
    }
}

// ───────────────────────────── Library ─────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Library(
    state: GalleryUiState,
    grid: LazyGridState,
    columns: Int,
    onColumns: (Int) -> Unit,
    onOpen: (List<Photo>, Int) -> Unit,
    onDownloadModel: () -> Unit,
    sources: SourceActions,
    onOpenSettings: () -> Unit,
    selection: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    onPeek: (Photo?) -> Unit,
    onOpenFilter: () -> Unit,
    onAddFolder: () -> Unit,
) {
    val flat = remember(state.sections) { state.sections.flatMap { it.photos } }
    val flatIndex = remember(flat) { flat.withIndex().associate { (i, p) -> p.id to i } }
    val selecting = selection.isNotEmpty()
    val select by rememberUpdatedState(onSelectionChange)
    // Grid item index where each month starts, for the scrubber: hero, (empty), then header + photos.
    val marks = remember(state.sections) {
        val zone = java.time.ZoneId.systemDefault()
        var i = if (flat.isEmpty()) 2 else 1
        state.sections.map { s ->
            val year = java.time.Instant.ofEpochMilli(s.photos.first().dateTaken).atZone(zone).year
            PeriodMark(i, s.label, year).also { i += 1 + s.photos.size }
        }
    }
    val drag = remember { DragSelect() }
    val peekAt by rememberUpdatedState(onPeek)
    val startIndex = remember(state.sections) {
        var acc = 0
        state.sections.associate { s -> s.key to acc.also { acc += s.photos.size } }
    }
    val corner by animateDpAsState(
        when (columns) { 2 -> 14.dp; 3 -> 8.dp; 4 -> 5.dp; else -> 3.dp }, Motion.glide(), label = "corner",
    )
    val pinch = remember { mutableFloatStateOf(1f) }
    val liveScale by animateFloatAsState(pinch.floatValue, Motion.settle(), label = "pinch")
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = grid,
        modifier = Modifier
            .fillMaxSize()
            .pinchToResize(columns, onColumns, pinch)
            .dragToSelect(drag, grid, flat, indexOfKey = { flatIndex[it as? Long] }, selection, onSelectionChange) { peekAt(null) }
            .graphicsLayer { scaleX = liveScale; scaleY = liveScale },
        contentPadding = PaddingValues(start = 3.dp, end = 3.dp, top = top + 8.dp, bottom = 140.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        item(key = "hero", span = { GridItemSpan(maxLineSpan) }, contentType = "hero") {
            LibraryHeader(flat.size, state, onDownloadModel, sources, onAddFolder, onOpenSettings, onOpenFilter)
        }
        if (state.loading) {
            item(key = "sk:h", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                Box(Modifier.fillMaxWidth().padding(start = 13.dp, end = 13.dp, top = 22.dp, bottom = 10.dp)) {
                    Box(Modifier.width(120.dp).height(20.dp).shimmer())
                }
            }
            items(columns * 7, key = { "sk:$it" }, contentType = { "skeleton" }) {
                Box(Modifier.aspectRatio(1f).shimmer(RoundedCornerShape(corner)))
            }
        } else if (flat.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) { EmptyLibrary(state.filter.kind) }
        }
        state.sections.forEach { section ->
            item(key = "h:${section.key}", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                Row(
                    Modifier.animateItem().fillMaxWidth().padding(start = 13.dp, end = 13.dp, top = 22.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Txt(section.label, Theme.type.title)
                    if (selecting) {
                        // Tick a whole month at once.
                        val ids = section.photos.map { it.id }
                        val all = selection.containsAll(ids)
                        Box(
                            Modifier.squish(pressedScale = 0.9f) { select(if (all) selection - ids.toSet() else selection + ids) }
                                .padding(start = 12.dp, top = 4.dp, bottom = 2.dp),
                        ) { SelectCheck(all) }
                    } else {
                        Txt("${section.photos.size}", Theme.type.numeric, Theme.colors.inkFaint)
                    }
                }
            }
            val base = startIndex.getValue(section.key)
            items(section.photos, key = { it.id }, contentType = { "photo" }) { photo ->
                PhotoTile(
                    photo = photo,
                    corner = corner,
                    modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = Motion.glide()),
                    selected = if (selecting) photo.id in selection else null,
                    unsynced = photo.id in state.unsynced,
                    onClick = {
                        if (selecting) select(if (photo.id in selection) selection - photo.id else selection + photo.id)
                        else onOpen(flat, base + section.photos.indexOf(photo))
                    },
                    onLongClick = {
                        val i = flatIndex[photo.id] ?: return@PhotoTile
                        drag.begin(i, photo.id, selection)
                        peekAt(photo)
                    },
                )
            }
        }
    }
    PeriodScrubber(grid, marks, topInset = top + 8.dp, bottomInset = 150.dp)
    }
}

/**
 * Long-press a photo, then keep dragging to paint a range, like ticking boxes with one stroke.
 * Holding still keeps the long-press preview; sliding onto another photo swaps it for painting.
 */
private class DragSelect {
    var anchor: Int? = null
    var base: Set<Long> = emptySet()
    var adding = true
    var painting = false

    fun begin(index: Int, photoId: Long, selection: Set<Long>) {
        adding = photoId !in selection
        base = selection
        painting = false
        anchor = index
    }
}

/** [order] is the photos in grid order; [indexOfKey] maps a grid item key to its place in [order]. */
private fun Modifier.dragToSelect(
    drag: DragSelect,
    grid: LazyGridState,
    order: List<Photo>,
    indexOfKey: (Any) -> Int?,
    selection: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    onPaintStart: () -> Unit,
): Modifier = composed {
    val currentSelection by rememberUpdatedState(selection)
    val select by rememberUpdatedState(onSelectionChange)
    val paintStart by rememberUpdatedState(onPaintStart)
    val keyToIndex by rememberUpdatedState(indexOfKey)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    pointerInput(order) {
        val edge = 72.dp.toPx()
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var autoScroll: kotlinx.coroutines.Job? = null
            var last = Offset.Unspecified
            fun paintAt(p: Offset) {
                val anchor = drag.anchor ?: return
                val hit = grid.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                    p.x >= item.offset.x && p.x < item.offset.x + item.size.width &&
                        p.y >= item.offset.y && p.y < item.offset.y + item.size.height
                } ?: return
                val at = keyToIndex(hit.key) ?: return
                if (!drag.painting) {
                    if (at == anchor) return
                    drag.painting = true
                    paintStart()
                }
                val range = order.subList(minOf(anchor, at), maxOf(anchor, at) + 1).map { it.id }
                val next = if (drag.adding) drag.base + range else drag.base - range.toSet()
                if (next != currentSelection) {
                    select(next)
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                }
            }
            fun speedAt(y: Float) = when {
                y < edge -> -(edge - y) / 3f
                y > size.height - edge -> (y - (size.height - edge)) / 3f
                else -> 0f
            }
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: break
                if (drag.anchor != null && change.pressed) {
                    // Own the gesture: no scrolling, no tile clicks.
                    change.consume()
                    last = change.position
                    paintAt(last)
                    if (speedAt(last.y) != 0f && autoScroll?.isActive != true) {
                        autoScroll = scope.launch {
                            while (true) {
                                val v = speedAt(last.y)
                                if (v == 0f) break
                                grid.scrollBy(v)
                                paintAt(last)
                                delay(16)
                            }
                        }
                    }
                }
            } while (event.changes.any { it.pressed })
            autoScroll?.cancel()
            drag.anchor = null
            drag.painting = false
        }
    }
}

/**
 * Two-finger pinch on the grid changes density (2–5 columns). The grid "gives" a little under the
 * fingers, then snaps to the new layout with a haptic tick at each step, like a detent.
 */
private fun Modifier.pinchToResize(
    columns: Int,
    onColumns: (Int) -> Unit,
    live: androidx.compose.runtime.MutableFloatState,
): Modifier = composed {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(columns)
    pointerInput(Unit) {
        awaitEachGesture {
            var zoom = 1f
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    zoom *= event.calculateZoom()
                    event.changes.forEach { it.consume() }
                    live.floatValue = zoom.coerceIn(0.94f, 1.06f)
                    val step = when {
                        zoom > 1.22f && current > 2 -> -1
                        zoom < 0.82f && current < 5 -> 1
                        else -> 0
                    }
                    if (step != 0) {
                        onColumns(current + step)
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        zoom = 1f
                    }
                }
            } while (event.changes.any { it.pressed })
            live.floatValue = 1f
        }
    }
}

@Composable
private fun LibraryHeader(
    count: Int,
    state: GalleryUiState,
    onDownload: () -> Unit,
    sources: SourceActions,
    onAddFolder: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFilter: () -> Unit,
) {
    val c = Theme.colors
    val nf = remember { NumberFormat.getIntegerInstance() }
    val filtered = !state.filter.isDefault
    val indexing = state.indexing
    val model = state.model
    Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 6.dp)) {
      Column(Modifier.padding(horizontal = 13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("Photos", Theme.type.display, modifier = Modifier.weight(1f))
            IconButton(
                Glyph.Filter,
                if (filtered) "Filter and sort, active" else "Filter and sort",
                tint = if (filtered) c.safelight else c.inkSoft,
                background = if (filtered) c.safelightSoft else Color.Transparent,
                onClick = onOpenFilter,
            )
            IconButton(Glyph.Settings, "Settings", tint = c.inkSoft, onClick = onOpenSettings)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val ready = model is ModelStore.State.Ready
            val base = when {
                state.loading -> null
                count == 0 && state.filter.kind != MediaKind.ALL -> "Nothing matches this filter"
                count == 0 -> "Nothing here yet"
                state.filter.kind == MediaKind.VIDEOS -> "${nf.format(count)} videos"
                state.filter.kind == MediaKind.PHOTOS -> "${nf.format(count)} photos, no videos"
                !ready -> "${nf.format(count)} photos"
                indexing.remaining <= 0 -> "${nf.format(count)} photos · all searchable"
                state.indexSchedule == null && !indexing.running ->
                    "${nf.format(count)} photos · ${nf.format(indexing.indexed)} searchable · not started"
                state.indexSchedule ==com.lucrativeworm.aigallery.ml.IndexSchedule.WHEN_IDLE && !indexing.running ->
                    "${nf.format(count)} photos · ${nf.format(indexing.indexed)} searchable · more overnight"
                else -> "${nf.format(count)} photos · ${nf.format(indexing.indexed)} searchable"
            }
            val caption = if (base != null && count > 0 && state.filter.sort == SortOrder.OLDEST) "$base · oldest first" else base
            if (caption == null) Box(Modifier.padding(vertical = 2.dp).width(150.dp).height(13.dp).shimmer(RoundedCornerShape(50)))
            else Txt(caption, Theme.type.caption, c.inkSoft, modifier = Modifier.weight(1f, fill = false), maxLines = 1)
            AnimatedVisibility(
                visible = ready && !state.loading && indexing.remaining > 0,
                enter = fadeIn() + scaleIn(Motion.settle(), initialScale = 0.7f),
                exit = fadeOut(tween(160)) + scaleOut(targetScale = 0.7f),
            ) {
                // "Overnight" mode with nothing running: offer to start now rather than a Pause
                // that would have nothing to pause. With no schedule chosen yet nothing can run
                // at all, so the button asks when instead.
                val unscheduled = state.indexSchedule == null && !indexing.running
                val waiting = unscheduled || (state.indexSchedule == com.lucrativeworm.aigallery.ml.IndexSchedule.WHEN_IDLE &&
                    !indexing.running && !state.indexingPaused)
                IndexingToggle(
                    progress = indexing,
                    paused = state.indexingPaused || waiting,
                    playLabel = if (waiting) "Index now" else "Resume",
                    onToggle = { sources.onPauseIndexing(if (waiting) false else !state.indexingPaused) },
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
      }
        SourceChips(
            mediaAccess = state.mediaAccess,
            folders = state.folders,
            scanning = state.scanning,
            onAllowMedia = sources.onAllowMedia,
            onAddFolder = onAddFolder,
            onRemoveFolder = sources.onRemoveFolder,
            modifier = Modifier.padding(top = 16.dp),
        )
        AnimatedVisibility(
            visible = state.modelChecked && model !is ModelStore.State.Ready,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut(tween(160)) + shrinkVertically(Motion.glide()),
        ) {
            ModelCard(model, onDownload, onOpenSettings, Modifier.padding(horizontal = 13.dp).padding(top = 18.dp))
        }
    }
}

/**
 * Pause/resume for indexing, sitting right next to the "searchable" count it controls. The ring
 * keeps showing progress; pausing stills it and swaps the bars for a play mark. The pause holds
 * across restarts and for background work too, until the user resumes.
 */
@Composable
private fun IndexingToggle(
    progress: Indexer.Progress,
    paused: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    playLabel: String = "Resume",
) {
    val c = Theme.colors
    val haptics = LocalHapticFeedback.current
    val bg by animateColorAsState(if (paused) c.safelightSoft else c.raised, tween(220), label = "bg")
    Row(
        modifier
            .squish(pressedScale = 0.9f, haptic = false) {
                haptics.performHapticFeedback(if (paused) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                onToggle()
            }
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp)
            .semantics { contentDescription = if (paused) (if (playLabel == "Resume") "Resume indexing" else playLabel) else "Pause indexing" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            ProgressRing(progress.fraction, active = progress.running && !paused, size = 22.dp, stroke = 1.75.dp)
            AnimatedContent(
                targetState = paused,
                transitionSpec = {
                    (scaleIn(Motion.settle(), initialScale = 0.3f) + fadeIn(tween(120)))
                        .togetherWith(scaleOut(tween(100), targetScale = 0.3f) + fadeOut(tween(100)))
                },
                label = "pauseGlyph",
            ) { p ->
                GlyphIcon(if (p) Glyph.Play else Glyph.Pause, if (p) c.safelight else c.inkSoft, 11.dp)
            }
        }
        Spacer(Modifier.width(6.dp))
        AnimatedContent(
            targetState = paused,
            transitionSpec = {
                (slideInVertically { it } + fadeIn(tween(160))).togetherWith(slideOutVertically { -it } + fadeOut(tween(100)))
            },
            label = "pauseLabel",
        ) { p ->
            Txt(if (p) playLabel else "Pause", Theme.type.caption, if (p) c.safelight else c.inkSoft, maxLines = 1)
        }
    }
}

/** The one-time model download, framed as "teach the gallery to see" rather than a settings chore. */
@Composable
private fun ModelCard(state: ModelStore.State, onDownload: () -> Unit, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val c = Theme.colors
    val t = Theme.type
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.raised).padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(c.safelightSoft), contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.Sparkle, c.safelight, 16.dp)
            }
            Spacer(Modifier.width(12.dp))
            Txt("Make every photo searchable", t.label)
        }
        Spacer(Modifier.height(10.dp))
        val system = when (state) {
            is ModelStore.State.Missing -> state.target == ModelStore.Target.SYSTEM
            is ModelStore.State.Downloading -> state.target == ModelStore.Target.SYSTEM
            is ModelStore.State.Failed -> state.target == ModelStore.Target.SYSTEM
            else -> false
        }
        Txt(
            when {
                state == ModelStore.State.Unsupported ->
                    "You chose Android's built-in model, but this phone doesn't have it. Pick another model in Settings to turn search on."
                system ->
                    "Android can fetch EmbeddingGemma 2 for this phone, once. After that, search works offline and your photos never leave this phone."
                else ->
                    "A one-time download of EmbeddingGemma 2 (about 390 MB, or 485 MB to also search sounds in videos). After that, search works offline and your photos never leave this phone."
            },
            t.caption, c.inkSoft,
        )
        Spacer(Modifier.height(16.dp))
        AnimatedContent(
            targetState = state,
            contentKey = { it::class },
            transitionSpec = { fadeIn(tween(200)).togetherWith(fadeOut(tween(120))) },
            label = "modelState",
        ) { s ->
            when (s) {
                is ModelStore.State.Downloading -> DownloadBar(s)
                ModelStore.State.Verifying -> Column {
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).lightLeak(c.sunken, c.safelight.copy(alpha = 0.6f)))
                    Spacer(Modifier.height(8.dp))
                    Txt("Checking the download…", t.caption, c.inkSoft)
                }
                is ModelStore.State.Failed -> Column {
                    Txt(s.message, t.caption, c.safelight)
                    Spacer(Modifier.height(10.dp))
                    PillButton("Try again", onDownload)
                }
                ModelStore.State.Unsupported -> PillButton("Open settings", onOpenSettings)
                else -> PillButton("Download", onDownload)
            }
        }
    }
}

@Composable
private fun DownloadBar(s: ModelStore.State.Downloading) {
    val c = Theme.colors
    val p by animateFloatAsState(s.fraction, Motion.gentle(), label = "dl")
    Column {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.sunken)) {
            Box(Modifier.fillMaxWidth(p).height(6.dp).clip(CircleShape).background(c.safelight))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Txt("Downloading", Theme.type.caption, c.inkSoft)
            Txt("${s.bytes / 1_000_000} / ${s.total / 1_000_000} MB", Theme.type.numeric, c.inkFaint)
        }
    }
}

@Composable
private fun PillButton(text: String, onClick: () -> Unit) {
    val c = Theme.colors
    Box(
        Modifier.squish(onClick = onClick).clip(RoundedCornerShape(50)).background(c.safelight)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) { Txt(text, Theme.type.label, c.onSafelight) }
}

@Composable
private fun EmptyLibrary(kind: MediaKind = MediaKind.ALL) {
    Column(Modifier.fillMaxWidth().padding(vertical = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        GlyphIcon(if (kind == MediaKind.ALL) Glyph.Photo else Glyph.Filter, Theme.colors.inkFaint, 40.dp)
        Spacer(Modifier.height(12.dp))
        val text = when (kind) {
            MediaKind.ALL -> "Photos you take or add will appear here"
            MediaKind.PHOTOS -> "No photos match this filter"
            MediaKind.VIDEOS -> "No videos here yet"
        }
        Txt(text, Theme.type.body, Theme.colors.inkSoft)
    }
}

@Composable
private fun CompactHeader(visible: Boolean, count: Int, indexing: Indexer.Progress, modelReady: Boolean) {
    val c = Theme.colors
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)) + slideInVertically { -it / 2 },
        exit = fadeOut(tween(120)),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 4.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(c.raised.copy(alpha = 0.94f))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt("${NumberFormat.getIntegerInstance().format(count)} photos", Theme.type.caption, c.inkSoft)
                if (modelReady && indexing.remaining > 0) {
                    Spacer(Modifier.width(8.dp))
                    ProgressRing(indexing.fraction, indexing.running, size = 11.dp, stroke = 1.5.dp)
                }
            }
        }
    }
}

// ───────────────────────────── Tiles ─────────────────────────────

@Composable
fun PhotoTile(
    photo: Photo,
    corner: Dp,
    modifier: Modifier = Modifier,
    enterDelayMs: Int = -1,
    /** null when not selecting; otherwise whether this photo is ticked. */
    selected: Boolean? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    /** For a video in search results: the moment that matched, shown and used as the thumbnail. */
    atMs: Long? = null,
    /** That moment was heard rather than seen. */
    heard: Boolean = false,
    /** Not indexed yet: badged so it's clear search can't find it until it syncs. */
    unsynced: Boolean = false,
) {
    val c = Theme.colors
    val inset by animateFloatAsState(if (selected == true) 0.86f else 1f, Motion.settle(), label = "sel")
    val context = LocalContext.current
    // Search results "develop" in sequence like prints in a tray; library tiles appear instantly.
    val enter = remember(photo.id, enterDelayMs) { Animatable(if (enterDelayMs < 0) 1f else 0f) }
    if (enterDelayMs >= 0) {
        LaunchedEffect(photo.id, enterDelayMs) {
            delay(enterDelayMs.toLong())
            enter.animateTo(1f, Motion.settle())
        }
    }
    Box(
        modifier
            .aspectRatio(1f)
            .graphicsLayer {
                val k = enter.value
                alpha = k.coerceIn(0f, 1f)
                scaleX = 0.9f + 0.1f * k; scaleY = scaleX
                translationY = (1f - k) * 18.dp.toPx()
            }
            .sharedPhoto(photo.id)
            .squish(pressedScale = 0.93f, onLongClick = onLongClick, onClick = onClick)
            .clip(RoundedCornerShape(corner))
            .background(if (photo.tint != 0) Color(photo.tint) else c.sunken),
    ) {
        AsyncImage(
            model = remember(photo.id, atMs) { thumbRequest(context, photo, frameMs = atMs) },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                // Ticked photos shrink inside their cell, like prints lifted off the sheet.
                scaleX = inset; scaleY = inset
                if (inset < 1f) {
                    clip = true
                    shape = RoundedCornerShape(corner + 6.dp)
                }
            },
        )
        if (photo.isVideo) {
            VideoBadge(
                text = (if (heard) "♪ " else "") + formatDuration(atMs ?: photo.durationMs),
                atMoment = atMs != null,
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp),
            )
        }
        AnimatedVisibility(
            visible = unsynced,
            enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.6f),
            exit = fadeOut(tween(220)) + scaleOut(targetScale = 0.6f),
            modifier = Modifier.align(Alignment.TopEnd).padding(5.dp),
        ) { UnsyncedBadge() }
        if (selected != null) {
            if (selected) Box(Modifier.fillMaxSize().background(c.safelight.copy(alpha = 0.12f)))
            SelectCheck(selected, Modifier.align(Alignment.TopStart).padding(6.dp))
        }
    }
}

/** Two chasing arrows on a dark chip: this item hasn't been indexed ("synced") yet. */
@Composable
private fun UnsyncedBadge() {
    Box(
        Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f))
            .semantics { contentDescription = "Not synced yet" },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(11.dp)) {
            val w = 1.4.dp.toPx()
            val r = size.minDimension / 2f - w
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            val box = androidx.compose.ui.geometry.Rect(center, r)
            // Two arcs with a gap each, arrowheads at their leading ends.
            for (start in listOf(-60f, 120f)) {
                drawArc(Color.White, start, 130f, false, box.topLeft, box.size, style = stroke)
                val a = Math.toRadians((start + 130f).toDouble())
                val tip = Offset(center.x + r * kotlin.math.cos(a).toFloat(), center.y + r * kotlin.math.sin(a).toFloat())
                val back = Math.toRadians((start + 130f - 90f).toDouble()) // tangent, pointing backwards along the arc
                val head = 2.6.dp.toPx()
                for (spread in listOf(-35.0, 35.0)) {
                    val d = back + Math.toRadians(spread)
                    drawLine(Color.White, tip, Offset(tip.x + head * kotlin.math.cos(d).toFloat(), tip.y + head * kotlin.math.sin(d).toFloat()), w, androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
        }
    }
}

/** Length of a video in the grid, or in search results the time of the matching moment. */
@Composable
private fun VideoBadge(text: String, atMoment: Boolean, modifier: Modifier = Modifier) {
    val c = Theme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (atMoment) c.safelight else Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(7.dp)) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, 0f); lineTo(size.width, size.height / 2f); lineTo(0f, size.height); close()
            }
            drawPath(path, if (atMoment) c.onSafelight else Color.White)
        }
        Spacer(Modifier.width(4.dp))
        Txt(text, Theme.type.numeric, if (atMoment) c.onSafelight else Color.White, maxLines = 1)
    }
}

// ───────────────────────────── Search results ─────────────────────────────

@Composable
private fun SearchResults(
    ui: SearchUi,
    indexing: Indexer.Progress,
    grid: LazyGridState,
    onOpen: (List<Photo>, Int) -> Unit,
    onPeek: (Photo?) -> Unit,
    selection: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    /** Library photos still on the device; deleted ones drop out of the results. */
    alive: Set<Long>,
    /** The photos currently on screen, in order, so "select all" and actions act on just these. */
    onVisible: (List<Photo>) -> Unit,
) {
    val c = Theme.colors
    val t = Theme.type
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val selecting = selection.isNotEmpty()
    val drag = remember { DragSelect() }
    val peekAt by rememberUpdatedState(onPeek)
    // Keep showing the previous results under the "thinking" border instead of blanking.
    var last by remember { mutableStateOf<SearchUi.Results?>(null) }
    if (ui is SearchUi.Results) last = ui
    val shown = last
    val generation = remember(shown?.input) { System.nanoTime() }

    LaunchedEffect(shown?.input) { grid.scrollToItem(0) }

    val hits = remember(shown, alive) { shown?.hits?.filter { alive.isEmpty() || it.photo.id in alive }.orEmpty() }

    // Show results a page at a time; pulling past the end and letting go reveals the next page.
    val total = hits.size
    var limit by remember(shown?.input) { mutableIntStateOf(RESULTS_PAGE) }
    var loadingMore by remember(shown?.input) { mutableStateOf(false) }
    val hasMore = limit < total
    val canLoadMore by rememberUpdatedState(hasMore && !loadingMore)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val loadMore = remember(grid, shown?.input) {
        object : NestedScrollConnection {
            private val armAt = with(density) { 56.dp.toPx() }
            private var pulled = 0f
            private var armed = false

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y < 0f && !grid.canScrollForward && canLoadMore) {
                    pulled -= available.y
                    if (!armed && pulled >= armAt) {
                        armed = true
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }
                } else if (available.y > 0f || consumed.y > 0f) {
                    pulled = 0f
                    armed = false
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val fire = armed && canLoadMore
                pulled = 0f
                armed = false
                if (fire) {
                    loadingMore = true
                    scope.launch {
                        // Let the shimmer row lay out, then bring it up from under the search bar.
                        withFrameNanos { }
                        val row = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.size?.height ?: 0
                        try { grid.animateScrollBy(row * 1.2f) } catch (_: CancellationException) {}
                        delay(LOAD_MORE_MS)
                        limit += RESULTS_PAGE
                        loadingMore = false
                    }
                }
                return Velocity.Zero
            }
        }
    }

    val visible = remember(hits, limit) { hits.take(limit) }
    val photos = remember(visible) { visible.map(Hit::photo) }
    LaunchedEffect(photos) { onVisible(photos) }
    // Grid item key -> place in [photos], for drag-to-select.
    val keyIndex = remember(photos, generation) {
        HashMap<Any, Int>(photos.size * 2).apply {
            put("best:$generation", 0)
            photos.forEachIndexed { i, p -> if (i > 0) put("r:$generation:${p.id}", i) }
        }
    }
    fun toggle(p: Photo) = onSelectionChange(if (p.id in selection) selection - p.id else selection + p.id)
    fun hold(i: Int, p: Photo) {
        drag.begin(i, p.id, selection)
        peekAt(p)
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = grid,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(loadMore)
            .dragToSelect(drag, grid, photos, indexOfKey = { keyIndex[it] }, selection, onSelectionChange) { peekAt(null) },
        contentPadding = PaddingValues(start = 3.dp, end = 3.dp, top = top + 20.dp, bottom = 150.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "q") {
            Column(Modifier.padding(horizontal = 13.dp).padding(bottom = 14.dp)) {
                when {
                    ui is SearchUi.NeedsModel -> {
                        Txt("Search needs the model", t.title)
                        Spacer(Modifier.height(6.dp))
                        Txt("Download EmbeddingGemma 2 from the top of your library to search by meaning.", t.caption, c.inkSoft)
                    }
                    shown == null -> {
                        Txt("Looking…", t.title, c.inkSoft)
                    }
                    else -> {
                        Txt(headline(shown), t.title, maxLines = 2)
                        Spacer(Modifier.height(4.dp))
                        val partial = if (indexing.remaining > 0) " · ${indexing.remaining} photos still being indexed" else ""
                        Txt("${shown.millis} ms · on device$partial", t.numeric, c.inkFaint)
                    }
                }
            }
        }
        if (ui is SearchUi.Thinking && shown == null) {
            items(12, key = { "sk$it" }) {
                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).lightLeak(c.sunken, c.raised))
            }
        }
        if (shown != null && hits.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "none") { NoMatches() }
        }
        if (shown != null && visible.isNotEmpty()) {
            val best = visible.first()
            item(span = { GridItemSpan(maxLineSpan) }, key = "best:${generation}") {
                BestMatch(
                    best,
                    selected = if (selecting) best.photo.id in selection else null,
                    onClick = { if (selecting) toggle(best.photo) else onOpen(photos, 0) },
                    onLongClick = { hold(0, best.photo) },
                )
            }
            items(visible.drop(1).withIndex().toList(), key = { "r:${generation}:${it.value.photo.id}" }) { (i, hit) ->
                PhotoTile(
                    photo = hit.photo,
                    corner = 8.dp,
                    // Each new page staggers in from its own first tile.
                    enterDelayMs = 90 + ((i + 1) % RESULTS_PAGE).coerceAtMost(18) * Motion.STAGGER_MS,
                    selected = if (selecting) hit.photo.id in selection else null,
                    onClick = { if (selecting) toggle(hit.photo) else onOpen(photos, i + 1) },
                    onLongClick = { hold(i + 1, hit.photo) },
                    atMs = hit.atMs,
                    heard = hit.heard,
                )
            }
            if (loadingMore) {
                items(minOf(6, total - limit), key = { "more:${generation}:$it" }) {
                    Box(Modifier.aspectRatio(1f).shimmer(RoundedCornerShape(8.dp)))
                }
            } else if (hasMore) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "more:${generation}") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                        Txt("Showing $limit of $total · pull up for more", t.caption, c.inkFaint)
                    }
                }
            }
        }
    }
}

private fun headline(r: SearchUi.Results): String {
    val n = r.hits.size
    val q = r.input as? SearchInput.Query ?: return ""
    val words = q.text.trim().takeIf { it.isNotEmpty() }?.let { "“$it”" }
    val photos = when (q.photos.size) {
        0 -> null
        1 -> "this photo"
        else -> "these photos"
    }
    return when {
        n == 0 && words != null && photos != null -> "Nothing like $photos with $words yet"
        n == 0 -> "Nothing like ${photos ?: words} yet"
        words != null && photos != null -> "$n like $photos, $words"
        photos != null -> "$n photos like ${if (q.photos.size == 1) "this one" else "these"}"
        else -> "$n for $words"
    }
}

@Composable
private fun BestMatch(hit: Hit, selected: Boolean?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Theme.colors
    val inset by animateFloatAsState(if (selected == true) 0.94f else 1f, Motion.settle(), label = "bestSel")
    val context = LocalContext.current
    val enter = remember(hit.photo.id) { Animatable(0f) }
    LaunchedEffect(hit.photo.id) { enter.animateTo(1f, Motion.settle()) }
    Box(
        Modifier
            .padding(bottom = 3.dp)
            .fillMaxWidth()
            .aspectRatio(1.45f)
            .graphicsLayer {
                alpha = enter.value.coerceIn(0f, 1f)
                scaleX = (0.94f + 0.06f * enter.value) * inset; scaleY = scaleX
            }
            .sharedPhoto(hit.photo.id)
            .squish(pressedScale = 0.97f, onLongClick = onLongClick, onClick = onClick)
            .clip(RoundedCornerShape(16.dp))
            .background(if (hit.photo.tint != 0) Color(hit.photo.tint) else c.sunken),
    ) {
        AsyncImage(
            model = remember(hit.photo.id, hit.atMs) { thumbRequest(context, hit.photo, px = 1080, frameMs = hit.atMs) },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Row(
            Modifier.align(Alignment.BottomStart).padding(12.dp)
                .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIcon(Glyph.Sparkle, c.safelight, 12.dp)
            Spacer(Modifier.width(6.dp))
            Txt(
                when {
                    hit.atMs == null -> "Best match"
                    hit.heard -> "Best match · heard at ${formatDuration(hit.atMs)}"
                    else -> "Best match · at ${formatDuration(hit.atMs)}"
                },
                Theme.type.caption, Color.White,
            )
        }
        if (selected != null) {
            if (selected) Box(Modifier.fillMaxSize().background(c.safelight.copy(alpha = 0.12f)))
            SelectCheck(selected, Modifier.align(Alignment.TopStart).padding(10.dp))
        }
    }
}

@Composable
private fun NoMatches() {
    val c = Theme.colors
    // A tiny shrug: the icon tilts back and forth once when nothing is found.
    val tilt = remember { Animatable(0f) }
    val reduced = Theme.reducedMotion
    LaunchedEffect(Unit) {
        if (!reduced) {
            listOf(-10f, 8f, -5f, 0f).forEach { tilt.animateTo(it, Motion.settle()) }
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.graphicsLayer { rotationZ = tilt.value }) { GlyphIcon(Glyph.Search, c.inkFaint, 36.dp) }
        Spacer(Modifier.height(12.dp))
        Txt("Try describing it differently", Theme.type.body, c.inkSoft)
    }
}

// ───────────────────────────── Search dock ─────────────────────────────

@OptIn(ExperimentalLayoutApi::class, ExperimentalAnimationApi::class)
@Composable
private fun SearchDock(
    modifier: Modifier,
    input: SearchInput,
    ui: SearchUi,
    focused: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onType: (String) -> Unit,
    onClear: () -> Unit,
    onSnap: () -> Unit,
    onAttach: (List<Uri>) -> Unit,
    onDetach: (SearchPhoto) -> Unit,
) {
    val c = Theme.colors
    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val haptics = LocalHapticFeedback.current
    val query = input as? SearchInput.Query
    val text = query?.text.orEmpty()
    val attached = query?.photos.orEmpty()
    val showSuggestions = focused && text.isEmpty()
    // While the field has focus, back only lets go of it. Some keyboards (Samsung's hide-keyboard
    // key, for one) send a back press when dismissed; without this it reached the app's handler
    // and cleared the search, or closed the app when the query was empty.
    BackHandler(enabled = focused) { focus.clearFocus() }
    // Back from the picker, put the cursor in the field so words can follow the photos.
    var picked by remember { mutableIntStateOf(0) }
    LaunchedEffect(picked) {
        if (picked > 0) {
            delay(150)
            runCatching { focusRequester.requestFocus() }
        }
    }
    val pickPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHED),
    ) { uris ->
        if (uris.isNotEmpty()) {
            onAttach(uris.take(MAX_ATTACHED - attached.size))
            picked++
        }
    }
    val openPicker = {
        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
        pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    Column(modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 14.dp, vertical = 12.dp)) {
        AnimatedVisibility(visible = showSuggestions, enter = fadeIn(tween(1)), exit = fadeOut(tween(120))) {
            // One line that scrolls sideways, bleeding past the dock's padding to the screen edges.
            val bleed = 14.dp
            Row(
                Modifier
                    .padding(bottom = 12.dp)
                    .layout { measurable, constraints ->
                        val extra = (bleed * 2).roundToPx()
                        val p = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + extra, minWidth = constraints.minWidth + extra))
                        layout(p.width - extra, p.height) { p.place(-extra / 2, 0) }
                    }
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = bleed),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SUGGESTIONS.forEachIndexed { i, s ->
                    Box(
                        Modifier.animateEnterExit(
                            enter = fadeIn(tween(220, delayMillis = i * Motion.STAGGER_MS)) +
                                slideInVertically(tween(320, delayMillis = i * Motion.STAGGER_MS)) { it },
                        )
                    ) {
                        Chip(s, onClick = { onType(s); focus.clearFocus() })
                    }
                }
            }
        }

        val width by animateFloatAsState(if (focused || input !is SearchInput.None) 1f else 0.66f, Motion.glide(), label = "w")
        val thinking = ui is SearchUi.Thinking
        val glow by animateFloatAsState(if (thinking) 1f else 0f, tween(260), label = "glow")
        // Only run the orbit while the comet is visible, and read it in the draw phase: reading it
        // here would recompose the whole search bar every frame, even when idle.
        val spin = if (Theme.reducedMotion || (!thinking && glow == 0f)) null else rememberInfiniteTransition(label = "spin").animateFloat(
            0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "a",
        )
        val shape = RoundedCornerShape(26.dp)

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxWidth(width)
                    .widthIn(min = 220.dp)
                    .height(54.dp)
                    .clip(shape)
                    .background(c.line)
                    // While EmbeddingGemma is thinking, a safelight comet orbits the pill border.
                    .drawBehind {
                        if (glow > 0f) rotate(spin?.value ?: 0f) {
                            drawCircle(
                                Brush.sweepGradient(
                                    listOf(Color.Transparent, c.safelight.copy(alpha = glow), Color.Transparent, Color.Transparent),
                                ),
                                radius = size.maxDimension,
                            )
                        }
                    }
                    .padding(1.5.dp)
                    .clip(shape)
                    .background(c.raised),
            ) {
                Row(Modifier.fillMaxSize().padding(start = if (attached.isEmpty()) 16.dp else 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (attached.isEmpty()) {
                        GlyphIcon(if (thinking) Glyph.Sparkle else Glyph.Search, if (thinking) c.safelight else c.inkSoft, 20.dp)
                        Spacer(Modifier.width(10.dp))
                    } else {
                        // Attached photos sit in the field like tokens; tap one to take it out.
                        attached.forEach { photo ->
                            key(photo.key) {
                                AttachedThumb(photo) {
                                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                    onDetach(photo)
                                }
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    Box(Modifier.weight(1f)) {
                        BasicTextField(
                            value = text,
                            onValueChange = onType,
                            singleLine = true,
                            textStyle = Theme.type.body.copy(color = c.ink),
                            cursorBrush = SolidColor(c.safelight),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                                .onFocusChanged { onFocusChange(it.isFocused) },
                            decorationBox = { inner ->
                                if (text.isEmpty()) {
                                    val hint = if (attached.isEmpty()) "Search your photos" else "Add words…"
                                    Txt(hint, Theme.type.body, c.inkFaint, maxLines = 1)
                                }
                                inner()
                            },
                        )
                    }
                    AnimatedVisibility(
                        visible = (focused || input !is SearchInput.None) && attached.size < MAX_ATTACHED,
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut(),
                    ) {
                        IconButton(Glyph.Photo, "Add photos to search", size = 42.dp, tint = c.inkSoft, onClick = openPicker)
                    }
                    AnimatedVisibility(visible = input !is SearchInput.None, enter = fadeIn() + scaleIn(), exit = fadeOut()) {
                        IconButton(Glyph.Close, "Clear search", size = 42.dp, tint = c.inkSoft) {
                            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            onClear()
                            focus.clearFocus()
                        }
                    }
                }
                // Tapping anywhere on the collapsed pill opens the keyboard.
                if (!focused && input is SearchInput.None) {
                    Box(Modifier.matchParentSize().squish(pressedScale = 0.97f) { focusRequester.requestFocus() })
                }
            }
            // Snap & circle: point the camera at something and search your photos for it.
            androidx.compose.animation.AnimatedVisibility(
                visible = !focused && input is SearchInput.None,
                enter = fadeIn(tween(200, delayMillis = 120)) + scaleIn(Motion.settle(), initialScale = 0.6f),
                exit = fadeOut(tween(100)) + scaleOut(targetScale = 0.6f),
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                IconButton(Glyph.Camera, "Snap and circle to search", size = 50.dp, tint = c.safelight, background = c.raised, onClick = onSnap)
            }
        }
    }
}

private const val MAX_ATTACHED = 3
private const val RESULTS_PAGE = 150
private const val LOAD_MORE_MS = 650L

@Composable
private fun AttachedThumb(photo: SearchPhoto, onRemove: () -> Unit) {
    val context = LocalContext.current
    val model = remember(photo.key) {
        when (photo) {
            is SearchPhoto.Library -> thumbRequest(context, photo.photo)
            is SearchPhoto.Picked -> photo.uri
        }
    }
    val enter = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, Motion.settle()) }
    Box(
        Modifier
            .size(34.dp)
            .graphicsLayer { scaleX = enter.value; scaleY = enter.value; alpha = enter.value.coerceIn(0f, 1f) }
            .squish(pressedScale = 0.9f, onClick = onRemove)
            .clip(RoundedCornerShape(8.dp))
            .background(Theme.colors.sunken)
            .semantics { contentDescription = "Attached photo. Tap to remove" },
    ) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.align(Alignment.TopEnd).padding(2.dp).size(13.dp)
                .clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(Glyph.Close, Color.White, 9.dp)
        }
    }
}

