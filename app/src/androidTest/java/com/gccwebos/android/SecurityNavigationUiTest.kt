package com.gccwebos.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.portal.*
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic read-only entry recovery; entering a screen never submits credentials or a mutation. */
class SecurityNavigationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun securityNavigationCoalescesBusyRefreshAndPreservesEntryRecovery() {
        val now = Instant.parse("2026-10-04T00:00:00Z")
        val saved = MemorySessionStore().apply {
            save(SavedSession(Secret("synthetic_security_navigation"), "synthetic_owner",
                now.plusSeconds(1800), 1_800_000, now, 0))
        }
        val releaseOverview = CompletableDeferred<Unit>()
        var overviewReads = 0
        var securityReads = 0
        var failSecurity = false
        var securityGate: CompletableDeferred<Unit>? = null
        var visible by mutableStateOf(true)
        var refreshRequest by mutableStateOf(0)
        var autoLoad by mutableStateOf(true)
        lateinit var account: AccountController
        val wire = object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                assertNull(body); assertNotNull(session)
                return when (path) {
                    "/api/portal/resources/current/overview" -> {
                        if (++overviewReads == 2) releaseOverview.await()
                        WireResponse(200, """{"resource":{"state":"none"}}""")
                    }
                    "/api/portal/security/current" -> {
                        securityReads++
                        securityGate?.await()
                        if (failSecurity) throw PortalFailure(PortalFailure.Reason.NETWORK)
                        WireResponse(200, """{"security":{"authenticatorBound":false,"initialAccount":false,
                            "passkeyEnabled":false,"passkeyExported":false,"setupRequired":false,
                            "safety":{"noAdminApi":true,"noPublicAdmin":true,"noRawSecretReturned":true}}}""")
                    }
                    else -> throw AssertionError("Unexpected synthetic route")
                }
            }
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val state = remember {
                AccountController(PortalRepository(wire, object : DeliveryStore {
                    override fun read(): DeliveryRecord? = null
                    override fun save(record: DeliveryRecord) = error("No delivery mutation")
                    override fun clear() = error("No delivery mutation")
                }, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L }, { now },
                    sessions = saved, profiles = MemoryLocalProfileStore()), scope)
            }
            account = state
            LaunchedEffect(state) { state.restoreAccount() }
            GccTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (visible) {
                        if (state.authenticated) AccountPanel(state)
                        SecurityStatusPanel(state, autoLoad = autoLoad, refreshRequest = refreshRequest)
                    }
                }
            }
        }
        compose.waitUntil(5_000) { account.authenticated && account.busy && overviewReads == 2 }
        compose.runOnIdle { assertEquals(0, securityReads); refreshRequest++ }
        compose.onNodeWithTag("security-refresh").assertDoesNotExist()
        releaseOverview.complete(Unit)
        compose.waitUntil(5_000) { securityReads == 1 && !account.busy }
        compose.onNodeWithTag("security-authenticator-status").assertTextEquals("未绑定")

        val original = account
        val releaseSecurity = CompletableDeferred<Unit>()
        compose.runOnIdle { failSecurity = true; securityGate = releaseSecurity; refreshRequest++ }
        compose.waitUntil(5_000) { securityReads == 2 && account.busy }
        repeat(2) { compose.runOnIdle { refreshRequest++ }; compose.waitForIdle() }
        compose.runOnIdle { assertSame(original, account); assertEquals(2, securityReads); releaseSecurity.complete(Unit) }
        compose.waitUntil(5_000) { securityReads == 2 && !account.busy && account.error != null }
        compose.onNodeWithText("网络连接失败，请重试").assertExists()
        compose.onNodeWithTag("account-refresh").assertDoesNotExist()
        compose.onNodeWithTag("security-refresh").assertDoesNotExist()
        compose.onNodeWithTag("account-loading").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertEquals(2, securityReads); assertNotNull(saved.read()); assertTrue(account.authenticated) }

        compose.runOnIdle { failSecurity = false; securityGate = null; refreshRequest++ }
        compose.waitUntil(5_000) { securityReads == 3 && !account.busy }
        compose.onNodeWithTag("security-authenticator-status").assertTextEquals("未绑定")
        compose.runOnIdle {
            assertNull(account.error); assertTrue(account.authenticated); assertNotNull(saved.read())
            assertEquals(2, overviewReads)
        }
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { visible = true }
        compose.waitUntil(5_000) { securityReads == 4 && !account.busy }
        compose.runOnIdle { autoLoad = false; refreshRequest++ }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(4, securityReads); assertSame(original, account) }
    }
}
