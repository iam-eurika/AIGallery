package com.lucrativeworm.seekframe.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lucrativeworm.seekframe.ui.theme.Motion
import com.lucrativeworm.seekframe.ui.theme.Theme
import kotlinx.coroutines.launch

@Composable
fun Txt(text: String, style: TextStyle, color: Color = Theme.colors.ink, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    BasicText(text = text, style = style.copy(color = color), modifier = modifier, maxLines = maxLines)
}

/**
 * The app's one pressable primitive: squishes under the finger on a stiff spring, springs back
 * with a slight overshoot on release, and fires a light haptic on click. No ripples.
 */
fun Modifier.squish(
    pressedScale: Float = 0.94f,
    haptic: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    this
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .semantics { role = Role.Button; this.onClick(label = null, action = { onClick(); true }) }
        .pointerInput(onClick, onLongClick) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                scope.launch { scale.animateTo(pressedScale, Motion.press()) }
                var up: PointerInputChange? = null
                var longPressed = false
                if (onLongClick == null) {
                    up = waitForUpOrCancellation()
                } else {
                    val finished = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        up = waitForUpOrCancellation()
                        true
                    }
                    if (finished == null) {
                        longPressed = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                        waitForUpOrCancellation()?.consume()
                    }
                }
                scope.launch { scale.animateTo(1f, Motion.settle()) }
                val released = up
                if (released != null && !longPressed) {
                    released.consume()
                    if (haptic) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onClick()
                }
            }
        }
}

/** Round, borderless icon button with a soft halo that only appears while pressed. */
@Composable
fun IconButton(
    glyph: Glyph,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = Theme.colors.ink,
    background: Color = Color.Transparent,
    size: Dp = 44.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .squish(pressedScale = 0.86f, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, tint = tint, size = size * 0.5f)
    }
}

/** A pill that dips under the finger and springs back with a little wobble. */
@Composable
fun Chip(text: String, modifier: Modifier = Modifier, leading: Glyph? = null, onClick: () -> Unit) {
    val c = Theme.colors
    Row(
        modifier
            .squish(pressedScale = 0.92f, onClick = onClick)
            .clip(RoundedCornerShape(50))
            .background(c.raised)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            GlyphIcon(leading, tint = c.safelight, size = 14.dp)
            Box(Modifier.size(6.dp))
        }
        Txt(text, Theme.type.label, c.inkSoft)
    }
}

/** Warm sweep used for loading placeholders: a slow light leak instead of a grey shimmer. */
fun Modifier.lightLeak(base: Color, highlight: Color): Modifier = composed {
    if (Theme.reducedMotion) return@composed background(base)
    val t by rememberInfiniteTransition(label = "leak").animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "leakX",
    )
    background(base).background(
        Brush.linearGradient(
            colors = listOf(Color.Transparent, highlight, Color.Transparent),
            start = Offset(t * 600f, 0f), end = Offset(t * 600f + 400f, 400f),
        )
    )
}

/**
 * Indexing indicator: a ring that fills with progress and slowly breathes while the model works,
 * so the user can see the app thinking without a modal spinner.
 */
@Composable
fun ProgressRing(fraction: Float, active: Boolean, modifier: Modifier = Modifier, size: Dp = 18.dp, stroke: Dp = 2.dp) {
    val c = Theme.colors
    val animated by animateFloatAsState(fraction, Motion.gentle(), label = "ring")
    val breath = if (active && !Theme.reducedMotion) {
        rememberInfiniteTransition(label = "breath").animateFloat(
            0.55f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "breathA",
        ).value
    } else 1f
    Canvas(modifier.size(size)) {
        val s = Stroke(stroke.toPx(), cap = StrokeCap.Round)
        drawArc(c.line, 0f, 360f, false, style = s)
        drawArc(c.safelight.copy(alpha = breath), -90f, 360f * animated, false, style = s)
    }
}

/** Remembers a boolean that flips once, used to trigger enter animations on first composition. */
@Composable
fun rememberEntered(): Boolean {
    var entered by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { entered = true }
    return entered
}

/**
 * A soft band of light sweeping across a placeholder while real content loads. Every shimmer on
 * screen shares the same clock and is drawn in window space, so the band crosses the whole layout
 * as one sweep rather than each block pulsing on its own. Still under reduced motion.
 */
fun Modifier.shimmer(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(6.dp)): Modifier = composed {
    val c = com.lucrativeworm.seekframe.ui.theme.Theme.colors
    val base = c.sunken
    val glint = if (c.isDark) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.55f)
    val reduced = com.lucrativeworm.seekframe.ui.theme.Theme.reducedMotion
    val progress = if (reduced) null else rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    var originX by remember { mutableStateOf(0f) }
    var span by remember { mutableStateOf(0f) }
    this
        .onGloballyPositioned {
            originX = it.positionInWindow().x
            span = (it.findRootCoordinates().size.width).toFloat()
        }
        .clip(shape)
        .background(base)
        .drawWithCache {
            onDrawBehind {
                val p = progress?.value ?: return@onDrawBehind
                val band = span.coerceAtLeast(size.width) * 0.45f
                // Travel from fully off the left of the window to fully off the right.
                val x = -band + p * (span + 2 * band) - originX
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, glint, Color.Transparent),
                        start = Offset(x - band / 2, 0f),
                        end = Offset(x + band / 2, size.height * 0.4f),
                    ),
                )
            }
        }
}
