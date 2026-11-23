package com.lucrativeworm.seekframe.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lucrativeworm.seekframe.ml.TextWord
import com.lucrativeworm.seekframe.ui.theme.Theme
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A word's rectangle in pixels of a layer that is [size] big. */
private fun TextWord.rectIn(size: IntSize) =
    Rect(bounds.left * size.width, bounds.top * size.height, bounds.right * size.width, bounds.bottom * size.height)

/** Index of the word closest to [p] (pixels), or -1 if none is within [reach]. */
private fun nearestWord(words: List<TextWord>, p: Offset, size: IntSize, reach: Float): Int {
    var best = -1
    var bestD = Float.MAX_VALUE
    words.forEachIndexed { i, w ->
        val r = w.rectIn(size)
        val dx = max(0f, max(r.left - p.x, p.x - r.right))
        val dy = max(0f, max(r.top - p.y, p.y - r.bottom))
        // Being off the line counts more than being off the end of it, so drags stay on a line.
        val d = hypot(dx, dy * 1.6f)
        if (d < bestD) { bestD = d; best = i }
    }
    return if (bestD <= reach) best else -1
}

/** One highlight rectangle per selected line, in pixels of a [size] layer. */
fun selectionRects(words: List<TextWord>, selection: IntRange, size: IntSize): List<Rect> =
    words.subList(selection.first, selection.last + 1)
        .groupBy { it.line }
        .values.map { line -> line.map { it.rectIn(size) }.reduce { a, b -> a.union(b) } }

private fun Rect.union(o: Rect) = Rect(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))

/**
 * Lives inside the photo's zoom layer, so highlights stay glued to the text. Long-press on text
 * selects its paragraph; keep dragging to select word by word from where you pressed.
 */
@Composable
fun TextSelectLayer(
    words: List<TextWord>?,
    selection: IntRange?,
    loadWords: suspend () -> List<TextWord>,
    onSelection: (IntRange?) -> Unit,
    onSelecting: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Theme.colors
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(words)
    val selected by rememberUpdatedState(selection)
    val select by rememberUpdatedState(onSelection)
    val selecting by rememberUpdatedState(onSelecting)
    val load by rememberUpdatedState(loadWords)

    Box(
        modifier.fillMaxSize().pointerInput(Unit) {
            var anchor = -1
            var paragraph: IntRange? = null
            val reach = 24.dp.toPx()
            detectDragGesturesAfterLongPress(
                onDragStart = { p ->
                    anchor = -1
                    selecting(true)
                    scope.launch {
                        val ws = current ?: load()
                        val i = nearestWord(ws, p, size, reach)
                        if (i < 0) return@launch
                        anchor = i
                        val block = ws[i].block
                        val first = ws.indexOfFirst { it.block == block }
                        val last = ws.indexOfLast { it.block == block }
                        paragraph = first..last
                        select(paragraph)
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
                onDrag = { change, _ ->
                    change.consume()
                    val ws = current ?: return@detectDragGesturesAfterLongPress
                    if (anchor < 0) return@detectDragGesturesAfterLongPress
                    val j = nearestWord(ws, change.position, size, Float.MAX_VALUE)
                    if (j < 0 || (j == anchor && paragraph != null)) return@detectDragGesturesAfterLongPress
                    paragraph = null
                    val range = min(anchor, j)..max(anchor, j)
                    if (range != selected) {
                        select(range)
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                },
                onDragEnd = { selecting(false) },
                onDragCancel = { selecting(false) },
            )
        },
    ) {
        val ws = words
        if (ws != null && selection != null && selection.last < ws.size) {
            Canvas(Modifier.fillMaxSize()) {
                val pad = 3.dp.toPx()
                val sz = IntSize(size.width.toInt(), size.height.toInt())
                selectionRects(ws, selection, sz).forEach { r ->
                    drawRoundRect(
                        c.safelight.copy(alpha = 0.38f),
                        topLeft = Offset(r.left - pad, r.top - pad),
                        size = androidx.compose.ui.geometry.Size(r.width + pad * 2, r.height + pad * 2),
                        cornerRadius = CornerRadius(pad * 1.5f),
                    )
                }
            }
        }
    }
}

/** The little floating bar over a text selection. */
@Composable
fun TextActions(onCopy: () -> Unit, onSelectAll: (() -> Unit)?, modifier: Modifier = Modifier) {
    val c = Theme.colors
    Row(
        modifier
            .shadow(10.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(c.raised)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.squish(pressedScale = 0.92f, onClick = onCopy).clip(RoundedCornerShape(50)).background(c.safelight)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIcon(Glyph.Copy, c.onSafelight, 16.dp)
            Spacer(Modifier.width(6.dp))
            Txt("Copy", Theme.type.label, c.onSafelight)
        }
        if (onSelectAll != null) {
            Box(Modifier.squish(pressedScale = 0.92f, onClick = onSelectAll).padding(horizontal = 14.dp, vertical = 9.dp)) {
                Txt("Select all", Theme.type.label, c.ink)
            }
        }
    }
}
