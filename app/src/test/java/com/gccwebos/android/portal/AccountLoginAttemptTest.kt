package com.gccwebos.android.portal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.time.Instant

/** Offline controller checks for the compact login's submit/retry boundary. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountLoginAttemptTest {
    private val policy = OriginPolicy("https://portal.invalid", setOf("https://sub.invalid"))
    private val now = Instant.parse("2026-09-30T00:00:00Z")
    private class EmptyDeliveryStore : DeliveryStore {
        override fun read(): DeliveryRecord? = null
        override fun save(record: DeliveryRecord) = throw AssertionError("Login failure must not save delivery")
        override fun clear() = Unit
    }

    private class PasswordWire : PortalTransport {
        val requests = mutableListOf<JSONObject>()
        override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
            assertEquals("/api/portal/password/session", path)
            assertNull(session)
            requests += JSONObject(requireNotNull(body))
            return WireResponse(401, """{"error":"invalid_credentials"}""")
        }
    }
    private fun passwordRepository(wire: PasswordWire) = PortalRepository(wire, EmptyDeliveryStore(), policy,
        { 0L }, { now }, subscription = SubscriptionSource { throw AssertionError("No subscription requests") },
        sessions = MemorySessionStore(), profiles = MemoryLocalProfileStore())

    @Test fun blankPasswordsNeverDispatchToPortal() = runTest {
        for (password in listOf("", " \t\r\n", "\uFEFF\u00A0\u3000", "\u2002\u2028\u2029")) {
            val wire = PasswordWire()
            val state = AccountController(passwordRepository(wire), this).apply {
                account = "fixture@example.invalid"; this.password = password
                answer = "3"; challenge = HumanChallenge("password-question", "1 + 2")
            }
            state.login(); runCurrent()
            assertTrue(wire.requests.isEmpty())
            assertFalse(state.authenticated); assertFalse(state.busy)
            assertNotNull(state.error)
        }
    }

    @Test fun oneToSevenCharacterPasswordsReachPortalWithoutRegistrationRules() = runTest {
        for (password in listOf("a", "ab", "abc", "abcd", "abcde", "abcdef", "abcdefg")) {
            val wire = PasswordWire()
            val state = AccountController(passwordRepository(wire), this).apply {
                account = "fixture@example.invalid"; this.password = password
                answer = "3"; challenge = HumanChallenge("password-question", "1 + 2")
            }
            state.login(); runCurrent()
            assertEquals(1, wire.requests.size)
            assertEquals(password, wire.requests.single().getString("password"))
            assertFalse(state.authenticated)
        }
    }

    @Test fun longPasswordsReachPortalCompleteWithInternalCharacters() = runTest {
        for (password in listOf("a".repeat(161), "prefix-" + "x".repeat(4096) + "-\"\\密码🙂\n  interior-tail")) {
            val wire = PasswordWire()
            val state = AccountController(passwordRepository(wire), this).apply {
                account = "fixture@example.invalid"; this.password = password
                answer = "3"; challenge = HumanChallenge("password-question", "1 + 2")
            }
            state.login(); runCurrent()
            assertEquals(1, wire.requests.size)
            val body = wire.requests.single()
            assertEquals(password, body.getString("password"))
            assertEquals(password.length, body.getString("password").length)
            assertEquals(setOf("accountId", "password", "rememberMe", "humanVerification"), body.keys().asSequence().toSet())
            assertEquals("fixture@example.invalid", body.getString("accountId"))
            assertFalse(body.getBoolean("rememberMe")); assertFalse(state.authenticated)
        }
    }

    @Test fun edgeWhitespaceMatchesServerWithoutChangingPasswordInterior() = runTest {
        val examples = listOf(
            " \tplain value\r\n" to "plain value",
            "\uFEFF\u00A0inside\uFEFFvalue\u3000\u2029" to "inside\uFEFFvalue",
            " \t\u001Cleft\u001F \r\n" to "\u001Cleft\u001F"
        )
        for ((password, expected) in examples) {
            val wire = PasswordWire()
            try {
                passwordRepository(wire).login("fixture@example.invalid", password,
                    HumanChallenge("password-question", "1 + 2"), "3")
                fail("The offline transport always rejects authentication")
            } catch (failure: PortalFailure) {
                assertEquals(PortalFailure.Reason.LOGIN_FAILED, failure.reason)
            }
            assertEquals(1, wire.requests.size)
            assertEquals(expected, wire.requests.single().getString("password"))
        }
    }

    @Test fun busyRepeatedSubmitSendsOnceAndPreservesNewInput() = runTest {
        var calls = 0
        val release = CompletableDeferred<Unit>()
        val wire = object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                assertEquals("/api/portal/password/session", path)
                calls++
                release.await()
                throw PortalFailure(PortalFailure.Reason.NETWORK)
            }
        }
        val state = AccountController(PortalRepository(wire, EmptyDeliveryStore(), policy, { 0L }, { now }), this)
        state.account = "fixture@example.invalid"
        state.password = "offline-password"
        state.answer = "3"
        state.challenge = HumanChallenge("first-question", "1 + 2")
        state.login()
        assertTrue(state.busy)
        assertNull(state.challenge)
        assertEquals("", state.password)
        assertEquals("", state.answer)
        runCurrent()
        assertEquals(1, calls)
        state.password = "next-offline-password"
        state.answer = "4"
        state.login()
        assertEquals("next-offline-password", state.password)
        assertEquals("4", state.answer)
        assertEquals(1, calls)
        release.complete(Unit)
        runCurrent()
        assertFalse(state.busy)
        assertFalse(state.authenticated)
        assertNull(state.challenge)
    }

    @Test fun failedAttemptRequiresExplicitFreshQuestionWithoutAutomaticReplay() = runTest {
        for (reason in listOf(PortalFailure.Reason.LOGIN_FAILED, PortalFailure.Reason.NETWORK,
            PortalFailure.Reason.RATE_LIMITED, PortalFailure.Reason.PROTOCOL, PortalFailure.Reason.UNAVAILABLE)) {
            val paths = mutableListOf<String>()
            val wire = object : PortalTransport {
                override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                    paths += path
                    if (path == "/api/portal/password/session") throw PortalFailure(reason)
                    assertEquals("/api/portal/human-challenges", path)
                    return WireResponse(201, """{"challenge":{"challengeRef":"fresh-question","type":"math","prompt":"2 + 2","expiresInSeconds":60}}""")
                }
            }
            val state = AccountController(PortalRepository(wire, EmptyDeliveryStore(), policy, { 0L }, { now }), this)
            state.account = "fixture@example.invalid"
            state.password = "offline-password"
            state.answer = "3"
            state.challenge = HumanChallenge("first-question", "1 + 2")
            state.login()
            runCurrent()
            assertEquals(listOf("/api/portal/password/session"), paths)
            assertFalse(state.busy)
            assertFalse(state.authenticated)
            assertNull(state.challenge)
            assertNotNull(state.error)
            state.password = "next-offline-password"
            state.answer = "4"
            state.login()
            runCurrent()
            assertEquals(listOf("/api/portal/password/session"), paths)
            state.challenge()
            runCurrent()
            assertEquals(listOf("/api/portal/password/session", "/api/portal/human-challenges"), paths)
            assertEquals("fresh-question", state.challenge?.reference)
            assertEquals("2 + 2", state.challenge?.prompt)
        }
    }

    @Test fun cancelledQuestionCannotPublishLateUncooperativeResponse() = runTest {
        var requests = 0
        val release = CompletableDeferred<Unit>()
        val wire = object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                assertEquals("/api/portal/human-challenges", path)
                requests++
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
                return WireResponse(201, """{"challenge":{"challengeRef":"late-question","type":"math","prompt":"1 + 2","expiresInSeconds":60}}""")
            }
        }
        val state = AccountController(PortalRepository(wire, EmptyDeliveryStore(), policy, { 0L }, { now }), this)
        state.challenge()
        runCurrent()
        assertEquals(1, requests)
        state.cancelAuthentication()
        assertNull(state.challenge)
        release.complete(Unit)
        runCurrent()
        assertEquals(1, requests)
        assertNull(state.challenge)
        assertFalse(state.authenticated)
        assertFalse(state.busy)
        assertFalse(state.clearing)
    }
}
