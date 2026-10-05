package com.gccwebos.android.portal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gccwebos.android.vpn.SafeProxyConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A local operation ticket, never a node choice or connection admission. */
internal class ConfigurationHandle internal constructor(internal val owner: Any,
    internal val libraryRevision: String, internal val entryId: String?, internal val clearVersion: Long) {
    override fun toString() = "ConfigurationHandle([OPAQUE])"
}

internal data class ConfigurationItem(val handle: ConfigurationHandle, val name: String,
    val kind: ManualSourceKind, val nodes: List<SafeProxyConfig.NodeSummary>,
    val status: LocalSubscriptionStatus, val active: Boolean)

internal data class ConfigurationLibrarySnapshot(val items: List<ConfigurationItem> = emptyList(),
    val storageError: Boolean = false)

/** Local library reads run on IO; all Compose publications return to the owning scope. */
internal class ConfigurationLibraryController(private val manager: LocalSubscriptionManager,
    private val scope: CoroutineScope, private val allowed: () -> Boolean,
    private val commitFence: ((() -> Unit) -> Unit),
    private val onSnapshot: (LocalSubscriptionSnapshot) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    var snapshot by mutableStateOf(ConfigurationLibrarySnapshot()); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private var generation = 0L
    private var task: Job? = null

    fun refresh() = run { null to manager.configurationLibrarySnapshot() }
    fun activate(handle: ConfigurationHandle) = mutate {
        manager.activateConfiguration(handle, guardedFence())
    }
    fun rename(handle: ConfigurationHandle, name: String) = mutate {
        manager.renameConfiguration(handle, name)
        manager.snapshot()
    }
    fun remove(handle: ConfigurationHandle) = mutate {
        manager.removeConfiguration(handle, guardedFence())
    }
    fun cancel() {
        generation++; task?.cancel(); task = null; busy = false; error = null
    }
    fun clearDisplay() { cancel(); snapshot = ConfigurationLibrarySnapshot() }
    private fun guardedFence(): (() -> Unit) -> Unit = { action -> commitFence {
        if (!allowed()) throw CancellationException("Configuration operation unavailable")
        action()
    } }
    private fun mutate(action: suspend () -> LocalSubscriptionSnapshot) = run {
        val value = action()
        value to manager.configurationLibrarySnapshot()
    }
    private fun run(action: suspend () -> Pair<LocalSubscriptionSnapshot?, ConfigurationLibrarySnapshot>) {
        if (busy || !allowed()) return
        val request = ++generation
        val clearVersion = manager.captureClearVersion()
        busy = true; error = null
        task = scope.launch {
            try {
                val result = withContext(ioDispatcher) { action() }
                coroutineContext.ensureActive()
                if (request != generation || clearVersion != manager.captureClearVersion() || !allowed()) return@launch
                val (local, library) = result
                local?.let(onSnapshot)
                snapshot = library
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (request == generation) {
                    error = when {
                        failure is PortalFailure && failure.reason == PortalFailure.Reason.STORAGE -> "本地配置暂不可用"
                        failure is IllegalStateException -> "请先断开连接"
                        failure is IllegalArgumentException -> "配置名称或内容无效"
                        else -> "配置暂不可用"
                    }
                }
            } finally { if (request == generation) { busy = false; task = null } }
        }
    }
}
