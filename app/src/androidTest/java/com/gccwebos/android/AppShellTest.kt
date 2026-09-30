package com.gccwebos.android

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class AppShellTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun signedOutLaunchCannotExposeMainOrVpn() {
        compose.onNodeWithTag("login-screen").assertExists()
        compose.onNodeWithTag("vpn-connect").assertDoesNotExist()
        compose.onNodeWithTag("nav-Connect").assertDoesNotExist()
        compose.onNodeWithTag("page-Profile").assertDoesNotExist()
    }
    @Test fun signedOutActivityRecreationStaysBehindLoginGate() {
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("login-screen").assertExists()
        listOf("Connect", "Lines", "Plans", "Orders", "Profile").forEach { name ->
            compose.onNodeWithTag("nav-$name").assertDoesNotExist()
        }
    }
}
