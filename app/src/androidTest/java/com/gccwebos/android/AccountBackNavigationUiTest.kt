package com.gccwebos.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import com.gccwebos.android.portal.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Real AppShell BackHandlers with synthetic in-memory sessions; no device accounts, HTTP or VPN. */
class AccountBackNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var account: AccountController
    private val now = Instant.parse("2026-10-04T00:00:00Z")

    private inner class Fixture(saved: Boolean = true) {
        private val memory = MemorySessionStore().apply {
            if (saved) save(SavedSession(Secret("native_back_fixture"), "account_back_fixture",
                now.plusSeconds(1800), 1800000, now, 0))
        }
        val clears = AtomicInteger()
        val overviewReads = AtomicInteger()
        val passwordReads = AtomicInteger()
        val sessions = object : SessionStore {
            override fun read() = memory.read()
            override fun save(value: SavedSession) = memory.save(value)
            override fun clear() { clears.incrementAndGet(); memory.clear() }
        }
        var overviewGate: CompletableDeferred<Unit>? = null
        var passwordGate: CompletableDeferred<Unit>? = null
        var requireSecondFactor = false
        fun repository() = PortalRepository(object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse = when (path) {
                "/api/portal/resources/current/overview" -> {
                    overviewReads.incrementAndGet()
                    assertNull(body)
                    assertNotNull(session)
                    overviewGate?.await()
                    WireResponse(200, """{"resource":{"state":"none"}}""")
                }
                "/api/portal/human-challenges" -> WireResponse(201,
                    """{"challenge":{"challengeRef":"back_fixture_question","type":"math","prompt":"1 + 2","expiresInSeconds":180}}""")
                "/api/portal/password/session" -> {
                    passwordReads.incrementAndGet()
                    passwordGate?.await()
                    if (requireSecondFactor) WireResponse(202,
                        """{"challenge":{"status":"second_factor_required","availableFactors":["totp"],
                        "challengeRef":"back_fixture_totp","expiresAt":"2026-10-04T00:05:00.000Z"}}""")
                    else WireResponse(200, """{"session":{"sessionRef":"native_back_fixture","accountId":"account_back_fixture",
                        "issuedAt":"2026-10-04T00:00:00.000Z","expiresAt":"2026-10-04T00:30:00.000Z"}}""")
                }
                else -> throw AssertionError("Unexpected request in Back navigation fixture")
            }
        }, object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord): Unit = throw AssertionError("No delivery writes")
            override fun clear(): Unit = throw AssertionError("No delivery clears")
        }, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L }, { now },
            subscription = SubscriptionSource { throw AssertionError("No subscription download") },
            sessions = sessions, profiles = MemoryLocalProfileStore())
    }

    private fun launchController(fixture: Fixture, restore: Boolean = true) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val state = remember { AccountController(fixture.repository(), scope) }
            account = state
            LaunchedEffect(state) { if (restore) state.restore() }
            AppShell(accountOverride = state, autoLoadCommerce = false)
        }
    }

    private fun back() = compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test fun backDuringSavedStartupNavigatesWithoutClearingAndFreshOwnerStillRestores() {
        val fixture = Fixture()
        val gate = CompletableDeferred<Unit>()
        fixture.overviewGate = gate
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val repository = remember { fixture.repository() }
            // Exercise AppShell's production automatic startup restore, without an account override.
            AppShell(repositoryOverride = repository, autoLoadCommerce = false)
        }
        try {
            compose.waitUntil(10_000) { fixture.overviewReads.get() == 1 }
            compose.onNodeWithTag("nav-Profile").performClick()
            compose.onNodeWithTag("session-checking").assertExists()
            back()
            compose.onNodeWithTag("page-Connect").assertExists()
            compose.runOnIdle { assertNotNull(fixture.sessions.read()); assertEquals(0, fixture.clears.get()); gate.complete(Unit) }
            compose.waitUntil(10_000) { fixture.overviewReads.get() == 2 }
            compose.onNodeWithTag("nav-Profile").performClick()
            compose.onNodeWithTag("mine-login-status").assertTextEquals("已登录")
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(10_000) { fixture.overviewReads.get() == 4 }
            compose.onNodeWithTag("mine-login-status").assertTextEquals("已登录")
            compose.onNodeWithTag("login-submit").assertDoesNotExist()
            assertEquals(0, fixture.passwordReads.get())
            assertEquals(0, fixture.clears.get())
            assertEquals(now.plusSeconds(1800), fixture.sessions.read()!!.expiresAt)
        } finally { gate.complete(Unit) }
    }

    @Test fun backDuringForegroundValidationNavigatesAndKeepsTheAcceptedSession() {
        val fixture = Fixture()
        launchController(fixture)
        compose.waitUntil(10_000) { account.authenticated && !account.busy }
        compose.onNodeWithTag("nav-Profile").performClick()
        val gate = CompletableDeferred<Unit>()
        try {
            compose.runOnIdle {
                fixture.overviewGate = gate
                val observer = AccountForegroundObserver(account)
                observer.onStateChanged(compose.activity, Lifecycle.Event.ON_STOP)
                observer.onStateChanged(compose.activity, Lifecycle.Event.ON_RESUME)
            }
            compose.waitUntil(10_000) { account.restoring && account.busy && fixture.overviewReads.get() == 3 }
            back()
            compose.onNodeWithTag("page-Connect").assertExists()
            compose.runOnIdle { assertNotNull(fixture.sessions.read()); assertEquals(0, fixture.clears.get()); gate.complete(Unit) }
            compose.waitUntil(10_000) { account.authenticated && !account.busy }
            compose.onNodeWithTag("nav-Profile").performClick()
            compose.onNodeWithTag("mine-login-status").assertTextEquals("已登录")
            compose.onNodeWithTag("login-submit").assertDoesNotExist()
            assertEquals(0, fixture.passwordReads.get())
            assertEquals(0, fixture.clears.get())
            assertEquals(now.plusSeconds(1800), fixture.sessions.read()!!.expiresAt)
        } finally { gate.complete(Unit) }
    }

    private fun beginLogin() {
        compose.onNodeWithTag("nav-Profile").performClick()
        compose.onNodeWithTag("mine-go-login").performScrollTo().performClick()
        compose.waitUntil(10_000) { account.challenge != null && !account.challengeLoading }
        compose.runOnIdle {
            account.account = "back.fixture@example.invalid"
            account.password = "synthetic-back-password"
            account.answer = "3"
            account.login()
        }
    }

    @Test fun backStillCancelsAnExplicitNewLoginWithoutPublishingItsLateResponse() {
        val fixture = Fixture(saved = false)
        val gate = CompletableDeferred<Unit>()
        fixture.passwordGate = gate
        launchController(fixture, restore = false)
        try {
            beginLogin()
            compose.waitUntil(10_000) { fixture.passwordReads.get() == 1 && account.busy }
            back()
            compose.runOnIdle { gate.complete(Unit) }
            compose.waitUntil(10_000) { !account.busy && !account.clearing }
            assertFalse(account.authenticated)
            assertNull(fixture.sessions.read())
            compose.onNodeWithTag("login-submit").assertExists()
            compose.onNodeWithTag("mine-welcome").assertDoesNotExist()
        } finally { gate.complete(Unit) }
    }

    @Test fun backStillCancelsAnExplicitSecondFactorChallenge() {
        val fixture = Fixture(saved = false).apply { requireSecondFactor = true }
        launchController(fixture, restore = false)
        beginLogin()
        compose.waitUntil(10_000) { account.factorReference != null && !account.busy }
        back()
        compose.waitUntil(10_000) { account.factorReference == null && !account.busy && !account.clearing }
        assertFalse(account.authenticated)
        assertNull(fixture.sessions.read())
        compose.onNodeWithTag("login-submit").assertExists()
    }
}
