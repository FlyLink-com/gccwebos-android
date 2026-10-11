package com.gccwebos.android.commerce

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class OrderHistoryPresentationTest {
    private val offer = Offer("offer_a", "offerv_a", LocalizedText("30天套餐", "30d"),
        LocalizedText("s", "s"), LocalizedNotices(emptyList(), emptyList()),
        30, 3000, listOf("paid_coins"), 30, 50, 0)
    private val instant = Instant.parse("2026-10-01T08:00:00Z")

    private fun order(payment: String, fulfillment: String) = PurchaseResult.Order(
        "order_a", offer, 30, listOf(CoinAllocation("paid_coins", 30)), payment, fulfillment, null,
        instant.plusSeconds(600), instant, instant)

    private fun deferred(state: String) = PurchaseResult.Deferred(
        "transition_a", offer, 30, "scheduled_switch", instant.plusSeconds(3600), state, null,
        instant, if (state == "settled") instant else null, instant, instant)

    @Test fun purchaseListMapsCapturedDeliveredToActiveAndNeverCancelsVerifiedPayment() {
        assertEquals("已生效", OrderHistoryPresentation.purchaseListStatus(order("captured", "delivered")))
        assertEquals("处理中", OrderHistoryPresentation.purchaseListStatus(order("captured", "pending")))
        assertEquals("处理中", OrderHistoryPresentation.purchaseListStatus(order("held", "not_requested")))
        assertEquals("已取消", OrderHistoryPresentation.purchaseListStatus(order("cancelled", "not_requested")))
        assertEquals("扣款未完成", OrderHistoryPresentation.purchaseListStatus(order("capture_failed", "not_requested")))
        assertEquals("确认中", OrderHistoryPresentation.purchaseListStatus(order("captured", "failed")))
        assertEquals("确认中", OrderHistoryPresentation.purchaseListStatus(order("unknown", "pending")))
    }

    @Test fun unpaidRechargeShowsWaitingWhileOpenAndInvalidOnceItsWindowPassed() {
        val base = CommerceJson.rechargeOrder(CommerceFixtures.rechargeOrder)
        for (status in listOf("created", "entry_ready", "pending")) {
            val unpaid = base.copy(status = status)
            assertEquals("待付款", OrderHistoryPresentation.rechargeListStatus(unpaid, unpaid.expiresAt.minusSeconds(1)))
            assertEquals("已失效", OrderHistoryPresentation.rechargeListStatus(unpaid, unpaid.expiresAt))
            assertEquals("已失效", OrderHistoryPresentation.rechargeListStatus(unpaid, unpaid.expiresAt.plusSeconds(3600)))
        }
        // Settled or in-doubt states are never turned into "invalid" or "waiting" by the clock.
        val late = base.expiresAt.plusSeconds(3600)
        assertEquals("已到账", OrderHistoryPresentation.rechargeListStatus(base.copy(status = "credited"), late))
        assertEquals("确认中", OrderHistoryPresentation.rechargeListStatus(base.copy(status = "payment_verified"), late))
        assertEquals("确认中", OrderHistoryPresentation.rechargeListStatus(base.copy(status = "reconciliation_required"), late))
    }

    @Test fun deferredAndRechargeKeepPaidPendingHonest() {
        assertEquals("已生效", OrderHistoryPresentation.deferredListStatus("settled"))
        assertEquals("处理中", OrderHistoryPresentation.deferredListStatus("processing"))
        assertEquals("已取消", OrderHistoryPresentation.deferredListStatus("cancelled"))
        assertEquals("确认中", OrderHistoryPresentation.deferredListStatus("requote_required"))
        assertEquals("已到账", OrderHistoryPresentation.rechargeListStatus("credited"))
        assertEquals("已失效", OrderHistoryPresentation.rechargeListStatus("expired"))
        assertEquals("已失效", OrderHistoryPresentation.rechargeListStatus("failed_no_charge"))
        assertEquals("确认中", OrderHistoryPresentation.rechargeListStatus("payment_verified"))
        assertEquals("处理中", OrderHistoryPresentation.rechargeListStatus("pending"))
        assertEquals("确认中", OrderHistoryPresentation.rechargeListStatus("reconciliation_required"))
        assertNotEquals("已失效", OrderHistoryPresentation.rechargeListStatus("payment_verified"))
        assertNotEquals("已取消", OrderHistoryPresentation.rechargeListStatus("payment_verified"))
    }

    @Test fun detailCopyDropsSyncJargonAndOffersSyncOnlyWhenDeliveredOrSettled() {
        assertEquals("已完成", OrderHistoryPresentation.paymentDetail("captured"))
        assertEquals("已生效", OrderHistoryPresentation.fulfillmentDetail("delivered"))
        assertEquals("处理中", OrderHistoryPresentation.fulfillmentDetail("pending"))
        assertEquals("已生效", OrderHistoryPresentation.deferredDetail("settled"))
        assertTrue(OrderHistoryPresentation.offersResourceSync(order("captured", "delivered")))
        assertFalse(OrderHistoryPresentation.offersResourceSync(order("captured", "pending")))
        assertTrue(OrderHistoryPresentation.offersResourceSync(deferred("settled")))
        assertFalse(OrderHistoryPresentation.offersResourceSync(deferred("processing")))
    }

    @Test fun dateHeadingUsesTodayYesterdayOrIsoWithoutZoneSuffix() {
        val zone = ZoneOffset.ofHours(8)
        val today = LocalDate.of(2026, 10, 9)
        val noon = today.atTime(12, 0).toInstant(zone)
        assertEquals("今天", OrderHistoryPresentation.dateHeading(noon, today, zone))
        assertEquals("昨天", OrderHistoryPresentation.dateHeading(noon.minusSeconds(86400), today, zone))
        assertEquals("2026-10-07", OrderHistoryPresentation.dateHeading(noon.minusSeconds(2 * 86400), today, zone))
        assertFalse(OrderHistoryPresentation.dateHeading(noon.minusSeconds(2 * 86400), today, zone).contains("GMT"))
        assertEquals("2026-10-09", OrderHistoryPresentation.dayKey(noon, zone))
    }
}
