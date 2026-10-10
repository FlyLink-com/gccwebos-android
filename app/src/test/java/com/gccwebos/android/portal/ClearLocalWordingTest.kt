package com.gccwebos.android.portal

import org.junit.Assert.*
import org.junit.Test

/** The 账户与安全 wipe action says plainly what it does: log out + clear this device's subscription config only. */
class ClearLocalWordingTest {
    @Test fun labelAndDialogNameTheRealEffect() {
        assertEquals("退出登录", CLEAR_LOCAL_LABEL)
        assertEquals("是否退出登录并清除当前设备订阅配置？", CLEAR_LOCAL_TITLE)
        assertEquals("退出登录", CLEAR_LOCAL_CONFIRM)
        assertTrue(CLEAR_LOCAL_BODY.contains("这台设备"))
        assertTrue(CLEAR_LOCAL_BODY.contains("不会删除或更改服务器上的账户"))
    }

    @Test fun oldAmbiguousWordingIsGone() {
        listOf(CLEAR_LOCAL_LABEL, CLEAR_LOCAL_TITLE, CLEAR_LOCAL_BODY, CLEAR_LOCAL_CONFIRM).forEach {
            assertFalse(it.contains("清除本地订阅与登录")); assertFalse(it.contains("清除本地登录与订阅")); assertFalse(it.contains("确认清除"))
        }
    }
}
