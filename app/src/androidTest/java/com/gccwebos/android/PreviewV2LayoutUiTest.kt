package com.gccwebos.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.gccwebos.android.commerce.*
import com.gccwebos.android.portal.*
import kotlinx.coroutines.CoroutineScope
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic in-memory UI evidence only. No production account, disk store, purchase or engine. */
class PreviewV2LayoutUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var account: AccountController
    private lateinit var commerce: CommerceController
    private val overviewReads = AtomicInteger()
    private val forbiddenActions = AtomicInteger()
    private var densityScale = 1f
    private val expiry = Instant.parse("2099-09-30T00:00:00Z")
    private var seedLocalChoices: (() -> Unit)? = null

    private fun forbidden(): Nothing {
        forbiddenActions.incrementAndGet()
        throw AssertionError("Preview fixture forbids network, external actions and persistent writes")
    }

    private val recovery = object : CommerceRecovery {
        override fun read(binding: String, kind: String): PendingCommerce? = forbidden()
        override fun save(binding: String, value: PendingCommerce): Unit = forbidden()
        override fun clear(binding: String, kind: String): Unit = forbidden()
    }

    private fun offer(ref: String, days: Long, coins: Long, traffic: Long) = Offer(
        ref, "${ref}_v1", LocalizedText("合成 ${days} 天套餐", "Synthetic $days day plan"),
        LocalizedText("仅供离线界面验收", "Offline UI fixture only"),
        LocalizedNotices(listOf("合成商品，不进行购买"), listOf("Synthetic offer; no purchase")),
        coins, coins * 100, listOf("paid_coins", "bonus_coins", "reward_coins"), days, traffic, null,
    )

    private fun accountFixture(scope: CoroutineScope): AccountController {
        val now = Instant.now()
        // The real Portal schema requires exactly three UTC fractional digits, including .000.
        val wireTime = DateTimeFormatterBuilder().appendInstant(3).toFormatter()
        val token = Secret("synthetic_preview_v2_native_session")
        val sessions = MemorySessionStore().apply {
            save(SavedSession.capture(Session(token, "synthetic_preview_v2_account",
                now.plusSeconds(3_600), 3_600_000, 0), now, 0))
        }
        val overviewResponse = """{"resource":{"state":"available",
            "resourceRef":"resource_${"c".repeat(64)}",
            "plan":{"id":"monthly_30d_50gb","durationDays":30,"monthlyTrafficGb":50},
            "validUntil":"2099-09-30T00:00:00.000Z","setupAvailable":false,
            "usage":{"totalBytes":53687091200,"usedBytes":10737418240,"remainingBytes":42949672960,
            "collectionStatus":"measured","updatedAt":"${wireTime.format(now.minusSeconds(900))}",
            "cycleStartedAt":"${wireTime.format(now.minusSeconds(86400))}",
            "cycleEndsAt":"${wireTime.format(now.plusSeconds(2_505_600))}"}}}"""
        // Fail at fixture construction if the production wire parser rejects our synthetic data.
        val parsed = PortalJson.resource(overviewResponse)
        assertTrue(parsed is Resource.Available)
        assertEquals(30L, (parsed as Resource.Available).durationDays)
        val transport = object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                if (path != "/api/portal/resources/current/overview") forbidden()
                assertNull(body)
                assertEquals(token.value, requireNotNull(session).value)
                assertTrue("Only restore validation and its typed overview are allowed", overviewReads.incrementAndGet() <= 2)
                return WireResponse(200, overviewResponse)
            }
            override suspend fun commerce(path: String, body: String?, session: Secret, key: String?): WireResponse = forbidden()
        }
        val delivery = object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord): Unit = forbidden()
            override fun clear(): Unit = forbidden()
        }
        val profileUrl = Secret("https://sub.invalid/sub/a/synthetic-preview-v2")
        val bytes = """proxies:
  - name: 合成节点 Alpha
    type: ss
    server: 192.0.2.51
    port: 443
    cipher: aes-128-gcm
    password: synthetic-preview-only
  - name: 合成节点 Beta
    type: ss
    server: 192.0.2.52
    port: 443
    cipher: aes-128-gcm
    password: synthetic-preview-only
  - name: 合成节点 Gamma
    type: ss
    server: 192.0.2.53
    port: 443
    cipher: aes-128-gcm
    password: synthetic-preview-only
""".toByteArray()
        val profiles = MemoryLocalProfileStore()
        val repository = PortalRepository(transport, delivery,
            OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L }, { now },
            subscription = SubscriptionSource { forbidden() }, sessions = sessions, profiles = profiles)
        CachedSubscription(sessionBinding(profileUrl), bytes, expiry, profileUrl).use(profiles::save)
        val owner = AccountController(repository, scope)
        seedLocalChoices = {
            try {
                // Accepting authentication intentionally revokes old opaque choices. Seed this
                // synthetic in-memory observation after that boundary, without a subscription GET.
                owner.localSubscription = repository.localSubscriptions.remember(bytes, profileUrl, expiry)
                owner.nodes = owner.localSubscription.nodes
                owner.nodeSelection.sync()
            } finally { bytes.fill(0) }
        }
        return owner
    }

    private fun launch(widthDp: Int, fontScale: Float = 1f) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val owner = remember { accountFixture(scope) }
            account = owner
            LaunchedEffect(owner) { owner.restore() }
            val state = remember(owner, owner.authSessionGeneration) {
                CommerceController(owner, scope, recovery).apply {
                    wallet = Wallet("active", 1, 17, 9, 5, 31, 7, 11)
                    walletLoaded = true
                    offers = listOf(offer("synthetic_preview_month", 30, 30, 50),
                        offer("synthetic_preview_year", 365, 299, 80),
                        offer("synthetic_preview_other", 47, 73, 17))
                    catalogLoaded = true
                    commerceModel = CommerceModel.LEGACY
                    rechargeOffersLoaded = true
                    orderPage = OrderPage(emptyList())
                    rechargeHistory = emptyList()
                    ledgerPage = WalletLedgerPage(emptyList(), null)
                }
            }
            commerce = state
            // Render the entire physical viewport at the requested logical width, without cropping
            // a 390dp fixture on a narrower device. Font scaling remains independently controlled.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val actual = LocalDensity.current
                val previewDensity = actual.density * maxWidth.value / widthDp
                densityScale = previewDensity
                CompositionLocalProvider(LocalDensity provides Density(previewDensity, fontScale)) {
                    Box(Modifier.fillMaxSize().testTag("preview-v2-fixture")) {
                        AppShell(accountOverride = owner, commerceOverride = state, autoLoadCommerce = false,
                            commerceRecoveryOverride = recovery, registrationOpener = { forbidden() },
                            paymentOpenerOverride = { forbidden() }, onConnectionPowerAction = { forbidden() },
                            connectionControlState = ConnectionControlState(ConnectionPhase.Disconnected))
                    }
                }
            }
        }
        compose.waitUntil(10_000) {
            ::account.isInitialized && ::commerce.isInitialized && !account.busy && account.authenticated &&
                account.authenticatedAccountId == "synthetic_preview_v2_account" &&
                account.accountUsage.state.value.authenticated && account.accountUsage.state.value.resource is Resource.Available &&
                commerce.canOperate()
        }
        compose.runOnIdle { requireNotNull(seedLocalChoices).invoke(); seedLocalChoices = null }
        compose.waitUntil(5_000) { account.nodeSelection.snapshot.status == LocalSubscriptionStatus.READY &&
            account.nodeSelection.snapshot.choices.size == 3 }
        assertNoActions()
    }

    private fun assertNoActions() = compose.runOnIdle {
        assertEquals(2, overviewReads.get())
        assertEquals(0, forbiddenActions.get())
        assertNull(commerce.quote)
        assertNull(commerce.purchase)
        assertNull(commerce.recharge)
        assertFalse(commerce.pendingPurchase)
        assertFalse(commerce.pendingRecharge)
    }

    @After fun fixtureNeverLeavesSyntheticState() {
        assertEquals(0, forbiddenActions.get())
        if (::account.isInitialized) assertEquals(2, overviewReads.get())
    }

    private fun capture(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "ui-evidence/preview-v2")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use { stream ->
            val node = if (dialog) compose.onNode(isDialog()) else compose.onRoot()
            assertTrue(node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }

    private fun captureConfirmation(name: String, offerRef: String) {
        compose.runOnIdle {
            val offer = commerce.offers.single { it.offerRef == offerRef }
            commerce.quote = Quote("synthetic_preview_quote", offer, offer.baseCoinUnits, 0, "available", 0,
                listOf(CoinAllocation("paid_coins", offer.baseCoinUnits)),
                QuoteBalances(CoinBalance(0, 500, 0, 0), CoinBalance(0, 500 - offer.baseCoinUnits, 0, offer.baseCoinUnits)),
                Instant.now().plusSeconds(120), null)
        }
        compose.onNodeWithTag("purchase-confirm").assertIsDisplayed().assertIsEnabled()
        capture(name, dialog = true)
        compose.onNodeWithTag("purchase-cancel").assertIsDisplayed().performClick()
        assertNoActions()
    }

    private fun reachable(tag: String): SemanticsNodeInteraction {
        val node = compose.onNodeWithTag(tag)
        // AppShell owns the fixed purchase dock. Other actions belong to the scrolling page;
        // scroll even a partially visible button fully into view before measuring its touch area.
        if (tag != "plan-continue") node.performScrollTo()
        return node.assertIsDisplayed()
    }

    private fun open(destination: String) {
        compose.onNodeWithTag("nav-$destination").assertIsDisplayed().performClick().assertIsSelected()
        compose.onNodeWithTag("page-$destination").assertExists()
    }

    private fun fourPages(widthDp: Int) {
        launch(widthDp)
        val prefix = "${widthDp}dp-font100"
        compose.onNodeWithTag("connection-status").assert(hasAnyAncestor(hasTestTag("connection-hero")))
        compose.onNodeWithTag("connection-nodes").assert(hasAnyAncestor(hasTestTag("connection-hero")))
        compose.onNodeWithTag("vpn-connect").assert(hasAnyAncestor(hasTestTag("connection-hero")))
        compose.onNodeWithTag("account-usage-card").assert(hasAnyAncestor(hasTestTag("connection-account-surface")))
        compose.onNodeWithTag("resource-card").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-read-status").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-retry").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-primary-hint").assertDoesNotExist()
        compose.onNodeWithTag("subscription-validity-countdown").assertDoesNotExist()
        compose.onNodeWithText("Ping", substring = false).assertDoesNotExist()
        capture("$prefix-connect")

        open("Lines")
        compose.onNodeWithTag("nodes-selection-summary").assertExists()
        capture("$prefix-nodes")
        reachable("node-choice-1").performClick()
        compose.onNodeWithTag("page-Connect").assertExists()
        compose.onNodeWithTag("connection-node-name", useUnmergedTree = true).assertTextEquals("合成节点 Beta")
        open("Lines")
        compose.onNodeWithTag("node-choice-1").assertIsSelected()
        compose.onNodeWithTag("nodes-selected-name").assertTextEquals("合成节点 Beta")
        compose.onNodeWithTag("node-choice-0").assertIsNotSelected()
        assertNoActions()

        open("Plans")
        compose.onNodeWithText("选择你的套餐").assertDoesNotExist()
        compose.onNodeWithText("先看流量和有效期，再决定怎么用").assertDoesNotExist()
        compose.onNodeWithTag("resource-card").assertDoesNotExist()
        compose.onNodeWithTag("plan-purchase-dock").assertExists()
        compose.onNodeWithTag("plan-offer-synthetic_preview_month").assertIsSelected()
        compose.onNodeWithTag("plan-continue").assertTextEquals("立即购买")
        capture("$prefix-plans")
        captureConfirmation("$prefix-confirmation", "synthetic_preview_month")
        reachable("catalog-yearly").performClick()
        reachable("plan-offer-synthetic_preview_year").performClick().assertIsSelected()
        compose.onNodeWithTag("plan-title-synthetic_preview_year", useUnmergedTree = true).assertTextEquals("365天")
        reachable("plan-continue").assertIsEnabled() // Review is reachable, but never invoked.
        capture("$prefix-plans-yearly")
        reachable("catalog-other").performClick()
        reachable("plan-offer-synthetic_preview_other").performClick().assertIsSelected()
        compose.onNodeWithTag("plan-title-synthetic_preview_other", useUnmergedTree = true).assertTextEquals("47天")
        compose.onNodeWithTag("resource-card").assertDoesNotExist()

        open("Profile")
        compose.onNodeWithTag("plan-purchase-dock").assertDoesNotExist()
        compose.onNodeWithTag("mine-welcome").assertExists()
        compose.onNodeWithTag("mine-welcome-title").assertDoesNotExist() // Signed-in header: identity + plan capsule only.
        compose.onNodeWithTag("mine-welcome").assert(hasAnyAncestor(hasTestTag("mine-surface")))
        compose.onNodeWithTag("mine-account-identity").assertTextEquals("账户")
        compose.onNodeWithTag("mine-current-plan").assertTextEquals("30天套餐")
        compose.onNodeWithTag("mine-plan-expiry").assertTextContains("会员有效期至：2099.09.", substring = true)
        compose.onNodeWithTag("wallet-balance").assertTextEquals("31 Coins")
        compose.onNodeWithTag("wallet-paid").assertDoesNotExist()
        capture("$prefix-mine")
        reachable("mine-more").assert(hasAnyAncestor(hasTestTag("mine-functions")))
        capture("$prefix-mine-functions")
        reachable("mine-more").performClick()
        compose.onNodeWithTag("mine-about").assertExists()
        compose.onNodeWithTag("mine-more").assertDoesNotExist()
        compose.onNodeWithTag("mine-functions").assertDoesNotExist()
        compose.onNodeWithTag("checkUpdate").assertDoesNotExist() // Offline fixture never reads a manifest.
        reachable("mine-back").performClick()
        reachable("mine-wallet").performClick()
        compose.onNodeWithTag("wallet-topbar-title").assertTextEquals("钱包充值")
        reachable("wallet-balance-details").performClick()
        compose.onNodeWithTag("wallet-paid").assertTextEquals("17 Coins")
        compose.onNodeWithTag("wallet-held").assertTextEquals("7 Coins")
        compose.onNodeWithTag("wallet-pending").assertTextEquals("11 Coins")
        compose.onNodeWithTag("wallet-balance-details-close").performClick()
        compose.onNodeWithTag("mine-more").assertDoesNotExist()
        compose.onNodeWithTag("mine-about").assertDoesNotExist()
        assertNoActions()
    }

    @Test fun previewV2FourPageHierarchyAt320DpUsesOnlySyntheticState() = fourPages(320)

    @Test fun previewV2FourPageHierarchyAt390DpUsesOnlySyntheticState() = fourPages(390)

    @Test fun previewV2At320DpDoubledFontKeepsNavigationAndActionsReachable() {
        launch(320, fontScale = 2f)
        listOf("Connect", "Lines", "Plans", "Profile").forEach { destination ->
            val nav = compose.onNodeWithTag("nav-$destination").assertIsDisplayed()
            assertTrue(nav.fetchSemanticsNode().boundsInRoot.height / densityScale >= 48f)
        }
        capture("320dp-font200-connect")
        open("Lines")
        reachable("node-choice-1").performClick()
        compose.onNodeWithTag("page-Connect").assertExists()
        open("Lines")
        compose.onNodeWithTag("node-choice-1").assertIsSelected()
        capture("320dp-font200-nodes")
        open("Plans")
        reachable("catalog-yearly").performClick()
        reachable("plan-offer-synthetic_preview_year").performClick().assertIsSelected()
        val purchase = reachable("plan-continue").assertIsEnabled()
        assertTrue(purchase.fetchSemanticsNode().boundsInRoot.height / densityScale >= 48f)
        capture("320dp-font200-plans")
        captureConfirmation("320dp-font200-confirmation", "synthetic_preview_year")
        open("Profile")
        compose.onNodeWithTag("plan-purchase-dock").assertDoesNotExist()
        listOf("mine-recharge", "mine-ledger", "mine-more").forEach { tag ->
            val action = reachable(tag)
            assertTrue(action.fetchSemanticsNode().boundsInRoot.height / densityScale >= 48f)
        }
        compose.onNodeWithTag("mine-more").assert(hasAnyAncestor(hasTestTag("mine-functions")))
        capture("320dp-font200-mine-functions")
        reachable("mine-recharge").performClick()
        compose.onNodeWithTag("wallet-balance").assertExists()
        compose.onNodeWithTag("wallet-paid").assertDoesNotExist()
        val back = reachable("mine-back").assertIsDisplayed()
        assertTrue(back.fetchSemanticsNode().boundsInRoot.height / densityScale >= 48f)
        reachable("recharge-selected-pay").assertIsDisplayed()
        compose.onNodeWithTag("mine-more").assertDoesNotExist()
        reachable("mine-back").performClick()
        reachable("mine-ledger").performClick()
        compose.onNodeWithTag("mine-history-tabs").assertExists()
        compose.onNodeWithTag("mine-more").assertDoesNotExist()
        assertNoActions()
    }
}
