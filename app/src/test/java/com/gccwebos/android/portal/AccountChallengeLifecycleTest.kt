package com.gccwebos.android.portal

import kotlinx.coroutines.*
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Anonymous questions and login responses are synthetic; there is no HTTP client or delivery write. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountChallengeLifecycleTest {
    private val now = Instant.parse("2026-09-30T00:00:00Z")
    private val none = WireResponse(200, """{"resource":{"state":"none"}}""")
    private val authenticated = WireResponse(200, """{"session":{"sessionRef":"native_challenge_fixture",
        "accountId":"account_challenge_fixture","issuedAt":"2026-09-30T00:00:00.000Z",
        "expiresAt":"2026-09-30T00:30:00.000Z"}}""")
    private val mfa = WireResponse(202, """{"challenge":{"status":"second_factor_required",
        "availableFactors":["totp"],"challengeRef":"question_fixture_totp",
        "expiresAt":"2026-09-30T00:05:00.000Z"}}""")
    private fun question(number: Int, seconds: Int = 60) = WireResponse(201,
        """{"challenge":{"challengeRef":"question_fixture_$number","type":"math",
        "prompt":"Server fixture prompt $number","expiresInSeconds":$seconds}}""")
    private data class Call(val path: String, val body: String?)
    private inner class Fixture(scope: CoroutineScope, elapsed: () -> Long = { 0L }) {
        val sessions = MemorySessionStore()
        val calls = mutableListOf<Call>()
        var questions = 0
        var questionReply: suspend (Int) -> WireResponse = { question(it) }
        var passwordReply: suspend () -> WireResponse = { throw PortalFailure(PortalFailure.Reason.LOGIN_FAILED) }
        var overviewReply: suspend () -> WireResponse = { none }
        val state = AccountController(PortalRepository(object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                calls += Call(path, body)
                return when (path) {
                    "/api/portal/human-challenges" -> {
                        assertNull(session); assertEquals(setOf("scope"), JSONObject(body!!).keys().asSequence().toSet())
                        assertEquals("portal_login", JSONObject(body).getString("scope")); questionReply(++questions)
                    }
                    "/api/portal/password/session" -> passwordReply()
                    "/api/portal/password/second-factor" -> authenticated
                    "/api/portal/resources/current/overview" -> overviewReply()
                    else -> throw AssertionError("Unexpected offline Portal path")
                }
            }
        }, object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = throw AssertionError("No delivery writes")
            override fun clear() = Unit
        }, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), elapsed, { now },
            subscription = SubscriptionSource { throw AssertionError("No subscription client") },
            sessions = sessions, profiles = MemoryLocalProfileStore()), scope)
        fun fill() { state.account = "fixture@example.invalid"; state.password = "p"; state.answer = "user supplied" }
    }

    @Test fun entryRequestsOnceAndConsumedFailureNeverLoopsOnRecomposition() = runTest {
        val f = Fixture(backgroundScope); val lease = f.state.enterLoginForm()
        f.state.ensureLoginChallenge(lease); f.state.ensureLoginChallenge(lease); runCurrent()
        assertEquals(1, f.questions); assertEquals("Server fixture prompt 1", f.state.challenge?.prompt)
        assertEquals("Server fixture prompt 1", loginQuestionText(f.state))
        assertEquals("", f.state.answer); assertFalse(f.state.busy); assertFalse(f.state.challengeLoading)
        f.fill(); f.state.login(); runCurrent()
        assertNull(f.state.challenge); assertNotNull(f.state.error)
        repeat(3) { f.state.ensureLoginChallenge(lease); runCurrent() }
        assertEquals(1, f.questions); assertEquals(1, f.calls.count { it.path == "/api/portal/password/session" })
        f.state.challenge(); runCurrent(); assertEquals(2, f.questions)
        assertEquals("question_fixture_2", f.state.challenge?.reference)
    }

    @Test fun savedSessionRestorationFinishesBeforeQuestionAndAuthenticatedRestoreSkipsIt() = runTest {
        for (restored in listOf(false, true)) {
            val f = Fixture(backgroundScope); val release = CompletableDeferred<Unit>()
            f.sessions.save(SavedSession(Secret("native_saved_question"), "account_saved_question", now.plusSeconds(1800), 1800000, now, 0))
            f.overviewReply = { release.await(); if (restored) none else throw PortalFailure(PortalFailure.Reason.NETWORK) }
            f.state.restore(); val lease = f.state.enterLoginForm(); f.state.ensureLoginChallenge(lease); runCurrent()
            assertTrue(f.state.busy); assertTrue(f.state.restoring); assertEquals(0, f.questions)
            release.complete(Unit); runCurrent()
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); runCurrent()
            assertFalse(f.state.busy); assertFalse(f.state.restoring); assertEquals(restored, f.state.authenticated)
            assertEquals(if (restored) 0 else 1, f.questions)
            assertNull(f.state.displayEmail)
            f.state.leaveLoginForm(lease)
        }
    }

    @Test fun pendingLoginKeepsAStaticQuestionAndStillBlocksDuplicateSubmission() = runTest {
        val f = Fixture(backgroundScope)
        val lease = f.state.enterLoginForm(); f.state.ensureLoginChallenge(lease); runCurrent(); f.fill()
        val release = CompletableDeferred<Unit>()
        f.passwordReply = { release.await(); mfa }
        f.state.login(); runCurrent()
        assertTrue(f.state.busy); assertTrue(f.state.authenticationSubmitting)
        assertEquals("验证题", loginQuestionText(f.state))
        f.state.login(); runCurrent()
        assertEquals(1, f.calls.count { it.path == "/api/portal/password/session" })
        release.complete(Unit); runCurrent()
        assertFalse(f.state.busy); assertFalse(f.state.authenticationSubmitting)
        assertNotNull(f.state.factorReference)
        assertEquals(1, f.questions)
    }

    @Test fun refreshInvalidatesOldAnswerAndSingleFlightFailureNeedsExplicitRetry() = runTest {
        val f = Fixture(backgroundScope); val lease = f.state.enterLoginForm()
        f.state.ensureLoginChallenge(lease); runCurrent(); f.fill()
        f.questionReply = { if (it == 2) throw PortalFailure(PortalFailure.Reason.NETWORK) else question(it) }
        f.state.challenge(); f.state.challenge()
        assertNull(f.state.challenge); assertEquals("", f.state.answer); assertTrue(f.state.challengeLoading)
        assertEquals("验证题", loginQuestionText(f.state))
        assertFalse(f.state.busy); assertEquals("p", f.state.password); assertEquals("fixture@example.invalid", f.state.account)
        runCurrent(); assertEquals(2, f.questions); assertNotNull(f.state.challengeError)
        assertEquals("无网络", loginQuestionText(f.state))
        f.state.ensureLoginChallenge(lease); runCurrent(); assertEquals(2, f.questions)
        f.state.challenge(); runCurrent(); assertEquals(3, f.questions); assertNull(f.state.challengeError)
        assertEquals("Server fixture prompt 3", loginQuestionText(f.state))
    }

    @Test fun monotonicExpiryInvalidatesAnswerWithoutAutomaticFetchOrPasswordSubmission() = runTest {
        val f = Fixture(backgroundScope) { testScheduler.currentTime }; f.questionReply = { question(it, 1) }
        val lease = f.state.enterLoginForm(); f.state.ensureLoginChallenge(lease); runCurrent(); f.fill()
        advanceTimeBy(1001); runCurrent()
        assertTrue(f.state.challengeExpired); assertNull(f.state.challenge); assertEquals("", f.state.answer)
        assertEquals("换一道题", loginQuestionText(f.state))
        assertEquals("p", f.state.password); f.state.login(); f.state.ensureLoginChallenge(lease); runCurrent()
        assertEquals(1, f.questions); assertTrue(f.calls.none { it.path == "/api/portal/password/session" })
        f.state.challenge(); runCurrent(); assertEquals(2, f.questions); assertFalse(f.state.challengeExpired)

        // Simulate elapsedRealtime advancing in deep sleep before the delay/Handler fires.
        for (foreground in listOf(false, true)) {
            var elapsed = 0L
            val asleep = Fixture(backgroundScope) { elapsed }; asleep.questionReply = { question(it, 1) }
            val asleepLease = asleep.state.enterLoginForm(); asleep.state.ensureLoginChallenge(asleepLease)
            runCurrent(); asleep.fill(); elapsed = 1001L
            assertNotNull(asleep.state.challenge)
            if (foreground) asleep.state.onForeground() else asleep.state.login()
            assertTrue(asleep.state.challengeExpired); assertNull(asleep.state.challenge)
            assertEquals("", asleep.state.answer); assertEquals("p", asleep.state.password)
            asleep.state.ensureLoginChallenge(asleepLease); runCurrent()
            assertEquals(1, asleep.questions)
            assertTrue(asleep.calls.none { it.path == "/api/portal/password/session" })
        }
    }

    @Test fun challengeFailureKeepsTypedReasonAndRefreshClearsIt() = runTest {
        for (reason in listOf(PortalFailure.Reason.NETWORK, PortalFailure.Reason.PROTOCOL, PortalFailure.Reason.RATE_LIMITED)) {
            val f = Fixture(backgroundScope)
            f.questionReply = { throw PortalFailure(reason) }
            val lease = f.state.enterLoginForm(); f.state.ensureLoginChallenge(lease); runCurrent()
            assertEquals(reason, f.state.challengeFailureReason)
            assertEquals(reason == PortalFailure.Reason.NETWORK, f.state.challengeError == "无网络")
            assertEquals(if (reason == PortalFailure.Reason.NETWORK) "无网络" else "换一道题", loginQuestionText(f.state))
            assertNull(f.state.challenge); assertFalse(f.state.challengeExpired)
            f.questionReply = { question(it) }
            f.state.refreshLoginChallenge(lease)
            assertNull(f.state.challengeFailureReason); assertNull(f.state.challengeError)
            runCurrent(); assertNotNull(f.state.challenge)
            f.state.leaveLoginForm(lease); assertNull(f.state.challengeFailureReason)
        }
    }

    @Test fun disposedQuestionCannotOverwriteNewEntryWithLateSuccessOrFailure() = runTest {
        for (outcome in listOf("success", "network", "session", "invalid")) {
            val f = Fixture(backgroundScope); val release = CompletableDeferred<Unit>()
            f.questionReply = { number ->
                if (number != 1) question(number) else {
                    withContext(NonCancellable) { release.await() }
                    when (outcome) {
                        "success" -> question(number)
                        "network" -> throw PortalFailure(PortalFailure.Reason.NETWORK)
                        "session" -> WireResponse(401, "")
                        else -> WireResponse(201, "{}")
                    }
                }
            }
            val first = f.state.enterLoginForm(); f.state.ensureLoginChallenge(first); runCurrent()
            f.state.leaveLoginForm(first); val second = f.state.enterLoginForm(); f.state.ensureLoginChallenge(second); runCurrent()
            assertEquals("question_fixture_2", f.state.challenge?.reference)
            release.complete(Unit); runCurrent()
            assertEquals(2, f.questions); assertEquals("question_fixture_2", f.state.challenge?.reference)
            assertNull(f.state.challengeError); assertFalse(f.state.challengeLoading); assertFalse(f.state.authenticated)
        }
    }

    @Test fun queuedLeaveAndOldLeaseCannotFetchOrCancelTheCurrentForm() = runTest {
        val f = Fixture(backgroundScope); val first = f.state.enterLoginForm()
        f.state.ensureLoginChallenge(first); f.state.leaveLoginForm(first); runCurrent(); assertEquals(0, f.questions)
        val second = f.state.enterLoginForm(); f.state.ensureLoginChallenge(second); runCurrent()
        f.state.ensureLoginChallenge(first); f.state.refreshLoginChallenge(first); f.state.leaveLoginForm(first); runCurrent()
        assertEquals(1, f.questions); assertEquals("question_fixture_1", f.state.challenge?.reference)
    }

    @Test fun leavingAfterPasswordSubmitPreservesMfaAndSuccessfulAuthenticationTasks() = runTest {
        for (requiresMfa in listOf(false, true)) {
            val f = Fixture(backgroundScope); val release = CompletableDeferred<Unit>()
            f.passwordReply = { release.await(); if (requiresMfa) mfa else authenticated }
            val lease = f.state.enterLoginForm(); f.state.ensureLoginChallenge(lease); runCurrent(); f.fill()
            f.state.login(); runCurrent(); assertTrue(f.state.busy)
            f.state.leaveLoginForm(lease); assertTrue(f.state.busy); release.complete(Unit); runCurrent()
            if (requiresMfa) {
                assertEquals("question_fixture_totp", f.state.factorReference); assertNull(f.state.displayEmail)
                f.state.code = "123456"; f.state.totp(); runCurrent()
            }
            assertTrue(f.state.authenticated); assertEquals("account_challenge_fixture", f.state.authenticatedAccountId)
            assertEquals("fixture@example.invalid", f.state.displayEmail); assertEquals(1, f.questions)
            assertNotNull(f.sessions.read()); f.state.ensureLoginChallenge(lease); runCurrent(); assertEquals(1, f.questions)
        }
    }

    @Test fun cancelledLegacyQuestionCannotExpireTheReplacementSuccessfulSession() = runTest {
        val f = Fixture(backgroundScope); val release = CompletableDeferred<Unit>()
        f.questionReply = { withContext(NonCancellable) { release.await() }; WireResponse(401, "") }
        f.state.challenge(); runCurrent(); assertTrue(f.state.challengeLoading); assertFalse(f.state.busy)
        f.state.cancelAuthentication(); runCurrent(); f.fill()
        f.state.challenge = HumanChallenge("replacement_question", "synthetic manually seeded prompt")
        f.passwordReply = { authenticated }; f.state.login(); runCurrent(); assertTrue(f.state.authenticated)
        release.complete(Unit); runCurrent()
        assertTrue(f.state.authenticated); assertEquals("fixture@example.invalid", f.state.displayEmail)
        assertNull(f.state.challengeError); assertFalse(f.state.challengeLoading); assertFalse(f.state.busy)
    }
}
