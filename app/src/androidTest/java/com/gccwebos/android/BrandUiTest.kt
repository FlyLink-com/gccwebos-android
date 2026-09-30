package com.gccwebos.android

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test

/** Offline native UI smoke tests. No login, production requests or VPN permissions. */
class BrandUiTest {
    @get:Rule val compose = createComposeRule()
    private fun launch(fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { AppShell() }
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "ui-evidence")
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use { stream ->
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }
    @Test fun fourNativeDestinationsAndDisabledProductionVpn() {
        launch()
        compose.onNodeWithTag("vpn-connect").assertIsNotEnabled()
        capture("connect")
        listOf("Lines", "Plans", "Profile").forEach { page ->
            compose.onNodeWithTag("nav-$page").performClick()
            compose.onNodeWithTag("page-$page").assertIsDisplayed()
            capture(page.lowercase())
        }
        compose.onNodeWithTag("login-submit").performScrollTo().assertIsNotEnabled()
    }
    @Test fun typedAccountSurvivesTabSwitchAndResetCancellation() {
        launch()
        compose.onNodeWithTag("nav-Profile").performClick()
        compose.onNodeWithTag("login-account").performScrollTo().performTextInput("ui-fixture@example.invalid")
        compose.onNodeWithTag("nav-Lines").performClick()
        compose.onNodeWithTag("nav-Profile").performClick()
        compose.onNodeWithTag("login-account").assertTextContains("ui-fixture@example.invalid")
        compose.onNodeWithTag("account-reset").performScrollTo().performClick()
        compose.onNodeWithTag("reset-cancel").performClick()
        compose.onNodeWithTag("reset-confirm").assertDoesNotExist()
        compose.onNodeWithTag("login-account").performScrollTo().assertTextContains("ui-fixture@example.invalid")
    }
    @Test fun doubledFontKeepsNavigationAndFormActionsReachable() {
        launch(2f)
        compose.onNodeWithTag("vpn-connect").performScrollTo().assertIsNotEnabled()
        capture("connect-font-200")
        compose.onNodeWithTag("nav-Profile").performClick()
        compose.onNodeWithTag("login-account").performScrollTo().assertIsDisplayed()
        capture("profile-font-200")
        compose.onNodeWithTag("login-submit").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("account-reset").performScrollTo().performClick()
        compose.onNodeWithTag("reset-cancel").assertIsDisplayed().performClick()
        compose.onNodeWithTag("nav-Connect").performClick()
        compose.onNodeWithTag("page-Connect").assertIsDisplayed()
    }
    @Test fun smallerFontStillRendersFourAccessibleTabs() {
        launch(.85f)
        compose.onNodeWithTag("vpn-connect").assertIsNotEnabled()
        capture("connect-font-85")
        listOf("Connect", "Lines", "Plans", "Profile").forEach { page ->
            compose.onNodeWithTag("nav-$page").assertIsDisplayed()
        }
    }
}
