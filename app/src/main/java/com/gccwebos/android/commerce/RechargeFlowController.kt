package com.gccwebos.android.commerce

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gccwebos.android.portal.AccountController
import java.time.Instant

/** Local confirmations only; the repository owns order creation and durable recovery. */
internal class RechargeFlowController(
    private val account: AccountController,
    private val commerce: CommerceController,
    private val opener: (String) -> Unit,
) {
    var selectedOffer by mutableStateOf<RechargeOffer?>(null)
        private set
    var paymentReview by mutableStateOf<RechargeOrder?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var requestedPaymentFor by mutableStateOf<String?>(null)
        private set

    private val epoch = account.authSessionGeneration
    private var disposed = false

    fun select(offer: RechargeOffer) {
        if (!ready()) return
        if (!currentOffer(offer)) {
            selectedOffer = null
            error = "充值档位已更新或暂不可用，请重新读取后选择。"
            return
        }
        selectedOffer = offer.copy(paymentMethods = offer.paymentMethods.toList())
        paymentReview = null; error = null; requestedPaymentFor = null
    }

    fun cancelSelection() {
        selectedOffer = null
        error = null
    }

    fun confirmSelection() {
        val offer = selectedOffer ?: return
        if (!ready()) return
        selectedOffer = null
        if (!currentOffer(offer)) {
            error = "充值档位已更新或暂不可用，请重新读取后选择。"
            return
        }
        error = null
        commerce.confirmRecharge(offer)
    }

    fun reviewPayment(order: RechargeOrder, now: Instant) {
        if (!ready()) return
        if (!commerce.canOpenPayment(order, now)) {
            paymentReview = null
            error = "付款入口已失效或订单已更新，请查询当前订单。"
            return
        }
        paymentReview = order; selectedOffer = null; error = null; requestedPaymentFor = null
    }

    fun dismissPayment() {
        paymentReview = null
        error = null
    }

    fun openPayment(now: Instant) {
        val order = paymentReview ?: return
        paymentReview = null // Consume this confirmation before invoking an external opener.
        if (!ready()) return
        if (!commerce.canOpenPayment(order, now)) {
            error = "付款入口已失效或订单已更新，请查询当前订单。"
            return
        }
        try {
            opener(requireNotNull(order.nextAction).url)
            if (currentSession()) requestedPaymentFor = order.orderRef
        } catch (_: Exception) {
            if (currentSession()) error = "无法打开付款页面，请保留当前订单并稍后查询，不要重复创建充值。"
        }
    }

    fun dispose() {
        disposed = true
        selectedOffer = null; paymentReview = null; error = null; requestedPaymentFor = null
    }

    private fun currentSession() = !disposed && account.authenticated &&
        account.authSessionGeneration == epoch && commerce.currentSession()

    private fun ready() = currentSession() && !account.restoring &&
        !account.foregroundCheckPending && !commerce.busy

    private fun currentOffer(offer: RechargeOffer) = commerce.rechargeOffersLoaded &&
        !commerce.pendingRecharge && "alipay" in offer.paymentMethods &&
        commerce.rechargeOffers.any { it == offer }
}
