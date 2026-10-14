package com.gccwebos.android.portal

/**
 * Defense-in-depth scrubber for debug / diagnostic text that may accidentally include a
 * subscription URL, session token or other secret. Callers should still never pass secrets;
 * this only ensures a mistaken interpolation cannot reach logcat.
 *
 * Replacement text is always the fixed token `[REDACTED]` — never a fragment of the original.
 */
internal object SensitiveLogRedaction {
    private const val PLACEHOLDER = "[REDACTED]"

    // http(s) URLs with optional userinfo, host, path, query and fragment.
    private val url = Regex("""https?://[^\s<>"')\]]+""", RegexOption.IGNORE_CASE)

    // Authorization: Bearer <token>  (token stops at whitespace)
    private val bearer = Regex("""(?i)Bearer\s+[A-Za-z0-9._\-+/=]{8,}""")

    // JWT-shaped or long opaque tokens (three base64url segments, or a single long secret).
    private val jwt = Regex("""\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b""")

    // 32+ hex (e.g. binding hashes) or 40+ url-safe base64 blobs that are not short ids.
    private val longSecret = Regex("""\b(?:[A-Fa-f0-9]{32,}|[A-Za-z0-9_-]{40,})\b""")

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var out = url.replace(text) { "https://$PLACEHOLDER" }
        out = bearer.replace(out) { "Bearer $PLACEHOLDER" }
        out = jwt.replace(out, PLACEHOLDER)
        out = longSecret.replace(out, PLACEHOLDER)
        return out
    }
}
