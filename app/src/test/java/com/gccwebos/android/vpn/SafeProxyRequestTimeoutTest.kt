package com.gccwebos.android.vpn

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic TUIC v5 compatibility only; this field has no timeout effect in the pinned v5 core. */
class SafeProxyRequestTimeoutTest {
    private fun artifact(type: String = "tuic", endpoint: String = "192.0.2.10") = """
        proxies:
          - name: synthetic request timeout fixture
            type: $type
            server: $endpoint
            port: 443
    """.trimIndent() + when (type) {
        "ss" -> "\n    cipher: aes-128-gcm\n    password: synthetic-only"
        "vless" -> "\n    uuid: 11111111-1111-4111-8111-111111111111\n    tls: true\n    servername: tls.fixture.invalid"
        "hysteria2" -> "\n    password: synthetic-only\n    sni: tls.fixture.invalid"
        "tuic" -> "\n    uuid: 22222222-2222-4222-8222-222222222222\n    password: synthetic-only\n    sni: tls.fixture.invalid"
        else -> error("Synthetic protocol required")
    }
    private fun timeout(source: String, value: String) = source + "\n    request-timeout: $value"
    private fun proxy(bytes: ByteArray) = JSONObject(String(bytes, Charsets.UTF_8))
        .getJSONArray("proxies").getJSONObject(0)
    private fun rejectAll(source: String, reason: String? = null) {
        val bytes = source.toByteArray()
        for (action in listOf<() -> Any>({ SafeProxyConfig.project(bytes, 0) },
            { SafeProxyConfig.validateForPreparation(bytes) }, { SafeProxyConfig.selectedEndpoint(bytes, 0) },
            { SafeProxyConfig.projectResolved(bytes, 0, "203.0.113.10") })) {
            val failure = assertThrows(UnsafeProxyConfiguration::class.java) { action() }
            if (reason != null) assertEquals(reason, failure.reason.name)
            assertEquals("Unsupported or unsafe proxy configuration", failure.message)
            assertNull(failure.cause)
        }
    }

    @Test fun explicitIntegerBoundariesArePreservedAcrossLiteralEntryPoints() {
        for (value in listOf(0, 1, 8000, 30000)) {
            val source = timeout(artifact(), value.toString()).toByteArray()
            assertTrue(SafeProxyConfig.inspect(source).single().supported)
            assertTrue(SafeProxyConfig.validateForPreparation(source).single().supported)
            assertEquals("192.0.2.10", SafeProxyConfig.selectedEndpoint(source, 0))
            for (projected in listOf(SafeProxyConfig.project(source, 0),
                SafeProxyConfig.projectResolved(source, 0, "192.0.2.10"))) {
                val numeric = proxy(projected).get("request-timeout")
                assertTrue("Timeout must remain an integer DTO field", numeric is Int)
                assertEquals(value, numeric)
            }
        }
    }

    @Test fun missingTimeoutRemainsOmittedWithoutCreatingDefaults() {
        val projected = proxy(SafeProxyConfig.project(artifact().toByteArray(), 0))
        assertFalse(projected.has("request-timeout"))
        assertFalse(projected.has("congestion-controller"))
        assertFalse(projected.has("bbr-profile"))
        assertFalse(projected.getBoolean("skip-cert-verify"))
    }

    @Test fun timeoutDoesNotRewriteCongestionBbrOrTlsFields() {
        for (value in listOf(0, 8000)) for (controller in listOf<String?>(null, "bbr", "cubic", "new_reno")) {
            val source = timeout(artifact(), value.toString()) + "\n    bbr-profile: aggressive" +
                (controller?.let { "\n    congestion-controller: $it" } ?: "")
            val projected = proxy(SafeProxyConfig.project(source.toByteArray(), 0))
            assertEquals(value, projected.getInt("request-timeout"))
            assertEquals("aggressive", projected.getString("bbr-profile"))
            if (controller == null) assertFalse(projected.has("congestion-controller"))
            else assertEquals(controller, projected.getString("congestion-controller"))
            assertEquals("tls.fixture.invalid", projected.getString("sni"))
            assertFalse(projected.getBoolean("skip-cert-verify"))
        }
    }

