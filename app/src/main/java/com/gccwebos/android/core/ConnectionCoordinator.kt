package com.gccwebos.android.core

import java.io.Closeable
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Disconnecting : ConnectionState
    data class Working(val step: Step, val operation: Operation = Operation.CONNECT) : ConnectionState
    data class Connected(val lineId: String) : ConnectionState
    data class Blocked(val reason: BusinessBlock) : ConnectionState
    data class Failed(val reason: Failure, val preparation: PreparationFailure? = null,
        val choices: List<ConnectionChoice> = emptyList()) : ConnectionState
}

enum class Operation { CONNECT, SWITCH, RECONNECT }
enum class Step { ACCOUNT, ENTITLEMENT, CONFIG, PERMISSION, TUNNEL, TRAFFIC }
enum class BusinessBlock { UNAUTHENTICATED, NO_PLAN, PROVISIONING, EXPIRED, QUOTA_EXHAUSTED }
enum class Failure {
    NO_LINE, ACCOUNT_FAILED, ENTITLEMENT_FAILED, CONFIG_UNAVAILABLE,
    PERMISSION_DENIED, TUNNEL_FAILED, TRAFFIC_FAILED, TIMEOUT, CLEANUP_FAILED,
    PREPARATION_FAILED, SELECTION_REQUIRED, ENGINE_DIED, PERMISSION_REVOKED,
}

data class AccessDecision(val block: BusinessBlock? = null)

/**
 * Owns a copy of secret configuration bytes. Never put configuration in logs, saved state,
 * analytics or navigation arguments. Consumers own (and must wipe) copies they request.
 * close() wipes the coordinator's copy, not the caller's constructor argument.
 */
class AuthorizedConfig(val lineId: String, material: ByteArray,
    val validUntil: Instant? = null,
    private val activationGuard: (((() -> Boolean)) -> Boolean)? = null) : Closeable {
    private val bytes = material.copyOf()
    private var closed = false

    init { require(lineId.isNotBlank()) { "A non-empty authorized line is required" } }

    @Synchronized
    fun copyMaterial(): ByteArray {
        check(!closed) { "Configuration is closed" }
        return bytes.copyOf()
    }

    val requiresAtomicActivation: Boolean get() = activationGuard != null

    /** Fresh production-shaped material can only cross a synchronous, fenced handoff. */
    fun activate(action: () -> Boolean): Boolean {
        synchronized(this) { check(!closed) { "Configuration is closed" } }
        return activationGuard?.invoke(action) ?: action()
    }

    @Synchronized
    override fun close() {
        bytes.fill(0)
        closed = true
    }

    override fun toString(): String = "AuthorizedConfig(material=<redacted>)"
}

interface AccountGateway { suspend fun isAuthenticated(): Boolean }
interface EntitlementGateway { suspend fun current(): AccessDecision }
interface ConfigGateway { suspend fun forLine(lineId: String?): AuthorizedConfig? }
interface PermissionGateway { suspend fun hasVpnPermission(): Boolean }

/**
 * One coordinator is the exclusive owner of a gateway instance. start/stop must cooperate
 * with coroutine cancellation. start must return only after accepting/copying the config.
 * A thrown/cancelled/false start may have allocated resources: stop must clean those too.
 * stop is idempotent, and must return normally ONLY when all owned tunnel resources are gone.
 * A native adapter must enforce its own hard timeout / fd-close boundary; coroutine timeouts
 * cannot interrupt arbitrary blocking or cancellation-ignoring native code.
 */
interface TunnelGateway {
    suspend fun start(config: AuthorizedConfig): Boolean
    suspend fun stop()
}

/** Bounded synchronous acceptance boundary for fresh preparation. It must accept/copy bytes
 * before returning, with no deferred start or suspension. Real native activation needs its own
 * cancellation/watchdog contract; AndroidVpnTunnel supplies the remote implementation.
 */
interface AtomicActivationTunnel : TunnelGateway {
    fun startAtomically(config: AuthorizedConfig): Boolean
}

/** Must probe the intended tunnel path in M1, never ordinary direct Internet connectivity. */
interface TrafficProbe { suspend fun isUsable(): Boolean }

data class ConnectionTimeouts(
    val stepMillis: Long = 20_000,
    val permissionMillis: Long = 120_000,
    val cleanupMillis: Long = 5_000,
) {
    init { require(stepMillis > 0 && permissionMillis > 0 && cleanupMillis > 0) }
}

