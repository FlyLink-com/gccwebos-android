package com.gccwebos.android.portal

import com.gccwebos.android.core.*
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ManualSourceAuthorityTest {
    private val now = Instant.parse("2026-10-04T00:00:00Z")
    private val policy = OriginPolicy("https://portal.invalid", setOf("https://sub.invalid"))
    private val url = Secret("https://sub.invalid/sub/a/synthetic")
    private fun yaml(host: String = "192.0.2.10") = """proxies:
  - name: Synthetic imported node
    type: ss
    server: $host
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""".toByteArray()
    private class Delivery : DeliveryStore {
        var value: DeliveryRecord? = null
        override fun read() = value
        override fun save(record: DeliveryRecord) { value = record }
        override fun clear() { value = null }
    }
    private inner class Fixture(val store: ManualSourceStore = MemoryManualSourceStore()) {
        val delivery = Delivery()
        val profiles = MemoryLocalProfileStore()
        var downloads = 0
        var until: Instant? = now.plusSeconds(600)
        var response = yaml()
        var failure = false
        var beforeRead: suspend () -> Unit = {}
        val manager = LocalSubscriptionManager(delivery, profiles, object : SubscriptionSource {
            override suspend fun download(url: Secret) = read(url).bytes
            override suspend fun read(url: Secret): SubscriptionRead {
                downloads++; beforeRead()
                if (failure) throw PortalFailure(PortalFailure.Reason.NETWORK)
                return SubscriptionRead(response.copyOf(), until)
            }
        }, policy, { now }, LocalSubscriptionAuthority(), manualSources = store)
        var writes = 0
        val gateway = OneClickConfigGateway(manager, PreparedConfigStore { writes++ })
        suspend fun local(bytes: ByteArray = yaml()) = manager.importManual(bytes) { it() }
        suspend fun remote(value: Secret = url) = manager.importManual(value.value.toByteArray()) { it() }
    }
    @Test fun localImportHasTypedLocalValidityAndNeverTouchesLegacyOrNetwork() = runTest {
        val f = Fixture()
        val result = f.local()
        assertEquals(ManualSourceKind.ImportedLocal, result.sourceKind)
        assertEquals(LocalSourceValidity.LocalNoServerExpiry, result.validity)
        assertNull(result.validUntil); assertEquals(0, f.downloads)
        assertNull(f.profiles.read()); assertNull(f.delivery.read())
        assertEquals(1, f.manager.nodeSelectionSnapshot().choices.size)
        assertEquals(0, f.writes)
    }
    @Test fun localOneClickRereadsAndCreatesFreshOneShotMaterialWithoutFakeExpiry() = runTest {
        val f = Fixture(); f.local()
        repeat(2) {
            val ready = f.gateway.prepare(SelectionIntent.Choose) as ConfigPreparation.Ready
            ready.config.use { config ->
                assertNull(config.validUntil)
                assertTrue(config.activate { true })
                assertThrows(Exception::class.java) { config.activate { true } }
            }
        }
        assertEquals(0, f.downloads); assertEquals(2, f.writes)
    }
    @Test fun rejectedInputKeepsPreviousWholeSourceAndSelection() = runTest {
        val f = Fixture(); f.local()
        val before = f.manager.nodeSelectionSnapshot().choices.single().selection
        for (bad in listOf(byteArrayOf(0xc3.toByte(), 0x28), ByteArray(65537) { 32 },
            "https://evil.invalid/sub/a/synthetic".toByteArray(),
            "https://sub.invalid/sub/a/synthetic?secret=not-allowed".toByteArray(),
            (yaml().toString(Charsets.UTF_8) + "  - name: bad\n    type: unknown\n").toByteArray())) {
            try { f.local(bad); fail("Rejected fixture was admitted") } catch (_: Exception) { }
            assertSame(before, f.manager.nodeSelectionSnapshot().choices.single().selection)
        }
    }
    @Test fun remoteRequiresOwnFutureExpiryWithoutBorrowingAccountMetadata() = runTest {
        val f = Fixture(); f.local(); f.until = null
        try { f.remote(); fail("Missing remote expiry admitted") } catch (_: PortalFailure) { }
        assertEquals(ManualSourceKind.ImportedLocal, f.manager.snapshot().sourceKind)
        f.until = now.minusSeconds(1)
        try { f.remote(); fail("Expired remote source admitted") } catch (_: PortalFailure) { }
        assertEquals(ManualSourceKind.ImportedLocal, f.manager.sourceKind())
    }
    @Test fun remoteEveryConnectReadsFreshAndFailureNeverUsesCachedOrLegacy() = runTest {
        val f = Fixture(); f.remote()
        val first = f.gateway.prepare(SelectionIntent.Choose) as ConfigPreparation.Ready
        first.config.close(); assertEquals(3, f.downloads)
        f.failure = true
        assertTrue(f.gateway.prepare(SelectionIntent.Choose) is ConfigPreparation.Unavailable)
        assertEquals(4, f.downloads); assertEquals(0, f.writes)
        assertNull(f.profiles.read())
    }
    @Test fun onlyExactSameRemoteCapabilityMayRetainItsKnownExpiry() = runTest {
        val f = Fixture(); f.remote(); f.until = null
        assertEquals(now.plusSeconds(600), f.remote().validUntil)
        try { f.remote(Secret("https://sub.invalid/sub/a/different")); fail("Borrowed expiry") }
        catch (_: PortalFailure) { }
        assertEquals(ManualSourceKind.ImportedRemote, f.manager.sourceKind())
    }
    @Test fun clearDuringRemoteReadCancelsAdoptionAndLeavesTombstone() = runTest {
        val f = Fixture(); val entered = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        f.beforeRead = { entered.complete(Unit); resume.await() }
        val pending = async(start = CoroutineStart.UNDISPATCHED) { f.remote() }
        entered.await(); f.manager.unlink(); resume.complete(Unit)
        try { pending.await(); fail("Late source adopted") } catch (_: CancellationException) { }
        assertEquals(ManualSourceKind.None, f.manager.sourceKind())
        assertEquals(LocalSubscriptionStatus.MISSING, f.manager.restore().status)
    }
    @Test fun cancelledReadAndRejectedConnectionFenceDoNotReplaceSource() = runTest {
        val f = Fixture(); f.local()
        val before = f.store.read()!!.use { it.revision }
        try { f.manager.importManual(yaml("192.0.2.11")) { throw CancellationException("Owned connection active") }; fail("Fence bypassed") }
        catch (_: CancellationException) { }
        assertEquals(before, f.store.read()!!.use { it.revision })
        assertEquals(LocalSubscriptionStatus.READY, f.manager.snapshot().status)
    }
    @Test fun sourceAtoBtoAInvalidatesOldOpaqueSelectionAndAdmission() = runTest {
        val f = Fixture(); f.local()
        val old = f.manager.nodeSelectionSnapshot().choices.single().selection
        f.manager.selectNode(old)
        val prepared = f.gateway.prepare(SelectionIntent.Selected(old)) as ConfigPreparation.Ready
        f.local(yaml("192.0.2.11")); f.local()
        assertFalse(f.manager.selectNode(old))
        prepared.config.use { assertThrows(PreparationRejected::class.java) { it.activate { true } } }
        assertEquals(0, f.writes)
    }
    @Test fun removeUsesDurableNoneAndAccountSourceRequiresExplicitSwitch() = runTest {
        val f = Fixture(); f.local()
        f.manager.removeManualSource { it() }
        assertEquals(ManualSourceKind.None, f.manager.sourceKind())
        assertEquals(LocalSubscriptionStatus.MISSING, f.manager.refresh().status)
        f.manager.useAccountSource { it() }
        assertEquals(ManualSourceKind.LegacyRemote, f.manager.sourceKind())
    }
    @Test fun manualModeRejectsLegacyRememberWithoutWritingProfile() = runTest {
        val f = Fixture(); f.local()
        assertThrows(Exception::class.java) { f.manager.remember(yaml(), url, now.plusSeconds(600)) }
        assertNull(f.profiles.read())
        assertEquals(ManualSourceKind.ImportedLocal, f.manager.sourceKind())
    }
    @Test fun failedReadbackBlocksAdmissionEvenIfStoreRetainedOldWholeEnvelope() = runTest {
        val memory = MemoryManualSourceStore()
        var reject = false
        val store = object : ManualSourceStore {
            override fun read() = memory.read()
            override fun save(record: ManualSourceRecord) { if (!reject) memory.save(record) }
        }
        val f = Fixture(store); f.local(); reject = true
        try { f.local(yaml("192.0.2.11")); fail("Failed commit passed") } catch (_: PortalFailure) { }
        assertEquals(LocalSubscriptionStatus.STORAGE_ERROR, f.manager.restore().status)
        assertTrue(f.gateway.prepare(SelectionIntent.Choose) is ConfigPreparation.Unavailable)
        assertEquals(0, f.writes)
    }
    @Test fun missingSelectedEnvelopeNeverFallsBackToLegacy() = runTest {
        val memory = MemoryManualSourceStore(); var missing = false
        val f = Fixture(object : ManualSourceStore {
            override fun read() = if (missing) null else memory.read()
            override fun save(record: ManualSourceRecord) = memory.save(record)
        })
        f.local(); missing = true
        assertEquals(LocalSubscriptionStatus.STORAGE_ERROR, f.manager.refresh().status)
        assertEquals(0, f.downloads)
    }
    @Test fun localProbeUsesTypedValidityAndInvalidatesWithSourceReplacement() = runTest {
        val f = Fixture(); f.local()
        val target = f.manager.captureNodeProbeTargets().single()
        assertTrue(f.manager.isCurrentNodeProbeTarget(target))
        assertTrue(f.manager.isNodeProbeTargetRegistryCurrent(target))
        f.local(yaml("192.0.2.11"))
        assertFalse(f.manager.isCurrentNodeProbeTarget(target))
        assertFalse(f.manager.isNodeProbeTargetRegistryCurrent(target))
    }
    @Test fun firstInitializationWritesOnlyLegacySelectorAndMissingAfterwardsBlocks() = runTest {
        val memory = MemoryManualSourceStore(null)
        var writes = 0; var missing = false
        val f = Fixture(object : ManualSourceStore {
            override fun read() = if (missing) null else memory.read()
            override fun save(record: ManualSourceRecord) { writes++; memory.save(record) }
        })
        assertEquals(ManualSourceKind.LegacyRemote, f.manager.sourceKind())
        assertEquals(1, writes); assertNull(f.profiles.read()); assertNull(f.delivery.read())
        missing = true
        assertThrows(PortalFailure::class.java) { f.manager.sourceKind() }
        assertEquals(1, writes)
        assertEquals(LocalSubscriptionStatus.STORAGE_ERROR, f.manager.restore().status)
    }
    @Test fun failedFirstInitializationDoesNotRetryFallback() = runTest {
        var writes = 0
        val f = Fixture(object : ManualSourceStore {
            override fun read(): ManualSourceRecord? = null
            override fun save(record: ManualSourceRecord) { writes++; throw PortalFailure(PortalFailure.Reason.STORAGE) }
        })
        repeat(2) { assertThrows(PortalFailure::class.java) { f.manager.sourceKind() } }
        assertEquals(1, writes)
    }
    @Test fun latePickerVersionRejectsSourceAtoBtoAAndClear() = runTest {
        val f = Fixture(); f.local()
        val version = f.manager.captureManualSourceVersion()
        f.local(yaml("192.0.2.11")); f.local()
        assertFalse(f.manager.isManualSourceVersionCurrent(version))
        try { f.manager.importManual(yaml(), version) { it() }; fail("Late picker result admitted") }
        catch (_: CancellationException) { }
        val current = f.manager.captureManualSourceVersion(); f.manager.unlink()
        assertFalse(f.manager.isManualSourceVersionCurrent(current))
    }
    @Test fun authenticationEpochAloneDoesNotInvalidatePickerButImportEpochStillFencesCommit() = runTest {
        val f = Fixture(); val version = f.manager.captureManualSourceVersion()
        f.manager.invalidatePending()
        assertTrue(f.manager.isManualSourceVersionCurrent(version))
        assertEquals(LocalSubscriptionStatus.READY, f.manager.importManual(yaml(), version) { it() }.status)
        try { f.manager.importManual(yaml("192.0.2.11")) { action -> f.manager.invalidatePending(); action() }; fail("Cancelled commit admitted") }
        catch (_: CancellationException) { }
        assertEquals(LocalSubscriptionStatus.READY, f.manager.snapshot().status)
    }
    @Test fun localPrepareRejectsBytesChangedUnderSameRevisionAndNeverUsesPreparedCache() = runTest {
        val f = Fixture(); f.local()
        val selection = f.manager.nodeSelectionSnapshot().choices.single().selection
        f.manager.selectNode(selection)
        val prepared = f.gateway.prepare(SelectionIntent.Selected(selection)) as ConfigPreparation.Ready
        val old = f.store.read()!!
        try { ManualSourceRecord(old.revision, old.kind, yaml("192.0.2.11")).use { f.store.save(it) } }
        finally { old.close() }
        prepared.config.use { assertThrows(PreparationRejected::class.java) { it.activate { true } } }
        assertEquals(0, f.writes)
        assertFalse(f.manager.selectNode(selection))
    }
    @Test fun localParserAcceptsExactByteAndNodeLimitsAndRejectsOneBeyond() = runTest {
        val f = Fixture()
        val body = yaml().toString(Charsets.UTF_8).substringAfter("proxies:\n")
        assertEquals(128, f.local(("proxies:\n" + body.repeat(128)).toByteArray()).nodes.size)
        try { f.local(("proxies:\n" + body.repeat(129)).toByteArray()); fail("129 nodes admitted") }
        catch (_: Exception) { }
        val bytes = yaml(); val padded = bytes + ByteArray(65536 - bytes.size) { 32 }
        assertEquals(LocalSubscriptionStatus.READY, f.local(padded).status)
        try { f.local(padded + byteArrayOf(32)); fail("Overlimit bytes admitted") } catch (_: Exception) { }
    }
    @Test fun rawYamlMayStartWithOtherSupportedTopLevelKeys() = runTest {
        val f = Fixture()
        assertEquals(LocalSubscriptionStatus.READY, f.local("mode: rule\n".toByteArray() + yaml()).status)
    }
    @Test fun manualRestoreRereadsSelectedEnvelopeWithoutHttpOrLegacyMutation() = runTest {
        val f = Fixture(); f.local()
        val restarted = LocalSubscriptionManager(f.delivery, f.profiles, SubscriptionSource { error("No download") },
            policy, { now }, LocalSubscriptionAuthority(), manualSources = f.store)
        val snapshot = restarted.restore()
        assertEquals(LocalSubscriptionStatus.READY, snapshot.status)
        assertEquals(LocalSourceValidity.LocalNoServerExpiry, snapshot.validity)
        assertEquals(1, restarted.nodeSelectionSnapshot().choices.size)
        assertNull(f.profiles.read()); assertNull(f.delivery.read())
    }
    @Test fun cancellationDuringDownloadPreservesPriorSourceWithoutCommit() = runTest {
        val f = Fixture(); f.local()
        val version = f.manager.captureManualSourceVersion()
        val entered = CompletableDeferred<Unit>(); val hold = CompletableDeferred<Unit>()
        f.beforeRead = { entered.complete(Unit); hold.await() }
        val pending = async(start = CoroutineStart.UNDISPATCHED) { f.remote() }
        entered.await(); pending.cancel(); pending.join()
        assertTrue(f.manager.isManualSourceVersionCurrent(version))
        assertEquals(ManualSourceKind.ImportedLocal, f.manager.sourceKind())
    }
    @Test fun fullClearPersistsNoneBeforeLegacyClearAndIncompleteClearStaysBlocked() = runTest {
        val source = MemoryManualSourceStore(); var clearFails = true
        val delivery = object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = Unit
            override fun clear() {
                assertEquals(ManualSourceKind.None, source.read()!!.use { it.kind })
                if (clearFails) throw PortalFailure(PortalFailure.Reason.STORAGE)
            }
        }
        val manager = LocalSubscriptionManager(delivery, MemoryLocalProfileStore(), SubscriptionSource { error("No download") },
            policy, { now }, LocalSubscriptionAuthority(), manualSources = source)
        manager.importManual(yaml()) { it() }
        assertThrows(PortalFailure::class.java) { manager.unlink() }
        assertEquals(LocalSubscriptionStatus.STORAGE_ERROR, manager.restore().status)
        try { manager.useAccountSource { it() }; fail("Incomplete clear recovered implicitly") } catch (_: PortalFailure) { }
        clearFails = false; manager.unlink()
        assertEquals(ManualSourceKind.None, manager.sourceKind())
        assertEquals(LocalSubscriptionStatus.MISSING, manager.restore().status)
    }
    @Test fun remoteSameRevisionMetadataReplacementRevokesPreparedAdmission() = runTest {
        for (terminal in listOf<LocalSubscriptionStatus?>(null, LocalSubscriptionStatus.INVALID_LINK)) {
            val f = Fixture(); f.remote()
            val ready = f.gateway.prepare(SelectionIntent.Choose) as ConfigPreparation.Ready
            val old = f.store.read()!!
            val bytes = old.copyBytes()!!
            try { ManualSourceRecord(old.revision, old.kind, bytes, old.url,
                if (terminal == null) now.minusSeconds(1) else old.validUntil, terminal).use { f.store.save(it) } }
            finally { bytes.fill(0); old.close() }
            ready.config.use { assertThrows(PreparationRejected::class.java) { it.activate { true } } }
            assertEquals(0, f.writes)
        }
    }
    @Test fun unfinishedLegacyRecoveryCannotLendExpiryToManualUrlWithoutServerExpiry() = runTest {
        val delivery = MemoryDeliveryStore()
        val candidate = DeliveryRecord("synthetic-account", "synthetic-binding", "synthetic-resource", "synthetic-operation", url,
            Secret("synthetic-handoff"), false)
        delivery.save(candidate)
        delivery.saveLegacyRecovery(LegacyRecoveryJournal(null, candidate, LegacyRecoveryPhase.PROFILE_COMMITTING))
        val profiles = MemoryLocalProfileStore()
        CachedSubscription(sessionBinding(url), yaml(), now.plusSeconds(600), url).use(profiles::save)
        val manager = LocalSubscriptionManager(delivery, profiles, SubscriptionSource { yaml() }, policy, { now },
            LocalSubscriptionAuthority(), manualSources = MemoryManualSourceStore())
        try { manager.importManual(url.value.toByteArray()) { it() }; fail("Uncommitted expiry borrowed") }
        catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.SUBSCRIPTION_UNAVAILABLE, failure.reason) }
        assertEquals(ManualSourceKind.LegacyRemote, manager.sourceKind())
        assertNotNull(delivery.readLegacyRecovery())
    }
    @Test fun fullClearCannotPublishMissingUntilOwnerCleanupAndFinalFenceComplete() = runTest {
        val f = Fixture(); f.local()
        val cleanupEntered = CompletableDeferred<Unit>(); val cleanupComplete = CompletableDeferred<Unit>()
        var observed = false; var finished = false
        f.manager.observeClear { observed = true }.use {
            val clearing = async(start = CoroutineStart.UNDISPATCHED) {
                f.manager.unlinkAndAwait({ cleanupEntered.complete(Unit); cleanupComplete.await() }) { action ->
                    assertTrue(cleanupComplete.isCompleted); action(); finished = true
                }
            }
            cleanupEntered.await()
            assertTrue(observed); assertFalse(finished)
            assertEquals(LocalSubscriptionStatus.STORAGE_ERROR, f.manager.snapshot().status)
            assertTrue(f.store.read()!!.use { it.kind == ManualSourceKind.None && it.clearPending })
            cleanupComplete.complete(Unit); clearing.await()
            assertTrue(finished)
            assertEquals(LocalSubscriptionStatus.MISSING, f.manager.snapshot().status)
            assertFalse(f.store.read()!!.use { it.clearPending })
        }
    }
    @Test fun interruptedClearStaysBlockedAcrossNewAuthorityAndCannotExposeRetainedV1() = runTest {
        val memory = MemoryLocalProfileStore()
        CachedSubscription(sessionBinding(url), yaml(), now.plusSeconds(600), url).use(memory::save)
        val profiles = object : LocalProfileStore {
            override fun read() = memory.read()
            override fun save(value: CachedSubscription) = memory.save(value)
            override fun clear() = throw PortalFailure(PortalFailure.Reason.STORAGE)
        }
        val sources = MemoryManualSourceStore()
        val delivery = Delivery()
        fun manager() = LocalSubscriptionManager(delivery, profiles, SubscriptionSource { error("No network") }, policy,
            { now }, LocalSubscriptionAuthority(), manualSources = sources)
        val first = manager(); first.importManual(yaml()) { it() }
        assertThrows(PortalFailure::class.java) { first.unlink() }
        assertNotNull(memory.read()?.also { it.close() })
        val restarted = manager()
        assertEquals(LocalSubscriptionStatus.STORAGE_ERROR, restarted.restore().status)
        try { restarted.useAccountSource { it() }; fail("Pending clear exposed retained v1") } catch (_: PortalFailure) { }
        assertTrue(sources.read()!!.use { it.kind == ManualSourceKind.None && it.clearPending })
    }
    @Test fun cleanupFailureOrCancellationRetainsDurablePendingTombstone() = runTest {
        for (cancel in listOf(false, true)) {
            val f = Fixture(); f.local()
            try {
                f.manager.unlinkAndAwait({
                    if (cancel) throw CancellationException("Synthetic cleanup cancellation")
                    throw PortalFailure(PortalFailure.Reason.STORAGE)
                }) { fail("Failure crossed final owner fence") }
                fail("Cleanup failure reported success")
            } catch (_: Exception) { }
            assertEquals(LocalSubscriptionStatus.STORAGE_ERROR, f.manager.snapshot().status)
            assertTrue(f.store.read()!!.use { it.clearPending })
            assertTrue(f.gateway.prepare(SelectionIntent.Choose) is ConfigPreparation.Unavailable)
        }
    }
    @Test fun synchronousManualClearWithLiveOwnerCannotClaimCompletion() = runTest {
        val f = Fixture(); f.local()
        f.manager.observeClear { }.use {
            assertThrows(PortalFailure::class.java) { f.manager.unlink() }
            assertTrue(f.store.read()!!.use { it.clearPending })
            assertEquals(LocalSubscriptionStatus.STORAGE_ERROR, f.manager.snapshot().status)
        }
        f.manager.unlink()
        assertFalse(f.store.read()!!.use { it.clearPending })
    }
    @Test fun importedRefreshAndRemovalPreserveExistingV1AndDeliveryUntilExplicitAccountSwitch() = runTest {
        val f = Fixture()
        val retained = yaml("192.0.2.90")
        val legacyUrl = Secret("https://sub.invalid/sub/a/retained-account")
        val original = DeliveryRecord("synthetic-account", "synthetic-binding", "synthetic-resource", "synthetic-operation",
            legacyUrl, Secret("synthetic-handoff"), false)
        f.delivery.save(original)
        CachedSubscription(sessionBinding(legacyUrl), retained, now.plusSeconds(900), legacyUrl).use(f.profiles::save)
        f.remote(); f.manager.refresh(); f.manager.removeManualSource { it() }
        assertSame(original, f.delivery.read())
        f.profiles.read()!!.use { preserved ->
            assertEquals(legacyUrl.value, preserved.url!!.value)
            assertEquals(now.plusSeconds(900), preserved.validUntil)
            val copy = preserved.copyBytes()
            try { assertArrayEquals(retained, copy) } finally { copy.fill(0) }
        }
        assertEquals(LocalSubscriptionStatus.MISSING, f.manager.refresh().status)
        f.manager.useAccountSource { it() }
        assertEquals(ManualSourceKind.LegacyRemote, f.manager.sourceKind())
        assertEquals(LocalSubscriptionStatus.READY, f.manager.refresh().status)
    }
    @Test fun unreadableFirstStoreThenMissingCannotBeReinitializedAsLegacy() = runTest {
        var broken = true; var writes = 0
        val f = Fixture(object : ManualSourceStore {
            override fun read(): ManualSourceRecord? {
                if (broken) throw PortalFailure(PortalFailure.Reason.STORAGE)
                return null
            }
            override fun save(record: ManualSourceRecord) { writes++ }
        })
        assertThrows(PortalFailure::class.java) { f.manager.sourceKind() }
        broken = false
        try { f.local(); fail("Failed first read was treated as never initialized") } catch (_: PortalFailure) { }
        assertEquals(0, writes)
        assertEquals(LocalSubscriptionStatus.STORAGE_ERROR, f.manager.restore().status)
    }
}
