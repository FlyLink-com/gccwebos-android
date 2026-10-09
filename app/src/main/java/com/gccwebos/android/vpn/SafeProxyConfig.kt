package com.gccwebos.android.vpn

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
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

class UnsafeProxyConfiguration(val reason: SafeProxyConfig.RejectionReason = SafeProxyConfig.RejectionReason.InputSyntax) :
    IllegalArgumentException("Unsupported or unsafe proxy configuration")

/** Versioned offline schema projection, never arbitrary YAML or options passed to the engine.
 * Display names/indexes are neither authorization nor stable line identifiers. This validates
 * configuration shape only; it does not establish protocol interoperability or enable VPN.
 */
object SafeProxyConfig {
    enum class RejectionReason {
        InputSyntax, TopLevelOptions, Protocol, NodeOptions,
        NodeOptionUdp, NodeOptionTfo, NodeOptionIpVersion, NodeOptionPorts, NodeOptionFingerprint,
        NodeOptionHopInterval, NodeOptionBbrProfile, NodeOptionToken, NodeOptionDisableSni,
        NodeOptionReduceRtt, NodeOptionRequestTimeout, NodeOptionHeartbeatInterval,
        NodeOptionMaxUdpRelayPacketSize, NodeOptionFastOpen, NodeOptionMaxOpenStreams,
        Endpoint, EndpointHostname, Port, ProtocolOptions, Selection
    }
    const val MAX_BYTES = 64 * 1024
    const val SCHEMA_VERSION = 1
    private val settings = LoadSettings.builder().setAllowDuplicateKeys(false)
        .setAllowRecursiveKeys(false).setMaxAliasesForCollections(0)
        .setCodePointLimit(MAX_BYTES).setUseMarks(false).setSchema(JsonSchema()).build()
    // Recognized subscription application settings remain bounded inert metadata. Only inline
    // proxies are imported; none of these settings, providers, paths or URLs enters the native DTO.
    private val inertApplicationKeys = setOf("external-controller", "find-process-mode", "geo-auto-update",
        "geox-url", "keep-alive-idle", "keep-alive-interval", "ntp", "rule-providers", "sniffer", "tun")
    private val rootKeys = setOf("proxies", "proxy-groups", "rules", "mode", "dns", "port", "socks-port", "mixed-port", "allow-lan", "log-level", "ipv6", "unified-delay", "tcp-concurrent", "global-client-fingerprint", "profile") + inertApplicationKeys
    private val baseKeys = setOf("name", "type", "server", "port")
    // Fixed public categories, ordered independently of input order. Values are never inspected.
    private val nodeOptionReasons = linkedMapOf(
        "token" to RejectionReason.NodeOptionToken,
        "disable-sni" to RejectionReason.NodeOptionDisableSni,
        "fingerprint" to RejectionReason.NodeOptionFingerprint,
        "ports" to RejectionReason.NodeOptionPorts,
        "hop-interval" to RejectionReason.NodeOptionHopInterval,
        "udp" to RejectionReason.NodeOptionUdp,
        "tfo" to RejectionReason.NodeOptionTfo,
        "ip-version" to RejectionReason.NodeOptionIpVersion,
        "bbr-profile" to RejectionReason.NodeOptionBbrProfile,
        "reduce-rtt" to RejectionReason.NodeOptionReduceRtt,
        "request-timeout" to RejectionReason.NodeOptionRequestTimeout,
        "heartbeat-interval" to RejectionReason.NodeOptionHeartbeatInterval,
        "max-udp-relay-packet-size" to RejectionReason.NodeOptionMaxUdpRelayPacketSize,
        "fast-open" to RejectionReason.NodeOptionFastOpen,
        "max-open-streams" to RejectionReason.NodeOptionMaxOpenStreams)
    private val tlsKeys = setOf("skip-cert-verify")
    private val tuicDefaultFalseKeys = setOf("disable-sni", "reduce-rtt", "fast-open")
    private val tuicDefaultIntegers = mapOf("heartbeat-interval" to 10000,
        "max-udp-relay-packet-size" to 1252, "max-open-streams" to 100)
    private val bbrProfiles = setOf("conservative", "standard", "aggressive")
    private val ciphers = setOf("aes-128-gcm", "aes-192-gcm", "aes-256-gcm", "chacha20-ietf-poly1305")
    private val fingerprints = setOf("chrome", "firefox", "safari", "ios", "edge")
    private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /** Display-only metadata; names/indexes are never stable authorization or connection IDs.
     * An info entry (for example "剩余流量：…") is a provider notice shaped like a node; it is never selectable. */
    data class NodeSummary(val index: Int, val name: String, val protocol: String, val supported: Boolean,
        val info: Boolean = false)
    // Conservative: a known notice keyword immediately followed by a colon. Ordinary node names never match.
    private val infoNodeName = Regex("(?:剩余流量|已用流量|总流量|流量|套餐到期|到期时间|过期时间|到期|官网|网址|客服|重置)\\s*[:：]|" +
        "(?i)\\b(?:remaining\\s+traffic|traffic|expire|expiry|expiration|website)\\s*[:：]")
    internal fun isInfoNodeName(name: String): Boolean = infoNodeName.containsMatchIn(name)
    /** Complete static validation; a hostname still needs a separately authorized physical-network resolution. */
    fun validateForPreparation(yaml: ByteArray): List<NodeSummary> = classified(RejectionReason.InputSyntax) {
        preparation(yaml).summaries
    }
    /** Returns the original endpoint only after every node has passed preparation validation. */
    fun selectedEndpoint(yaml: ByteArray, selectedIndex: Int): String = classified(RejectionReason.InputSyntax) {
        string(selected(preparation(yaml).proxies, selectedIndex), "server", RejectionReason.Endpoint)
    }
    /** Worker-only metadata, never credentials, UI details, a resolved address or connection authority. */
    internal class ProbeEndpoint internal constructor(val endpoint: String, val port: Int, val protocol: String,
        val obfuscated: Boolean) {
        override fun toString() = "ProbeEndpoint(<redacted>)"
    }
    /** Validate every node before extracting any target; no display-only or partial-list fallback. */
    internal fun probeEndpoints(yaml: ByteArray): List<ProbeEndpoint> = classified(RejectionReason.InputSyntax) {
        preparation(yaml).proxies.map { proxy ->
            val protocol = string(proxy, "type", RejectionReason.Protocol)
            ProbeEndpoint(string(proxy, "server", RejectionReason.Endpoint), proxy["port"] as Int, protocol,
                protocol == "hysteria2" && "obfs" in proxy)
        }
    }
    /** Only the selected endpoint is replaced; this validates the address, not its resolution or lease. */
    fun projectResolved(yaml: ByteArray, selectedIndex: Int, ipv4: String): ByteArray = classified(RejectionReason.InputSyntax) {
        val proxy = selected(preparation(yaml).proxies, selectedIndex)
        this.ipv4(ipv4)
        projection(node(proxy, allowHostname = true).put("server", ipv4))
    }
    /** Memory-only node identities, index-aligned with the proxies. A key is a SHA-256 over the display
     * name and the normalized engine DTO, so key order, whitespace and other nodes never change it.
     * Keys contain credential material in digested form only; never log, persist or display them. */
    internal fun selectionKeys(yaml: ByteArray): List<String> = classified(RejectionReason.InputSyntax) {
        input(yaml).map { p ->
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(string(p, "name", RejectionReason.NodeOptions).toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(canonical(node(p, allowHostname = true)).toByteArray(Charsets.UTF_8))
            val bytes = digest.digest()
            try { Base64.getEncoder().encodeToString(bytes) } finally { bytes.fill(0) }
        }
    }
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    fun inspect(yaml: ByteArray): List<NodeSummary> = try {
        input(yaml).mapIndexed { index, p ->
            val metadata = summary(index, p)
            metadata.copy(supported = try { node(p); true } catch (_: Exception) { false })
        }
    } catch (failure: UnsafeProxyConfiguration) { throw failure }
      catch (_: Exception) { throw UnsafeProxyConfiguration() }
    fun project(yaml: ByteArray, selectedIndex: Int): ByteArray = try {
        val proxies = input(yaml)
        classified(RejectionReason.Selection) { require(selectedIndex in proxies.indices) }
        // Validate ALL nodes. An unsupported mixed node must never disappear silently.
        val safe = proxies.map(::node)
        projection(safe[selectedIndex])
    } catch (failure: UnsafeProxyConfiguration) { throw failure }
      catch (_: Exception) { throw UnsafeProxyConfiguration() }
    private class Preparation(val proxies: List<Map<*, *>>, val summaries: List<NodeSummary>)
    private fun preparation(yaml: ByteArray): Preparation {
        val proxies = input(yaml)
        val summaries = proxies.mapIndexed { index, proxy ->
            val metadata = summary(index, proxy)
            node(proxy, allowHostname = true) // Every node is validated; no display-only fallback.
            metadata
        }
        return Preparation(proxies, summaries)
    }
    private fun selected(proxies: List<Map<*, *>>, index: Int): Map<*, *> = classified(RejectionReason.Selection) {
        require(index in proxies.indices)
        proxies[index]
    }
    private fun summary(index: Int, p: Map<*, *>): NodeSummary {
        val name = string(p, "name", RejectionReason.NodeOptions)
        classified(RejectionReason.NodeOptions) {
            require(name.isNotBlank() && name.toByteArray().size <= 128 && name.none { it.isISOControl() })
        }
        val protocol = string(p, "type", RejectionReason.Protocol)
        classified(RejectionReason.Protocol) { require(protocol.matches(Regex("[a-z0-9-]{1,24}"))) }
        return NodeSummary(index, name, protocol, true, isInfoNodeName(name))
    }
    private fun projection(proxy: JSONObject): ByteArray =
        JSONObject().put("schemaVersion", SCHEMA_VERSION).put("proxies", JSONArray().put(proxy))
            .put("rules", JSONArray().put("MATCH,GCC_SELECTED_PROXY"))
            .toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }
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
        classified(RejectionReason.TopLevelOptions) {
            keys(root, rootKeys, RejectionReason.TopLevelOptions)
            for (key in listOf("unified-delay", "tcp-concurrent")) if (key in root) require(root[key] is Boolean)
            if ("global-client-fingerprint" in root) require(root["global-client-fingerprint"] in fingerprints)
            if ("profile" in root) {
                val profile = root["profile"] as? Map<*, *> ?: throw UnsafeProxyConfiguration(RejectionReason.TopLevelOptions)
                keys(profile, setOf("store-selected", "store-fake-ip"), RejectionReason.TopLevelOptions)
                require(profile.values.all { it is Boolean })
            }
        }
        val proxies = root["proxies"] as? List<*> ?: throw UnsafeProxyConfiguration()
        require(proxies.size in 1..128)
        // Metadata inspection uses the same bounded parser, but cannot authorize a node.
        return proxies.map { it as? Map<*, *> ?: throw UnsafeProxyConfiguration() }
    }

    private fun node(p: Map<*, *>): JSONObject = node(p, allowHostname = false)
    private fun node(p: Map<*, *>, allowHostname: Boolean): JSONObject = classified(RejectionReason.ProtocolOptions) {
        val type = string(p, "type", RejectionReason.Protocol)
        val specific = when (type) {
            "ss" -> setOf("cipher", "password", "udp")
            "vless" -> setOf("uuid", "udp", "tls", "network", "servername", "client-fingerprint", "flow", "packet-encoding", "reality-opts", "encryption") + tlsKeys
            "hysteria2" -> setOf("password", "sni", "alpn", "up", "down", "obfs", "obfs-password", "bbr-profile") + tlsKeys
            "tuic" -> setOf("uuid", "password", "sni", "alpn", "congestion-controller", "udp-relay-mode", "bbr-profile", "request-timeout") + tlsKeys + tuicDefaultFalseKeys + tuicDefaultIntegers.keys
            else -> throw UnsafeProxyConfiguration(RejectionReason.Protocol)
        }
        keys(p, baseKeys + specific)
        val name = string(p, "name", RejectionReason.NodeOptions)
        classified(RejectionReason.NodeOptions) { require(name.isNotBlank() && name.toByteArray().size <= 128 && '\u0000' !in name) }
        val server = string(p, "server", RejectionReason.Endpoint)
        if (!allowHostname || !endpointHostname(server)) ipv4(server)
        val port = p["port"]; classified(RejectionReason.Port) { require(port is Int && port in 1..65535) }
        val out = JSONObject().put("name", "GCC_SELECTED_PROXY").put("type", type).put("server", server).put("port", port)
        when (type) {
            "ss" -> {
                require(p["cipher"] in ciphers)
                out.put("cipher", p["cipher"]).put("password", secret(p, "password")).put("udp", boolean(p, "udp", true))
            }
            "vless" -> {
                require(p["tls"] == true)
                // Pinned Mihomo transport/vless/encryption.NewClient treats only "" and "none"
                // as the omitted default. Normalize those strings out; never ignore a new mode.
                if ("encryption" in p) require(string(p, "encryption") in setOf("", "none"))
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
                    val r = p["reality-opts"] as? Map<*, *> ?: throw UnsafeProxyConfiguration(RejectionReason.ProtocolOptions)
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
                if ("bbr-profile" in p) {
                    val profile = string(p, "bbr-profile")
                    require(profile in bbrProfiles)
                    out.put("bbr-profile", profile)
                }
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
                    validateTuicDefaults(p)
                    // Bounded compatibility in milliseconds only; the pinned TUIC v5 core does not consume this field.
                    if ("request-timeout" in p) {
                        val timeout = p["request-timeout"]
                        require(timeout is Int && timeout in 0..30000)
                        out.put("request-timeout", timeout)
                    }
                    if ("congestion-controller" in p) { require(p["congestion-controller"] in setOf("cubic", "new_reno", "bbr")); out.put("congestion-controller", p["congestion-controller"]) }
                    if ("udp-relay-mode" in p) { require(p["udp-relay-mode"] in setOf("native", "quic")); out.put("udp-relay-mode", p["udp-relay-mode"]) }
                }
            }
        }
        out
    }
    /** Only pinned default-equivalent values; omit all six from the native DTO. */
    private fun validateTuicDefaults(p: Map<*, *>) {
        for (key in nodeOptionReasons.keys) {
            if (key !in p) continue
            if (key in tuicDefaultFalseKeys) classified(nodeOptionReasons.getValue(key)) {
                require(p[key] is Boolean && p[key] == false)
            } else tuicDefaultIntegers[key]?.let { expected ->
                classified(nodeOptionReasons.getValue(key)) {
                    val value = p[key]
                    require(value is Int && (value == 0 || value == expected))
                }
            }
        }
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
    private inline fun <T> classified(reason: RejectionReason, block: () -> T): T = try { block() }
        catch (failure: UnsafeProxyConfiguration) { throw failure }
        catch (_: Exception) { throw UnsafeProxyConfiguration(reason) }
    private fun keys(map: Map<*, *>, allowed: Set<String>, reason: RejectionReason = RejectionReason.NodeOptions) =
        classified(if (reason == RejectionReason.NodeOptions) nodeOptionReason(map, allowed) else reason) {
            require(map.keys.all { it is String && it in allowed })
        }
    private fun nodeOptionReason(map: Map<*, *>, allowed: Set<String>): RejectionReason {
        val extras = map.keys.filter { it !in allowed }
        if (extras.isEmpty() || extras.any { it !is String || it !in nodeOptionReasons }) return RejectionReason.NodeOptions
        return nodeOptionReasons.entries.first { it.key in extras }.value
    }
    private fun string(map: Map<*, *>, key: String, reason: RejectionReason = RejectionReason.ProtocolOptions) =
        map[key] as? String ?: throw UnsafeProxyConfiguration(reason)
    private fun boolean(map: Map<*, *>, key: String, default: Boolean): Boolean =
        if (key !in map) default else map[key] as? Boolean ?: throw UnsafeProxyConfiguration(RejectionReason.ProtocolOptions)
    private fun secret(map: Map<*, *>, key: String) = string(map, key).also {
        require(it.toByteArray(Charsets.UTF_8).size in 1..1024 && '\u0000' !in it)
    }
    private fun uuid(map: Map<*, *>) = string(map, "uuid").also {
        require(it.matches(uuidPattern) && it.replace("-", "").any { c -> c != '0' })
    }
    private fun dnsName(map: Map<*, *>, key: String) = string(map, key).also { name ->
        require(name.length in 1..253 && name.split('.').all { it.length in 1..63 && it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")) })
    }
    /** Categorizes rejected endpoint syntax without resolving or admitting it. */
    private fun endpointHostname(server: String): Boolean {
        if (server.length !in 1..253 || server.none { it in 'A'..'Z' || it in 'a'..'z' }) return false
        if (Regex("(?:0[xX][0-9a-fA-F]+|[0-9]+)(?:\\.(?:0[xX][0-9a-fA-F]+|[0-9]+)){0,3}").matches(server)) return false
        return server.split('.').all {
            it.length in 1..63 && it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"))
        }
    }
    private fun ipv4(server: String) = classified(
        if (endpointHostname(server)) RejectionReason.EndpointHostname else RejectionReason.Endpoint) {
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
        val list = p["alpn"] as? List<*> ?: throw UnsafeProxyConfiguration(RejectionReason.ProtocolOptions)
        require(list.size in 1..4 && list.toSet().size == list.size && list.all { it is String && it.length in 1..32 && it.all { ch -> ch.code in 33..126 } })
        out.put("alpn", JSONArray(list))
    }
}
