package com.gccwebos.android.portal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gccwebos.android.core.ConnectionSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Node transport RTT only; none of these observations authorize a VPN connection. */
internal sealed interface NodeLatencyObservation {
    data class Success(val milliseconds: Long, val networkKey: Long) : NodeLatencyObservation
    data object NoReply : NodeLatencyObservation
    data object Unavailable : NodeLatencyObservation
    data object NetworkChanged : NodeLatencyObservation
}

internal interface NodeLatencyProbe {
    suspend fun measure(target: NodeProbeTarget): NodeLatencyObservation
    fun currentNetworkKey(): Long?
}

/** Page-owned, memory-only evidence. Manager reads never refresh, select, prepare or write. */
internal class NodeLatencyController(
    private val manager: LocalSubscriptionManager,
    private val scope: CoroutineScope,
    private val probe: NodeLatencyProbe? = null,
    private val authGeneration: () -> Long,
    private val allowed: () -> Boolean = { true },
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class Reading(val target: NodeProbeTarget, val observation: NodeLatencyObservation,
        val measuredAt: Long, val network: Long?, val account: Long)
    private data class Attempt(val at: Long, val network: Long?, val cooldown: Long = CooldownMillis)

    private val readings = mutableStateMapOf<ConnectionSelection, Reading>()
    private val attempts = mutableMapOf<ConnectionSelection, Attempt>()
    /** Newest successful round trip per node, kept past the visible TTL so a connected card can still show it. */
    private val lastGood = mutableMapOf<ConnectionSelection, Pair<NodeProbeTarget, Long>>()
    private var capturedTargets: List<NodeProbeTarget>? = null
    // Shared across cancelled/replacement batches, so a slow cancellation cannot multiply sockets.
    private val permits = Semaphore(4)
    private var generation = 0L
    private var accountGeneration = authGeneration()
    private var task: Job? = null
    private var syncTask: Job? = null
    var busy by mutableStateOf(false)
        private set
    private var foregroundState by mutableStateOf(true)
    val foreground: Boolean get() = foregroundState

    /** Unlike page cancellation, background suspension also rejects subsequently scheduled work. */
    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foregroundState = value
        if (!value) cancel()
    }

    /** No disk reads or parsing on the composition thread. sync performs full validation on IO. */
    fun reading(selection: ConnectionSelection?): NodeLatencyObservation? {
        if (selection == null || probe == null || !canRun()) return null
        val entry = readings[selection] ?: return null
        return entry.observation.takeIf { visible(entry, elapsedMillis(), networkKey()) }
    }

    /**
     * The newest successful measurement for [selection] while that node is still in the current registry,
     * even after its visible window has passed or the tunnel came up (probes cannot run through it).
     * Display-only; a real, previously measured value, never an estimate.
     */
    fun lastKnownSuccess(selection: ConnectionSelection?): NodeLatencyObservation.Success? {
        if (selection == null || probe == null) return null
        val entry = lastGood[selection] ?: return null
        if (entry.first.selection !== selection) return null
        return NodeLatencyObservation.Success(entry.second, 0L).takeIf { manager.isNodeProbeTargetRegistryCurrent(entry.first) }
    }

    /**
     * Monotonic elapsed millis of the newest still-visible reading, or null when nothing is on
     * screen. Display-only; does not start measurement or touch disk.
     */
    fun lastMeasuredAtElapsed(): Long? {
        if (probe == null || !canRun()) return null
        val now = elapsedMillis()
        val network = networkKey()
        return readings.values.mapNotNull { entry ->
            entry.measuredAt.takeIf { visible(entry, now, network) }
        }.maxOrNull()
    }

    /**
     * Like [lastMeasuredAtElapsed], but only counts readings that actually measured something
     * (a round trip or a timeout). A batch of "unavailable" cells is not a test result.
     */
    fun lastResultAtElapsed(): Long? {
        if (probe == null || !canRun()) return null
        val now = elapsedMillis()
        val network = networkKey()
        return readings.values.mapNotNull { entry ->
            entry.measuredAt.takeIf {
                entry.observation is NodeLatencyObservation.Success || entry.observation == NodeLatencyObservation.NoReply
            }?.takeIf { visible(entry, now, network) }
        }.maxOrNull()
    }

    /** Same clock [lastMeasuredAtElapsed] uses, so the UI can compute a relative "ago" label. */
    fun latencyClockElapsed(): Long = elapsedMillis()

    fun ensureMeasured(selection: ConnectionSelection? = null) = measure(selection, force = false)

    /** Explicit remeasurement is rate-limited per opaque node and physical network. */
    fun refresh(selection: ConnectionSelection? = null) = measure(selection, force = true)

    /** Immediately hides stale in-memory evidence; no capability reads or mutation. */
    fun prune() {
        refreshAccountOwner()
        val now = elapsedMillis()
        val network = networkKey()
        readings.keys.toList().forEach { key ->
            if (readings[key]?.let { visible(it, now, network) } != true) readings.remove(key)
        }
        attempts.keys.toList().forEach { key ->
            val attempt = attempts[key] ?: return@forEach
            if (attempt.network != network || !recent(attempt.at, now, attempt.cooldown)) attempts.remove(key)
        }
    }

    /** UI clock tick: only expire memory evidence, never parse or decrypt configuration. */
    fun pruneExpired() = prune()

    /** Revalidates cached display evidence without starting any network measurement. */
    fun sync() {
        prune()
        if (probe == null || !canRun()) return
        syncTask?.cancel()
        val request = generation
        val account = accountGeneration
        syncTask = scope.launch {
            val targets = captureTargets()
            currentCoroutineContext().ensureActive()
            if (!current(request, account)) return@launch
            retainTargets(targets)
            prune()
        }
    }

    /** Page disposal abandons unfinished work, preserving only completed, still-current readings. */
    fun cancel() {
        generation++
        task?.cancel(); task = null
        syncTask?.cancel(); syncTask = null
        busy = false
        prune()
    }

    private fun measure(selection: ConnectionSelection?, force: Boolean) {
        prune()
        if (probe == null || !canRun() || busy) return
        // Page ticks must not re-read and parse a fresh artifact every second. A full batch is
        // complete only for the exact captured opaque targets, never merely an equal result count.
        // sync remains the independent IO revalidation path for externally changed cached bytes.
        if (!force && if (selection != null) reading(selection) != null else
            capturedTargets?.takeIf { it.isNotEmpty() }?.all { target ->
                manager.isNodeProbeTargetRegistryCurrent(target) && reading(target.selection) != null
            } == true) return
        syncTask?.cancel(); syncTask = null
        val request = ++generation
        val account = accountGeneration
        busy = true
        task = scope.launch {
            try {
                val targets = captureTargets()
                currentCoroutineContext().ensureActive()
                if (!current(request, account)) return@launch
                retainTargets(targets)
                val network = networkKey()
                val now = elapsedMillis()
                val wanted = targets.filter { target ->
                    (selection == null || target.selection === selection) &&
                        (force || readings[target.selection]?.let { visible(it, now, network) } != true) &&
                        (!force || attempts[target.selection]?.let {
                            it.network == network && recent(it.at, now, it.cooldown)
                        } != true)
                }
                coroutineScope {
                    wanted.forEach { target -> launch {
                        permits.withPermit { measureOne(target, request, account, network) }
                    } }
                }
            } finally {
                if (request == generation) { busy = false; task = null }
            }
        }
    }

    private suspend fun measureOne(target: NodeProbeTarget, request: Long, account: Long, network: Long?) {
        currentCoroutineContext().ensureActive()
        if (!current(request, account) || networkKey() != network) return
        val before = targetCurrent(target)
        currentCoroutineContext().ensureActive()
        if (!current(request, account) || networkKey() != network) return
        if (!before) { readings.remove(target.selection); return }
        attempts[target.selection] = Attempt(elapsedMillis(), network)
        val observation = if (network == null) NodeLatencyObservation.Unavailable else try {
            withTimeout(ProbeTimeoutMillis) { requireNotNull(probe).measure(target) }
        } catch (_: TimeoutCancellationException) {
            NodeLatencyObservation.NoReply
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            NodeLatencyObservation.Unavailable
        }
        currentCoroutineContext().ensureActive()
        if (!current(request, account) || networkKey() != network) return
        val after = targetCurrent(target)
        currentCoroutineContext().ensureActive()
        if (!current(request, account) || networkKey() != network) return
        if (!after || !manager.isNodeProbeTargetRegistryCurrent(target)) {
            readings.remove(target.selection)
            return
        }
        if (observation == NodeLatencyObservation.NetworkChanged ||
            observation is NodeLatencyObservation.Success &&
            (observation.networkKey != network || observation.milliseconds !in 0..ProbeTimeoutMillis)) {
            readings.remove(target.selection)
            return
        }
        val completedAt = elapsedMillis()
        if (observation !is NodeLatencyObservation.Success)
            attempts[target.selection] = Attempt(completedAt, network, FailedCooldownMillis)
        if (observation is NodeLatencyObservation.Success) lastGood[target.selection] = target to observation.milliseconds
        readings[target.selection] = Reading(target, observation, completedAt, network, account)
    }

    private suspend fun captureTargets(): List<NodeProbeTarget> = withContext(ioDispatcher) {
        try {
            manager.captureNodeProbeTargets().takeIf { targets ->
                targets.size <= MaxNodes && targets.map { it.selection }.distinct().size == targets.size
            } ?: emptyList()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun targetCurrent(target: NodeProbeTarget): Boolean = withContext(ioDispatcher) {
        try { manager.isCurrentNodeProbeTarget(target) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { false }
    }

    private fun retainTargets(targets: List<NodeProbeTarget>) {
        capturedTargets = targets
        val current = targets.mapTo(mutableSetOf()) { it.selection }
        readings.keys.toList().filterNot { it in current }.forEach(readings::remove)
        attempts.keys.toList().filterNot { it in current }.forEach(attempts::remove)
        lastGood.keys.toList().filterNot { it in current }.forEach(lastGood::remove)
    }

    private fun refreshAccountOwner() {
        val account = authGeneration()
        if (account == accountGeneration) return
        generation++
        task?.cancel(); task = null
        syncTask?.cancel(); syncTask = null
        busy = false
        readings.clear(); attempts.clear(); lastGood.clear(); capturedTargets = null
        accountGeneration = account
    }

    private fun current(request: Long, account: Long): Boolean =
        request == generation && account == authGeneration() && canRun()

    private fun canRun(): Boolean = foreground && allowed()

    private fun visible(entry: Reading, now: Long, network: Long?): Boolean =
        entry.account == authGeneration() && entry.network == network &&
            recent(entry.measuredAt, now, if (entry.observation is NodeLatencyObservation.Success)
                ResultTtlMillis else FailedResultTtlMillis) &&
            manager.isNodeProbeTargetRegistryCurrent(entry.target) &&
            (entry.observation !is NodeLatencyObservation.Success || entry.observation.networkKey == network)

    private fun networkKey(): Long? = try { probe?.currentNetworkKey() } catch (_: Exception) { null }

    private fun recent(at: Long, now: Long, duration: Long): Boolean = (now - at).let { it >= 0 && it < duration }

    private companion object {
        const val ResultTtlMillis = 120_000L
        const val FailedResultTtlMillis = 10_000L
        const val CooldownMillis = 30_000L
        const val FailedCooldownMillis = 2_000L
        const val ProbeTimeoutMillis = 4_000L
        const val MaxNodes = 128
    }
}
