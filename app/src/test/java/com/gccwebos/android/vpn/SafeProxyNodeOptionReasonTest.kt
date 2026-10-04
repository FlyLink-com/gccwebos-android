package com.gccwebos.android.vpn

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Fixed public option categories only. Every artifact and marker is synthetic. */
class SafeProxyNodeOptionReasonTest {
    private fun artifact(type: String = "hysteria2"): String = """
        proxies:
          - name: synthetic option fixture
            type: $type
            server: 192.0.2.10
            port: 443
    """.trimIndent() + when (type) {
        "ss" -> "\n    cipher: aes-128-gcm\n    password: synthetic-only"
        "vless" -> "\n    uuid: 11111111-1111-4111-8111-111111111111\n    tls: true\n    servername: tls.fixture.invalid"
        "hysteria2" -> "\n    password: synthetic-only\n    sni: tls.fixture.invalid"
        "tuic" -> "\n    uuid: 11111111-1111-4111-8111-111111111111\n    password: synthetic-only\n    sni: tls.fixture.invalid"
        else -> error("Synthetic protocol fixture required")
    }

    private fun extra(source: String, key: String, value: String = "true") = source + "\n    $key: $value"
    private fun reject(expected: String, source: String): UnsafeProxyConfiguration {
        val failure = assertThrows(UnsafeProxyConfiguration::class.java) { SafeProxyConfig.project(source.toByteArray(), 0) }
        assertEquals(expected, failure.reason.name)
        assertEquals("Unsupported or unsafe proxy configuration", failure.message)
        assertNull(failure.cause)
        return failure
    }

    @Test fun fixedPublicKeysReceiveOnlyPredefinedCategoriesWithoutBecomingAccepted() {
        // Fifteen public keys plus an unknown key: sixteen independent synthetic fixtures.
        val fixtures = listOf(
            "udp" to "NodeOptionUdp", "tfo" to "NodeOptionTfo", "ip-version" to "NodeOptionIpVersion",
            "ports" to "NodeOptionPorts", "fingerprint" to "NodeOptionFingerprint",
            "hop-interval" to "NodeOptionHopInterval", "bbr-profile" to "NodeOptionBbrProfile",
            "token" to "NodeOptionToken", "disable-sni" to "NodeOptionDisableSni",
            "reduce-rtt" to "NodeOptionReduceRtt", "request-timeout" to "NodeOptionRequestTimeout",
            "heartbeat-interval" to "NodeOptionHeartbeatInterval",
            "max-udp-relay-packet-size" to "NodeOptionMaxUdpRelayPacketSize",
            "fast-open" to "NodeOptionFastOpen", "max-open-streams" to "NodeOptionMaxOpenStreams",
            "synthetic-unknown" to "NodeOptions")
        for ((key, reason) in fixtures) {
            // bbr-profile is now supported only on HY2/TUIC; its SS cross-field rejection stays diagnostic.
            val source = extra(artifact(if (key == "bbr-profile") "ss" else "hysteria2"), key)
            reject(reason, source)
            assertFalse("A diagnostic must not authorize an unsupported option", SafeProxyConfig.inspect(source.toByteArray()).single().supported)
        }
    }

    @Test fun udpOnHysteria2AndTuicIsRejectedForEveryValueWithoutReadingIt() {
        for (type in listOf("hysteria2", "tuic")) for (value in listOf("true", "false", "null", "1", "\"true\"", "[true]")) {
            reject("NodeOptionUdp", extra(artifact(type), "udp", value))
        }
    }

    @Test fun tfoRemainsRejectedAcrossAllFourProtocolsIncludingFalse() {
        for (type in listOf("ss", "vless", "hysteria2", "tuic")) for (value in listOf("false", "true", "null", "\"false\"")) {
            reject("NodeOptionTfo", extra(artifact(type), "tfo", value))
        }
    }

    @Test fun ipVersionPortsAndFingerprintDoNotValidateOrExposeTheirValues() {
        for ((key, reason) in listOf("ip-version" to "NodeOptionIpVersion", "ports" to "NodeOptionPorts", "fingerprint" to "NodeOptionFingerprint")) {
            for (value in listOf("false", "null", "[1, 2]", "synthetic-private-value")) reject(reason, extra(artifact(), key, value))
        }
    }

    @Test fun knownExtrasUseFixedAuthenticationTlsAndPortsPriorityIndependentOfYamlOrder() {
        val pairs = listOf("tfo", "ports", "fingerprint", "disable-sni", "token")
        for (keys in listOf(pairs, pairs.reversed(), listOf("ports", "token", "tfo", "disable-sni", "fingerprint"))) {
            reject("NodeOptionToken", keys.fold(artifact()) { source, key -> extra(source, key) })
        }
        reject("NodeOptionDisableSni", extra(extra(extra(artifact(), "tfo"), "ports"), "disable-sni"))
        reject("NodeOptionFingerprint", extra(extra(extra(artifact(), "udp"), "ports"), "fingerprint"))
        reject("NodeOptionPorts", extra(extra(artifact(), "udp"), "ports"))
    }

