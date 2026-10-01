package com.gccwebos.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
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
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic history only. No server, payment URL, order mutation, or recovery storage. */
class OrderHistoryUiTest {
    @get:Rule val compose = createComposeRule()
    private val network = AtomicInteger()
    private val reads = AtomicInteger()
    private val instant = Instant.parse("2026-09-30T00:00:00Z")
    private lateinit var commerce: CommerceController
    private lateinit var details: OrderDetailController
    private val offer = Offer("offer_fixture_history", "offerv_fixture_history", LocalizedText("合成历史商品", "Synthetic history"),
        LocalizedText("合成说明", "Synthetic description"), LocalizedNotices(listOf("仅供离线测试"), listOf("Offline test")),
        73, 7300, listOf("paid_coins"), 47, 17, 0)
    private fun legacy() = PurchaseResult.Order("order_fixture_history", offer, 73,
        listOf(CoinAllocation("paid_coins", 73)), "captured", "pending", null,
        instant.plusSeconds(600), instant, instant)
    private fun deferred() = PurchaseResult.Deferred("transition_fixture_history", offer, 73,
        "scheduled_switch", instant.plusSeconds(3600), "settled", null, instant, null, instant, instant)
    private fun recharge(status: String = "payment_verified") = RechargeOrder("recharge_fixture_history", "recharge_fixture_offer", 1,
        73, 12345, "USD", 3, "fixture_adapter", "paypal", status, null, null, null, null,
        instant.plusSeconds(600), instant, instant)

