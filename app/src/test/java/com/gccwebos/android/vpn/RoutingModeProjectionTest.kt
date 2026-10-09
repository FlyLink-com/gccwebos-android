package com.gccwebos.android.vpn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RoutingModeProjectionTest {
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

    @Test fun defaultProjectionIsGlobalSchemaTwo() {
        val dto = JSONObject(String(SafeProxyConfig.project(fixture.toByteArray(), 0)))
        assertEquals(2, dto.getInt("schemaVersion"))
        assertEquals("global", dto.getString("routingMode"))
        assertEquals("MATCH,GCC_SELECTED_PROXY", dto.getJSONArray("rules").getString(0))
        assertEquals(setOf("schemaVersion", "proxies", "rules", "routingMode"), dto.keys().asSequence().toSet())
    }

    @Test fun ruleModeIsProductOwnedAndStillDropsSubscriptionRules() {
        val polluted = fixture + "\nrules: [MATCH,DIRECT]\n"
        val dto = JSONObject(String(SafeProxyConfig.project(polluted.toByteArray(), 0, RoutingMode.Rule)))
        assertEquals("rule", dto.getString("routingMode"))
        assertEquals(1, dto.getJSONArray("rules").length())
        assertEquals("MATCH,GCC_SELECTED_PROXY", dto.getJSONArray("rules").getString(0))
        assertFalse(dto.toString().contains("MATCH,DIRECT"))
    }

    @Test fun parseFailsClosedToGlobal() {
        assertEquals(RoutingMode.Global, RoutingMode.parse(null))
        assertEquals(RoutingMode.Global, RoutingMode.parse(""))
        assertEquals(RoutingMode.Global, RoutingMode.parse("bypass-mainland"))
        assertEquals(RoutingMode.Rule, RoutingMode.parse("rule"))
        assertEquals(RoutingMode.Rule, RoutingMode.parse("RULE"))
        assertEquals(RoutingMode.Global, RoutingMode.parse("global"))
    }
}
