package com.gccwebos.android.portal

import org.junit.Assert.*
import org.junit.Test

/** The single 退出登录 action says plainly what it does: log out + clear this device's account subscription only. */
class LogoutWordingTest {
    @Test fun labelAndDialogNameTheRealEffect() {
        assertEquals("退出登录", LOGOUT_LABEL)
        assertEquals("退出登录？", LOGOUT_TITLE)
        assertTrue("a title, not a sentence", LOGOUT_TITLE.length <= 8)
        assertEquals("退出登录", LOGOUT_CONFIRM)
        assertTrue(LOGOUT_BODY.contains("这台设备"))
        assertTrue(LOGOUT_BODY.contains("不会删除或更改服务器上的账户"))
        assertTrue(LOGOUT_BODY.contains("你自己导入的配置会保留"))
    }

    @Test fun theOldSeparateClearFeatureWordingIsGone() {
        listOf(LOGOUT_LABEL, LOGOUT_TITLE, LOGOUT_BODY, LOGOUT_CONFIRM).forEach {
            assertFalse(it.contains("清除本地订阅与登录")); assertFalse(it.contains("清除本地订阅并重置登录"))
            assertFalse(it.contains("保留本地订阅")); assertFalse(it.contains("确认清除"))
        }
    }
}
