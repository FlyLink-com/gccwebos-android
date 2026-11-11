package com.gccwebos.android

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity

/**
 * Debug-only test seam, in the same pattern as payment.PaymentTestActivity: an
 * activity whose window configuration carries fontScale 2.0, the way a real device
 * with doubled system font presents it. WalletCompactDoubledFontUiTest launches it
 * because the balance-details bottom sheet is a separate window composition — a
 * subtree LocalDensity override in a test never reaches it (measured in run
 * 43003132701) — so the doubled-font arrangement contract can only be exercised
 * through the window configuration itself. Never shipped: debug source set only.
 */
class DoubledFontComponentActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        config.fontScale = 2f
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }
}
