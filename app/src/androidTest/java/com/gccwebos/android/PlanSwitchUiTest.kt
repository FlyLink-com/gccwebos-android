package com.gccwebos.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.gccwebos.android.commerce.*
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.portal.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Synthetic in-memory contracts of the monthly-card plan change on the plans page: the server's plan-change state labels the
 * cards, its quote prices the change, and a server refusal reads in plain Chinese. No HTTP, order, recovery write or payment.
 */
class PlanSwitchUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var commerce: CommerceController
    private val calls = mutableListOf<String>()
    private var previewReply: () -> WireResponse = { WireResponse(200, quoteBody(planSwitch = UPGRADE, amount = 5)) }

    private fun offer(ref: String, title: String, base: Long, traffic: Long) = Offer(ref, "${ref}_v1", LocalizedText(title, title),
        LocalizedText("合成商品权益", "Synthetic benefit"), LocalizedNotices(listOf("合成必要条款"), listOf("Synthetic notice")),
        base, base * 100, listOf("paid_coins"), 30, traffic, 0)
    private val current = offer("plan_a", "50 GB 月卡", 20, 50)
    private val bigger = offer("plan_b", "100 GB 月卡", 30, 100)
    // The same 30-day tab as the others: a card of another period would sit on another tab.
    private val yearly = offer("plan_c", "200 GB 月卡", 60, 200)

    private fun planState(pending: PlanSwitchPending? = null, offers: List<PlanSwitchOffer>) = PlanSwitchState(
        PlanSwitchCurrent("plan_a", Instant.parse("2099-10-30T00:00:00Z")), pending, offers)
    private val upgradeOffers = listOf(
        PlanSwitchOffer("plan_a", PlanSwitchAction.CurrentRenew, 20, 0, null, null),
        PlanSwitchOffer("plan_b", PlanSwitchAction.Upgrade, 5, 0, null, null),
        PlanSwitchOffer("plan_c", PlanSwitchAction.Unavailable, null, null, null, "plan_switch_cycle_misaligned"))

    private fun launch(planSwitch: PlanSwitchState?, shown: List<Offer> = listOf(current, bigger, yearly)) {
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
                    account = "plan-switch@example.invalid"; authenticated = true; authSessionGeneration = 1
                }
            }
            val state = remember {
                val recovery = object : CommerceRecovery {
                    override fun read(binding: String, kind: String): PendingCommerce? = null
                    override fun save(binding: String, value: PendingCommerce) = throw AssertionError("No order write")
                    override fun clear(binding: String, kind: String) = throw AssertionError("No recovery clear")
                }
                val repository = CommerceRepository({ path, _, key ->
                    calls += path; assertNull(key)
                    when (path) {
                        "/api/portal/commerce/model" -> WireResponse(200, """{"commerceModel":"legacy"}""")
                        "/api/portal/commerce/checkout-preview" -> previewReply()
                        else -> throw AssertionError("No order, payment or other request: $path")
                    }
                }, recovery, "plan_switch_synthetic_account")
                runBlocking { repository.loadModel() }
                CommerceController(owner, scope, recovery, initialRepository = repository).apply {
                    catalogLoaded = true; commerceModel = CommerceModel.LEGACY; offers = shown; this.planSwitch = planSwitch
                }
            }
            commerce = state
            GccTheme { Surface {
                Column(Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    CommercePanel(owner, CommerceMode.Plans, stateOverride = state, autoLoad = false)
                }
            } }
        }
    }

    private fun quoteBody(planSwitch: String?, amount: Int, target: Offer = bigger) = """{"quote":{"quoteRef":"plan_switch_quote","offer":{
        "offerRef":"${target.offerRef}","offerVersionRef":"${target.offerVersionRef}","category":"plan",
        "title":{"zh":"${target.title.zh}","en":"${target.title.zh}"},"summary":{"zh":"合成商品权益","en":"Synthetic benefit"},
        "importantNotices":{"zh":["合成必要条款"],"en":["Synthetic notice"]},"baseCoinUnits":${target.baseCoinUnits},
        "referencePrice":{"currency":"CNY","amountMinor":${target.referenceAmountMinor}},"acceptedCoinTypes":["paid_coins"],
        "delivery":{"kind":"subscription_plan","durationDays":${target.durationDays},"monthlyTrafficGb":${target.monthlyTrafficGb},"trafficGb":null,
        "neverExpires":false,"discountBasisPoints":0}},"amountUnits":$amount,"appliedDiscountBasisPoints":0,
        "purchaseAvailability":"available","shortfallUnits":0,"allocation":[{"coinType":"paid_coins","amountUnits":$amount}],
        "balances":{"before":{"bonusCoins":0,"paidCoins":200,"rewardCoins":0,"heldCoins":0},
        "after":{"bonusCoins":0,"paidCoins":${200 - amount},"rewardCoins":0,"heldCoins":$amount}},
        "expiresAt":"2099-09-30T00:00:00.000Z"${planSwitch?.let { ""","planSwitch":$it""" }.orEmpty()}}}"""

    @After fun onlyModelAndQuoteReadsAreEverMade() {
        assertTrue(calls.all { it == "/api/portal/commerce/model" || it == "/api/portal/commerce/checkout-preview" })
        assertNull(commerce.purchase); assertFalse(commerce.pendingPurchase)
    }

    private fun choose(ref: String) { compose.onNodeWithTag("plan-offer-$ref").performScrollTo().performClick() }

    @Test fun anUpgradeCardShowsThePriceDifferenceAndTakesEffectNow() {
        launch(planState(offers = upgradeOffers))
        compose.onNodeWithTag("plan-switch-current").performScrollTo().assertTextContains("当前套餐：50 GB 月卡")
        compose.onNodeWithTag("plan-switch-chip-plan_b").performScrollTo().assertTextEquals("升级 · 补差价约 5 Coins · 立即生效")
        compose.onNodeWithTag("plan-switch-chip-plan_a").performScrollTo().assertTextEquals("续费")
        choose("plan_b")
        compose.onNodeWithTag("plan-dock-summary").performScrollTo().assertTextContains("补差价约 5 Coins · 立即生效")
        compose.onNodeWithTag("plan-continue").performScrollTo().assertTextEquals("立即升级").performClick()
        compose.waitUntil(5_000) { commerce.quote != null && !commerce.busy }
        compose.onNodeWithTag("plan-switch-dialog-title").assertTextEquals("确认升级套餐")
        compose.onNodeWithTag("plan-switch-line-1").assertTextEquals("本次实扣：5 Coins")
        compose.onNodeWithTag("plan-switch-line-2").assertTextEquals("立即生效")
        compose.onNodeWithTag("plan-switch-line-3").assertTextEquals("到期日不变，已用流量不清零")
        compose.onNodeWithTag("purchase-confirm").assertIsEnabled().assertTextEquals("确认升级")
        compose.onNodeWithTag("purchase-cancel").performClick()
        compose.onNodeWithTag("plan-switch-dialog").assertDoesNotExist()
    }

    @Test fun aDowngradeIsScheduledForExpiryAndAPendingOneShowsAsWaiting() {
        launch(planState(PlanSwitchPending("plan_a", Instant.parse("2099-10-30T00:00:00Z")), listOf(
            PlanSwitchOffer("plan_a", PlanSwitchAction.Downgrade, 20, 0, Instant.parse("2099-10-30T00:00:00Z"), null),
            PlanSwitchOffer("plan_b", PlanSwitchAction.CurrentRenew, 30, 0, null, null))), shown = listOf(current, bigger))
        compose.onNodeWithTag("plan-switch-pending").performScrollTo().assertTextContains("待生效")
        compose.onNodeWithTag("plan-switch-chip-plan_a").performScrollTo().assertTextEquals("降级 · 到期后生效")
        previewReply = { WireResponse(200, quoteBody("""{"kind":"downgrade_scheduled","creditUnits":0,"chargeUnits":20,"effectiveAt":"2099-10-30T00:00:00.000Z","cancelsPendingDowngrade":false}""", 20, current)) }
        choose("plan_a")
        compose.onNodeWithTag("plan-continue").performScrollTo().assertTextEquals("预约降级").performClick()
        compose.waitUntil(5_000) { commerce.quote != null && !commerce.busy }
        compose.onNodeWithTag("plan-switch-dialog-title").assertTextEquals("确认预约降级")
        compose.onNodeWithTag("plan-switch-line-2").assertTextContains("到期后生效")
        compose.onNodeWithTag("purchase-confirm").assertIsEnabled().assertTextEquals("确认预约降级")
        compose.onNodeWithTag("purchase-cancel").performClick()
    }

    @Test fun aRefusedChangeCannotBeChosenAndSaysWhyInPlainChinese() {
        launch(planState(offers = upgradeOffers))
        compose.onNodeWithTag("plan-switch-chip-plan_c").performScrollTo().assertTextEquals("暂不可切换")
        compose.onNodeWithTag("plan-switch-note-plan_c").assertTextContains("年卡")
        compose.onNodeWithTag("plan-offer-plan_c").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("plan-continue").performScrollTo().assertIsNotEnabled()
    }

    @Test fun withTheFeatureOffNothingChangesFromTheOrdinaryCatalog() {
        launch(null)
        compose.onNodeWithTag("plan-offer-plan_b").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("plan-switch-banner").assertDoesNotExist()
        compose.onNodeWithTag("plan-switch-chip-plan_b").assertDoesNotExist()
        choose("plan_b")
        compose.onNodeWithTag("plan-continue").performScrollTo().assertTextEquals("立即购买")
    }

    @Test fun aQuoteThatIsNotTheUpgradeTheCardPromisedCannotBeConfirmed() {
        launch(planState(offers = upgradeOffers))
        previewReply = { WireResponse(200, quoteBody(null, 30)) } // the server priced a plain full-price purchase
        choose("plan_b")
        compose.onNodeWithTag("plan-continue").performScrollTo().performClick()
        compose.waitUntil(5_000) { commerce.quote != null && !commerce.busy }
        compose.onNodeWithTag("purchase-confirm").assertIsNotEnabled()
        compose.onNodeWithText("套餐状态已变化，请关闭后重新选择套餐。", substring = true).assertExists()
        compose.onNodeWithTag("purchase-cancel").performClick()
    }

    @Test fun aDowngradeBeingAppliedShowsProcessingAndLocksTheCards() {
        // The date has passed (2000) but the server has not cleared the scheduled switch yet.
        launch(PlanSwitchState(PlanSwitchCurrent("plan_a", Instant.parse("2000-01-01T00:00:00Z")),
            PlanSwitchPending("plan_a", Instant.now().minusSeconds(20)), listOf(
                PlanSwitchOffer("plan_a", PlanSwitchAction.Unavailable, null, null, null, "plan_switch_application_pending"),
                PlanSwitchOffer("plan_b", PlanSwitchAction.Unavailable, null, null, null, "plan_switch_application_pending"))),
            shown = listOf(current, bigger))
        compose.onNodeWithTag("plan-switch-pending").performScrollTo().assertTextContains("处理中")
        compose.onNodeWithTag("plan-switch-chip-plan_b").performScrollTo().assertTextEquals("处理中")
        compose.onNodeWithTag("plan-switch-note-plan_b").assertTextEquals("正在应用已预约的降级，请稍后。")
        compose.onNodeWithTag("plan-offer-plan_b").assertIsNotEnabled()
        compose.onNodeWithTag("plan-continue").performScrollTo().assertIsNotEnabled()
    }

    @Test fun anOrdinaryOfferRefusedWhileACardIsActiveSaysSoInPlainChinese() {
        launch(planState(offers = upgradeOffers))
        previewReply = { WireResponse(409, """{"error":{"code":"offer_unavailable","retryable":false}}""") }
        choose("plan_a")
        compose.onNodeWithTag("plan-continue").performScrollTo().performClick()
        compose.waitUntil(5_000) { commerce.error != null && !commerce.busy }
        compose.onNodeWithTag("commerce-error").performScrollTo().assertTextEquals("当前套餐生效中，暂不能购买其他套餐。")
    }

    @Test fun aServerRefusalOfTheUpgradeWindowReadsInPlainChinese() {
        launch(planState(offers = upgradeOffers))
        previewReply = { WireResponse(409, """{"error":{"code":"plan_switch_upgrade_window_closed","retryable":false}}""") }
        choose("plan_b")
        compose.onNodeWithTag("plan-continue").performScrollTo().performClick()
        compose.waitUntil(5_000) { commerce.error != null && !commerce.busy }
        compose.onNodeWithTag("commerce-error").performScrollTo().assertTextContains("剩余有效期不足 60 分钟")
        assertNull(commerce.quote)
    }

    private companion object {
        const val UPGRADE = """{"kind":"upgrade_inplace","creditUnits":0,"chargeUnits":5,"effectiveAt":null,"cancelsPendingDowngrade":false}"""
    }
}
