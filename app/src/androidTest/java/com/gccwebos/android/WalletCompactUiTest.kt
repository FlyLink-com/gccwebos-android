package com.gccwebos.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gccwebos.android.commerce.*
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.portal.*
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** Offline DTO fixtures and local callbacks only. No account, recharge, or payment request may escape. */
class WalletCompactUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var state: CommerceController
    private lateinit var owner: AccountController
    private var densityScale = 1f
    private var rechargeNavigations = 0
    private var ledgerNavigations = 0
    private val calls = mutableListOf<String>()
    private val opened = mutableListOf<String>()
    // Top-up screen: only the spendable balance is on the card; all six figures live in 余额明细.
    private val tags = listOf("wallet-detail-total", "wallet-paid", "wallet-bonus", "wallet-reward", "wallet-held", "wallet-pending")
    private val confirmed = Wallet("active", 1, 17, 9, 5, 31, 7, 11)
    private val offer = RechargeOffer("compact_fixture", 9, 73, 2345, "CNY", 2, listOf("alipay"))

    private fun account(scope: CoroutineScope) = AccountController(PortalRepository(
        object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse =
                throw AssertionError("No Portal requests in compact wallet fixtures")
        },
        object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = throw AssertionError("No delivery writes")
            override fun clear() = Unit
        },
        OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L },
        subscription = SubscriptionSource { throw AssertionError("No subscription requests") },
        sessions = MemorySessionStore(), profiles = MemoryLocalProfileStore()), scope).apply { authenticated = true }

    private fun launch(mode: CommerceMode = CommerceMode.Wallet, width: Int = 360, fontScale: Float = 1f,
        setup: CommerceController.() -> Unit = {}) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val account = remember { account(scope) }
            owner = account
            val controller = remember {
                val recovery = object : CommerceRecovery {
                    override fun read(binding: String, kind: String): PendingCommerce? = null
                    override fun save(binding: String, value: PendingCommerce) = throw AssertionError("No order creation")
                    override fun clear(binding: String, kind: String) = throw AssertionError("No recovery clears")
                }
                val repo = CommerceRepository({ path, _, _ -> calls += path; throw AssertionError("No commerce transport") },
                    recovery, "compact_ui_account")
                CommerceController(account, scope, recovery, initialRepository = repo).apply(setup)
            }
            state = controller
            val density = LocalDensity.current
            densityScale = density.density
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GccTheme {
                    Column(Modifier.width(width.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        WalletPanel(account, controller, mode, Instant.parse("2026-10-01T00:00:00Z"),
                            paymentOpener = { opened += it },
                            onRecharge = { rechargeNavigations++ }, onLedger = { ledgerNavigations++ })
                    }
                }
            }
        }
    }

    private fun openDetails() { compose.onNodeWithTag("wallet-balance-details").performScrollTo().performClick() }
    private fun closeDetails() { compose.onNodeWithTag("wallet-balance-details-close").performClick() }
    private fun assertAllFields(text: (Int) -> String) {
        compose.onNodeWithTag("wallet-balance").assertTextEquals(text(31))
        openDetails()
        tags.zip(listOf(31, 17, 9, 5, 7, 11)).forEach { (tag, amount) ->
            compose.onNodeWithTag(tag).assertTextEquals(text(amount))
        }
        closeDetails()
        compose.onNodeWithTag("wallet-balance-details-dialog").assertDoesNotExist()
    }
    private fun assertConfirmedFields() = assertAllFields { "$it Coins" }
    private fun assertUnknownFields() = assertAllFields { "— Coins" }
    private fun assertNoSideEffects() = compose.runOnIdle {
        assertTrue(calls.isEmpty()); assertTrue(opened.isEmpty()); assertNull(state.recharge); assertFalse(state.pendingRecharge)
    }

    @Test fun cardShowsSpendableAndCompositionWhileDetailKeepsSixFieldsWithoutAddingHeldOrPending() {
        launch { wallet = confirmed; walletLoaded = true }
        assertConfirmedFields()
        compose.onNodeWithTag("wallet-composition").assertTextEquals("含 已购 17 · 赠送 9 · 奖励 5")
        listOf("wallet-held", "wallet-pending", "wallet-paid").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        openDetails()
        listOf("总可用", "已购可用", "赠送可用", "奖励可用", "订单预留", "待确认奖励").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithText("49 Coins").assertDoesNotExist()
        closeDetails()
        compose.onNodeWithTag("wallet-status").assertDoesNotExist()
        assertNoSideEffects()
    }

    @Test fun unknownFailureAndBusyNeverBecomeConfirmedZero() {
        launch()
        assertUnknownFields()
        compose.runOnIdle { state.walletError = "合成读取失败"; state.busy = true }
        compose.onNodeWithTag("wallet-error").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("合成读取失败", state.walletError)
            assertNull(state.wallet); assertFalse(state.walletLoaded); assertTrue(state.busy)
        }
        compose.onNodeWithTag("wallet-loading").assertDoesNotExist()
        compose.onNodeWithTag("recharge-selected-pay").assertIsNotEnabled()
        compose.onNodeWithTag("commerce-refresh").assertDoesNotExist()
        assertUnknownFields()
        compose.onNodeWithText("0 Coins").assertDoesNotExist()
        compose.runOnIdle {
            state.busy = false; state.walletError = null; state.walletLoaded = true
            state.wallet = Wallet("not_created", 0, 0, 0, 0, 0, 0, 0)
        }
        tags.forEach { compose.onNodeWithTag(it).assertTextEquals("0 Coins") }
        compose.onNodeWithTag("wallet-status").assertTextEquals("钱包尚未创建")
        assertNoSideEffects()
    }

    @Test fun frozenAndClosedKeepServerAmountsAndShortStatus() {
        launch { wallet = confirmed.copy(status = "frozen"); walletLoaded = true }
        assertConfirmedFields()
        compose.onNodeWithTag("wallet-status").assertTextEquals("钱包已冻结")
        compose.runOnIdle { state.wallet = confirmed.copy(status = "closed") }
        assertConfirmedFields()
        compose.onNodeWithTag("wallet-status").assertTextEquals("钱包已关闭")
        assertNoSideEffects()
    }

    @Test fun backgroundReadRetainsSameOwnerDisplayButCannotAuthorizeRecharge() {
        launch { wallet = confirmed; walletLoaded = true; rechargeOffers = listOf(offer); rechargeOffersLoaded = true }
        assertConfirmedFields()
        compose.onNodeWithTag("recharge-select-${offer.offerRef}").performScrollTo().performClick()
        compose.runOnIdle {
            state.wallet = null; state.walletLoaded = false
            state.rechargeOffers = emptyList(); state.rechargeOffersLoaded = false; state.busy = true
        }
        assertConfirmedFields()
        compose.onNodeWithTag("recharge-select-${offer.offerRef}").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("recharge-selected-pay").assertIsNotEnabled()
        compose.onNodeWithTag("wallet-loading").assertDoesNotExist()
        compose.runOnIdle { state.busy = false; state.walletError = "合成刷新失败" }
        assertConfirmedFields()
        compose.onNodeWithTag("wallet-error").assertTextEquals("合成刷新失败")
        compose.runOnIdle { owner.authSessionGeneration++ }
        assertUnknownFields()
        compose.onNodeWithTag("recharge-select-${offer.offerRef}").assertDoesNotExist()
        assertNoSideEffects()
    }

    @Test fun mineShowsOnlyServerSpendableBalanceAndKeepsLargeRechargeAndLedgerTargets() {
        launch(CommerceMode.Mine, width = 320, fontScale = 2f) { wallet = confirmed; walletLoaded = true }
        compose.onNodeWithTag("wallet-balance").assertTextEquals("31 Coins")
        tags.drop(1).forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.onNodeWithText("49 Coins").assertDoesNotExist()
        listOf("mine-recharge", "mine-ledger").forEach { tag ->
            val node = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            assertTrue(node.fetchSemanticsNode().boundsInRoot.height / densityScale >= 48f)
            node.performClick()
        }
        compose.runOnIdle { assertEquals(1, rechargeNavigations); assertEquals(1, ledgerNavigations) }
        compose.onNodeWithTag("recharge-selected-pay").assertDoesNotExist()
        compose.runOnIdle { state.busy = true }
        compose.onNodeWithTag("mine-recharge").assertIsNotEnabled()
        compose.onNodeWithTag("mine-ledger").assertIsNotEnabled()
        assertNoSideEffects()
    }

    @Test fun narrowDoubledFontReflowsAndKeepsAmountConfirmationAndCancelReachable() {
        launch(width = 320, fontScale = 2f) {
            wallet = confirmed; walletLoaded = true; rechargeOffers = listOf(offer); rechargeOffersLoaded = true
        }
        compose.onNodeWithTag("wallet-balance").performScrollTo().assertIsDisplayed().assertTextEquals("31 Coins")
        openDetails()
        tags.zip(listOf(31, 17, 9, 5, 7, 11)).forEach { (tag, amount) ->
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertTextEquals("$amount Coins")
        }
        compose.onNodeWithTag(tags[0]).performScrollTo()
        val first = compose.onNodeWithTag(tags[0]).fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag(tags[1]).fetchSemanticsNode().boundsInRoot
        assertTrue(second.top > first.bottom)
        assertEquals(first.left, second.left, 1f)
        closeDetails()
        compose.onNodeWithTag("recharge-select-${offer.offerRef}").performScrollTo().performClick()
        compose.onNodeWithTag("recharge-confirm").assertDoesNotExist()
        // The checkout bar itself is the confirmation: amount and 立即充值 are on screen, and there is no dialog step.
        compose.onNodeWithTag("recharge-selected-pay").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("recharge-payable-amount").assertTextEquals("应付金额：￥23.45")
        compose.onNodeWithText("确认充值金额").assertDoesNotExist()
        compose.onNodeWithTag("commerce-refresh").assertDoesNotExist()
        assertNoSideEffects()
    }

    @Test fun walletReadsStaySilentAndNeverExposeAStandaloneRetry() {
        launch {
            wallet = confirmed; walletLoaded = true
            rechargeOffers = listOf(offer); rechargeOffersLoaded = true
        }
        compose.onNodeWithTag("commerce-refresh").assertDoesNotExist()
        compose.runOnIdle { state.walletError = "合成读取失败" }
        compose.onNodeWithTag("commerce-refresh").assertDoesNotExist()
        compose.onNodeWithTag("wallet-error").assertTextEquals("合成读取失败")
        compose.runOnIdle { state.busy = true }
        compose.onNodeWithTag("commerce-refresh").assertDoesNotExist()
        compose.runOnIdle { state.busy = false; state.walletError = null; state.walletLoaded = false; state.wallet = null }
        compose.onNodeWithTag("commerce-refresh").assertDoesNotExist()
        compose.onNodeWithTag("wallet-balance").assertTextEquals("— Coins")
        compose.runOnIdle { owner.foregroundCheckPending = true }
        compose.onNodeWithTag("commerce-refresh").assertDoesNotExist()
        assertNoSideEffects()
    }

    @Test fun firstDirectoryHasDefaultTileButChangedDirectoryOrAccountDiscardsLocalChoice() {
        launch { rechargeOffers = listOf(offer); rechargeOffersLoaded = true }
        // Default highlight for the first directory of this visit; selecting still creates nothing.
        compose.onNodeWithTag("recharge-select-${offer.offerRef}").assertIsSelected()
        compose.onNodeWithTag("recharge-selected-pay").assertIsEnabled()
        compose.onNodeWithTag("recharge-payable-amount").assertTextEquals("应付金额：￥23.45")
        compose.onNodeWithTag("recharge-select-${offer.offerRef}").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("recharge-confirm").assertDoesNotExist()
        compose.onNodeWithTag("recharge-selected-pay").assertIsEnabled()
        assertNoSideEffects()
        val changed = offer.copy(quoteVersion = 10, amountMinor = 2567)
        compose.runOnIdle { state.rechargeOffers = listOf(changed) }
        compose.onNodeWithTag("recharge-select-${offer.offerRef}").assertIsNotSelected()
        compose.onNodeWithTag("recharge-selected-pay").assertIsNotEnabled()
        compose.onNodeWithTag("recharge-select-${offer.offerRef}").performScrollTo().performClick()
        compose.runOnIdle { state.busy = true }
        compose.onNodeWithTag("recharge-selected-pay").assertIsNotEnabled()
        compose.onNodeWithTag("recharge-select-${offer.offerRef}").assertIsNotEnabled()
        compose.runOnIdle { state.busy = false }
        compose.onNodeWithTag("recharge-selected-pay").performScrollTo().assertIsEnabled()
        compose.runOnIdle { owner.authSessionGeneration++ }
        compose.runOnIdle { assertFalse(state.currentSession()); assertEquals(listOf(changed), state.rechargeOffers) }
        compose.onNodeWithTag("recharge-select-${offer.offerRef}").assertDoesNotExist()
        compose.onNodeWithTag("recharge-selected-pay").assertIsNotEnabled()
        compose.onNodeWithTag("recharge-confirm").assertDoesNotExist()
        assertNoSideEffects()
    }
}
