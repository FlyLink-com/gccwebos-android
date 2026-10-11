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
    private data class LibraryReading(val contentKey: String, val observation: NodeLatencyObservation,
        val measuredAt: Long, val network: Long?, val account: Long)
    private data class Attempt(val at: Long, val network: Long?, val cooldown: Long = CooldownMillis)

    private val readings = mutableStateMapOf<ConnectionSelection, Reading>()
    private val attempts = mutableMapOf<ConnectionSelection, Attempt>()
    /** Newest successful round trip per node, kept past the visible TTL so a connected card can still show it. */
    private val lastGood = mutableMapOf<ConnectionSelection, Pair<NodeProbeTarget, Long>>()
    private var capturedTargets: List<NodeProbeTarget>? = null
    /**
     * Nodes of every saved source other than the active one. Display-only readings keyed by source and position and
     * tied to the exact stored bytes ([LibraryProbeTarget.contentKey]); they never select or authorize anything.
     */
    private val libraryReadings = mutableStateMapOf<LibraryNodeKey, LibraryReading>()
    private var capturedLibrary: List<LibraryProbeTarget>? = null
    /**
     * When a node of another source last failed. Automatic batches leave it alone for [AutoRetryMillis] so a
     * permanently unreachable node cannot keep every batch (and its library read) running; a manual test always may.
     */
    private val autoFailedAt = mutableMapOf<LibraryNodeKey, Long>()
    private val libraryAttempts = mutableMapOf<LibraryNodeKey, Attempt>()
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
     * Latency of one node of a non-active saved source, or null. [contentKey] must equal the source's current
     * [ConfigurationItem.contentKey], so a replaced or refreshed source never shows an older reading. Reads only
     * memory: no disk, parsing or lock.
     */
    fun libraryReading(source: String, position: Int, contentKey: String?): NodeLatencyObservation? {
        if (contentKey == null || probe == null || !canRun()) return null
        val entry = libraryReadings[LibraryNodeKey(source, position)] ?: return null
        return entry.observation.takeIf { entry.contentKey == contentKey && libraryVisible(entry, elapsedMillis(), networkKey()) }
    }

    /** The saved sources changed (import, replace, rename, delete, restore): re-capture non-active nodes next batch. */
    fun libraryChanged() { capturedLibrary = null }

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
        return (readings.values.mapNotNull { entry ->
            entry.measuredAt.takeIf { visible(entry, now, network) }
        } + libraryReadings.values.mapNotNull { entry ->
            entry.measuredAt.takeIf { libraryVisible(entry, now, network) }
        }).maxOrNull()
    }

    /**
     * Like [lastMeasuredAtElapsed], but only counts readings that actually measured something
     * (a round trip or a timeout). A batch of "unavailable" cells is not a test result.
     */
    fun lastResultAtElapsed(): Long? {
        if (probe == null || !canRun()) return null
        val now = elapsedMillis()
        val network = networkKey()
        fun result(o: NodeLatencyObservation) = o is NodeLatencyObservation.Success || o == NodeLatencyObservation.NoReply
        return (readings.values.mapNotNull { entry ->
            entry.measuredAt.takeIf { result(entry.observation) }?.takeIf { visible(entry, now, network) }
        } + libraryReadings.values.mapNotNull { entry ->
            entry.measuredAt.takeIf { result(entry.observation) }?.takeIf { libraryVisible(entry, now, network) }
        }).maxOrNull()
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
        libraryReadings.keys.toList().forEach { key ->
            if (libraryReadings[key]?.let { libraryVisible(it, now, network) } != true) libraryReadings.remove(key)
        }
        libraryAttempts.keys.toList().forEach { key ->
            val attempt = libraryAttempts[key] ?: return@forEach
            if (attempt.network != network || !recent(attempt.at, now, attempt.cooldown)) libraryAttempts.remove(key)
        }
        autoFailedAt.keys.toList().forEach { key -> if (!recent(autoFailedAt[key] ?: 0L, now, AutoRetryMillis)) autoFailedAt.remove(key) }
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
        if (!force && if (selection != null) reading(selection) != null else batchComplete()) return
        syncTask?.cancel(); syncTask = null
        val request = ++generation
        val account = accountGeneration
        busy = true
        task = scope.launch {
            PrepTrace.mark("latency-batch-begin")
            try {
                val targets = PrepTrace.span("latency-capture") { captureTargets() }
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
                // Nodes of the other saved sources join the same batch (same permits, so the same bounded
                // concurrency). A single-node retest never touches them.
                val library = if (selection != null) emptyList() else {
                    val captured = captureLibrary()
                    currentCoroutineContext().ensureActive()
                    if (!current(request, account)) return@launch
                    retainLibrary(captured)
                    val at = elapsedMillis()
                    captured.filter { target ->
                        (force || libraryReadings[target.key]?.let { it.contentKey == target.contentKey && libraryVisible(it, at, network) } != true) &&
                            (force || autoFailedAt[target.key]?.let { recent(it, at, AutoRetryMillis) } != true) &&
                            (!force || libraryAttempts[target.key]?.let {
                                it.network == network && recent(it.at, at, it.cooldown)
                            } != true)
                    }
                }
                coroutineScope {
                    wanted.forEach { target -> launch {
                        permits.withPermit { measureOne(target, request, account, network) }
                    } }
                    library.forEach { target -> launch {
                        permits.withPermit { measureLibraryOne(target, request, account, network) }
                    } }
                }
            } finally {
                PrepTrace.mark("latency-batch-end")
                if (request == generation) { busy = false; task = null }
            }
        }
    }

    private suspend fun measureOne(target: NodeProbeTarget, request: Long, account: Long, network: Long?) {
        currentCoroutineContext().ensureActive()
        if (!current(request, account) || networkKey() != network) return
        val before = PrepTrace.span("latency-recheck-before") { targetCurrent(target) }
        currentCoroutineContext().ensureActive()
        if (!current(request, account) || networkKey() != network) return
        if (!before) { readings.remove(target.selection); return }
        attempts[target.selection] = Attempt(elapsedMillis(), network)
        val observation = if (network == null) NodeLatencyObservation.Unavailable else try {
            PrepTrace.span("latency-probe") { withTimeout(ProbeTimeoutMillis) { requireNotNull(probe).measure(target) } }
        } catch (_: TimeoutCancellationException) {
            NodeLatencyObservation.NoReply
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            NodeLatencyObservation.Unavailable
        }
        currentCoroutineContext().ensureActive()
        if (!current(request, account) || networkKey() != network) return
        val after = PrepTrace.span("latency-recheck-after") { targetCurrent(target) }
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

    /**
     * Probes one node of a non-active source. There is no registry to revalidate (the node is not selectable); the
     * reading is tied to the stored bytes by [LibraryProbeTarget.contentKey] and checked against the listing when shown.
     * The inert selection below exists only because probes take a [NodeProbeTarget]; it is registered nowhere.
     */
    private suspend fun measureLibraryOne(target: LibraryProbeTarget, request: Long, account: Long, network: Long?) {
        currentCoroutineContext().ensureActive()
        if (!current(request, account) || networkKey() != network) return
        libraryAttempts[target.key] = Attempt(elapsedMillis(), network)
        val observation = if (network == null) NodeLatencyObservation.Unavailable else try {
            val probeTarget = NodeProbeTarget(ConnectionSelection(), target.endpoint, target.protocol, target.port, target.transport)
            PrepTrace.span("latency-probe-library") { withTimeout(ProbeTimeoutMillis) { requireNotNull(probe).measure(probeTarget) } }
        } catch (_: TimeoutCancellationException) {
            NodeLatencyObservation.NoReply
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            NodeLatencyObservation.Unavailable
        }
        currentCoroutineContext().ensureActive()
        if (!current(request, account) || networkKey() != network) return
        if (observation == NodeLatencyObservation.NetworkChanged ||
            observation is NodeLatencyObservation.Success &&
            (observation.networkKey != network || observation.milliseconds !in 0..ProbeTimeoutMillis)) {
            libraryReadings.remove(target.key)
            return
        }
        val completedAt = elapsedMillis()
        if (observation !is NodeLatencyObservation.Success) {
            libraryAttempts[target.key] = Attempt(completedAt, network, FailedCooldownMillis)
            autoFailedAt[target.key] = completedAt
        } else autoFailedAt.remove(target.key)
        libraryReadings[target.key] = LibraryReading(target.contentKey, observation, completedAt, network, account)
    }

    private suspend fun captureLibrary(): List<LibraryProbeTarget> = withContext(ioDispatcher) {
        try {
            manager.captureLibraryProbeTargets().takeIf { targets ->
                targets.size <= MaxNodes && targets.map { it.key }.distinct().size == targets.size
            } ?: emptyList()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun retainLibrary(targets: List<LibraryProbeTarget>) {
        capturedLibrary = targets
        val keep = targets.associateBy { it.key }
        libraryReadings.keys.toList().filter { key -> keep[key]?.contentKey != libraryReadings[key]?.contentKey }
            .forEach(libraryReadings::remove)
        libraryAttempts.keys.toList().filterNot { it in keep }.forEach(libraryAttempts::remove)
        autoFailedAt.keys.toList().filter { it !in keep }.forEach(autoFailedAt::remove)
    }

    /** True when every captured node (active and other sources) shows a reading or was just tried and failed. */
    private fun batchComplete(): Boolean {
        val active = capturedTargets
        val library = capturedLibrary
        if (active == null || library == null || (active.isEmpty() && library.isEmpty())) return false
        val now = elapsedMillis()
        val network = networkKey()
        return active.all { target ->
            manager.isNodeProbeTargetRegistryCurrent(target) && reading(target.selection) != null
        } && library.all { target ->
            libraryReadings[target.key]?.let { it.contentKey == target.contentKey && libraryVisible(it, now, network) } == true ||
                autoFailedAt[target.key]?.let { recent(it, now, AutoRetryMillis) } == true
        }
    }

    private fun libraryVisible(entry: LibraryReading, now: Long, network: Long?): Boolean =
        entry.account == authGeneration() && entry.network == network &&
            recent(entry.measuredAt, now, if (entry.observation is NodeLatencyObservation.Success)
                ResultTtlMillis else FailedResultTtlMillis) &&
            (entry.observation !is NodeLatencyObservation.Success || entry.observation.networkKey == network)

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
        libraryReadings.clear(); libraryAttempts.clear(); autoFailedAt.clear(); capturedLibrary = null
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
        /** An automatic batch does not retry a node of another source that failed less than this long ago. */
        const val AutoRetryMillis = 30_000L
        const val ProbeTimeoutMillis = 4_000L
        const val MaxNodes = 128
    }
}
