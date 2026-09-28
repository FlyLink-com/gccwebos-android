package com.gccwebos.android

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class AppShellTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun realVpnIsExplicitlyUnavailable() {
        compose.onNodeWithTag("vpn-connect").assertIsNotEnabled()
    }

    @Test fun allFourDestinationsRemainAccessible() {
        listOf("Lines", "Plans", "Profile", "Connect").forEach { name ->
            compose.onNodeWithTag("nav-$name").performClick()
            compose.onNodeWithTag("page-$name").assertExists()
        }
    }
}
