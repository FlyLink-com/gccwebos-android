package com.gccwebos.android.payment

import java.net.URI

/** The only app the payment page hands an Alipay deep link to. Other apps that claim the scheme never get it. */
internal const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"

/** Why a navigation was refused; only used for the plain-language notice and tests, never logged with a URL. */
internal enum class BlockReason { OtherOrigin, UnsupportedScheme, MalformedUrl, WrongPackage }

internal sealed interface PaymentNavigation {
    /** Same pinned https origin: let the WebView continue. */
    data object Allow : PaymentNavigation

    /** An Alipay deep link (`alipays:`/`alipay:` URI, already mapped from any `intent:` form) for the Alipay package only. */
    data class Alipay(val uri: String) : PaymentNavigation

    data class Block(val reason: BlockReason) : PaymentNavigation
}

/**
 * Pure decision table for every navigation the payment page attempts. The page URL itself has already been checked by
 * `CommerceJson.trustedPaymentRedirect`; this policy then pins all later navigations to that single https origin and
 * lets only Alipay deep links leave it. Nothing here reads, logs or stores a URL beyond the call.
 */
internal class PaymentNavigationPolicy(pinnedUrl: String) {
    private val host: String = requireNotNull(hostOf(pinnedUrl)) { "Invalid pinned payment URL" }

    fun decide(url: String?): PaymentNavigation {
        if (url.isNullOrEmpty() || url.length > MAX_URL_LENGTH || url.any { it.code !in 0x21..0x7e }) {
            return PaymentNavigation.Block(BlockReason.MalformedUrl)
        }
        val colon = url.indexOf(':')
        if (colon <= 0) return PaymentNavigation.Block(BlockReason.MalformedUrl)
        return when (url.substring(0, colon).lowercase()) {
            "https" -> if (hostOf(url) == host) PaymentNavigation.Allow else PaymentNavigation.Block(BlockReason.OtherOrigin)
            "alipays", "alipay" -> if (wellFormed(url)) PaymentNavigation.Alipay(url) else PaymentNavigation.Block(BlockReason.MalformedUrl)
            "intent" -> fromIntentUri(url)
            else -> PaymentNavigation.Block(BlockReason.UnsupportedScheme)
        }
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
        if (host != null && uri.scheme.equals("https", ignoreCase = true) && uri.rawUserInfo == null && uri.port in setOf(-1, 443)) host else null
    } catch (_: Exception) { null }

    private companion object { const val MAX_URL_LENGTH = 8192 }
}
