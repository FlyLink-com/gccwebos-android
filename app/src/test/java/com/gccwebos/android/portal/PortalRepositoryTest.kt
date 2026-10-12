package com.gccwebos.android.portal

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.time.Instant

class PortalRepositoryTest {
    private val now = Instant.parse("2026-09-30T00:00:00Z")
    private val policy = OriginPolicy("https://portal.invalid", setOf("https://sub.invalid"))
    private class Store : DeliveryStore {
        var value: DeliveryRecord? = null
        var fail = false
        val events = mutableListOf<String>()
        override fun read() = value
        override fun save(record: DeliveryRecord) {
            if (fail) throw PortalFailure(PortalFailure.Reason.STORAGE)
            value = record; events += if(record.url == null) "key" else if(record.pendingAck) "receipt" else "acked"
        }
        override fun clear() { value = null }
    }
    private class Wire : PortalTransport {
        val calls = mutableListOf<Triple<String,String?,Secret?>>()
        val replies = ArrayDeque<WireResponse>()
        var observer: ((String) -> Unit)? = null
        override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
            calls += Triple(path,body,session); observer?.invoke(path)
            return replies.removeFirst()
        }
    }
    private fun loginResponse(token: String = "native_fixture") = WireResponse(200, """{"session":{"sessionRef":"$token","accountId":"fixture@example.invalid","issuedAt":"2026-09-30T00:00:00.000Z","expiresAt":"2026-09-30T00:30:00.000Z"}}""")
    private fun delivery() = WireResponse(200,"""{"delivery":{"handoffRef":"fixture_handoff","kind":"subscription_url","subscriptionUrl":"https://sub.invalid/sub/a/synthetic_only"}}""")
    private val resource = Resource.Available("resource_"+"a".repeat(64),"synthetic",now.plusSeconds(3600),true,Usage(100,null,null,"pending",null))
    private suspend fun login(repository: PortalRepository, wire: Wire) {
        wire.replies += loginResponse()
        repository.login("fixture@example.invalid","synthetic",HumanChallenge("challenge","1 + 2"),"3")
    }
    @Test fun securityReadUsesNativeSessionAndRejectsRevocationWithoutFallback() = runTest {
        val wire = Wire(); val repo = PortalRepository(wire, Store(), policy, {0}, {now}); login(repo, wire)
        wire.replies += WireResponse(200, securityBody)
        val status = repo.security(); assertFalse(status.authenticatorBound); assertTrue(status.setupRequired)
        assertEquals("/api/portal/security/current", wire.calls.last().first)
        assertNull(wire.calls.last().second); assertEquals("native_fixture", wire.calls.last().third!!.value)
        wire.replies += WireResponse(401, "{\"error\":\"invalid_session\"}")
        try { repo.security(); fail() } catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.SESSION_EXPIRED, failure.reason) }
        try { repo.security(); fail() } catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.SESSION_EXPIRED, failure.reason) }
        assertEquals(3, wire.calls.size)
    }
    private val securityBody = """{"security":{"authenticatorBound":false,"initialAccount":true,"passkeyEnabled":false,"passkeyExported":false,"setupRequired":true,"safety":{"noAdminApi":true,"noPublicAdmin":true,"noRawSecretReturned":true}}}"""
    @Test fun securityProjectionRejectsCoercedBooleansMissingSafetyAndSecretExtensions() {
        assertFalse(PortalJson.security(securityBody).passkeyEnabled)
        for (body in listOf(securityBody.replace("\"authenticatorBound\":false", "\"authenticatorBound\":\"false\""),
            securityBody.replace("\"noRawSecretReturned\":true", "\"noRawSecretReturned\":false"),
            securityBody.replace("\"setupRequired\":true", "\"setupRequired\":true,\"otpSecret\":\"synthetic\""),
            "{\"security\":{}}")) {
            assertThrows(Exception::class.java) { PortalJson.security(body) }
        }
    }
    @Test fun persistsBeforeAckAndDoesNotRepeatSetup() = runTest {
        val wire = Wire(); val store = Store(); val repo = PortalRepository(wire,store,policy,{0},{now})
        login(repo,wire); wire.replies += delivery(); wire.replies += WireResponse(204,"")
        wire.observer = { path -> if(path.endsWith("/ack")) assertEquals(listOf("key","receipt"),store.events) }
        repo.acquire(resource); repo.acquire(resource)
        assertEquals(listOf("key","receipt","acked"),store.events)
        assertEquals(1,wire.calls.count { it.first.endsWith("/setup") })
        assertFalse(store.value!!.pendingAck)
    }
    @Test fun failedSetupReusesDurableKey() = runTest {
        val wire = Wire(); val store = Store(); val repo = PortalRepository(wire,store,policy,{0},{now})
        login(repo,wire); wire.replies += WireResponse(503,"")
        try { repo.acquire(resource); fail() } catch(_: PortalFailure) { }
        val key = store.value!!.operationKey
        wire.replies += delivery(); wire.replies += WireResponse(204,""); repo.acquire(resource)
        val bodies = wire.calls.filter { it.first.endsWith("/setup") }.map { JSONObject(it.second!!).getString("idempotencyKey") }
        assertEquals(listOf(key,key),bodies)
    }
    @Test fun storageFailureNeverAcknowledges() = runTest {
        val wire = Wire(); val store = Store(); val repo = PortalRepository(wire,store,policy,{0},{now})
        login(repo,wire); wire.replies += delivery()
        wire.observer = { if(it.endsWith("/setup")) store.fail = true }
        try { repo.acquire(resource); fail() } catch(f: PortalFailure) { assertEquals(PortalFailure.Reason.STORAGE,f.reason) }
        assertFalse(wire.calls.any { it.first.endsWith("/ack") })
    }
    @Test fun pendingAckRetriesWithoutSetup() = runTest {
        val wire = Wire(); val store = Store(); val repo = PortalRepository(wire,store,policy,{0},{now})
        login(repo,wire); wire.replies += delivery(); wire.replies += WireResponse(503,"")
        try { repo.acquire(resource); fail() } catch(_: PortalFailure) { }
        wire.replies += WireResponse(204,""); repo.acquire(resource)
        assertEquals(1,wire.calls.count { it.first.endsWith("/setup") })
        assertEquals(2,wire.calls.count { it.first.endsWith("/ack") })
    }
    @Test fun pendingOldSessionCannotAcknowledge() = runTest {
        val wire = Wire(); val store = Store(); val repo = PortalRepository(wire,store,policy,{0},{now})
        store.value = DeliveryRecord("fixture@example.invalid",sessionBinding(Secret("old")),resource.reference,"operation_fixture",Secret("https://sub.invalid/sub/a/fake"),Secret("receipt"),true)
        login(repo,wire)
        try { repo.acquire(resource); fail() } catch(f: PortalFailure) { assertEquals(PortalFailure.Reason.RECOVERY_REQUIRED,f.reason) }
        assertEquals(1,wire.calls.size)
    }
    @Test fun portal401ExpiresSessionWithoutFabricatingNone() = runTest {
        val wire = Wire(); val repo = PortalRepository(wire,Store(),policy,{0},{now})
        login(repo,wire); wire.replies += WireResponse(401,"")
        repeat(2) { try { repo.refresh(); fail() } catch(f: PortalFailure) { assertEquals(PortalFailure.Reason.SESSION_EXPIRED,f.reason) } }
        assertEquals(2,wire.calls.size)
    }
    @Test fun elapsedExpiryCannotBeExtendedByWallClockRollback() = runTest {
        val wire = Wire(); var elapsed = 0L
        val repo = PortalRepository(wire,Store(),policy,{elapsed},{now.minusSeconds(60)})
        login(repo,wire); elapsed = 1800000
        try { repo.refresh(); fail() } catch(f: PortalFailure) { assertEquals(PortalFailure.Reason.SESSION_EXPIRED,f.reason) }
        assertEquals(1,wire.calls.size)
    }
    @Test fun logoutClearsLocalDeliveryAndSession() = runTest {
        val wire = Wire(); val store = Store(); val repo = PortalRepository(wire,store,policy,{0},{now})
        login(repo,wire); repo.clearLocalData(); assertNull(store.value)
        try { repo.refresh(); fail() } catch(f: PortalFailure) { assertEquals(PortalFailure.Reason.SESSION_EXPIRED,f.reason) }
        assertEquals(1,wire.calls.size)
    }
    @Test fun passwordFlowUsesLegacyBodyWithoutBrowserMarkers() = runTest {
        val wire = Wire(); val repo = PortalRepository(wire,Store(),policy,{0},{now}); login(repo,wire)
        val body = JSONObject(wire.calls.single().second!!)
        assertFalse(body.getBoolean("rememberMe")); assertNull(wire.calls.single().third)
        assertEquals(setOf("accountId","password","rememberMe","humanVerification"),body.keys().asSequence().toSet())
    }
    @Test fun cancelledAccountLoginCannotPublishLateTotpTicketIntoNewAccount() = runTest {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>(); val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var count = 0
        val wire = object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                require(path == "/api/portal/password/session")
                if (++count == 1) {
                    started.complete(Unit); kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
                    return WireResponse(202,"""{"challenge":{"status":"second_factor_required","challengeRef":"old-mfa","availableFactors":["totp"],"expiresAt":"2026-09-30T00:05:00.000Z"}}""")
                }
                return loginResponse("native_new_account")
            }
        }
        val saved = MemorySessionStore(); val repo = PortalRepository(wire, Store(), policy, {0}, {now}, sessions = saved)
        val old = async { runCatching { repo.login("old", "synthetic", HumanChallenge("c", "prompt"), "3") } }
        started.await(); val reset = launch { repo.cancelAuthentication() }; runCurrent(); release.complete(Unit)
        assertTrue(old.await().exceptionOrNull() is kotlinx.coroutines.CancellationException); reset.join()
        assertNull(saved.read())
        repo.login("new", "synthetic", HumanChallenge("d", "prompt"), "3")
        try { repo.totp("old-mfa", "123456"); fail() } catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.LOGIN_FAILED, failure.reason) }
        assertEquals(2, count); assertNotNull(repo.commerceSessionBinding())
    }
    @Test fun explicitAuthenticationCancelRemovesTotpTicketAndNeverPersistsIt() = runTest {
        val wire = Wire(); val saved = MemorySessionStore()
        val repo = PortalRepository(wire, Store(), policy, {0}, {now}, sessions = saved)
        wire.replies += WireResponse(202,"""{"challenge":{"status":"second_factor_required","challengeRef":"mfa","availableFactors":["totp"],"expiresAt":"2026-09-30T00:05:00.000Z"}}""")
        repo.login("a", "synthetic", HumanChallenge("c", "prompt"), "3")
        assertNull(saved.read()); repo.cancelAuthentication()
        try { repo.totp("mfa", "123456"); fail() } catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.LOGIN_FAILED, failure.reason) }
        assertEquals(1, wire.calls.size); assertNull(saved.read())
    }
    @Test fun totpChallengeDoesNotCreateSession() = runTest {
        val wire = Wire(); val repo = PortalRepository(wire,Store(),policy,{0},{now})
        wire.replies += WireResponse(202,"""{"challenge":{"status":"second_factor_required","challengeRef":"mfa","availableFactors":["totp"],"expiresAt":"2026-09-30T00:05:00.000Z"}}""")
        assertTrue(repo.login("a","b",HumanChallenge("c","prompt"),"d") is LoginResult.TotpRequired)
        try { repo.refresh(); fail() } catch(f: PortalFailure) { assertEquals(PortalFailure.Reason.SESSION_EXPIRED,f.reason) }
        wire.replies += loginResponse(); repo.totp("mfa","123456")
        assertEquals("totp",JSONObject(wire.calls.last().second!!).getString("factor"))
    }
    @Test fun secretStringRepresentationsAreRedacted() {
        assertFalse(Secret("sensitive").toString().contains("sensitive"))
        assertFalse(DeliveryRecord("private-account",sessionBinding(Secret("sensitive")),"resource","key").toString().contains("private-account"))
        assertFalse(WireResponse(200,"sensitive").toString().contains("sensitive"))
    }
    @Test fun strictSubscriptionBoundary() {
        assertEquals("https://sub.invalid/sub/a/fake",policy.subscription("https://sub.invalid/sub/a/fake"))
        listOf("http://sub.invalid/sub/a/fake","https://other.invalid/sub/a/fake","https://user@sub.invalid/sub/a/fake","https://sub.invalid/sub/a/fake?q=secret","https://sub.invalid/sub/a/%2e%2e","https://sub.invalid/sub/a/fake#x","https://sub.invalid:444/sub/a/fake").forEach {
            try { policy.subscription(it); fail(it) } catch(_: IllegalArgumentException) { }
        }
    }
    @Test fun pendingIsNotMeasuredZero() {
        try { Usage(100,0,null,"pending",null); fail() } catch(_: IllegalArgumentException) { }
        assertNull(Usage(100,null,null,"pending",null).usedBytes)
        assertEquals(0L,Usage(100,0,100,"measured_zero",now).usedBytes)
    }
    @Test fun zeroQuotaIsNotUnlimitedOrZeroRemaining() {
        assertNull(Usage(0,10,null,"measured",now).remainingBytes)
        try { Usage(0,10,0,"measured",now); fail() } catch(_: IllegalArgumentException) { }
    }
    @Test fun overQuotaClampsRemaining() { assertEquals(0L,Usage(100,101,0,"measured",now).remainingBytes) }
    @Test fun rejectsUnknownResourceState() {
        try { PortalJson.resource("""{"resource":{"state":"invented"}}"""); fail() } catch(_: IllegalStateException) { }
    }
    @Test fun noneAndReconciliationRemainDistinct() {
        assertEquals(Resource.None,PortalJson.resource("""{"resource":{"state":"none"}}"""))
        assertEquals(Resource.Reconciliation,PortalJson.resource("""{"resource":{"state":"reconciliation_required"}}"""))
    }
    @Test fun rejectsBrowserContextAsSession() {
        try { PortalJson.session(JSONObject(loginResponse("portalctx_public").body),0); fail() } catch(_: IllegalArgumentException) { }
    }
    @Test fun humanChallengeExpiryPreventsPasswordSubmission() = runTest {
        val wire = Wire(); val repo = PortalRepository(wire,Store(),policy,{100},{now})
        try { repo.login("a","b",HumanChallenge("c","prompt",99),"d"); fail() }
        catch(f: PortalFailure) { assertEquals(PortalFailure.Reason.LOGIN_FAILED,f.reason) }
        assertTrue(wire.calls.isEmpty())
    }
    @Test fun totpExpiryRequiresRestartLogin() = runTest {
        val wire = Wire(); var elapsed = 0L
        val repo = PortalRepository(wire,Store(),policy,{elapsed},{now})
        wire.replies += WireResponse(202,"""{"challenge":{"status":"second_factor_required","challengeRef":"mfa","availableFactors":["totp"],"expiresAt":"2026-09-30T00:05:00.000Z"}}""")
        repo.login("a","b",HumanChallenge("c","prompt"),"d"); elapsed = 300000
        try { repo.totp("mfa","123456"); fail() }
        catch(f: PortalFailure) { assertEquals(PortalFailure.Reason.LOGIN_FAILED,f.reason) }
        assertEquals(1,wire.calls.size)
    }
    @Test fun newAccountPurgesOldMaterialBeforeAnyResourceRequest() = runTest {
        val wire = Wire(); val store = Store(); val repo = PortalRepository(wire,store,policy,{0},{now})
        store.value = DeliveryRecord("other@example.invalid","hash",resource.reference,"old-key")
        login(repo,wire); assertNull(store.value)
    }
    @Test fun serviceFailureIsNotNoResource() = runTest {
        val wire = Wire(); val repo = PortalRepository(wire,Store(),policy,{0},{now})
        login(repo,wire); wire.replies += WireResponse(503,"")
        try { repo.refresh(); fail() }
        catch(f: PortalFailure) { assertEquals(PortalFailure.Reason.UNAVAILABLE,f.reason) }
    }
    @Test fun sessionBindingDoesNotPersistBearer() {
        val binding = sessionBinding(Secret("native_sensitive_fixture"))
        assertEquals(64,binding.length); assertFalse(binding.contains("native_sensitive_fixture"))
    }
    @Test fun resourceFixturesPreserveNullAndStaleSemantics() {
        val root = JSONObject(requireNotNull(javaClass.getResource("/portal-cases.json")).readText())
        val resources = root.getJSONObject("resources")
        resources.keys().forEach { key -> PortalJson.resource(resources.getJSONObject(key).toString()) }
    }

    @Test fun cancellingAuthenticationPreservesDurableSubscription() = runTest {
        val wire = Wire(); val store = Store(); val repo = PortalRepository(wire,store,policy,{0},{now})
        val receipt = DeliveryRecord("fixture@example.invalid","hash",resource.reference,"existing-key")
        store.value = receipt
        repo.cancelAuthentication()
        assertSame(receipt,store.value); assertTrue(wire.calls.isEmpty())
        try { repo.refresh(); fail() } catch(f: PortalFailure) { assertEquals(PortalFailure.Reason.SESSION_EXPIRED,f.reason) }
    }


    @Test fun oldCommerceSessionCannotDispatchAfterReplacementLogin() = runTest {
        val wire = Wire(); val repo = PortalRepository(wire,Store(),policy,{0},{now})
        login(repo,wire)
        val oldBinding = repo.commerceSessionBinding()
        repo.clearLocalData()
        wire.replies += loginResponse("replacement_fixture")
        repo.login("fixture@example.invalid","synthetic",HumanChallenge("challenge","1+2"),"3")
        val count = wire.calls.size
        try { repo.commerce("/api/portal/coins/wallet", expectedSession=oldBinding); fail() }
        catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(count,wire.calls.size)
        assertNotEquals(oldBinding,repo.commerceSessionBinding())
    }

    @Test fun anOptionalCommerceRouteAnswering401Or404NeverEndsTheSession() = runTest {
        val wire = Wire(); val repo = PortalRepository(wire,Store(),policy,{0},{now})
        login(repo,wire)
        // A disabled feature falls through to the protected fallback (401) or an absent route (404).
        for (status in listOf(401, 404)) {
            // #772 answers a disabled feature with a plain string error body.
            wire.replies += WireResponse(status, "{\"error\":\"Unauthorized\"}")
            assertEquals(status, repo.commerceOptional("/api/portal/commerce/plan-switch/state").status)
        }
        // The session is still alive: the ordinary commerce read works and a real 401 there still expires it.
        wire.replies += WireResponse(200, "{}")
        assertEquals(200, repo.commerce("/api/portal/commerce/model").status)
        wire.replies += WireResponse(401, "{}")
        try { repo.commerce("/api/portal/commerce/model"); fail() } catch (f: PortalFailure) { assertEquals(PortalFailure.Reason.SESSION_EXPIRED, f.reason) }
    }
    @Test fun anOptionalCommerceReadIsLimitedToTheCommercePathsWithoutAQuery() = runTest {
        val wire = Wire(); val repo = PortalRepository(wire,Store(),policy,{0},{now}); login(repo,wire)
        val count = wire.calls.size
        for (path in listOf("/api/portal/coins/wallet", "/api/portal/commerce/plan-switch/state?x=1", "/api/portal/security/current")) {
            try { repo.commerceOptional(path); fail(path) } catch (_: PortalFailure) { }
        }
        assertEquals(count, wire.calls.size)
    }
}
