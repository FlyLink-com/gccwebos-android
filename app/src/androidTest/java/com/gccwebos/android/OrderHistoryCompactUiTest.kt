package com.gccwebos.android

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gccwebos.android.commerce.*
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.portal.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** In-memory history only: no login, real HTTP, payment URL, order mutation or persistent writes. */
class OrderHistoryCompactUiTest {
    @get:Rule val compose = createComposeRule()
    private val network = AtomicInteger()
    private val writes = AtomicInteger()
    private val reads = AtomicInteger()
    private val noon = LocalDate.of(2026, 9, 30).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()
    private lateinit var commerce: CommerceController
    private lateinit var detailState: OrderDetailController
    private lateinit var scroll: ScrollState
    private val offer = Offer("compact_history_offer", "compact_history_offer_v1", LocalizedText("合成历史商品", "Synthetic history"),
        LocalizedText("合成说明", "Synthetic description"), LocalizedNotices(listOf("仅供离线测试"), listOf("Offline test")),
        73, 7300, listOf("paid_coins"), 47, 17, 0)

    private fun order(ref: String = "compact_history_order", createdAt: Instant = noon, amount: Long = 73) =
        PurchaseResult.Order(ref, offer, amount, listOf(CoinAllocation("paid_coins", amount)),
            "captured", "pending", null, createdAt.plusSeconds(600), createdAt, createdAt)

    private fun plan() = PurchaseResult.Deferred("compact_history_plan", offer, 73, "scheduled_switch",
        noon.plusSeconds(3600), "settled", null, noon, noon.plusSeconds(30), noon, noon.plusSeconds(30))

    private fun recharge(ref: String, status: String, currency: String, exponent: Int, minor: Long) =
        RechargeOrder(ref, "compact_recharge_offer", 1, 73, minor, currency, exponent,
            "fixture_adapter", "alipay", status, null, null, null, null, noon.plusSeconds(600), noon, noon)

