package com.gccwebos.android.commerce

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gccwebos.android.design.*

internal data class CatalogPurchaseSummary(val title: String?, val price: String?, val enabled: Boolean, val busy: Boolean)

/** The page owns the guarded action. This host retains presentation only while that page exists. */
internal class CatalogDockState {
    var summary by mutableStateOf<CatalogPurchaseSummary?>(null)
        private set
    private var owner: Any? = null
    private var action: (() -> Unit)? = null
    fun show(owner: Any, summary: CatalogPurchaseSummary?, action: () -> Unit) {
        this.owner = owner; this.summary = summary; this.action = action
    }
    fun clear(owner: Any) {
        if (this.owner === owner) { this.owner = null; summary = null; action = null }
    }
    fun continuePurchase() { if (summary?.enabled == true) action?.invoke() }
}

internal val LocalCatalogPurchaseDock = staticCompositionLocalOf<CatalogDockState?> { null }

@Composable
internal fun CatalogPurchaseDock(summary: CatalogPurchaseSummary, onContinue: () -> Unit) {
    BrandCard(Modifier.testTag("plan-purchase-dock"), contentPadding = PaddingValues(10.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val details: @Composable (Modifier) -> Unit = { modifier ->
                // "已选 100 GB 月度套餐 | 30 Coins": the price part is highlighted.
                val line = catalogDockSummary(summary.title, summary.price)
                val price = summary.price?.takeIf { summary.title != null }
                Text(buildAnnotatedString {
                    if (price == null) append(line) else {
                        append(line.removeSuffix(price))
                        withStyle(SpanStyle(color = Brand.Cyan, fontWeight = FontWeight.Bold)) { append(price) }
                    }
                }, modifier.testTag("plan-dock-summary"), style = MaterialTheme.typography.bodyMedium,
                    color = Brand.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val action: @Composable (Modifier) -> Unit = { modifier ->
                BrandButton("立即购买", onContinue,
                    enabled = summary.enabled, haptic = UiHaptic.Primary, modifier = modifier.testTag("plan-continue"))
            }
            if (maxWidth < 260.dp || LocalDensity.current.fontScale > 1.3f) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { details(Modifier.fillMaxWidth()); action(Modifier.fillMaxWidth()) }
            } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                details(Modifier.weight(1.4f)); action(Modifier.weight(1f))
            }
        }
    }
}
