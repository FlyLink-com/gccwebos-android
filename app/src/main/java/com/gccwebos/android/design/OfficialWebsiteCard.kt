package com.gccwebos.android.design

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gccwebos.android.R

internal const val OfficialWebsiteUrl = "https://flinkvpn.com"
internal const val OfficialWebsiteTitle = "其他平台如何使用我们的订阅"
internal const val OfficialWebsiteAction = "查看多端导入教程"

/**
 * Compact single-row link to the multi-platform guide on the official website. Bundled presentation only;
 * the fixed website is opened exclusively by an explicit click. Opaque blue-purple neon gradient with a breathing cyan rim
 * ([BreathingGlowOverlay]: only while visible and resumed, static under reduce-motion, separate overlay under the text).
 */
@Composable
internal fun OfficialWebsiteCard(
    modifier: Modifier = Modifier,
    opener: ((String) -> Unit)? = null,
    /** Kept for callers; the card no longer has any motion to disable. */
    @Suppress("UNUSED_PARAMETER") motionEnabled: Boolean = true,
) {
    val context = LocalContext.current
    var unavailable by remember { mutableStateOf(false) }
    val (shown, trackShown) = rememberShownState()
    GlassSurface(trackShown(modifier.fillMaxWidth()).testTag("official-website-card"),
        edge = BorderStroke(1.dp, Color.White.copy(alpha = .12f)), tint = OfficialWebsiteGradient) {
        Box {
            BreathingGlowOverlay(radius = 22.dp, shown = shown, tag = "official-website-glow")
        Column {
            BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClickLabel = "访问官网 flinkvpn.com") {
                    unavailable = false
                    try {
                        if (opener != null) opener(OfficialWebsiteUrl)
                        else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OfficialWebsiteUrl)))
                    } catch (_: ActivityNotFoundException) {
                        unavailable = true
                    } catch (_: SecurityException) {
                        unavailable = true
                    }
                }.testTag("official-website-open").padding(horizontal = 14.dp, vertical = 10.dp)) {
                val stacked = maxWidth < 240.dp || LocalDensity.current.fontScale >= 1.5f
                val text: @Composable (Modifier) -> Unit = { textModifier ->
                    Column(textModifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(OfficialWebsiteTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth().testTag("official-website-title"))
                        Text(OfficialWebsiteAction, color = Brand.Text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().testTag("official-website-action"))
                    }
                }
                val chevron: @Composable () -> Unit = {
                    Icon(painterResource(R.drawable.brand_arrow), contentDescription = null,
                        tint = Brand.Cyan, modifier = Modifier.size(18.dp))
                }
                if (stacked) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MultiDeviceIcon()
                    Row(verticalAlignment = Alignment.CenterVertically) { text(Modifier.weight(1f)); chevron() }
                } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MultiDeviceIcon()
                    text(Modifier.weight(1f))
                    chevron()
                }
            }
            if (unavailable) Text("暂时无法打开官网",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)
                    .testTag("official-website-error").semantics { liveRegion = LiveRegionMode.Polite })
        }
        }
    }
}

/**
 * Neon blue → indigo → purple (the brand action tokens) under a light [Brand.Background] scrim, fully opaque so the glass
 * behind never shows through. Light enough to read as neon, dark enough for [Brand.Text] at >= 4.5:1 on every stop,
 * including under the surface's white top reflection (checked in OfficialWebsiteGradientTest).
 */
internal val OfficialWebsiteGradientColors = listOf(Brand.ActionStart, Brand.ActionMiddle, Brand.ActionEnd)
    .map { Brand.Background.copy(alpha = .20f).compositeOver(it) }
internal val OfficialWebsiteGradient: androidx.compose.ui.graphics.Brush
    get() = androidx.compose.ui.graphics.Brush.horizontalGradient(OfficialWebsiteGradientColors)

/** Decorative laptop + tablet + phone cluster, drawn in code (no bitmap, no animation). */
@Composable
private fun MultiDeviceIcon() {
    Canvas(Modifier.size(width = 44.dp, height = 36.dp).testTag("official-website-devices").clearAndSetSemantics { }) {
        val stroke = 1.6.dp.toPx()
        val line = Stroke(stroke, cap = StrokeCap.Round)
        fun device(color: Color, left: Float, top: Float, width: Float, height: Float, radius: Float) {
            // Opaque fill so overlapping outlines read as stacked devices.
            drawRoundRect(Color(0xFF0E1A30), Offset(left, top), Size(width, height), CornerRadius(radius))
            drawRoundRect(color, Offset(left, top), Size(width, height), CornerRadius(radius), style = line)
        }
        val w = size.width
        val h = size.height
        // Laptop: screen plus a base line.
        device(Brand.Muted.copy(alpha = .85f), w * .02f, h * .14f, w * .62f, h * .5f, 3.dp.toPx())
        drawLine(Brand.Muted.copy(alpha = .85f), Offset(0f, h * .74f), Offset(w * .68f, h * .74f), stroke, StrokeCap.Round)
        // Tablet, then phone in front.
        device(Brand.Blue.copy(alpha = .95f), w * .5f, h * .3f, w * .3f, h * .58f, 3.dp.toPx())
        device(Brand.Cyan, w * .74f, h * .42f, w * .22f, h * .54f, 2.5.dp.toPx())
        drawDot(Offset(w * .85f, h * .9f), 1.2.dp.toPx())
    }
}

private fun DrawScope.drawDot(center: Offset, radius: Float) = drawCircle(Brand.Cyan, radius, center)
