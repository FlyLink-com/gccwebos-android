package com.gccwebos.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsProperties
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

    @Test fun signedOutMineUsesTheNormalLayoutWithLoginPromptsAndInertWalletActions() {
        launch()
        compose.onNodeWithTag("mine-signed-out").assertExists()
        compose.onNodeWithTag("login-screen").assertDoesNotExist()
        compose.onNodeWithTag("mine-login-status").assertTextEquals("未登录")
        compose.onNodeWithTag("mine-account-identity").assertDoesNotExist()
        compose.onNodeWithTag("mine-current-plan").assertDoesNotExist()
        compose.onNodeWithTag("mine-go-login").assertTextEquals("去登录").assertIsEnabled()
        compose.onNodeWithTag("wallet-balance-signed-out").assertTextEquals("登录查看你的余额")
        compose.onNodeWithTag("wallet-balance").assertDoesNotExist()
        for (tag in listOf("mine-recharge", "mine-ledger")) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsNotEnabled()
            assertTrue(compose.onNodeWithTag(tag).fetchSemanticsNode().config.contains(SemanticsProperties.Disabled))
            compose.onNodeWithTag(tag).performClick()
        }
        compose.onNodeWithTag("mine-signed-out").assertExists()
        compose.onNodeWithTag("mine-back").assertDoesNotExist()
        compose.onNodeWithText("钱包充值").assertExists()
        compose.onNodeWithText("账单明细").assertExists()
        compose.onNodeWithTag("mine-signout").assertDoesNotExist()
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
        compose.onNodeWithTag("mine-wallet").performScrollTo().performClick()
        compose.onNodeWithTag("login-screen").assertExists()
    }
}
