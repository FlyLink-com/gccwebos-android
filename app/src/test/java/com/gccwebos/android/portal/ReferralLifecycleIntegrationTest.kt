package com.gccwebos.android.portal

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Two page controllers share one verified account, but no network or persistent referral store. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReferralLifecycleIntegrationTest {
    private val now = Instant.parse("2026-09-30T00:00:00Z")
    private val path = "/r/" + "A".repeat(32)
    private fun profile(link: String? = null) = JSONObject().put("profile", JSONObject()
        .put("eligible", true).put("linkPath", link ?: JSONObject.NULL)
        .put("policy", JSONObject.NULL).put("attributionDays", 21)).toString()
    private val performance = """{"performance":{"registrations":0,"firstRechargeConversions":0,
        "creditedFirstRechargeCoins":0,"pendingRewardCoins":0,"availableRewardCoins":0,
        "reversedRewardCoins":0,"conversionRate":0,"reconciliationRequired":false}}"""

    private inner class Fixture(private val scope: CoroutineScope) {
        val sessions = MemorySessionStore()
        val posts = mutableListOf<String>()
        val copies = mutableListOf<String>()
        var link: String? = null
        var id = "account_lifecycle_fixture_a"
        var token = "native_lifecycle_fixture_a"
        var profileReply: (() -> WireResponse)? = null
        var post: suspend () -> WireResponse = { throw PortalFailure(PortalFailure.Reason.NETWORK) }
        val account = AccountController(PortalRepository(object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?) = when (path) {
                "/api/portal/password/session" -> WireResponse(200, """{"session":{"sessionRef":"$token",
                    "accountId":"$id","issuedAt":"2026-09-30T00:00:00.000Z",
                    "expiresAt":"2026-09-30T00:30:00.000Z"}}""")
                "/api/portal/resources/current/overview" -> WireResponse(200, """{"resource":{"state":"none"}}""")
                else -> throw AssertionError("No other Portal request in this offline fixture")
            }
        }, object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = throw AssertionError("No delivery mutation")
            override fun clear() = Unit
        }, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L }, { now },
            subscription = SubscriptionSource { throw AssertionError("No subscription client") },
            sessions = sessions, profiles = MemoryLocalProfileStore()), scope)
        private val repository = ReferralRepository { request, body ->
            when (request) {
                ReferralJson.PROFILE -> { assertNull(body); profileReply?.invoke() ?: WireResponse(200, profile(link)) }
                ReferralJson.PERFORMANCE -> { assertNull(body); WireResponse(200, performance) }
                ReferralJson.LINK -> { assertEquals("{}", body); posts += body!!; post() }
                else -> throw AssertionError("No other referral route")
            }
        }
        fun login() {
            account.account = "submitted.fixture@example.invalid"; account.password = "fixture"; account.answer = "3"
            account.challenge = HumanChallenge("lifecycle_question", "1 + 2"); account.login()
        }
        fun replaceSavedSession(nextId: String = id, nextToken: String) {
            sessions.save(SavedSession(Secret(nextToken), nextId, now.plusSeconds(1800), 1800000, now, 0))
            account.onForeground()
        }
        fun page() = ReferralController(account, scope, { copies += it }, repository)
    }

    @Test fun recreatedPageAndSuccessfulEmptyReadCannotReplayUnknownGeneration() = runTest {
        val f = Fixture(backgroundScope); f.login(); runCurrent()
        val first = f.page(); first.load(); runCurrent(); first.generateLink(); runCurrent()
        assertEquals(1, f.posts.size); assertNotNull(first.error); first.dispose()
        val second = f.page(); second.load(); runCurrent(); second.generateLink(); runCurrent()
        assertEquals("A successful empty snapshot cannot confirm the lost POST outcome", 1, f.posts.size)
        assertFalse(second.canGenerate); assertNotNull(second.error)
        second.load(); runCurrent(); second.generateLink(); runCurrent(); assertEquals(1, f.posts.size)
        f.link = path; second.load(); runCurrent(); second.copyLink()
        assertEquals(1, f.posts.size); assertEquals(listOf(ReferralJson.shareUrl(path)), f.copies)
    }

    @Test fun cancelledDispatchedPostKeepsUnknownAfterItsLateOutcomeAndPageRecreation() = runTest {
        for (success in listOf(false, true)) {
            val f = Fixture(backgroundScope); f.login(); runCurrent()
            val release = CompletableDeferred<Unit>()
            f.post = {
                withContext(NonCancellable) { release.await() }
                if (success) WireResponse(200, profile(path)) else throw PortalFailure(PortalFailure.Reason.NETWORK)
            }
            val first = f.page(); first.load(); runCurrent(); first.generateLink(); runCurrent()
            assertEquals(1, f.posts.size); first.dispose(); release.complete(Unit); runCurrent()
            // The old local call has ended before B's read; this does not fake mutex overtaking.
            f.post = { throw PortalFailure(PortalFailure.Reason.NETWORK) }
            val second = f.page(); second.load(); runCurrent(); second.generateLink(); runCurrent()
            assertEquals("Disposed dispatched attempt remains unknown in the same session", 1, f.posts.size)
            assertNull(first.profile); assertFalse(second.canGenerate); assertTrue(f.account.authenticated)
        }
    }

    @Test fun sameTokenForegroundKeepsUnknownWhileNewTokenAndAccountHaveSeparateOwnership() = runTest {
        val f = Fixture(backgroundScope); f.login(); runCurrent()
        val first = f.page(); first.load(); runCurrent(); first.generateLink(); runCurrent(); first.dispose()
        val generation = f.account.authSessionGeneration
        f.account.onForeground(); runCurrent(); assertTrue(f.account.authSessionGeneration > generation)
        assertEquals("submitted.fixture@example.invalid", f.account.displayEmail)
        val same = f.page(); same.load(); runCurrent(); same.generateLink(); runCurrent()
        assertEquals("Same verified token preserves unknown through foreground generation change", 1, f.posts.size)
        same.dispose(); f.replaceSavedSession(nextToken = "native_lifecycle_fixture_b"); runCurrent()
        assertNull(f.account.displayEmail); assertEquals(f.id, f.account.authenticatedAccountId)
        val newToken = f.page(); newToken.load(); runCurrent(); assertTrue(newToken.canGenerate)
        newToken.generateLink(); runCurrent(); assertEquals(2, f.posts.size); newToken.dispose()
        f.replaceSavedSession("account_lifecycle_fixture_b", "native_lifecycle_fixture_c"); runCurrent()
        val newAccount = f.page(); newAccount.load(); runCurrent(); assertTrue(newAccount.canGenerate)
        assertEquals("account_lifecycle_fixture_b", f.account.authenticatedAccountId)
        assertNull(f.account.displayEmail)
        newAccount.generateLink(); runCurrent(); assertEquals(3, f.posts.size)
        f.account.logout(); runCurrent(); f.id = "account_lifecycle_fixture_b"; f.token = "native_lifecycle_fixture_c"
        f.login(); runCurrent()
        val afterExplicitLogout = f.page(); afterExplicitLogout.load(); runCurrent()
        assertTrue(afterExplicitLogout.canGenerate)
    }

    @Test fun disposalBeforeDispatchDoesNotMarkTheSessionUnknown() = runTest {
        val f = Fixture(backgroundScope); f.login(); runCurrent()
        val first = f.page(); first.load(); runCurrent(); first.generateLink(); first.dispose(); runCurrent()
        assertEquals(0, f.posts.size)
        val second = f.page(); second.load(); runCurrent(); assertTrue(second.canGenerate)
        second.generateLink(); runCurrent(); assertEquals(1, f.posts.size)
    }

    @Test fun oldSessionLateSuccessCannotClearNewSessionsUnknownAttempt() = runTest {
        val f = Fixture(backgroundScope); f.login(); runCurrent()
        val release = CompletableDeferred<Unit>()
        f.post = { withContext(NonCancellable) { release.await() }; WireResponse(200, profile(path)) }
        val first = f.page(); first.load(); runCurrent(); first.generateLink(); runCurrent()
        f.replaceSavedSession(nextToken = "native_lifecycle_fixture_b"); runCurrent()
        f.post = { throw PortalFailure(PortalFailure.Reason.NETWORK) }
        val second = f.page(); second.load(); runCurrent(); second.generateLink(); runCurrent()
        assertEquals(2, f.posts.size)
        release.complete(Unit); runCurrent(); assertNull(first.profile?.linkPath)
        second.dispose(); val third = f.page(); third.load(); runCurrent(); third.generateLink(); runCurrent()
        assertEquals("Late old session outcome cannot release B's unknown guard", 2, f.posts.size)
        assertFalse(third.canGenerate); assertTrue(f.account.authenticated)
    }

    @Test fun missingMalformedOrEmptyProfilesNeverReleaseAnUncertainAttempt() = runTest {
        for (outcome in listOf("missing", "invalid", "network")) {
            val f = Fixture(backgroundScope); f.login(); runCurrent()
            f.post = { when (outcome) {
                "missing" -> WireResponse(404, "")
                "invalid" -> WireResponse(200, "{}")
                else -> throw PortalFailure(PortalFailure.Reason.NETWORK)
            } }
            val first = f.page(); first.load(); runCurrent(); first.generateLink(); runCurrent(); first.dispose()
            val second = f.page()
            f.profileReply = { WireResponse(404, "") }; second.load(); runCurrent(); assertFalse(second.canGenerate)
            f.profileReply = { WireResponse(200, "{}") }; second.load(); runCurrent(); assertFalse(second.canGenerate)
            f.profileReply = null; second.load(); runCurrent(); second.generateLink(); runCurrent()
            assertEquals(1, f.posts.size); assertNotNull(second.error); assertFalse(second.canGenerate)
        }
    }
}
