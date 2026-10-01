package com.gccwebos.android.commerce

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gccwebos.android.portal.AccountController
import com.gccwebos.android.portal.PortalFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal enum class HistoryKind { Legacy, Deferred, Recharge }
internal data class HistorySelection(val kind: HistoryKind, val ref: String)
internal sealed interface HistoryDetail {
    data class Purchase(val value: PurchaseResult) : HistoryDetail
    data class Recharge(val value: RechargeOrder) : HistoryDetail
}

/** Explicit read-only detail requests; returning never changes an order or its recovery record. */
internal class OrderDetailController(
    private val account: AccountController,
    private val scope: CoroutineScope,
    private val read: suspend (HistorySelection) -> HistoryDetail,
) {
    var selected by mutableStateOf<HistorySelection?>(null)
        private set
    var visible by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var value by mutableStateOf<HistoryDetail?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private val epoch = account.authSessionGeneration
    private var requestId = 0L
    private var task: Job? = null

    fun open(selection: HistorySelection) {
        if (busy || !ready()) return
        task?.cancel()
        val request = ++requestId
        selected = selection; visible = true; busy = true; value = null; error = null
        task = scope.launch {
            try {
                ensureCurrentRequest(request, selection)
                val detail = read(selection)
                ensureCurrentRequest(request, selection)
                require(matches(selection, detail)) { "Detail projection mismatch" }
                value = detail
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: PortalFailure) {
                ensureCurrentRequest(request, selection)
                if (failure.reason == PortalFailure.Reason.SESSION_EXPIRED) {
                    error = "登录已到期，请重新登录。"
                    account.commerceSessionExpired()
                } else {
                    error = "订单详情尚未读取，请重试查询。"
                }
            } catch (_: CommerceFailure) {
                ensureCurrentRequest(request, selection)
                error = "订单详情尚未确认，请重试查询。"
            } catch (_: Exception) {
                ensureCurrentRequest(request, selection)
                error = "订单详情尚未读取，请重试查询。"
            } finally {
                // A cancelled older request must not clear a newer request's loading state.
                if (requestId == request) busy = false
            }
        }
    }

    fun retry() {
        if (visible) selected?.let(::open)
    }

    fun back() {
        requestId++
        task?.cancel(); task = null
        visible = false; busy = false; value = null; error = null
        // Keep the selected row while the underlying list and its cursor remain in place.
    }

    fun dispose() {
        back()
        selected = null
    }

    private fun ready() = account.authenticated && account.authSessionGeneration == epoch &&
        !account.restoring && !account.foregroundCheckPending

    private suspend fun ensureCurrentRequest(request: Long, selection: HistorySelection) {
        currentCoroutineContext().ensureActive()
        if (!ready() || requestId != request || !visible || selected != selection) {
            throw CancellationException("Detail request no longer current")
        }
    }

    private fun matches(selection: HistorySelection, detail: HistoryDetail): Boolean = when (detail) {
        is HistoryDetail.Purchase -> detail.value.ref == selection.ref && when (selection.kind) {
            HistoryKind.Legacy -> detail.value.model == CommerceModel.LEGACY
            HistoryKind.Deferred -> detail.value.model == CommerceModel.WALLET_FIRST
            HistoryKind.Recharge -> false
        }
        is HistoryDetail.Recharge -> selection.kind == HistoryKind.Recharge && detail.value.orderRef == selection.ref
    }
}
