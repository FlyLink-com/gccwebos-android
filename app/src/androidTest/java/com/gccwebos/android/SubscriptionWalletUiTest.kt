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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Explicit offline UI fixtures, never a production wallet, payment, subscription or account. */
class SubscriptionWalletUiTest {
    @get:Rule val compose = createComposeRule()
    private val requests = AtomicInteger()
    private val loginChallengeRequests = AtomicInteger()
    private var expectedLoginChallengeRequests = 0
    private lateinit var account: AccountController
    private lateinit var commerce: CommerceController
    private val future = Instant.parse("2099-09-30T00:00:00Z")
    private val collected = Instant.parse("2026-09-30T00:00:00Z")
    private val verifiedOverviewReads = AtomicInteger()
    private var verifiedOverviewBody = """{"resource":{"state":"none"}}"""
    private var verifiedOverviewFailure: PortalFailure.Reason? = null

    /** Server-validated synthetic native session; an editable email is never its account binding. */
    private fun launchVerifiedOverviewShell(fontScale: Float = 1f,
        onPowerAction: ((ConnectionPowerAction) -> Unit)? = null) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember {
                val now = Instant.now()
                val token = Secret("fixture_usage_native_session")
                val sessions = MemorySessionStore().apply {
                    save(SavedSession.capture(Session(token, "fixture_usage_stable_account",
                        now.plusSeconds(600), 600_000, 0), now, 0))
                }
                val wire = object : PortalTransport {
                    override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                        if (path != "/api/portal/resources/current/overview") {
                            requests.incrementAndGet()
                            throw AssertionError("Verified overview fixture refuses every other route")
                        }
                        assertNull(body)
                        assertEquals(token.value, requireNotNull(session).value)
                        verifiedOverviewReads.incrementAndGet()
                        verifiedOverviewFailure?.let { throw PortalFailure(it) }
                        return WireResponse(200, verifiedOverviewBody)
                    }
                    override suspend fun commerce(path: String, body: String?, session: Secret, key: String?): WireResponse {
                        requests.incrementAndGet()
                        throw AssertionError("Verified overview fixture refuses commerce")
                    }
                }
                val delivery = object : DeliveryStore {
                    override fun read(): DeliveryRecord? = null
                    override fun save(record: DeliveryRecord) = throw AssertionError("No fixture delivery writes")
                    override fun clear() = Unit
                }
                AccountController(PortalRepository(wire, delivery,
                    OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L }, { now },
                    subscription = SubscriptionSource { throw AssertionError("No fixture subscription download") },
                    sessions = sessions, profiles = MemoryLocalProfileStore()), scope)
            }
            account = controller
            LaunchedEffect(controller) { controller.restoreAccount() }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AppShell(accountOverride = controller, autoLoadCommerce = false,
                    onConnectionPowerAction = onPowerAction)
            }
        }
        compose.waitUntil(5_000) { ::account.isInitialized && !account.busy && account.authenticated &&
            account.authenticatedAccountId == "fixture_usage_stable_account" &&
            account.accountUsage.state.value.authenticated && account.accountUsage.state.value.resource === Resource.None }
        compose.runOnIdle {
            assertEquals(2, verifiedOverviewReads.get()) // restore validation + typed current overview.
            assertTrue(account.account.isBlank()) // No fabricated display email or accountId from input text.
        }
    }

    private fun verifiedAvailableBody(days: Long = 30) = """{"resource":{"state":"available",
        "resourceRef":"resource_${"a".repeat(64)}","plan":{"id":"fixture_usage_plan","durationDays":$days,"monthlyTrafficGb":50},
        "validUntil":"2099-09-30T00:00:00.000Z","setupAvailable":false,"usage":{"totalBytes":4294967296,
        "usedBytes":1073741824,"remainingBytes":3221225472,"collectionStatus":"measured",
        "updatedAt":"2026-10-02T00:00:00.000Z","cycleStartedAt":"2026-10-01T00:00:00.000Z",
        "cycleEndsAt":"2026-11-01T00:00:00.000Z"}}}"""

    private fun accountFixture(scope: CoroutineScope, allowLoginChallenge: Boolean = false): AccountController {
        val wire = object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                // The mounted login form may fetch exactly one in-memory question. This
                // grants no authentication, commerce, subscription or actual network access.
                if (allowLoginChallenge && path == "/api/portal/human-challenges") {
                    assertNull(session)
                    val submitted = JSONObject(requireNotNull(body))
                    assertEquals(setOf("scope"), submitted.keys().asSequence().toSet())
                    assertEquals("portal_login", submitted.getString("scope"))
                    assertEquals("The login form must not loop its question request", 1,
                        loginChallengeRequests.incrementAndGet())
                    return WireResponse(201, """{"challenge":{"challengeRef":"offline_subscription_question",
                        "type":"math","prompt":"离线题目：2 + 3 = ?","expiresInSeconds":180}}""")
                }
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
            OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L },
            subscription = SubscriptionSource { throw AssertionError("No UI subscription request") },
            sessions = MemorySessionStore(), profiles = MemoryLocalProfileStore()), scope).apply {
            account = "offline-ui@example.invalid"
            authenticated = true
            authSessionGeneration = 1
        }
    }

    private fun launchShell(fontScale: Float = 1f, allowLoginChallenge: Boolean = false, catalog: List<Offer>? = null,
        setup: AccountController.() -> Unit = {}) {
        // Permitting an offline question does not mount the private login page.
        // Anonymous launch stays on Connect; only an explicit My action requests it.
        expectedLoginChallengeRequests = 0
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { accountFixture(scope, allowLoginChallenge).apply(setup) }
            account = controller
            val catalogState = remember(controller) { catalog?.let { fixtures ->
                CommerceController(controller, scope, object : CommerceRecovery {
                    override fun read(binding: String, kind: String): PendingCommerce? =
                        throw AssertionError("Catalog rendering must not read recovery storage")
                    override fun save(binding: String, value: PendingCommerce) =
                        throw AssertionError("Catalog rendering must not create an order")
                    override fun clear(binding: String, kind: String) =
                        throw AssertionError("Catalog rendering must not clear recovery storage")
                }).apply { offers = fixtures; catalogLoaded = true; commerceModel = CommerceModel.LEGACY }
            } }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AppShell(accountOverride = controller, autoLoadCommerce = false, commerceOverride = catalogState)
            }
        }
    }

    private fun launchCommerce(mode: CommerceMode, fontScale: Float = 1f,
        setup: CommerceController.() -> Unit = {}) {
        var stagedQuote: Quote? = null
        compose.setContent {
            val scope = rememberCoroutineScope()
            val owner = remember { accountFixture(scope) }
            account = owner
            val state = remember(owner.authSessionGeneration) {
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
                CommerceController(owner, scope, recovery).apply(setup).apply {
                    if (wallet != null) walletLoaded = true
                    if (rechargeOffers.isNotEmpty()) rechargeOffersLoaded = true
                    // A confirmation fixture includes the matching current server catalog;
                    // a retained quote without an admitted selection must never be payable.
                    quote?.let { value ->
                        catalogLoaded = true
                        offers = listOf(value.offer)
                        if (commerceModel == null) commerceModel = CommerceModel.LEGACY
                        if (mode == CommerceMode.Plans) { stagedQuote = value; quote = null }
                    }
                }
            }
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
        compose.waitForIdle()
        // Production visits discard old quotes. Admit the fixture only after an explicit
        // current-catalog selection, as a synthetic response to that selected offer.
        stagedQuote?.let { value ->
            compose.onNodeWithTag("plan-offer-${value.offer.offerRef}").performScrollTo().performClick()
            compose.runOnIdle { commerce.quote = value }
        }
    }

    @After fun noFixtureEverContactsNetwork() {
        assertEquals(0, requests.get())
        assertEquals(expectedLoginChallengeRequests, loginChallengeRequests.get())
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "ui-evidence")
        directory.mkdirs()
        // Full display includes separate Dialog windows; root PixelCopy can capture the backdrop instead.
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory, "subscription-$name-display.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    private fun available(days: Long = 30, status: String = "measured") = Resource.Available(
        "offline-resource", "offline-plan", future, false,
        if (status == "pending") Usage(1024, null, null, "pending", null)
        else Usage(1024, 256, 768, status, collected),
        durationDays = days, monthlyTrafficGb = 99.0,
    )

    @Test fun launcherAndAccessibleHeaderUseExactBrandNameWithoutChangingPackage() {
        launchShell { authenticated = false }
        compose.onNodeWithTag("page-Connect").assertIsDisplayed()
        compose.onNodeWithTag("login-screen").assertDoesNotExist()
        compose.onNodeWithTag("brand-name").assertTextEquals("飞连vpn")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.gccwebos.android.debug", context.packageName)
        assertEquals("飞连vpn", context.packageManager.getApplicationLabel(context.applicationInfo).toString())
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

    @Test fun signedOutLegacyNoneNeverBecomesConfirmedPlanAndLoginRequiresExplicitChoice() {
        launchShell(allowLoginChallenge = true) { authenticated = false; resource = Resource.None }
        compose.onNodeWithTag("page-Connect").assertIsDisplayed()
        compose.onNodeWithTag("nav-Connect").assertIsDisplayed()
        compose.onNodeWithTag("login-screen").assertDoesNotExist()
        // An unowned legacy None must never become a confirmed no-plan result.
        compose.onNodeWithTag("vpn-connect").performScrollTo().assertTextEquals("开通飞连vpn").assertIsEnabled()
        compose.onNodeWithTag("account-usage-no-plan").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-remaining").assertTextEquals("暂未获取")
        compose.onNodeWithTag("no-resource-guidance").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, loginChallengeRequests.get()) }
        compose.onNodeWithTag("vpn-connect").performClick()
        compose.onNodeWithTag("usage-guest-guidance").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, loginChallengeRequests.get()) }
        expectedLoginChallengeRequests = 1
        compose.onNodeWithTag("usage-guest-login").performClick()
        compose.onNodeWithTag("login-screen").assertIsDisplayed()
        compose.onNodeWithTag("login-account").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, loginChallengeRequests.get()) }
        compose.onNodeWithTag("no-resource-guidance").assertDoesNotExist()
        compose.onNodeWithTag("nav-Connect").performClick()
        compose.onNodeWithTag("local-subscription-title").assertDoesNotExist()
        compose.onNodeWithTag("vpn-connect").performScrollTo().assertTextEquals("开通飞连vpn").assertIsEnabled()
        compose.onNodeWithTag("account-usage-no-plan").assertDoesNotExist()
        compose.onNodeWithTag("no-resource-guidance").assertDoesNotExist()
    }

    @Test fun verifiedNoneKeepsInlineOpeningAcrossRefreshAndNavigationWithoutAutomaticModal() {
        launchVerifiedOverviewShell()
        compose.onNodeWithTag("no-resource-guidance").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-no-plan").assertTextEquals("暂无生效套餐")
        compose.onNodeWithTag("vpn-connect").performScrollTo().assertTextEquals("开通飞连vpn").assertIsEnabled()
        capture("none-inline-opening")
        repeat(2) {
            listOf("Lines", "Plans", "Profile", "Connect").forEach {
                compose.onNodeWithTag("nav-$it").performClick()
                compose.onNodeWithTag("page-$it").assertIsDisplayed()
                compose.onNodeWithTag("no-resource-guidance").assertDoesNotExist()
            }
            compose.runOnIdle { account.refreshAccountUsage() }
            compose.waitUntil(5_000) { !account.accountUsage.state.value.busy }
            compose.onNodeWithTag("no-resource-guidance").assertDoesNotExist()
            compose.onNodeWithTag("vpn-connect").performScrollTo().assertTextEquals("开通飞连vpn").assertIsEnabled()
        }
        // Real same-session revalidation must not automatically open business guidance.
        compose.runOnIdle { account.onForeground() }
        compose.waitUntil(5_000) { !account.busy && !account.foregroundCheckPending && account.authenticated }
        compose.onNodeWithTag("no-resource-guidance").assertDoesNotExist()
        compose.onNodeWithTag("vpn-connect").performScrollTo().assertTextEquals("开通飞连vpn").assertIsEnabled()
    }

    @Test fun verifiedNoneInlineOpeningRoutesToPlansAndWalletStaysAnExplicitNavigation() {
        launchVerifiedOverviewShell()
        compose.onNodeWithTag("vpn-connect").performScrollTo().assertTextEquals("开通飞连vpn").performClick()
        compose.onNodeWithTag("page-Plans").assertIsDisplayed()
        compose.onNodeWithTag("nav-Connect").performClick()
        compose.onNodeWithTag("no-resource-guidance").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-no-plan").assertTextEquals("暂无生效套餐")
        compose.onNodeWithTag("nav-Profile").performClick()
        compose.onNodeWithTag("page-Profile").assertIsDisplayed()
        compose.onNodeWithTag("wallet-card").assertExists()
        compose.onNodeWithTag("nav-Connect").performClick()
        compose.onNodeWithTag("no-resource-guidance").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, verifiedOverviewReads.get()) }
    }

    @Test fun oneClickAfterOverviewFailureOnlyRefreshesTheReadWithoutStartingVpnOrOpeningAPlan() {
        val powerActions = mutableListOf<ConnectionPowerAction>()
        launchVerifiedOverviewShell(onPowerAction = { powerActions += it })
        compose.runOnIdle { verifiedOverviewFailure = PortalFailure.Reason.NETWORK; account.refreshAccountUsage() }
        compose.waitUntil(5_000) { !account.accountUsage.state.value.busy && account.accountUsage.state.value.error != null }
        compose.onNodeWithTag("account-usage-read-status").assertDoesNotExist()
        compose.onNodeWithTag("connection-read-error").performScrollTo().assertTextEquals("账户信息暂未更新")
        compose.onNodeWithTag("account-usage-no-plan").assertDoesNotExist()
        compose.onNodeWithTag("vpn-connect").performScrollTo().assertTextEquals("一键连接").assertIsEnabled()
        compose.onNodeWithTag("no-resource-guidance").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(3, verifiedOverviewReads.get()); assertTrue(powerActions.isEmpty())
            verifiedOverviewFailure = null
        }
        compose.onNodeWithTag("vpn-connect").performClick()
        compose.waitUntil(5_000) { !account.accountUsage.state.value.busy && account.accountUsage.state.value.error == null }
        compose.onNodeWithTag("account-usage-no-plan").assertTextEquals("暂无生效套餐")
        compose.onNodeWithTag("vpn-connect").assertTextEquals("开通飞连vpn")
        compose.onNodeWithTag("page-Connect").assertExists()
        compose.onNodeWithTag("connection-status").assertTextEquals("未连接")
        compose.runOnIdle { assertEquals(4, verifiedOverviewReads.get()); assertTrue(powerActions.isEmpty()) }
    }

    @Test fun plansShowServerCatalogWithoutRepeatingResourceAndKeepLocalExpirySeparate() {
        val fixtures = offers()
        launchShell(catalog = fixtures) { resource = available() }
        compose.onNodeWithTag("no-resource-guidance").assertDoesNotExist()
        compose.onNodeWithTag("nav-Plans").performClick()
        compose.onNodeWithTag("resource-card").assertDoesNotExist()
        compose.onNodeWithTag("resource-countdown").assertDoesNotExist()
        compose.onNodeWithTag("plan-title-${fixtures.first().offerRef}", useUnmergedTree = true)
            .performScrollTo().assertTextEquals("30天")
        compose.onNodeWithTag("plan-traffic-${fixtures.first().offerRef}", useUnmergedTree = true)
            .assertTextEquals("10 GB")
        compose.onNodeWithTag("plan-price-${fixtures.first().offerRef}", useUnmergedTree = true)
            .assertTextEquals("20 Coins")
        capture("plans-with-active-resource")
        compose.runOnIdle { account.resource = available().copy(validUntil = Instant.EPOCH) }
        compose.onNodeWithTag("nav-Connect").performClick()
        // Portal resource expiry cannot establish the separate local configuration's validity.
        compose.onNodeWithTag("local-subscription-title").assertDoesNotExist()
        compose.onNodeWithTag("expired-subscription-renew").assertDoesNotExist()
        compose.onNodeWithTag("portal-expired-renew").assertDoesNotExist()
        compose.runOnIdle {
            account.localSubscription = LocalSubscriptionSnapshot(LocalSubscriptionStatus.EXPIRED,
                hasProfile = true, hasCapability = true, validUntil = Instant.EPOCH)
        }
        compose.onNodeWithTag("local-subscription-title").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(LocalSubscriptionStatus.EXPIRED, account.localSubscription.status)
            assertEquals(Instant.EPOCH, account.localSubscription.validUntil)
        }
        compose.onNodeWithTag("expired-subscription-renew").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithTag("page-Plans").assertIsDisplayed()
        compose.onNodeWithTag("resource-card").assertDoesNotExist()
        compose.onNodeWithTag("plan-offer-${fixtures.first().offerRef}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("no-resource-guidance").assertDoesNotExist()
    }

    @Test fun resourceComponentRetainsDurationCountdownAndTruthfulUnknownStaleAndExpiredTraffic() {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val owner = remember { accountFixture(scope).apply { resource = available() } }
            account = owner
            GccTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                ResourceCard(owner)
            } }
        }
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
    }

    @Test fun largeFontGuidanceAndResourcesKeepActionsReachable() {
        val powerActions = mutableListOf<ConnectionPowerAction>()
        launchVerifiedOverviewShell(fontScale = 2f, onPowerAction = { powerActions += it })
        compose.onNodeWithTag("no-resource-guidance").assertDoesNotExist()
        compose.onNodeWithTag("vpn-connect").performScrollTo().assertIsDisplayed().assertTextEquals("开通飞连vpn").assertIsEnabled()
        capture("none-inline-font-200")
        compose.onNodeWithTag("vpn-connect").performClick()
        compose.onNodeWithTag("page-Plans").assertIsDisplayed()
        compose.onNodeWithTag("nav-Connect").performClick()
        compose.runOnIdle { assertEquals(2, verifiedOverviewReads.get()); assertTrue(powerActions.isEmpty()) }
        compose.runOnIdle { verifiedOverviewBody = verifiedAvailableBody(days = 365); account.refreshAccountUsage() }
        compose.waitUntil(5_000) { !account.accountUsage.state.value.busy && account.accountUsage.state.value.resource is Resource.Available }
        compose.onNodeWithTag("account-usage-plan").performScrollTo().assertTextEquals("365天套餐")
        compose.onNodeWithTag("account-usage-remaining").performScrollTo().assertTextEquals("3 GB")
        compose.onNodeWithTag("account-usage-cycle").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("account-usage-expiry").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("account-usage-refresh").assertDoesNotExist()
        // The one-click action can prepare MISSING local material. Merely rendering or returning
        // to this page must not prepare it or admit a VPN connection.
        compose.onNodeWithTag("vpn-connect").performScrollTo().assertIsDisplayed().assertTextEquals("一键连接").assertIsEnabled()
        compose.onNodeWithTag("account-usage-primary-hint").assertDoesNotExist()
        compose.onNodeWithTag("connection-status").assertTextEquals("未连接")
        compose.onNodeWithTag("connection-power").assertDoesNotExist()
        compose.onNodeWithTag("connection-prepare-cancel").assertDoesNotExist()
        capture("usage-available-font-200")
        compose.onNodeWithTag("nav-Plans").performClick()
        compose.onNodeWithTag("page-Plans").assertIsDisplayed()
        compose.onNodeWithTag("nav-Connect").performClick()
        compose.onNodeWithTag("vpn-connect").performScrollTo().assertTextEquals("一键连接").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(3, verifiedOverviewReads.get())
            assertEquals(LocalSubscriptionStatus.MISSING, account.localSubscription.status)
            assertFalse(account.localSubscription.hasProfile)
            assertFalse(account.localSubscription.hasCapability)
            assertTrue(powerActions.isEmpty())
            assertEquals(ConnectionPowerAction.None, connectionOverviewPresentation(account.localSubscription,
                Instant.now(), ConnectionControlState(ConnectionPhase.Disconnected, canConnect = true)).action)
        }
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
        compose.onNodeWithTag("wallet-balance").performScrollTo().assertTextEquals("— Coins")
        compose.runOnIdle { commerce.error = "离线余额读取失败" }
        compose.onNodeWithTag("wallet-error").performScrollTo().assertTextEquals("离线余额读取失败")
        compose.onNodeWithTag("wallet-balance").performScrollTo().assertTextEquals("— Coins")
        capture("wallet-error")
        compose.runOnIdle {
            commerce.error = null
            commerce.wallet = Wallet("active", 1, 12, 5, 3, 20, 3, 4)
        }
        compose.onNodeWithTag("wallet-balance").performScrollTo().assertTextEquals("20 Coins")
        compose.onNodeWithTag("wallet-composition").assertTextEquals("含 已购 12 · 赠送 5 · 奖励 3")
        compose.onNodeWithTag("wallet-held").assertDoesNotExist()
        compose.onNodeWithTag("wallet-balance-details").performScrollTo().performClick()
        compose.onNodeWithTag("wallet-detail-total").assertTextEquals("20 Coins")
        compose.onNodeWithTag("wallet-paid").assertTextEquals("12 Coins")
        compose.onNodeWithTag("wallet-bonus").assertTextEquals("5 Coins")
        compose.onNodeWithTag("wallet-reward").assertTextEquals("3 Coins")
        compose.onNodeWithTag("wallet-held").assertTextEquals("3 Coins")
        compose.onNodeWithTag("wallet-pending").assertTextEquals("4 Coins")
        capture("wallet-balance-details-fixture")
        compose.onNodeWithTag("wallet-balance-details-close").performClick()
        capture("wallet-confirmed-fixture")
        compose.runOnIdle { account.authenticated = false }
        compose.onNodeWithTag("commerce-signed-out").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("wallet-balance").assertDoesNotExist()
    }

    @Test fun serverOffersKeepExactDurationPriceAndTrafficWithinMonthAndYearFiltersAtLargeFont() {
        val fixtures = offers()
        launchCommerce(CommerceMode.Plans, fontScale = 2f) { offers = fixtures; catalogLoaded = true; commerceModel = CommerceModel.LEGACY }
        compose.onNodeWithTag("catalog-other").assertDoesNotExist()
        fixtures.forEach { offer ->
            compose.onNodeWithTag(if (offer.durationDays == 365L) "catalog-yearly" else "catalog-plans")
                .performScrollTo().performClick()
            compose.onNodeWithTag("plan-title-${offer.offerRef}", useUnmergedTree = true).performScrollTo()
                .assertTextEquals("${offer.durationDays}天")
            compose.onNodeWithTag("plan-price-${offer.offerRef}", useUnmergedTree = true).performScrollTo().assertTextEquals(formatCoins(offer.baseCoinUnits))
            compose.onNodeWithTag("plan-traffic-${offer.offerRef}", useUnmergedTree = true)
                .performScrollTo().assertTextEquals("${offer.monthlyTrafficGb} GB")
            compose.onNodeWithTag("plan-offer-${offer.offerRef}").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithTag("plan-continue").performScrollTo().assertIsDisplayed().assertIsEnabled()
        }
        capture("offers-font-200")
    }

    @Test fun periodAndCategoryChangesKeepNonstandardOffersAndRejectOldSelectionsAndLateQuotes() {
        val monthly = offers().first()
        val yearly = offers().last()
        val other = offer("offline_47_days", 47, 17, 73)
        val secondOther = offer("offline_83_days", 83, 29, 91)
        val pack = monthly.copy(offerRef = "offline_data_pack", offerVersionRef = "offline_data_pack_v1",
            category = "data_pack", durationDays = null, monthlyTrafficGb = null,
            discountBasisPoints = null, trafficGb = 23)
        launchCommerce(CommerceMode.Plans) {
            offers = listOf(monthly, yearly, other, secondOther, pack)
            catalogLoaded = true; commerceModel = CommerceModel.LEGACY
        }
        compose.onNodeWithTag("catalog-plans").assertIsSelected()
        compose.onNodeWithTag("plan-offer-${monthly.offerRef}").assertExists()
        listOf(yearly, other, secondOther, pack).forEach {
            compose.onNodeWithTag("plan-offer-${it.offerRef}").assertDoesNotExist()
        }
        compose.onNodeWithTag("catalog-other").performScrollTo().performClick()
        listOf(other, secondOther).forEach {
            compose.onNodeWithTag("plan-title-${it.offerRef}", useUnmergedTree = true)
                .performScrollTo().assertTextEquals("${it.durationDays}天")
        }
        compose.onNodeWithTag("plan-offer-${monthly.offerRef}").assertDoesNotExist()
        compose.onNodeWithTag("plan-offer-${yearly.offerRef}").assertDoesNotExist()
        compose.onNodeWithTag("plan-offer-${other.offerRef}").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("catalog-yearly").performScrollTo().performClick()
        compose.onNodeWithTag("plan-offer-${yearly.offerRef}").assertIsNotSelected()
        compose.onNodeWithTag("plan-continue").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { commerce.quote = quote(other) }
        compose.onNodeWithTag("purchase-confirm").assertDoesNotExist()
        compose.runOnIdle { assertNull(commerce.quote) }
        compose.onNodeWithTag("plan-offer-${yearly.offerRef}").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("catalog-data-packs").performScrollTo().performClick()
        compose.onNodeWithTag("plan-offer-${yearly.offerRef}").assertDoesNotExist()
        compose.onNodeWithTag("plan-offer-${pack.offerRef}").assertIsNotSelected()
        compose.onNodeWithTag("plan-traffic-${pack.offerRef}", useUnmergedTree = true).assertTextEquals("23 GB")
        compose.runOnIdle { commerce.quote = quote(yearly) }
        compose.onNodeWithTag("purchase-confirm").assertDoesNotExist()
        compose.onNodeWithTag("plan-continue").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertNull(commerce.quote); assertNull(commerce.purchase); assertFalse(commerce.pendingPurchase) }
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

    @Test fun catalogAutoLoadWaitsOnceForRestorationAndBusyWithoutLoop() {
        val paths = mutableListOf<String>()
        val walletPath = "/api/portal/coins/wallet"
        val modelPath = "/api/portal/commerce/model"
        val catalogPath = "/api/portal/commerce/deferred/offers"
        val walletReads = AtomicInteger()
        val modelReads = AtomicInteger()
        val initialBalance = CompletableDeferred<Unit>()
        val catalogModel = CompletableDeferred<Unit>()
        val laterBalance = CompletableDeferred<Unit>()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val owner = remember { accountFixture(scope).apply { restoring = true } }
            val state = remember {
                val recovery = object : CommerceRecovery {
                    override fun read(binding: String, kind: String): PendingCommerce? = null
                    override fun save(binding: String, value: PendingCommerce) = throw AssertionError("No order writes")
                    override fun clear(binding: String, kind: String) = throw AssertionError("No recovery clear")
                }
                val repository = CommerceRepository({ path, body, key ->
                    assertNull(body); assertNull(key); paths += path
                    if (path == walletPath) when (walletReads.incrementAndGet()) {
                        1 -> initialBalance.await()
                        2 -> laterBalance.await()
                        else -> throw AssertionError("Only explicit balance reads are allowed")
                    }
                    if (path == modelPath) {
                        assertEquals("Catalog reads must not loop", 1, modelReads.incrementAndGet())
                        catalogModel.await()
                    }
                    WireResponse(200, when (path) {
                        walletPath -> """{"wallet":{"status":"active","version":1,"balances":{"paidCoins":21,"bonusCoins":0,"rewardCoins":0},"spendableUnits":21,"heldUnits":0,"pendingUnits":0}}"""
                        catalogPath -> """{"offers":[]}"""
                        modelPath -> """{"commerceModel":"wallet_first_unique_v1"}"""
                        else -> throw AssertionError("Unexpected read: $path")
                    })
                }, recovery, "synthetic_catalog_account")
                CommerceController(owner, scope, recovery, initialRepository = repository)
            }
            account = owner; commerce = state
            GccTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                CommercePanel(owner, CommerceMode.Plans, stateOverride = state, autoLoad = true)
            } }
        }
        compose.onNodeWithTag("commerce-signed-out").assertDoesNotExist()
        listOf("wallet-card", "plan-options", "catalog-empty", "purchase-result", "purchase-confirm")
            .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.runOnIdle {
            assertTrue(account.restoring)
            assertFalse(commerce.canOperate())
            assertTrue(paths.isEmpty())
            assertFalse(commerce.busy)
            // Admit a real, deferred read before the eligible catalog effect recomposes.
            // Its busy slot belongs to current-session work, not revoked restoration work.
            account.restoring = false
            commerce.loadBalance()
        }
        compose.onNodeWithTag("catalog-loading").assertDoesNotExist()
        compose.onNodeWithTag("commerce-refresh").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(walletPath), paths)
            assertFalse(commerce.catalogLoaded)
            initialBalance.complete(Unit)
        }
        compose.waitUntil(5_000) { modelReads.get() == 1 }
        compose.onNodeWithTag("catalog-loading").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(walletPath, modelPath), paths)
            assertEquals(1, walletReads.get()); assertTrue(commerce.busy)
            assertFalse(commerce.catalogLoaded)
            catalogModel.complete(Unit)
        }
        compose.waitUntil(5_000) { commerce.catalogLoaded && !commerce.busy }
        compose.waitForIdle()
        compose.onNodeWithTag("catalog-status").assertDoesNotExist()
        compose.onNodeWithTag("catalog-empty").assertTextEquals("暂无可购套餐")
        // Completing another explicit balance read must not restart the loaded catalog.
        compose.runOnIdle { commerce.loadBalance() }
        compose.onNodeWithTag("catalog-loading").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(commerce.catalogLoaded)
            assertEquals(2, walletReads.get())
            laterBalance.complete(Unit)
        }
        compose.waitUntil(5_000) { !commerce.busy }
        compose.onNodeWithTag("catalog-status").assertDoesNotExist()
        compose.onNodeWithTag("catalog-empty").assertTextEquals("暂无可购套餐")
        compose.runOnIdle {
            assertEquals(listOf(walletPath, modelPath, catalogPath, walletPath), paths)
            assertEquals(1, modelReads.get())
            assertNull(commerce.quote); assertNull(commerce.purchase); assertNull(commerce.recharge)
            assertFalse(commerce.pendingPurchase); assertFalse(commerce.pendingRecharge)
        }
    }

    @Test fun catalogStatesKeepServerDurationCoinsAndTrafficOnCardsAndInDetails() {
        val fixture = offers().first().copy(durationDays = 47, baseCoinUnits = 73, monthlyTrafficGb = 17)
        launchCommerce(CommerceMode.Plans) { error = "合成目录读取失败" }
        compose.onNodeWithTag("catalog-status").assertDoesNotExist()
        compose.onNodeWithTag("commerce-refresh").assertDoesNotExist()
        compose.onNodeWithTag("commerce-error").assertTextEquals("合成目录读取失败")
        compose.onNodeWithTag("plan-offer-${fixture.offerRef}").assertDoesNotExist()
        compose.runOnIdle {
            commerce.error = null; commerce.catalogLoaded = true; commerce.commerceModel = CommerceModel.LEGACY
            commerce.offers = listOf(fixture)
        }
        compose.onNodeWithTag("plan-title-${fixture.offerRef}", useUnmergedTree = true).performScrollTo().assertTextEquals("47天")
        compose.onNodeWithTag("plan-price-${fixture.offerRef}", useUnmergedTree = true).assertTextEquals("73 Coins")
        compose.onNodeWithTag("catalog-other").assertIsSelected()
        compose.onNodeWithTag("plan-traffic-${fixture.offerRef}", useUnmergedTree = true).assertTextEquals("17 GB")
        compose.onNodeWithTag("plan-offer-${fixture.offerRef}").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("plan-benefits").performScrollTo().assertIsDisplayed()
        // Benefits never repeat the card's validity or traffic.
        compose.onNodeWithText("每月流量").assertDoesNotExist()
        compose.onNodeWithText("有效期").assertDoesNotExist()
        compose.onNodeWithText("安全加密", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { commerce.busy = true }
        compose.onNodeWithTag("plan-continue").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("catalog-plans").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("catalog-yearly").assertIsNotEnabled()
        compose.onNodeWithTag("catalog-other").assertIsNotEnabled()
        compose.onNodeWithTag("catalog-data-packs").assertIsNotEnabled()
    }

    @Test fun insufficientDeferredQuoteKeepsConfirmationDisabledAndCancelsLocally() {
        val balance = CoinBalance(0, 5, 0, 0)
        val fixture = quote().copy(purchaseAvailability = "insufficient_balance", shortfallUnits = 15,
            allocation = emptyList(), balances = QuoteBalances(balance, balance),
            transition = QuoteTransition("initial_activation", future))
        launchCommerce(CommerceMode.Plans) { quote = fixture; commerceModel = CommerceModel.WALLET_FIRST }
        repeat(2) {
            compose.onNodeWithTag("purchase-confirm").assertIsNotEnabled()
            compose.onNodeWithText("余额不足，尚缺 15 Coins。请前往我的充值，再重新获取报价。")
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("purchase-cancel").performClick()
            compose.runOnIdle { assertNull(commerce.quote); assertNull(commerce.purchase) }
            if (it == 0) compose.runOnIdle { commerce.quote = fixture }
        }
    }

    @Test fun accountGenerationSwitchClosesOldOrderCancellationDialog() {
        val fixture = offers().first()
        launchCommerce(CommerceMode.Plans) {
            pendingPurchase = true
            purchase = PurchaseResult.Order("offline_order_${account.authSessionGeneration}", fixture,
                fixture.baseCoinUnits, listOf(CoinAllocation("paid_coins", fixture.baseCoinUnits)),
                "held", "not_requested", null, future, collected, collected)
        }
        compose.onNodeWithText("取消此套餐订单").performScrollTo().performClick()
        compose.onNodeWithTag("purchase-order-cancel-dialog").assertExists()
        compose.runOnIdle { account.authSessionGeneration++ }
        compose.onNodeWithTag("purchase-order-cancel-dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2L, account.authSessionGeneration); assertTrue(commerce.currentSession()) }
        compose.onNodeWithText("取消此套餐订单").performScrollTo().assertIsDisplayed()
    }

    @Test fun rechargeDialogCancelsWithoutCreatingAnOrderAtLargeFont() {
        val fixture = rechargeOffer()
        launchCommerce(CommerceMode.Wallet, fontScale = 2f) { rechargeOffers = listOf(fixture) }
        compose.onNodeWithTag("recharge-select-${fixture.offerRef}").performScrollTo().performClick()
        compose.onNodeWithTag("recharge-selected-pay").performScrollTo().performClick()
        compose.onNodeWithTag("recharge-confirm").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("recharge-cancel").assertIsDisplayed()
        capture("recharge-confirm-font-200")
        compose.onNodeWithTag("recharge-cancel").performClick()
        compose.onNodeWithTag("recharge-confirm").assertDoesNotExist()
        compose.runOnIdle { assertNull(commerce.recharge); assertFalse(commerce.pendingRecharge) }
    }

    @Test fun pendingAndVerifiedRechargeStayOffTheRechargeScreenAndOfferNoPaymentThere() {
        // The unpaid order lives in 订单明细; the recharge screen has no 上一笔充值 card and no Pay action.
        launchCommerce(CommerceMode.Wallet) {
            rechargeOffers = listOf(rechargeOffer())
            pendingRecharge = true
            recharge = rechargeOrder("pending")
        }
        compose.onNodeWithTag("recharge-order").assertDoesNotExist()
        compose.onNodeWithTag("recharge-pay").assertDoesNotExist()
        compose.runOnIdle {
            commerce.recharge = rechargeOrder("payment_verified", PaymentRedirect("https://example.invalid/do-not-open", future))
        }
        compose.onNodeWithTag("recharge-order").assertDoesNotExist()
        compose.onNodeWithTag("recharge-pay").assertDoesNotExist()
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

    @Test fun unconfirmedPurchaseHidesNewQuoteActions() {
        val fixtures = offers()
        launchCommerce(CommerceMode.Plans) { offers = fixtures; pendingPurchase = true }
        compose.onNodeWithTag("purchase-result").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("上次提交结果待核对，不要重复购买。").assertExists()
        compose.onNodeWithTag("plan-continue").assertDoesNotExist()
        fixtures.forEach { compose.onNodeWithTag("plan-offer-${it.offerRef}").assertDoesNotExist() }
        compose.onNodeWithTag("purchase-confirm").assertDoesNotExist()
    }
}
