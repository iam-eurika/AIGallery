package com.lucrativeworm.seekframe.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lucrativeworm.seekframe.data.LinkPreview
import com.lucrativeworm.seekframe.data.LinkPreviews
import com.lucrativeworm.seekframe.ml.QrCode
import com.lucrativeworm.seekframe.ui.theme.Motion
import com.lucrativeworm.seekframe.ui.theme.Theme
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Corner brackets drawn over each QR code found in a photo. Lives inside the photo's zoom/pan
 * layer so the brackets stay glued to the code; each one is a tap target that opens the sheet.
 */
@Composable
fun QrOverlay(codes: List<QrCode>, visible: Boolean, onTap: (QrCode) -> Unit, modifier: Modifier = Modifier) {
    val c = Theme.colors
    val density = LocalDensity.current
    val reduced = Theme.reducedMotion
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val minTouch = with(density) { 48.dp.toPx() }
        val outset = with(density) { 6.dp.toPx() }
        codes.forEach { code ->
            val enter = remember(code) { Animatable(if (reduced) 1f else 0f) }
            val shown = remember(code) { Animatable(0f) }
            LaunchedEffect(code) { enter.animateTo(1f, Motion.settle()) }
            LaunchedEffect(code, visible) { shown.animateTo(if (visible) 1f else 0f, tween(180)) }

            val left = code.bounds.left * w - outset
            val top = code.bounds.top * h - outset
            val bw = code.bounds.width() * w + outset * 2
            val bh = code.bounds.height() * h + outset * 2
            // Small codes still get a finger-sized target, centered on the code.
            val tw = max(bw, minTouch)
            val th = max(bh, minTouch)
            val tx = left - (tw - bw) / 2
            val ty = top - (th - bh) / 2
            Box(
                Modifier
                    .offset { IntOffset(tx.roundToInt(), ty.roundToInt()) }
                    .size(with(density) { tw.toDp() }, with(density) { th.toDp() })
                    .graphicsLayer {
                        alpha = shown.value
                        val s = 1.15f - 0.15f * enter.value
                        scaleX = s; scaleY = s
                    }
                    .semantics { contentDescription = "QR code: ${code.label}" }
                    .pointerInput(code, visible) {
                        if (visible) detectTapGestures(onTap = { onTap(code) })
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val ox = (tw - bw) / 2
                    val oy = (th - bh) / 2
                    val arm = minOf(bw, bh) * 0.22f
                    val r = 4.dp.toPx()
                    val stroke = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                    drawRoundRect(
                        c.safelight.copy(alpha = 0.14f),
                        topLeft = Offset(ox, oy),
                        size = Size(bw, bh),
                        cornerRadius = CornerRadius(r * 2),
                    )
                    val corners = listOf(
                        Triple(Offset(ox, oy), 1f, 1f),
                        Triple(Offset(ox + bw, oy), -1f, 1f),
                        Triple(Offset(ox, oy + bh), 1f, -1f),
                        Triple(Offset(ox + bw, oy + bh), -1f, -1f),
                    )
                    corners.forEach { (p, dx, dy) ->
                        val path = Path().apply {
                            moveTo(p.x, p.y + dy * arm)
                            lineTo(p.x, p.y + dy * r)
                            quadraticTo(p.x, p.y, p.x + dx * r, p.y)
                            lineTo(p.x + dx * arm, p.y)
                        }
                        drawPath(path, Color.Black.copy(alpha = 0.35f), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                        drawPath(path, c.safelight, style = stroke)
                    }
                }
            }
        }
    }
}

/** Pill that names what the code points to, for when the code itself is tiny in the frame. */
@Composable
fun QrChip(code: QrCode, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Theme.colors
    Row(
        modifier
            .squish(pressedScale = 0.92f, onClick = onClick)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.14f))
            .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(if (code.url != null) Glyph.Link else Glyph.Qr, c.safelight, 16.dp)
        Spacer(Modifier.width(8.dp))
        BasicText(
            code.label + if (count > 1) "  +${count - 1}" else "",
            style = Theme.type.label.copy(color = Color.White),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 240.dp),
        )
    }
}

