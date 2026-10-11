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

    private fun chain(vararg urls: String) = urls.forEach { assertEquals(it, PaymentNavigation.Allow, policy.decide(it, redirect = true)) }

    @Test fun gatewayPagesContinueInsideTheWebView() {
        for (url in listOf(pinned, "https://www.xsd8866.com/", "HTTPS://WWW.XSD8866.COM/next?a=1#frag", "https://www.xsd8866.com:443/x")) {
            assertEquals(url, PaymentNavigation.Allow, policy.decide(url))
        }
        assertEquals(listOf("www.xsd8866.com"), policy.establishedHosts)
    }

    @Test fun theRedirectChainFromTheGatewayMayFollowHttpsHopsToRotatingThirdPartyHosts() {
        chain("https://news.planhappytrip.com/cashier?o=1", "https://pay.other-rotating-domain.example/go")
        assertEquals(listOf("www.xsd8866.com", "news.planhappytrip.com", "pay.other-rotating-domain.example"), policy.establishedHosts)
        // Script/meta navigations without a user gesture count as the page moving on by itself.
        assertEquals(PaymentNavigation.Allow, policy.decide("https://third.example.net/x", userGesture = false))
        // Pages already in the chain stay reachable, also by a tap, and cost no further hop.
        assertEquals(PaymentNavigation.Allow, policy.decide("https://news.planhappytrip.com/other", userGesture = true))
        assertEquals(3, policy.hops)
    }

    @Test fun theChainIsCappedAtFiveHostsBeyondTheGateway() {
        for (i in 1..PaymentNavigationPolicy.MAX_HOPS) assertEquals(PaymentNavigation.Allow, policy.decide("https://hop$i.example.com/", redirect = true))
        assertEquals(PaymentNavigation.Block(BlockReason.HopLimit), policy.decide("https://hop6.example.com/", redirect = true))
        assertEquals(PaymentNavigation.Block(BlockReason.HopLimit), policy.decide("https://hop7.example.com/"))
        assertEquals(PaymentNavigation.Allow, policy.decide("https://hop3.example.com/x")) // established hosts still work
        assertEquals(PaymentNavigationPolicy.MAX_HOPS, policy.hops)
        policy.restart()
        assertEquals(listOf("www.xsd8866.com"), policy.establishedHosts)
        assertEquals(PaymentNavigation.Allow, policy.decide("https://hop6.example.com/", redirect = true))
    }

    @Test fun aRedirectLoopAmongEstablishedHostsEventuallyStops() {
        var last: PaymentNavigation = PaymentNavigation.Allow
        repeat(PaymentNavigationPolicy.MAX_NAVIGATIONS + 1) { last = policy.decide("https://www.xsd8866.com/again", redirect = true) }
        assertEquals(PaymentNavigation.Block(BlockReason.HopLimit), last)
    }

    @Test fun theUserTappingALinkToANewHostIsBlocked() {
        assertEquals(PaymentNavigation.Block(BlockReason.OtherOrigin), policy.decide("https://example.com/", redirect = false, userGesture = true))
        chain("https://news.planhappytrip.com/cashier")
        assertEquals(PaymentNavigation.Block(BlockReason.OtherOrigin), policy.decide("https://elsewhere.example.org/", userGesture = true))
        assertEquals(listOf("www.xsd8866.com", "news.planhappytrip.com"), policy.establishedHosts)
        // A tapped link on an established host that the server then redirects out of is still the chain moving on.
        assertEquals(PaymentNavigation.Allow, policy.decide("https://redirected.example.org/", redirect = true, userGesture = true))
    }

    @Test fun aTapOnTheGatewayPagesAlipayButtonMayGoToAlipaysOwnDomainOnly() {
        chain("https://news.planhappytrip.com/cashier", "https://www.gzshengli.top/pay")
        assertEquals(PaymentNavigation.Allow, policy.decide("https://openapi.alipay.com/gateway.do?x=1", userGesture = true))
        for (url in listOf("https://alipay.com.evil.example/", "https://evilalipay.com/", "https://openapi.alipay.com.evil.example/x", "https://notalipay.com/",
            "https://alipay.com@evil.example/", "https://xalipay.com/")) {
            assertEquals(url, PaymentNavigation.Block(BlockReason.OtherOrigin), policy.decide(url, userGesture = true))
        }
        assertTrue(policy.decide("http://openapi.alipay.com/x", userGesture = true) is PaymentNavigation.Block)
    }

    @Test fun framesNeverExtendTheChain() {
        assertEquals(PaymentNavigation.Block(BlockReason.OtherOrigin), policy.decide("https://ads.example.com/frame", mainFrame = false, redirect = true))
        assertEquals(PaymentNavigation.Allow, policy.decide("https://www.xsd8866.com/frame", mainFrame = false))
        assertEquals(listOf("www.xsd8866.com"), policy.establishedHosts)
    }

    @Test fun hostsThatAreNotPlainDnsNamesOrCarryUserinfoOrPortsAreNeverFollowed() {
        for (url in listOf("https://www.xsd8866.com@evil.example.com/", "https://user@evil.example.com/", "https://evil.example.com:8443/",
            "https://evil.example.com./", "https://127.0.0.1/", "https://10.1.2.3/pay", "https://[::1]/", "https://localhost/", "https://intranet/",
            "https://-bad.example.com/", "https://under_score.example.com/", "https://www.xsd8866.com\\@evil.invalid/")) {
            assertTrue(url, policy.decide(url, redirect = true) is PaymentNavigation.Block)
        }
        assertEquals(listOf("www.xsd8866.com"), policy.establishedHosts)
    }

    @Test fun cleartextAndLocalSchemesAreNeverLoaded() {
        blocked("http://www.xsd8866.com/pay", BlockReason.InsecureScheme)
        blocked("http://news.planhappytrip.com/", BlockReason.InsecureScheme)
        assertEquals(PaymentNavigation.Block(BlockReason.InsecureScheme), policy.decide("http://hop.example.com/", redirect = true))
        for (url in listOf("file:///data/data/com.gccwebos.android/files/x", "content://com.gccwebos.android.updatefiles/x",
            "javascript:alert(1)", "data:text/html,hi", "about:blank", "blob:https://www.xsd8866.com/uuid", "wss://www.xsd8866.com/", "market://details?id=x",
            "weixin://dl/business", "tel:123", "intent-extra://x")) blocked(url, BlockReason.UnsupportedScheme)
        assertEquals(listOf("www.xsd8866.com"), policy.establishedHosts)
    }

    @Test fun malformedOrOversizedUrlsAreBlocked() {
        blocked(null); blocked(""); blocked("no-scheme"); blocked(":x")
        blocked("https://www.xsd8866.com/a b"); blocked("https://www.xsd8866.com/\n"); blocked("https://www.xsd8866.com/支付")
        blocked("https://www.xsd8866.com/" + "a".repeat(9000), BlockReason.MalformedUrl)
        blocked("https://www.xsd8866.com\\@evil.invalid/")
    }

    @Test fun alipayLinksAreHandedOverFromAnyHostOfTheChainAndOnlyToTheAlipayPackage() {
        chain("https://news.planhappytrip.com/cashier")
        val link = "alipays://platformapi/startapp?appId=20000067"
        assertEquals(PaymentNavigation.Alipay(link), policy.decide(link, userGesture = true))
        val activity = File("src/main/java/com/gccwebos/android/payment/PaymentActivity.kt").readText()
        assertTrue(activity.contains("setPackage(handoffPackage)"))
        assertTrue(activity.contains("protected open val handoffPackage: String get() = ALIPAY_PACKAGE"))
        assertFalse("no chooser", activity.contains("createChooser"))
        assertEquals("com.eg.android.AlipayGphone", ALIPAY_PACKAGE)
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

    @Test fun noticesNeverShowAUrlOrOrderData() {
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

    @Test fun noHostIsShownAndTheChainNeverSharesAnythingWithTheApp() {
        val screen = File("src/main/java/com/gccwebos/android/payment/PaymentScreen.kt").readText()
        assertFalse("no domain in the UI", screen.contains("payment-host"))
        assertFalse(screen.contains(".host"))
        val activity = File("src/main/java/com/gccwebos/android/payment/PaymentActivity.kt").readText()
        assertFalse("host never reaches page state", activity.contains("state.host"))
        assertTrue(activity.contains("override fun onCreateWindow") && activity.contains("= false"))
        assertTrue(activity.contains("request.hasGesture()"))
        assertTrue(activity.contains("setSupportMultipleWindows(false)"))
        assertTrue(activity.contains("policy?.restart()"))
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
