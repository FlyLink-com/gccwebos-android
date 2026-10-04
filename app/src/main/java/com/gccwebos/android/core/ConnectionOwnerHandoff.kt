package com.gccwebos.android.core

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal sealed interface ConnectionOwnerAvailability {
    data object Waiting : ConnectionOwnerAvailability
    data class Ready(val owner: ConnectionCoordinator) : ConnectionOwnerAvailability
    data object Unavailable : ConnectionOwnerAvailability
    data object Destroyed : ConnectionOwnerAvailability
}

/** One Service incarnation. Waiting never owns the old cleanup job or its release signal.
 * Factory registration happens under authority's lock, outside this controller/UI lock.
 * A claim is either adopted before retirement, or cleaned by the initializer without publication. */
internal class ConnectionOwnerHandoff(
    private val scope: CoroutineScope,
    private val awaitClaim: suspend (() -> ConnectionCoordinator) -> ConnectionCoordinator,
    private val factory: () -> ConnectionCoordinator,
    private val waitMillis: Long = 8_000,
) {
    private val lock = Any()
    private val destroyed = AtomicBoolean(false)
    private val availability = MutableStateFlow<ConnectionOwnerAvailability>(ConnectionOwnerAvailability.Waiting)
    val state: StateFlow<ConnectionOwnerAvailability> = availability.asStateFlow()
    private var adopted: ConnectionCoordinator? = null
    private var retirement: CompletableDeferred<ConnectionState?>? = null
    private var discardedOutcome: ConnectionState? = null
    private var discardedFailure: Exception? = null
    private val initializer = scope.launch(start = CoroutineStart.LAZY) {
        var claimed: ConnectionCoordinator? = null
        var installed = false
        try {
            val owner = withTimeout(waitMillis) {
                val acquiringContext = currentCoroutineContext()
                awaitClaim {
                    acquiringContext.ensureActive()
                    if (destroyed.get()) throw CancellationException("Owner incarnation retired")
                    factory().also { claimed = it }
                }
            }
            val context = currentCoroutineContext()
            context.ensureActive()
            synchronized(lock) {
                context.ensureActive()
                if (!destroyed.get()) {
                    adopted = owner
                    installed = true
                    availability.value = ConnectionOwnerAvailability.Ready(owner)
                }
            }
        } catch (_: TimeoutCancellationException) {
            synchronized(lock) { if (!destroyed.get()) availability.value = ConnectionOwnerAvailability.Unavailable }
        } catch (cancelled: CancellationException) {
            synchronized(lock) { if (!destroyed.get()) availability.value = ConnectionOwnerAvailability.Unavailable }
            throw cancelled
        } catch (_: Exception) {
            synchronized(lock) { if (!destroyed.get()) availability.value = ConnectionOwnerAvailability.Unavailable }
        } finally {
            if (!installed) claimed?.let { owner ->
                withContext(NonCancellable) {
                    try {
                        val outcome = owner.close()
                        synchronized(lock) { discardedOutcome = outcome }
                    } catch (failure: Exception) {
                        synchronized(lock) {
                            discardedFailure = failure
                            if (!destroyed.get()) availability.value = ConnectionOwnerAvailability.Unavailable
                        }
                    }
                }
            }
        }
    }

    init { require(waitMillis > 0) }
    fun start() { initializer.start() }

    /** Closes admission before returning. All callers share one parent-independent cleanup receipt.
     * The cleanup task is not a child of coordinator.owner (which close cancels/joins). */
    fun retire(): Deferred<ConnectionState?> = synchronized(lock) {
        retirement?.let { return it }
        destroyed.set(true)
        availability.value = ConnectionOwnerAvailability.Destroyed
        val receipt = CompletableDeferred<ConnectionState?>()
        retirement = receipt
        val owner = adopted
        adopted = null
        var beginFailure: Exception? = null
        try { owner?.beginClose() }
        catch (failure: Exception) { beginFailure = failure }
        finally { initializer.cancel() }
        scope.launch(NonCancellable) {
            try {
                initializer.join() // Includes cleanup of a registered but never adopted owner.
                val outcome = owner?.close() ?: synchronized(lock) { discardedOutcome }
                val failure = beginFailure ?: synchronized(lock) { discardedFailure }
                if (failure != null) receipt.completeExceptionally(failure) else receipt.complete(outcome)
            } catch (failure: Throwable) { receipt.completeExceptionally(failure) }
        }
        receipt
    }
}
