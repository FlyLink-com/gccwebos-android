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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import com.gccwebos.android.design.dialog.GccDialog
import com.gccwebos.android.design.dialog.GccDialogAction
import com.gccwebos.android.shouldCancelLoginOnBack
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

/** Content slot of one 套餐 tab (category + plan period); keys the sliding tab content. */
private data class CatalogTabKey(val category: String, val period: CatalogPeriod?)

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
    /** Advisory plan-change display state; null when the server does not offer plan changes (feature off) or on any doubt. */
    var planSwitch by mutableStateOf<PlanSwitchState?>(null)
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
        quote = null; recharge = null; planSwitch = null
    }
    /** Catalog reads never wait for wallet, recharge offers, or recharge recovery. */
    fun loadCatalog() = run {
        catalogLoaded = false; commerceModel = null; offers = emptyList(); quote = null; purchase = null; planSwitch = null
        val r = repository()
        // Preserve an existing purchase before exposing a new checkout action. An unknown
        // create is never replayed by navigation; currentPurchase only reads a known result.
        pendingPurchase = r.pending("purchase") != null
        readPart({ r.currentPurchase() }) { purchase = it }
        readPart({ r.loadModel() }) { commerceModel = it }
        if (commerceModel != null) readPart({ r.offers() }) { offers = it; catalogLoaded = true }
        if (catalogLoaded) readPlanSwitch(r)
    }
    /** Best effort: any failure just shows the ordinary catalog (the server still decides every quote and confirmation). */
    private suspend fun readPlanSwitch(r: CommerceRepository) {
        val value = try { r.planSwitchState() } catch (failure: CancellationException) { throw failure } catch (_: Exception) { null }
        ensureCurrentSession()
        planSwitch = value
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
            }, storage, binding, optionalRequest = { path ->
                ensureCurrentSession()
                val generation = account.authSessionGeneration
                account.commerceOptional(path, session).also {
                    ensureCurrentSession()
                    if (account.authSessionGeneration != generation) throw CancellationException("Account changed")
                }
            })
        }
        return requireNotNull(repo)
    }
    fun loadLedger(more: Boolean = false) = run {
        val old = if (more) ledgerPage else null
        // A refresh keeps the previous page of this same session on screen until the new one arrives
        // (the controller is session-scoped), so re-opening 订单明细 is not a blank wait.
        val next = repository().ledger(old?.nextCursor)
        ensureCurrentSession()
        require(old == null || old.entries.none { entry -> next.entries.any { it.entryRef == entry.entryRef } })
        ledgerPage = if (old == null) next else WalletLedgerPage(old.entries + next.entries, next.nextCursor)
        // 订单明细 also lists unpaid recharge orders (the recharge screen never does). An extra read: if it fails, the ledger
        // itself still shows and an unpaid order simply isn't listed this time.
        if (!more) {
            val orders = try { repository().rechargeHistory() } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
            ensureCurrentSession()
            if (orders != null) rechargeHistory = orders
        }
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
    /** [onCreated] runs right after the server's order is installed, still inside the operation (e.g. to open its payment entry). */
    fun confirmRecharge(offer: RechargeOffer, onCreated: (RechargeOrder) -> Unit = {}) = run {
        try {
            val next = requireNotNull(repo).recharge(offer)
            ensureCurrentSession()
            recharge = next
            onCreated(next)
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
    fun finishPurchase() = run {
        requireNotNull(repo).finishPurchase(); ensureCurrentSession(); pendingPurchase = false; purchase = null; wallet = null
        account.purchaseReturnRef = null
        // The card, its expiry and any scheduled downgrade just changed; read them again with the account.
        if (catalogLoaded) readPlanSwitch(requireNotNull(repo))
        account.refresh()
    }
    /** Drops the pointer to an unpaid order that cannot be paid any more (the server expires it by itself). */
    fun abandonRecharge(now: Instant = Instant.now()) = run {
        requireNotNull(repo).abandonRecharge(now); ensureCurrentSession(); pendingRecharge = false; recharge = null
    }
    fun finishRecharge() = run { requireNotNull(repo).finishRecharge(); ensureCurrentSession(); pendingRecharge = false; recharge = null; wallet = null; walletLoaded = false; quote = null }
    fun currentSession() = account.authenticated && if (identity == null) account.authSessionGeneration == epoch
        else account.commerceSessionIdentity === identity
    /** The current order, or a still-payable unpaid order from 订单明细 (the server list carries the same trusted entry). */
    fun canOpenPayment(order: RechargeOrder, now: Instant) = canOperate() && (recharge == order || rechargeHistory?.contains(order) == true) &&
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
                    else -> planSwitchErrorText(failure.code)?.takeIf { failure.status == 409 }
                        ?: "订单尚未确认，请沿用原记录查询，不要重新付款。"
                }
                // A refused plan change means the card list is out of date: read it again so the cards and the dock agree.
                if (failure.status == 409 && (failure.code in PlanSwitchRejections || failure.code == "offer_unavailable") && catalogLoaded)
                    try { readPlanSwitch(requireNotNull(repo)) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) {}
            }
            catch (_: Exception) { ensureCurrentSession(); error = "操作尚未确认，请重试查询；已有记录会沿用原订单，不会另建一笔。" }
            finally { if (operation == sequence) { busy = false; requestJob = null } }
        }
    }
}

