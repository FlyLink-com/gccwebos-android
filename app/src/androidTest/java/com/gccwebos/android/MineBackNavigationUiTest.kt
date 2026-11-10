package com.gccwebos.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.gccwebos.android.commerce.CommerceController
import com.gccwebos.android.commerce.CommerceRecovery
import com.gccwebos.android.commerce.MinePanel
import com.gccwebos.android.commerce.PendingCommerce
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.portal.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** Real back dispatch with synthetic in-memory sessions; no network or device private stores. */
class MineBackNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var account: AccountController
    private val saved = MemorySessionStore()
    private val gate = CompletableDeferred<Unit>()
    private var clears = 0
    private var overviewReads = 0
    private var securityReads = 0
    private var unexpected = 0
    private var blockOverview = false
    private var allowLoginChallenge = false
    private var loginChallenges = 0

    private fun launch() {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val owner = remember {
                val now = Instant.now()
                saved.save(SavedSession(Secret("synthetic_mine_back_session"), "synthetic_mine_back_owner",
                    now.plusSeconds(1800), 1_800_000, now, 0))
                val sessions = object : SessionStore {
                    override fun read() = saved.read()
                    override fun save(value: SavedSession) = saved.save(value)
                    override fun clear() { clears++; saved.clear() }
                }
                val transport = object : PortalTransport {
                    override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                        if (path == "/api/portal/human-challenges" && allowLoginChallenge) {
                            assertNull(session)
                            assertEquals(1, ++loginChallenges)
                            return WireResponse(201, """{"challenge":{"challengeRef":"synthetic_mine_back_question",
                                "type":"math","prompt":"合成题目：2 + 3 = ?","expiresInSeconds":180}}""")
                        }
                        assertNull(body)
                        return when (path) {
                            "/api/portal/resources/current/overview" -> {
                                overviewReads++
                                if (blockOverview) gate.await()
                                WireResponse(200, """{"resource":{"state":"none"}}""")
                            }
                            "/api/portal/security/current" -> {
                                securityReads++; gate.await()
                                WireResponse(200, """{"security":{"authenticatorBound":false,"initialAccount":false,
                                    "passkeyEnabled":false,"passkeyExported":false,"setupRequired":false,
                                    "safety":{"noAdminApi":true,"noPublicAdmin":true,"noRawSecretReturned":true}}}""")
                            }
                            else -> { unexpected++; throw AssertionError("No other fixture requests") }
                        }
                    }
                }
                AccountController(PortalRepository(transport, object : DeliveryStore {
                    override fun read(): DeliveryRecord? = null
                    override fun save(record: DeliveryRecord) = error("No delivery writes")
                    override fun clear() = error("No delivery clear")
                }, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L }, { now },
                    subscription = SubscriptionSource { error("No subscription requests") },
                    sessions = sessions, profiles = MemoryLocalProfileStore()), scope)
            }
            account = owner
            LaunchedEffect(owner) { owner.restoreAccount() }
            val state = remember(owner.authSessionGeneration) {
                CommerceController(owner, scope, object : CommerceRecovery {
                    override fun read(binding: String, kind: String): PendingCommerce? = null
                    override fun save(binding: String, value: PendingCommerce) = error("No recovery writes")
                    override fun clear(binding: String, kind: String) = error("No recovery clear")
                })
            }
            GccTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    MinePanel(owner, autoLoad = false, stateOverride = state)
                }
            }
        }
        compose.waitUntil(5_000) { ::account.isInitialized && account.authenticated && !account.busy }
        compose.onNodeWithTag("mine-security").performScrollTo().performClick()
        compose.onNodeWithTag("mine-back").assertExists()
    }

    private fun back() = compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @After fun onlySyntheticExpectedOperations() {
        gate.complete(Unit)
        assertEquals(0, unexpected)
    }

    @Test fun securityReadBackReturnsToMineWithoutClearingTheSavedSession() {
        launch()
        compose.runOnIdle { account.readSecurity() }
        compose.waitUntil(5_000) { account.busy && securityReads == 1 }
        compose.onNodeWithTag("account-loading").assertDoesNotExist()
        compose.onNodeWithTag("security-refresh").assertDoesNotExist()
        compose.onNodeWithTag("account-security-state").assertExists()
        back()
        compose.onNodeWithTag("mine-welcome").assertExists()
        compose.runOnIdle { assertTrue(account.authenticated); assertTrue(account.busy); assertNotNull(saved.read()); assertEquals(0, clears) }
        gate.complete(Unit)
        compose.waitUntil(5_000) { !account.busy }
        compose.runOnIdle { assertTrue(account.authenticated); assertNotNull(saved.read()); assertEquals(0, clears) }
    }

    @Test fun resourceRefreshKeepsConfirmedContentWithoutSpinnerAndBackKeepsSession() {
        launch()
        compose.runOnIdle { blockOverview = true; account.refresh() }
        compose.waitUntil(5_000) { account.busy && overviewReads == 3 }
        compose.onNodeWithTag("account-loading").assertDoesNotExist()
        compose.onNodeWithTag("account-refresh").assertDoesNotExist()
        compose.onNodeWithTag("account-security-state").assertExists()
        back()
        compose.onNodeWithTag("mine-welcome").assertExists()
        compose.runOnIdle { assertTrue(account.authenticated); assertTrue(account.busy); assertNotNull(saved.read()); assertEquals(0, clears) }
        gate.complete(Unit)
        compose.waitUntil(5_000) { !account.busy }
        compose.runOnIdle { assertTrue(account.authenticated); assertNotNull(saved.read()); assertEquals(0, clears) }
    }

    @Test fun foregroundVerificationBackKeepsThePendingSavedSessionAndCanFinish() {
        launch()
        compose.runOnIdle { blockOverview = true; account.onForeground() }
        compose.waitUntil(5_000) { account.restoring && account.restorationPending && overviewReads == 3 }
        back()
        compose.onNodeWithTag("mine-welcome").assertExists()
        compose.runOnIdle { assertTrue(account.busy); assertTrue(account.restorationPending); assertNotNull(saved.read()); assertEquals(0, clears) }
        gate.complete(Unit)
        compose.waitUntil(5_000) { account.authenticated && !account.busy }
        compose.runOnIdle { assertNotNull(saved.read()); assertEquals(0, clears); assertEquals(4, overviewReads) }
    }

    @Test fun pendingRestoreFlagAloneCannotBeTreatedAsNewLoginOnBack() {
        launch()
        compose.runOnIdle { account.authenticated = false; account.busy = true; account.restorationPending = true }
        back()
        compose.onNodeWithTag("mine-welcome").assertExists()
        compose.runOnIdle { assertNotNull(saved.read()); assertEquals(0, clears); account.busy = false }
    }

    private fun cancelledFreshLogin(totp: Boolean) {
        launch()
        compose.runOnIdle {
            account.authenticated = false
            account.busy = !totp
            account.factorReference = if (totp) "synthetic_mine_back_mfa" else null
            allowLoginChallenge = true
        }
        back()
        compose.waitUntil(5_000) { !account.busy && !account.clearing }
        compose.runOnIdle { assertFalse(account.authenticated); assertNull(account.factorReference); assertNull(saved.read()); assertTrue(clears > 0) }
    }

    @Test fun freshLoginBackStillCancelsOnlyTheLoginOperation() = cancelledFreshLogin(false)

    @Test fun totpBackStillCancelsItsPendingAuthentication() = cancelledFreshLogin(true)
}
