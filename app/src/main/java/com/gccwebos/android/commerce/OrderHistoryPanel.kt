package com.gccwebos.android.commerce

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gccwebos.android.design.*
import com.gccwebos.android.portal.AccountController
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Details are an explicit read. The list composition, cursor, filter, and scroll survive return. */
@Composable
internal fun OrderHistoryPanel(account: AccountController, state: CommerceController,
    detailOverride: OrderDetailController? = null) {
    val scope = rememberCoroutineScope()
    val details = detailOverride ?: remember(account.authSessionGeneration, state) {
        OrderDetailController(account, scope, state::historyDetail)
    }
    var chosenCategory by remember(account.authSessionGeneration, state) { mutableStateOf<String?>(null) }
    val category = when {
        chosenCategory == "recharge" -> "recharge"
        state.commerceModel == CommerceModel.LEGACY -> "orders"
        state.commerceModel == CommerceModel.WALLET_FIRST -> "plans"
        else -> null
    }
    DisposableEffect(details) { onDispose { details.dispose() } }
    BackHandler(enabled = details.visible, onBack = details::back)
    val selectedRows = when (category) {
        "orders" -> state.orderPage?.orders?.size
        "plans" -> state.transitions?.size
        "recharge" -> state.rechargeHistory?.size
        else -> null
    }
    val listError = historyReadError(selectedRows, state.busy,
        if (category == "recharge") state.rechargeHistoryError else state.purchaseHistoryError, state.error)

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("订单", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f))
        if (showReadRetry(state.busy, state.canOperate(), listError != null, selectedRows == null))
            BrandButton("重试", state::load, modifier = Modifier.width(88.dp).testTag("commerce-refresh"))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val purchaseCategory = when (state.commerceModel) {
            CommerceModel.LEGACY -> "orders" to "商城"
            CommerceModel.WALLET_FIRST -> "plans" to "套餐计划"
            null -> null
        }
        listOfNotNull(purchaseCategory, "recharge" to "充值").forEach { (key, label) ->
            FilterChip(selected = category == key, onClick = { chosenCategory = key },
                label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp).testTag("orders-$key"))
        }
    }
    BrandCard(Modifier.testTag("orders-state-separation"), contentPadding = PaddingValues(12.dp)) {
        when (category) {
            "orders" -> {
                val page = state.orderPage
                HistoryReadState(page?.orders?.size, state.busy, state.purchaseHistoryError, "商城订单", state.error)
                HistoryGroups(page?.orders.orEmpty(), { it.createdAt }) { order ->
                    val selection = HistorySelection(HistoryKind.Legacy, order.ref)
                    HistorySummary("history-order-${order.ref}", historyOfferTitle(order.offer),
                        formatCoins(order.amountUnits), if (order.offer.category == "data_pack") "流量包购买" else "套餐购买",
                        purchaseStatePairs(order), null,
                        order.ref, order.createdAt, com.gccwebos.android.R.drawable.brand_receipt,
                        details.selected == selection, !state.busy && !details.busy) { details.open(selection) }
                }
                if (page?.nextCursor != null) BrandButton("加载更早订单", state::moreOrders, enabled = !state.busy,
                    modifier = Modifier.testTag("orders-more"))
            }
            "plans" -> {
                HistoryReadState(state.transitions?.size, state.busy, state.purchaseHistoryError, "套餐计划", state.error)
                HistoryGroups(state.transitions.orEmpty(), { it.createdAt }) { order ->
                    val selection = HistorySelection(HistoryKind.Deferred, order.ref)
                    HistorySummary("history-order-${order.ref}", historyOfferTitle(order.offer),
                        formatCoins(order.amountUnits), "套餐计划", purchaseStatePairs(order), null,
                        order.ref, order.createdAt, com.gccwebos.android.R.drawable.brand_receipt,
                        details.selected == selection, !state.busy && !details.busy) { details.open(selection) }
                }
            }
            "recharge" -> {
                HistoryReadState(state.rechargeHistory?.size, state.busy, state.rechargeHistoryError, "充值", state.error)
                HistoryGroups(state.rechargeHistory.orEmpty(), { it.createdAt }) { order ->
                    val selection = HistorySelection(HistoryKind.Recharge, order.orderRef)
                    HistorySummary("history-recharge-${order.orderRef}", "钱包充值",
                        formatMoney(order.amountMinor, order.currency, order.currencyExponent), "支付充值",
                        listOf("充值" to historyRechargeStatus(order.status), "到账" to historyCreditState(order.status)),
                        "预计 ${formatCoins(order.expectedPaidCoinUnits)}", order.orderRef, order.createdAt,
                        com.gccwebos.android.R.drawable.brand_wallet,
                        details.selected == selection, !state.busy && !details.busy) {
                        details.open(selection)
                    }
                }
            }
            else -> HistoryReadState(null, state.busy, state.purchaseHistoryError, "订单类型", state.error)
        }
    }
    if (details.visible) OrderDetailsDialog(details)
}

