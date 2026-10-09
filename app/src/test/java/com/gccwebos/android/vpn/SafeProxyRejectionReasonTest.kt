package com.gccwebos.android.vpn

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Static diagnostic categories only; every input is synthetic and never contacts a peer. */
class SafeProxyRejectionReasonTest {
    private val ss = """
        proxies:
          - name: synthetic node
            type: ss
            server: 192.0.2.10
            port: 18443
            cipher: aes-128-gcm
            password: synthetic-only
    """.trimIndent()
    private val hy2 = """
        proxies:
          - name: synthetic node
            type: hysteria2
            server: 192.0.2.10
            port: 18443
            password: synthetic-only
            sni: fixture.example.invalid
    """.trimIndent()
    private val vless = """
        proxies:
          - name: synthetic node
            type: vless
            server: 192.0.2.10
            port: 18443
            uuid: 11111111-1111-4111-8111-111111111111
            tls: true
            servername: fixture.example.invalid
    """.trimIndent()

    @Test fun hostnameEndpointHasFixedReasonWithoutBecomingSupported() {
        val input = endpoint("fixture.example.invalid")
        rejects("EndpointHostname", input)
        assertFalse(SafeProxyConfig.inspect(input.toByteArray()).single().supported)
    }

    @Test fun strictAsciiHostnameShapeIncludesSingleLabelAndLengthBoundary() {
        val maximum = "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61)
        assertEquals(253, maximum.length)
        for (value in listOf("synthetic", "node1", "1a.2b.invalid", maximum)) {
            rejects("EndpointHostname", endpoint(value))
        }
    }

    @Test fun numericIpFormsAndIpv6RemainGenericEndpointRejections() {
        for (value in listOf("0.0.0.0", "224.0.0.1", "01.2.3.4", "999.2.3.4", "2130706433",
            "0177.0.0.1", "0x7f000001", "0X7F000001", "0x7f.1", "0x7f.0.1", "0x7f.0.0.1",
            "2001:db8::1", "[2001:db8::1]")) {
            rejects("Endpoint", endpoint(value))
        }
    }

    @Test fun missingOrNonStringEndpointKeepsGenericReason() {
        for (replacement in listOf("server: 7", "server: null", "server: []")) {
            rejects("Endpoint", hy2.replace("server: 192.0.2.10", replacement))
        }
        rejects("Endpoint", hy2.replace("    server: 192.0.2.10\n", ""))
    }

    @Test fun malformedHostnameShapesRemainGenericEndpointRejections() {
        val maximum = "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61)
        for (value in listOf("", "123.invalid_name", "-synthetic.invalid", "synthetic-.invalid",
            "synthetic.invalid.", ".synthetic.invalid", "synthetic..invalid", "a".repeat(64) + ".invalid",
            maximum + "a", "synthetic\u00e9.invalid", "synthetic.invalid:443", "synthetic.invalid/path",
            "https://synthetic.invalid", "user@synthetic.invalid", "synthetic\t.invalid", "synthetic\n.invalid")) {
            rejects("Endpoint", endpoint(value))
        }
    }

    @Test fun hostnameInUnselectedNodeStillRejectsTheWholeProjection() {
        val input = ss + "\n" + endpoint("fixture.example.invalid").substringAfter("proxies:\n")
        val metadata = SafeProxyConfig.inspect(input.toByteArray())
        assertEquals(2, metadata.size)
        assertTrue(metadata[0].supported)
        assertFalse(metadata[1].supported)
        rejects("EndpointHostname", input)
    }

    @Test fun hostnameDiagnosticNeverCarriesEndpointMaterialOrCause() {
        val marker = "synthetic-sensitive-marker.invalid"
        val failure = rejects("EndpointHostname", endpoint(marker))
        assertFalse(failure.toString().contains(marker))
        assertFalse(failure.message.orEmpty().contains(marker))
    }

    @Test fun extraHysteria2NodeKeyHasFixedNodeOptionsReason() {
        rejects("NodeOptionHopInterval", hy2 + "\n    hop-interval: 10")
    }

    @Test fun unknownRootKeyAndInvalidRootOptionsRemainDistinctFromSyntax() {
        for (input in listOf(ss + "\nsynthetic-extra-root: {}", ss + "\nunified-delay: 1",
            ss + "\nprofile: {store-selected: 1}")) {
            rejects("TopLevelOptions", input)
            rejectsInspection("TopLevelOptions", input.toByteArray())
        }
    }

