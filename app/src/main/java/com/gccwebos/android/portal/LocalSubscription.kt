package com.gccwebos.android.portal

import com.gccwebos.android.core.AuthorizedConfig
import com.gccwebos.android.core.ConfigGateway
import com.gccwebos.android.vpn.SafeProxyConfig
import com.gccwebos.android.vpn.UnsafeProxyConfiguration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CachedSubscription(val binding: String, bytes: ByteArray, val validUntil: Instant?, val url: Secret? = null,
    val observedStatus: LocalSubscriptionStatus? = null) : AutoCloseable {
    private var material: ByteArray? = bytes.copyOf()
    fun copyBytes(): ByteArray = requireNotNull(material).copyOf()
    override fun close() { material?.fill(0); material = null }
    override fun toString() = "CachedSubscription([REDACTED])"
}
interface LocalProfileStore {
    fun read(): CachedSubscription?
    fun save(value: CachedSubscription)
    fun clear()
}
class MemoryLocalProfileStore : LocalProfileStore {
    private var value: CachedSubscription? = null
    override fun read(): CachedSubscription? = value?.let { old -> val bytes = old.copyBytes(); try { CachedSubscription(old.binding, bytes, old.validUntil, old.url, old.observedStatus) } finally { bytes.fill(0) } }
    override fun save(value: CachedSubscription) { val bytes = value.copyBytes(); try { this.value?.close(); this.value = CachedSubscription(value.binding, bytes, value.validUntil, value.url, value.observedStatus) } finally { bytes.fill(0) } }
    override fun clear() { value?.close(); value = null }
}
enum class LocalSubscriptionStatus { MISSING, READY, EXPIRED, RETRY_REQUIRED, INVALID_LINK, UNSUPPORTED, STORAGE_ERROR }
data class LocalSubscriptionSnapshot(val status: LocalSubscriptionStatus, val hasProfile: Boolean = false,
    val hasCapability: Boolean = false, val nodes: List<SafeProxyConfig.NodeSummary> = emptyList(), val validUntil: Instant? = null)
fun interface LocalConnectionLineResolver {
    /** Display indexes are not authoritative stable line IDs. No production resolver is installed. */
    fun resolve(snapshot: LocalSubscriptionSnapshot, requestedLineId: String?): ResolvedConnectionLine?
}

/** Local encrypted capability lifecycle; never obtains or sends a Portal session token.
 * Cached bytes are UI evidence only. Every prepare downloads and validates fresh artifact bytes.
 */
