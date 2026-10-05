package com.gccwebos.android.vpn

import org.junit.Assert.*
import org.junit.Test

/** Static probe metadata only; no DNS, sockets, account state or credential-bearing DTO. */
class SafeProxyProbeEndpointsTest {
    private fun ss(port: String = "443", server: String = "synthetic.example") = """  - name: synthetic-secret-display-name
    type: ss
    server: $server
    port: $port
    cipher: aes-128-gcm
    password: synthetic-sensitive-password
"""

    @Test fun allMetadataComesFromCompletePreparationAndStringRepresentationsAreRedacted() {
        val bytes = ("proxies:\n" + ss("1") + ss("65535", "192.0.2.10")).toByteArray()
        try {
            val endpoints = SafeProxyConfig.probeEndpoints(bytes)
            assertEquals(listOf("synthetic.example", "192.0.2.10"), endpoints.map { it.endpoint })
            assertEquals(listOf(1, 65535), endpoints.map { it.port })
            assertTrue(endpoints.all { it.protocol == "ss" && !it.obfuscated })
            endpoints.forEach { endpoint ->
                assertEquals("ProbeEndpoint(<redacted>)", endpoint.toString())
                assertFalse(endpoint.toString().contains("synthetic"))
                assertFalse(endpoint.toString().contains("192.0.2"))
            }
            // The DTO intentionally has no display name, original map, UUID, password, SNI or YAML field.
            assertEquals(setOf("endpoint", "port", "protocol", "obfuscated"),
                endpoints.first().javaClass.declaredFields.filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet())
        } finally { bytes.fill(0) }
    }

    @Test fun aValidFirstNodeNeverHidesAnInvalidLaterNodeOrRootOption() {
        val invalid = listOf(ss("0"), ss("65536"), ss("'443'"), ss("443", "256.0.0.1"),
            ss().replace("type: ss", "type: vmess"), ss().replace("cipher: aes-128-gcm", "cipher: invalid"),
            ss() + "    obfs: salamander\n")
        invalid.forEach { second ->
            val bytes = ("proxies:\n" + ss() + second).toByteArray()
            try { assertThrows(UnsafeProxyConfiguration::class.java) { SafeProxyConfig.probeEndpoints(bytes) } }
            finally { bytes.fill(0) }
        }
        val badRoot = ("unrecognized-executable-option: true\nproxies:\n" + ss()).toByteArray()
        try { assertThrows(UnsafeProxyConfiguration::class.java) { SafeProxyConfig.probeEndpoints(badRoot) } }
        finally { badRoot.fill(0) }
    }

    @Test fun obfuscationIsExplicitMetadataOnlyAfterItsCompleteProtocolContractPasses() {
        val plain = """proxies:
  - name: synthetic-hy2
    type: hysteria2
    server: synthetic.example
    port: 443
    password: synthetic-sensitive-password
    sni: synthetic.example
"""
        val variants = listOf(plain to false,
            plain + "    obfs: salamander\n    obfs-password: synthetic-obfuscation\n" to true)
        variants.forEach { (text, obfuscated) ->
            val bytes = text.toByteArray()
            try {
                val endpoint = SafeProxyConfig.probeEndpoints(bytes).single()
                assertEquals("hysteria2", endpoint.protocol); assertEquals(443, endpoint.port)
                assertEquals(obfuscated, endpoint.obfuscated)
            } finally { bytes.fill(0) }
        }
        for (suffix in listOf("    obfs: salamander\n", "    obfs-password: synthetic-obfuscation\n",
            "    obfs: invalid\n    obfs-password: synthetic-obfuscation\n")) {
            val bytes = (plain + suffix).toByteArray()
            try { assertThrows(UnsafeProxyConfiguration::class.java) { SafeProxyConfig.probeEndpoints(bytes) } }
            finally { bytes.fill(0) }
        }
    }
}
