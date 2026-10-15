package com.gccwebos.android.portal

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Manual-import probe reads and batch target capture must not hold the shared UI lock while storage stalls. */
class ManualProbeSnapshotLockTest {
    private val now = Instant.parse("2026-10-14T00:00:00Z")
    private fun yaml(n: Int) = ("proxies:\n" + (1..n).joinToString("") { i ->
        "  - name: n$i\n    type: ss\n    server: 192.0.2.$i\n    port: 443\n    cipher: aes-128-gcm\n    password: synthetic-only\n" }).toByteArray()

    private class GatedStore(val backing: MemoryManualSourceStore = MemoryManualSourceStore()) : LibraryManualSourceStore {
        @Volatile var gate: CountDownLatch? = null
        @Volatile var entered: CountDownLatch? = null
        override fun read(): ManualSourceRecord? { entered?.countDown(); gate?.await(); return backing.read() }
        override fun save(record: ManualSourceRecord) = backing.save(record)
        override fun readLibrary(): ConfigurationLibrary? = backing.readLibrary()
        override fun saveLibrary(library: ConfigurationLibrary) = backing.saveLibrary(library)
    }

    private fun stalledReadDoesNotBlock(action: (LocalSubscriptionManager) -> Unit) = runTest {
        val store = GatedStore()
        val manager = LocalSubscriptionManager(MemoryDeliveryStore(), MemoryLocalProfileStore(), SubscriptionSource { error("no network") },
            OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { now }, LocalSubscriptionAuthority(),
            manualSources = store)
        manager.importManual(yaml(8)) { it() }
        val targets = manager.captureNodeProbeTargets()
        assertEquals(8, targets.size)
        manager.invalidatePending() // force a rebuild so the next probe path reads storage
        val gate = CountDownLatch(1); val entered = CountDownLatch(1)
        store.entered = entered; store.gate = gate
        val worker = Thread { action(manager) }.apply { start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val ui = Thread { manager.isNodeProbeTargetRegistryCurrent(targets[0]) }.apply { start() }
            ui.join(2_000)
            assertFalse("UI lock reader waited on a stalled manual-source read", ui.isAlive)
        } finally { gate.countDown(); worker.join(5_000) }
    }

    @Test fun perNodeRecheckReadsManualSourceOutsideTheLock() =
        stalledReadDoesNotBlock { m -> m.isCurrentNodeProbeTarget(NodeProbeTarget(m.nodeSelectionDisplaySnapshot().choices.first().selection, "192.0.2.1", "ss", 443, NodeProbeTransport.Tcp)) }

    @Test fun batchCaptureReadsAndParsesOutsideTheLock() = stalledReadDoesNotBlock { it.captureNodeProbeTargets() }
}
