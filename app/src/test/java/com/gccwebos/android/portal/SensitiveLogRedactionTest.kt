package com.gccwebos.android.portal

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Subscription URLs, session tokens and long secrets must never reach a debug sink / logcat line,
 * even if a caller accidentally interpolates them into a PrepTrace mark.
 */
class SensitiveLogRedactionTest {
    @After fun clear() { PrepTrace.sink = null }

    @Test fun redactsHttpsSubscriptionUrlsAndQuerySecrets() {
        val input = "refresh https://sub.invalid/sub/a/tok_LIVE_secret123?token=abc.def.ghi-jkl extra"
        val out = SensitiveLogRedaction.redact(input)
        assertFalse(out.contains("tok_LIVE_secret123"))
        assertFalse(out.contains("abc.def.ghi-jkl"))
        assertFalse(out.contains("sub.invalid/sub"))
        assertTrue(out.contains("https://[REDACTED]"))
        assertTrue(out.startsWith("refresh "))
        assertTrue(out.endsWith(" extra") || out.contains(" extra"))
    }

    @Test fun redactsBearerAndRawJwtLikeTokens() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signaturepaddingvalue"
        val out = SensitiveLogRedaction.redact("Authorization: Bearer $jwt phase=ok")
        assertFalse(out.contains(jwt))
        assertFalse(out.contains("Bearer eyJ"))
        assertTrue(out.contains("[REDACTED]"))
        assertTrue(out.contains("phase=ok"))
    }

    @Test fun redactsLongHexAndBase64SecretsButKeepsShortIds() {
        val hex = "a".repeat(64)
        val short = "node-1"
        val out = SensitiveLogRedaction.redact("binding=$hex id=$short")
        assertFalse(out.contains(hex))
        assertTrue(out.contains("id=$short"))
        assertTrue(out.contains("[REDACTED]"))
    }

    @Test fun leavesSafePhaseMarksUnchanged() {
        assertEquals("refresh-download-begin", SensitiveLogRedaction.redact("refresh-download-begin"))
        assertEquals("latency-batch-end +12 ms", SensitiveLogRedaction.redact("latency-batch-end +12 ms"))
        assertEquals("read-summary import/primary http=200 type=text/plain bytes=12 gzip=no first=yaml-like",
            SensitiveLogRedaction.redact("read-summary import/primary http=200 type=text/plain bytes=12 gzip=no first=yaml-like"))
    }

    @Test fun prepTraceSinkNeverReceivesARawSubscriptionUrl() {
        val lines = mutableListOf<String>()
        PrepTrace.sink = { lines += it }
        PrepTrace.mark(" co-leak https://cdn.invalid/sub/user/SUPER_SECRET_PATH_TOKEN_XYZ")
        PrepTrace.markLazy { "lazy https://cdn.invalid/sub/user/ANOTHER_SECRET_VALUE_999?key=1" }
        assertEquals(2, lines.size)
        for (line in lines) {
            assertFalse(line.contains("SUPER_SECRET"))
            assertFalse(line.contains("ANOTHER_SECRET"))
            assertFalse(line.contains("cdn.invalid/sub"))
            assertTrue(line.contains("[REDACTED]"))
        }
    }
}
