package com.gccwebos.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import com.gccwebos.android.commerce.*
import com.gccwebos.android.portal.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** Actual Shell/Mine composition and Activity stop/resume, memory network/recovery/opener only. */
class WalletResumeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val now = Instant.parse("2026-10-01T00:00:00Z")
    private class Recovery : CommerceRecovery {
        val records = mutableMapOf<Pair<String, String>, PendingCommerce>()
        override fun read(binding: String, kind: String) = records[binding to kind]
        override fun save(binding: String, value: PendingCommerce) { records[binding to value.kind] = value }
        override fun clear(binding: String, kind: String) { records.remove(binding to kind) }
    }
    private val recovery = Recovery()
    private val sessions = MemorySessionStore()
    private lateinit var account: AccountController
    private var gate: CompletableDeferred<Unit>? = null
    private var reads = 0
    private var posts = 0
    private var opens = 0
    private fun saved(id: String = "resume_ui_account_a", token: String = "resume_ui_token_a") =
        SavedSession(Secret(token), id, now.plusSeconds(1800), 1800000, now, 0)
    private val wallet = """{"wallet":{"status":"active","version":1,"balances":{"paidCoins":20,"bonusCoins":0,"rewardCoins":0},"spendableUnits":20,"heldUnits":0,"pendingUnits":0}}"""
    private val offers = """{"offers":[{"offerRef":"resume_ui_offer","quoteVersion":1,"paidCoinUnits":10,"amountMinor":1000,"currency":"CNY","currencyExponent":2,"paymentMethods":["alipay"]}]}"""
    private val order = """{"recharge":{"order":{"orderRef":"resume_ui_order","offerRef":"resume_ui_offer","quoteVersion":1,"expectedPaidCoinUnits":10,"amountMinor":1000,"currency":"CNY","currencyExponent":2,"adapterKey":"xsd_v1","paymentMethod":"alipay","status":"entry_ready","providerOrderRef":null,"nextAction":{"type":"redirect","url":"https://www.xsd8866.com/pay?fixture=synthetic","expiresAt":"2099-10-01T00:00:00.000Z"},"walletOperationRef":null,"walletLotRef":null,"expiresAt":"2099-10-01T00:00:00.000Z","createdAt":"2026-10-01T00:00:00.000Z","updatedAt":"2026-10-01T00:00:00.000Z"}}}"""
    private fun launch(hasProfile: Boolean = false, unknownPending: Boolean = false) {
        sessions.save(saved())
        if (unknownPending) recovery.save(sessionBinding(Secret("resume_ui_account_a")),
            PendingCommerce("recharge", null, "resume_ui_offer", "resume_ui_unknown_key"))
        val profiles = MemoryLocalProfileStore()
        if (hasProfile) {
            val url = Secret("https://sub.invalid/sub/a/resume-ui-profile")
            profiles.save(CachedSubscription(sessionBinding(url), """proxies:
  - name: fixture
    type: ss
    server: 192.0.2.31
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""".toByteArray(), now.plusSeconds(3600), url))
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val owner = remember { AccountController(PortalRepository(object : PortalTransport {
                override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                    assertNotNull(session)
                    if (body != null) posts++
                    return when (path) {
                        "/api/portal/resources/current/overview" -> {
                            assertNull(body); gate?.await(); WireResponse(200, """{"resource":{"state":"none"}}""")
                        }
                        "/api/portal/coins/wallet" -> { assertNull(body); WireResponse(200, wallet) }
                        "/api/portal/coins/recharge/offers" -> { assertNull(body); WireResponse(200, offers) }
                        "/api/portal/coins/recharge/orders" -> { assertNotNull(body); WireResponse(201, order) }
                        "/api/portal/coins/recharge/orders/resume_ui_order" -> {
                            assertNull(body); reads++; WireResponse(200, order)
                        }
                        else -> throw AssertionError("Unexpected fixture path: $path")
                    }
                }
            }, object : DeliveryStore {
                override fun read(): DeliveryRecord? = null
                override fun save(record: DeliveryRecord) = throw AssertionError("No delivery writes")
                override fun clear() = Unit
            }, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L }, { now },
                subscription = SubscriptionSource { throw AssertionError("No subscription requests") },
                sessions = sessions, profiles = profiles), scope) }
            account = owner
            var ready by remember { mutableStateOf(false) }
            LaunchedEffect(owner) {
                if (hasProfile) { owner.restoreLocal(); snapshotFlow { !owner.busy }.first { it } }
                owner.restoreAccount(); snapshotFlow { !owner.busy }.first { it }
                ready = true
            }
            if (ready) AppShell(owner, commerceRecoveryOverride = recovery, paymentOpenerOverride = { opens++ })
            else Text("offline fixture startup")
        }
        compose.waitUntil(5000) { ::account.isInitialized && account.authenticated && !account.busy }
        compose.onNodeWithTag("nav-Profile").performClick()
        compose.onNodeWithTag("mine-recharge").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("wallet-balance").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun createPending() {
        compose.onNodeWithTag("recharge-select-resume_ui_offer").performScrollTo().performClick()
        compose.onNodeWithTag("recharge-selected-pay").performScrollTo().performClick()
        compose.onNodeWithTag("recharge-confirm").performClick()
        compose.waitUntil(5000) { posts == 1 }
        // The new order goes straight to its payment entry (figures match the confirmed offer); there is no
        // 上一笔充值 card or second pay step on the recharge screen.
        compose.waitUntil(5000) { opens == 1 }
        compose.onNodeWithTag("recharge-order").assertDoesNotExist()
        compose.onNodeWithTag("recharge-pay").assertDoesNotExist()
    }
    private fun stopAndResume() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    }
    private fun assertSameSessionReturn(hasProfile: Boolean) {
        launch(hasProfile); createPending()
        compose.runOnIdle { assertEquals(1, opens); assertTrue(account.authenticated); assertEquals(hasProfile, account.localSubscription.hasProfile) }
        val before = reads
        val owner = account.commerceSessionIdentity
        compose.runOnIdle { gate = CompletableDeferred() }
        stopAndResume()
        compose.waitUntil(5000) { account.restoring && account.foregroundCheckPending }
        compose.onNodeWithTag("nav-Profile").assertExists()
        compose.onNodeWithTag("mine-back").assertExists()
        compose.onNodeWithTag("login-screen").assertDoesNotExist()
        // The same owner's foreground verification stays quiet. It revokes payment controls
        // until the GET completes without presenting a logged-out placeholder or losing recovery.
        compose.onNodeWithTag("commerce-signed-out").assertDoesNotExist()
        compose.onNodeWithTag("recharge-pay").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(account.retainingCommerceSession); assertSame(owner, account.commerceSessionIdentity)
            assertEquals(1, posts); assertEquals(1, opens); gate!!.complete(Unit)
        }
        compose.waitUntil(5000) { reads > before && account.authenticated && !account.busy }
        compose.onNodeWithTag("mine-back").assertExists()
        compose.onNodeWithTag("recharge-order").assertDoesNotExist()
        compose.runOnIdle {
            assertSame(owner, account.commerceSessionIdentity)
            assertEquals(1, posts); assertEquals(1, opens)
        }
    }
    @Test fun walletWithoutProfileSurvivesTheProductionActivityResumeAndOnlyRereadsPending() = assertSameSessionReturn(false)
    @Test fun walletWithProfileSurvivesTheProductionActivityResumeAndOnlyRereadsPending() = assertSameSessionReturn(true)

    @Test fun realTokenThenAccountReplacementResetMineOwnershipWithoutDeletingRecovery() {
        launch(); createPending()
        val binding = sessionBinding(Secret("resume_ui_account_a"))
        compose.runOnIdle { sessions.save(saved(token = "resume_ui_token_b")) }
        stopAndResume(); compose.waitUntil(5000) { account.authenticated && !account.busy }
        compose.onNodeWithTag("mine-welcome").assertExists()
        compose.onNodeWithTag("mine-back").assertDoesNotExist()
        compose.runOnIdle { assertNotNull(recovery.read(binding, "recharge")); sessions.save(saved("resume_ui_account_b", "resume_ui_token_c")) }
        stopAndResume(); compose.waitUntil(5000) { account.authenticated && !account.busy }
        compose.onNodeWithTag("mine-welcome").assertExists()
        compose.onNodeWithTag("mine-back").assertDoesNotExist()
        compose.onNodeWithTag("recharge-order").assertDoesNotExist()
        compose.runOnIdle { assertNotNull(recovery.read(binding, "recharge")); assertEquals(1, posts); assertEquals(0, opens) }
    }

    @Test fun unknownPendingResumeKeepsTheWalletWithoutPostingOrOpeningPayment() {
        launch(unknownPending = true)
        stopAndResume(); compose.waitUntil(5000) { account.authenticated && !account.busy }
        compose.onNodeWithTag("mine-back").assertExists()
        compose.onNodeWithTag("recharge-status").performScrollTo().assertTextEquals("提交结果尚未确认，可直接重新充值，或点下方查询。")
        compose.onNodeWithTag("recharge-pay").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, posts); assertEquals(0, reads); assertEquals(0, opens) }
    }
}
