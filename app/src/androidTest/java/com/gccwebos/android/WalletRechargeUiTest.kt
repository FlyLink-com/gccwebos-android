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

/** Synthetic fixture only: local font scale and memory opener, no account/payment requests. */
class WalletRechargeUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var state: CommerceController
    private val opened = mutableListOf<String>()
    private val calls = mutableListOf<String>()
    private val future = Instant.parse("2099-10-01T00:00:00Z")
    private val offer = RechargeOffer("recharge_ui_fixture", 9, 73, 2345, "CNY", 2, listOf("alipay"))
    private val order = RechargeOrder("recharge_ui_order", offer.offerRef, 10, 79, 2567, "CNY", 2,
        "xsd_v1", "alipay", "entry_ready", null, PaymentRedirect("https://www.xsd8866.com/offline-fixture", future),
        null, null, future, Instant.EPOCH, Instant.EPOCH)
    private fun account(scope: CoroutineScope) = AccountController(PortalRepository(
        object : PortalTransport { override suspend fun request(path: String, body: String?, session: Secret?): WireResponse = throw AssertionError("No Portal requests") },
        object : DeliveryStore { override fun read(): DeliveryRecord? = null; override fun save(record: DeliveryRecord) = throw AssertionError("No delivery writes"); override fun clear() = Unit },
        OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L },
        subscription = SubscriptionSource { throw AssertionError("No subscription requests") },
        sessions = MemorySessionStore(), profiles = MemoryLocalProfileStore()), scope).apply { authenticated = true }
    private fun launch(fontScale: Float = 1f, mode: CommerceMode = CommerceMode.Wallet, setup: CommerceController.() -> Unit = {}) {
        compose.setContent {
            val scope = rememberCoroutineScope(); val owner = remember { account(scope) }
            val controller = remember {
                val recovery = object : CommerceRecovery {
                    override fun read(binding: String, kind: String): PendingCommerce? = null
                    override fun save(binding: String, value: PendingCommerce) = throw AssertionError("No order creation in these fixtures")
                    override fun clear(binding: String, kind: String) = throw AssertionError("No recovery clears")
                }
                val repository = CommerceRepository({ path, _, _ -> calls += path; throw AssertionError("No transport expected") }, recovery, "ui_fixture_account")
                CommerceController(owner, scope, recovery, initialRepository = repository).apply(setup)
            }
            state = controller
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GccTheme { Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    CommercePanel(owner, mode, controller, autoLoad = false, paymentOpener = { opened += it })
                } }
            }
        }
    }
    @Test fun unknownErrorAndConfirmedZeroKeepDirectoryStatesSeparate() {
        launch()
        compose.onNodeWithTag("wallet-balance").assertTextEquals("— Coins")
        compose.onNodeWithTag("recharge-offers-unread").assertDoesNotExist()
        compose.onNodeWithTag("recharge-offers-empty").assertDoesNotExist()
        compose.onNodeWithTag("commerce-refresh").assertDoesNotExist()
        compose.onNodeWithTag("recharge-selected-pay").assertIsNotEnabled()
        compose.runOnIdle { state.walletError = "合成余额读取失败"; state.rechargeOffersError = "合成档位读取失败" }
        compose.onNodeWithTag("wallet-error").assertTextEquals("合成余额读取失败")
        compose.onNodeWithTag("recharge-offers-error").assertExists()
        compose.runOnIdle {
            state.wallet = Wallet("not_created", 0, 0, 0, 0, 0, 0, 0); state.walletLoaded = true; state.walletError = null
            state.rechargeOffers = emptyList(); state.rechargeOffersLoaded = true; state.rechargeOffersError = null
        }
        compose.onNodeWithTag("wallet-balance").assertTextEquals("0 Coins")
        compose.onNodeWithTag("recharge-offers-empty").assertExists()
        compose.runOnIdle { assertTrue(calls.isEmpty()); assertTrue(opened.isEmpty()) }
    }
    @Test fun aChangedOfferSnapshotRejectsTheTapWithOneInlineMessageAndNoDialog() {
        launch(fontScale = 2f) { rechargeOffers = listOf(offer); rechargeOffersLoaded = true }
        compose.onNodeWithText("73 Coins").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("recharge-select-${offer.offerRef}").performScrollTo().performClick()
        // The directory changes after the user selected (price moved): the tap must neither create nor open anything.
        compose.runOnIdle { state.rechargeOffers = listOf(offer.copy(quoteVersion = 10, amountMinor = 2567)) }
        compose.onNodeWithTag("recharge-selected-pay").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("recharge-confirm").assertDoesNotExist()
        compose.onNodeWithText("确认充值金额").assertDoesNotExist()
        compose.onNodeWithText("核对服务器付款信息").assertDoesNotExist()
        compose.runOnIdle { assertTrue(calls.isEmpty()); assertNull(state.recharge); assertTrue(opened.isEmpty()) }
    }
    @Test fun anUnpaidOrderIsNeverShownOnTheRechargeScreenItLivesInOrderDetails() {
        launch { pendingRecharge = true; recharge = order }
        compose.onNodeWithTag("recharge-amount-card").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("recharge-order").assertDoesNotExist()
        compose.onNodeWithTag("recharge-status").assertDoesNotExist()
        compose.onNodeWithTag("recharge-pay").assertDoesNotExist()
        compose.onNodeWithTag("payment-open-confirm").assertDoesNotExist()
        compose.runOnIdle { assertTrue(opened.isEmpty()); assertTrue(state.pendingRecharge); assertTrue(calls.isEmpty()) }
    }
    @Test fun orderDetailsListsAnUnpaidOrderWithPayNowAndAnExpiredOneWithoutAButton() {
        val expired = order.copy(orderRef = "recharge_ui_expired", nextAction = PaymentRedirect(order.nextAction!!.url, Instant.EPOCH),
            expiresAt = Instant.EPOCH)
        launch(mode = CommerceMode.Ledger) { rechargeHistory = listOf(order, expired) }
        compose.onNodeWithTag("unpaid-recharge-container").assertIsDisplayed()
        compose.onNodeWithTag("unpaid-recharge-status-${order.orderRef}").assertTextContains("待付款", substring = true)
        compose.onNodeWithTag("history-pay-${order.orderRef}").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("unpaid-recharge-status-${expired.orderRef}").assertTextContains("已失效", substring = true)
        compose.onNodeWithTag("history-pay-${expired.orderRef}").assertDoesNotExist()
        // Straight to the payment entry: no review dialog, no request.
        compose.onNodeWithTag("history-pay-${order.orderRef}").performClick()
        compose.onNodeWithText("核对服务器付款信息").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(order.nextAction!!.url), opened); assertTrue(calls.isEmpty()) }
    }
    @Test fun verifiedOrExpiredOrdersAlsoStayOffTheRechargeScreen() {
        launch { pendingRecharge = true; recharge = order.copy(status = "payment_verified", nextAction = null) }
        compose.onNodeWithTag("recharge-order").assertDoesNotExist()
        compose.runOnIdle { state.recharge = order.copy(nextAction = PaymentRedirect(order.nextAction!!.url, Instant.EPOCH)) }
        compose.onNodeWithTag("recharge-order").assertDoesNotExist()
        compose.onNodeWithTag("recharge-pay").assertDoesNotExist()
        compose.runOnIdle { assertTrue(calls.isEmpty()); assertTrue(opened.isEmpty()) }
    }
    @Test fun anUnknownCreateResultKeepsOnlyAMinimalRecoveryNotice() {
        launch { pendingRecharge = true; recharge = null }
        compose.onNodeWithTag("recharge-status").performScrollTo().assertTextEquals("提交结果尚未确认，可直接重新充值，或点下方查询。")
        compose.onNodeWithTag("recharge-abandon").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("recharge-pay").assertDoesNotExist()
        compose.onNodeWithTag("recharge-amount-card").performScrollTo().assertIsDisplayed()
    }
    @Test fun anExpiredUnpaidOrderNeverBlocksStartingANewRecharge() {
        launch { pendingRecharge = true; recharge = order.copy(nextAction = PaymentRedirect(order.nextAction!!.url, Instant.EPOCH))
            rechargeOffers = listOf(offer); rechargeOffersLoaded = true }
        compose.onNodeWithTag("recharge-order").assertDoesNotExist()
        compose.onNodeWithTag("recharge-pay").assertDoesNotExist()
        // The amount grid and the checkout bar stay available beside the old order.
        compose.onNodeWithTag("recharge-amount-card").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("recharge-select-${offer.offerRef}").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("recharge-selected-pay").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertTrue(calls.isEmpty()); assertTrue(opened.isEmpty()) }
    }
}
