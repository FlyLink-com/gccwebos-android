package com.gccwebos.android.commerce

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    BrandCard(Modifier.testTag("plan-purchase-dock"), contentPadding = PaddingValues(14.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val details: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(summary.title?.let { "已选 · $it" } ?: "请选择套餐",
                        style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                    summary.price?.let { Text(it, color = Brand.Cyan, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleLarge) }
                }
            }
            val action: @Composable (Modifier) -> Unit = { modifier ->
                BrandButton(if (summary.busy) "处理中…" else "查看购买确认", onContinue,
                    enabled = summary.enabled, modifier = modifier.testTag("plan-continue"))
            }
            if (maxWidth < 310.dp || LocalDensity.current.fontScale > 1.3f) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { details(Modifier.fillMaxWidth()); action(Modifier.fillMaxWidth()) }
            } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                details(Modifier.weight(1f)); action(Modifier.weight(1f))
            }
        }
    }
}
