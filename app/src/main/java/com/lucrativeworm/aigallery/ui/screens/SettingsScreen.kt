package com.lucrativeworm.aigallery.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lucrativeworm.aigallery.data.IndexBackups
import com.lucrativeworm.aigallery.ml.IndexSchedule
import com.lucrativeworm.aigallery.ml.ModelChoice
import com.lucrativeworm.aigallery.ml.ModelStore
import com.lucrativeworm.aigallery.ml.SystemModelStatus
import com.lucrativeworm.aigallery.ml.Variant
import com.lucrativeworm.aigallery.ui.components.Glyph
import com.lucrativeworm.aigallery.ui.components.GlyphIcon
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
    backups: IndexBackups.State? = null,
    backupActions: BackupActions = BackupActions(),
) {
    val c = Theme.colors
    val t = Theme.type
    var open by rememberSaveable { mutableStateOf<Picker?>(null) }
    val close = { open = null }
    val systemOk = state.system != SystemModelStatus.UNSUPPORTED
    val downloadMb = if (state.sounds == true) Variant.FULL.approxMb else Variant.VISION.approxMb
    Box(Modifier.fillMaxSize().background(c.canvas)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
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
                SettingTile(
                    value = when (state.choice) {
                        ModelChoice.AUTO -> "Automatic"
                        ModelChoice.SYSTEM -> "Android system model"
                        ModelChoice.BUNDLED -> "Downloaded model"
                    },
                    status = when (state.choice) {
                        ModelChoice.AUTO -> autoStatus(state)
                        ModelChoice.SYSTEM -> systemStatus(state.system)
                        ModelChoice.BUNDLED -> if (state.bundledDownloaded) "Downloaded" else "Not downloaded"
                    },
                    onClick = { open = Picker.MODEL },
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
                SettingTile(
                    value = when (state.sounds) {
                        true -> "Include sounds"
                        false -> "Pictures only"
                        null -> "Not chosen yet"
                    },
                    status = if (state.sounds == false) "Sounds aren't indexed" else soundStatus(state),
                    onClick = { open = Picker.SOUNDS },
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
                SettingTile(
                    value = when (indexSchedule) {
                        IndexSchedule.WHEN_IDLE -> "While I'm asleep"
                        IndexSchedule.ANYTIME -> "Anytime"
                        null -> "Not chosen yet"
                    },
                    status = when (indexSchedule) {
                        IndexSchedule.WHEN_IDLE -> "New photos become searchable by morning"
                        IndexSchedule.ANYTIME -> "Search fills in sooner"
                        null -> "Nothing is prepared until you choose"
                    },
                    onClick = { open = Picker.SCHEDULE },
                )
                if (backups != null) {
                    Spacer(Modifier.height(32.dp))
                    BackupSection(backups, state.activeModel, backupActions)
                }
                Spacer(Modifier.height(32.dp))
            }
        }

        OptionSheet(open == Picker.MODEL, "Search model", onDismiss = close) {
            Option(
                title = "Automatic",
                body = "Android's built-in model when this phone has it, otherwise the downloaded one.",
                status = autoStatus(state),
                selected = state.choice == ModelChoice.AUTO,
                onClick = { onChoose(ModelChoice.AUTO); close() },
            )
            Spacer(Modifier.height(10.dp))
            Option(
                title = "Android system model",
                body = "Provided by Android through ML Kit and AICore. Nothing extra for the app to download, and it uses the phone's AI chip where there is one.",
                status = systemStatus(state.system),
                selected = state.choice == ModelChoice.SYSTEM,
                enabled = systemOk || state.choice == ModelChoice.SYSTEM,
                onClick = { onChoose(ModelChoice.SYSTEM); close() },
            )
            Spacer(Modifier.height(10.dp))
            Option(
                title = "Downloaded model",
                body = "Bundled with the app as a one-time download of about $downloadMb MB. Works on any phone.",
                status = if (state.bundledDownloaded) "Downloaded" else "Not downloaded",
                selected = state.choice == ModelChoice.BUNDLED,
                onClick = { onChoose(ModelChoice.BUNDLED); close() },
            )
        }

        OptionSheet(open == Picker.SOUNDS, "Sounds in videos", onDismiss = close) {
            Option(
                title = "Include sounds",
                body = "Uses the larger downloaded model (about ${Variant.FULL.approxMb} MB), since Android's built-in one can't hear. Videos take longer to prepare.",
                status = soundStatus(state),
                selected = state.sounds == true,
                onClick = { onSounds(true); close() },
            )
            Spacer(Modifier.height(10.dp))
            Option(
                title = "Pictures only",
                body = "Search videos by what they show. Lighter and faster to prepare.",
                status = if (state.sounds == null) "Not chosen yet" else "Sounds aren't indexed",
                selected = state.sounds == false,
                onClick = { onSounds(false); close() },
            )
        }

        OptionSheet(open == Picker.SCHEDULE, "When to prepare photos", onDismiss = close) {
            Option(
                title = "While I'm asleep",
                body = "Only when the phone is charging and not in use, like overnight. Nothing slows down during the day.",
                status = "New photos become searchable by morning",
                selected = indexSchedule == IndexSchedule.WHEN_IDLE,
                onClick = { onIndexSchedule(IndexSchedule.WHEN_IDLE); close() },
            )
            Spacer(Modifier.height(10.dp))
            Option(
                title = "Anytime",
                body = "Starts right away and keeps going in the background, easing off while you use the phone.",
                status = "Search fills in sooner",
                selected = indexSchedule == IndexSchedule.ANYTIME,
                onClick = { onIndexSchedule(IndexSchedule.ANYTIME); close() },
            )
        }
    }
}

