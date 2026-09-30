package com.gccwebos.android.vpn

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

private val fixture = """
proxies:
  - name: local lab
    type: ss
    server: 127.0.0.1
    port: 18388
    cipher: aes-128-gcm
    password: lab-only-password
    udp: true
""".trimIndent()

class SafeProxyConfigTest {
    @Test fun onlySelectedProxyAndOwnedRuleReachCore() {
        val value = JSONObject(String(SafeProxyConfig.project((fixture + "\nport: 9999\ndns: {enable: true, nameserver: [https://evil.example/dns-query]}\nrules: [MATCH,DIRECT]\n").toByteArray(), 0)))
        assertEquals(setOf("proxies", "rules"), value.keys().asSequence().toSet())
        assertEquals("MATCH,GCC_SELECTED_PROXY", value.getJSONArray("rules").getString(0))
        assertEquals("GCC_SELECTED_PROXY", value.getJSONArray("proxies").getJSONObject(0).getString("name"))
        assertFalse(value.toString().contains("evil.example"))
    }
    @Test fun rejectsProviderControllerScriptAndFileDirectives() {
        for (key in listOf("proxy-providers", "rule-providers", "external-controller", "external-ui", "external-ui-url", "geox-url", "script", "listeners", "tun", "secret", "profile")) {
            invalid(fixture + "\n$key: {}")
        }
    }
    @Test fun rejectsProxyPluginsPathsAndDangerousTypes() {
        for (key in listOf("plugin", "plugin-opts", "dialer-proxy", "interface-name", "routing-mark", "smux", "certificate", "private-key", "skip-cert-verify")) invalid(fixture + "\n    $key: unsafe")
        for (type in listOf("direct", "http", "socks5", "vmess", "trojan", "hysteria2")) invalid(fixture.replace("type: ss", "type: $type"))
    }
    @Test fun endpointsAreStrictLiteralsAndNeverUrls() {
        for (host in listOf("evil.example", "https://evil.example", "127.000.0.1", "256.1.1.1", "127.1", "::1", "1.1.1.1/path")) invalid(fixture.replace("127.0.0.1", host))
    }
    @Test fun duplicateKeysAliasesTagsMultidocumentAndDepthRejected() {
        invalid(fixture + "\nproxies: []")
        invalid(fixture.replace("password: lab-only-password", "password: &pw abc"))
        invalid(fixture.replace("password: lab-only-password", "password: !!str abc"))
        invalid(fixture + "\n---\nproxies: []")
        invalid("proxies: " + "[".repeat(20) + "0" + "]".repeat(20))
    }
    @Test fun invalidPortCipherAndPasswordRejected() {
        invalid(fixture.replace("18388", "0")); invalid(fixture.replace("18388", "65536"))
        invalid(fixture.replace("18388", "\"18388\"")); invalid(fixture.replace("aes-128-gcm", "none"))
        invalid(fixture.replace("lab-only-password", "\"\""))
    }
    @Test fun sizeUtf8AndSelectionBounded() {
        invalid(" ".repeat(SafeProxyConfig.MAX_BYTES + 1))
        assertThrows(UnsafeProxyConfiguration::class.java) { SafeProxyConfig.project(byteArrayOf(0xc3.toByte()), 0) }
        assertThrows(UnsafeProxyConfiguration::class.java) { SafeProxyConfig.project(fixture.toByteArray(), 1) }
    }
    @Test fun noCredentialTextInException() {
        val e = assertThrows(UnsafeProxyConfiguration::class.java) { SafeProxyConfig.project(fixture.replace("aes-128-gcm", "secret-password-in-error").toByteArray(), 0) }
        assertFalse(e.toString().contains("secret-password-in-error")); assertNull(e.cause)
    }
    private fun invalid(value: String) { assertThrows(UnsafeProxyConfiguration::class.java) { SafeProxyConfig.project(value.toByteArray(), 0) } }
}

