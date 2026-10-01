package com.gccwebos.android.portal

import kotlinx.coroutines.*
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** All addresses, receipt IDs, legal documents and transport replies below are synthetic fixtures. */
@OptIn(ExperimentalCoroutinesApi::class)
class RegistrationFlowTest {
    private val legal = RegistrationLegalPolicy("https://portal.invalid/terms", "https://portal.invalid/privacy",
        "fixture-terms-v1", "fixture-privacy-v1")
    private fun verification(ref: String = "verification_fixture", seconds: Long = 37) = WireResponse(201,
        """{"verification":{"verificationRef":"$ref","expiresInSeconds":$seconds}}""")
    private val registered = WireResponse(201,
        """{"registration":{"userRef":"registered_fixture"},"session":{"userRef":"registered_fixture","displayName":"fixture@example.invalid"}}""")
    private data class Call(val path: String, val body: String)
    private inner class Fixture(scope: CoroutineScope, policy: RegistrationLegalPolicy? = legal,
                                elapsed: () -> Long = { 0L }) {
        var epoch = 0L
        var allowed = true
        val calls = mutableListOf<Call>()
        var reply: suspend (String, String) -> WireResponse = { path, _ ->
            if (path == RegistrationJson.VERIFICATION_PATH) verification() else registered
        }
        val state = RegistrationController(scope, AnonymousRegistrationGateway(RegistrationTransport { path, body ->
            calls += Call(path, body); reply(path, body)
        }), policy, elapsed, { epoch }, { allowed })
        fun enterFilled(): Long {
            val lease = state.enterRegistrationForm()
            state.email = " Fixture@Example.Invalid "; fill()
            return lease
        }
        fun fill() {
            state.password = " PassWord1! "; state.confirmPassword = " PassWord1! "; state.legalAccepted = true
        }
        fun otp() { state.verificationCode = "123456" }
        fun count(path: String) = calls.count { it.path == path }
    }

    @Test fun missingOrPlaceholderLegalPolicyBlocksBothPostsWithoutGatewayCalls() = runTest {
        for (policy in listOf(null,
            RegistrationLegalPolicy("https://gccwebos.com/#support", "https://gccwebos.com/#support", "fixture", "fixture"),
            RegistrationLegalPolicy("https://portal.invalid/terms", "https://portal.invalid/privacy", "", "fixture"))) {
            val f = Fixture(backgroundScope, policy); f.enterFilled(); f.otp()
            f.state.requestEmailVerification(); f.state.register(); runCurrent()
            assertFalse(f.state.legalAvailable); assertNotNull(f.state.error)
            assertEquals(0, f.calls.size); assertNull(f.state.receipt); assertFalse(f.state.busy)
        }
    }

    @Test fun registrationValidationDoesNotImposeItsPasswordRulesOnExistingLogin() = runTest {
        assertEquals("p", normalizedLoginPassword(" p "))
        for (invalid in listOf("", "Short1!", "lowercase1!", "UPPERCASE!", "Uppercase123", "A1!" + "x".repeat(158))) {
            val f = Fixture(backgroundScope); f.enterFilled(); f.state.password = invalid; f.state.confirmPassword = invalid
            f.state.requestEmailVerification(); runCurrent()
            assertNotNull(f.state.error); assertEquals(0, f.calls.size)
        }
        val f = Fixture(backgroundScope); f.enterFilled()
        f.state.legalAccepted = false; f.state.requestEmailVerification(); assertEquals(0, f.calls.size)
        f.state.legalAccepted = true; f.state.email = "not-an-email"; f.state.requestEmailVerification(); assertEquals(0, f.calls.size)
        f.state.email = "fixture@example.invalid"; f.state.confirmPassword = "Different1!"
        f.state.requestEmailVerification(); assertEquals(0, f.calls.size)
    }

