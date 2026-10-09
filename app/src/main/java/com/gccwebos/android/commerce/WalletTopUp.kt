package com.gccwebos.android.commerce

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gccwebos.android.R
import com.gccwebos.android.design.*

private val TopUpGold = Color(0xFFFFD36E)
private val TopUpGoldDeep = Color(0xFFE79A2B)
private val TileShape = RoundedCornerShape(16.dp)

/** Standalone navigation bar: back chevron, centred title, optional record link. Never overlaps the brand header. */
@Composable
internal fun WalletTopBar(title: String, onBack: (() -> Unit)?, onRecords: (() -> Unit)?, enabled: Boolean = true) {
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("wallet-topbar")) {
        if (onBack != null) IconButton(onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart).size(48.dp).testTag("mine-back")
                .semantics { contentDescription = "返回我的" }) {
            Canvas(Modifier.size(18.dp)) {
                val w = size.width; val h = size.height
                val stroke = 2.2.dp.toPx()
                drawLine(Brand.Text, Offset(w * .66f, h * .14f), Offset(w * .30f, h * .5f), stroke, StrokeCap.Round)
                drawLine(Brand.Text, Offset(w * .30f, h * .5f), Offset(w * .66f, h * .86f), stroke, StrokeCap.Round)
            }
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Brand.Text,
            textAlign = TextAlign.Center, maxLines = 1,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 96.dp).semantics { heading() }
                .testTag("wallet-topbar-title"))
        if (onRecords != null) TextButton(onClick = onRecords, enabled = enabled,
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.align(Alignment.CenterEnd).heightIn(min = 48.dp).testTag("wallet-recharge-records")) {
            Text("充值记录", style = MaterialTheme.typography.labelLarge, color = Brand.Cyan)
        }
    }
}

/** Drawn glowing coin; decorative only. */
@Composable
internal fun GlowingCoinIcon(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        drawCircle(Brush.radialGradient(listOf(TopUpGold.copy(alpha = .45f), Color.Transparent), center, r), r)
        val coin = r * .68f
        drawCircle(Brush.linearGradient(listOf(TopUpGold, TopUpGoldDeep), Offset(center.x - coin, center.y - coin),
            Offset(center.x + coin, center.y + coin)), coin)
        drawCircle(Color.White.copy(alpha = .55f), coin * .72f, style = Stroke(coin * .10f))
        // A stylised "C" mark.
        drawArc(Color(0xFF7A4A0C), startAngle = 45f, sweepAngle = 270f, useCenter = false,
            topLeft = Offset(center.x - coin * .38f, center.y - coin * .38f), size = Size(coin * .76f, coin * .76f),
            style = Stroke(coin * .14f, cap = StrokeCap.Round))
    }
}

/** Only the available balance is prominent; held/pending figures live in the detail view. */
@Composable
internal fun WalletTopUpBalance(wallet: Wallet?, onDetails: () -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("当前可用余额", style = MaterialTheme.typography.labelLarge, color = Brand.Muted, modifier = Modifier.weight(1f))
        TextButton(onClick = onDetails, enabled = enabled, contentPadding = PaddingValues(horizontal = 6.dp),
            modifier = Modifier.heightIn(min = 48.dp).testTag("wallet-balance-details")) {
            Text("余额明细 ›", style = MaterialTheme.typography.labelLarge, color = Brand.Cyan)
        }
    }
    val formatted = wallet?.spendableUnits?.let(::formatCoins) ?: "— Coins"
    val amount = formatted.removeSuffix(" Coins")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GlowingCoinIcon(44.dp)
        Text(buildAnnotatedString {
            withStyle(SpanStyle(fontSize = if (amount.length > 9) 30.sp else 38.sp, fontWeight = FontWeight.ExtraBold)) { append(amount) }
            withStyle(SpanStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TopUpGold)) { append(" Coins") }
        }, style = MaterialTheme.typography.headlineLarge.copy(lineHeight = 46.sp), color = Brand.Text,
            modifier = Modifier.weight(1f).testTag("wallet-balance"))
    }
    walletCompositionLine(wallet)?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.Muted, modifier = Modifier.testTag("wallet-composition"))
    }
}

