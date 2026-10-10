package com.gccwebos.android.commerce

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gccwebos.android.R
import com.gccwebos.android.design.*
import com.gccwebos.android.portal.AccountController

/** One opaque container; selecting a row reads no additional endpoint or transaction. */
@Composable
internal fun LedgerPanel(account: AccountController, state: CommerceController) {
    var selectedGroup by remember(state, account.authSessionGeneration) { mutableStateOf<LedgerDisplayGroup?>(null) }
    val rows = state.ledgerPage?.entries
    val groups = remember(rows) { rows?.let(LedgerPresentation::displayGroups) }
    val detail = selectedGroup?.takeIf { state.canOperate() && groups?.any { g -> g.id == it.id } == true }
    // One flat 流水: every balance change in server order, no tabs, no date or category sections.
    HistoryListCard(Modifier.testTag("ledger-container")) {
        CommerceReadNotice(state, state.error?.takeUnless { state.busy }, "ledger-error")
        when {
            // First read in flight: the card and title are already on screen with row placeholders.
            rows == null && (state.busy || state.error == null) ->
                SkeletonCards(count = 4, cardHeight = 56.dp, spacing = 8.dp, shape = RoundedCornerShape(14.dp), lines = 0,
                    modifier = Modifier.testTag("ledger-skeleton"))
            groups.isNullOrEmpty() && rows != null -> SupportingText("暂无账单", Modifier.testTag("ledger-empty"))
        }
        if (state.currentSession()) groups?.forEachIndexed { index, group ->
            if (index > 0) HorizontalDivider(color = Brand.RowDivider)
            LedgerRow(group, enabled = !state.busy && state.canOperate()) { selectedGroup = group }
        }
        if (state.ledgerPage?.nextCursor != null) TextButton(onClick = { selectedGroup = null; state.loadLedger(true) },
            enabled = !state.busy && state.canOperate(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ledger-more")) { Text("加载更早账单") }
    }
    detail?.let { group ->
        AlertDialog(onDismissRequest = { selectedGroup = null }, modifier = Modifier.testTag("ledger-detail"),
            title = { Text("余额变动详情") }, text = {
                Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LedgerDetailField("业务", group.title)
                    LedgerDetailField("时间", LedgerPresentation.detailTime(group.occurredAt))
                    LedgerDetailField("合计变动", LedgerPresentation.signedCoins(group.netTotalDeltaUnits), "ledger-detail-total")
                    group.entries.forEach { entry ->
                        HorizontalDivider(color = Brand.RowDivider)
                        LedgerDetailField("明细", LedgerPresentation.categoryLabel(entry.category))
                        LedgerDetailField("币种", LedgerPresentation.coinTypeLabel(entry.coinType))
                        LedgerDetailField("变动", LedgerPresentation.signedCoins(entry.totalDeltaUnits),
                            "ledger-detail-amount-${entry.entryRef}")
                        LedgerDetailField("可用变化", entry.availableDeltaUnits?.let(LedgerPresentation::signedCoins) ?: "未提供",
                            "ledger-detail-available-${entry.entryRef}")
                        LedgerDetailField("待确认变化", entry.pendingDeltaUnits?.let(LedgerPresentation::signedCoins) ?: "未提供",
                            "ledger-detail-pending-${entry.entryRef}")
                        LedgerDetailField("预留变化", entry.heldDeltaUnits?.let(LedgerPresentation::signedCoins) ?: "未提供",
                            "ledger-detail-held-${entry.entryRef}")
                        LedgerDetailField("记录标识", entry.entryRef, "ledger-detail-ref-${entry.entryRef}")
                        entry.relatedOrderRef?.let { LedgerDetailField("关联订单", it) }
                    }
                    // Stable aliases for single-entry groups (instrumentation).
                    if (group.entries.size == 1) {
                        val entry = group.entries.first()
                        LedgerDetailField("可用变化", entry.availableDeltaUnits?.let(LedgerPresentation::signedCoins) ?: "未提供",
                            "ledger-detail-available")
                        LedgerDetailField("预留变化", entry.heldDeltaUnits?.let(LedgerPresentation::signedCoins) ?: "未提供",
                            "ledger-detail-held")
                        LedgerDetailField("记录标识", entry.entryRef, "ledger-detail-ref")
                    }
                }
            }, confirmButton = { TextButton(onClick = { selectedGroup = null },
                modifier = Modifier.heightIn(min = 48.dp).testTag("ledger-detail-close")) { Text("返回账单") } })
    }
}

@Composable
private fun LedgerRow(group: LedgerDisplayGroup, enabled: Boolean, onClick: () -> Unit) {
    val primaryRef = group.id
    val net = group.netTotalDeltaUnits
    val amountColor = when {
        net > 0 -> Brand.Green
        else -> Brand.Text
    }
    val largeFont = LocalDensity.current.fontScale > 1.3f
    Surface(Modifier.fillMaxWidth().testTag("ledger-row-$primaryRef")
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = "查看余额变动详情", onClick = onClick),
        shape = RoundedCornerShape(14.dp), color = Color.Transparent) {
        Row(Modifier.heightIn(min = 64.dp).padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(if (net < 0) R.drawable.brand_receipt else R.drawable.brand_wallet),
                null, tint = Brand.Cyan, modifier = Modifier.size(24.dp))
            val heading: @Composable () -> Unit = {
                Text(group.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                val subtitle = LedgerPresentation.rowTime(group.occurredAt)
                SupportingText(subtitle)
            }
            val amount: @Composable () -> Unit = {
                Text(LedgerPresentation.signedCoins(net), color = amountColor, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("ledger-amount-$primaryRef"))
            }
            if (largeFont) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { heading(); amount() }
            else {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { heading() }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) { amount() }
            }
            Icon(painterResource(R.drawable.brand_arrow), null, tint = Brand.Muted, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun LedgerDetailField(label: String, value: String, tag: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SupportingText(label)
        Text(value, modifier = if (tag == null) Modifier else Modifier.testTag(tag))
    }
}
