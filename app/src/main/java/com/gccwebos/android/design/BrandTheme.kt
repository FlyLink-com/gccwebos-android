package com.gccwebos.android.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Adapted from gccwebos-mobile@091ee6e8 style.css and button-tokens.css. */
internal object Brand {
    val Background = Color(0xFF071122)
    val Surface = Color(0xFF0D1C38)
    val SurfaceRaised = Color(0xFF152C4D)
    val Border = Color(0xFF47618A)
    val Text = Color(0xFFF5F8FF)
    val Muted = Color(0xFFD0DDF1)
    val Cyan = Color(0xFF39D7FF)
    val Blue = Color(0xFF2658FF)
    val Green = Color(0xFF45E3C0)
    val Amber = Color(0xFFFFCB73)
    // Cyan is decorative; the actionable button remains dark enough for white text.
    val ActionStart = Color(0xFF007DA4)
    val ActionMiddle = Color(0xFF235BED)
    val ActionEnd = Color(0xFF6940E7)
    val Shape = RoundedCornerShape(22.dp)
}

@Composable
internal fun GccTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Brand.Cyan, onPrimary = Brand.Background,
            secondary = Brand.Green, onSecondary = Brand.Background,
            background = Brand.Background, onBackground = Brand.Text,
            surface = Brand.Surface, onSurface = Brand.Text,
            surfaceVariant = Brand.SurfaceRaised, onSurfaceVariant = Brand.Muted,
            outline = Brand.Border, error = Color(0xFFFFB4AB),
        ),
        content = content,
    )
}

@Composable
internal fun BrandCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = Brand.Shape, color = Color.Transparent,
        border = BorderStroke(1.dp, Color(0xFFA9CCF1).copy(alpha = .46f))) {
        Column(Modifier.background(Brush.linearGradient(listOf(
            Color(0xFF182B4B).copy(alpha = .94f), Color(0xFF1E2549).copy(alpha = .90f))))
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
internal fun BrandButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                         enabled: Boolean = true) {
    val shape = RoundedCornerShape(14.dp)
    Button(onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape)
            .background(if (enabled) Brush.horizontalGradient(listOf(Brand.ActionStart, Brand.ActionMiddle, Brand.ActionEnd))
                else Brush.horizontalGradient(listOf(Brand.SurfaceRaised, Brand.SurfaceRaised))),
        shape = shape,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent,
            contentColor = Brand.Text, disabledContainerColor = Color.Transparent,
            disabledContentColor = Brand.Muted),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun Eyebrow(text: String) {
    Text(text, color = Brand.Cyan, style = MaterialTheme.typography.labelMedium)
}

@Composable
internal fun SupportingText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = Brand.Muted, style = MaterialTheme.typography.bodyMedium)
}
