package com.gccwebos.android.vpn

import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic parser tests only. Resolving an endpoint and authorizing a lease are external steps. */
class SafeProxyPreparationTest {
    private val ss = """
        proxies:
          - name: synthetic literal
            type: ss
            server: 192.0.2.10
            port: 18443
            cipher: aes-128-gcm
            password: synthetic-unselected-secret
    """.trimIndent()
    private val vless = """
        proxies:
          - name: synthetic hostname
            type: vless
            server: edge.fixture.invalid
            port: 18443
            uuid: 11111111-1111-4111-8111-111111111111
            tls: true
            servername: tls.fixture.invalid
    """.trimIndent()
    private val hy2 = """
        proxies:
          - name: synthetic hostname
            type: hysteria2
            server: edge.fixture.invalid
            port: 18443
            password: synthetic-selected-secret
            sni: tls.fixture.invalid
            alpn: [h3]
    """.trimIndent()

    @Test fun preparationAcceptsHostnameAndLiteralWithoutChangingLegacyAdmission() {
        val bytes = mixed(ss, hy2).toByteArray()
        val nodes = SafeProxyConfig.validateForPreparation(bytes)
        assertEquals(2, nodes.size)
        assertTrue(nodes.all { it.supported })
        assertEquals(listOf("ss", "hysteria2"), nodes.map { it.protocol })
        assertTrue(SafeProxyConfig.selectedEndpoint(bytes, 0) == "192.0.2.10")
        assertTrue(SafeProxyConfig.selectedEndpoint(bytes, 1) == "edge.fixture.invalid")
        assertFalse(SafeProxyConfig.inspect(bytes)[1].supported)
        rejected { SafeProxyConfig.project(bytes, 0) }
    }

    @Test fun resolvedProjectionPreservesTlsIdentityAndProtocolOptions() {
        for ((input, field) in listOf(vless to "servername", hy2 to "sni",
            (hy2.replace("type: hysteria2", "type: tuic") + "\n    uuid: 22222222-2222-4222-8222-222222222222") to "sni")) {
            val literal = input.replace("edge.fixture.invalid", "203.0.113.10")
            val expected = SafeProxyConfig.project(literal.toByteArray(), 0)
            val actual = SafeProxyConfig.projectResolved(input.toByteArray(), 0, "203.0.113.10")
            assertTrue("Resolved projection changed protocol fields", expected.contentEquals(actual))
            val proxy = JSONObject(String(actual, Charsets.UTF_8)).getJSONArray("proxies").getJSONObject(0)
            assertTrue(proxy.getString("server") == "203.0.113.10")
            assertTrue(proxy.getString(field) == "tls.fixture.invalid")
            assertFalse(proxy.getBoolean("skip-cert-verify"))
        }
    }

    @Test fun sameHostnameInSniRemainsTlsIdentityAfterServerResolution() {
        val input = hy2.replace("tls.fixture.invalid", "edge.fixture.invalid")
        val proxy = JSONObject(String(SafeProxyConfig.projectResolved(input.toByteArray(), 0, "203.0.113.10"),
            Charsets.UTF_8)).getJSONArray("proxies").getJSONObject(0)
        assertTrue(proxy.getString("server") == "203.0.113.10")
        assertTrue(proxy.getString("sni") == "edge.fixture.invalid")
    }

    @Test fun everyLiteralGoldenKeepsNativeVersionOneProjectionBytes() {
        val names = listOf("ss-minimal", "vless-reality", "hysteria2-minimal", "tuic-v5-minimal", "mixed",
            "vless-tls", "hysteria2-bounded-options", "tuic-v5-bounded-options", "ss-aes192")
        for (name in names) for (format in listOf("clash", "mihomo")) {
            val bytes = requireNotNull(javaClass.getResourceAsStream("/protocol-contract/renderer/$name.$format.yaml"))
                .use { it.readBytes() }
            val nodes = SafeProxyConfig.validateForPreparation(bytes)
            assertTrue(nodes.all { it.supported })
            for (index in nodes.indices) {
                val endpoint = SafeProxyConfig.selectedEndpoint(bytes, index)
                assertTrue("Literal golden projection changed", SafeProxyConfig.project(bytes, index)
                    .contentEquals(SafeProxyConfig.projectResolved(bytes, index, endpoint)))
            }
        }
    }

    @Test fun unsupportedUnselectedNodeRejectsAllPreparationEntryPoints() {
        for (bad in listOf(hy2.replace("type: hysteria2", "type: synthetic-unknown"),
            hy2 + "\n    hop-interval: 10", hy2.replace("port: 18443", "port: 0"),
            hy2 + "\n    skip-cert-verify: true")) {
            rejectedEverywhere(mixed(ss, bad), 0)
        }
    }

    @Test fun invalidProtocolOptionsAndUnknownRootAreNeverHiddenByResolution() {
        for (input in listOf(vless.replace("tls: true", "tls: false"),
            vless.replace("tls: true", "tls: true\n    network: ws"),
            hy2.replace("synthetic-selected-secret", "\"\""), hy2 + "\nsynthetic-extra-root: {}")) {
            rejectedEverywhere(input, 0)
        }
    }

    @Test fun numericAliasesIpv6AndMalformedHostnamesRemainRejected() {
        val endpoints = listOf("0.0.0.0", "224.0.0.1", "01.2.3.4", "256.1.1.1", "127.1",
            "2130706433", "0177.0.0.1", "0x7f000001", "0x7f.1", "0x7f.0.1", "0x7f.0.0.1",
            "::1", "::ffff:192.0.2.1", "[2001:db8::1]", "", "host..invalid", "host.invalid.",
            "-host.invalid", "host-.invalid", "host_name.invalid", "host\u00e9.invalid", "host.invalid:443",
            "https://host.invalid", "host.invalid/path", "host\t.invalid", "host\n.invalid",
            "a".repeat(64) + ".invalid")
        for (value in endpoints) rejectedEverywhere(endpoint(value), 0)
    }

