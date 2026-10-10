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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import com.gccwebos.android.design.BrandBackdrop
import com.gccwebos.android.design.GccTheme
import kotlinx.coroutines.CompletableDeferred

/** 我的 round 3 synthetic screenshots. Synthetic in-memory UI evidence only. No production account, disk store, purchase or engine. */
class MyPageR3EvidenceTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var account: AccountController
    private lateinit var commerce: CommerceController
    private val overviewReads = AtomicInteger()
    private val forbiddenActions = AtomicInteger()
    private var densityScale = 1f
    private val expiry = Instant.parse("2099-09-30T00:00:00Z")
    private var seedLocalChoices: (() -> Unit)? = null

    private val totpConfirms = AtomicInteger()
    @Volatile private var totpBound = false
    private val totpEnrollment = """{"enrollment":{"enrollmentRef":"totp_enroll_s1","issuer":"GlobalCircle","accountLabel":"synthetic",
        "manualKey":"JBSWY3DPEHPK3PXP","otpauthUri":"otpauth://totp/GlobalCircle:synthetic?secret=JBSWY3DPEHPK3PXP&issuer=GlobalCircle",
        "expiresAt":"2099-01-01T00:05:00.000Z","expiresInSeconds":300,"issuedAt":"2099-01-01T00:00:00.000Z",
        "safety":{"noAdminApi":true,"noPublicAdmin":true,"noDbMutationBeforeConfirm":true}}}"""
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
                // Synthetic TOTP enrollment only (the public RFC example key); nothing here touches a real account.
                if (path == "/api/portal/security/totp/enrollment") return WireResponse(201, totpEnrollment)
                if (path == "/api/portal/security/totp/enrollment/confirm") {
                    totpConfirms.incrementAndGet()
                    return if (org.json.JSONObject(requireNotNull(body)).getString("code") == "123456")
                        WireResponse(200, """{"binding":{"status":"bound","method":"totp","boundAt":"2026-09-30T00:01:00.000Z"}}""")
                    else WireResponse(400, """{"error":"validation_error"}""")
                }
                if (path == "/api/portal/security/current") return WireResponse(200,
                    """{"security":{"authenticatorBound":${totpConfirms.get() > 0 && totpBound},"initialAccount":false,"passkeyEnabled":false,"passkeyExported":false,"setupRequired":false,"safety":{"noAdminApi":true,"noPublicAdmin":true,"noRawSecretReturned":true}}}""")
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
                    ledgerPage = syntheticLedger()
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
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "ui-evidence/my-page-r3")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use { stream ->
            val node = if (dialog) compose.onNode(isDialog()) else compose.onRoot()
            assertTrue(node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
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

    private fun syntheticLedger(): WalletLedgerPage {
        val base = Instant.now().minusSeconds(3_600)
        fun e(ref: String, cat: String, coin: String, amount: Long, total: Long, a: Long?, p: Long?, h: Long?, order: String?, ago: Long) =
            WalletLedgerEntry(ref, cat, coin, amount, a, p, h, total, order, base.minusSeconds(ago))
        return WalletLedgerPage(listOf(
            e("entry_s1", "held_to_consumed", "paid_coins", 30, -30, 0, 0, -30, "order_s1", 0),
            e("entry_s2", "available_to_held", "paid_coins", 30, 0, -30, 0, 30, "order_s1", 5),
            e("entry_s3", "credit", "paid_coins", 100, 100, null, null, null, "recharge_s1", 86_400),
            e("entry_s4", "credit", "bonus_coins", 20, 20, null, null, null, "recharge_s1", 86_405),
            e("entry_s5", "credit", "reward_coins", 5, 5, null, null, null, null, 4 * 86_400),
            e("entry_s6", "available_to_expired", "bonus_coins", 8, -8, -8, 0, 0, null, 40 * 86_400),
            e("entry_s7", "credit", "paid_coins", 300, 300, null, null, null, "recharge_s0", 400 * 86_400),
        ), null)
    }

    @Test fun myPageSubpagesAfterRound3() {
        launch(390)
        open("Profile")
        compose.onNodeWithTag("mine-topbar").assertDoesNotExist()
        compose.onNodeWithTag("mine-topbar-title").assertDoesNotExist()
        compose.onNodeWithTag("mine-copy-id").assertExists().assertHasClickAction()
        capture("01-mine-signed-in")
        compose.onNodeWithTag("mine-copy-id").performClick()
        compose.onNodeWithTag("mine-copy-id-done", useUnmergedTree = true).assertExists()
        capture("02-mine-id-copied-check")
        // 订单明细: one flat list, no tabs.
        compose.onNodeWithTag("mine-history").performScrollTo().performClick()
        compose.onNodeWithTag("mine-history-tabs").assertDoesNotExist()
        compose.onNodeWithTag("wallet-topbar-title").assertTextEquals("订单明细")
        compose.onNodeWithTag("ledger-container").assertExists()
        capture("03-order-detail-flat-ledger")
        // The shimmer is an infinite animation: freeze the test clock so idle detection and capture still work.
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { commerce.ledgerPage = null; commerce.busy = true }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("ledger-skeleton").assertExists()
        capture("04-order-detail-loading-skeleton")
        compose.runOnUiThread { commerce.busy = false; commerce.ledgerPage = syntheticLedger() }
        compose.mainClock.advanceTimeBy(400)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("mine-back").performClick()
        // 账户与安全: exactly two rows.
        compose.runOnIdle { account.security = AccountSecurity(authenticatorBound = true, initialAccount = false,
            passkeyEnabled = false, passkeyExported = false, setupRequired = false) }
        compose.onNodeWithTag("mine-security").performScrollTo().performClick()
        compose.onNodeWithTag("security-passkey").assertExists()
        compose.onNodeWithTag("security-authenticator").assertExists()
        compose.onNodeWithTag("resource-card").assertDoesNotExist()
        compose.onNodeWithTag("account-panel").assertDoesNotExist()
        compose.onNodeWithTag("security-passkey-status", useUnmergedTree = true).assertTextEquals("未启用")
        compose.onNodeWithTag("security-authenticator-status", useUnmergedTree = true).assertTextEquals("已绑定")
        capture("05-account-security-two-functions")
        assertNoActions()
    }

    @Test fun authenticatorBindingFlow() {
        launch(390)
        open("Profile")
        compose.runOnIdle { account.security = AccountSecurity(authenticatorBound = false, initialAccount = false,
            passkeyEnabled = false, passkeyExported = false, setupRequired = false) }
        compose.onNodeWithTag("mine-security").performScrollTo().performClick()
        compose.onNodeWithTag("security-authenticator-status", useUnmergedTree = true).assertTextEquals("未绑定")
        compose.onNodeWithTag("security-authenticator-action", useUnmergedTree = true).assertExists()
        capture("08-security-authenticator-unbound")
        compose.onNodeWithTag("security-authenticator").performClick()
        compose.waitUntil(5_000) { account.totpSetup != null }
        compose.onNodeWithTag("totp-setup").assertExists()
        compose.onNodeWithTag("totp-qr").assertExists()
        compose.onNodeWithTag("totp-manual-key", useUnmergedTree = true).assertTextEquals("JBSW Y3DP EHPK 3PXP")
        compose.onNodeWithTag("totp-setup-confirm").assertIsNotEnabled()
        capture("09-authenticator-setup-secret-and-qr")
        compose.onNodeWithTag("totp-setup-code").performTextInput("000000")
        compose.onNodeWithTag("totp-setup-confirm").performClick()
        compose.waitUntil(5_000) { account.totpError != null }
        compose.onNodeWithTag("totp-setup-error").assertExists()
        capture("10-authenticator-wrong-code")
        compose.onNodeWithTag("totp-setup-code").performTextInput("123456")
        totpBound = true
        compose.onNodeWithTag("totp-setup-confirm").performClick()
        compose.waitUntil(5_000) { account.totpSetup == null && account.security?.authenticatorBound == true }
        compose.onNodeWithTag("security-authenticator-status", useUnmergedTree = true).assertTextEquals("已绑定")
        compose.onNodeWithTag("security-authenticator-action", useUnmergedTree = true).assertDoesNotExist()
        capture("11-authenticator-bound")
    }

    @Test fun shareLinkPageAfterRound3() {
        val path = "/r/" + "b".repeat(32)
        val profile = """{"profile":{"eligible":true,"linkPath":"$path","policy":{"policyRef":"referral_ui_policy","version":3,"effectiveAt":"2026-09-30T00:00:00.000Z","rateBps":735,"rounding":"floor","attributionDays":47,"confirmationDays":9},"attributionDays":47}}"""
        val performance = """{"performance":{"registrations":4,"firstRechargeConversions":1,"creditedFirstRechargeCoins":17,"pendingRewardCoins":3,"availableRewardCoins":5,"reversedRewardCoins":2,"conversionRate":0.25,"reconciliationRequired":false}}"""
        lateinit var referral: ReferralController
        compose.setContent {
            val scope = rememberCoroutineScope()
            val owner = remember { AccountController(PortalRepository(
                object : PortalTransport { override suspend fun request(path: String, body: String?, session: Secret?): WireResponse = forbidden() },
                object : DeliveryStore { override fun read(): DeliveryRecord? = null; override fun save(record: DeliveryRecord) = forbidden(); override fun clear() = Unit },
                OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L },
                subscription = SubscriptionSource { forbidden() }, sessions = MemorySessionStore(), profiles = MemoryLocalProfileStore()), scope)
                .apply { authenticated = true; account = "opaque_ui_account_fixture"; resource = Resource.None } }
            val state = remember { ReferralController(owner, scope, { }, ReferralRepository { route, body ->
                WireResponse(200, if (route == ReferralJson.PROFILE) profile else performance)
            }) }
            referral = state
            GccTheme {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    BrandBackdrop(Modifier.fillMaxSize())
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        ReferralPanel(owner, autoLoad = true, stateOverride = state, copyOverride = { })
                    }
                }
            }
        }
        // Skeletons shimmer forever while the read is in flight: step the clock by hand until it lands.
        compose.mainClock.autoAdvance = false
        var guard = 0
        while (!(!referral.busy && referral.performance != null && referral.profile != null) && guard++ < 200) {
            compose.mainClock.advanceTimeBy(50)
        }
        assertTrue("referral fixture loaded", guard < 200)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("referral-link-title").assertTextEquals("我的分享链接")
        capture("06-share-link-top")
        compose.onNodeWithTag("referral-performance").performScrollTo()
        capture("07-share-link-results")
        assertEquals(0, forbiddenActions.get())
    }
}
