package com.gccwebos.android.commerce

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class LedgerPresentationTest {
    private val t0 = Instant.parse("2026-10-02T12:00:00Z")
    private val t1 = Instant.parse("2026-10-02T12:01:00Z")
    private val t2 = Instant.parse("2026-10-02T12:02:00Z")

    private fun entry(ref: String, category: String, coin: String, amount: Long, total: Long,
                      available: Long?, pending: Long?, held: Long?, order: String?, at: Instant) =
        WalletLedgerEntry(ref, category, coin, amount, available, pending, held, total, order, at)

    @Test fun hidesOnlyPureReserveAndReleaseBookkeeping() {
        val hold = entry("h1", "available_to_held", "paid_coins", 17, 0, -17, 0, 17, "ord_a", t0)
        val release = entry("r1", "held_to_available", "paid_coins", 17, 0, 17, 0, -17, "ord_a", t1)
        val consume = entry("c1", "held_to_consumed", "paid_coins", 17, -17, 0, 0, -17, "ord_a", t2)
        val credit = entry("cr1", "credit", "paid_coins", 100, 100, null, null, null, "rech_a", t0)
        val pending = entry("p1", "pending_to_available", "reward_coins", 5, 0, 5, -5, 0, null, t1)
        assertTrue(LedgerPresentation.isHiddenBookkeeping(hold))
        assertTrue(LedgerPresentation.isHiddenBookkeeping(release))
        assertFalse(LedgerPresentation.isHiddenBookkeeping(consume))
        assertFalse(LedgerPresentation.isHiddenBookkeeping(credit))
        assertFalse(LedgerPresentation.isHiddenBookkeeping(pending))
        val groups = LedgerPresentation.displayGroups(listOf(hold, release, consume, credit, pending))
        assertEquals(listOf("c1", "cr1", "p1"), groups.map { it.id })
    }

    @Test fun groupsSameOrderPaidAndBonusIntoOneBusinessLine() {
        val paidHold = entry("h_paid", "available_to_held", "paid_coins", 190, 0, -190, 0, 190, "ord_x", t0)
        val bonusHold = entry("h_bonus", "available_to_held", "bonus_coins", 10, 0, -10, 0, 10, "ord_x", t0)
        val paid = entry("c_paid", "held_to_consumed", "paid_coins", 190, -190, 0, 0, -190, "ord_x", t1)
        val bonus = entry("c_bonus", "held_to_consumed", "bonus_coins", 10, -10, 0, 0, -10, "ord_x", t1)
        val groups = LedgerPresentation.displayGroups(listOf(paidHold, bonusHold, paid, bonus))
        assertEquals(1, groups.size)
        assertEquals("购买扣款", groups[0].title)
        assertEquals(-200L, groups[0].netTotalDeltaUnits)
        assertEquals(listOf("c_paid", "c_bonus"), groups[0].entries.map { it.entryRef })
    }

    @Test fun keepsUnrelatedAndUnclearEntriesVisibleAndPreservesOrder() {
        val a = entry("a", "credit", "paid_coins", 30, 30, null, null, null, "r1", t0)
        val b = entry("b", "available_to_reversed", "paid_coins", 5, -5, -5, 0, 0, null, t1)
        val c = entry("c", "mystery_future", "paid_coins", 1, -1, -1, 0, 0, null, t2)
        // mystery_future is not in the hidden set — keep visible.
        assertFalse(LedgerPresentation.isHiddenBookkeeping(c))
        val groups = LedgerPresentation.displayGroups(listOf(a, b, c))
        assertEquals(listOf("a", "b", "c"), groups.map { it.id })
        assertEquals("充值入账", groups[0].title)
        assertEquals("余额撤回", groups[1].title)
        assertEquals("账单记录", groups[2].title)
        assertEquals(30L, groups[0].netTotalDeltaUnits)
        assertEquals(-5L, groups[1].netTotalDeltaUnits)
    }

    @Test fun dateHeadingOmitsZoneSuffix() {
        val zone = ZoneOffset.ofHours(8)
        val today = LocalDate.of(2026, 10, 9)
        val noon = today.atTime(12, 0).toInstant(zone)
        assertEquals("今天", LedgerPresentation.dateHeading(noon, today, zone))
        assertEquals("昨天", LedgerPresentation.dateHeading(noon.minusSeconds(86400), today, zone))
        assertEquals("2026-10-07", LedgerPresentation.dateHeading(noon.minusSeconds(2 * 86400), today, zone))
        assertEquals("+30 Coins", LedgerPresentation.signedCoins(30))
        assertEquals("-200 Coins", LedgerPresentation.signedCoins(-200))
    }

    @Test fun flatRowTimeShowsMonthDayThisYearAndFullDateOtherwise() {
        val zone = ZoneOffset.ofHours(8)
        val today = LocalDate.of(2026, 10, 10)
        val thisYear = LocalDate.of(2026, 3, 5).atTime(9, 7).toInstant(zone)
        val lastYear = LocalDate.of(2025, 12, 31).atTime(23, 59).toInstant(zone)
        assertEquals("03-05 09:07", LedgerPresentation.rowTime(thisYear, today, zone))
        assertEquals("2025-12-31 23:59", LedgerPresentation.rowTime(lastYear, today, zone))
    }

    @Test fun flatListKeepsOneLinePerBalanceChangeInServerOrderWithoutInventingRows() {
        val t = Instant.parse("2026-10-09T01:00:00Z")
        val rows = listOf(
            entry("p1", "credit", "paid_coins", 30, 30, null, null, null, "o1", t),
            entry("p2", "credit", "bonus_coins", 5, 5, null, null, null, "o1", t),
            entry("h1", "available_to_held", "paid_coins", 10, 0, -10, 0, 10, "o2", t),
            entry("c1", "held_to_consumed", "paid_coins", 10, -10, 0, 0, -10, "o2", t),
        )
        val groups = LedgerPresentation.displayGroups(rows)
        // The reserve line is bookkeeping (nets to zero), the same-order split collapses to one net change.
        assertEquals(listOf("p1", "c1"), groups.map { it.id })
        assertEquals(listOf(35L, -10L), groups.map { it.netTotalDeltaUnits })
        assertEquals(rows.sumOf { it.totalDeltaUnits }, groups.sumOf { it.netTotalDeltaUnits })
    }
}
