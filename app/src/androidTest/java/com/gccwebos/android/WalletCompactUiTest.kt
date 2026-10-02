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
    private val tags = listOf("wallet-balance", "wallet-paid", "wallet-bonus", "wallet-reward", "wallet-held", "wallet-pending")
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

    private fun assertConfirmedFields() {
        tags.zip(listOf(31, 17, 9, 5, 7, 11)).forEach { (tag, amount) ->
            compose.onNodeWithTag(tag).assertTextEquals("$amount Coins")
        }
    }
    private fun assertNoSideEffects() = compose.runOnIdle {
        assertTrue(calls.isEmpty()); assertTrue(opened.isEmpty()); assertNull(state.recharge); assertFalse(state.pendingRecharge)
    }

    @Test fun sixAuthoritativeFieldsUseThreeColumnsAndDoNotAddHeldOrPendingToAvailability() {
        launch { wallet = confirmed; walletLoaded = true }
        assertConfirmedFields()
        listOf("总可用", "已购可用", "赠送可用", "奖励可用", "订单预留", "待确认奖励").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithText("49 Coins").assertDoesNotExist()
        val bounds = tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        assertEquals(bounds[0].top, bounds[1].top, 1f)
        assertEquals(bounds[1].top, bounds[2].top, 1f)
        assertTrue(bounds[3].top > bounds[0].bottom)
        assertEquals(bounds[3].top, bounds[4].top, 1f)
        assertEquals(bounds[4].top, bounds[5].top, 1f)
        compose.onNodeWithTag("wallet-status").assertDoesNotExist()
        assertNoSideEffects()
    }

    @Test fun unknownFailureAndBusyNeverBecomeConfirmedZero() {
        launch()
        tags.forEach { compose.onNodeWithTag(it).assertTextEquals("— Coins") }
        compose.runOnIdle { state.walletError = "合成读取失败"; state.busy = true }
        compose.onNodeWithTag("wallet-error").assertTextEquals("合成读取失败")
        compose.onNodeWithTag("wallet-loading").assertExists()
        compose.onNodeWithTag("recharge-selected-pay").assertIsNotEnabled()
        compose.onNodeWithTag("commerce-refresh").assertDoesNotExist()
        tags.forEach { compose.onNodeWithTag(it).assertTextEquals("— Coins") }
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

    @Test fun mineUsesSameSixFieldsAndKeepsLargeRechargeAndLedgerTargets() {
        launch(CommerceMode.Mine, width = 320, fontScale = 2f) { wallet = confirmed; walletLoaded = true }
        assertConfirmedFields()
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
        tags.zip(listOf(31, 17, 9, 5, 7, 11)).forEach { (tag, amount) ->
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertTextEquals("$amount Coins")
        }
        compose.onNodeWithTag(tags[0]).performScrollTo()
        val first = compose.onNodeWithTag(tags[0]).fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag(tags[1]).fetchSemanticsNode().boundsInRoot
        assertTrue(second.top > first.bottom)
        assertEquals(first.left, second.left, 1f)
        compose.onNodeWithTag("recharge-select-${offer.offerRef}").performScrollTo().performClick()
        compose.onNodeWithTag("recharge-confirm").assertDoesNotExist()
        compose.onNodeWithTag("recharge-selected-pay").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("CNY 23.45 → 73 Coins").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("recharge-cancel").assertIsDisplayed().performClick()
        compose.onNodeWithTag("recharge-confirm").assertDoesNotExist()
        compose.onNodeWithTag("commerce-refresh").assertDoesNotExist()
        assertNoSideEffects()
    }

    @Test fun loadedWalletHidesRoutineRefreshButUnreadAndFailedReadKeepRecovery() {
        launch {
            wallet = confirmed; walletLoaded = true
            rechargeOffers = listOf(offer); rechargeOffersLoaded = true
        }
        compose.onNodeWithTag("commerce-refresh").assertDoesNotExist()
        compose.runOnIdle { state.walletError = "合成读取失败" }
        compose.onNodeWithTag("commerce-refresh").performScrollTo().assertIsDisplayed().assertTextEquals("重试")
        compose.runOnIdle { state.busy = true }
        compose.onNodeWithTag("commerce-refresh").assertDoesNotExist()
        compose.runOnIdle { state.busy = false; state.walletError = null; state.walletLoaded = false; state.wallet = null }
        compose.onNodeWithTag("commerce-refresh").performScrollTo().assertIsDisplayed().assertTextEquals("重试")
        compose.runOnIdle { owner.foregroundCheckPending = true }
        compose.onNodeWithTag("commerce-refresh").assertDoesNotExist()
        assertNoSideEffects()
    }

    @Test fun singleCtaHasNoDefaultAndChangedDirectoryOrAccountDiscardsLocalChoice() {
        launch { rechargeOffers = listOf(offer); rechargeOffersLoaded = true }
        compose.onNodeWithTag("recharge-selected-pay").assertIsNotEnabled()
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
        compose.onNodeWithTag("recharge-selected-pay").performScrollTo().performClick()
        compose.onNodeWithText("CNY 25.67 → 73 Coins").assertExists()
        compose.runOnIdle { owner.authSessionGeneration++ }
        compose.onNodeWithTag("recharge-select-${offer.offerRef}").assertIsNotSelected().assertIsNotEnabled()
        compose.onNodeWithTag("recharge-selected-pay").assertIsNotEnabled()
        compose.onNodeWithTag("recharge-confirm").assertDoesNotExist()
        assertNoSideEffects()
    }
}
