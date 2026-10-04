package com.gccwebos.android.portal

import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SessionPersistenceTest {
    private val at = Instant.parse("2026-09-30T00:00:00Z")
    private val policy = OriginPolicy("https://portal.invalid", setOf("https://sub.invalid"))
    private val empty = object : DeliveryStore {
        override fun read(): DeliveryRecord? = null
        override fun save(record: DeliveryRecord) = Unit
        override fun clear() = Unit
    }
    private val saved = SavedSession(Secret("native_synthetic"), "fixture@example.invalid", at.plusSeconds(1800), 1800000, at, 1000)
    private val none = WireResponse(200, """{"resource":{"state":"none"}}""")
    @Test fun processRestartRestoresOnlyAfterAuthenticatedServerRead() = runTest {
        val sessions = MemorySessionStore().apply { save(saved) }
        var calls = 0
        val wire = object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                assertEquals("/api/portal/resources/current/overview", path); assertNull(body)
                assertEquals("native_synthetic", session!!.value); calls++; return none
            }
        }
        val repo = PortalRepository(wire, empty, policy, { 61000 }, { at.plusSeconds(60) }, sessions = sessions)
        assertEquals("fixture@example.invalid", repo.restoreSession()!!.accountId)
        assertEquals(1, calls); assertEquals(1740000, sessions.read()!!.remainingMillis)
        assertNotNull(repo.commerceSessionBinding())
    }
    @Test fun expiredClockRollbackAndBrowserContextsCannotRestore() {
        assertNull(saved.restore(at.plusSeconds(1800), 1801000))
        assertNull(saved.restore(at.minusSeconds(1), 1000))
        assertNull(SavedSession(Secret("portalctx_public"), "a", at.plusSeconds(60), 60000, at, 0).restore(at, 0))
        assertEquals(1799000, saved.restore(at, 2000)!!.lifetimeMillis)
    }
    @Test fun rebootStillUsesWallTtlAndNeverExtendsNativeTtl() {
        assertEquals(1740000, saved.restore(at.plusSeconds(60), 1)!!.lifetimeMillis)
        val long = Session(Secret("native"), "a", at.plusSeconds(604800), 604800000, 0)
        assertEquals(1800000, SavedSession.capture(long, at, 0).remainingMillis)
    }
    @Test fun serverRevocationClearsSavedTokenAndDoesNotExposeSession() = runTest {
        val sessions = MemorySessionStore().apply { save(saved) }
        val repo = PortalRepository(object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?) = WireResponse(401, "")
        }, empty, policy, {1000}, {at}, sessions = sessions)
        try { repo.restoreSession(); fail() } catch (e: PortalFailure) { assertEquals(PortalFailure.Reason.SESSION_EXPIRED, e.reason) }
        assertNull(sessions.read())
        try { repo.commerceSessionBinding(); fail() } catch (_: PortalFailure) { }
    }
    @Test fun temporaryFailurePreservesEncryptedRecoveryButNeverAdmitsSession() = runTest {
        val sessions = MemorySessionStore().apply { save(saved) }
        val repo = PortalRepository(object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse = throw PortalFailure(PortalFailure.Reason.NETWORK)
        }, empty, policy, {1000}, {at}, sessions = sessions)
        try { repo.restoreSession(); fail() } catch (e: PortalFailure) { assertEquals(PortalFailure.Reason.NETWORK, e.reason) }
        assertNotNull(sessions.read())
        try { repo.commerceSessionBinding(); fail() } catch (_: PortalFailure) { }
    }
    @Test fun lifecycleCancellationAfterRestoreSaveRetainsSessionForAnotherProcess() = runTest {
        val backing = MemorySessionStore().apply { save(saved) }
        lateinit var restoring: Deferred<Session?>
        var cancelAfterSave = true
        var saves = 0
        var clears = 0
        val sessions = object : SessionStore {
            override fun read() = backing.read()
            override fun save(value: SavedSession) {
                backing.save(value)
                saves++
                if (cancelAfterSave) {
                    cancelAfterSave = false
                    restoring.cancel() // Ordinary lifecycle cancellation after a successful durable save.
                }
            }
            override fun clear() { clears++; backing.clear() }
        }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var reads = 0
        val wire = object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                assertEquals("/api/portal/resources/current/overview", path)
                assertNull(body)
                assertEquals(saved.token.value, session!!.value)
                if (++reads == 1) { started.complete(Unit); release.await() }
                return none
            }
        }
        val repo = PortalRepository(wire, empty, policy, {61000}, {at.plusSeconds(60)}, sessions = sessions)
        restoring = async { repo.restoreSession() }
        started.await(); release.complete(Unit)
        try { restoring.await(); fail("Cancelled restore must not publish authentication") }
        catch (_: CancellationException) { }
        assertEquals(1, saves)
        try { repo.commerceSessionBinding(); fail("Cancelled restore must remain unauthenticated") }
        catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.SESSION_EXPIRED, failure.reason) }
        assertEquals("Lifecycle cancellation must not clear a valid saved session", 0, clears)
        val retained = requireNotNull(sessions.read())
        assertEquals(saved.token.value, retained.token.value)
        assertEquals(saved.accountId, retained.accountId)
        assertEquals(saved.expiresAt, retained.expiresAt)
        assertEquals(1740000L, retained.remainingMillis)

        // A new repository still requires its own authenticated read; lifetime is not extended.
        val restarted = PortalRepository(wire, empty, policy, {121000}, {at.plusSeconds(120)}, sessions = sessions)
        val restored = requireNotNull(restarted.restoreSession())
        assertEquals(saved.accountId, restored.accountId)
        assertEquals(saved.expiresAt, restored.expiresAt)
        assertEquals(1680000L, restored.lifetimeMillis)
        assertEquals(2, reads)
        assertEquals(2, saves)
        assertEquals(0, clears)
        assertNotNull(restarted.commerceSessionBinding())
    }
    @Test fun actualRestoreSaveFailureDoesNotPublishAuthentication() = runTest {
        val backing = MemorySessionStore().apply { save(saved) }
        var saveAttempts = 0
        val sessions = object : SessionStore {
            override fun read() = backing.read()
            override fun save(value: SavedSession) {
                saveAttempts++
                throw PortalFailure(PortalFailure.Reason.STORAGE)
            }
            override fun clear() = backing.clear()
        }
        val repo = PortalRepository(object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?) = none
        }, empty, policy, {1000}, {at}, sessions = sessions)
        try { repo.restoreSession(); fail("Storage failure must not restore authentication") }
        catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.STORAGE, failure.reason) }
        assertEquals(1, saveAttempts)
        assertNull(sessions.read())
        try { repo.commerceSessionBinding(); fail("Storage failure must remain unauthenticated") }
        catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.SESSION_EXPIRED, failure.reason) }
    }
    @Test fun logoutDuringRestoreCannotResurrectToken() = runTest {
        val sessions = MemorySessionStore().apply { save(saved) }
        val start = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val repo = PortalRepository(object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse { start.complete(Unit); finish.await(); return none }
        }, empty, policy, {1000}, {at}, sessions = sessions)
        val restore = async { repo.restoreSession() }; start.await()
        val logout = launch { repo.clearLocalData() }; runCurrent(); finish.complete(Unit)
        try { restore.await(); fail() } catch (_: CancellationException) { }
        logout.join(); assertNull(sessions.read())
    }
    @Test fun explicitCancelPreservesDeliveryButClearsRememberedLogin() = runTest {
        val sessions = MemorySessionStore().apply { save(saved) }
        var cleared = false
        val delivery = object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = Unit
            override fun clear() { cleared = true }
        }
        val repo = PortalRepository(object : PortalTransport { override suspend fun request(path: String, body: String?, session: Secret?) = none },
            delivery, policy, {1000}, {at}, sessions = sessions)
        repo.cancelAuthentication(); assertNull(sessions.read()); assertFalse(cleared)
    }
    @Test fun cancelledResetCallerCannotLeaveTokenRestorable() = runTest {
        for (logout in listOf(false, true)) {
            val sessions = MemorySessionStore().apply { save(saved) }
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val repo = PortalRepository(object : PortalTransport {
                override suspend fun request(path: String, body: String?, session: Secret?): WireResponse { started.complete(Unit); release.await(); return none }
            }, empty, policy, {1000}, {at}, sessions = sessions)
            val reading = async { repo.restoreSession() }; started.await()
            val reset = launch { if (logout) repo.clearLocalData() else repo.cancelAuthentication() }
            runCurrent(); reset.cancel()
            // Token removal is admitted synchronously, before mutex/network drainage.
            assertNull(sessions.read())
            release.complete(Unit)
            try { reading.await(); fail() } catch (_: CancellationException) { }
            reset.join(); assertNull(sessions.read()); assertNull(repo.restoreSession())
        }
    }
    @Test fun diagnosticsNeverContainSessionMaterial() {
        assertEquals("SavedSession([REDACTED])", saved.toString())
        assertFalse(saved.toString().contains("native_synthetic"))
    }
}
