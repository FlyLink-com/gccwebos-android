package com.gccwebos.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.gccwebos.android.design.BrandBackdrop
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.design.LocalAnimatorScaleOverride
import com.gccwebos.android.design.dialog.*
import com.gccwebos.android.portal.LogoutDialog
import java.io.File
import org.junit.Rule
import org.junit.Test

/**
 * Screenshot gallery for the shared dialog system (saved to the app's external files dir; no account, no network).
 * The real [LogoutDialog] and the real sheet components are rendered over the real backdrop.
 */
class DialogSystemEvidenceTest {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, reduceMotion: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { GccTheme {
            CompositionLocalProvider(LocalAnimatorScaleOverride provides (if (reduceMotion) 0f else null)) {
                Box(Modifier.fillMaxSize()) { BrandBackdrop(); content() }
            }
        } }
        compose.waitForIdle(); Thread.sleep(1500)
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun logoutDialog() = shot("01-logout-dialog") { LogoutDialog({}, {}) }

    @Test fun logoutDialogReduceMotion() = shot("02-logout-dialog-reduce-motion", reduceMotion = true) { LogoutDialog({}, {}) }

    @Test fun threeActionsStacked() = shot("03-three-actions-stacked") {
        GccDialog({}, title = "登录后开通套餐", body = "登录或注册后即可查看并购买套餐。", icon = GccDialogIcon.Info,
            primary = GccDialogAction("登录", {}), secondary = GccDialogAction("注册", {}), tertiary = GccDialogAction("取消", {}))
    }

    @Test fun neutralTwoActions() = shot("04-neutral-two-actions") {
        GccDialog({}, title = "套餐即将到期", body = "套餐还剩 3 天到期，续费后继续使用。", icon = GccDialogIcon.Warning,
            primary = GccDialogAction("去续费", {}), secondary = GccDialogAction("关闭", {}))
    }

    @Test fun loadingState() = shot("05-loading-state") {
        GccDialog({}, title = "正在提交", body = "请稍候…", icon = GccDialogIcon.Success,
            primary = GccDialogAction("确定", {}, loading = true), secondary = GccDialogAction("取消", {}))
    }

    @Test fun sheetWithPinnedAction() = shot("06-sheet-pinned-action") {
        GccSheet({}, title = "套餐时间", actions = { close -> GccSecondaryButton(GccDialogAction("知道了", close)) }) {
            Text("统计周期\n2026.10.01 - 2026.10.31"); Text("套餐到期\n2026.11.01 00:00")
        }
    }

    @Test fun sheetWithCloseButton() = shot("07-sheet-close-button") {
        GccSheet({}, title = "余额明细", showClose = true) { Text("可用余额 120 Coins"); Text("订单预留和待确认奖励不计入可用余额。") }
    }

    @Test fun menu() = shot("08-menu") {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            GccMenu(true, {}) {
                GccMenuItem("查看", {}); GccMenuItem("重命名", {}); GccMenuItem("替换配置", {})
                GccMenuItem("删除", {}, destructive = true)
            }
        }
    }

    @Test fun messageBar() {
        lateinit var send: (String, GccMessageKind, String?) -> Unit
        compose.setContent { GccTheme { Box(Modifier.fillMaxSize()) {
            BrandBackdrop()
            val messages = rememberGccMessages()
            send = { t, k, a -> messages.show(t, k, a, {}) }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { GccMessageHost(messages) }
        } } }
        compose.waitForIdle()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        fun snap(name: String) {
            Thread.sleep(900)
            val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.runOnIdle { send("已复制", GccMessageKind.Success, null) }; snap("09-message-success")
        Thread.sleep(3500)
        compose.runOnIdle { send("读取失败，请检查网络后重试，若仍无法读取可稍后再试或联系客服。", GccMessageKind.Error, "重试") }; snap("10-message-error-action")
    }
}
