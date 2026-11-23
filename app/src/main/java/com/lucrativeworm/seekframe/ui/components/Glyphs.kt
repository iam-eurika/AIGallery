package com.lucrativeworm.seekframe.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Hand-drawn icon set on a 24-unit grid with round caps and a 1.75 stroke: softer and more
 * personal than a stock icon font, and it lets icons morph/animate as plain draw calls.
 */
enum class Glyph(val draw: DrawScope.(Color, Stroke) -> Unit) {
    Search({ c, s ->
        drawCircle(c, 7f, Offset(10.5f, 10.5f), style = s)
        drawLine(c, Offset(15.8f, 15.8f), Offset(20f, 20f), s.width, StrokeCap.Round)
    }),
    Close({ c, s ->
        drawLine(c, Offset(6f, 6f), Offset(18f, 18f), s.width, StrokeCap.Round)
        drawLine(c, Offset(18f, 6f), Offset(6f, 18f), s.width, StrokeCap.Round)
    }),
    Back({ c, s ->
        drawPath(Path().apply { moveTo(14.5f, 5.5f); lineTo(8f, 12f); lineTo(14.5f, 18.5f) }, c, style = s)
    }),
    Forward({ c, s ->
        drawPath(Path().apply { moveTo(9.5f, 5.5f); lineTo(16f, 12f); lineTo(9.5f, 18.5f) }, c, style = s)
    }),
    Sparkle({ c, _ ->
        // Four-point star: the "AI" mark, used sparingly.
        val p = Path().apply {
            moveTo(12f, 2.5f)
            quadraticTo(13f, 11f, 21.5f, 12f)
            quadraticTo(13f, 13f, 12f, 21.5f)
            quadraticTo(11f, 13f, 2.5f, 12f)
            quadraticTo(11f, 11f, 12f, 2.5f)
            close()
        }
        drawPath(p, c)
    }),
    Similar({ c, s ->
        drawRoundRect(c, Offset(3.5f, 7.5f), Size(10.5f, 13f), CornerRadius(2.5f), style = s)
        drawPath(Path().apply {
            moveTo(9.5f, 4.5f); lineTo(18f, 4.5f)
            quadraticTo(20.5f, 4.5f, 20.5f, 7f); lineTo(20.5f, 16.5f)
        }, c, style = s)
    }),
    Share({ c, s ->
        drawPath(Path().apply { moveTo(12f, 14.5f); lineTo(12f, 3.5f); moveTo(7.5f, 8f); lineTo(12f, 3.5f); lineTo(16.5f, 8f) }, c, style = s)
        drawPath(Path().apply { moveTo(5f, 12.5f); lineTo(5f, 18.5f); quadraticTo(5f, 20.5f, 7f, 20.5f); lineTo(17f, 20.5f); quadraticTo(19f, 20.5f, 19f, 18.5f); lineTo(19f, 12.5f) }, c, style = s)
    }),
    Info({ c, s ->
        drawCircle(c, 8.75f, Offset(12f, 12f), style = s)
        drawLine(c, Offset(12f, 11f), Offset(12f, 16.5f), s.width, StrokeCap.Round)
        drawCircle(c, 1.1f, Offset(12f, 7.75f))
    }),
    Photo({ c, s ->
        drawRoundRect(c, Offset(3.5f, 5f), Size(17f, 14f), CornerRadius(3f), style = s)
        drawPath(Path().apply { moveTo(3.5f, 16f); lineTo(9f, 11f); lineTo(13.5f, 15f); lineTo(16f, 12.75f); lineTo(20.5f, 16.5f) }, c, style = s)
        drawCircle(c, 1.5f, Offset(15.5f, 9f))
    }),
    Folder({ c, s ->
        drawPath(Path().apply {
            moveTo(3.5f, 7f); quadraticTo(3.5f, 5f, 5.5f, 5f); lineTo(9.5f, 5f); lineTo(11.5f, 7.5f)
            lineTo(18.5f, 7.5f); quadraticTo(20.5f, 7.5f, 20.5f, 9.5f); lineTo(20.5f, 17f)
            quadraticTo(20.5f, 19f, 18.5f, 19f); lineTo(5.5f, 19f); quadraticTo(3.5f, 19f, 3.5f, 17f); close()
        }, c, style = s)
    }),
    Plus({ c, s ->
        drawLine(c, Offset(12f, 5.5f), Offset(12f, 18.5f), s.width, StrokeCap.Round)
        drawLine(c, Offset(5.5f, 12f), Offset(18.5f, 12f), s.width, StrokeCap.Round)
    }),
    Check({ c, s ->
        drawPath(Path().apply { moveTo(5.5f, 12.5f); lineTo(10f, 17f); lineTo(18.5f, 7.5f) }, c, style = s)
    }),
    Lock({ c, s ->
        drawRoundRect(c, Offset(5f, 10.5f), Size(14f, 10f), CornerRadius(2.5f), style = s)
        drawPath(Path().apply { moveTo(8.5f, 10.5f); lineTo(8.5f, 7.5f); cubicTo(8.5f, 3f, 15.5f, 3f, 15.5f, 7.5f); lineTo(15.5f, 10.5f) }, c, style = s)
    }),
    Pause({ c, s ->
        drawLine(c, Offset(9f, 6.5f), Offset(9f, 17.5f), s.width * 1.3f, StrokeCap.Round)
        drawLine(c, Offset(15f, 6.5f), Offset(15f, 17.5f), s.width * 1.3f, StrokeCap.Round)
    }),
    Play({ c, s ->
        drawPath(Path().apply { moveTo(8.5f, 6f); lineTo(18f, 12f); lineTo(8.5f, 18f); close() }, c, style = s)
    }),
    Camera({ c, s ->
        drawPath(Path().apply {
            moveTo(5f, 7.5f); lineTo(8f, 7.5f); lineTo(9.5f, 5f); lineTo(14.5f, 5f); lineTo(16f, 7.5f); lineTo(19f, 7.5f)
            quadraticTo(21f, 7.5f, 21f, 9.5f); lineTo(21f, 17.5f); quadraticTo(21f, 19.5f, 19f, 19.5f)
            lineTo(5f, 19.5f); quadraticTo(3f, 19.5f, 3f, 17.5f); lineTo(3f, 9.5f); quadraticTo(3f, 7.5f, 5f, 7.5f); close()
        }, c, style = s)
        drawCircle(c, 3.6f, Offset(12f, 13.3f), style = s)
    }),
    /** A loose loop with a tail: "circle something". */
    Lasso({ c, s ->
        drawPath(Path().apply {
            moveTo(9f, 15.2f)
            cubicTo(3.5f, 14.2f, 2.5f, 8f, 8f, 5.5f)
            cubicTo(13f, 3.2f, 21f, 5f, 20.5f, 10f)
            cubicTo(20f, 14.5f, 13f, 16.2f, 9f, 15.2f)
            cubicTo(7f, 16.5f, 7f, 19f, 9.5f, 20.5f)
        }, c, style = s)
    }),
    Copy({ c, s ->
        drawRoundRect(c, Offset(8.5f, 8.5f), Size(11.5f, 12f), CornerRadius(2.5f), style = s)
        drawPath(Path().apply {
            moveTo(15.5f, 6f); lineTo(15.5f, 5.5f); quadraticTo(15.5f, 3.5f, 13.5f, 3.5f); lineTo(6f, 3.5f)
            quadraticTo(4f, 3.5f, 4f, 5.5f); lineTo(4f, 14f); quadraticTo(4f, 16f, 6f, 16f); lineTo(6f, 16f)
        }, c, style = s)
    }),
    Trash({ c, s ->
        drawLine(c, Offset(4f, 6.5f), Offset(20f, 6.5f), s.width, StrokeCap.Round)
        drawPath(Path().apply { moveTo(9f, 6.5f); lineTo(9.5f, 4f); lineTo(14.5f, 4f); lineTo(15f, 6.5f) }, c, style = s)
        drawPath(Path().apply {
            moveTo(6f, 6.5f); lineTo(7f, 18.5f); quadraticTo(7.2f, 20.5f, 9.2f, 20.5f); lineTo(14.8f, 20.5f)
            quadraticTo(16.8f, 20.5f, 17f, 18.5f); lineTo(18f, 6.5f)
        }, c, style = s)
        drawLine(c, Offset(10.25f, 10f), Offset(10.5f, 17f), s.width, StrokeCap.Round)
        drawLine(c, Offset(13.75f, 10f), Offset(13.5f, 17f), s.width, StrokeCap.Round)
    }),
    Pin({ c, s ->
        drawPath(Path().apply {
            moveTo(12f, 21f)
            cubicTo(12f, 21f, 5f, 14.5f, 5f, 9.5f)
            cubicTo(5f, 5.5f, 8.2f, 3f, 12f, 3f)
            cubicTo(15.8f, 3f, 19f, 5.5f, 19f, 9.5f)
            cubicTo(19f, 14.5f, 12f, 21f, 12f, 21f)
            close()
        }, c, style = s)
        drawCircle(c, 2.5f, Offset(12f, 9.5f), style = s)
    }),
    /** Three finder squares and a scatter of modules. */
    Qr({ c, s ->
        listOf(Offset(3.5f, 3.5f), Offset(14f, 3.5f), Offset(3.5f, 14f)).forEach { o ->
            drawRoundRect(c, o, Size(6.5f, 6.5f), CornerRadius(1.5f), style = s)
            drawRect(c, o + Offset(2.25f, 2.25f), Size(2f, 2f))
        }
        drawRect(c, Offset(14f, 14f), Size(2.5f, 2.5f))
        drawRect(c, Offset(18f, 18f), Size(2.5f, 2.5f))
        drawRect(c, Offset(18f, 14f), Size(2.5f, 2.5f))
        drawRect(c, Offset(14f, 18f), Size(2.5f, 2.5f))
    }),
    Link({ c, s ->
        drawPath(Path().apply {
            moveTo(10.5f, 13.5f); cubicTo(11.8f, 15f, 14f, 15.1f, 15.4f, 13.7f); lineTo(18.6f, 10.5f)
            cubicTo(20f, 9f, 20f, 6.8f, 18.6f, 5.4f); cubicTo(17.2f, 4f, 15f, 4f, 13.5f, 5.4f); lineTo(12.3f, 6.6f)
        }, c, style = s)
        drawPath(Path().apply {
            moveTo(13.5f, 10.5f); cubicTo(12.2f, 9f, 10f, 8.9f, 8.6f, 10.3f); lineTo(5.4f, 13.5f)
            cubicTo(4f, 15f, 4f, 17.2f, 5.4f, 18.6f); cubicTo(6.8f, 20f, 9f, 20f, 10.5f, 18.6f); lineTo(11.7f, 17.4f)
        }, c, style = s)
    }),
    /** Two sliders: settings, without the stock cog. */
    Settings({ c, s ->
        drawLine(c, Offset(4f, 8f), Offset(20f, 8f), s.width, StrokeCap.Round)
        drawLine(c, Offset(4f, 16f), Offset(20f, 16f), s.width, StrokeCap.Round)
        drawCircle(c, 2.6f, Offset(9f, 8f), style = s)
        drawCircle(c, 2.6f, Offset(15f, 16f), style = s)
    }),
    /** Three narrowing lines: filter and sort. */
    Filter({ c, s ->
        drawLine(c, Offset(4f, 7f), Offset(20f, 7f), s.width, StrokeCap.Round)
        drawLine(c, Offset(7f, 12f), Offset(17f, 12f), s.width, StrokeCap.Round)
        drawLine(c, Offset(10f, 17f), Offset(14f, 17f), s.width, StrokeCap.Round)
    }),
    /** Bars of a sound wave: what's heard in videos. */
    Wave({ c, s ->
        listOf(4f to 2.5f, 8f to 5.5f, 12f to 8.5f, 16f to 4.5f, 20f to 2f).forEach { (x, h) ->
            drawLine(c, Offset(x, 12f - h), Offset(x, 12f + h), s.width, StrokeCap.Round)
        }
    }),
    /** Speech bubble with two lines of text: spoken words. */
    Speech({ c, s ->
        drawPath(Path().apply {
            moveTo(6.5f, 4.5f); lineTo(17.5f, 4.5f); quadraticTo(20.5f, 4.5f, 20.5f, 7.5f); lineTo(20.5f, 13.5f)
            quadraticTo(20.5f, 16.5f, 17.5f, 16.5f); lineTo(12f, 16.5f); lineTo(7.5f, 20f); lineTo(7.5f, 16.5f)
            lineTo(6.5f, 16.5f); quadraticTo(3.5f, 16.5f, 3.5f, 13.5f); lineTo(3.5f, 7.5f)
            quadraticTo(3.5f, 4.5f, 6.5f, 4.5f); close()
        }, c, style = s)
        drawLine(c, Offset(7.5f, 9f), Offset(16.5f, 9f), s.width, StrokeCap.Round)
        drawLine(c, Offset(7.5f, 12.5f), Offset(13f, 12.5f), s.width, StrokeCap.Round)
    }),
    /** Crescent: overnight, while the phone sleeps. */
    Moon({ c, s ->
        drawPath(Path().apply {
            moveTo(20f, 14.5f)
            cubicTo(18.8f, 18.3f, 15.3f, 20.5f, 11.8f, 20.5f)
            cubicTo(7.2f, 20.5f, 3.5f, 16.8f, 3.5f, 12.2f)
            cubicTo(3.5f, 8.7f, 5.7f, 5.2f, 9.5f, 4f)
            cubicTo(8.3f, 9.8f, 13.6f, 16.2f, 20f, 14.5f)
            close()
        }, c, style = s)
    }),
    Clock({ c, s ->
        drawCircle(c, 8.75f, Offset(12f, 12f), style = s)
        drawPath(Path().apply { moveTo(12f, 7.5f); lineTo(12f, 12f); lineTo(15.25f, 14f) }, c, style = s)
    }),
    /** Arrow leaving a box: "open elsewhere". */
    External({ c, s ->
        drawPath(Path().apply { moveTo(13.5f, 4f); lineTo(20f, 4f); lineTo(20f, 10.5f); moveTo(20f, 4f); lineTo(11f, 13f) }, c, style = s)
        drawPath(Path().apply {
            moveTo(17.5f, 14f); lineTo(17.5f, 18f); quadraticTo(17.5f, 20f, 15.5f, 20f); lineTo(6f, 20f)
            quadraticTo(4f, 20f, 4f, 18f); lineTo(4f, 8.5f); quadraticTo(4f, 6.5f, 6f, 6.5f); lineTo(10f, 6.5f)
        }, c, style = s)
    }),
}

@Composable
fun GlyphIcon(glyph: Glyph, tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 24f
        scale(k, k, pivot = Offset.Zero) {
            glyph.draw(this, tint, Stroke(width = 1.75f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
