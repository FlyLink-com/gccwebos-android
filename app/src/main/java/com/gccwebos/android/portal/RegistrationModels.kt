package com.gccwebos.android.portal

import org.json.JSONObject
import java.net.URI
import java.util.Locale

/** Explicit document identities; no production defaults or generic support-page substitutes. */
internal class RegistrationLegalPolicy(val termsUrl: String, val privacyUrl: String,
                                      val termsVersion: String, val privacyVersion: String) {
    val configured: Boolean get() = document(termsUrl) && document(privacyUrl) && termsUrl != privacyUrl &&
        listOf(termsVersion, privacyVersion).all { it.isNotBlank() && it.trim() == it && it.length <= 128 }
    private fun document(value: String) = try {
        val uri = URI(value)
        value.none { it.isISOControl() } && uri.scheme == "https" && uri.host != null &&
            uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null &&
            uri.port in listOf(-1, 443) && !uri.rawPath.isNullOrEmpty() && uri.rawPath != "/"
    } catch (_: Exception) { false }
}

internal class EmailVerificationReceipt(val verificationRef: String, val expiresInSeconds: Long) {
    init { require(verificationRef.isNotBlank() && expiresInSeconds in 1..9007199254740991L) }
    override fun toString() = "EmailVerificationReceipt([REDACTED])"
}
/** Registration is complete, but this is deliberately not a native authenticated Session. */
internal class RegistrationReceipt(val userRef: String) {
    init { require(userRef.isNotBlank()) }
    override fun toString() = "RegistrationReceipt([REDACTED])"
}
internal class RegistrationSubmission(val email: String, val password: String,
    val legalPolicy: RegistrationLegalPolicy, val verificationRef: String, val code: String) {
    override fun toString() = "RegistrationSubmission([REDACTED])"
}
internal class RegistrationFailure(val kind: Kind, val outcomeUnknown: Boolean = false) : Exception(kind.name) {
    enum class Kind { LEGAL_UNAVAILABLE, LEGAL_REQUIRED, EMAIL, PASSWORD, PASSWORD_CONFIRMATION, CODE,
        VERIFICATION_REQUIRED, VERIFICATION_FAILED, ALREADY_EXISTS, RATE_LIMITED, UNAVAILABLE, PROTOCOL, UNKNOWN }
}

internal object RegistrationRules {
    fun email(value: String): String {
        val normalized = normalizedLoginPassword(value).lowercase(Locale.ROOT)
        if (!Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+").matches(normalized) ||
            normalized.any { it.isWhitespace() || it.isISOControl() }) throw RegistrationFailure(RegistrationFailure.Kind.EMAIL)
        return normalized
    }
    fun password(value: String): String {
        val normalized = normalizedLoginPassword(value)
        // Mobile registration requirements only; existing-account login remains trim + nonempty.
        if (normalized.length !in 8..160 || !Regex("[A-Z]").containsMatchIn(normalized) ||
            !Regex("[0-9]").containsMatchIn(normalized) || !Regex("[^A-Za-z0-9]").containsMatchIn(normalized))
            throw RegistrationFailure(RegistrationFailure.Kind.PASSWORD)
        return normalized
    }
    fun code(value: String): String = normalizedLoginPassword(value).also {
        if (!Regex("[0-9]{6}").matches(it)) throw RegistrationFailure(RegistrationFailure.Kind.CODE)
    }
}