    @Test fun disabledTlsHasFixedProtocolOptionsReasonAndKeepsDisplayOnlyInspection() {
        val input = vless.replace("tls: true", "tls: false")
        rejects("ProtocolOptions", input)
        val metadata = SafeProxyConfig.inspect(input.toByteArray())
        assertEquals(1, metadata.size)
        assertFalse(metadata.single().supported)
    }

    @Test fun oversizedAndMalformedInputHaveFixedInputSyntaxReason() {
        for (input in listOf(ByteArray(SafeProxyConfig.MAX_BYTES + 1) { 32 }, byteArrayOf(0xc3.toByte()),
            (ss + "\nproxies: []").toByteArray(), (ss + "\n---\nproxies: []").toByteArray(),
            ss.replace("synthetic-only", "&fixture synthetic-only").toByteArray())) {
            rejects("InputSyntax", input)
            rejectsInspection("InputSyntax", input)
        }
    }

    @Test fun unknownAndNonStringProtocolHaveFixedProtocolReason() {
        rejects("Protocol", ss.replace("type: ss", "type: synthetic-unknown"))
        rejects("Protocol", ss.replace("type: ss", "type: 7"))
        rejectsInspection("Protocol", ss.replace("type: ss", "type: 7").toByteArray())
    }

    @Test fun invalidPortHasFixedPortReason() {
        for (port in listOf("0", "65536", "\"18443\"")) {
            rejects("Port", ss.replace("port: 18443", "port: $port"))
        }
    }

    @Test fun missingNameIsNodeOptionsRatherThanProtocolOptions() {
        val input = ss.replace("name: synthetic node", "name: 7")
        rejects("NodeOptions", input)
        rejectsInspection("NodeOptions", input.toByteArray())
    }

    @Test fun invalidSelectionHasFixedSelectionReason() {
        val error = assertThrows(UnsafeProxyConfiguration::class.java) {
            SafeProxyConfig.project(ss.toByteArray(), 1)
        }
        assertFailure("Selection", error)
    }

    @Test fun categoriesNeverCarryRejectedMaterialOrCause() {
        val marker = "synthetic-sensitive-marker"
        val error = rejects("ProtocolOptions", ss.replace("aes-128-gcm", marker))
        assertFalse(error.toString().contains(marker))
        assertEquals("Unsupported or unsafe proxy configuration", error.message)
        assertNull(error.cause)
    }

    @Test fun validProjectionKeepsOwnedRuleAndProtocolDefaultsUnchanged() {
        val expected = JSONObject("""{"schemaVersion":2,"proxies":[{"name":"GCC_SELECTED_PROXY","type":"ss","server":"192.0.2.10","port":18443,"cipher":"aes-128-gcm","password":"synthetic-only","udp":true}],"rules":["MATCH,GCC_SELECTED_PROXY"],"routingMode":"global"}""")
        val actual = JSONObject(String(SafeProxyConfig.project(ss.toByteArray(), 0), Charsets.UTF_8))
        assertTrue("Synthetic projection changed", structure(actual) == structure(expected))
        assertTrue(SafeProxyConfig.inspect(ss.toByteArray()).single().supported)
    }

    private fun endpoint(value: String) = hy2.replace("192.0.2.10", JSONObject.quote(value))
    private fun rejects(reason: String, input: String): UnsafeProxyConfiguration = rejects(reason, input.toByteArray())
    private fun rejects(reason: String, input: ByteArray): UnsafeProxyConfiguration =
        assertThrows(UnsafeProxyConfiguration::class.java) { SafeProxyConfig.project(input, 0) }
            .also { assertFailure(reason, it) }
    private fun rejectsInspection(reason: String, input: ByteArray) {
        assertFailure(reason, assertThrows(UnsafeProxyConfiguration::class.java) { SafeProxyConfig.inspect(input) })
    }
    private fun assertFailure(reason: String, error: UnsafeProxyConfiguration) {
        assertEquals(reason, error.reason.name)
        assertEquals("Unsupported or unsafe proxy configuration", error.message)
        assertNull(error.cause)
    }
    private fun structure(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().toList().associateWith { structure(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { structure(value.get(it)) }
        JSONObject.NULL -> null
        else -> value
    }
}
