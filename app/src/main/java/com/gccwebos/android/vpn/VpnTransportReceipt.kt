package com.gccwebos.android.vpn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/** A binding receipt is cleanup authority even when activation has already been cancelled. */
internal class VpnTransportReceipt<T : Any> {
    val ready = CompletableDeferred<T>()
    val dead = CompletableDeferred<Unit>()
    private val received = CompletableDeferred<T>()
    private var value: T? = null
    private var witnessed = false
    private var stopping = false
    private var lost = false
    private var admitted = false

    @Synchronized fun capture(candidate: T, registerDeath: (T) -> Unit): Boolean {
        value?.let { return witnessed && it === candidate }
        value = candidate
        try { registerDeath(candidate) }
        catch (failure: Exception) { ready.completeExceptionally(failure); throw failure }
        witnessed = true
        received.complete(candidate)
        if (!stopping && !lost && !dead.isCompleted) ready.complete(candidate)
        else ready.completeExceptionally(IllegalStateException("VPN transport cannot activate"))
        return true
    }

    @Synchronized fun admit(): Boolean {
        if (!witnessed || stopping || lost || admitted || dead.isCompleted) return false
        admitted = true
        return true
    }

    @Synchronized fun beginStop() { stopping = true }
    @Synchronized fun isStopping(): Boolean = stopping
    @Synchronized fun binder(): T? = value
    @Synchronized fun isCurrent(): Boolean = admitted && !stopping && !lost && !dead.isCompleted
    @Synchronized fun transportLost() {
        lost = true
        ready.completeExceptionally(IllegalStateException("VPN transport lost"))
    }
    @Synchronized fun binderDied() {
        dead.complete(Unit)
        ready.completeExceptionally(IllegalStateException("VPN transport died"))
    }

    suspend fun forCleanup(bound: Boolean): T? {
        synchronized(this) { if (witnessed) return requireNotNull(value) }
        if (!bound) return null
        return received.await()
    }

    suspend fun stopAndReleaseBinding(bound: Boolean, requestStop: suspend (T) -> Unit,
        releaseBinding: suspend () -> Unit) {
        val remote = withTimeout(500) { forCleanup(bound) }
        if (remote != null && !dead.isCompleted) requestStop(remote)
        // AUTO_CREATE must be removed before death, otherwise Android can start a successor.
        releaseBinding()
        if (remote != null) withTimeout(4_500) { dead.await() }
    }
}
