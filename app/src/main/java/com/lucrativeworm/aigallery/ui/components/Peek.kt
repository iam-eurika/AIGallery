package com.lucrativeworm.aigallery.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lucrativeworm.aigallery.data.Photo
import com.lucrativeworm.aigallery.ui.theme.Motion
import com.lucrativeworm.aigallery.ui.theme.Theme
import kotlin.math.min

/** What the peek menu can do. [onSelect] is null where selecting isn't possible (search results). */
class PeekActions(
    val onOpen: (Photo) -> Unit,
    val onShare: (Photo) -> Unit,
    val onSimilar: (Photo) -> Unit,
    val onDelete: (Photo) -> Unit,
    val onSelect: ((Photo) -> Unit)? = null,
)

/**
 * Long-press preview, like iOS: the photo lifts out at its own shape over a dimmed, blurred
 * library, with a menu underneath. Tap the photo to open it, tap outside (or Back) to put it down.
 */
@Composable
fun PhotoPeek(photo: Photo?, selected: Boolean, actions: PeekActions, onDismiss: () -> Unit) {
    val c = Theme.colors
    val context = LocalContext.current
    var last by remember { mutableStateOf(photo) }
    if (photo != null) last = photo
    BackHandler(enabled = photo != null, onBack = onDismiss)

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(photo != null, enter = fadeIn(tween(180)), exit = fadeOut(tween(160))) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (c.isDark) 0.55f else 0.35f))
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            )
        }
        AnimatedVisibility(
            photo != null,
            enter = fadeIn(tween(140)) + scaleIn(Motion.settle(), initialScale = 0.82f),
            exit = fadeOut(tween(140)) + scaleOut(tween(160), targetScale = 0.9f),
            modifier = Modifier.align(Alignment.Center),
        ) {
            val p = last ?: return@AnimatedVisibility
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val ratio = if (p.width > 0 && p.height > 0) p.width.toFloat() / p.height else 1f
                // Fit the photo in 86% of the width and ~55% of the height, keeping its shape.
                val maxW = maxWidth * 0.86f
                val maxH = maxHeight * 0.55f
                val w = min(maxW.value, maxH.value * ratio)
                val h = w / ratio
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AsyncImage(
                        model = remember(p.id) { fullRequest(context, p) },
                        contentDescription = "Preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(w.dp, h.dp)
                            .shadow(24.dp, RoundedCornerShape(18.dp))
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (p.tint != 0) Color(p.tint) else c.sunken)
                            .squish(pressedScale = 0.97f) { actions.onOpen(p) },
                    )
                    Spacer(Modifier.height(14.dp))
                    Column(
                        Modifier.width(250.dp).shadow(16.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
                            .background(c.raised),
                    ) {
                        actions.onSelect?.let { select ->
                            MenuRow(if (selected) "Deselect" else "Select", Glyph.Check, c.ink) { select(p) }
                            Divider()
                        }
                        MenuRow("Share", Glyph.Share, c.ink) { actions.onShare(p) }
                        Divider()
                        MenuRow("Find similar", Glyph.Similar, c.ink) { actions.onSimilar(p) }
                        Divider()
                        MenuRow("Delete", Glyph.Trash, Color(0xFFE5484D)) { actions.onDelete(p) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, glyph: Glyph, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().squish(pressedScale = 0.97f, onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Txt(label, Theme.type.body, tint)
        GlyphIcon(glyph, tint, 20.dp)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Theme.colors.line))
}
