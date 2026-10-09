package com.gccwebos.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.gccwebos.android.portal.*
import java.time.Instant
import java.util.Collections
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SignedOutMineUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val at = Instant.parse("2026-09-30T00:00:00Z")
    private val policy = OriginPolicy("https://portal.invalid", setOf("https://sub.invalid"))
    private val empty = object : DeliveryStore {
        override fun read(): DeliveryRecord? = null
        override fun save(record: DeliveryRecord) = Unit
        override fun clear() = Unit
    }
    private val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private lateinit var state: AccountController

    private fun launch() {
        val wire = object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                paths += path
                return if (path.contains("challenge")) WireResponse(201,
                    """{"challenge":{"challengeRef":"synthetic","type":"math","prompt":"1 + 2","expiresInSeconds":3600}}""")
                else WireResponse(401, "")
            }
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            state = remember { AccountController(PortalRepository(wire, empty, policy, {0}, {at},
                sessions = MemorySessionStore()), scope).apply { restore() } }
            AppShell(state, autoLoadCommerce = true)
        }
        compose.waitUntil(10_000) { !state.busy && !state.restoring }
        compose.onNodeWithTag("nav-Profile").performClick()
    }

    @Test fun signedOutMineShowsOneLoginActionAndHidesAccountOnlyEntries() {
        launch()
        compose.onNodeWithTag("mine-signed-out").assertExists()
        compose.onNodeWithTag("login-screen").assertDoesNotExist()
        compose.onNodeWithTag("mine-login-status").assertTextEquals("未登录")
        compose.onNodeWithTag("mine-account-identity").assertDoesNotExist()
        compose.onNodeWithTag("mine-current-plan").assertDoesNotExist()
        compose.onNodeWithTag("mine-go-login").assertTextEquals("去登录").assertIsEnabled()
        compose.onNodeWithTag("wallet-balance-signed-out").assertTextEquals("登录后查看余额")
        compose.onNodeWithTag("wallet-signed-out-hint").assertTextEquals("充值与账单明细登录后使用")
        compose.onNodeWithTag("wallet-balance").assertDoesNotExist()
        // 去登录 is the only action in the wallet area: no inert recharge / ledger buttons.
        for (tag in listOf("mine-recharge", "mine-ledger")) compose.onNodeWithTag(tag).assertDoesNotExist()
        compose.onNodeWithTag("mine-signed-out").assertExists()
        compose.onNodeWithTag("mine-back").assertDoesNotExist()
        compose.onNodeWithTag("mine-history").assertTextContains("登录后查看订单与充值记录", substring = true)
        compose.onNodeWithTag("mine-signout").assertDoesNotExist()
        // The invite banner is a signed-in surface; signed out it is not shown at all.
        compose.onNodeWithTag("mine-referral-banner").assertDoesNotExist()
        compose.onNodeWithTag("mine-referral").assertDoesNotExist()
        compose.onNodeWithTag("mine-telegram").assert(hasAnyAncestor(hasTestTag("mine-menu")))
        compose.onNodeWithTag("mine-security").assert(hasAnyAncestor(hasTestTag("mine-functions")))
        compose.onNodeWithTag("mine-wallet").assertDoesNotExist() // The balance card already covers the wallet.
        compose.onNodeWithTag("mine-copy-id").assertDoesNotExist()
        compose.onNodeWithTag("mine-renew").assertDoesNotExist()
        compose.onNodeWithTag("mine-avatar").assertExists()
        compose.onNodeWithTag("mine-avatar-initial", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("mine-topbar-title").assertTextEquals("我的")
        compose.onNodeWithTag("brand-name").assertDoesNotExist()
        compose.waitForIdle()
        // Only the anonymous startup paths may run: no wallet, ledger, order, referral or overview read.
        val forbidden = listOf("wallet", "ledger", "order", "recharge", "referral", "overview", "commerce")
        assertTrue("unexpected reads: $paths", paths.none { p -> forbidden.any { p.contains(it) } })
    }

    @Test fun goLoginOpensTheExistingFormAndBackReturnsToSignedOutMine() {
        launch()
        compose.onNodeWithTag("mine-go-login").performClick()
        compose.onNodeWithTag("login-screen").assertExists()
        compose.onNodeWithTag("mine-signed-out").assertDoesNotExist()
        compose.waitUntil(10_000) { !state.busy && !state.restoring }
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.onNodeWithTag("mine-signed-out").assertExists()
        // Retapping the tab after an explicit request returns to the Mine layout, not the form.
        compose.onNodeWithTag("mine-go-login").performClick()
        compose.onNodeWithTag("login-screen").assertExists()
        compose.onNodeWithTag("nav-Profile").performClick()
        compose.onNodeWithTag("mine-signed-out").assertExists()
    }

    @Test fun signedOutMenuEntriesThatNeedAnAccountOpenTheLoginForm() {
        launch()
        compose.onNodeWithTag("mine-history").performScrollTo().performClick()
        compose.onNodeWithTag("login-screen").assertExists()
    }
}