    private fun owner(scope: CoroutineScope): AccountController {
        val transport = object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                network.incrementAndGet(); throw AssertionError("No real Portal requests")
            }
            override suspend fun commerce(path: String, body: String?, session: Secret, key: String?): WireResponse {
                network.incrementAndGet(); throw AssertionError("No real commerce requests")
            }
        }
        val delivery = object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) { writes.incrementAndGet(); throw AssertionError("No delivery writes") }
            override fun clear() { writes.incrementAndGet(); throw AssertionError("No delivery clear") }
        }
        return AccountController(PortalRepository(transport, delivery,
            OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L },
            subscription = SubscriptionSource { throw AssertionError("No subscription requests") },
            sessions = MemorySessionStore(), profiles = MemoryLocalProfileStore()), scope).apply {
            account = "history.fixture@example.invalid"; authenticated = true
        }
    }

    private fun launch(width: Int = 320, fontScale: Float = 1f, setup: CommerceController.() -> Unit,
        fixtureShown: State<Boolean>? = null,
        read: suspend (HistorySelection) -> HistoryDetail) {
        compose.setContent {
            if (fixtureShown?.value == false) return@setContent
            val scope = rememberCoroutineScope()
            val account = remember { owner(scope) }
            val state = remember {
                val recovery = object : CommerceRecovery {
                    override fun read(binding: String, kind: String): PendingCommerce? = throw AssertionError("No recovery read")
                    override fun save(binding: String, value: PendingCommerce) {
                        writes.incrementAndGet(); throw AssertionError("No order writes")
                    }
                    override fun clear(binding: String, kind: String) {
                        writes.incrementAndGet(); throw AssertionError("No order clear")
                    }
                }
                CommerceController(account, scope, recovery).apply(setup)
            }
            val details = remember { OrderDetailController(account, scope) { reads.incrementAndGet(); read(it) } }
            commerce = state
            detailState = details
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GccTheme { Surface {
                    val rememberedScroll = rememberScrollState()
                    scroll = rememberedScroll
                    Column(Modifier.width(width.dp).fillMaxHeight().testTag("history-compact-host")
                        .verticalScroll(rememberedScroll).padding(12.dp)) {
                        OrderHistoryPanel(account, state, details)
                    }
                } }
            }
        }
    }

    @After fun allFixturesRemainOfflineAndReadOnly() {
        assertEquals(0, network.get())
        assertEquals(0, writes.get())
    }

    @Test fun groupedWholeRowsKeepCursorSelectionAndScrollAfterDetailReturn() {
        val first = order("compact_history_first")
        val second = order("compact_history_second", noon.minusSeconds(600)).copy(offer = offer.copy(
            title = LocalizedText("合成流量包", "Synthetic data pack"), category = "data_pack",
            durationDays = null, monthlyTrafficGb = null, trafficGb = 9))
        val third = order("compact_history_third", noon.minusSeconds(86400))
        val page = OrderPage(listOf(first, second, third), OrderCursor(third.createdAt, third.ref))
        launch(setup = { commerceModel = CommerceModel.LEGACY; orderPage = page }) { selected ->
            HistoryDetail.Purchase(page.orders.single { it.ref == selected.ref })
        }
        val day = noon.atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val priorDay = third.createdAt.atZone(ZoneId.systemDefault()).toLocalDate().toString()
        compose.onAllNodesWithTag("history-date-$day").assertCountEquals(1)
        compose.onAllNodesWithTag("history-date-$priorDay").assertCountEquals(1)
        compose.onNodeWithTag("history-order-${second.ref}-time", useUnmergedTree = true)
            .assertTextContains("流量包购买", substring = true)
        compose.onNodeWithText("记录 ${third.ref}").assertDoesNotExist()
        val target = compose.onNodeWithTag("history-detail-${third.ref}")
        target.assertHasClickAction().assertHeightIsAtLeast(48.dp).performScrollTo()
        var position = 0
        compose.runOnIdle { position = scroll.value }
        target.performClick()
        compose.onNodeWithText(third.ref).assertExists()
        compose.onNodeWithTag("history-payment-state").assertTextEquals("已完成")
        compose.onNodeWithTag("history-fulfillment-state").assertTextEquals("处理中")
        compose.onNodeWithTag("history-detail-back").performClick()
        compose.onNodeWithTag("history-order-${third.ref}").assertIsSelected()
        compose.runOnIdle { assertSame(page, commerce.orderPage); assertEquals(position, scroll.value) }
        assertEquals(1, reads.get())
    }

    @Test fun rechargeAmountUsesCurrencyExponentAndVerifiedPaymentDoesNotMeanCredit() {
        val plan = plan()
        val verified = recharge("compact_recharge_verified", "payment_verified", "USD", 3, 12345)
        val credited = recharge("compact_recharge_credited", "credited", "JPY", 0, 1234)
        launch(setup = {
            commerceModel = CommerceModel.WALLET_FIRST; transitions = listOf(plan)
            rechargeHistory = listOf(verified, credited)
        }) { selection ->
            if (selection.kind == HistoryKind.Recharge) HistoryDetail.Recharge(
                listOf(verified, credited).single { it.orderRef == selection.ref }) else HistoryDetail.Purchase(plan)
        }
        compose.onNodeWithTag("orders-orders").assertDoesNotExist()
        compose.onNodeWithTag("orders-recharge").performScrollTo().performClick()
        val verifiedTag = "history-recharge-${verified.orderRef}"
        compose.onNodeWithTag("$verifiedTag-amount", useUnmergedTree = true).assertTextEquals("USD 12.345")
        compose.onNodeWithTag("$verifiedTag-state-0", useUnmergedTree = true).assertTextEquals("确认中")
        compose.onNodeWithTag("$verifiedTag-state-1", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("$verifiedTag-expected", useUnmergedTree = true).assertTextEquals("预计 73 Coins")
        val creditedTag = "history-recharge-${credited.orderRef}"
        compose.onNodeWithTag("$creditedTag-amount", useUnmergedTree = true).assertTextEquals("JPY 1234")
        compose.onNodeWithTag("$creditedTag-state-0", useUnmergedTree = true).assertTextEquals("已到账")
        compose.onNodeWithTag("history-detail-${verified.orderRef}").performScrollTo().performClick()
        compose.onNodeWithTag("history-recharge-state").assertTextEquals("确认中")
        compose.onNodeWithTag("history-credit-state").assertTextEquals("确认中")
        compose.onNodeWithTag("history-detail-back").performClick()
        compose.onNodeWithTag("orders-recharge").assertIsSelected()
        compose.onNodeWithTag("history-recharge-${verified.orderRef}").assertIsSelected()
        assertEquals(1, reads.get())
    }

    @Test fun loadingDisablesWholeRowAndLocalBackRejectsLateDetail() {
        val value = order()
        val release = CompletableDeferred<Unit>()
        try {
            launch(setup = { commerceModel = CommerceModel.LEGACY; orderPage = OrderPage(listOf(value)) }) {
                withContext(NonCancellable) { release.await() }
                HistoryDetail.Purchase(value)
            }
            compose.onNodeWithTag("history-detail-${value.ref}").performScrollTo().performClick()
            compose.waitUntil { reads.get() == 1 }
            compose.onNodeWithTag("history-detail-${value.ref}").assertIsNotEnabled()
            compose.onNodeWithTag("history-detail-loading").assertDoesNotExist()
            compose.runOnIdle { assertTrue(detailState.busy); assertNull(detailState.value) }
            compose.onNodeWithTag("history-detail-back").performClick()
            compose.runOnIdle { release.complete(Unit) }
            compose.waitForIdle()
            compose.onNodeWithTag("history-detail-title").assertDoesNotExist()
            compose.onNodeWithTag("history-payment-state").assertDoesNotExist()
            compose.onNodeWithTag("history-order-${value.ref}").assertIsSelected()
            assertEquals(1, reads.get())
        } finally {
            // Release even when launch/assertions fail, before the Compose rule tears down its scope.
            release.complete(Unit)
        }
    }

    @Test fun blockedDetailAssertionFailureReleasesReaderAndCompositionDisposalRejectsLateValue() {
        val value = order()
        val release = CompletableDeferred<Unit>()
        val fixtureShown = mutableStateOf(true)
        val readFinished = AtomicInteger()
        val expected = AssertionError("Synthetic assertion before the normal detail release")
        val failure = assertThrows(AssertionError::class.java) {
            try {
                launch(setup = { commerceModel = CommerceModel.LEGACY; orderPage = OrderPage(listOf(value)) },
                    fixtureShown = fixtureShown) {
                    try {
                        withContext(NonCancellable) { release.await() }
                        HistoryDetail.Purchase(value)
                    } finally {
                        readFinished.incrementAndGet()
                    }
                }
                compose.onNodeWithTag("history-detail-${value.ref}").performScrollTo().performClick()
                compose.waitUntil(timeoutMillis = 5_000) { reads.get() == 1 }
                compose.onNodeWithTag("history-detail-loading").assertDoesNotExist()
                compose.runOnIdle { assertTrue(detailState.busy); assertNull(detailState.value) }
                throw expected
            } finally {
                try {
                    // Dispose the actual composition and its request while the reader is still blocked.
                    compose.runOnIdle { fixtureShown.value = false }
                    compose.onNodeWithTag("history-compact-host").assertDoesNotExist()
                } finally {
                    // Cleanup cannot strand the NonCancellable read even if disposal itself fails.
                    release.complete(Unit)
                }
            }
        }
        assertSame(expected, failure)
        compose.waitUntil(timeoutMillis = 5_000) { readFinished.get() == 1 }
        compose.runOnIdle {
            assertFalse(detailState.visible); assertFalse(detailState.busy)
            assertNull(detailState.selected); assertNull(detailState.value); assertNull(detailState.error)
        }
        compose.onNodeWithTag("history-compact-host").assertDoesNotExist()
        compose.onNodeWithTag("history-detail-title").assertDoesNotExist()
        compose.onNodeWithTag("history-payment-state").assertDoesNotExist()
        assertEquals(1, reads.get())
    }

    @Test fun narrowDoubledFontKeepsLongAmountInsideWholeTargetAndReturnReachable() {
        val value = order(amount = MAX_SAFE_COIN_UNITS)
        launch(width = 280, fontScale = 2f,
            setup = { commerceModel = CommerceModel.LEGACY; orderPage = OrderPage(listOf(value)) }) {
            HistoryDetail.Purchase(value)
        }
        val target = compose.onNodeWithTag("history-detail-${value.ref}")
        target.assertHasClickAction().assertHeightIsAtLeast(48.dp).performScrollTo()
        val hostBounds = compose.onNodeWithTag("history-compact-host").fetchSemanticsNode().boundsInRoot
        val amountNode = compose.onNodeWithTag("history-order-${value.ref}-amount", useUnmergedTree = true)
        amountNode.assertTextEquals("9007199254740991 Coins")
        val amountBounds = amountNode.fetchSemanticsNode().boundsInRoot
        assertTrue(amountBounds.left >= hostBounds.left)
        assertTrue(amountBounds.right <= hostBounds.right)
        target.performClick()
        compose.onNodeWithTag("history-detail-back").assertIsEnabled().performClick()
        compose.onNodeWithTag("history-order-${value.ref}").assertIsSelected()
        assertEquals(1, reads.get())
    }
}
