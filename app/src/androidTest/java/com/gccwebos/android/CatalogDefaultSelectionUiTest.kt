package com.gccwebos.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gccwebos.android.commerce.*
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.portal.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Local Compose fixtures: choosing a default never reads the network or requests a quote. */
class CatalogDefaultSelectionUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var commerce: CommerceController
    private lateinit var owner: AccountController
    private var showPlans by mutableStateOf(true)
    private var requests = 0
    private val monthly = Offer("actual_monthly_b", "actual_monthly_b_v1",
        LocalizedText("合成目录月套餐", "Synthetic catalog month"), LocalizedText("", ""),
        LocalizedNotices(emptyList(), emptyList()), 30, 10000, listOf("paid_coins"), 30, 117, 0)
    private val cheaper = monthly.copy(offerRef = "actual_monthly_a", offerVersionRef = "actual_monthly_a_v1", baseCoinUnits = 20)
    private val yearly = monthly.copy(offerRef = "actual_yearly", offerVersionRef = "actual_yearly_v1", durationDays = 365, baseCoinUnits = 200)

    private fun launch(offers: List<Offer> = listOf(cheaper, monthly, yearly),
        model: CommerceModel? = CommerceModel.LEGACY, authenticated: Boolean = true,
        width: Int = 360, fontScale: Float = 1f) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val account = remember {
                val transport = object : PortalTransport {
                    override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                        requests++; throw AssertionError("No Portal request")
                    }
                    override suspend fun commerce(path: String, body: String?, session: Secret, key: String?): WireResponse {
                        requests++; throw AssertionError("No commerce request")
                    }
                }
                val delivery = object : DeliveryStore {
                    override fun read(): DeliveryRecord? = null
                    override fun save(record: DeliveryRecord) = throw AssertionError("No delivery write")
                    override fun clear() = throw AssertionError("No delivery clear")
                }
                AccountController(PortalRepository(transport, delivery,
                    OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L },
                    subscription = SubscriptionSource { throw AssertionError("No subscription request") },
                    sessions = MemorySessionStore(), profiles = MemoryLocalProfileStore()), scope).apply {
                    this.authenticated = authenticated; authSessionGeneration = 1
                }
            }
            val state = remember {
                val recovery = object : CommerceRecovery {
                    override fun read(binding: String, kind: String): PendingCommerce? = null
                    override fun save(binding: String, value: PendingCommerce) = throw AssertionError("No order write")
                    override fun clear(binding: String, kind: String) = throw AssertionError("No recovery clear")
                }
                CommerceController(account, scope, recovery).apply {
                    catalogLoaded = true; commerceModel = model; this.offers = offers
                }
            }
            owner = account
            commerce = state
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GccTheme { Surface {
                    Column(Modifier.width(width.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        if (showPlans) CommercePanel(account, CommerceMode.Plans, stateOverride = state, autoLoad = false)
                    }
                } }
            }
        }
    }

    @After fun defaultAndSelectionHaveNoCommerceSideEffects() {
        assertEquals(0, requests)
        assertNull(commerce.quote); assertNull(commerce.purchase); assertNull(commerce.recharge)
        assertFalse(commerce.pendingPurchase); assertFalse(commerce.pendingRecharge)
    }

    @Test fun defaultUsesTheRealSnapshotAndUserSelectionSurvivesCatalogReordering() {
        launch()
        compose.onNodeWithTag("plan-offer-${monthly.offerRef}").performScrollTo().assertIsSelected()
        compose.runOnIdle { commerce.busy = true }
        compose.onNodeWithTag("plan-offer-${monthly.offerRef}").performScrollTo().assertIsSelected()
        compose.runOnIdle { commerce.busy = false }
        compose.runOnIdle { commerce.offers = listOf(yearly, monthly, cheaper) }
        compose.onNodeWithTag("plan-offer-${monthly.offerRef}").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("plan-price-${monthly.offerRef}", useUnmergedTree = true).assertTextEquals("30 Coins")
        compose.onNodeWithTag("plan-continue").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("commerce-refresh").assertDoesNotExist()
        compose.onNodeWithTag("plan-offer-${cheaper.offerRef}").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { commerce.offers = listOf(yearly, monthly, cheaper) }
        compose.onNodeWithTag("plan-offer-${cheaper.offerRef}").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("plan-offer-${monthly.offerRef}").assertIsNotSelected()
    }

    @Test fun reenteringPlansDefaultsToRealMonthlyOfferAndDropsReturnShortcut() {
        launch()
        compose.onNodeWithTag("plan-offer-${cheaper.offerRef}").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { showPlans = false; owner.purchaseReturnRef = yearly.offerRef }
        compose.onNodeWithTag("plan-continue").assertDoesNotExist()
        compose.runOnIdle { showPlans = true }
        compose.onNodeWithTag("plan-offer-${monthly.offerRef}").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("plan-offer-${cheaper.offerRef}").assertIsNotSelected()
        compose.onNodeWithTag("purchase-return").assertDoesNotExist()
        compose.onNodeWithTag("plan-continue").performScrollTo().assertTextEquals("购买").assertIsEnabled()
        compose.runOnIdle { assertNull(owner.purchaseReturnRef) }
    }

    @Test fun offersShareOneGroupAndWaitingNeverShowsASpinnerOrEnablesPurchase() {
        launch()
        listOf(monthly, cheaper).forEach { offer ->
            compose.onNodeWithTag("plan-offer-${offer.offerRef}").assert(hasAnyAncestor(hasTestTag("plan-options")))
        }
        compose.runOnIdle { commerce.busy = true }
        compose.onNodeWithTag("catalog-loading").assertDoesNotExist()
        compose.onNodeWithTag("plan-offer-${monthly.offerRef}").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("plan-continue").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("读取中…").assertDoesNotExist()
    }

    @Test fun refreshKeepsDisplayOnlyAndDropsItWhenItsAccountGenerationChanges() {
        launch()
        compose.onNodeWithTag("plan-offer-${monthly.offerRef}").performScrollTo().assertIsSelected()
        compose.runOnIdle { commerce.catalogLoaded = false; commerce.offers = emptyList(); commerce.busy = true }
        compose.onNodeWithTag("plan-offer-${monthly.offerRef}").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("plan-continue").assertDoesNotExist()
        compose.onNodeWithTag("catalog-loading").assertDoesNotExist()
        compose.runOnIdle { commerce.busy = false; commerce.error = "合成刷新失败" }
        compose.onNodeWithTag("commerce-error").assertTextEquals("合成刷新失败")
        compose.onNodeWithTag("plan-offer-${monthly.offerRef}").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { owner.authSessionGeneration++ }
        compose.onNodeWithTag("plan-offer-${monthly.offerRef}").assertDoesNotExist()
        compose.onNodeWithTag("purchase-confirm").assertDoesNotExist()
    }

    @Test fun changedDefaultVersionOrCancelledCatalogNeedsAnExplicitFreshSelection() {
        launch()
        compose.onNodeWithTag("plan-offer-${monthly.offerRef}").performScrollTo().assertIsSelected()
        compose.runOnIdle { commerce.offers = listOf(monthly.copy(offerVersionRef = "actual_monthly_b_v2")) }
        compose.onNodeWithTag("plan-offer-${monthly.offerRef}").performScrollTo().assertIsNotSelected()
        compose.onNodeWithTag("plan-continue").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { commerce.catalogLoaded = false }
        compose.onNodeWithTag("plan-offer-${monthly.offerRef}").assertDoesNotExist()
        compose.runOnIdle { commerce.catalogLoaded = true; commerce.offers = listOf(monthly) }
        compose.onNodeWithTag("plan-offer-${monthly.offerRef}").performScrollTo().assertIsNotSelected().performClick()
        compose.onNodeWithTag("plan-continue").performScrollTo().assertIsEnabled()
    }

    @Test fun ambiguousCatalogKeepsTheSingleActionDisabledUntilUserSelects() {
        val alternate = monthly.copy(offerRef = "actual_alternate", offerVersionRef = "actual_alternate_v1")
        launch(offers = listOf(monthly, alternate))
        compose.onNodeWithTag("plan-offer-${monthly.offerRef}").performScrollTo().assertIsNotSelected()
        compose.onNodeWithTag("plan-offer-${alternate.offerRef}").assertIsNotSelected()
        compose.onNodeWithTag("plan-continue").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("plan-offer-${alternate.offerRef}").performScrollTo().performClick().assertIsSelected()
    }

    @Test fun unknownModelDoesNotDefault() {
        launch(model = null)
        compose.onNodeWithTag("plan-offer-${monthly.offerRef}").performScrollTo().assertIsNotSelected()
        compose.onNodeWithTag("plan-continue").performScrollTo().assertIsNotEnabled()
    }

    @Test fun signedOutCatalogKeepsTheRealLoginGate() {
        launch(authenticated = false)
        compose.onNodeWithTag("commerce-signed-out").assertIsDisplayed()
        compose.onNodeWithTag("plan-offer-${monthly.offerRef}").assertDoesNotExist()
        compose.onNodeWithTag("plan-continue").assertDoesNotExist()
    }

    @Test fun narrowDoubledFontKeepsDefaultBenefitsAndActionReachable() {
        launch(width = 320, fontScale = 2f)
        compose.onNodeWithTag("plan-offer-${monthly.offerRef}").performScrollTo().assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("plan-benefits").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("plan-continue").performScrollTo().assertIsDisplayed().assertIsEnabled()
    }

    @Test fun catalogCategoriesKeepSelectedSemanticsAndNeverPreviewOnCategoryClick() {
        launch(width = 320, fontScale = 2f)
        compose.onNodeWithTag("catalog-plans").performScrollTo().assertIsSelected().assertHeightIsAtLeast(44.dp)
        compose.onNodeWithTag("catalog-yearly").performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
        compose.onNodeWithTag("catalog-plans").assertIsNotSelected()
        compose.onNodeWithTag("plan-offer-${yearly.offerRef}").performScrollTo().assertIsNotSelected()
        compose.onNodeWithTag("plan-continue").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("catalog-data-packs").performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
        compose.onNodeWithTag("catalog-yearly").assertIsNotSelected()
        compose.onNodeWithTag("catalog-empty").assertTextEquals("暂无可购流量包")
        compose.onNodeWithTag("plan-continue").assertDoesNotExist()
    }
}
