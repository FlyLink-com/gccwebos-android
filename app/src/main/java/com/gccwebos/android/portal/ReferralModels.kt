package com.gccwebos.android.portal

import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder

internal data class ReferralPolicy(val policyRef: String, val version: Long, val effectiveAt: Instant,
    val rateBps: Long, val rounding: String, val attributionDays: Long, val confirmationDays: Long)
internal data class ReferralProfile(val eligible: Boolean, val linkPath: String?, val policy: ReferralPolicy?, val attributionDays: Long?)
internal data class ReferralPerformance(val registrations: Long, val firstRechargeConversions: Long,
    val creditedFirstRechargeCoins: Long, val pendingRewardCoins: Long, val availableRewardCoins: Long,
    val reversedRewardCoins: Long, val conversionRate: Double, val reconciliationRequired: Boolean)
internal class ReferralFailure(val status: Int) : Exception("Referral response $status")

/** Fixed public Portal paths and projections. Link creation has no invented quote or reward fields. */
internal object ReferralJson {
    const val PROFILE = "/api/portal/referral/profile"
    const val PERFORMANCE = "/api/portal/referral/performance"
    const val LINK = "/api/portal/referral/link"
    const val SHARE_ORIGIN = "https://portal.gccwebos.com"
    fun validRequest(path: String, body: String?) = when (path) {
        PROFILE, PERFORMANCE -> body == null
        LINK -> body == "{}"
        else -> false
    }
    fun shareUrl(path: String): String {
        require(Regex("/r/[A-Za-z0-9_-]{32}").matches(path))
        return SHARE_ORIGIN + path
    }
    private fun JSONObject.exact(vararg keys: String) { require(keys().asSequence().toSet() == keys.toSet()) }
    private fun JSONObject.text(key: String): String { val value = get(key); require(value is String); return value }
    private fun JSONObject.integer(key: String, minimum: Long = 0, maximum: Long = Usage.MAX_SAFE): Long {
        val raw = get(key); require(raw is Number && raw.toDouble().isFinite())
        val value = raw.toLong(); require(value in minimum..maximum && value.toDouble() == raw.toDouble())
        return value
    }
    fun profile(body: String): ReferralProfile {
        val root = JSONObject(body); root.exact("profile")
        val value = root.getJSONObject("profile")
        val keys = value.keys().asSequence().toSet()
        require(keys == setOf("eligible", "linkPath", "policy", "attributionDays") || keys == setOf("eligible", "linkPath", "policy"))
        val eligible = value.get("eligible"); require(eligible is Boolean)
        val path = if (value.isNull("linkPath")) null else value.text("linkPath").also { shareUrl(it) }
        require(eligible || path == null)
        val policy = if (value.isNull("policy")) null else value.getJSONObject("policy").let { p ->
            p.exact("policyRef", "version", "effectiveAt", "rateBps", "rounding", "attributionDays", "confirmationDays")
            val reference = p.text("policyRef").also { require(Regex("[A-Za-z0-9][A-Za-z0-9_:-]{0,127}").matches(it)) }
            val effective = p.text("effectiveAt").also { require(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z").matches(it)) }
            val instant = Instant.parse(effective)
            require(DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(instant) == effective)
            val rounding = p.text("rounding").also { require(it in setOf("floor", "half_up")) }
            ReferralPolicy(reference, p.integer("version", 1), instant, p.integer("rateBps", 1, 10000),
                rounding, p.integer("attributionDays", 1, 365), p.integer("confirmationDays", 0, 365))
        }
        val attribution = if (value.has("attributionDays")) {
            if (value.isNull("attributionDays")) null else value.integer("attributionDays", 1, 365)
        } else policy?.attributionDays
        require(policy == null || attribution == policy.attributionDays)
        return ReferralProfile(eligible, path, policy, attribution)
    }
    fun performance(body: String): ReferralPerformance {
        val root = JSONObject(body); root.exact("performance")
        val value = root.getJSONObject("performance")
        value.exact("registrations", "firstRechargeConversions", "creditedFirstRechargeCoins", "pendingRewardCoins", "availableRewardCoins", "reversedRewardCoins", "conversionRate", "reconciliationRequired")
        val registrations = value.integer("registrations"); val conversions = value.integer("firstRechargeConversions")
        require(conversions <= registrations)
        val rate = value.get("conversionRate"); require(rate is Number && rate.toDouble().isFinite())
        require(rate.toDouble() == if (registrations == 0L) 0.0 else conversions.toDouble() / registrations)
        val reconciliation = value.get("reconciliationRequired"); require(reconciliation is Boolean)
        return ReferralPerformance(registrations, conversions, value.integer("creditedFirstRechargeCoins"),
            value.integer("pendingRewardCoins"), value.integer("availableRewardCoins"), value.integer("reversedRewardCoins"), rate.toDouble(), reconciliation)
    }
}

internal class ReferralRepository(private val request: suspend (String, String?) -> WireResponse) {
    suspend fun profile() = read(ReferralJson.PROFILE, null, ReferralJson::profile)
    suspend fun generateLink() = read(ReferralJson.LINK, "{}") { body ->
        ReferralJson.profile(body).also { require(it.eligible && it.linkPath != null && it.attributionDays != null) }
    }
    suspend fun performance() = read(ReferralJson.PERFORMANCE, null, ReferralJson::performance)
    private suspend fun <T> read(path: String, body: String?, project: (String) -> T): T {
        val response = request(path, body)
        if (response.status == 401) throw PortalFailure(PortalFailure.Reason.SESSION_EXPIRED)
        if (response.status != 200) throw ReferralFailure(response.status)
        return try { project(response.body) } catch (_: Exception) { throw PortalFailure(PortalFailure.Reason.PROTOCOL) }
    }
}