    @Test fun hostnameResolutionPreservesTimeoutAndIndependentTlsIdentity() {
        for (value in listOf(0, 1, 8000, 30000)) {
            val source = timeout(artifact(endpoint = "endpoint.fixture.invalid"), value.toString()).toByteArray()
            assertTrue(SafeProxyConfig.validateForPreparation(source).single().supported)
            assertEquals("endpoint.fixture.invalid", SafeProxyConfig.selectedEndpoint(source, 0))
            val projected = proxy(SafeProxyConfig.projectResolved(source, 0, "203.0.113.10"))
            assertEquals(value, projected.getInt("request-timeout"))
            assertEquals("203.0.113.10", projected.getString("server"))
            assertEquals("tls.fixture.invalid", projected.getString("sni"))
            assertFalse(projected.getBoolean("skip-cert-verify"))
            assertThrows(UnsafeProxyConfiguration::class.java) { SafeProxyConfig.project(source, 0) }
        }
    }

    @Test fun negativeOutOfRangeAndOverflowValuesAreRejected() {
        for (value in listOf("-1", "30001", "2147483647", "2147483648", "9223372036854775807",
            "999999999999999999999999999999999999")) {
            val source = timeout(artifact(), value)
            rejectAll(source)
            assertFalse(SafeProxyConfig.inspect(source.toByteArray()).single().supported)
        }
    }

    @Test fun nullStringsBooleansFractionsAndCoercionAliasesAreRejected() {
        for (value in listOf("null", "\"8000\"", "\"0\"", "true", "false", "8000.0", "8e3", "1.5",
            "+1", "01", "0x1", "0o1", "1_000", ".inf", ".NaN", "8000ms", "8000 ms", "[]", "{}")) {
            val source = timeout(artifact(), value)
            rejectAll(source)
            assertFalse(SafeProxyConfig.inspect(source.toByteArray()).single().supported)
        }
    }

    @Test fun otherProtocolsStillRejectRequestTimeoutIncludingZero() {
        for (type in listOf("ss", "vless", "hysteria2")) for (value in listOf("0", "8000")) {
            val source = timeout(artifact(type), value)
            rejectAll(source, "NodeOptionRequestTimeout")
            assertFalse(SafeProxyConfig.inspect(source.toByteArray()).single().supported)
        }
    }

    @Test fun invalidUnselectedTimeoutBlocksEveryAdmissionEntryPoint() {
        for (value in listOf("30001", "null", "\"8000\"")) {
            val source = artifact("ss") + "\n" + timeout(artifact(), value).substringAfter("proxies:\n")
            rejectAll(source)
            val summaries = SafeProxyConfig.inspect(source.toByteArray())
            assertTrue(summaries[0].supported)
            assertFalse(summaries[1].supported)
        }
    }

    @Test fun duplicateCaseAliasAndNestedKeysDoNotBypassValidation() {
        rejectAll(timeout(timeout(artifact(), "0"), "8000"), "InputSyntax")
        for (key in listOf("Request-Timeout", "REQUEST-TIMEOUT", "request_timeout", "\"request-timeout \""))
            rejectAll(artifact() + "\n    $key: 8000", "NodeOptions")
        rejectAll(timeout(artifact(), "8000") + "\n    requestTimeout: 8000", "NodeOptions")
        rejectAll(artifact() + "\n    request-timeout: &timeout 8000", "InputSyntax")
        rejectAll(artifact() + "\n    request-timeout: *missing", "InputSyntax")
        rejectAll(artifact() + "\n    request-timeout: !!int 8000", "InputSyntax")
        rejectAll(artifact() + "\nprofile: {request-timeout: 8000}", "TopLevelOptions")
    }

    @Test fun timeoutNeverAdmitsTuicV4TlsBypassOrOtherUnknownOptions() {
        val source = timeout(artifact(), "8000")
        rejectAll(source + "\n    token: synthetic-only", "NodeOptionToken")
        rejectAll(source.replace("    uuid: 22222222-2222-4222-8222-222222222222\n", ""))
        rejectAll(source + "\n    skip-cert-verify: true")
        rejectAll(source + "\n    disable-sni: true", "NodeOptionDisableSni")
        rejectAll(source + "\n    heartbeat-interval: 10001", "NodeOptionHeartbeatInterval")
        rejectAll(source + "\n    synthetic-unknown-option: true", "NodeOptions")
    }
}