/**
 * Bottom sheet for a tapped QR code: the decoded link with a live preview of the page it leads
 * to (title, description, image), plus open and copy. Non-link codes show their text.
 */
@Composable
fun QrSheet(code: QrCode?, onDismiss: () -> Unit) {
    var last by remember { mutableStateOf(code) }
    if (code != null) last = code
    BackHandler(enabled = code != null, onBack = onDismiss)

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(code != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(160))) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            )
        }
        AnimatedVisibility(
            code != null,
            enter = slideInVertically(Motion.glide()) { it },
            exit = slideOutVertically(tween(180)) { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            last?.let { SheetBody(it, onDismiss) }
        }
    }
}

@Composable
private fun SheetBody(code: QrCode, onDismiss: () -> Unit) {
    val c = Theme.colors
    val context = LocalContext.current
    var preview by remember(code) { mutableStateOf<LinkPreview?>(null) }
    var loading by remember(code) { mutableStateOf(code.url != null) }
    LaunchedEffect(code) {
        val url = code.url ?: return@LaunchedEffect
        preview = LinkPreviews.fetch(url)
        loading = false
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .background(c.raised)
            .pointerInput(Unit) { detectTapGestures { } } // taps on the sheet don't reach the scrim
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 10.dp, bottom = 16.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).clip(RoundedCornerShape(50)).background(c.line))
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.Qr, c.safelight, 16.dp)
            Spacer(Modifier.width(8.dp))
            Txt(if (code.url != null) "Link from QR code" else "Text from QR code", Theme.type.caption, c.inkSoft)
            Spacer(Modifier.weight(1f))
            IconButton(Glyph.Close, "Close", tint = c.inkSoft, size = 36.dp, onClick = onDismiss)
        }
        Spacer(Modifier.height(10.dp))

        if (code.url != null) {
            LinkCard(code.url, preview, loading)
        } else {
            Box(
                Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(16.dp)).background(c.sunken)
                    .verticalScroll(rememberScrollState()).padding(14.dp)
            ) {
                Txt(code.raw, Theme.type.body, c.ink)
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (code.url != null) {
                SheetButton("Open link", Glyph.External, primary = true, modifier = Modifier.weight(1f)) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(code.url)).addCategory(Intent.CATEGORY_BROWSABLE)
                    try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            SheetButton("Copy", Glyph.Copy, primary = code.url == null, modifier = Modifier.weight(if (code.url != null) 0.6f else 1f)) {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("QR code", code.url ?: code.raw))
            }
        }
    }
}

@Composable
private fun LinkCard(url: String, preview: LinkPreview?, loading: Boolean) {
    val c = Theme.colors
    val host = remember(url) { Uri.parse(url).host?.removePrefix("www.") ?: url }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.sunken)) {
        when {
            loading -> Box(Modifier.fillMaxWidth().aspectRatio(1.91f).lightLeak(c.sunken, c.line))
            preview?.imageBytes != null -> AsyncImage(
                model = preview.imageBytes,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(1.91f),
            )
        }
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.Link, c.inkFaint, 14.dp)
                Spacer(Modifier.width(6.dp))
                BasicText(
                    preview?.siteName ?: host,
                    style = Theme.type.caption.copy(color = c.inkSoft),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            val title = preview?.title
            if (title != null) {
                Spacer(Modifier.height(6.dp))
                BasicText(title, style = Theme.type.title.copy(color = c.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else if (loading) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(0.7f).height(18.dp).clip(RoundedCornerShape(6.dp)).lightLeak(c.raised, c.line))
            }
            preview?.description?.let {
                Spacer(Modifier.height(4.dp))
                BasicText(it, style = Theme.type.body.copy(color = c.inkSoft), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            BasicText(url, style = Theme.type.caption.copy(color = c.inkFaint), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SheetButton(label: String, glyph: Glyph, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Theme.colors
    val fg = if (primary) c.onSafelight else c.ink
    Row(
        modifier
            .squish(pressedScale = 0.95f, onClick = onClick)
            .clip(RoundedCornerShape(16.dp))
            .background(if (primary) c.safelight else c.sunken)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(glyph, fg, 18.dp)
        Spacer(Modifier.width(8.dp))
        Txt(label, Theme.type.label, fg)
    }
}
