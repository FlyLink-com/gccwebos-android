package com.gccwebos.android

import androidx.compose.ui.unit.dp
import com.gccwebos.android.design.AccountUsageState
import com.gccwebos.android.design.OfficialWebsiteAction
import com.gccwebos.android.design.OfficialWebsiteTitle
import com.gccwebos.android.design.OfficialWebsiteUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {
    @Test fun usedShareIsAPercentWithOneDecimalAndNeverAFalseZero() {
        assertEquals("76.2%", connectionUsedPercent(.762f))
        assertEquals("0%", connectionUsedPercent(0f))
        assertEquals("100%", connectionUsedPercent(1f))
        assertEquals("6%", connectionUsedPercent(.06f))
        assertEquals("<0.1%", connectionUsedPercent(.0001f))
        assertEquals("100%", connectionUsedPercent(1.4f))
        assertEquals("0%", connectionUsedPercent(-.2f))
    }

    @Test fun usedSummaryFallsBackToTheExistingAmountWithoutConfirmedProgress() {
        assertEquals("已用 76.2%", connectionUsedSummary("779.74 GB", .762f))
        assertEquals("已用 3 GB", connectionUsedSummary("3 GB", null))
        assertEquals("已用 暂未获取", connectionUsedSummary("暂未获取", null))
        assertEquals("已用 3 GB", connectionUsedSummary("3 GB", Float.NaN))
    }

    @Test fun spokenUsedDescriptionKeepsTheGbAmountTheVisibleLineDropped() {
        assertEquals("已用 779.74 GB，76.2%", connectionUsedDescription("779.74 GB", .762f))
        assertEquals("已用 暂未获取", connectionUsedDescription("暂未获取", null))
    }

    @Test fun renewLinkOnlyForAVerifiedSignedInAccountPlanWithThePlansRoute() {
        assertTrue(connectionRenewVisible(authenticated = true, manualSource = false, state = AccountUsageState.Available))
        assertFalse(connectionRenewVisible(authenticated = false, manualSource = false, state = AccountUsageState.Available))
        assertFalse(connectionRenewVisible(authenticated = true, manualSource = true, state = AccountUsageState.Available))
        AccountUsageState.entries.filter { it != AccountUsageState.Available }.forEach {
            assertFalse(it.name, connectionRenewVisible(authenticated = true, manualSource = false, state = it))
        }
        assertFalse(connectionRenewVisible(true, false, AccountUsageState.Available, hasPlansRoute = false))
    }

    @Test fun onlyAnEstablishedTunnelEmphasizesTheNodeInfo() {
        ConnectionPhase.entries.forEach { assertEquals(it.name, it == ConnectionPhase.Connected, connectionNodeEmphasized(it)) }
    }

    @Test fun spacingSystemKeepsAtLeastTwentyDpAboveTheBottomNavigation() {
        assertEquals(16.dp, HomeSpacing.SidePadding)
        assertTrue(HomeSpacing.CardGap in 12.dp..16.dp)
        assertEquals(8.dp, homeBottomSpacer())
        assertEquals(HomeSpacing.BottomInset, HomeSpacing.PageBottomPadding + homeBottomSpacer())
        assertEquals(0.dp, homeBottomSpacer(pageBottomPadding = 24.dp))
        assertEquals(20.dp, homeBottomSpacer(pageBottomPadding = 0.dp))
    }

    @Test fun multiPlatformLinkKeepsTheSameTargetWithAnActionLabel() {
        assertEquals("https://flinkvpn.com", OfficialWebsiteUrl)
        assertEquals("其他平台如何使用我们的订阅", OfficialWebsiteTitle)
        assertEquals("查看多端导入教程", OfficialWebsiteAction)
    }
}
