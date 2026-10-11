package com.gccwebos.android.payment

import java.net.URI

/** The only app the payment page hands an Alipay deep link to. Other apps that claim the scheme never get it. */
internal const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"

/** Why a navigation was refused; only used for the plain-language notice and tests, never logged with a URL. */
internal enum class BlockReason { OtherOrigin, UnsupportedScheme, MalformedUrl, WrongPackage, InsecureScheme, HopLimit }

internal sealed interface PaymentNavigation {
    /** An https page inside the chain that started at the gateway entry: let the WebView continue. */
    data object Allow : PaymentNavigation

    /** An Alipay deep link (`alipays:`/`alipay:` URI, already mapped from any `intent:` form) for the Alipay package only. */
    data class Alipay(val uri: String) : PaymentNavigation

    data class Block(val reason: BlockReason) : PaymentNavigation
}

/**
 * Pure decision table for every navigation the payment page attempts, with one piece of state: the chain of hosts.
 *
 * The page starts at the trusted gateway entry (`CommerceJson.trustedPaymentRedirect` has already pinned it to
 * www.xsd8866.com). That gateway sends every order to a different, rotating third-party https page, so a per-domain allowlist
 * cannot work. Instead the *chain* is trusted, not the hosts: the top-level redirect chain that starts at the gateway may
 * follow https hops to other hosts, subject to
 *  - https only (never http, no mixed content, no SSL-error bypass: those are WebView settings/handlers);
 *  - at most [MAX_HOPS] distinct hosts beyond the gateway (and [MAX_NAVIGATIONS] navigations in total, against loops);
 *  - a hop to a host the chain has not established yet is accepted only when the page moved there by itself (a server
 *    redirect or a script/meta navigation without a user gesture), never because the user tapped a link;
 *  - sub-frames never extend the chain: they may only load established hosts;
 *  - no userinfo, no non-443 port, no IP literal or single-label host, no trailing dot.
 * Alipay deep links are the only thing allowed to leave https, and only for the Alipay package. Nothing here reads, logs
 * or stores a URL beyond the call.
 */
internal class PaymentNavigationPolicy(pinnedUrl: String) {
    private val gatewayHost: String = requireNotNull(hostOf(pinnedUrl)) { "Invalid pinned payment URL" }
    private val hosts = linkedSetOf(gatewayHost)
    private var navigations = 0

    /** Hosts the chain has established so far, gateway first. */
    val establishedHosts: List<String> get() = hosts.toList()

    /** Distinct hosts beyond the gateway. */
    val hops: Int get() = hosts.size - 1

    /** A manual reload of the gateway entry starts a new chain. */
    fun restart() { hosts.clear(); hosts.add(gatewayHost); navigations = 0 }

    fun decide(url: String?, mainFrame: Boolean = true, redirect: Boolean = false, userGesture: Boolean = false): PaymentNavigation {
        if (url.isNullOrEmpty() || url.length > MAX_URL_LENGTH || url.any { it.code !in 0x21..0x7e }) {
            return PaymentNavigation.Block(BlockReason.MalformedUrl)
        }
        val colon = url.indexOf(':')
        if (colon <= 0) return PaymentNavigation.Block(BlockReason.MalformedUrl)
        return when (url.substring(0, colon).lowercase()) {
            "https" -> decideHttps(url, mainFrame, redirect, userGesture)
            "http" -> PaymentNavigation.Block(BlockReason.InsecureScheme)
            "alipays", "alipay" -> if (wellFormed(url)) PaymentNavigation.Alipay(url) else PaymentNavigation.Block(BlockReason.MalformedUrl)
            "intent" -> fromIntentUri(url)
            else -> PaymentNavigation.Block(BlockReason.UnsupportedScheme)
        }
    }

