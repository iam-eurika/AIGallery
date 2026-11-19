package com.lucrativeworm.aigallery.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.Job
import kotlin.math.roundToInt
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lucrativeworm.aigallery.data.Photo
import com.lucrativeworm.aigallery.ml.Lift
import com.lucrativeworm.aigallery.ui.CircleState
import com.lucrativeworm.aigallery.ui.Finds
import com.lucrativeworm.aigallery.ui.components.Glyph
import com.lucrativeworm.aigallery.ui.components.GlyphIcon
import com.lucrativeworm.aigallery.ui.components.IconButton
import com.lucrativeworm.aigallery.ui.components.Txt
import com.lucrativeworm.aigallery.ui.components.lightLeak
import com.lucrativeworm.aigallery.ui.components.sharedPhoto
import com.lucrativeworm.aigallery.ui.components.squish
import com.lucrativeworm.aigallery.ui.components.thumbRequest
import com.lucrativeworm.aigallery.ui.theme.Motion
import com.lucrativeworm.aigallery.ui.theme.Theme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.min

class CircleActions(
    val onBack: () -> Unit,
    val onCircle: (List<Pair<Float, Float>>) -> Unit,
    val onSearchWhole: () -> Unit,
    val onClearLift: () -> Unit,
    /** Returns true when the system shows its own clipboard confirmation; null on failure. */
    val onCopy: suspend () -> Boolean?,
    val onShare: () -> Unit,
    val onOpen: (List<Photo>, Int) -> Unit,
)

/**
 * Circle to search. Draw a loop (or scribble, or tap) over anything in the photo: MagicTouch lifts
 * that object out, the rest of the photo dims, and EmbeddingGemma finds photos that contain
 * something like it. The lifted object can be copied as a transparent sticker and pasted anywhere.
 */
@Composable
fun CircleSearchScreen(state: CircleState, actions: CircleActions) {
    val showPanel = state.finds != Finds.None
    val split by animateFloatAsState(if (showPanel) PEEK else 1f, Motion.glide(), label = "split")
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val totalH = constraints.maxHeight.toFloat()
        // The results sheet's top edge: resting just under the photo, or pulled all the way up.
        val collapsed = totalH * PEEK
        val expanded = 0f
        var sheetTop by remember { mutableFloatStateOf(totalH) }
        var settling by remember { mutableStateOf<Job?>(null) }
        fun animateTo(target: Float, velocity: Float = 0f) {
            settling?.cancel()
            settling = scope.launch {
                animate(sheetTop, target, velocity, Motion.glide()) { v, _ -> sheetTop = v }
            }
        }
        fun dragBy(dy: Float): Float {
            settling?.cancel()
            val next = (sheetTop + dy).coerceIn(expanded, collapsed)
            val used = next - sheetTop
            sheetTop = next
            return used
        }
        fun settle(velocity: Float) = animateTo(
            when {
                velocity < -800f -> expanded
                velocity > 800f -> collapsed
                sheetTop < collapsed / 2f -> expanded
                else -> collapsed
            },
            velocity,
        )
        LaunchedEffect(showPanel, collapsed) { animateTo(if (showPanel) collapsed else totalH) }
        val expandedness = ((collapsed - sheetTop) / collapsed).coerceIn(0f, 1f)
        BackHandler(enabled = showPanel && sheetTop < collapsed - 1f) { animateTo(collapsed) }

        // Scrolling the grid up first pulls the sheet up; scrolling down past its top pulls it back.
        val nested = remember(collapsed) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                    if (available.y < 0f && sheetTop > expanded) Offset(0f, dragBy(available.y)) else Offset.Zero

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                    if (available.y > 0f && source == NestedScrollSource.UserInput && sheetTop < collapsed) Offset(0f, dragBy(available.y))
                    else Offset.Zero

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (sheetTop > expanded + 0.5f && sheetTop < collapsed - 0.5f) {
                        settle(available.y)
                        return available
                    }
                    return Velocity.Zero
                }
            }
        }

        Box(Modifier.fillMaxWidth().fillMaxHeight(split)) {
            PhotoCanvas(state, actions)
        }
        if (sheetTop < totalH - 0.5f) {
            val statusTop = WindowInsets.statusBars.getTop(density)
            ResultsPanel(
                finds = state.finds,
                onOpen = actions.onOpen,
                expandedness = expandedness,
                topInset = with(density) { (statusTop * expandedness).toDp() },
                handle = Modifier
                    .draggable(
                        rememberDraggableState { dragBy(it) },
                        Orientation.Vertical,
                        onDragStopped = { settle(it) },
                    )
                    .pointerInput(Unit) {
                        detectTapGestures { animateTo(if (sheetTop > collapsed / 2f) expanded else collapsed) }
                    },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { totalH.toDp() })
                    .offset { IntOffset(0, sheetTop.roundToInt()) }
                    .nestedScroll(nested),
            )
        }

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp)
                .graphicsLayer { alpha = (1f - expandedness * 2.5f).coerceIn(0f, 1f) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(Glyph.Back, "Back", tint = Color.White, background = Color.Black.copy(alpha = 0.35f), onClick = actions.onBack)
            Spacer(Modifier.weight(1f))
            Hint(state)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(44.dp))
        }
    }
}