/** Glass amount tile. Selected = neon cyan glow border, faint gradient and a check badge. */
@Composable
internal fun RechargeAmountTile(offer: RechargeOffer, selected: Boolean, enabled: Boolean, unsupportedNote: String?,
    onClick: () -> Unit, modifier: Modifier = Modifier) {
    val glow = Brand.Cyan
    val background = if (selected) Brush.linearGradient(listOf(Brand.Cyan.copy(alpha = .22f), Brand.Blue.copy(alpha = .16f)))
        else Brush.linearGradient(listOf(Color.White.copy(alpha = .06f), Color.White.copy(alpha = .025f)))
    Box(modifier
        .drawBehind {
            if (selected) {
                // Soft outer halo: a few widening, fading strokes approximate a neon glow without blur APIs.
                val radius = 16.dp.toPx()
                for (i in 1..4) {
                    val spread = i * 2.dp.toPx()
                    drawRoundRect(glow.copy(alpha = .16f / i), topLeft = Offset(-spread, -spread),
                        size = Size(size.width + spread * 2, size.height + spread * 2),
                        cornerRadius = CornerRadius(radius + spread), style = Stroke(2.dp.toPx()))
                }
            }
        }
        .clip(TileShape)
        .background(background)
        .border(if (selected) 1.5.dp else 1.dp, if (selected) glow else Brand.GlassBorder.copy(alpha = .22f), TileShape)
        .selectable(selected = selected, role = Role.RadioButton, enabled = enabled, onClick = onClick)
        .heightIn(min = 72.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(formatCoins(offer.paidCoinUnits), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = if (enabled) Brand.Text else Brand.DisabledText, textAlign = TextAlign.Center)
            Text(rechargePriceText(offer), style = MaterialTheme.typography.bodySmall, color = Brand.Cyan, textAlign = TextAlign.Center)
            rechargeBonusBadge(offer)?.let { badge ->
                Text(badge, style = MaterialTheme.typography.labelSmall, color = TopUpGold, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("recharge-bonus-${offer.offerRef}"))
            }
            unsupportedNote?.let { SupportingText(it) }
        }
        offer.promotion?.let { promotion ->
            Text(promotion.label, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = Color(0xFF241400),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart)
                    .background(Brush.horizontalGradient(listOf(TopUpGold, TopUpGoldDeep)), RoundedCornerShape(topStart = 16.dp, bottomEnd = 10.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp).testTag("recharge-promotion-${offer.offerRef}"))
        }
        if (selected) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp).clip(CircleShape).background(glow)
            .testTag("recharge-selected-check-${offer.offerRef}"), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(10.dp)) {
                val w = size.width; val h = size.height; val s = 1.8.dp.toPx()
                drawLine(Brand.Background, Offset(w * .08f, h * .52f), Offset(w * .40f, h * .82f), s, StrokeCap.Round)
                drawLine(Brand.Background, Offset(w * .40f, h * .82f), Offset(w * .94f, h * .18f), s, StrokeCap.Round)
            }
        }
    }
}

/** Radio cards for the methods this client can complete; additional methods only need a new enum entry + flow. */
@Composable
internal fun RechargePaymentMethodSelector(methods: List<RechargePaymentMethod>, selected: RechargePaymentMethod?,
    enabled: Boolean, onSelect: (RechargePaymentMethod) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup().testTag("recharge-payment-methods"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        methods.forEach { method ->
            val chosen = method == selected
            Row(Modifier.fillMaxWidth().clip(TileShape)
                .background(if (chosen) Brand.Cyan.copy(alpha = .10f) else Color.White.copy(alpha = .04f))
                .border(if (chosen) 1.5.dp else 1.dp, if (chosen) Brand.Cyan else Brand.GlassBorder.copy(alpha = .22f), TileShape)
                .selectable(selected = chosen, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(method) })
                .heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("recharge-method-${method.wire}"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PaymentMethodMark(method)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(method.title, style = MaterialTheme.typography.titleSmall, color = Brand.Text)
                    Text(method.subtitle, style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                }
                RadioButton(selected = chosen, onClick = null, enabled = enabled,
                    colors = RadioButtonDefaults.colors(selectedColor = Brand.Cyan, unselectedColor = Brand.Muted))
            }
        }
    }
}

@Composable
private fun PaymentMethodMark(method: RechargePaymentMethod) {
    when (method) {
        RechargePaymentMethod.Alipay -> Surface(color = Color(0xFFF0F9FF), shape = RoundedCornerShape(9.dp), modifier = Modifier.size(36.dp)) {
            Image(painterResource(R.drawable.brand_alipay), null, modifier = Modifier.padding(4.dp))
        }
    }
}

/** Settlement bar: real payable amount on the left, gradient submit on the right. */
@Composable
internal fun RechargeCheckoutBar(payable: String?, enabled: Boolean, onSubmit: () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth().testTag("recharge-checkout-bar"), shape = RoundedCornerShape(20.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            val summary: @Composable (Modifier) -> Unit = { modifier ->
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(color = Brand.Muted, fontSize = 13.sp)) { append("应付金额：") }
                    withStyle(SpanStyle(color = Brand.Cyan, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)) { append(payable ?: "—") }
                }, modifier = modifier.testTag("recharge-payable-amount"))
            }
            val submit: @Composable (Modifier) -> Unit = { modifier ->
                val shape = RoundedCornerShape(24.dp)
                Button(onClick = onSubmit, enabled = enabled, shape = shape,
                    modifier = modifier.heightIn(min = 48.dp).clip(shape).background(
                        if (enabled) Brush.horizontalGradient(listOf(Brand.Cyan, Brand.ActionMiddle, Brand.ActionEnd))
                        else Brush.horizontalGradient(listOf(Brand.Disabled, Brand.Disabled)))
                        .testTag("recharge-selected-pay"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.White,
                        disabledContainerColor = Color.Transparent, disabledContentColor = Brand.DisabledText),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp)) {
                    Text("立即充值", fontWeight = FontWeight.Bold)
                }
            }
            if (maxWidth < 280.dp || LocalDensity.current.fontScale > 1.3f) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { summary(Modifier.fillMaxWidth()); submit(Modifier.fillMaxWidth()) }
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                summary(Modifier.weight(1f)); submit(Modifier)
            }
        }
    }
}
