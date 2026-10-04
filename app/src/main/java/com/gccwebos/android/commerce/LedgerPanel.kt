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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One glass container; selecting a row reads no additional endpoint or transaction. */
@Composable
internal fun LedgerPanel(account: AccountController, state: CommerceController) {
    var selectedEntry by remember(state, account.authSessionGeneration) { mutableStateOf<WalletLedgerEntry?>(null) }
    val rows = state.ledgerPage?.entries
    val detail = selectedEntry?.takeIf { state.canOperate() && rows?.contains(it) == true }
    BrandCard(Modifier.testTag("ledger-container")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("账单明细", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            if (showReadRetry(state.busy, state.canOperate(), state.error != null, rows == null)) {
                BrandButton("重试", { selectedEntry = null; state.loadLedger() },
                    modifier = Modifier.width(88.dp).testTag("ledger-refresh"))
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("ledger-error")) }
        when {
            state.busy -> Spacer(Modifier.height(8.dp))
            rows == null -> SupportingText("账单尚未读取", Modifier.testTag("ledger-unread"))
            rows.isEmpty() -> SupportingText("暂无账单", Modifier.testTag("ledger-empty"))
        }
        if (state.currentSession()) rows?.groupBy { ledgerDate(it.occurredAt) }?.forEach { (date, entries) ->
            Text(date, style = MaterialTheme.typography.labelLarge, color = Brand.Muted, modifier = Modifier.testTag("ledger-date-$date"))
            entries.forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider(color = Brand.RowDivider)
                LedgerRow(entry, enabled = !state.busy && state.canOperate()) { selectedEntry = entry }
            }
        }
        if (state.ledgerPage?.nextCursor != null) TextButton(onClick = { selectedEntry = null; state.loadLedger(true) },
            enabled = !state.busy && state.canOperate(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ledger-more")) { Text("加载更早账单") }
    }
    detail?.let { entry ->
        AlertDialog(onDismissRequest = { selectedEntry = null }, modifier = Modifier.testTag("ledger-detail"),
            title = { Text("余额变动详情") }, text = {
                Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LedgerDetailField("交易类型", ledgerLabel(entry.category))
                    LedgerDetailField("币种", ledgerRowCoinType(entry.coinType))
                    LedgerDetailField("时间", ledgerDetailTime(entry.occurredAt))
                    LedgerDetailField("总余额变化", ledgerSigned(entry.totalDeltaUnits), "ledger-detail-total")
                    LedgerDetailField("可用变化", ledgerSigned(entry.availableDeltaUnits), "ledger-detail-available")
                    LedgerDetailField("待确认变化", ledgerSigned(entry.pendingDeltaUnits), "ledger-detail-pending")
                    LedgerDetailField("预留变化", ledgerSigned(entry.heldDeltaUnits), "ledger-detail-held")
                    LedgerDetailField("记录金额", formatCoins(entry.amountUnits))
                    LedgerDetailField("记录标识", entry.entryRef, "ledger-detail-ref")
                    entry.relatedOrderRef?.let { LedgerDetailField("关联订单", it) }
                }
            }, confirmButton = { TextButton(onClick = { selectedEntry = null },
                modifier = Modifier.heightIn(min = 48.dp).testTag("ledger-detail-close")) { Text("返回账单") } })
    }
}

@Composable
private fun LedgerRow(entry: WalletLedgerEntry, enabled: Boolean, onClick: () -> Unit) {
    val primary = when {
        entry.totalDeltaUnits != 0L -> "总余额" to entry.totalDeltaUnits
        entry.availableDeltaUnits != null && entry.availableDeltaUnits != 0L -> "可用" to entry.availableDeltaUnits
        entry.pendingDeltaUnits != null && entry.pendingDeltaUnits != 0L -> "待确认" to entry.pendingDeltaUnits
        entry.heldDeltaUnits != null && entry.heldDeltaUnits != 0L -> "预留" to entry.heldDeltaUnits
        else -> "总余额" to entry.totalDeltaUnits
    }
    val largeFont = LocalDensity.current.fontScale > 1.3f
    Surface(Modifier.fillMaxWidth().testTag("ledger-row-${entry.entryRef}")
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = "查看余额变动详情", onClick = onClick),
        shape = RoundedCornerShape(14.dp), color = Color.Transparent) {
        Row(Modifier.heightIn(min = 64.dp).padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(if (entry.category == "available_to_held") R.drawable.brand_lock
                else R.drawable.brand_wallet), null, tint = Brand.Cyan, modifier = Modifier.size(24.dp))
            val heading: @Composable () -> Unit = {
                Text(ledgerLabel(entry.category), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                SupportingText("${ledgerClock(entry.occurredAt)} · ${ledgerRowCoinType(entry.coinType)}")
                if (entry.totalDeltaUnits == 0L) SupportingText("总余额未变", Modifier.testTag("ledger-total-unchanged-${entry.entryRef}"))
            }
            val amount: @Composable () -> Unit = {
                Text(ledgerSigned(primary.second), color = if (primary.second > 0) Brand.Green else Brand.Text,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("ledger-amount-${entry.entryRef}"))
                Text("${primary.first}变化", style = MaterialTheme.typography.labelSmall, color = Brand.Muted,
                    modifier = Modifier.testTag("ledger-qualifier-${entry.entryRef}"))
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

private fun ledgerSigned(value: Long?): String = value?.let { "${if (it > 0) "+" else ""}$it Coins" } ?: "未提供"
private fun ledgerRowCoinType(value: String) = when (value) { "paid_coins" -> "已购 Coins"; "bonus_coins" -> "赠送 Coins"; "reward_coins" -> "奖励 Coins"; else -> "Coins" }
private fun ledgerDate(value: Instant) = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault()).format(value)
private fun ledgerClock(value: Instant) = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(value)
private fun ledgerDetailTime(value: Instant) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(ZoneId.systemDefault()).format(value)
private fun ledgerLabel(value: String) = when(value) {
    "credit" -> "入账"; "pending_to_available" -> "待确认转可用"; "pending_to_reversed" -> "待确认撤回"
    "available_to_held" -> "订单预留"; "held_to_available" -> "预留释放"; "held_to_consumed" -> "订单扣款"
    "held_to_expired", "available_to_expired" -> "余额到期"; "available_to_reversed" -> "可用余额撤回"; else -> "账单记录"
}
