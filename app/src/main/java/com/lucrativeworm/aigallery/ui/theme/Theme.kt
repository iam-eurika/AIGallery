package com.lucrativeworm.aigallery.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * "Darkroom": photos are the only saturated thing on screen. Chrome is warm, near-neutral ink
 * and paper, with a single safelight-amber accent reserved for the AI moments (search, indexing).
 */
@Immutable
data class Palette(
    val canvas: Color,
    val raised: Color,
    val sunken: Color,
    val line: Color,
    val ink: Color,
    val inkSoft: Color,
    val inkFaint: Color,
    val safelight: Color,
    val safelightSoft: Color,
    val onSafelight: Color,
    val isDark: Boolean,
)

private val Darkroom = Palette(
    canvas = Color(0xFF100F0D),
    raised = Color(0xFF1C1A17),
    sunken = Color(0xFF0A0908),
    line = Color(0x1FF3EDE2),
    ink = Color(0xFFF3EDE2),
    inkSoft = Color(0xB3F3EDE2),
    inkFaint = Color(0x66F3EDE2),
    safelight = Color(0xFFFFA24C),
    safelightSoft = Color(0x29FFA24C),
    onSafelight = Color(0xFF1E1306),
    isDark = true,
)

private val Contact = Palette(
    canvas = Color(0xFFF6F2EA),
    raised = Color(0xFFFFFCF6),
    sunken = Color(0xFFEAE4D8),
    line = Color(0x1F1E1A14),
    ink = Color(0xFF1E1A14),
    inkSoft = Color(0xB31E1A14),
    inkFaint = Color(0x661E1A14),
    safelight = Color(0xFFD9661A),
    safelightSoft = Color(0x24D9661A),
    onSafelight = Color(0xFFFFF8EF),
    isDark = false,
)

@Immutable
data class Type(
    val display: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    val numeric: TextStyle,
)

private val AppType = Type(
    // System font throughout, iOS-style hierarchy: heavy, tightly tracked titles; plain body;
    // tabular figures for numbers instead of a monospace face.
    display = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = (-0.6).sp),
    title = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp),
    body = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp),
    label = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 19.sp, letterSpacing = (-0.1).sp),
    caption = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    numeric = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = "tnum"),
)

/** Motion tokens. Everything interactive is a spring so gestures can be interrupted mid-flight. */
object Motion {
    /** Press-in: fast, no overshoot, so the finger feels in control. */
    fun <T> press(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 1400f)
    /** Release / settle: a little bounce gives physical weight. */
    fun <T> settle(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.55f, stiffness = 600f)
    /** Layout & content changes. */
    fun <T> glide(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.86f, stiffness = 380f)
    fun <T> gentle(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
    const val STAGGER_MS = 28
}

val LocalPalette = staticCompositionLocalOf { Darkroom }
val LocalType = staticCompositionLocalOf { AppType }
/** True when the user turned animations off; ambient/looping motion stops, feedback stays instant. */
val LocalReducedMotion = staticCompositionLocalOf { false }

object Theme {
    val colors: Palette @Composable get() = LocalPalette.current
    val type: Type @Composable get() = LocalType.current
    val reducedMotion: Boolean @Composable get() = LocalReducedMotion.current
}

@Composable
fun GalleryTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val reduced = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    CompositionLocalProvider(
        LocalPalette provides if (dark) Darkroom else Contact,
        LocalType provides AppType,
        LocalReducedMotion provides reduced,
        content = content,
    )
}
