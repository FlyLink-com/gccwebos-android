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
    private val writePaths = mutableListOf<String>()
    private val powerActions = mutableListOf<ConnectionPowerAction>()
    private var resourceMissing = false
    private var downloads = 0

    private fun launch(none: Boolean = false, supported: Boolean = true, downloadFails: Boolean = false, source: String? = null,
        shell: Boolean = false) {
        resourceMissing = none
        val now = Instant.parse("2026-09-30T00:00:00Z")
        val wire = object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                paths += path
                if (body != null) writePaths += path
                return when (path) {
                    "/api/portal/password/session" -> WireResponse(200, """{"session":{"sessionRef":"synthetic","accountId":"fixture@example.invalid","issuedAt":"2026-09-30T00:00:00.000Z","expiresAt":"2026-09-30T00:30:00.000Z"}}""")
                    "/api/portal/resources/current/overview" -> WireResponse(200, if (resourceMissing) """{"resource":{"state":"none"}}""" else """{"resource":{"state":"available","plan":{"id":"synthetic","durationDays":365,"monthlyTrafficGb":10},"validUntil":"2099-09-30T00:00:00.000Z","setupAvailable":true,"resourceRef":"resource_${"a".repeat(64)}","usage":{"totalBytes":1000,"usedBytes":null,"remainingBytes":null,"collectionStatus":"pending","updatedAt":null,"cycleStartedAt":"2026-09-30T00:00:00.000Z","cycleEndsAt":"2026-10-30T00:00:00.000Z"}}}""")
                    "/api/portal/commerce/model" -> {
                        assertNull(body); assertNotNull(session)
                        WireResponse(200, """{"commerceModel":"wallet_first_unique_v1"}""")
                    }
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
                (source ?: """proxies:
  - name: synthetic
    type: ${if (supported) "ss" else "vless"}
    server: 192.0.2.1
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""").toByteArray()
            })
        compose.setContent {
            val scope = rememberCoroutineScope()
            account = remember { AccountController(repo, scope).apply {
                this.account = "fixture@example.invalid"; password = "synthetic"; answer = "3"
                challenge = HumanChallenge("synthetic", "1 + 2")
            } }
            if (shell) AppShell(account, autoLoadCommerce = false, autoRefreshPages = true,
                connectionControlState = ConnectionControlState(phase = ConnectionPhase.Disconnected),
                onConnectionPowerAction = { powerActions += it })
            else GccTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AccountPanel(account) } }
        }
        compose.runOnIdle { account.login() }
        compose.waitUntil(10_000) { account.authenticated && !account.busy }
    }
    private fun importedProtocol(type: String, fields: String) {
        launch(source = """unified-delay: true
profile: {store-selected: true}
proxies:
  - name: synthetic
    type: $type
    server: 192.0.2.10
    port: 443
""" + fields.trimIndent().prependIndent("    ") + "\n")
        compose.onNodeWithText(subscriptionImportMessage(ImportStatus.IMPORTED)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(ImportStatus.IMPORTED, account.importStatus)
            assertEquals(1, downloads); assertTrue(account.deliveryReceived)
        }
        compose.onNodeWithText("已连接", substring = false).assertDoesNotExist()
    }
    @Test fun vlessTlsImportsOfflineWithoutClaimingConnection() = importedProtocol("vless", """
        uuid: 11111111-1111-4111-8111-111111111111
        tls: true
        network: tcp
        servername: fixture.example.invalid
        udp: false
    """)
    @Test fun vlessRealityImportsOfflineWithoutClaimingConnection() = importedProtocol("vless", """
        uuid: 11111111-1111-4111-8111-111111111111
        tls: true
        servername: fixture.example.invalid
        client-fingerprint: chrome
        reality-opts:
          public-key: AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE
          short-id: "0123456789abcdef"
    """)
    @Test fun hysteria2ImportsOfflineWithoutClaimingConnection() = importedProtocol("hysteria2", """
        password: synthetic-only
        sni: fixture.example.invalid
        up: "100 Mbps"
    """)
    @Test fun tuicV5ImportsOfflineWithoutClaimingConnection() = importedProtocol("tuic", """
        uuid: 22222222-2222-4222-8222-222222222222
        password: synthetic-only
        sni: fixture.example.invalid
    """)
    @Test fun loginDownloadsAndImportsTuicDefaultOptionsWithoutClaimingConnection() {
        importedProtocol("tuic", """
            uuid: 22222222-2222-4222-8222-222222222222
            password: synthetic-only
            sni: fixture.example.invalid
            request-timeout: 8000
            heartbeat-interval: 10000
            max-udp-relay-packet-size: 1252
            max-open-streams: 100
            disable-sni: false
            reduce-rtt: false
            fast-open: false
        """)
        compose.runOnIdle {
            assertEquals(ImportStatus.IMPORTED, account.importStatus)
            assertNull(account.importRejection)
            assertEquals(1, account.nodes.size)
            assertTrue(account.nodes.single().supported)
        }
    }
    @Test fun nonDefaultTuicHeartbeatKeepsTheDownloadedReceiptAndBlocksImport() {
        launch(source = """proxies:
  - name: synthetic
    type: tuic
    server: 192.0.2.10
    port: 443
    uuid: 22222222-2222-4222-8222-222222222222
    password: synthetic-only
    sni: fixture.example.invalid
    heartbeat-interval: 10001
""")
        compose.onNodeWithText(subscriptionImportMessage(ImportStatus.UNSUPPORTED_CONFIGURATION)).assertDoesNotExist()
        compose.onNodeWithText("节点心跳间隔选项暂不受支持").assertExists()
        compose.onNodeWithText("已连接", substring = false).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(ImportStatus.UNSUPPORTED_CONFIGURATION, account.importStatus)
            assertEquals(1, downloads)
            assertTrue(account.deliveryReceived)
            assertNull(account.error)
            assertEquals(com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.NodeOptionHeartbeatInterval,
                account.importRejection)
        }
    }
    @Test fun noPlanLoginShowsAuthoritativeEmptyStateWithoutClaiming() {
        launch(none = true)
        compose.onNodeWithText("暂无已发放资源").assertExists()
        compose.onNodeWithText(subscriptionImportMessage(ImportStatus.NO_RESOURCE)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(ImportStatus.NO_RESOURCE, account.importStatus)
            assertEquals(0, downloads); assertFalse(paths.any { it.contains("/setup") })
        }
    }
    /**
     * Signed in with nothing selected, opening the nodes page loads the account's own subscription (#65): the overview read,
     * then the setup request and its acknowledgement. That is a page load, not a connection: no power action, no
     * "已连接", and no node is chosen for the user.
     */
    @Test fun nodeNavigationLoadsTheAccountSubscriptionWithoutConnectingOrSelectingANode() {
        launch(none = true, shell = true)
        compose.runOnIdle {
            assertTrue(account.authenticated)
            assertEquals(Resource.None, account.resource)
            assertEquals(listOf("/api/portal/password/session", "/api/portal/resources/current/overview"), paths)
            assertEquals(listOf("/api/portal/password/session"), writePaths)
            assertFalse(account.localSubscription.hasCapability)
            assertFalse(account.localSubscription.hasProfile)
            resourceMissing = false
            paths.clear(); writePaths.clear()
        }
        compose.onNodeWithTag("nav-Lines").performClick()
        compose.waitUntil(10_000) {
            account.accountUsage.state.value.resource is Resource.Available &&
                !account.busy && !account.accountUsage.state.value.busy && account.deliveryReceived
        }
        compose.onNodeWithTag("page-Lines").assertIsDisplayed()
        compose.onNodeWithText("已连接", substring = false).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue("Opening the nodes page requests the account delivery", paths.any { it == "/api/portal/resources/current/setup" })
            assertTrue("and acknowledges it", paths.any { it == "/api/portal/resources/current/setup/ack" })
            assertTrue("Only the delivery request and its acknowledgement write",
                writePaths.isNotEmpty() && writePaths.all { it.startsWith("/api/portal/resources/current/setup") })
            assertEquals(1, downloads)
            assertTrue(account.deliveryReceived)
            assertTrue(account.localSubscription.hasProfile)
            assertNull("The page load never picks a node for the user", account.nodeSelection.snapshot.selected)
            assertTrue(powerActions.isEmpty())
        }
    }
    @Test fun supportedFixtureImportsButNeverClaimsVpnConnected() {
        launch()
        compose.onNodeWithText(subscriptionImportMessage(ImportStatus.IMPORTED)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(ImportStatus.IMPORTED, account.importStatus)
            assertEquals(1, downloads); assertTrue(account.deliveryReceived)
        }
        compose.onNodeWithText("已连接", substring = false).assertDoesNotExist()
    }
    @Test fun unsupportedFixtureRetainsReceiptAndShowsCompatibilityBlock() {
        launch(supported = false)
        val message = compose.runOnIdle {
            assertEquals(ImportStatus.UNSUPPORTED_CONFIGURATION, account.importStatus)
            assertTrue(account.deliveryReceived); assertNull(account.error)
            account.importRejection?.let(::subscriptionRejectionMessage) ?: "订阅配置暂不兼容"
        }
        compose.onNodeWithText(message).assertExists()
        compose.onNodeWithText("已连接", substring = false).assertDoesNotExist()
    }
    @Test fun downloadedFailureDoesNotHideDurableReceiptOrPretendImportSucceeded() {
        launch(downloadFails = true)
        compose.onNodeWithText("订阅已安全保存；自动导入尚未完成，可刷新重试").assertDoesNotExist()
        val message = compose.runOnIdle {
            assertTrue(account.deliveryReceived); assertNull(account.importStatus)
            requireNotNull(account.error)
        }
        compose.onNodeWithText(message).assertExists()
        compose.onNodeWithText("已连接", substring = false).assertDoesNotExist()
    }
}
