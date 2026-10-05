package com.gccwebos.android.portal

import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LocalNodeDisplaySnapshotTest {
    @Test fun displayTickHidesKnownStorageFailureAndInterruptedClearWithoutReadingAgain() = runTest {
        for (pendingClear in listOf(false, true)) {
            val memory = MemoryManualSourceStore()
            var reads = 0
            var failRead = false
            val store = object : ManualSourceStore {
                override fun read(): ManualSourceRecord? {
                    reads++
                    if (failRead) throw IllegalStateException("Synthetic storage failure")
                    return memory.read()
                }
                override fun save(record: ManualSourceRecord) = memory.save(record)
            }
            val manager = LocalSubscriptionManager(MemoryDeliveryStore(), MemoryLocalProfileStore(),
                SubscriptionSource { error("Local display must not download") },
                OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")),
                { Instant.parse("2026-10-05T00:00:00Z") }, LocalSubscriptionAuthority(), manualSources = store)
            val yaml = """proxies:
  - name: Synthetic display
    type: ss
    server: 192.0.2.10
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""".toByteArray()
            try { manager.importManual(yaml) { it() } } finally { yaml.fill(0) }
            val controller = NodeSelectionController(manager, this, { 0L })
            controller.sync()
            assertTrue(controller.select(controller.snapshot.choices.single().selection))
            if (pendingClear) ManualSourceRecord(kind = ManualSourceKind.None, clearPending = true).use(memory::save)
            else failRead = true
            // The same read path used by a latency batch observes the durable-store failure.
            try { manager.captureNodeProbeTargets() } catch (_: PortalFailure) { }
            assertEquals(LocalSubscriptionStatus.STORAGE_ERROR, manager.snapshot().status)
            val beforeTicks = reads
            repeat(5) { controller.expireIfNeeded() }
            assertEquals(LocalSubscriptionStatus.STORAGE_ERROR, controller.snapshot.status)
            assertTrue(controller.snapshot.choices.isEmpty()); assertNull(controller.snapshot.selected)
            assertEquals(beforeTicks, reads)
        }
    }
}
