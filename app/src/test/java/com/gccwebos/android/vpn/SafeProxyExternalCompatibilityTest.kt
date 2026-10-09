package com.gccwebos.android.vpn

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic fixtures only. Subscription application settings are never engine instructions. */
class SafeProxyExternalCompatibilityTest {
    private val vless = """
        proxies:
          - name: synthetic external node
            type: vless
            server: 192.0.2.10
            port: 443
            uuid: 11111111-1111-4111-8111-111111111111
            tls: true
            network: tcp
            servername: fixture.example.invalid
            client-fingerprint: chrome
            flow: xtls-rprx-vision
    """.trimIndent()

    private val applicationSettings = listOf(
        "external-controller: 0.0.0.0:9090",
        "find-process-mode: always",
        "geo-auto-update: true",
        "geox-url: {geoip: 'https://fixture.invalid/synthetic-geo.dat'}",
        "keep-alive-idle: 30",
        "keep-alive-interval: 15",
        "ntp: {enable: true, server: 'fixture.invalid', port: 123}",
        "rule-providers: {fixture: {type: http, behavior: domain, url: 'https://fixture.invalid/synthetic-rules', path: './synthetic-rules.yaml'}}",
        "sniffer: {enable: true, force-dns-mapping: true}",
        "tun: {enable: true, stack: system, auto-route: true, dns-hijack: ['any:53']}"
    )

    @Test fun eachKnownApplicationSettingLeavesTheEntireNativeProjectionUnchanged() {
        val expected = SafeProxyConfig.project(vless.toByteArray(), 0)
        for (setting in applicationSettings) {
            val input = (setting + "\n" + vless).toByteArray()
            val original = input.copyOf()
            assertTrue(SafeProxyConfig.validateForPreparation(input).single().supported)
            assertTrue(SafeProxyConfig.inspect(input).single().supported)
            assertArrayEquals(expected, SafeProxyConfig.project(input, 0))
            assertArrayEquals(expected, SafeProxyConfig.projectResolved(input, 0, "192.0.2.10"))
            assertArrayEquals(original, input)
        }
    }

    @Test fun combinedExternalSettingsAndDefaultEncryptionProduceOnlyTheOwnedNodeAndRule() {
        val input = (applicationSettings.joinToString("\n") + "\n" + vless +
            "\n    encryption: none\nrules: ['MATCH,DIRECT']").toByteArray()
        val output = SafeProxyConfig.project(input, 0)
        assertArrayEquals(SafeProxyConfig.project(vless.toByteArray(), 0), output)
        val root = JSONObject(String(output, Charsets.UTF_8))
        assertEquals(setOf("schemaVersion", "proxies", "rules", "routingMode"), root.keys().asSequence().toSet())
        assertEquals(2, root.getInt("schemaVersion"))
        assertEquals("global", root.getString("routingMode"))
        assertEquals(1, root.getJSONArray("proxies").length())
        assertEquals("MATCH,GCC_SELECTED_PROXY", root.getJSONArray("rules").getString(0))
        val proxy = root.getJSONArray("proxies").getJSONObject(0)
        assertFalse(proxy.has("encryption"))
        assertFalse(String(output, Charsets.UTF_8).contains("synthetic-rules"))
    }

    @Test fun explicitlyDefaultVlessEncryptionMatchesThePinnedOmittedDefault() {
        val expected = SafeProxyConfig.project(vless.toByteArray(), 0)
        for (value in listOf("none", "\"\"")) {
            val input = (vless + "\n    encryption: $value").toByteArray()
            assertTrue(SafeProxyConfig.validateForPreparation(input).single().supported)
            assertTrue(SafeProxyConfig.inspect(input).single().supported)
            assertArrayEquals(expected, SafeProxyConfig.project(input, 0))
            assertArrayEquals(expected, SafeProxyConfig.projectResolved(input, 0, "192.0.2.10"))
        }
    }

    @Test fun nonDefaultOrNonStringEncryptionCannotBeSilentlyDropped() {
        for (value in listOf("synthetic-new-encryption", "\" none\"", "NONE", "null", "0", "false", "[]", "{}")) {
            rejectEveryPreparation(vless + "\n    encryption: $value")
        }
    }

    @Test fun anUnselectedNodeWithNonDefaultEncryptionRejectsTheWholeImport() {
        val second = vless.substringAfter("proxies:\n") + "\n    encryption: synthetic-new-encryption"
        rejectEveryPreparation(vless + "\n" + second)
    }

    @Test fun applicationSettingCompatibilityDoesNotAdmitUnknownRootOrProviderOnlyInput() {
        for (key in listOf("synthetic-extra-root", "proxy-providers", "external-ui", "external-ui-url",
            "listeners", "script", "secret")) {
            rejectEveryPreparation(vless + "\n$key: {}")
        }
        rejectEveryPreparation("rule-providers: {fixture: {type: http}}")
        rejectEveryPreparation("tun: {enable: true}\nproxies: []")
    }

    @Test fun ignoredApplicationSettingsStillObeyTheSameBoundedYamlParser() {
        for (suffix in listOf(
            "tun: &synthetic {enable: true}",
            "tun: *synthetic",
            "tun: !!map {enable: true}",
            "tun: [[[[[[[[[true]]]]]]]]]",
            "tun: {enable: true}\ntun: {enable: false}",
            "tun: {synthetic: \"\\u0000\"}"
        )) rejectEveryPreparation(vless + "\n" + suffix)
    }

    @Test fun addedDefaultDoesNotRelaxTlsTransportOrOtherNodeOptions() {
        val withDefault = vless + "\n    encryption: none"
        for (input in listOf(
            withDefault.replace("tls: true", "tls: false"),
            withDefault.replace("network: tcp", "network: ws"),
            withDefault.replace("flow: xtls-rprx-vision", "flow: synthetic-flow"),
            withDefault + "\n    skip-cert-verify: true",
            withDefault + "\n    certificate: synthetic-certificate",
            withDefault + "\n    ech-opts: {}"
        )) rejectEveryPreparation(input)
    }

    private fun rejectEveryPreparation(input: String) {
        val bytes = input.toByteArray()
        rejected { SafeProxyConfig.validateForPreparation(bytes) }
        rejected { SafeProxyConfig.project(bytes, 0) }
        rejected { SafeProxyConfig.projectResolved(bytes, 0, "192.0.2.10") }
    }

    private fun rejected(action: () -> Unit) {
        val failure = assertThrows(UnsafeProxyConfiguration::class.java, action)
        assertEquals("Unsupported or unsafe proxy configuration", failure.message)
        assertNull(failure.cause)
    }
}
