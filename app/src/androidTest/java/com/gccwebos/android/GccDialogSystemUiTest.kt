package com.gccwebos.android

import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.width
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.design.LocalAnimatorScaleOverride
import com.gccwebos.android.design.dialog.GccDialog
import com.gccwebos.android.design.dialog.GccDialogAction
import com.gccwebos.android.design.dialog.GccDialogIcon
import com.gccwebos.android.design.dialog.GccDialogTone
import com.gccwebos.android.design.dialog.GccMenu
import com.gccwebos.android.design.dialog.GccMenuItem
import com.gccwebos.android.design.dialog.GccMessageHost
import com.gccwebos.android.design.dialog.GccMessageKind
import com.gccwebos.android.design.dialog.GccSheet
import com.gccwebos.android.design.dialog.LocalGccMessages
import com.gccwebos.android.design.dialog.rememberGccMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The shared dialog / sheet / menu / message components, rendered for real (tags, layout, secure flag, motion). */
class GccDialogSystemUiTest {
    @get:Rule val compose = createComposeRule()

    private fun host(content: @androidx.compose.runtime.Composable () -> Unit) =
        compose.setContent { GccTheme { Box(Modifier.fillMaxSize()) { content() } } }

    /** FLAG_SECURE of the window this composable is drawn in (dialog and sheet windows have their own). */
    @androidx.compose.runtime.Composable
    private fun SecureProbe(out: (Boolean) -> Unit) {
        val flags = (LocalView.current.rootView.layoutParams as? WindowManager.LayoutParams)?.flags ?: 0
        out(flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test fun twoActionsKeepTheirTagsAndCallbacks() {
        var confirmed = 0; var cancelled = 0
        host { GccDialog({}, title = "退出登录？", body = "说明文字", icon = GccDialogIcon.Warning, tone = GccDialogTone.Danger,
            testTag = "d", primary = GccDialogAction("退出登录", { confirmed++ }, testTag = "d-ok"),
            secondary = GccDialogAction("取消", { cancelled++ }, testTag = "d-cancel")) }
        compose.onNodeWithTag("d-title").assertTextEquals("退出登录？")
        compose.onNodeWithTag("d-body").assertTextEquals("说明文字")
        compose.onNodeWithTag("d-cancel").assertTextEquals("取消").performClick()
        compose.onNodeWithTag("d-ok").assertTextEquals("退出登录").performClick()
        compose.runOnIdle { assertEquals(1, confirmed); assertEquals(1, cancelled) }
    }

    @Test fun shortActionsShareARowWithPrimaryOnTheRight() {
        host { GccDialog({}, title = "t", testTag = "d", primary = GccDialogAction("确定", {}, testTag = "p"),
            secondary = GccDialogAction("取消", {}, testTag = "s")) }
        val p = compose.onNodeWithTag("p").getUnclippedBoundsInRoot()
        val s = compose.onNodeWithTag("s").getUnclippedBoundsInRoot()
        assertEquals("same row", p.top, s.top)
        assertTrue("primary is right of secondary", p.left > s.left)
    }

    @Test fun threeActionsStackWithPrimaryFirst() {
        host { GccDialog({}, title = "开通套餐", body = "登录后即可", testTag = "d", primary = GccDialogAction("登录", {}, testTag = "p"),
            secondary = GccDialogAction("注册", {}, testTag = "s"), tertiary = GccDialogAction("取消", {}, testTag = "t")) }
        val p = compose.onNodeWithTag("p").getUnclippedBoundsInRoot()
        val s = compose.onNodeWithTag("s").getUnclippedBoundsInRoot()
        val t = compose.onNodeWithTag("t").getUnclippedBoundsInRoot()
        assertTrue("primary above secondary above tertiary", p.top < s.top && s.top < t.top)
        assertEquals("equal width, full row", p.width, s.width)
        assertEquals(p.left, t.left)
    }

    @Test fun aLongLabelStacksTwoActions() {
        host { GccDialog({}, title = "t", testTag = "d", primary = GccDialogAction("沿用原记录恢复", {}, testTag = "p"),
            secondary = GccDialogAction("返回", {}, testTag = "s")) }
        assertTrue(compose.onNodeWithTag("p").getUnclippedBoundsInRoot().top < compose.onNodeWithTag("s").getUnclippedBoundsInRoot().top)
    }

    @Test fun loadingDisablesEveryActionAndNothingFires() {
        var fired = 0
        host { GccDialog({ fired++ }, title = "t", testTag = "d",
            primary = GccDialogAction("确定", { fired++ }, loading = true, testTag = "p"),
            secondary = GccDialogAction("取消", { fired++ }, testTag = "s")) }
        compose.onNodeWithTag("s").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, fired) }
    }

