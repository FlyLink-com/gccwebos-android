package com.gccwebos.android.portal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where the signed-in account's own subscription load stands. Display-only; carries no URL, token or content. */
internal enum class AccountLoadState { Idle, Loading, Failed }

internal object AccountLoadPolicy {
    /** Automatic retries after a failed load, each after the matching pause; then the user's own retry is the only way. */
    val BackoffMillis = longArrayOf(4_000L, 12_000L, 30_000L)
}

/** How one load attempt ended. */
internal enum class AccountLoadOutcome {
    /** The account's nodes are now loaded. */
    Loaded,
    /** The request completed but produced no usable subscription (for example an unsupported format); retrying cannot help. */
    NotLoaded,
    /** The request failed (network, service unavailable); worth retrying. */
    Failed,
    /** Superseded or cancelled; nothing to show. */
    Cancelled
}

/**
 * Drives the signed-in account's subscription load with a visible state and a few automatic retries.
 * [start] begins one attempt and reports its [AccountLoadOutcome]; it returns false when the attempt could not
 * start right now (something else is busy), which counts as a failed attempt for the retry schedule.
 * A manual request restarts the schedule. The class never reads or logs any subscription material.
 */
internal class AccountLoadCoordinator(private val scope: CoroutineScope,
    private val start: (report: (AccountLoadOutcome) -> Unit) -> Boolean,
    private val backoffMillis: LongArray = AccountLoadPolicy.BackoffMillis) {
    var state by mutableStateOf(AccountLoadState.Idle); private set
    private var retries = 0
    private var retryJob: Job? = null
    private var generation = 0L

    /** Starts a load now. [manual] (the user's tap) resets the automatic retry budget. */
    fun request(manual: Boolean = false): Boolean {
        if (manual) retries = 0
        retryJob?.cancel(); retryJob = null
        if (attempt()) return true
        if (manual) return false
        scheduleRetry()
        return false
    }

    /** Sign-out, account change or any superseding action: forget the state and stop retrying. */
    fun cancel() {
        generation++
        retryJob?.cancel(); retryJob = null
        retries = 0
        state = AccountLoadState.Idle
    }

    private fun attempt(): Boolean {
        val mine = ++generation
        val before = state
        state = AccountLoadState.Loading
        val started = start { outcome -> if (mine == generation) settle(outcome) }
        if (!started) state = if (before == AccountLoadState.Loading) AccountLoadState.Failed else before
        return started
    }

    private fun settle(outcome: AccountLoadOutcome) {
        when (outcome) {
            AccountLoadOutcome.Loaded -> { state = AccountLoadState.Idle; retries = 0 }
            AccountLoadOutcome.Cancelled -> state = AccountLoadState.Idle
            AccountLoadOutcome.NotLoaded -> state = AccountLoadState.Failed
            AccountLoadOutcome.Failed -> { state = AccountLoadState.Failed; scheduleRetry() }
        }
    }

    private fun scheduleRetry() {
        state = AccountLoadState.Failed
        if (retries >= backoffMillis.size) return
        val pause = backoffMillis[retries++]
        retryJob = scope.launch {
            delay(pause)
            retryJob = null
            if (!attempt()) scheduleRetry()
        }
    }
}
