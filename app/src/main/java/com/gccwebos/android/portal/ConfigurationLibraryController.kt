package com.gccwebos.android.portal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gccwebos.android.core.ConnectionChoice
import com.gccwebos.android.core.ConnectionSelection
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
    val status: LocalSubscriptionStatus, val active: Boolean,
    /** One-way node keys aligned with [nodes] (null where a node cannot be addressed). Empty for the read-only account subscription. */
    val nodeKeys: List<String?> = emptyList(),
    /** Positions whose name is a local alias. */
    val nodeAliased: Set<Int> = emptySet()) {
    /** Only imported nodes can be renamed or deleted; the account subscription is read-only. */
    fun canEditNode(position: Int): Boolean = kind != ManualSourceKind.LegacyRemote && handle.entryId != null &&
        nodeKeys.getOrNull(position) != null && nodes.getOrNull(position)?.info == false
}

internal data class ConfigurationLibrarySnapshot(val items: List<ConfigurationItem> = emptyList(),
    val storageError: Boolean = false)

/** Local library reads run on IO; all Compose publications return to the owning scope. */
internal class ConfigurationLibraryController(private val manager: LocalSubscriptionManager,
    private val scope: CoroutineScope, private val allowed: () -> Boolean,
    private val commitFence: ((() -> Unit) -> Unit),
    private val onSnapshot: (LocalSubscriptionSnapshot) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** True while an account is signed in: the account subscription row is then always listed and selectable. */
    private val accountSignedIn: () -> Boolean = { false },
    /** Called after a node-targeted activation settled; it may select the matching node (never a different one). */
    private val chooseNode: (NodeTarget) -> Boolean = { false },
    /** Called after a local alias / deletion changed the active registry, so the node list re-reads it. */
    private val nodesChanged: () -> Unit = {}) {
    var snapshot by mutableStateOf(ConfigurationLibrarySnapshot()); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private var generation = 0L
    private var task: Job? = null

    fun refresh() = run { null to manager.configurationLibrarySnapshot(accountSignedIn()) }
    fun activate(handle: ConfigurationHandle) = mutate {
        manager.activateConfiguration(handle, guardedFence())
    }
    /**
     * Choosing a node in any saved source is one step for the user: the source becomes the active egress
     * source through the same fenced activation, then the tapped node is selected if it is still there.
     * A node that changed or disappeared is never replaced by a different one.
     */
    fun activateAndChoose(handle: ConfigurationHandle, target: NodeTarget) = run(afterSuccess = {
        if (!chooseNode(target)) error = "节点已变化，请重新选择节点"
    }) {
        val value = manager.activateConfiguration(handle, guardedFence())
        value to manager.configurationLibrarySnapshot(accountSignedIn())
    }
    fun rename(handle: ConfigurationHandle, name: String) = mutate {
        manager.renameConfiguration(handle, name)
        manager.snapshot()
    }
    fun remove(handle: ConfigurationHandle) = mutate {
        manager.removeConfiguration(handle, guardedFence())
    }
    /** Local alias of one imported node; a blank alias restores the delivered name. */
    fun renameNode(item: ConfigurationItem, position: Int, alias: String) {
        val key = item.nodeKeys.getOrNull(position)
        if (key == null || !item.canEditNode(position)) return
        mutate(afterSuccess = nodesChanged) { manager.setNodeOverride(item.handle, key, NodeOverride(alias = alias.trim())); manager.snapshot() }
    }
    /** Local deletion of one imported node: hidden here, kept in the saved file, and it stays gone after a refresh. */
    fun deleteNode(item: ConfigurationItem, position: Int) {
        val key = item.nodeKeys.getOrNull(position)
        if (key == null || !item.canEditNode(position)) return
        mutate(afterSuccess = nodesChanged) { manager.setNodeOverride(item.handle, key, NodeOverride(hidden = true)); manager.snapshot() }
    }
    fun cancel() {
        generation++; task?.cancel(); task = null; busy = false; error = null
    }
    fun clearDisplay() { cancel(); snapshot = ConfigurationLibrarySnapshot() }
    private fun guardedFence(): (() -> Unit) -> Unit = { action -> commitFence {
        if (!allowed()) throw CancellationException("Configuration operation unavailable")
        action()
    } }
    private fun mutate(afterSuccess: (() -> Unit)? = null, action: suspend () -> LocalSubscriptionSnapshot) = run(afterSuccess) {
        val value = action()
        value to manager.configurationLibrarySnapshot(accountSignedIn())
    }
    private fun run(afterSuccess: (() -> Unit)? = null,
        action: suspend () -> Pair<LocalSubscriptionSnapshot?, ConfigurationLibrarySnapshot>) {
        if (busy || !allowed()) return
        val request = ++generation
        val clearVersion = manager.captureClearVersion()
        busy = true; error = null
        task = scope.launch {
            var settled = false
            try {
                val result = withContext(ioDispatcher) { action() }
                coroutineContext.ensureActive()
                if (request != generation || clearVersion != manager.captureClearVersion() || !allowed()) return@launch
                val (local, library) = result
                local?.let(onSnapshot)
                snapshot = library
                settled = true
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
            // After busy is released, so the node-selection controller accepts the choice.
            if (settled && request == generation) afterSuccess?.invoke()
        }
    }
}

/** A node the user tapped in a saved source: its position in that source's list plus name and protocol. */
internal data class NodeTarget(val position: Int, val name: String, val protocol: String)

/**
 * Finds the freshly registered choice for [target]: the same position when name and protocol still agree,
 * otherwise the single non-notice choice with the same name and protocol. Anything ambiguous or missing
 * returns null, so the UI asks the user to choose again instead of connecting through another node.
 */
internal fun matchNodeTarget(choices: List<ConnectionChoice>, target: NodeTarget): ConnectionSelection? {
    val atPosition = choices.getOrNull(target.position)
    if (atPosition != null && !atPosition.info && atPosition.name == target.name && atPosition.protocol == target.protocol)
        return atPosition.selection
    val same = choices.filter { !it.info && it.name == target.name && it.protocol == target.protocol }
    return same.singleOrNull()?.selection
}
