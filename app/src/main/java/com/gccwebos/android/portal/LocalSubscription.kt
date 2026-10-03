package com.gccwebos.android.portal

import com.gccwebos.android.core.AuthorizedConfig
import com.gccwebos.android.core.ConfigGateway
import com.gccwebos.android.core.ConfigPreparation
import com.gccwebos.android.core.ConnectionChoice
import com.gccwebos.android.core.ConnectionSelection
import com.gccwebos.android.core.LocalConfigGateway
import com.gccwebos.android.core.PreparationRejected
import com.gccwebos.android.core.PreparationFailure
import com.gccwebos.android.core.SelectionIntent
import java.security.MessageDigest
import java.io.Closeable
import com.gccwebos.android.vpn.SafeProxyConfig
import com.gccwebos.android.vpn.UnsafeProxyConfiguration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.CompletableDeferred
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
/** Credential-free, process-local UI selection; never connection admission or a persisted ID. */
data class NodeSelectionSnapshot(val status: LocalSubscriptionStatus,
    val choices: List<ConnectionChoice> = emptyList(), val selected: ConnectionSelection? = null,
    val validUntil: Instant? = null)
fun interface LocalConnectionLineResolver {
    /** Display indexes are not authoritative stable line IDs. No production resolver is installed. */
    fun resolve(snapshot: LocalSubscriptionSnapshot, requestedLineId: String?): ResolvedConnectionLine?
}

/** One application-local subscription authority, shared even across repository recreation.
 * Tests may inject an isolated authority. No state/selection is persisted as connection authority.
 * A second connection owner is rejected until the first releases its lease after cleanup.
 */
class LocalSubscriptionAuthority {
    internal val mutex = Mutex()
    internal val lock = Any()
    internal var epoch = 0L
    internal var clearVersion = 0L
    internal var selectionRevision: ByteArray? = null
    internal var selectionBinding: String? = null
    internal var selectionSchema = -1
    internal var selectionClearVersion = -1L
    internal var choices = emptyList<ConnectionChoice>()
    internal var selected: ConnectionSelection? = null
    internal var selectionStatus: LocalSubscriptionStatus? = null
    internal var selectionUntil: Instant? = null
    internal var selectionRequiresRefresh = false
    internal var clearObserver: (() -> Unit)? = null
    private var ownerReleased = CompletableDeferred<Unit>().apply { complete(Unit) }
    internal fun observeClear(observer: () -> Unit): Closeable = synchronized(lock) {
        check(clearObserver == null) { "A local connection owner already exists" }
        clearObserver = observer
        val release = CompletableDeferred<Unit>()
        ownerReleased = release
        Closeable {
            val released = synchronized(lock) {
                if (clearObserver === observer && ownerReleased === release) {
                    clearObserver = null
                    release
                } else null
            }
            released?.complete(Unit) // A waiter never owns/cancels this identity-scoped signal.
        }
    }
    /** Check and factory registration share the authority lock; competing waiters cannot adopt
     * the same vacancy. The factory must only construct/register, never take UI locks or do IO.
     * Only the matching lease's real cleanup completion wakes waiters. */
    internal suspend fun <T> awaitConnectionOwner(factory: () -> T): T {
        while (true) {
            val context = currentCoroutineContext()
            context.ensureActive()
            val released = synchronized(lock) {
                context.ensureActive()
                if (clearObserver == null) return factory()
                ownerReleased
            }
            released.await()
        }
    }
    companion object { internal val process = LocalSubscriptionAuthority() }
}

/** Local encrypted capability lifecycle; never obtains or sends a Portal session token.
 * Cached bytes are UI evidence only. Every prepare downloads and validates fresh artifact bytes.
 */
