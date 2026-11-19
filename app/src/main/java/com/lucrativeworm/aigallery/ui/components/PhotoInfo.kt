package com.lucrativeworm.aigallery.ui.components

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lucrativeworm.aigallery.data.Photo
import com.lucrativeworm.aigallery.data.PhotoDetails
import com.lucrativeworm.aigallery.data.PhotoDetailsLoader
import com.lucrativeworm.aigallery.ui.theme.Theme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val dayFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private val stampFmt = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

private fun Long.at() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())

/** The photo's details: what you see when you slide a photo up. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhotoInfo(photo: Photo, modifier: Modifier = Modifier) {
    val c = Theme.colors
    val context = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    var details by remember(photo.id) { mutableStateOf<PhotoDetails?>(null) }
    LaunchedEffect(photo.id, reload) { details = PhotoDetailsLoader.load(context, photo) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { reload++ }

    Column(modifier.padding(horizontal = 20.dp)) {
        val d = details
        val taken = d?.dateTaken ?: photo.dateTaken
        Txt(taken.at().format(dayFmt), Theme.type.title, c.ink)
        Spacer(Modifier.height(2.dp))
        Txt(taken.at().format(timeFmt), Theme.type.body, c.inkSoft)

        if (d == null) {
            Spacer(Modifier.height(20.dp))
            repeat(3) {
                Box(Modifier.fillMaxWidth(if (it == 1) 0.6f else 0.85f).height(16.dp).clip(RoundedCornerShape(6.dp)).lightLeak(c.raised, c.line))
                Spacer(Modifier.height(12.dp))
            }
            return@Column
        }

        // Camera, with its settings as a row of chips.
        val camera = listOfNotNull(d.make, d.model).let { parts ->
            // "Xiaomi Xiaomi 13" → "Xiaomi 13": many makers repeat the brand in the model.
            if (parts.size == 2 && parts[1].startsWith(parts[0], ignoreCase = true)) parts[1] else parts.joinToString(" ")
        }.ifBlank { null }
        val chips = listOfNotNull(
            d.iso?.let { "ISO $it" },
            d.focalLength?.let { f ->
                val mm = if (f % 1.0 == 0.0) "${f.toInt()} mm" else String.format(Locale.US, "%.1f mm", f)
                if (d.focal35 != null && d.focal35 != f.roundToInt()) "$mm (${d.focal35} mm eq.)" else mm
            },
            d.aperture?.let { String.format(Locale.US, "ƒ/%.1f", it) },
            d.exposureSeconds?.let(::shutter),
            d.flashFired?.let { if (it) "Flash" else "No flash" },
        )
        if (camera != null || chips.isNotEmpty()) {
            Section(Glyph.Camera, "Camera") {
                camera?.let { Txt(it, Theme.type.label, c.ink) }
                d.lens?.let { Txt(it, Theme.type.caption, c.inkSoft) }
                if (chips.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        chips.forEach {
                            Txt(it, Theme.type.caption, c.ink, modifier = Modifier.clip(RoundedCornerShape(50)).background(c.raised).padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                }
            }
        }

        // File.
        Section(Glyph.Photo, "File") {
            d.name?.let { Txt(it, Theme.type.label, c.ink) }
            val bits = listOfNotNull(
                if (d.width > 0 && d.height > 0) {
                    val mp = d.width.toLong() * d.height / 1_000_000.0
                    String.format(Locale.US, "%.1f MP · %d × %d", mp, d.width, d.height)
                } else null,
                d.sizeBytes?.let { Formatter.formatShortFileSize(context, it) },
                d.mimeType?.substringAfter('/')?.uppercase(),
            )
            if (bits.isNotEmpty()) Txt(bits.joinToString(" · "), Theme.type.caption, c.inkSoft)
            d.path?.let { Txt(it, Theme.type.caption, c.inkFaint) }
        }

        // Location.
        Section(Glyph.Pin, "Location") {
            when {
                d.latitude != null && d.longitude != null -> {
                    Txt(coords(d.latitude, d.longitude), Theme.type.label, c.ink)
                    d.altitude?.let { Txt("${it.roundToInt()} m above sea level", Theme.type.caption, c.inkSoft) }
                    Spacer(Modifier.height(10.dp))
                    SmallButton("Open in Maps") {
                        val geo = Uri.parse("geo:${d.latitude},${d.longitude}?q=${d.latitude},${d.longitude}")
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, geo)) } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, "No maps app installed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                d.locationHidden -> {
                    Txt("Android hides photo locations until you allow it.", Theme.type.caption, c.inkSoft)
                    Spacer(Modifier.height(10.dp))
                    SmallButton("Show locations") { askLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) }
                }
                else -> Txt("No location saved with this photo", Theme.type.caption, c.inkSoft)
            }
        }

        // Dates.
        Section(Glyph.Info, "Dates") {
            Row { Txt("Taken", Theme.type.caption, c.inkSoft, Modifier.width(84.dp)); Txt(taken.at().format(stampFmt), Theme.type.caption, c.ink) }
            d.dateModified?.let { m ->
                Spacer(Modifier.height(4.dp))
                Row { Txt("Modified", Theme.type.caption, c.inkSoft, Modifier.width(84.dp)); Txt(m.at().format(stampFmt), Theme.type.caption, c.ink) }
            }
            d.software?.let {
                Spacer(Modifier.height(4.dp))
                Row { Txt("Software", Theme.type.caption, c.inkSoft, Modifier.width(84.dp)); Txt(it, Theme.type.caption, c.ink) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(glyph: Glyph, title: String, content: @Composable () -> Unit) {
    val c = Theme.colors
    Spacer(Modifier.height(22.dp))
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(c.raised), contentAlignment = Alignment.Center) {
            GlyphIcon(glyph, c.inkSoft, 18.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, Theme.type.caption, c.inkFaint)
            Spacer(Modifier.height(2.dp))
            content()
        }
    }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    val c = Theme.colors
    Box(
        Modifier.squish(pressedScale = 0.94f, onClick = onClick).clip(RoundedCornerShape(50)).background(c.safelightSoft)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Txt(text, Theme.type.label, c.safelight) }
}

private fun shutter(s: Double): String =
    if (s >= 1.0) String.format(Locale.US, "%.1f s", s).replace(".0 s", " s")
    else "1/${(1.0 / s).roundToInt()} s"

private fun coords(lat: Double, lng: Double): String =
    String.format(Locale.US, "%.5f° %s, %.5f° %s", abs(lat), if (lat >= 0) "N" else "S", abs(lng), if (lng >= 0) "E" else "W")
