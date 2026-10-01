package com.gccwebos.android.portal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Synthetic referral gateways and in-memory copy callbacks; no generated or shared production links. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReferralControllerTest {
    private val now = Instant.parse("2026-09-30T00:00:00Z")
    private val linkPath = "/r/" + "A".repeat(32)
    private val policy = """{"policyRef":"policy_fixture","version":3,"effectiveAt":"2026-09-30T00:00:00.000Z",
        "rateBps":1375,"rounding":"half_up","attributionDays":21,"confirmationDays":4}"""
    private val statistics = """{"performance":{"registrations":8,"firstRechargeConversions":2,
        "creditedFirstRechargeCoins":70,"pendingRewardCoins":9,"availableRewardCoins":3,
        "reversedRewardCoins":2,"conversionRate":0.25,"reconciliationRequired":true}}"""
    private fun profile(path: String? = null, eligible: Boolean = true, rewardPolicy: String? = null,
        attributionDays: Long? = 21) =
        JSONObject().put("profile", JSONObject().put("eligible", eligible)
            .put("linkPath", path ?: JSONObject.NULL).put("policy", rewardPolicy?.let(::JSONObject) ?: JSONObject.NULL)
            .put("attributionDays", attributionDays ?: JSONObject.NULL)).toString()

    private fun account(scope: CoroutineScope) = AccountController(PortalRepository(
        object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse =
                throw AssertionError("No real Portal transport")
        }, object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = throw AssertionError("No delivery writes")
            override fun clear() = Unit
        }, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L }, { now },
        subscription = SubscriptionSource { throw AssertionError("No subscription requests") },
        sessions = MemorySessionStore(), profiles = MemoryLocalProfileStore()), scope).apply { authenticated = true }

    private data class Call(val path: String, val body: String?)
    private inner class Fixture(scope: CoroutineScope,
        reply: suspend (String, String?) -> WireResponse = { path, _ ->
            when (path) {
                ReferralJson.PROFILE -> WireResponse(200, profile())
                ReferralJson.PERFORMANCE -> WireResponse(200, statistics)
                ReferralJson.LINK -> WireResponse(200, profile(linkPath))
                else -> throw AssertionError("Unexpected synthetic referral path")
            }
        }) {
        val account = this@ReferralControllerTest.account(scope)
        val calls = mutableListOf<Call>()
        val copied = mutableListOf<String>()
        val repository = ReferralRepository { path, body ->
            calls += Call(path, body)
            assertTrue(ReferralJson.validRequest(path, body))
            reply(path, body)
        }
        val controller = ReferralController(account, scope, { copied += it }, repository)
    }

    @Test fun profileAndPerformanceFailuresRemainIndependentWithoutInventedZeros() = runTest {
        for (failedPath in listOf(ReferralJson.PROFILE, ReferralJson.PERFORMANCE)) {
            var failing = false
            val fixture = Fixture(this) { path, _ ->
                if (failing && path == failedPath) WireResponse(503, "")
                else WireResponse(200, if (path == ReferralJson.PROFILE) profile(rewardPolicy = policy) else statistics)
            }
            fixture.controller.load(); runCurrent()
            val beforeProfile = fixture.controller.profile
            val beforePerformance = fixture.controller.performance
            failing = true; fixture.controller.load(); runCurrent()
            assertEquals(beforeProfile, fixture.controller.profile)
            assertEquals(beforePerformance, fixture.controller.performance)
            assertEquals(9L, fixture.controller.performance?.pendingRewardCoins)
            assertEquals(1375L, fixture.controller.profile?.policy?.rateBps)
            assertEquals(failedPath == ReferralJson.PROFILE, fixture.controller.profileError != null)
            assertEquals(failedPath == ReferralJson.PERFORMANCE, fixture.controller.performanceError != null)
            assertEquals(2, fixture.calls.count { it.path == ReferralJson.PROFILE })
            assertEquals(2, fixture.calls.count { it.path == ReferralJson.PERFORMANCE })
            assertTrue(fixture.calls.all { it.body == null })
        }
    }

    @Test fun disabledEmpty404RemainsUnavailableAndCannotGenerateOrCopy() = runTest {
        val fixture = Fixture(this) { _, _ -> WireResponse(404, "") }
        fixture.controller.load(); runCurrent()
        fixture.controller.generateLink(); fixture.controller.copyLink(); runCurrent()
        assertNull(fixture.controller.profile); assertNull(fixture.controller.performance)
        assertTrue(fixture.controller.profileError!!.contains("未开放"))
        assertTrue(fixture.controller.performanceError!!.contains("未开放"))
        assertTrue(fixture.copied.isEmpty()); assertEquals(2, fixture.calls.size)
        assertFalse(fixture.controller.canGenerate); assertFalse(fixture.controller.canCopy)
        assertTrue(fixture.calls.all { it.body == null }); assertTrue(fixture.account.authenticated)
    }

    @Test fun knownLinkAndNullRewardPolicyStayReadOnlyUntilExplicitCopy() = runTest {
        val fixture = Fixture(this) { path, _ ->
            assertNotEquals(ReferralJson.LINK, path)
            WireResponse(200, if (path == ReferralJson.PROFILE) profile(linkPath) else statistics)
        }
        fixture.controller.load(); runCurrent()
        assertNull(fixture.controller.profile?.policy)
        assertEquals(21L, fixture.controller.profile?.attributionDays)
        assertFalse(fixture.controller.canGenerate); assertTrue(fixture.controller.canCopy)
        assertFalse(fixture.controller.copied); assertTrue(fixture.copied.isEmpty())
        fixture.controller.generateLink(); runCurrent()
        assertEquals(2, fixture.calls.size); assertTrue(fixture.copied.isEmpty())
        fixture.controller.copyLink()
        assertEquals(listOf("https://portal.gccwebos.com$linkPath"), fixture.copied)
        assertTrue(fixture.controller.copied); assertEquals(2, fixture.calls.size)
    }

    @Test fun repeatedExplicitGenerationSendsOnePostWithExactEmptyObject() = runTest {
        val release = CompletableDeferred<Unit>()
        val fixture = Fixture(this) { path, body ->
            when (path) {
                ReferralJson.PROFILE -> WireResponse(200, profile())
                ReferralJson.PERFORMANCE -> WireResponse(200, statistics)
                ReferralJson.LINK -> {
                    assertEquals("{}", body); release.await()
                    WireResponse(200, profile(linkPath))
                }
                else -> throw AssertionError("Unexpected synthetic referral path")
            }
        }
        fixture.controller.load(); runCurrent()
        assertTrue(fixture.controller.canGenerate); assertFalse(fixture.controller.canCopy)
        fixture.controller.generateLink(); fixture.controller.generateLink(); runCurrent()
        assertTrue(fixture.controller.busy)
        assertFalse(fixture.controller.canGenerate); assertFalse(fixture.controller.canCopy)
        assertEquals(1, fixture.calls.count { it.path == ReferralJson.LINK })
        release.complete(Unit); runCurrent()
        fixture.controller.generateLink(); runCurrent()
        assertFalse(fixture.controller.busy); assertEquals(linkPath, fixture.controller.profile?.linkPath)
        assertFalse(fixture.controller.canGenerate); assertTrue(fixture.controller.canCopy)
        assertEquals(1, fixture.calls.count { it.path == ReferralJson.LINK })
        assertFalse(fixture.controller.copied); assertTrue(fixture.copied.isEmpty())
    }

    @Test fun unknownGenerationRequiresProfileReadBeforeAnotherPost() = runTest {
        var recoverKnown = false
        val fixture = Fixture(this) { path, _ ->
            when (path) {
                ReferralJson.PROFILE -> WireResponse(200, profile(if (recoverKnown) linkPath else null))
                ReferralJson.PERFORMANCE -> WireResponse(200, statistics)
                ReferralJson.LINK -> throw PortalFailure(PortalFailure.Reason.NETWORK)
                else -> throw AssertionError("Unexpected synthetic referral path")
            }
        }
        fixture.controller.load(); runCurrent()
        fixture.controller.generateLink(); runCurrent()
        assertNull(fixture.controller.profile?.linkPath); assertNotNull(fixture.controller.error)
        assertFalse(fixture.controller.canGenerate); assertFalse(fixture.controller.canCopy)
        fixture.controller.generateLink(); runCurrent()
        assertEquals(1, fixture.calls.count { it.path == ReferralJson.LINK })
        recoverKnown = true; fixture.controller.load(); runCurrent()
        assertEquals(linkPath, fixture.controller.profile?.linkPath); assertNull(fixture.controller.error)
        fixture.controller.generateLink(); fixture.controller.copyLink(); runCurrent()
        assertEquals(1, fixture.calls.count { it.path == ReferralJson.LINK }); assertEquals(1, fixture.copied.size)
    }

    @Test fun copyingRequiresCurrentEligibilityValidPathAndReadySession() = runTest {
        for (gate in listOf("unread", "missing", "ineligible", "unsafe", "nullwindow", "auth", "restoring", "foreground")) {
            val fixture = Fixture(this) { path, _ ->
                WireResponse(200, if (path == ReferralJson.PERFORMANCE) statistics else when (gate) {
                    "missing" -> profile()
                    "ineligible" -> profile(eligible = false)
                    "unsafe" -> profile("https://untrusted.invalid/r/" + "A".repeat(32))
                    "nullwindow" -> profile(linkPath, attributionDays = null)
                    else -> profile(linkPath)
                })
            }
            if (gate != "unread") { fixture.controller.load(); runCurrent() }
            when (gate) {
                "auth" -> fixture.account.authenticated = false
                "restoring" -> fixture.account.restoring = true
                "foreground" -> fixture.account.foregroundCheckPending = true
            }
            fixture.controller.copyLink()
            assertFalse(fixture.controller.copied); assertTrue(fixture.copied.isEmpty())
            assertFalse(fixture.controller.canCopy)
            if (gate == "nullwindow") {
                assertFalse(fixture.controller.canGenerate)
                fixture.controller.generateLink(); runCurrent()
                assertNotNull(fixture.controller.error)
            }
            assertTrue(fixture.calls.all { it.body == null })
        }
        val noWindow = Fixture(this) { path, _ ->
            WireResponse(200, if (path == ReferralJson.PROFILE) profile(attributionDays = null) else statistics)
        }
        noWindow.controller.load(); runCurrent()
        assertFalse(noWindow.controller.canGenerate); assertFalse(noWindow.controller.canCopy)
        noWindow.controller.generateLink(); runCurrent()
        assertTrue(noWindow.calls.all { it.body == null }); assertNull(noWindow.controller.profile?.linkPath)
        val release = CompletableDeferred<Unit>()
        val busy = Fixture(this) { path, _ ->
            if (path == ReferralJson.PERFORMANCE) release.await()
            WireResponse(200, if (path == ReferralJson.PROFILE) profile(linkPath) else statistics)
        }
        busy.controller.load(); runCurrent(); busy.controller.copyLink()
        assertTrue(busy.controller.busy); assertFalse(busy.controller.copied); assertTrue(busy.copied.isEmpty())
        release.complete(Unit); runCurrent()
        val job = Job(coroutineContext[Job])
        val cancelled = Fixture(CoroutineScope(coroutineContext + job)) { path, _ ->
            WireResponse(200, if (path == ReferralJson.PROFILE) profile(linkPath) else statistics)
        }
        cancelled.controller.load(); runCurrent(); assertNotNull(cancelled.controller.profile?.linkPath)
        job.cancel(); cancelled.controller.copyLink()
        assertFalse(cancelled.controller.copied); assertTrue(cancelled.copied.isEmpty())
    }

    @Test fun cancelledGenerationCannotPublishUncooperativeSuccessOrAnyLateFailure() = runTest {
        for (cancellation in listOf("dispose", "scope")) for (outcome in listOf("success", "network", "session", "referral", "unknown")) {
            val release = CompletableDeferred<Unit>()
            val job = Job(coroutineContext[Job]); val scope = CoroutineScope(coroutineContext + job)
            val fixture = Fixture(scope) { path, _ ->
                when (path) {
                    ReferralJson.PROFILE -> WireResponse(200, profile())
                    ReferralJson.PERFORMANCE -> WireResponse(200, statistics)
                    ReferralJson.LINK -> {
                        withContext(NonCancellable) { release.await() }
                        when (outcome) {
                            "success" -> WireResponse(200, profile(linkPath))
                            "network" -> throw PortalFailure(PortalFailure.Reason.NETWORK)
                            "session" -> WireResponse(401, "")
                            "referral" -> WireResponse(503, "")
                            else -> throw IllegalStateException("Synthetic late failure")
                        }
                    }
                    else -> throw AssertionError("Unexpected synthetic referral path")
                }
            }
            fixture.controller.load(); runCurrent(); fixture.controller.generateLink(); runCurrent()
            if (cancellation == "dispose") fixture.controller.dispose() else job.cancel()
            release.complete(Unit); runCurrent()
            fixture.controller.load(); fixture.controller.generateLink(); fixture.controller.copyLink(); runCurrent()
            assertTrue(fixture.account.authenticated); assertFalse(fixture.controller.busy)
            assertNull(fixture.controller.profile?.linkPath); assertNull(fixture.controller.error)
            assertFalse(fixture.controller.copied); assertTrue(fixture.copied.isEmpty())
            assertEquals(1, fixture.calls.count { it.path == ReferralJson.LINK })
            job.cancel()
        }
    }

    @Test fun queuedGenerationAndOldAccountLateResponsesCannotPublishOrExpireNewSession() = runTest {
        val queued = Fixture(this)
        queued.controller.load(); runCurrent(); queued.controller.generateLink()
        queued.account.authSessionGeneration++; runCurrent()
        assertEquals(0, queued.calls.count { it.path == ReferralJson.LINK })
        queued.controller.copyLink(); assertTrue(queued.copied.isEmpty())
        val known = Fixture(this) { path, _ ->
            WireResponse(200, if (path == ReferralJson.PROFILE) profile(linkPath) else statistics)
        }
        known.controller.load(); runCurrent(); assertNotNull(known.controller.profile?.linkPath)
        known.account.authSessionGeneration++; known.controller.copyLink()
        assertFalse(known.controller.copied); assertTrue(known.copied.isEmpty())

        for (delayedPath in listOf(ReferralJson.PROFILE, ReferralJson.PERFORMANCE, ReferralJson.LINK))
            for (outcome in listOf("success", "network", "session", "referral", "unknown")) {
                val release = CompletableDeferred<Unit>()
                val fixture = Fixture(this) { path, _ ->
                    if (path == delayedPath) {
                        withContext(NonCancellable) { release.await() }
                        when (outcome) {
                            "network" -> throw PortalFailure(PortalFailure.Reason.NETWORK)
                            "session" -> return@Fixture WireResponse(401, "")
                            "referral" -> return@Fixture WireResponse(503, "")
                            "unknown" -> throw IllegalStateException("Synthetic late failure")
                        }
                    }
                    WireResponse(200, when (path) {
                        ReferralJson.PROFILE -> profile()
                        ReferralJson.LINK -> profile(linkPath)
                        else -> statistics
                    })
                }
                fixture.controller.load(); runCurrent()
                if (delayedPath == ReferralJson.LINK) { fixture.controller.generateLink(); runCurrent() }
                val beforeProfile = fixture.controller.profile
                val beforePerformance = fixture.controller.performance
                fixture.account.authSessionGeneration++
                release.complete(Unit); runCurrent()
                val count = fixture.calls.size
                fixture.controller.load(); fixture.controller.generateLink(); fixture.controller.copyLink(); runCurrent()
                assertTrue(fixture.account.authenticated); assertFalse(fixture.controller.busy)
                assertEquals(beforeProfile, fixture.controller.profile)
                assertEquals(beforePerformance, fixture.controller.performance)
                assertNull(fixture.controller.profile?.linkPath)
                assertNull(fixture.controller.profileError); assertNull(fixture.controller.performanceError)
                assertNull(fixture.controller.error); assertTrue(fixture.copied.isEmpty())
                assertEquals(count, fixture.calls.size)
                assertEquals(if (delayedPath == ReferralJson.LINK) 1 else 0, fixture.calls.count { it.body != null })
            }
    }
}
