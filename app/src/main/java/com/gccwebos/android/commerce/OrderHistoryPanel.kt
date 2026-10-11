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

/** Details are an explicit read. The list composition, cursor, filter, and scroll survive return. */
@Composable
internal fun OrderHistoryPanel(account: AccountController, state: CommerceController,
    detailOverride: OrderDetailController? = null, paymentOpener: (String) -> Unit = {}, now: Instant = Instant.now()) {
    val scope = rememberCoroutineScope()
    // 待付款 orders are paid from here (not from the recharge screen): straight to the order's valid payment entry.
    val opener by rememberUpdatedState(paymentOpener)
    val payFlow = remember(account.authSessionGeneration, state) { RechargeFlowController(account, state) { opener(it) } }
    DisposableEffect(payFlow) { onDispose { payFlow.dispose() } }
    val details = detailOverride ?: remember(account.authSessionGeneration, state) {
        OrderDetailController(account, scope, state::historyDetail)
    }
    // null = 全部 (display-only composition of already-loaded purchase + recharge lists).
    var chosenCategory by remember(account.authSessionGeneration, state) { mutableStateOf<String?>(null) }
    val purchaseKey = when (state.commerceModel) {
        CommerceModel.LEGACY -> "orders"
        CommerceModel.WALLET_FIRST -> "plans"
        null -> null
    }
    val category = when (chosenCategory) {
        "recharge" -> "recharge"
        "all" -> "all"
        "orders", "plans" -> chosenCategory
        else -> purchaseKey // default: 套餐购买 for the active commerce model
    }
    DisposableEffect(details) { onDispose { details.dispose() } }
    BackHandler(enabled = details.visible, onBack = details::back)
    val selectedRows = when (category) {
        "orders" -> state.orderPage?.orders?.size
        "plans" -> state.transitions?.size
        "recharge" -> state.rechargeHistory?.size
        "all" -> {
            val purchases = when (state.commerceModel) {
                CommerceModel.LEGACY -> state.orderPage?.orders?.size
                CommerceModel.WALLET_FIRST -> state.transitions?.size
                null -> null
            }
            val recharges = state.rechargeHistory?.size
            if (purchases == null && recharges == null) null else (purchases ?: 0) + (recharges ?: 0)
        }
        else -> null
    }
    val listError = historyReadError(selectedRows, state.busy,
        if (category == "recharge") state.rechargeHistoryError
        else if (category == "all") state.purchaseHistoryError ?: state.rechargeHistoryError
        else state.purchaseHistoryError, state.error)

    CommerceReadNotice(state, listError, "history-error")
    Row(Modifier.fillMaxWidth().testTag("orders-secondary-tabs"),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (purchaseKey != null) {
            FilterChip(selected = category == purchaseKey, onClick = { chosenCategory = purchaseKey },
                label = { Text("套餐购买") },
                modifier = Modifier.heightIn(min = 48.dp).testTag("orders-$purchaseKey"))
        }
        FilterChip(selected = category == "recharge", onClick = { chosenCategory = "recharge" },
            label = { Text("代币充值") }, modifier = Modifier.heightIn(min = 48.dp).testTag("orders-recharge"))
        FilterChip(selected = category == "all", onClick = { chosenCategory = "all" },
            label = { Text("全部") }, modifier = Modifier.heightIn(min = 48.dp).testTag("orders-all"))
    }
    HistoryListCard(Modifier.testTag("orders-state-separation"), contentPadding = PaddingValues(12.dp)) {
        when (category) {
            "orders" -> {
                val page = state.orderPage
                HistoryReadState(page?.orders?.size, state.busy, "商城订单")
                HistoryGroups(page?.orders.orEmpty(), { it.createdAt }) { order ->
                    val selection = HistorySelection(HistoryKind.Legacy, order.ref)
                    HistorySummary("history-order-${order.ref}", historyOfferTitle(order.offer),
                        formatCoins(order.amountUnits), if (order.offer.category == "data_pack") "流量包购买" else "套餐购买",
                        listOf(OrderHistoryPresentation.purchaseListStatus(order)),
                        null, order.ref, order.createdAt, com.gccwebos.android.R.drawable.brand_receipt,
                        details.selected == selection, !state.busy && !details.busy) { details.open(selection) }
                }
                if (page?.nextCursor != null) BrandButton("加载更早订单", state::moreOrders, enabled = !state.busy,
                    modifier = Modifier.testTag("orders-more"))
            }
            "plans" -> {
                HistoryReadState(state.transitions?.size, state.busy, "套餐计划")
                HistoryGroups(state.transitions.orEmpty(), { it.createdAt }) { order ->
                    val selection = HistorySelection(HistoryKind.Deferred, order.ref)
                    HistorySummary("history-order-${order.ref}", historyOfferTitle(order.offer),
                        formatCoins(order.amountUnits), "套餐计划",
                        listOf(OrderHistoryPresentation.purchaseListStatus(order)),
                        null, order.ref, order.createdAt, com.gccwebos.android.R.drawable.brand_receipt,
                        details.selected == selection, !state.busy && !details.busy) { details.open(selection) }
                }
            }
            "recharge" -> {
                HistoryReadState(state.rechargeHistory?.size, state.busy, "充值")
                HistoryGroups(state.rechargeHistory.orEmpty(), { it.createdAt }) { order ->
                    val selection = HistorySelection(HistoryKind.Recharge, order.orderRef)
                    HistorySummary("history-recharge-${order.orderRef}", "钱包充值",
                        formatMoney(order.amountMinor, order.currency, order.currencyExponent), "支付充值",
                        listOf(OrderHistoryPresentation.rechargeListStatus(order, now)),
                        "预计 ${formatCoins(order.expectedPaidCoinUnits)}", order.orderRef, order.createdAt,
                        com.gccwebos.android.R.drawable.brand_wallet,
                        details.selected == selection, !state.busy && !details.busy) {
                        details.open(selection)
                    }
                    PayNowAction(order, state, payFlow, now)
                }
            }
            "all" -> {
                val purchases: List<PurchaseResult> = when (state.commerceModel) {
                    CommerceModel.LEGACY -> state.orderPage?.orders.orEmpty()
                    CommerceModel.WALLET_FIRST -> state.transitions.orEmpty()
                    null -> emptyList()
                }
                val recharges = state.rechargeHistory
                val ready = state.commerceModel != null || recharges != null
                val combined = buildList {
                    purchases.forEach { add(AllHistoryItem.Purchase(it)) }
                    recharges.orEmpty().forEach { add(AllHistoryItem.Recharge(it)) }
                }.sortedByDescending { it.createdAt }
                HistoryReadState(if (!ready) null else combined.size, state.busy, "订单")
                combined.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(color = Brand.RowDivider)
                    val day = OrderHistoryPresentation.dayKey(item.createdAt)
                    if (index == 0 || day != OrderHistoryPresentation.dayKey(combined[index - 1].createdAt)) {
                        Text(OrderHistoryPresentation.dateHeading(item.createdAt), color = Brand.Muted,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 6.dp, start = 4.dp).testTag("history-date-$day"))
                    }
                    when (item) {
                        is AllHistoryItem.Purchase -> {
                            val order = item.value
                            val selection = when (order) {
                                is PurchaseResult.Order -> HistorySelection(HistoryKind.Legacy, order.ref)
                                is PurchaseResult.Deferred -> HistorySelection(HistoryKind.Deferred, order.ref)
                            }
                            val type = when {
                                order is PurchaseResult.Order && order.offer.category == "data_pack" -> "流量包购买"
                                order is PurchaseResult.Deferred -> "套餐计划"
                                else -> "套餐购买"
                            }
                            HistorySummary("history-order-${order.ref}", historyOfferTitle(order.offer),
                                formatCoins(order.amountUnits), type,
                                listOf(OrderHistoryPresentation.purchaseListStatus(order)),
                                null, order.ref, item.createdAt, com.gccwebos.android.R.drawable.brand_receipt,
                                details.selected == selection, !state.busy && !details.busy) { details.open(selection) }
                        }
                        is AllHistoryItem.Recharge -> {
                            val order = item.value
                            val selection = HistorySelection(HistoryKind.Recharge, order.orderRef)
                            HistorySummary("history-recharge-${order.orderRef}", "钱包充值",
                                formatMoney(order.amountMinor, order.currency, order.currencyExponent), "支付充值",
                                listOf(OrderHistoryPresentation.rechargeListStatus(order, now)),
                                "预计 ${formatCoins(order.expectedPaidCoinUnits)}", order.orderRef, order.createdAt,
                                com.gccwebos.android.R.drawable.brand_wallet,
                                details.selected == selection, !state.busy && !details.busy) { details.open(selection) }
                            PayNowAction(order, state, payFlow, now)
                        }
                    }
                }
            }
            else -> HistoryReadState(null, state.busy, "订单类型")
        }
    }
    if (details.visible) OrderDetailsDialog(details, onSyncResources = {
        account.refreshSubscription()
        details.back()
    })
}

