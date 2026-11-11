package com.gccwebos.android

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.gccwebos.android.payment.FakeAlipayActivity
import com.gccwebos.android.payment.PaymentActivity
import com.gccwebos.android.payment.PaymentPage
import com.gccwebos.android.payment.PaymentTestActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Debug-only: the real payment Activity with a local HTML page under the pinned origin and a stand-in for Alipay inside this
 * app. No xsd8866 request is made and no real Alipay app, order or wallet is involved.
 *
 * Observation design: a successful hand-off puts [FakeAlipayActivity] in front, and the payment Activity's Compose
 * semantics are not observable while another Activity covers it (run 43021307831: the hand-off itself succeeded —
 * GCCPay/FakeAlipay log lines and the START intent are in the device log — while the test timed out waiting for
 * `payment-handed-off`). Hand-off tests therefore observe the delivery itself (the resumed stand-in and the intent
 * it received) and only check the payment chrome after returning to the payment Activity.
 */
@OptIn(ExperimentalTestApi::class)
class PaymentPageDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val pinned = "https://www.xsd8866.com/pay/test?order=SYNTHETIC"

    private fun test(scenario: String, vararg extras: Pair<String, Boolean>) = ActivityScenario.launch<PaymentTestActivity>(
        Intent(context, PaymentTestActivity::class.java).putExtra(PaymentPage.EXTRA_URL, pinned).putExtra("scenario", scenario)
            .also { intent -> extras.forEach { intent.putExtra(it.first, it.second) } })

    private fun resumedFakeAlipay(): FakeAlipayActivity? {
        var found: FakeAlipayActivity? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<FakeAlipayActivity>().firstOrNull()
        }
        return found
    }

    private fun awaitFakeAlipay(): FakeAlipayActivity {
        var found: FakeAlipayActivity? = null
        compose.waitUntil(10_000) { resumedFakeAlipay()?.also { found = it } != null }
        return found!!
    }

    private fun findWebView(view: View): WebView? = when {
        view is WebView -> view
        view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
        else -> null
    }

    private fun webView(scenario: ActivityScenario<PaymentTestActivity>): WebView {
        var found: WebView? = null
        scenario.onActivity { found = findWebView(it.window.decorView) }
        return found!!
    }

    /** WebView state is read on the main thread, where the WebView lives. */
    private fun webViewUrl(scenario: ActivityScenario<PaymentTestActivity>): String? {
        var url: String? = null
        scenario.onActivity { url = findWebView(it.window.decorView)?.url }
        return url
    }

    /** A real input-pipeline tap, so a link navigation carries the user gesture the policy checks. */
    private fun tapOnWebView(scenario: ActivityScenario<PaymentTestActivity>, yOffsetPx: Int) {
        val view = webView(scenario)
        val location = IntArray(2)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { view.getLocationOnScreen(location) }
        val x = location[0] + view.width / 2f
        val y = location[1] + yOffsetPx.toFloat()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(down.downTime, down.eventTime + 60, MotionEvent.ACTION_UP, x, y, 0)
        instrumentation.sendPointerSync(down)
        instrumentation.sendPointerSync(up)
        down.recycle(); up.recycle()
    }

    @Test fun windowIsSecureAndTheChromeIsShown() {
        test("idle").use { scenario ->
            compose.waitUntilExactlyOneExists(hasTestTag("payment-web"), 5_000)
            compose.onNodeWithTag("payment-title").assertTextEquals("支付宝付款")
            compose.onNodeWithTag("payment-back").assertIsDisplayed()
            scenario.onActivity { assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
            compose.waitUntilDoesNotExist(hasTestTag("payment-loading"), 10_000)
        }
    }

    /**
     * One hand-off scenario, observed end to end: the stand-in comes to the front carrying exactly the URI the
     * policy produced, package-scoped to the hand-off package; back on the payment page the handed-off notice is
     * shown and the missing-Alipay hint is not.
     */
    private fun assertHandedOff(scenarioName: String, expectedData: String) {
        test(scenarioName).use {
            val fake = awaitFakeAlipay()
            val delivered = fake.intent
            assertEquals("alipays", delivered.data?.scheme)
            assertEquals(expectedData, delivered.dataString)
            assertEquals(context.packageName, delivered.`package`)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { fake.finish() }
            compose.waitUntilExactlyOneExists(hasTestTag("payment-handed-off"), 10_000)
            compose.onNodeWithTag("payment-alipay-missing").assertDoesNotExist()
        }
    }

    @Test fun alipaysLinksAreHandedToTheAlipayPackageOnly() =
        assertHandedOff("alipays", "alipays://platformapi/startapp?appId=20000067&url=test")

    @Test fun intentLinksAreMappedAndHandedToTheAlipayPackageOnly() =
        assertHandedOff("intent", "alipays://platformapi/startapp?appId=20000067")

    @Test fun iframeAlipaysLinksAreHandedToTheAlipayPackageOnly() =
        assertHandedOff("iframe", "alipays://platformapi/startapp?appId=20000067&frame=1")

    @Test fun missingAlipayLeavesTheGatewayPageWithAPlainHint() {
        test("alipays", "missingAlipay" to true).use { scenario ->
            compose.waitUntilExactlyOneExists(hasTestTag("payment-alipay-missing"), 10_000)
            compose.onNodeWithTag("payment-handed-off").assertDoesNotExist()
            compose.onNodeWithTag("payment-web").assertExists()
            compose.onNodeWithTag("payment-error").assertDoesNotExist()
            assertTrue(scenario.state.isAtLeast(Lifecycle.State.STARTED))
        }
    }

    /**
     * The chain contract (PaymentNavigationPolicy): a script navigation is the page moving on by itself, so it may
     * extend the chain to a new https host — the gateway rotates its cashier host per order. The navigation away
     * from the local page is the observable; nothing is blocked and nothing is handed off.
     */
    @Test fun scriptNavigationToANewHttpsHostExtendsTheChain() {
        test("other-origin").use { scenario ->
            compose.waitUntil(10_000) { webViewUrl(scenario)?.startsWith("https://example.com/") == true }
            compose.onNodeWithTag("payment-blocked").assertDoesNotExist()
            compose.onNodeWithTag("payment-handed-off").assertDoesNotExist()
            assertNull(resumedFakeAlipay())
        }
    }

    /** A tapped link to a host the chain has not established is refused, and a refused top-level navigation shows the full-page error, not the sub-frame notice. */
    @Test fun aTappedLinkToAnUntrustedHttpsHostIsBlockedWithTheFullPageError() {
        test("tap-new-host").use {
            compose.waitUntilExactlyOneExists(hasTestTag("payment-web"), 5_000)
            compose.waitUntilDoesNotExist(hasTestTag("payment-loading"), 10_000)
            tapOnWebView(it, 220)
            compose.waitUntilExactlyOneExists(hasTestTag("payment-error"), 10_000)
            compose.onNodeWithTag("payment-blocked").assertDoesNotExist()
            compose.onNodeWithTag("payment-handed-off").assertDoesNotExist()
            assertNull(resumedFakeAlipay())
        }
    }

    /** A sub-frame may only load established hosts; a refused sub-frame shows the inline `payment-blocked` notice and the page itself stays. */
    @Test fun aSubframeToAnUntrustedHostIsBlockedWithTheInlineNotice() {
        test("iframe-other-origin").use {
            compose.waitUntilExactlyOneExists(hasTestTag("payment-blocked"), 10_000)
            compose.onNodeWithTag("payment-error").assertDoesNotExist()
            compose.onNodeWithTag("payment-web").assertExists()
            compose.onNodeWithTag("payment-handed-off").assertDoesNotExist()
            assertNull(resumedFakeAlipay())
        }
    }

    /** Same contract for a sub-frame that is part of the initial page (a gateway page's own iframe), as opposed to one injected by script. */
    @Test fun aStaticSubframeToAnUntrustedHostIsBlockedWithTheInlineNotice() {
        test("iframe-static-other-origin").use {
            compose.waitUntilExactlyOneExists(hasTestTag("payment-blocked"), 10_000)
            compose.onNodeWithTag("payment-error").assertDoesNotExist()
            compose.onNodeWithTag("payment-web").assertExists()
            compose.onNodeWithTag("payment-handed-off").assertDoesNotExist()
            assertNull(resumedFakeAlipay())
        }
    }

    /** Wrong-package intent links, cleartext http and file URLs are refused at the top level: full-page error, no hand-off, no missing-Alipay hint. */
    @Test fun wrongPackageHttpAndFileNeverLeaveThePage() {
        for (scenarioName in listOf("intent-wrong-package", "http", "file")) test(scenarioName).use {
            compose.waitUntilExactlyOneExists(hasTestTag("payment-error"), 10_000)
            compose.onNodeWithTag("payment-handed-off").assertDoesNotExist()
            compose.onNodeWithTag("payment-alipay-missing").assertDoesNotExist()
            compose.onNodeWithTag("payment-web").assertExists()
            assertNull(resumedFakeAlipay())
        }
    }

    @Test fun backReturnsToTheApp() {
        test("idle").use { scenario ->
            compose.waitUntilExactlyOneExists(hasTestTag("payment-back"), 5_000)
            compose.onNodeWithTag("payment-back").performClick()
            compose.waitUntil(5_000) { scenario.state == Lifecycle.State.DESTROYED }
        }
    }

    @Test fun anUntrustedUrlIsRefusedBeforeAnyPageIsCreated() {
        for (url in listOf("http://www.xsd8866.com/pay", "https://example.com/pay", "alipays://platformapi/startapp")) {
            assertFalse(PaymentPage.accepts(url))
            ActivityScenario.launch<PaymentActivity>(Intent(context, PaymentActivity::class.java).putExtra(PaymentPage.EXTRA_URL, url)).use { scenario ->
                compose.waitUntil(5_000) { scenario.state == Lifecycle.State.DESTROYED }
            }
        }
    }
}