class NativeVpnSessionTest {
    private class Tun : OwnedTun { var closed = false; override val fd = 37; override fun close() { closed = true } }
    private class Engine : NativeEngine { var starts = 0; var stops = 0; var failStart = false; var failStop = false; var duringStart: () -> Unit = {}
        override fun start(config: ByteArray, tunFd: Int) { starts++; duringStart(); if (failStart) error("secret config") }
        override fun stop() { stops++; if (failStop) error("stop failed") }
    }
    @Test fun successfulStartIsExplicitlyUnverifiedAndStopClosesBoth() {
        val engine = Engine(); val tun = Tun(); val states = mutableListOf<VpnLabState>(); val bytes = byteArrayOf(1, 2)
        val session = NativeVpnSession(engine, { tun }, states::add)
        session.start(bytes); assertEquals(VpnLabState.ENGINE_RUNNING_UNVERIFIED, states.last()); assertArrayEquals(byteArrayOf(0, 0), bytes)
        session.stop(); session.stop(); assertEquals(1, engine.stops); assertTrue(tun.closed); assertEquals(VpnLabState.IDLE, states.last())
    }
    @Test fun cancelledStartDoesNotPublishRunningAndCleans() {
        val engine = Engine(); val tun = Tun(); val states = mutableListOf<VpnLabState>(); val session = NativeVpnSession(engine, { tun }, states::add)
        engine.duringStart = session::requestStop
        session.start(byteArrayOf(1)); assertFalse(states.contains(VpnLabState.ENGINE_RUNNING_UNVERIFIED)); assertTrue(tun.closed); assertEquals(1, engine.stops)
    }
    @Test fun partialNativeFailureStillStopsAndCloses() {
        val engine = Engine().apply { failStart = true }; val tun = Tun(); val states = mutableListOf<VpnLabState>()
        NativeVpnSession(engine, { tun }, states::add).start(byteArrayOf(1))
        assertTrue(states.contains(VpnLabState.FAILED)); assertTrue(tun.closed); assertEquals(1, engine.stops)
    }
    @Test fun cleanupFailureLatchesAndBlocksRestart() {
        val engine = Engine().apply { failStop = true }; val tun = Tun(); val states = mutableListOf<VpnLabState>(); val session = NativeVpnSession(engine, { tun }, states::add)
        session.start(byteArrayOf(1)); session.stop(); session.start(byteArrayOf(1)); assertEquals(1, engine.starts)
        assertEquals(VpnLabState.CLEANUP_FAILED, states.last()); assertTrue(tun.closed)
    }
    @Test fun stoppedBeforeAdmissionAllocatesNothing() {
        val engine = Engine(); val session = NativeVpnSession(engine, { error("must not open") }, {})
        session.requestStop(); session.start(byteArrayOf(1)); assertEquals(0, engine.starts)
    }
    @Test fun tunFailureNeverInvokesEngine() {
        val engine = Engine(); val states = mutableListOf<VpnLabState>(); val session = NativeVpnSession(engine, { error("no permission") }, states::add)
        session.start(byteArrayOf(1)); assertEquals(0, engine.starts); assertEquals(0, engine.stops); assertEquals(VpnLabState.IDLE, states.last())
    }
}

class LabPermissionAttemptTest {
    @Test fun cancelBeforeResultInvalidatesIt() { val gate = LabPermissionAttempt(); val token = gate.begin()!!; gate.cancel(); assertFalse(gate.complete(token)) }
    @Test fun repeatedClicksDoNotLaunchTwice() { val gate = LabPermissionAttempt(); gate.begin(); assertNull(gate.begin()) }
    @Test fun oldResultCannotCompleteNewRequest() { val gate = LabPermissionAttempt(); val old = gate.begin()!!; gate.cancel(); assertNull(gate.begin()); assertFalse(gate.complete(old)); val next = gate.begin()!!; assertFalse(gate.complete(old)); assertTrue(gate.complete(next)) }
    @Test fun rotatedActivityRejectsUnknownResult() { val old = LabPermissionAttempt().begin()!!; assertFalse(LabPermissionAttempt().complete(old)) }
    @Test fun resultConsumedOnlyOnce() { val gate = LabPermissionAttempt(); val token = gate.begin()!!; assertTrue(gate.complete(token)); assertFalse(gate.complete(token)) }
}
