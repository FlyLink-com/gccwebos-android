package com.gccwebos.android.design

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

/** Shared native tokens matched to the validated unified preview; business state stays outside. */
internal object Brand {
    val Background = Color(0xFF071122)
    val Surface = Color(GlassMaterialPolicy.CardSolidArgb)
    val SurfaceRaised = Color(0xFF152C4D)
    val Border = Color(0xFF47618A)
    val Text = Color(0xFFF5F9FF)
    val Muted = Color(0xFFD7E5F7)
    val Cyan = Color(0xFF39D7FF)
    val Blue = Color(0xFF2658FF)
    val Green = Color(0xFF45E3C0)
    val Amber = Color(0xFFFFCB73)
    // Cyan is decorative; the actionable button remains dark enough for white text.
    val ActionStart = Color(0xFF176CAE)
    val ActionMiddle = Color(0xFF3157E0)
    val ActionEnd = Color(0xFF6550D2)
    val Disabled = Color(GlassMaterialPolicy.DisabledArgb)
    val DisabledText = Color(GlassMaterialPolicy.DisabledTextArgb)
    val PaymentBlue = Color(GlassMaterialPolicy.PaymentArgb)
    val PaymentDisabled = Color(GlassMaterialPolicy.PaymentDisabledArgb)
    val PaymentDisabledText = Color(GlassMaterialPolicy.PaymentDisabledTextArgb)
    val GlassBorder = Color(0xFFD3E8FF)
    val RowDivider = GlassBorder.copy(alpha = .22f)
    val RowHighlight = Color.White.copy(alpha = .035f)
    val PriceAccent = Color(0xFFB8EEFF)
    val Shape = RoundedCornerShape(22.dp)
}

@Composable
internal fun GccTheme(materialMode: GlassMode? = null, content: @Composable () -> Unit) {
    ProvideGlassMaterial(materialMode) {
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
}

@Composable
internal fun BrandCard(modifier: Modifier = Modifier, selected: Boolean = false,
                      contentPadding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(modifier.fillMaxWidth(), selected = selected) {
        Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
internal fun BrandButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                         enabled: Boolean = true) {
    val shape = RoundedCornerShape(14.dp)
    Button(onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape)
            .background(if (enabled) Brush.horizontalGradient(listOf(Brand.ActionStart, Brand.ActionMiddle, Brand.ActionEnd))
                else Brush.horizontalGradient(listOf(Brand.Disabled, Brand.Disabled))),
        shape = shape,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent,
            contentColor = Brand.Text, disabledContainerColor = Color.Transparent,
            disabledContentColor = Brand.DisabledText),
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
