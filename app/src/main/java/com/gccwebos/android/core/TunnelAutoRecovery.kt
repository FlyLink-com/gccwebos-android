package com.gccwebos.android.core

/**
 * Decides whether an unexpected engine death of an established tunnel is retried automatically.
 * Pure policy (no IO, no threads): the owner feeds every coordinator state and user command, and runs the returned
 * restart after [Decision.Restart.delayMillis] only if [stillWanted] confirms nothing changed meanwhile.
 *
 * Rules:
 * - Only [Failure.ENGINE_DIED] after a Connected tunnel (or during an automatic reconnect) is retried.
 *   Permission revocation, user disconnect, business blocks and every other failure are never retried.
 * - At most [maxAttempts] automatic restarts; backoff grows per attempt. A tunnel that stays Connected for
 *   [stableMillis] earns a fresh budget.
 * - Without a usable network the restart is deferred (not consumed) until [onNetworkAvailable].
 */
class TunnelAutoRecovery(
    private val now: () -> Long,
    private val maxAttempts: Int = 3,
    private val backoffMillis: List<Long> = listOf(1_000, 4_000, 10_000),
    private val stableMillis: Long = 60_000,
) {
    sealed interface Decision {
        data object None : Decision
        data class Restart(val attempt: Int, val delayMillis: Long, val token: Long) : Decision
        /** Out of attempts: surface the failure to the user (manual 重试连接). */
        data object GiveUp : Decision
        /** Waiting for the network; nothing scheduled yet. */
        data object WaitingForNetwork : Decision
    }

    private var armed = false
    private var connectedAt: Long? = null
    private var attempts = 0
    private var token = 0L
    private var waitingForNetwork = false
    var recovering = false; private set

    fun onState(state: ConnectionState, networkAvailable: Boolean): Decision {
        when (state) {
            is ConnectionState.Connected -> {
                armed = true; recovering = false; waitingForNetwork = false
                if (connectedAt == null) connectedAt = now()
                return Decision.None
            }
            is ConnectionState.Working, ConnectionState.Disconnecting -> return Decision.None
            is ConnectionState.Failed -> {
                val since = connectedAt
                if (since != null && now() - since >= stableMillis) attempts = 0
                connectedAt = null
                if (state.reason != Failure.ENGINE_DIED || !(armed || recovering)) return reset()
                if (attempts >= maxAttempts) { reset(); return Decision.GiveUp }
                recovering = true
                if (!networkAvailable) { waitingForNetwork = true; return Decision.WaitingForNetwork }
                return schedule()
            }
            ConnectionState.Disconnected, is ConnectionState.Blocked -> {
                if (recovering && state == ConnectionState.Disconnected) return Decision.None // restart in flight
                return reset()
            }
        }
    }

    fun onNetworkAvailable(): Decision =
        if (recovering && waitingForNetwork) { waitingForNetwork = false; schedule() } else Decision.None

    /** Any explicit user command (connect, disconnect, cancel, node switch) cancels automatic recovery. */
    fun onUserCommand() { reset(); token++ }

    /** True only if the scheduled restart [token] is still the latest wanted one. */
    fun stillWanted(token: Long): Boolean = recovering && !waitingForNetwork && token == this.token

    private fun schedule(): Decision {
        val attempt = ++attempts
        val delay = backoffMillis.getOrElse(attempt - 1) { backoffMillis.last() }
        return Decision.Restart(attempt, delay, ++token)
    }

    private fun reset(): Decision {
        armed = false; recovering = false; waitingForNetwork = false; connectedAt = null; attempts = 0
        return Decision.None
    }
}
