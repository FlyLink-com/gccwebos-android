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
    fun nativeSession(body: String, now: Instant, elapsed: Long): Pair<Session, NativeRefreshGrant> {
        val root = JSONObject(body); root.keysExactly("session", "refreshGrant")
        val rawSession = root.getJSONObject("session")
        rawSession.keysExactly("sessionRef", "accountId", "authMethod", "status", "issuedAt", "expiresAt", "safety")
        require(rawSession.text("authMethod") in setOf("password", "totp") && rawSession.text("status") == "active")
        val safety = rawSession.getJSONObject("safety")
        val flags = arrayOf("totpCodeReturned", "encryptedTotpSecretReturned", "rawEntryTokenReturned", "runtimeDbMutated")
        safety.keysExactly(*flags)
        flags.forEach { require(safety.get(it) == false) }
        val issued = time(rawSession.text("issuedAt"))
        val expiry = time(rawSession.text("expiresAt"))
        require(issued <= now && expiry > now)
        val value = session(root, elapsed)
        require(value.lifetimeMillis in 1..SavedSession.MAX_NATIVE_SESSION_MILLIS)
        val rawGrant = root.getJSONObject("refreshGrant")
        rawGrant.keysExactly("refreshToken", "issuedAt", "expiresAt")
        val grant = NativeRefreshGrant(Secret(rawGrant.text("refreshToken")), time(rawGrant.text("issuedAt")),
            time(rawGrant.text("expiresAt")), now, elapsed)
        require(grant.valid(now, elapsed) && expiry <= grant.expiresAt && issued >= grant.issuedAt)
        SavedSession.capture(value, now, elapsed, grant) // Validate the explicit native identity and bearer.
        return value to grant
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
