package com.gccwebos.android.portal

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/**
 * The latency test covers every node of the unified list, not only the active source's. Synthetic data only; the
 * probe records endpoints but nothing here is a real address.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryLatencyTest {
    private val now = Instant.parse("2026-10-10T00:00:00Z")
    private fun hosts(prefix: String, count: Int) = (1..count).map { "192.0.2.${prefix.hashCode().and(0x3f) * 3 + it}" }.toSet()
    private fun yaml(prefix: String, count: Int): ByteArray = buildString {
        append("proxies:\n")
        for (index in 1..count) {
            append("  - name: $prefix $index\n    type: ss\n    server: 192.0.2.${prefix.hashCode().and(0x3f) * 3 + index}\n")
            append("    port: 443\n    cipher: aes-128-gcm\n    password: synthetic-only\n")
        }
    }.toByteArray()

    private class Probe : NodeLatencyProbe {
        var network: Long? = 7L
        val endpoints = mutableListOf<String>()
        val running = AtomicInteger(); var peak = 0
        var response: suspend (NodeProbeTarget) -> NodeLatencyObservation = { NodeLatencyObservation.Success(12, requireNotNull(network)) }
        override fun currentNetworkKey() = network
        override suspend fun measure(target: NodeProbeTarget): NodeLatencyObservation {
            endpoints += target.endpoint
            peak = maxOf(peak, running.incrementAndGet())
            try { return response(target) } finally { running.decrementAndGet() }
        }
    }

    private inner class Fixture(val test: TestScope) {
        val store = MemoryManualSourceStore()
        val profiles = MemoryLocalProfileStore()
        val overrides = MemoryNodeOverrideStore()
        var time = now
        val probe = Probe()
        val manager = LocalSubscriptionManager(object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = error("No delivery")
            override fun clear() = Unit
        }, profiles, SubscriptionSource { error("No HTTP expected") },
            OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { time },
            LocalSubscriptionAuthority(), manualSources = store, nodeOverrides = overrides)
        val controller = NodeLatencyController(manager, test.backgroundScope, probe = probe, authGeneration = { 1L },
            elapsedMillis = { test.testScheduler.currentTime }, ioDispatcher = StandardTestDispatcher(test.testScheduler))
        suspend fun local(prefix: String, count: Int) { val b = yaml(prefix, count); try { manager.importManual(b) { it() } } finally { b.fill(0) } }
        fun seedAccount() {
            val bytes = yaml("Account", 1)
            val source = Secret("https://sub.invalid/sub/a/library-latency")
            try { CachedSubscription(sessionBinding(source), bytes, now.plusSeconds(3600), source).use(profiles::save) }
            finally { bytes.fill(0) }
        }
        fun items() = manager.configurationLibrarySnapshot(true).items
        fun other() = items().single { !it.active && it.nodes.isNotEmpty() }
        fun source(item: ConfigurationItem) = item.handle.entryId ?: AccountSourceId
        fun reading(item: ConfigurationItem, position: Int) = controller.libraryReading(source(item), position, item.contentKey)
    }

    @Test fun oneBatchMeasuresTheActiveSourceAndEveryOtherSourceIncludingTheAccount() = runTest {
        val f = Fixture(this); f.seedAccount(); f.local("Alpha", 2); f.local("Beta", 3)
        f.manager.nodeSelectionSnapshot()
        val items = f.items()
        val active = items.single { it.active }
        assertEquals(3, active.nodes.size)
        f.controller.ensureMeasured(); runCurrent()
        // 3 active + 2 Alpha + 1 account.
        assertEquals(6, f.probe.endpoints.size); assertEquals(6, f.probe.endpoints.distinct().size)
        val others = items.filter { !it.active && it.nodes.isNotEmpty() }
        assertEquals(2, others.size)
        for (item in others) item.nodes.indices.forEach { position ->
            assertEquals("${item.name} #$position", NodeLatencyObservation.Success(12, 7), f.reading(item, position))
        }
        assertFalse(f.controller.busy)
        assertNotNull(f.controller.lastResultAtElapsed())
    }

    @Test fun aSecondAutomaticPassDoesNotProbeAgainWhileEverythingIsFresh() = runTest {
        val f = Fixture(this); f.seedAccount(); f.local("Alpha", 2); f.local("Beta", 2)
        f.manager.nodeSelectionSnapshot()
        f.controller.ensureMeasured(); runCurrent()
        val first = f.probe.endpoints.size
        repeat(5) { advanceTimeBy(1_000); f.controller.ensureMeasured(); runCurrent() }
        assertEquals(first, f.probe.endpoints.size)
    }

    @Test fun aReadingBelongsToTheExactContentAndIsDroppedWhenTheSourceChanges() = runTest {
        val f = Fixture(this); f.local("Alpha", 2); f.local("Beta", 1)
        f.manager.nodeSelectionSnapshot()
        f.controller.ensureMeasured(); runCurrent()
        val alpha = f.other()
        assertNotNull(f.reading(alpha, 0))
        assertNull(f.controller.libraryReading(f.source(alpha), 0, "0000"))
        assertNull(f.controller.libraryReading(f.source(alpha), 0, null))
        assertNull(f.controller.libraryReading("unknown-entry", 0, alpha.contentKey))
        assertNotEquals(alpha.contentKey, f.items().single { it.active }.contentKey)
    }

    @Test fun locallyDeletedNodesAreNotProbed() = runTest {
        val f = Fixture(this); f.local("Alpha", 3); f.local("Beta", 1)
        val alpha = f.other()
        f.manager.setNodeHidden(alpha.handle, requireNotNull(alpha.nodeKeys[1]), true)
        f.manager.nodeSelectionSnapshot()
        f.controller.ensureMeasured(); runCurrent()
        // 1 active + 2 visible Alpha nodes; the deleted one is skipped.
        assertEquals(3, f.probe.endpoints.size)
        val refreshed = f.other()
        assertNull(f.reading(refreshed, 1)); assertNotNull(f.reading(refreshed, 0)); assertNotNull(f.reading(refreshed, 2))
    }

    @Test fun aSingleNodeRetestNeverTouchesOtherSources() = runTest {
        val f = Fixture(this); f.local("Alpha", 2); f.local("Beta", 2)
        val choices = f.manager.nodeSelectionSnapshot().choices
        f.controller.refresh(choices[0].selection); runCurrent()
        assertEquals(1, f.probe.endpoints.size)
        assertNull(f.reading(f.other(), 0))
    }

    @Test fun concurrencyStaysBoundedAcrossAllSources() = runTest {
        val f = Fixture(this); f.local("Alpha", 20); f.local("Beta", 20)
        f.manager.nodeSelectionSnapshot()
        f.probe.response = { delay(50); NodeLatencyObservation.Success(10, 7) }
        f.controller.ensureMeasured(); runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals(40, f.probe.endpoints.size)
        assertTrue("peak ${f.probe.peak}", f.probe.peak in 1..4)
    }

    @Test fun unreachableNodesOfOtherSourcesAreNotRetriedByAutomaticPassesButAreByAnExplicitTest() = runTest {
        val f = Fixture(this); f.local("Alpha", 2); f.local("Beta", 1)
        f.manager.nodeSelectionSnapshot()
        val alpha = hosts("Alpha", 2)
        f.probe.response = { target -> if (target.endpoint in alpha) NodeLatencyObservation.NoReply
            else NodeLatencyObservation.Success(12, 7) }
        fun alphaProbes() = f.probe.endpoints.count { it in alpha }
        f.controller.ensureMeasured(); runCurrent()
        assertEquals(2, alphaProbes())
        // Failed readings expire after 10 s; automatic passes still leave those nodes alone for 30 s.
        repeat(2) { advanceTimeBy(11_000); f.controller.ensureMeasured(); runCurrent() }
        assertEquals(2, alphaProbes())
        f.controller.refresh(); runCurrent()
        assertEquals(4, alphaProbes())
        advanceTimeBy(31_000); f.controller.ensureMeasured(); runCurrent()
        assertEquals(6, alphaProbes())
    }

    @Test fun networkChangeHidesLibraryReadingsAndAccountChangeClearsThem() = runTest {
        var auth = 1L
        val f = Fixture(this)
        val controller = NodeLatencyController(f.manager, backgroundScope, probe = f.probe, authGeneration = { auth },
            elapsedMillis = { testScheduler.currentTime }, ioDispatcher = StandardTestDispatcher(testScheduler))
        f.local("Alpha", 2); f.local("Beta", 1); f.manager.nodeSelectionSnapshot()
        controller.ensureMeasured(); runCurrent()
        val alpha = f.other()
        assertNotNull(controller.libraryReading(f.source(alpha), 0, alpha.contentKey))
        f.probe.network = 8
        assertNull(controller.libraryReading(f.source(alpha), 0, alpha.contentKey))
        f.probe.network = 7
        auth = 2
        controller.prune()
        assertNull(controller.libraryReading(f.source(alpha), 0, alpha.contentKey))
    }

    @Test fun contentKeyIsStableForEqualBytesAndDiffersForDifferentBytes() = runTest {
        val f = Fixture(this); f.local("Alpha", 2); f.local("Beta", 2)
        val before = f.items()
        val after = f.items()
        assertEquals(before.map { it.contentKey }, after.map { it.contentKey })
        val keys = before.mapNotNull { it.contentKey }
        assertEquals(2, keys.distinct().size)
        assertTrue(keys.all { it.length == 32 })
    }
}
