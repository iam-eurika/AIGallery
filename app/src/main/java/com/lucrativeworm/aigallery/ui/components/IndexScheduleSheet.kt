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
import com.lucrativeworm.aigallery.ml.IndexSchedule
import com.lucrativeworm.aigallery.ui.theme.Motion
import com.lucrativeworm.aigallery.ui.theme.Theme

/**
 * Asked once, right before the first indexing pass: may it run now, or only while the phone is
 * charging and idle (overnight)? Dismissing just defers the question; nothing indexes meanwhile.
 */
@Composable
fun IndexScheduleSheet(
    visible: Boolean,
    onChoose: (IndexSchedule) -> Unit,
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
                Txt("When should photos become searchable?", t.title, c.ink)
                Spacer(Modifier.height(8.dp))
                Txt(
                    "Preparing photos for search runs on this phone and keeps it busy for a while. You can change this anytime in Settings.",
                    t.body, c.inkSoft,
                )
                Spacer(Modifier.height(18.dp))
                Choice(
                    title = "While I'm asleep",
                    body = "Only when the phone is charging and not in use, like overnight. Nothing slows down during the day.",
                    primary = true,
                ) { onChoose(IndexSchedule.WHEN_IDLE) }
                Spacer(Modifier.height(10.dp))
                Choice(
                    title = "Start now",
                    body = "Search fills in sooner. It keeps going in the background and eases off while you use the phone.",
                    primary = false,
                ) { onChoose(IndexSchedule.ANYTIME) }
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