    @Test fun exactAnonymousBodiesAndMinimalReceiptNeverAuthenticateOrForwardHumanFields() = runTest {
        val f = Fixture(backgroundScope); f.enterFilled(); f.state.requestEmailVerification(); runCurrent(); f.otp()
        f.state.register(); runCurrent()
        assertEquals(2, f.calls.size)
        val issued = JSONObject(f.calls[0].body)
        assertEquals(setOf("email"), issued.keys().asSequence().toSet())
        assertEquals("fixture@example.invalid", issued.getString("email"))
        val submitted = JSONObject(f.calls[1].body)
        assertEquals(setOf("email", "password", "legalAgreement", "emailVerification"), submitted.keys().asSequence().toSet())
        assertEquals("PassWord1!", submitted.getString("password"))
        assertEquals("fixture@example.invalid", submitted.getString("email"))
        val agreement = submitted.getJSONObject("legalAgreement")
        assertEquals(setOf("termsAccepted", "privacyAccepted", "termsVersion", "privacyVersion"), agreement.keys().asSequence().toSet())
        assertTrue(agreement.getBoolean("termsAccepted")); assertTrue(agreement.getBoolean("privacyAccepted"))
        assertEquals(legal.termsVersion, agreement.getString("termsVersion")); assertEquals(legal.privacyVersion, agreement.getString("privacyVersion"))
        val code = submitted.getJSONObject("emailVerification")
        assertEquals(setOf("verificationRef", "code"), code.keys().asSequence().toSet())
        assertEquals("verification_fixture", code.getString("verificationRef")); assertEquals("123456", code.getString("code"))
        assertEquals("registered_fixture", f.state.receipt?.userRef); assertEquals(0L, f.epoch)
        assertEquals("fixture@example.invalid", f.state.completedLoginEmail())
        assertEquals("", f.state.password); assertEquals("", f.state.confirmPassword); assertEquals("", f.state.verificationCode)
        assertNull(f.state.verificationExpiresInSeconds); assertFalse(f.state.pendingUnknown)
        f.state.register(); f.state.requestEmailVerification(); runCurrent(); assertEquals(2, f.calls.size)
    }

    @Test fun requestAdapterAllowsOnlyExactTwoPostsWithNoCookieOrSessionOrBrowserCredentials() {
        val policy = OriginPolicy("https://portal.invalid", emptySet())
        val bodies = listOf(RegistrationJson.VERIFICATION_PATH to RegistrationJson.verificationBody("fixture@example.invalid"),
            RegistrationJson.REGISTRATION_PATH to RegistrationJson.registrationBody(
                RegistrationSubmission("fixture@example.invalid", "PassWord1!", legal, "verification_fixture", "123456")))
        for ((path, body) in bodies) {
            val request = portalRegistrationRequest(policy, path, body)
            assertEquals("POST", request.method); assertEquals("https://portal.invalid$path", request.url.toString())
            assertEquals("https://portal.invalid", request.header("Origin")); assertEquals("portal.invalid", request.header("Host"))
            for (header in listOf("Cookie", "Authorization", "X-Portal-Session", "X-Portal-Request", "X-Portal-Session-Context", "Idempotency-Key"))
                assertNull(request.header(header))
            val buffer = Buffer(); request.body!!.writeTo(buffer); assertEquals(body, buffer.readUtf8())
        }
        for (path in listOf("/api/portal/password/session", "/api/portal/registration?sessionRef=fixture", "/api/portal/registration/"))
            assertFalse(RegistrationJson.validRequest(path, bodies[0].second))
        for (field in listOf("sessionRef", "humanVerification", "referrerId", "referralToken")) {
            val extra = JSONObject(bodies[1].second).put(field, "fixture").toString()
            assertFalse(RegistrationJson.validRequest(RegistrationJson.REGISTRATION_PATH, extra))
        }
        val coercion = JSONObject(bodies[1].second)
        coercion.getJSONObject("legalAgreement").put("termsAccepted", "true")
        assertFalse(RegistrationJson.validRequest(RegistrationJson.REGISTRATION_PATH, coercion.toString()))
    }

    @Test fun typedResponseProjectionRejectsCoercionAndUnknownRegistrationSuccess() {
        assertEquals("registered_fixture", RegistrationJson.registration(registered).userRef)
        for (body in listOf("{}", """{"registration":{"userRef":123}}""", """{"registration":{"userRef":" "}}""")) {
            try { RegistrationJson.registration(WireResponse(201, body)); fail("Invalid receipt") }
            catch (failure: RegistrationFailure) { assertTrue(failure.outcomeUnknown) }
        }
        for (ttl in listOf("0", "-1", "1.5", "\"37\"", "9007199254740992")) {
            try { RegistrationJson.verification(WireResponse(201, """{"verification":{"verificationRef":"fixture","expiresInSeconds":$ttl}}""")); fail("Invalid TTL") }
            catch (failure: RegistrationFailure) { assertTrue(failure.outcomeUnknown) }
        }
        try { RegistrationJson.registration(WireResponse(200, registered.body)); fail("Only 201") }
        catch (failure: RegistrationFailure) { assertTrue(failure.outcomeUnknown) }
    }

