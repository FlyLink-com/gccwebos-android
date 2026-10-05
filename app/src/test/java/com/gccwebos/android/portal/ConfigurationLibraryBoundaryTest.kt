package com.gccwebos.android.portal

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Only synthetic in-memory stores and an owned worker; no real endpoint, app files or VPN. */
class ConfigurationLibraryBoundaryTest {
    private val now = Instant.parse("2026-10-05T00:00:00Z")
    private val yaml = """
        proxies:
          - name: Synthetic boundary
            type: ss
            server: 192.0.2.10
            port: 443
            cipher: aes-128-gcm
            password: synthetic-only
    """.trimIndent().toByteArray()

    private class FaultingLibraryStore(val backing: MemoryManualSourceStore) : LibraryManualSourceStore {
        var returnMissingLibrary = false
        var sourceReads = 0
        var libraryReads = 0
        var saves = 0
        override fun read(): ManualSourceRecord? { sourceReads++; return backing.read() }
        override fun readLibrary(): ConfigurationLibrary? {
            libraryReads++
            return if (returnMissingLibrary) null else backing.readLibrary()
        }
        override fun save(record: ManualSourceRecord) { saves++; backing.save(record) }
        override fun saveLibrary(library: ConfigurationLibrary) { saves++; backing.saveLibrary(library) }
    }

    private fun manager(store: LibraryManualSourceStore, authority: LocalSubscriptionAuthority) =
        LocalSubscriptionManager(object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = error("No account write allowed")
            override fun clear() = error("No account clear allowed")
        }, MemoryLocalProfileStore(), SubscriptionSource { error("No HTTP allowed") },
            OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { now }, authority,
            manualSources = store)

    private fun seed(): MemoryManualSourceStore {
        val store = MemoryManualSourceStore(null)
        ManualSourceRecord(kind = ManualSourceKind.ImportedLocal, bytes = yaml).use { source ->
            ConfigurationEntry(source.revision, "原名称", source).use { entry ->
                ConfigurationLibrary(entries = listOf(entry), selector = ConfigurationSelector.Entry,
                    selectedEntryId = entry.id, selectorRevision = entry.sourceRevision).use(store::saveLibrary)
            }
        }
        return store
    }

    @Test fun knownActiveReadFollowedByMissingLibraryNeverNormalizesOrWritesAReplacement() = runBlocking {
        val store = FaultingLibraryStore(seed())
        val authority = LocalSubscriptionAuthority()
        val manager = manager(store, authority)
        manager.restore()
        val before = manager.configurationLibrarySnapshot()
        assertFalse(before.storageError)
        val handle = before.items.single { it.kind == ManualSourceKind.ImportedLocal }.handle
        val sourceReads = store.sourceReads
        val writes = store.saves
        store.returnMissingLibrary = true

        val missing = manager.configurationLibrarySnapshot()
        assertTrue(missing.storageError)
        assertTrue(missing.items.isEmpty())
        assertTrue(store.sourceReads > sourceReads)
        assertEquals(LocalSubscriptionStatus.STORAGE_ERROR, manager.nodeSelectionDisplaySnapshot().status)
        try {
            manager.renameConfiguration(handle, "不得保存")
            fail("A missing known library was accepted as a legacy source")
        } catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.STORAGE, failure.reason) }
        assertEquals(writes, store.saves)
        store.backing.readLibrary()!!.use { retained ->
            assertEquals(1, retained.entries.size); assertEquals("原名称", retained.entries.single().name)
        }
    }

    @Test fun renameCancelledWhileWaitingForAuthorityMonitorCannotCommitAfterTheLockIsReleased() {
        val store = FaultingLibraryStore(seed())
        val authority = LocalSubscriptionAuthority()
        val manager = manager(store, authority)
        val snapshot = manager.configurationLibrarySnapshot()
        assertFalse(snapshot.storageError)
        val handle = snapshot.items.single { it.kind == ManualSourceKind.ImportedLocal }.handle
        val writes = store.saves
        val worker = AtomicReference<Thread>()
        val started = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "synthetic-library-boundary").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var operation: Job? = null
        try {
            synchronized(authority.lock) {
                operation = scope.launch {
                    worker.set(Thread.currentThread())
                    started.countDown()
                    manager.renameConfiguration(handle, "取消后的名称")
                }.also { it.invokeOnCompletion { completed.countDown() } }
                assertTrue("Synthetic worker never started", started.await(5, TimeUnit.SECONDS))
                awaitBlocked(worker.get())
                operation!!.cancel()
            }
            assertTrue("Cancelled synthetic operation did not finish", completed.await(5, TimeUnit.SECONDS))
            assertTrue(operation!!.isCancelled)
            assertEquals(writes, store.saves)
            store.readLibrary()!!.use { retained -> assertEquals("原名称", retained.entries.single().name) }
        } finally {
            operation?.cancel()
            scope.cancel()
            dispatcher.close()
        }
    }

    /** Observe the exact monitor wait without a sleep that could race the cancellation point. */
    private fun awaitBlocked(worker: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (worker.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
        assertEquals("Synthetic rename did not reach the authority monitor", Thread.State.BLOCKED, worker.state)
    }
}