    @Test fun anyUnknownExtraKeepsTheWholeFailureGenericInEitherOrder() {
        val unknown = "synthetic-sensitive-option"
        for (keys in listOf(listOf("udp", unknown), listOf(unknown, "udp"), listOf("token", "tfo", unknown))) {
            reject("NodeOptions", keys.fold(artifact()) { source, key -> extra(source, key, "synthetic-private-value") })
        }
    }

    @Test fun nonStringExtraKeysStayGenericEvenWhenKnownExtrasArePresent() {
        for (key in listOf("7", "true", "null")) {
            reject("NodeOptions", extra(extra(artifact(), "token"), key))
        }
    }

    @Test fun casingAliasesAndClientFingerprintNeverMasqueradeAsCertificateFingerprint() {
        for (key in listOf("UDP", "TFO", "ip_version", "Fingerprint", "client-fingerprint", "\"fingerprint \"")) {
            reject("NodeOptions", extra(artifact(), key))
        }
        val vless = extra(artifact("vless"), "client-fingerprint", "chrome")
        val projected = JSONObject(String(SafeProxyConfig.project(vless.toByteArray(), 0), Charsets.UTF_8))
        assertEquals("chrome", projected.getJSONArray("proxies").getJSONObject(0).getString("client-fingerprint"))
        reject("ProtocolOptions", vless.replace("client-fingerprint: chrome", "client-fingerprint: synthetic-invalid"))
    }

    @Test fun missingAndInvalidNamesRemainGenericNodeOptions() {
        val source = artifact()
        for (input in listOf(source.replace("  - name: synthetic option fixture\n    type:", "  - type:"),
            source.replace("name: synthetic option fixture", "name: 7"),
            source.replace("name: synthetic option fixture", "name: \"\""),
            source.replace("name: synthetic option fixture", "name: \"${"a".repeat(129)}\""))) {
            reject("NodeOptions", input)
        }
    }

    @Test fun rootAndNestedFailuresKeepTheirOriginalReasons() {
        reject("TopLevelOptions", artifact() + "\ntfo: false")
        reject("TopLevelOptions", artifact() + "\nprofile: {ports: true}")
        val reality = extra(extra(artifact("vless"), "client-fingerprint", "chrome"),
            "reality-opts", "{tfo: false}")
        reject("ProtocolOptions", reality)
    }

    @Test fun acceptedUdpFieldsKeepDefaultsAndInvalidValuesKeepProtocolOptionsReason() {
        for ((type, default) in listOf("ss" to true, "vless" to false)) {
            val source = artifact(type)
            val normal = JSONObject(String(SafeProxyConfig.project(source.toByteArray(), 0), Charsets.UTF_8))
            assertEquals(default, normal.getJSONArray("proxies").getJSONObject(0).getBoolean("udp"))
            for (value in listOf("null", "1", "\"false\"")) reject("ProtocolOptions", extra(source, "udp", value))
        }
    }

    @Test fun unsupportedUnselectedKnownKeyStillBlocksEveryPreparationEntryPoint() {
        val source = artifact("ss") + "\n" + extra(artifact(), "token").substringAfter("proxies:\n")
        val bytes = source.toByteArray()
        reject("NodeOptionToken", source)
        for (action in listOf<() -> Any>({ SafeProxyConfig.validateForPreparation(bytes) },
            { SafeProxyConfig.selectedEndpoint(bytes, 0) }, { SafeProxyConfig.projectResolved(bytes, 0, "203.0.113.10") })) {
            val failure = assertThrows(UnsafeProxyConfiguration::class.java) { action() }
            assertEquals("NodeOptionToken", failure.reason.name)
        }
        val summaries = SafeProxyConfig.inspect(bytes)
        assertTrue(summaries[0].supported)
        assertFalse(summaries[1].supported)
    }

    @Test fun knownAndUnknownDiagnosticsNeverRetainRawKeyValueOrName() {
        val privateKey = "synthetic-sensitive-option"
        val privateValue = "synthetic-private-value"
        val privateName = "synthetic-private-name"
        for (key in listOf("token", privateKey)) {
            val source = extra(artifact().replace("synthetic option fixture", privateName), key, privateValue)
            val failure = reject(if (key == "token") "NodeOptionToken" else "NodeOptions", source)
            for (marker in listOf(privateKey, privateValue, privateName)) assertFalse(failure.toString().contains(marker))
        }
    }

    @Test fun validLiteralProjectionBytesAndResolvedProjectionStayIdentical() {
        for (type in listOf("ss", "vless", "hysteria2", "tuic")) {
            val bytes = artifact(type).toByteArray()
            val strict = SafeProxyConfig.project(bytes, 0)
            val resolved = SafeProxyConfig.projectResolved(bytes, 0, "192.0.2.10")
            assertTrue("Diagnostic-only changes must preserve accepted DTO bytes", strict.contentEquals(resolved))
            assertTrue(SafeProxyConfig.validateForPreparation(bytes).single().supported)
        }
    }
}
