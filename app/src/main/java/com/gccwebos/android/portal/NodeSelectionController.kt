package com.gccwebos.android.portal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gccwebos.android.core.ConnectionSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns only a local-config read. It never owns authentication, permission or a tunnel task. */
internal class NodeSelectionController(private val manager: LocalSubscriptionManager,
    private val scope: CoroutineScope, private val authGeneration: () -> Long,
    private val allowed: () -> Boolean = { true },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
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
    private var localReadOnly = false

    fun sync() {
        if (accountGeneration != authGeneration()) invalidate()
        if (!busy) snapshot = manager.nodeSelectionSnapshot()
    }
    /** Presentation only; selection and connection still perform their full capability checks. */
    fun expireIfNeeded() {
        if (accountGeneration != authGeneration()) invalidate()
        if (!busy) snapshot = manager.nodeSelectionDisplaySnapshot()
    }
    /** A healthy page visit reads local authority once on IO, without a subscription request. */
    fun refreshIfNeeded(canRefresh: () -> Boolean = { true }) {
        if (!allowed() || busy || !canRefresh()) return
        if (accountGeneration != authGeneration()) invalidate()
        val request = ++generation
        val account = authGeneration()
        busy = true; message = null; localReadOnly = true
        task = scope.launch {
            var needsRefresh = false
            try {
                withContext(ioDispatcher) { manager.nodeSelectionSnapshot() }
                coroutineContext.ensureActive()
                if (request != generation || account != authGeneration() || !allowed() || !canRefresh()) return@launch
                // Re-read only the in-memory registry after the suspension: a source/clear operation
                // may have superseded the IO result. Cached handles never become new authority here.
                snapshot = manager.nodeSelectionDisplaySnapshot()
                val local = manager.snapshot()
                onSnapshot(local.copy(status = snapshot.status))
                needsRefresh = snapshot.status != LocalSubscriptionStatus.STORAGE_ERROR &&
                    (snapshot.status != LocalSubscriptionStatus.READY || snapshot.choices.isEmpty())
            } finally { if (request == generation) { busy = false; localReadOnly = false } }
            if (needsRefresh && request == generation && account == authGeneration() && allowed() && canRefresh()) refresh()
        }
    }
    fun refresh() {
        if (!allowed()) return
        if (accountGeneration != authGeneration()) invalidate()
        val request = ++generation
        val account = authGeneration()
        manager.invalidatePending()
        task?.cancel()
        busy = true; message = null; localReadOnly = false
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
    fun select(selection: ConnectionSelection): Boolean {
        if (busy || !allowed()) return false
        if (accountGeneration != authGeneration()) { invalidate(); return false }
        val accepted = manager.selectNode(selection)
        snapshot = manager.nodeSelectionSnapshot()
        message = if (accepted) null else "节点已失效，请刷新后重新选择。"
        return accepted
    }
    /**
     * Rebuild selectable node handles from local cache only. Used when opaque handles were
     * revoked (login / foreground) while Connecting/Connected/Disconnecting blocks HTTP refresh.
     */
    fun rebuildLocallyIfNeeded() {
        if (!allowed() || busy) return
        if (accountGeneration != authGeneration()) accountGeneration = authGeneration()
        if (snapshot.status == LocalSubscriptionStatus.READY && snapshot.choices.isNotEmpty()) return
        val request = ++generation
        val account = authGeneration()
        busy = true; message = null; localReadOnly = true
        task = scope.launch {
            try {
                withContext(ioDispatcher) { manager.rebuildNodeSelectionFromLocal() }
                coroutineContext.ensureActive()
                if (request != generation || account != authGeneration() || !allowed()) return@launch
                snapshot = manager.nodeSelectionDisplaySnapshot()
                val local = manager.snapshot()
                onSnapshot(local.copy(status = snapshot.status))
                if (snapshot.choices.isEmpty()) message = "连接中无法刷新节点列表，断开后可重新选择。"
            } finally {
                if (request == generation) { busy = false; localReadOnly = false }
            }
        }
    }
    fun cancel() {
        if (localReadOnly) {
            generation++; task?.cancel(); task = null
            busy = false; localReadOnly = false
            expireIfNeeded()
        } else invalidate("读取已取消，请刷新后重新选择。")
    }
    fun invalidate(reason: String? = null) {
        generation++
        accountGeneration = authGeneration()
        manager.invalidateNodeSelection()
        task?.cancel(); task = null
        busy = false; localReadOnly = false
        snapshot = NodeSelectionSnapshot(LocalSubscriptionStatus.MISSING)
        message = reason
    }
}
