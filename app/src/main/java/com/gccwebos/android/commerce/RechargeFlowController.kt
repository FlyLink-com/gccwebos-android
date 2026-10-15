package com.gccwebos.android.commerce

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gccwebos.android.portal.AccountController
import java.time.Instant

/** 立即充值 / 立即支付 go straight through: no confirmation or review dialog. The repository owns order creation and durable recovery. */
internal class RechargeFlowController(
    private val account: AccountController,
    private val commerce: CommerceController,
    private val clock: () -> Instant = { Instant.now() },
    private val opener: (String) -> Unit,
) {
    var error by mutableStateOf<String?>(null)
        private set
    var requestedPaymentFor by mutableStateOf<String?>(null)
        private set

    private val epoch = account.authSessionGeneration
    private var disposed = false

    /**
     * 立即充值: the amount the user picked is already on screen (picker + 应付金额 in the checkout bar), so the tap creates the
     * order at once and then opens its payment entry. The offer must still be exactly the one the server listed.
     */
    fun rechargeNow(offer: RechargeOffer) {
        if (!ready()) return
        requestedPaymentFor = null
        if (!currentOffer(offer)) {
            error = "充值档位已更新或暂不可用，请重新读取后选择。"
            return
        }
        error = null
        commerce.confirmRecharge(offer.copy(paymentMethods = offer.paymentMethods.toList())) { created -> afterCreated(offer, created) }
    }

    /**
     * The payment entry opens only when the server's figures are exactly what the user selected (price, currency, coins,
     * quote version, method). Any difference stops here with one inline message: the order is kept (it is listed in 订单明细,
     * where its own amount is shown next to 立即支付), but nothing is opened and nobody pays an unseen amount.
     */
    private fun afterCreated(offer: RechargeOffer, order: RechargeOrder) {
        if (!currentSession()) return
        if (createdMatches(offer, order)) openNow(order, clock())
        else error = "订单金额与所选档位不一致，未打开付款页面。订单已保留，请到订单明细核对后再支付。"
    }

    /** 立即支付 on an existing order (订单明细): its figures are the ones on screen, so the valid entry opens directly. */
    fun payNow(order: RechargeOrder, now: Instant) {
        if (!ready()) return
        error = null; requestedPaymentFor = null
        openNow(order, now)
    }

    private fun openNow(order: RechargeOrder, now: Instant) {
        if (!commerce.canOpenPayment(order, now)) {
            error = "付款入口已失效或订单已更新，请到订单明细查看。"
            return
        }
        try {
            opener(requireNotNull(order.nextAction).url)
            requestedPaymentFor = order.orderRef
        } catch (_: Exception) {
            error = "无法打开付款页面，请保留当前订单并稍后查询，不要重复创建充值。"
        }
    }

    private fun createdMatches(offer: RechargeOffer, order: RechargeOrder) =
        order.offerRef == offer.offerRef && order.quoteVersion == offer.quoteVersion &&
        order.amountMinor == offer.amountMinor && order.currency == offer.currency &&
        order.currencyExponent == offer.currencyExponent && order.expectedPaidCoinUnits == offer.paidCoinUnits &&
        order.paymentMethod in offer.paymentMethods

    fun dispose() {
        disposed = true
        error = null; requestedPaymentFor = null
    }

    private fun currentSession() = !disposed && account.authenticated &&
        account.authSessionGeneration == epoch && commerce.currentSession()

    private fun ready() = currentSession() && !account.restoring &&
        !account.foregroundCheckPending && !commerce.busy

    private fun currentOffer(offer: RechargeOffer) = commerce.rechargeOffersLoaded &&
        "alipay" in offer.paymentMethods &&
        commerce.rechargeOffers.any { it == offer }
}
