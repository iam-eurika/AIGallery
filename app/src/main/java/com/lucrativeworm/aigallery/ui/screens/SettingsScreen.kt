package com.lucrativeworm.aigallery.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lucrativeworm.aigallery.ml.IndexSchedule
import com.lucrativeworm.aigallery.ml.ModelChoice
import com.lucrativeworm.aigallery.ml.ModelStore
import com.lucrativeworm.aigallery.ml.SystemModelStatus
import com.lucrativeworm.aigallery.ml.Variant
import com.lucrativeworm.aigallery.ui.components.Glyph
import com.lucrativeworm.aigallery.ui.components.IconButton
import com.lucrativeworm.aigallery.ui.components.Txt
import com.lucrativeworm.aigallery.ui.components.lightLeak
import com.lucrativeworm.aigallery.ui.components.squish
import com.lucrativeworm.aigallery.ui.theme.Motion
import com.lucrativeworm.aigallery.ui.theme.Theme

class SettingsUiState(
    val choice: ModelChoice,
    val model: ModelStore.State,
    val system: SystemModelStatus,
    /** The [ModelStore.Source.id] search is running on right now, if any. */
    val activeModel: String?,
    val bundledDownloaded: Boolean,
    /** Sounds in videos searchable? null = not answered yet. */
    val sounds: Boolean? = null,
    /** Sounds are on but the model that can hear isn't downloaded yet. */
    val soundModelMissing: Boolean = false,
    /** Progress of that download, if one is running or failed. */
    val soundDownload: ModelStore.State? = null,
    /** The loaded model has an audio encoder. */
    val modelHears: Boolean = false,
)

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onChoose: (ModelChoice) -> Unit,
    onDownload: () -> Unit,
    indexSchedule: IndexSchedule? = null,
    onIndexSchedule: (IndexSchedule) -> Unit = {},
    onSounds: (Boolean) -> Unit = {},
    onDownloadSoundModel: () -> Unit = {},
) {
    val c = Theme.colors
    val t = Theme.type
    Column(
        Modifier.fillMaxSize().background(c.canvas).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Glyph.Back, "Back", onClick = onBack)
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            Txt("Settings", t.display)
            Spacer(Modifier.height(28.dp))
            Txt("Search model", t.title)
            Spacer(Modifier.height(6.dp))
            Txt(
                "Search, \"More like this\" and Circle all run on EmbeddingGemma 2, on this phone. Choose where it comes from.",
                t.caption, c.inkSoft,
            )
            Spacer(Modifier.height(18.dp))

            val systemOk = state.system != SystemModelStatus.UNSUPPORTED
            Option(
                title = "Automatic",
                body = "Android's built-in model when this phone has it, otherwise the downloaded one.",
                status = autoStatus(state),
                selected = state.choice == ModelChoice.AUTO,
                onClick = { onChoose(ModelChoice.AUTO) },
            )
            Spacer(Modifier.height(10.dp))
            Option(
                title = "Android system model",
                body = "Provided by Android through ML Kit and AICore. Nothing extra for the app to download, and it uses the phone's AI chip where there is one.",
                status = systemStatus(state.system),
                selected = state.choice == ModelChoice.SYSTEM,
                enabled = systemOk || state.choice == ModelChoice.SYSTEM,
                onClick = { onChoose(ModelChoice.SYSTEM) },
            )
            Spacer(Modifier.height(10.dp))
            Option(
                title = "Downloaded model",
                body = "Bundled with the app as a one-time download of about ${if (state.sounds == true) Variant.FULL.approxMb else Variant.VISION.approxMb} MB. Works on any phone.",
                status = if (state.bundledDownloaded) "Downloaded" else "Not downloaded",
                selected = state.choice == ModelChoice.BUNDLED,
                onClick = { onChoose(ModelChoice.BUNDLED) },
            )

            AnimatedVisibility(
                visible = state.model !is ModelStore.State.Ready,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut(tween(160)) + shrinkVertically(Motion.glide()),
            ) {
                NeedsAction(state.model, onDownload, Modifier.padding(top = 16.dp))
            }

            Spacer(Modifier.height(18.dp))
            Txt(
                "The two models make different kinds of fingerprints for your photos. After a switch, the library is re-indexed with the new one, so search fills back in over a few minutes.",
                t.caption, c.inkFaint,
            )

            Spacer(Modifier.height(32.dp))
            Txt("Sounds in videos", t.title)
            Spacer(Modifier.height(6.dp))
            Txt(
                "Videos are always searchable by what they show. Sounds add what you hear, like a dog barking or the birthday song, and jump to that moment.",
                t.caption, c.inkSoft,
            )
            Spacer(Modifier.height(18.dp))
            Option(
                title = "Include sounds",
                body = "Uses the larger downloaded model (about ${Variant.FULL.approxMb} MB), since Android's built-in one can't hear. Videos take longer to prepare.",
                status = soundStatus(state),
                selected = state.sounds == true,
                onClick = { onSounds(true) },
            )
            Spacer(Modifier.height(10.dp))
            Option(
                title = "Pictures only",
                body = "Search videos by what they show. Lighter and faster to prepare.",
                status = if (state.sounds == null) "Not chosen yet" else "Sounds aren't indexed",
                selected = state.sounds == false,
                onClick = { onSounds(false) },
            )
            val sound = state.soundDownload
            AnimatedVisibility(
                visible = state.sounds == true && (state.soundModelMissing || sound != null),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut(tween(160)) + shrinkVertically(Motion.glide()),
            ) {
                NeedsAction(
                    sound ?: ModelStore.State.Missing(ModelStore.Target.BUNDLED),
                    onDownloadSoundModel,
                    Modifier.padding(top = 16.dp),
                    missingText = "Search keeps working with the current model while the one that can hear downloads (about ${Variant.FULL.approxMb} MB). Then your library is prepared again with it.",
                )
            }
            Spacer(Modifier.height(18.dp))
            Txt(
                "Switching between these changes which model your library is prepared with, so search fills back in over a while.",
                t.caption, c.inkFaint,
            )

            Spacer(Modifier.height(32.dp))
            Txt("When to prepare photos", t.title)
            Spacer(Modifier.height(6.dp))
            Txt(
                "Making photos searchable runs on this phone and keeps it busy for a while.",
                t.caption, c.inkSoft,
            )
            Spacer(Modifier.height(18.dp))
            Option(
                title = "While I'm asleep",
                body = "Only when the phone is charging and not in use, like overnight. Nothing slows down during the day.",
                status = "New photos become searchable by morning",
                selected = indexSchedule == IndexSchedule.WHEN_IDLE,
                onClick = { onIndexSchedule(IndexSchedule.WHEN_IDLE) },
            )
            Spacer(Modifier.height(10.dp))
            Option(
                title = "Anytime",
                body = "Starts right away and keeps going in the background, easing off while you use the phone.",
                status = "Search fills in sooner",
                selected = indexSchedule == IndexSchedule.ANYTIME,
                onClick = { onIndexSchedule(IndexSchedule.ANYTIME) },
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

private fun autoStatus(s: SettingsUiState): String = when {
    s.choice != ModelChoice.AUTO -> when (s.system) {
        SystemModelStatus.AVAILABLE -> "Would use Android's model"
        SystemModelStatus.CHECKING -> "Checking this phone…"
        else -> "Would use the downloaded model"
    }
    s.activeModel == null -> if (s.model is ModelStore.State.Ready) "Loading the model…" else "Not set up yet"
    s.activeModel.startsWith("mlkit:") -> "Using Android's model"
    else -> "Using the downloaded model"
}

private fun soundStatus(s: SettingsUiState): String = when {
    s.sounds != true -> if (s.sounds == null) "Not chosen yet" else "Off"
    s.choice == ModelChoice.SYSTEM -> "Android's model can't hear. Choose Automatic or the downloaded model above"
    s.modelHears -> "On, sounds are being indexed"
    s.soundDownload is ModelStore.State.Downloading -> "Downloading the model that can hear…"
    s.soundModelMissing -> "Needs the model that can hear"
    else -> "On"
}

private fun systemStatus(s: SystemModelStatus): String = when (s) {
    SystemModelStatus.CHECKING -> "Checking this phone…"
    SystemModelStatus.UNREACHABLE -> "Android's model service didn't respond"
    SystemModelStatus.UNSUPPORTED -> "Not available on this phone"
    SystemModelStatus.DOWNLOADABLE -> "Available, Android needs to download it"
    SystemModelStatus.DOWNLOADING -> "Android is downloading it"
    SystemModelStatus.AVAILABLE -> "Ready on this phone"
}

@Composable
private fun Option(
    title: String,
    body: String,
    status: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = Theme.colors
    val t = Theme.type
    val border by animateColorAsState(if (selected) c.safelight else c.line, tween(200), label = "border")
    val dot by animateFloatAsState(if (selected) 1f else 0f, Motion.settle(), label = "dot")
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .then(if (enabled) Modifier.squish(pressedScale = 0.97f, onClick = onClick) else Modifier)
            .semantics { role = Role.RadioButton; this.selected = selected }
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) c.safelightSoft else c.raised)
            .border(1.dp, border, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.padding(top = 2.dp).size(20.dp).clip(CircleShape)
                .border(1.75.dp, if (selected) c.safelight else c.inkFaint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(10.dp * dot).clip(CircleShape).background(c.safelight))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, t.label)
            Spacer(Modifier.height(4.dp))
            Txt(body, t.caption, c.inkSoft)
            Spacer(Modifier.height(8.dp))
            Txt(status, t.caption, if (selected) c.safelight else c.inkFaint)
        }
    }
}

