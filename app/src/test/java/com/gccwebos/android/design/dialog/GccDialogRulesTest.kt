package com.gccwebos.android.design.dialog

import org.junit.Assert.*
import org.junit.Test

class GccDialogRulesTest {
    @Test fun labelUnitsCountCjkAsOneAndLatinAsHalf() {
        assertEquals(4.0, GccDialogRules.labelUnits("退出登录"), 0.0)
        assertEquals(1.0, GccDialogRules.labelUnits("OK"), 0.0)
        assertEquals(3.0, GccDialogRules.labelUnits("取消OK"), 0.0)
        assertEquals(0.0, GccDialogRules.labelUnits(""), 0.0)
    }

    @Test fun oneOrTwoShortActionsShareARow() {
        assertEquals(GccDialogActionLayout.SideBySide, GccDialogRules.actionLayout(listOf("知道了")))
        assertEquals(GccDialogActionLayout.SideBySide, GccDialogRules.actionLayout(listOf("退出登录", "取消")))
        assertEquals(GccDialogActionLayout.SideBySide, GccDialogRules.actionLayout(listOf("去续费", "关闭")))
    }

    @Test fun threeActionsAlwaysStack() {
        assertEquals(GccDialogActionLayout.Stacked, GccDialogRules.actionLayout(listOf("登录", "注册", "取消")))
    }

    @Test fun aLongLabelStacksEvenWithTwoActions() {
        assertEquals(GccDialogActionLayout.Stacked, GccDialogRules.actionLayout(listOf("沿用原记录恢复", "返回")))
        // exactly at the limit still fits
        assertEquals(GccDialogActionLayout.SideBySide, GccDialogRules.actionLayout(listOf("六个汉字字符", "取消")))
        assertEquals(GccDialogActionLayout.Stacked, GccDialogRules.actionLayout(listOf("七个汉字的标签", "取消")))
    }

    @Test fun aSingleLongLabelDoesNotStackAnything() {
        // One action fills the row by itself; there is nothing to stack with.
        assertEquals(GccDialogActionLayout.SideBySide, GccDialogRules.actionLayout(listOf("沿用原记录恢复继续充值")))
    }

    @Test fun motionOnFadesAndScalesMotionOffIsAlreadyThere() {
        val on = GccDialogRules.enter(true)
        assertTrue(on.animated); assertEquals(160, on.fadeMillis); assertEquals(.96f, on.startScale, 0f)
        val off = GccDialogRules.enter(false)
        assertFalse("reduce motion must not animate", off.animated)
        assertEquals(0, off.fadeMillis); assertEquals(1f, off.startScale, 0f)
    }

    @Test fun secureFlagIsPassedThroughUnchanged() {
        assertTrue(GccDialogRules.usesSecureWindow(true))
        assertFalse(GccDialogRules.usesSecureWindow(false))
    }

    @Test fun contentHeightIsCappedButNeverTinyOnShortScreens() {
        assertEquals(640, GccDialogRules.maxContentHeightDp(800))
        assertEquals(680, GccDialogRules.maxContentHeightDp(800, GccDialogRules.SheetMaxHeightFraction))
        assertEquals(240, GccDialogRules.maxContentHeightDp(200))
    }

    @Test fun scrimAndWidthAreTheSharedConstants() {
        assertEquals(.55f, GccDialogRules.ScrimAlpha, 0f)
        assertEquals(420, GccDialogRules.MaxWidthDp)
    }
}

class GccMessageRulesTest {
    @Test fun neverTruncatesAtACommaOrSemicolon() {
        val text = "读取失败，请检查网络后重试；稍后会自动再试。"
        assertEquals(text, GccMessageRules.displayText(text))
    }

    @Test fun tidiesWhitespaceAndNewlinesOnly() {
        assertEquals("已复制 到剪贴板", GccMessageRules.displayText("  已复制 \n 到剪贴板  "))
        assertEquals("", GccMessageRules.displayText("  \n "))
    }

    @Test fun durationsFollowKindAndAction() {
        assertEquals(3_000L, GccMessageRules.durationMillis(GccMessageKind.Success, false))
        assertEquals(3_000L, GccMessageRules.durationMillis(GccMessageKind.Info, false))
        assertEquals(5_000L, GccMessageRules.durationMillis(GccMessageKind.Error, false))
        assertEquals(6_000L, GccMessageRules.durationMillis(GccMessageKind.Error, true))
        assertEquals(6_000L, GccMessageRules.durationMillis(GccMessageKind.Info, true))
    }

    @Test fun kindIsAlsoSpokenNotOnlyColoured() {
        assertEquals("", GccMessageRules.accessibilityPrefix(GccMessageKind.Info))
        assertEquals("成功：", GccMessageRules.accessibilityPrefix(GccMessageKind.Success))
        assertEquals("出错了：", GccMessageRules.accessibilityPrefix(GccMessageKind.Error))
    }
}