@Composable
private fun HistoryReadState(size: Int?, busy: Boolean, specificError: String?, label: String, globalError: String?) {
    val error = historyReadError(size, busy, specificError, globalError)
    if (error != null) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("history-error"))
    if (busy && size == null) { Spacer(Modifier.height(8.dp)); return }
    if (size == null || size == 0) Surface(Modifier.fillMaxWidth().testTag("history-read-state"),
        shape = Brand.Shape, color = Color.Transparent) {
        Row(Modifier.heightIn(min = 48.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(com.gccwebos.android.R.drawable.brand_receipt), null, tint = Brand.Muted,
                modifier = Modifier.size(24.dp))
            Text(when {
                busy -> label
                size == null && error != null -> "${label}查询失败"
                size == null -> "${label}尚未读取"
                label == "商城订单" -> "暂无这类订单"
                else -> "暂无${label}记录"
            }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("history-read-title"))
        }
    }
}

/** Whole-load failures matter only while this history is unread; a wallet error cannot erase a known result. */
internal fun historyReadError(size: Int?, busy: Boolean, specificError: String?, globalError: String?): String? =
    specificError ?: globalError?.takeIf { size == null && !busy }

@Composable
private fun <T> HistoryGroups(rows: List<T>, createdAt: (T) -> Instant, content: @Composable (T) -> Unit) {
    // Preserve the server's cursor/order. Only consecutive rows on the same local day share a heading.
    rows.forEachIndexed { index, row ->
        val timestamp = createdAt(row)
        val day = historyDay(timestamp)
        if (index > 0) HorizontalDivider(color = Brand.RowDivider)
        if (index == 0 || day != historyDay(createdAt(rows[index - 1]))) {
            Text(historyDateHeading(timestamp), color = Brand.Muted, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp).testTag("history-date-$day"))
        }
        content(row)
    }
}

@Composable
private fun HistorySummary(tag: String, title: String, amount: String, type: String,
    states: List<Pair<String, String>>, expected: String?, ref: String, createdAt: Instant, icon: Int,
    isSelected: Boolean, enabled: Boolean, onDetail: () -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag(tag).semantics { selected = isSelected },
        shape = RoundedCornerShape(14.dp), color = Color.Transparent,
        border = if (isSelected) BorderStroke(1.dp, Brand.Cyan) else null) {
        BoxWithConstraints {
            val stackedAmount = maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.5f || amount.length > 13
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = "查看详情", onClick = onDetail)
                .testTag("history-detail-$ref").padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(10.dp), color = Brand.Cyan.copy(alpha = .10f),
                    modifier = Modifier.size(34.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(painterResource(icon), null, tint = Brand.Cyan, modifier = Modifier.size(22.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (stackedAmount) {
                        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        HistoryAmount(amount, "$tag-amount")
                    } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        HistoryAmount(amount, "$tag-amount")
                    }
                    Text("${historyClock(createdAt)} · $type", color = Brand.Muted,
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("$tag-time"))
                    states.forEachIndexed { index, (label, value) ->
                        Text("$label · $value", color = Brand.Muted, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("$tag-state-$index"))
                    }
                    expected?.let { Text(it, color = Brand.Muted, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("$tag-expected")) }
                }
                Icon(painterResource(com.gccwebos.android.R.drawable.brand_arrow), null, tint = Brand.Muted,
                    modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun HistoryAmount(amount: String, tag: String) {
    // An order amount is neither a debit nor a confirmed wallet credit.
    Text(amount, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        color = Brand.Text, modifier = Modifier.testTag(tag))
}

@Composable
private fun OrderDetailsDialog(state: OrderDetailController) {
    AlertDialog(onDismissRequest = state::back,
        title = { Text("订单详情", modifier = Modifier.testTag("history-detail-title")) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.selected?.let { DetailField("记录", it.ref) }
                if (state.busy) {
                    Spacer(Modifier.height(14.dp))
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("history-detail-error")) }
                when (val detail = state.value) {
                    is HistoryDetail.Purchase -> PurchaseDetails(detail.value)
                    is HistoryDetail.Recharge -> RechargeDetails(detail.value)
                    null -> Unit
                }
            }
        },
        confirmButton = { TextButton(onClick = state::back,
            modifier = Modifier.heightIn(min = 48.dp).testTag("history-detail-back")) { Text("返回列表") } },
        dismissButton = {
            if (showReadRetry(state.busy, true, state.error != null, false)) TextButton(onClick = state::retry,
                modifier = Modifier.heightIn(min = 48.dp).testTag("history-detail-retry")) { Text("重试查询") }
        })
}

@Composable
private fun PurchaseDetails(value: PurchaseResult) {
    DetailField("商品", value.offer.title.zh)
    DetailField("订单金额", formatCoins(value.amountUnits))
    DetailField("商品交付", if (value.offer.category == "data_pack") "${value.offer.trafficGb} GB 独立流量包"
        else "${value.offer.durationDays} 天 · 每月 ${value.offer.monthlyTrafficGb} GB")
    value.offer.importantNotices.zh.forEach { SupportingText(it) }
    when (value) {
        is PurchaseResult.Order -> {
            DetailField("支付状态", paymentState(value.paymentStatus), "history-payment-state")
            DetailField("资源交付", fulfillmentState(value.fulfillmentStatus), "history-fulfillment-state")
            value.fulfillmentSafeCode?.let { DetailField("交付核对代码", it) }
            value.allocation.forEach { DetailField("${coinTypeLabel(it.coinType)}分配", formatCoins(it.amountUnits)) }
            DetailField("可取消窗口截至", historyTime(value.cancelUntil))
            DetailField("创建时间", historyTime(value.createdAt))
            DetailField("更新时间", historyTime(value.updatedAt))
        }
        is PurchaseResult.Deferred -> {
            DetailField("计划状态", deferredState(value.state), "history-transition-state")
            DetailField("计划类型", if (value.flowKind == "initial_activation") "首次启用" else "计划切换")
            DetailField("计划生效时间", historyTime(value.scheduledFor), "history-scheduled-for")
            DetailField("预留时间", historyTime(value.reservedAt))
            DetailField("结算时间", value.settledAt?.let(::historyTime) ?: "服务器未提供")
            value.safeCode?.let { DetailField("核对代码", it) }
            DetailField("创建时间", historyTime(value.createdAt))
            DetailField("更新时间", historyTime(value.updatedAt))
            SupportingText("计划安排与结算状态不代表本机已有可用订阅，请另行同步资源。")
        }
    }
}

@Composable
private fun RechargeDetails(value: RechargeOrder) {
    DetailField("充值金额", formatMoney(value.amountMinor, value.currency, value.currencyExponent))
    DetailField("预计到账", formatCoins(value.expectedPaidCoinUnits))
    DetailField("充值状态", rechargeStatus(value.status), "history-recharge-state")
    DetailField("Coins 到账", historyCreditState(value.status), "history-credit-state")
    DetailField("支付方式", when (value.paymentMethod) { "alipay" -> "支付宝"; "wechat_pay" -> "微信支付"; else -> "PayPal" })
    DetailField("订单有效至", historyTime(value.expiresAt))
    DetailField("创建时间", historyTime(value.createdAt))
    DetailField("更新时间", historyTime(value.updatedAt))
}

@Composable
private fun DetailField(label: String, value: String, tag: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        SupportingText(label)
        Text(value, style = MaterialTheme.typography.bodyLarge,
            modifier = if (tag == null) Modifier else Modifier.testTag(tag))
    }
}