/**
 * Latest command wins. The short monitor protects command identity, not network operations.
 * The lifecycle mutex protects global tunnel ownership; a replacement first cancels the old
 * worker and may acquire that mutex ONLY after old cleanup finishes. A successful worker
 * stays alive until cancelled, so cancelling the owner's scope also stops a connected tunnel.
 *
 * connect()/switchLine()/reconnect() await a terminal outcome, NOT the entire tunnel lifetime.
 * disconnect() awaits cleanup. A superseded request resolves to its own Disconnected outcome;
 * it cannot overwrite the newer request's StateFlow. Caller cancellation cancels only its
 * request; cancellation is never reclassified as an ordinary network failure.
 */
class ConnectionCoordinator(
    private val account: AccountGateway?,
    private val entitlement: EntitlementGateway?,
    private val config: ConfigGateway?,
    private val permission: PermissionGateway,
    private val tunnel: TunnelGateway,
    private val probe: TrafficProbe,
    parentScope: CoroutineScope,
    private val timeouts: ConnectionTimeouts = ConnectionTimeouts(),
    private val local: LocalConfigGateway? = null,
) {
    /** Account-independent local capability path, shared by production and offline contracts. */
    constructor(config: LocalConfigGateway, permission: PermissionGateway, tunnel: LifecycleTunnel,
        probe: TrafficProbe, parentScope: CoroutineScope, timeouts: ConnectionTimeouts = ConnectionTimeouts()) :
        this(null, null, null, permission, tunnel, probe, parentScope, timeouts, config)
    private val owner = SupervisorJob(requireNotNull(parentScope.coroutineContext[Job]) {
        "The coordinator requires a lifecycle-owned CoroutineScope"
    })
    private val scope = CoroutineScope(parentScope.coroutineContext + owner)
    private val commandLock = Any()
    private val lifecycle = Mutex()
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = mutableState.asStateFlow()
    private var sequence = 0L
    private var current: Ticket? = null
    private var closed = false
    private var retirement: Ticket? = null
    @Volatile private var retirementAdmissionFailed = false
    private var lastRequestedLine: String? = null
    private var lastSelection: SelectionIntent = SelectionIntent.Choose
    // Only accessed while holding lifecycle. True is deliberately retained on cleanup failure.
    @Volatile private var tunnelMayBeRunning = false
    @Volatile private var preparedMaterialMayExist = false
    private val clearObservation = try { local?.observeClear {
        synchronized(commandLock) {
            if (!closed && owner.isActive) submit(null, Operation.CONNECT, disconnect = true)
        }
    } } catch (failure: Throwable) { owner.cancel(); throw failure }

    init {
        require(local != null || (account != null && entitlement != null && config != null))
        require(local == null || tunnel is LifecycleTunnel)
        owner.invokeOnCompletion {
            try { local?.invalidatePending() }
            catch (_: Exception) {
                // Completion must not interrupt cancel/join and skip the actual cleanup below.
                retirementAdmissionFailed = true
                mutableState.value = ConnectionState.Failed(Failure.CLEANUP_FAILED)
            }
            if (!retirementAdmissionFailed && !tunnelMayBeRunning && !preparedMaterialMayExist) clearObservation?.close()
        }
    }

    private class Ticket(
        val id: Long,
        val lineId: String?,
        val operation: Operation,
        val disconnect: Boolean,
        val predecessor: Job?,
        val selection: SelectionIntent?,
    ) {
        var terminal: TunnelTerminal? = null
        val begin = CompletableDeferred<Unit>()
        val result = CompletableDeferred<ConnectionState>()
        lateinit var job: Job
    }

    private class Abort(val outcome: ConnectionState) : Exception(null, null, false, false)

    /** UI observers may detach/cancel await() without stopping service-owned work.
     * Use stop(), cancel() or owner.close() explicitly. Commands are submitted synchronously.
     */
    class Command internal constructor(private val outcome: CompletableDeferred<ConnectionState>,
        private val cancelAction: () -> Unit) {
        suspend fun await(): ConnectionState = outcome.await()
        fun cancel() = cancelAction()
    }
    private fun command(ticket: Ticket) = Command(ticket.result) {
        synchronized(commandLock) {
            if (current?.id == ticket.id && ticket.job.isActive) {
                local?.invalidatePending()
                mutableState.value = ConnectionState.Disconnecting
                ticket.job.cancel(CancellationException("Connection command cancelled"))
            }
        }
    }
    fun start(intent: SelectionIntent = SelectionIntent.Choose): Command {
        check(local != null) { "Typed selection requires local admission" }
        return command(submit(null, Operation.CONNECT, selection = intent))
    }
    fun switchSelection(selection: ConnectionSelection): Command {
        check(local != null) { "Typed selection requires local admission" }
        return command(submit(null, Operation.SWITCH, selection = SelectionIntent.Selected(selection)))
    }
    fun restart(): Command = command(submit(null, Operation.RECONNECT))
    fun stop(): Command = command(submit(null, Operation.CONNECT, disconnect = true))

    suspend fun connect(lineId: String? = null): ConnectionState = await(submit(lineId, Operation.CONNECT))

    suspend fun switchLine(lineId: String): ConnectionState {
        require(lineId.isNotBlank()) { "A non-empty line is required" }
        return await(submit(lineId, Operation.SWITCH))
    }

    suspend fun reconnect(): ConnectionState = await(submit(null, Operation.RECONNECT))

    suspend fun disconnect(): ConnectionState = await(submit(null, Operation.CONNECT, disconnect = true))

    /** Reserve the existing final stop synchronously before an Android lifecycle callback returns.
     * This closes command admission immediately; it does not release the cleanup/authority lease. */
    fun beginClose() = synchronized(commandLock) {
        if (!closed) {
            try {
                if (owner.isActive) retirement = submit(null, Operation.CONNECT, disconnect = true)
            } catch (_: Exception) {
                retirementAdmissionFailed = true
                owner.cancel() // Fence any old worker even when final-stop submission failed.
                mutableState.value = ConnectionState.Failed(Failure.CLEANUP_FAILED)
            } finally { closed = true }
        }
        Unit
    }

    /** Call from the lifecycle owner. Safe to repeat; every caller awaits the same final stop. */
    suspend fun close(): ConnectionState {
        beginClose()
        val ticket = synchronized(commandLock) { retirement }
        return withContext(NonCancellable) {
            if (ticket != null) ticket.result.await()
            owner.cancel()
            owner.join()
            lifecycle.withLock {
                if (tunnelMayBeRunning) {
                    mutableState.value = if (stopSafely()) ConnectionState.Disconnected
                        else ConnectionState.Failed(Failure.CLEANUP_FAILED)
                }
                if (!tunnelMayBeRunning && preparedMaterialMayExist) {
                    mutableState.value = if (clearPreparedSafely()) ConnectionState.Disconnected
                        else ConnectionState.Failed(Failure.CLEANUP_FAILED)
                }
            }
            if (retirementAdmissionFailed) mutableState.value = ConnectionState.Failed(Failure.CLEANUP_FAILED)
            if (!retirementAdmissionFailed && !tunnelMayBeRunning && !preparedMaterialMayExist) clearObservation?.close()
            state.value
        }
    }

    private fun submit(lineId: String?, operation: Operation, disconnect: Boolean = false,
        selection: SelectionIntent? = null): Ticket {
        val ticket = synchronized(commandLock) {
            check(!closed && owner.isActive) { "Connection coordinator is closed" }
            val previous = current
            val connected = mutableState.value as? ConnectionState.Connected
            // Only identical active ordinary connects are coalesced. Switch/reconnect are explicit.
            if (local == null && !disconnect && operation == Operation.CONNECT && previous != null && previous.job.isActive &&
                !previous.disconnect && (previous.lineId == lineId || (connected != null && lineId == null))) {
                return previous
            }
            val resolvedLine = if (operation == Operation.RECONNECT) lastRequestedLine else lineId
            if (local != null) require(lineId == null) { "Local admission requires an opaque selection" }
            val resolvedSelection = if (local == null) null else if (operation == Operation.RECONNECT) lastSelection
                else selection ?: SelectionIntent.Choose
            local?.invalidatePending()
            previous?.job?.cancel(CancellationException("Superseded connection command"))
            val next = Ticket(++sequence, resolvedLine, operation, disconnect, previous?.job, resolvedSelection)
            if (!disconnect && resolvedSelection != null) lastSelection = resolvedSelection
            if (!disconnect) lastRequestedLine = resolvedLine
            current = next
            mutableState.value = if (disconnect) ConnectionState.Disconnecting
                else ConnectionState.Working(if (local == null) Step.ACCOUNT else Step.CONFIG, operation)
            // UND starts inside execute's try/finally before awaiting begin. Even a command
            // cancelled before admission to lifecycle gets a cleanup-aware terminal outcome.
            next.job = scope.launch(start = CoroutineStart.UNDISPATCHED) { execute(next) }
            next
        }
        ticket.begin.complete(Unit)
        return ticket
    }

    private suspend fun await(ticket: Ticket): ConnectionState = try {
        ticket.result.await()
    } catch (cancelled: CancellationException) {
        ticket.job.cancel(cancelled)
        throw cancelled
    }

    private fun publish(ticket: Ticket, value: ConnectionState) = synchronized(commandLock) {
        if (current?.id == ticket.id) mutableState.value = value
    }

    private suspend fun publishActive(ticket: Ticket, value: ConnectionState, complete: Boolean = false) {
        val context = currentCoroutineContext()
        synchronized(commandLock) {
            context.ensureActive()
            if (current?.id != ticket.id || ticket.terminal != null)
                throw CancellationException("Connection generation terminated")
            mutableState.value = value
            if (complete) ticket.result.complete(value)
        }
    }

    private suspend fun checkpoint(ticket: Ticket) {
        currentCoroutineContext().ensureActive()
        synchronized(commandLock) {
            if (current?.id != ticket.id) throw CancellationException("Superseded connection command")
        }
    }

    private suspend fun execute(ticket: Ticket) {
        var acquired = false
        try {
            ticket.begin.await()
            lifecycle.withLock {
                acquired = true
            var stage = Step.ACCOUNT
            var material: AuthorizedConfig? = null
            var ownsNewTunnel = false
            var outcome: ConnectionState = ConnectionState.Disconnected
            try {
                checkpoint(ticket)
                // A previous cleanup failure is a safety latch. A new start is forbidden until
                // a later, explicit command successfully cleans the still-uncertain resources.
                if (tunnelMayBeRunning && !stopSafely()) {
                    throw Abort(ConnectionState.Failed(Failure.CLEANUP_FAILED))
                }
                if (preparedMaterialMayExist && !clearPreparedSafely()) {
                    throw Abort(ConnectionState.Failed(Failure.CLEANUP_FAILED))
                }
                checkpoint(ticket)
                if (ticket.disconnect) return@withLock

                suspend fun <T> step(value: Step, action: suspend () -> T): T {
                    stage = value
                    checkpoint(ticket)
                    publishActive(ticket, ConnectionState.Working(value, ticket.operation))
                    val budget = if (value == Step.PERMISSION) timeouts.permissionMillis else timeouts.stepMillis
                    return withTimeout(budget) { action() }.also { checkpoint(ticket) }
                }

                if (local == null) {
                    if (!step(Step.ACCOUNT) { requireNotNull(account).isAuthenticated() }) {
                        throw Abort(ConnectionState.Blocked(BusinessBlock.UNAUTHENTICATED))
                    }
                    step(Step.ENTITLEMENT) { requireNotNull(entitlement).current() }.block?.let {
                        throw Abort(ConnectionState.Blocked(it))
                    }
                }
                // Capture ownership inside the timed block; late material is always wiped.
                val authorized = step(Step.CONFIG) {
                    if (local == null) requireNotNull(config).forLine(ticket.lineId).also { material = it }
                    else when (val result = run {
                        preparedMaterialMayExist = true // A failed preparation can still finish its write.
                        requireNotNull(local).prepare(requireNotNull(ticket.selection))
                    }) {
                        is ConfigPreparation.Ready -> result.config.also { material = it }
                        is ConfigPreparation.Unavailable -> throw Abort(ConnectionState.Failed(
                            Failure.PREPARATION_FAILED, result.reason))
                        is ConfigPreparation.SelectionRequired -> throw Abort(ConnectionState.Failed(
                            Failure.SELECTION_REQUIRED, choices = result.choices))
                    }
                } ?: throw Abort(ConnectionState.Failed(Failure.CONFIG_UNAVAILABLE))
                if (local == null && ticket.lineId != null && ticket.lineId != authorized.lineId) {
                    throw Abort(ConnectionState.Failed(Failure.NO_LINE))
                }
                if (local != null && !authorized.requiresAtomicActivation) {
                    throw Abort(ConnectionState.Failed(Failure.CONFIG_UNAVAILABLE))
                }
                if (!step(Step.PERMISSION) { permission.hasVpnPermission() }) {
                    throw Abort(ConnectionState.Failed(Failure.PERMISSION_DENIED))
                }
                val started = step(Step.TUNNEL) {
                    ownsNewTunnel = true
                    tunnelMayBeRunning = true // start may partially allocate, even when it fails.
                    if (tunnel is TunnelPreparation && !tunnel.prepareTransport()) {
                        throw Abort(ConnectionState.Failed(Failure.TUNNEL_FAILED))
                    }
                    checkpoint(ticket)
                    if (authorized.requiresAtomicActivation) {
                        val atomic = tunnel as? AtomicActivationTunnel
                            ?: throw IllegalStateException("Atomic activation adapter required")
                        val context = currentCoroutineContext()
                        synchronized(commandLock) {
                            context.ensureActive()
                            if (current?.id != ticket.id) throw CancellationException("Superseded connection command")
                            authorized.activate {
                                context.ensureActive()
                                if (local != null) (atomic as LifecycleTunnel).accept(authorized) { event ->
                                    terminal(ticket, event)
                                } else atomic.startAtomically(authorized)
                            }
                        }
                    } else tunnel.start(authorized)
                }
                if (!started) throw Abort(ConnectionState.Failed(Failure.TUNNEL_FAILED))
                // The adapter has copied/consumed what it needs. Avoid keeping source bytes alive.
                authorized.close()
                if (!step(Step.TRAFFIC) { probe.isUsable() }) {
                    throw Abort(ConnectionState.Failed(Failure.TRAFFIC_FAILED))
                }
                checkpoint(ticket)
                val connected = ConnectionState.Connected(authorized.lineId)
                publishActive(ticket, connected, complete = true)
                // Keep tunnel lifetime tied to owner cancellation, not to the UI awaiter.
                awaitCancellation()
            } catch (abort: Abort) {
                outcome = abort.outcome
            } catch (_: TimeoutCancellationException) {
                outcome = if (local != null && stage == Step.CONFIG)
                    ConnectionState.Failed(Failure.PREPARATION_FAILED, PreparationFailure.TIMEOUT)
                    else ConnectionState.Failed(Failure.TIMEOUT)
            } catch (cancelled: CancellationException) {
                outcome = synchronized(commandLock) { ticket.terminal?.let { event ->
                    ConnectionState.Failed(if (event == TunnelTerminal.ENGINE_DIED) Failure.ENGINE_DIED
                        else Failure.PERMISSION_REVOKED)
                } ?: ConnectionState.Disconnected }
                throw cancelled
            } catch (failure: PreparationRejected) {
                outcome = ConnectionState.Failed(Failure.PREPARATION_FAILED, failure.reason)
            } catch (_: Exception) {
                // Exception messages may contain URLs/credentials. Expose only our safe enum.
                outcome = ConnectionState.Failed(when (stage) {
                    Step.ACCOUNT -> Failure.ACCOUNT_FAILED
                    Step.ENTITLEMENT -> Failure.ENTITLEMENT_FAILED
                    Step.CONFIG -> Failure.CONFIG_UNAVAILABLE
                    Step.PERMISSION -> Failure.PERMISSION_DENIED
                    Step.TUNNEL -> Failure.TUNNEL_FAILED
                    Step.TRAFFIC -> Failure.TRAFFIC_FAILED
                })
            } finally {
                material?.close()
                if (ownsNewTunnel && !stopSafely()) outcome = ConnectionState.Failed(Failure.CLEANUP_FAILED)
                if (!tunnelMayBeRunning && preparedMaterialMayExist && !clearPreparedSafely())
                    outcome = ConnectionState.Failed(Failure.CLEANUP_FAILED)
                // Still inside lifecycle: no newer start can race old cleanup.
                publish(ticket, outcome)
                ticket.result.complete(outcome)
            }
            }
        } finally {
            if (!acquired) withContext(NonCancellable) {
                publish(ticket, ConnectionState.Disconnecting)
                // Waiting for the specific predecessor cannot block on a newer connected owner.
                // Every cancelled queued ticket drains its predecessor before it completes.
                ticket.predecessor?.join()
                var outcome: ConnectionState = ConnectionState.Disconnected
                if (lifecycle.tryLock()) {
                    try {
                        if (tunnelMayBeRunning || preparedMaterialMayExist) outcome = ConnectionState.Failed(Failure.CLEANUP_FAILED)
                        publish(ticket, outcome)
                    } finally { lifecycle.unlock() }
                }
                // A newer worker can own lifecycle now. Do not wait for or stop that tunnel.
                ticket.result.complete(outcome)
            }
        }
    }

    private fun terminal(ticket: Ticket, event: TunnelTerminal) = synchronized(commandLock) {
        if (current?.id == ticket.id && ticket.job.isActive && ticket.terminal == null) {
            ticket.terminal = event
            local?.invalidatePending()
            mutableState.value = ConnectionState.Disconnecting
            ticket.job.cancel(CancellationException("Tunnel terminated"))
        }
    }

    private suspend fun stopSafely(): Boolean = withContext(NonCancellable) {
        try {
            withTimeout(timeouts.cleanupMillis) { tunnel.stop() }
            tunnelMayBeRunning = false
            true
        } catch (_: Exception) {
            tunnelMayBeRunning = true
            false
        }
    }

    private fun clearPreparedSafely(): Boolean = try {
        local?.clearPreparedMaterial()
        preparedMaterialMayExist = false
        true
    } catch (_: Exception) {
        preparedMaterialMayExist = true
        false
    }
}
