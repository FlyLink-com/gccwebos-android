package com.gccwebos.android.vpn

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Completely synthetic renderer -> Kotlin DTO -> shared Go/core golden contract. */
class ProtocolContractTest {
    private val positive = listOf("ss-minimal", "vless-reality", "hysteria2-minimal", "tuic-v5-minimal", "mixed", "vless-tls", "hysteria2-bounded-options", "tuic-v5-bounded-options", "ss-aes192")
    private fun resource(path: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/protocol-contract/$path")) { "Missing synthetic contract fixture" }.use { it.readBytes() }
    private fun yaml(name: String) = String(resource("renderer/$name.clash.yaml"), Charsets.UTF_8)
    private fun project(text: String, index: Int = 0) = JSONObject(String(SafeProxyConfig.project(text.toByteArray(), index)))
    private fun reject(text: String, index: Int = 0) {
        val error = assertThrows(UnsafeProxyConfiguration::class.java) { project(text, index) }
        assertEquals("Unsupported or unsafe proxy configuration", error.message)
        assertNull(error.cause)
    }
    // Android's compile-time JSONObject API lacks desktop org.json's similar().
    // Compare structure explicitly, ignoring object key order but preserving array order/types.
    private fun structure(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().toList().associateWith { structure(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { structure(value.get(it)) }
        JSONObject.NULL -> null
        else -> value
    }
    @Test fun backendRendererGoldensProduceExactVersionedContract() {
        val output = System.getProperty("protocolContractOutput")?.let(::File)?.also { it.mkdirs() }
        for (name in positive) for (format in listOf("clash", "mihomo")) for (index in 0 until if (name == "mixed") 4 else 1) {
            val input = resource("renderer/$name.$format.yaml")
            val actual = SafeProxyConfig.project(input, index)
            val filename = "$name.$format.$index.json"
            val expected = JSONObject(String(resource("accepted/$filename")))
            assertEquals("Golden contract mismatch: $filename", structure(expected), structure(JSONObject(String(actual))))
            // Native CI reads THESE actual bytes, not an older compiled bridge/APK or hand-written DTO.
            output?.let { File(it, filename).writeBytes(actual) }
            actual.fill(0); input.fill(0)
        }
    }
    @Test fun supportedMixedSubscriptionValidatesEveryNodeAndHonorsExplicitIndex() {
        val input = yaml("mixed")
        for ((i, type) in listOf("ss", "vless", "hysteria2", "tuic").withIndex()) {
            assertEquals(type, project(input, i).getJSONArray("proxies").getJSONObject(0).getString("type"))
        }
        reject(input.replace("type: tuic", "type: vmess"), 0)
        reject(input.replace("type: ss", "type: vmess"), 3)
    }
    @Test fun unsupportedRendererShapesAreNotFilteredOrCoerced() {
        for (name in listOf("vless-defaults", "hysteria2-options", "tuic-v5-options", "tuic-token-ambiguous", "ss-hostname")) {
            for (format in listOf("clash", "mihomo")) reject(String(resource("renderer/$name.$format.yaml")))
        }
        reject(yaml("provider-only"))
    }
    @Test fun rootActionsAndGlobalRoutesCannotReachNativeDto() {
        val result = project(String(resource("renderer/mixed.mihomo.yaml")), 1)
        assertEquals(setOf("schemaVersion", "proxies", "rules"), result.keys().asSequence().toSet())
        assertEquals(1, result.getInt("schemaVersion"))
        assertEquals("MATCH,GCC_SELECTED_PROXY", result.getJSONArray("rules").getString(0))
        for (secret in listOf("gstatic", "GLOBAL", "url-test", "store-selected", "mixed-port")) assertFalse(result.toString().contains(secret))
        for (field in listOf("proxy-providers", "rule-providers", "external-controller", "external-ui", "listeners", "tun", "script")) reject(yaml("ss-minimal") + "\n$field: {}\n")
        reject(yaml("ss-minimal") + "\nprofile: {store-selected: true, path: /tmp/unsafe}\n")
        reject(yaml("ss-minimal") + "\nprofile: {store-selected: 1}\n")
    }
    @Test fun ssCipherIpv4AndUdpSemanticsAgreeWithNative() {
        val source = yaml("ss-minimal")
        for (cipher in listOf("aes-128-gcm", "aes-192-gcm", "aes-256-gcm", "chacha20-ietf-poly1305"))
            assertEquals(cipher, project(source.replace("aes-128-gcm", cipher)).getJSONArray("proxies").getJSONObject(0).getString("cipher"))
        val udpFalse = source.replace("    type: ss", "    type: ss\n    udp: false")
        assertFalse(project(udpFalse).getJSONArray("proxies").getJSONObject(0).getBoolean("udp"))
        assertTrue(project(source).getJSONArray("proxies").getJSONObject(0).getBoolean("udp"))
        for (host in listOf("0.0.0.0", "224.0.0.1", "239.255.255.255", "::1", "192.000.2.10", "node.example.invalid")) reject(source.replace("192.0.2.10", host))
        for (value in listOf("null", "0", "\"false\"")) reject(source.replace("    type: ss", "    type: ss\n    udp: $value"))
    }
    @Test fun tlsVerificationCannotBeDisabledOrSilentlyOverridden() {
        for (name in listOf("vless-reality", "hysteria2-minimal", "tuic-v5-minimal")) {
            val source = yaml(name)
            val insecure = if (source.contains("skip-cert-verify: false")) source.replace("skip-cert-verify: false", "skip-cert-verify: true") else source.replace("    type:", "    skip-cert-verify: true\n    type:")
            reject(insecure)
            assertFalse(project(source).getJSONArray("proxies").getJSONObject(0).getBoolean("skip-cert-verify"))
        }
        reject(yaml("tuic-v5-minimal").replace("    type: tuic", "    type: tuic\n    disable-sni: true"))
        reject(yaml("vless-reality").replace("tls: true", "tls: false"))
    }
    @Test fun vlessRequiresExactTransportUuidFlowAndPacketEncoding() {
        val source = yaml("vless-reality")
        for (network in listOf("ws", "grpc", "h2", "xhttp", "udp")) reject(source.replace("network: \"tcp\"", "network: \"$network\""))
        for (flow in listOf("xtls-rprx-vision-udp443", "unsupported", "")) reject(source.replace("xtls-rprx-vision", flow))
        for (encoding in listOf("packet", "bogus", "")) reject(source.replace("\"xudp\"", "\"$encoding\""))
        reject(source.replace("udp: true", "udp: false"))
        for (uuid in listOf("invalid", "00000000-0000-0000-0000-000000000000", "11111111111141118111111111111111")) reject(source.replace("11111111-1111-4111-8111-111111111111", uuid))
        val tls = project(yaml("vless-tls")).getJSONArray("proxies").getJSONObject(0)
        assertFalse(tls.getBoolean("udp")); assertEquals("tcp", tls.getString("network"))
    }
    @Test fun partialOrMalformedRealityCannotDowngradeToOrdinaryTls() {
        val source = yaml("vless-reality")
        for (key in listOf("", "not-base64", "A".repeat(43), "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAF")) {
            if (key == "A".repeat(43)) continue // Canonical all-zero parse fixture is structurally valid.
            reject(source.replace("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE", key))
        }
        for (short in listOf("a", "0g", "1".repeat(18))) reject(source.replace("0123456789abcdef", short))
        reject(source.replace("    client-fingerprint: \"chrome\"\n", ""))
        reject(source.replace("      public-key: \"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE\"\n", ""))
        reject(source.replace("      short-id: \"0123456789abcdef\"\n", ""))
        reject(source.replace("client-fingerprint: \"chrome\"", "client-fingerprint: \"random\""))
    }
    @Test fun bandwidthHasExplicitBoundedDecimalMbpsAndObfsIsPaired() {
        val source = yaml("hysteria2-bounded-options")
        for (value in listOf("0 Mbps", "10001 Mbps", "1 mbps", "1 Gbps", "-1", "1.5 Mbps", "999999999999999999999999999999")) reject(source.replace("100 Mbps", value))
        assertEquals("100 Mbps", project(source.replace("100 Mbps", "100")).getJSONArray("proxies").getJSONObject(0).getString("up"))
        reject(source.replace("salamander", "unknown"))
        reject(source.replace("    obfs-password: \"synthetic-obfs-only\"\n", ""))
        reject(source.replace("    obfs: \"salamander\"\n", ""))
    }
    @Test fun tuicV5CannotSelectV4OrUnknownModes() {
        val source = yaml("tuic-v5-bounded-options")
        reject(source.replace("    type: tuic", "    type: tuic\n    token: synthetic-only"))
        reject(source.replace("udp-relay-mode: \"quic\"", "udp-relay-mode: \"fallback\""))
        reject(source.replace("congestion-controller: \"bbr\"", "congestion-controller: \"unknown\""))
        for (key in listOf("request-timeout", "heartbeat-interval", "max-open-streams", "ports", "hop-interval", "fast-open", "reduce-rtt")) reject(source.replace("    type: tuic", "    type: tuic\n    $key: 1"))
    }
    @Test fun tlsNamesAlpnAndCrossProtocolFieldsAreStrict() {
        for (name in listOf("vless-reality", "hysteria2-minimal", "tuic-v5-minimal")) {
            for (sni in listOf("", "https://fixture.invalid", "*.fixture.invalid", "x..invalid", "x.invalid.", "a".repeat(64) + ".invalid", "\u00e9.invalid")) reject(yaml(name).replace("fixture.example.invalid", sni))
        }
        for (alpn in listOf("[]", "[h3,h3]", "[null]", "[1]", "[\"\"]", "[a,b,c,d,e]", "[\"with space\"]")) reject(yaml("hysteria2-minimal").replace("    alpn:\n      - \"h3\"", "    alpn: $alpn"))
        reject(yaml("hysteria2-minimal").replace("    type: hysteria2", "    type: hysteria2\n    udp: false"))
        reject(yaml("tuic-v5-minimal").replace("    type: tuic", "    type: tuic\n    cipher: aes-128-gcm"))
    }
    @Test fun escapedUnicodeCannotSilentlyRewriteCredentials() {
        val source = yaml("ss-minimal")
        for (escape in listOf("\\uD800", "\\uDC00", "\\uD800x", "\\uD800\\u0041")) reject(source.replace("synthetic-only", escape))
        val pair = project(source.replace("synthetic-only", "\\uD800\\uDC00")).getJSONArray("proxies").getJSONObject(0).getString("password")
        assertEquals(String(Character.toChars(0x10000)), pair)
        reject(source + "\ndns: {nameserver: [\"\\uD800\"]}\n")
    }
    @Test fun yamlHostilityAndSecretsStayBounded() {
        val source = yaml("ss-minimal")
        reject(source.replace("synthetic-only", "\u0000"))
        reject(source.replace("synthetic-only", "\u00e9".repeat(513)))
        reject(source + "\n---\nproxies: []\n")
        reject(source + "\nproxies: []\n")
        reject(source.replace("\"synthetic-only\"", "&pw \"synthetic-only\""))
        reject(source.replace("\"synthetic-only\"", "!!str \"synthetic-only\""))
        reject(source.replace("\"synthetic-only\"", "*missing"))
        reject(source + "\ndns: " + "[".repeat(20) + "0" + "]".repeat(20))
    }
}
