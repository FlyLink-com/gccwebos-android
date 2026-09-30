package com.gccwebos.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.portal.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Offline controller-to-Compose coverage; never contacts production or asks for VPN permission. */
class AutomaticSubscriptionUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var account: AccountController
    private val paths = mutableListOf<String>()
    private var downloads = 0

    private fun launch(none: Boolean = false, supported: Boolean = true, downloadFails: Boolean = false) {
        val now = Instant.parse("2026-09-30T00:00:00Z")
        val wire = object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                paths += path
                return when (path) {
                    "/api/portal/password/session" -> WireResponse(200, """{"session":{"sessionRef":"synthetic","accountId":"fixture@example.invalid","issuedAt":"2026-09-30T00:00:00.000Z","expiresAt":"2026-09-30T00:30:00.000Z"}}""")
                    "/api/portal/resources/current/overview" -> WireResponse(200, if (none) """{"resource":{"state":"none"}}""" else """{"resource":{"state":"available","plan":{"id":"synthetic","durationDays":365,"monthlyTrafficGb":10},"validUntil":"2099-09-30T00:00:00.000Z","setupAvailable":true,"resourceRef":"resource_${"a".repeat(64)}","usage":{"totalBytes":1000,"usedBytes":null,"remainingBytes":null,"collectionStatus":"pending","updatedAt":null,"cycleStartedAt":"2026-09-30T00:00:00.000Z","cycleEndsAt":"2026-10-30T00:00:00.000Z"}}}""")
                    "/api/portal/resources/current/setup" -> WireResponse(200, """{"delivery":{"handoffRef":"synthetic","kind":"subscription_url","subscriptionUrl":"https://sub.invalid/sub/a/synthetic"}}""")
                    "/api/portal/resources/current/setup/ack" -> WireResponse(204, "")
                    else -> throw AssertionError("Unexpected offline request")
                }
            }
        }
        val store = object : DeliveryStore {
            var value: DeliveryRecord? = null
            override fun read() = value
            override fun save(record: DeliveryRecord) { value = record }
            override fun clear() { value = null }
        }
        val repo = PortalRepository(wire, store, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L }, { now },
            SubscriptionSource {
                downloads++
                assertFalse(store.value!!.pendingAck)
                if (downloadFails) throw PortalFailure(PortalFailure.Reason.NETWORK)
                """proxies:
  - name: synthetic
    type: ${if (supported) "ss" else "vless"}
    server: 192.0.2.1
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""".toByteArray()
            })
        compose.setContent {
            val scope = rememberCoroutineScope()
            account = remember { AccountController(repo, scope).apply {
                this.account = "fixture@example.invalid"; password = "synthetic"; answer = "3"
                challenge = HumanChallenge("synthetic", "1 + 2")
            } }
            GccTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AccountPanel(account) } }
        }
        compose.runOnIdle { account.login() }
        compose.waitUntil(10_000) { account.authenticated && !account.busy }
    }
    @Test fun noPlanLoginShowsAuthoritativeEmptyStateWithoutClaiming() {
        launch(none = true)
        compose.onNodeWithText(subscriptionImportMessage(ImportStatus.NO_RESOURCE)).assertExists()
        compose.runOnIdle { assertEquals(0, downloads); assertFalse(paths.any { it.contains("/setup") }) }
    }
    @Test fun supportedFixtureImportsButNeverClaimsVpnConnected() {
        launch()
        compose.onNodeWithText(subscriptionImportMessage(ImportStatus.IMPORTED)).assertExists()
        compose.runOnIdle { assertEquals(1, downloads); assertTrue(account.deliveryReceived) }
        compose.onNodeWithText("已连接", substring = false).assertDoesNotExist()
    }
    @Test fun unsupportedFixtureRetainsReceiptAndShowsCompatibilityBlock() {
        launch(supported = false)
        compose.onNodeWithText(subscriptionImportMessage(ImportStatus.UNSUPPORTED_CONFIGURATION)).assertExists()
        compose.runOnIdle { assertTrue(account.deliveryReceived); assertNull(account.error) }
    }
    @Test fun downloadedFailureDoesNotHideDurableReceiptOrPretendImportSucceeded() {
        launch(downloadFails = true)
        compose.onNodeWithText("订阅已安全保存；自动导入尚未完成，可刷新重试").assertExists()
        compose.runOnIdle { assertTrue(account.deliveryReceived); assertNull(account.importStatus); assertNotNull(account.error) }
    }
}
