package com.gccwebos.android.portal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Synthetic login labels only. The server account ID remains opaque and authoritative. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountDisplayEmailTest {
    private val now = Instant.parse("2026-09-30T00:00:00Z")
    private val opaqueId = "account_fixture_opaque_a"
    private val email = "login.fixture@example.invalid"
    private val nativeToken = "native_display_fixture_a"
    private val none = WireResponse(200, """{"resource":{"state":"none"}}""")
    private val totpRequired = WireResponse(202, """{"challenge":{"status":"second_factor_required",
        "availableFactors":["totp"],"challengeRef":"display_fixture_totp",
        "expiresAt":"2026-09-30T00:05:00.000Z"}}""".trimIndent())

    private fun authenticated(id: String = opaqueId, token: String = nativeToken) = WireResponse(200,
        """{"session":{"sessionRef":"$token","accountId":"$id",
        "issuedAt":"2026-09-30T00:00:00.000Z","expiresAt":"2026-09-30T00:30:00.000Z"}}""".trimIndent())

    private fun saved(id: String = opaqueId, token: String = nativeToken) =
        SavedSession(Secret(token), id, now.plusSeconds(1800), 1800000, now, 0)

    private fun controller(scope: CoroutineScope, wire: PortalTransport,
        sessions: SessionStore = MemorySessionStore()): AccountController {
        val delivery = object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = throw AssertionError("No delivery writes")
            override fun clear() = Unit
        }
        return AccountController(PortalRepository(wire, delivery,
            OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L }, { now },
            subscription = SubscriptionSource { throw AssertionError("No subscription requests") },
            sessions = sessions, profiles = MemoryLocalProfileStore()), scope)
    }

    private fun AccountController.prepareLogin(input: String = email) {
        account = input; password = "synthetic-display-password"; answer = "3"
        challenge = HumanChallenge("display_fixture_question", "1 + 2")
    }

    @Test fun queuedInputEditCannotChangeSubmittedEmailOrStableAccountIdentity() = runTest {
        val sessions = MemorySessionStore(); val bodies = mutableListOf<JSONObject>()
        val state = controller(backgroundScope, object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse = when (path) {
                "/api/portal/password/session" -> {
                    assertNull(session); bodies += JSONObject(requireNotNull(body)); authenticated()
                }
                "/api/portal/resources/current/overview" -> { assertNull(body); none }
                else -> throw AssertionError("Unexpected offline path")
            }
        }, sessions)
        state.prepareLogin("  $email  "); state.login()
        state.account = "edited.before.dispatch@example.invalid"
        assertNull(state.displayEmail); runCurrent()
        assertEquals(email, bodies.single().getString("accountId"))
        assertEquals(email, state.displayEmail); assertTrue(state.authenticated)
        assertEquals(email, state.account); assertEquals(opaqueId, state.authenticatedAccountId); assertEquals(opaqueId, sessions.read()!!.accountId)
        assertNotEquals(email, state.authenticatedAccountId)
        state.logout(); assertNull(state.displayEmail); runCurrent()
        assertNull(sessions.read()); assertFalse(state.authenticated); assertEquals(email, state.account)
    }

    @Test fun mfaNeverShowsPendingEmailAndUsesOriginalInputAfterEditsAndRepeatedSubmit() = runTest {
        val secondFactorBodies = mutableListOf<JSONObject>()
        val state = controller(backgroundScope, object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse = when (path) {
                "/api/portal/password/session" -> totpRequired
                "/api/portal/password/second-factor" -> {
                    secondFactorBodies += JSONObject(requireNotNull(body)); authenticated()
                }
                "/api/portal/resources/current/overview" -> none
                else -> throw AssertionError("Unexpected offline path")
            }
        })
        state.prepareLogin(); state.login(); runCurrent()
        assertFalse(state.authenticated); assertNull(state.displayEmail)
        assertEquals("display_fixture_totp", state.factorReference)
        state.account = "edited.during.mfa@example.invalid"; state.code = "123456"; state.totp()
        state.code = "654321"; state.totp()
        assertEquals("654321", state.code); assertNull(state.displayEmail); runCurrent()
        assertEquals(1, secondFactorBodies.size)
        assertEquals("123456", secondFactorBodies.single().getString("code"))
        assertEquals("display_fixture_totp", secondFactorBodies.single().getString("challengeRef"))
        assertNull(state.factorReference); assertEquals(email, state.account); assertEquals(opaqueId, state.authenticatedAccountId); assertEquals(email, state.displayEmail)
    }

    @Test fun cancellingMfaCannotReuseItsEmailOrTicketForNextAccountLogin() = runTest {
        var passwordCalls = 0; var secondFactorCalls = 0
        val sessions = MemorySessionStore()
        val state = controller(backgroundScope, object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse = when (path) {
                "/api/portal/password/session" -> if (++passwordCalls == 1) totpRequired
                    else authenticated("account_fixture_opaque_b", "native_display_fixture_b")
                "/api/portal/password/second-factor" -> { secondFactorCalls++; throw AssertionError("Cancelled ticket must not dispatch") }
                "/api/portal/resources/current/overview" -> none
                else -> throw AssertionError("Unexpected offline path")
            }
        }, sessions)
        state.prepareLogin(); state.login(); runCurrent(); assertNotNull(state.factorReference)
        state.cancelAuthentication(); runCurrent()
        assertNull(state.factorReference); assertNull(state.displayEmail); assertNull(sessions.read())
        state.code = "123456"; state.totp(); runCurrent(); assertEquals(0, secondFactorCalls)
        val nextEmail = "next.login.fixture@example.invalid"
        state.prepareLogin(nextEmail); state.login(); runCurrent()
        assertEquals(nextEmail, state.displayEmail); assertEquals(nextEmail, state.account); assertEquals("account_fixture_opaque_b", state.authenticatedAccountId)
        assertEquals(2, passwordCalls); assertEquals(0, secondFactorCalls)
    }

    @Test fun cancelledNonCooperativePasswordOrMfaSuccessCannotResurrectEmailOrSession() = runTest {
        for (secondFactor in listOf(false, true)) {
            val release = CompletableDeferred<Unit>(); val sessions = MemorySessionStore(); var blockedReads = 0
            val state = controller(backgroundScope, object : PortalTransport {
                override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                    if (path == "/api/portal/password/session" && secondFactor) return totpRequired
                    if (path == if (secondFactor) "/api/portal/password/second-factor" else "/api/portal/password/session") {
                        blockedReads++; withContext(NonCancellable) { release.await() }; return authenticated()
                    }
                    throw AssertionError("Cancelled authentication must not read resource")
                }
            }, sessions)
            state.prepareLogin(); state.login(); runCurrent()
            if (secondFactor) { state.code = "123456"; state.totp(); runCurrent() }
            assertEquals(1, blockedReads); assertNull(state.displayEmail)
            state.cancelAuthentication(); assertNull(state.displayEmail)
            release.complete(Unit); runCurrent()
            assertFalse(state.busy); assertFalse(state.clearing); assertFalse(state.authenticated)
            assertNull(state.factorReference); assertNull(state.displayEmail); assertNull(sessions.read())
        }
    }

    @Test fun freshControllerRestoresOldSessionWithoutGuessingEmailFromInputOrAccountId() = runTest {
        for (savedId in listOf(opaqueId, "saved.id.shape@example.invalid")) {
            val sessions = MemorySessionStore().apply { save(saved(savedId)) }
            val state = controller(backgroundScope, object : PortalTransport {
                override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                    assertEquals("/api/portal/resources/current/overview", path); assertNull(body)
                    assertNotNull(session); return none
                }
            }, sessions)
            state.account = email; state.restore(); assertNull(state.displayEmail); runCurrent()
            assertTrue(state.authenticated); assertEquals("", state.account); assertEquals(savedId, state.authenticatedAccountId)
            assertNull(state.displayEmail); assertEquals(savedId, sessions.read()!!.accountId)
            state.logout(); runCurrent(); assertEquals("", state.account); assertNull(state.authenticatedAccountId)
        }
    }

    @Test fun successfulForegroundValidationRebindsHintOnlyForExactSameSession() = runTest {
        val sessions = MemorySessionStore()
        val state = controller(backgroundScope, object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse = when (path) {
                "/api/portal/password/session" -> authenticated()
                "/api/portal/resources/current/overview" -> none
                else -> throw AssertionError("Unexpected offline path")
            }
        }, sessions)
        state.prepareLogin(); state.login(); runCurrent(); val generation = state.authSessionGeneration
        assertEquals(email, state.displayEmail)
        state.onForeground(); assertNull(state.displayEmail); runCurrent()
        assertTrue(state.authenticated); assertFalse(state.restoring); assertFalse(state.foregroundCheckPending)
        assertTrue(state.authSessionGeneration > generation)
        assertEquals(email, state.account); assertEquals(opaqueId, state.authenticatedAccountId); assertEquals(email, state.displayEmail)
    }

    @Test fun differentAccountTokenOrAuthenticationGenerationInvalidatesPreviousHint() = runTest {
        for (replacement in listOf("account", "token", "generation")) {
            val sessions = MemorySessionStore()
            val state = controller(backgroundScope, object : PortalTransport {
                override suspend fun request(path: String, body: String?, session: Secret?): WireResponse = when (path) {
                    "/api/portal/password/session" -> authenticated()
                    "/api/portal/resources/current/overview" -> none
                    else -> throw AssertionError("Unexpected offline path")
                }
            }, sessions)
            state.prepareLogin(); state.login(); runCurrent(); assertEquals(email, state.displayEmail)
            when (replacement) {
                "account" -> { sessions.save(saved("account_fixture_opaque_b")); state.onForeground(); runCurrent() }
                "token" -> { sessions.save(saved(token = "native_display_fixture_replaced")); state.onForeground(); runCurrent() }
                else -> state.authSessionGeneration++
            }
            assertTrue(state.authenticated); assertNull(state.displayEmail)
            if (replacement != "generation") assertEquals("", state.account)
            state.commerceSessionExpired(); assertFalse(state.authenticated); assertNull(state.displayEmail)
            assertEquals("", state.account); assertNull(state.authenticatedAccountId)
        }
    }

    @Test fun sessionStorageFailureNeverPublishesSubmittedEmail() = runTest {
        var saveAttempts = 0
        val sessions = object : SessionStore {
            override fun read(): SavedSession? = null
            override fun save(value: SavedSession) {
                saveAttempts++; throw PortalFailure(PortalFailure.Reason.STORAGE)
            }
            override fun clear() = Unit
        }
        val state = controller(backgroundScope, object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                assertEquals("/api/portal/password/session", path); return authenticated()
            }
        }, sessions)
        state.prepareLogin(); state.login(); runCurrent()
        assertEquals(1, saveAttempts); assertFalse(state.busy); assertFalse(state.authenticated)
        assertNull(state.displayEmail); assertNull(sessions.read()); assertNotNull(state.error)
    }
}
