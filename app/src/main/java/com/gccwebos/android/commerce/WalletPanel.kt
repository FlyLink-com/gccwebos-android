package com.gccwebos.android.commerce

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.gccwebos.android.design.*
import com.gccwebos.android.design.dialog.GccDialogAction
import com.gccwebos.android.design.dialog.GccSecondaryButton
import com.gccwebos.android.design.dialog.GccSheet
import com.gccwebos.android.portal.AccountController
import java.time.Instant

/** Server amounts, local confirmations, and read-only status are separate steps. */
@Composable
internal fun WalletPanel(account: AccountController, state: CommerceController, mode: CommerceMode,
    now: Instant, paymentOpener: (String) -> Unit, onRecharge: (() -> Unit)? = null, onLedger: (() -> Unit)? = null,
    embeddedBalance: Boolean = false, onBack: (() -> Unit)? = null, onRechargeRecords: (() -> Unit)? = null) {
    val opener by rememberUpdatedState(paymentOpener)
    val flow = remember(account.authSessionGeneration, state, mode) { RechargeFlowController(account, state) { opener(it) } }
    DisposableEffect(flow) { onDispose { flow.dispose() } }
    var recoveryConfirmation by remember(account.authSessionGeneration, state) { mutableStateOf(false) }
    // A changed directory/session discards the complete selected quote snapshot, never silently adopts a new price.
    var selectedRechargeOffer by remember(account.authSessionGeneration, state, state.rechargeOffers, state.currentSession()) {
        mutableStateOf<RechargeOffer?>(null)
    }
    var selectedRechargeMethod by remember(account.authSessionGeneration, state) { mutableStateOf<RechargePaymentMethod?>(null) }
    // First directory loaded on this visit; only it may receive a default highlighted tile.
    var initialRechargeDirectory by remember(account.authSessionGeneration, state) { mutableStateOf<List<RechargeOffer>?>(null) }
    val displayOwner = account.commerceSessionIdentity ?: account.authSessionGeneration
    var lastDisplayedWallet by remember(state, displayOwner) { mutableStateOf<Wallet?>(null) }
    var lastDisplayedOffers by remember(state, displayOwner) { mutableStateOf<List<RechargeOffer>>(emptyList()) }
    val wallet = if (!state.currentSession()) null else state.wallet ?: lastDisplayedWallet.takeIf { state.busy || state.walletError != null || state.error != null }
    val displayOffers = when {
        !state.currentSession() -> emptyList()
        state.rechargeOffersLoaded -> state.rechargeOffers
        state.busy || state.rechargeOffersError != null || state.error != null -> lastDisplayedOffers
        else -> emptyList()
    }
    SideEffect {
        if (state.currentSession() && state.wallet != null) lastDisplayedWallet = state.wallet
        if (state.currentSession() && state.rechargeOffersLoaded) lastDisplayedOffers = state.rechargeOffers
        if (state.currentSession() && state.rechargeOffersLoaded && initialRechargeDirectory == null &&
            state.rechargeOffers.isNotEmpty()) initialRechargeDirectory = state.rechargeOffers
    }
    val walletFailure = state.walletError ?: state.error?.takeIf { !state.walletLoaded && !state.busy }
    val offersFailure = state.rechargeOffersError ?: state.error?.takeIf { !state.rechargeOffersLoaded && !state.busy }
    var balanceDetails by remember(account.authSessionGeneration, state) { mutableStateOf(false) }
    if (mode == CommerceMode.Wallet) WalletTopBar("钱包充值", onBack, onRechargeRecords, enabled = !state.busy)
    BalanceCard(Modifier.testTag("wallet-card"), embedded = embeddedBalance) {
        if (mode == CommerceMode.Mine) {
            MineBalanceHeading()
            MineSpendableBalance(wallet)
        } else WalletTopUpBalance(wallet, onDetails = { balanceDetails = true }, enabled = true)
        CommerceReadNotice(state, walletFailure?.takeUnless { state.busy }, "wallet-error")
        wallet?.let { w ->
            if (w.status != "active") SupportingText(when (w.status) {
                "not_created" -> "钱包尚未创建"
                "frozen" -> "钱包已冻结"
                else -> "钱包已关闭"
            }, Modifier.testTag("wallet-status"))
        }
        if (mode == CommerceMode.Mine) MineWalletActions(enabled = !state.busy,
            onRecharge = { onRecharge?.invoke() }, onLedger = { onLedger?.invoke() })
    }
    if (mode != CommerceMode.Wallet) return
    flow.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("recharge-flow-error")) }
    if (!state.pendingRecharge) {
        val methods = availableRechargeMethods(displayOffers)
        val method = selectedRechargeMethod?.takeIf { it in methods } ?: methods.firstOrNull()
        val effectiveOffer = effectiveRechargeSelection(displayOffers, selectedRechargeOffer, method,
            defaultAllowed = rechargeDefaultAllowed(initialRechargeDirectory, displayOffers))
        BrandCard(Modifier.testTag("recharge-amount-card"), contentPadding = PaddingValues(12.dp)) {
        Text("选择充值额度", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        CommerceReadNotice(state, offersFailure?.takeUnless { state.busy }, "recharge-offers-error")
        when {
            // First read in flight with nothing to show: skeleton tiles; the grid then fades in.
            displayOffers.isEmpty() && state.busy && offersFailure == null ->
                SkeletonCards(count = 2, cardHeight = 64.dp, spacing = 10.dp, shape = RoundedCornerShape(16.dp), lines = 1,
                    modifier = Modifier.testTag("recharge-offer-skeleton"))
            displayOffers.isEmpty() && (!state.rechargeOffersLoaded || state.busy || offersFailure != null) -> Unit
            displayOffers.isEmpty() -> Text("当前暂无充值档位", modifier = Modifier.testTag("recharge-offers-empty"))
            else -> BoxWithConstraints(Modifier.fillMaxWidth().enterMotion(rememberEnterMotion()).testTag("recharge-offer-grid")) {
                val columns = when {
                    LocalDensity.current.fontScale > 1.6f || maxWidth < 230.dp -> 1
                    LocalDensity.current.fontScale > 1.15f || maxWidth < 280.dp -> 2
                    else -> 3
                }
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    displayOffers.chunked(columns).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { offer ->
                                RechargeAmountTile(offer, selected = effectiveOffer == offer,
                                    enabled = state.rechargeOffersLoaded && !state.busy && state.canOperate() &&
                                        rechargeOfferPayable(offer, method) && "alipay" in offer.paymentMethods,
                                    unsupportedNote = if ("alipay" !in offer.paymentMethods) "暂不支持支付宝" else null,
                                    onClick = { selectedRechargeOffer = offer },
                                    modifier = Modifier.weight(1f).testTag("recharge-select-${offer.offerRef}"))
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        }
        if (methods.isNotEmpty()) BrandCard(Modifier.testTag("recharge-method-card"), contentPadding = PaddingValues(12.dp)) {
            Text("支付方式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            RechargePaymentMethodSelector(methods, method, enabled = !state.busy) { selectedRechargeMethod = it }
        }
        val payableSelection = effectiveOffer?.takeIf { selected ->
            state.rechargeOffersLoaded && offersFailure == null && state.rechargeOffers.any { it == selected } &&
                rechargeOfferPayable(selected, method) && "alipay" in selected.paymentMethods && state.canOperate()
        }
        // Same guarded handler as before: selection -> local confirmation dialog -> idempotent order creation.
        RechargeCheckoutBar(payable = payableSelection?.let(::rechargePriceText),
            enabled = !state.busy && payableSelection != null, onSubmit = { payableSelection?.let(flow::select) })
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
        AlertDialog(onDismissRequest = flow::cancelSelection, containerColor = Brand.SurfaceRaised, shape = Brand.Shape, title = { Text("确认充值金额") },
            text = { Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${formatMoney(offer.amountMinor, offer.currency, offer.currencyExponent)} → ${formatCoins(offer.paidCoinUnits)}")
                Text("本次选项尚未锁价。创建后按服务器返回金额再次核对，再主动打开支付宝。")
                Text("服务平台：飞连vpn · portal.gccwebos.com")
            } }, confirmButton = { TextButton(onClick = flow::confirmSelection, enabled = !state.busy,
                modifier = Modifier.testTag("recharge-confirm")) { Text("确认金额并创建订单") } },
            dismissButton = { TextButton(onClick = flow::cancelSelection, modifier = Modifier.testTag("recharge-cancel")) { Text("取消") } })
    }
    flow.paymentReview?.let { order ->
        AlertDialog(onDismissRequest = flow::dismissPayment, containerColor = Brand.SurfaceRaised, shape = Brand.Shape, title = { Text("核对服务器付款信息") },
            text = { Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("实际应付：${formatMoney(order.amountMinor, order.currency, order.currencyExponent)}")
                Text("到账目标：${formatCoins(order.expectedPaidCoinUnits)}")
                Text("支付宝 · 飞连vpn · portal.gccwebos.com")
                Text("将打开：${order.nextAction?.let { java.net.URI(it.url).host } ?: "入口不可用"}")
                Text("请核对实际金额；选项价格变化时，以此订单为准。暂不付款会保留订单。")
                if (!state.canOpenPayment(order, now)) Text("订单已更新或入口已失效，请关闭并查询。")
            } }, confirmButton = { AlipayButton("支付宝支付", { flow.openPayment(Instant.now()) },
                enabled = !state.busy && state.canOpenPayment(order, now), modifier = Modifier.testTag("payment-open-confirm")) },
            dismissButton = { TextButton(onClick = flow::dismissPayment, modifier = Modifier.testTag("payment-open-cancel")) { Text("暂不付款") } })
    }
    // Read-only breakdown: a swipe-dismissable bottom sheet over the top-up page (same tags and fields).
    if (balanceDetails && mode == CommerceMode.Wallet) GccSheet(onDismissRequest = { balanceDetails = false },
        modifier = Modifier.testTag("wallet-balance-details-dialog"), title = "余额明细",
        actions = { close -> GccSecondaryButton(GccDialogAction("知道了", close, testTag = "wallet-balance-details-close"),
            modifier = Modifier.fillMaxWidth()) }) {
        WalletBalanceMetrics(wallet)
        SupportingText("订单预留和待确认奖励不计入可用余额。")
    }
    if (recoveryConfirmation) AlertDialog(onDismissRequest = { recoveryConfirmation = false }, containerColor = Brand.SurfaceRaised, shape = Brand.Shape, title = { Text("恢复原创建请求？") },
        text = { Text("仅沿用本机原幂等记录。未获得订单编号时可能重发原创建请求；这不是付款或到账确认。") },
        confirmButton = { TextButton(onClick = { recoveryConfirmation = false; state.queryRecharge() }, enabled = !state.busy,
            modifier = Modifier.testTag("recharge-recover-confirm")) { Text("沿用原记录恢复") } },
        dismissButton = { TextButton(onClick = { recoveryConfirmation = false }) { Text("返回") } })
}

@Composable
private fun BalanceCard(modifier: Modifier, embedded: Boolean, content: @Composable ColumnScope.() -> Unit) {
    // Mine: a standalone wallet card (blue/gold glass wash), separate from the header card.
    if (embedded) MineWalletCard(modifier, content)
    else BrandCard(modifier, contentPadding = PaddingValues(12.dp), content = content)
}

/** The overview highlights only spendable funds; the full wallet keeps every independent metric. */
@Composable
private fun MineSpendableBalance(wallet: Wallet?) {
    val formatted = wallet?.spendableUnits?.let(::formatCoins) ?: "— Coins"
    val amount = formatted.removeSuffix(" Coins")
    val amountStyle = if (amount.length > 9) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge
    val unitStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, color = MineStyle.Gold)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GccSpacing.Medium)) {
        CoinIcon(30.dp)
        Text(buildAnnotatedString {
            withStyle(amountStyle.toSpanStyle()) { append(amount) }
            withStyle(unitStyle.toSpanStyle()) { append(" Coins") }
        }, style = amountStyle, color = Brand.Text, modifier = Modifier.weight(1f).testTag("wallet-balance"))
    }
}

