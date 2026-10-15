package com.gccwebos.android.portal

import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NodeRefreshTimeoutTest {
    private val now = Instant.parse("2026-10-14T00:00:00Z")
    private val yaml = "proxies:\n  - name: n1\n    type: ss\n    server: 192.0.2.1\n    port: 443\n    cipher: aes-128-gcm\n    password: synthetic-only\n".toByteArray()

    @Test fun aHungSubscriptionReadEndsAsARecoverableTimeout() = runTest {
        var hang = false
        val never = CompletableDeferred<Unit>()
        val delivery = MemoryDeliveryStore()
        delivery.save(DeliveryRecord("acct", "binding", "resource", "op", Secret("https://sub.invalid/sub/a/x"), null, false))
        val manager = LocalSubscriptionManager(delivery, MemoryLocalProfileStore(), object : SubscriptionSource {
            override suspend fun download(url: Secret) = read(url).bytes
            override suspend fun read(url: Secret): SubscriptionRead {
                if (hang) never.await()
                return SubscriptionRead(yaml.copyOf(), now.plusSeconds(3600))
            }
        }, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { now }, LocalSubscriptionAuthority())
        manager.refresh()
        val controller = NodeSelectionController(manager, this, { 0L },
            ioDispatcher = coroutineContext[kotlin.coroutines.ContinuationInterceptor] as CoroutineDispatcher)
        hang = true
        controller.refresh(); runCurrent()
        assertTrue(controller.busy)
        advanceTimeBy(NodeSelectionController.RefreshTimeoutMillis + 1); runCurrent()
        assertFalse("a hung read must not load forever", controller.busy)
        assertEquals(NodeSelectionController.RefreshTimeoutMessage, controller.message)
        hang = false
        controller.refresh(); advanceUntilIdle()
        assertFalse(controller.busy); assertNull(controller.message)
        assertEquals(LocalSubscriptionStatus.READY, controller.snapshot.status)
    }
}
