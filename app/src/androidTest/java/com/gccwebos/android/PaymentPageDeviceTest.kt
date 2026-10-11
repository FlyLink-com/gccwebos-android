package com.gccwebos.android

import android.content.Context
import android.content.Intent
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.gccwebos.android.payment.PaymentActivity
import com.gccwebos.android.payment.PaymentPage
import com.gccwebos.android.payment.PaymentTestActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Debug-only: the real payment Activity with a local HTML page under the pinned origin and a stand-in for Alipay inside this
 * app. No xsd8866 request is made and no real Alipay app, order or wallet is involved.
 */
@OptIn(ExperimentalTestApi::class)
class PaymentPageDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val pinned = "https://www.xsd8866.com/pay/test?order=SYNTHETIC"

    private fun test(scenario: String, vararg extras: Pair<String, Boolean>) = ActivityScenario.launch<PaymentTestActivity>(
        Intent(context, PaymentTestActivity::class.java).putExtra(PaymentPage.EXTRA_URL, pinned).putExtra("scenario", scenario)
            .also { intent -> extras.forEach { intent.putExtra(it.first, it.second) } })

    @Test fun windowIsSecureAndTheChromeIsShown() {
        test("idle").use { scenario ->
            compose.waitUntilExactlyOneExists(hasTestTag("payment-web"), 5_000)
            compose.onNodeWithTag("payment-title").assertTextEquals("支付宝付款")
            compose.onNodeWithTag("payment-back").assertIsDisplayed()
            scenario.onActivity { assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
            compose.waitUntilDoesNotExist(hasTestTag("payment-loading"), 10_000)
        }
    }

    @Test fun alipaysAndIntentLinksAreHandedToTheAlipayPackageOnly() {
        for (scenarioName in listOf("alipays", "intent", "iframe")) test(scenarioName).use {
            compose.waitUntilExactlyOneExists(hasTestTag("payment-handed-off"), 10_000)
            compose.onNodeWithTag("payment-alipay-missing").assertDoesNotExist()
        }
    }

    @Test fun missingAlipayLeavesTheGatewayPageWithAPlainHint() {
        test("alipays", "missingAlipay" to true).use { scenario ->
            compose.waitUntilExactlyOneExists(hasTestTag("payment-alipay-missing"), 10_000)
            compose.onNodeWithTag("payment-handed-off").assertDoesNotExist()
            compose.onNodeWithTag("payment-web").assertExists()
            compose.onNodeWithTag("payment-error").assertDoesNotExist()
            assertTrue(scenario.state.isAtLeast(Lifecycle.State.STARTED))
        }
    }

    @Test fun otherOriginsAndWrongPackagesNeverLeaveThePage() {
        test("other-origin").use {
            compose.waitUntilExactlyOneExists(hasTestTag("payment-blocked"), 10_000)
            compose.onNodeWithTag("payment-handed-off").assertDoesNotExist()
        }
        for (scenarioName in listOf("intent-wrong-package", "http", "file")) test(scenarioName).use {
            Thread.sleep(2_000)
            compose.onNodeWithTag("payment-handed-off").assertDoesNotExist()
            compose.onNodeWithTag("payment-alipay-missing").assertDoesNotExist()
            compose.onNodeWithTag("payment-web").assertExists()
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
