package com.lucrativeworm.aigallery.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lucrativeworm.aigallery.ui.theme.Motion
import com.lucrativeworm.aigallery.ui.theme.Theme

/** The round check in a tile's corner while selecting: an empty ring, or filled with a tick. */
@Composable
fun SelectCheck(selected: Boolean, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val c = Theme.colors
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (selected) c.safelight else Color.Black.copy(alpha = 0.18f))
            .border(1.75.dp, if (selected) c.safelight else Color.White.copy(alpha = 0.9f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Canvas(Modifier.size(size * 0.55f)) {
                val w = this.size.width
                drawPath(
                    Path().apply { moveTo(w * 0.1f, w * 0.52f); lineTo(w * 0.4f, w * 0.8f); lineTo(w * 0.92f, w * 0.2f) },
                    c.onSafelight,
                    style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}

/** Replaces the library's header while selecting: close, how many, select all. */
@Composable
fun SelectionTopBar(count: Int, allSelected: Boolean, onClose: () -> Unit, onSelectAll: () -> Unit, modifier: Modifier = Modifier) {
    val c = Theme.colors
    Row(
        modifier.fillMaxWidth().background(c.canvas.copy(alpha = 0.96f)).statusBarsPadding()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(Glyph.Close, "Cancel selection", tint = c.ink, onClick = onClose)
        Spacer(Modifier.width(6.dp))
        Txt(if (count == 1) "1 selected" else "$count selected", Theme.type.title, c.ink, modifier = Modifier.weight(1f))
        Box(
            Modifier.squish(pressedScale = 0.92f, onClick = onSelectAll).clip(RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) { Txt(if (allSelected) "Deselect all" else "Select all", Theme.type.label, c.safelight) }
    }
}

/** Floating bar with what you can do to the selection. */
@Composable
fun SelectionActions(enabled: Boolean, onShare: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val c = Theme.colors
    Row(
        modifier
            .navigationBarsPadding()
            .padding(bottom = 16.dp)
            .shadow(14.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(c.raised)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton(Glyph.Share, "Share", c.ink, enabled, onShare)
        BarButton(Glyph.Trash, "Delete", Color(0xFFE5484D), enabled, onDelete)
    }
}

@Composable
private fun BarButton(glyph: Glyph, label: String, tint: Color, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.squish(pressedScale = 0.92f) { if (enabled) onClick() }.clip(RoundedCornerShape(50))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val t = if (enabled) tint else tint.copy(alpha = 0.35f)
        GlyphIcon(glyph, t, 20.dp)
        Spacer(Modifier.width(8.dp))
        Txt(label, Theme.type.label, t)
    }
}

/** A plain confirm sheet for actions that can't be undone. */
@Composable
fun ConfirmSheet(
    visible: Boolean,
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Theme.colors
    var last by remember { mutableStateOf(title to body) }
    if (visible) last = title to body
    BackHandler(enabled = visible, onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(160))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).pointerInput(Unit) { detectTapGestures { onDismiss() } })
        }
        AnimatedVisibility(
            visible,
            enter = slideInVertically(Motion.glide()) { it },
            exit = slideOutVertically(tween(180)) { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                    .background(c.raised)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .navigationBarsPadding()
                    .padding(horizontal = 22.dp, vertical = 20.dp),
            ) {
                Txt(last.first, Theme.type.title, c.ink)
                Spacer(Modifier.height(8.dp))
                Txt(last.second, Theme.type.body, c.inkSoft)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.weight(1f).squish(pressedScale = 0.95f, onClick = onDismiss).clip(RoundedCornerShape(16.dp))
                            .background(c.sunken).padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Txt("Cancel", Theme.type.label, c.ink) }
                    Box(
                        Modifier.weight(1f).squish(pressedScale = 0.95f, onClick = onConfirm).clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFFE5484D)).padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Txt(confirm, Theme.type.label, Color.White) }
                }
            }
        }
    }
}
