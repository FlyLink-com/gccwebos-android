package com.gccwebos.android.portal

import org.json.JSONObject
import java.time.Instant

internal object PortalJson {
    private fun JSONObject.keysExactly(vararg fields: String) { require(keys().asSequence().toSet() == fields.toSet()) }
    private fun JSONObject.text(key: String) = (get(key) as String).also { require(it.isNotBlank()) }
    private fun JSONObject.number(key: String): Long {
        val value = get(key)
        require(value is Number && value.toDouble().isFinite())
        val result = value.toLong()
        require(result in 0..Usage.MAX_SAFE && result.toDouble() == value.toDouble())
        return result
    }
    private fun JSONObject.nullableNumber(key: String) = if (isNull(key)) null else number(key)
    private fun time(value: String): Instant {
        require(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z").matches(value))
        return Instant.parse(value)
    }
    fun security(body: String): AccountSecurity {
        val root = JSONObject(body); root.keysExactly("security")
        val value = root.getJSONObject("security")
        value.keysExactly("authenticatorBound", "initialAccount", "passkeyEnabled", "passkeyExported", "setupRequired", "safety")
        val safety = value.getJSONObject("safety")
        safety.keysExactly("noAdminApi", "noPublicAdmin", "noRawSecretReturned")
        listOf("noAdminApi", "noPublicAdmin", "noRawSecretReturned").forEach { require(safety.get(it) == true) }
        return AccountSecurity(value.get("authenticatorBound") as Boolean, value.get("initialAccount") as Boolean,
            value.get("passkeyEnabled") as Boolean, value.get("passkeyExported") as Boolean, value.get("setupRequired") as Boolean)
    }
    fun totpEnrollment(body: String): TotpEnrollment {
        val root = JSONObject(body); root.keysExactly("enrollment")
        val value = root.getJSONObject("enrollment")
        value.keysExactly("enrollmentRef", "issuer", "accountLabel", "manualKey", "otpauthUri", "expiresAt", "expiresInSeconds", "issuedAt", "safety")
        val ref = value.text("enrollmentRef"); val key = value.text("manualKey"); val uri = value.text("otpauthUri")
        require(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}").matches(ref))
        require(Regex("[A-Z2-7]{16,128}").matches(key))
        require(uri.startsWith("otpauth://totp/") && uri.contains("secret=$key"))
        return TotpEnrollment(ref, value.text("issuer"), key, uri)
    }
    /** The bind confirmation must say "bound"; anything else is not a success. */
    fun totpBound(body: String) {
        val root = JSONObject(body); root.keysExactly("binding")
        val value = root.getJSONObject("binding")
        require(value.get("status") == "bound" && value.get("method") == "totp")
    }
    fun resource(body: String): Resource {
        val root = JSONObject(body); root.keysExactly("resource")
        val resource = root.getJSONObject("resource")
        return when(resource.text("state")) {
            "none" -> { resource.keysExactly("state"); Resource.None }
            "reconciliation_required" -> { resource.keysExactly("state"); Resource.Reconciliation }
            "available" -> {
                resource.keysExactly("state", "plan", "validUntil", "setupAvailable", "resourceRef", "usage")
                val plan = resource.getJSONObject("plan")
                plan.keysExactly("id", "durationDays", "monthlyTrafficGb")
                val durationDays = plan.number("durationDays")
                require(durationDays > 0)
                val traffic = plan.get("monthlyTrafficGb")
                require(traffic is Number && traffic.toDouble().isFinite() && traffic.toDouble() >= 0)
                val reference = resource.text("resourceRef")
                require(Regex("resource_[a-f0-9]{64}").matches(reference))
                val usage = resource.getJSONObject("usage")
                usage.keysExactly("totalBytes", "usedBytes", "remainingBytes", "collectionStatus", "updatedAt", "cycleStartedAt", "cycleEndsAt")
                val cycleStartedAt = time(usage.text("cycleStartedAt"))
                val cycleEndsAt = time(usage.text("cycleEndsAt"))
                require(cycleStartedAt < cycleEndsAt)
                Resource.Available(reference, plan.text("id"), time(resource.text("validUntil")),
                    resource.get("setupAvailable") as Boolean,
                    Usage(usage.number("totalBytes"), usage.nullableNumber("usedBytes"), usage.nullableNumber("remainingBytes"),
                        usage.text("collectionStatus"), if (usage.isNull("updatedAt")) null else time(usage.text("updatedAt")),
                        cycleStartedAt, cycleEndsAt),
                    durationDays = durationDays, monthlyTrafficGb = traffic.toDouble())
            }
            else -> error("Invalid resource schema")
        }
    }
    fun session(root: JSONObject, elapsed: Long): Session {
        val value = root.getJSONObject("session")
        val token = value.text("sessionRef")
        require(!token.startsWith("portalctx_") && !token.startsWith("portalbrowser_"))
        val issued = Instant.parse(value.text("issuedAt")); val expires = Instant.parse(value.text("expiresAt"))
        require(expires > issued)
        val lifetime = java.time.Duration.between(issued, expires).toMillis()
        return Session(Secret(token), value.text("accountId"), expires, lifetime, elapsed)
    }
    fun delivery(body: String, previous: DeliveryRecord, policy: OriginPolicy): DeliveryRecord {
        val root = JSONObject(body); root.keysExactly("delivery")
        val value = root.getJSONObject("delivery")
        value.keysExactly("handoffRef", "kind", "subscriptionUrl")
        require(value.text("kind") == "subscription_url")
        return DeliveryRecord(previous.accountId, previous.sessionBinding, previous.resource, previous.operationKey,
            Secret(policy.subscription(value.text("subscriptionUrl"))), Secret(value.text("handoffRef")), true)
    }
}