/** How much of the screen the photo keeps while results peek up from below. */
private const val PEEK = 0.56f

@Composable
private fun Hint(state: CircleState) {
    val text = when {
        state.failed -> "Couldn't open this photo"
        state.image == null -> "Opening…"
        state.lifting -> "Looking closely…"
        state.lift != null -> "Circle again to change"
        else -> "Circle, scribble or tap anything"
    }
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (fadeIn(tween(200)) + slideInVertically { it / 2 }).togetherWith(fadeOut(tween(120)) + slideOutVertically { -it / 2 })
        },
        label = "hint",
    ) { t ->
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIcon(Glyph.Lasso, Theme.colors.safelight, 14.dp)
            Spacer(Modifier.width(8.dp))
            Txt(t, Theme.type.label, Color.White, maxLines = 1)
        }
    }
}

/** Where the photo sits inside the canvas once fitted (letterboxed). */
private fun fitRect(container: Size, w: Int, h: Int): Rect {
    val k = min(container.width / w, container.height / h)
    val dw = w * k; val dh = h * k
    val left = (container.width - dw) / 2f; val top = (container.height - dh) / 2f
    return Rect(left, top, left + dw, top + dh)
}

@Composable
private fun PhotoCanvas(state: CircleState, actions: CircleActions) {
    val c = Theme.colors
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val reduced = Theme.reducedMotion
    val image = remember(state.image) { state.image?.asImageBitmap() }
    val cutout = remember(state.lift) { state.lift?.cutout?.asImageBitmap() }

    // The live stroke, in canvas pixels, plus a fade for after the finger lifts.
    val stroke = remember { mutableStateListOf<Offset>() }
    val strokeAlpha = remember { Animatable(0f) }
    var size by remember { mutableStateOf(Size.Zero) }

    // Photo appearing: fade + a slight settle, then one glimmer sweep to say "this is live".
    val appear = remember { Animatable(0f) }
    val glimmer = remember { Animatable(-0.3f) }
    LaunchedEffect(image) {
        if (image == null) return@LaunchedEffect
        appear.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        if (!reduced) glimmer.animateTo(1.3f, tween(1100, easing = LinearEasing))
    }

    // Lift: the scene dims, the object rises toward you with a sticker outline and a breathing glow.
    val dim by animateFloatAsState(if (state.lift != null || state.lifting) 1f else 0f, tween(320), label = "dim")
    val rise = remember { Animatable(0f) }
    LaunchedEffect(state.liftId) {
        if (state.lift == null) return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        rise.snapTo(0f)
        rise.animateTo(1f, Motion.settle())
    }
    val breathe = if (reduced) 0.7f else rememberInfiniteTransition(label = "halo").animateFloat(
        0.45f, 1f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "b",
    ).value
    val pulse = if (reduced) 0.5f else rememberInfiniteTransition(label = "pulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "p",
    ).value
    LaunchedEffect(state.lifting) { if (!state.lifting && state.lift != null) strokeAlpha.animateTo(0f, tween(360)) }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = appear.value; val s = 0.97f + 0.03f * appear.value; scaleX = s; scaleY = s }
                .pointerInput(image) {
                    val img = image ?: return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val rect = fitRect(Size(this.size.width.toFloat(), this.size.height.toFloat()), img.width, img.height)
                        if (!rect.contains(down.position)) return@awaitEachGesture
                        stroke.clear()
                        stroke += down.position
                        scope.launch { strokeAlpha.snapTo(1f) }
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        var travelled = 0f
                        var lastTick = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val p = change.position
                            val prev = stroke.last()
                            travelled += hypot(p.x - prev.x, p.y - prev.y)
                            if (hypot(p.x - prev.x, p.y - prev.y) > 2f) stroke += p
                            // Faint ticks while drawing, like a pen on paper.
                            if (travelled - lastTick > 140f) {
                                lastTick = travelled
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            }
                            change.consume()
                        }
                        val points = stroke.map {
                            ((it.x - rect.left) / rect.width).coerceIn(0f, 1f) to ((it.y - rect.top) / rect.height).coerceIn(0f, 1f)
                        }
                        actions.onCircle(points)
                    }
                },
        ) {
            size = this.size
            val img = image ?: return@Canvas
            val rect = fitRect(this.size, img.width, img.height)
            drawImage(img, dstOffset = IntOffset(rect.left.toInt(), rect.top.toInt()),
                dstSize = IntSize(rect.width.toInt(), rect.height.toInt()))

            // One-time glimmer: a soft diagonal band of light crossing the photo.
            if (glimmer.value in -0.2f..1.2f) clipRect(rect.left, rect.top, rect.right, rect.bottom) {
                val x = rect.left + rect.width * glimmer.value
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.22f), Color.Transparent),
                        start = Offset(x - 160f, rect.top), end = Offset(x + 160f, rect.top + 220f),
                    ),
                    topLeft = rect.topLeft, size = rect.size,
                )
            }

            if (dim > 0f) drawRect(Color.Black.copy(alpha = 0.62f * dim), rect.topLeft, rect.size)

            val lift = state.lift
            if (lift != null && cutout != null) drawLift(cutout, lift, rect, rise.value, breathe, c.safelight)

            if (stroke.size > 1 && strokeAlpha.value > 0f) drawLightPen(stroke, strokeAlpha.value, c.safelight)

            // While MagicTouch works: a ripple from the middle of what was circled.
            if (state.lifting && stroke.isNotEmpty()) {
                val cx = stroke.sumOf { it.x.toDouble() }.toFloat() / stroke.size
                val cy = stroke.sumOf { it.y.toDouble() }.toFloat() / stroke.size
                drawCircle(c.safelight.copy(alpha = 0.5f * (1 - pulse)), 12f + 70f * pulse, Offset(cx, cy), style = Stroke(3f))
                drawCircle(c.safelight, 6f, Offset(cx, cy))
            }
        }

        LiftActions(state, actions, Modifier.align(Alignment.BottomCenter))
    }
}

