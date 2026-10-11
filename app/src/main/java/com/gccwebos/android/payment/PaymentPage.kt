package com.gccwebos.android.payment

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.gccwebos.android.commerce.CommerceJson

/** Launch contract for the in-app payment page. The URL is re-validated here and again inside the Activity. */
internal object PaymentPage {
    const val EXTRA_URL = "com.gccwebos.android.payment.URL"

    /** Recharge payments are Alipay through the xsd gateway; this is the same pinned-origin rule the order projection used. */
    const val ADAPTER_KEY = "xsd_v1"

    fun accepts(url: String?): Boolean = url != null && CommerceJson.trustedPaymentRedirect(url, ADAPTER_KEY)

    fun intent(context: Context, url: String): Intent {
        require(accepts(url)) { "Untrusted payment URL" }
        return Intent(context, PaymentActivity::class.java).putExtra(EXTRA_URL, url)
    }

    /** Throws for an untrusted URL, so `RechargeFlowController.openPayment` reports the order as unopened and keeps it. */
    fun open(context: Context, url: String) = context.startActivity(intent(context, url))
}

/** Page state shown by the Compose chrome around the WebView. */
internal enum class PaymentFailure { Network, Insecure, Interrupted, Unavailable, Blocked }

internal class PaymentPageState {
    var progress by androidx.compose.runtime.mutableIntStateOf(0)
    var pageShown by androidx.compose.runtime.mutableStateOf(false)
    var failure by androidx.compose.runtime.mutableStateOf<PaymentFailure?>(null)
    var alipayMissing by androidx.compose.runtime.mutableStateOf(false)
    var handedOff by androidx.compose.runtime.mutableStateOf(false)
    var blockedOrigin by androidx.compose.runtime.mutableStateOf(false)
}
