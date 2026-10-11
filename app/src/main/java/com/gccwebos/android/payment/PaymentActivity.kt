package com.gccwebos.android.payment

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.gccwebos.android.design.GccTheme

/**
 * Full-screen payment page for the xsd gateway. It loads only the already validated, pinned https URL, keeps every
 * later navigation on that origin, and hands `alipays:`/`alipay:`/`intent:` links to the Alipay app. It shares no
 * data with the rest of the app: no JavaScript bridge, no file or content access, no persisted cookies, no screenshots.
 */
open class PaymentActivity : ComponentActivity() {
    private val state = PaymentPageState()
    private var webView: WebView? = null
    private var policy: PaymentNavigationPolicy? = null
    private var pageUrl = ""
    private var lastHandoff: Pair<String, Long>? = null

    /** The only package an `alipays:` link is delivered to. Overridden by the debug-only test activity. */
    protected open val handoffPackage: String get() = ALIPAY_PACKAGE

    /** Debug-only seam: loads local test content under the pinned origin instead of the network. */
    protected open fun loadPage(view: WebView, url: String) = view.loadUrl(url)

    override fun onCreate(savedInstanceState: Bundle?) {
        // A recreated page (process death) would silently reload an old payment URL; return to the app instead.
        super.onCreate(null)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setBackgroundDrawable(ColorDrawable(0xFF071122.toInt()))
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT))
        val url = intent.getStringExtra(PaymentPage.EXTRA_URL)
        if (savedInstanceState != null || url == null || !PaymentPage.accepts(url)) { finish(); return }
        pageUrl = url
        policy = PaymentNavigationPolicy(url)
        val view = createWebView()
        webView = view
        if (view == null) state.failure = PaymentFailure.Unavailable
        setContent {
            GccTheme {
                PaymentScreen(state, view, onClose = ::finish, onRetry = ::retry, onOpenInBrowser = ::openInBrowser)
            }
        }
        view?.let { loadPage(it, url) }
    }

    private fun createWebView(): WebView? {
        val view = try { WebView(this) } catch (_: Exception) { return null }
        view.isSaveEnabled = false
        view.setBackgroundColor(AndroidColor.WHITE)
        view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        PaymentWebSettings.apply(view.settings)
        CookieManager.getInstance().apply {
            removeAllCookies(null) // Start without any earlier gateway session; nothing from this page outlives it.
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, false)
        }
        view.setDownloadListener { _, _, _, _, _ -> /* Payment pages never download files. */ }
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { state.progress = newProgress }
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                // Debug builds only; level, line and source host, never the message text (it can carry order data).
                debugLog("console ${message.messageLevel()} line=${message.lineNumber()} src=${message.sourceId()?.let { Uri.parse(it).host }}")
                return true
            }
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?) = false
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) = request.deny()
            override fun onShowFileChooser(webView: WebView?, callback: android.webkit.ValueCallback<Array<Uri>>?,
                                           params: FileChooserParams?): Boolean { callback?.onReceiveValue(null); return true }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                when (val decision = policy?.decide(request.url.toString()) ?: PaymentNavigation.Block(BlockReason.MalformedUrl)) {
                    PaymentNavigation.Allow -> false
                    is PaymentNavigation.Alipay -> { handOff(decision.uri); true }
                    is PaymentNavigation.Block -> {
                        debugLog("blocked main=${request.isForMainFrame} redirect=${request.isRedirect} reason=${decision.reason} " +
                            "scheme=${request.url.scheme} host=${request.url.host}")
                        // A blocked top-level navigation leaves the WebView blank: say so and offer the browser, never a white page.
                        if (request.isForMainFrame) state.failure = PaymentFailure.Blocked
                        else if (decision.reason == BlockReason.OtherOrigin) state.blockedOrigin = true
                        true
                    }
                }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                state.failure = null
                state.blockedOrigin = false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                debugLog("finished host=${url?.let { Uri.parse(it).host }}")
                if (state.failure == null) state.pageShown = true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                debugLog("error main=${request.isForMainFrame} code=${error.errorCode} host=${request.url.host}")
                if (request.isForMainFrame) state.failure = PaymentFailure.Network
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                debugLog("http main=${request.isForMainFrame} status=${errorResponse.statusCode} host=${request.url.host}")
                if (request.isForMainFrame && errorResponse.statusCode >= 400) state.failure = PaymentFailure.Network
            }

            // Fail closed: a certificate problem is never offered a "continue anyway".
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                state.failure = PaymentFailure.Insecure
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                state.failure = PaymentFailure.Interrupted
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
                webView = null
                return true
            }
        }
        return view
    }

    /** Debug builds only, tag GCCPay: event kinds, codes and hosts. Never a URL path/query, header, cookie or page text. */
    private fun debugLog(line: String) { if (com.gccwebos.android.BuildConfig.DEBUG) android.util.Log.i("GCCPay", line) }

    private fun handOff(uri: String) {
        val now = SystemClock.elapsedRealtime()
        lastHandoff?.let { (previous, at) -> if (previous == uri && now - at < 1500) return }
        lastHandoff = uri to now
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(handoffPackage)
        try {
            startActivity(intent)
            state.alipayMissing = false
            state.handedOff = true
        } catch (_: ActivityNotFoundException) {
            state.alipayMissing = true
        } catch (_: SecurityException) {
            state.alipayMissing = true
        }
    }

    private fun retry() {
        val view = webView ?: run { finish(); return }
        state.failure = null
        state.progress = 0
        state.pageShown = false
        loadPage(view, pageUrl)
    }

    /** Escape hatch for a page that cannot work inside the app: the original system-browser path for the same order. */
    private fun openInBrowser() {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl)).addCategory(Intent.CATEGORY_BROWSABLE)) }
        catch (_: Exception) { state.failure = PaymentFailure.Unavailable }
    }

    override fun onDestroy() {
        webView?.let { view ->
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.stopLoading()
            view.loadUrl("about:blank")
            view.clearHistory()
            view.clearCache(true)
            view.destroy()
        }
        webView = null
        if (isFinishing || isDestroyed) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
        super.onDestroy()
    }
}

/** Hardened WebView settings, kept in one place so the policy can be read (and asserted) without the Activity. */
internal object PaymentWebSettings {
    fun apply(settings: WebSettings) {
        settings.javaScriptEnabled = true // The gateway's own page needs it to start the Alipay hand-off.
        settings.domStorageEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        @Suppress("DEPRECATION") run {
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.saveFormData = false
        }
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.safeBrowsingEnabled = true
        settings.setGeolocationEnabled(false)
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.mediaPlaybackRequiresUserGesture = true
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
    }
}
