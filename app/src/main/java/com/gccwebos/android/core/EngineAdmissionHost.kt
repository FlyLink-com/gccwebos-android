package com.gccwebos.android.core

import java.util.IdentityHashMap

/** Standalone JVM ownership contract. This does not implement a native tunnel or admit real traffic. */
class EngineAdmissionHost(val maxConfigBytes: Int, private val worker: Worker) {
    init { require(maxConfigBytes in 1..MAX_EXPLICIT_CONFIG_BYTES) { "Invalid engine admission limit" } }
    override fun toString() = "EngineAdmissionHost(<opaque>)"

    companion object {
        /** A ceiling for an explicitly supplied contract parameter, never a production configuration default. */
        const val MAX_EXPLICIT_CONFIG_BYTES = 16 * 1024 * 1024
        const val MAX_OBSERVERS = 32
    }

    enum class Phase { IDLE, ADMITTED, STARTING, ENGINE_RUNNING_UNVERIFIED, STOPPING, CLEANUP_FAILED, CLEANUP_CONFIRMED, CLOSED }
    enum class Reason { EMPTY_CONFIG, CONFIG_TOO_LARGE, OCCUPIED, OWNER_CLOSED, STOP_REQUESTED,
        START_FAILED, STARTUP_TIMEOUT, STOP_REQUEST_FAILED, CLEANUP_TIMEOUT, FORCE_REQUEST_FAILED }
    data class Snapshot(val phase: Phase, val reason: Reason?)

    class AdmissionHandle internal constructor(private val owner: Any) {
        internal fun belongsTo(value: Any) = owner === value
        override fun toString() = "EngineAdmission(<opaque>)"
    }
    class WorkerHandle internal constructor(private val owner: Any) {
        internal fun belongsTo(value: Any) = owner === value
        override fun toString() = "EngineWorker(<opaque>)"
    }
    class ResourceLease internal constructor(private val owner: Any) {
        internal fun belongsTo(value: Any) = owner === value
        override fun toString() = "EngineResource(<opaque>)"
    }
    class CleanupTicket internal constructor(private val owner: Any) {
        internal fun belongsTo(value: Any) = owner === value
        override fun toString() = "EngineCleanup(<opaque>)"
    }
    class TimeoutTicket internal constructor(private val owner: Any) {
        internal fun belongsTo(value: Any) = owner === value
        override fun toString() = "EngineBudget(<opaque>)"
    }
    class UiAwaitHandle internal constructor(private val owner: Any) {
        internal fun belongsTo(value: Any) = owner === value
        override fun toString() = "EngineUiAwait(<opaque>)"
    }
    sealed interface Admission {
        class Accepted(val handle: AdmissionHandle) : Admission {
            override fun toString() = "EngineAdmissionAccepted(<opaque>)"
        }
        data class Rejected(val reason: Reason) : Admission
    }

    /** All calls run outside the host monitor. Register leases before attempting any allocation.
     * Terminal ACK records logical termination; claimed external calls must also drain before cleanup.
     * Implementations must not treat an enqueue, erased material, or a timeout as OS cleanup.
     */
    interface Worker {
        fun start(handle: WorkerHandle, material: ByteArray): Boolean
        fun requestStop(handle: WorkerHandle, ticket: CleanupTicket)
        fun forceTermination(handle: WorkerHandle, ticket: CleanupTicket)
    }

    private val lock = Any()
    private val owner = Any()
    private var closed = false
    private var state = Snapshot(Phase.IDLE, null)
    private class Epoch(owner: Any, val material: ByteArray) {
        val admission = AdmissionHandle(owner)
        val worker = WorkerHandle(owner)
        val startupBudget = TimeoutTicket(owner)
        val resources = IdentityHashMap<ResourceLease, Boolean>()
        var startReserved = false
        var startInFlight = false
        var externalCalls = 0
        var cleanup: CleanupTicket? = null
        var cleanupBudget: TimeoutTicket? = null
        var terminal = false
        var forceRequested = false
    }
    private var current: Epoch? = null
    private data class StopDispatch(val epoch: Epoch, val ticket: CleanupTicket)
    private class Observer(val handle: UiAwaitHandle, val callback: (Snapshot) -> Unit) {
        var delivered: Any? = null
    }
    private class Notice(val stamp: Any, val snapshot: Snapshot, val observers: List<Observer>)
    private val observers = IdentityHashMap<UiAwaitHandle, Observer>()
    private var publication = Any()
    private var pendingNotice = false
    private var draining = false

