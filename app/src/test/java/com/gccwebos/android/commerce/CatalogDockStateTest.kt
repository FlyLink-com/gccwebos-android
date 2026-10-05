package com.gccwebos.android.commerce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Presentation ownership only; actual account/quote admission remains in CommercePanel and its controller. */
class CatalogDockStateTest {
    private val enabled = CatalogPurchaseSummary("服务器商品", "73 Coins", enabled = true, busy = false)

    @Test fun showNeverExecutesAnActionAndReplacingTheActionDropsTheOldClosure() {
        val dock = CatalogDockState()
        val owner = Any()
        var oldCalls = 0
        var newCalls = 0
        dock.show(owner, enabled) { oldCalls++ }
        dock.show(owner, enabled) { newCalls++ }
        assertEquals(0, oldCalls)
        assertEquals(0, newCalls)
        dock.continuePurchase()
        assertEquals(0, oldCalls)
        assertEquals(1, newCalls)
    }

    @Test fun aDisposedOldPageCannotClearTheReplacementPagesAction() {
        data class Owner(val name: String)
        val oldOwner = Owner("same-value")
        val newOwner = Owner("same-value")
        val dock = CatalogDockState()
        var oldCalls = 0
        var newCalls = 0
        dock.show(oldOwner, enabled) { oldCalls++ }
        dock.show(newOwner, enabled) { newCalls++ }
        dock.clear(oldOwner)
        assertSame(enabled, dock.summary)
        dock.continuePurchase()
        assertEquals(0, oldCalls)
        assertEquals(1, newCalls)
        dock.clear(newOwner)
        assertNull(dock.summary)
        dock.continuePurchase()
        assertEquals(1, newCalls)
    }

    @Test fun disabledOrHiddenSummaryRevokesThePreviouslyEnabledAction() {
        val owner = Any()
        val dock = CatalogDockState()
        var calls = 0
        dock.show(owner, enabled) { calls++ }
        dock.show(owner, enabled.copy(enabled = false, busy = true)) { calls++ }
        dock.continuePurchase()
        dock.show(owner, null) { calls++ }
        dock.continuePurchase()
        assertEquals(0, calls)
        assertNull(dock.summary)
    }

    @Test fun clearingPageOwnershipLeavesNoActionToReplay() {
        val owner = Any()
        val dock = CatalogDockState()
        var calls = 0
        dock.show(owner, enabled) { calls++ }
        dock.clear(owner)
        dock.clear(owner)
        dock.continuePurchase()
        assertNull(dock.summary)
        assertEquals(0, calls)
    }
}
