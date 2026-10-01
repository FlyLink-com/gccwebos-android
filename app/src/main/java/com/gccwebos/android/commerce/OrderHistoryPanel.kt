package com.gccwebos.android.commerce

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("按记录核对进度", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = state::load, enabled = !state.busy,
            modifier = Modifier.heightIn(min = 48.dp).testTag("commerce-refresh")) { Text("刷新订单") }
    }
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("orders-loading"), color = Brand.Cyan)
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
    when (category) {
        "orders" -> {
            val page = state.orderPage
            HistoryReadState(page?.orders?.size, state.busy, state.purchaseHistoryError, "商城订单", state.error)
            page?.orders?.forEach { order ->
                val selection = HistorySelection(HistoryKind.Legacy, order.ref)
                HistorySummary("history-order-${order.ref}", order.offer.title.zh,
                    formatCoins(order.amountUnits), purchaseStatus(order), order.ref, order.createdAt,
                    details.selected == selection, !state.busy && !details.busy) { details.open(selection) }
            }
            if (page?.nextCursor != null) BrandButton("加载更早订单", state::moreOrders, enabled = !state.busy,
                modifier = Modifier.testTag("orders-more"))
        }
        "plans" -> {
            HistoryReadState(state.transitions?.size, state.busy, state.purchaseHistoryError, "套餐计划", state.error)
            state.transitions?.forEach { order ->
                val selection = HistorySelection(HistoryKind.Deferred, order.ref)
                HistorySummary("history-order-${order.ref}", order.offer.title.zh,
                    formatCoins(order.amountUnits), purchaseStatus(order), order.ref, order.createdAt,
                    details.selected == selection, !state.busy && !details.busy) { details.open(selection) }
            }
        }
        "recharge" -> {
            HistoryReadState(state.rechargeHistory?.size, state.busy, state.rechargeHistoryError, "充值", state.error)
            state.rechargeHistory?.forEach { order ->
                val selection = HistorySelection(HistoryKind.Recharge, order.orderRef)
                HistorySummary("history-recharge-${order.orderRef}", "充值 ${formatCoins(order.expectedPaidCoinUnits)}",
                    formatMoney(order.amountMinor, order.currency, order.currencyExponent), rechargeStatus(order.status),
                    order.orderRef, order.createdAt, details.selected == selection, !state.busy && !details.busy) {
                    details.open(selection)
                }
            }
        }
        else -> HistoryReadState(null, state.busy, state.purchaseHistoryError, "订单类型", state.error)
    }
    SupportingText("支付、Coins 到账与资源交付分别核对。", Modifier.testTag("orders-state-separation"))
    if (details.visible) OrderDetailsDialog(details)
}

@Composable
private fun HistoryReadState(size: Int?, busy: Boolean, specificError: String?, label: String, globalError: String?) {
    val error = historyReadError(size, busy, specificError, globalError)
    if (error != null) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("history-error"))
    if (size == null || size == 0) BrandCard(Modifier.testTag("history-read-state")) {
        Text(when {
            busy -> "正在查询$label"
            size == null && error != null -> "${label}查询失败"
            size == null -> "${label}尚未读取"
            label == "商城订单" -> "暂无这类订单"
            else -> "暂无${label}记录"
        }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("history-read-title"))
        SupportingText(if (size == null) "未完成查询不代表没有记录，请重试。" else "服务器本次返回了空列表。")
    }
}

/** Whole-load failures matter only while this history is unread; a wallet error cannot erase a known result. */
internal fun historyReadError(size: Int?, busy: Boolean, specificError: String?, globalError: String?): String? =
    specificError ?: globalError?.takeIf { size == null && !busy }

@Composable
private fun HistorySummary(tag: String, title: String, amount: String, status: String,
    ref: String, createdAt: Instant, isSelected: Boolean, enabled: Boolean, onDetail: () -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag(tag).semantics { selected = isSelected },
        shape = Brand.Shape, color = Brand.Surface.copy(alpha = .82f),
        border = BorderStroke(1.dp, if (isSelected) Brand.Cyan else Brand.Border)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(amount, style = MaterialTheme.typography.titleMedium)
            SupportingText(status)
            SupportingText(historyTime(createdAt))
            SupportingText("记录 $ref")
            TextButton(onClick = onDetail, enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag("history-detail-$ref")) { Text("查看详情  ›") }
        }
    }
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
                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("history-detail-loading"), color = Brand.Cyan)
                    Text("正在读取详情")
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("history-detail-error")) }
                when (val detail = state.value) {
                    is HistoryDetail.Purchase -> PurchaseDetails(detail.value)
                    is HistoryDetail.Recharge -> RechargeDetails(detail.value)
                    null -> Unit
                }
                SupportingText("返回只关闭详情，保留列表；不会取消或重建订单。")
            }
        },
        confirmButton = { TextButton(onClick = state::back,
            modifier = Modifier.heightIn(min = 48.dp).testTag("history-detail-back")) { Text("返回列表") } },
        dismissButton = {
            if (state.error != null) TextButton(onClick = state::retry, enabled = !state.busy,
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
    DetailField("支付方式", when (value.paymentMethod) { "alipay" -> "支付宝"; "wechat_pay" -> "微信支付"; else -> "PayPal" })
    DetailField("订单有效至", historyTime(value.expiresAt))
    DetailField("创建时间", historyTime(value.createdAt))
    DetailField("更新时间", historyTime(value.updatedAt))
    SupportingText("支付已核验需继续查询 Coins 到账；充值到账后购买商品仍需另行确认。")
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
private fun historyTime(value: Instant) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(ZoneId.systemDefault()).format(value)
