package com.gccwebos.android.payment

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Synthetic URLs only; the real gateway page and the Alipay app are never contacted. */
class PaymentNavigationPolicyTest {
    private val pinned = "https://www.xsd8866.com/pay/index.html?order=SYNTHETIC"
    private val policy = PaymentNavigationPolicy(pinned)
    private fun blocked(url: String?, reason: BlockReason? = null) {
        val decision = policy.decide(url)
        assertTrue("$url -> $decision", decision is PaymentNavigation.Block)
        if (reason != null) assertEquals(reason, (decision as PaymentNavigation.Block).reason)
    }

    @Test fun samePinnedOriginContinuesInsideTheWebView() {
        for (url in listOf(pinned, "https://www.xsd8866.com/", "HTTPS://WWW.XSD8866.COM/next?a=1#frag", "https://www.xsd8866.com:443/x")) {
            assertEquals(url, PaymentNavigation.Allow, policy.decide(url))
        }
    }

    @Test fun otherHttpsOriginsAreBlockedIncludingLookalikes() {
        for (url in listOf("https://example.com/", "https://xsd8866.com/", "https://pay.xsd8866.com/", "https://www.xsd8866.com.evil.invalid/",
            "https://evilwww.xsd8866.com/", "https://www.xsd8866.com@evil.invalid/", "https://user@www.xsd8866.com/",
            "https://www.xsd8866.com:8443/", "https://www.xsd8866.com./", "https://openapi.alipay.com/gateway.do?x=1")) {
            blocked(url, BlockReason.OtherOrigin)
        }
    }

    @Test fun cleartextAndLocalSchemesAreNeverLoaded() {
        for (url in listOf("http://www.xsd8866.com/pay", "file:///data/data/com.gccwebos.android/files/x", "content://com.gccwebos.android.updatefiles/x",
            "javascript:alert(1)", "data:text/html,hi", "about:blank", "blob:https://www.xsd8866.com/uuid", "wss://www.xsd8866.com/", "market://details?id=x",
            "weixin://dl/business", "tel:123", "intent-extra://x")) blocked(url, BlockReason.UnsupportedScheme)
    }

    @Test fun malformedOrOversizedUrlsAreBlocked() {
        blocked(null); blocked(""); blocked("no-scheme"); blocked(":x")
        blocked("https://www.xsd8866.com/a b"); blocked("https://www.xsd8866.com/\n"); blocked("https://www.xsd8866.com/支付")
        blocked("https://www.xsd8866.com/" + "a".repeat(9000), BlockReason.MalformedUrl)
        blocked("https://www.xsd8866.com\\@evil.invalid/")
    }

    @Test fun alipayLinksAreHandedOverUnchanged() {
        val direct = "alipays://platformapi/startapp?appId=20000067&url=https%3A%2F%2Fexample.invalid%2Fx"
        assertEquals(PaymentNavigation.Alipay(direct), policy.decide(direct))
        val legacy = "alipay://platformapi/startapp?saId=10000007"
        assertEquals(PaymentNavigation.Alipay(legacy), policy.decide(legacy))
        assertEquals(PaymentNavigation.Alipay("AliPayS://platformapi/startapp?a=1"), policy.decide("AliPayS://platformapi/startapp?a=1"))
    }

    @Test fun intentLinksMapToAnAlipaySchemeUriForTheAlipayPackage() {
        val withPackage = "intent://platformapi/startapp?appId=20000067&url=x#Intent;scheme=alipays;package=$ALIPAY_PACKAGE;end"
        assertEquals(PaymentNavigation.Alipay("alipays://platformapi/startapp?appId=20000067&url=x"), policy.decide(withPackage))
        val noPackage = "intent://platformapi/startapp?appId=1#Intent;scheme=alipay;end"
        assertEquals(PaymentNavigation.Alipay("alipay://platformapi/startapp?appId=1"), policy.decide(noPackage))
        val fallbackIgnored = "intent://platformapi/startapp?appId=1#Intent;scheme=alipays;S.browser_fallback_url=https%3A%2F%2Fevil.invalid;end"
        assertEquals(PaymentNavigation.Alipay("alipays://platformapi/startapp?appId=1"), policy.decide(fallbackIgnored))
    }