private enum class Picker { MODEL, SOUNDS, SCHEDULE }

/** A setting with several choices: shows the current one, and opens a sheet to change it. */
@Composable
private fun SettingTile(value: String, status: String, onClick: () -> Unit) {
    val c = Theme.colors
    val t = Theme.type
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .squish(pressedScale = 0.97f, onClick = onClick)
            .semantics { role = Role.Button }
            .clip(shape)
            .background(c.raised)
            .border(1.dp, c.line, shape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(value, t.label)
            Spacer(Modifier.height(4.dp))
            Txt(status, t.caption, c.inkSoft)
        }
        Spacer(Modifier.width(12.dp))
        GlyphIcon(Glyph.Forward, c.inkFaint, size = 20.dp)
    }
}

/** Bottom sheet listing a setting's choices. Picking one applies it and closes the sheet. */
@Composable
private fun OptionSheet(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val c = Theme.colors
    BackHandler(enabled = visible, onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(160))) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            )
        }
        AnimatedVisibility(
            visible,
            enter = slideInVertically(Motion.glide()) { it },
            exit = slideOutVertically(tween(180)) { it },
            modifier = Modifier.align(Alignment.BottomCenter).statusBarsPadding().padding(top = 48.dp),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                    .background(c.raised)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 12.dp),
            ) {
                Box(
                    Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape).background(c.line),
                )
                Spacer(Modifier.height(16.dp))
                Txt(title, Theme.type.title, c.ink)
                Spacer(Modifier.height(16.dp))
                content()
                Spacer(Modifier.height(8.dp))
            }
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
    role: Role = Role.RadioButton,
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
            .semantics { this.role = role; this.selected = selected }
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

class BackupActions(
    val onPickFolder: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onBackupNow: () -> Unit = {},
    val onRestore: (IndexBackups.BackupFile) -> Unit = {},
    val onAuto: (Boolean) -> Unit = {},
    val onDismissMessage: () -> Unit = {},
)

