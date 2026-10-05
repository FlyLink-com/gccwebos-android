package com.gccwebos.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.gccwebos.android.portal.LocalSubscriptionSnapshot
import com.gccwebos.android.portal.LocalSubscriptionStatus
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Real MainActivity recreation, only on a fresh isolated candidate with no saved account.
 * Never enters production My or triggers login/registration/configuration refresh/engine actions.
 * Private My restoration is covered separately by the offline AppShellTest fixture.
 */
class AppShellActivityRecreationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun assertFourTabs() {
        listOf("Connect", "Lines", "Plans", "Profile").forEach { compose.onNodeWithTag("nav-$it").assertExists() }
        compose.onNodeWithTag("nav-Orders").assertDoesNotExist()
    }

    private fun assertSignedOutGuidanceDoesNotAdmitProductionConnect() {
        // This enabled AccountChoice entry is guidance, not a production power command.
        // Do not enter the private account form or request a configuration in this real Activity.
        compose.onNodeWithTag("vpn-connect").performScrollTo()
            .assertTextEquals("开通飞连vpn").assertIsEnabled()
        compose.onNodeWithTag("connection-status").assertTextEquals("未连接")
        compose.onNodeWithTag("connection-node-name", useUnmergedTree = true).assertTextEquals("未选择")
        compose.onNodeWithTag("connection-power").assertDoesNotExist()
        compose.onNodeWithTag("connection-prepare-cancel").assertDoesNotExist()
        // Missing local material cannot issue Connect even when the packed owner is operable.
        assertEquals(ConnectionPowerAction.None, connectionOverviewPresentation(
            LocalSubscriptionSnapshot(LocalSubscriptionStatus.MISSING), Instant.now(),
            ConnectionControlState(ConnectionPhase.Disconnected, canConnect = true)).action)
    }

    @Test fun signedOutRealActivityRecreationKeepsDefaultConnectionAndDisabledProductionPower() {
        compose.onNodeWithTag("page-Connect").assertExists()
        assertFourTabs()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertFourTabs()
        compose.onNodeWithTag("page-Connect").assertExists()
        compose.onNodeWithTag("login-screen").assertDoesNotExist()
        assertSignedOutGuidanceDoesNotAdmitProductionConnect()
        compose.onNodeWithTag("wallet-balance").assertDoesNotExist()
    }

    @Test fun signedOutRealActivityRecreationRestoresNodeTabAndReturnsToConnection() {
        compose.onNodeWithTag("nav-Lines").performClick()
        compose.onNodeWithTag("page-Lines").assertExists()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertFourTabs()
        compose.onNodeWithTag("page-Lines").assertExists()
        compose.onNodeWithTag("nav-Lines").assertIsSelected()
        compose.onNodeWithTag("nodes-account").assertExists()
        compose.onNodeWithTag("login-screen").assertDoesNotExist()
        compose.onNodeWithTag("nav-Connect").performClick()
        compose.onNodeWithTag("page-Connect").assertExists()
        compose.onNodeWithTag("connection-node-name", useUnmergedTree = true).assertTextEquals("未选择")
        assertSignedOutGuidanceDoesNotAdmitProductionConnect()
    }
}