    fun snapshot(): Snapshot = synchronized(lock) { state }

    /** Caller must not mutate input concurrently with this synchronous call. Only accepted bytes are copied. */
    fun admit(input: ByteArray): Admission {
        val result = synchronized(lock) {
            when {
                input.isEmpty() -> Admission.Rejected(Reason.EMPTY_CONFIG)
                input.size > maxConfigBytes -> Admission.Rejected(Reason.CONFIG_TOO_LARGE)
                closed -> Admission.Rejected(Reason.OWNER_CLOSED)
                current != null -> Admission.Rejected(Reason.OCCUPIED)
                else -> {
                    val epoch = Epoch(owner, input.copyOf())
                    current = epoch
                    setStateLocked(Phase.ADMITTED)
                    Admission.Accepted(epoch.admission)
                }
            }
        }
        // Admission only queues advisory events: no arbitrary callback belongs to the copy/return boundary.
        return result
    }

    /** Launch acknowledgement only; even started() exposes no status beyond ENGINE_RUNNING_UNVERIFIED. */
    fun start(handle: AdmissionHandle): Boolean {
        val epoch = synchronized(lock) {
            val active = current
            if (closed || active == null || !handle.belongsTo(owner) || active.admission !== handle ||
                state.phase != Phase.ADMITTED || active.startReserved) return false
            active.startReserved = true
            // This reservation prevents stop/ACK in the dispatch gap from releasing the generation.
            active.startInFlight = true
            setStateLocked(Phase.STARTING)
            active
        }
        drainNotices()
        var accepted = false
        var invoke = false
        try {
            invoke = synchronized(lock) { current === epoch && state.phase == Phase.STARTING }
            if (invoke) accepted = worker.start(epoch.worker, epoch.material)
        } catch (_: Throwable) { accepted = false }
        finally {
            val stop = synchronized(lock) {
                epoch.startInFlight = false
                val stop = if (current === epoch && invoke && !accepted) {
                    if (epoch.cleanup == null) beginStopLocked(epoch, Reason.START_FAILED)
                    else {
                        // A failed start after an early stop must reconfirm termination after that boundary.
                        epoch.terminal = false
                        epoch.externalCalls++
                        setStateLocked(Phase.STOPPING, Reason.START_FAILED)
                        StopDispatch(epoch, requireNotNull(epoch.cleanup))
                    }
                } else null
                completeIfCleanLocked(epoch)
                stop
            }
            drainNotices()
            dispatchStop(stop)
        }
        return synchronized(lock) { accepted && current === epoch &&
            state.phase in setOf(Phase.STARTING, Phase.ENGINE_RUNNING_UNVERIFIED) }
    }

    fun started(handle: WorkerHandle): Boolean {
        val result = synchronized(lock) {
            val epoch = workerEpochLocked(handle) ?: return false
            if (!epoch.startReserved || state.phase != Phase.STARTING || epoch.cleanup != null) return false
            setStateLocked(Phase.ENGINE_RUNNING_UNVERIFIED)
            true
        }
        drainNotices()
        return result
    }

    /** Returning this opaque lease registers ownership BEFORE the worker performs external allocation. */
    fun registerResource(handle: WorkerHandle): ResourceLease? = synchronized(lock) {
        val epoch = workerEpochLocked(handle) ?: return null
        if (!epoch.startReserved || epoch.cleanup != null || state.phase !in
            setOf(Phase.STARTING, Phase.ENGINE_RUNNING_UNVERIFIED)) return null
        ResourceLease(owner).also { epoch.resources[it] = false }
    }

