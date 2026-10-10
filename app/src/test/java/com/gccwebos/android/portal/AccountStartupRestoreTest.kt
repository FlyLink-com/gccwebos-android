package com.gccwebos.android.portal

import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** Fresh UI/repository owners restore synthetic durable state; no HTTP client or device stores. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountStartupRestoreTest {
    private val now = Instant.parse("2026-10-04T00:00:00Z")
    private val accountId = "account_startup_fixture"
    private val token = "native_startup_fixture"
    private val profileExpiry = now.plusSeconds(86400)
    private val policy = OriginPolicy("https://portal.invalid", setOf("https://sub.invalid"))
    private val source = Secret("https://sub.invalid/sub/a/startup-fixture")
    private val yaml = """proxies:
  - name: Startup fixture
    type: ss
    server: 192.0.2.10
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""".toByteArray()
    private val available = WireResponse(200, """{"resource":{"state":"available",
        "plan":{"id":"startup-fixture","durationDays":30,"monthlyTrafficGb":10},
        "validUntil":"2026-11-04T00:00:00.000Z","setupAvailable":true,
        "resourceRef":"resource_${"a".repeat(64)}",
        "usage":{"totalBytes":1024,"usedBytes":256,"remainingBytes":768,
        "collectionStatus":"measured","updatedAt":"2026-10-04T00:00:00.000Z",
        "cycleStartedAt":"2026-10-01T00:00:00.000Z","cycleEndsAt":"2026-11-01T00:00:00.000Z"}}}""")

    private fun saved(at: Instant = now) = SavedSession(Secret(token), accountId,
        at.plusSeconds(1800), 1800000, at, 0)

    private inner class Fixture(savedSession: SavedSession? = saved()) {
        val sessions = MemorySessionStore().apply { savedSession?.let(::save) }
        private val memoryProfiles = MemoryLocalProfileStore().apply {
            CachedSubscription(sessionBinding(source), yaml, profileExpiry, source).use { save(it) }
        }
        var profileWrites = 0
        var profileClears = 0
        val profiles = object : LocalProfileStore {
            override fun read() = memoryProfiles.read()
            override fun save(value: CachedSubscription) { profileWrites++; memoryProfiles.save(value) }
            override fun clear() { profileClears++; memoryProfiles.clear() }
        }
        val originalDelivery = DeliveryRecord(accountId, "startup-session-binding", "startup-resource",
            "startup-operation", source, Secret("startup-handoff"), false)
        var deliveryValue: DeliveryRecord? = originalDelivery
        var deliveryWrites = 0
        var deliveryClears = 0
        private val delivery = object : DeliveryStore {
            override fun read() = deliveryValue
            override fun save(record: DeliveryRecord) { deliveryWrites++; deliveryValue = record }
            override fun clear() { deliveryClears++; deliveryValue = null }
        }
        val calls = mutableListOf<String>()
        var downloads = 0
        var reply: suspend (Int) -> WireResponse = { available }
        private val wire = object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                calls += path
                assertEquals("Only an authenticated overview read is allowed during cached startup",
                    "/api/portal/resources/current/overview", path)
                assertNull(body)
                assertEquals(token, session?.value)
                return reply(calls.size)
            }
        }

        fun controller(scope: CoroutineScope): AccountController {
            val repository = PortalRepository(wire, delivery, policy, { 0L }, { now },
                subscription = SubscriptionSource { downloads++; error("Cached startup must not download") },
                sessions = sessions, profiles = profiles)
            // This fixture owns memory stores, not another test's process-wide selection handles.
            repository.localSubscriptions.invalidateNodeSelection()
            return AccountController(repository, scope)
        }

        fun assertLocalPreserved(state: AccountController) {
            assertTrue(state.localSubscription.hasProfile)
            assertTrue(state.localSubscription.hasCapability)
            assertEquals(LocalSubscriptionStatus.READY, state.localSubscription.status)
            assertEquals(1, state.localSubscription.nodes.size)
            assertEquals(profileExpiry, state.localSubscription.validUntil)
            requireNotNull(profiles.read()).use { cached ->
                assertEquals(sessionBinding(source), cached.binding)
                assertEquals(profileExpiry, cached.validUntil)
                val bytes = cached.copyBytes()
                try { assertArrayEquals(yaml, bytes) } finally { bytes.fill(0) }
            }
            assertSame(originalDelivery, deliveryValue)
            assertEquals(0, profileWrites)
            assertEquals(0, profileClears)
            assertEquals(0, deliveryWrites)
            assertEquals(0, deliveryClears)
            assertEquals(0, downloads)
        }

        fun assertVerifiedAccount(state: AccountController) {
            assertTrue(state.authenticated)
            assertEquals(accountId, state.authenticatedAccountId)
            assertFalse(state.restoring)
            assertFalse(state.restorationPending)
            assertFalse(state.foregroundCheckPending)
            assertFalse(state.busy)
            val usage = state.accountUsage.state.value
            assertTrue(usage.authenticated)
            assertFalse(usage.verifying)
            assertFalse(usage.busy)
            assertNull(usage.error)
            assertSame(state.resource, usage.resource)
            assertEquals(PortalJson.resource(available.body), usage.resource)
            assertNotNull(sessions.read())
            assertLocalPreserved(state)
        }
    }

    @Test fun cachedProfileRestoresSavedAccountAndUsageOnlyAfterServerValidationWithoutReimport() = runTest {
        val fixture = Fixture()
        val release = CompletableDeferred<Unit>()
        fixture.reply = { if (it == 1) release.await(); available }
        val state = fixture.controller(backgroundScope)
        try {
            state.restore(); runCurrent()
            fixture.assertLocalPreserved(state)
            assertEquals(1, fixture.calls.size)
            assertTrue(state.restoring)
            assertTrue(state.busy)
            assertFalse(state.authenticated)
            assertFalse(state.accountUsage.state.value.authenticated)
            assertNull(state.accountUsage.state.value.resource)
            release.complete(Unit); runCurrent()
            // One validation GET, then the current accepted overview used by account/usage UI.
            assertEquals(2, fixture.calls.size)
            fixture.assertVerifiedAccount(state)
        } finally { release.complete(Unit); runCurrent() }
    }

    @Test fun freshRepositoryAndControllerRestoreTheSameDurableProfileAndSession() = runTest {
        val fixture = Fixture()
        val first = fixture.controller(backgroundScope)
        first.restore(); runCurrent()
        fixture.assertVerifiedAccount(first)
        assertEquals(2, fixture.calls.size)

        val reopened = fixture.controller(backgroundScope)
        assertFalse(reopened.authenticated)
        assertNull(reopened.accountUsage.state.value.resource)
        reopened.restore(); runCurrent()
        fixture.assertVerifiedAccount(reopened)
        assertEquals(4, fixture.calls.size)
    }

    @Test fun missingOrExpiredSavedSessionKeepsTheCachedProfileWithoutAuthenticatedUi() = runTest {
        for (session in listOf(null, saved(now.minusSeconds(3600)))) {
            val fixture = Fixture(session)
            val state = fixture.controller(backgroundScope)
            state.restore(); runCurrent()
            fixture.assertLocalPreserved(state)
            assertFalse(state.authenticated)
            assertNull(state.authenticatedAccountId)
            assertNull(state.resource)
            assertFalse(state.accountUsage.state.value.authenticated)
            assertNull(state.accountUsage.state.value.resource)
            assertFalse(state.busy)
            assertFalse(state.restoring)
            assertFalse(state.restorationPending)
            assertNull(fixture.sessions.read())
            assertTrue(fixture.calls.isEmpty())
        }
    }

    @Test fun failedStartupValidationPreservesRecoveryAndNeedsOneExplicitRetry() = runTest {
        val fixture = Fixture()
        val priorSession = fixture.sessions.read()
        fixture.reply = { throw PortalFailure(PortalFailure.Reason.NETWORK) }
        val state = fixture.controller(backgroundScope)
        state.restore(); runCurrent()
        fixture.assertLocalPreserved(state)
        assertEquals(1, fixture.calls.size)
        assertFalse(state.authenticated)
        assertFalse(state.busy)
        assertFalse(state.restoring)
        assertTrue(state.restorationPending)
        assertEquals("网络连接失败，请重试", state.error)
        assertSame(priorSession, fixture.sessions.read())
        assertFalse(state.accountUsage.state.value.authenticated)
        assertNull(state.accountUsage.state.value.resource)

        repeat(3) { state.onForeground(); advanceTimeBy(1000); runCurrent() }
        assertEquals("An unauthenticated foreground event must not start a retry loop", 1, fixture.calls.size)
        fixture.reply = { available }
        state.restoreAccount(); state.restoreAccount(); runCurrent()
        assertEquals("One explicit retry owns its validation and overview reads", 3, fixture.calls.size)
        assertNull(state.error)
        fixture.assertVerifiedAccount(state)
    }

    @Test fun logoutDuringStartupRejectsALateSuccessfulValidationAndClearsTheAccountSubscription() = runTest {
        val fixture = Fixture()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.reply = {
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            available
        }
        val state = fixture.controller(backgroundScope)
        try {
            state.restore(); runCurrent()
            assertTrue("Cached startup must actually validate its saved session", started.isCompleted)
            fixture.assertLocalPreserved(state)
            state.logout(); runCurrent()
            assertFalse(state.authenticated)
            assertNull(fixture.sessions.read())
            release.complete(Unit); runCurrent()
            assertEquals(1, fixture.calls.size)
            assertFalse(state.authenticated)
            assertNull(state.authenticatedAccountId)
            assertNull(state.resource)
            assertFalse(state.accountUsage.state.value.authenticated)
            assertNull(state.accountUsage.state.value.resource)
            assertFalse(state.busy)
            assertFalse(state.clearing)
            assertFalse(state.restoring)
            assertNull(fixture.sessions.read())
            // 退出登录 also forgets this device's account subscription.
            assertFalse(state.localSubscription.hasProfile)
            assertNull(fixture.profiles.read())
        } finally { release.complete(Unit); runCurrent() }
    }
}
