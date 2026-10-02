package com.gccwebos.android.commerce

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gccwebos.android.portal.AccountController
import com.gccwebos.android.portal.PortalFailure
import com.gccwebos.android.portal.ReferralController
import com.gccwebos.android.portal.ReferralPanel
import com.gccwebos.android.design.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal enum class CommerceMode { Overview, Mine, Plans, Orders, Wallet, Ledger }
internal class CommerceController(private val account: AccountController, private val scope: CoroutineScope,
    private val storage: CommerceRecovery, private val includeHistory: Boolean = false, initialRepository: CommerceRepository? = null) {
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var wallet by mutableStateOf<Wallet?>(null)
    var walletLoaded by mutableStateOf(false)
    var walletError by mutableStateOf<String?>(null)
    var offers by mutableStateOf<List<Offer>>(emptyList())
    var commerceModel by mutableStateOf<CommerceModel?>(null)
    var catalogLoaded by mutableStateOf(false)
    var ledgerPage by mutableStateOf<WalletLedgerPage?>(null)
    var rechargeOffers by mutableStateOf<List<RechargeOffer>>(emptyList())
    var rechargeOffersLoaded by mutableStateOf(false)
    var rechargeOffersError by mutableStateOf<String?>(null)
    var quote by mutableStateOf<Quote?>(null)
    var purchase by mutableStateOf<PurchaseResult?>(null)
    var recharge by mutableStateOf<RechargeOrder?>(null)
    var pendingPurchase by mutableStateOf(false)
    var pendingRecharge by mutableStateOf(false)
    var orderPage by mutableStateOf<OrderPage?>(null)
    var rechargeHistory by mutableStateOf<List<RechargeOrder>?>(null)
    var transitions by mutableStateOf<List<PurchaseResult.Deferred>?>(null)
    var purchaseHistoryError by mutableStateOf<String?>(null)
    var rechargeHistoryError by mutableStateOf<String?>(null)
    private var repo: CommerceRepository? = initialRepository
    private val epoch = account.authSessionGeneration
    private val identity = account.commerceSessionIdentity
    private var operation = 0L
    private var requestJob: Job? = null
    private class ReadEpoch(val generation: Long, val sequence: Long? = null) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ReadEpoch>
    }
    /** A validation pause revokes old confirmations without deleting account-scoped recovery. */
    fun pauseForAuthentication() {
        operation++; requestJob?.cancel(); requestJob = null; busy = false
        quote = null; recharge = null
    }
    fun load() = run {
        orderPage = null; rechargeHistory = null; transitions = null; catalogLoaded = false; commerceModel = null
        purchaseHistoryError = null; rechargeHistoryError = null
        quote = null; wallet = null; offers = emptyList(); rechargeOffers = emptyList(); purchase = null; recharge = null
        walletLoaded = false; rechargeOffersLoaded = false; walletError = null; rechargeOffersError = null
        val r = repository()
        pendingPurchase = r.pending("purchase") != null; pendingRecharge = r.pending("recharge") != null
        // Reconcile money first; a broken catalog cannot preserve an old payment action.
        readPart({ r.currentPurchase() }) { purchase = it }
        readPart({ r.currentRecharge() }) { recharge = it }
        readPart({ r.wallet() }, onFailure = { walletError = "余额查询失败，请重试。" }) { wallet = it; walletLoaded = true }
        readPart({ r.rechargeOffers() }, onFailure = { rechargeOffersError = "充值报价尚未读取，请重试。" }) { rechargeOffers = it; rechargeOffersLoaded = true }
        readPart({ r.loadModel() }, onFailure = { purchaseHistoryError = "订单类型未能读取，请重试查询。" }) { commerceModel = it }
        if (commerceModel != null) readPart({ r.offers() }) { offers = it; catalogLoaded = true }
        if (includeHistory) {
            commerceModel?.let { selected -> readPart({ r.purchaseHistory(selected) },
                onFailure = { purchaseHistoryError = "订单列表查询失败，请重试；这不代表没有订单。" }) {
                orderPage = it.first; transitions = it.second
            } }
            readPart({ r.rechargeHistory() }, onFailure = { rechargeHistoryError = "充值记录查询失败，请重试；这不代表未付款。" }) { rechargeHistory = it }
        }
    }
    /** Mine balance reads only the wallet; recharge recovery belongs to Wallet and polling. */
    fun loadBalance() = run {
        wallet = null; walletLoaded = false; walletError = null
        val r = repository()
        readPart({ r.wallet() }, onFailure = { walletError = "余额查询失败，请重试。" }) { wallet = it; walletLoaded = true }
    }
    /** Wallet and recharge reads do not depend on a purchase model or silently replay an unknown create. */
    fun loadWallet() = run {
        wallet = null; walletLoaded = false; walletError = null
        rechargeOffers = emptyList(); rechargeOffersLoaded = false; rechargeOffersError = null; recharge = null
        val r = repository()
        pendingRecharge = r.pending("recharge") != null
        readPart({ r.currentRecharge() }) { recharge = it }
        readPart({ r.wallet() }, onFailure = { walletError = "余额查询失败，请重试。" }) { wallet = it; walletLoaded = true }
        readPart({ r.rechargeOffers() }, onFailure = { rechargeOffersError = "充值报价查询失败，请重试。" }) { rechargeOffers = it; rechargeOffersLoaded = true }
    }
    private suspend fun repository(): CommerceRepository {
        if (repo == null) {
            val session = account.commerceSessionBinding()
            val binding = account.commerceAccountBinding()
            ensureCurrentSession()
            repo = CommerceRepository({ path, body, key ->
                ensureCurrentSession()
                val generation = account.authSessionGeneration
                account.commerce(path, body, key, session).also {
                    ensureCurrentSession()
                    if (account.authSessionGeneration != generation) throw CancellationException("Account changed")
                }
            }, storage, binding)
        }
        return requireNotNull(repo)
    }
    fun loadLedger(more: Boolean = false) = run {
        val old = if (more) ledgerPage else null
        if (!more) ledgerPage = null
        val next = repository().ledger(old?.nextCursor)
        ensureCurrentSession()
        require(old == null || old.entries.none { entry -> next.entries.any { it.entryRef == entry.entryRef } })
        ledgerPage = if (old == null) next else WalletLedgerPage(old.entries + next.entries, next.nextCursor)
    }
    fun moreOrders() = run {
        val current = orderPage ?: return@run
        val cursor = current.nextCursor ?: return@run
        purchaseHistoryError = null
        readPart({
            val next = requireNotNull(repo).orderHistory(cursor)
            require(current.orders.none { old -> next.orders.any { it.ref == old.ref } })
            OrderPage(current.orders + next.orders, next.nextCursor)
        }, onFailure = { purchaseHistoryError = "更早订单查询失败，已保留当前列表。" }) { orderPage = it }
    }
    suspend fun historyDetail(selection: HistorySelection): HistoryDetail = withContext(ReadEpoch(account.authSessionGeneration)) {
        ensureCurrentSession()
        val r = requireNotNull(repo)
        val detail = when (selection.kind) {
            HistoryKind.Legacy -> {
                require(commerceModel == CommerceModel.LEGACY)
                HistoryDetail.Purchase(r.purchaseHistoryDetail(CommerceModel.LEGACY, selection.ref))
            }
            HistoryKind.Deferred -> {
                require(commerceModel == CommerceModel.WALLET_FIRST)
                HistoryDetail.Purchase(r.purchaseHistoryDetail(CommerceModel.WALLET_FIRST, selection.ref))
            }
            HistoryKind.Recharge -> HistoryDetail.Recharge(r.rechargeHistoryDetail(selection.ref))
        }
        ensureCurrentSession()
        detail
    }
    fun canOperate() = currentSession() && !account.busy && !account.restoring &&
        !account.foregroundCheckPending && !account.clearing
    private suspend fun currentOperation(): Boolean {
        val context = currentCoroutineContext()
        val read = context[ReadEpoch]
        return context.isActive && canOperate() && (read == null ||
            (read.generation == account.authSessionGeneration && (read.sequence == null || read.sequence == operation)))
    }
    private suspend fun ensureCurrentSession() {
        currentCoroutineContext().ensureActive()
        if (!currentOperation()) throw CancellationException("Account changed")
    }
    private suspend fun <T> readPart(read: suspend () -> T, onFailure: () -> Unit = {}, publish: (T) -> Unit) {
        try {
            val value = read()
            ensureCurrentSession()
            publish(value)
        }
        catch (failure: CancellationException) { throw failure }
        catch (failure: PortalFailure) {
            ensureCurrentSession()
            if (failure.reason == PortalFailure.Reason.SESSION_EXPIRED) throw failure
            onFailure()
            error = "部分信息尚未读取，请重试查询；未读取的余额或订单不代表零余额或未付款。"
        }
        catch (_: Exception) { ensureCurrentSession(); onFailure(); error = "部分信息尚未读取，请重试查询；不要重复下单。" }
    }
    fun preview(offer: Offer) = run {
        account.purchaseReturnRef = offer.offerRef; quote = null
        val value = requireNotNull(repo).preview(offer)
        ensureCurrentSession()
        quote = value
    }
    fun confirmPurchase() = run {
        val value = requireNotNull(quote); quote = null
        try {
            val next = requireNotNull(repo).purchase(value)
            ensureCurrentSession(); purchase = next
        } finally { if (currentOperation()) { pendingPurchase = requireNotNull(repo).pending("purchase") != null; wallet = null } }
    }
    fun confirmRecharge(offer: RechargeOffer) = run {
        try {
            val next = requireNotNull(repo).recharge(offer)
            ensureCurrentSession()
            recharge = next
        }
        finally {
            if (currentOperation())
                pendingRecharge = requireNotNull(repo).pending("recharge") != null
        }
    }
    fun queryPurchase() = run {
        try {
            purchase = null; wallet = null
            val next = requireNotNull(repo).retryPurchase()
            ensureCurrentSession(); purchase = next
            val balance = requireNotNull(repo).wallet()
            ensureCurrentSession(); wallet = balance
        } finally { if (currentOperation()) pendingPurchase = requireNotNull(repo).pending("purchase") != null }
    }
    /** Explicit recovery may replay the SAME durable create; it is never used for a browser return. */
    fun queryRecharge() = run {
        recharge = null; wallet = null; walletLoaded = false; walletError = null
        val next = requireNotNull(repo).retryRecharge()
        ensureCurrentSession(); recharge = next
        val balance = requireNotNull(repo).wallet()
        ensureCurrentSession(); wallet = balance; walletLoaded = true
    }
    fun readRechargeStatus() = run {
        recharge = null
        val next = requireNotNull(repo).currentRecharge()
        ensureCurrentSession(); recharge = next
        if (next?.status == "credited") {
            wallet = null; walletLoaded = false; walletError = null
            readPart({ requireNotNull(repo).wallet() }, onFailure = { walletError = "Coins 已到账，余额尚未读取，请重试查询。" }) {
                wallet = it; walletLoaded = true
            }
        }
    }
    suspend fun pollRecharge() {
        if (busy || !canOperate()) return
        val sequence = ++operation
        withContext(ReadEpoch(account.authSessionGeneration, sequence)) {
        busy = true
        try { // Awaited by the RESUMED lifecycle job, never an independent mutation/retry job.
            val r = repository()
            pendingRecharge = r.pending("recharge") != null
            if (!pendingRecharge || (recharge != null && recharge?.awaitingUpdate != true)) return@withContext
            recharge = null // An unsuccessful read must not preserve an old payment action.
            val next = r.currentRecharge()
            ensureCurrentSession()
            recharge = next
            if (next?.status == "credited") {
                wallet = null; walletLoaded = false
                val latest = requireNotNull(repo).wallet()
                ensureCurrentSession(); wallet = latest; walletLoaded = true
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: PortalFailure) {
            ensureCurrentSession()
            if (currentOperation()) {
                if (failure.reason == PortalFailure.Reason.SESSION_EXPIRED) account.commerceSessionExpired()
                error = "充值状态尚未确认，请稍后查询原订单。"
            }
        } catch (_: Exception) {
            ensureCurrentSession()
            error = "充值状态尚未确认，请稍后查询原订单。"
        }
        finally { if (operation == sequence) busy = false }
        }
    }
    fun cancelPurchase() = run { val next = requireNotNull(repo).cancelPurchase(); ensureCurrentSession(); purchase = next; wallet = null }
    fun finishPurchase() = run { requireNotNull(repo).finishPurchase(); ensureCurrentSession(); pendingPurchase = false; purchase = null; wallet = null; account.purchaseReturnRef = null; account.refresh() }
    fun finishRecharge() = run { requireNotNull(repo).finishRecharge(); ensureCurrentSession(); pendingRecharge = false; recharge = null; wallet = null; walletLoaded = false; quote = null }
    fun currentSession() = account.authenticated && if (identity == null) account.authSessionGeneration == epoch
        else account.commerceSessionIdentity === identity
    fun canOpenPayment(order: RechargeOrder, now: Instant) = canOperate() && recharge == order &&
        order.paymentMethod == "alipay" && order.adapterKey == "xsd_v1" && order.payableAt(now) &&
        order.nextAction?.let { CommerceJson.trustedPaymentRedirect(it.url, order.adapterKey) } == true
    fun cannotOpenPayment() { error = "无法打开付款页面，请保留当前订单并稍后查询，不要重复创建充值。" }
    private fun run(action: suspend () -> Unit) {
        if (busy || !canOperate()) return
        val sequence = ++operation
        val generation = account.authSessionGeneration
        busy = true; error = null
        requestJob = scope.launch(ReadEpoch(generation, sequence)) {
            try {
                ensureCurrentSession()
                if (account.restoring || account.foregroundCheckPending) return@launch
                action()
            }
            catch (error: CancellationException) { throw error }
            catch (error: PortalFailure) {
                ensureCurrentSession()
                if (error.reason == PortalFailure.Reason.SESSION_EXPIRED) {
                    wallet = null; offers = emptyList(); rechargeOffers = emptyList(); quote = null
                    purchase = null; recharge = null; account.commerceSessionExpired()
                }
                this@CommerceController.error = "未能确认服务响应，请重试查询；不要重复下单。"
            }
            catch (failure: CommerceFailure) {
                ensureCurrentSession()
                error = when(failure.code) {
                    "quote_expired", "quote_stale" -> "报价已失效，请重新获取报价后再确认购买。"
                    "insufficient_coins" -> "钱包余额不足，请充值到账后重新获取报价。"
                    "wallet_not_active" -> "钱包暂不可用于购买，请核对钱包状态。"
                    "offer_unavailable" -> "此套餐当前不可购买，请刷新套餐列表。"
                    "storage" -> "本机恢复记录不可用，已停止新建订单，请先核对已有订单。"
                    else -> "订单尚未确认，请沿用原记录查询，不要重新付款。"
                }
            }
            catch (_: Exception) { ensureCurrentSession(); error = "操作尚未确认，请重试查询；已有记录会沿用原订单，不会另建一笔。" }
            finally { if (operation == sequence) { busy = false; requestJob = null } }
        }
    }
}