    fun stop(handle: AdmissionHandle): CleanupTicket? {
        val epoch: Epoch
        val dispatch: StopDispatch?
        synchronized(lock) {
            epoch = current ?: return null
            if (!handle.belongsTo(owner) || epoch.admission !== handle) return null
            dispatch = beginStopLocked(epoch, Reason.STOP_REQUESTED)
        }
        drainNotices()
        dispatchStop(dispatch)
        return epoch.cleanup
    }

    /** Permanent owner closure; UI awaiter detachment is a separate operation. */
    fun ownerClose(): CleanupTicket? {
        val epoch: Epoch?
        val dispatch: StopDispatch?
        synchronized(lock) {
            closed = true
            epoch = current
            dispatch = epoch?.let { beginStopLocked(it, Reason.OWNER_CLOSED) }
            if (epoch == null) setStateLocked(Phase.CLOSED, Reason.OWNER_CLOSED)
        }
        drainNotices()
        dispatchStop(dispatch)
        return epoch?.cleanup
    }

    fun startupTimeoutTicket(handle: AdmissionHandle): TimeoutTicket? = synchronized(lock) {
        current?.takeIf { handle.belongsTo(owner) && it.admission === handle }?.startupBudget
    }
    fun cleanupTimeoutTicket(ticket: CleanupTicket): TimeoutTicket? = synchronized(lock) {
        current?.takeIf { ticket.belongsTo(owner) && it.cleanup === ticket }?.cleanupBudget
    }

    /** Fake/external budget expiry requests force once. It never proves cleanup or releases admission. */
    fun timeout(ticket: TimeoutTicket): Boolean {
        val epoch: Epoch
        val stop: StopDispatch?
        val cleanup: CleanupTicket
        synchronized(lock) {
            epoch = current ?: return false
            if (!ticket.belongsTo(owner) || epoch.forceRequested) return false
            if (epoch.startupBudget === ticket && state.phase == Phase.STARTING) {
                stop = beginStopLocked(epoch, Reason.STARTUP_TIMEOUT)
                setStateLocked(Phase.CLEANUP_FAILED, Reason.STARTUP_TIMEOUT)
            } else if (epoch.cleanupBudget === ticket && epoch.cleanup != null &&
                state.phase in setOf(Phase.STOPPING, Phase.CLEANUP_FAILED)) {
                stop = null
                setStateLocked(Phase.CLEANUP_FAILED, Reason.CLEANUP_TIMEOUT)
            } else return false
            cleanup = requireNotNull(epoch.cleanup)
            epoch.forceRequested = true
            epoch.externalCalls++
        }
        drainNotices()
        dispatchStop(stop)
        try { worker.forceTermination(epoch.worker, cleanup) }
        catch (_: Throwable) { failCleanup(epoch, cleanup, Reason.FORCE_REQUEST_FAILED) }
        finally { finishExternalCall(epoch) }
        return true
    }

    fun released(worker: WorkerHandle, lease: ResourceLease, ticket: CleanupTicket): Boolean {
        val result = synchronized(lock) {
            val epoch = cleanupEpochLocked(worker, ticket) ?: return false
            if (!lease.belongsTo(owner) || !epoch.resources.containsKey(lease) || epoch.resources[lease] == true) return false
            epoch.resources[lease] = true
            completeIfCleanLocked(epoch)
            true
        }
        drainNotices()
        return result
    }

    fun workerTerminated(worker: WorkerHandle, ticket: CleanupTicket): Boolean {
        val result = synchronized(lock) {
            val epoch = cleanupEpochLocked(worker, ticket) ?: return false
            if (epoch.terminal) return false
            epoch.terminal = true
            completeIfCleanLocked(epoch)
            true
        }
        drainNotices()
        return result
    }

