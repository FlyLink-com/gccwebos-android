package com.gccwebos.android.portal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gccwebos.android.core.ConnectionSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Owns only a local-config read. It never owns authentication, permission or a tunnel task. */
internal class NodeSelectionController(private val manager: LocalSubscriptionManager,
    private val scope: CoroutineScope, private val authGeneration: () -> Long,
    private val allowed: () -> Boolean = { true },
    private val onSnapshot: (LocalSubscriptionSnapshot) -> Unit = {}) {
    var snapshot by mutableStateOf(NodeSelectionSnapshot(LocalSubscriptionStatus.MISSING))
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    private var generation = 0L
    private var accountGeneration = authGeneration()
    private var task: Job? = null

    fun sync() {
        if (accountGeneration != authGeneration()) invalidate()
        if (!busy) snapshot = manager.nodeSelectionSnapshot()
    }
    fun refresh() {
        if (!allowed()) return
        if (accountGeneration != authGeneration()) invalidate()
        val request = ++generation
        val account = authGeneration()
        manager.invalidatePending()
        task?.cancel()
        busy = true; message = null
        task = scope.launch {
            try {
                val value = manager.refresh()
                coroutineContext.ensureActive()
                if (request != generation || account != authGeneration()) return@launch
                val previous = snapshot.selected
                snapshot = manager.nodeSelectionSnapshot()
                onSnapshot(value)
                if (previous != null && snapshot.selected == null && snapshot.status == LocalSubscriptionStatus.READY)
                    message = "配置已变化，请重新选择节点。"
            } catch (cancel: CancellationException) { throw cancel }
            finally { if (request == generation) busy = false }
        }
    }
    fun select(selection: ConnectionSelection) {
        if (busy || !allowed()) return
        if (accountGeneration != authGeneration()) { invalidate(); return }
        if (manager.selectNode(selection)) { snapshot = manager.nodeSelectionSnapshot(); message = null }
        else { snapshot = manager.nodeSelectionSnapshot(); message = "节点已失效，请刷新后重新选择。" }
    }
    fun cancel() = invalidate("读取已取消，请刷新后重新选择。")
    fun invalidate(reason: String? = null) {
        generation++
        accountGeneration = authGeneration()
        manager.invalidateNodeSelection()
        task?.cancel(); task = null
        busy = false
        snapshot = NodeSelectionSnapshot(LocalSubscriptionStatus.MISSING)
        message = reason
    }
}
