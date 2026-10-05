package com.gccwebos.android.portal

import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ManualSourceRepositoryClearTest {
    private val now = Instant.parse("2026-10-04T00:00:00Z")
    private val policy = OriginPolicy("https://portal.invalid", setOf("https://sub.invalid"))
    private val saved = SavedSession(Secret("native_synthetic"), "synthetic@example.invalid", now.plusSeconds(600), 600000, now, 0)
    private val yaml = """proxies:
  - name: Synthetic imported node
    type: ss
    server: 192.0.2.10
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""".toByteArray()
    @Test fun clearRevokesPersistedSessionAndSourceBeforeOutstandingReadDrains() = runTest {
        val sessions = MemorySessionStore().apply { save(saved) }
        val sources = MemoryManualSourceStore()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>(); val cleanupComplete = CompletableDeferred<Unit>()
        val repo = PortalRepository(object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                started.complete(Unit); release.await()
                return WireResponse(200, """{"resource":{"state":"none"}}""")
            }
        }, MemoryDeliveryStore(), policy, { 0 }, { now }, sessions = sessions, manualSources = sources)
        repo.localSubscriptions.importManual(yaml) { it() }
        val restoring = async { repo.restoreSession() }; started.await()
        val clear = launch {
            repo.clearLocalData(cleanup = { cleanupEntered.complete(Unit); cleanupComplete.await() }) { it() }
        }
        try {
            runCurrent()
            assertNull(sessions.read()) // Existing synchronous token-revocation contract remains required.
            assertTrue(sources.read()!!.use { it.kind == ManualSourceKind.None && it.clearPending })
            assertEquals(LocalSubscriptionStatus.STORAGE_ERROR, repo.localSubscriptions.snapshot().status)
            release.complete(Unit)
            try { restoring.await(); fail("Late authentication was restored") } catch (_: CancellationException) { }
            cleanupEntered.await()
            assertTrue(sources.read()!!.use { it.clearPending })
            cleanupComplete.complete(Unit); clear.join()
            assertNull(sessions.read())
            assertFalse(sources.read()!!.use { it.clearPending })
        } finally { release.complete(Unit); cleanupComplete.complete(Unit); clear.join() }
    }
    @Test fun sessionDeletionFailureStillRunsOwnerCleanupAndKeepsSourceClearPending() = runTest {
        val backing = MemorySessionStore().apply { save(saved) }
        var failClear = true; var cleanupCalls = 0
        val sessions = object : SessionStore {
            override fun read() = backing.read()
            override fun save(value: SavedSession) = backing.save(value)
            override fun clear() { if (failClear) throw PortalFailure(PortalFailure.Reason.STORAGE); backing.clear() }
        }
        val sources = MemoryManualSourceStore()
        val repo = PortalRepository(object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?) = error("No HTTP")
        }, MemoryDeliveryStore(), policy, { 0 }, { now }, sessions = sessions, manualSources = sources)
        repo.localSubscriptions.importManual(yaml) { it() }
        try {
            repo.clearLocalData(cleanup = { cleanupCalls++ }) { fail("Failed deletion must not finalize source clear") }
            fail("Failed deletion was reported complete")
        } catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.STORAGE, failure.reason) }
        assertEquals(1, cleanupCalls)
        assertTrue(sources.read()!!.use { it.clearPending })
        assertEquals(LocalSubscriptionStatus.STORAGE_ERROR, repo.localSubscriptions.snapshot().status)
        failClear = false
        repo.clearLocalData(cleanup = { cleanupCalls++ }) { it() }
        assertEquals(2, cleanupCalls); assertNull(sessions.read())
        assertFalse(sources.read()!!.use { it.clearPending })
    }
    @Test fun disposedClearCallerStillWaitsOwnerAndFinalizesOnlyAfterCleanup() = runTest {
        val sources = MemoryManualSourceStore()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = PortalRepository(object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?) = error("No HTTP")
        }, MemoryDeliveryStore(), policy, { 0 }, { now }, manualSources = sources)
        repo.localSubscriptions.importManual(yaml) { it() }
        val clear = launch {
            repo.clearLocalData(cleanup = { started.complete(Unit); release.await() }) { it() }
        }
        try {
            started.await(); clear.cancel(); runCurrent()
            assertFalse(clear.isCompleted)
            assertTrue(sources.read()!!.use { it.clearPending })
            release.complete(Unit); clear.join()
            assertFalse(sources.read()!!.use { it.clearPending })
            assertEquals(LocalSubscriptionStatus.MISSING, repo.localSubscriptions.snapshot().status)
        } finally { release.complete(Unit); clear.join() }
    }
}
