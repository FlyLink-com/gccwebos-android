package com.gccwebos.android.vpn

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONObject
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Parse
import org.snakeyaml.engine.v2.events.CollectionStartEvent
import org.snakeyaml.engine.v2.events.Event
import org.snakeyaml.engine.v2.events.NodeEvent
import org.snakeyaml.engine.v2.events.ScalarEvent
import org.snakeyaml.engine.v2.schema.JsonSchema

class UnsafeProxyConfiguration : IllegalArgumentException("Unsupported or unsafe proxy configuration")

/** This is a schema projection, never pass subscription YAML directly to the engine.
 * First native milestone deliberately supports only static Shadowsocks AEAD entries.
 * Display names/indexes are not account authorization or stable line identifiers.
 */
object SafeProxyConfig {
    const val MAX_BYTES = 64 * 1024
    private val settings = LoadSettings.builder().setAllowDuplicateKeys(false)
        .setAllowRecursiveKeys(false).setMaxAliasesForCollections(0)
        .setCodePointLimit(MAX_BYTES).setUseMarks(false).setSchema(JsonSchema()).build()
    private val rootKeys = setOf("proxies", "proxy-groups", "rules", "mode", "dns", "port", "socks-port", "mixed-port", "allow-lan", "log-level", "ipv6")
    private val proxyKeys = setOf("name", "type", "server", "port", "cipher", "password", "udp")
    private val ciphers = setOf("aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305")

    fun project(yaml: ByteArray, selectedIndex: Int): ByteArray = try {
        require(yaml.size in 1..MAX_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(yaml)).toString()
        // Bound parser nesting before object construction; reject every alias, anchor and explicit tag.
        var depth = 0; var documents = 0; var events = 0
        for (event in Parse(settings).parseString(text)) {
            require(++events <= 8192)
            if (event is NodeEvent) require(!event.anchor.isPresent)
            if (event is ScalarEvent) require(!event.tag.isPresent)
            if (event is CollectionStartEvent) require(!event.tag.isPresent)
            when (event.eventId) {
                Event.ID.DocumentStart -> require(++documents == 1)
                Event.ID.MappingStart, Event.ID.SequenceStart -> require(++depth <= 8)
                Event.ID.MappingEnd, Event.ID.SequenceEnd -> depth--
                Event.ID.Alias -> throw UnsafeProxyConfiguration()
                else -> Unit
            }
        }
        val root = Load(settings).loadFromString(text) as? Map<*, *> ?: throw UnsafeProxyConfiguration()
        require(root.keys.all { it is String && it in rootKeys })
        val proxies = root["proxies"] as? List<*> ?: throw UnsafeProxyConfiguration()
        require(proxies.size in 1..128 && selectedIndex in proxies.indices)
        // Validate all supplied proxies, not just the selected one, to avoid hidden unsafe options.
        val safe = proxies.map { value ->
            val proxy = value as? Map<*, *> ?: throw UnsafeProxyConfiguration()
            require(proxy.keys.all { it is String && it in proxyKeys })
            require(proxy["type"] == "ss" && proxy["cipher"] in ciphers)
            val name = proxy["name"] as? String ?: throw UnsafeProxyConfiguration()
            require(name.isNotBlank() && name.length <= 128)
            val server = proxy["server"] as? String ?: throw UnsafeProxyConfiguration()
            // First lab milestone: literal IPv4 only, so endpoint bootstrap cannot trigger DNS leaks.
            val octets = server.split('.')
            require(octets.size == 4 && octets.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() in 0..255 })
            val port = proxy["port"]
            require(port is Int && port in 1..65535)
            val password = proxy["password"] as? String ?: throw UnsafeProxyConfiguration()
            require(password.isNotEmpty() && password.length <= 1024 && '\u0000' !in password)
            require(proxy["udp"] == null || proxy["udp"] is Boolean)
            JSONObject().put("name", "GCC_SELECTED_PROXY").put("type", "ss").put("server", server)
                .put("port", port).put("cipher", proxy["cipher"]).put("password", password).put("udp", true)
        }
        // All incoming DNS/rules/groups/listen settings are discarded, never executed or merged.
        // No provider, URL, path, script, plugin, controller, external UI, geodata or rule downloads.
        JSONObject().put("proxies", JSONArray().put(safe[selectedIndex]))
            .put("rules", JSONArray().put("MATCH,GCC_SELECTED_PROXY"))
            .toString().toByteArray(Charsets.UTF_8)
    } catch (_: Exception) { throw UnsafeProxyConfiguration() }
}
