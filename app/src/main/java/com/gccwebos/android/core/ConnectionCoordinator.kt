package com.gccwebos.android.core

import java.io.Closeable
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
    data class Failed(val reason: Failure) : ConnectionState
}

enum class Operation { CONNECT, SWITCH, RECONNECT }
enum class Step { ACCOUNT, ENTITLEMENT, CONFIG, PERMISSION, TUNNEL, TRAFFIC }
enum class BusinessBlock { UNAUTHENTICATED, NO_PLAN, PROVISIONING, EXPIRED, QUOTA_EXHAUSTED }
enum class Failure {
    NO_LINE, ACCOUNT_FAILED, ENTITLEMENT_FAILED, CONFIG_UNAVAILABLE,
    PERMISSION_DENIED, TUNNEL_FAILED, TRAFFIC_FAILED, TIMEOUT, CLEANUP_FAILED,
}

data class AccessDecision(val block: BusinessBlock? = null)

/**
 * Owns a copy of secret configuration bytes. Never put configuration in logs, saved state,
 * analytics or navigation arguments. Consumers own (and must wipe) copies they request.
 * close() wipes the coordinator's copy, not the caller's constructor argument.
 */
class AuthorizedConfig(val lineId: String, material: ByteArray,
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
 * cancellation/watchdog contract; no production implementation exists in this slice.
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
    private val account: AccountGateway,
    private val entitlement: EntitlementGateway,
    private val config: ConfigGateway,
    private val permission: PermissionGateway,
    private val tunnel: TunnelGateway,
    private val probe: TrafficProbe,
    parentScope: CoroutineScope,
    private val timeouts: ConnectionTimeouts = ConnectionTimeouts(),
) {
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
    private var lastRequestedLine: String? = null
    // Only accessed while holding lifecycle. True is deliberately retained on cleanup failure.
    private var tunnelMayBeRunning = false

    private class Ticket(
        val id: Long,
        val lineId: String?,
        val operation: Operation,
        val disconnect: Boolean,
        val predecessor: Job?,
    ) {
        val begin = CompletableDeferred<Unit>()
        val result = CompletableDeferred<ConnectionState>()
        lateinit var job: Job
    }

    private class Abort(val outcome: ConnectionState) : Exception(null, null, false, false)

    suspend fun connect(lineId: String? = null): ConnectionState = await(submit(lineId, Operation.CONNECT))

    suspend fun switchLine(lineId: String): ConnectionState {
        require(lineId.isNotBlank()) { "A non-empty line is required" }
        return await(submit(lineId, Operation.SWITCH))
    }

    suspend fun reconnect(): ConnectionState = await(submit(null, Operation.RECONNECT))

    suspend fun disconnect(): ConnectionState = await(submit(null, Operation.CONNECT, disconnect = true))

    /** Call from the lifecycle owner. Safe to repeat. No new commands are admitted afterwards. */
    suspend fun close(): ConnectionState {
        val ticket = synchronized(commandLock) {
            if (closed || !owner.isActive) {
                closed = true
                null
            } else {
                // submit's monitor is re-entrant. Reserve the final stop before closing admission.
                val next = submit(null, Operation.CONNECT, disconnect = true)
                closed = true
                next
            }
        }
        return withContext(NonCancellable) {
            if (ticket != null) ticket.result.await()
            owner.cancel()
            owner.join()
            lifecycle.withLock {
                if (tunnelMayBeRunning) {
                    mutableState.value = if (stopSafely()) ConnectionState.Disconnected
                        else ConnectionState.Failed(Failure.CLEANUP_FAILED)
                }
            }
            state.value
        }
    }

    private fun submit(lineId: String?, operation: Operation, disconnect: Boolean = false): Ticket {
        val ticket = synchronized(commandLock) {
            check(!closed && owner.isActive) { "Connection coordinator is closed" }
            val previous = current
            val connected = mutableState.value as? ConnectionState.Connected
            // Only identical active ordinary connects are coalesced. Switch/reconnect are explicit.
            if (!disconnect && operation == Operation.CONNECT && previous != null && previous.job.isActive &&
                !previous.disconnect && (previous.lineId == lineId || (connected != null && lineId == null))) {
                return previous
            }
            val resolvedLine = if (operation == Operation.RECONNECT) lastRequestedLine else lineId
            previous?.job?.cancel(CancellationException("Superseded connection command"))
            val next = Ticket(++sequence, resolvedLine, operation, disconnect, previous?.job)
            if (!disconnect) lastRequestedLine = resolvedLine
            current = next
            mutableState.value = if (disconnect) ConnectionState.Disconnecting
                else ConnectionState.Working(Step.ACCOUNT, operation)
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
                checkpoint(ticket)
                if (ticket.disconnect) return@withLock

                suspend fun <T> step(value: Step, action: suspend () -> T): T {
                    stage = value
                    checkpoint(ticket)
                    publish(ticket, ConnectionState.Working(value, ticket.operation))
                    val budget = if (value == Step.PERMISSION) timeouts.permissionMillis else timeouts.stepMillis
                    return withTimeout(budget) { action() }.also { checkpoint(ticket) }
                }

                if (!step(Step.ACCOUNT) { account.isAuthenticated() }) {
                    throw Abort(ConnectionState.Blocked(BusinessBlock.UNAUTHENTICATED))
                }
                step(Step.ENTITLEMENT) { entitlement.current() }.block?.let {
                    throw Abort(ConnectionState.Blocked(it))
                }
                // Capture ownership *inside* the timed block, before a timeout/checkpoint could
                // discard the result. A late config is always wiped in finally.
                val authorized = step(Step.CONFIG) { config.forLine(ticket.lineId).also { material = it } }
                    ?: throw Abort(ConnectionState.Failed(Failure.CONFIG_UNAVAILABLE))
                if (ticket.lineId != null && ticket.lineId != authorized.lineId) {
                    throw Abort(ConnectionState.Failed(Failure.NO_LINE))
                }
                if (!step(Step.PERMISSION) { permission.hasVpnPermission() }) {
                    throw Abort(ConnectionState.Failed(Failure.PERMISSION_DENIED))
                }
                val started = step(Step.TUNNEL) {
                    ownsNewTunnel = true
                    tunnelMayBeRunning = true // start may partially allocate, even when it fails.
                    if (authorized.requiresAtomicActivation) {
                        val atomic = tunnel as? AtomicActivationTunnel
                            ?: throw IllegalStateException("Atomic activation adapter required")
                        val context = currentCoroutineContext()
                        synchronized(commandLock) {
                            context.ensureActive()
                            if (current?.id != ticket.id) throw CancellationException("Superseded connection command")
                            authorized.activate { context.ensureActive(); atomic.startAtomically(authorized) }
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
                publish(ticket, connected)
                ticket.result.complete(connected)
                // Keep tunnel lifetime tied to owner cancellation, not to the UI awaiter.
                awaitCancellation()
            } catch (abort: Abort) {
                outcome = abort.outcome
            } catch (_: TimeoutCancellationException) {
                outcome = ConnectionState.Failed(Failure.TIMEOUT)
            } catch (cancelled: CancellationException) {
                outcome = ConnectionState.Disconnected
                throw cancelled
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
                        if (tunnelMayBeRunning) outcome = ConnectionState.Failed(Failure.CLEANUP_FAILED)
                        publish(ticket, outcome)
                    } finally { lifecycle.unlock() }
                }
                // A newer worker can own lifecycle now. Do not wait for or stop that tunnel.
                ticket.result.complete(outcome)
            }
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
}
