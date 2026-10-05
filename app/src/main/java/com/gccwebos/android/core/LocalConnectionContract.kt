package com.gccwebos.android.core

/** Opaque, in-memory selection. No backend identity, credentials, digest or ordinal is exposed. */
class ConnectionSelection internal constructor() {
    override fun toString() = "ConnectionSelection(<opaque>)"
}
data class ConnectionChoice(val selection: ConnectionSelection, val name: String, val protocol: String)
sealed interface SelectionIntent {
    data object Choose : SelectionIntent
    data class Selected(val selection: ConnectionSelection) : SelectionIntent
}
enum class PreparationFailure { MISSING, EXPIRED, INVALID_LINK, NETWORK, TIMEOUT, UNSUPPORTED, STORAGE, STALE }
sealed interface ConfigPreparation {
    data class Ready(val config: AuthorizedConfig) : ConfigPreparation
    data class Unavailable(val reason: PreparationFailure) : ConfigPreparation
    data class SelectionRequired(val choices: List<ConnectionChoice>) : ConfigPreparation
}

/** The service-owned instance is shared with refresh/unlink operations; never page-owned.
 * invalidatePending is synchronous and fences writes as well as engine admission.
 */
interface LocalConfigGateway {
    suspend fun prepare(intent: SelectionIntent): ConfigPreparation
    fun invalidatePending()
    fun observeClear(onClear: () -> Unit): java.io.Closeable
    /** Serialized lifecycle cleanup, including a failed/denied attempt before TUN ownership. */
    fun clearPreparedMaterial() = Unit
}
enum class TunnelTerminal { ENGINE_DIED, PERMISSION_REVOKED }

/** Returning true means the bounded synchronous engine boundary
 * has accepted/copied the bytes. Enqueueing an Android Intent is NOT an implementation.
 * The callback belongs to this acceptance generation, including partial/failed acceptance.
 */
interface LifecycleTunnel : AtomicActivationTunnel {
    fun accept(config: AuthorizedConfig, terminal: (TunnelTerminal) -> Unit): Boolean
    override fun startAtomically(config: AuthorizedConfig): Boolean = error("Generation callback required")
    override suspend fun start(config: AuthorizedConfig): Boolean = error("Atomic acceptance required")
}

/** Optional transport allocation. The coordinator owns cleanup before invoking this boundary,
 * outside the capability/command acceptance fences. Success does not mean engine acceptance.
 */
interface TunnelPreparation {
    suspend fun prepareTransport(): Boolean
    suspend fun prepareTransport(config: AuthorizedConfig): Boolean = prepareTransport()
}

/** One attempt's physical transport, separate from resource expiry and DNS TTL. */
interface ConnectionTransportLease : java.io.Closeable {
    val networkHandle: Long
    val validUntilElapsedMillis: Long
    fun isCurrent(): Boolean
}

/** Safe typed activation failure; never carries raw response/configuration details. */
class PreparationRejected(val reason: PreparationFailure) : Exception(reason.name)
