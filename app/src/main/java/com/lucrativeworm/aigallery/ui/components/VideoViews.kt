package com.lucrativeworm.aigallery.ui.components

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.lucrativeworm.aigallery.data.Photo
import com.lucrativeworm.aigallery.ui.theme.Motion
import com.lucrativeworm.aigallery.ui.theme.Theme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** "1:05", "12:40", "1:02:03". */
fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * A video page in the viewer. The current page shows the shared [player]; the others show their
 * poster frame so swiping stays cheap. Tap toggles the chrome, a vertical drag closes the viewer
 * like it does for photos.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPage(
    photo: Photo,
    player: Player?,
    isCurrent: Boolean,
    onTap: () -> Unit,
    onDismissDrag: (Float) -> Unit,
    onDismissEnd: (Float) -> Unit,
) {
    val context = LocalContext.current
    val tap by rememberUpdatedState(onTap)
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(photo.id) { detectTapGestures(onTap = { tap() }) }
            .draggable(
                rememberDraggableState { onDismissDrag(it) },
                Orientation.Vertical,
                onDragStopped = { onDismissEnd(it) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isCurrent && player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        this.player = player
                    }
                },
                update = { it.player = player },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize().sharedPhoto(photo.id),
            )
        } else {
            AsyncImage(
                model = remember(photo.id) { thumbRequest(context, photo, px = 1080) },
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Big play/pause in the middle of the video, shown with the chrome. */
@Composable
fun PlayPauseButton(playing: Boolean, visible: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut() + scaleOut(targetScale = 0.8f), modifier = modifier) {
        Box(
            Modifier
                .size(68.dp)
                .squish(pressedScale = 0.9f, onClick = onToggle)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
                .semantics { contentDescription = if (playing) "Pause" else "Play" },
            contentAlignment = Alignment.Center,
        ) {
            if (playing) {
                Row {
                    Box(Modifier.size(6.dp, 24.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
                    Spacer(Modifier.width(7.dp))
                    Box(Modifier.size(6.dp, 24.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
                }
            } else {
                // A right-pointing triangle, drawn with the same glyph language as the rest of the app.
                androidx.compose.foundation.Canvas(Modifier.size(26.dp).offset(x = 2.dp)) {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(size.width * 0.15f, 0f)
                        lineTo(size.width, size.height / 2f)
                        lineTo(size.width * 0.15f, size.height)
                        close()
                    }
                    drawPath(path, Color.White)
                }
            }
        }
    }
}

/**
 * Timeline for the current video, with a tick at every moment that matched the search. Drag or
 * tap anywhere to seek; tapping close to a tick snaps to it.
 */
@Composable
fun VideoScrubber(player: Player, marks: List<Long>, modifier: Modifier = Modifier) {
    val c = Theme.colors
    var position by remember { mutableLongStateOf(player.currentPosition) }
    var duration by remember { mutableLongStateOf(player.duration.coerceAtLeast(0)) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(player) {
        while (true) {
            if (!dragging) position = player.currentPosition
            duration = player.duration.takeIf { it > 0 } ?: duration
            delay(100)
        }
    }
    val density = LocalDensity.current
    val shown = if (dragging) dragFraction else if (duration > 0) position.toFloat() / duration else 0f
    val thumb by animateFloatAsState(if (dragging) 1.4f else 1f, Motion.settle(), label = "thumb")

    Row(modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(formatDuration(if (dragging) (dragFraction * duration).toLong() else position), Theme.type.numeric, Color.White.copy(alpha = 0.85f))
        Spacer(Modifier.width(12.dp))
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .height(32.dp)
                .pointerInput(duration, marks) {
                    fun seekAt(x: Float) {
                        if (duration <= 0) return
                        var target = (x / size.width).coerceIn(0f, 1f) * duration
                        // Snap to a match if the finger landed within a few dp of one.
                        val snap = with(density) { 12.dp.toPx() } / size.width * duration
                        marks.minByOrNull { kotlin.math.abs(it - target) }?.let { if (kotlin.math.abs(it - target) < snap) target = it.toFloat() }
                        player.seekTo(target.toLong())
                        position = target.toLong()
                    }
                    detectTapGestures { seekAt(it.x) }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true; dragFraction = (it.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            if (duration > 0) player.seekTo((dragFraction * duration).toLong())
                            position = (dragFraction * duration).toLong()
                            dragging = false
                        },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        change.consume()
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val width = constraints.maxWidth.toFloat()
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)))
            Box(Modifier.fillMaxWidth(shown.coerceIn(0f, 1f)).height(4.dp).clip(CircleShape).background(Color.White))
            if (duration > 0) for (m in marks) {
                val x = (m.toFloat() / duration).coerceIn(0f, 1f) * width
                Box(
                    Modifier
                        .offset { IntOffset((x - 3.dp.toPx()).roundToInt(), 0) }
                        .size(6.dp, 12.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(c.safelight),
                )
            }
            Box(
                Modifier
                    .offset { IntOffset((shown.coerceIn(0f, 1f) * width - 7.dp.toPx()).roundToInt(), 0) }
                    .size(14.dp * thumb)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
        Spacer(Modifier.width(12.dp))
        Txt(formatDuration(duration), Theme.type.numeric, Color.White.copy(alpha = 0.6f))
    }
}
