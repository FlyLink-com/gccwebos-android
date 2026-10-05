package com.gccwebos.android.portal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gccwebos.android.manualimport.ManualImportFailure
import com.gccwebos.android.manualimport.ManualImportInputKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Owns pending input by identity. UI state contains no configuration, URL or file URI. */
internal class ManualImportController(
    private val manager: LocalSubscriptionManager,
    private val scope: CoroutineScope,
    private val allowed: () -> Boolean,
    private val commitFence: ((() -> Unit) -> Unit),
    private val onSnapshot: (LocalSubscriptionSnapshot) -> Unit,
    private val onSettled: () -> Unit = {},
) {
    class Ticket internal constructor(internal val source: ManualSourceVersion)
    var visible by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private var ticket: Ticket? = null
    private var task: Job? = null
    private var operation = 0L

    fun open() { if (!busy) { cancel(); visible = true } }
    fun begin(): Ticket? {
        if (!visible || busy || !allowed()) return null
        return try {
            Ticket(manager.captureManualSourceVersion()).also { ticket = it; error = null }
        } catch (_: Exception) { error = "本地配置暂不可用"; null }
    }
    fun failure(value: Ticket, failure: ManualImportFailure) {
        if (ticket === value) error = failure.caption
    }
    fun cancel(value: Ticket) { if (ticket === value) cancel() }
    fun cancel() {
        val previous = task
        operation++
        ticket = null; task = null
        busy = false; visible = false; error = null
        previous?.cancel()
        if (previous != null) onSettled()
    }
    /** Ownership of bytes transfers synchronously, including rejected and never-started work. */
    fun submit(value: Ticket, bytes: ByteArray, kind: ManualImportInputKind) {
        if (ticket !== value || busy || !allowed()) { bytes.fill(0); return }
        if (bytes.isEmpty() || bytes.size > com.gccwebos.android.manualimport.ManualInputBytes.Limit ||
            (kind == ManualImportInputKind.Link && !isHttpsLink(bytes))) {
            error = if (bytes.size > com.gccwebos.android.manualimport.ManualInputBytes.Limit) "配置超过 64 KiB" else "配置内容无效"
            bytes.fill(0)
            return
        }
        val owned = bytes.copyOf(); bytes.fill(0)
        run(value, onComplete = { owned.fill(0) }) {
            // All three surfaces pass through exactly the same bounded parser and authority.
            manager.importManual(owned, value.source, guardedFence(value))
        }
    }
    fun useAccount() = changeSource { fence -> manager.useAccountSource(fence); manager.refresh() }
    fun remove() = changeSource { fence -> manager.removeManualSource(fence) }
    private fun changeSource(action: suspend (fence: (() -> Unit) -> Unit) -> LocalSubscriptionSnapshot) {
        val value = begin() ?: return
        run(value) { action(guardedFence(value)) }
    }
    private fun checkCurrent(value: Ticket) {
        if (ticket !== value || !allowed() || !manager.isManualSourceVersionCurrent(value.source))
            throw CancellationException("Import owner changed")
    }
    private fun guardedFence(value: Ticket): (() -> Unit) -> Unit = { action ->
        // Check again inside the actual connection owner's lock, after any download/mutex wait.
        commitFence { checkCurrent(value); action() }
    }
    private fun run(value: Ticket, onComplete: () -> Unit = {}, action: suspend () -> LocalSubscriptionSnapshot) {
        val current = ++operation
        busy = true; error = null
        // Assign ownership before execution, including an already-cancelled parent/immediate scope.
        val work = scope.launch(start = CoroutineStart.LAZY) {
            try {
                checkCurrent(value)
                val result = action()
                coroutineContext.ensureActive()
                if (ticket === value && current == operation) {
                    onSnapshot(result)
                    if (ticket === value && current == operation) { ticket = null; visible = false }
                }
            } catch (cancel: CancellationException) {
                if (ticket === value && current == operation) error = "导入已取消"
                throw cancel
            }
            catch (failure: Exception) {
                if (ticket === value) error = when ((failure as? PortalFailure)?.reason) {
                    PortalFailure.Reason.STORAGE -> "本地配置暂不可用"
                    PortalFailure.Reason.NETWORK -> "导入失败"
                    PortalFailure.Reason.SUBSCRIPTION_UNAVAILABLE -> "订阅不可用或未提供有效期"
                    else -> if (failure is IllegalStateException) "请先断开连接后再导入" else "配置不可用"
                }
            }
        }
        task = work
        // Also wipes the private copy if cancellation wins before the coroutine enters its body.
        work.invokeOnCompletion {
            onComplete()
            if (current == operation) {
                task = null; busy = false
                onSettled()
            }
        }
        work.start()
    }

    /** Only a shape check; the shared authority still validates the exact HTTPS origin and path. */
    private fun isHttpsLink(bytes: ByteArray): Boolean {
        val start = bytes.indexOfFirst { (it.toInt() and 0xff) > 32 }
        val prefix = "https://"
        return start >= 0 && bytes.size - start >= prefix.length &&
            prefix.indices.all { bytes[start + it].toInt() == prefix[it].code }
    }
}
