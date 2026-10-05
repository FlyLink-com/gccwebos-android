package com.gccwebos.android.portal

import com.gccwebos.android.core.*
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExternalManualImportTest {
    private val url = "https://subscription.example/api/v1/client/subscribe?token=synthetic"
    private fun yaml(host: String = "192.0.2.10") = """proxies:
  - name: Synthetic external node
    type: ss
    server: $host
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""".toByteArray()
    private inner class Fixture {
        var now = Instant.parse("2026-10-05T00:00:00Z")
        var until: Instant? = null
        var reads = 0
        var officialReads = 0
        var writes = 0
        var response = yaml()
        var beforeRead: suspend () -> Unit = {}
        val store = MemoryManualSourceStore()
        val profiles = MemoryLocalProfileStore()
        val delivery = object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = error("External import must not create delivery")
            override fun clear() = Unit
        }
        val source = object : SubscriptionSource {
            override suspend fun download(url: Secret) = read(url).bytes
            override suspend fun read(url: Secret): SubscriptionRead {
                reads++; beforeRead()
                return SubscriptionRead(response.copyOf(), until)
            }
        }
        private val officialSource = object : SubscriptionSource {
            override suspend fun download(url: Secret) = read(url).bytes
            override suspend fun read(url: Secret): SubscriptionRead {
                officialReads++; return SubscriptionRead(response.copyOf(), until)
            }
        }
        fun manager() = LocalSubscriptionManager(delivery, profiles, officialSource,
            OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { now },
            LocalSubscriptionAuthority(), manualSources = store, externalSource = source)
        val manager = manager()
        val gateway = OneClickConfigGateway(manager, PreparedConfigStore { writes++ })
        suspend fun import(value: String = url) = manager.importManual(value.toByteArray()) { it() }
    }

    @Test fun externalQueryUrlWithoutExpiryImportsAndRestoresAsExplicitUnknownValidity() = runTest {
        val f = Fixture(); val imported = f.import()
        assertEquals(LocalSubscriptionStatus.READY, imported.status)
        assertEquals("ExternalRemote", imported.sourceKind.name)
        assertEquals("ExternalUnreported", imported.validity::class.simpleName)
        assertNull(imported.validUntil); assertEquals(1, f.reads); assertEquals(1, imported.nodes.size)
        assertEquals(0, f.officialReads)
        assertNull(f.profiles.read()); assertNull(f.delivery.read())
        val restored = f.manager().restore()
        assertEquals(imported.sourceKind, restored.sourceKind)
        assertEquals(imported.validity, restored.validity); assertEquals(imported.nodes, restored.nodes)
        assertEquals(1, f.reads); assertEquals(0, f.writes)
    }

    @Test fun externalUnknownExpiryRequiresFreshReadAndOneShotActivationEveryTime() = runTest {
        val f = Fixture(); f.import()
        val ready = f.gateway.prepare(SelectionIntent.Choose) as ConfigPreparation.Ready
        assertEquals(3, f.reads)
        ready.config.use {
            assertNull(it.validUntil); assertTrue(it.activate { true })
            assertThrows(Exception::class.java) { it.activate { true } }
        }
        assertEquals(1, f.writes)
        f.beforeRead = { throw PortalFailure(PortalFailure.Reason.NETWORK) }
        assertTrue(f.gateway.prepare(SelectionIntent.Choose) is ConfigPreparation.Unavailable)
        assertEquals(4, f.reads); assertEquals(1, f.writes)
        assertTrue(f.manager.nodeSelectionSnapshot().choices.isEmpty())
    }

    @Test fun knownExpirySurvivesMissingMetadataAndCannotBeBorrowedByAnotherUrl() = runTest {
        val f = Fixture(); val until = f.now.plusSeconds(60); f.until = until; f.import()
        f.until = null
        assertEquals(until, f.manager.refresh().validUntil)
        f.now = until.plusSeconds(1)
        assertEquals(LocalSubscriptionStatus.EXPIRED, f.manager.refresh().status)
        assertTrue(f.gateway.prepare(SelectionIntent.Choose) is ConfigPreparation.Unavailable)
        val replacement = f.import("https://subscription.example/another?token=synthetic-new")
        assertNull(replacement.validUntil)
        assertEquals("ExternalUnreported", replacement.validity::class.simpleName)
    }

    @Test fun reimportMissingExpiryCannotResetKnownExpiryAfterPersistedRemoteFailure() = runTest {
        for ((failure, status) in listOf(
            PortalFailure.Reason.SUBSCRIPTION_EXPIRED to LocalSubscriptionStatus.EXPIRED,
            PortalFailure.Reason.SUBSCRIPTION_REVOKED to LocalSubscriptionStatus.INVALID_LINK,
        )) {
            val f = Fixture(); val until = f.now.plusSeconds(60); f.until = until; f.import()
            val revision = f.store.read()!!.use { it.revision }
            f.beforeRead = { throw PortalFailure(failure) }
            assertEquals(status, f.manager.refresh().status)
            f.store.read()!!.use { assertEquals(status, it.observedStatus); assertEquals(until, it.validUntil) }

            // The same URL now returns 200 with no expiry after the remembered expiry has elapsed.
            f.now = until.plusSeconds(1); f.until = null; f.beforeRead = {}
            try { f.import(); fail("A missing expiry reset an expired external source") }
            catch (rejected: PortalFailure) { assertEquals(PortalFailure.Reason.SUBSCRIPTION_EXPIRED, rejected.reason) }
            f.store.read()!!.use {
                assertEquals(revision, it.revision); assertEquals(until, it.validUntil)
                assertEquals(status, it.observedStatus)
            }
            assertEquals(3, f.reads); assertEquals(0, f.officialReads); assertEquals(0, f.writes)
            assertTrue(f.manager.nodeSelectionSnapshot().choices.isEmpty())
        }
    }

    @Test fun repositoryExternalImportUsesOnlyInjectedExternalSourceAndPreservesAccountStores() = runTest {
        val now = Instant.parse("2026-10-05T00:00:00Z")
        var portalCalls = 0; var officialReads = 0; var externalReads = 0; var sessionReads = 0
        val saved = SavedSession(Secret("native_synthetic"), "synthetic@example.invalid",
            now.plusSeconds(600), 600000, now, 0)
        val sessions = object : SessionStore {
            override fun read(): SavedSession { sessionReads++; return saved }
            override fun save(value: SavedSession) = error("External import must not update the account session")
            override fun clear() = error("External import must not clear the account session")
        }
        val sources = MemoryManualSourceStore()
        val profiles = MemoryLocalProfileStore()
        val delivery = MemoryDeliveryStore()
        val repository = PortalRepository(object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                portalCalls++; error("External import must not request the Portal")
            }
        }, delivery, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L }, { now },
            subscription = SubscriptionSource { officialReads++; error("External import used the official downloader") },
            sessions = sessions, profiles = profiles, manualSources = sources,
            externalSubscription = SubscriptionSource { requested ->
                externalReads++; assertEquals(url, requested.value); yaml()
            })
        val input = url.toByteArray()
        val snapshot = try { repository.localSubscriptions.importManual(input) { it() } }
            finally { input.fill(0) }
        assertEquals(LocalSubscriptionStatus.READY, snapshot.status)
        assertEquals(ManualSourceKind.ExternalRemote, snapshot.sourceKind)
        assertEquals(LocalSourceValidity.ExternalUnreported, snapshot.validity)
        sources.read()!!.use { assertEquals(ManualSourceKind.ExternalRemote, it.kind); assertNull(it.validUntil) }
        assertEquals(1, externalReads); assertEquals(0, officialReads); assertEquals(0, portalCalls)
        assertEquals(0, sessionReads)
        assertNull(profiles.read()); assertNull(delivery.read())
        assertSame(saved, sessions.read())
    }

    @Test fun expiredReplacementAndUnsupportedPayloadKeepOriginalSourceAndChoice() = runTest {
        val f = Fixture(); f.import()
        val revision = f.store.read()!!.use { it.revision }
        val choice = f.manager.nodeSelectionSnapshot().choices.single().selection
        f.until = f.now.minusSeconds(1)
        try { f.import(); fail("Expired external source accepted") } catch (_: PortalFailure) { }
        assertEquals(revision, f.store.read()!!.use { it.revision })
        assertSame(choice, f.manager.nodeSelectionSnapshot().choices.single().selection)
        f.until = null; f.response = "vmess://synthetic-only".toByteArray()
        try { f.import(); fail("Unsupported payload accepted") } catch (_: com.gccwebos.android.vpn.UnsafeProxyConfiguration) { }
        assertEquals(revision, f.store.read()!!.use { it.revision })
        assertSame(choice, f.manager.nodeSelectionSnapshot().choices.single().selection)
    }

    @Test fun lateExternalReadAfterClearCannotRestoreSourceOrNodes() = runTest {
        val f = Fixture(); f.import()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.beforeRead = { entered.complete(Unit); release.await() }
        val pending = async { f.import() }
        entered.await(); f.manager.unlink(); release.complete(Unit)
        try { pending.await(); fail("Late import survived clear") } catch (_: CancellationException) { }
        assertEquals(ManualSourceKind.None, f.manager.sourceKind())
        assertTrue(f.manager.nodeSelectionSnapshot().choices.isEmpty())
    }

    @Test fun controllerScanLinkAndDocumentUseIndependentExternalSource() = runTest {
        for (kind in com.gccwebos.android.manualimport.ManualImportInputKind.entries) {
            val f = Fixture(); val snapshots = mutableListOf<LocalSubscriptionSnapshot>()
            val controller = ManualImportController(f.manager, this, { true }, { it() }, { snapshots += it })
            controller.open(); val bytes = url.toByteArray()
            controller.submit(requireNotNull(controller.begin()), bytes, kind); runCurrent()
            assertNull(controller.error); assertFalse(controller.visible)
            assertEquals("ExternalRemote", snapshots.single().sourceKind.name)
            assertEquals(1, f.reads); assertEquals(0, f.writes); assertTrue(bytes.all { it == 0.toByte() })
            assertEquals(0, f.officialReads)
        }
    }

    @Test fun officialImportsKeepDedicatedDownloaderAndMandatoryExpiry() = runTest {
        val f = Fixture()
        try { f.import("https://sub.invalid/sub/a/synthetic"); fail("Official missing expiry accepted") }
        catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.SUBSCRIPTION_UNAVAILABLE, failure.reason) }
        assertEquals(1, f.officialReads); assertEquals(0, f.reads)
        f.until = f.now.plusSeconds(60)
        val snapshot = f.import("https://sub.invalid/sub/a/synthetic")
        assertEquals(ManualSourceKind.ImportedRemote, snapshot.sourceKind)
        assertEquals(LocalSourceValidity.RemoteExpiry(f.until), snapshot.validity)
        assertEquals(2, f.officialReads); assertEquals(0, f.reads)
    }

    @Test fun changedExpiryFencesAlreadyPreparedUnknownExpiryMaterial() = runTest {
        for (changeThroughRefresh in listOf(true, false)) {
            val f = Fixture(); f.import()
            val ready = f.gateway.prepare(SelectionIntent.Choose) as ConfigPreparation.Ready
            ready.config.use { prepared ->
                val until = f.now.plusSeconds(30)
                if (changeThroughRefresh) {
                    f.until = until; assertEquals(until, f.manager.refresh().validUntil)
                } else {
                    f.store.read()!!.use { old ->
                        val bytes = old.copyBytes()!!
                        try { ManualSourceRecord(old.revision, old.kind, bytes, old.url, until).use(f.store::save) }
                        finally { bytes.fill(0) }
                    }
                }
                assertThrows(PreparationRejected::class.java) { prepared.activate { true } }
                assertEquals(0, f.writes)
            }
        }
    }
}