internal fun paymentState(value: String) = when (value) {
    "held" -> "Coins 已预留"; "capture_pending" -> "扣款处理中"; "captured" -> "已扣款"
    "cancelled" -> "已取消"; "capture_failed" -> "扣款未完成"; else -> "需要对账"
}
internal fun fulfillmentState(value: String) = when (value) {
    "not_requested" -> "尚未开始"; "pending" -> "等待发放"; "processing" -> "发放中"
    "delivered" -> "已发放，请同步资源"; else -> "发放失败，需核对"
}
internal fun deferredState(value: String) = when (value) {
    "reserved" -> "已预留，等待生效"; "processing" -> "处理中"; "effect_applied" -> "权益已处理，等待结算"
    "settled" -> "已结算，请同步资源"; "requote_required" -> "需要重新报价，请先核对原记录"
    "cancelled" -> "已取消"; else -> "需要对账，请勿重复购买"
}
private fun coinTypeLabel(value: String) = when (value) { "paid_coins" -> "充值 Coins"; "bonus_coins" -> "赠送 Coins"; else -> "奖励 Coins" }
private fun historyOfferTitle(offer: Offer) = if (offer.category == "plan" && offer.durationDays != null)
    "${offer.durationDays} 天套餐" else offer.title.zh
private fun purchaseStatePairs(value: PurchaseResult): List<Pair<String, String>> = when (value) {
    is PurchaseResult.Order -> listOf("扣币" to paymentState(value.paymentStatus),
        "交付" to fulfillmentState(value.fulfillmentStatus))
    is PurchaseResult.Deferred -> listOf("计划" to deferredState(value.state))
}
private fun historyRechargeStatus(status: String) = when (status) {
    "created" -> "订单已创建"; "entry_ready" -> "等待付款"; "pending" -> "等待支付确认"
    "payment_verified" -> "支付已核验"; "credited" -> "充值已到账"
    "failed_no_charge" -> "服务器确认未扣款"; "expired" -> "订单已过期"
    else -> "需要对账，请勿重复付款"
}
private fun historyCreditState(status: String) = when (status) {
    "credited" -> "Coins 已到账"; "payment_verified" -> "等待 Coins 到账"; else -> "未记为到账"
}
private fun historyDay(value: Instant) = value.atZone(ZoneId.systemDefault()).toLocalDate().toString()
private fun historyDateHeading(value: Instant) = DateTimeFormatter.ofPattern("yyyy-MM-dd z")
    .withZone(ZoneId.systemDefault()).format(value)
private fun historyClock(value: Instant) = DateTimeFormatter.ofPattern("HH:mm")
    .withZone(ZoneId.systemDefault()).format(value)
private fun historyTime(value: Instant) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(ZoneId.systemDefault()).format(value)