    private fun decideHttps(url: String, mainFrame: Boolean, redirect: Boolean, userGesture: Boolean): PaymentNavigation {
        val host = hostOf(url) ?: return PaymentNavigation.Block(BlockReason.OtherOrigin)
        if (host in hosts) {
            if (mainFrame && ++navigations > MAX_NAVIGATIONS) return PaymentNavigation.Block(BlockReason.HopLimit)
            return PaymentNavigation.Allow
        }
        // A host the chain has not reached yet: only the main page, only by itself (redirect or no user tap), only within the cap.
        if (!mainFrame) return PaymentNavigation.Block(BlockReason.OtherOrigin)
        if (userGesture && !redirect) return PaymentNavigation.Block(BlockReason.OtherOrigin)
        if (hosts.size - 1 >= MAX_HOPS || ++navigations > MAX_NAVIGATIONS) return PaymentNavigation.Block(BlockReason.HopLimit)
        hosts.add(host)
        return PaymentNavigation.Allow
    }

    /**
     * `intent://host/path?query#Intent;scheme=alipays;package=com.eg.android.AlipayGphone;end` is Chrome's encoding of a
     * deep link. It is never passed to `Intent.parseUri` (which could name arbitrary components or selectors); only the
     * scheme and package are read, and the result is a plain `alipays:` URI for the Alipay package.
     */
    private fun fromIntentUri(url: String): PaymentNavigation {
        val marker = url.indexOf("#Intent;")
        if (marker < 0 || !url.endsWith(";end")) return PaymentNavigation.Block(BlockReason.MalformedUrl)
        val target = url.substring("intent:".length, marker)
        // ";scheme=…;package=…;end" with the leading and trailing delimiters shared with the marker removed.
        val parameters = url.substring(marker + "#Intent".length, url.length - ";end".length).split(';').filter { it.isNotEmpty() }
        var scheme: String? = null
        for (parameter in parameters) {
            val eq = parameter.indexOf('=')
            if (eq <= 0) return PaymentNavigation.Block(BlockReason.MalformedUrl)
            val key = parameter.substring(0, eq)
            val value = parameter.substring(eq + 1)
            when {
                key == "scheme" -> scheme = value.lowercase()
                key == "package" -> if (value != ALIPAY_PACKAGE) return PaymentNavigation.Block(BlockReason.WrongPackage)
                // A browser fallback is parsed away and never followed; any other field (component, selector, action,
                // category, flags, typed extras) is not something an Alipay hand-off needs.
                key == "S.browser_fallback_url" -> Unit
                else -> return PaymentNavigation.Block(BlockReason.MalformedUrl)
            }
        }
        if (scheme != "alipays" && scheme != "alipay") return PaymentNavigation.Block(BlockReason.UnsupportedScheme)
        if (!target.startsWith("//") || target.length <= 2) return PaymentNavigation.Block(BlockReason.MalformedUrl)
        val uri = "$scheme:$target"
        return if (wellFormed(uri)) PaymentNavigation.Alipay(uri) else PaymentNavigation.Block(BlockReason.MalformedUrl)
    }

    private fun wellFormed(url: String): Boolean = try { URI(url).scheme != null } catch (_: Exception) { false }

    private fun hostOf(url: String): String? = try {
        val uri = URI(url)
        val host = uri.host?.lowercase()
        if (host != null && uri.scheme.equals("https", ignoreCase = true) && uri.rawUserInfo == null && uri.port in setOf(-1, 443) &&
            plausibleHostName(host)) host else null
    } catch (_: Exception) { null }

    /** A registrable-looking DNS name: labels of letters/digits/hyphen, at least two of them, no IP literal, no trailing dot. */
    private fun plausibleHostName(host: String): Boolean {
        if (host.length > 253 || host.endsWith('.') || ':' in host) return false
        val labels = host.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() || it.length > 63 || it.startsWith('-') || it.endsWith('-') ||
                !it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' } }) return false
        return !labels.all { label -> label.all { it in '0'..'9' } } // dotted-decimal IPv4
    }

    companion object {
        const val MAX_URL_LENGTH = 8192
        /** Distinct hosts the chain may add beyond the gateway entry. */
        const val MAX_HOPS = 5
        /** Total top-level navigations of one chain, so a redirect loop among established hosts also ends. */
        const val MAX_NAVIGATIONS = 25
    }
}