@Composable
private fun CatalogOfferCard(offer: Offer, selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onSelect: () -> Unit) {
    Surface(modifier.heightIn(min = 100.dp).testTag("plan-offer-${offer.offerRef}")
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = if (selected) Brand.SurfaceRaised else Brand.Surface.copy(alpha = .9f),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Brand.Cyan else Brand.Border)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (selected) "已选择" else "选择", color = if (selected) Brand.Cyan else Brand.Muted,
                style = MaterialTheme.typography.labelSmall)
            val title = if (offer.category == "plan") offer.durationDays?.let { "${it}天" } ?: offer.title.zh else offer.title.zh
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("plan-title-${offer.offerRef}"))
            Text(formatCoins(offer.baseCoinUnits), style = MaterialTheme.typography.bodyLarge,
                color = Brand.Cyan, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("plan-price-${offer.offerRef}"))
        }
    }
}

@Composable
private fun CatalogBenefits(offer: Offer?, category: String) {
    Surface(Modifier.fillMaxWidth().testTag("plan-benefits"), shape = Brand.Shape,
        color = Brand.Surface.copy(alpha = .9f), border = BorderStroke(1.dp, Brand.Border)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (category == "plan") "套餐权益" else "商品权益", style = MaterialTheme.typography.titleMedium)
            if (offer == null) SupportingText("选择商品查看权益") else {
                SupportingText(offer.title.zh)
                CatalogBenefit("有效期", if (category == "plan") "${offer.durationDays} 天" else "永久有效，用完为止")
                CatalogBenefit(if (category == "plan") "每月流量" else "独立流量",
                    if (category == "plan") "${offer.monthlyTrafficGb} GB" else "${offer.trafficGb} GB")
                CatalogBenefit("支付余额", offer.acceptedCoinTypes.joinToString("、", transform = ::ledgerCoinType))
            }
        }
    }
}

