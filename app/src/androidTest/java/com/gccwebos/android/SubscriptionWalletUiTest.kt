package com.gccwebos.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Region
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.gccwebos.android.commerce.*
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.portal.*
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Explicit offline UI fixtures, never a production wallet, payment, subscription or account. */
class SubscriptionWalletUiTest {
    @get:Rule val compose = createComposeRule()
    private val requests = AtomicInteger()
    private lateinit var account: AccountController
    private lateinit var commerce: CommerceController
    private val future = Instant.parse("2099-09-30T00:00:00Z")
    private val collected = Instant.parse("2026-09-30T00:00:00Z")

    private fun accountFixture(scope: CoroutineScope): AccountController {
        val wire = object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                requests.incrementAndGet()
                throw AssertionError("Offline UI fixture attempted a request: $path")
            }
            override suspend fun commerce(path: String, body: String?, session: Secret, key: String?): WireResponse {
                requests.incrementAndGet()
                throw AssertionError("Offline UI fixture attempted commerce: $path")
            }
        }
        val store = object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) { throw AssertionError("UI fixture cannot save a subscription") }
            override fun clear() = Unit
        }
        return AccountController(PortalRepository(wire, store,
            OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L }), scope).apply {
            account = "offline-ui@example.invalid"
            authenticated = true
            authSessionGeneration = 1
        }
    }

    private fun launchShell(fontScale: Float = 1f, setup: AccountController.() -> Unit = {}) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { accountFixture(scope).apply(setup) }
            account = controller
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AppShell(accountOverride = controller, autoLoadCommerce = false)
            }
        }
    }

    private fun launchCommerce(mode: CommerceMode, fontScale: Float = 1f,
        setup: CommerceController.() -> Unit = {}) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val owner = remember { accountFixture(scope) }
            val state = remember {
                val recovery = object : CommerceRecovery {
                    override fun read(binding: String, kind: String): PendingCommerce? =
                        throw AssertionError("Offline UI fixture must not access recovery storage")
                    override fun save(binding: String, value: PendingCommerce) {
                        throw AssertionError("Offline UI fixture must not mutate recovery storage")
                    }
                    override fun clear(binding: String, kind: String) {
                        throw AssertionError("Offline UI fixture must not clear recovery storage")
                    }
                }
                CommerceController(owner, scope, recovery).apply(setup)
            }
            account = owner
            commerce = state
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GccTheme {
                    Surface {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                            CommercePanel(owner, mode, stateOverride = state, autoLoad = false)
                        }
                    }
                }
            }
        }
    }

    @After fun noFixtureEverContactsNetwork() { assertEquals(0, requests.get()) }

    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "ui-evidence")
        directory.mkdirs()
        // Dialogs add a second root/window. Capture every rendered root rather than assuming one.
        val roots = compose.onAllNodes(isRoot())
        val count = roots.fetchSemanticsNodes().size
        repeat(count) { index ->
            File(directory, "subscription-$name-root-$index.png").outputStream().use {
                roots[index].captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private fun available(days: Long = 30, status: String = "measured") = Resource.Available(
        "offline-resource", "offline-plan", future, false,
        if (status == "pending") Usage(1024, null, null, "pending", null)
        else Usage(1024, 256, 768, status, collected),
        durationDays = days, monthlyTrafficGb = 99.0,
    )

    @Test fun launcherAndAccessibleHeaderUseExactBrandNameWithoutChangingPackage() {
        launchShell { authenticated = false }
        compose.onNodeWithTag("brand-name").assertTextEquals("clash飞连vpn")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.gccwebos.android.debug", context.packageName)
        assertEquals("clash飞连vpn", context.packageManager.getApplicationLabel(context.applicationInfo).toString())
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue(icon is AdaptiveIconDrawable)
        icon.setBounds(0, 0, 192, 192)
        val mask = Region().apply { setPath((icon as AdaptiveIconDrawable).iconMask, Region(0, 0, 192, 192)) }
        val renderedIcon = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        icon.draw(Canvas(renderedIcon))
        assertTrue(mask.contains(96, 96))
        assertTrue(Color.alpha(renderedIcon.getPixel(96, 96)) > 0)
        // Check platform adaptive-mask clipping, never raw equality with the rectangular source PNG.
        listOf(0 to 0, 191 to 0, 0 to 191, 191 to 191).forEach { (x, y) ->
            if (!mask.contains(x, y)) assertEquals(0, Color.alpha(renderedIcon.getPixel(x, y)))
        }
        val evidence = File(context.getExternalFilesDir(null), "ui-evidence").apply { mkdirs() }
        File(evidence, "subscription-launcher-adaptive-mask.png").outputStream().use {
            renderedIcon.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        capture("exact-brand-name")
    }

    @Test fun signedOutLoadingErrorAndUnknownNeverShowNoSubscriptionModal() {
        launchShell { authenticated = false; resource = Resource.None }
        compose.onNodeWithText("登录后查看资源").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("no-resource-guidance").assertDoesNotExist()
        compose.runOnIdle { account.authenticated = true; account.busy = true }
        compose.onNodeWithText("正在读取资源").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("no-resource-guidance").assertDoesNotExist()
        compose.runOnIdle { account.busy = false; account.error = "离线同步失败" }
        compose.onNodeWithText("资源暂时无法确认").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("no-resource-guidance").assertDoesNotExist()
        capture("resource-error")
        compose.runOnIdle { account.error = null; account.resource = null }
        compose.onNodeWithText("等待读取资源").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("no-resource-guidance").assertDoesNotExist()
        compose.runOnIdle { account.resource = Resource.Reconciliation }
        compose.onNodeWithText("资源正在对账").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("no-resource-guidance").assertDoesNotExist()
    }

    @Test fun noneGuidanceDismissalSurvivesRefreshAndRepeatedNavigation() {
        launchShell { resource = Resource.None }
        compose.onNodeWithTag("no-resource-guidance").assertIsDisplayed()
        capture("none-guidance")
        compose.onNodeWithTag("guidance-dismiss").performClick()
        repeat(2) {
            listOf("Lines", "Plans", "Profile", "Connect").forEach {
                compose.onNodeWithTag("nav-$it").performClick()
                compose.onNodeWithTag("page-$it").assertIsDisplayed()
                compose.onNodeWithTag("no-resource-guidance").assertDoesNotExist()
            }
            compose.runOnIdle { account.busy = true }
            compose.runOnIdle { account.busy = false; account.resource = Resource.None }
            compose.onNodeWithTag("no-resource-guidance").assertDoesNotExist()
        }
        compose.onNodeWithTag("vpn-connect").assertIsNotEnabled()
        // A fresh same-account authentication is a new lifecycle, even without a signed-out frame.
        compose.runOnIdle { account.authSessionGeneration++ }
        compose.onNodeWithTag("no-resource-guidance").assertIsDisplayed()
        compose.onNodeWithTag("guidance-dismiss").performClick()
    }

    @Test fun noneGuidanceNavigatesToPlansAndWalletWithoutReappearing() {
        launchShell { resource = Resource.None }
        compose.onNodeWithTag("guidance-plans").performClick()
        compose.onNodeWithTag("page-Plans").assertIsDisplayed()
        compose.onNodeWithTag("nav-Connect").performClick()
        compose.onNodeWithTag("no-resource-guidance").assertDoesNotExist()
        compose.runOnIdle { account.authSessionGeneration++ }
        compose.onNodeWithTag("guidance-wallet").performClick()
        compose.onNodeWithTag("page-Profile").assertIsDisplayed()
        compose.onNodeWithTag("wallet-card").assertExists()
        compose.onNodeWithTag("nav-Connect").performClick()
        compose.onNodeWithTag("no-resource-guidance").assertDoesNotExist()
    }

    @Test fun activeResourceHasDurationCountdownAndTruthfulTrafficStates() {
        launchShell { resource = available() }
        compose.onNodeWithText("30天").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("resource-countdown").performScrollTo().assertTextContains("剩余", substring = true)
        compose.onNodeWithText("1.0 KiB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("256 B").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("768 B").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("no-resource-guidance").assertDoesNotExist()
        capture("active-usage")
        compose.runOnIdle { account.resource = available(days = 365, status = "pending") }
        compose.onNodeWithText("年卡365天").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("尚未采集").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("暂不可确定").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { account.resource = available(status = "stale") }
        compose.onNodeWithText("256 B · 数据已过期").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("768 B · 数据已过期").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { account.resource = available().copy(validUntil = Instant.EPOCH) }
        compose.onNodeWithTag("resource-countdown").performScrollTo().assertTextEquals("已到期")
        compose.onNodeWithTag("no-resource-guidance").assertDoesNotExist()
    }

    @Test fun largeFontGuidanceAndResourcesKeepActionsReachable() {
        launchShell(fontScale = 2f) { resource = Resource.None }
        compose.onNodeWithTag("guidance-plans").assertIsDisplayed()
        compose.onNodeWithTag("guidance-wallet").assertIsDisplayed()
        compose.onNodeWithTag("guidance-dismiss").assertIsDisplayed()
        capture("none-font-200")
        compose.onNodeWithTag("guidance-dismiss").performClick()
        compose.runOnIdle { account.resource = available(days = 365) }
        compose.onNodeWithTag("brand-name").performScrollTo().assertTextEquals("clash飞连vpn")
        compose.onNodeWithTag("resource-countdown").performScrollTo().assertIsDisplayed()
        capture("countdown-font-200")
        compose.onNodeWithTag("vpn-connect").performScrollTo().assertIsNotEnabled()
    }

    private fun offers() = listOf(
        offer("offline_month_one", 30, 10, 20),
        offer("offline_month_two", 30, 50, 60),
        offer("offline_annual", 365, 100, 180),
    )
    private fun offer(ref: String, days: Long, quota: Long, coins: Long) = Offer(
        ref, "${ref}_v1", LocalizedText("离线测试商品", "Offline UI fixture"),
        LocalizedText("仅用于离线界面验证", "Offline rendering only"),
        LocalizedNotices(listOf("测试数据不代表销售价格"), listOf("Not a live price")),
        coins, coins * 100, listOf("paid_coins"), days, quota, 0,
    )
    private fun quote(offer: Offer = offers().first()) = Quote(
        "offline_quote", offer, offer.baseCoinUnits, 0, "available", 0,
        listOf(CoinAllocation("paid_coins", offer.baseCoinUnits)),
        QuoteBalances(CoinBalance(0, 200, 0, 0), CoinBalance(0, 200 - offer.baseCoinUnits, 0, offer.baseCoinUnits)),
        future, null,
    )
    private fun rechargeOffer() = RechargeOffer("offline_recharge", 1, 100, 10000, "CNY", 2, listOf("alipay"))
    private fun rechargeOrder(status: String, action: PaymentRedirect? = null) = RechargeOrder(
        "offline_order", "offline_recharge", 1, 100, 10000, "CNY", 2,
        "xsd_v1", "alipay", status, null, action, null, null, future, collected, collected,
    )

    @Test fun walletUnknownAndErrorNeverBecomeZeroAndConfirmedBalanceIsExact() {
        launchCommerce(CommerceMode.Wallet)
        compose.onNodeWithTag("wallet-balance").performScrollTo().assertTextEquals("尚未读取")
        compose.runOnIdle { commerce.error = "离线余额读取失败" }
        compose.onNodeWithTag("commerce-error").performScrollTo().assertTextEquals("离线余额读取失败")
        compose.onNodeWithTag("wallet-balance").performScrollTo().assertTextEquals("尚未读取")
        capture("wallet-error")
        compose.runOnIdle {
            commerce.error = null
            commerce.wallet = Wallet("active", 1, 12, 5, 3, 20, 3, 4)
        }
        compose.onNodeWithTag("wallet-balance").performScrollTo().assertTextEquals("20 Coins")
        compose.onNodeWithText("已充值 12 Coins · 赠送 5 Coins · 奖励 3 Coins").assertExists()
        compose.onNodeWithText("冻结中 3 Coins · 待到账 4 Coins").assertExists()
        capture("wallet-confirmed-fixture")
        compose.runOnIdle { account.authenticated = false }
        compose.onNodeWithTag("commerce-signed-out").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("wallet-balance").assertDoesNotExist()
    }

    @Test fun threeServerShapedOffersHaveDurationOnlyHeadingsAtLargeFont() {
        val fixtures = offers()
        launchCommerce(CommerceMode.Plans, fontScale = 2f) { offers = fixtures }
        fixtures.forEach { offer ->
            compose.onNodeWithTag("plan-title-${offer.offerRef}").performScrollTo()
                .assertTextEquals(if (offer.durationDays == 365L) "年卡365天" else "30天")
            compose.onNodeWithTag("plan-quote-${offer.offerRef}").performScrollTo().assertIsDisplayed()
        }
        capture("offers-font-200")
    }

    @Test fun quoteDialogCanBeCancelledRepeatedlyWithoutPurchase() {
        val fixture = quote()
        launchCommerce(CommerceMode.Plans) { quote = fixture }
        repeat(2) {
            compose.onNodeWithTag("purchase-confirm").assertIsEnabled()
            compose.onNodeWithTag("purchase-cancel").performClick()
            compose.onNodeWithTag("purchase-confirm").assertDoesNotExist()
            compose.runOnIdle { assertNull(commerce.quote); assertNull(commerce.purchase) }
            if (it == 0) compose.runOnIdle { commerce.quote = fixture }
        }
    }

    @Test fun expiredQuoteCannotPurchaseAndLargeFontCancelIsReachable() {
        val fixture = quote().copy(expiresAt = Instant.EPOCH)
        launchCommerce(CommerceMode.Plans, fontScale = 2f) { quote = fixture }
        compose.onNodeWithTag("purchase-confirm").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("报价已过期，请关闭后重新获取").performScrollTo().assertIsDisplayed()
        capture("quote-expired-font-200")
        compose.onNodeWithTag("purchase-cancel").assertIsDisplayed().performClick()
        compose.onNodeWithTag("purchase-confirm").assertDoesNotExist()
    }

    @Test fun rechargeDialogCancelsWithoutCreatingAnOrderAtLargeFont() {
        val fixture = rechargeOffer()
        launchCommerce(CommerceMode.Wallet, fontScale = 2f) { rechargeOffers = listOf(fixture) }
        compose.onNodeWithTag("recharge-select-${fixture.offerRef}").performScrollTo().performClick()
        compose.onNodeWithTag("recharge-confirm").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("recharge-cancel").assertIsDisplayed()
        capture("recharge-confirm-font-200")
        compose.onNodeWithTag("recharge-cancel").performClick()
        compose.onNodeWithTag("recharge-confirm").assertDoesNotExist()
        compose.runOnIdle { assertNull(commerce.recharge); assertFalse(commerce.pendingRecharge) }
    }

    @Test fun pendingAndVerifiedRechargeCannotCreateAnotherOrderOrRepeatPayment() {
        // No safe redirect is present while the result is pending, so there is no Pay action.
        launchCommerce(CommerceMode.Wallet) {
            rechargeOffers = listOf(rechargeOffer())
            pendingRecharge = true
            recharge = rechargeOrder("pending")
        }
        compose.onNodeWithTag("recharge-order").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("recharge-pay").assertDoesNotExist()
        compose.onNodeWithTag("recharge-offer-offline_recharge").assertDoesNotExist()
        compose.runOnIdle {
            // Even an injected old redirect must not expose payment after verification.
            commerce.recharge = rechargeOrder("payment_verified", PaymentRedirect("https://example.invalid/do-not-open", future))
        }
        compose.onNodeWithText("支付已核验，Coins 尚待到账").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("recharge-pay").assertDoesNotExist()
        compose.onNodeWithTag("recharge-offer-offline_recharge").assertDoesNotExist()
        capture("payment-verified-awaiting-coins")
    }

    @Test fun paymentGuardRejectsExpiredChangedOrderAndReplacedAccountSession() {
        val now = collected
        val action = PaymentRedirect("https://www.xsd8866.com/offline-ui-fixture", future)
        val fixture = rechargeOrder("entry_ready", action)
        launchCommerce(CommerceMode.Wallet) { pendingRecharge = true; recharge = fixture }
        compose.runOnIdle {
            assertTrue(commerce.canOpenPayment(fixture, now))
            assertFalse(commerce.canOpenPayment(fixture, future))
            val expiredOrder = fixture.copy(expiresAt = now)
            commerce.recharge = expiredOrder
            assertFalse(commerce.canOpenPayment(expiredOrder, now))
            val expiredAction = fixture.copy(nextAction = PaymentRedirect(action.url, now))
            commerce.recharge = expiredAction
            assertFalse(commerce.canOpenPayment(expiredAction, now))
            commerce.recharge = fixture.copy(orderRef = "offline_replacement_order")
            assertFalse(commerce.canOpenPayment(fixture, now))
            commerce.recharge = null
            assertFalse(commerce.canOpenPayment(fixture, now))
            val otherAdapter = fixture.copy(adapterKey = "unsupported_adapter")
            commerce.recharge = otherAdapter
            assertFalse(commerce.canOpenPayment(otherAdapter, now))
            val otherMethod = fixture.copy(paymentMethod = "unsupported_method")
            commerce.recharge = otherMethod
            assertFalse(commerce.canOpenPayment(otherMethod, now))
            commerce.recharge = fixture
            account.authenticated = false
            assertFalse(commerce.canOpenPayment(fixture, now))
            account.authenticated = true
            assertTrue(commerce.canOpenPayment(fixture, now))
            account.authSessionGeneration++
            assertFalse(commerce.canOpenPayment(fixture, now))
        }
    }

    @Test fun paymentReviewCanOpenAndCancelWithoutAnExternalPaymentIntent() {
        val fixture = rechargeOrder("entry_ready",
            PaymentRedirect("https://www.xsd8866.com/offline-ui-fixture", future))
        launchCommerce(CommerceMode.Wallet, fontScale = 2f) { pendingRecharge = true; recharge = fixture }
        repeat(2) {
            compose.onNodeWithTag("recharge-pay").performScrollTo().performClick()
            compose.onNodeWithText("核对付款信息").assertIsDisplayed()
            // This only asserts the action; the test deliberately never invokes the external Intent.
            compose.onNodeWithTag("payment-open-confirm").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("将打开：www.xsd8866.com").performScrollTo().assertIsDisplayed()
            if (it == 0) capture("payment-review-font-200")
            if (it == 1) {
                compose.runOnIdle { commerce.recharge = fixture.copy(status = "payment_verified", nextAction = null) }
                compose.onNodeWithTag("payment-open-confirm").assertIsNotEnabled()
            }
            compose.onNodeWithText("暂不付款").assertIsDisplayed().performClick()
            compose.onNodeWithTag("payment-open-confirm").assertDoesNotExist()
        }
        compose.runOnIdle { assertTrue(commerce.pendingRecharge); assertEquals("payment_verified", commerce.recharge?.status) }
    }

    @Test fun unconfirmedPurchaseHidesNewQuoteActions() {
        val fixtures = offers()
        launchCommerce(CommerceMode.Plans) { offers = fixtures; pendingPurchase = true }
        compose.onNodeWithTag("purchase-result").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("上次提交结果待核对，不要重复购买。").assertExists()
        offers().forEach { compose.onNodeWithTag("plan-quote-${it.offerRef}").assertDoesNotExist() }
        compose.onNodeWithTag("purchase-confirm").assertDoesNotExist()
    }
}
