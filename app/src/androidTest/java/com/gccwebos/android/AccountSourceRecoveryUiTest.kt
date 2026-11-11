package com.gccwebos.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.gccwebos.android.manualimport.ManualImportInputKind
import com.gccwebos.android.portal.*
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real AppShell/controller/import authority with memory-only stores and synthetic transports.
 * Does not open a camera, file picker, network socket, VPN, or another package's data. */
class AccountSourceRecoveryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var account: AccountController
    private val now = Instant.parse("2026-10-04T00:00:00Z")
    private val powers = mutableListOf<ConnectionPowerAction>()
    private fun yaml() = """proxies:
  - name: Synthetic account recovery node
    type: ss
    server: 192.0.2.10
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""".toByteArray()

    private inner class Fixture(val signedIn: Boolean = true) {
        val manual = ManualSourceRecord(kind = ManualSourceKind.None).use { MemoryManualSourceStore(it) }
        val delivery = MemoryDeliveryStore()
        val profiles = MemoryLocalProfileStore()
        val sessions = MemorySessionStore().apply {
            if (signedIn) save(SavedSession(Secret("native_recovery_fixture"), "recovery_fixture_account",
                now.plusSeconds(1800), 1_800_000, now, 0))
        }
        val overviewReads = AtomicInteger()
        val modelReads = AtomicInteger()
        val setupReads = AtomicInteger()
        val acknowledgments = AtomicInteger()
        val downloads = AtomicInteger()
        val commits = AtomicInteger()
        var overviewGate: CompletableDeferred<Unit>? = null
        private val source = object : SubscriptionSource {
            override suspend fun download(url: Secret) = read(url).bytes
            override suspend fun read(url: Secret): SubscriptionRead {
                assertEquals("https://sub.invalid/sub/a/recovery-fixture", url.value)
                downloads.incrementAndGet()
                return SubscriptionRead(yaml(), now.plusSeconds(86400))
            }
        }
        val repository = PortalRepository(object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                assertEquals("native_recovery_fixture", session?.value)
                return when (path) {
                    "/api/portal/resources/current/overview" -> {
                        assertNull(body); overviewReads.incrementAndGet(); overviewGate?.await()
                        WireResponse(200, """{"resource":{"state":"available",
                            "resourceRef":"resource_${"a".repeat(64)}",
                            "plan":{"id":"recovery-fixture","durationDays":30,"monthlyTrafficGb":50},
                            "validUntil":"2099-10-04T00:00:00.000Z","setupAvailable":true,
                            "usage":{"totalBytes":1000,"usedBytes":100,"remainingBytes":900,
                            "collectionStatus":"measured","updatedAt":"2026-10-04T00:00:00.000Z",
                            "cycleStartedAt":"2026-10-01T00:00:00.000Z","cycleEndsAt":"2026-11-01T00:00:00.000Z"}}}""")
                    }
                    "/api/portal/commerce/model" -> {
                        assertNull(body); modelReads.incrementAndGet()
                        WireResponse(200, """{"commerceModel":"wallet_first_unique_v1"}""")
                    }
                    "/api/portal/resources/current/setup" -> {
                        assertNotNull(body); setupReads.incrementAndGet()
                        WireResponse(200, """{"delivery":{"handoffRef":"recovery_fixture_handoff",
                            "kind":"subscription_url","subscriptionUrl":"https://sub.invalid/sub/a/recovery-fixture"}}""")
                    }
                    "/api/portal/resources/current/setup/ack" -> {
                        assertNotNull(body); acknowledgments.incrementAndGet(); WireResponse(204, "")
                    }
                    else -> throw AssertionError("Unexpected synthetic recovery endpoint")
                }
            }
        }, delivery, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L }, { now },
            subscription = source, sessions = sessions, profiles = profiles, manualSources = manual,
            externalSubscription = source)
    }

    private fun idle() = ::account.isInitialized && !account.busy && !account.restoring &&
        !account.foregroundCheckPending && !account.manualImport.busy && !account.configurationLibrary.busy &&
        !account.nodeSelection.busy && !account.accountUsage.state.value.busy && !account.accountUsage.state.value.verifying

    private fun awaitIdle() { compose.waitUntil(10_000) { idle() } }

    /** [autoLoad] false: the account does not fetch its own subscription (#65), to exercise the explicit recovery paths. */
    private fun launch(f: Fixture, autoLoad: Boolean = true) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            account = remember { AccountController(f.repository, scope, sourceCommitFence = { action ->
                action(); f.commits.incrementAndGet()
            }).apply { accountLoadEnabled = autoLoad } }
            LaunchedEffect(account) { account.restore() }
            AppShell(accountOverride = account, repositoryOverride = f.repository,
                autoLoadCommerce = false, autoRefreshPages = false,
                connectionControlState = ConnectionControlState(ConnectionPhase.Disconnected, canConnect = true),
                onConnectionPowerAction = { powers += it })
        }
        compose.waitUntil(10_000) { idle() && account.authenticated == f.signedIn &&
            account.localSubscription.sourceKind == ManualSourceKind.None }
    }

    private fun openMenu() {
        compose.onNodeWithTag("nav-Lines").performClick()
        awaitIdle()
        compose.onNodeWithTag("nodes-import").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("manual-import-host").assertExists()
        listOf("scan", "document", "link").forEach {
            compose.onNodeWithTag("manual-import-$it").assertExists()
        }
    }

    private fun assertUnclaimed(f: Fixture) = compose.runOnIdle {
        assertNull(f.delivery.read()); assertNull(f.profiles.read())
        assertEquals(0, f.modelReads.get()); assertEquals(0, f.setupReads.get())
        assertEquals(0, f.acknowledgments.get()); assertEquals(0, f.downloads.get())
        assertTrue(powers.isEmpty())
    }

    @Test fun emptySelectionRequiresExplicitAccountRecoveryBeforeOneClickCanImport() {
        // The account's automatic load (#65) is off here: activation still happens on the nodes page, but it must not fetch or claim anything.
        val f = Fixture(); launch(f, autoLoad = false)
        compose.runOnIdle {
            // Ruling (2026-11-10): a signed-in account always has exactly one official
            // library row — present, inactive and not yet loaded; download happens
            // only on activation. It is never joined by invented entries.
            val items = account.configurationLibrary.snapshot.items
            assertEquals(1, items.size)
            assertEquals(ManualSourceKind.LegacyRemote, items.single().kind)
            assertFalse(items.single().active)
            assertEquals(LocalSubscriptionStatus.MISSING, items.single().status)
            assertTrue(items.single().nodes.isEmpty())
            assertEquals(2, f.overviewReads.get())
        }
        // An ordinary connection attempt cannot silently undo the user's None selection.
        compose.onNodeWithTag("vpn-connect").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { idle() && f.overviewReads.get() == 3 }
        assertUnclaimed(f)
        // Current contract (#65): opening the nodes page already activates the
        // signed-in account row while nothing is chosen, so the import menu no
        // longer offers a separate use-account entry. Activation alone fetches
        // nothing while the automatic load is off: recovery is still not a claim.
        openMenu()
        compose.onNodeWithTag("manual-use-account").assertDoesNotExist()
        compose.onNodeWithTag("manual-import-cancel").performClick()
        compose.runOnIdle {
            assertEquals(ManualSourceKind.LegacyRemote, account.localSubscription.sourceKind)
        }
        assertUnclaimed(f)
        compose.runOnIdle {
            assertEquals(1, f.commits.get())
            assertEquals(LocalSubscriptionStatus.MISSING, account.localSubscription.status)
            assertEquals(3, f.overviewReads.get())
        }
        openMenu()
        compose.onNodeWithTag("manual-use-account").assertDoesNotExist()
        compose.onNodeWithTag("manual-import-cancel").performClick()
        // Recovery only selects account authority. The separate explicit power action imports it.
        compose.onNodeWithTag("nav-Connect").performClick()
        compose.onNodeWithTag("vpn-connect").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { idle() && powers.size == 1 }
        compose.runOnIdle {
            assertEquals(listOf(ConnectionPowerAction.Connect), powers)
            assertEquals(ImportStatus.IMPORTED, account.importStatus)
            assertEquals(LocalSubscriptionStatus.READY, account.localSubscription.status)
            assertEquals(ManualSourceKind.LegacyRemote, account.localSubscription.sourceKind)
            assertTrue(account.localSubscription.hasCapability)
            assertEquals(1, account.nodes.size)
            assertEquals(1, f.modelReads.get()); assertEquals(1, f.setupReads.get())
            assertEquals(1, f.acknowledgments.get()); assertEquals(1, f.downloads.get())
            assertNotNull(f.delivery.read()); assertNotNull(f.sessions.read())
            assertFalse(requireNotNull(f.delivery.read()).pendingAck)
        }
    }

    @Test fun signedOutEmptySelectionHasOnlyTheThreeImportMethods() {
        val f = Fixture(signedIn = false); launch(f); openMenu()
        compose.onNodeWithTag("manual-use-account").assertDoesNotExist()
        compose.onNodeWithTag("manual-import-cancel").performClick()
        compose.runOnIdle {
            assertEquals(ManualSourceKind.None, f.repository.localSubscriptions.sourceKind())
            assertEquals(0, f.overviewReads.get()); assertEquals(0, f.commits.get())
        }
        assertUnclaimed(f)
    }

    @Test fun manualSourcesImportWithoutAccountSetupAndStaySavedWhileTheAccountRowAutoActivates() {
        // Current contract (ruling 2026-11-10, option A): a manual import never reads the
        // account's setup or delivery and never downloads the account subscription.
        // Afterwards the nodes page auto-activates the signed-in account row while no
        // node is chosen (#65); with the automatic load disabled here that activation
        // fetches nothing. The imported entries stay saved in the library throughout.
        val f = Fixture(); launch(f, autoLoad = false)
        for (kind in listOf(ManualImportInputKind.Document, ManualImportInputKind.Link)) {
            openMenu()
            compose.runOnIdle {
                val bytes = if (kind == ManualImportInputKind.Document) yaml()
                    else "https://sub.invalid/sub/a/recovery-fixture".toByteArray()
                account.manualImport.submit(requireNotNull(account.manualImport.begin()), bytes, kind)
                assertTrue(bytes.all { it == 0.toByte() })
            }
            compose.waitUntil(10_000) { idle() && !account.manualImport.visible &&
                account.localSubscription.status == LocalSubscriptionStatus.READY }
            val expected = if (kind == ManualImportInputKind.Document) ManualSourceKind.ImportedLocal else ManualSourceKind.ImportedRemote
            // Let the visit settle: the account row auto-activates (#65) without any fetch.
            compose.waitUntil(10_000) { idle() &&
                account.localSubscription.sourceKind == ManualSourceKind.LegacyRemote }
            openMenu()
            compose.onNodeWithTag("manual-use-account").assertDoesNotExist()
            compose.onNodeWithTag("manual-import-cancel").performClick()
            compose.runOnIdle {
                assertEquals(ManualSourceKind.LegacyRemote, account.localSubscription.sourceKind)
                assertEquals(ManualSourceKind.LegacyRemote, f.repository.localSubscriptions.sourceKind())
                assertEquals(LocalSubscriptionStatus.MISSING, account.localSubscription.status)
                // The imported entry stays saved under its own kind, inactive.
                assertTrue(account.configurationLibrary.snapshot.items.any { it.kind == expected && !it.active })
                assertEquals(0, f.setupReads.get()); assertEquals(0, f.acknowledgments.get())
                assertTrue(powers.isEmpty())
            }
        }
        compose.runOnIdle {
            System.out.println("ISOCALIB iso2 items=" + account.configurationLibrary.snapshot.items.size +
                " commits=" + f.commits.get() + " downloads=" + f.downloads.get())
            assertEquals(1, f.downloads.get())
        }
    }

    /**
     * Signed in with nothing selected, the nodes page loads the account's own subscription by itself (#65), so the explicit
     * 「使用账户订阅」 recovery entry has nothing left to recover; it only appears while a manual source is the active one.
     * A busy account read still disables every entry of the import menu, and cancelling changes nothing.
     */
    @Test fun autoLoadedAccountNeedsNoRecoveryAndABusyAccountReadDisablesTheImportMenu() {
        val f = Fixture(); launch(f)
        compose.onNodeWithTag("nav-Lines").performClick()
        compose.waitUntil(10_000) { f.acknowledgments.get() == 1 && idle() }
        compose.runOnIdle {
            assertEquals(1, f.setupReads.get()); assertEquals(1, f.downloads.get())
            assertTrue(powers.isEmpty())
        }
        compose.onNodeWithTag("nodes-import").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("manual-import-host").assertExists()
        compose.onNodeWithTag("manual-use-account").assertDoesNotExist()
        compose.onNodeWithTag("manual-import-scan").assertIsEnabled()
        val gate = CompletableDeferred<Unit>()
        val reads = f.overviewReads.get()
        try {
            compose.runOnIdle { f.overviewGate = gate; account.refreshAccountUsage() }
            compose.waitUntil(10_000) { account.accountUsage.state.value.busy && f.overviewReads.get() > reads }
            compose.onNodeWithTag("manual-import-scan").assertIsNotEnabled()
            compose.onNodeWithTag("manual-import-document").assertIsNotEnabled()
            compose.onNodeWithTag("manual-import-cancel").performClick()
            compose.onNodeWithTag("manual-import-host").assertDoesNotExist()
            compose.runOnIdle { assertFalse(account.manualImport.busy); assertTrue(powers.isEmpty()) }
        } finally { gate.complete(Unit) }
        awaitIdle()
        compose.runOnIdle { assertEquals(1, f.setupReads.get()); assertTrue(powers.isEmpty()) }
    }
}
