package com.gccwebos.android.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/** Shared P0 scale: spacing, radii, type extras and semantic colour. Glass alpha stays in [GlassMaterialPolicy]. */
internal object GccSpacing {
    val ExtraSmall = 4.dp
    val Small = 8.dp
    val Medium = 12.dp
    val Large = 16.dp
    val ExtraLarge = 24.dp
}

internal object GccShapes {
    val SmallRadius = 8.dp
    val MediumRadius = 12.dp
    val CardRadius = 20.dp
    val small = RoundedCornerShape(SmallRadius)
    val medium = RoundedCornerShape(MediumRadius)
    val card = RoundedCornerShape(CardRadius)
    val pill = RoundedCornerShape(percent = 50)
}

/** Gold is the single amber; Danger and Latency currently share the existing critical red. */
internal object GccSemantic {
    val Gold = Color(0xFFFFCB73)
    val GoldDeep = Color(0xFFE59A2F)
    val Danger = Color(0xFFFF5D5D)
    val Latency = Color(0xFFFF5D5D)
}

/** Type styles the current [GccTheme] Typography block does not already name. */
internal object GccType {
    val Display = TextStyle(fontSize = 38.sp, lineHeight = 46.sp, fontWeight = FontWeight.ExtraBold)
    val DisplayCompact = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.ExtraBold)
    val Identity = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
    fun avatarGlyph(size: Dp) = TextStyle(fontSize = (size.value * .36f).sp, fontWeight = FontWeight.Bold)
}

/** Mine/Brand glass rim: 12% white→cyan. Not a GlassMaterialPolicy alpha. */
internal object GccCard {
    val Edge = BorderStroke(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = .12f), Brand.Cyan.copy(alpha = .12f))))
}

private val SpacingScale = listOf(4, 8, 12, 16, 24)
private val ShapeScale = listOf(8, 12, 20)

/** Nearest step on the P0 spacing scale; a tie rounds up. */
internal fun gccSnapSpacing(dp: Int): Int = snapUp(dp, SpacingScale)

/** Nearest of small/medium/card; a tie rounds up. Pill is percent-based and is not a dp step. */
internal fun gccSnapShape(dp: Int): Int = snapUp(dp, ShapeScale)

private fun snapUp(dp: Int, scale: List<Int>): Int =
    scale.minWith(compareBy<Int> { abs(it - dp) }.thenByDescending { it })