/** A glowing pen: wide soft safelight under a bright core. */
private fun DrawScope.drawLightPen(points: List<Offset>, alpha: Float, glow: Color) {
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            // Smooth through midpoints so a shaky finger still draws a calm line.
            val a = points[i - 1]; val b = points[i]
            quadraticTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2)
        }
    }
    drawPath(path, glow.copy(alpha = 0.22f * alpha), style = Stroke(34f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, glow.copy(alpha = 0.55f * alpha), style = Stroke(14f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, Color.White.copy(alpha = 0.95f * alpha), style = Stroke(4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** The lifted object at its true position: outlined like a sticker, haloed, and slightly raised. */
private fun DrawScope.drawLift(cutout: ImageBitmap, lift: Lift, photo: Rect, rise: Float, breathe: Float, glow: Color) {
    val b = lift.bounds
    val dst = Rect(
        photo.left + b.left * photo.width, photo.top + b.top * photo.height,
        photo.left + b.right * photo.width, photo.top + b.bottom * photo.height,
    )
    val s = 1f + 0.05f * rise
    val offset = IntOffset(dst.left.toInt(), dst.top.toInt())
    val dsize = IntSize(dst.width.toInt().coerceAtLeast(1), dst.height.toInt().coerceAtLeast(1))
    scale(s, s, pivot = dst.center) {
        // Halo: the silhouette in safelight, smeared in a ring of offsets.
        val haloR = 10f * rise
        for (i in 0 until 12) {
            val a = i * Math.PI / 6
            drawImage(cutout, dstOffset = IntOffset((offset.x + haloR * kotlin.math.cos(a)).toInt(), (offset.y + haloR * kotlin.math.sin(a)).toInt()),
                dstSize = dsize, alpha = 0.10f * breathe * rise, colorFilter = ColorFilter.tint(glow))
        }
        // Sticker edge: a crisp white rim from tighter offsets.
        val rim = 3.5f * rise
        for (i in 0 until 12) {
            val a = i * Math.PI / 6
            drawImage(cutout, dstOffset = IntOffset((offset.x + rim * kotlin.math.cos(a)).toInt(), (offset.y + rim * kotlin.math.sin(a)).toInt()),
                dstSize = dsize, alpha = rise.coerceIn(0f, 1f), colorFilter = ColorFilter.tint(Color.White))
        }
        drawImage(cutout, dstOffset = offset, dstSize = dsize)
    }
}

@Composable
private fun LiftActions(state: CircleState, actions: CircleActions, modifier: Modifier) {
    val c = Theme.colors
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var copied by remember(state.liftId) { mutableStateOf(false) }
    val showWhole = state.image != null && state.lift == null && !state.lifting && state.finds == Finds.None

    Box(modifier.fillMaxWidth().padding(bottom = 14.dp).let { if (state.finds == Finds.None) it.navigationBarsPadding() else it },
        contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = state.lift != null && !state.lifting,
            enter = fadeIn(tween(200)) + slideInVertically(Motion.settle()) { it } + scaleIn(initialScale = 0.9f),
            exit = fadeOut(tween(120)) + slideOutVertically { it / 2 },
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Color(0xE6151412)).padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill(
                    glyph = if (copied) Glyph.Check else Glyph.Copy,
                    text = if (copied) "Copied" else "Copy",
                    accent = true,
                ) {
                    scope.launch {
                        val systemShown = actions.onCopy() ?: return@launch
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        copied = true
                        delay(if (systemShown) 1400 else 1800)
                        copied = false
                    }
                }
                Pill(Glyph.Share, "Share", accent = false, onClick = actions.onShare)
                Pill(Glyph.Close, "Clear", accent = false, onClick = actions.onClearLift)
            }
        }
        AnimatedVisibility(visible = showWhole, enter = fadeIn(tween(240, delayMillis = 600)), exit = fadeOut(tween(120))) {
            Pill(Glyph.Search, "Search whole photo", accent = false, modifier = Modifier
                .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)), onClick = actions.onSearchWhole)
        }
    }
}