internal object RegistrationJson {
    const val VERIFICATION_PATH = "/api/portal/registration/email-verification"
    const val REGISTRATION_PATH = "/api/portal/registration"
    fun verificationBody(email: String): String = JSONObject().put("email", RegistrationRules.email(email)).toString()
    fun registrationBody(input: RegistrationSubmission): String {
        if (!input.legalPolicy.configured) throw RegistrationFailure(RegistrationFailure.Kind.LEGAL_UNAVAILABLE)
        require(input.verificationRef.isNotBlank())
        return JSONObject().put("email", RegistrationRules.email(input.email))
            .put("password", RegistrationRules.password(input.password))
            .put("legalAgreement", JSONObject().put("termsAccepted", true).put("privacyAccepted", true)
                .put("termsVersion", input.legalPolicy.termsVersion).put("privacyVersion", input.legalPolicy.privacyVersion))
            .put("emailVerification", JSONObject().put("verificationRef", input.verificationRef)
                .put("code", RegistrationRules.code(input.code))).toString()
    }
    /** Reject arbitrary routes, extra fields and credential/browser/session projections before dispatch. */
    fun validRequest(path: String, body: String): Boolean = try {
        val json = JSONObject(body)
        when (path) {
            VERIFICATION_PATH -> keys(json) == setOf("email") &&
                text(json, "email").let { RegistrationRules.email(it) == it }
            REGISTRATION_PATH -> {
                val legal = json.getJSONObject("legalAgreement"); val verification = json.getJSONObject("emailVerification")
                keys(json) == setOf("email", "password", "legalAgreement", "emailVerification") &&
                    text(json, "email").let { RegistrationRules.email(it) == it } &&
                    text(json, "password").let { RegistrationRules.password(it) == it } &&
                    keys(legal) == setOf("termsAccepted", "privacyAccepted", "termsVersion", "privacyVersion") &&
                    legal.get("termsAccepted") == true && legal.get("privacyAccepted") == true &&
                    listOf("termsVersion", "privacyVersion").all { text(legal, it).isNotBlank() } &&
                    keys(verification) == setOf("verificationRef", "code") &&
                    text(verification, "verificationRef").isNotBlank() &&
                    text(verification, "code").let { RegistrationRules.code(it) == it }
            }
            else -> false
        }
    } catch (_: Exception) { false }
    fun verification(response: WireResponse): EmailVerificationReceipt = parse(response) { root ->
        val value = root.getJSONObject("verification")
        EmailVerificationReceipt(text(value, "verificationRef"), positiveInteger(value.get("expiresInSeconds")))
    }
    fun registration(response: WireResponse): RegistrationReceipt = parse(response) { root ->
        // Do not parse, retain, persist or authenticate the separate registration session payload.
        RegistrationReceipt(text(root.getJSONObject("registration"), "userRef"))
    }
    private fun <T> parse(response: WireResponse, read: (JSONObject) -> T): T {
        if (response.status != 201) {
            val code = try { JSONObject(response.body).get("error") as? String } catch (_: Exception) { null }
            val kind = when (code) {
                "account_already_exists" -> RegistrationFailure.Kind.ALREADY_EXISTS
                "email_verification_required" -> RegistrationFailure.Kind.VERIFICATION_REQUIRED
                "email_verification_failed" -> RegistrationFailure.Kind.VERIFICATION_FAILED
                "legal_agreement_required" -> RegistrationFailure.Kind.LEGAL_REQUIRED
                "email_verification_rate_limited", "registration_rate_limited" -> RegistrationFailure.Kind.RATE_LIMITED
                "email_verification_unavailable" -> RegistrationFailure.Kind.UNAVAILABLE
                "validation_error" -> RegistrationFailure.Kind.PROTOCOL
                else -> RegistrationFailure.Kind.UNKNOWN
            }
            // An unfamiliar 4xx cannot prove the account creation did not happen.
            val definitive = when (response.status) {
                400 -> code in setOf("validation_error", "legal_agreement_required",
                    "email_verification_required", "email_verification_failed")
                409 -> code == "account_already_exists"
                429 -> code in setOf("email_verification_rate_limited", "registration_rate_limited")
                else -> false
            }
            throw RegistrationFailure(kind, outcomeUnknown = !definitive)
        }
        try { return read(JSONObject(response.body)) }
        catch (_: Exception) { throw RegistrationFailure(RegistrationFailure.Kind.PROTOCOL, outcomeUnknown = true) }
    }
    private fun keys(value: JSONObject) = value.keys().asSequence().toSet()
    private fun text(value: JSONObject, key: String): String = (value.get(key) as? String) ?: error("Expected string")
    private fun positiveInteger(value: Any): Long {
        require(value is Number)
        val number = value.toDouble()
        require(number.isFinite() && number >= 1 && number <= 9007199254740991.0 && number % 1.0 == 0.0)
        return number.toLong()
    }
}