class LocalSubscriptionManager(private val delivery: DeliveryStore, private val profiles: LocalProfileStore,
    private val source: SubscriptionSource, private val policy: OriginPolicy, private val now: () -> Instant) {
    private val mutex = Mutex()
    private val lock = Any()
    private var revision = 0L
    private var state = LocalSubscriptionSnapshot(LocalSubscriptionStatus.MISSING)
    fun snapshot(): LocalSubscriptionSnapshot = synchronized(lock) { state }
    private fun capability(): Secret? {
        val cached = profiles.read()
        try { cached?.url?.let { policy.subscription(it.value); require(sessionBinding(it) == cached.binding); return it } }
        finally { cached?.close() }
        return delivery.read()?.takeIf { !it.pendingAck }?.url?.also { policy.subscription(it.value) }
    }
    private fun sameCapability(binding: String) = capability()?.let(::sessionBinding) == binding
    private fun describe(raw: ByteArray, until: Instant?): LocalSubscriptionSnapshot {
        val nodes = SafeProxyConfig.inspect(raw)
        val projected = SafeProxyConfig.project(raw, 0)
        projected.fill(0)
        return LocalSubscriptionSnapshot(if (until != null && until <= now()) LocalSubscriptionStatus.EXPIRED else LocalSubscriptionStatus.READY,
            true, true, nodes, until)
    }
    /** Only called after acknowledged delivery and strict projection; never a connection admission. */
    fun remember(raw: ByteArray, url: Secret, until: Instant?): LocalSubscriptionSnapshot = synchronized(lock) {
        val binding = sessionBinding(url)
        check(sameCapability(binding)) { "Subscription changed" }
        val next = describe(raw, until)
        val cached = CachedSubscription(binding, raw, until, url)
        try { profiles.save(cached) } finally { cached.close() }
        revision++; state = next; next
    }
    fun unlink() = synchronized(lock) {
        revision++; state = LocalSubscriptionSnapshot(LocalSubscriptionStatus.MISSING)
        try { profiles.clear() } finally { delivery.clear() }
    }
    private fun rememberObservation(kind: LocalSubscriptionStatus) {
        if (kind !in setOf(LocalSubscriptionStatus.EXPIRED, LocalSubscriptionStatus.INVALID_LINK)) return
        val old = profiles.read() ?: return
        try {
            val raw = old.copyBytes()
            val updated = try { CachedSubscription(old.binding, raw, old.validUntil, old.url, kind) } finally { raw.fill(0) }
            try { profiles.save(updated) } finally { updated.close() }
        } finally { old.close() }
    }
    suspend fun restore(): LocalSubscriptionSnapshot = mutex.withLock {
        try {
            val url = capability() ?: return@withLock synchronized(lock) { state = LocalSubscriptionSnapshot(LocalSubscriptionStatus.MISSING); state }
            val binding = sessionBinding(url)
            val cached = profiles.read()
            if (cached != null) try {
                if (cached.binding == binding) {
                    val raw = cached.copyBytes()
                    try { return@withLock synchronized(lock) { check(sameCapability(binding)); state = describe(raw, cached.validUntil).let { parsed -> cached.observedStatus?.let { parsed.copy(status = it) } ?: parsed }; state } }
                    finally { raw.fill(0) }
                }
            } finally { cached.close() }
            // Upgrade from a durable acknowledged receipt: no Portal login or setup/claim call.
            refreshLocked(null, null, null)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { synchronized(lock) { state = LocalSubscriptionSnapshot(LocalSubscriptionStatus.STORAGE_ERROR, hasCapability = capability() != null); state } }
    }
    suspend fun refresh(): LocalSubscriptionSnapshot = mutex.withLock { refreshLocked(null, null, null) }
    suspend fun prepare(requested: String?, resolver: LocalConnectionLineResolver, files: AtomicPreparedConfigStore): AuthorizedConfig? {
        var prepared: AuthorizedConfig? = null
        try {
            mutex.withLock { refreshLocked(requested, resolver) { snapshot, raw, binding, epoch ->
                if (snapshot.status != LocalSubscriptionStatus.READY) return@refreshLocked
                val selected = resolver.resolve(snapshot, requested) ?: return@refreshLocked
                require(requested == null || requested == selected.lineId)
                val bytes = SafeProxyConfig.project(raw, selected.index)
                try {
                    var consumed = false
                    prepared = AuthorizedConfig(selected.lineId, bytes) { action -> synchronized(lock) {
                        check(!consumed); consumed = true
                        check(revision == epoch && sameCapability(binding)) { "Subscription changed" }
                        check(snapshot.validUntil == null || snapshot.validUntil > now()) { "Subscription expired" }
                        val copy = requireNotNull(prepared).copyMaterial()
                        try { files.replace(copy) } finally { copy.fill(0) }
                        check(revision == epoch && sameCapability(binding)) { "Subscription changed" }
                        check(snapshot.validUntil == null || snapshot.validUntil > now()) { "Subscription expired" }
                        action()
                    } }
                } finally { bytes.fill(0) }
            } }
            currentCoroutineContext().ensureActive()
            return prepared
        } catch (failure: Throwable) { prepared?.close(); throw failure }
    }
    private suspend fun refreshLocked(requested: String?, resolver: LocalConnectionLineResolver?,
        prepared: ((LocalSubscriptionSnapshot, ByteArray, String, Long) -> Unit)?): LocalSubscriptionSnapshot {
        val epoch = synchronized(lock) { ++revision }
        try {
            val url = capability() ?: return synchronized(lock) { state = LocalSubscriptionSnapshot(LocalSubscriptionStatus.MISSING); state }
            val binding = sessionBinding(url)
            val result = source.read(url)
            try {
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    check(revision == epoch && sameCapability(binding)) { "Subscription changed" }
                    val until = result.validUntil ?: state.validUntil
                    val next = try { describe(result.bytes, until) } catch (_: UnsafeProxyConfiguration) {
                        val nodes = try { SafeProxyConfig.inspect(result.bytes) } catch (_: Exception) { emptyList() }
                        state = state.copy(status = LocalSubscriptionStatus.UNSUPPORTED, hasCapability = true, nodes = nodes)
                        return state
                    }
                    val cached = CachedSubscription(binding, result.bytes, until, url)
                    try { profiles.save(cached) } finally { cached.close() }
                    state = next
                    if (resolver != null) prepared?.invoke(next, result.bytes, binding, epoch)
                    return next
                }
            } finally { result.bytes.fill(0) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            return synchronized(lock) {
                if (revision != epoch) throw CancellationException("Subscription operation cancelled")
                val kind = when {
                    failure is UnsafeProxyConfiguration -> LocalSubscriptionStatus.UNSUPPORTED
                    failure is PortalFailure && failure.reason == PortalFailure.Reason.SUBSCRIPTION_EXPIRED -> LocalSubscriptionStatus.EXPIRED
                    failure is PortalFailure && failure.reason == PortalFailure.Reason.SUBSCRIPTION_REVOKED -> LocalSubscriptionStatus.INVALID_LINK
                    failure is PortalFailure && failure.reason == PortalFailure.Reason.STORAGE -> LocalSubscriptionStatus.STORAGE_ERROR
                    else -> LocalSubscriptionStatus.RETRY_REQUIRED
                }
                rememberObservation(kind)
                state = state.copy(status = kind, hasCapability = capability() != null)
                state
            }
        }
    }
}
/** Production service/line resolver remains separately gated; no cached load/start path exists. */
class FreshLocalSubscriptionGateway(private val manager: LocalSubscriptionManager,
    private val resolver: LocalConnectionLineResolver, private val files: AtomicPreparedConfigStore) : ConfigGateway {
    override suspend fun forLine(lineId: String?): AuthorizedConfig? = manager.prepare(lineId, resolver, files)
}
