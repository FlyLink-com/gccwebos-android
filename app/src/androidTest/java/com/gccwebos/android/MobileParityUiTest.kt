package com.gccwebos.android

import android.graphics.Bitmap
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.gccwebos.android.commerce.*
import com.gccwebos.android.portal.*
import com.gccwebos.android.vpn.SafeProxyConfig
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MobileParityUiTest {
    @get:Rule val compose = createComposeRule()
    private val at = Instant.parse("2026-09-30T00:00:00Z")
    private val policy = OriginPolicy("https://portal.invalid", setOf("https://sub.invalid"))
    private val empty = object : DeliveryStore {
        override fun read(): DeliveryRecord? = null
        override fun save(record: DeliveryRecord) = Unit
        override fun clear() = Unit
    }
    private fun sessions() = MemorySessionStore().apply { save(SavedSession(Secret("native_synthetic"), "fixture@example.invalid", at.plusSeconds(1800), 1800000, at, 0)) }
    private fun loginChallenge() = WireResponse(201,
        """{"challenge":{"challengeRef":"synthetic","type":"math","prompt":"1 + 2","expiresInSeconds":3600}}""")
    private fun capture(name: String) {
        compose.waitForIdle()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "ui-evidence").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun savedSessionKeepsConnectionVisibleButPrivateAccountWaitsForValidation() {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val saved = sessions()
        lateinit var state: AccountController
        var reads = 0
        val wire = object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                assertEquals("/api/portal/resources/current/overview", path)
                if (++reads == 1) { started.complete(Unit); release.await() }
                return WireResponse(200, """{"resource":{"state":"none"}}""")
            }
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            state = remember { AccountController(PortalRepository(wire, empty, policy, {0}, {at}, sessions = saved), scope).apply { restore() } }
            AppShell(state, autoLoadCommerce = false)
        }
        compose.waitUntil(10_000) { started.isCompleted }
        compose.onNodeWithTag("page-Connect").assertExists()
        compose.onNodeWithTag("nav-Profile").performClick()
        compose.onNodeWithTag("session-checking").assertExists()
        compose.onNodeWithTag("nav-Connect").assertExists()
        capture("session-restore-gated")
        compose.runOnIdle { release.complete(Unit) }
        compose.waitUntil(10_000) { state.authenticated && !state.busy }
        compose.onNodeWithTag("nav-Connect").assertExists()
        compose.runOnIdle { assertEquals("", state.account); assertEquals("fixture@example.invalid", state.authenticatedAccountId); assertEquals(2, reads) }
    }
    @Test fun restoreNetworkFailureKeepsConnectionAndCanRetryAccountWithoutPassword() {
        val saved = sessions(); var fail = true; lateinit var state: AccountController
        val wire = object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                if (fail) throw PortalFailure(PortalFailure.Reason.NETWORK)
                return WireResponse(200, """{"resource":{"state":"none"}}""")
            }
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            state = remember { AccountController(PortalRepository(wire, empty, policy, {0}, {at}, sessions = saved), scope).apply { restore() } }
            AppShell(state, autoLoadCommerce = false)
        }
        compose.waitUntil(10_000) { !state.busy && state.restorationPending }
        compose.onNodeWithTag("nav-Connect").assertExists()
        compose.onNodeWithTag("nav-Profile").performClick()
        compose.onNodeWithTag("session-restore-retry").performScrollTo().assertIsDisplayed()
        capture("session-restore-retry")
        compose.runOnIdle { assertNotNull(saved.read()); fail = false }
        compose.onNodeWithTag("session-restore-retry").performClick()
        compose.waitUntil(10_000) { state.authenticated && !state.busy }
        compose.onNodeWithTag("nav-Connect").assertExists()
    }
    @Test fun revokedSavedSessionReturnsToLoginAndClearsToken() {
        val saved = sessions(); lateinit var state: AccountController
        compose.setContent {
            val scope = rememberCoroutineScope()
            state = remember { AccountController(PortalRepository(object : PortalTransport {
                override suspend fun request(path: String, body: String?, session: Secret?) = WireResponse(401, "")
            }, empty, policy, {0}, {at}, sessions = saved), scope).apply { restore() } }
            AppShell(state, autoLoadCommerce = false)
        }
        compose.waitUntil(10_000) { !state.busy }
        compose.onNodeWithTag("page-Connect").assertExists()
        compose.onNodeWithTag("nav-Profile").performClick()
        compose.onNodeWithTag("login-screen").assertExists()
        compose.onNodeWithTag("nav-Connect").assertExists()
        compose.runOnIdle { assertNull(saved.read()); assertFalse(state.authenticated) }
    }
    @Test fun authenticatorRoundTripRetainsTicketAndCanCompleteLogin() {
        lateinit var state: AccountController
        val calls = mutableListOf<String>()
        val wire = object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                calls += path
                return when(path) {
                    "/api/portal/password/session" -> WireResponse(202, """{"challenge":{"status":"second_factor_required","availableFactors":["totp"],"challengeRef":"synthetic-totp","expiresAt":"2026-09-30T00:05:00Z"}}""")
                    "/api/portal/password/second-factor" -> WireResponse(200, """{"session":{"sessionRef":"native_synthetic","accountId":"fixture@example.invalid","issuedAt":"2026-09-30T00:00:00Z","expiresAt":"2026-09-30T00:30:00Z"}}""")
                    "/api/portal/resources/current/overview" -> WireResponse(200, """{"resource":{"state":"none"}}""")
                    else -> error("Unexpected offline path")
                }
            }
        }
        val localUrl = Secret("https://sub.invalid/sub/a/synthetic")
        val profiles = MemoryLocalProfileStore().apply {
            val saved = CachedSubscription(sessionBinding(localUrl), """proxies:
  - name: Synthetic local TOTP node
    type: ss
    server: 192.0.2.10
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""".toByteArray(), Instant.parse("2099-09-30T00:00:00Z"), localUrl)
            try { save(saved) } finally { saved.close() }
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            state = remember { AccountController(PortalRepository(wire, empty, policy, {0}, {at}, profiles = profiles), scope).apply {
                account = "fixture@example.invalid"; password = "synthetic"; answer = "3"; challenge = HumanChallenge("synthetic", "1 + 2"); restore()
            } }
            AppShell(state, autoLoadCommerce = false)
        }
        compose.waitUntil(10_000) { state.localSubscription.hasProfile && !state.busy }
        compose.runOnIdle { state.login() }
        compose.waitUntil(10_000) { state.factorReference != null && !state.busy }
        compose.onNodeWithTag("nav-Profile").performClick()
        compose.onNodeWithTag("totp-code").performScrollTo().assertIsDisplayed()
        capture("local-subscription-totp-pending")
        compose.onNodeWithTag("nav-Connect").performClick()
        compose.onNodeWithTag("nav-Profile").performClick()
        compose.runOnIdle { state.restoreAccount(); state.onForeground(); assertEquals("synthetic-totp", state.factorReference); assertEquals(1, calls.size) }
        compose.onNodeWithTag("totp-code").performScrollTo().performTextInput("123456")
        compose.runOnIdle { state.totp() }
        compose.waitUntil(10_000) { state.authenticated && !state.busy }
        compose.onNodeWithTag("nav-Connect").assertExists()
    }
    @Test fun foregroundWhileBusyGatesCommerceAndValidatesAfterReadCompletes() {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        lateinit var state: AccountController; var reads = 0
        val wire = object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                if (path == "/api/portal/human-challenges") return loginChallenge()
                if (path.endsWith("/password/session")) return WireResponse(200, """{"session":{"sessionRef":"native_synthetic","accountId":"fixture@example.invalid","issuedAt":"2026-09-30T00:00:00Z","expiresAt":"2026-09-30T00:30:00Z"}}""")
                assertEquals("/api/portal/resources/current/overview", path)
                if (++reads == 1) { started.complete(Unit); release.await() }
                return WireResponse(200, """{"resource":{"state":"none"}}""")
            }
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            state = remember { AccountController(PortalRepository(wire, empty, policy, {0}, {at}), scope).apply {
                account = "fixture@example.invalid"; password = "synthetic"
            } }
            AppShell(state, autoLoadCommerce = false)
        }
        // Compact form admission owns the challenge; a preseeded question is intentionally invalidated.
        compose.onNodeWithTag("nav-Profile").performClick()
        compose.waitUntil(10_000) { state.challenge != null && !state.challengeLoading }
        compose.runOnIdle { state.answer = "3"; state.login() }; compose.waitUntil(10_000) { started.isCompleted }
        compose.runOnIdle { state.onForeground(); assertTrue(state.foregroundCheckPending) }
        // Same-session validation retains navigation, but cannot expose commerce actions.
        compose.onNodeWithTag("nav-Connect").assertExists()
        compose.onNodeWithTag("nav-Plans").performClick()
        compose.onNodeWithTag("commerce-signed-out").assertExists()
        compose.onNodeWithTag("catalog-status").assertDoesNotExist()
        compose.onNodeWithTag("purchase-confirm").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, reads); release.complete(Unit) }
        compose.waitUntil(10_000) { state.authenticated && !state.busy && !state.foregroundCheckPending }
        compose.runOnIdle { assertEquals(3, reads) }
        compose.onNodeWithTag("commerce-signed-out").assertDoesNotExist()
        compose.onNodeWithTag("nav-Connect").assertExists()
    }
    @Test fun foregroundIdleSessionExpiryClosesPrivateAccountButKeepsConnection() {
        lateinit var state: AccountController; val saved = MemorySessionStore()
        val authenticatedRequests = mutableListOf<String>()
        val wire = object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                if (session != null) authenticatedRequests += path
                return when (path) {
                    "/api/portal/human-challenges" -> loginChallenge()
                    "/api/portal/password/session" -> WireResponse(200, """{"session":{"sessionRef":"native_synthetic","accountId":"fixture@example.invalid","issuedAt":"2026-09-30T00:00:00Z","expiresAt":"2026-09-30T00:00:05Z"}}""")
                    "/api/portal/resources/current/overview" -> WireResponse(200, """{"resource":{"state":"none"}}""")
                    else -> throw AssertionError("Unexpected offline path: $path")
                }
            }
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            state = remember { AccountController(PortalRepository(wire, empty, policy, {0}, {at}, sessions = saved), scope).apply {
                account = "fixture@example.invalid"; password = "synthetic"
            } }
            AppShell(state, autoLoadCommerce = false)
        }
        compose.onNodeWithTag("nav-Profile").performClick()
        compose.waitUntil(10_000) { state.challenge != null && !state.challengeLoading }
        compose.runOnIdle { state.answer = "3"; state.login() }
        compose.waitUntil(10_000) { state.authenticated && !state.busy }
        compose.onNodeWithTag("nav-Connect").performClick()
        compose.onNodeWithTag("nav-Connect").assertExists()
        compose.runOnIdle {
            assertNotNull(saved.read())
            assertEquals(listOf("/api/portal/resources/current/overview"), authenticatedRequests)
        }
        // Drive the controller's Compose coroutine delay, without a request or foreground callback.
        compose.mainClock.advanceTimeBy(5_001)
        compose.waitUntil(15_000) { !state.authenticated }
        compose.onNodeWithTag("nav-Connect").assertExists()
        compose.onNodeWithTag("page-Connect").assertExists()
        compose.onNodeWithTag("nav-Profile").performClick()
        compose.onNodeWithTag("login-screen").assertExists()
        compose.runOnIdle {
            assertNull(saved.read())
            assertEquals(listOf("/api/portal/resources/current/overview"), authenticatedRequests)
        }
    }
    @Test fun logoutAdmissionSurvivesImmediateControllerScopeCancellation() {
        val saved = sessions()
        compose.runOnIdle {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val repo = PortalRepository(object : PortalTransport { override suspend fun request(path: String, body: String?, session: Secret?) = error("No network") }, empty, policy, {0}, {at}, sessions = saved)
            val state = AccountController(repo, scope)
            state.logout(); scope.cancel()
            assertNull(saved.read()); assertFalse(state.authenticated)
        }
    }
    @Test fun localProfileSurvivesPortalLogoutAndKeepsCommerceGated() {
        lateinit var state: AccountController
        val delivery = object : DeliveryStore {
            var record: DeliveryRecord? = DeliveryRecord("fixture", "binding", "resource", "key", Secret("https://sub.invalid/sub/a/synthetic"), Secret("handoff"), false)
            override fun read() = record
            override fun save(record: DeliveryRecord) { this.record = record }
            override fun clear() { record = null }
        }
        val profiles = MemoryLocalProfileStore()
        val saved = MemorySessionStore()
        val calls = mutableListOf<String>()
        val repo = PortalRepository(object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                calls += path
                return when (path) {
                    "/api/portal/human-challenges" -> loginChallenge()
                    "/api/portal/password/session" -> WireResponse(200, """{"session":{"sessionRef":"native_synthetic","accountId":"fixture@example.invalid","issuedAt":"2026-09-30T00:00:00Z","expiresAt":"2026-09-30T00:30:00Z"}}""")
                    "/api/portal/resources/current/overview" -> WireResponse(200, """{"resource":{"state":"none"}}""")
                    else -> throw AssertionError("Unexpected offline path: $path")
                }
            }
        }, delivery, policy, {0}, {at}, SubscriptionSource { error("Cached UI does not connect") }, sessions = saved, profiles = profiles)
        val raw = """proxies:
  - name: Synthetic retained subscription
    type: ss
    server: 192.0.2.10
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""".toByteArray()
        repo.localSubscriptions.remember(raw, delivery.record!!.url!!, Instant.parse("2099-09-30T00:00:00Z"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            state = remember { AccountController(repo, scope).apply { restore() } }
            AppShell(state, autoLoadCommerce = false)
        }
        compose.waitUntil(10_000) { state.localSubscription.hasProfile && !state.busy }
        compose.runOnIdle { assertFalse(state.authenticated); assertTrue(calls.isEmpty()); state.logout() }
        compose.waitUntil(10_000) { !state.busy }
        compose.onNodeWithTag("nav-Connect").assertExists()
        compose.onNodeWithTag("nav-Plans").performClick()
        compose.onNodeWithTag("commerce-signed-out").assertExists()
        compose.onNodeWithTag("catalog-status").assertDoesNotExist()
        compose.onNodeWithTag("purchase-confirm").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(state.localSubscription.hasProfile)
            assertNotNull(profiles.read()?.also { it.close() })
            assertNull(saved.read()); assertTrue(calls.isEmpty())
        }
        compose.onNodeWithTag("nav-Profile").performClick()
        // The signed-out compact form intentionally omits reset. Reset belongs to the
        // authenticated account panel, so reach it through a fresh offline login.
        compose.onNodeWithTag("account-reset").assertDoesNotExist()
        compose.waitUntil(10_000) { state.challenge != null && !state.challengeLoading }
        compose.onNodeWithTag("login-account").performScrollTo().performTextInput("fixture@example.invalid")
        compose.onNodeWithTag("login-password").performScrollTo().performTextInput("synthetic")
        compose.onNodeWithTag("login-answer").performScrollTo().performTextInput("3")
        compose.onNodeWithTag("login-submit").performScrollTo().performClick()
        compose.waitUntil(10_000) { state.authenticated && !state.busy }
        compose.onNodeWithTag("mine-security").performScrollTo().performClick()
        compose.onNodeWithTag("account-reset").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(state.localSubscription.hasProfile); assertNotNull(saved.read()) }
        compose.onNodeWithTag("reset-cancel").performClick()
        compose.runOnIdle {
            assertTrue(state.authenticated); assertTrue(state.localSubscription.hasProfile)
            assertNotNull(profiles.read()?.also { it.close() }); assertNotNull(saved.read())
        }
        compose.onNodeWithTag("account-reset").performScrollTo().performClick()
        compose.onNodeWithTag("reset-confirm").performClick()
        compose.waitUntil(10_000) { !state.localSubscription.hasProfile && !state.busy }
        compose.onNodeWithTag("nav-Connect").assertExists()
        compose.onNodeWithTag("login-screen").assertExists()
        compose.runOnIdle {
            assertFalse(state.authenticated); assertNull(state.authenticatedAccountId); assertNull(state.displayEmail)
            assertNull(profiles.read()); assertNull(saved.read()); assertNull(delivery.record)
        }
    }
    @Test fun localProfileRestoresAccountAutomaticallyAndBrowserReturnRevalidatesIt() {
        lateinit var state: AccountController; var reads = 0
        val delivery = object : DeliveryStore {
            val record = DeliveryRecord("fixture@example.invalid", "binding", "resource", "key", Secret("https://sub.invalid/sub/a/synthetic"), Secret("handoff"), false)
            override fun read() = record
            override fun save(record: DeliveryRecord) = Unit
            override fun clear() = Unit
        }
        val repo = PortalRepository(object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                assertEquals("/api/portal/resources/current/overview", path); reads++
                return WireResponse(200, """{"resource":{"state":"none"}}""")
            }
        }, delivery, policy, {0}, {at}, SubscriptionSource { error("No subscription fetch for wallet validation") }, sessions = sessions())
        repo.localSubscriptions.remember("""proxies:
  - name: Synthetic local node
    type: ss
    server: 192.0.2.10
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""".toByteArray(), delivery.record.url!!, Instant.parse("2099-09-30T00:00:00Z"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            state = remember { AccountController(repo, scope).apply { restore() } }
            AppShell(state, autoLoadCommerce = false)
        }
        compose.waitUntil(10_000) { state.localSubscription.hasProfile && !state.busy }
        compose.runOnIdle {
            assertTrue(state.authenticated)
            assertTrue(state.accountUsage.state.value.authenticated)
            assertSame(Resource.None, state.accountUsage.state.value.resource)
            assertEquals(2, reads)
            state.onForeground()
        }
        compose.waitUntil(10_000) { state.authenticated && !state.busy && !state.foregroundCheckPending }
        compose.runOnIdle { assertEquals(4, reads); assertTrue(state.localSubscription.hasProfile) }
    }
    @Test fun walletAccountSwitchFailurePreservesSeparateLocalSubscription() {
        lateinit var state: AccountController
        val delivery = object : DeliveryStore {
            var value: DeliveryRecord? = DeliveryRecord("account-a@example.invalid", "old-binding", "resource", "key", Secret("https://sub.invalid/sub/a/synthetic"), Secret("handoff"), false)
            override fun read() = value
            override fun save(record: DeliveryRecord) { value = record }
            override fun clear() { value = null }
        }
        val profiles = MemoryLocalProfileStore()
        val saved = MemorySessionStore()
        val repo = PortalRepository(object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                if (path.endsWith("/password/session")) return WireResponse(200, """{"session":{"sessionRef":"native_account_b","accountId":"account_b_synthetic","issuedAt":"2026-09-30T00:00:00Z","expiresAt":"2026-09-30T00:30:00Z"}}""")
                assertEquals("/api/portal/resources/current/overview", path)
                throw PortalFailure(PortalFailure.Reason.NETWORK)
            }
        }, delivery, policy, {0}, {at}, SubscriptionSource { error("Wallet login must not replace local subscription") }, sessions = saved, profiles = profiles)
        repo.localSubscriptions.remember("""proxies:
  - name: Synthetic account A local node
    type: ss
    server: 192.0.2.10
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""".toByteArray(), delivery.value!!.url!!, Instant.parse("2099-09-30T00:00:00Z"))
        compose.setContent {
            val scope = rememberCoroutineScope()
            state = remember { AccountController(repo, scope).apply { restore() } }
            AppShell(state, autoLoadCommerce = false)
        }
        compose.waitUntil(10_000) { state.localSubscription.hasProfile && !state.busy }
        compose.runOnIdle {
            state.account = "account-b@example.invalid"; state.password = "synthetic"; state.answer = "3"
            state.challenge = HumanChallenge("synthetic", "1 + 2"); state.login()
        }
        compose.waitUntil(10_000) { state.authenticated && !state.busy && state.error != null }
        compose.runOnIdle {
            // A failed resource refresh does not undo accepted authentication. The
            // submitted email remains a display hint, separate from the server ID.
            assertEquals("account-b@example.invalid", state.account)
            assertEquals("account-b@example.invalid", state.displayEmail)
            assertEquals("account_b_synthetic", state.authenticatedAccountId)
            assertEquals("account_b_synthetic", saved.read()?.accountId)
            assertEquals("", state.password); assertEquals("", state.answer)
            assertNull(state.resource)
            assertEquals("Synthetic account A local node", state.localSubscription.nodes.single().name)
            assertNull(delivery.value); state.logout()
        }
        compose.waitUntil(10_000) { !state.authenticated && !state.busy }
        compose.onNodeWithTag("nav-Connect").assertExists()
        compose.onNodeWithTag("nav-Plans").performClick()
        compose.onNodeWithTag("commerce-signed-out").assertExists()
        compose.onNodeWithTag("catalog-status").assertDoesNotExist()
        compose.onNodeWithTag("purchase-confirm").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("account-b@example.invalid", state.account)
            assertNull(state.authenticatedAccountId); assertNull(state.displayEmail); assertNull(saved.read())
            assertTrue(state.localSubscription.hasProfile); assertNotNull(profiles.read()?.also { it.close() })
            assertEquals("Synthetic account A local node", state.localSubscription.nodes.single().name)
        }
    }
    @Test fun expiredLocalSubscriptionGuidesPurchaseWithoutInventingWalletAuthentication() {
        lateinit var state: AccountController
        compose.setContent {
            val scope = rememberCoroutineScope()
            state = remember { offlineUiAccount(scope).apply {
                authenticated = false
                localSubscription = LocalSubscriptionSnapshot(LocalSubscriptionStatus.EXPIRED, hasProfile = true, hasCapability = true,
                    validUntil = Instant.parse("2020-09-30T00:00:00Z"))
            } }
            AppShell(state, autoLoadCommerce = false)
        }
        compose.onNodeWithTag("expired-subscription-renew").performScrollTo().assertIsDisplayed()
        capture("local-subscription-expired-renewal")
        compose.onNodeWithTag("expired-subscription-renew").performClick()
        compose.onNodeWithTag("commerce-signed-out").assertExists()
        compose.runOnIdle { assertFalse(state.authenticated) }
    }
    @Test fun cancelledForegroundPollCannotPublishLateRechargeResponse() = cancelledForegroundPoll(success = false)
    @Test fun cancelledForegroundPollCannotRestoreALatePaymentAction() = cancelledForegroundPoll(success = true)
    private fun cancelledForegroundPoll(success: Boolean) {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        lateinit var state: CommerceController; lateinit var poll: kotlinx.coroutines.Job
        var responses = 0
        val record = PendingCommerce("recharge", null, "offer_fixture", "key_fixture", "recharge_fixture")
        val payableBody = """{"recharge":{"order":{"orderRef":"recharge_fixture","offerRef":"offer_fixture",
            "quoteVersion":1,"expectedPaidCoinUnits":100,"amountMinor":10000,"currency":"CNY","currencyExponent":2,
            "adapterKey":"xsd_v1","paymentMethod":"alipay","status":"entry_ready","providerOrderRef":null,
            "nextAction":{"type":"redirect","url":"https://www.xsd8866.com/pay?fixture=synthetic","expiresAt":"2026-09-30T01:00:00.000Z"},
            "walletOperationRef":null,"walletLotRef":null,"expiresAt":"2026-09-30T01:00:00.000Z",
            "createdAt":"2026-09-30T00:00:00.000Z","updatedAt":"2026-09-30T00:00:00.000Z"}}}"""
        val payable = CommerceJson.rechargeOrder(payableBody)
        val recovery = object : CommerceRecovery {
            override fun read(binding: String, kind: String) = if (kind == "recharge") record else null
            override fun save(binding: String, value: PendingCommerce) = error("Polling must not create")
            override fun clear(binding: String, kind: String) = error("Polling must not clear")
        }
        val wire = CommerceRepository({ path, body, key ->
            assertEquals("/api/portal/coins/recharge/orders/recharge_fixture", path); assertNull(body); assertNull(key)
            started.complete(Unit)
            withContext(NonCancellable) {
                release.await(); responses++
                if (success) WireResponse(200, payableBody) else WireResponse(500, "{}")
            }
        }, recovery, "fixture")
        compose.setContent {
            val scope = rememberCoroutineScope()
            val account = remember { offlineUiAccount(scope) }
            state = remember { CommerceController(account, scope, recovery, initialRepository = wire).apply {
                recharge = payable
                assertTrue(canOpenPayment(payable, at))
            } }
            androidx.compose.runtime.LaunchedEffect(Unit) { poll = launch { state.pollRecharge() } }
        }
        compose.waitUntil(10_000) { started.isCompleted }
        compose.runOnIdle {
            // Poll admission revokes the old payment action even before cancellation or response.
            assertNull(state.recharge); assertTrue(state.pendingRecharge); assertTrue(state.busy)
            assertFalse(state.canOpenPayment(payable, at))
            poll.cancel(); release.complete(Unit)
        }
        compose.waitUntil(10_000) { poll.isCompleted }
        compose.runOnIdle {
            assertEquals(1, responses); assertTrue(poll.isCancelled)
            assertNull(state.recharge); assertNull(state.error); assertFalse(state.busy)
            assertFalse(state.canOpenPayment(payable, at)); assertTrue(state.pendingRecharge)
            assertEquals(record, recovery.read("fixture", "recharge"))
        }
    }
    @Test fun syntheticWalletAlipayCatalogAndOrderLayoutsRemainServerShaped() {
        lateinit var state: CommerceController
        val openedPayments = mutableListOf<String>()
        val recovery = object : CommerceRecovery {
            override fun read(binding: String, kind: String): PendingCommerce? = throw AssertionError("No recovery read in layout fixture")
            override fun save(binding: String, value: PendingCommerce) = throw AssertionError("No order writes in layout fixture")
            override fun clear(binding: String, kind: String) = throw AssertionError("No recovery clear in layout fixture")
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val account = remember { offlineUiAccount(scope).apply { purchaseReturnRef = "offer_synthetic" } }
            val commerce = remember {
                CommerceController(account, scope, recovery).apply {
                    wallet = Wallet("active", 1, 1000, 150, 50, 1200, 0, 0)
                    commerceModel = CommerceModel.LEGACY; catalogLoaded = true
                    offers = listOf(Offer("offer_synthetic", "version_synthetic", LocalizedText("合成验收年卡", "Synthetic plan"), LocalizedText("仅离线界面验收", "Offline only"), LocalizedNotices(emptyList(), emptyList()), 100, 10000, listOf("paid_coins"), 365, 100, 0))
                    rechargeOffers = listOf(RechargeOffer("recharge_synthetic", 1, 100, 10000, "CNY", 2, listOf("alipay")))
                    rechargeOffersLoaded = true; walletLoaded = true
                    orderPage = OrderPage(emptyList()); rechargeHistory = emptyList(); transitions = emptyList()
                }
            }
            state = commerce
            AppShell(account, autoLoadCommerce = false, commerceOverride = commerce, commerceRecoveryOverride = recovery,
                paymentOpenerOverride = { openedPayments += it })
        }
        compose.onNodeWithTag("nav-Profile").performClick()
        compose.onNodeWithTag("wallet-balance").performScrollTo().assertTextContains("1200 Coins")
        capture("synthetic-profile-balance-validity")
        compose.onNodeWithTag("nav-Lines").performClick()
        compose.onNodeWithTag("subscription-node-0").assertExists()
        capture("synthetic-parsed-nodes")
        compose.onNodeWithTag("nav-Plans").performClick()
        compose.onNodeWithTag("plan-title-offer_synthetic", useUnmergedTree = true).assertTextEquals("365天")
        compose.onNodeWithTag("plan-price-offer_synthetic", useUnmergedTree = true).assertTextEquals("100 Coins")
        compose.onNodeWithTag("catalog-data-packs").performScrollTo().performClick()
        compose.onNodeWithTag("catalog-empty").assertTextEquals("暂无可购流量包")
        compose.onNodeWithTag("catalog-status").assertDoesNotExist()
        compose.onNodeWithTag("catalog-loading").assertDoesNotExist()
        compose.onNodeWithTag("commerce-error").assertDoesNotExist()
        compose.onNodeWithTag("plan-offer-offer_synthetic").assertDoesNotExist()
        compose.onNodeWithTag("plan-continue").assertDoesNotExist()
        capture("server-empty-data-packs")
        compose.onNodeWithTag("nav-Profile").performClick()
        compose.onNodeWithTag("mine-history").performScrollTo().performClick()
        compose.onNodeWithTag("orders-recharge").performScrollTo().performClick()
        compose.onNodeWithTag("history-read-title").assertTextEquals("暂无充值记录")
        compose.onNodeWithTag("orders-loading").assertDoesNotExist()
        compose.onNodeWithTag("history-error").assertDoesNotExist()
        compose.onNodeWithTag("purchase-confirm").assertDoesNotExist()
        capture("server-empty-order-history")
        compose.onNodeWithTag("mine-back").performScrollTo().performClick()
        capture("synthetic-my-wallet-summary")
        compose.onNodeWithTag("mine-return-purchase").performScrollTo().performClick()
        compose.onNodeWithTag("purchase-return-requote").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("nav-Profile").performClick()
        compose.onNodeWithTag("mine-recharge").performScrollTo().performClick()
        compose.onNodeWithTag("recharge-select-recharge_synthetic").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("recharge-selected-pay").assertTextEquals("支付宝支付").assertIsNotEnabled()
        compose.onNodeWithTag("recharge-pay").assertDoesNotExist()
        compose.onNodeWithTag("payment-open-confirm").assertDoesNotExist()
        capture("synthetic-alipay-recharge")
        compose.onNodeWithTag("recharge-select-recharge_synthetic").performClick()
        compose.onNodeWithTag("recharge-selected-pay").assertIsEnabled().performScrollTo().performClick()
        compose.onNodeWithText("CNY 100.00 → 100 Coins").assertExists()
        compose.onNodeWithTag("recharge-confirm").assertTextEquals("确认金额并创建订单")
        compose.onNodeWithTag("payment-open-confirm").assertDoesNotExist()
        compose.onNodeWithTag("recharge-cancel").performClick()
        compose.onNodeWithTag("recharge-confirm").assertDoesNotExist()
        compose.onNodeWithTag("recharge-pay").assertDoesNotExist()
        compose.onNodeWithTag("payment-open-confirm").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(openedPayments.isEmpty())
            assertNull(state.error); assertNull(state.purchase); assertNull(state.recharge)
            assertTrue(!state.pendingPurchase && !state.pendingRecharge)
            assertEquals(CommerceModel.LEGACY, state.commerceModel)
            assertEquals(listOf("offer_synthetic"), state.offers.map { it.offerRef })
        }
    }
}