    @Test fun secureDialogKeepsFlagSecureAndPlainOneDoesNot() {
        var secureSeen: Boolean? = null; var plainSeen: Boolean? = null
        var showSecure by mutableStateOf(true)
        host {
            if (showSecure) GccDialog({}, title = "s", secure = true, primary = GccDialogAction("ok", {}),
                content = { SecureProbe { secureSeen = it } })
            else GccDialog({}, title = "p", primary = GccDialogAction("ok", {}), content = { SecureProbe { plainSeen = it } })
        }
        compose.runOnIdle { assertEquals(true, secureSeen) }
        compose.runOnIdle { showSecure = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(false, plainSeen) }
    }

    @Test fun reduceMotionShowsTheDialogImmediately() {
        compose.setContent { GccTheme { CompositionLocalProvider(LocalAnimatorScaleOverride provides 0f) {
            Box(Modifier.fillMaxSize()) { GccDialog({}, title = "无动画", testTag = "d", primary = GccDialogAction("好", {}, testTag = "p")) }
        } } }
        compose.onNodeWithTag("d-title").assertIsDisplayed()
        compose.onNodeWithTag("p").assertIsDisplayed()
    }

    @Test fun sheetCloseButtonAndPinnedActionDismiss() {
        var dismissed = 0
        host { GccSheet({ dismissed++ }, title = "详情", showClose = true, closeTag = "x",
            actions = { close -> com.gccwebos.android.design.dialog.GccSecondaryButton(GccDialogAction("知道了", close, testTag = "ok")) }) {
            Text("内容一")
        } }
        compose.onNodeWithText("内容一").assertIsDisplayed()
        compose.onNodeWithTag("ok").assertIsDisplayed()
        compose.onNodeWithTag("x").performClick()
        compose.waitUntil(5_000) { dismissed == 1 }
    }

    @Test fun secureSheetKeepsFlagSecure() {
        var seen: Boolean? = null
        host { GccSheet({}, title = "安全", secure = true) { SecureProbe { seen = it }; Text("x") } }
        compose.runOnIdle { assertEquals(true, seen) }
    }

    @Test fun menuRowsAreClickableAndDestructiveIsMarked() {
        var renamed = 0; var deleted = 0; var disabled = 0
        host { GccMenu(true, {}) {
            GccMenuItem("重命名", { renamed++ }, testTag = "m-rename")
            GccMenuItem("删除", { deleted++ }, destructive = true, testTag = "m-delete")
            GccMenuItem("不可用", { disabled++ }, enabled = false, testTag = "m-off")
        } }
        compose.onNodeWithTag("m-off").assertIsNotEnabled()
        compose.onNodeWithTag("m-rename").performClick()
        compose.onNodeWithTag("m-delete").performClick()
        compose.runOnIdle { assertEquals(1, renamed); assertEquals(1, deleted); assertEquals(0, disabled) }
    }

    @Test fun messageShowsTheWholeSentenceAndRunsTheAction() {
        var retried = 0
        var send: (() -> Unit)? = null
        host {
            val messages = rememberGccMessages()
            CompositionLocalProvider(LocalGccMessages provides messages) {
                val m = LocalGccMessages.current
                send = { m.show("读取失败，请检查网络后重试", GccMessageKind.Error, "重试", { retried++ }) }
                GccMessageHost(messages)
            }
        }
        compose.runOnIdle { send!!() }
        compose.onNodeWithText("读取失败，请检查网络后重试").assertIsDisplayed()
        compose.onNodeWithTag("gcc-message-action").performClick()
        compose.runOnIdle { assertEquals(1, retried) }
    }

    @Test fun anIdleHostDrawsNothing() {
        host { GccMessageHost(rememberGccMessages()) }
        compose.onNodeWithTag("gcc-message").assertDoesNotExist()
    }

}
