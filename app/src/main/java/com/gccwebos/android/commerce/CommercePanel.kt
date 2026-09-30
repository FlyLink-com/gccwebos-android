package com.gccwebos.android.commerce

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gccwebos.android.portal.AccountController
import com.gccwebos.android.portal.PortalFailure
import com.gccwebos.android.design.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal enum class CommerceMode { Overview, Mine, Plans, Orders, Wallet, Ledger }
internal class CommerceController(private val account: AccountController, private val scope: CoroutineScope,
    private val storage: CommerceRecovery, private val includeHistory: Boolean = false, initialRepository: CommerceRepository? = null) {
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var wallet by mutableStateOf<Wallet?>(null)
    var offers by mutableStateOf<List<Offer>>(emptyList())
    var commerceModel by mutableStateOf<CommerceModel?>(null)
    var catalogLoaded by mutableStateOf(false)
    var ledgerPage by mutableStateOf<WalletLedgerPage?>(null)
    var rechargeOffers by mutableStateOf<List<RechargeOffer>>(emptyList())
    var quote by mutableStateOf<Quote?>(null)
    var purchase by mutableStateOf<PurchaseResult?>(null)
    var recharge by mutableStateOf<RechargeOrder?>(null)
    var pendingPurchase by mutableStateOf(false)
    var pendingRecharge by mutableStateOf(false)
    var orderPage by mutableStateOf<OrderPage?>(null)
    var rechargeHistory by mutableStateOf<List<RechargeOrder>?>(null)
    var transitions by mutableStateOf<List<PurchaseResult.Deferred>?>(null)
    private var repo: CommerceRepository? = initialRepository
    private val epoch = account.authSessionGeneration
    fun load() = run {
        orderPage = null; rechargeHistory = null; transitions = null; catalogLoaded = false; commerceModel = null
        quote = null; wallet = null; offers = emptyList(); rechargeOffers = emptyList(); purchase = null; recharge = null
        if (repo == null) {
            val session = account.commerceSessionBinding()
            val binding = account.commerceAccountBinding()
            repo = CommerceRepository({ path, body, key ->
                if (!account.authenticated || account.authSessionGeneration != epoch) throw CancellationException("Account changed")
                account.commerce(path, body, key, session).also {
                    if (!currentSession()) throw CancellationException("Account changed")
                }
            }, storage, binding)
        }
        val r = requireNotNull(repo)
        pendingPurchase = r.pending("purchase") != null; pendingRecharge = r.pending("recharge") != null
        // Reconcile money first; a broken catalog cannot preserve an old payment action.
        readPart { purchase = r.currentPurchase() }
        readPart { recharge = r.currentRecharge() }
        readPart { wallet = r.wallet() }
        readPart { rechargeOffers = r.rechargeOffers() }
        readPart { commerceModel = r.loadModel() }
        if (commerceModel != null) readPart { offers = r.offers(); catalogLoaded = true }
        if (includeHistory) {
            commerceModel?.let { selected -> readPart {
                val history = r.purchaseHistory(selected); orderPage = history.first; transitions = history.second
            } }
            readPart { rechargeHistory = r.rechargeHistory() }
        }
    }
    fun loadLedger(more: Boolean = false) = run {
        val old = if (more) ledgerPage else null
        if (!more) ledgerPage = null
        val next = requireNotNull(repo).ledger(old?.nextCursor)
        require(old == null || old.entries.none { entry -> next.entries.any { it.entryRef == entry.entryRef } })
        ledgerPage = if (old == null) next else WalletLedgerPage(old.entries + next.entries, next.nextCursor)
    }
    fun moreOrders() = run {
        val current = orderPage ?: return@run
        val cursor = current.nextCursor ?: return@run
        val next = requireNotNull(repo).orderHistory(cursor)
        require(current.orders.none { old -> next.orders.any { it.ref == old.ref } })
        orderPage = OrderPage(current.orders + next.orders, next.nextCursor)
    }
    private suspend fun readPart(action: suspend () -> Unit) {
        try { action() }
        catch (failure: CancellationException) { throw failure }
        catch (failure: PortalFailure) {
            if (failure.reason == PortalFailure.Reason.SESSION_EXPIRED) throw failure
            error = "部分信息尚未读取，请重试查询；未读取的余额或订单不代表零余额或未付款。"
        }
        catch (_: Exception) { error = "部分信息尚未读取，请重试查询；不要重复下单。" }
    }
    fun preview(offer: Offer) = run { account.purchaseReturnRef = offer.offerRef; quote = null; quote = requireNotNull(repo).preview(offer) }
    fun confirmPurchase() = run {
        val value = requireNotNull(quote); quote = null
        try { purchase = requireNotNull(repo).purchase(value) }
        finally { pendingPurchase = requireNotNull(repo).pending("purchase") != null; wallet = null }
    }
    fun confirmRecharge(offer: RechargeOffer) = run {
        try { recharge = requireNotNull(repo).recharge(offer) }
        finally { pendingRecharge = requireNotNull(repo).pending("recharge") != null }
    }
    fun queryPurchase() = run {
        try { purchase = null; wallet = null; purchase = requireNotNull(repo).retryPurchase(); wallet = requireNotNull(repo).wallet() }
        finally { pendingPurchase = requireNotNull(repo).pending("purchase") != null }
    }
    fun queryRecharge() = run { recharge = null; wallet = null; recharge = requireNotNull(repo).retryRecharge(); wallet = requireNotNull(repo).wallet() }
    suspend fun pollRecharge() {
        if (busy || !currentSession() || recharge?.awaitingUpdate != true) return
        busy = true
        try { // Awaited by the RESUMED lifecycle job, never an independent mutation/retry job.
            val next = requireNotNull(repo).currentRecharge()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!currentSession()) return
            recharge = next
            if (next?.status == "credited") {
                val latest = requireNotNull(repo).wallet()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (currentSession()) wallet = latest
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: PortalFailure) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (currentSession()) {
                if (failure.reason == PortalFailure.Reason.SESSION_EXPIRED) account.commerceSessionExpired()
                error = "充值状态尚未确认，请稍后查询原订单。"
            }
        } catch (_: Exception) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (currentSession()) error = "充值状态尚未确认，请稍后查询原订单。"
        }
        finally { busy = false }
    }
    fun cancelPurchase() = run { purchase = requireNotNull(repo).cancelPurchase(); wallet = null }
    fun finishPurchase() = run { requireNotNull(repo).finishPurchase(); pendingPurchase = false; purchase = null; wallet = null; account.purchaseReturnRef = null; account.refresh() }
    fun finishRecharge() = run { requireNotNull(repo).finishRecharge(); pendingRecharge = false; recharge = null; wallet = null; quote = null }
    fun currentSession() = account.authenticated && account.authSessionGeneration == epoch
    fun canOpenPayment(order: RechargeOrder, now: Instant) = currentSession() && recharge == order && order.paymentMethod == "alipay" && order.adapterKey == "xsd_v1" && order.payableAt(now)
    fun cannotOpenPayment() { error = "无法打开付款页面，请保留当前订单并稍后查询，不要重复创建充值。" }
    private fun run(action: suspend () -> Unit) {
        if (busy || !account.authenticated || account.authSessionGeneration != epoch) return
        busy = true; error = null
        scope.launch {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: PortalFailure) {
                if (!currentSession()) return@launch
                if (error.reason == PortalFailure.Reason.SESSION_EXPIRED) {
                    wallet = null; offers = emptyList(); rechargeOffers = emptyList(); quote = null
                    purchase = null; recharge = null; account.commerceSessionExpired()
                }
                this@CommerceController.error = "未能确认服务响应，请重试查询；不要重复下单。"
            }
            catch (failure: CommerceFailure) {
                if (!currentSession()) return@launch
                error = when(failure.code) {
                    "quote_expired", "quote_stale" -> "报价已失效，请重新获取报价后再确认购买。"
                    "insufficient_coins" -> "钱包余额不足，请充值到账后重新获取报价。"
                    "wallet_not_active" -> "钱包暂不可用于购买，请核对钱包状态。"
                    "offer_unavailable" -> "此套餐当前不可购买，请刷新套餐列表。"
                    "storage" -> "本机恢复记录不可用，已停止新建订单，请先核对已有订单。"
                    else -> "订单尚未确认，请沿用原记录查询，不要重新付款。"
                }
            }
            catch (_: Exception) { if(currentSession()) error = "操作尚未确认，请重试查询；已有记录会沿用原订单，不会另建一笔。" }
            finally { busy = false }
        }
    }
}

