package com.gccwebos.android.portal

import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Official-contract shaped, entirely in-memory HTTP request/projection checks. */
class ReferralContractTest {
    private val policy = OriginPolicy("https://portal.gccwebos.com", setOf("https://sub.gccwebos.com"))
    private val path = "/r/" + "a".repeat(32)
    private val profile = """{"profile":{"eligible":true,"linkPath":null,"policy":{"policyRef":"referral_fixture_v2","version":2,"effectiveAt":"2026-09-30T00:00:00.000Z","rateBps":735,"rounding":"half_up","attributionDays":47,"confirmationDays":9},"attributionDays":47}}"""
    private val performance = """{"performance":{"registrations":4,"firstRechargeConversions":1,"creditedFirstRechargeCoins":17,"pendingRewardCoins":3,"availableRewardCoins":5,"reversedRewardCoins":2,"conversionRate":0.25,"reconciliationRequired":false}}"""
    private fun reject(action: () -> Unit) { try { action(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} }
    private fun assertNativeHeaders(request: Request) {
        assertEquals("https://portal.gccwebos.com", request.header("Origin")); assertEquals("portal.gccwebos.com", request.header("Host"))
        assertEquals("native_referral_fixture", request.header("X-Portal-Session")); assertEquals("no-store", request.header("Cache-Control"))
        assertNull(request.header("Authorization")); assertNull(request.header("Cookie")); assertNull(request.header("Idempotency-Key"))
    }
    @Test fun onlyTwoReadsAndEmptyLinkPostUseMatchingPortalOriginHostAndNativeHeader() {
        for (route in listOf(ReferralJson.PROFILE, ReferralJson.PERFORMANCE)) {
            val request = portalReferralRequest(policy, route, null, Secret("native_referral_fixture"))
            assertNativeHeaders(request); assertEquals("GET", request.method); assertNull(request.body)
            assertEquals("https://portal.gccwebos.com$route", request.url.toString())
        }
        val create = portalReferralRequest(policy, ReferralJson.LINK, "{}", Secret("native_referral_fixture"))
        assertNativeHeaders(create); assertEquals("POST", create.method)
        val buffer = Buffer(); requireNotNull(create.body).writeTo(buffer); assertEquals("{}", buffer.readUtf8())
    }
    @Test fun unrelatedRoutesQueryFragmentsAndWrongMethodsBodiesCannotReachAdapter() {
        for ((route, body) in listOf(
            "/api/portal/referral/profile?extra=1" to null, "/api/portal/referral/link#fragment" to "{}",
            "/api/portal/coins/recharge/orders" to "{}", "/api/admin/referral/profile" to null,
            ReferralJson.PROFILE to "{}", ReferralJson.PERFORMANCE to "{}", ReferralJson.LINK to null,
            ReferralJson.LINK to "", ReferralJson.LINK to "{ }", ReferralJson.LINK to "{\"email\":\"fixture@example.invalid\"}")) {
            reject { portalReferralRequest(policy, route, body, Secret("native_referral_fixture")) }
        }
        reject { OriginPolicy("http://portal.gccwebos.com", emptySet()) }
        reject { OriginPolicy("https://portal.gccwebos.com@untrusted.invalid", emptySet()) }
        for (invalidSession in listOf("", "ab", "Native_upper", "native.referral", "native/referral", "native referral", "a".repeat(97))) {
            reject { portalReferralRequest(policy, ReferralJson.PROFILE, null, Secret(invalidSession)) }
        }
    }
    @Test fun dynamicPolicyAndLegacyNullProfileHaveNoInventedRateOrAttribution() {
        val value = ReferralJson.profile(profile)
        assertEquals(735L, value.policy!!.rateBps); assertEquals(47L, value.attributionDays); assertEquals(9L, value.policy.confirmationDays)
        val legacy = ReferralJson.profile("""{"profile":{"eligible":true,"linkPath":null,"policy":null}}""")
        assertNull(legacy.policy); assertNull(legacy.attributionDays); assertNull(legacy.linkPath)
        assertEquals("https://portal.gccwebos.com$path", ReferralJson.shareUrl(path))
        for (bad in listOf("/r/" + "a".repeat(31), path + "?token=fixture", path + "#x", "https://untrusted.invalid$path", "/r/" + "%".repeat(32))) reject { ReferralJson.shareUrl(bad) }
    }
    @Test fun policyTypesCanonicalTimeAndCrossFieldMismatchesFailClosed() {
        for (change in listOf<(JSONObject) -> Unit>(
            { it.getJSONObject("policy").put("policyRef", 7) }, { it.getJSONObject("policy").put("policyRef", "referral.fixture") },
            { it.getJSONObject("policy").put("effectiveAt", "2026-09-30T24:00:00.000Z") },
            { it.getJSONObject("policy").put("rateBps", 0) }, { it.getJSONObject("policy").put("rounding", "ceil") },
            { it.put("attributionDays", 48) }, { it.put("attributionDays", JSONObject.NULL) },
            { it.put("eligible", false).put("linkPath", path) }, { it.put("linkPath", 123) })) {
            val root = JSONObject(profile); change(root.getJSONObject("profile")); reject { ReferralJson.profile(root.toString()) }
        }
    }
    @Test fun performanceUsesExactServerTotalsRatioAndNeverWalletBalance() {
        val value = ReferralJson.performance(performance)
        assertEquals(4L, value.registrations); assertEquals(1L, value.firstRechargeConversions); assertEquals(0.25, value.conversionRate, 0.0)
        assertEquals(5L, value.availableRewardCoins); assertEquals(3L, value.pendingRewardCoins); assertEquals(2L, value.reversedRewardCoins)
        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("registrations", -1) }, { it.put("registrations", "4") }, { it.put("firstRechargeConversions", 5) },
            { it.put("conversionRate", 0.2500000001) }, { it.put("pendingRewardCoins", 0.1) },
            { it.put("reconciliationRequired", "false") })) {
            val root = JSONObject(performance); change(root.getJSONObject("performance")); reject { ReferralJson.performance(root.toString()) }
        }
    }
    @Test fun generationResponseCannotSucceedWithoutEligiblePathAndAttribution() = runTest {
        for (body in listOf(profile, """{"profile":{"eligible":true,"linkPath":"$path","policy":null,"attributionDays":null}}""")) {
            val repository = ReferralRepository { route, submitted -> assertEquals(ReferralJson.LINK, route); assertEquals("{}", submitted); WireResponse(200, body) }
            try { repository.generateLink(); fail() } catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.PROTOCOL, failure.reason) }
        }
    }
}
