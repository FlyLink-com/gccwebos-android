package com.gccwebos.android.commerce

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Display-only order history copy. Never mutates payment, fulfillment, or recovery state.
 * List labels stay short and user-facing; ambiguous or paid-but-pending states stay honest.
 */
internal object OrderHistoryPresentation {
    /** Single list badge for a purchase/plan row. */
    fun purchaseListStatus(value: PurchaseResult): String = when (value) {
        is PurchaseResult.Order -> purchaseOrderListStatus(value.paymentStatus, value.fulfillmentStatus)
        is PurchaseResult.Deferred -> deferredListStatus(value.state)
    }

    fun purchaseOrderListStatus(paymentStatus: String, fulfillmentStatus: String): String = when (paymentStatus) {
        "cancelled" -> "已取消"
        "capture_failed" -> "扣款未完成"
        "held", "capture_pending" -> "处理中"
        "captured" -> when (fulfillmentStatus) {
            "delivered" -> "已生效"
            "pending", "processing", "not_requested" -> "处理中"
            else -> "确认中"
        }
        else -> "确认中"
    }

    fun deferredListStatus(state: String): String = when (state) {
        "settled" -> "已生效"
        "reserved", "processing", "effect_applied" -> "处理中"
        "cancelled" -> "已取消"
        "requote_required" -> "确认中"
        else -> "确认中"
    }

    /**
     * Recharge list badge. Paid-but-not-credited (`payment_verified`) is never labelled cancelled/expired.
     * Unpaid failure and true expiry collapse to 已失效.
     */
    fun rechargeListStatus(status: String): String = when (status) {
        "credited" -> "已到账"
        "expired", "failed_no_charge" -> "已失效"
        "created", "entry_ready", "pending" -> "处理中"
        "payment_verified" -> "确认中"
        "reconciliation_required" -> "确认中"
        else -> "确认中"
    }

    /** Detail payment field — user-facing, no backend jargon. */
    fun paymentDetail(value: String): String = when (value) {
        "held", "capture_pending" -> "处理中"
        "captured" -> "已完成"
        "cancelled" -> "已取消"
        "capture_failed" -> "扣款未完成"
        else -> "确认中"
    }

    fun fulfillmentDetail(value: String): String = when (value) {
        "not_requested", "pending", "processing" -> "处理中"
        "delivered" -> "已生效"
        else -> "确认中"
    }

    fun deferredDetail(value: String): String = when (value) {
        "reserved", "processing", "effect_applied" -> "处理中"
        "settled" -> "已生效"
        "cancelled" -> "已取消"
        "requote_required" -> "确认中"
        else -> "确认中"
    }

    fun rechargeDetail(status: String): String = when (status) {
        "credited" -> "已到账"
        "expired", "failed_no_charge" -> "已失效"
        "created", "entry_ready", "pending" -> "处理中"
        "payment_verified", "reconciliation_required" -> "确认中"
        else -> "确认中"
    }

    /** True when delivered/settled so the existing subscription refresh action may be offered. */
    fun offersResourceSync(value: PurchaseResult): Boolean = when (value) {
        is PurchaseResult.Order -> value.fulfillmentStatus == "delivered"
        is PurchaseResult.Deferred -> value.state == "settled"
    }

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
