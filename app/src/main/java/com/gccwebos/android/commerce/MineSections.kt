package com.gccwebos.android.commerce

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gccwebos.android.R
import com.gccwebos.android.design.Brand
import com.gccwebos.android.design.GlassSurface

/**
 * Visual building blocks of the 我的 page. Static gradients and 1px rims only: no blur, no animation,
 * no account data. Callers own every enabled state and click target.
 */
internal object MineStyle {
    val CardShape = RoundedCornerShape(20.dp)
    /** ~12% white→cyan rim, the "glass edge" of every Mine card. */
    val Edge = BorderStroke(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = .12f), Brand.Cyan.copy(alpha = .12f))))
    val Chevron = Color(0xFFA9B9CE)
    val Gold = Color(0xFFFFCB73)
    val GoldDeep = Color(0xFFE59A2F)
    /** Electric blue into a faint gold corner for the standalone wallet card. */
    val WalletTint = Brush.linearGradient(listOf(Color(0xFF2658FF).copy(alpha = .30f),
        Color(0xFF39D7FF).copy(alpha = .08f), Color(0xFFFFCB73).copy(alpha = .16f)))
    /** Deep violet/navy wash for the invite banner. */
    val InviteTint = Brush.linearGradient(listOf(Color(0xFF4A2A8C).copy(alpha = .42f),
        Color(0xFF0E2C55).copy(alpha = .30f), Color(0xFF39D7FF).copy(alpha = .10f)))
}

@Composable
internal fun MineCard(modifier: Modifier = Modifier, tint: Brush? = null,
                      contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                      verticalSpacing: Dp = 8.dp, content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(modifier.fillMaxWidth(), shape = MineStyle.CardShape, edge = MineStyle.Edge, tint = tint) {
        Column(Modifier.fillMaxWidth().padding(contentPadding), verticalArrangement = Arrangement.spacedBy(verticalSpacing),
            content = content)
    }
}

@Composable
internal fun MineRowDivider() = HorizontalDivider(color = Brand.Border.copy(alpha = .35f))

/** Leading icon on a soft cyan halo; disabled rows drop the halo and use the muted tint. */
@Composable
internal fun MineGlowIcon(icon: Int, enabled: Boolean, size: Dp = 20.dp) {
    Box(Modifier.size(34.dp).drawBehind {
        if (enabled) drawCircle(Brush.radialGradient(listOf(Brand.Cyan.copy(alpha = .26f), Color.Transparent),
            center = center, radius = this.size.minDimension / 2))
    }, contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, tint = if (enabled) Brand.Cyan else Brand.Muted, modifier = Modifier.size(size))
    }
}

@Composable
internal fun MineMenuRow(title: String, tag: String, enabled: Boolean = true, subtitle: String? = null, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(tag)) {
        val icon = when (tag) {
            "mine-history" -> R.drawable.brand_receipt
            "mine-security" -> R.drawable.brand_lock
            "mine-referral" -> R.drawable.brand_share
            "mine-telegram" -> R.drawable.brand_paper_plane
            "mine-channel" -> R.drawable.brand_link
            "mine-more" -> R.drawable.brand_refresh
            else -> R.drawable.brand_arrow
        }
        MineGlowIcon(icon, enabled)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = if (enabled) Brand.Text else Brand.Muted)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.Muted) }
        }
        Spacer(Modifier.width(8.dp))
        Text("›", fontSize = 20.sp, color = MineStyle.Chevron.copy(alpha = if (enabled) 1f else .5f))
    }
}

/**
 * The share entry as a banner. The whole banner is the single click target (tag [tag]); the
 * 立即邀请 pill is a visual affordance inside it, not a second button. Copy is unchanged from the
 * list row because the referral reward is a server-set, eligibility-gated percentage.
 */
@Composable
internal fun MineInviteBanner(tag: String, enabled: Boolean, onClick: () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth().testTag("mine-referral-banner"), shape = MineStyle.CardShape,
        edge = MineStyle.Edge, tint = MineStyle.InviteTint) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).testTag(tag)
            .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GiftIcon(40.dp, enabled)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("分享一份好连接", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = if (enabled) Brand.Text else Brand.Muted)
                Text("邀请好友 · 查看推荐与奖励", style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
            }
            Surface(shape = RoundedCornerShape(50), color = Color.Transparent,
                modifier = Modifier.drawBehind {
                    drawRoundRect(if (enabled) Brush.horizontalGradient(listOf(Brand.ActionStart, Brand.ActionEnd))
                        else Brush.horizontalGradient(listOf(Brand.Disabled, Brand.Disabled)),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
                }) {
                Text("立即邀请", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    color = if (enabled) Brand.Text else Brand.DisabledText, maxLines = 1,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).testTag("mine-referral-cta"))
            }
        }
    }
}

