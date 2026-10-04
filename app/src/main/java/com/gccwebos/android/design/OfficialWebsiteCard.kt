package com.gccwebos.android.design

import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gccwebos.android.R

private const val OfficialWebsiteUrl = "https://gccwebos.com"

/** Bundled presentation only. The fixed website is opened exclusively by an explicit click. */
@Composable
internal fun OfficialWebsiteCard(
    modifier: Modifier = Modifier,
    opener: ((String) -> Unit)? = null,
    motionEnabled: Boolean = true,
) {
    val context = LocalContext.current
    var unavailable by remember { mutableStateOf(false) }
    val glow = officialWebsiteGlow(motionEnabled)
    GlassSurface(modifier.fillMaxWidth().testTag("official-website-card")) {
        Column {
            BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .clickable(role = Role.Button, onClickLabel = "访问官网") {
                    unavailable = false
                    try {
                        if (opener != null) opener(OfficialWebsiteUrl)
                        else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OfficialWebsiteUrl)))
                    } catch (_: ActivityNotFoundException) {
                        unavailable = true
                    } catch (_: SecurityException) {
                        unavailable = true
                    }
                }.testTag("official-website-open").padding(horizontal = 14.dp, vertical = 12.dp)) {
                val compact = maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.5f
                val globe: @Composable () -> Unit = {
                    Box(Modifier.size(36.dp).testTag("official-website-globe").drawBehind {
                        drawCircle(Brand.Cyan.copy(alpha = glow.value))
                    }, contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.brand_globe), contentDescription = null,
                            tint = Brand.Cyan, modifier = Modifier.size(24.dp))
                    }
                }
                val title: @Composable () -> Unit = {
                    Text("官网", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                val domain: @Composable () -> Unit = {
                    Text("gccwebos.com", color = Brand.Muted, style = MaterialTheme.typography.bodyMedium,
                        // Use the available line width rather than the rounded intrinsic text width.
                        // The card grows vertically for accessibility fonts; the domain is never clipped.
                        modifier = Modifier.fillMaxWidth().testTag("official-website-domain"))
                }
                val visit: @Composable () -> Unit = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("访问", color = Brand.Cyan, style = MaterialTheme.typography.labelMedium)
                        Icon(painterResource(R.drawable.brand_arrow), contentDescription = null,
                            tint = Brand.Cyan, modifier = Modifier.size(18.dp))
                    }
                }
                if (compact) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        globe()
                        title()
                        Spacer(Modifier.weight(1f))
                        visit()
                    }
                    domain()
                } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    globe()
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        title()
                        domain()
                    }
                    visit()
                }
            }
            if (unavailable) Text("暂时无法打开官网，请安装或启用浏览器后重试。",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)
                    .testTag("official-website-error").semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

@Composable
private fun officialWebsiteGlow(enabled: Boolean): State<Float> {
    // Android supplies a live MotionDurationScale to the composition, including accessibility's
    // remove-animations setting. The platform fallback also takes zero-duration animation seriously.
    val scale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor
        ?: if (ValueAnimator.areAnimatorsEnabled()) 1f else 0f
    if (!enabled || scale <= 0f) return rememberUpdatedState(.08f)
    return rememberInfiniteTransition(label = "official-website-glow").animateFloat(
        initialValue = .05f, targetValue = .15f,
        animationSpec = infiniteRepeatable(tween(3_500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "official-website-glow-alpha",
    )
}