@Composable
private fun Pill(glyph: Glyph, text: String, accent: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Theme.colors
    val bg = if (accent) c.safelight else Color.Transparent
    val fg = if (accent) c.onSafelight else Color.White
    Row(
        modifier.squish(pressedScale = 0.9f, onClick = onClick)
            .clip(RoundedCornerShape(50)).background(bg)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(glyph, transitionSpec = { scaleIn(Motion.settle(), 0.4f).togetherWith(fadeOut(tween(90))) }, label = "pg") {
            GlyphIcon(it, fg, 18.dp)
        }
        Spacer(Modifier.width(8.dp))
        Txt(text, Theme.type.label, fg, maxLines = 1)
    }
}

@Composable
private fun ResultsPanel(
    finds: Finds,
    onOpen: (List<Photo>, Int) -> Unit,
    expandedness: Float,
    topInset: androidx.compose.ui.unit.Dp,
    handle: Modifier,
    modifier: Modifier,
) {
    val c = Theme.colors
    // Square corners once it fills the screen.
    val corner = 26.dp * (1f - expandedness)
    Column(
        modifier
            .clip(RoundedCornerShape(topStart = corner, topEnd = corner))
            .background(c.canvas)
            .padding(top = topInset),
    ) {
        // A generous grab area around the little bar: drag it, or tap to expand / collapse.
        Box(
            handle.fillMaxWidth().height(28.dp).semantics { contentDescription = "Drag to expand results" },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(50)).background(c.line))
        }
        AnimatedContent(
            targetState = finds,
            contentKey = { it::class },
            transitionSpec = { fadeIn(tween(220)).togetherWith(fadeOut(tween(120))) },
            label = "finds",
        ) { f ->
            when (f) {
                Finds.None -> Box(Modifier.fillMaxSize())
                Finds.NeedsModel -> Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Txt("Search needs EmbeddingGemma", Theme.type.title, c.ink)
                    Spacer(Modifier.height(8.dp))
                    Txt(
                        "Download it from the library to find photos like this. You can still copy or share what you circled.",
                        Theme.type.body, c.inkSoft,
                    )
                }
                Finds.Searching -> LazyVerticalGrid(
                    GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 48.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    userScrollEnabled = false,
                ) {
                    items(9) {
                        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).lightLeak(c.raised, c.safelightSoft))
                    }
                }
                is Finds.Found -> FoundGrid(f, onOpen)
            }
        }
    }
}

