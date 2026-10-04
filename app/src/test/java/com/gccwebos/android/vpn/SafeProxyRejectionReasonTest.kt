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

    @Test fun dnsEndpointHasFixedEndpointReason() {
        rejects("Endpoint", hy2.replace("192.0.2.10", "fixture.example.invalid"))
    }

    @Test fun extraHysteria2NodeKeyHasFixedNodeOptionsReason() {
        rejects("NodeOptions", hy2 + "\n    hop-interval: 10")
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
        val expected = JSONObject("""{"schemaVersion":1,"proxies":[{"name":"GCC_SELECTED_PROXY","type":"ss","server":"192.0.2.10","port":18443,"cipher":"aes-128-gcm","password":"synthetic-only","udp":true}],"rules":["MATCH,GCC_SELECTED_PROXY"]}""")
        val actual = JSONObject(String(SafeProxyConfig.project(ss.toByteArray(), 0), Charsets.UTF_8))
        assertTrue("Synthetic projection changed", structure(actual) == structure(expected))
        assertTrue(SafeProxyConfig.inspect(ss.toByteArray()).single().supported)
    }

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