@Composable
internal fun CommercePanel(account: AccountController, mode: CommerceMode, stateOverride: CommerceController? = null, autoLoad: Boolean = true,
    onRecharge: (() -> Unit)? = null, onLedger: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = stateOverride ?: remember(account.authSessionGeneration) { CommerceController(account, scope, FileCommerceRecovery(context.applicationContext), includeHistory = mode == CommerceMode.Orders) }
    var orderCategory by remember(account.authSessionGeneration, state.commerceModel) { mutableStateOf(if (state.commerceModel == CommerceModel.WALLET_FIRST) "plans" else "orders") }
    var catalogCategory by remember(account.authSessionGeneration) { mutableStateOf("plan") }
    var selectedRecharge by remember(account.authSessionGeneration) { mutableStateOf<RechargeOffer?>(null) }
    var cancelConfirmation by remember { mutableStateOf(false) }
    var paymentReview by remember(account.authSessionGeneration) { mutableStateOf<RechargeOrder?>(null) }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(account.authSessionGeneration, account.authenticated) { if(autoLoad && account.authenticated) state.load() }
    LaunchedEffect(Unit) { while(true) { now = Instant.now(); kotlinx.coroutines.delay(1000) } }
    // A browser return is merely a reason to reread. It never confirms, creates or pays an order.
    val lifecycle = (LocalActivity.current as? ComponentActivity)?.lifecycle
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, event -> if(autoLoad && event == Lifecycle.Event.ON_RESUME && account.authenticated) {
            selectedRecharge = null; paymentReview = null; cancelConfirmation = false; state.load()
        } }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    LaunchedEffect(state, lifecycle, autoLoad) {
        if (autoLoad) lifecycle?.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                kotlinx.coroutines.delay(10_000)
                if (account.authenticated && !account.restoring && !account.foregroundCheckPending) state.pollRecharge()
            }
        }
    }
    if (!account.authenticated || account.restoring || account.foregroundCheckPending) {
        BrandCard(Modifier.testTag("commerce-signed-out")) { Text(if(mode == CommerceMode.Wallet) "登录后查看钱包" else "登录后查看套餐"); SupportingText("价格、余额与可购买商品由服务器确认。") }
        return
    }
    if (mode != CommerceMode.Mine || state.error != null || state.busy) BrandCard(Modifier.testTag("commerce-status")) {
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Brand.Cyan)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("commerce-error")) }
        BrandButton("刷新钱包与套餐", state::load, enabled = !state.busy, modifier = Modifier.testTag("commerce-refresh"))
    }
    if (mode == CommerceMode.Wallet || mode == CommerceMode.Overview || mode == CommerceMode.Mine) {
        BrandCard(Modifier.testTag("wallet-card")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Eyebrow("可用余额")
                    Text(state.wallet?.let { formatCoins(it.spendableUnits) } ?: "尚未读取", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.testTag("wallet-balance"))
                }
                if (androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.3f)
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.gccwebos.android.R.drawable.brand_wallet_coin), null, modifier = Modifier.size(92.dp))
            }
            if (mode == CommerceMode.Mine) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BrandButton("充值", { onRecharge?.invoke() }, enabled = !state.busy, modifier = Modifier.weight(1f).testTag("mine-recharge"))
                OutlinedButton(onClick = { onLedger?.invoke() }, enabled = !state.busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("mine-ledger")) { Text("账单明细  ›") }
            }
            if (mode != CommerceMode.Mine) state.wallet?.let { w ->
                SupportingText("已充值 ${formatCoins(w.paidCoins)} · 赠送 ${formatCoins(w.bonusCoins)} · 奖励 ${formatCoins(w.rewardCoins)}")
                SupportingText("冻结中 ${formatCoins(w.heldUnits)} · 待到账 ${formatCoins(w.pendingUnits)}")
                SupportingText(when(w.status) { "active" -> "钱包可用"; "not_created" -> "钱包尚未创建"; "frozen" -> "钱包已冻结"; else -> "钱包已关闭" })
            }
            SupportingText("余额以本次成功查询为准；充值到账不等于套餐已购买。")
        }
        if (mode == CommerceMode.Wallet) AlipayBadge()
        if (mode == CommerceMode.Wallet && !state.pendingRecharge) state.rechargeOffers.forEach { offer ->
            BrandCard(Modifier.testTag("recharge-offer-${offer.offerRef}")) {
                Text("充值 ${formatCoins(offer.paidCoinUnits)}", style = MaterialTheme.typography.titleLarge)
                SupportingText(formatMoney(offer.amountMinor, offer.currency, offer.currencyExponent))
                SupportingText(if ("alipay" in offer.paymentMethods) "支付宝 · 安全支付" else "当前原生版本暂不支持此支付方式")
                SupportingText("支付方式以服务器返回为准：" + offer.paymentMethods.joinToString(" / "))
                if ("alipay" !in offer.paymentMethods) SupportingText("当前原生版本暂不支持此支付方式")
                AlipayButton("选择支付宝充值", { selectedRecharge = offer }, enabled = !state.busy && "alipay" in offer.paymentMethods, modifier = Modifier.testTag("recharge-select-${offer.offerRef}"))
            }
        }
        if (mode == CommerceMode.Wallet && state.pendingRecharge) BrandCard(Modifier.testTag("recharge-order")) {
            Text("当前充值", style = MaterialTheme.typography.titleLarge)
            val order = state.recharge
            SupportingText(order?.let { rechargeStatus(it.status) } ?: "上次提交结果待核对，请沿用原记录查询。")
            order?.let { SupportingText("订单 ${it.orderRef}"); SupportingText("${formatMoney(it.amountMinor, it.currency, it.currencyExponent)} → ${formatCoins(it.expectedPaidCoinUnits)}") }
            BrandButton("查询／恢复原充值", state::queryRecharge, enabled = !state.busy)
            if (order != null && order.paymentMethod == "alipay" && order.adapterKey == "xsd_v1" && order.status in setOf("entry_ready", "pending") && order.nextAction != null &&
                order.expiresAt > now && order.nextAction.expiresAt > now) {
                SupportingText("付款方式：${order.paymentMethod} · 通道：${order.adapterKey}")
                AlipayButton("查看支付宝付款信息", { paymentReview = order }, enabled = !state.busy, modifier = Modifier.testTag("recharge-pay"))
            }
            if(order?.status in setOf("credited", "failed_no_charge", "expired")) BrandButton("核对完成，返回充值", state::finishRecharge, enabled = !state.busy)
            SupportingText("返回应用后请查询状态；不要因为页面关闭或等待到账重新付款。")
        }
    } else if (mode == CommerceMode.Plans) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(selected = catalogCategory == "plan", onClick = { catalogCategory = "plan" }, label = { Text("套餐") }, modifier = Modifier.testTag("catalog-plans"))
            FilterChip(selected = catalogCategory == "data_pack", onClick = { catalogCategory = "data_pack" }, label = { Text("流量包") }, modifier = Modifier.testTag("catalog-data-packs"))
        }
        val shownOffers = state.offers.filter { it.category == catalogCategory }
        if (shownOffers.isEmpty() && !state.busy) BrandCard {
            Text(if (!state.catalogLoaded) "商品尚未读取" else if (catalogCategory == "data_pack") "当前暂无可购买流量包" else "当前暂无可购买套餐", style = MaterialTheme.typography.titleMedium)
            SupportingText("只展示本次服务器返回的商品。未发布的商品、档位与价格不会出现在这里。")
        }
        if (!state.pendingPurchase) shownOffers.forEach { offer ->
            BrandCard(Modifier.testTag("plan-offer-${offer.offerRef}")) {
                Text(if (offer.category == "data_pack") offer.title.zh else if(offer.durationDays == 365L) "年卡365天" else "${offer.durationDays}天", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("plan-title-${offer.offerRef}"))
                Text(formatCoins(offer.baseCoinUnits), style = MaterialTheme.typography.headlineSmall)
                SupportingText(if (offer.category == "data_pack") "${offer.trafficGb} GB · 永久有效，用完为止" else "每月流量 ${offer.monthlyTrafficGb} GB")
                SupportingText(offer.summary.zh)
                offer.importantNotices.zh.forEach { SupportingText(it) }
                BrandButton("查看实时报价", { state.preview(offer) }, enabled = !state.busy, modifier = Modifier.testTag("plan-quote-${offer.offerRef}"))
            }
        }
        if (state.pendingPurchase) BrandCard(Modifier.testTag("purchase-result")) {
            Text("当前套餐订单", style = MaterialTheme.typography.titleLarge)
            val result = state.purchase
            SupportingText(result?.let(::purchaseStatus) ?: "上次提交结果待核对，不要重复购买。")
            result?.let { SupportingText("记录 ${it.ref}") }
            BrandButton("查询／恢复原订单", state::queryPurchase, enabled = !state.busy)
            val cancellable = when(result) {
                is PurchaseResult.Order -> result.paymentStatus == "held" && result.cancelUntil > now
                is PurchaseResult.Deferred -> result.state == "reserved"
                else -> false
            }
            if(cancellable) BrandButton("取消此套餐订单", { cancelConfirmation = true }, enabled = !state.busy)
            val done = when(result) {
                is PurchaseResult.Order -> result.paymentStatus == "cancelled" || (result.paymentStatus == "captured" && result.fulfillmentStatus == "delivered")
                is PurchaseResult.Deferred -> result.state in setOf("settled", "cancelled")
                else -> false
            }
            if(done) BrandButton("完成并同步资源", state::finishPurchase, enabled = !state.busy)
        }
    }
    if (mode == CommerceMode.Plans && account.purchaseReturnRef != null) BrandCard(Modifier.testTag("purchase-return")) {
        val retained = state.offers.firstOrNull { it.offerRef == account.purchaseReturnRef }
        Text("继续所选商品", style = MaterialTheme.typography.titleMedium)
        SupportingText("充值不会自动购买。返回后读取当前商品并重新报价，仍需你确认。")
        if (retained != null) BrandButton("重新获取所选商品报价", { state.preview(retained) }, enabled = !state.busy && !state.pendingPurchase, modifier = Modifier.testTag("purchase-return-requote"))
        else SupportingText(if (state.catalogLoaded) "所选商品当前不可用，请重新选择。" else "商品尚未读取，请刷新后重试。")
        onRecharge?.let { BrandButton("去充值，保留所选商品", it, enabled = !state.busy, modifier = Modifier.testTag("purchase-return-recharge")) }
        TextButton(onClick = { account.purchaseReturnRef = null }) { Text("取消保留商品") }
    }
    if (mode == CommerceMode.Orders) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (when(state.commerceModel) {
                CommerceModel.LEGACY -> listOf("orders" to "商城", "recharge" to "充值")
                CommerceModel.WALLET_FIRST -> listOf("plans" to "套餐计划", "recharge" to "充值")
                null -> listOf("recharge" to "充值")
            }).forEach { (key, label) ->
                FilterChip(selected = orderCategory == key, onClick = { orderCategory = key }, label = { Text(label) }, modifier = Modifier.testTag("orders-$key"))
            }
        }
        when (orderCategory) {
            "orders" -> {
                val page = state.orderPage
                HistoryState(page?.orders?.size, state.busy)
                page?.orders?.forEach { order -> PurchaseHistoryCard(order) }
                if (page?.nextCursor != null) BrandButton("加载更早订单", state::moreOrders, enabled = !state.busy)
            }
            "recharge" -> {
                HistoryState(state.rechargeHistory?.size, state.busy)
                state.rechargeHistory?.forEach { order -> BrandCard(Modifier.testTag("history-recharge-${order.orderRef}")) {
                    if (order.paymentMethod == "alipay") AlipayBadge() else SupportingText("支付方式：${order.paymentMethod}")
                    Text(formatMoney(order.amountMinor, order.currency, order.currencyExponent), style = MaterialTheme.typography.titleLarge)
                    SupportingText("${formatCoins(order.expectedPaidCoinUnits)} · ${rechargeStatus(order.status)}")
                    SupportingText("订单 ${order.orderRef}")
                    SupportingText(localTime(order.createdAt))
                } }
            }
            else -> {
                HistoryState(state.transitions?.size, state.busy)
                state.transitions?.forEach { order -> PurchaseHistoryCard(order) }
            }
        }
        SupportingText("订单、支付与发放状态分别以服务器查询为准。关闭支付页面不代表到账；待核对订单请在钱包或套餐中恢复原记录。")
    }
    if (mode == CommerceMode.Ledger) {
        BrandButton("刷新账单", { state.loadLedger() }, enabled = !state.busy)
        HistoryState(state.ledgerPage?.entries?.size, state.busy, "账单")
        state.ledgerPage?.entries?.forEach { entry -> BrandCard {
            Text(ledgerLabel(entry.category), style = MaterialTheme.typography.titleMedium)
            Text("${if (entry.totalDeltaUnits > 0) "+" else ""}${entry.totalDeltaUnits} Coins", style = MaterialTheme.typography.headlineSmall)
            SupportingText(localTime(entry.occurredAt))
            SupportingText("可用变动：${entry.availableDeltaUnits ?: "未单独提供"} · 预留变动：${entry.heldDeltaUnits ?: "未单独提供"}")
            SupportingText("记录 ${entry.entryRef}")
        } }
        if (state.ledgerPage?.nextCursor != null) BrandButton("加载更早账单", { state.loadLedger(true) }, enabled = !state.busy)
    }
    state.quote?.let { quote ->
        val valid = quote.expiresAt > now && quote.purchaseAvailability == "available"
        AlertDialog(onDismissRequest = { if(!state.busy) state.quote = null }, title = { Text(if (quote.offer.category == "data_pack") "确认购买${quote.offer.trafficGb} GB流量包" else "确认购买${quote.offer.durationDays}天套餐") },
            text = { Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("服务平台：clash|飞连 · portal.gccwebos.com")
                Text("本次扣除：${formatCoins(quote.amountUnits)}")
                Text(if (quote.offer.category == "data_pack") "流量：${quote.offer.trafficGb} GB；永久有效，用完为止" else "每月流量：${quote.offer.monthlyTrafficGb} GB")
                Text("报价有效至：${localTime(quote.expiresAt)}")
                quote.transition?.let { Text("服务器安排生效时间：${localTime(it.scheduledFor)}") }
                quote.offer.importantNotices.zh.forEach { Text(it) }
                if(!valid) Text(if(quote.expiresAt <= now) "报价已过期，请关闭后重新获取" else "余额不足，尚缺 ${formatCoins(quote.shortfallUnits)}。请前往我的充值，再重新获取报价。")
                Text("确认后提交一次商品订单；资源到账状态另行核对。")
            } }, confirmButton = { TextButton(onClick = state::confirmPurchase, enabled = valid && !state.busy, modifier = Modifier.testTag("purchase-confirm")) { Text("确认购买") } },
            dismissButton = { TextButton(onClick = { state.quote = null }, enabled = !state.busy, modifier = Modifier.testTag("purchase-cancel")) { Text("取消") } })
    }
    selectedRecharge?.let { offer ->
        AlertDialog(onDismissRequest = { selectedRecharge = null }, title = { Text("确认创建充值订单") },
            text = { Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) { Text("向 clash|飞连（portal.gccwebos.com）充值 ${formatCoins(offer.paidCoinUnits)}，应付 ${formatMoney(offer.amountMinor, offer.currency, offer.currencyExponent)}。创建后还需主动打开支付页面，是否到账以服务器查询为准。") } },
            confirmButton = { TextButton(onClick = { selectedRecharge = null; state.confirmRecharge(offer) }, enabled = !state.busy, modifier = Modifier.testTag("recharge-confirm")) { Text("创建充值订单") } },
            dismissButton = { TextButton(onClick = { selectedRecharge = null }, modifier = Modifier.testTag("recharge-cancel")) { Text("取消") } })
    }
    paymentReview?.let { order ->
        val action = order.nextAction
        val current = state.canOpenPayment(order, now)
        AlertDialog(onDismissRequest = { paymentReview = null }, title = { Text("核对付款信息") },
            text = { Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("服务平台：clash|飞连 · portal.gccwebos.com")
                Text("本次一次性充值：${formatCoins(order.expectedPaidCoinUnits)}")
                Text("服务器确认应付：${formatMoney(order.amountMinor, order.currency, order.currencyExponent)}")
                Text("支付方式：${order.paymentMethod}；通道：${order.adapterKey}")
                Text("将打开：${action?.let { java.net.URI(it.url).host } ?: "支付入口不可用"}")
                Text("订单：${order.orderRef}。请在支付页面核对收款方与最终金额；返回后查询到账，不会自动购买套餐。")
                if (!current) Text("订单已更新或付款入口已过期，请关闭并重新查询。")
            } },
            confirmButton = { TextButton(onClick = {
                paymentReview = null
                if (state.canOpenPayment(order, Instant.now()) && action != null) {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(action.url))) }
                    catch (_: Exception) { state.cannotOpenPayment() }
                }
            }, enabled = current && !state.busy, modifier = Modifier.testTag("payment-open-confirm")) { Text("打开付款页面") } },
            dismissButton = { TextButton(onClick = { paymentReview = null }) { Text("暂不付款") } })
    }
    if(cancelConfirmation) AlertDialog(onDismissRequest = { cancelConfirmation = false }, title = { Text("请求取消此套餐订单？") },
        text = { Text("能否取消由服务器根据当前状态和取消期限决定，请以返回结果为准。") },
        confirmButton = { TextButton(onClick = { cancelConfirmation = false; state.cancelPurchase() }) { Text("确认取消订单") } },
        dismissButton = { TextButton(onClick = { cancelConfirmation = false }) { Text("返回") } })
}
internal fun rechargeStatus(status: String) = when(status) {
    "created" -> "充值订单已创建，支付入口准备中"; "entry_ready", "pending" -> "等待付款或支付确认"
    "payment_verified" -> "支付已核验，Coins 尚待到账"; "credited" -> "Coins 已到账，购买套餐需另行确认"
    "failed_no_charge" -> "订单失败，服务器确认未扣款"; "expired" -> "订单已过期"
    else -> "需要对账，请勿重复付款"
}
internal fun purchaseStatus(value: PurchaseResult): String = when(value) {
    is PurchaseResult.Order -> {
        val payment = when(value.paymentStatus) {
            "held" -> "Coins 已预留"; "capture_pending" -> "扣款处理中"; "captured" -> "已扣款"
            "cancelled" -> "已取消"; "capture_failed" -> "扣款未完成"; else -> "需要对账"
        }
        val fulfillment = when(value.fulfillmentStatus) {
            "not_requested" -> "尚未开始"; "pending" -> "等待发放"; "processing" -> "发放中"
            "delivered" -> "已发放，请同步资源"; else -> "发放失败，需核对"
        }
        "付款：$payment；资源：$fulfillment"
    }
    is PurchaseResult.Deferred -> {
        val phase = when(value.state) {
            "reserved" -> "已预留，等待生效"; "processing" -> "处理中"; "effect_applied" -> "权益已处理，等待结算"
            "settled" -> "已结算，请同步资源"; "requote_required" -> "需要重新报价，请先核对原记录"
            "cancelled" -> "已取消"; else -> "需要对账，请勿重复购买"
        }
        "$phase；计划生效：${localTime(value.scheduledFor)}"
    }
}
private fun localTime(value: Instant) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(ZoneId.systemDefault()).format(value)

