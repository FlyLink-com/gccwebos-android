package com.gccwebos.android.commerce

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Display-only ledger grouping. Never alters stored ledger rows or amounts.
 * Pure reserve/hold (`available_to_held`) and release (`held_to_available`) bookkeeping that
 * nets to zero on total balance is hidden from the main list; unclear categories stay visible.
 */
internal data class LedgerDisplayGroup(
    val id: String,
    val title: String,
    val netTotalDeltaUnits: Long,
    val occurredAt: Instant,
    val entries: List<WalletLedgerEntry>,
)

internal object LedgerPresentation {
    private val hiddenBookkeeping = setOf("available_to_held", "held_to_available")

    fun isHiddenBookkeeping(entry: WalletLedgerEntry): Boolean =
        entry.category in hiddenBookkeeping

    /**
     * Preserve server cursor order. Group consecutive visible entries that share the same
     * non-null relatedOrderRef so gifted/paid splits collapse to one business line.
     */
    fun displayGroups(entries: List<WalletLedgerEntry>): List<LedgerDisplayGroup> {
        val visible = entries.filterNot(::isHiddenBookkeeping)
        if (visible.isEmpty()) return emptyList()
        val groups = mutableListOf<LedgerDisplayGroup>()
        var index = 0
        while (index < visible.size) {
            val first = visible[index]
            val orderRef = first.relatedOrderRef
            val chunk = mutableListOf(first)
            var next = index + 1
            if (orderRef != null) {
                while (next < visible.size && visible[next].relatedOrderRef == orderRef) {
                    chunk += visible[next]
                    next++
                }
            }
            groups += LedgerDisplayGroup(
                id = chunk.first().entryRef,
                title = businessTitle(chunk),
                netTotalDeltaUnits = chunk.sumOf { it.totalDeltaUnits },
                occurredAt = chunk.first().occurredAt,
                entries = chunk,
            )
            index = next
        }
        return groups
    }

    fun businessTitle(entries: List<WalletLedgerEntry>): String {
        val primary = entries.firstOrNull { it.category == "held_to_consumed" }
            ?: entries.firstOrNull { it.category == "credit" }
            ?: entries.first()
        return when (primary.category) {
            "credit" -> "充值入账"
            "held_to_consumed" -> "购买扣款"
            "pending_to_available" -> "待确认转可用"
            "pending_to_reversed" -> "待确认撤回"
            "held_to_expired", "available_to_expired" -> "余额到期"
            "available_to_reversed" -> "余额撤回"
            else -> "账单记录"
        }
    }

    fun coinTypeLabel(value: String): String = when (value) {
        "paid_coins" -> "已购 Coins"
        "bonus_coins" -> "赠送 Coins"
        "reward_coins" -> "奖励 Coins"
        else -> "Coins"
    }

    fun categoryLabel(value: String): String = when (value) {
        "credit" -> "入账"
        "pending_to_available" -> "待确认转可用"
        "pending_to_reversed" -> "待确认撤回"
        "available_to_held" -> "订单预留"
        "held_to_available" -> "预留释放"
        "held_to_consumed" -> "订单扣款"
        "held_to_expired", "available_to_expired" -> "余额到期"
        "available_to_reversed" -> "可用余额撤回"
        else -> "账单记录"
    }

    fun signedCoins(value: Long): String = "${if (value > 0) "+" else ""}$value Coins"

    fun dayKey(value: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        value.atZone(zone).toLocalDate().toString()

    fun dateHeading(value: Instant, today: LocalDate = LocalDate.now(ZoneId.systemDefault()),
                    zone: ZoneId = ZoneId.systemDefault()): String {
        val day = value.atZone(zone).toLocalDate()
        return when (day) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> day.toString()
        }
    }

    fun clock(value: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(value)

    fun detailTime(value: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone).format(value)
}
