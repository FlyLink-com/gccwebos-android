package com.gccwebos.android.portal

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.gccwebos.android.design.Brand

/** Static neon gift box with floating coins; drawn once, no bitmaps and no running animation. */
@Composable
internal fun ReferralGiftArt(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height; val u = minOf(w, h) / 100f
        drawCircle(Brush.radialGradient(listOf(Brand.Cyan.copy(alpha = .30f), Color.Transparent),
            center = Offset(w * .5f, h * .62f), radius = 48 * u), 48 * u, Offset(w * .5f, h * .62f))
        val boxLeft = w * .5f - 26 * u; val boxTop = h * .5f
        val body = Size(52 * u, 34 * u)
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF1C3C72), Color(0xFF14254A)), boxTop, boxTop + body.height),
            Offset(boxLeft, boxTop), body, CornerRadius(5 * u))
        drawRoundRect(Brand.Cyan, Offset(boxLeft, boxTop), body, CornerRadius(5 * u), style = Stroke(1.8f * u))
        val lidTop = boxTop - 11 * u
        drawRoundRect(Brush.horizontalGradient(listOf(Brand.ActionStart, Brand.ActionEnd)),
            Offset(boxLeft - 4 * u, lidTop), Size(60 * u, 12 * u), CornerRadius(4 * u))
        drawRoundRect(Brand.Cyan, Offset(boxLeft - 4 * u, lidTop), Size(60 * u, 12 * u), CornerRadius(4 * u), style = Stroke(1.8f * u))
        // Ribbon and bow.
        drawRect(Brand.Amber, Offset(w * .5f - 4 * u, lidTop), Size(8 * u, body.height + 11 * u))
        val bow = Path().apply {
            moveTo(w * .5f, lidTop)
            cubicTo(w * .5f - 20 * u, lidTop - 16 * u, w * .5f - 22 * u, lidTop + 2 * u, w * .5f, lidTop)
            cubicTo(w * .5f + 22 * u, lidTop + 2 * u, w * .5f + 20 * u, lidTop - 16 * u, w * .5f, lidTop)
        }
        drawPath(bow, Brand.Amber, style = Stroke(2.4f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        coin(Offset(w * .5f - 34 * u, h * .5f - 22 * u), 8 * u)
        coin(Offset(w * .5f + 34 * u, h * .5f - 30 * u), 10 * u)
        coin(Offset(w * .5f + 40 * u, h * .5f + 6 * u), 6 * u)
        sparkle(Offset(w * .5f - 40 * u, h * .5f + 14 * u), 4 * u)
        sparkle(Offset(w * .5f + 14 * u, h * .5f - 40 * u), 3 * u)
    }
}

private fun DrawScope.coin(center: Offset, r: Float) {
    drawCircle(Brand.Amber.copy(alpha = .25f), r * 1.6f, center)
    drawCircle(Brush.linearGradient(listOf(Color(0xFFFFE3A3), Brand.Amber, Color(0xFFE59A2F)),
        Offset(center.x - r, center.y - r), Offset(center.x + r, center.y + r)), r, center)
    drawCircle(Color(0xFFFFF4D6), r * .62f, center, style = Stroke(r * .16f))
}

private fun DrawScope.sparkle(center: Offset, r: Float) {
    drawLine(Brand.Cyan, Offset(center.x - r, center.y), Offset(center.x + r, center.y), r * .35f, StrokeCap.Round)
    drawLine(Brand.Cyan, Offset(center.x, center.y - r), Offset(center.x, center.y + r), r * .35f, StrokeCap.Round)
}

internal enum class ReferralGlyph { Copy, Text, Telegram, X, Share }

/** Small line icons for the share row (the project has no icon library). */
@Composable
internal fun ReferralGlyphIcon(glyph: ReferralGlyph, tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val s = size.minDimension; val stroke = Stroke(s * .09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        when (glyph) {
            ReferralGlyph.Copy -> {
                drawRoundRect(tint, p(.32f, .30f), Size(s * .48f, s * .52f), CornerRadius(s * .08f), style = stroke)
                drawRoundRect(tint, p(.18f, .16f), Size(s * .48f, s * .52f), CornerRadius(s * .08f), style = stroke)
            }
            ReferralGlyph.Text -> {
                drawRoundRect(tint, p(.2f, .14f), Size(s * .6f, s * .72f), CornerRadius(s * .08f), style = stroke)
                listOf(.34f, .5f, .66f).forEachIndexed { i, y ->
                    drawLine(tint, p(.32f, y), p(if (i == 2) .54f else .68f, y), s * .08f, StrokeCap.Round)
                }
            }
            ReferralGlyph.Telegram -> drawPath(Path().apply {
                moveTo(.12f * s, .48f * s); lineTo(.86f * s, .18f * s); lineTo(.72f * s, .82f * s)
                lineTo(.46f * s, .62f * s); lineTo(.36f * s, .80f * s); lineTo(.34f * s, .58f * s); close()
                moveTo(.34f * s, .58f * s); lineTo(.86f * s, .18f * s)
            }, tint, style = stroke)
            ReferralGlyph.X -> {
                drawLine(tint, p(.2f, .18f), p(.8f, .82f), s * .1f, StrokeCap.Round)
                drawLine(tint, p(.8f, .18f), p(.2f, .82f), s * .07f, StrokeCap.Round)
            }
            ReferralGlyph.Share -> {
                val a = p(.72f, .22f); val b = p(.28f, .5f); val c = p(.72f, .78f)
                drawLine(tint, a, b, s * .07f); drawLine(tint, b, c, s * .07f)
                listOf(a, b, c).forEach { drawCircle(tint, s * .12f, it) }
            }
        }
    }
}