/** What the chosen model still needs: a download, or a different choice on this phone. */
@Composable
private fun NeedsAction(
    model: ModelStore.State,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    missingText: String? = null,
) {
    val c = Theme.colors
    val t = Theme.type
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.raised).padding(16.dp)) {
        when (model) {
            is ModelStore.State.Missing -> {
                Txt(
                    missingText ?: if (model.target == ModelStore.Target.SYSTEM) "Android will download the model for this phone."
                    else "The model needs a one-time download.",
                    t.caption, c.inkSoft,
                )
                Spacer(Modifier.height(12.dp))
                Pill("Download", onDownload)
            }
            is ModelStore.State.Downloading -> {
                val p by animateFloatAsState(model.fraction, Motion.gentle(), label = "dl")
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.sunken)) {
                    Box(Modifier.fillMaxWidth(p).height(6.dp).clip(CircleShape).background(c.safelight))
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Txt("Downloading", t.caption, c.inkSoft)
                    if (model.total > 0) Txt("${model.bytes / 1_000_000} / ${model.total / 1_000_000} MB", t.numeric, c.inkFaint)
                }
            }
            ModelStore.State.Verifying -> {
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).lightLeak(c.sunken, c.safelight.copy(alpha = 0.6f)))
                Spacer(Modifier.height(8.dp))
                Txt("Checking the download…", t.caption, c.inkSoft)
            }
            ModelStore.State.Unsupported -> Txt(
                "This phone's Android doesn't offer the model. Search is off until you choose Automatic or the downloaded model.",
                t.caption, c.safelight,
            )
            is ModelStore.State.Failed -> {
                Txt(model.message, t.caption, c.safelight)
                Spacer(Modifier.height(12.dp))
                Pill("Try again", onDownload)
            }
            is ModelStore.State.Ready -> Unit
        }
    }
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    val c = Theme.colors
    Box(
        Modifier.squish(onClick = onClick).clip(RoundedCornerShape(50)).background(c.safelight)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) { Txt(text, Theme.type.label, c.onSafelight) }
}
