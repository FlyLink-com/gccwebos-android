package com.gccwebos.android.portal

import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** 绑定身份验证器 client contract, taken from proxy-gateway-minimal server/routes/user-portal.mjs + its route tests. */
class TotpEnrollmentTest {
    private val at = Instant.parse("2026-09-30T00:00:00Z")
    private val policy = OriginPolicy("https://portal.invalid", setOf("https://sub.invalid"))
    private val empty = object : DeliveryStore {
        override fun read(): DeliveryRecord? = null
        override fun save(record: DeliveryRecord) = Unit
        override fun clear() = Unit
    }
    private val saved = SavedSession(Secret("native_synthetic"), "fixture@example.invalid", at.plusSeconds(1800), 1800000, at, 1000)
    private val enrollment = """{"enrollment":{"enrollmentRef":"totp_enroll_1","issuer":"GlobalCircle","accountLabel":"acct_1",
        "manualKey":"JBSWY3DPEHPK3PXP","otpauthUri":"otpauth://totp/GlobalCircle:acct_1?secret=JBSWY3DPEHPK3PXP&issuer=GlobalCircle",
        "expiresAt":"2026-09-30T00:05:00.000Z","expiresInSeconds":300,"issuedAt":"2026-09-30T00:00:00.000Z",
        "safety":{"noAdminApi":true,"noPublicAdmin":true,"noDbMutationBeforeConfirm":true}}}"""
    private fun repo(wire: (String, String?, String?) -> WireResponse): PortalRepository {
        val sessions = MemorySessionStore().apply { save(saved) }
        return PortalRepository(object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?) = wire(path, body, session?.value)
        }, empty, policy, { 61000 }, { at.plusSeconds(60) }, sessions = sessions)
    }
    private fun restoring(log: MutableList<String>, bound: (String, String?) -> WireResponse) = repo { path, body, session ->
        log += path
        if (path == "/api/portal/resources/current/overview") WireResponse(200, """{"resource":{"state":"none"}}""") else bound(path, body)
    }

    @Test fun startReadsOneTimeKeyFrom201AndSendsOnlyTheSessionHeader() = runTest {
        val log = mutableListOf<String>()
        val r = restoring(log) { path, body -> assertEquals("/api/portal/security/totp/enrollment", path); assertEquals("{}", body); WireResponse(201, enrollment) }
        r.restoreSession()
        val e = r.startTotp()
        assertEquals("totp_enroll_1", e.enrollmentRef); assertEquals("JBSWY3DPEHPK3PXP", e.manualKey)
        assertFalse(e.toString().contains("JBSWY3DPEHPK3PXP"))
    }
    @Test fun startRejectsAKeyThatIsNotBase32OrAUriForAnotherSecret() = runTest {
        for (bad in listOf(enrollment.replace("JBSWY3DPEHPK3PXP\",\"otpauth", "jbswy3dpehpk3pxp\",\"otpauth"),
            enrollment.replace("secret=JBSWY3DPEHPK3PXP", "secret=AAAAAAAAAAAAAAAA"), enrollment.replace("otpauth://totp/", "https://"))) {
            val r = restoring(mutableListOf()) { _, _ -> WireResponse(201, bad) }
            r.restoreSession()
            try { r.startTotp(); fail() } catch (e: PortalFailure) { assertEquals(PortalFailure.Reason.PROTOCOL, e.reason) }
        }
    }
    @Test fun confirmSendsRefAndSixDigitsAndAcceptsOnlyBound() = runTest {
        var sent: String? = null
        var reply = """{"binding":{"status":"bound","accountId":"a","method":"totp","boundAt":"2026-09-30T00:01:00.000Z","safety":{"noRawSecretReturned":true}}}"""
        val r = restoring(mutableListOf()) { path, body ->
            assertEquals("/api/portal/security/totp/enrollment/confirm", path); sent = body; WireResponse(200, reply)
        }
        r.restoreSession()
        r.confirmTotp("totp_enroll_1", "654321")
        val json = org.json.JSONObject(sent!!)
        assertEquals("totp_enroll_1", json.getString("enrollmentRef")); assertEquals("654321", json.getString("code"))
        reply = """{"binding":{"status":"pending","method":"totp"}}"""
        try { r.confirmTotp("totp_enroll_1", "654321"); fail() } catch (e: PortalFailure) { assertEquals(PortalFailure.Reason.PROTOCOL, e.reason) }
    }
    @Test fun wrongCodeIs400AndExpiredSessionIs401() = runTest {
        var status = 400
        val r = restoring(mutableListOf()) { _, _ -> WireResponse(status, """{"error":"validation_error"}""") }
        r.restoreSession()
        try { r.confirmTotp("totp_enroll_1", "000000"); fail() } catch (e: PortalFailure) { assertEquals(PortalFailure.Reason.LOGIN_FAILED, e.reason) }
        status = 401
        try { r.confirmTotp("totp_enroll_1", "000000"); fail() } catch (e: PortalFailure) { assertEquals(PortalFailure.Reason.SESSION_EXPIRED, e.reason) }
    }
}
