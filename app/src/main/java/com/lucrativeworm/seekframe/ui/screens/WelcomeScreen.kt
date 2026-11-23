package com.lucrativeworm.seekframe.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.lucrativeworm.seekframe.ui.components.Glyph
import com.lucrativeworm.seekframe.ui.components.GlyphIcon
import com.lucrativeworm.seekframe.ui.components.Txt
import com.lucrativeworm.seekframe.ui.components.squish
import com.lucrativeworm.seekframe.ui.theme.Motion
import com.lucrativeworm.seekframe.ui.theme.Theme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

/**
 * First run. A small stack of "prints" deals itself onto the table and then drifts, which says
 * "photos" without a single stock illustration. The copy promises the one thing that matters:
 * nothing leaves the phone.
 */
@Composable
fun WelcomeScreen(denied: Boolean, onContinue: () -> Unit, onFolders: () -> Unit) {
    val c = Theme.colors
    val t = Theme.type
    Column(
        Modifier.fillMaxSize().background(c.canvas).safeDrawingPadding().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            PrintStack()
        }
        Column(Modifier.padding(bottom = 12.dp)) {
            Txt("Your photos,\nfinally findable.", t.display)
            Spacer(Modifier.height(14.dp))
            Txt(
                if (denied) "Photo access is off. Allow it in Settings, or hand the gallery just the folders you want."
                else "Search by what’s in them: “sunset with the dog”, “that wifi password on the whiteboard”. The model runs on this phone and nothing is uploaded.",
                t.body, c.inkSoft,
            )
            Spacer(Modifier.height(28.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .squish(pressedScale = 0.96f, onClick = onContinue)
                    .clip(RoundedCornerShape(18.dp))
                    .background(c.safelight),
                contentAlignment = Alignment.Center,
            ) {
                Txt(if (denied) "Open settings" else "Open my library", t.label, c.onSafelight)
            }
            Spacer(Modifier.height(6.dp))
            // A private alternative: no photo permission at all, just folders the user hands over.
            Box(
                Modifier.fillMaxWidth().height(48.dp).squish(pressedScale = 0.96f, onClick = onFolders),
                contentAlignment = Alignment.Center,
            ) {
                Txt("Choose specific folders instead", t.label, c.inkSoft)
            }
        }
    }
}

private data class Print(val angle: Float, val dx: Float, val dy: Float, val top: Color, val bottom: Color)

@Composable
private fun PrintStack() {
    val prints = remember {
        listOf(
            Print(-14f, -54f, 14f, Color(0xFF3B5B6E), Color(0xFFE59B5A)), // dusk over water
            Print(9f, 48f, -6f, Color(0xFF2F4A2C), Color(0xFFB7C47A)),    // forest
            Print(-2f, 0f, -20f, Color(0xFFF2C38A), Color(0xFFC0563A)),   // golden hour
        )
    }
    val reduced = Theme.reducedMotion
    val drift by if (reduced) remember { androidx.compose.runtime.mutableFloatStateOf(0f) } else
        rememberInfiniteTransition(label = "drift").animateFloat(
            -1f, 1f, infiniteRepeatable(tween(3600), RepeatMode.Reverse), label = "d",
        )
    Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
        prints.forEachIndexed { i, p ->
            val deal = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                delay(120L + i * 140L)
                deal.animateTo(1f, Motion.settle())
            }
            Box(
                Modifier
                    .size(150.dp, 190.dp)
                    .graphicsLayer {
                        val k = deal.value
                        val wobble = drift * (i - 1) * 1.6f
                        rotationZ = p.angle * k + wobble
                        translationX = p.dx.dp.toPx() * k
                        translationY = (p.dy.dp.toPx() + drift * (2 + i) * 2f) * k + (1 - k) * 180.dp.toPx()
                        alpha = k.coerceIn(0f, 1f)
                    }
                    .shadow(18.dp, RoundedCornerShape(10.dp))
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFF7F1E6))
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 28.dp),
            ) {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp))
                        .background(Brush.verticalGradient(listOf(p.top, p.bottom)))
                )
            }
        }
        // The search sparkle lands last, on top of the pile.
        val pop = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            delay(700)
            coroutineScope { launch { pop.animateTo(1f, Motion.settle()) } }
        }
        Box(
            Modifier
                .graphicsLayer {
                    scaleX = pop.value; scaleY = pop.value
                    translationX = 70.dp.toPx(); translationY = (-96).dp.toPx()
                    rotationZ = (1 - pop.value) * -90f
                }
                .size(52.dp)
                .shadow(12.dp, RoundedCornerShape(50))
                .clip(RoundedCornerShape(50))
                .background(Theme.colors.safelight),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(Glyph.Sparkle, Theme.colors.onSafelight, 24.dp)
        }
    }
}
