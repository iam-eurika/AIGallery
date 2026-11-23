package com.lucrativeworm.seekframe.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lucrativeworm.seekframe.ui.LibraryFilter
import com.lucrativeworm.seekframe.ui.MediaKind
import com.lucrativeworm.seekframe.ui.SortOrder
import com.lucrativeworm.seekframe.ui.theme.Motion
import com.lucrativeworm.seekframe.ui.theme.Theme

/** Which media the library shows and in which order. Changes apply as they're tapped. */
@Composable
fun FilterSheet(
    visible: Boolean,
    filter: LibraryFilter,
    onChange: (LibraryFilter) -> Unit,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt("Filter & sort", t.title, c.ink, modifier = Modifier.weight(1f))
                    if (!filter.isDefault) {
                        Txt(
                            "Reset", t.label, c.safelight,
                            modifier = Modifier.squish { onChange(LibraryFilter()) }.padding(horizontal = 6.dp, vertical = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Txt("Show", t.caption, c.inkSoft)
                Spacer(Modifier.height(8.dp))
                Segmented(
                    options = listOf(MediaKind.ALL to "Everything", MediaKind.PHOTOS to "Photos", MediaKind.VIDEOS to "Videos"),
                    selected = filter.kind,
                ) { onChange(filter.copy(kind = it)) }
                Spacer(Modifier.height(18.dp))
                Txt("Sort by date", t.caption, c.inkSoft)
                Spacer(Modifier.height(8.dp))
                Segmented(
                    options = listOf(SortOrder.NEWEST to "Newest first", SortOrder.OLDEST to "Oldest first"),
                    selected = filter.sort,
                ) { onChange(filter.copy(sort = it)) }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val c = Theme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(c.sunken).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            val bg by animateColorAsState(if (on) c.safelightSoft else Color.Transparent, tween(160), label = "seg")
            val fg by animateColorAsState(if (on) c.safelight else c.inkSoft, tween(160), label = "segInk")
            Box(
                Modifier.weight(1f)
                    .squish(pressedScale = 0.95f) { onSelect(value) }
                    .semantics { role = Role.Tab; this.selected = on }
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Txt(label, Theme.type.label.copy(textAlign = TextAlign.Center), fg, maxLines = 1) }
        }
    }
}