    private fun owner(scope: CoroutineScope): AccountController {
        val transport = object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                network.incrementAndGet(); throw AssertionError("No Portal requests")
            }
            override suspend fun commerce(path: String, body: String?, session: Secret, key: String?): WireResponse {
                network.incrementAndGet(); throw AssertionError("No commerce requests")
            }
        }
        val delivery = object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = throw AssertionError("No delivery writes")
            override fun clear() = Unit
        }
        return AccountController(PortalRepository(transport, delivery,
            OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L },
            subscription = SubscriptionSource { throw AssertionError("No subscription requests") },
            sessions = MemorySessionStore(), profiles = MemoryLocalProfileStore()), scope).apply { authenticated = true }
    }

    private fun launch(fontScale: Float = 1f, setup: CommerceController.() -> Unit,
        read: suspend (HistorySelection) -> HistoryDetail) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val account = remember { owner(scope) }
            val state = remember {
                val recovery = object : CommerceRecovery {
                    override fun read(binding: String, kind: String): PendingCommerce? = throw AssertionError("No recovery read")
                    override fun save(binding: String, value: PendingCommerce) = throw AssertionError("No recovery save")
                    override fun clear(binding: String, kind: String) = throw AssertionError("No recovery clear")
                }
                CommerceController(account, scope, recovery).apply(setup)
            }
            val detail = remember { OrderDetailController(account, scope) { reads.incrementAndGet(); read(it) } }
            commerce = state; details = detail
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GccTheme { Surface {
                    Column(Modifier.widthIn(max = 320.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        OrderHistoryPanel(account, state, detail)
                    }
                } }
            }
        }
    }

    @After fun noRealRequests() { assertEquals(0, network.get()) }

    @Test fun legacyDetailsReturnKeepsListCursorCategoryAndSelectedRow() {
        val value = legacy()
        val page = OrderPage(listOf(value), OrderCursor(instant, value.ref))
        launch(setup = { commerceModel = CommerceModel.LEGACY; orderPage = page }) { HistoryDetail.Purchase(value) }
        compose.onNodeWithTag("history-detail-${value.ref}").performScrollTo().performClick()
        compose.onNodeWithTag("history-payment-state").assertTextEquals("已扣款")
        compose.onNodeWithTag("history-fulfillment-state").assertTextEquals("等待发放")
        compose.onNodeWithTag("history-detail-back").performClick()
        compose.onNodeWithTag("history-detail-title").assertDoesNotExist()
        compose.onNodeWithTag("history-order-${value.ref}").assertIsSelected()
        compose.onNodeWithTag("orders-orders").assertIsSelected()
        compose.runOnIdle { assertSame(page, commerce.orderPage); assertEquals(page.nextCursor, commerce.orderPage!!.nextCursor) }
        compose.onNodeWithTag("history-detail-${value.ref}").performClick()
        compose.onNodeWithTag("history-detail-back").performClick()
        assertEquals(2, reads.get())
    }

    @Test fun deferredAndRechargeKeepScheduleCurrencyAndVerifiedPaymentIndependent() {
        val plan = deferred(); val topup = recharge()
        launch(setup = { commerceModel = CommerceModel.WALLET_FIRST; transitions = listOf(plan); rechargeHistory = listOf(topup) }) {
            if (it.kind == HistoryKind.Recharge) HistoryDetail.Recharge(topup) else HistoryDetail.Purchase(plan)
        }
        compose.onNodeWithTag("history-detail-${plan.ref}").performScrollTo().performClick()
        compose.onNodeWithTag("history-transition-state").assertTextEquals("已结算，请同步资源")
        compose.onNodeWithTag("history-scheduled-for").assertExists()
        compose.onNodeWithText("服务器未提供").assertExists()
        compose.onNodeWithTag("history-detail-back").performClick()
        compose.onNodeWithTag("orders-recharge").performScrollTo().performClick()
        compose.onNodeWithText("USD 12.345").assertExists()
        compose.onNodeWithTag("history-detail-${topup.orderRef}").performScrollTo().performClick()
        compose.onNodeWithTag("history-recharge-state").assertTextEquals("支付已核验，Coins 尚待到账")
        compose.onNodeWithTag("history-detail-back").performClick()
        compose.onNodeWithTag("orders-recharge").assertIsSelected()
        compose.onNodeWithTag("history-recharge-${topup.orderRef}").assertIsSelected()
        compose.onNodeWithTag("orders-orders").assertDoesNotExist()
    }

    @Test fun unreadLoadingErrorAndAuthoritativeEmptyDoNotFallbackToLegacy() {
        launch(setup = {}) { throw AssertionError("No detail read") }
        compose.onNodeWithTag("history-read-title").assertTextEquals("订单类型尚未读取")
        compose.onNodeWithTag("orders-orders").assertDoesNotExist()
        compose.runOnIdle { commerce.error = "恢复记录不可用" }
        compose.onNodeWithTag("history-read-title").assertTextEquals("订单类型查询失败")
        compose.onNodeWithTag("history-error").assertTextEquals("恢复记录不可用")
        compose.runOnIdle { commerce.busy = true }
        compose.onNodeWithTag("history-read-title").assertTextEquals("正在查询订单类型")
        compose.onNodeWithTag("history-error").assertDoesNotExist()
        compose.runOnIdle { commerce.busy = false; commerce.purchaseHistoryError = "订单类型读取失败" }
        compose.onNodeWithTag("history-read-title").assertTextEquals("订单类型查询失败")
        compose.onNodeWithText("暂无这类订单").assertDoesNotExist()
        compose.runOnIdle { commerce.commerceModel = CommerceModel.LEGACY; commerce.purchaseHistoryError = null; commerce.orderPage = OrderPage(emptyList()) }
        compose.onNodeWithTag("history-read-title").assertTextEquals("暂无这类订单")
        compose.onNodeWithTag("history-error").assertDoesNotExist()
        compose.runOnIdle { commerce.orderPage = OrderPage(listOf(legacy())) }
        compose.onNodeWithTag("history-error").assertDoesNotExist()
        compose.onNodeWithTag("history-order-${legacy().ref}").assertExists()
        assertEquals(0, reads.get())
    }

    @Test fun detailErrorRetriesOnlySameReadAndReturnNeverDeletesPendingRecord() {
        val order = legacy(); val page = OrderPage(listOf(order))
        launch(setup = { commerceModel = CommerceModel.LEGACY; orderPage = page; pendingPurchase = true }) {
            assertEquals(HistorySelection(HistoryKind.Legacy, order.ref), it)
            if (reads.get() == 1) throw CommerceFailure("order_not_found", 404)
            HistoryDetail.Purchase(order)
        }
        compose.onNodeWithTag("history-detail-${order.ref}").performScrollTo().performClick()
        compose.onNodeWithTag("history-detail-error").assertExists()
        compose.onNodeWithTag("history-payment-state").assertDoesNotExist()
        compose.onNodeWithTag("history-detail-retry").performClick()
        compose.onNodeWithTag("history-payment-state").assertTextEquals("已扣款")
        compose.onNodeWithTag("history-detail-back").performClick()
        compose.runOnIdle { assertTrue(commerce.pendingPurchase); assertSame(page, commerce.orderPage) }
        assertEquals(2, reads.get())
    }

    @Test fun narrowDoubledFontDetailKeepsLocalReturnReachable() {
        val order = legacy()
        launch(fontScale = 2f, setup = { commerceModel = CommerceModel.LEGACY; orderPage = OrderPage(listOf(order)) }) {
            HistoryDetail.Purchase(order)
        }
        compose.onNodeWithTag("history-detail-${order.ref}").performScrollTo().performClick()
        compose.onNodeWithTag("history-detail-back").assertIsEnabled().performClick()
        compose.onNodeWithTag("history-order-${order.ref}").assertIsSelected()
        assertEquals(1, reads.get())
    }
}
