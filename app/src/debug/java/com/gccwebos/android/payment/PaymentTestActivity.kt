package com.gccwebos.android.payment

import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebView

/**
 * DEBUG-ONLY test seam, absent from release builds. It is the real [PaymentActivity] with two differences: the page is a
 * local HTML string loaded under the pinned gateway origin (so no network and no real xsd8866 page is involved), and
 * `alipays:` links are delivered to this app's own [FakeAlipayActivity] instead of the Alipay package.
 *
 * `scenario` selects what the local page does on load; `unsecured=true` clears FLAG_SECURE only so a screenshot can be taken.
 */
class PaymentTestActivity : PaymentActivity() {
    override val handoffPackage: String
        get() = if (intent.getBooleanExtra("missingAlipay", false)) "com.gccwebos.nonexistent.alipay" else packageName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("unsecured", false)) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun loadPage(view: WebView, url: String) {
        val scenario = intent.getStringExtra("scenario") ?: "idle"
        if (scenario == "tap-new-host") {
            // One big link and nothing else: the test taps it with a real touch, so the navigation carries a user gesture.
            val link = """<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
                <body style="margin:0"><a href="https://example.com/phish" style="display:block;padding:64px;font:36px sans-serif">continue to payment</a></body>"""
            view.loadDataWithBaseURL(url, link, "text/html", "utf-8", null)
            return
        }
        val action = when (scenario) {
            "alipays" -> "location.href='alipays://platformapi/startapp?appId=20000067&url=test';"
            "intent" -> "location.href='intent://platformapi/startapp?appId=20000067#Intent;scheme=alipays;package=com.eg.android.AlipayGphone;end';"
            "intent-wrong-package" -> "location.href='intent://platformapi/startapp?appId=1#Intent;scheme=alipays;package=evil.app;end';"
            "iframe" -> "var f=document.createElement('iframe');f.src='alipays://platformapi/startapp?appId=20000067&frame=1';document.body.appendChild(f);"
            "other-origin" -> "location.href='https://example.com/phish';"
            "iframe-other-origin" -> "var f=document.createElement('iframe');f.src='https://example.com/frame';document.body.appendChild(f);"
            "http" -> "location.href='http://www.xsd8866.com/pay';"
            "file" -> "location.href='file:///data/local/tmp/x.html';"
            else -> ""
        }
        val extra = if (scenario == "iframe-static-other-origin") "<iframe src='https://example.com/frame'></iframe>" else ""
        val html = """<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
            <body style="font:18px sans-serif;padding:24px"><h2>Local payment test page</h2><p>scenario: $scenario</p>$extra
            <p>This page is served from the app, not from the gateway.</p>
            <script>setTimeout(function(){ $action }, 700);</script></body>"""
        view.loadDataWithBaseURL(url, html, "text/html", "utf-8", null)
    }
}