/** Small gold coin, drawn; decorative only. */
@Composable
internal fun CoinIcon(size: Dp, enabled: Boolean = true) {
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2
        val gold = if (enabled) MineStyle.Gold else Brand.Muted
        val deep = if (enabled) MineStyle.GoldDeep else Brand.Border
        drawCircle(Brush.radialGradient(listOf(gold.copy(alpha = .45f), Color.Transparent), center, r))
        drawCircle(Brush.linearGradient(listOf(gold, deep), Offset.Zero, Offset(this.size.width, this.size.height)), r * .78f)
        drawCircle(Color.White.copy(alpha = .55f), r * .56f, style = Stroke(width = r * .09f))
        // Stylised "C" mark.
        drawArc(Color.White.copy(alpha = .9f), startAngle = 45f, sweepAngle = 270f, useCenter = false,
            topLeft = Offset(center.x - r * .3f, center.y - r * .3f), size = Size(r * .6f, r * .6f),
            style = Stroke(width = r * .12f))
    }
}

/** Gift box with ribbon and bow, drawn; decorative only. */
@Composable
internal fun GiftIcon(size: Dp, enabled: Boolean = true) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val box = if (enabled) Brand.Cyan else Brand.Muted
        val ribbon = if (enabled) MineStyle.Gold else Brand.DisabledText
        drawCircle(Brush.radialGradient(listOf(box.copy(alpha = .22f), Color.Transparent), center, w / 2))
        val lidTop = h * .36f; val lidH = h * .14f
        drawRoundRect(box.copy(alpha = .9f), Offset(w * .18f, lidTop), Size(w * .64f, lidH),
            androidx.compose.ui.geometry.CornerRadius(w * .03f))
        drawRoundRect(box.copy(alpha = .55f), Offset(w * .23f, lidTop + lidH), Size(w * .54f, h * .34f),
            androidx.compose.ui.geometry.CornerRadius(w * .03f))
        drawRect(ribbon, Offset(w * .46f, lidTop), Size(w * .08f, lidH + h * .34f))
        val bow = Path().apply {
            moveTo(w * .5f, lidTop)
            cubicTo(w * .30f, h * .14f, w * .22f, h * .32f, w * .5f, lidTop)
            moveTo(w * .5f, lidTop)
            cubicTo(w * .70f, h * .14f, w * .78f, h * .32f, w * .5f, lidTop)
        }
        drawPath(bow, ribbon, style = Stroke(width = w * .06f))
    }
}

/**
 * Masks the verified session email for display: keeps the first characters of the local part
 * (at most 3, never more than half) and the full domain. Non-email values are masked the same way.
 */
internal fun maskAccountEmail(email: String): String {
    val value = email.trim()
    if (value.isEmpty()) return value
    val at = value.lastIndexOf('@')
    if (at == 0) return "***$value"
    val local = if (at > 0) value.substring(0, at) else value
    val domain = if (at > 0) value.substring(at) else ""
    val keep = ((local.length + 1) / 2).coerceIn(1, 3).coerceAtMost(local.length)
    return local.take(keep) + "***" + domain
}

@Composable
internal fun MineWalletCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    MineCard(modifier, tint = MineStyle.WalletTint, contentPadding = PaddingValues(16.dp), verticalSpacing = 10.dp,
        content = content)
}

@Composable
internal fun MineBalanceHeading() {
    Text("可用余额", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Brand.Muted)
}

/**
 * Primary gradient 钱包充值 and secondary translucent-outline 账单明细. Tags and labels are the
 * contract used by tests; [enabled] = false keeps both inert with disabled semantics.
 */
@Composable
internal fun MineWalletActions(enabled: Boolean, onRecharge: () -> Unit, onLedger: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val recharge: @Composable (Modifier) -> Unit = { modifier ->
            com.gccwebos.android.design.BrandButton("钱包充值", onRecharge, enabled = enabled,
                modifier = modifier.heightIn(min = 48.dp).testTag("mine-recharge"))
        }
        val ledger: @Composable (Modifier) -> Unit = { modifier ->
            androidx.compose.material3.OutlinedButton(onClick = onLedger, enabled = enabled, shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, if (enabled) Brand.GlassBorder.copy(alpha = .45f) else Brand.Border.copy(alpha = .35f)),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.White.copy(alpha = .05f), contentColor = Brand.Text,
                    disabledContainerColor = Color.White.copy(alpha = .02f), disabledContentColor = Brand.DisabledText),
                modifier = modifier.heightIn(min = 48.dp).testTag("mine-ledger")) { Text("账单明细", fontWeight = FontWeight.Medium) }
        }
        if (maxWidth < 260.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                recharge(Modifier.fillMaxWidth()); ledger(Modifier.fillMaxWidth())
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            recharge(Modifier.weight(1f)); ledger(Modifier.weight(1f))
        }
    }
}