    @Test fun hostnameBoundariesUseExistingAsciiShape() {
        val maximum = "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61)
        for (host in listOf("synthetic", "node1.fixture.invalid", "NODE.fixture.invalid", maximum)) {
            assertTrue(SafeProxyConfig.validateForPreparation(endpoint(host).toByteArray()).single().supported)
        }
        rejectedEverywhere(endpoint(maximum + "a"), 0)
    }

    @Test fun resolvedAddressMustAgainBeStrictCanonicalIpv4() {
        for (address in listOf("edge.fixture.invalid", "", "0.0.0.0", "224.0.0.1", "192.000.2.1",
            "256.1.1.1", "127.1", "0x7f000001", "0177.0.0.1", "::1", "::ffff:192.0.2.1",
            "203.0.113.10:443", "203.0.113.10/path", "203.0.113.10\u0000")) {
            rejected { SafeProxyConfig.projectResolved(hy2.toByteArray(), 0, address) }
        }
    }

    @Test fun missingOrNonStringServerAndControlCharacterMetadataStayRejected() {
        for (input in listOf(hy2.replace("server: edge.fixture.invalid", "server: null"),
            hy2.replace("server: edge.fixture.invalid", "server: 7"),
            hy2.replace("    server: edge.fixture.invalid\n", ""),
            hy2.replace("name: synthetic hostname", "name: \"synthetic\\tname\""))) {
            rejectedEverywhere(input, 0)
        }
    }

    @Test fun selectionIsCheckedAfterEveryNodeHasBeenValidated() {
        for (index in listOf(-1, 1)) {
            assertEquals(SafeProxyConfig.RejectionReason.Selection,
                rejected { SafeProxyConfig.selectedEndpoint(hy2.toByteArray(), index) }.reason)
            assertEquals(SafeProxyConfig.RejectionReason.Selection,
                rejected { SafeProxyConfig.projectResolved(hy2.toByteArray(), index, "203.0.113.10") }.reason)
        }
        val invalid = mixed(ss, hy2.replace("type: hysteria2", "type: synthetic-unknown")).toByteArray()
        assertEquals(SafeProxyConfig.RejectionReason.Protocol,
            rejected { SafeProxyConfig.selectedEndpoint(invalid, 99) }.reason)
    }

    @Test fun resolvedProjectionIsSelectedOnlyAndDoesNotRewriteRawIdentity() {
        val input = (mixed(ss, hy2) + "\ndns: {enable: true}\nrules: [MATCH,DIRECT]").toByteArray()
        val original = input.copyOf()
        val before = MessageDigest.getInstance("SHA-256").digest(input)
        for (address in listOf("203.0.113.10", "203.0.113.11")) {
            val output = SafeProxyConfig.projectResolved(input, 1, address)
            val text = String(output, Charsets.UTF_8)
            val dto = JSONObject(text)
            assertEquals(2, dto.getInt("schemaVersion"))
            assertEquals("global", dto.getString("routingMode"))
            assertEquals(1, dto.getJSONArray("proxies").length())
            assertTrue(dto.getJSONArray("rules").getString(0) == "MATCH,GCC_SELECTED_PROXY")
            assertFalse(text.contains("edge.fixture.invalid"))
            assertFalse(text.contains("synthetic-unselected-secret"))
            assertFalse(text.contains("192.0.2.10"))
            assertTrue(input.contentEquals(original))
            assertTrue(before.contentEquals(MessageDigest.getInstance("SHA-256").digest(input)))
        }
    }

    @Test fun hostileInputAndExceptionsStayBoundedAndRedacted() {
        for (bytes in listOf(ByteArray(SafeProxyConfig.MAX_BYTES + 1) { 32 }, byteArrayOf(0xc3.toByte()),
            (hy2 + "\nproxies: []").toByteArray(), (hy2 + "\n---\nproxies: []").toByteArray(),
            hy2.replace("synthetic-selected-secret", "&fixture synthetic-selected-secret").toByteArray())) {
            rejected { SafeProxyConfig.validateForPreparation(bytes) }
            rejected { SafeProxyConfig.selectedEndpoint(bytes, 0) }
            rejected { SafeProxyConfig.projectResolved(bytes, 0, "203.0.113.10") }
        }
        val marker = "synthetic-rejected-private-material"
        val error = rejected { SafeProxyConfig.validateForPreparation(hy2.replace("hysteria2", marker).toByteArray()) }
        assertFalse(error.toString().contains(marker))
    }

    private fun mixed(first: String, second: String) = first + "\n" + second.substringAfter("proxies:\n")
    private fun endpoint(value: String) = hy2.replace("edge.fixture.invalid", JSONObject.quote(value))
    private fun rejectedEverywhere(input: String, index: Int) {
        val bytes = input.toByteArray()
        rejected { SafeProxyConfig.validateForPreparation(bytes) }
        rejected { SafeProxyConfig.selectedEndpoint(bytes, index) }
        rejected { SafeProxyConfig.projectResolved(bytes, index, "203.0.113.10") }
    }
    private fun rejected(action: () -> Unit): UnsafeProxyConfiguration =
        assertThrows(UnsafeProxyConfiguration::class.java, action).also {
            assertEquals("Unsupported or unsafe proxy configuration", it.message)
            assertNull(it.cause)
        }
}