/** Each figure is a separate authoritative field. Held and pending funds are never added to availability. */
@Composable
private fun WalletBalanceMetrics(wallet: Wallet?) {
    val metrics = listOf(
        Triple("总可用", wallet?.spendableUnits, "wallet-detail-total"),
        Triple("已购可用", wallet?.paidCoins, "wallet-paid"),
        Triple("赠送可用", wallet?.bonusCoins, "wallet-bonus"),
        Triple("奖励可用", wallet?.rewardCoins, "wallet-reward"),
        Triple("订单预留", wallet?.heldUnits, "wallet-held"),
        Triple("待确认奖励", wallet?.pendingUnits, "wallet-pending"),
    )
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("wallet-metrics")) {
        val columns = when {
            LocalDensity.current.fontScale > 1.6f || maxWidth < 230.dp -> 1
            LocalDensity.current.fontScale > 1.15f || maxWidth < 280.dp -> 2
            else -> 3
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            metrics.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { metric ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(metric.first, style = MaterialTheme.typography.labelMedium, color = Brand.Muted)
                            Text(metric.second?.let(::formatCoins) ?: "— Coins",
                                style = if (metric.third == "wallet-detail-total") MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                                color = if (metric.third == "wallet-detail-total") Brand.Cyan else Brand.Text,
                                fontWeight = FontWeight.Bold, modifier = Modifier.testTag(metric.third))
                        }
                    }
                }
            }
        }
    }
}
