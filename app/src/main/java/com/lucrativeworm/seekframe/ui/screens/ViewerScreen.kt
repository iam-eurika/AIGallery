package com.lucrativeworm.seekframe.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import com.lucrativeworm.seekframe.ui.components.PhotoInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import com.lucrativeworm.seekframe.ml.TextScanner
import com.lucrativeworm.seekframe.ml.TextWord
import com.lucrativeworm.seekframe.ml.joinAsText
import com.lucrativeworm.seekframe.ui.components.TextActions
import com.lucrativeworm.seekframe.ui.components.TextSelectLayer
import com.lucrativeworm.seekframe.ui.components.selectionRects
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import com.lucrativeworm.seekframe.ui.components.PlayPauseButton
import com.lucrativeworm.seekframe.ui.components.VideoPage
import com.lucrativeworm.seekframe.ui.components.VideoScrubber
import com.lucrativeworm.seekframe.data.Photo
import com.lucrativeworm.seekframe.ml.Hit
import com.lucrativeworm.seekframe.ml.QrCode
import com.lucrativeworm.seekframe.ml.QrScanner
import com.lucrativeworm.seekframe.ui.components.Glyph
import com.lucrativeworm.seekframe.ui.components.GlyphIcon
import com.lucrativeworm.seekframe.ui.components.IconButton
import com.lucrativeworm.seekframe.ui.components.QrChip
import com.lucrativeworm.seekframe.ui.components.QrOverlay
import com.lucrativeworm.seekframe.ui.components.QrSheet
import com.lucrativeworm.seekframe.ui.components.Txt
import com.lucrativeworm.seekframe.ui.components.fullRequest
import com.lucrativeworm.seekframe.ui.components.sharedPhoto
import com.lucrativeworm.seekframe.ui.components.squish
import com.lucrativeworm.seekframe.ui.components.thumbRequest
import com.lucrativeworm.seekframe.ui.theme.Motion
import com.lucrativeworm.seekframe.ui.theme.Theme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Composable
fun ViewerScreen(
    photos: List<Photo>,
    startIndex: Int,
    onDismiss: (Photo) -> Unit,
    onLikePhoto: (Photo) -> Unit,
    similar: suspend (Photo) -> List<Hit>,
    onOpenList: (List<Photo>, Int) -> Unit,
    onCircle: (Photo) -> Unit = {},
    onDelete: (Photo) -> Unit = {},
    /** Where to start each video, e.g. the moment a search matched. */
    seekTo: Map<Long, Long> = emptyMap(),
    /** Matching moments per video, ticked on its timeline. */
    marks: Map<Long, List<Long>> = emptyMap(),
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val pager = rememberPagerState(startIndex) { photos.size }
    val current = photos.getOrNull(pager.currentPage.coerceAtMost(photos.lastIndex)) ?: return
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    val dismiss = remember { Animatable(0f) }
    val threshold = with(density) { 120.dp.toPx() }
    val progress = (abs(dismiss.value) / (threshold * 2.5f)).coerceIn(0f, 1f)
    var crossedThreshold by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var openQr by remember { mutableStateOf<QrCode?>(null) }
    // QR codes in the photo on screen; scanned once the page settles, cached per photo.
    var qr by remember(current.id) { mutableStateOf(QrScanner.cached(current.id).orEmpty()) }
    LaunchedEffect(current.id) {
        if (current.isVideo) return@LaunchedEffect
        if (QrScanner.cached(current.id) == null) delay(350)
        qr = QrScanner.scan(context, current.id, Uri.parse(current.uri))
    }

    // One player for whichever video is on screen; it follows the pager.
    val player = remember { ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_OFF } }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(current.id) {
        if (!current.isVideo) {
            player.stop()
            player.clearMediaItems()
            return@LaunchedEffect
        }
        player.setMediaItem(MediaItem.fromUri(Uri.parse(current.uri)))
        // A little before the match, so the moment itself isn't cut off.
        val start = seekTo[current.id]?.let { (it - SEEK_LEAD_MS).coerceAtLeast(0) } ?: 0L
        player.seekTo(start)
        player.prepare()
        player.playWhenReady = true
    }

    ImmersiveWhen(!chrome)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Details live beneath the photo: slide it up to reveal them.
        val infoMax = constraints.maxHeight * 0.62f
        val reveal = remember { Animatable(0f) }
        val infoOpen = reveal.value > 1f
        fun settleInfo(velocity: Float = 0f) {
            val target = when {
                velocity < -700f -> infoMax
                velocity > 700f -> 0f
                reveal.value > infoMax / 2f -> infoMax
                else -> 0f
            }
            scope.launch { reveal.animateTo(target, Motion.glide(), initialVelocity = -velocity) }
        }
        fun dragInfo(dy: Float) {
            scope.launch { reveal.snapTo((reveal.value - dy).coerceIn(0f, infoMax)) }
        }
        BackHandler(enabled = infoOpen) { scope.launch { reveal.animateTo(0f, Motion.glide()) } }
        // The backdrop thins out as you pull, revealing that the grid is right underneath.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - progress }.background(Color.Black))

        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1,
            pageSpacing = 16.dp,
            key = { photos[it].id },
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, (dismiss.value - reveal.value).toInt()) }
                .graphicsLayer {
                    val s = 1f - progress * 0.25f
                    scaleX = s; scaleY = s
                },
        ) { page ->
            if (photos[page].isVideo) {
                VideoPage(
                    photo = photos[page],
                    player = player,
                    isCurrent = page == pager.currentPage,
                    onTap = { if (reveal.value > 1f) settleInfo(1000f) else chrome = !chrome },
                    onDismissDrag = { dy ->
                        scope.launch { dismiss.snapTo(dismiss.value + dy) }
                        val past = abs(dismiss.value) > threshold
                        if (past != crossedThreshold) {
                            crossedThreshold = past
                            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        }
                    },
                    onDismissEnd = { vy ->
                        crossedThreshold = false
                        if (abs(dismiss.value) > threshold || abs(vy) > 1800f) onDismiss(photos[pager.currentPage])
                        else scope.launch { dismiss.animateTo(0f, Motion.settle()) }
                    },
                )
                return@HorizontalPager
            }
            ZoomablePhoto(
                photo = photos[page],
                isCurrent = page == pager.currentPage,
                qrCodes = if (page == pager.currentPage) qr else emptyList(),
                showQr = chrome && progress < 0.05f,
                onQrTap = { openQr = it },
                onTap = { if (reveal.value > 1f) settleInfo(1000f) else chrome = !chrome },
                infoOpen = { reveal.value > 1f },
                onInfoDrag = ::dragInfo,
                onInfoEnd = ::settleInfo,
                onZoomChanged = { if (page == pager.currentPage) zoomed = it },
                onDismissDrag = { dy ->
                    scope.launch { dismiss.snapTo(dismiss.value + dy) }
                    val past = abs(dismiss.value) > threshold
                    if (past != crossedThreshold) {
                        crossedThreshold = past
                        // A detent you can feel: tick when the release would close the photo.
                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    }
                },
                onDismissEnd = { vy ->
                    crossedThreshold = false
                    if (abs(dismiss.value) > threshold || abs(vy) > 1800f) onDismiss(photos[pager.currentPage])
                    else scope.launch { dismiss.animateTo(0f, Motion.settle()) }
                },
            )
        }

        if (current.isVideo) {
            PlayPauseButton(
                playing = playing,
                visible = chrome && progress < 0.05f && !infoOpen,
                onToggle = { if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() } },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        AnimatedVisibility(
            visible = chrome && progress < 0.05f,
            enter = fadeIn(tween(180)) + slideInVertically { -it / 3 },
            exit = fadeOut(tween(140)) + slideOutVertically { -it / 3 },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopBar(current, onBack = { onDismiss(current) })
        }

        AnimatedVisibility(
            visible = chrome && progress < 0.05f && !zoomed && !infoOpen,
            enter = fadeIn(tween(180)) + slideInVertically { it / 3 },
            exit = fadeOut(tween(140)) + slideOutVertically { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BottomBar(
                photo = current,
                qrCodes = qr,
                onQrTap = { openQr = it },
                similar = similar,
                onLikePhoto = { onLikePhoto(current) },
                onOpenList = onOpenList,
                onCircle = { onCircle(current) },
                onInfo = { scope.launch { reveal.animateTo(infoMax, Motion.glide()) } },
                onDelete = { onDelete(current) },
                player = if (current.isVideo) player else null,
                marks = marks[current.id].orEmpty(),
            )
        }

        if (reveal.value > 0.5f) {
            val panelScroll = rememberScrollState()
            val nested = remember(infoMax) {
                object : NestedScrollConnection {
                    // At the top of the details, pulling down slides the photo back over them.
                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                        if (available.y > 0f && source == NestedScrollSource.UserInput) { dragInfo(available.y); return Offset(0f, available.y) }
                        return Offset.Zero
                    }
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        if (available.y < 0f && reveal.value < infoMax - 0.5f) { dragInfo(available.y); return Offset(0f, available.y) }
                        return Offset.Zero
                    }
                    override suspend fun onPreFling(available: Velocity): Velocity {
                        if (reveal.value > 0.5f && reveal.value < infoMax - 0.5f) { settleInfo(available.y); return available }
                        return Velocity.Zero
                    }
                }
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(with(density) { infoMax.toDp() })
                    .offset { IntOffset(0, (infoMax - reveal.value).toInt()) }
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(Theme.colors.canvas)
                    .nestedScroll(nested),
            ) {
                Box(
                    Modifier.fillMaxWidth().height(26.dp)
                        .draggable(rememberDraggableState { dragInfo(it) }, Orientation.Vertical, onDragStopped = { settleInfo(it) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(50)).background(Theme.colors.line))
                }
                PhotoInfo(current, Modifier.fillMaxWidth().verticalScroll(panelScroll).navigationBarsPadding())
            }
        }

        QrSheet(openQr, onDismiss = { openQr = null })
    }
}

