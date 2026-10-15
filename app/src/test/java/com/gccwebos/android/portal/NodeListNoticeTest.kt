package com.gccwebos.android.portal

import org.junit.Assert.*
import org.junit.Test

class NodeListNoticeTest {
    private fun notice(status: LocalSubscriptionStatus, busy: Boolean = false, rows: Boolean = false,
        capability: Boolean = false, auth: Boolean = true, import: Boolean = true) =
        nodeListNotice(status, busy, rows, capability, auth, import)

    @Test fun rowsAreNeverHiddenBehindANotice() {
        LocalSubscriptionStatus.values().forEach { status ->
            assertNull(notice(status, rows = true)); assertNull(notice(status, busy = true, rows = true))
        }
    }

    @Test fun busyWithoutRowsShowsLoadingWithoutAction() {
        LocalSubscriptionStatus.values().forEach { status ->
            val n = notice(status, busy = true)!!
            assertTrue(n.loading); assertEquals(NodeListAction.None, n.action)
        }
    }

    @Test fun everyFailureHasAClearMessageAndARecoveryAction() {
        LocalSubscriptionStatus.values().forEach { status ->
            val n = notice(status)!!
            assertFalse(n.loading); assertTrue(n.message.isNotBlank())
            assertNotEquals("$status must offer a way out", NodeListAction.None, n.action)
            assertFalse(n.actionLabel.isNullOrBlank())
        }
    }

    @Test fun recoveryActionsMatchTheFailure() {
        assertEquals(NodeListAction.Retry, notice(LocalSubscriptionStatus.RETRY_REQUIRED)!!.action)
        assertEquals(NodeListAction.Retry, notice(LocalSubscriptionStatus.STORAGE_ERROR)!!.action)
        assertEquals(NodeListAction.Account, notice(LocalSubscriptionStatus.INVALID_LINK)!!.action)
        assertEquals(NodeListAction.Account, notice(LocalSubscriptionStatus.MISSING, auth = false)!!.action)
        assertEquals(NodeListAction.Retry, notice(LocalSubscriptionStatus.MISSING, capability = true)!!.action)
        assertEquals(NodeListAction.Import, notice(LocalSubscriptionStatus.UNSUPPORTED)!!.action)
        assertEquals(NodeListAction.None, notice(LocalSubscriptionStatus.UNSUPPORTED, import = false)!!.action)
    }

    @Test fun networkDetailIsShownWhenAvailable() {
        val n = nodeListNotice(LocalSubscriptionStatus.RETRY_REQUIRED, false, false, true, true, true, "网络不可用，请检查网络后重试")!!
        assertEquals("网络不可用，请检查网络后重试", n.message)
    }

    @Test fun copyUsesTheProductName() {
        assertTrue(notice(LocalSubscriptionStatus.MISSING, auth = false)!!.message.contains("飞连VPN"))
        LocalSubscriptionStatus.values().forEach { assertFalse(notice(it)!!.message.contains("GCC")) }
    }
}
