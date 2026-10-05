package com.gccwebos.android.vpn

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic default-equivalent inputs only; no TUIC tuning or new native DTO fields. */
class TuicDefaultOptionsTest {
    private val booleans = linkedMapOf(
        "disable-sni" to "NodeOptionDisableSni",
        "reduce-rtt" to "NodeOptionReduceRtt",
        "fast-open" to "NodeOptionFastOpen",
    )
    private val integers = linkedMapOf(
        "heartbeat-interval" to Pair(10000, "NodeOptionHeartbeatInterval"),
        "max-udp-relay-packet-size" to Pair(1252, "NodeOptionMaxUdpRelayPacketSize"),
        "max-open-streams" to Pair(100, "NodeOptionMaxOpenStreams"),
    )
    private fun artifact(type: String = "tuic", endpoint: String = "192.0.2.10") = """
        proxies:
          - name: synthetic default options fixture
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
    private fun option(source: String, key: String, value: String) = source + "\n    $key: $value"
    private fun combined(source: String = artifact(), zero: Boolean = false): String {
        var result = source
        for (key in booleans.keys) result = option(result, key, "false")
        for ((key, expected) in integers) result = option(result, key, if (zero) "0" else expected.first.toString())
        return result
    }
    private fun proxy(bytes: ByteArray) = JSONObject(String(bytes, Charsets.UTF_8))
        .getJSONArray("proxies").getJSONObject(0)
    private fun assertOmitted(bytes: ByteArray) {
        val projected = proxy(bytes)
        for (key in booleans.keys + integers.keys) assertFalse("Default-only options must be omitted", projected.has(key))
    }
    private fun equivalent(source: String, baseline: String = artifact()) {
        val bytes = source.toByteArray()
        assertTrue(SafeProxyConfig.inspect(bytes).single().supported)
        assertTrue(SafeProxyConfig.validateForPreparation(bytes).single().supported)
        assertEquals("192.0.2.10", SafeProxyConfig.selectedEndpoint(bytes, 0))
        val actual = SafeProxyConfig.project(bytes, 0)
        assertArrayEquals(SafeProxyConfig.project(baseline.toByteArray(), 0), actual)
        assertOmitted(actual)
        val resolved = SafeProxyConfig.projectResolved(bytes, 0, "203.0.113.10")
        assertArrayEquals(SafeProxyConfig.projectResolved(baseline.toByteArray(), 0, "203.0.113.10"), resolved)
        assertOmitted(resolved)
    }
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

    @Test fun falseDisableSniIsOmittedWithTlsVerificationPreserved() {
        val source = option(artifact(), "disable-sni", "false")
        equivalent(source)
        val projected = proxy(SafeProxyConfig.project(source.toByteArray(), 0))
        assertEquals("tls.fixture.invalid", projected.getString("sni"))
        assertFalse(projected.getBoolean("skip-cert-verify"))
    }
    @Test fun falseReduceRttIsOmittedWithoutCreatingEarlyDataOption() {
        equivalent(option(artifact(), "reduce-rtt", "false"))
    }
    @Test fun falseFastOpenIsOmittedWithoutCreatingNativeOption() {
        equivalent(option(artifact(), "fast-open", "false"))
    }
    @Test fun heartbeatZeroAndExactDefaultAreOmitted() {
        for (value in listOf("0", "10000")) equivalent(option(artifact(), "heartbeat-interval", value))
    }
    @Test fun udpPacketSizeZeroAndExactDefaultAreOmitted() {
        for (value in listOf("0", "1252")) equivalent(option(artifact(), "max-udp-relay-packet-size", value))
    }
    @Test fun streamLimitZeroAndExactDefaultAreOmitted() {
        for (value in listOf("0", "100")) equivalent(option(artifact(), "max-open-streams", value))
    }
    @Test fun completeRendererDefaultTupleIsByteIdenticalToMissingOptions() {
        equivalent(combined())
    }
    @Test fun completeZeroTupleIsByteIdenticalToMissingOptions() {
        equivalent(combined(zero = true))
    }
    @Test fun missingOptionsDoNotIntroduceDtoDefaults() {
        val projected = SafeProxyConfig.project(artifact().toByteArray(), 0)
        assertOmitted(projected)
        assertFalse(proxy(projected).has("congestion-controller"))
        assertFalse(proxy(projected).has("request-timeout"))
        assertFalse(proxy(projected).has("bbr-profile"))
    }
    @Test fun booleansRejectTrueAndEveryOtherScalarOrShapeWithFixedCategory() {
        for ((key, reason) in booleans) for (value in listOf("true", "null", "\"false\"", "\"true\"",
            "0", "1", "0.0", "FALSE", "False", "off", "no", "[]", "{}")) {
            val source = option(artifact(), key, value)
            rejectAll(source, reason)
            assertFalse(SafeProxyConfig.inspect(source.toByteArray()).single().supported)
        }
    }
    @Test fun integersRejectNegativeNonDefaultAndOverflowWithFixedCategory() {
        for ((key, expected) in integers) for (value in listOf("-1", "1", (expected.first - 1).toString(),
            (expected.first + 1).toString(), "2147483647", "2147483648", "9223372036854775807",
            "999999999999999999999999999999999999")) {
            val source = option(artifact(), key, value)
            rejectAll(source, expected.second)
            assertFalse(SafeProxyConfig.inspect(source.toByteArray()).single().supported)
        }
    }
    @Test fun integersRejectTypeCoercionAndShapesWithFixedCategory() {
        for ((key, expected) in integers) for (value in listOf("null", "\"0\"", "\"${expected.first}\"",
            "true", "false", "0.0", "${expected.first}.0", "1e2", "+0", "00", "0x0", "0o0",
            "1_000", ".inf", ".NaN", "[]", "{}")) rejectAll(option(artifact(), key, value), expected.second)
    }
    @Test fun aliasesDuplicatesAnchorsAndNestedKeysRemainRejected() {
        for (key in booleans.keys + integers.keys) {
            val value = if (key in booleans) "false" else "0"
            rejectAll(option(option(artifact(), key, value), key, value), "InputSyntax")
            for (alias in listOf(key.uppercase(), key.replace('-', '_'), "\"$key \""))
                rejectAll(option(artifact(), alias, value), "NodeOptions")
            rejectAll(option(artifact(), key, "&default $value"), "InputSyntax")
            rejectAll(option(artifact(), key, "*missing"), "InputSyntax")
            rejectAll(artifact() + "\nprofile: {$key: $value}", "TopLevelOptions")
        }
    }
    @Test fun defaultValuesOnSsVlessAndHysteria2RemainUnsupported() {
        for (type in listOf("ss", "vless", "hysteria2")) {
            for ((key, reason) in booleans) rejectAll(option(artifact(type), key, "false"), reason)
            for ((key, expected) in integers) for (value in listOf("0", expected.first.toString()))
                rejectAll(option(artifact(type), key, value), expected.second)
        }
    }
    @Test fun unsafeUnselectedTuicCannotBeDiscardedByAnyAdmissionEntryPoint() {
        val unsafe = booleans.map { Triple(it.key, "true", it.value) } +
            integers.map { Triple(it.key, (it.value.first + 1).toString(), it.value.second) }
        for ((key, value, reason) in unsafe) {
            val source = artifact("ss") + "\n" + option(artifact(), key, value).substringAfter("proxies:\n")
            rejectAll(source, reason)
            val summaries = SafeProxyConfig.inspect(source.toByteArray())
            assertTrue(summaries[0].supported)
            assertFalse(summaries[1].supported)
        }
    }
    @Test fun defaultsCannotReplaceSniUuidTlsVerificationOrV5Authority() {
        val source = combined()
        rejectAll(source.replace("    sni: tls.fixture.invalid", "    sni: null"))
        rejectAll(source.replace("\n    sni: tls.fixture.invalid", ""))
        rejectAll(source.replace("\n    uuid: 22222222-2222-4222-8222-222222222222", ""))
        rejectAll(source + "\n    skip-cert-verify: true")
        rejectAll(source + "\n    token: synthetic-only", "NodeOptionToken")
        rejectAll(source + "\n    synthetic-unknown-option: true", "NodeOptions")
        rejectAll(source.replace("disable-sni: false", "disable-sni: true") + "\n    skip-cert-verify: false", "NodeOptionDisableSni")
    }
    @Test fun resolvedHostnameDefaultsDoNotChangeIndependentTlsIdentity() {
        val baseline = artifact(endpoint = "endpoint.fixture.invalid")
        for (source in listOf(combined(baseline), combined(baseline, zero = true))) {
            val bytes = source.toByteArray()
            assertTrue(SafeProxyConfig.validateForPreparation(bytes).single().supported)
            assertEquals("endpoint.fixture.invalid", SafeProxyConfig.selectedEndpoint(bytes, 0))
            val actual = SafeProxyConfig.projectResolved(bytes, 0, "203.0.113.10")
            assertArrayEquals(SafeProxyConfig.projectResolved(baseline.toByteArray(), 0, "203.0.113.10"), actual)
            assertEquals("tls.fixture.invalid", proxy(actual).getString("sni"))
            assertFalse(proxy(actual).getBoolean("skip-cert-verify"))
            assertOmitted(actual)
            assertThrows(UnsafeProxyConfiguration::class.java) { SafeProxyConfig.project(bytes, 0) }
        }
    }
    @Test fun compatibilityPreservesRequestTimeoutBbrControllerAndRelayFields() {
        val baseline = artifact() + "\n    request-timeout: 8000\n    bbr-profile: standard" +
            "\n    congestion-controller: cubic\n    udp-relay-mode: quic\n    alpn: [h3]"
        for (source in listOf(combined(baseline), combined(baseline, zero = true))) equivalent(source, baseline)
        val projected = proxy(SafeProxyConfig.project(combined(baseline).toByteArray(), 0))
        assertEquals(8000, projected.getInt("request-timeout"))
        assertEquals("standard", projected.getString("bbr-profile"))
        assertEquals("cubic", projected.getString("congestion-controller"))
        assertEquals("quic", projected.getString("udp-relay-mode"))
        assertEquals("h3", projected.getJSONArray("alpn").getString(0))
    }
    @Test fun rejectionNeverExposesSyntheticValuesOrCauses() {
        for ((key, reason) in booleans) rejectAll(option(artifact(), key, "\"synthetic-diagnostic-marker\""), reason)
        for ((key, expected) in integers) rejectAll(option(artifact(), key, "\"synthetic-diagnostic-marker\""), expected.second)
    }
}