@Composable
private fun FoundGrid(found: Finds.Found, onOpen: (List<Photo>, Int) -> Unit) {
    val c = Theme.colors
    val context = LocalContext.current
    val photos = remember(found) { found.hits.map { it.photo } }
    LazyVerticalGrid(
        GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.Sparkle, c.safelight, 14.dp)
                Spacer(Modifier.width(8.dp))
                Txt(
                    if (photos.isEmpty()) "Nothing like it yet" else "${photos.size} with something like it",
                    Theme.type.title, c.ink, maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Txt("${found.millis} ms", Theme.type.numeric, c.inkFaint)
            }
        }
        if (photos.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Txt("Try circling something larger, or index more photos.", Theme.type.body, c.inkSoft, Modifier.padding(4.dp))
        }
        itemsIndexed(photos, key = { _, p -> p.id }) { i, p ->
            val enter = remember(p.id) { Animatable(0f) }
            LaunchedEffect(p.id) {
                delay(i.coerceAtMost(14) * Motion.STAGGER_MS.toLong() * 1)
                enter.animateTo(1f, Motion.settle())
            }
            AsyncImage(
                model = remember(p.id) { thumbRequest(context, p) },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .graphicsLayer {
                        alpha = enter.value.coerceIn(0f, 1f)
                        val s = 0.85f + 0.15f * enter.value
                        scaleX = s; scaleY = s
                        translationY = (1 - enter.value) * 18.dp.toPx()
                    }
                    .aspectRatio(1f)
                    .squish(pressedScale = 0.93f) { onOpen(photos, i) }
                    .sharedPhoto(p.id)
                    .clip(RoundedCornerShape(if (i == 0) 14.dp else 8.dp))
                    .background(c.raised),
            )
        }
    }
}
