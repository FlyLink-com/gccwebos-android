package com.gccwebos.android.commerce

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gccwebos.android.R
import com.gccwebos.android.design.*

private val CardRadius = 22.dp
private val BadgeStart = Color(0xFFFFB347)
private val BadgeEnd = Color(0xFFFF7A59)
private val BadgeText = Color(0xFF2A1300)

/**
 * The plan list: one glass card per offer, then the compact payment-source row. Selection,
 * enablement and purchase guards stay with the caller; this only renders the snapshot it is given.
 */
@Composable
internal fun CatalogOfferList(shown: List<Offer>, selected: Offer?, enabled: Boolean, category: String,
                              onSelect: (Offer) -> Unit) {
    val bestValue = catalogBestValueRef(shown)
    val needsTitle = catalogOffersNeedingTitle(shown)
    Column(Modifier.fillMaxWidth().testTag("plan-options"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        shown.forEach { offer ->
            CatalogOfferCard(offer, selected == offer, enabled, badge = offer.offerRef == bestValue,
                showTitle = offer.offerRef in needsTitle, onSelect = { onSelect(offer) })
        }
        CatalogBenefits(selected, category)
    }
}

@Composable
private fun CatalogOfferCard(offer: Offer, selected: Boolean, enabled: Boolean, badge: Boolean, showTitle: Boolean,
                             onSelect: () -> Unit) {
    val text = catalogOfferText(offer)
    val glow by animateFloatAsState(if (selected) 1f else 0f, tween(220), label = "plan-glow")
    val edge by animateColorAsState(if (selected) Brand.Cyan else Color.Transparent, tween(220), label = "plan-edge")
    val shape = RoundedCornerShape(CardRadius)
    Box(Modifier.fillMaxWidth().heightIn(min = 94.dp).testTag("plan-offer-${offer.offerRef}")
        .drawBehind {
            // A few widening low-alpha strokes approximate a neon glow without a blur pass.
            if (glow > 0f) {
                val radius = CardRadius.toPx()
                listOf(10.dp to .07f, 6.dp to .12f, 3.dp to .22f).forEach { (spread, alpha) ->
                    val s = spread.toPx()
                    drawRoundRect(Brand.Cyan.copy(alpha = alpha * glow), topLeft = Offset(-s / 2, -s / 2),
                        size = Size(size.width + s, size.height + s), cornerRadius = CornerRadius(radius + s / 2),
                        style = Stroke(width = s))
                }
            }
        }
        .clip(shape)
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)) {
        GlassSurface(Modifier.fillMaxWidth(), shape = shape) {
            Box(Modifier.fillMaxWidth()
                .drawBehind {
                    if (glow > 0f) drawRect(Brush.linearGradient(listOf(Brand.Cyan.copy(alpha = .16f * glow),
                        Brand.Blue.copy(alpha = .06f * glow), Color.Transparent), start = Offset.Zero,
                        end = Offset(size.width, size.height)))
                }
                .border(BorderStroke(1.5.dp, edge), shape)) {
                BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp,
                    top = if (badge) 22.dp else 14.dp, bottom = 14.dp)) {
                    val stacked = maxWidth < 260.dp || LocalDensity.current.fontScale > 1.3f
                    val details: @Composable (Modifier) -> Unit = { modifier ->
                        Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            SelectionDot(selected, enabled)
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(text.headline, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold,
                                        color = Brand.Text, modifier = Modifier.testTag("plan-traffic-${offer.offerRef}"))
                                    text.headlineSuffix?.let {
                                        Text(it, style = MaterialTheme.typography.titleMedium, color = Brand.Muted,
                                            modifier = Modifier.padding(start = 3.dp, bottom = 3.dp))
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${text.kind} · ", style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
                                    Text(text.validity, style = MaterialTheme.typography.bodyMedium, color = Brand.Muted,
                                        modifier = Modifier.testTag("plan-title-${offer.offerRef}"))
                                    text.validitySuffix?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Brand.Muted) }
                                }
                                if (showTitle) Text(offer.title.zh, style = MaterialTheme.typography.bodySmall, color = Brand.Muted,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    val price: @Composable () -> Unit = { CatalogPrice(offer) }
                    if (stacked) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { details(Modifier); price() }
                    else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        details(Modifier.weight(1f)); Spacer(Modifier.width(10.dp)); price()
                    }
                }
                if (badge) BestValueBadge(Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

@Composable
private fun SelectionDot(selected: Boolean, enabled: Boolean) {
    val ring = if (!enabled) Brand.Disabled else if (selected) Brand.Cyan else Brand.Border
    Box(Modifier.size(20.dp).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
        if (selected) Box(Modifier.size(10.dp).background(ring, CircleShape))
    }
}

/** The tagged text stays exactly the formatted server price (e.g. "30 Coins"); only its styling changes. */
@Composable
private fun CatalogPrice(offer: Offer) {
    val formatted = formatCoins(offer.baseCoinUnits)
    val number = formatted.removeSuffix(" Coins")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Image(painterResource(R.drawable.brand_wallet_coin), contentDescription = null, modifier = Modifier.size(20.dp))
        Text(buildAnnotatedString {
            withStyle(SpanStyle(fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Brand.Cyan)) { append(number) }
            withStyle(SpanStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Brand.PriceAccent)) {
                append(formatted.removePrefix(number))
            }
        }, modifier = Modifier.testTag("plan-price-${offer.offerRef}"))
    }
}

@Composable
private fun BestValueBadge(modifier: Modifier) {
    Text("超值推荐", modifier = modifier.testTag("plan-best-value")
        .background(Brush.horizontalGradient(listOf(BadgeStart, BadgeEnd)),
            RoundedCornerShape(topEnd = CardRadius, bottomStart = 12.dp))
        .padding(horizontal = 12.dp, vertical = 3.dp),
        color = BadgeText, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
}

/** Product benefits confirmed by the owner for this page (copy, not server data). */
internal val CatalogProductBenefits = listOf(
    "极速节点" to R.drawable.brand_lightning,
    "多设备通用" to R.drawable.brand_user,
    "安全加密" to R.drawable.brand_lock,
    "全局加速" to R.drawable.brand_globe,
)

/**
 * Validity and traffic are already on the card, so they are never repeated here. Rows are the
 * owner-confirmed product benefits plus the Coin balances the selected offer actually accepts.
 */
@Composable
private fun CatalogBenefits(offer: Offer?, category: String) {
    BrandCard(Modifier.testTag("plan-benefits"), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Text(if (category == "plan") "套餐权益" else "商品权益", style = MaterialTheme.typography.titleSmall, color = Brand.Text)
        val items: List<Pair<String, Int>> = CatalogProductBenefits +
            offer?.acceptedCoinTypes.orEmpty().map { catalogCoinTypeLabel(it) to R.drawable.brand_wallet_coin }
        items.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (label, icon) ->
                    Row(Modifier.weight(1f).heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (icon == R.drawable.brand_wallet_coin)
                            Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
                        else androidx.compose.material3.Icon(painterResource(icon), contentDescription = null,
                            tint = Brand.Cyan, modifier = Modifier.size(18.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = Brand.Text)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (offer == null) SupportingText("选择商品后显示可用的支付余额")
    }
}
