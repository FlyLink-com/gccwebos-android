package com.gccwebos.android.portal

import com.gccwebos.android.core.*
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OneClickConfigGatewayTest {
    private val now = Instant.parse("2026-10-02T00:00:00Z")
    private fun artifact(vararg hosts: String) = ("proxies:\n" + hosts.joinToString("") { host -> """  - name: synthetic node
    type: ss
    server: $host
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""" }).toByteArray()

    private class Delivery : DeliveryStore {
        var value: DeliveryRecord? = DeliveryRecord("synthetic-account", "synthetic-session",
            "synthetic-resource", "synthetic-operation", Secret("https://sub.invalid/sub/a/synthetic"), null, false)
        override fun read() = value
        override fun save(record: DeliveryRecord) { value = record }
        override fun clear() { value = null }
    }
    private inner class Fixture {
        val delivery = Delivery()
        val profiles = MemoryLocalProfileStore()
        val authority = LocalSubscriptionAuthority()
        var time = now
        var until: Instant? = now.plusSeconds(600)
        var bytes = artifact("192.0.2.10")
        var reads = 0
        var writes = 0
        var failure: PortalFailure.Reason? = null
        var beforeRead: (Int) -> Unit = {}
        val manager = LocalSubscriptionManager(delivery, profiles, object : SubscriptionSource {
            override suspend fun download(url: Secret) = read(url).bytes
            override suspend fun read(url: Secret): SubscriptionRead {
                reads++; beforeRead(reads)
                failure?.let { throw PortalFailure(it) }
                return SubscriptionRead(bytes.copyOf(), until)
            }
        }, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { time }, authority)
        // Admission contract only; these tests make no OS permissions or atomic-file claim.
        val gateway = OneClickConfigGateway(manager, PreparedConfigStore { writes++ })
    }

    @Test fun soleFreshChoiceIsExplicitlySelectedAndPreparedFromAnotherFreshRead() = runTest {
        val f = Fixture()
        val prepared = f.gateway.prepare(SelectionIntent.Choose)
        assertTrue(prepared is ConfigPreparation.Ready)
        (prepared as ConfigPreparation.Ready).config.use { config ->
            assertNotNull(f.manager.nodeSelectionSnapshot().selected)
            assertEquals(2, f.reads)
            assertEquals(0, f.writes)
            assertTrue(config.activate { true })
            assertEquals(1, f.writes)
        }
    }

    @Test fun explicitOneClickChoosesOnlyAnAllowedOpaqueNodeAndValidatesItWithAnotherFreshRead() = runTest {
        val f = Fixture(); f.bytes = artifact("192.0.2.10", "192.0.2.11")
        val prepared = f.gateway.prepare(SelectionIntent.Choose)
        assertTrue(prepared is ConfigPreparation.Ready)
        (prepared as ConfigPreparation.Ready).config.use { config ->
            val snapshot = f.manager.nodeSelectionSnapshot()
            assertEquals(2, snapshot.choices.size)
            assertTrue(snapshot.choices.any { it.selection === snapshot.selected })
            val selectedIndex = snapshot.choices.indexOfFirst { it.selection === snapshot.selected }
            val material = config.copyMaterial()
            try { assertTrue(material.toString(Charsets.UTF_8).contains("192.0.2.${10 + selectedIndex}")) }
            finally { material.fill(0) }
            assertEquals(2, f.reads); assertEquals(0, f.writes)
        }
    }

    @Test fun currentExplicitOpaqueSelectionIsUsedByOneClickAfterFreshValidation() = runTest {
        val f = Fixture(); f.bytes = artifact("192.0.2.10", "192.0.2.11")
        f.manager.refresh()
        val selected = f.manager.nodeSelectionSnapshot().choices[1].selection
        assertTrue(f.manager.selectNode(selected))
        val prepared = f.gateway.prepare(SelectionIntent.Choose)
        assertTrue(prepared is ConfigPreparation.Ready)
        (prepared as ConfigPreparation.Ready).config.use { config ->
            val material = config.copyMaterial()
            try { assertTrue(material.toString(Charsets.UTF_8).contains("192.0.2.11")) }
            finally { material.fill(0) }
            assertEquals(2, f.reads); assertSame(selected, f.manager.nodeSelectionSnapshot().selected)
        }
    }

    @Test fun changedContentCannotAutomaticallyReselectAnEarlierSelectedSingleNode() = runTest {
        val f = Fixture(); f.manager.refresh()
        val selected = f.manager.nodeSelectionSnapshot().choices.single().selection
        assertTrue(f.manager.selectNode(selected))
        f.bytes = artifact("192.0.2.11")
        assertTrue(f.gateway.prepare(SelectionIntent.Choose) is ConfigPreparation.SelectionRequired)
        assertNull(f.manager.nodeSelectionSnapshot().selected)
        assertEquals(2, f.reads); assertEquals(0, f.writes)
    }

    @Test fun changedUnselectedRegistryRequiresReselectionBeforeSingleChoiceConvenience() = runTest {
        val f = Fixture(); f.manager.refresh()
        f.bytes = artifact("192.0.2.11")
        assertTrue(f.gateway.prepare(SelectionIntent.Choose) is ConfigPreparation.SelectionRequired)
        assertNull(f.manager.nodeSelectionSnapshot().selected)
        assertEquals(2, f.reads); assertEquals(0, f.writes)
    }

    @Test fun changedUnselectedMultiNodeRegistryDoesNotRandomlyAdmitAReplacement() = runTest {
        val f = Fixture(); f.bytes = artifact("192.0.2.10", "192.0.2.11"); f.manager.refresh()
        f.bytes = artifact("192.0.2.12", "192.0.2.13")
        assertTrue(f.gateway.prepare(SelectionIntent.Choose) is ConfigPreparation.SelectionRequired)
        assertNull(f.manager.nodeSelectionSnapshot().selected)
        assertEquals(2, f.reads); assertEquals(0, f.writes)
    }

    @Test fun randomSelectionDoesNotSurviveRevokedAdmissionOrSecondReadFailure() = runTest {
        val revoked = Fixture(); revoked.bytes = artifact("192.0.2.10", "192.0.2.11")
        val ready = revoked.gateway.prepare(SelectionIntent.Choose) as ConfigPreparation.Ready
        ready.config.use { config ->
            revoked.manager.invalidatePending()
            try { config.activate { fail("A revoked preparation cannot start a tunnel"); true }; fail("Expected stale admission") }
            catch (failure: PreparationRejected) { assertEquals(PreparationFailure.STALE, failure.reason) }
            assertEquals(0, revoked.writes)
        }
        val failed = Fixture(); failed.bytes = artifact("192.0.2.10", "192.0.2.11")
        failed.beforeRead = { if (it == 2) failed.failure = PortalFailure.Reason.NETWORK }
        assertEquals(ConfigPreparation.Unavailable(PreparationFailure.NETWORK), failed.gateway.prepare(SelectionIntent.Choose))
        assertEquals(2, failed.reads); assertEquals(0, failed.writes)
    }

    @Test fun emptyAndMixedUnsupportedArtifactsNeverProduceARandomSelection() = runTest {
        for (bytes in listOf("proxies: []".toByteArray(),
            artifact("192.0.2.10", "192.0.2.11").toString(Charsets.UTF_8).replaceFirst("type: ss", "type: vmess").toByteArray())) {
            val f = Fixture(); f.bytes = bytes
            assertEquals(ConfigPreparation.Unavailable(PreparationFailure.UNSUPPORTED), f.gateway.prepare(SelectionIntent.Choose))
            assertNull(f.manager.nodeSelectionSnapshot().selected)
            assertEquals(1, f.reads); assertEquals(0, f.writes)
        }
    }

    @Test fun userChoiceMadeDuringDiscoveryRevokesThatRequestWithoutBeingOverwritten() = runTest {
        val f = Fixture(); f.bytes = artifact("192.0.2.10", "192.0.2.11"); f.manager.refresh()
        val selected = f.manager.nodeSelectionSnapshot().choices[1].selection
        f.beforeRead = { if (it == 2) assertTrue(f.manager.selectNode(selected)) }
        try { f.gateway.prepare(SelectionIntent.Choose); fail("Selection change must revoke the in-flight epoch") }
        catch (_: CancellationException) { }
        assertSame(selected, f.manager.nodeSelectionSnapshot().selected)
        assertEquals(2, f.reads); assertEquals(0, f.writes)
    }

    @Test fun manualChoiceAfterDiscoveryBeforeCurrentSnapshotCancelsTheOriginalIntent() = runTest {
        val f = Fixture(); f.bytes = artifact("192.0.2.10", "192.0.2.11")
        var manual: ConnectionSelection? = null
        val gateway = OneClickConfigGateway(f.manager, PreparedConfigStore { f.writes++ },
            chooseUnselected = { choices ->
                // The fresh read has returned, but the gateway has not yet captured its next snapshot.
                manual = choices[1].selection
                assertTrue(f.manager.selectNode(requireNotNull(manual)))
                choices[0]
            })
        try { gateway.prepare(SelectionIntent.Choose); fail("Manual selection must cancel the random request") }
        catch (_: CancellationException) { }
        assertSame(manual, f.manager.nodeSelectionSnapshot().selected)
        assertEquals(1, f.reads); assertEquals(0, f.writes)
    }

    @Test fun cancellationAfterDiscoveryBeforeCurrentSnapshotCannotAcquireAnotherEpoch() = runTest {
        val f = Fixture(); f.bytes = artifact("192.0.2.10", "192.0.2.11")
        val gateway = OneClickConfigGateway(f.manager, PreparedConfigStore { f.writes++ },
            chooseUnselected = { choices -> f.manager.invalidatePending(); choices[0] })
        try { gateway.prepare(SelectionIntent.Choose); fail("A cancelled random request must not acquire a new epoch") }
        catch (_: CancellationException) { }
        assertNull(f.manager.nodeSelectionSnapshot().selected)
        assertEquals(1, f.reads); assertEquals(0, f.writes)
    }

    @Test fun cancellationWhileFirstDiscoveryWaitsForMutexCannotStartADownload() = runTest {
        val f = Fixture()
        f.authority.mutex.lock()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { f.gateway.prepare(SelectionIntent.Choose) }
        try {
            assertFalse(pending.isCompleted)
            f.manager.invalidatePending()
        } finally { f.authority.mutex.unlock() }
        try { pending.await(); fail("Cancelled first discovery must not admit a new epoch") }
        catch (_: CancellationException) { }
        assertEquals(0, f.reads); assertEquals(0, f.writes)
        assertNull(f.manager.nodeSelectionSnapshot().selected)
    }

    @Test fun manualChoiceWhileFirstDiscoveryWaitsForMutexCancelsInsteadOfConnectingIt() = runTest {
        val f = Fixture(); f.bytes = artifact("192.0.2.10", "192.0.2.11"); f.manager.refresh()
        val manual = f.manager.nodeSelectionSnapshot().choices[1].selection
        f.authority.mutex.lock()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { f.gateway.prepare(SelectionIntent.Choose) }
        try {
            assertFalse(pending.isCompleted)
            assertTrue(f.manager.selectNode(manual))
        } finally { f.authority.mutex.unlock() }
        try { pending.await(); fail("A manual choice must revoke the waiting original request") }
        catch (_: CancellationException) { }
        assertSame(manual, f.manager.nodeSelectionSnapshot().selected)
        assertEquals(1, f.reads); assertEquals(0, f.writes)
    }

    @Test fun manualChoiceAfterCurrentSnapshotStillRejectsTheRandomCompareAndSet() = runTest {
        val f = Fixture(); f.bytes = artifact("192.0.2.10", "192.0.2.11"); f.manager.refresh()
        val observed = f.manager.nodeSelectionAttempt()
        val manual = observed.snapshot.choices[1].selection
        assertTrue(f.manager.selectNode(manual))
        assertNull(f.manager.selectNodeIfUnchanged(observed.snapshot.choices[0].selection, observed))
        assertSame(manual, f.manager.nodeSelectionSnapshot().selected)
        assertEquals(1, f.reads); assertEquals(0, f.writes)
    }

    @Test fun selectionChangeAfterRandomCommitCannotStartAnotherFreshRead() = runTest {
        val f = Fixture(); f.bytes = artifact("192.0.2.10", "192.0.2.11"); f.manager.refresh()
        val observed = f.manager.nodeSelectionAttempt()
        val random = observed.snapshot.choices[0].selection
        val manual = observed.snapshot.choices[1].selection
        val epoch = requireNotNull(f.manager.selectNodeIfUnchanged(random, observed))
        assertTrue(f.manager.selectNode(manual))
        try {
            f.manager.prepareAtEpoch(SelectionIntent.Selected(random), PreparedConfigStore { f.writes++ },
                LiteralConfigAddressPreparer, epoch)
            fail("A manual selection must revoke the random intent before fresh read admission")
        } catch (_: CancellationException) { }
        assertSame(manual, f.manager.nodeSelectionSnapshot().selected)
        assertEquals(1, f.reads); assertEquals(0, f.writes)
    }

    @Test fun refreshedRegistryCannotReuseAnEarlierRandomSelectionAttempt() = runTest {
        val f = Fixture(); f.bytes = artifact("192.0.2.10", "192.0.2.11"); f.manager.refresh()
        val observed = f.manager.nodeSelectionAttempt()
        f.bytes = artifact("192.0.2.12", "192.0.2.13"); f.manager.refresh()
        assertNull(f.manager.selectNodeIfUnchanged(observed.snapshot.choices[0].selection, observed))
        assertNull(f.manager.nodeSelectionSnapshot().selected)
        assertEquals(2, f.reads); assertEquals(0, f.writes)
    }

    @Test fun randomSuggestionOutsideTheObservedAllowedRegistryCannotBeCommitted() = runTest {
        val foreign = Fixture(); foreign.manager.refresh()
        val invalid = foreign.manager.nodeSelectionSnapshot().choices.single()
        val f = Fixture(); f.bytes = artifact("192.0.2.10", "192.0.2.11")
        val gateway = OneClickConfigGateway(f.manager, PreparedConfigStore { f.writes++ },
            chooseUnselected = { invalid })
        try { gateway.prepare(SelectionIntent.Choose); fail("A suggestion is not selection authority") }
        catch (_: CancellationException) { }
        assertNull(f.manager.nodeSelectionSnapshot().selected)
        assertEquals(1, f.reads); assertEquals(0, f.writes)
    }

    @Test fun changeBetweenSoleChoiceDiscoveryAndPreparationCannotBeRetriedAutomatically() = runTest {
        val f = Fixture(); f.beforeRead = { if (it == 2) f.bytes = artifact("192.0.2.11") }
        assertTrue(f.gateway.prepare(SelectionIntent.Choose) is ConfigPreparation.SelectionRequired)
        assertEquals(2, f.reads); assertEquals(0, f.writes)
        assertNull(f.manager.nodeSelectionSnapshot().selected)
    }

    @Test fun foreignExplicitSelectionNeverFallsBackToSingleChoiceConvenience() = runTest {
        val foreign = Fixture(); foreign.manager.refresh()
        val foreignSelection = foreign.manager.nodeSelectionSnapshot().choices.single().selection
        val f = Fixture()
        assertTrue(f.gateway.prepare(SelectionIntent.Selected(foreignSelection)) is ConfigPreparation.SelectionRequired)
        assertEquals(1, f.reads); assertEquals(0, f.writes)
        assertNull(f.manager.nodeSelectionSnapshot().selected)
    }

    @Test fun downloadFailureDoesNotActivateCachedSingleChoice() = runTest {
        val f = Fixture(); f.manager.refresh()
        assertTrue(f.manager.selectNode(f.manager.nodeSelectionSnapshot().choices.single().selection))
        f.failure = PortalFailure.Reason.NETWORK
        assertEquals(ConfigPreparation.Unavailable(PreparationFailure.NETWORK), f.gateway.prepare(SelectionIntent.Choose))
        assertEquals(2, f.reads); assertEquals(0, f.writes)
    }

    @Test fun readyMaterialCarriesFreshExpiryAndMetadataFallbackWithoutLoggingIt() = runTest {
        val f = Fixture()
        val expiry = f.until
        val fresh = f.manager.prepare(SelectionIntent.Choose, PreparedConfigStore { }) as ConfigPreparation.SelectionRequired
        assertTrue(f.manager.selectNode(fresh.choices.single().selection))
        f.until = null
        val prepared = f.gateway.prepare(SelectionIntent.Selected(fresh.choices.single().selection)) as ConfigPreparation.Ready
        prepared.config.use { assertEquals(expiry, it.validUntil) }
        val legacy = f.manager.prepare(null, LocalConnectionLineResolver { _, _ -> ResolvedConnectionLine("synthetic-line", 0) },
            PreparedConfigStore { })!!
        legacy.use { assertEquals(expiry, it.validUntil) }
        assertEquals(0, f.writes)
    }

    @Test fun missingCapabilityAndExpiredFreshArtifactRemainTypedBlocks() = runTest {
        val missing = Fixture(); missing.delivery.clear()
        assertEquals(ConfigPreparation.Unavailable(PreparationFailure.MISSING), missing.gateway.prepare(SelectionIntent.Choose))
        assertEquals(0, missing.reads); assertEquals(0, missing.writes)
        val expired = Fixture(); expired.until = now
        assertEquals(ConfigPreparation.Unavailable(PreparationFailure.EXPIRED), expired.gateway.prepare(SelectionIntent.Choose))
        assertEquals(1, expired.reads); assertEquals(0, expired.writes)
    }
}
