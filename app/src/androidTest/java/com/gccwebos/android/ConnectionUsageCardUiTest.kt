package com.gccwebos.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gccwebos.android.design.AccountUsagePresentation
import com.gccwebos.android.design.AccountUsageState
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.design.GlassMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** Isolated presentation boundary checks: synthetic snapshots and callbacks, no portal or VPN. */
class ConnectionUsageCardUiTest {
    @get:Rule val compose = createComposeRule()

    private class Fixture {
        var presentation by mutableStateOf(AccountUsagePresentation(AccountUsageState.Available,
            remainingText = "47 GB", usedText = "3 GB", totalText = "50 GB", periodText = "本期用量",
            progress = .06f, cycleText = "2026-09-05 00:00:00 +08:00 — 2026-10-05 00:00:00 +08:00",
            expiresAtText = "2027-09-05 00:00:00 +08:00"))
        var loading by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var showPrimary by mutableStateOf(true)
        var refreshEnabled by mutableStateOf(true)
        var refreshes = 0
        var primaryActions = 0
    }

    private fun show(fixture: Fixture, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GccTheme(materialMode = GlassMode.Solid) {
                    Column(Modifier.width(280.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        ConnectionUsageCard(fixture.presentation, authenticated = true,
                            onRefresh = { fixture.refreshes++ }, loading = fixture.loading, error = fixture.error,
                            refreshEnabled = fixture.refreshEnabled, primaryLabel = "一键连接", primaryEnabled = true,
                            primaryHint = null, showPrimary = fixture.showPrimary,
                            onPrimaryAction = { fixture.primaryActions++ })
                    }
                }
            }
        }
    }

    @Test fun compactDatesKeepFullBoundariesAccessibleAndTrafficTextIntact() {
        val fixture = Fixture()
        show(fixture)
        compose.onNodeWithTag("account-usage-remaining").assertTextEquals("47 GB")
        compose.onNodeWithTag("account-usage-used").assertTextEquals("已用 3 GB")
        compose.onNodeWithTag("account-usage-total").assertTextEquals("总量 50 GB")
        compose.onNodeWithTag("account-usage-cycle").assertContentDescriptionEquals(
            "统计周期 ${fixture.presentation.cycleText}")
        compose.onNodeWithTag("account-usage-time-details").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("统计周期\n${fixture.presentation.cycleText}").assertIsDisplayed()
        compose.onNodeWithText("套餐到期\n${fixture.presentation.expiresAtText}").assertIsDisplayed()
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithTag("vpn-connect").performScrollTo().assertHeightIsAtLeast(56.dp).performClick()
        assertEquals(1, fixture.primaryActions)
        assertEquals(0, fixture.refreshes)
    }

    @Test fun refreshFailureKeepsSnapshotButRoutesPrimaryToRetryAndRespectsBusy() {
        val fixture = Fixture().apply { error = "无法读取" }
        show(fixture)
        compose.onNodeWithTag("account-usage-read-status").assertTextEquals("读取失败 · 未更新")
        compose.onNodeWithTag("account-usage-remaining").assertTextEquals("47 GB")
        compose.onNodeWithTag("vpn-connect").performScrollTo().assertTextEquals("重试").performClick()
        assertEquals(1, fixture.refreshes)
        assertEquals(0, fixture.primaryActions)
        compose.runOnIdle { fixture.loading = true }
        compose.onNodeWithTag("account-usage-read-status").assertTextEquals("读取失败 · 未更新")
        compose.onNodeWithText("正在读取").assertDoesNotExist()
        compose.onNodeWithTag("vpn-connect").assertIsNotEnabled()
        compose.runOnIdle { fixture.loading = false; fixture.showPrimary = false }
        compose.onNodeWithTag("vpn-connect").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-retry").performScrollTo().assertIsEnabled().performClick()
        assertEquals(2, fixture.refreshes)
        compose.runOnIdle { fixture.refreshEnabled = false }
        compose.onNodeWithTag("account-usage-retry").assertIsNotEnabled()
    }

    @Test fun ordinaryReadIsQuietAndCannotEnableAnUnverifiedPrimaryAction() {
        val fixture = Fixture().apply {
            loading = true
            presentation = AccountUsagePresentation(AccountUsageState.Unknown)
        }
        show(fixture)
        compose.onNodeWithText("正在读取").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-read-status").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-remaining").assertTextEquals("暂未获取")
        compose.onNodeWithTag("vpn-connect").performScrollTo().assertTextEquals("重试").assertIsNotEnabled()
            .performTouchInput { click() }
        assertEquals(0, fixture.primaryActions); assertEquals(0, fixture.refreshes)
    }

    @Test fun pendingUsageNeverInventsZeroProgressOrDates() {
        val fixture = Fixture().apply {
            presentation = AccountUsagePresentation(AccountUsageState.Available, periodText = "周期未确认",
                totalText = "50 GB", collectionText = "暂未获取")
        }
        show(fixture)
        compose.onNodeWithTag("account-usage-remaining").assertTextEquals("暂未获取")
        compose.onNodeWithTag("account-usage-used").assertTextEquals("已用 暂未获取")
        compose.onNodeWithTag("account-usage-period").assertTextEquals("周期未确认")
        compose.onNodeWithTag("account-usage-collection").assertTextEquals("暂未获取")
        compose.onNodeWithTag("account-usage-progress").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-cycle").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-expiry").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-time-details").assertDoesNotExist()
    }

    @Test fun longAmountAndFullTimeActionsRemainReachableAtTwoTimesFontScale() {
        val fixture = Fixture().apply { presentation = presentation.copy(remainingText = "8589934592 GB") }
        show(fixture, fontScale = 2f)
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("account-usage-remaining").assertTextEquals("8589934592 GB")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertFalse(results.single().hasVisualOverflow)
        compose.onNodeWithTag("account-usage-time-details").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithTag("vpn-connect").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, fixture.primaryActions)
    }
}
