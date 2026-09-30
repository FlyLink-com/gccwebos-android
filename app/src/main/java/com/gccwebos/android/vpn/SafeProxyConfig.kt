package com.gccwebos.android.vpn

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
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

/** Versioned offline schema projection, never arbitrary YAML or options passed to the engine.
 * Display names/indexes are neither authorization nor stable line identifiers. This validates
 * configuration shape only; it does not establish protocol interoperability or enable VPN.
 */
object SafeProxyConfig {
    const val MAX_BYTES = 64 * 1024
    const val SCHEMA_VERSION = 1
    private val settings = LoadSettings.builder().setAllowDuplicateKeys(false)
        .setAllowRecursiveKeys(false).setMaxAliasesForCollections(0)
        .setCodePointLimit(MAX_BYTES).setUseMarks(false).setSchema(JsonSchema()).build()
    private val rootKeys = setOf("proxies", "proxy-groups", "rules", "mode", "dns", "port", "socks-port", "mixed-port", "allow-lan", "log-level", "ipv6", "unified-delay", "tcp-concurrent", "global-client-fingerprint", "profile")
    private val baseKeys = setOf("name", "type", "server", "port")
    private val tlsKeys = setOf("skip-cert-verify")
    private val ciphers = setOf("aes-128-gcm", "aes-192-gcm", "aes-256-gcm", "chacha20-ietf-poly1305")
    private val fingerprints = setOf("chrome", "firefox", "safari", "ios", "edge")
    private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /** Display-only metadata; names/indexes are never stable authorization or connection IDs. */
    data class NodeSummary(val index: Int, val name: String, val protocol: String, val supported: Boolean)
    fun inspect(yaml: ByteArray): List<NodeSummary> = try {
        input(yaml).mapIndexed { index, p ->
            val name = string(p, "name")
            require(name.isNotBlank() && name.toByteArray().size <= 128 && name.none { it.isISOControl() })
            val protocol = string(p, "type")
            require(protocol.matches(Regex("[a-z0-9-]{1,24}")))
            NodeSummary(index, name, protocol, try { node(p); true } catch (_: Exception) { false })
        }
    } catch (_: Exception) { throw UnsafeProxyConfiguration() }
    fun project(yaml: ByteArray, selectedIndex: Int): ByteArray = try {
        val proxies = input(yaml)
        require(selectedIndex in proxies.indices)
        // Validate ALL nodes. An unsupported mixed node must never disappear silently.
        val safe = proxies.map(::node)
        JSONObject().put("schemaVersion", SCHEMA_VERSION).put("proxies", JSONArray().put(safe[selectedIndex]))
            .put("rules", JSONArray().put("MATCH,GCC_SELECTED_PROXY"))
            .toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }
    } catch (_: Exception) { throw UnsafeProxyConfiguration() }
    private fun input(yaml: ByteArray): List<Map<*, *>> {
        require(yaml.size in 1..MAX_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(yaml)).toString()
        require('\u0000' !in text)
        // Bound parser nesting before object construction; reject every alias, anchor and explicit tag.
        var depth = 0; var documents = 0; var events = 0
        for (event in Parse(settings).parseString(text)) {
            require(++events <= 8192)
            if (event is NodeEvent) require(!event.anchor.isPresent)
            if (event is ScalarEvent) {
                require(!event.tag.isPresent)
                require(validUnicode(event.value) && '\u0000' !in event.value)
            }
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
        keys(root, rootKeys)
        for (key in listOf("unified-delay", "tcp-concurrent")) if (key in root) require(root[key] is Boolean)
        if ("global-client-fingerprint" in root) require(root["global-client-fingerprint"] in fingerprints)
        if ("profile" in root) {
            val profile = root["profile"] as? Map<*, *> ?: throw UnsafeProxyConfiguration()
            keys(profile, setOf("store-selected", "store-fake-ip"))
            require(profile.values.all { it is Boolean })
        }
        val proxies = root["proxies"] as? List<*> ?: throw UnsafeProxyConfiguration()
        require(proxies.size in 1..128)
        // Metadata inspection uses the same bounded parser, but cannot authorize a node.
        return proxies.map { it as? Map<*, *> ?: throw UnsafeProxyConfiguration() }
    }

    private fun node(p: Map<*, *>): JSONObject {
        val type = string(p, "type")
        val specific = when (type) {
            "ss" -> setOf("cipher", "password", "udp")
            "vless" -> setOf("uuid", "udp", "tls", "network", "servername", "client-fingerprint", "flow", "packet-encoding", "reality-opts") + tlsKeys
            "hysteria2" -> setOf("password", "sni", "alpn", "up", "down", "obfs", "obfs-password") + tlsKeys
            "tuic" -> setOf("uuid", "password", "sni", "alpn", "congestion-controller", "udp-relay-mode") + tlsKeys
            else -> throw UnsafeProxyConfiguration()
        }
        keys(p, baseKeys + specific)
        val name = string(p, "name"); require(name.isNotBlank() && name.toByteArray().size <= 128 && '\u0000' !in name)
        val server = string(p, "server"); ipv4(server)
        val port = p["port"]; require(port is Int && port in 1..65535)
        val out = JSONObject().put("name", "GCC_SELECTED_PROXY").put("type", type).put("server", server).put("port", port)
        when (type) {
            "ss" -> {
                require(p["cipher"] in ciphers)
                out.put("cipher", p["cipher"]).put("password", secret(p, "password")).put("udp", boolean(p, "udp", true))
            }
            "vless" -> {
                require(p["tls"] == true)
                // Missing network is Mihomo's TCP default, explicitly normalized in the DTO.
                require("network" !in p || p["network"] == "tcp")
                out.put("uuid", uuid(p)).put("tls", true).put("network", "tcp")
                    .put("servername", dnsName(p, "servername")).put("udp", boolean(p, "udp", false))
                if ("client-fingerprint" in p) { require(p["client-fingerprint"] in fingerprints); out.put("client-fingerprint", p["client-fingerprint"]) }
                if ("flow" in p) { require(p["flow"] == "xtls-rprx-vision"); out.put("flow", p["flow"]) }
                if ("packet-encoding" in p) {
                    require(out.getBoolean("udp") && p["packet-encoding"] in setOf("xudp", "packetaddr"))
                    out.put("packet-encoding", p["packet-encoding"])
                }
                if ("reality-opts" in p) {
                    require("client-fingerprint" in p)
                    val r = p["reality-opts"] as? Map<*, *> ?: throw UnsafeProxyConfiguration()
                    require(r.keys == setOf("public-key", "short-id"))
                    val key = string(r, "public-key")
                    require(key.matches(Regex("[A-Za-z0-9_-]{43}")))
                    val bytes = Base64.getUrlDecoder().decode(key)
                    require(bytes.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == key)
                    val short = string(r, "short-id"); require(short.length % 2 == 0 && short.matches(Regex("[0-9a-fA-F]{0,16}")))
                    out.put("reality-opts", JSONObject().put("public-key", key).put("short-id", short))
                }
                tls(p, out)
            }
            "hysteria2", "tuic" -> {
                out.put("password", secret(p, "password")).put("sni", dnsName(p, "sni"))
                tls(p, out); alpn(p, out)
                if (type == "hysteria2") {
                    for (key in listOf("up", "down")) if (key in p) {
                        val bandwidth = string(p, key)
                        require(bandwidth.matches(Regex("[1-9][0-9]{0,4}( Mbps)?")))
                        val mbps = bandwidth.substringBefore(' ').toInt(); require(mbps in 1..10000)
                        out.put(key, "$mbps Mbps")
                    }
                    require(("obfs" in p) == ("obfs-password" in p))
                    if ("obfs" in p) { require(p["obfs"] == "salamander"); out.put("obfs", "salamander").put("obfs-password", secret(p, "obfs-password")) }
                } else {
                    out.put("uuid", uuid(p))
                    if ("congestion-controller" in p) { require(p["congestion-controller"] in setOf("cubic", "new_reno", "bbr")); out.put("congestion-controller", p["congestion-controller"]) }
                    if ("udp-relay-mode" in p) { require(p["udp-relay-mode"] in setOf("native", "quic")); out.put("udp-relay-mode", p["udp-relay-mode"]) }
                }
            }
        }
        return out
    }
    // YAML Unicode escapes must not turn an unpaired UTF-16 surrogate into '?' at UTF-8 encoding.
    private fun validUnicode(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val ch = value[index++]
            if (Character.isHighSurrogate(ch)) {
                if (index >= value.length || !Character.isLowSurrogate(value[index++])) return false
            } else if (Character.isLowSurrogate(ch)) return false
        }
        return true
    }
    private fun keys(map: Map<*, *>, allowed: Set<String>) = require(map.keys.all { it is String && it in allowed })
    private fun string(map: Map<*, *>, key: String) = map[key] as? String ?: throw UnsafeProxyConfiguration()
    private fun boolean(map: Map<*, *>, key: String, default: Boolean): Boolean =
        if (key !in map) default else map[key] as? Boolean ?: throw UnsafeProxyConfiguration()
    private fun secret(map: Map<*, *>, key: String) = string(map, key).also {
        require(it.toByteArray(Charsets.UTF_8).size in 1..1024 && '\u0000' !in it)
    }
    private fun uuid(map: Map<*, *>) = string(map, "uuid").also {
        require(it.matches(uuidPattern) && it.replace("-", "").any { c -> c != '0' })
    }
    private fun dnsName(map: Map<*, *>, key: String) = string(map, key).also { name ->
        require(name.length in 1..253 && name.split('.').all { it.length in 1..63 && it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")) })
    }
    private fun ipv4(server: String) {
        val octets = server.split('.')
        require(octets.size == 4 && octets.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() in 0..255 })
        require(server != "0.0.0.0" && octets[0].toInt() !in 224..239)
    }
    private fun tls(p: Map<*, *>, out: JSONObject) {
        require("skip-cert-verify" !in p || p["skip-cert-verify"] == false)
        out.put("skip-cert-verify", false)
    }
    private fun alpn(p: Map<*, *>, out: JSONObject) {
        if ("alpn" !in p) return
        val list = p["alpn"] as? List<*> ?: throw UnsafeProxyConfiguration()
        require(list.size in 1..4 && list.toSet().size == list.size && list.all { it is String && it.length in 1..32 && it.all { ch -> ch.code in 33..126 } })
        out.put("alpn", JSONArray(list))
    }
}