@Composable
private fun HistoryReadState(size: Int?, busy: Boolean, label: String) {
    if (busy || size == null) return
    if (size == 0) Surface(Modifier.fillMaxWidth().testTag("history-read-state"),
        shape = Brand.Shape, color = Color.Transparent) {
        Row(Modifier.heightIn(min = 48.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(com.gccwebos.android.R.drawable.brand_receipt), null, tint = Brand.Muted,
                modifier = Modifier.size(24.dp))
            Text(when {
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
        val day = OrderHistoryPresentation.dayKey(timestamp)
        if (index > 0) HorizontalDivider(color = Brand.RowDivider)
        if (index == 0 || day != OrderHistoryPresentation.dayKey(createdAt(rows[index - 1]))) {
            Text(OrderHistoryPresentation.dateHeading(timestamp), color = Brand.Muted,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp).testTag("history-date-$day"))
        }
        content(row)
    }
}

@Composable
private fun HistorySummary(tag: String, title: String, amount: String, type: String,
    states: List<String>, expected: String?, ref: String, createdAt: Instant, icon: Int,
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
                    Text("${OrderHistoryPresentation.clock(createdAt)} · $type", color = Brand.Muted,
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("$tag-time"))
                    states.forEachIndexed { index, value ->
                        Text(value, color = Brand.Muted, style = MaterialTheme.typography.bodySmall,
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

/**
 * 立即支付 on a still-payable 待付款 recharge (server entry present, trusted, not expired): opens that entry directly, with
 * no review step (the row already shows the server's amount). An expired or entry-less order shows no button (its row says
 * 已失效). Never creates an order.
 */
@Composable
private fun PayNowAction(order: RechargeOrder, state: CommerceController, flow: RechargeFlowController, now: Instant) {
    if (state.canOpenPayment(order, now)) {
        AlipayButton("立即支付", { flow.payNow(order, now) }, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().testTag("history-pay-${order.orderRef}"))
        flow.error?.takeIf { flow.requestedPaymentFor == null }?.let { SupportingText(it, Modifier.testTag("history-pay-error-${order.orderRef}")) }
        if (flow.requestedPaymentFor == order.orderRef) SupportingText("已打开付款页面；付款结果返回后在此刷新确认。",
            Modifier.testTag("history-pay-requested-${order.orderRef}"))
    }
}

@Composable
private fun HistoryAmount(amount: String, tag: String) {
    // An order amount is neither a debit nor a confirmed wallet credit.
    Text(amount, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        color = Brand.Text, modifier = Modifier.testTag(tag))
}

@Composable
private fun OrderDetailsDialog(state: OrderDetailController, onSyncResources: () -> Unit) {
    AlertDialog(onDismissRequest = state::back,
        title = { Text("订单详情", modifier = Modifier.testTag("history-detail-title")) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.selected?.let { DetailField("记录", it.ref) }
                CommerceReadNotice(state, state.error?.takeUnless { state.busy }, "history-detail-error")
                when (val detail = state.value) {
                    is HistoryDetail.Purchase -> PurchaseDetails(detail.value, onSyncResources)
                    is HistoryDetail.Recharge -> RechargeDetails(detail.value)
                    null -> Unit
                }
            }
        },
        confirmButton = { TextButton(onClick = state::back,
            modifier = Modifier.heightIn(min = 48.dp).testTag("history-detail-back")) { Text("返回列表") } })
}

@Composable
private fun PurchaseDetails(value: PurchaseResult, onSyncResources: () -> Unit) {
    DetailField("商品", value.offer.title.zh)
    DetailField("订单金额", formatCoins(value.amountUnits))
    DetailField("商品交付", if (value.offer.category == "data_pack") "${value.offer.trafficGb} GB 独立流量包"
        else "${value.offer.durationDays} 天 · 每月 ${value.offer.monthlyTrafficGb} GB")
    value.offer.importantNotices.zh.forEach { SupportingText(it) }
    when (value) {
        is PurchaseResult.Order -> {
            DetailField("支付状态", OrderHistoryPresentation.paymentDetail(value.paymentStatus), "history-payment-state")
            DetailField("资源交付", OrderHistoryPresentation.fulfillmentDetail(value.fulfillmentStatus), "history-fulfillment-state")
            value.fulfillmentSafeCode?.let { DetailField("交付核对代码", it) }
            value.allocation.forEach { DetailField("${coinTypeLabel(it.coinType)}分配", formatCoins(it.amountUnits)) }
            DetailField("可取消窗口截至", OrderHistoryPresentation.detailTime(value.cancelUntil))
            DetailField("创建时间", OrderHistoryPresentation.detailTime(value.createdAt))
            DetailField("更新时间", OrderHistoryPresentation.detailTime(value.updatedAt))
        }
        is PurchaseResult.Deferred -> {
            DetailField("计划状态", OrderHistoryPresentation.deferredDetail(value.state), "history-transition-state")
            DetailField("计划类型", if (value.flowKind == "initial_activation") "首次启用" else "计划切换")
            DetailField("计划生效时间", OrderHistoryPresentation.detailTime(value.scheduledFor), "history-scheduled-for")
            DetailField("预留时间", OrderHistoryPresentation.detailTime(value.reservedAt))
            DetailField("结算时间", value.settledAt?.let(OrderHistoryPresentation::detailTime) ?: "尚未提供")
            value.safeCode?.let { DetailField("核对代码", it) }
            DetailField("创建时间", OrderHistoryPresentation.detailTime(value.createdAt))
            DetailField("更新时间", OrderHistoryPresentation.detailTime(value.updatedAt))
        }
    }
    if (OrderHistoryPresentation.offersResourceSync(value)) {
        TextButton(onClick = onSyncResources, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .testTag("history-sync-resources")) { Text("同步节点") }
    }
}

@Composable
private fun RechargeDetails(value: RechargeOrder) {
    DetailField("充值金额", formatMoney(value.amountMinor, value.currency, value.currencyExponent))
    DetailField("预计到账", formatCoins(value.expectedPaidCoinUnits))
    DetailField("充值状态", OrderHistoryPresentation.rechargeDetail(value.status), "history-recharge-state")
    // Keep a separate credit hint only while payment is verified but coins are not yet available.
    if (value.status == "payment_verified") {
        DetailField("到账进度", "确认中", "history-credit-state")
    } else if (value.status == "credited") {
        DetailField("到账进度", "已到账", "history-credit-state")
    }
    DetailField("支付方式", when (value.paymentMethod) { "alipay" -> "支付宝"; "wechat_pay" -> "微信支付"; else -> "PayPal" })
    DetailField("订单有效至", OrderHistoryPresentation.detailTime(value.expiresAt))
    DetailField("创建时间", OrderHistoryPresentation.detailTime(value.createdAt))
    DetailField("更新时间", OrderHistoryPresentation.detailTime(value.updatedAt))
}

@Composable
private fun DetailField(label: String, value: String, tag: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        SupportingText(label)
        Text(value, style = MaterialTheme.typography.bodyLarge,
            modifier = if (tag == null) Modifier else Modifier.testTag(tag))
    }
}

// Kept for any remaining callers/tests that still import the old names; delegate to presentation.
internal fun paymentState(value: String) = OrderHistoryPresentation.paymentDetail(value)
internal fun fulfillmentState(value: String) = OrderHistoryPresentation.fulfillmentDetail(value)
internal fun deferredState(value: String) = OrderHistoryPresentation.deferredDetail(value)
private fun coinTypeLabel(value: String) = when (value) {
    "paid_coins" -> "充值 Coins"; "bonus_coins" -> "赠送 Coins"; else -> "奖励 Coins"
}
private fun historyOfferTitle(offer: Offer) = if (offer.category == "plan" && offer.durationDays != null)
    "${offer.durationDays} 天套餐" else offer.title.zh

private sealed class AllHistoryItem {
    abstract val createdAt: Instant
    data class Purchase(val value: PurchaseResult) : AllHistoryItem() {
        override val createdAt: Instant get() = when (val row = value) {
            is PurchaseResult.Order -> row.createdAt
            is PurchaseResult.Deferred -> row.createdAt
        }
    }
    data class Recharge(val value: RechargeOrder) : AllHistoryItem() {
        override val createdAt: Instant get() = value.createdAt
    }
}