class LocalSubscriptionManager(private val delivery: DeliveryStore, private val profiles: LocalProfileStore,
    private val source: SubscriptionSource, private val policy: OriginPolicy, private val now: () -> Instant,
    private val authority: LocalSubscriptionAuthority = LocalSubscriptionAuthority.process) {
    private val mutex get() = authority.mutex
    private val lock get() = authority.lock
    private var revision: Long
        get() = authority.epoch
        set(value) { authority.epoch = value }
    fun invalidatePending() = synchronized(lock) { revision++; Unit }
    fun observeClear(observer: () -> Unit): Closeable = authority.observeClear(observer)
    internal suspend fun <T> awaitConnectionOwner(factory: () -> T): T = authority.awaitConnectionOwner(factory)
    private var stateClearVersion = authority.clearVersion
    private var observed = LocalSubscriptionSnapshot(LocalSubscriptionStatus.MISSING)
    private var state: LocalSubscriptionSnapshot
        get() = if (stateClearVersion == authority.clearVersion) observed
            else LocalSubscriptionSnapshot(LocalSubscriptionStatus.MISSING)
        set(value) { observed = value; stateClearVersion = authority.clearVersion }
    fun snapshot(): LocalSubscriptionSnapshot = synchronized(lock) { state }
    /** Selection invalidation preserves the user's encrypted subscription and delivery receipt. */
    fun invalidateNodeSelection() = synchronized(lock) {
        revision++; invalidateSelections(); authority.selectionRequiresRefresh = true; Unit
    }
    fun nodeSelectionSnapshot(): NodeSelectionSnapshot = synchronized(lock) {
        val observed = authority.selectionStatus ?: state.status
        val until = if (authority.selectionStatus != null) authority.selectionUntil else state.validUntil
        val status = if (observed == LocalSubscriptionStatus.READY && until?.let { it <= now() } == true)
            LocalSubscriptionStatus.EXPIRED else observed
        if (status != LocalSubscriptionStatus.READY) {
            if (status != LocalSubscriptionStatus.MISSING) invalidateSelections(status, until)
            return@synchronized NodeSelectionSnapshot(status, validUntil = until)
        }
        val binding = authority.selectionBinding
            ?: return@synchronized NodeSelectionSnapshot(status, validUntil = until)
        val current = try { sameCapability(binding) }
            catch (failure: PortalFailure) {
                val failed = if (failure.reason == PortalFailure.Reason.STORAGE)
                    LocalSubscriptionStatus.STORAGE_ERROR else LocalSubscriptionStatus.INVALID_LINK
                revision++; invalidateSelections(failed, until)
                state = state.copy(status = failed)
                return@synchronized NodeSelectionSnapshot(failed, validUntil = until)
            }
            catch (_: Exception) { false }
        if (!current) {
            revision++; invalidateSelections(LocalSubscriptionStatus.INVALID_LINK, until)
            state = state.copy(status = LocalSubscriptionStatus.INVALID_LINK)
            return@synchronized NodeSelectionSnapshot(LocalSubscriptionStatus.INVALID_LINK, validUntil = state.validUntil)
        }
        NodeSelectionSnapshot(status, authority.choices.toList(), authority.selected, until)
    }
    /** Object identity from the current validated registry only. This does not perform HTTP or prepare. */
    fun selectNode(selection: ConnectionSelection): Boolean = synchronized(lock) {
        val current = nodeSelectionSnapshot()
        if (current.status != LocalSubscriptionStatus.READY || current.choices.none { it.selection === selection }) return@synchronized false
        authority.selected = selection
        true
    }
    private fun capability(): Secret? {
        val cached = profiles.read()
        try { cached?.url?.let { validateCapability(it, cached.binding); return it } }
        finally { cached?.close() }
        return delivery.read()?.takeIf { !it.pendingAck }?.url?.also { validateCapability(it) }
    }
    private fun validateCapability(url: Secret, binding: String? = null) {
        try {
            policy.subscription(url.value)
            require(binding == null || sessionBinding(url) == binding)
        } catch (_: Exception) { throw PortalFailure(PortalFailure.Reason.SUBSCRIPTION_REVOKED) }
    }
    private fun sameCapability(binding: String) = capability()?.let(::sessionBinding) == binding
    private fun observeContent(raw: ByteArray, binding: String, nodes: List<SafeProxyConfig.NodeSummary>, until: Instant?) {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw)
        if (authority.selectionBinding != binding || authority.selectionClearVersion != authority.clearVersion ||
            authority.selectionSchema != SafeProxyConfig.SCHEMA_VERSION ||
            authority.selectionRevision?.contentEquals(digest) != true) {
            authority.selectionRevision?.fill(0)
            authority.selectionRevision = digest
            authority.selectionBinding = binding
            authority.selectionSchema = SafeProxyConfig.SCHEMA_VERSION
            authority.selectionClearVersion = authority.clearVersion
            authority.choices = nodes.map { ConnectionChoice(ConnectionSelection(), it.name, it.protocol) }
            authority.selected = null
        } else digest.fill(0)
        authority.selectionStatus = LocalSubscriptionStatus.READY
        authority.selectionUntil = until
        authority.selectionRequiresRefresh = false
    }
    private fun invalidateSelections(status: LocalSubscriptionStatus? = null, until: Instant? = null) {
        authority.selectionRevision?.fill(0); authority.selectionRevision = null
        authority.selectionBinding = null; authority.choices = emptyList()
        authority.selected = null
        authority.selectionStatus = status; authority.selectionUntil = until
        if (status != null) authority.selectionRequiresRefresh = true
    }
    private fun requireCurrent(epoch: Long, binding: String, until: Instant?) {
        if (revision != epoch) throw PreparationRejected(PreparationFailure.STALE)
        val same = try { sameCapability(binding) }
        catch (failure: PortalFailure) { throw PreparationRejected(
            if (failure.reason == PortalFailure.Reason.STORAGE) PreparationFailure.STORAGE else PreparationFailure.INVALID_LINK) }
        catch (_: IllegalArgumentException) { throw PreparationRejected(PreparationFailure.INVALID_LINK) }
        if (!same) throw PreparationRejected(PreparationFailure.STALE)
        if (until != null && until <= now()) throw PreparationRejected(PreparationFailure.EXPIRED)
    }
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
        revision++ // A newly observed artifact fences old admission even if durable save fails.
        val cached = CachedSubscription(binding, raw, until, url)
        try { profiles.save(cached) } catch (_: Exception) {
            invalidateSelections(LocalSubscriptionStatus.STORAGE_ERROR, until)
            state = next.copy(status = LocalSubscriptionStatus.STORAGE_ERROR)
            throw PortalFailure(PortalFailure.Reason.STORAGE)
        } finally { cached.close() }
        if (next.status == LocalSubscriptionStatus.READY) observeContent(raw, binding, next.nodes, until)
        else invalidateSelections(next.status, until)
        state = next; next
    }
    fun unlink() {
        var observer: (() -> Unit)? = null
        try { synchronized(lock) {
            revision++; authority.clearVersion++; invalidateSelections()
            observer = authority.clearObserver
            state = LocalSubscriptionSnapshot(LocalSubscriptionStatus.MISSING)
            try { profiles.clear() } finally { delivery.clear() }
        } } finally { observer?.invoke() } // Never call the owner while holding the capability lock.
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
        val epoch = synchronized(lock) { ++revision }
        var knownCapability = false
        try {
            val url = capability() ?: return@withLock synchronized(lock) {
                if (revision == epoch) {
                    invalidateSelections(LocalSubscriptionStatus.MISSING)
                    state = LocalSubscriptionSnapshot(LocalSubscriptionStatus.MISSING)
                }
                state
            }
            knownCapability = true
            val binding = sessionBinding(url)
            val cached = profiles.read()
            if (cached != null) try {
                if (cached.binding == binding) {
                    val raw = cached.copyBytes()
                    try { return@withLock synchronized(lock) {
                        if (revision == epoch) {
                            check(sameCapability(binding))
                            val blocked = authority.selectionStatus?.takeIf { it != LocalSubscriptionStatus.READY }
                            state = describe(raw, cached.validUntil).let { parsed ->
                                (blocked ?: cached.observedStatus)?.let { parsed.copy(status = it) } ?: parsed
                            }
                            if (state.status == LocalSubscriptionStatus.READY && !authority.selectionRequiresRefresh)
                                observeContent(raw, binding, state.nodes, cached.validUntil)
                            else invalidateSelections(state.status, cached.validUntil)
                        }
                        state
                    } }
                    finally { raw.fill(0) }
                }
            } finally { cached.close() }
            // Upgrade from a durable acknowledged receipt: no Portal login or setup/claim call.
            synchronized(lock) { if (revision != epoch) return@withLock state }
            refreshLocked(null)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { synchronized(lock) {
            if (revision == epoch) {
                invalidateSelections(LocalSubscriptionStatus.STORAGE_ERROR, state.validUntil)
                state = LocalSubscriptionSnapshot(LocalSubscriptionStatus.STORAGE_ERROR, hasCapability = knownCapability)
            }
            state
        } }
    }
    suspend fun refresh(): LocalSubscriptionSnapshot = mutex.withLock { refreshLocked(null) }
    suspend fun prepare(requested: String?, resolver: LocalConnectionLineResolver, files: PreparedConfigStore): AuthorizedConfig? {
        var prepared: AuthorizedConfig? = null
        try {
            mutex.withLock { refreshLocked { snapshot, raw, binding, epoch ->
                if (snapshot.status != LocalSubscriptionStatus.READY) return@refreshLocked
                val selected = resolver.resolve(snapshot, requested) ?: return@refreshLocked
                require(requested == null || requested == selected.lineId)
                val bytes = SafeProxyConfig.project(raw, selected.index)
                try {
                    var consumed = false
                    prepared = AuthorizedConfig(selected.lineId, bytes, snapshot.validUntil) { action -> synchronized(lock) {
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
    /** No cached activation, no implicit first entry, and no cross-revision name matching. */
    suspend fun prepare(intent: SelectionIntent, files: PreparedConfigStore): ConfigPreparation {
        var result: ConfigPreparation? = null
        var material: AuthorizedConfig? = null
        try {
            mutex.withLock {
                val snapshot = refreshLocked { fresh, raw, binding, epoch ->
                    if (fresh.status != LocalSubscriptionStatus.READY) return@refreshLocked
                    val selected = (intent as? SelectionIntent.Selected)?.selection
                    val index = authority.choices.indexOfFirst { it.selection === selected }
                    if (index < 0) {
                        result = ConfigPreparation.SelectionRequired(authority.choices.toList()); return@refreshLocked
                    }
                    val bytes = SafeProxyConfig.project(raw, index)
                    try {
                        var consumed = false
                        material = AuthorizedConfig("local-selection", bytes, fresh.validUntil) { action -> synchronized(lock) {
                            check(!consumed); consumed = true
                            requireCurrent(epoch, binding, fresh.validUntil)
                            val copy = requireNotNull(material).copyMaterial()
                            try { files.replace(copy) } catch (_: Exception) {
                                throw PreparationRejected(PreparationFailure.STORAGE)
                            } finally { copy.fill(0) }
                            requireCurrent(epoch, binding, fresh.validUntil)
                            action()
                        } }
                        result = ConfigPreparation.Ready(requireNotNull(material))
                    } finally { bytes.fill(0) }
                }
                if (result == null) result = ConfigPreparation.Unavailable(when (snapshot.status) {
                    LocalSubscriptionStatus.MISSING -> PreparationFailure.MISSING
                    LocalSubscriptionStatus.EXPIRED -> PreparationFailure.EXPIRED
                    LocalSubscriptionStatus.INVALID_LINK -> PreparationFailure.INVALID_LINK
                    LocalSubscriptionStatus.UNSUPPORTED -> PreparationFailure.UNSUPPORTED
                    LocalSubscriptionStatus.STORAGE_ERROR -> PreparationFailure.STORAGE
                    else -> PreparationFailure.NETWORK
                })
            }
            currentCoroutineContext().ensureActive()
            return requireNotNull(result)
        } catch (failure: Throwable) { material?.close(); throw failure }
    }
    private suspend fun refreshLocked(
        prepared: ((LocalSubscriptionSnapshot, ByteArray, String, Long) -> Unit)?): LocalSubscriptionSnapshot {
        val epoch = synchronized(lock) { ++revision }
        var knownCapability = false
        try {
            val url = capability() ?: return synchronized(lock) {
                if (revision != epoch) throw CancellationException("Subscription operation cancelled")
                invalidateSelections(LocalSubscriptionStatus.MISSING)
                state = LocalSubscriptionSnapshot(LocalSubscriptionStatus.MISSING); state
            }
            knownCapability = true
            val binding = sessionBinding(url)
            // Bind durable known expiry to this capability, independent of restore/UI lifetime.
            val metadata = profiles.read()
            val knownUntil = try { metadata?.takeIf { it.binding == binding }?.validUntil }
                finally { metadata?.close() }
            val result = source.read(url)
            try {
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    check(revision == epoch && sameCapability(binding)) { "Subscription changed" }
                    val until = result.validUntil ?: knownUntil
                    val next = try { describe(result.bytes, until) } catch (_: UnsafeProxyConfiguration) {
                        invalidateSelections(LocalSubscriptionStatus.UNSUPPORTED, until)
                        val nodes = try { SafeProxyConfig.inspect(result.bytes) } catch (_: Exception) { emptyList() }
                        state = state.copy(status = LocalSubscriptionStatus.UNSUPPORTED, hasCapability = true, nodes = nodes)
                        return state
                    }
                    val cached = CachedSubscription(binding, result.bytes, until, url)
                    try { profiles.save(cached) } finally { cached.close() }
                    if (next.status == LocalSubscriptionStatus.READY) observeContent(result.bytes, binding, next.nodes, until)
                    else invalidateSelections(next.status, until)
                    state = next
                    prepared?.invoke(next, result.bytes, binding, epoch)
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
                // Do not repeat a failed capability/storage read while handling that failure.
                // Persistence is best-effort evidence; failure remains an explicit storage block.
                val observed = if (knownCapability) try { rememberObservation(kind); kind }
                    catch (_: Exception) { LocalSubscriptionStatus.STORAGE_ERROR } else kind
                invalidateSelections(observed, state.validUntil)
                state = state.copy(status = observed, hasCapability = knownCapability)
                state
            }
        }
    }
}
/** Production service/line resolver remains separately gated; no cached load/start path exists. */
class FreshLocalSubscriptionGateway(private val manager: LocalSubscriptionManager,
    private val resolver: LocalConnectionLineResolver, private val files: PreparedConfigStore) : ConfigGateway {
    override suspend fun forLine(lineId: String?): AuthorizedConfig? = manager.prepare(lineId, resolver, files)
}

/** Local-only admission. Construction makes no account, device-limit or reset requests. */
class LocalSubscriptionConfigGateway(private val manager: LocalSubscriptionManager,
    private val files: PreparedConfigStore) : LocalConfigGateway {
    override suspend fun prepare(intent: SelectionIntent): ConfigPreparation = manager.prepare(intent, files)
    override fun invalidatePending() = manager.invalidatePending()
    override fun clearPreparedMaterial() = files.clear()
    override fun observeClear(onClear: () -> Unit): Closeable = manager.observeClear(onClear)
}
