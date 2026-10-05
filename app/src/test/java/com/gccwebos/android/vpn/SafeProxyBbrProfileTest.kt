package com.gccwebos.android.vpn

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic BBR-only boundaries; no endpoint is contacted and no customer artifact is read. */
class SafeProxyBbrProfileTest {
    private val profiles = listOf("conservative", "standard", "aggressive")
    private fun artifact(type: String, endpoint: String = "192.0.2.10") = """
        proxies:
          - name: synthetic BBR fixture
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
    private fun withProfile(source: String, value: String) = source + "\n    bbr-profile: $value"
    private fun proxy(bytes: ByteArray) = JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("proxies").getJSONObject(0)
    private fun rejected(source: String, reason: String = "ProtocolOptions") {
        val failure = assertThrows(UnsafeProxyConfiguration::class.java) { SafeProxyConfig.project(source.toByteArray(), 0) }
        assertEquals(reason, failure.reason.name)
        assertEquals("Unsupported or unsafe proxy configuration", failure.message)
        assertNull(failure.cause)
        assertFalse(SafeProxyConfig.inspect(source.toByteArray()).single().supported)
    }

    @Test fun allThreeExactProfilesArePreservedForBothProtocols() {
        for (type in listOf("hysteria2", "tuic")) for (profile in profiles) {
            val source = withProfile(artifact(type), "\"$profile\"").toByteArray()
            assertEquals(profile, proxy(SafeProxyConfig.project(source, 0)).getString("bbr-profile"))
            assertTrue(SafeProxyConfig.inspect(source).single().supported)
            assertTrue(SafeProxyConfig.validateForPreparation(source).single().supported)
            assertEquals("192.0.2.10", SafeProxyConfig.selectedEndpoint(source, 0))
        }
    }

    @Test fun absentProfileRemainsAbsentAndDoesNotCreateTuningDefaults() {
        for (type in listOf("hysteria2", "tuic")) {
            val projected = proxy(SafeProxyConfig.project(artifact(type).toByteArray(), 0))
            assertFalse(projected.has("bbr-profile"))
            assertFalse(projected.has("congestion-controller"))
            assertFalse(projected.getBoolean("skip-cert-verify"))
        }
    }

    @Test fun tuicCongestionControllerIsNeitherAddedNorRewritten() {
        for (controller in listOf<String?>(null, "bbr", "cubic", "new_reno")) for (profile in profiles) {
            val source = artifact("tuic") + (controller?.let { "\n    congestion-controller: $it" } ?: "")
            val projected = proxy(SafeProxyConfig.project(withProfile(source, profile).toByteArray(), 0))
            assertEquals(profile, projected.getString("bbr-profile"))
            if (controller == null) assertFalse(projected.has("congestion-controller"))
            else assertEquals(controller, projected.getString("congestion-controller"))
        }
    }

    @Test fun nullEmptyCaseUnknownAndMistypedProfilesRemainRejected() {
        for (type in listOf("hysteria2", "tuic")) for (value in listOf("null", "\"\"", "\" standard\"", "\"standard \"",
            "STANDARD", "Standard", "synthetic-unknown", "true", "1", "1.5", "[standard]", "{value: standard}")) {
            rejected(withProfile(artifact(type), value))
        }
    }

    @Test fun ssAndVlessStillRejectTheBbrCrossProtocolField() {
        for (type in listOf("ss", "vless")) for (profile in profiles) {
            rejected(withProfile(artifact(type), profile), "NodeOptionBbrProfile")
        }
    }

    @Test fun hostnamePreparationAndResolvedProjectionPreserveProfileAndTlsIdentity() {
        for (type in listOf("hysteria2", "tuic")) for (profile in profiles) {
            val source = withProfile(artifact(type, "endpoint.fixture.invalid"), profile).toByteArray()
            assertTrue(SafeProxyConfig.validateForPreparation(source).single().supported)
            assertEquals("endpoint.fixture.invalid", SafeProxyConfig.selectedEndpoint(source, 0))
            val projected = proxy(SafeProxyConfig.projectResolved(source, 0, "203.0.113.10"))
            assertEquals("203.0.113.10", projected.getString("server"))
            assertEquals("tls.fixture.invalid", projected.getString("sni"))
            assertEquals(profile, projected.getString("bbr-profile"))
            assertFalse(projected.getBoolean("skip-cert-verify"))
            assertFalse(projected.has("fingerprint"))
        }
    }

    @Test fun invalidUnselectedProfileBlocksAllPreparationAndProjectionEntryPoints() {
        val source = artifact("ss") + "\n" + withProfile(artifact("hysteria2"), "synthetic-unknown").substringAfter("proxies:\n")
        val bytes = source.toByteArray()
        for (action in listOf<() -> Any>({ SafeProxyConfig.project(bytes, 0) },
            { SafeProxyConfig.validateForPreparation(bytes) }, { SafeProxyConfig.selectedEndpoint(bytes, 0) },
            { SafeProxyConfig.projectResolved(bytes, 0, "203.0.113.10") })) {
            val failure = assertThrows(UnsafeProxyConfiguration::class.java) { action() }
            assertEquals("ProtocolOptions", failure.reason.name)
            assertNull(failure.cause)
        }
        assertTrue(SafeProxyConfig.inspect(bytes)[0].supported)
        assertFalse(SafeProxyConfig.inspect(bytes)[1].supported)
    }

    @Test fun bbrNeverAdmitsAnotherNodeOptionOrTlsBypass() {
        for (type in listOf("hysteria2", "tuic")) {
            val source = withProfile(artifact(type), "standard")
            rejected(source + "\n    synthetic-unknown-option: true", "NodeOptions")
            rejected(source + "\n    fingerprint: synthetic-only", "NodeOptionFingerprint")
            rejected(source + "\n    ports: 443", "NodeOptionPorts")
            rejected(source + "\n    token: synthetic-only", "NodeOptionToken")
            rejected(source + "\n    skip-cert-verify: true")
        }
    }
}
