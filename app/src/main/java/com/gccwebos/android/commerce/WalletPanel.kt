package com.gccwebos.android.commerce

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gccwebos.android.design.*
import com.gccwebos.android.portal.AccountController
import java.time.Instant

/** Server amounts, local confirmations, and read-only status are separate steps. */
@Composable
internal fun WalletPanel(account: AccountController, state: CommerceController, mode: CommerceMode,
    now: Instant, paymentOpener: (String) -> Unit, onRecharge: (() -> Unit)? = null, onLedger: (() -> Unit)? = null) {
    val opener by rememberUpdatedState(paymentOpener)
    val flow = remember(account.authSessionGeneration, state, mode) { RechargeFlowController(account, state) { opener(it) } }
    DisposableEffect(flow) { onDispose { flow.dispose() } }
    var recoveryConfirmation by remember(account.authSessionGeneration, state) { mutableStateOf(false) }
    val wallet = state.wallet
    val walletFailure = state.walletError ?: state.error?.takeIf { !state.walletLoaded && !state.busy }
    BalanceCard(mode, Modifier.testTag("wallet-card")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Eyebrow("可用余额")
                Text(wallet?.let { formatCoins(it.spendableUnits) } ?: "尚未读取",
                    style = MaterialTheme.typography.headlineLarge, modifier = Modifier.testTag("wallet-balance"))
            }
            TextButton(onClick = { if (mode == CommerceMode.Mine) state.loadBalance() else state.loadWallet() }, enabled = !state.busy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("commerce-refresh")) { Text("刷新") }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("wallet-loading"), color = Brand.Cyan)
        walletFailure?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("wallet-error")) }
        wallet?.let { w ->
            if (mode != CommerceMode.Mine || w.status != "active") SupportingText(when (w.status) { "active" -> "钱包可用"; "not_created" -> "钱包尚未创建"; "frozen" -> "钱包已冻结"; else -> "钱包已关闭" })
            if (mode != CommerceMode.Mine) {
                SupportingText("充值 Coins 余额 ${formatCoins(w.paidCoins)} · 赠送 ${formatCoins(w.bonusCoins)} · 奖励 ${formatCoins(w.rewardCoins)}")
                SupportingText("冻结中 ${formatCoins(w.heldUnits)} · 待到账 ${formatCoins(w.pendingUnits)}")
            }
        }
        if (mode == CommerceMode.Mine) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrandButton("充值", { onRecharge?.invoke() }, enabled = !state.busy,
                modifier = Modifier.weight(1f).testTag("mine-recharge"))
            OutlinedButton(onClick = { onLedger?.invoke() }, enabled = !state.busy,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("mine-ledger")) { Text("账单明细") }
        }
    }
    if (mode != CommerceMode.Wallet) return
    flow.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("recharge-flow-error")) }
    if (!state.pendingRecharge) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("充值 Coins", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            SupportingText("支付宝")
        }
        val offersFailure = state.rechargeOffersError ?: state.error?.takeIf { !state.rechargeOffersLoaded && !state.busy }
        when {
            offersFailure != null -> BrandCard(Modifier.testTag("recharge-offers-error")) { Text(offersFailure) }
            !state.rechargeOffersLoaded -> BrandCard(Modifier.testTag("recharge-offers-unread")) { Text(if (state.busy) "正在读取充值档位" else "充值档位尚未读取") }
            state.rechargeOffers.isEmpty() -> BrandCard(Modifier.testTag("recharge-offers-empty")) { Text("当前暂无充值档位") }
            else -> state.rechargeOffers.forEach { offer ->
                Surface(Modifier.fillMaxWidth().testTag("recharge-offer-${offer.offerRef}"), shape = Brand.Shape,
                    color = Brand.Surface.copy(alpha = .82f), border = BorderStroke(1.dp, Brand.Border)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(formatCoins(offer.paidCoinUnits), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(formatMoney(offer.amountMinor, offer.currency, offer.currencyExponent), color = Brand.Cyan)
                        if ("alipay" !in offer.paymentMethods) SupportingText("此档位暂不支持支付宝")
                        AlipayButton("支付宝支付", { flow.select(offer) },
                            enabled = !state.busy && "alipay" in offer.paymentMethods && state.currentSession(),
                            modifier = Modifier.testTag("recharge-select-${offer.offerRef}"))
                    }
                }
            }
        }
        SupportingText("先确认金额创建订单，再核对服务器付款信息。充值到账后，套餐需另行购买。")
    } else BrandCard(Modifier.testTag("recharge-order")) {
        val order = state.recharge
        Text("当前充值", style = MaterialTheme.typography.titleMedium)
        Text(order?.let { rechargeStatus(it.status) } ?: "提交结果尚未确认，请保留原记录。", modifier = Modifier.testTag("recharge-status"))
        order?.let {
            Text(formatMoney(it.amountMinor, it.currency, it.currencyExponent), style = MaterialTheme.typography.headlineSmall)
            SupportingText("到账目标 ${formatCoins(it.expectedPaidCoinUnits)} · 订单 ${it.orderRef}")
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("recharge-error")) }
        if (order != null && state.canOpenPayment(order, now)) AlipayButton("支付宝支付", { flow.reviewPayment(order, now) },
            enabled = !state.busy, modifier = Modifier.testTag("recharge-pay"))
        else if (order?.status in setOf("entry_ready", "pending")) SupportingText("付款入口不可用或已过期，请查询原订单。")
        flow.requestedPaymentFor?.let { SupportingText("已请求打开付款页面；支付和到账仍需查询确认。") }
        BrandButton("查询到账状态", state::readRechargeStatus, enabled = !state.busy,
            modifier = Modifier.testTag("recharge-status-query"))
        if (order == null) TextButton(onClick = { recoveryConfirmation = true }, enabled = !state.busy,
            modifier = Modifier.heightIn(min = 48.dp).testTag("recharge-recover")) { Text("恢复原创建请求") }
        if (order?.status in setOf("credited", "failed_no_charge", "expired")) TextButton(onClick = state::finishRecharge,
            enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("recharge-finish")) { Text("核对完成，返回充值") }
    }
    flow.selectedOffer?.let { offer ->
        AlertDialog(onDismissRequest = flow::cancelSelection, title = { Text("确认充值金额") },
            text = { Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${formatMoney(offer.amountMinor, offer.currency, offer.currencyExponent)} → ${formatCoins(offer.paidCoinUnits)}")
                Text("本次选项尚未锁价。创建后按服务器返回金额再次核对，再主动打开支付宝。")
                Text("服务平台：飞连vpn · portal.gccwebos.com")
            } }, confirmButton = { TextButton(onClick = flow::confirmSelection, enabled = !state.busy,
                modifier = Modifier.testTag("recharge-confirm")) { Text("确认金额并创建订单") } },
            dismissButton = { TextButton(onClick = flow::cancelSelection, modifier = Modifier.testTag("recharge-cancel")) { Text("取消") } })
    }
    flow.paymentReview?.let { order ->
        AlertDialog(onDismissRequest = flow::dismissPayment, title = { Text("核对服务器付款信息") },
            text = { Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("实际应付：${formatMoney(order.amountMinor, order.currency, order.currencyExponent)}")
                Text("到账目标：${formatCoins(order.expectedPaidCoinUnits)}")
                Text("支付宝 · 飞连vpn · portal.gccwebos.com")
                Text("将打开：${order.nextAction?.let { java.net.URI(it.url).host } ?: "入口不可用"}")
                Text("请核对实际金额；选项价格变化时，以此订单为准。暂不付款会保留订单。")
                if (!state.canOpenPayment(order, now)) Text("订单已更新或入口已失效，请关闭并查询。")
            } }, confirmButton = { TextButton(onClick = { flow.openPayment(Instant.now()) },
                enabled = !state.busy && state.canOpenPayment(order, now), modifier = Modifier.testTag("payment-open-confirm")) { Text("支付宝支付") } },
            dismissButton = { TextButton(onClick = flow::dismissPayment, modifier = Modifier.testTag("payment-open-cancel")) { Text("暂不付款") } })
    }
    if (recoveryConfirmation) AlertDialog(onDismissRequest = { recoveryConfirmation = false }, title = { Text("恢复原创建请求？") },
        text = { Text("仅沿用本机原幂等记录。未获得订单编号时可能重发原创建请求；这不是付款或到账确认。") },
        confirmButton = { TextButton(onClick = { recoveryConfirmation = false; state.queryRecharge() }, enabled = !state.busy,
            modifier = Modifier.testTag("recharge-recover-confirm")) { Text("沿用原记录恢复") } },
        dismissButton = { TextButton(onClick = { recoveryConfirmation = false }) { Text("返回") } })
}

@Composable
private fun BalanceCard(mode: CommerceMode, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    if (mode == CommerceMode.Mine) Surface(modifier.fillMaxWidth(), shape = Brand.Shape,
        color = Brand.Surface.copy(alpha = .82f), border = BorderStroke(1.dp, Brand.Border)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    } else BrandCard(modifier, content)
}