    /** Fixed observer capacity keeps monitor work bounded; null means no observer was attached. */
    fun observe(callback: (Snapshot) -> Unit): UiAwaitHandle? {
        val handle = UiAwaitHandle(owner)
        synchronized(lock) {
            if (observers.size >= MAX_OBSERVERS) return null
            val observer = Observer(handle, callback)
            observers[handle] = observer
            pendingNotice = true
        }
        drainNotices()
        return handle
    }
    fun detachUiAwaiter(handle: UiAwaitHandle): Boolean = synchronized(lock) {
        handle.belongsTo(owner) && observers.remove(handle) != null
    }
    /** Owner calls this outside any outer admission/activation fence. No executor or deadline is implied. */
    fun dispatchObservers() = drainNotices()

    private fun workerEpochLocked(handle: WorkerHandle): Epoch? =
        current?.takeIf { handle.belongsTo(owner) && it.worker === handle }
    private fun cleanupEpochLocked(handle: WorkerHandle, ticket: CleanupTicket): Epoch? =
        workerEpochLocked(handle)?.takeIf { ticket.belongsTo(owner) && it.cleanup === ticket &&
            state.phase in setOf(Phase.STOPPING, Phase.CLEANUP_FAILED) }

    private fun beginStopLocked(epoch: Epoch, reason: Reason): StopDispatch? {
        if (current !== epoch || epoch.cleanup != null) return null
        val ticket = CleanupTicket(owner)
        epoch.cleanup = ticket
        epoch.cleanupBudget = TimeoutTicket(owner)
        epoch.externalCalls++ // Claim the stop call before lock-free observer/worker dispatch.
        epoch.material.fill(0) // Private material erasure is independent of the resource ledger.
        setStateLocked(Phase.STOPPING, reason)
        return StopDispatch(epoch, ticket)
    }
    private fun completeIfCleanLocked(epoch: Epoch) {
        if (current !== epoch || epoch.cleanup == null || epoch.startInFlight || epoch.externalCalls != 0 || !epoch.terminal ||
            epoch.resources.values.any { !it }) return
        epoch.material.fill(0)
        current = null
        setStateLocked(if (closed) Phase.CLOSED else Phase.CLEANUP_CONFIRMED,
            if (closed) Reason.OWNER_CLOSED else null)
    }
    private fun dispatchStop(dispatch: StopDispatch?) {
        if (dispatch == null) return
        try { worker.requestStop(dispatch.epoch.worker, dispatch.ticket) }
        catch (_: Throwable) { failCleanup(dispatch.epoch, dispatch.ticket, Reason.STOP_REQUEST_FAILED) }
        finally { finishExternalCall(dispatch.epoch) }
    }
    private fun failCleanup(epoch: Epoch, ticket: CleanupTicket, reason: Reason) {
        synchronized(lock) {
            if (current === epoch && epoch.cleanup === ticket) {
                epoch.terminal = false // A throwing boundary needs a later current terminal acknowledgement.
                setStateLocked(Phase.CLEANUP_FAILED, reason)
            }
        }
        drainNotices()
    }
    private fun finishExternalCall(epoch: Epoch) {
        synchronized(lock) { epoch.externalCalls--; completeIfCleanLocked(epoch) }
        drainNotices()
    }
    private fun setStateLocked(phase: Phase, reason: Reason? = null) {
        state = Snapshot(phase, reason)
        publication = Any()
        pendingNotice = true // One latest slot: no listener enumeration or growing queue in admission.
    }
    /** One lock-free callback pump, coalescing obsolete queued events. Reentrant mutations queue later events. */
    private fun drainNotices() {
        synchronized(lock) { if (draining) return; draining = true }
        try {
            while (true) {
                val notice = synchronized(lock) {
                    if (!pendingNotice) null else {
                        pendingNotice = false
                        Notice(publication, state, observers.values.toList())
                    }
                } ?: return
                for (observer in notice.observers) {
                    val deliver = synchronized(lock) {
                        if (publication === notice.stamp && observers[observer.handle] === observer && observer.delivered !== notice.stamp) {
                            observer.delivered = notice.stamp
                            true
                        } else false
                    }
                    if (deliver) try { observer.callback(notice.snapshot) } catch (_: Throwable) { /* Safe advisory observer. */ }
                }
            }
        } finally {
            val pending = synchronized(lock) { draining = false; pendingNotice }
            if (pending) drainNotices()
        }
    }
}
