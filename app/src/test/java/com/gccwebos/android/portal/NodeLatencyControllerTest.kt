package com.gccwebos.android.portal

import com.gccwebos.android.core.ConnectionSelection
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NodeLatencyControllerTest {
    private val start = Instant.parse("2026-10-04T00:00:00Z")

    private fun artifact(count: Int = 2, prefix: String = "192.0.2") = ("proxies:\n" +
        (1..count).joinToString("") { index -> """  - name: duplicate
    type: ss
    server: $prefix.$index
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""" }).toByteArray()

    private class Profiles : LocalProfileStore {
        private val memory = MemoryLocalProfileStore()
        var reads = 0
        var saves = 0
        var clears = 0
        override fun read(): CachedSubscription? { reads++; return memory.read() }
        override fun save(value: CachedSubscription) { saves++; memory.save(value) }
        override fun clear() { clears++; memory.clear() }
    }

    private class Probe : NodeLatencyProbe {
        var network: Long? = 7L
        val calls = mutableListOf<ConnectionSelection>()
        var response: suspend (NodeProbeTarget) -> NodeLatencyObservation = {
            NodeLatencyObservation.Success(12, requireNotNull(network))
        }
        override fun currentNetworkKey() = network
        override suspend fun measure(target: NodeProbeTarget): NodeLatencyObservation {
            calls.add(target.selection)
            return response(target)
        }
    }

    private inner class Fixture(val test: TestScope, count: Int = 2, enabledProbe: Boolean = true) {
        val profiles = Profiles()
        var time = start
        var bytes = artifact(count)
        var downloads = 0
        var deliveryWrites = 0
        var auth = 1L
        var allowed = true
        val authority = LocalSubscriptionAuthority()
        val probe = Probe()
        private var delivery: DeliveryRecord? = DeliveryRecord("synthetic-account", "synthetic-session",
            "synthetic-resource", "synthetic-operation", Secret("https://sub.invalid/sub/a/synthetic"), null, false)
        val manager = LocalSubscriptionManager(object : DeliveryStore {
            override fun read() = delivery
            override fun save(record: DeliveryRecord) { deliveryWrites++; delivery = record }
            override fun clear() { deliveryWrites++; delivery = null }
        }, profiles, object : SubscriptionSource {
            override suspend fun download(url: Secret) = read(url).bytes
            override suspend fun read(url: Secret): SubscriptionRead {
                downloads++
                return SubscriptionRead(bytes.copyOf(), start.plusSeconds(3600))
            }
        }, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { time }, authority)
        val controller = NodeLatencyController(manager, test.backgroundScope,
            probe = if (enabledProbe) probe else null, authGeneration = { auth }, allowed = { allowed },
            elapsedMillis = { test.testScheduler.currentTime }, ioDispatcher = StandardTestDispatcher(test.testScheduler))
        suspend fun import() = manager.refresh()
        fun selections() = manager.nodeSelectionSnapshot().choices.map { it.selection }
    }

    @Test fun opaqueNodesWithDuplicateNamesKeepIndependentResultsWithoutAdmissionOrWrites() = runTest {
        val f = Fixture(this); f.import()
        val selections = f.selections()
        f.manager.selectNode(selections[1])
        val epoch = f.authority.epoch
        f.probe.response = { target -> NodeLatencyObservation.Success(if (target.selection === selections[0]) 9 else 28, 7) }
        f.controller.ensureMeasured(); runCurrent()
        val storageReads = f.profiles.reads
        assertEquals(NodeLatencyObservation.Success(9, 7), f.controller.reading(selections[0]))
        assertEquals(NodeLatencyObservation.Success(28, 7), f.controller.reading(selections[1]))
        f.controller.prune()
        assertEquals(storageReads, f.profiles.reads)
        assertSame(selections[1], f.manager.nodeSelectionSnapshot().selected)
        assertEquals(epoch, f.authority.epoch)
        assertEquals(1, f.downloads); assertEquals(1, f.profiles.saves)
        assertEquals(0, f.profiles.clears); assertEquals(0, f.deliveryWrites)
    }

    @Test fun repeatedEnsureAndExplicitRefreshAreDeduplicatedAndRespectCooldown() = runTest {
        val f = Fixture(this); f.import()
        val selection = f.selections()[0]
        val gate = CompletableDeferred<Unit>()
        f.probe.response = { gate.await(); NodeLatencyObservation.Success(10, 7) }
        f.controller.ensureMeasured(selection); f.controller.ensureMeasured(selection); runCurrent()
        assertTrue(f.controller.busy); assertEquals(1, f.probe.calls.size)
        f.controller.refresh(selection); runCurrent(); assertEquals(1, f.probe.calls.size)
        gate.complete(Unit); runCurrent(); assertFalse(f.controller.busy)
        f.controller.ensureMeasured(selection); f.controller.refresh(selection); runCurrent()
        assertEquals(1, f.probe.calls.size)
        advanceTimeBy(29_999); f.controller.refresh(selection); runCurrent(); assertEquals(1, f.probe.calls.size)
        advanceTimeBy(1); f.controller.refresh(selection); runCurrent(); assertEquals(2, f.probe.calls.size)
    }

    @Test fun automaticMeasurementOnlyRepeatsAtExpiryOrForAnotherNetwork() = runTest {
        val f = Fixture(this); f.import()
        val selection = f.selections()[0]
        f.controller.ensureMeasured(selection); runCurrent()
        advanceTimeBy(119_999)
        f.controller.ensureMeasured(selection); runCurrent(); assertEquals(1, f.probe.calls.size)
        advanceTimeBy(1); assertNull(f.controller.reading(selection))
        f.controller.ensureMeasured(selection); runCurrent(); assertEquals(2, f.probe.calls.size)
        f.probe.network = 8
        assertNull(f.controller.reading(selection))
        f.controller.ensureMeasured(selection); runCurrent()
        assertEquals(3, f.probe.calls.size)
        assertEquals(NodeLatencyObservation.Success(12, 8), f.controller.reading(selection))
    }

    @Test fun backgroundBlocksNewWorkAndLateResultsUntilThePageReturnsToForeground() = runTest {
        val f = Fixture(this); f.import()
        val selections = f.selections()
        val gate = CompletableDeferred<Unit>()
        f.probe.response = { target ->
            if (target.selection === selections[1]) withContext(NonCancellable) { gate.await() }
            NodeLatencyObservation.Success(18, 7)
        }
        f.controller.ensureMeasured(); runCurrent()
        assertNotNull(f.controller.reading(selections[0])); assertTrue(f.controller.busy)
        f.controller.setForeground(false)
        val storageReads = f.profiles.reads
        assertFalse(f.controller.foreground); assertFalse(f.controller.busy)
        assertNull(f.controller.reading(selections[0]))
        f.controller.ensureMeasured(); f.controller.refresh(); f.controller.sync(); runCurrent()
        assertEquals(storageReads, f.profiles.reads); assertEquals(2, f.probe.calls.size)
        gate.complete(Unit); runCurrent()
        assertNull(f.controller.reading(selections[1]))
        f.controller.setForeground(true)
        assertNotNull(f.controller.reading(selections[0]))
        assertNull(f.controller.reading(selections[1]))
        f.controller.ensureMeasured(); runCurrent()
        assertEquals(3, f.probe.calls.size)
        assertNotNull(f.controller.reading(selections[1]))
    }

    @Test fun periodicEnsureAvoidsDiskReadsUntilTtlOrNetworkRequiresNewEvidence() = runTest {
        val f = Fixture(this); f.import()
        val selections = f.selections()
        f.controller.ensureMeasured(); runCurrent()
        val storageReads = f.profiles.reads
        repeat(119) {
            advanceTimeBy(1_000)
            f.controller.prune()
            f.controller.ensureMeasured(selections[0]); f.controller.ensureMeasured(); runCurrent()
            assertFalse(f.controller.busy)
        }
        assertEquals(storageReads, f.profiles.reads); assertEquals(2, f.probe.calls.size)
        advanceTimeBy(1_000); f.controller.prune()
        selections.forEach { assertNull(f.controller.reading(it)) }
        f.controller.ensureMeasured(); runCurrent()
        assertEquals(4, f.probe.calls.size); assertTrue(f.profiles.reads > storageReads)
        f.probe.network = 8
        f.controller.prune(); selections.forEach { assertNull(f.controller.reading(it)) }
        f.controller.ensureMeasured(); runCurrent()
        assertEquals(6, f.probe.calls.size)
        selections.forEach { assertEquals(NodeLatencyObservation.Success(12, 8), f.controller.reading(it)) }
    }

    @Test fun wholeBatchFastPathTracksOpaqueTargetsRatherThanResultCount() = runTest {
        val f = Fixture(this); f.import()
        val oldSelections = f.selections()
        f.controller.ensureMeasured(); runCurrent()
        f.bytes = artifact(prefix = "198.51.100"); f.manager.refresh()
        val newSelections = f.selections()
        assertEquals(oldSelections.size, newSelections.size)
        oldSelections.forEach { assertNull(f.controller.reading(it)) }
        f.controller.ensureMeasured(); runCurrent()
        assertEquals(4, f.probe.calls.size)
        newSelections.forEach { assertNotNull(f.controller.reading(it)) }
    }

    @Test fun syncStillRevalidatesStoredBytesWhileFastPathEvidenceIsFresh() = runTest {
        val f = Fixture(this); f.import()
        val selection = f.selections()[0]
        f.controller.ensureMeasured(selection); runCurrent()
        f.profiles.read()!!.use { old ->
            CachedSubscription(old.binding, artifact(prefix = "198.51.100"), old.validUntil, old.url)
                .use(f.profiles::save)
        }
        val storageReads = f.profiles.reads
        f.controller.sync(); runCurrent()
        assertTrue(f.profiles.reads > storageReads)
        assertNull(f.controller.reading(selection)); assertEquals(1, f.probe.calls.size)
    }

    @Test fun expiryClearAndAuthenticationChangeHideCompletedReadingsImmediately() = runTest {
        for (change in 0..2) {
            val f = Fixture(this); f.import()
            val selection = f.selections()[0]
            f.controller.ensureMeasured(selection); runCurrent(); assertNotNull(f.controller.reading(selection))
            when (change) {
                0 -> f.time = start.plusSeconds(3600)
                1 -> f.manager.unlink()
                else -> f.auth++
            }
            assertNull(f.controller.reading(selection))
            f.controller.sync(); runCurrent(); assertNull(f.controller.reading(selection))
        }
    }

    @Test fun syncDropsChangedContentButOrdinarySelectionDoesNotInvalidateLatency() = runTest {
        val f = Fixture(this); f.import()
        val selections = f.selections()
        f.controller.ensureMeasured(); runCurrent()
        f.manager.selectNode(selections[1]); f.controller.sync(); runCurrent()
        assertNotNull(f.controller.reading(selections[0])); assertNotNull(f.controller.reading(selections[1]))
        f.bytes = artifact(prefix = "198.51.100"); f.manager.refresh()
        assertNull(f.controller.reading(selections[0]))
        f.controller.sync(); runCurrent()
        assertNull(f.controller.reading(selections[1]))
        assertEquals(2, f.probe.calls.size)
    }

    @Test fun pageCancellationKeepsCompletedResultsAndRejectsNonCooperativeLateResult() = runTest {
        val f = Fixture(this); f.import()
        val selections = f.selections()
        val gate = CompletableDeferred<Unit>()
        f.probe.response = { target ->
            if (target.selection === selections[1]) withContext(NonCancellable) { gate.await() }
            NodeLatencyObservation.Success(16, 7)
        }
        f.controller.ensureMeasured(); runCurrent()
        assertNotNull(f.controller.reading(selections[0])); assertNull(f.controller.reading(selections[1]))
        f.controller.cancel(); assertFalse(f.controller.busy)
        gate.complete(Unit); runCurrent()
        assertNotNull(f.controller.reading(selections[0])); assertNull(f.controller.reading(selections[1]))
        assertEquals(1, f.downloads); assertEquals(1, f.profiles.saves)
    }

    @Test fun networkChangesAndMismatchedPhysicalNetworkResultsCannotBePublished() = runTest {
        val f = Fixture(this); f.import()
        val selection = f.selections()[0]
        f.probe.response = { f.probe.network = 8; NodeLatencyObservation.Success(10, 7) }
        f.controller.ensureMeasured(selection); runCurrent(); assertNull(f.controller.reading(selection))
        f.probe.response = { NodeLatencyObservation.Success(10, 7) }
        f.controller.ensureMeasured(selection); runCurrent(); assertNull(f.controller.reading(selection))
        assertEquals(2, f.probe.calls.size)
    }

    @Test fun aBatchNeverExceedsFourProbesAndTimeoutIsNotAZeroMillisecondSuccess() = runTest {
        val f = Fixture(this, count = 8); f.import()
        val gate = CompletableDeferred<Unit>()
        var active = 0; var maximum = 0
        f.probe.response = {
            active++; maximum = maxOf(maximum, active)
            try { gate.await(); NodeLatencyObservation.Success(19, 7) } finally { active-- }
        }
        f.controller.ensureMeasured(); runCurrent()
        assertEquals(4, maximum); assertEquals(4, f.probe.calls.size)
        gate.complete(Unit); runCurrent()
        assertEquals(4, maximum); assertEquals(8, f.probe.calls.size); assertFalse(f.controller.busy)
        advanceTimeBy(30_000)
        val selection = f.selections()[0]
        f.probe.response = { awaitCancellation() }
        f.controller.refresh(selection); runCurrent(); advanceTimeBy(4_000); runCurrent()
        assertEquals(NodeLatencyObservation.NoReply, f.controller.reading(selection))
        assertFalse(f.controller.busy)
    }

    @Test fun disabledMissingAndForeignTargetsNeverStartAProbe() = runTest {
        val noProbe = Fixture(this, enabledProbe = false); noProbe.import()
        noProbe.controller.ensureMeasured(); runCurrent(); assertFalse(noProbe.controller.busy)
        assertNull(noProbe.controller.reading(noProbe.selections()[0]))
        val f = Fixture(this)
        f.controller.ensureMeasured(); runCurrent(); assertEquals(0, f.probe.calls.size)
        f.import(); f.allowed = false; f.controller.ensureMeasured(); runCurrent()
        assertEquals(0, f.probe.calls.size)
        f.allowed = true; f.controller.ensureMeasured(ConnectionSelection()); runCurrent()
        assertEquals(0, f.probe.calls.size)
    }

    @Test fun changedCachedBytesDuringARefreshInvalidateBothTheLateAndEarlierReading() = runTest {
        val f = Fixture(this); f.import()
        val selection = f.selections()[0]
        f.controller.ensureMeasured(selection); runCurrent(); assertNotNull(f.controller.reading(selection))
        advanceTimeBy(30_000)
        f.probe.response = {
            f.profiles.read()!!.use { old ->
                CachedSubscription(old.binding, artifact(prefix = "198.51.100"), old.validUntil, old.url)
                    .use(f.profiles::save)
            }
            NodeLatencyObservation.Success(11, 7)
        }
        f.controller.refresh(selection); runCurrent()
        assertNull(f.controller.reading(selection))
        assertEquals(1, f.downloads)
    }

    @Test fun accountChangeDuringAProbeAndOversizeRegistryCannotPublishOrProbeAnotherOwner() = runTest {
        val f = Fixture(this); f.import()
        val selection = f.selections()[0]
        f.probe.response = { f.auth++; NodeLatencyObservation.Success(11, 7) }
        f.controller.ensureMeasured(selection); runCurrent(); assertNull(f.controller.reading(selection))
        val oversized = Fixture(this, count = 129); oversized.import()
        oversized.controller.ensureMeasured(); runCurrent()
        assertEquals(0, oversized.probe.calls.size)
        assertFalse(oversized.controller.busy)
    }
}
