package com.lucrativeworm.aigallery.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lucrativeworm.aigallery.ui.theme.Motion
import com.lucrativeworm.aigallery.ui.theme.Theme

/**
 * "Search what's heard in videos too?" Asked before the first model download (the answer decides
 * which model to fetch), on app open while unanswered, and changeable in Settings. Dismissing
 * defers it; nothing about sound happens until the user says yes.
 *
 * @param cost what yes would cost right now, e.g. a bigger download and re-indexing.
 */
@Composable
fun SoundsSheet(
    visible: Boolean,
    cost: String,
    onChoose: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Theme.colors
    val t = Theme.type
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
                Txt("Search what's heard in videos too?", t.title, c.ink)
                Spacer(Modifier.height(8.dp))
                Txt(
                    "Videos are always searchable by what they show, down to the moment. Sounds add what you hear: a dog barking, the birthday song, waves. Everything still runs on this phone.",
                    t.body, c.inkSoft,
                )
                Spacer(Modifier.height(8.dp))
                Txt(
                    "$cost Changing your mind later means preparing the library again, so it's worth deciding now.",
                    t.caption, c.inkFaint,
                )
                Spacer(Modifier.height(18.dp))
                Choice(
                    title = "Include sounds",
                    body = "Find a moment by what you heard. Videos take longer to prepare.",
                    primary = true,
                ) { onChoose(true) }
                Spacer(Modifier.height(10.dp))
                Choice(
                    title = "Pictures only",
                    body = "Smaller model, faster to prepare. You can turn sounds on in Settings.",
                    primary = false,
                ) { onChoose(false) }
            }
        }
    }
}

/**
 * "Also find what people say?" Asked once sounds are on and no speech-to-text has been chosen.
 * Each choice says what it downloads, and picking one is the go-ahead for that download; nothing
 * is fetched otherwise. Dismissing defers it for this visit; "Not now" turns spoken words off
 * (Settings can turn them on later).
 *
 * @param androidOffered this phone's Android offers its own speech model.
 */
@Composable
fun SpeechSheet(
    visible: Boolean,
    androidOffered: Boolean,
    sizeMb: Int,
    onChoose: (com.lucrativeworm.aigallery.ml.SpeechChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Theme.colors
    val t = Theme.type
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
                Txt("Find what people say in videos?", t.title, c.ink)
                Spacer(Modifier.height(8.dp))
                Txt(
                    "Sounds find a moment by what it sounds like. To search the words someone actually said, like \"happy birthday\" or a name, the app writes down speech in your videos, on this phone.",
                    t.body, c.inkSoft,
                )
                Spacer(Modifier.height(18.dp))
                Choice(
                    title = "Automatic",
                    body = if (androidOffered) "Android's speech model on this phone, which Android downloads and keeps up to date. More languages, better accuracy."
                    else "This phone's Android doesn't offer a speech model, so this downloads the app's own (about $sizeMb MB, English).",
                    primary = true,
                ) { onChoose(com.lucrativeworm.aigallery.ml.SpeechChoice.AUTO) }
                if (androidOffered) {
                    Spacer(Modifier.height(10.dp))
                    Choice(
                        title = "The app's own model",
                        body = "A one-time download of about $sizeMb MB, English only. Use this if you'd rather not rely on Android's.",
                        primary = false,
                    ) { onChoose(com.lucrativeworm.aigallery.ml.SpeechChoice.DOWNLOADED) }
                }
                Spacer(Modifier.height(10.dp))
                Choice(
                    title = "Not now",
                    body = "Nothing is downloaded. Sounds still work, and you can turn this on in Settings.",
                    primary = false,
                ) { onChoose(com.lucrativeworm.aigallery.ml.SpeechChoice.OFF) }
            }
        }
    }
}

@Composable
private fun Choice(title: String, body: String, primary: Boolean, onClick: () -> Unit) {
    val c = Theme.colors
    val t = Theme.type
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth()
            .squish(pressedScale = 0.97f, onClick = onClick)
            .semantics { role = Role.Button }
            .clip(shape)
            .background(if (primary) c.safelightSoft else c.sunken)
            .border(1.dp, if (primary) c.safelight else c.line, shape)
            .padding(16.dp),
    ) {
        Txt(title, t.label, if (primary) c.safelight else c.ink)
        Spacer(Modifier.height(4.dp))
        Txt(body, t.caption, c.inkSoft)
    }
}