    @Test fun hostileIntentLinksAreRejected() {
        blocked("intent://platformapi/startapp#Intent;scheme=alipays;package=evil.app;end", BlockReason.WrongPackage)
        blocked("intent://platformapi/startapp#Intent;scheme=alipays;package=${ALIPAY_PACKAGE}x;end", BlockReason.WrongPackage)
        blocked("intent://x/y#Intent;scheme=http;end", BlockReason.UnsupportedScheme)
        blocked("intent://x/y#Intent;scheme=file;end", BlockReason.UnsupportedScheme)
        blocked("intent://x/y#Intent;end", BlockReason.UnsupportedScheme)
        blocked("intent://x/y#Intent;scheme=alipays;component=evil.app/.Main;end", BlockReason.MalformedUrl)
        blocked("intent://x/y#Intent;scheme=alipays;SEL;component=evil.app/.Main;end", BlockReason.MalformedUrl)
        blocked("intent://x/y#Intent;scheme=alipays;action=android.intent.action.DELETE;end", BlockReason.MalformedUrl)
        blocked("intent://x/y#Intent;scheme=alipays;S.extra=1;end", BlockReason.MalformedUrl)
        blocked("intent://x/y#Intent;scheme=alipays", BlockReason.MalformedUrl)
        blocked("intent:#Intent;scheme=alipays;end", BlockReason.MalformedUrl)
        blocked("intent://#Intent;scheme=alipays;end", BlockReason.MalformedUrl)
        blocked("intent:x/y#Intent;scheme=alipays;end", BlockReason.MalformedUrl)
    }

    @Test fun policyRequiresAnHttpsPinnedUrl() {
        for (url in listOf("http://www.xsd8866.com/", "alipays://x", "https://user@www.xsd8866.com/", "https://www.xsd8866.com:8443/", "", "not a url")) {
            assertThrows(IllegalArgumentException::class.java) { PaymentNavigationPolicy(url) }
        }
    }

    @Test fun launchAcceptsOnlyTheValidatedGatewayRedirect() {
        assertTrue(PaymentPage.accepts("https://www.xsd8866.com/pay/index.html?order=SYNTHETIC"))
        for (url in listOf(null, "", "http://www.xsd8866.com/pay", "https://www.xsd8866.com.evil.invalid/pay", "https://www.sandbox.paypal.com/pay",
            "https://www.xsd8866.com/pay#fragment", "https://www.xsd8866.com:8443/pay", "alipays://platformapi/startapp?a=1", "https://127.0.0.1/pay")) {
            assertFalse("$url", PaymentPage.accepts(url))
        }
    }

    @Test fun noticesNeverShowAHostOrUrl() {
        val copy = listOf(PaymentCopy.Title, PaymentCopy.Loading, PaymentCopy.AlipayMissing, PaymentCopy.HandedOff, PaymentCopy.BlockedOrigin) +
            PaymentFailure.entries.map(PaymentCopy::failure)
        for (text in copy) assertFalse(text, Regex("xsd8866|gccwebos|https?:|\\.com", RegexOption.IGNORE_CASE).containsMatchIn(text))
    }

    @Test fun walletDialogsNoLongerShowTheHostConfirmation() {
        val source = listOf("src/main/java/com/gccwebos/android/commerce/WalletPanel.kt").map { File(it) }.single { it.exists() }.readText()
        assertFalse(source.contains("将打开"))
        assertFalse(source.contains("portal.gccwebos.com"))
        assertFalse(source.contains("URI(it.url).host"))
        // The pinning check stays: opening is still gated by the validated redirect.
        assertTrue(source.contains("state.canOpenPayment(order, now)"))
    }

    @Test fun webViewHardeningStaysInTheActivitySource() {
        val source = File("src/main/java/com/gccwebos/android/payment/PaymentActivity.kt").readText()
        for (required in listOf("FLAG_SECURE", "handler.cancel()", "MIXED_CONTENT_NEVER_ALLOW", "safeBrowsingEnabled = true", "allowFileAccess = false",
            "allowContentAccess = false", "domStorageEnabled = false", "setAcceptThirdPartyCookies(view, false)", "removeAllCookies")) {
            assertTrue(required, source.contains(required))
        }
        for (forbidden in listOf("addJavascriptInterface", "handler.proceed", "setWebContentsDebuggingEnabled", "onReceivedClientCertRequest")) {
            assertFalse(forbidden, source.contains(forbidden))
        }
        assertTrue(source.contains("setPackage(handoffPackage)"))
        assertFalse("never parse an intent URI", source.contains("parseUri"))
    }
}