/** Original mobile Alipay vector, placed on the same light plate and brand gradient. */
@Composable
private fun AlipayBadge() {
    Surface(color = Brand.SurfaceRaised, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            AlipayMark(48.dp)
            Column { Text("支付宝支付", style = MaterialTheme.typography.titleMedium); SupportingText("安全便捷的在线支付方式") }
        }
    }
}
@Composable
private fun AlipayMark(size: androidx.compose.ui.unit.Dp) {
    Surface(color = androidx.compose.ui.graphics.Color(0xFFF0F9FF), shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp), modifier = Modifier.size(size)) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.gccwebos.android.R.drawable.brand_alipay), null, modifier = Modifier.padding(5.dp))
    }
}
@Composable
private fun AlipayButton(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    Button(onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp)
        .then(Modifier.background(if (enabled) androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Brand.ActionStart, Brand.ActionMiddle, Brand.ActionEnd))
            else androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Brand.SurfaceRaised, Brand.SurfaceRaised)), shape)),
        shape = shape, colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color.Transparent, contentColor = androidx.compose.ui.graphics.Color.White)) {
        AlipayMark(28.dp); Spacer(Modifier.width(10.dp)); Text(text)
    }
}
@Composable
private fun HistoryState(size: Int?, busy: Boolean, label: String = "订单") {
    if (size == null || size == 0) BrandCard {
        Text(if (busy) "正在查询$label" else if (size == null) "${label}尚未读取" else if (label == "订单") "暂无这类订单" else "暂无${label}记录", style = MaterialTheme.typography.titleMedium)
        SupportingText(if (size == null) "查询失败或尚未完成不代表没有订单，请重试。" else "这里只展示服务器返回的记录。")
    }
}
@Composable
private fun PurchaseHistoryCard(order: PurchaseResult) {
    BrandCard(Modifier.testTag("history-order-${order.ref}")) {
        Text(order.offer.title.zh, style = MaterialTheme.typography.titleLarge)
        Text(formatCoins(order.amountUnits), style = MaterialTheme.typography.titleMedium)
        SupportingText(purchaseStatus(order))
        if (order.offer.category == "data_pack") SupportingText("独立流量包 · ${order.offer.trafficGb} GB，永久有效，用完为止。交付与重置入口需服务器另行开放。")
        SupportingText("订单 ${order.ref}")
    }
}