    @Test fun verificationIsSingleFlightUsesServerTtlAndNeverTreatsTtlAsResendCooldown() = runTest {
        val f = Fixture(backgroundScope, elapsed = { testScheduler.currentTime }); f.enterFilled()
        val release = CompletableDeferred<Unit>()
        f.reply = { _, _ -> release.await(); verification(seconds = 37) }
        f.state.requestEmailVerification(); f.state.requestEmailVerification(); runCurrent()
        assertEquals(1, f.calls.size); assertTrue(f.state.verificationLoading); assertFalse(f.state.registering)
        release.complete(Unit); runCurrent(); assertEquals(37L, f.state.verificationExpiresInSeconds)
        assertEquals(37L, f.state.verificationRemainingSeconds)
        f.otp(); f.state.requestEmailVerification()
        assertEquals("", f.state.verificationCode); runCurrent(); assertEquals(2, f.calls.size)
        advanceTimeBy(37001); runCurrent()
        assertTrue(f.state.verificationExpired); assertNull(f.state.verificationExpiresInSeconds)
        f.otp(); f.state.register(); runCurrent(); assertEquals(0, f.count(RegistrationJson.REGISTRATION_PATH))
        assertEquals(" PassWord1! ", f.state.password)
    }

    @Test fun repeatedRegistrationPostsOnceAndConsumesOtpBeforeAnyServerResponse() = runTest {
        val f = Fixture(backgroundScope); f.enterFilled(); f.state.requestEmailVerification(); runCurrent(); f.otp()
        val release = CompletableDeferred<Unit>()
        f.reply = { _, _ -> release.await(); registered }
        f.state.register(); assertEquals("", f.state.password); assertEquals("", f.state.verificationCode)
        assertNull(f.state.verificationExpiresInSeconds); assertTrue(f.state.registering)
        f.state.password = "NewPassword2!"; f.state.register(); runCurrent()
        assertEquals(1, f.count(RegistrationJson.REGISTRATION_PATH)); assertTrue(f.state.pendingUnknown)
        assertEquals("NewPassword2!", f.state.password)
        release.complete(Unit); runCurrent(); assertNotNull(f.state.receipt); assertFalse(f.state.pendingUnknown)
    }

    @Test fun allDefinitiveFailuresRequireNewOtpAndCannotReplayConsumedVerification() = runTest {
        for ((status, code) in listOf(400 to "validation_error", 400 to "legal_agreement_required",
            400 to "email_verification_failed", 409 to "account_already_exists", 429 to "registration_rate_limited")) {
            val f = Fixture(backgroundScope); f.enterFilled(); f.state.requestEmailVerification(); runCurrent(); f.otp()
            f.reply = { _, _ -> WireResponse(status, JSONObject().put("error", code).toString()) }
            f.state.register(); runCurrent(); assertNotNull(f.state.error); assertFalse(f.state.pendingUnknown)
            assertNull(f.state.receipt); f.fill(); f.otp(); f.state.register(); runCurrent()
            assertEquals(1, f.count(RegistrationJson.REGISTRATION_PATH)); assertNull(f.state.verificationExpiresInSeconds)
            f.reply = { path, _ -> if (path == RegistrationJson.VERIFICATION_PATH) verification("fresh_verification") else registered }
            f.state.requestEmailVerification(); runCurrent(); f.otp(); f.state.register(); runCurrent()
            assertEquals(2, f.count(RegistrationJson.VERIFICATION_PATH)); assertEquals(2, f.count(RegistrationJson.REGISTRATION_PATH))
            assertNotNull(f.state.receipt); assertFalse(f.state.pendingUnknown)
        }
    }

    @Test fun uncertainRegistrationStaysBlockedAcrossCancelLeaveAndEmptyNewEntry() = runTest {
        for (outcome in listOf("network", "server", "invalid", "future400", "future409", "wrongStatus")) {
            val f = Fixture(backgroundScope); val lease = f.enterFilled(); f.state.requestEmailVerification(); runCurrent(); f.otp()
            f.reply = { _, _ -> when (outcome) {
                "network" -> throw PortalFailure(PortalFailure.Reason.NETWORK)
                "server" -> WireResponse(500, """{"error":"internal_error"}""")
                "future400" -> WireResponse(400, """{"error":"future_unknown"}""")
                "future409" -> WireResponse(409, """{"error":"future_unknown"}""")
                "wrongStatus" -> WireResponse(409, """{"error":"validation_error"}""")
                else -> WireResponse(201, "{}")
            } }
            f.state.register(); runCurrent(); assertTrue(f.state.pendingUnknown); assertNull(f.state.receipt)
            f.state.cancel(); f.state.leaveRegistrationForm(lease); f.state.enterRegistrationForm(); f.fill(); f.otp()
            assertTrue(f.state.pendingUnknown); f.state.register(); f.state.requestEmailVerification(); runCurrent()
            assertEquals(1, f.count(RegistrationJson.REGISTRATION_PATH)); assertEquals(1, f.count(RegistrationJson.VERIFICATION_PATH))
            assertNull(f.state.completedLoginEmail()); assertNotNull(f.state.error)
        }
    }