@Composable
private fun CatalogBenefit(label: String, value: String) {
    if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { SupportingText(label); Text(value) }
    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SupportingText(label, Modifier.weight(1f)); Text(value, modifier = Modifier.weight(1.2f))
    }
}

private data class CatalogSelection(val offer: Offer, val accountGeneration: Long)

@Composable
internal fun CommercePanel(account: AccountController, mode: CommerceMode, stateOverride: CommerceController? = null, autoLoad: Boolean = true,
    onRecharge: (() -> Unit)? = null, onLedger: (() -> Unit)? = null, paymentOpener: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = stateOverride ?: remember(account.authSessionGeneration) { CommerceController(account, scope, FileCommerceRecovery(context.applicationContext), includeHistory = mode == CommerceMode.Orders) }
    var catalogCategory by remember(account.authSessionGeneration) { mutableStateOf("plan") }
    // A catalog selection is the complete server snapshot, scoped to this controller and account.
    // A same-ref replacement (including an altered version, price or delivery) must be selected again.
    var selectedSnapshot by remember(state) {
        mutableStateOf(state.quote?.let { CatalogSelection(it.offer, account.authSessionGeneration) })
    }
    var selectionNotice by remember(state, account.authSessionGeneration) { mutableStateOf<String?>(null) }
    var cancelConfirmation by remember(account.authSessionGeneration, state) { mutableStateOf(false) }
    val walletMode = mode in setOf(CommerceMode.Wallet, CommerceMode.Mine, CommerceMode.Overview)
    var now by remember { mutableStateOf(Instant.now()) }
    val accountReady = state.canOperate()
    val selectedOffer = selectedSnapshot?.takeIf { selection ->
        mode == CommerceMode.Plans && state.catalogLoaded && accountReady && !state.pendingPurchase &&
            selection.accountGeneration == account.authSessionGeneration && selection.offer.category == catalogCategory &&
            state.offers.any { it == selection.offer }
    }?.offer
    LaunchedEffect(state, account.authSessionGeneration, catalogCategory, state.catalogLoaded, state.offers, accountReady, state.pendingPurchase) {
        if (mode == CommerceMode.Plans && selectedSnapshot != null && selectedOffer == null) {
            selectedSnapshot = null
            state.quote = null
        }
    }
    LaunchedEffect(state, state.quote, selectedOffer, mode) {
        if (mode == CommerceMode.Plans && state.quote != null && state.quote?.offer != selectedOffer) {
            state.quote = null
            selectedSnapshot = null
            selectionNotice = "商品已更新，请刷新后重新选择"
        }
    }
    LaunchedEffect(state, account.authSessionGeneration, accountReady, autoLoad, mode) {
        // Login exposes the session before resource loading finishes. Account work can also
        // interrupt an existing read: revoke it, then reload once the same session is eligible.
        if (!accountReady) state.pauseForAuthentication()
        if (autoLoad && accountReady) {
            // Commerce busy is not an effect key: completing/failing a read must not retry it.
            snapshotFlow { !state.busy && state.canOperate() }.first { it }
            if (state.canOperate()) {
                if (mode == CommerceMode.Mine) state.loadBalance()
                else if (walletMode) state.loadWallet()
                else if (mode == CommerceMode.Ledger) state.loadLedger()
                else if (!state.catalogLoaded) state.load()
            }
        }
    }
    LaunchedEffect(Unit) { while(true) { now = Instant.now(); kotlinx.coroutines.delay(1000) } }
    // A browser return is merely a reason to reread. It never confirms, creates or pays an order.
    val lifecycle = (LocalActivity.current as? ComponentActivity)?.lifecycle
    DisposableEffect(lifecycle, state, autoLoad, mode) {
        val observer = LifecycleEventObserver { _, event -> if(autoLoad && event == Lifecycle.Event.ON_RESUME &&
            state.currentSession() && !account.restoring && !account.foregroundCheckPending) {
            cancelConfirmation = false
            if (mode == CommerceMode.Mine) state.loadBalance() else if (walletMode) state.loadWallet()
            else if (mode == CommerceMode.Ledger) state.loadLedger() else state.load()
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
        BrandCard(Modifier.testTag("commerce-signed-out")) {
            Text(if (account.restoring || account.foregroundCheckPending) "正在验证登录"
                else if(mode == CommerceMode.Wallet) "登录后查看钱包" else if(mode == CommerceMode.Orders) "登录后查看订单" else "登录后查看套餐")
            SupportingText("价格、余额与可购买商品由服务器确认。")
        }
        return
    }
    if (mode == CommerceMode.Plans) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("套餐", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { selectedSnapshot = null; selectionNotice = null; state.quote = null; state.load() },
                enabled = !state.busy && state.canOperate(), modifier = Modifier.size(48.dp).testTag("commerce-refresh")) {
                Icon(painterResource(com.gccwebos.android.R.drawable.brand_refresh), "刷新商品", tint = Brand.Cyan)
            }
        }
        if (state.busy || !state.catalogLoaded) Text(if (state.busy) "读取中…" else "商品尚未读取",
            modifier = Modifier.testTag("catalog-status"), style = MaterialTheme.typography.bodyMedium)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("catalog-loading"), color = Brand.Cyan)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("commerce-error")) }
        selectionNotice?.let { SupportingText(it, Modifier.testTag("plan-selection-notice")) }
    } else if (mode != CommerceMode.Orders && mode != CommerceMode.Ledger && !walletMode) BrandCard(Modifier.testTag("commerce-status")) {
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Brand.Cyan)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("commerce-error")) }
        BrandButton("刷新钱包与套餐", state::load, enabled = !state.busy, modifier = Modifier.testTag("commerce-refresh"))
    }
    if (walletMode) {
        WalletPanel(account, state, mode, now, paymentOpener ?: { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }, onRecharge, onLedger)
        return // Wallet never inherits a retained purchase confirmation from another destination.
    } else if (mode == CommerceMode.Plans) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(selected = catalogCategory == "plan", onClick = { selectedSnapshot = null; selectionNotice = null; state.quote = null; catalogCategory = "plan" }, enabled = !state.busy,
                label = { Text("套餐") }, modifier = Modifier.testTag("catalog-plans"))
            FilterChip(selected = catalogCategory == "data_pack", onClick = { selectedSnapshot = null; selectionNotice = null; state.quote = null; catalogCategory = "data_pack" }, enabled = !state.busy,
                label = { Text("流量包") }, modifier = Modifier.testTag("catalog-data-packs"))
        }
        val shownOffers = if (state.catalogLoaded && state.currentSession()) state.offers.filter { it.category == catalogCategory } else emptyList()
        if (shownOffers.isEmpty() && !state.busy) BrandCard {
            Text(if (!state.catalogLoaded) "点击刷新重试" else if (catalogCategory == "data_pack") "暂无可购流量包" else "暂无可购套餐",
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("catalog-empty"))
        }
        if (!state.pendingPurchase && shownOffers.isNotEmpty()) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
                val columns = minOf(shownOffers.size, if (fontScale > 1.3f || maxWidth < 280.dp) 1 else if (maxWidth < 320.dp) 2 else 3)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shownOffers.chunked(columns).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { offer -> CatalogOfferCard(offer, selectedOffer == offer,
                                enabled = !state.busy && state.canOperate(), modifier = Modifier.weight(1f),
                                onSelect = { if (!state.busy && state.canOperate() && state.catalogLoaded && state.offers.any { it == offer }) {
                                    state.quote = null; selectionNotice = null; selectedSnapshot = CatalogSelection(offer, account.authSessionGeneration)
                                } }) }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            CatalogBenefits(selectedOffer, catalogCategory)
            BrandButton(if (state.busy) "处理中…" else "继续购买", {
                val selected = selectedSnapshot
                if (selected != null && !state.busy && state.canOperate() && state.catalogLoaded &&
                    !state.pendingPurchase && selected.accountGeneration == account.authSessionGeneration &&
                    selected.offer.category == catalogCategory && state.offers.any { it == selected.offer }) state.preview(selected.offer)
            }, enabled = selectedOffer != null && !state.busy && state.canOperate(), modifier = Modifier.testTag("plan-continue"))
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
        SupportingText("充值后需重新报价并确认购买")
        if (retained != null && state.catalogLoaded) TextButton(onClick = {
            if (state.canOperate() && !state.busy && state.catalogLoaded && state.offers.any { it == retained }) {
                catalogCategory = retained.category
                selectedSnapshot = CatalogSelection(retained, account.authSessionGeneration); selectionNotice = null; state.quote = null
            }
        },
            enabled = !state.busy && !state.pendingPurchase && state.canOperate(), modifier = Modifier.heightIn(min = 48.dp).testTag("purchase-return-requote")) { Text("重新选择所保留商品") }
        else SupportingText(if (state.catalogLoaded) "商品当前不可用，请重新选择" else "点击刷新读取商品")
        onRecharge?.let { TextButton(onClick = it, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("purchase-return-recharge")) { Text("去充值") } }
        TextButton(onClick = { account.purchaseReturnRef = null }) { Text("取消保留商品") }
    }
    if (mode == CommerceMode.Orders) {
        OrderHistoryPanel(account, state)
        return // History has only read/local-return actions, even if a shared fixture retains a quote.
    }
    if (mode == CommerceMode.Ledger) {
        LedgerPanel(account, state)
        return // Read-only history must not inherit a retained purchase confirmation.
    }
    state.quote?.let { quote ->
        val valid = quote.purchasableAt(now) && state.canOperate() && !state.pendingPurchase &&
            selectedOffer == quote.offer && state.catalogLoaded && state.offers.any { it == quote.offer }
        AlertDialog(onDismissRequest = { if(!state.busy) state.quote = null }, title = { Text(if (quote.offer.category == "data_pack") "确认购买${quote.offer.trafficGb} GB流量包" else "确认购买${quote.offer.durationDays}天套餐") },
            text = { Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("服务平台：飞连vpn · portal.gccwebos.com")
                Text("本次扣除：${formatCoins(quote.amountUnits)}")
                quote.allocation.forEach { Text("${ledgerCoinType(it.coinType)}：${formatCoins(it.amountUnits)}") }
                Text("确认后预留：${formatCoins(quote.balances.after.heldCoins)}")
                Text(if (quote.offer.category == "data_pack") "流量：${quote.offer.trafficGb} GB；永久有效，用完为止" else "每月流量：${quote.offer.monthlyTrafficGb} GB")
                Text("报价有效至：${localTime(quote.expiresAt)}")
                quote.transition?.let { Text("服务器安排生效时间：${localTime(it.scheduledFor)}") }
                quote.offer.importantNotices.zh.forEach { Text(it) }
                if(!valid) Text(when {
                    quote.expiresAt <= now -> "报价已过期，请关闭后重新获取"
                    quote.purchaseAvailability != "available" -> "余额不足，尚缺 ${formatCoins(quote.shortfallUnits)}。请前往我的充值，再重新获取报价。"
                    else -> "商品或账户已变化，请重新获取报价"
                })
                Text("确认后提交一次商品订单；资源到账状态另行核对。")
            } }, confirmButton = { TextButton(onClick = {
                if (!state.busy && state.canOperate() && !state.pendingPurchase && state.quote === quote &&
                    quote.purchasableAt(Instant.now()) && selectedSnapshot?.accountGeneration == account.authSessionGeneration &&
                    selectedSnapshot?.offer == quote.offer && state.catalogLoaded &&
                    state.offers.any { it == quote.offer }) state.confirmPurchase()
            }, enabled = valid && !state.busy, modifier = Modifier.testTag("purchase-confirm")) { Text("确认购买") } },
            dismissButton = { TextButton(onClick = { state.quote = null }, enabled = !state.busy, modifier = Modifier.testTag("purchase-cancel")) { Text("取消") } })
    }
    if(cancelConfirmation) AlertDialog(onDismissRequest = { cancelConfirmation = false },
        modifier = Modifier.testTag("purchase-order-cancel-dialog"), title = { Text("请求取消此套餐订单？") },
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

/** Balance movements retain their supplied delta field; a reservation is not a debit. */
@Composable
private fun LedgerPanel(account: AccountController, state: CommerceController) {
    var selectedEntry by remember(state, account.authSessionGeneration) { mutableStateOf<WalletLedgerEntry?>(null) }
    val rows = state.ledgerPage?.entries
    val detail = selectedEntry?.takeIf { state.canOperate() && rows?.contains(it) == true }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("账单明细", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        IconButton(onClick = { selectedEntry = null; state.loadLedger() }, enabled = !state.busy && state.canOperate(),
            modifier = Modifier.size(48.dp).testTag("ledger-refresh")) {
            Icon(painterResource(com.gccwebos.android.R.drawable.brand_refresh), "刷新账单", tint = Brand.Cyan)
        }
    }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("ledger-error")) }
    when {
        state.busy -> LinearProgressIndicator(Modifier.fillMaxWidth().testTag("ledger-loading"), color = Brand.Cyan)
        rows == null -> SupportingText("账单尚未读取，点击刷新重试", Modifier.testTag("ledger-unread"))
        rows.isEmpty() -> SupportingText("暂无账单", Modifier.testTag("ledger-empty"))
    }
    if (state.currentSession()) rows?.groupBy { ledgerDate(it.occurredAt) }?.forEach { (date, entries) ->
        Text(date, style = MaterialTheme.typography.labelLarge, color = Brand.Muted, modifier = Modifier.testTag("ledger-date-$date"))
        entries.forEach { entry ->
            LedgerRow(entry, enabled = !state.busy && state.canOperate()) { selectedEntry = entry }
        }
    }
    if (state.ledgerPage?.nextCursor != null) TextButton(onClick = { selectedEntry = null; state.loadLedger(true) },
        enabled = !state.busy && state.canOperate(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ledger-more")) { Text("加载更早账单") }
    detail?.let { entry ->
        AlertDialog(onDismissRequest = { selectedEntry = null }, modifier = Modifier.testTag("ledger-detail"),
            title = { Text("余额变动详情") }, text = {
                Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LedgerDetailField("交易类型", ledgerLabel(entry.category))
                    LedgerDetailField("币种", ledgerCoinType(entry.coinType))
                    LedgerDetailField("时间", localTime(entry.occurredAt))
                    LedgerDetailField("总余额变化", ledgerSigned(entry.totalDeltaUnits), "ledger-detail-total")
                    LedgerDetailField("可用变化", ledgerSigned(entry.availableDeltaUnits), "ledger-detail-available")
                    LedgerDetailField("待确认变化", ledgerSigned(entry.pendingDeltaUnits), "ledger-detail-pending")
                    LedgerDetailField("预留变化", ledgerSigned(entry.heldDeltaUnits), "ledger-detail-held")
                    LedgerDetailField("记录金额", formatCoins(entry.amountUnits))
                    LedgerDetailField("记录标识", entry.entryRef, "ledger-detail-ref")
                    entry.relatedOrderRef?.let { LedgerDetailField("关联订单", it) }
                }
            }, confirmButton = { TextButton(onClick = { selectedEntry = null }, modifier = Modifier.testTag("ledger-detail-close")) { Text("返回账单") } })
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
    val largeFont = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    Surface(Modifier.fillMaxWidth().testTag("ledger-row-${entry.entryRef}")
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = Brand.Surface.copy(alpha = .9f), border = BorderStroke(1.dp, Brand.Border)) {
        Row(Modifier.heightIn(min = 64.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(if (entry.category == "available_to_held") com.gccwebos.android.R.drawable.brand_lock
                else com.gccwebos.android.R.drawable.brand_wallet), null, tint = Brand.Cyan, modifier = Modifier.size(24.dp))
            val heading: @Composable () -> Unit = {
                Text(ledgerLabel(entry.category), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                SupportingText("${ledgerClock(entry.occurredAt)} · ${ledgerCoinType(entry.coinType)}")
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
            Text("›", color = Brand.Muted)
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
private fun ledgerCoinType(value: String) = when (value) { "paid_coins" -> "已购 Coins"; "bonus_coins" -> "赠送 Coins"; "reward_coins" -> "奖励 Coins"; else -> "Coins" }
private fun ledgerDate(value: Instant) = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault()).format(value)
private fun ledgerClock(value: Instant) = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(value)

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
internal fun AlipayButton(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
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

private fun ledgerLabel(value: String) = when(value) {
    "credit" -> "入账"; "pending_to_available" -> "待确认转可用"; "pending_to_reversed" -> "待确认撤回"
    "available_to_held" -> "订单预留"; "held_to_available" -> "预留释放"; "held_to_consumed" -> "订单扣款"
    "held_to_expired", "available_to_expired" -> "余额到期"; "available_to_reversed" -> "可用余额撤回"; else -> "账单记录"
}

/** Native functional counterpart of mobile src/mine.jsx; account data is never hard-coded. */
@Composable
internal fun MinePanel(account: AccountController, autoLoad: Boolean = true, stateOverride: CommerceController? = null,
    onReturnToPlans: (() -> Unit)? = null, telegramOpener: ((String) -> Unit)? = null,
    referralOverride: ReferralController? = null, copyOverride: ((String) -> Unit)? = null,
    recoveryOverride: CommerceRecovery? = null, paymentOpener: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionOwner = account.commerceSessionIdentity ?: account.authSessionGeneration
    val state = stateOverride ?: remember(sessionOwner, recoveryOverride) { CommerceController(account, scope,
        recoveryOverride ?: FileCommerceRecovery(context.applicationContext), includeHistory = true) }
    var view by remember(sessionOwner) { mutableStateOf("mine") }
    var historyTab by remember(sessionOwner) { mutableStateOf("orders") }
    var telegramUnavailable by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = view != "mine") {
        if (account.factorReference != null || account.busy) account.cancelAuthentication() else view = "mine"
    }
    if (view != "mine") TextButton(onClick = { view = "mine" }, modifier = Modifier.heightIn(min = 48.dp).testTag("mine-back")) { Text("‹ 返回我的") }
    if (view == "mine") BrandCard(Modifier.testTag("mine-welcome")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Icon(androidx.compose.ui.res.painterResource(com.gccwebos.android.R.drawable.brand_user), null,
                tint = Brand.Cyan, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("我的", style = MaterialTheme.typography.titleLarge)
                Text(if (account.authenticated) account.displayEmail ?: "账户" else "账户未登录",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("mine-account-identity"))
            }
        }
    }
    if (view == "security") {
        com.gccwebos.android.portal.AccountPanel(account, compactLogin = true)
        com.gccwebos.android.portal.SecurityStatusPanel(account, autoLoad)
    } else if (view == "referral") {
        ReferralPanel(account, autoLoad, referralOverride, copyOverride)
    } else {
        if (view == "history") {
            Row(Modifier.fillMaxWidth().testTag("mine-history-tabs"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = historyTab == "orders", onClick = { historyTab = "orders" },
                    enabled = !state.busy && state.canOperate(), label = { Text("订单") },
                    modifier = Modifier.testTag("mine-history-orders"))
                FilterChip(selected = historyTab == "ledger", onClick = {
                    historyTab = "ledger"
                }, enabled = !state.busy && state.canOperate(), label = { Text("账单明细") },
                    modifier = Modifier.testTag("mine-history-ledger"))
            }
        }
        val mode = when(view) {
            "history" -> if (historyTab == "ledger") CommerceMode.Ledger else CommerceMode.Orders
            "ledger" -> CommerceMode.Ledger
            "wallet", "recharge" -> CommerceMode.Wallet
            else -> CommerceMode.Mine
        }
        CommercePanel(account, mode, state, autoLoad, paymentOpener = paymentOpener,
            onRecharge = { view = if (account.authenticated) "recharge" else "security" },
            onLedger = { if (account.authenticated) {
                view = "history"; historyTab = "ledger"
            } else view = "security" })
        if (!account.authenticated && !account.retainingCommerceSession) BrandButton("登录账户管理钱包", { view = "security" }, modifier = Modifier.testTag("mine-login"))
    }
    if (account.authenticated && account.purchaseReturnRef != null && onReturnToPlans != null) BrandCard {
        SupportingText("已保留所选商品；充值到账后请返回重新报价。")
        BrandButton("返回所选商品", onReturnToPlans, modifier = Modifier.testTag("mine-return-purchase"))
    }
    if (view == "mine") Surface(Modifier.fillMaxWidth().testTag("mine-menu"), shape = Brand.Shape,
        color = Brand.Surface.copy(alpha = .82f), border = BorderStroke(1.dp, Brand.Border)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
        MineMenuRow("钱包", "mine-wallet", !state.busy && !account.busy && !account.restoring && !account.foregroundCheckPending) { view = if (account.authenticated) "wallet" else "security" }
        HorizontalDivider(color = Brand.Border.copy(alpha = .55f))
        MineMenuRow("订单与账单", "mine-history", !state.busy && !account.busy && !account.restoring && !account.foregroundCheckPending) {
            view = if (account.authenticated) "history" else "security"
            historyTab = "orders"
        }
        HorizontalDivider(color = Brand.Border.copy(alpha = .55f))
        MineMenuRow("账户与安全", "mine-security", !account.busy) { view = "security" }
        HorizontalDivider(color = Brand.Border.copy(alpha = .55f))
        MineMenuRow("推荐奖励", "mine-referral", !account.busy) { view = if (account.authenticated) "referral" else "security" }
        HorizontalDivider(color = Brand.Border.copy(alpha = .55f))
        MineMenuRow("Telegram频道  @flylinevpn", "mine-telegram") {
            telegramUnavailable = false
            try { (telegramOpener ?: { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) })("https://t.me/flylinevpn") }
            catch (_: Exception) { telegramUnavailable = true }
        }
        if (telegramUnavailable) SupportingText("暂时无法打开 Telegram 频道，请稍后重试。")
        if (account.authenticated) {
            HorizontalDivider(color = Brand.Border.copy(alpha = .55f))
            MineMenuRow("退出登录（保留本地订阅）", "mine-signout", !account.busy, account::logout)
        }
        }
    }
}

@Composable
private fun MineMenuRow(title: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) {
        val icon = when (tag) {
            "mine-wallet" -> com.gccwebos.android.R.drawable.brand_wallet
            "mine-history" -> com.gccwebos.android.R.drawable.brand_receipt
            "mine-security" -> com.gccwebos.android.R.drawable.brand_lock
            "mine-referral" -> com.gccwebos.android.R.drawable.brand_share
            "mine-telegram" -> com.gccwebos.android.R.drawable.brand_telegram
            else -> com.gccwebos.android.R.drawable.brand_arrow
        }
        Icon(painterResource(icon), null, tint = if (enabled) Brand.Cyan else Brand.Muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp)); Text("›")
    }
}
