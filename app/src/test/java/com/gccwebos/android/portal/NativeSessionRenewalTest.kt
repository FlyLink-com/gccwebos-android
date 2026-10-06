package com.gccwebos.android.portal

import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NativeSessionRenewalTest {
    private val start = Instant.parse("2026-10-06T00:00:00Z")
    private val grantA = "pnr1_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 1 })
    private val grantB = "pnr1_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 2 })
    private fun iso(value: Instant) = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC).format(value)
    private class Call(val path: String, val body: String?, val token: String?)
    private inner class Fixture {
        var now = start
        var elapsed = 1000L
        val sessions = MemorySessionStore()
        val calls = mutableListOf<Call>()
        var exchangeStatus = 200
        var refreshStatus = 200
        var refreshAccount = "native_fixture_account"
        var beforeRefresh: suspend () -> Unit = {}
        var beforeSecurity: suspend () -> Unit = {}
        var refreshTransform: (JSONObject) -> Unit = {}
        var overviewStatus = 200
        var requireTotp = false
        var deliveryRecord: DeliveryRecord? = null
        fun sessionDto(token: String, issued: Instant = now, account: String = "native_fixture_account") = JSONObject()
            .put("sessionRef", token).put("accountId", account).put("authMethod", if (requireTotp) "totp" else "password").put("status", "active")
            .put("safety", JSONObject().put("totpCodeReturned", false).put("encryptedTotpSecretReturned", false)
                .put("rawEntryTokenReturned", false).put("runtimeDbMutated", false)).put("issuedAt", iso(issued))
            .put("expiresAt", iso(issued.plusSeconds(1800)))
        fun envelope(token: String, grant: String, issued: Instant = now, account: String = "native_fixture_account") = JSONObject()
            .put("session", sessionDto(token, issued, account)).put("refreshGrant", JSONObject()
                .put("refreshToken", grant).put("issuedAt", iso(start)).put("expiresAt", iso(start.plusSeconds(604800)))).toString()
        val wire = object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                calls += Call(path, body, session?.value)
                return when (path) {
                    "/api/portal/password/session" -> if (requireTotp) WireResponse(202,
                        JSONObject().put("challenge", JSONObject().put("status", "second_factor_required")
                            .put("availableFactors", org.json.JSONArray().put("totp")).put("challengeRef", "fixture_totp")
                            .put("expiresAt", iso(now.plusSeconds(300)))).toString())
                        else WireResponse(200, JSONObject().put("session", sessionDto("access_a")).toString())
                    "/api/portal/password/second-factor" -> WireResponse(200, JSONObject().put("session", sessionDto("access_a")).toString())
                    "/api/portal/native-session" -> {
                        assertEquals("{}", body); assertEquals("access_a", session?.value)
                        WireResponse(exchangeStatus, if (exchangeStatus == 200) envelope("access_a", grantA) else "{}")
                    }
                    "/api/portal/native-session/refresh" -> {
                        assertNull(session); assertEquals(setOf("refreshToken"), JSONObject(body!!).keys().asSequence().toSet())
                        assertEquals(grantA, JSONObject(body).getString("refreshToken")); beforeRefresh()
                        WireResponse(refreshStatus, when (refreshStatus) {
                            200 -> JSONObject(envelope("access_b", grantB, account = refreshAccount)).also(refreshTransform).toString()
                            401 -> """{"error":"invalid_refresh_grant"}"""
                            else -> "{}"
                        })
                    }
                    "/api/portal/native-session/logout" -> { assertNull(session); WireResponse(200, """{"loggedOut":true}""") }
                    "/api/portal/security/current" -> { beforeSecurity(); WireResponse(401, "{}") }
                    "/api/portal/resources/current/overview" -> WireResponse(overviewStatus, """{"resource":{"state":"none"}}""")
                    else -> throw AssertionError("Unexpected synthetic request")
                }
            }
        }
        fun repository(sessionStore: SessionStore = sessions) = PortalRepository(wire, object : DeliveryStore {
            override fun read(): DeliveryRecord? = deliveryRecord
            override fun save(record: DeliveryRecord) = throw AssertionError("Renewal must not claim a delivery")
            override fun clear() { deliveryRecord = null }
        }, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { elapsed }, { now },
            sessions = sessionStore, nativeSessionRenewal = true)
        suspend fun login(repo: PortalRepository) = repo.login("fixture", "synthetic-only", HumanChallenge("question", "2+2", Long.MAX_VALUE), "4")
        fun expireAccess() { now = start.plusSeconds(1860); elapsed += 1860000 }
    }

    @Test fun freshLoginExchangesOnceAndRestartRenewsExpiredAccessWithoutPassword() = runTest {
        val f = Fixture(); f.login(f.repository())
        assertEquals(grantA, f.sessions.read()!!.refreshGrant!!.token.value)
        f.expireAccess()
        val restored = f.repository().restoreSession()!!
        assertEquals("access_b", restored.token.value)
        assertEquals(grantB, f.sessions.read()!!.refreshGrant!!.token.value)
        assertEquals(start.plusSeconds(604800), f.sessions.read()!!.refreshGrant!!.expiresAt)
        assertEquals(1, f.calls.count { it.path.endsWith("password/session") })
        assertEquals(1, f.calls.count { it.path.endsWith("native-session/refresh") })
    }

    @Test fun unsupportedExchangeRetainsTheOriginalShortSessionWithoutGrant() = runTest {
        for (status in listOf(404, 405, 400, 401, 429, 503)) {
            val f = Fixture(); f.exchangeStatus = status
            val result = f.login(f.repository()) as LoginResult.Authenticated
            assertEquals("access_a", result.session.token.value)
            assertNull(f.sessions.read()!!.refreshGrant)
            assertEquals(2, f.calls.size)
        }
    }

    @Test fun concurrentRestorationSharesOneRotation() = runTest {
        val f = Fixture(); val repo = f.repository(); f.login(repo); f.expireAccess()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.beforeRefresh = { entered.complete(Unit); release.await() }
        val first = async { repo.restoreSession() }; entered.await()
        val second = async { repo.restoreSession() }; runCurrent(); release.complete(Unit)
        assertEquals("access_b", first.await()!!.token.value)
        assertEquals("access_b", second.await()!!.token.value)
        assertEquals(1, f.calls.count { it.path.endsWith("native-session/refresh") })
    }

    @Test fun temporaryFailureRetainsGrantButDoesNotExposeAnAuthenticatedSession() = runTest {
        val f = Fixture(); val repo = f.repository(); f.login(repo); f.expireAccess(); f.refreshStatus = 503
        try { repo.restoreSession(); fail() } catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.UNAVAILABLE, failure.reason) }
        assertEquals(grantA, f.sessions.read()!!.refreshGrant!!.token.value)
        try { repo.commerceSessionBinding(); fail() } catch (_: PortalFailure) { }
        assertEquals(grantA, f.sessions.read()!!.refreshGrant!!.token.value)
        f.refreshStatus = 200; assertEquals("access_b", repo.restoreSession()!!.token.value)
    }

    @Test fun invalidGrantRequiresLoginAndIsNotReplayed() = runTest {
        val f = Fixture(); val repo = f.repository(); f.login(repo); f.expireAccess(); f.refreshStatus = 401
        try { repo.restoreSession(); fail() } catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.SESSION_EXPIRED, failure.reason) }
        assertNull(f.sessions.read()); assertNull(repo.restoreSession())
        assertEquals(1, f.calls.count { it.path.endsWith("native-session/refresh") })
    }

    @Test fun logoutAdmitsLocalDeletionBeforeAnInFlightRotationCanReturn() = runTest {
        val f = Fixture(); val repo = f.repository(); f.login(repo); f.expireAccess()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.beforeRefresh = { entered.complete(Unit); release.await() }
        val restore = launch { try { repo.restoreSession() } catch (_: kotlinx.coroutines.CancellationException) { } }
        entered.await(); val logout = launch { repo.signOutAccount() }; runCurrent()
        assertNull(f.sessions.read()); release.complete(Unit); restore.join(); logout.join()
        assertNull(f.sessions.read()); assertNull(repo.restoreSession())
    }

    @Test fun rotationCannotChangeAccountIdentity() = runTest {
        val f = Fixture(); val repo = f.repository(); f.login(repo); f.expireAccess(); f.refreshAccount = "other_fixture_account"
        try { repo.restoreSession(); fail() } catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.PROTOCOL, failure.reason) }
        assertEquals("native_fixture_account", f.sessions.read()!!.accountId)
        try { repo.commerceSessionBinding(); fail() } catch (_: PortalFailure) { }
    }

    @Test fun controllerRenewsBeforeExpiryWithoutReplayingDeliveryOrPassword() = runTest {
        val f = Fixture(); val state = AccountController(f.repository(), backgroundScope)
        state.account = "fixture"; state.password = "synthetic-only"; state.answer = "4"
        state.challenge = HumanChallenge("question", "2+2", Long.MAX_VALUE)
        state.login(); runCurrent(); assertTrue(state.authenticated)
        val identity = state.commerceSessionIdentity
        f.now = start.plusSeconds(1740); f.elapsed += 1740000
        testScheduler.advanceTimeBy(1740000); runCurrent()
        assertEquals(1, f.calls.count { it.path.endsWith("native-session/refresh") })
        assertTrue(state.authenticated); assertNotSame(identity, state.commerceSessionIdentity)
        assertEquals(1, f.calls.count { it.path.endsWith("password/session") })
        assertFalse(f.calls.any { it.path.contains("/setup") })
    }

    @Test fun controllerForegroundCanRetryTemporaryRenewalFailureWithoutNewLogin() = runTest {
        val f = Fixture(); f.login(f.repository()); f.expireAccess(); f.refreshStatus = 503
        val state = AccountController(f.repository(), backgroundScope)
        state.restore(); runCurrent(); assertFalse(state.authenticated)
        assertNotNull(f.sessions.read()!!.refreshGrant)
        f.refreshStatus = 200; state.onForeground(); runCurrent()
        assertTrue(state.authenticated)
        assertEquals(1, f.calls.count { it.path.endsWith("password/session") })
        assertEquals(2, f.calls.count { it.path.endsWith("native-session/refresh") })
    }

    @Test fun controller401RenewsButDoesNotReplayTheFailedOperation() = runTest {
        val f = Fixture(); val state = AccountController(f.repository(), backgroundScope)
        state.account = "fixture"; state.password = "synthetic-only"; state.answer = "4"
        state.challenge = HumanChallenge("question", "2+2", Long.MAX_VALUE)
        state.login(); runCurrent(); state.readSecurity(); runCurrent()
        assertTrue(state.authenticated)
        assertEquals(1, f.calls.count { it.path.endsWith("native-session/refresh") })
        assertEquals(1, f.calls.count { it.path.endsWith("security/current") })
    }

    @Test fun logoutClearsCredentialsEvenWhenReadingTheGrantFails() = runTest {
        val f = Fixture(); var unreadable = false; var clears = 0
        val broken = object : SessionStore {
            override fun read(): SavedSession? {
                if (unreadable) throw PortalFailure(PortalFailure.Reason.STORAGE)
                return f.sessions.read()
            }
            override fun save(value: SavedSession) = f.sessions.save(value)
            override fun clear() { clears++; f.sessions.clear() }
        }
        val repo = f.repository(broken); f.login(repo); val before = clears; unreadable = true
        repo.signOutAccount()
        assertTrue(clears > before); assertNull(f.sessions.read())
        try { repo.commerceSessionBinding(); fail() } catch (failure: PortalFailure) {
            assertEquals(PortalFailure.Reason.SESSION_EXPIRED, failure.reason)
        }
        assertFalse(f.calls.any { it.path.endsWith("native-session/logout") })
    }

    @Test fun rotationRejectsFamilyChangesAndTokenReuse() = runTest {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONObject("refreshGrant").put("issuedAt", iso(start.plusSeconds(1))).put("expiresAt", iso(start.plusSeconds(604801))) },
            { it.getJSONObject("refreshGrant").put("refreshToken", grantA) },
            { it.getJSONObject("session").put("sessionRef", "access_a") },
            { it.getJSONObject("session").put("accountId", "") }
        )
        for (mutate in mutations) {
            val f = Fixture(); val repo = f.repository(); f.login(repo); f.expireAccess(); f.refreshTransform = mutate
            try { repo.restoreSession(); fail() } catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.PROTOCOL, failure.reason) }
            assertEquals(grantA, f.sessions.read()!!.refreshGrant!!.token.value)
            try { repo.commerceSessionBinding(); fail() } catch (_: PortalFailure) { }
        }
    }

    @Test fun committedRotationSurvivesOverviewFailureWithoutReusingConsumedGrant() = runTest {
        val f = Fixture(); val repo = f.repository(); f.login(repo); f.expireAccess(); f.overviewStatus = 503
        try { repo.restoreSession(); fail() } catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.UNAVAILABLE, failure.reason) }
        assertEquals(grantB, f.sessions.read()!!.refreshGrant!!.token.value)
        try { repo.commerceSessionBinding(); fail() } catch (_: PortalFailure) { }
        f.overviewStatus = 200
        assertEquals("access_b", f.repository().restoreSession()!!.token.value)
        assertEquals(1, f.calls.count { it.path.endsWith("native-session/refresh") })
        assertEquals("access_b", f.calls.last().token)
    }

    @Test fun lostRefreshResponseIsNotAutomaticallyRetried() = runTest {
        val f = Fixture(); val repo = f.repository(); f.login(repo); f.expireAccess()
        f.beforeRefresh = { throw PortalFailure(PortalFailure.Reason.NETWORK) }
        val state = AccountController(repo, backgroundScope); state.restore(); runCurrent()
        assertFalse(state.authenticated)
        assertEquals(1, f.calls.count { it.path.endsWith("native-session/refresh") })
        assertEquals(grantA, f.sessions.read()!!.refreshGrant!!.token.value)
        f.beforeRefresh = {}; f.refreshStatus = 401; state.onForeground(); runCurrent()
        assertFalse(state.authenticated); assertNull(f.sessions.read())
        assertEquals(2, f.calls.count { it.path.endsWith("native-session/refresh") })
    }

    @Test fun old401CannotRemoveANewerLogin() = runTest {
        val f = Fixture(); val repo = f.repository(); f.login(repo)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.beforeSecurity = { entered.complete(Unit); release.await() }
        val old = async { try { repo.security(); false } catch (_: kotlinx.coroutines.CancellationException) { true } }
        entered.await(); val fresh = async { f.login(repo) }; runCurrent(); release.complete(Unit)
        assertTrue(old.await()); assertTrue(fresh.await() is LoginResult.Authenticated)
        assertEquals(grantA, f.sessions.read()!!.refreshGrant!!.token.value)
        assertEquals(sessionBinding(Secret("access_a")), repo.commerceSessionBinding())
    }

    @Test fun proactiveRenewalDefersUntilBusyReadSettlesAndDoesNotReplayIt() = runTest {
        val f = Fixture(); val state = AccountController(f.repository(), backgroundScope)
        state.account = "fixture"; state.password = "synthetic-only"; state.answer = "4"
        state.challenge = HumanChallenge("question", "2+2", Long.MAX_VALUE)
        state.login(); runCurrent()
        val release = CompletableDeferred<Unit>(); f.beforeSecurity = { release.await() }
        state.readSecurity(); runCurrent()
        f.now = start.plusSeconds(1740); f.elapsed += 1740000
        testScheduler.advanceTimeBy(1740000); runCurrent()
        assertTrue(state.foregroundCheckPending)
        assertEquals(0, f.calls.count { it.path.endsWith("native-session/refresh") })
        release.complete(Unit); runCurrent()
        assertTrue(state.authenticated)
        assertEquals(1, f.calls.count { it.path.endsWith("native-session/refresh") })
        assertEquals(1, f.calls.count { it.path.endsWith("security/current") })
    }

    @Test fun rotationStorageFailureCannotPublishAuthentication() = runTest {
        val f = Fixture(); var refuseRotation = false
        val fault = object : SessionStore {
            override fun read() = f.sessions.read()
            override fun save(value: SavedSession) {
                if (refuseRotation) throw PortalFailure(PortalFailure.Reason.STORAGE)
                f.sessions.save(value)
            }
            override fun clear() = f.sessions.clear()
        }
        val repo = f.repository(fault); f.login(repo); f.expireAccess(); refuseRotation = true
        try { repo.restoreSession(); fail() } catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.STORAGE, failure.reason) }
        try { repo.commerceSessionBinding(); fail() } catch (_: PortalFailure) { }
        assertEquals(1, f.calls.count { it.path.endsWith("native-session/refresh") })
    }

    @Test fun secondFactorMustCompleteBeforeGrantExchange() = runTest {
        val f = Fixture(); f.requireTotp = true; val repo = f.repository()
        assertTrue(f.login(repo) is LoginResult.TotpRequired)
        assertNull(f.sessions.read())
        assertFalse(f.calls.any { it.path == "/api/portal/native-session" })
        val result = repo.totp("fixture_totp", "123456")
        assertEquals("native_fixture_account", result.session.accountId)
        assertNotNull(f.sessions.read()!!.refreshGrant)
        assertEquals(listOf("/api/portal/password/session", "/api/portal/password/second-factor", "/api/portal/native-session"), f.calls.map { it.path })
    }

    @Test fun rotatingAccessRetainsAccountRecoveryIdentityAndExactDeliveryReceipt() = runTest {
        for (pending in listOf(false, true)) {
            val f = Fixture(); val repo = f.repository(); f.login(repo)
            val accountBinding = repo.commerceAccountBinding(); val oldBinding = repo.commerceSessionBinding()
            val receipt = DeliveryRecord("native_fixture_account", oldBinding, "resource_" + "a".repeat(64),
                "synthetic_operation", Secret("https://sub.invalid/synthetic-capability"), Secret("synthetic_handoff"), pending)
            f.deliveryRecord = receipt; f.expireAccess(); repo.restoreSession()
            assertEquals(accountBinding, repo.commerceAccountBinding())
            assertNotEquals(oldBinding, repo.commerceSessionBinding())
            assertSame(receipt, f.deliveryRecord)
            assertEquals(oldBinding, f.deliveryRecord!!.sessionBinding)
            assertFalse(f.calls.any { it.path.contains("/setup") || it.path.contains("/commerce/") })
        }
    }
}