@Composable
private fun ZoomablePhoto(
    photo: Photo,
    isCurrent: Boolean,
    qrCodes: List<QrCode>,
    showQr: Boolean,
    onQrTap: (QrCode) -> Unit,
    onTap: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    onDismissDrag: (Float) -> Unit,
    onDismissEnd: (Float) -> Unit,
    infoOpen: () -> Boolean = { false },
    onInfoDrag: (Float) -> Unit = {},
    onInfoEnd: (Float) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scale = remember { Animatable(1f) }
    val pan = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var ratio by remember(photo.id) {
        mutableFloatStateOf(if (photo.width > 0 && photo.height > 0) photo.width.toFloat() / photo.height else 1f)
    }
    val tap by rememberUpdatedState(onTap)
    val zoomChanged by rememberUpdatedState(onZoomChanged)
    val drag by rememberUpdatedState(onDismissDrag)
    val dragEnd by rememberUpdatedState(onDismissEnd)
    val isInfoOpen by rememberUpdatedState(infoOpen)
    val infoDrag by rememberUpdatedState(onInfoDrag)
    val infoEnd by rememberUpdatedState(onInfoEnd)

    LaunchedEffect(isCurrent) {
        if (!isCurrent) { scale.snapTo(1f); pan.snapTo(Offset.Zero) }
    }

    // Text in the photo: recognized shortly after the page settles (or on the first long-press),
    // so pressing on text highlights it right away.
    var words by remember(photo.id) { mutableStateOf(TextScanner.cached(photo.id)) }
    var textSel by remember(photo.id) { mutableStateOf<IntRange?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var suppressTapUntil by remember { mutableLongStateOf(0L) }
    var innerSize by remember { mutableStateOf(IntSize.Zero) }
    var pillSize by remember { mutableStateOf(IntSize.Zero) }
    val loadWords: suspend () -> List<TextWord> = {
        TextScanner.scan(context, photo.id, Uri.parse(photo.uri)).also { words = it }
    }
    LaunchedEffect(isCurrent, photo.id) {
        if (!isCurrent) textSel = null
        else if (words == null) { delay(800); loadWords() }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(photo.id) {
                detectTapGestures(
                    onTap = {
                        when {
                            // The release that ends a long-press isn't a tap.
                            SystemClock.uptimeMillis() < suppressTapUntil -> Unit
                            textSel != null -> textSel = null
                            else -> tap()
                        }
                    },
                    onDoubleTap = { at ->
                        scope.launch {
                            if (scale.value > 1.05f) {
                                launch { scale.animateTo(1f, Motion.glide()) }
                                pan.animateTo(Offset.Zero, Motion.glide())
                                zoomChanged(false)
                            } else {
                                val target = 2.5f
                                val center = Offset(size.width / 2f, size.height / 2f)
                                launch { scale.animateTo(target, Motion.glide()) }
                                pan.animateTo((center - at) * (target - 1f), Motion.glide())
                                zoomChanged(true)
                            }
                        }
                    },
                )
            }
            .pointerInput(photo.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var decided = false
                    var dismissing = false
                    var revealing = false
                    var travel = Offset.Zero
                    val velocity = VelocityTracker()
                    do {
                        val event = awaitPointerEvent()
                        if (selecting) continue // a text selection drag owns this gesture
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || scale.value > 1.01f) {
                            val z = event.calculateZoom()
                            val p = event.calculatePan()
                            val centroid = event.calculateCentroid()
                            if (z != 1f || p != Offset.Zero) {
                                val newScale = (scale.value * z).coerceIn(1f, 5f)
                                val k = newScale / scale.value
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val raw = pan.value * k + (centroid - center) * (1f - k) + p
                                val maxX = size.width * (newScale - 1f) / 2f
                                val maxY = size.height * (newScale - 1f) / 2f
                                val clamped = Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
                                scope.launch { scale.snapTo(newScale); pan.snapTo(clamped) }
                                zoomChanged(newScale > 1.01f)
                                event.changes.forEach { it.consume() }
                            }
                        } else if (fingers == 1) {
                            val change = event.changes.first { it.pressed }
                            travel += change.positionChange()
                            if (!decided && travel.getDistance() > viewConfiguration.touchSlop) {
                                decided = true
                                if (abs(travel.y) > abs(travel.x) * 1.3f) {
                                    // Up (or anything while details show) moves the details; down closes.
                                    if (isInfoOpen() || travel.y < 0f) revealing = true else dismissing = true
                                }
                            }
                            if (revealing) {
                                velocity.addPosition(change.uptimeMillis, change.position)
                                infoDrag(change.positionChange().y)
                                change.consume()
                            }
                            if (dismissing) {
                                velocity.addPosition(change.uptimeMillis, change.position)
                                drag(change.positionChange().y)
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (dismissing) dragEnd(velocity.calculateVelocity().y)
                    if (revealing) infoEnd(velocity.calculateVelocity().y)
                    if (scale.value < 1.05f && scale.value != 1f) {
                        scope.launch { scale.animateTo(1f, Motion.settle()) }
                        scope.launch { pan.animateTo(Offset.Zero, Motion.settle()) }
                        zoomChanged(false)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val wide = ratio > maxWidth / maxHeight
        Box(
            (if (wide) Modifier.fillMaxWidth().aspectRatio(ratio) else Modifier.fillMaxHeight().aspectRatio(ratio, matchHeightConstraintsFirst = true))
                .then(if (isCurrent) Modifier.sharedPhoto(photo.id) else Modifier)
                .onSizeChanged { innerSize = it }
                .graphicsLayer {
                    scaleX = scale.value; scaleY = scale.value
                    translationX = pan.value.x; translationY = pan.value.y
                },
        ) {
            AsyncImage(
                model = remember(photo.id) { fullRequest(context, photo) },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { s ->
                    val size = s.painter.intrinsicSize
                    if (size.width > 0 && size.height > 0) ratio = size.width / size.height
                },
            )
            // Below the QR brackets, so tapping a code still opens it.
            TextSelectLayer(
                words = words,
                selection = textSel,
                loadWords = loadWords,
                onSelection = { textSel = it },
                onSelecting = {
                    selecting = it
                    suppressTapUntil = if (it) Long.MAX_VALUE else SystemClock.uptimeMillis() + 300
                },
            )
            if (qrCodes.isNotEmpty()) QrOverlay(qrCodes, visible = showQr, onTap = onQrTap)
        }

        val ws = words
        val sel = textSel
        if (ws != null && sel != null && sel.last < ws.size && innerSize != IntSize.Zero) {
            val rects = remember(ws, sel, innerSize) { selectionRects(ws, sel, innerSize) }
            val outerW = constraints.maxWidth.toFloat()
            val outerH = constraints.maxHeight.toFloat()
            val gap = with(density) { 14.dp.toPx() }
            val safeTop = with(density) { 96.dp.toPx() }
            TextActions(
                onCopy = {
                    val text = ws.subList(sel.first, sel.last + 1).joinAsText()
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Text from photo", text))
                    // Android 13+ shows its own confirmation.
                    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    textSel = null
                },
                onSelectAll = if (sel != ws.indices) { { textSel = ws.indices } } else null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .onSizeChanged { pillSize = it }
                    .offset {
                        // Selection corners from the zoom layer to screen: scaled about the center, then panned.
                        val ic = Offset(innerSize.width / 2f, innerSize.height / 2f)
                        val oc = Offset(outerW / 2f, outerH / 2f)
                        fun screen(p: Offset) = oc + (p - ic) * scale.value + pan.value
                        val top = screen(Offset(0f, rects.minOf { it.top })).y
                        val bottom = screen(Offset(0f, rects.maxOf { it.bottom })).y
                        val cx = screen(Offset((rects.minOf { it.left } + rects.maxOf { it.right }) / 2f, 0f)).x
                        val above = top - gap - pillSize.height
                        val y = if (above >= safeTop) above else (bottom + gap).coerceAtMost(outerH - pillSize.height - safeTop)
                        val x = (cx - pillSize.width / 2f).coerceIn(gap, (outerW - pillSize.width - gap).coerceAtLeast(gap))
                        IntOffset(x.roundToInt(), y.roundToInt())
                    },
            )
        }
    }
}

@Composable
private fun TopBar(photo: Photo, onBack: () -> Unit) {
    val date = remember(photo.id) {
        DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy · HH:mm")
            .format(Instant.ofEpochMilli(photo.dateTaken).atZone(ZoneId.systemDefault()))
    }
    Row(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
            .statusBarsPadding()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(Glyph.Back, "Back", tint = Color.White, onClick = onBack)
        Spacer(Modifier.width(4.dp))
        Column {
            Txt(date, Theme.type.label, Color.White, maxLines = 1)
            if (photo.album.isNotBlank()) Txt(photo.album, Theme.type.caption, Color.White.copy(alpha = 0.65f), maxLines = 1)
        }
    }
}

@Composable
private fun BottomBar(
    photo: Photo,
    qrCodes: List<QrCode>,
    onQrTap: (QrCode) -> Unit,
    similar: suspend (Photo) -> List<Hit>,
    onLikePhoto: () -> Unit,
    onOpenList: (List<Photo>, Int) -> Unit,
    onCircle: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
    /** Set for videos: the timeline sits above the actions. */
    player: Player? = null,
    marks: List<Long> = emptyList(),
) {
    val c = Theme.colors
    val context = LocalContext.current
    var related by remember(photo.id) { mutableStateOf<List<Photo>>(emptyList()) }
    LaunchedEffect(photo.id) {
        delay(320) // let the page settle before asking the model, so swiping stays smooth
        related = similar(photo).map { it.photo }.take(12)
    }

    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
            .navigationBarsPadding()
            .padding(bottom = 10.dp, top = 28.dp),
    ) {
        AnimatedVisibility(visible = qrCodes.isNotEmpty(), enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
            qrCodes.firstOrNull()?.let { first ->
                Box(Modifier.fillMaxWidth().padding(bottom = 14.dp), contentAlignment = Alignment.Center) {
                    QrChip(first, qrCodes.size, onClick = { onQrTap(first) })
                }
            }
        }
        AnimatedVisibility(visible = related.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
            Column {
                Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(Glyph.Sparkle, c.safelight, 12.dp)
                    Spacer(Modifier.width(6.dp))
                    Txt("More like this", Theme.type.caption, Color.White.copy(alpha = 0.8f))
                }
                Spacer(Modifier.height(10.dp))
                // Rabbit-hole strip: each tap re-roots the viewer on a related set.
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(related, key = { _, p -> p.id }) { i, p ->
                        val enter = remember(p.id) { Animatable(0f) }
                        LaunchedEffect(p.id) {
                            delay(i * 40L)
                            enter.animateTo(1f, Motion.settle())
                        }
                        AsyncImage(
                            model = remember(p.id) { thumbRequest(context, p) },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .graphicsLayer {
                                    alpha = enter.value.coerceIn(0f, 1f)
                                    translationY = (1 - enter.value) * 24.dp.toPx()
                                }
                                .size(56.dp)
                                .squish(pressedScale = 0.9f) { onOpenList(listOf(photo) + related, i + 1) }
                                .clip(RoundedCornerShape(10.dp)),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
        }
        if (player != null) {
            VideoScrubber(player, marks, Modifier.padding(bottom = 10.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            val slot = Modifier.weight(1f)
            // Circling needs a still image; videos skip it.
            if (!photo.isVideo) Action(Glyph.Lasso, "Circle", slot, onCircle)
            Action(Glyph.Similar, "Find similar", slot, onLikePhoto)
            Action(Glyph.Share, "Share", slot) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = photo.mimeType.ifBlank { "image/*" }
                    putExtra(Intent.EXTRA_STREAM, Uri.parse(photo.uri))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, null))
            }
            Action(Glyph.Info, "Info", slot, onInfo)
            Action(Glyph.Trash, "Delete", slot, onDelete)
        }
    }
}

@Composable
private fun Action(glyph: Glyph, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.squish(pressedScale = 0.88f, onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlyphIcon(glyph, Color.White, 24.dp)
        Spacer(Modifier.height(4.dp))
        Txt(label, Theme.type.caption, Color.White.copy(alpha = 0.8f), maxLines = 1)
    }
}

/** Hides system bars while the photo is shown alone, so nothing competes with it. */
@Composable
private fun ImmersiveWhen(immersive: Boolean) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return
    val controller = remember(window) { WindowCompat.getInsetsController(window, view) }
    LaunchedEffect(immersive) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }
    DisposableEffect(Unit) {
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** Videos opened from a search start this long before the matched moment. */
private const val SEEK_LEAD_MS = 1_000L
