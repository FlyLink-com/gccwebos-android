package com.gccwebos.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gccwebos.android.commerce.*
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.portal.*
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic in-memory UI contracts. No HTTP, account mutation, order create or payment. */
class CommerceCompactUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var account: AccountController
    private lateinit var commerce: CommerceController
    private val calls = mutableListOf<String>()
    private var quoteBarrier: CompletableDeferred<Unit>? = null
    private val fixture = Offer("compact_offer", "compact_offer_v1", LocalizedText("合成套餐", "Synthetic plan"),
        LocalizedText("合成商品权益", "Synthetic benefit"), LocalizedNotices(listOf("合成必要条款"), listOf("Synthetic notice")),
        73, 7300, listOf("paid_coins"), 47, 17, 0)

    private fun launch(mode: CommerceMode = CommerceMode.Plans, width: Int = 360, fontScale: Float = 1f,
        setup: CommerceController.() -> Unit = {}) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val owner = remember {
                val transport = object : PortalTransport {
                    override suspend fun request(path: String, body: String?, session: Secret?): WireResponse =
                        throw AssertionError("No real portal request: $path")
                    override suspend fun commerce(path: String, body: String?, session: Secret, key: String?): WireResponse =
                        throw AssertionError("No real commerce request: $path")
                }
                val delivery = object : DeliveryStore {
                    override fun read(): DeliveryRecord? = null
                    override fun save(record: DeliveryRecord) = throw AssertionError("No subscription writes")
                    override fun clear() = throw AssertionError("No subscription clear")
                }
                AccountController(PortalRepository(transport, delivery,
                    OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L },
                    subscription = SubscriptionSource { throw AssertionError("No subscription request") },
                    sessions = MemorySessionStore(), profiles = MemoryLocalProfileStore()), scope).apply {
                    account = "compact@example.invalid"; authenticated = true; authSessionGeneration = 1
                }
            }
            val state = remember {
                val recovery = object : CommerceRecovery {
                    override fun read(binding: String, kind: String): PendingCommerce? = null
                    override fun save(binding: String, value: PendingCommerce) = throw AssertionError("No order write")
                    override fun clear(binding: String, kind: String) = throw AssertionError("No recovery clear")
                }
                val repository = CommerceRepository({ path, body, key ->
                    calls += path; assertNull(key)
                    when (path) {
                        "/api/portal/commerce/model" -> {
                            assertNull(body); WireResponse(200, """{"commerceModel":"legacy"}""")
                        }
                        "/api/portal/commerce/checkout-preview" -> {
                            assertEquals(fixture.offerRef, JSONObject(requireNotNull(body)).getString("offerRef"))
                            quoteBarrier?.await()
                            WireResponse(200, quoteBody())
                        }
                        else -> throw AssertionError("No order, payment or other request: $path")
                    }
                }, recovery, "compact_synthetic_account")
                // This one synchronous memory read selects the real repository's model branch.
                runBlocking { repository.loadModel() }
                CommerceController(owner, scope, recovery, initialRepository = repository).apply {
                    catalogLoaded = true; commerceModel = CommerceModel.LEGACY; offers = listOf(fixture)
                }.apply(setup)
            }
            account = owner; commerce = state
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GccTheme { Surface {
                    Column(Modifier.width(width.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        CommercePanel(owner, mode, stateOverride = state, autoLoad = false)
                    }
                } }
            }
        }
    }

    private fun quoteBody() = """{"quote":{"quoteRef":"compact_quote","offer":{
        "offerRef":"compact_offer","offerVersionRef":"compact_offer_v1","category":"plan",
        "title":{"zh":"合成套餐","en":"Synthetic plan"},"summary":{"zh":"合成商品权益","en":"Synthetic benefit"},
        "importantNotices":{"zh":["合成必要条款"],"en":["Synthetic notice"]},"baseCoinUnits":73,
        "referencePrice":{"currency":"CNY","amountMinor":7300},"acceptedCoinTypes":["paid_coins"],
        "delivery":{"kind":"subscription_plan","durationDays":47,"monthlyTrafficGb":17,"trafficGb":null,
        "neverExpires":false,"discountBasisPoints":0}},"amountUnits":71,"appliedDiscountBasisPoints":300,
        "purchaseAvailability":"available","shortfallUnits":0,"allocation":[{"coinType":"paid_coins","amountUnits":71}],
        "balances":{"before":{"bonusCoins":0,"paidCoins":200,"rewardCoins":0,"heldCoins":0},
        "after":{"bonusCoins":0,"paidCoins":129,"rewardCoins":0,"heldCoins":71}},
        "expiresAt":"2099-09-30T00:00:00.000Z"}}"""

    @After fun onlyExplicitSyntheticModelAndQuoteReadsArePossible() {
        assertTrue(calls.all { it == "/api/portal/commerce/model" || it == "/api/portal/commerce/checkout-preview" })
        assertNull(commerce.purchase)
        assertNull(commerce.recharge)
        assertFalse(commerce.pendingPurchase)
        assertFalse(commerce.pendingRecharge)
    }

    @Test fun selectionUsesOneQuoteActionAndKeepsServerAmountAllocationAndCancel() {
        val barrier = CompletableDeferred<Unit>(); quoteBarrier = barrier
        launch()
        compose.onNodeWithTag("plan-continue").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("plan-offer-${fixture.offerRef}").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(listOf("/api/portal/commerce/model"), calls) }
        compose.onNodeWithTag("plan-continue").performScrollTo().performClick()
        compose.onNodeWithTag("plan-continue").assertIsNotEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, calls.count { it.endsWith("checkout-preview") })
            assertNull(commerce.quote); barrier.complete(Unit)
        }
        compose.waitUntil(5_000) { commerce.quote != null && !commerce.busy }
        compose.onNodeWithText("本次扣除：71 Coins").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("已购 Coins：71 Coins").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("合成必要条款").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("purchase-confirm").assertIsEnabled()
        compose.onNodeWithTag("purchase-cancel").performClick()
        compose.runOnIdle { assertNull(commerce.quote); assertEquals(1, calls.count { it.endsWith("checkout-preview") }) }
    }

    @Test fun sameRefVersionOrDeliveryReplacementRequiresAnExplicitNewSelection() {
        launch()
        compose.onNodeWithTag("plan-offer-${fixture.offerRef}").performScrollTo().performClick()
        compose.onNodeWithTag("plan-continue").performScrollTo().assertIsEnabled()
        compose.runOnIdle { commerce.offers = listOf(fixture.copy(offerVersionRef = "compact_offer_v2")) }
        compose.onNodeWithTag("plan-continue").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("plan-offer-${fixture.offerRef}").performScrollTo().assertIsNotSelected().performClick()
        compose.onNodeWithTag("plan-continue").performScrollTo().assertIsEnabled()
        compose.runOnIdle { commerce.offers = listOf(commerce.offers.single().copy(durationDays = 83, baseCoinUnits = 91)) }
        compose.onNodeWithTag("plan-continue").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("plan-title-${fixture.offerRef}", useUnmergedTree = true).performScrollTo().assertTextEquals("83天")
        compose.onNodeWithTag("plan-price-${fixture.offerRef}", useUnmergedTree = true).assertTextEquals("91 Coins")
        compose.runOnIdle { assertEquals(listOf("/api/portal/commerce/model"), calls) }
    }

    @Test fun catalogInvalidationDoesNotReselectTheOldSnapshotWhenCatalogReturns() {
        launch()
        compose.onNodeWithTag("plan-offer-${fixture.offerRef}").performScrollTo().performClick()
        compose.runOnIdle { commerce.catalogLoaded = false }
        compose.onNodeWithTag("plan-offer-${fixture.offerRef}").assertDoesNotExist()
        compose.onNodeWithTag("plan-continue").assertDoesNotExist()
        compose.runOnIdle { commerce.catalogLoaded = true }
        compose.onNodeWithTag("plan-offer-${fixture.offerRef}").performScrollTo().assertIsNotSelected()
        compose.onNodeWithTag("plan-continue").performScrollTo().assertIsNotEnabled()
    }

    @Test fun retainedDataPackSelectsItsCategoryBeforeTheSingleQuoteAction() {
        val pack = fixture.copy(offerRef = "compact_data", offerVersionRef = "compact_data_v1", category = "data_pack",
            durationDays = null, monthlyTrafficGb = null, discountBasisPoints = null, trafficGb = 23)
        launch { offers = listOf(fixture, pack) }
        compose.runOnIdle { account.purchaseReturnRef = pack.offerRef }
        compose.onNodeWithTag("purchase-return-requote").performScrollTo().performClick()
        compose.onNodeWithTag("plan-offer-${pack.offerRef}").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("plan-offer-${fixture.offerRef}").assertDoesNotExist()
        compose.onNodeWithTag("plan-continue").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(listOf("/api/portal/commerce/model"), calls) }
    }

    @Test fun changedQuoteSnapshotClearsConfirmationAndRequiresSelectionAgain() {
        launch()
        compose.onNodeWithTag("plan-offer-${fixture.offerRef}").performScrollTo().performClick()
        compose.runOnIdle {
            commerce.quote = CommerceJson.quote(quoteBody().replace("compact_offer_v1", "compact_offer_v2"), CommerceModel.LEGACY)
        }
        compose.onNodeWithTag("purchase-confirm").assertDoesNotExist()
        compose.onNodeWithTag("plan-continue").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("plan-selection-notice").performScrollTo().assertTextEquals("商品已更新，请刷新后重新选择")
        compose.runOnIdle { assertNull(commerce.quote); assertEquals(listOf("/api/portal/commerce/model"), calls) }
    }

    @Test fun lateQuoteAfterAccountGenerationChangesCannotExposeConfirmation() {
        val barrier = CompletableDeferred<Unit>(); quoteBarrier = barrier
        launch()
        compose.onNodeWithTag("plan-offer-${fixture.offerRef}").performScrollTo().performClick()
        compose.onNodeWithTag("plan-continue").performScrollTo().performClick()
        compose.runOnIdle { account.authSessionGeneration++; barrier.complete(Unit) }
        compose.waitForIdle()
        compose.onNodeWithTag("purchase-confirm").assertDoesNotExist()
        compose.onNodeWithTag("plan-continue").assertDoesNotExist()
        compose.runOnIdle { assertNull(commerce.quote); assertEquals(1, calls.count { it.endsWith("checkout-preview") }) }
    }

    @Test fun narrowDoubledFontKeepsSelectionBenefitsAndSingleActionReachable() {
        launch(width = 320, fontScale = 2f) {
            offers = listOf(fixture, fixture.copy(offerRef = "compact_second", durationDays = 83, baseCoinUnits = 91),
                fixture.copy(offerRef = "compact_third", durationDays = 120, baseCoinUnits = 137))
        }
        commerce.offers.forEach { offer ->
            compose.onNodeWithTag("plan-offer-${offer.offerRef}").performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
            compose.onNodeWithTag("plan-benefits").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("plan-continue").performScrollTo().assertIsDisplayed().assertIsEnabled()
        }
        compose.onAllNodesWithTag("plan-continue").assertCountEquals(1)
    }

    @Test fun ledgerReservationShowsItsDeltaFieldAndLocalDetailPreservesZeroTotal() {
        val entry = WalletLedgerEntry("compact_ledger_hold", "available_to_held", "paid_coins", 17,
            -17, 0, 17, 0, "compact_order", Instant.parse("2026-10-02T12:30:00Z"))
        launch(mode = CommerceMode.Ledger, width = 320, fontScale = 2f) {
            ledgerPage = WalletLedgerPage(listOf(entry), null)
        }
        compose.onNodeWithTag("ledger-amount-${entry.entryRef}", useUnmergedTree = true).performScrollTo().assertTextEquals("-17 Coins")
        compose.onNodeWithTag("ledger-qualifier-${entry.entryRef}", useUnmergedTree = true).assertTextEquals("可用变化")
        compose.onNodeWithTag("ledger-total-unchanged-${entry.entryRef}", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        repeat(2) {
            compose.onNodeWithTag("ledger-row-${entry.entryRef}").performScrollTo().performClick()
            compose.onNodeWithTag("ledger-detail-total").performScrollTo().assertTextEquals("0 Coins")
            compose.onNodeWithTag("ledger-detail-available").performScrollTo().assertTextEquals("-17 Coins")
            compose.onNodeWithTag("ledger-detail-held").performScrollTo().assertTextEquals("+17 Coins")
            compose.onNodeWithTag("ledger-detail-ref").performScrollTo().assertTextEquals(entry.entryRef)
            if (it == 0) compose.onNodeWithTag("ledger-detail-close").performClick()
            else compose.runOnIdle { account.authSessionGeneration++ }
        }
        compose.onNodeWithTag("ledger-detail").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("/api/portal/commerce/model"), calls) }
    }
}
