package com.gccwebos.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import com.gccwebos.android.manualimport.ManualImportInputKind
import com.gccwebos.android.portal.*
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real repository/session/controller/UI boundaries; all material and transport are synthetic. */
class ManualImportAccountUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var account: AccountController
    private val now = Instant.parse("2026-10-04T00:00:00Z")
    private val actions = mutableListOf<ConnectionPowerAction>()
    private fun yaml() = """proxies:
  - name: Synthetic manual UI node
    type: ss
    server: 192.0.2.10
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""".toByteArray()

    private inner class Fixture(val signedIn: Boolean, val available: Boolean = false) {
        val sessions = MemorySessionStore().apply {
            if (signedIn) save(SavedSession(Secret("native_manual_fixture"), "manual_fixture_account",
                now.plusSeconds(1800), 1_800_000, now, 0))
        }
        val manual = ManualSourceRecord(kind = ManualSourceKind.None).use { MemoryManualSourceStore(it) }
        val reads = AtomicInteger()
        val modelReads = AtomicInteger()
        val downloads = AtomicInteger()
        var downloadGate: CompletableDeferred<Unit>? = null
        var remoteExpiry: Instant? = now.plusSeconds(600)
        var remoteUsage: SubscriptionUsageReport? = null
        val repository = PortalRepository(object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                // The account's own load path (#65) negotiates the delivery mode via
                // commerce/model; that read belongs to the account, never to a manual
                // import, and is served + counted so any extra traffic still fails.
                if (path == "/api/portal/commerce/model") {
                    assertNull(body); assertNotNull(session); modelReads.incrementAndGet()
                    return WireResponse(200, """{"commerceModel":"wallet_first_unique_v1"}""")
                }
                assertEquals("/api/portal/resources/current/overview", path)
                assertNull(body); assertNotNull(session); reads.incrementAndGet()
                return WireResponse(200, if (available) """{"resource":{"state":"available",
                    "resourceRef":"resource_${"a".repeat(64)}","plan":{"id":"synthetic_plan","durationDays":30,"monthlyTrafficGb":50},
                    "validUntil":"2099-10-04T00:00:00.000Z","setupAvailable":false,"usage":{"totalBytes":1000,
                    "usedBytes":100,"remainingBytes":900,"collectionStatus":"measured","updatedAt":"2026-10-04T00:00:00.000Z",
                    "cycleStartedAt":"2026-10-01T00:00:00.000Z","cycleEndsAt":"2026-11-01T00:00:00.000Z"}}}"""
                    else """{"resource":{"state":"none"}}""")
            }
        }, object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = error("Manual UI fixture must not claim a delivery")
            override fun clear() = Unit
        }, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L }, { now },
            subscription = object : SubscriptionSource {
                override suspend fun download(url: Secret) = read(url).bytes
                override suspend fun read(url: Secret): SubscriptionRead {
                    downloads.incrementAndGet(); downloadGate?.await()
                    return SubscriptionRead(yaml(), remoteExpiry, remoteUsage)
                }
            }, sessions = sessions, manualSources = manual)
    }

    private fun launch(fixture: Fixture) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            account = remember { AccountController(fixture.repository, scope, sourceCommitFence = { it() }) }
            LaunchedEffect(account) { account.restore() }
            AppShell(accountOverride = account, autoLoadCommerce = false,
                connectionControlState = ConnectionControlState(ConnectionPhase.Disconnected, canConnect = true),
                onConnectionPowerAction = { actions += it })
        }
        // Restore also schedules independent library/selection reads. Wait for the
        // same admission gates as begin(); Compose idleness alone does not join IO.
        compose.waitUntil(10_000) { ::account.isInitialized && !account.busy && !account.restoring &&
            !account.foregroundCheckPending && !account.clearing && !account.manualImport.busy &&
            !account.configurationLibrary.busy && !account.nodeSelection.busy &&
            !account.accountUsage.state.value.busy && !account.accountUsage.state.value.verifying &&
            account.authenticated == fixture.signedIn && account.localSubscription.sourceKind == ManualSourceKind.None }
    }

    @Test fun foregroundDuringImportDefersVerificationAndSettlesWithoutDroppingTheSavedLogin() {
        val f = Fixture(signedIn = true)
        launch(f)
        compose.runOnIdle {
            assertEquals(2, f.reads.get())
            f.downloadGate = CompletableDeferred()
            account.manualImport.open()
            account.manualImport.submit(requireNotNull(account.manualImport.begin()),
                "https://sub.invalid/sub/a/synthetic".toByteArray(), ManualImportInputKind.Link)
        }
        compose.waitUntil(10_000) { f.downloads.get() == 1 && account.manualImport.busy }
        compose.runOnIdle {
            val observer = AccountForegroundObserver(account)
            observer.onStateChanged(compose.activity, Lifecycle.Event.ON_STOP)
            observer.onStateChanged(compose.activity, Lifecycle.Event.ON_RESUME)
            assertTrue(account.foregroundCheckPending)
            assertTrue(account.authenticated)
            assertFalse(account.restoring); assertFalse(account.busy)
            assertEquals(2, f.reads.get())
            f.downloadGate!!.complete(Unit)
        }
        compose.waitUntil(10_000) { !account.manualImport.busy && account.authenticated && !account.busy &&
            !account.restoring && !account.foregroundCheckPending }
        compose.runOnIdle {
            assertEquals(4, f.reads.get()); assertNotNull(f.sessions.read())
            assertEquals("manual_fixture_account", account.authenticatedAccountId)
            // Verification gate canceled adoption; finishing it must still resume auth verification.
            assertEquals(ManualSourceKind.None, f.repository.localSubscriptions.sourceKind())
            assertTrue(actions.isEmpty())
        }
    }

    @Test fun validLocalSourceConnectsWithoutInventingAccountUsageAndOnlyAfterExplicitPowerClick() {
        val f = Fixture(signedIn = false)
        launch(f)
        compose.runOnIdle {
            account.manualImport.open()
            account.manualImport.submit(requireNotNull(account.manualImport.begin()), yaml(), ManualImportInputKind.Document)
        }
        compose.waitUntil(10_000) { !account.manualImport.busy && account.localSubscription.status == LocalSubscriptionStatus.READY }
        // The node card is one accessible button; its source label is inside merged semantics.
        compose.onNodeWithTag("connection-local-source", useUnmergedTree = true)
            .performScrollTo().assertTextEquals("本地配置")
        compose.onNodeWithTag("vpn-connect").performScrollTo().assertIsEnabled()
        compose.runOnIdle {
            assertFalse(account.authenticated); assertNull(account.accountUsage.state.value.resource)
            assertEquals(LocalSourceValidity.LocalNoServerExpiry, account.localSubscription.validity)
            assertNull(account.localSubscription.validUntil)
            // A local file has no subscription-reported usage.
            assertNull(account.localSubscription.usage)
            assertEquals(0, f.reads.get()); assertEquals(0, f.downloads.get()); assertTrue(actions.isEmpty())
        }
        // Current contract (AppShellTest C10/C11): signed-out + READY local config with no
        // subscription-reported usage hides the account usage card entirely — there is no
        // "暂未获取" card to scroll to in this state.
        compose.onNodeWithTag("account-usage-card").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-remaining").assertDoesNotExist()
        compose.onNodeWithTag("subscription-usage-source").assertDoesNotExist()
        compose.onNodeWithTag("vpn-connect").performClick()
        compose.runOnIdle { assertEquals(listOf(ConnectionPowerAction.Connect), actions) }
        compose.onNodeWithText("已连接", substring = false).assertDoesNotExist()
    }

    @Test fun signedOutRemoteImportShowsOnlyItsOwnReportedUsageWithoutAccountReads() {
        val f = Fixture(signedIn = false).apply { remoteUsage = SubscriptionUsageReport(1_073_741_824L, 2_147_483_648L, 10_737_418_240L) }
        launch(f)
        compose.runOnIdle {
            account.manualImport.open()
            account.manualImport.submit(requireNotNull(account.manualImport.begin()),
                "https://sub.invalid/sub/a/synthetic".toByteArray(), ManualImportInputKind.Link)
        }
        compose.waitUntil(10_000) { !account.manualImport.busy && account.localSubscription.status == LocalSubscriptionStatus.READY }
        compose.runOnIdle {
            assertFalse(account.authenticated); assertNull(account.accountUsage.state.value.resource)
            assertEquals(ManualSourceKind.ImportedRemote, account.localSubscription.sourceKind)
            assertEquals(10_737_418_240L, requireNotNull(account.localSubscription.usage).totalBytes)
            assertEquals(0, f.reads.get()); assertEquals(1, f.downloads.get())
        }
        compose.onNodeWithTag("account-usage-remaining").performScrollTo().assertTextEquals("7 GB")
        compose.onNodeWithTag("account-usage-used").assertTextEquals("已用 30%").assertContentDescriptionEquals("已用 3 GB，30%")
        compose.onNodeWithTag("account-usage-total").assertTextEquals("总量 10 GB")
        compose.onNodeWithTag("subscription-usage-source", useUnmergedTree = true).assertExists()
    }

    @Test fun availableAccountCannotSupplyExpiryToAnImportedRemoteLink() {
        val f = Fixture(signedIn = true, available = true).apply { remoteExpiry = null }
        launch(f)
        compose.onNodeWithTag("nav-Lines").performClick()
        compose.onNodeWithTag("nodes-import").performClick()
        compose.onNodeWithTag("manual-import-link").performClick()
        compose.onNodeWithTag("manual-link-input").performTextInput("https://sub.invalid/sub/a/synthetic")
        compose.onNodeWithTag("manual-import-confirm").performClick()
        compose.waitUntil(10_000) { f.downloads.get() == 1 && !account.manualImport.busy && account.manualImport.error != null }
        compose.onNodeWithTag("manual-import-error").assertTextEquals("订阅不可用或未提供有效期")
        compose.runOnIdle {
            assertTrue(account.accountUsage.state.value.resource is Resource.Available)
            // The nodes-page visit auto-activated the account row (#65) and its load
            // negotiated the delivery model once — served and counted by the fixture —
            // yet the account delivered nothing: still MISSING, with no expiry anywhere.
            // The rejected import borrowed none of it; its only download is its own.
            // (Calibration run 42950446054: reads=3, model=1, downloads=1.)
            assertEquals(ManualSourceKind.LegacyRemote, f.repository.localSubscriptions.sourceKind())
            assertEquals(LocalSubscriptionStatus.MISSING, account.localSubscription.status)
            assertNull(account.localSubscription.validUntil); assertTrue(actions.isEmpty())
            assertEquals(3, f.reads.get()); assertEquals(1, f.modelReads.get())
            assertEquals(1, f.downloads.get())
        }
        compose.onNodeWithTag("manual-import-cancel").performClick()
        compose.onNodeWithTag("nav-Connect").performClick()
        // This shared tag also identifies explicit account preparation.
        // Exercising it re-runs the account load negotiation but must still deliver
        // nothing, borrow no expiry for the rejected import and never power the VPN.
        compose.onNodeWithTag("vpn-connect").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { f.reads.get() == 4 && !account.busy }
        compose.runOnIdle {
            // (Calibration run 42950446054: reads=4, model=2, downloads=1.)
            assertEquals(ManualSourceKind.LegacyRemote, f.repository.localSubscriptions.sourceKind())
            assertEquals(LocalSubscriptionStatus.MISSING, account.localSubscription.status)
            assertNull(account.localSubscription.validUntil)
            assertEquals(2, f.modelReads.get()); assertEquals(1, f.downloads.get()); assertTrue(actions.isEmpty())
            assertEquals(ConnectionPowerAction.None, connectionOverviewPresentation(account.localSubscription, now,
                ConnectionControlState(ConnectionPhase.Disconnected, canConnect = true)).action)
        }
        compose.onNodeWithText("已连接", substring = false).assertDoesNotExist()
    }
}