    @Test fun editedEmailOrDisposedFormCannotPublishLateVerificationOrRegistration() = runTest {
        for (registration in listOf(false, true)) for (outcome in listOf("success", "network", "invalid", "auth")) {
            val f = Fixture(backgroundScope); val lease = f.enterFilled()
            if (registration) { f.state.requestEmailVerification(); runCurrent(); f.otp() }
            val release = CompletableDeferred<Unit>()
            f.reply = { _, _ ->
                withContext(NonCancellable) { release.await() }
                when (outcome) {
                    "success" -> if (registration) registered else verification("late_fixture")
                    "network" -> throw PortalFailure(PortalFailure.Reason.NETWORK)
                    "invalid" -> WireResponse(201, "{}")
                    else -> WireResponse(401, """{"error":"invalid_session"}""")
                }
            }
            if (registration) f.state.register() else f.state.requestEmailVerification()
            runCurrent(); f.state.email = "replacement@example.invalid"
            f.state.leaveRegistrationForm(lease); f.state.enterRegistrationForm()
            release.complete(Unit); runCurrent()
            assertFalse(f.state.busy); assertNull(f.state.error); assertNull(f.state.receipt)
            assertNull(f.state.verificationExpiresInSeconds); assertNull(f.state.completedLoginEmail()); assertEquals(0L, f.epoch)
            assertEquals("", f.state.password); assertEquals("", f.state.confirmPassword); assertEquals("", f.state.verificationCode)
            assertFalse(f.state.legalAccepted)
            if (registration) { f.state.email = "fixture@example.invalid"; assertTrue(f.state.pendingUnknown) }
        }
    }

    @Test fun authGenerationAndQueuedCancellationPreventDispatchAndLateIdentityPublication() = runTest {
        val queued = Fixture(backgroundScope); val lease = queued.enterFilled()
        queued.state.requestEmailVerification(); queued.state.leaveRegistrationForm(lease); runCurrent()
        assertEquals(0, queued.calls.size); assertFalse(queued.state.pendingUnknown)
        val f = Fixture(backgroundScope); f.enterFilled(); f.state.requestEmailVerification(); runCurrent(); f.otp()
        f.state.register(); f.epoch++; runCurrent()
        assertEquals(0, f.count(RegistrationJson.REGISTRATION_PATH)); assertFalse(f.state.pendingUnknown); assertNull(f.state.receipt)
        val late = Fixture(backgroundScope); late.enterFilled(); late.state.requestEmailVerification(); runCurrent(); late.otp()
        val release = CompletableDeferred<Unit>(); late.reply = { _, _ -> withContext(NonCancellable) { release.await() }; registered }
        late.state.register(); runCurrent(); late.epoch++; release.complete(Unit); runCurrent()
        assertNull(late.state.receipt); assertNull(late.state.completedLoginEmail()); assertTrue(late.state.pendingUnknown)
        val disabled = Fixture(backgroundScope); disabled.enterFilled(); disabled.allowed = false
        disabled.state.requestEmailVerification(); disabled.state.register(); runCurrent(); assertEquals(0, disabled.calls.size)
    }

    @Test fun monotonicJumpRejectsExpiredOtpBeforeTimerAndOldLeaseCannotCancelNewForm() = runTest {
        var elapsed = 0L
        val f = Fixture(backgroundScope, elapsed = { elapsed }); val old = f.enterFilled()
        f.state.requestEmailVerification(); runCurrent(); f.otp(); elapsed = 37001
        f.state.register(); assertTrue(f.state.verificationExpired); runCurrent()
        assertEquals(0, f.count(RegistrationJson.REGISTRATION_PATH)); assertNull(f.state.verificationExpiresInSeconds)
        val current = f.state.enterRegistrationForm(); f.fill()
        f.state.leaveRegistrationForm(old); f.state.requestEmailVerification(); runCurrent()
        assertEquals(2, f.count(RegistrationJson.VERIFICATION_PATH)); assertNotNull(f.state.verificationExpiresInSeconds)
        f.state.leaveRegistrationForm(current); assertNull(f.state.verificationExpiresInSeconds)
        assertEquals(" Fixture@Example.Invalid ", f.state.email); assertEquals("", f.state.password)
    }
}
