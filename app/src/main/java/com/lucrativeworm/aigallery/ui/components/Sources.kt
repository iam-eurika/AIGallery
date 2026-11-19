package com.lucrativeworm.aigallery.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lucrativeworm.aigallery.data.Folder
import com.lucrativeworm.aigallery.ui.theme.Motion
import com.lucrativeworm.aigallery.ui.theme.Theme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import kotlin.math.roundToInt

/** A small orbiting arc for "working, no known end" (folder walks). */
@Composable
fun Spinner(size: Dp = 14.dp, color: Color = Theme.colors.safelight) {
    val angle = if (Theme.reducedMotion) 0f else rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "a",
    ).value
    Canvas(Modifier.size(size)) {
        rotate(angle) {
            drawArc(color, 0f, 250f, false, style = Stroke(1.75.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

/**
 * Where the gallery's photos come from, as a row of chips under the title. Sources are always
 * visible and always revocable from here, so access never feels like a one-way door.
 */
@Composable
fun SourceChips(
    mediaAccess: Boolean,
    folders: List<Folder>,
    scanning: Set<Long>,
    onAllowMedia: () -> Unit,
    onAddFolder: () -> Unit,
    onRemoveFolder: (Folder) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Theme.colors
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "media") {
            SourcePill(Modifier.animateItem(), onClick = if (mediaAccess) null else onAllowMedia) {
                GlyphIcon(if (mediaAccess) Glyph.Check else Glyph.Photo, if (mediaAccess) c.safelight else c.inkSoft, 15.dp)
                Spacer(Modifier.width(7.dp))
                Txt(if (mediaAccess) "Photo library" else "Allow photo library", Theme.type.caption, c.inkSoft)
            }
        }
        items(folders, key = { it.id }) { folder ->
            FolderChip(folder, folder.id in scanning, onRemove = { onRemoveFolder(folder) }, modifier = Modifier.animateItem())
        }
        item(key = "add") {
            SourcePill(Modifier.animateItem(), dashed = true, onClick = onAddFolder) {
                GlyphIcon(Glyph.Plus, c.safelight, 15.dp)
                Spacer(Modifier.width(6.dp))
                Txt("Add folder", Theme.type.caption, c.ink)
            }
        }
    }
}

@Composable
private fun SourcePill(
    modifier: Modifier = Modifier,
    background: Color = Theme.colors.raised,
    dashed: Boolean = false,
    onClick: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    val c = Theme.colors
    Row(
        modifier
            .then(if (onClick != null) Modifier.squish(pressedScale = 0.92f, onClick = onClick) else Modifier)
            .clip(RoundedCornerShape(50))
            .background(if (dashed) c.safelightSoft else background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/**
 * A folder source. Removing is two taps on the same spot: ✕ turns the chip amber and asks
 * "Remove?", a second tap confirms; leave it and it quietly reverts after a few seconds.
 * No modal dialog, and no way to remove a folder by accident.
 */
@Composable
private fun FolderChip(folder: Folder, scanning: Boolean, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val c = Theme.colors
    val haptics = LocalHapticFeedback.current
    var confirming by remember { mutableStateOf(false) }
    val nudge = remember { Animatable(0f) }
    LaunchedEffect(confirming) {
        if (confirming) {
            // A quick sideways shiver says "are you sure?" without words.
            listOf(5f, -4f, 2f, 0f).forEach { nudge.animateTo(it, spring(dampingRatio = 0.4f, stiffness = 2200f)) }
            delay(3_000)
            confirming = false
        }
    }
    val bg by animateColorAsState(if (confirming) c.safelight else c.raised, tween(180), label = "bg")
    val fg = if (confirming) c.onSafelight else c.inkSoft

    Row(
        modifier
            .graphicsLayer { translationX = nudge.value.dp.toPx() }
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = confirming,
            transitionSpec = { (fadeIn(tween(160)) + slideInVertically { it / 2 }).togetherWith(fadeOut(tween(100))) },
            label = "chip",
        ) { asking ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                if (asking) {
                    Txt("Remove ${folder.name}?", Theme.type.caption, fg, maxLines = 1)
                } else {
                    if (scanning) Spinner() else GlyphIcon(Glyph.Folder, c.inkSoft, 15.dp)
                    Spacer(Modifier.width(7.dp))
                    Txt(folder.name, Theme.type.caption, fg, maxLines = 1)
                    if (!scanning) {
                        Spacer(Modifier.width(6.dp))
                        Txt(NumberFormat.getIntegerInstance().format(folder.photoCount), Theme.type.numeric, c.inkFaint)
                    }
                }
            }
        }
        Box(
            Modifier.size(30.dp).clip(CircleShape).squish(pressedScale = 0.8f) {
                if (confirming) {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onRemove()
                } else {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    confirming = true
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(if (confirming) Glyph.Check else Glyph.Close, fg, 14.dp)
        }
    }
}

/**
 * Asks before opening the system folder picker, in plain words: what we'll get, what we won't,
 * and how to take it back. The system picker that follows is the actual grant.
 */
@Composable
fun FolderAccessSheet(visible: Boolean, onChoose: () -> Unit, onDismiss: () -> Unit) {
    val c = Theme.colors
    val t = Theme.type
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    val density = LocalDensity.current
    LaunchedEffect(visible) { if (visible) drag.snapTo(0f) }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(180))) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .squish(pressedScale = 1f, haptic = false, onClick = onDismiss)
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(dampingRatio = 0.82f, stiffness = 420f)) { it },
            exit = slideOutVertically(tween(200)) { it },
        ) {
            Column(
                Modifier
                    .offset { IntOffset(0, drag.value.roundToInt()) }
                    // Pull down to dismiss; it follows the finger and springs back if you let go early.
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { d -> scope.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) } },
                        onDragStopped = { v ->
                            if (drag.value > with(density) { 120.dp.toPx() } || v > 1500f) onDismiss()
                            else drag.animateTo(0f, Motion.settle())
                        },
                    )
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(c.raised)
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(top = 12.dp, bottom = 20.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).clip(CircleShape).background(c.line))
                Spacer(Modifier.height(22.dp))
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(c.safelightSoft), contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.Folder, c.safelight, 22.dp)
                }
                Spacer(Modifier.height(16.dp))
                Txt("Add photos from a folder", t.title)
                Spacer(Modifier.height(8.dp))
                Txt(
                    "Pick any folder on this phone, even ones your photo library hides. Every image inside becomes browsable and searchable.",
                    t.body, c.inkSoft,
                )
                Spacer(Modifier.height(18.dp))
                listOf(
                    Glyph.Check to "Only the folder you choose, and the folders inside it",
                    Glyph.Lock to "Read-only. Nothing is moved, changed or deleted",
                    Glyph.Close to "Remove it here any time and access is handed back",
                ).forEachIndexed { i, (glyph, line) ->
                    val enter = remember(visible) { Animatable(0f) }
                    LaunchedEffect(visible) {
                        if (visible) { delay(140L + i * 70L); enter.animateTo(1f, Motion.settle()) }
                    }
                    Row(
                        Modifier.padding(vertical = 6.dp).graphicsLayer {
                            alpha = enter.value.coerceIn(0f, 1f)
                            translationX = (1 - enter.value) * 16.dp.toPx()
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GlyphIcon(glyph, c.safelight, 16.dp)
                        Spacer(Modifier.width(12.dp))
                        Txt(line, t.caption, c.ink)
                    }
                }
                Spacer(Modifier.height(24.dp))
                Box(
                    Modifier.fillMaxWidth().height(54.dp)
                        .squish(pressedScale = 0.96f, onClick = onChoose)
                        .clip(RoundedCornerShape(18.dp)).background(c.safelight),
                    contentAlignment = Alignment.Center,
                ) { Txt("Choose a folder", t.label, c.onSafelight) }
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.fillMaxWidth().height(46.dp).squish(pressedScale = 0.96f, onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) { Txt("Not now", t.label, c.inkSoft) }
            }
        }
    }
}
