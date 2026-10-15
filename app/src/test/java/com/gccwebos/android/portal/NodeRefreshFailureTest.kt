package com.gccwebos.android.portal

import java.time.Instant
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** A failing subscription refresh must end busy, explain itself and never escape as an uncaught exception. */
class NodeRefreshFailureTest {
    private val now = Instant.parse("2026-10-14T00:00:00Z")
    private val yaml = "proxies:\n  - name: n1\n    type: ss\n    server: 192.0.2.1\n    port: 443\n    cipher: aes-128-gcm\n    password: synthetic-only\n".toByteArray()

    private class FailingLibrary(val backing: MemoryManualSourceStore = MemoryManualSourceStore()) : LibraryManualSourceStore {
        var fail = false
        override fun read(): ManualSourceRecord? { if (fail) throw java.io.IOException("synthetic"); return backing.read() }
        override fun save(record: ManualSourceRecord) = backing.save(record)
        override fun readLibrary(): ConfigurationLibrary? { if (fail) throw java.io.IOException("synthetic"); return backing.readLibrary() }
        override fun saveLibrary(library: ConfigurationLibrary) = backing.saveLibrary(library)
    }

    @Test fun storageFailureDuringRefreshEndsBusyWithStorageErrorStatus() = runTest {
        val store = FailingLibrary()
        val manager = LocalSubscriptionManager(MemoryDeliveryStore(), MemoryLocalProfileStore(), SubscriptionSource { error("no network") },
            OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { now }, LocalSubscriptionAuthority(),
            manualSources = store)
        manager.importManual(yaml) { it() }
        val controller = NodeSelectionController(manager, this, { 0L }, ioDispatcher = coroutineContext[kotlin.coroutines.ContinuationInterceptor] as kotlinx.coroutines.CoroutineDispatcher)
        store.fail = true
        controller.refresh()
        runCurrent(); advanceUntilIdle()
        assertFalse(controller.busy)
        assertEquals(LocalSubscriptionStatus.STORAGE_ERROR, controller.snapshot.status)
    }

    @Test fun controlHealthyRefreshEndsBusy() = runTest {
        val store = FailingLibrary()
        val manager = LocalSubscriptionManager(MemoryDeliveryStore(), MemoryLocalProfileStore(), SubscriptionSource { error("no network") },
            OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { now }, LocalSubscriptionAuthority(),
            manualSources = store)
        manager.importManual(yaml) { it() }
        val controller = NodeSelectionController(manager, this, { 0L }, ioDispatcher = coroutineContext[kotlin.coroutines.ContinuationInterceptor] as kotlinx.coroutines.CoroutineDispatcher)
        controller.refresh()
        runCurrent(); advanceUntilIdle()
        assertFalse(controller.busy)
    }

    @Test fun networkFailureDuringAccountRefreshEndsBusyWithoutEscaping() = runTest {
        for (failure in listOf<Exception>(PortalFailure(PortalFailure.Reason.NETWORK), java.io.IOException("synthetic"),
            PortalFailure(PortalFailure.Reason.UNAVAILABLE))) {
            var fail = false
            val delivery = MemoryDeliveryStore()
            delivery.save(DeliveryRecord("acct", "binding", "resource", "op", Secret("https://sub.invalid/sub/a/x"), null, false))
            val manager = LocalSubscriptionManager(delivery, MemoryLocalProfileStore(), object : SubscriptionSource {
                override suspend fun download(url: Secret) = read(url).bytes
                override suspend fun read(url: Secret): SubscriptionRead {
                    if (fail) throw failure
                    return SubscriptionRead(yaml.copyOf(), now.plusSeconds(3600))
                }
            }, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { now }, LocalSubscriptionAuthority())
            manager.refresh()
            val controller = NodeSelectionController(manager, this, { 0L },
                ioDispatcher = coroutineContext[kotlin.coroutines.ContinuationInterceptor] as kotlinx.coroutines.CoroutineDispatcher)
            fail = true
            controller.refresh(); runCurrent(); advanceUntilIdle()
            assertFalse(controller.busy)
            assertTrue("$failure must be visible", controller.message != null || controller.snapshot.status != LocalSubscriptionStatus.READY)
        }
    }
}