/** Pick a folder once; back up there by hand or automatically; restore by tapping a backup. */
@Composable
private fun BackupSection(state: IndexBackups.State, activeModel: String?, actions: BackupActions) {
    val c = Theme.colors
    val t = Theme.type
    LaunchedEffect(Unit) { actions.onRefresh() }
    Txt("Backup", t.title)
    Spacer(Modifier.height(6.dp))
    Txt(
        "Save what search knows about your photos to a folder, so after a reinstall or on a new phone you restore it instead of preparing everything again.",
        t.caption, c.inkSoft,
    )
    Spacer(Modifier.height(18.dp))

    val busy = state.work != null
    if (state.folderName == null || state.lostAccess) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.raised).padding(16.dp)) {
            Txt(
                if (state.lostAccess) "The app can no longer reach \"${state.folderName}\". Choose it again to see your backups."
                else "Choose a folder, such as Documents/AI Gallery. After a reinstall, choose the same folder and your backups appear here.",
                t.caption, c.inkSoft,
            )
            Spacer(Modifier.height(12.dp))
            Pill("Choose folder", actions.onPickFolder)
        }
        Message(state, actions)
        return
    }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.raised).padding(16.dp)) {
        Txt(state.folderName, t.label)
        Spacer(Modifier.height(4.dp))
        Txt(
            if (state.lastBackupAt > 0) "Last backup ${formatDate(state.lastBackupAt)}" else "No backup from this phone yet",
            t.caption, c.inkFaint,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.alpha(if (busy) 0.45f else 1f)) { Pill("Back up now") { if (!busy) actions.onBackupNow() } }
            Spacer(Modifier.width(16.dp))
            Box(Modifier.squish(onClick = actions.onPickFolder).padding(vertical = 10.dp)) {
                Txt("Change folder", t.label, c.safelight)
            }
        }
        if (busy) {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).lightLeak(c.sunken, c.safelight.copy(alpha = 0.6f)))
            Spacer(Modifier.height(8.dp))
            Txt(
                when (state.work) {
                    IndexBackups.Work.BACKING_UP -> "Backing up…"
                    IndexBackups.Work.RESTORING -> "Restoring… indexing is paused until this finishes"
                    else -> "Looking for backups…"
                },
                t.caption, c.inkSoft,
            )
        }
    }
    Message(state, actions)
    Spacer(Modifier.height(10.dp))
    Option(
        title = "Back up automatically",
        body = "After photos are prepared, a fresh backup is saved here. The newest 5 are kept.",
        status = if (state.auto) "On" else "Off",
        selected = state.auto,
        role = Role.Switch,
        onClick = { actions.onAuto(!state.auto) },
    )

    if (state.files.isNotEmpty()) {
        Spacer(Modifier.height(22.dp))
        Txt("Restore", t.label)
        Spacer(Modifier.height(4.dp))
        Txt(
            "Gives photos on this phone their search data back. Anything a backup doesn't cover is prepared as usual.",
            t.caption, c.inkSoft,
        )
        Spacer(Modifier.height(12.dp))
        state.files.forEach { file ->
            BackupRow(file, activeModel, enabled = !busy, onRestore = { actions.onRestore(file) })
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun Message(state: IndexBackups.State, actions: BackupActions) {
    val message = state.message ?: return
    Spacer(Modifier.height(10.dp))
    Box(Modifier.fillMaxWidth().squish(pressedScale = 0.98f, onClick = actions.onDismissMessage)) {
        Txt(message, Theme.type.caption, Theme.colors.safelight)
    }
}

@Composable
private fun BackupRow(file: IndexBackups.BackupFile, activeModel: String?, enabled: Boolean, onRestore: () -> Unit) {
    val c = Theme.colors
    val t = Theme.type
    val h = file.header
    val otherModel = activeModel != null && h.models.isNotEmpty() && activeModel !in h.models
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.raised)
            .border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(formatDate(h.createdAt), t.label)
            Spacer(Modifier.height(4.dp))
            Txt("${IndexBackups.count(h)} · ${"%.1f".format(file.bytes / 1_000_000.0)} MB", t.caption, c.inkSoft)
            Spacer(Modifier.height(4.dp))
            Txt(
                when {
                    otherModel -> "Made with ${modelName(h.models)}, not the model search uses now"
                    else -> "Made with ${modelName(h.models)}"
                },
                t.caption, if (otherModel) c.safelight else c.inkFaint,
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.alpha(if (enabled && !otherModel) 1f else 0.45f)) {
            Pill("Restore") { if (enabled && !otherModel) onRestore() }
        }
    }
}

private fun modelName(models: List<String>): String = when {
    models.isEmpty() -> "no model"
    models.size > 1 -> "more than one model"
    models[0].startsWith("mlkit:") -> "Android's model"
    else -> "the downloaded model"
}

private fun formatDate(ms: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(ms))
