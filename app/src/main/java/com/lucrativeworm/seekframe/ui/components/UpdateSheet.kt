package com.lucrativeworm.seekframe.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.lucrativeworm.seekframe.update.Updater
import com.lucrativeworm.seekframe.ui.theme.Motion
import com.lucrativeworm.seekframe.ui.theme.Theme

/**
 * "A new version is ready." Offers the release's APK for this phone with Download or Ignore;
 * while downloading it shows progress and Cancel, then hands off to Android's installer.
 * Ignore skips that version for good; tapping outside only hides the sheet until next launch.
 */
@Composable
fun UpdateSheet(
    state: Updater.State,
    installed: String,
    onDownload: () -> Unit,
    onIgnore: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Theme.colors
    val t = Theme.type
    val visible = state !is Updater.State.Idle
    // Keep the last content on screen while the sheet slides away.
    var last by remember { mutableStateOf(state) }
    if (visible) last = state
    val shown = last
    val release = when (shown) {
        is Updater.State.Available -> shown.release
        is Updater.State.Downloading -> shown.release
        is Updater.State.Ready -> shown.release
        is Updater.State.Failed -> shown.release
        Updater.State.Idle -> null
    }
    val downloading = shown is Updater.State.Downloading
    val dismiss = if (downloading) ({}) else onDismiss

    BackHandler(enabled = visible && !downloading, onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(160))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).pointerInput(downloading) { detectTapGestures { dismiss() } })
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
                if (release == null) return@Column
                Txt("SeekFrame ${release.version} is available", t.title, c.ink)
                Spacer(Modifier.height(8.dp))
                Txt(
                    "You have $installed. The update is a ${mb(release.apkBytes)} download from GitHub. Your photos, search data and settings stay as they are.",
                    t.body, c.inkSoft,
                )
                val notes = remember(release.notes) { cleanNotes(release.notes) }
                if (notes.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Box(
                        Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(RoundedCornerShape(14.dp))
                            .background(c.sunken).verticalScroll(rememberScrollState()).padding(14.dp),
                    ) { Txt(notes, t.caption, c.inkSoft) }
                }

                when (shown) {
                    is Updater.State.Downloading -> {
                        Spacer(Modifier.height(18.dp))
                        val fraction = if (shown.total > 0) (shown.bytes.toFloat() / shown.total).coerceIn(0f, 1f) else 0f
                        val shownFraction by animateFloatAsState(fraction, tween(250), label = "update")
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.sunken)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(shownFraction).clip(CircleShape).background(c.safelight))
                        }
                        Spacer(Modifier.height(8.dp))
                        Txt("Downloading… ${mb(shown.bytes)} of ${mb(shown.total)}", t.caption, c.inkSoft)
                    }
                    is Updater.State.Failed -> {
                        Spacer(Modifier.height(12.dp))
                        Txt(shown.message, t.caption, c.safelight)
                    }
                    is Updater.State.Ready -> {
                        Spacer(Modifier.height(12.dp))
                        Txt("Downloaded. If Android asks, allow installs from SeekFrame, then come back and tap Install.", t.caption, c.inkSoft)
                    }
                    else -> {}
                }

                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val (secondary, onSecondary) = if (downloading) "Cancel" to onCancel else "Ignore" to onIgnore
                    val (primary, onPrimary) = when (shown) {
                        is Updater.State.Ready -> "Install" to onInstall
                        is Updater.State.Failed -> "Try again" to onDownload
                        is Updater.State.Downloading -> null to null
                        else -> "Download update" to onDownload
                    }
                    SheetAction(secondary, primary = false, modifier = Modifier.weight(1f), onClick = onSecondary)
                    if (primary != null && onPrimary != null) {
                        SheetAction(primary, primary = true, modifier = Modifier.weight(1f), onClick = onPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetAction(label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Theme.colors
    Box(
        modifier.squish(pressedScale = 0.95f, onClick = onClick).clip(RoundedCornerShape(16.dp))
            .background(if (primary) c.safelight else c.sunken).padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Txt(label, Theme.type.label, if (primary) c.onSafelight else c.ink) }
}

private fun mb(bytes: Long): String = if (bytes <= 0) "?" else "%.0f MB".format(bytes / 1_048_576.0)

/** GitHub's generated notes, minus markdown noise and the trailing changelog link. */
private fun cleanNotes(md: String): String = md.lines()
    .filterNot { it.startsWith("**Full Changelog**") }
    .map { line ->
        line.replace(Regex("^#+\\s*"), "")
            .replace(Regex("^\\s*[*-]\\s+"), "• ")
            .replace(Regex("\\s+by @\\S+ in https://\\S+"), "")
            .replace("**", "")
    }
    .joinToString("\n")
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()