private fun ledgerLabel(value: String) = when(value) {
    "credit" -> "入账"; "pending_to_available" -> "待确认转可用"; "pending_to_reversed" -> "待确认撤回"
    "available_to_held" -> "订单预留"; "held_to_available" -> "预留释放"; "held_to_consumed" -> "订单扣款"
    "held_to_expired", "available_to_expired" -> "余额到期"; "available_to_reversed" -> "可用余额撤回"; else -> "账单记录"
}

/** Native functional counterpart of mobile src/mine.jsx; account data is never hard-coded. */
@Composable
internal fun MinePanel(account: AccountController, autoLoad: Boolean = true, stateOverride: CommerceController? = null, onReturnToPlans: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = stateOverride ?: remember(account.authSessionGeneration) { CommerceController(account, scope, FileCommerceRecovery(context.applicationContext)) }
    var view by remember { mutableStateOf("mine") }
    androidx.activity.compose.BackHandler(enabled = view != "mine") {
        if (account.factorReference != null || account.busy) account.cancelAuthentication() else view = "mine"
    }
    if (view != "mine") TextButton(onClick = { view = "mine" }, modifier = Modifier.testTag("mine-back")) { Text("‹ 返回我的") }
    if (view == "mine") BrandCard(Modifier.testTag("mine-welcome")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SupportingText(if (account.authenticated) "欢迎回来" else "本地订阅与账户分开管理")
                Text(if (account.authenticated) account.account else "账户未登录", style = MaterialTheme.typography.headlineSmall)
            }
            if (androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.3f)
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.gccwebos.android.R.drawable.brand_robot_welcome), null,
                    modifier = Modifier.width(110.dp).height(146.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
        }
    }
    if (view == "security") {
        com.gccwebos.android.portal.AccountPanel(account)
        com.gccwebos.android.portal.SecurityStatusPanel(account, autoLoad)
    } else {
        val mode = when(view) { "ledger" -> CommerceMode.Ledger; "wallet", "recharge" -> CommerceMode.Wallet; else -> CommerceMode.Mine }
        CommercePanel(account, mode, state, autoLoad,
            onRecharge = { view = if (account.authenticated) "recharge" else "security" },
            onLedger = { if (account.authenticated) { view = "ledger"; if (autoLoad) state.loadLedger() } else view = "security" })
        if (!account.authenticated) BrandButton("登录账户管理钱包", { view = "security" }, modifier = Modifier.testTag("mine-login"))
    }
    if (account.authenticated && account.purchaseReturnRef != null && onReturnToPlans != null) BrandCard {
        SupportingText("已保留所选商品；充值到账后请返回重新报价。")
        BrandButton("返回所选商品", onReturnToPlans, modifier = Modifier.testTag("mine-return-purchase"))
    }
    if (view == "mine") BrandCard(Modifier.testTag("mine-menu")) {
        TextButton(onClick = { view = if (account.authenticated) "wallet" else "security" }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("mine-wallet")) {
            Icon(androidx.compose.ui.res.painterResource(com.gccwebos.android.R.drawable.brand_receipt), null, tint = Brand.Amber)
            Spacer(Modifier.width(12.dp)); Text("钱包", modifier = Modifier.weight(1f)); Text("›")
        }
        HorizontalDivider(color = Brand.Border.copy(alpha = .55f))
        TextButton(onClick = { view = "security" }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("mine-security")) {
            Icon(androidx.compose.ui.res.painterResource(com.gccwebos.android.R.drawable.brand_lock), null, tint = Brand.Cyan)
            Spacer(Modifier.width(12.dp)); Text("账户与安全", modifier = Modifier.weight(1f)); Text("›")
        }
        if (account.authenticated) {
            HorizontalDivider(color = Brand.Border.copy(alpha = .55f))
            TextButton(onClick = account::logout, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("mine-signout")) { Text("退出登录（保留本地订阅）") }
        }
    }
}