private data class CatalogSelection(val offer: Offer, val accountGeneration: Long)

@Composable
internal fun CommercePanel(account: AccountController, mode: CommerceMode, stateOverride: CommerceController? = null, autoLoad: Boolean = true,
    onRecharge: (() -> Unit)? = null, onLedger: (() -> Unit)? = null, paymentOpener: ((String) -> Unit)? = null,
    embeddedWallet: Boolean = false, defaultSelectionRequest: Int = 0, refreshRequest: Int = 0,
    catalogDisplayCache: CatalogDisplayCache? = null, onWalletBack: (() -> Unit)? = null,
    onRechargeRecords: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = stateOverride ?: remember(account.authSessionGeneration) { CommerceController(account, scope, FileCommerceRecovery(context.applicationContext), includeHistory = mode == CommerceMode.Orders) }
    var catalogCategory by remember(account.authSessionGeneration) { mutableStateOf("plan") }
    var requestedPeriod by remember(account.authSessionGeneration) { mutableStateOf<CatalogPeriod?>(null) }
    // A catalog selection is the complete server snapshot, scoped to this controller and account.
    // A same-ref replacement (including an altered version, price or delivery) must be selected again.
    var selectedSnapshot by remember(state, account.authSessionGeneration) {
        mutableStateOf<CatalogSelection?>(null)
    }
    var selectionNotice by remember(state, account.authSessionGeneration) { mutableStateOf<String?>(null) }
    // One default preference per visit. Refreshes and changed snapshots never silently
    // choose another product, and a new visit never reopens a retained quote.
    var initialSelectionHandled by remember(state, account.authSessionGeneration) {
        mutableStateOf(false)
    }
    var handledVisit by remember(state, account.authSessionGeneration) { mutableStateOf<Int?>(null) }
    var handledRefresh by remember(state, account.authSessionGeneration, mode) { mutableStateOf<Int?>(null) }
    val visitReady = (mode != CommerceMode.Plans || handledVisit == defaultSelectionRequest) &&
        (!autoLoad || handledRefresh == refreshRequest)
    val displayOwner = account.commerceSessionIdentity ?: account.authSessionGeneration
    var lastDisplayedOffers by remember(state, displayOwner) { mutableStateOf<List<Offer>>(emptyList()) }
    val cachedOffers = if (mode == CommerceMode.Plans) catalogDisplayCache?.read(account.commerceSessionIdentity).orEmpty() else emptyList()
    val displayOffers = when {
        !state.currentSession() -> emptyList()
        state.catalogLoaded -> state.offers
        state.busy || state.error != null -> lastDisplayedOffers.ifEmpty { cachedOffers }
        else -> cachedOffers
    }
    SideEffect {
        if (state.currentSession() && state.catalogLoaded) {
            lastDisplayedOffers = state.offers
            if (mode == CommerceMode.Plans && !state.busy && state.error == null)
                catalogDisplayCache?.publish(account.commerceSessionIdentity, state.offers)
        }
    }
    var cancelConfirmation by remember(account.authSessionGeneration, state) { mutableStateOf(false) }
    val walletMode = mode in setOf(CommerceMode.Wallet, CommerceMode.Mine, CommerceMode.Overview)
    var now by remember { mutableStateOf(Instant.now()) }
    val accountReady = state.canOperate()
    val period = requestedPeriod ?: selectedSnapshot?.offer?.let(::catalogPeriod) ?: initialCatalogPeriod(displayOffers)
    // Plan-change labels per card (empty when the server offers none): the card list, dock and dialog read the same map.
    val switchCards = if (mode == CommerceMode.Plans && state.currentSession()) planSwitchCards(state.planSwitch, now) else emptyMap()
    fun currentSelectedOffer(): Offer? = selectedSnapshot?.takeIf { selection ->
        switchCards[selection.offer.offerRef]?.selectable != false &&
        mode == CommerceMode.Plans && state.catalogLoaded && state.canOperate() && !state.pendingPurchase &&
            selection.accountGeneration == account.authSessionGeneration && selection.offer.category == catalogCategory &&
            (catalogCategory != "plan" || catalogPeriod(selection.offer) == period) &&
            state.offers.any { it == selection.offer }
    }?.offer
    val selectedOffer = currentSelectedOffer()
    val dock = LocalCatalogPurchaseDock.current
    val dockOwner = remember(state, mode) { Any() }
    val continuePurchase: () -> Unit = {
        val selected = currentSelectedOffer()
        if (visitReady && selected != null && !state.busy && state.canOperate() && !state.pendingPurchase) state.preview(selected)
    }
    val showPurchase = mode == CommerceMode.Plans && account.authenticated && !account.restoring &&
        !account.foregroundCheckPending && state.catalogLoaded && state.currentSession() && !state.pendingPurchase &&
        state.offers.any { it.category == catalogCategory && (catalogCategory != "plan" || catalogPeriod(it) == period) }
    val selectedSwitch = selectedOffer?.let { switchCards[it.offerRef] }
    val purchaseSummary = if (showPurchase) CatalogPurchaseSummary(selectedOffer?.title?.zh,
        selectedSwitch?.dockPrice ?: selectedOffer?.baseCoinUnits?.let(::formatCoins),
        visitReady && selectedOffer != null && !state.busy && state.canOperate(), state.busy,
        selectedSwitch?.dockAction ?: "立即购买") else null
    if (dock != null && mode == CommerceMode.Plans) {
        SideEffect { dock.show(dockOwner, purchaseSummary, continuePurchase) }
        DisposableEffect(dock, dockOwner) { onDispose { dock.clear(dockOwner) } }
    }
    LaunchedEffect(state, account.authSessionGeneration, mode, defaultSelectionRequest, accountReady, state.busy, state.pendingPurchase) {
        if (mode == CommerceMode.Plans && handledVisit != defaultSelectionRequest && state.canOperate() &&
            !state.busy && !state.pendingPurchase) {
            catalogCategory = "plan"; requestedPeriod = null; selectedSnapshot = null
            selectionNotice = null; initialSelectionHandled = false; state.quote = null
            account.purchaseReturnRef = null
            handledVisit = defaultSelectionRequest
        }
    }
    LaunchedEffect(state, account.authSessionGeneration, mode, catalogCategory, state.catalogLoaded, state.offers, accountReady, state.pendingPurchase) {
        if (mode == CommerceMode.Plans && selectedSnapshot != null && currentSelectedOffer() == null) {
            selectedSnapshot = null
            state.quote = null
        }
    }
    LaunchedEffect(state, state.quote, selectedOffer, mode) {
        if (mode == CommerceMode.Plans && state.quote != null && state.quote?.offer != currentSelectedOffer()) {
            state.quote = null
            selectedSnapshot = null
            selectionNotice = "商品已更新，请重新选择"
        }
    }
    LaunchedEffect(state, account.authSessionGeneration, mode, catalogCategory, state.catalogLoaded,
        state.offers, state.commerceModel, accountReady, state.busy, state.pendingPurchase, state.quote, visitReady) {
        if (mode == CommerceMode.Plans && visitReady && !initialSelectionHandled && state.catalogLoaded &&
            state.canOperate() && !state.busy && !state.pendingPurchase && state.commerceModel != null) {
            initialSelectionHandled = true
            if (catalogCategory == "plan" && period == CatalogPeriod.Monthly && selectedSnapshot == null && state.quote == null) {
                initialMonthlyCatalogOffer(state.offers, state.commerceModel)?.let {
                    selectedSnapshot = CatalogSelection(it, account.authSessionGeneration)
                }
            }
        }
    }
    LaunchedEffect(state, account.authSessionGeneration, accountReady, autoLoad, mode, refreshRequest) {
        // Login exposes the session before resource loading finishes. Account work can also
        // interrupt an existing read: revoke it, then reload once the same session is eligible.
        if (!accountReady) { handledRefresh = null; state.pauseForAuthentication() }
        if (autoLoad && accountReady) {
            // Commerce busy is not an effect key: completing/failing a read must not retry it.
            snapshotFlow { !state.busy && state.canOperate() }.first { it }
            if (state.canOperate()) {
                if (mode == CommerceMode.Plans) state.loadCatalog()
                else if (mode == CommerceMode.Mine) state.loadBalance()
                else if (walletMode) state.loadWallet()
                else if (mode == CommerceMode.Ledger) state.loadLedger()
                else state.load()
                snapshotFlow { !state.busy }.first { it }
                if (state.canOperate()) handledRefresh = refreshRequest
            }
        }
    }
    LaunchedEffect(Unit) { while(true) { now = Instant.now(); kotlinx.coroutines.delay(1000) } }
    // A browser return is merely a reason to reread. It never confirms, creates or pays an order.
    val lifecycle = (LocalActivity.current as? ComponentActivity)?.lifecycle
    DisposableEffect(lifecycle, state, autoLoad, mode) {
        // Registering on an already resumed Activity replays ON_RESUME. The initial
        // LaunchedEffect owns that read; only an actual background return adds another.
        var leftForeground = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) leftForeground = true
            if (event == Lifecycle.Event.ON_RESUME) {
                val returned = leftForeground
                leftForeground = false
                if (autoLoad && returned && state.currentSession() && !account.restoring && !account.foregroundCheckPending) {
                    cancelConfirmation = false
                    if (mode == CommerceMode.Plans) state.loadCatalog()
                    else if (mode == CommerceMode.Mine) state.loadBalance() else if (walletMode) state.loadWallet()
                    else if (mode == CommerceMode.Ledger) state.loadLedger() else state.load()
                }
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    LaunchedEffect(state, lifecycle, autoLoad, walletMode) {
        if (autoLoad && walletMode) lifecycle?.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                kotlinx.coroutines.delay(10_000)
                if (account.authenticated && !account.restoring && !account.foregroundCheckPending) state.pollRecharge()
            }
        }
    }
    if (!account.authenticated || account.restoring || account.foregroundCheckPending) {
        if (account.restoring || account.foregroundCheckPending) return
        val message: @Composable ColumnScope.() -> Unit = {
            Text(if(mode == CommerceMode.Wallet) "登录后查看钱包" else if(mode == CommerceMode.Orders) "登录后查看订单" else "登录后查看套餐")
        }
        if (embeddedWallet) Column(Modifier.testTag("commerce-signed-out"), content = message)
        else BrandCard(Modifier.testTag("commerce-signed-out"), content = message)
        return
    }
    if (mode == CommerceMode.Plans) {
        // Preserve transaction feedback; ordinary initial-read failures only announce once.
        if (state.pendingPurchase || state.catalogLoaded) {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("commerce-error")) }
        } else CommerceReadNotice(state, state.error?.takeUnless { state.busy }, "commerce-error")
        selectionNotice?.let { SupportingText(it, Modifier.testTag("plan-selection-notice")) }
    } else if (mode != CommerceMode.Orders && mode != CommerceMode.Ledger && !walletMode) BrandCard(Modifier.testTag("commerce-status")) {
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("commerce-error")) }
    }
    if (walletMode) {
        WalletPanel(account, state, mode, now, paymentOpener ?: { url -> com.gccwebos.android.payment.PaymentPage.open(context, url) }, onRecharge, onLedger, embeddedWallet,
            onBack = onWalletBack, onRechargeRecords = onRechargeRecords)
        return // Wallet never inherits a retained purchase confirmation from another destination.
    } else if (mode == CommerceMode.Plans) {
        val planTerms = CatalogPeriod.entries.filter { it != CatalogPeriod.Other || displayOffers.any { offer -> offer.category == "plan" && catalogPeriod(offer) == it } }
        val planTabs = planTerms.map { SlidingTab(it.title, it.tag) } + SlidingTab("流量包", "catalog-data-packs")
        val planTabIndex = if (catalogCategory == "data_pack") planTerms.size
            else if (catalogCategory == "plan") planTerms.indexOf(period) else -1
        // One sliding capsule highlight; the click handlers are the former chip handlers, unchanged.
        // Opaque blue-purple capsule (the nodes switcher's gradient); 年卡 and 流量包 breathe while unselected.
        val glowTabs = setOfNotNull(planTerms.indexOf(CatalogPeriod.Yearly).takeIf { it >= 0 }, planTerms.size)
        SlidingTabRow(planTabs, planTabIndex, enabled = !state.busy, containerBrush = SlidingTabGradient,
            selectedColor = SlidingTabGradientSelected, selectedEdge = SlidingTabGradientSelectedEdge, glowTabs = glowTabs, onSelect = { index ->
            initialSelectionHandled = true; selectedSnapshot = null; selectionNotice = null; state.quote = null
            if (index < planTerms.size) { catalogCategory = "plan"; requestedPeriod = planTerms[index] }
            else catalogCategory = "data_pack"
        })
        // The first catalog read (nothing to show yet) is a distinct loading state: skeleton cards, then a fade-in.
        val planLoading = !state.catalogLoaded && state.busy && !state.pendingPurchase && displayOffers.isEmpty()
        val planContent = if (planLoading) null else CatalogTabKey(catalogCategory, period.takeIf { catalogCategory == "plan" })
        TabContent(planContent, indexOf = { key ->
            when {
                key == null -> -1
                key.category == "data_pack" -> planTerms.size
                else -> planTerms.indexOf(key.period)
            }
        }) { key ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (key == null) SkeletonCards(count = 3, cardHeight = 94.dp, modifier = Modifier.testTag("plan-skeleton"))
                else {
                    val shownOffers = displayOffers.filter {
                        it.category == key.category && (key.category != "plan" || catalogPeriod(it) == key.period)
                    }
                    if (shownOffers.isEmpty() && state.catalogLoaded && !state.busy) BrandCard {
                        Text(if (key.category == "data_pack") "暂无可购流量包" else "暂无可购套餐",
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("catalog-empty"))
                    }
                    if (!state.pendingPurchase && key.category == "plan") planSwitchBanner(state.planSwitch, displayOffers, now)?.let { banner ->
                        PlanSwitchBannerCard(banner)
                    }
                    if (!state.pendingPurchase && shownOffers.isNotEmpty()) {
                        CatalogOfferList(shownOffers, selectedOffer, category = key.category, switchCards = switchCards,
                            enabled = visitReady && state.catalogLoaded && !state.busy && state.canOperate(),
                            onSelect = { offer -> if (!state.busy && state.canOperate() && state.catalogLoaded && state.offers.any { it == offer }) {
                                initialSelectionHandled = true; state.quote = null; selectionNotice = null
                                selectedSnapshot = CatalogSelection(offer, account.authSessionGeneration)
                            } })
                        if (dock == null && purchaseSummary != null) CatalogPurchaseDock(purchaseSummary, continuePurchase)
                    }
                }
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
    if (mode == CommerceMode.Orders) {
        OrderHistoryPanel(account, state, paymentOpener = paymentOpener ?: { url -> com.gccwebos.android.payment.PaymentPage.open(context, url) }, now = now)
        return // History has only read/local-return actions, even if a shared fixture retains a quote.
    }
    if (mode == CommerceMode.Ledger) {
        LedgerPanel(account, state, paymentOpener = paymentOpener ?: { url -> com.gccwebos.android.payment.PaymentPage.open(context, url) }, now = now)
        return // Read-only history must not inherit a retained purchase confirmation.
    }
    state.quote?.takeIf { visitReady }?.let { quote ->
        val switchIssue = planSwitchQuoteIssue(switchCards[quote.offer.offerRef], quote)
        val valid = quote.purchasableAt(now) && state.canOperate() && !state.pendingPurchase &&
            selectedOffer == quote.offer && state.catalogLoaded && state.offers.any { it == quote.offer } && switchIssue == null
        val confirm = {
            if (!state.busy && state.canOperate() && !state.pendingPurchase && state.quote === quote &&
                quote.purchasableAt(Instant.now()) && selectedSnapshot?.accountGeneration == account.authSessionGeneration &&
                selectedSnapshot?.offer == quote.offer && state.catalogLoaded &&
                state.offers.any { it == quote.offer } && planSwitchQuoteIssue(switchCards[quote.offer.offerRef], quote) == null) state.confirmPurchase()
        }
        val change = planSwitchDialog(quote)
        if (change != null) GccDialog(onDismissRequest = { if (!state.busy) state.quote = null }, title = change.title,
            testTag = "plan-switch-dialog", secure = false, dismissOnOutsideTap = false,
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    change.lines.forEachIndexed { index, line ->
                        Text(line, color = Brand.Text, modifier = Modifier.testTag("plan-switch-line-$index"))
                    }
                    quote.allocation.forEach { Text("${ledgerCoinType(it.coinType)}：${formatCoins(it.amountUnits)}", color = Brand.Muted) }
                    Text("报价有效至：${localTime(quote.expiresAt)}", color = Brand.Muted)
                    quote.offer.importantNotices.zh.forEach { Text(it, color = Brand.Muted) }
                    if (!valid) Text(when {
                        switchIssue != null -> switchIssue
                        quote.expiresAt <= now -> "报价已过期，请关闭后重新获取"
                        quote.purchaseAvailability != "available" -> "余额不足，尚缺 ${formatCoins(quote.shortfallUnits)}。请前往我的充值，再重新获取报价。"
                        else -> "商品或账户已变化，请重新获取报价"
                    }, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("plan-switch-invalid"))
                }
            },
            primary = GccDialogAction(change.confirm, confirm, enabled = valid && !state.busy, testTag = "purchase-confirm"),
            secondary = GccDialogAction("取消", { state.quote = null }, enabled = !state.busy, testTag = "purchase-cancel"))
        else AlertDialog(onDismissRequest = { if(!state.busy) state.quote = null }, containerColor = Brand.SurfaceRaised, shape = Brand.Shape,
            title = { Text(if (quote.offer.category == "data_pack") "确认购买${quote.offer.trafficGb} GB流量包" else "确认购买${quote.offer.durationDays}天套餐") },
            text = { Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("服务：飞连vpn")
                Text("本次扣除：${formatCoins(quote.amountUnits)}")
                quote.allocation.forEach { Text("${ledgerCoinType(it.coinType)}：${formatCoins(it.amountUnits)}") }
                Text("确认后预留：${formatCoins(quote.balances.after.heldCoins)}")
                Text(if (quote.offer.category == "data_pack") "流量：${quote.offer.trafficGb} GB；永久有效，用完为止" else "每月流量：${quote.offer.monthlyTrafficGb} GB")
                Text("报价有效至：${localTime(quote.expiresAt)}")
                quote.transition?.let { Text("服务器安排生效时间：${localTime(it.scheduledFor)}") }
                quote.offer.importantNotices.zh.forEach { Text(it) }
                if(!valid) Text(when {
                    switchIssue != null -> switchIssue
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
    if(cancelConfirmation) AlertDialog(onDismissRequest = { cancelConfirmation = false }, containerColor = Brand.SurfaceRaised, shape = Brand.Shape,
        modifier = Modifier.testTag("purchase-order-cancel-dialog"), title = { Text("请求取消此套餐订单？") },
        text = { Text("能否取消由服务器根据当前状态和取消期限决定，请以返回结果为准。") },
        confirmButton = { TextButton(onClick = { cancelConfirmation = false; state.cancelPurchase() }) { Text("确认取消订单") } },
        dismissButton = { TextButton(onClick = { cancelConfirmation = false }) { Text("返回") } })
}
internal const val RECHARGE_EXPIRED_STATUS = "已失效（付款入口已过期）"
internal fun rechargeStatus(status: String) = when(status) {
    "created" -> "充值订单已创建，支付入口准备中"; "entry_ready", "pending" -> "等待付款或支付确认"
    "payment_verified" -> "支付已核验，Coins 尚待到账"; "credited" -> "Coins 已到账，购买套餐需另行确认"
    "failed_no_charge" -> "订单失败，服务器确认未扣款"; "expired" -> "订单已过期"
    else -> "需要对账，请勿重复付款"
}
internal fun purchaseStatus(value: PurchaseResult): String = when(value) {
    is PurchaseResult.Order -> planSwitchOrderStatus(value) ?: run {
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

private fun ledgerCoinType(value: String) = catalogCoinTypeLabel(value)


/** Original mobile Alipay vector, placed on a light plate within the blue payment action. */
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
    Button(onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = shape, colors = ButtonDefaults.buttonColors(containerColor = Brand.PaymentBlue,
            contentColor = androidx.compose.ui.graphics.Color.White, disabledContainerColor = Brand.PaymentDisabled,
            disabledContentColor = Brand.PaymentDisabledText)) {
        AlipayMark(28.dp); Spacer(Modifier.width(10.dp)); Text(text)
    }
}
@Composable
private fun HistoryState(size: Int?, busy: Boolean, label: String = "订单") {
    if (!busy && size == 0) BrandCard {
        Text(if (label == "订单") "暂无这类订单" else "暂无${label}记录", style = MaterialTheme.typography.titleMedium)
    }
}



/** Native functional counterpart of mobile src/mine.jsx; account data is never hard-coded. */
@Composable
internal fun MinePanel(account: AccountController, autoLoad: Boolean = true, stateOverride: CommerceController? = null,
    onReturnToPlans: (() -> Unit)? = null, telegramOpener: ((String) -> Unit)? = null,
    referralOverride: ReferralController? = null, copyOverride: ((String) -> Unit)? = null,
    recoveryOverride: CommerceRecovery? = null, paymentOpener: ((String) -> Unit)? = null, refreshRequest: Int = 0,
    onLogin: (() -> Unit)? = null) {
    if (onLogin != null && !account.authenticated && !account.retainingCommerceSession) {
        // Signed out: no commerce controller exists, so no wallet, ledger or order read can start.
        MineSignedOutPanel(account, onLogin, autoLoad, telegramOpener, refreshRequest)
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionOwner = account.commerceSessionIdentity ?: account.authSessionGeneration
    val state = stateOverride ?: remember(sessionOwner, recoveryOverride) { CommerceController(account, scope,
        recoveryOverride ?: FileCommerceRecovery(context.applicationContext), includeHistory = true) }
    var view by remember(sessionOwner) { mutableStateOf("mine") }
    var confirmLogout by remember(sessionOwner) { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = view != "mine") {
        if (shouldCancelLoginOnBack(account)) account.cancelAuthentication() else view = "mine"
    }
    // Wallet top-up and order/ledger history draw a standalone top bar (same "mine-back" tag).
    val historyTopBar = view == "history" && account.authenticated && !account.restoring && !account.foregroundCheckPending
    val walletTopBar = (view == "wallet" || view == "recharge") && account.authenticated && !account.restoring && !account.foregroundCheckPending
    if (view != "mine" && !walletTopBar && !historyTopBar) TextButton(onClick = { view = "mine" }, modifier = Modifier.heightIn(min = 48.dp).testTag("mine-back")) { Text("‹ 返回我的") }
    if (historyTopBar) WalletTopBar("订单明细", onBack = { view = "mine" }, onRecords = null)
    if (view == "about") {
        SectionCard(Modifier.testTag("mine-about")) {
            Text("关于与更新", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            com.gccwebos.android.update.UpdatePanel(autoCheck = autoLoad, refreshRequest = refreshRequest)
        }
    } else if (view == "security") {
        // Signed in: only 通行密钥 and 绑定身份验证器 (no account-resource detail). Otherwise the login form.
        if (!account.authenticated) com.gccwebos.android.portal.AccountPanel(account, compactLogin = true)
        else {
            MineGroupLabel("账户与安全", "security-title")
            com.gccwebos.android.portal.SecurityStatusPanel(account, autoLoad, refreshRequest)
        }
    } else if (view == "referral") {
        ReferralPanel(account, autoLoad, referralOverride, copyOverride, refreshRequest)
    } else if (view != "mine") {
        val mode = when(view) {
            "history" -> CommerceMode.Ledger // one flat 流水 list; orders and ledger are no longer split
            "ledger" -> CommerceMode.Ledger
            "wallet", "recharge" -> CommerceMode.Wallet
            else -> CommerceMode.Mine
        }
        CommercePanel(account, mode, state, autoLoad, paymentOpener = paymentOpener, refreshRequest = refreshRequest,
            onWalletBack = { view = "mine" }, onRechargeRecords = { view = "history" },
            onRecharge = { view = if (account.authenticated) "recharge" else "security" },
            onLedger = { if (account.authenticated) view = "history" else view = "security" })
        if (!account.authenticated && !account.retainingCommerceSession) BrandButton("登录账户管理钱包", { view = "security" }, modifier = Modifier.testTag("mine-login"))
    }
    if (confirmLogout) com.gccwebos.android.portal.LogoutDialog(
        onConfirm = { confirmLogout = false; account.logout() }, onDismiss = { confirmLogout = false })
    if (view == "mine") Column(Modifier.fillMaxWidth().testTag("mine-surface"), verticalArrangement = Arrangement.spacedBy(GccSpacing.Medium)) {
        val idle = !account.busy && !account.restoring && !account.foregroundCheckPending
        MineWelcomeCard(account, onRenew = onReturnToPlans?.takeIf { account.authenticated }, copyOverride = copyOverride)
        CommercePanel(account, CommerceMode.Mine, state, autoLoad, paymentOpener = paymentOpener, embeddedWallet = true, refreshRequest = refreshRequest,
            onRecharge = { view = if (account.authenticated) "recharge" else "security" },
            onLedger = { if (account.authenticated) view = "history" else view = "security" })
        if (!account.authenticated && !account.retainingCommerceSession) BrandButton("登录账户管理钱包", { view = "security" }, modifier = Modifier.testTag("mine-login"))
        // ① Invite banner. Neutral copy: any reward rate belongs to the server policy on the referral screen.
        MineInviteBanner("mine-referral", !account.busy) {
            view = if (account.authenticated) "referral" else "security"
        }
        // ② 服务与记录. The wallet has no separate row: the balance card's 钱包充值 / 订单明细 cover it.
        MineGroupLabel("服务与记录", "mine-group-service")
        SectionCard(Modifier.testTag("mine-menu"), contentPadding = PaddingValues(horizontal = GccSpacing.Medium, vertical = GccSpacing.ExtraSmall), verticalSpacing = 0.dp) {
            MineMenuRow("订单明细", "mine-history", !state.busy && idle, subtitle = "充值、购买等余额变动流水") {
                view = if (account.authenticated) "history" else "security"
            }
            MineRowDivider()
            MineOfficialChannelRow(MINE_OFFICIAL_CHANNEL_URL, telegramOpener) { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
        // ③ 系统与设置.
        MineGroupLabel("系统与设置", "mine-group-system")
        SectionCard(Modifier.testTag("mine-functions"), contentPadding = PaddingValues(horizontal = GccSpacing.Medium, vertical = GccSpacing.ExtraSmall), verticalSpacing = 0.dp) {
            MineMenuRow("账户与安全", "mine-security", !account.busy) { view = "security" }
            MineRowDivider()
            MineMenuRow("关于与更新", "mine-more", subtitle = mineVersionLabel(com.gccwebos.android.BuildConfig.VERSION_NAME)) { view = "about" }
            if (account.authenticated) {
                MineRowDivider()
                MineMenuRow(com.gccwebos.android.portal.LOGOUT_LABEL, "mine-signout", !account.busy) { confirmLogout = true }
            }
        }
    }
}

/** Same layout as the signed-in Mine page; account data is replaced by login prompts and wallet actions are inert. */
@Composable
private fun MineSignedOutPanel(account: AccountController, onLogin: () -> Unit, autoLoad: Boolean,
    telegramOpener: ((String) -> Unit)?, refreshRequest: Int) {
    val context = LocalContext.current
    var about by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = about) { about = false }
    if (about) {
        TextButton(onClick = { about = false }, modifier = Modifier.heightIn(min = 48.dp).testTag("mine-back")) { Text("‹ 返回我的") }
        SectionCard(Modifier.testTag("mine-about")) {
            Text("关于与更新", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            com.gccwebos.android.update.UpdatePanel(autoCheck = autoLoad, refreshRequest = refreshRequest)
        }
        return
    }
    val idle = !account.busy && !account.restoring && !account.foregroundCheckPending
    Column(Modifier.fillMaxWidth().testTag("mine-surface"), verticalArrangement = Arrangement.spacedBy(GccSpacing.Medium)) {
        Column(Modifier.fillMaxWidth().testTag("mine-signed-out"), verticalArrangement = Arrangement.spacedBy(GccSpacing.Medium)) {
        MineWelcomeCard(account, onLogin = onLogin)
        MineWalletCard(Modifier.testTag("wallet-card")) {
            MineBalanceHeading()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GccSpacing.Medium)) {
                CoinIcon(30.dp, enabled = false)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GccSpacing.ExtraSmall)) {
                    Text("登录后查看余额", style = MaterialTheme.typography.titleMedium, color = Brand.Muted,
                        modifier = Modifier.testTag("wallet-balance-signed-out"))
                    Text("充值与订单明细登录后使用", style = MaterialTheme.typography.bodySmall, color = Brand.Muted,
                        modifier = Modifier.testTag("wallet-signed-out-hint"))
                }
            }
            // No recharge / ledger buttons while signed out: 去登录 above is the single action.
        }
        // The invite banner and its 立即邀请 action appear after login (referral needs an account).
        MineGroupLabel("服务与记录", "mine-group-service")
        SectionCard(Modifier.testTag("mine-menu"), contentPadding = PaddingValues(horizontal = GccSpacing.Medium, vertical = GccSpacing.ExtraSmall), verticalSpacing = 0.dp) {
            MineMenuRow("订单明细", "mine-history", idle, subtitle = "登录后查看余额变动流水", onClick = onLogin)
            MineRowDivider()
            MineOfficialChannelRow(MINE_OFFICIAL_CHANNEL_URL, telegramOpener) { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
        MineGroupLabel("系统与设置", "mine-group-system")
        SectionCard(Modifier.testTag("mine-functions"), contentPadding = PaddingValues(horizontal = GccSpacing.Medium, vertical = GccSpacing.ExtraSmall), verticalSpacing = 0.dp) {
            MineMenuRow("账户与安全", "mine-security", idle, subtitle = "登录后管理账户与安全", onClick = onLogin)
            MineRowDivider()
            MineMenuRow("关于与更新", "mine-more", subtitle = mineVersionLabel(com.gccwebos.android.BuildConfig.VERSION_NAME)) { about = true }
        }
        }
    }
}

/** The active card and, while one is scheduled, the 待生效 downgrade; both come from the server state, never from local rules. */
@Composable
internal fun PlanSwitchBannerCard(banner: PlanSwitchBanner) {
    BrandCard(Modifier.testTag("plan-switch-banner")) {
        banner.current?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Brand.Text, modifier = Modifier.testTag("plan-switch-current")) }
        banner.pending?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Brand.Cyan, modifier = Modifier.testTag("plan-switch-pending"))
        }
    }
}
