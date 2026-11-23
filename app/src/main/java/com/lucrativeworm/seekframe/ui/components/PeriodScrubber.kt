package com.lucrativeworm.seekframe.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lucrativeworm.seekframe.ui.theme.Theme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Where a period starts in the grid. [year] labels the track while scrubbing. */
data class PeriodMark(val itemIndex: Int, val label: String, val year: Int)

/**
 * A fast-scroll handle on the right edge. It appears while the library scrolls; grab it (or
 * press anywhere along the edge while it's showing) and drag to fly through the months, with a
 * bubble naming the period under your thumb and years marked along the track.
 */
@Composable
fun PeriodScrubber(
    grid: LazyGridState,
    marks: List<PeriodMark>,
    topInset: Dp,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    val c = Theme.colors
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val total by remember { derivedStateOf { grid.layoutInfo.totalItemsCount } }
    // Not worth a scrubber for a screen or two of photos.
    if (marks.size < 2 || total < 60) return

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    val scrolling = grid.isScrollInProgress
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) shown = true
        else { delay(1500); shown = false }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 120 else 400), label = "scrubAlpha")

    val scrollFraction by remember {
        derivedStateOf {
            val info = grid.layoutInfo
            val visible = info.visibleItemsInfo.size
            val span = (info.totalItemsCount - visible).coerceAtLeast(1)
            (grid.firstVisibleItemIndex.toFloat() / span).coerceIn(0f, 1f)
        }
    }
    val fraction = if (dragging) dragFraction else scrollFraction
    val markList by rememberUpdatedState(marks)
    fun markAt(f: Float): PeriodMark {
        val target = (f * (total - 1)).roundToInt()
        return markList.lastOrNull { it.itemIndex <= target } ?: markList.first()
    }
    val current = markAt(fraction)

    val thumbH = 52.dp
    val thumbW by animateDpAsState(if (dragging) 30.dp else 6.dp, tween(140), label = "thumbW")

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .padding(top = topInset, bottom = bottomInset)
            .graphicsLayer { this.alpha = alpha },
    ) {
        val trackPx = with(density) { (maxHeight - thumbH).toPx() }.coerceAtLeast(1f)
        val thumbHalf = with(density) { thumbH.toPx() / 2f }

        // Years along the track, only while scrubbing, spaced so they never collide.
        if (dragging) {
            val minGap = with(density) { 22.dp.toPx() }
            var lastY = -minGap
            var lastYear = Int.MIN_VALUE
            marks.forEach { m ->
                if (m.year == lastYear) return@forEach
                lastYear = m.year
                val y = m.itemIndex.toFloat() / (total - 1).coerceAtLeast(1) * trackPx + thumbHalf
                if (y - lastY < minGap) return@forEach
                lastY = y
                Txt(
                    m.year.toString(), Theme.type.caption, c.inkFaint,
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 38.dp)
                        .layout { measurable, constraints ->
                            val p = measurable.measure(constraints)
                            layout(p.width, p.height) { p.place(0, (y - p.height / 2f).roundToInt()) }
                        },
                )
            }
        }

        // The grab strip. Ignores touches while hidden so it never steals taps from the grid.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .width(36.dp)
                .fillMaxHeight()
                .semantics { contentDescription = "Scroll through periods" }
                .pointerInput(shown, total) {
                    if (!shown) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var lastJumpAt = 0L
                        var lastTarget = -1
                        fun jump(y: Float, force: Boolean = false) {
                            val f = ((y - thumbHalf) / trackPx).coerceIn(0f, 1f)
                            val before = markAt(dragFraction)
                            dragFraction = f
                            val newPeriod = markAt(f) != before
                            if (newPeriod) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            // Re-laying out a big library at a new spot is the expensive part, so
                            // the grid follows at most every ~120 ms (or on a new month); the
                            // handle and bubble follow the finger on every frame regardless.
                            val target = (f * (total - 1)).roundToInt()
                            val now = android.os.SystemClock.uptimeMillis()
                            if (target != lastTarget && (force || newPeriod || now - lastJumpAt > 120)) {
                                grid.requestScrollToItem(target)
                                lastTarget = target
                                lastJumpAt = now
                            }
                        }
                        down.consume()
                        dragging = true
                        dragFraction = scrollFraction
                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        jump(down.position.y)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            jump(change.position.y)
                        }
                        // Land exactly where the finger let go.
                        grid.requestScrollToItem((dragFraction * (total - 1)).roundToInt())
                        dragging = false
                    }
                },
        )

        val thumbY = fraction * trackPx
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, thumbY.roundToInt()) }
                .padding(end = 4.dp)
                .size(thumbW, thumbH)
                .shadow(if (dragging) 6.dp else 0.dp, RoundedCornerShape(50))
                .clip(RoundedCornerShape(50))
                .background(if (dragging) c.raised else c.inkFaint),
            contentAlignment = Alignment.Center,
        ) {
            if (dragging) {
                // Grip lines.
                Box(Modifier.size(12.dp, 2.dp).offset(y = (-4).dp).clip(RoundedCornerShape(50)).background(c.inkSoft))
                Box(Modifier.size(12.dp, 2.dp).clip(RoundedCornerShape(50)).background(c.inkSoft))
                Box(Modifier.size(12.dp, 2.dp).offset(y = 4.dp).clip(RoundedCornerShape(50)).background(c.inkSoft))
            }
        }

        // The period under your thumb, in a bubble left of the handle.
        if (dragging) {
            val centerY = thumbY + thumbHalf
            Txt(
                current.label, Theme.type.title, c.ink, maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 44.dp)
                    .layout { measurable, constraints ->
                        val p = measurable.measure(constraints)
                        layout(p.width, p.height) { p.place(0, (centerY - p.height / 2f).roundToInt()) }
                    }
                    .shadow(8.dp, RoundedCornerShape(50))
                    .clip(RoundedCornerShape(50))
                    .background(c.raised)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

}
