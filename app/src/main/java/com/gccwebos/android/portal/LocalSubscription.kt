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
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
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
/** Local files carry no claim about server subscription expiry or account rights. */
sealed interface LocalSourceValidity {
    data class RemoteExpiry(val until: Instant?) : LocalSourceValidity
    data object LocalNoServerExpiry : LocalSourceValidity
    data class ExternalExpiry(val until: Instant) : LocalSourceValidity
    data object ExternalUnreported : LocalSourceValidity
}
data class LocalSubscriptionSnapshot(val status: LocalSubscriptionStatus, val hasProfile: Boolean = false,
    val hasCapability: Boolean = false, val nodes: List<SafeProxyConfig.NodeSummary> = emptyList(), val validUntil: Instant? = null,
    val sourceKind: ManualSourceKind = ManualSourceKind.LegacyRemote,
    val validity: LocalSourceValidity = LocalSourceValidity.RemoteExpiry(validUntil))
/** Credential-free, process-local UI selection; never connection admission or a persisted ID. */
data class NodeSelectionSnapshot(val status: LocalSubscriptionStatus,
    val choices: List<ConnectionChoice> = emptyList(), val selected: ConnectionSelection? = null,
    val validUntil: Instant? = null,
    val sourceKind: ManualSourceKind = ManualSourceKind.LegacyRemote,
    val validity: LocalSourceValidity = LocalSourceValidity.RemoteExpiry(validUntil))
/** One local observation for compare-and-set selection, never config or tunnel admission. */
internal data class NodeSelectionAttempt(val snapshot: NodeSelectionSnapshot, val epoch: Long)
/** Opaque UI operation fence; contains no configuration, URL or account material. */
class ManualSourceVersion internal constructor(internal val revision: String?, internal val clearVersion: Long) {
    override fun toString() = "ManualSourceVersion([OPAQUE])"
}
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
    internal var selectionSourceKind = ManualSourceKind.LegacyRemote
    internal var selectionSourceRevision: String? = null
    internal var manualSourceInitialized = false
    internal var manualStorageBlocked = false
    internal var incompleteClear = false
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
    private val authority: LocalSubscriptionAuthority = LocalSubscriptionAuthority.process,
    private val allowHostnamePreparation: Boolean = false,
    private val manualSources: ManualSourceStore? = null,
    private val externalSource: SubscriptionSource? = null) {
    private val mutex get() = authority.mutex
    private val lock get() = authority.lock
    private var revision: Long
        get() = authority.epoch
        set(value) { authority.epoch = value }
    fun invalidatePending() = synchronized(lock) { revision++; Unit }
    internal fun captureClearVersion(): Long = synchronized(lock) { authority.clearVersion }
    internal fun <T> withClearVersion(version: Long, action: () -> T): T = synchronized(lock) {
        if (authority.clearVersion != version) throw CancellationException("Local subscription cleared")
        action()
    }
    fun observeClear(observer: () -> Unit): Closeable = authority.observeClear(observer)
    internal suspend fun <T> awaitConnectionOwner(factory: () -> T): T = authority.awaitConnectionOwner(factory)
    private var stateClearVersion = authority.clearVersion
    private var observed = LocalSubscriptionSnapshot(LocalSubscriptionStatus.MISSING)
    private var state: LocalSubscriptionSnapshot
        get() = if (manualSources != null && (authority.incompleteClear || authority.manualStorageBlocked))
            observed.copy(status = LocalSubscriptionStatus.STORAGE_ERROR)
            else if (stateClearVersion == authority.clearVersion) observed
            else LocalSubscriptionSnapshot(LocalSubscriptionStatus.MISSING)
        set(value) { observed = value; stateClearVersion = authority.clearVersion }
    fun snapshot(): LocalSubscriptionSnapshot = synchronized(lock) { state }
    /** Called under the shared authority lock. Never initialize after a failed/live missing read. */
    private fun readManualSource(allowBlocked: Boolean = false): ManualSourceRecord? = synchronized(lock) {
        val store = manualSources ?: return null
        if ((!allowBlocked && authority.manualStorageBlocked) || authority.incompleteClear)
            throw PortalFailure(PortalFailure.Reason.STORAGE)
        try {
            var record = store.read()
            if (record == null) {
                if (authority.manualSourceInitialized) throw PortalFailure(PortalFailure.Reason.STORAGE)
                authority.manualSourceInitialized = true // A failed first write cannot authorize a retry fallback.
                val initial = ManualSourceRecord(kind = ManualSourceKind.LegacyRemote)
                try {
                    store.save(initial)
                    record = store.read() ?: throw PortalFailure(PortalFailure.Reason.STORAGE)
                    if (!initial.sameEnvelope(record)) {
                        record.close(); throw PortalFailure(PortalFailure.Reason.STORAGE)
                    }
                } finally { initial.close() }
            }
            authority.manualSourceInitialized = true
            if (record.clearPending) {
                record.close()
                authority.incompleteClear = true
                throw PortalFailure(PortalFailure.Reason.STORAGE)
            }
            try {
                if (record.kind == ManualSourceKind.ImportedRemote) validateCapability(requireNotNull(record.url))
                if (record.kind == ManualSourceKind.ExternalRemote) ManualSubscriptionUrlPolicy.validate(requireNotNull(record.url).value)
            }
            catch (failure: Exception) { record.close(); throw failure }
            return record
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) {
            authority.manualSourceInitialized = true // A failed read is never evidence of a never-created store.
            authority.manualStorageBlocked = true
            throw PortalFailure(PortalFailure.Reason.STORAGE)
        }
    }
    fun sourceKind(): ManualSourceKind = synchronized(lock) {
        readManualSource()?.use { it.kind } ?: ManualSourceKind.LegacyRemote
    }
    fun captureManualSourceVersion(): ManualSourceVersion = synchronized(lock) {
        val source = readManualSource()
        try { ManualSourceVersion(source?.revision, authority.clearVersion) } finally { source?.close() }
    }
    fun isManualSourceVersionCurrent(version: ManualSourceVersion): Boolean = synchronized(lock) {
        try { val current = readManualSource(); try {
            version.clearVersion == authority.clearVersion && version.revision == current?.revision
        } finally { current?.close() } } catch (_: Exception) { false }
    }
    internal suspend fun <T> withLegacySource(action: suspend () -> T): T = mutex.withLock {
        synchronized(lock) { requireLegacySource() }
        action()
    }
    internal fun <T> whenLegacySource(action: () -> T): T? = synchronized(lock) {
        val selected = readManualSource()
        try {
            if (selected != null && selected.kind != ManualSourceKind.LegacyRemote) null else action()
        } finally { selected?.close() }
    }
    private fun requireLegacySource() {
        val selected = readManualSource()
        try { if (selected != null && selected.kind != ManualSourceKind.LegacyRemote)
            throw PortalFailure(PortalFailure.Reason.RECONCILIATION)
        } finally { selected?.close() }
    }
    private fun sourceBinding(record: ManualSourceRecord): String = when (record.kind) {
        ManualSourceKind.ImportedLocal -> "local:${record.revision}:${record.contentIdentity}"
        ManualSourceKind.ImportedRemote -> "remote:${record.revision}:${sessionBinding(requireNotNull(record.url))}"
        ManualSourceKind.ExternalRemote -> "external:${record.revision}:${sessionBinding(requireNotNull(record.url))}"
        else -> throw PortalFailure(PortalFailure.Reason.STORAGE)
    }
    private fun manualSnapshot(raw: ByteArray, record: ManualSourceRecord): LocalSubscriptionSnapshot {
        val parsed = describe(raw, record.validUntil)
        return parsed.copy(sourceKind = record.kind, validity = sourceValidity(record.kind, record.validUntil))
    }
    private fun sourceValidity(kind: ManualSourceKind, until: Instant?): LocalSourceValidity = when (kind) {
        ManualSourceKind.ImportedLocal -> LocalSourceValidity.LocalNoServerExpiry
        ManualSourceKind.ExternalRemote -> until?.let { LocalSourceValidity.ExternalExpiry(it) } ?: LocalSourceValidity.ExternalUnreported
        else -> LocalSourceValidity.RemoteExpiry(until)
    }
    private fun sourceTimeValid(kind: ManualSourceKind, until: Instant?): Boolean = when (kind) {
        ManualSourceKind.ImportedLocal -> until == null
        ManualSourceKind.ExternalRemote -> until == null || until > now()
        else -> until?.let { it > now() } == true
    }
    /** Save/readback occurs as one authority transaction, with no profile-v1 or delivery write. */
    private fun writeManualSource(record: ManualSourceRecord) {
        val store = manualSources ?: throw PortalFailure(PortalFailure.Reason.STORAGE)
        try {
            store.save(record)
            val readback = store.read() ?: throw PortalFailure(PortalFailure.Reason.STORAGE)
            try { if (!record.sameEnvelope(readback)) throw PortalFailure(PortalFailure.Reason.STORAGE) }
            finally { readback.close() }
        } catch (failure: Exception) {
            authority.manualStorageBlocked = true
            invalidateSelections(LocalSubscriptionStatus.STORAGE_ERROR)
            state = state.copy(status = LocalSubscriptionStatus.STORAGE_ERROR)
            if (failure is CancellationException) throw failure
            throw PortalFailure(PortalFailure.Reason.STORAGE)
        }
    }
    private data class SourceCommitTicket(val epoch: Long, val clearVersion: Long, val sourceRevision: String)
    private fun captureSourceCommit(expected: ManualSourceVersion?): Pair<SourceCommitTicket, ManualSourceRecord> = synchronized(lock) {
        val old = readManualSource(allowBlocked = true) ?: throw PortalFailure(PortalFailure.Reason.STORAGE)
        if (expected != null && (expected.clearVersion != authority.clearVersion || expected.revision != old.revision)) {
            old.close(); throw CancellationException("Configuration source changed")
        }
        SourceCommitTicket(++revision, authority.clearVersion, old.revision) to old
    }
    private suspend fun commitSource(record: ManualSourceRecord, staged: LocalSubscriptionSnapshot?, ticket: SourceCommitTicket,
        commitFence: ((() -> Unit) -> Unit)) {
        // Lock order is connection command lock -> authority lock. Never call the owner while holding lock.
        var committed = false
        val context = currentCoroutineContext()
        context.ensureActive()
        commitFence {
            synchronized(lock) {
                context.ensureActive()
                if (committed || revision != ticket.epoch || authority.clearVersion != ticket.clearVersion)
                    throw CancellationException("Configuration import cancelled")
                val current = readManualSource(allowBlocked = true)
                try { if (current?.revision != ticket.sourceRevision) throw CancellationException("Configuration source changed") }
                finally { current?.close() }
                revision++ // Persistence/readback failure also revokes every older admission.
                invalidateSelections(LocalSubscriptionStatus.STORAGE_ERROR)
                writeManualSource(record)
                authority.manualStorageBlocked = false
                authority.selectionSourceKind = record.kind
                authority.selectionSourceRevision = record.revision
                val next = staged ?: LocalSubscriptionSnapshot(LocalSubscriptionStatus.MISSING, sourceKind = record.kind)
                val bytes = record.copyBytes()
                try {
                    if (next.status == LocalSubscriptionStatus.READY && bytes != null)
                        observeContent(bytes, sourceBinding(record), next.nodes, next.validUntil, record.kind, record.revision)
                    else invalidateSelections(next.status, next.validUntil)
                    state = next
                    committed = true
                } finally { bytes?.fill(0) }
            }
        }
        if (!committed) throw CancellationException("Configuration import cancelled")
    }
    /** Explicit import stages all bytes before taking the connection-owner commit fence. Never starts a VPN. */
    suspend fun importManual(input: ByteArray, expectedSource: ManualSourceVersion? = null,
        commitFence: ((() -> Unit) -> Unit)): LocalSubscriptionSnapshot = mutex.withLock {
        val owned = input.copyOf()
        var old: ManualSourceRecord? = null
        var staged: ManualSourceRecord? = null
        try {
            require(owned.size in 1..SafeProxyConfig.MAX_BYTES)
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(owned)).toString().trim()
            require(text.isNotEmpty() && '\u0000' !in text)
            val (ticket, previous) = captureSourceCommit(expectedSource)
            old = previous
            val next: LocalSubscriptionSnapshot
            if (Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(text)) {
                val official = try { policy.subscription(text) } catch (_: Exception) { null }
                val kind = if (official != null) ManualSourceKind.ImportedRemote else ManualSourceKind.ExternalRemote
                val url = Secret(official ?: try { ManualSubscriptionUrlPolicy.validate(text) }
                    catch (_: Exception) { throw UnsupportedManualSubscriptionLink() })
                val reader = if (kind == ManualSourceKind.ImportedRemote) source else externalSource
                    ?: throw UnsupportedManualSubscriptionLink()
                val result = reader.read(url)
                try {
                    currentCoroutineContext().ensureActive()
                    val known = if (previous.kind == kind && previous.url?.value == url.value &&
                        (kind == ManualSourceKind.ExternalRemote || previous.observedStatus == null))
                        previous.validUntil else if (kind == ManualSourceKind.ImportedRemote && previous.kind == ManualSourceKind.LegacyRemote) {
                            if (delivery.readLegacyRecovery() != null) null else {
                                val profile = profiles.read()
                                try { profile?.takeIf { it.url?.value == url.value && it.binding == sessionBinding(url) &&
                                    it.observedStatus == null }?.validUntil }
                                finally { profile?.close() }
                            }
                        } else null
                    val until = result.validUntil ?: known
                    if (until == null && kind == ManualSourceKind.ImportedRemote) throw PortalFailure(PortalFailure.Reason.SUBSCRIPTION_UNAVAILABLE)
                    if (until != null && until <= now()) throw PortalFailure(PortalFailure.Reason.SUBSCRIPTION_EXPIRED)
                    staged = ManualSourceRecord(kind = kind, bytes = result.bytes, url = url,
                        validUntil = until)
                    next = manualSnapshot(result.bytes, requireNotNull(staged))
                } finally { result.bytes.fill(0) }
            } else {
                staged = ManualSourceRecord(kind = ManualSourceKind.ImportedLocal, bytes = owned)
                next = manualSnapshot(owned, requireNotNull(staged))
            }
            currentCoroutineContext().ensureActive()
            commitSource(requireNotNull(staged), next, ticket, commitFence)
            next
        } finally { owned.fill(0); staged?.close(); old?.close() }
    }
    suspend fun useAccountSource(commitFence: ((() -> Unit) -> Unit)): LocalSubscriptionSnapshot =
        replaceSourceSelector(ManualSourceKind.LegacyRemote, commitFence)
    suspend fun removeManualSource(commitFence: ((() -> Unit) -> Unit)): LocalSubscriptionSnapshot =
        replaceSourceSelector(ManualSourceKind.None, commitFence)
    private suspend fun replaceSourceSelector(kind: ManualSourceKind, commitFence: ((() -> Unit) -> Unit)) = mutex.withLock {
        val (ticket, previous) = captureSourceCommit(null)
        try {
            currentCoroutineContext().ensureActive()
            ManualSourceRecord(kind = kind).use { commitSource(it, null, ticket, commitFence) }
            state
        } finally { previous.close() }
    }
    /** Selection invalidation preserves the user's encrypted subscription and delivery receipt. */
    fun invalidateNodeSelection() = synchronized(lock) {
        revision++; invalidateSelections(); authority.selectionRequiresRefresh = true; Unit
    }
    fun nodeSelectionSnapshot(): NodeSelectionSnapshot = synchronized(lock) {
        fun result(status: LocalSubscriptionStatus, choices: List<ConnectionChoice> = emptyList(),
            selected: ConnectionSelection? = null, until: Instant? = null) = NodeSelectionSnapshot(status, choices,
            selected, until, authority.selectionSourceKind, sourceValidity(authority.selectionSourceKind, until))
        val observed = authority.selectionStatus ?: state.status
        val until = if (authority.selectionStatus != null) authority.selectionUntil else state.validUntil
        val status = if (observed == LocalSubscriptionStatus.READY && until?.let { it <= now() } == true)
            LocalSubscriptionStatus.EXPIRED else observed
        if (status != LocalSubscriptionStatus.READY) {
            if (status != LocalSubscriptionStatus.MISSING) invalidateSelections(status, until)
            return@synchronized result(status, until = until)
        }
        val binding = authority.selectionBinding
            ?: return@synchronized result(status, until = until)
        val current = try { sameCapability(binding) }
            catch (failure: PortalFailure) {
                val failed = if (failure.reason == PortalFailure.Reason.STORAGE)
                    LocalSubscriptionStatus.STORAGE_ERROR else LocalSubscriptionStatus.INVALID_LINK
                revision++; invalidateSelections(failed, until)
                state = state.copy(status = failed)
                return@synchronized result(failed, until = until)
            }
            catch (_: Exception) { false }
        if (!current) {
            revision++; invalidateSelections(LocalSubscriptionStatus.INVALID_LINK, until)
            state = state.copy(status = LocalSubscriptionStatus.INVALID_LINK)
            return@synchronized result(LocalSubscriptionStatus.INVALID_LINK, until = state.validUntil)
        }
        result(status, authority.choices.toList(), authority.selected, until)
    }
    /** Object identity from the current validated registry only. This does not perform HTTP or prepare. */
    fun selectNode(selection: ConnectionSelection): Boolean = synchronized(lock) {
        val current = nodeSelectionSnapshot()
        if (current.status != LocalSubscriptionStatus.READY || current.choices.none { it.selection === selection }) return@synchronized false
        if (authority.selected !== selection) revision++
        authority.selected = selection
        true
    }
    internal fun nodeSelectionAttempt(): NodeSelectionAttempt = synchronized(lock) {
        NodeSelectionAttempt(nodeSelectionSnapshot(), revision)
    }
    /** A random suggestion can fill an unchanged empty selection, never replace user intent. */
    internal fun selectNodeIfUnchanged(selection: ConnectionSelection, expected: NodeSelectionAttempt): Long? = synchronized(lock) {
        if (revision != expected.epoch || expected.snapshot.selected != null) return@synchronized null
        val current = nodeSelectionSnapshot()
        if (revision != expected.epoch || current.status != LocalSubscriptionStatus.READY || current.selected != null ||
            current.choices != expected.snapshot.choices || current.choices.none { it.selection === selection }) return@synchronized null
        authority.selected = selection
        ++revision
    }
    /** One cache read per batch; no refresh, selection, epoch change or connection admission. */
    internal fun captureNodeProbeTargets(): List<NodeProbeTarget> = synchronized(lock) {
        withNodeProbeArtifact { raw, choices ->
            choices.mapIndexed { index, choice ->
                NodeProbeTarget(choice.selection, SafeProxyConfig.selectedEndpoint(raw, index), choice.protocol)
            }
        } ?: emptyList()
    }
    /** Recheck results before publication; a target never renews a removed or changed registry. */
    internal fun isCurrentNodeProbeTarget(target: NodeProbeTarget): Boolean = synchronized(lock) {
        if (authority.choices.none { it.selection === target.selection }) return@synchronized false
        withNodeProbeArtifact { raw, choices ->
            val index = choices.indexOfFirst { it.selection === target.selection }
            index >= 0 && choices[index].protocol == target.protocol &&
                SafeProxyConfig.selectedEndpoint(raw, index) == target.endpoint
        } == true
    }
    /** Memory-only display masking. Never sufficient permission to probe: workers must also
     * call isCurrentNodeProbeTarget on IO before/after measuring, plus their auth/network/TTL guards. */
    internal fun isNodeProbeTargetRegistryCurrent(target: NodeProbeTarget): Boolean = synchronized(lock) {
        authority.selectionStatus == LocalSubscriptionStatus.READY && !authority.selectionRequiresRefresh &&
            !authority.manualStorageBlocked && !authority.incompleteClear &&
            authority.selectionSchema == SafeProxyConfig.SCHEMA_VERSION &&
            authority.selectionClearVersion == authority.clearVersion && authority.selectionBinding != null &&
            authority.selectionRevision != null &&
            sourceTimeValid(authority.selectionSourceKind, authority.selectionUntil) &&
            authority.choices.any { it.selection === target.selection && it.protocol == target.protocol }
    }
    /** Caller holds the authority lock. Unlike nodeSelectionSnapshot, rejection is read-only. */
    private fun <T> withNodeProbeArtifact(read: (ByteArray, List<ConnectionChoice>) -> T): T? {
        var cached: CachedSubscription? = null
        var raw: ByteArray? = null
        try {
            if (authority.selectionStatus != LocalSubscriptionStatus.READY || authority.selectionRequiresRefresh ||
                authority.selectionSchema != SafeProxyConfig.SCHEMA_VERSION ||
                authority.selectionClearVersion != authority.clearVersion || authority.choices.isEmpty()) return null
            val selectedSource = readManualSource()
            if (selectedSource != null) try {
                if (selectedSource.kind != ManualSourceKind.LegacyRemote)
                    return withManualNodeProbeArtifact(selectedSource, read)
                if (selectedSource.revision != authority.selectionSourceRevision) return null
            } finally { selectedSource.close() }
            val until = authority.selectionUntil ?: return null
            if (until <= now()) return null
            val binding = authority.selectionBinding ?: return null
            val fingerprint = authority.selectionRevision ?: return null
            val choices = authority.choices.toList()
            if (delivery.readLegacyRecovery() != null) return null
            val profile = profiles.read() ?: return null
            cached = profile
            if (profile.binding != binding || profile.validUntil != until ||
                profile.observedStatus?.let { it != LocalSubscriptionStatus.READY } == true) return null
            // Match capability() precedence without reading the same profile for each node.
            val url = profile.url ?: delivery.read()?.takeIf { !it.pendingAck }?.url ?: return null
            validateCapability(url, profile.binding)
            val bytes = profile.copyBytes()
            raw = bytes
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            try { if (!fingerprint.contentEquals(digest)) return null } finally { digest.fill(0) }
            val validated = describe(bytes, profile.validUntil)
            if (validated.status != LocalSubscriptionStatus.READY || validated.nodes.size != choices.size ||
                validated.nodes.indices.any { validated.nodes[it].name != choices[it].name ||
                    validated.nodes[it].protocol != choices[it].protocol }) return null
            if (authority.selectionStatus != LocalSubscriptionStatus.READY || authority.selectionRequiresRefresh ||
                authority.selectionSchema != SafeProxyConfig.SCHEMA_VERSION ||
                authority.selectionRevision !== fingerprint || authority.selectionBinding != binding ||
                authority.selectionClearVersion != authority.clearVersion || authority.selectionUntil != until ||
                authority.choices != choices || until <= now()) return null
            return read(bytes, choices)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { return null }
        finally { raw?.fill(0); cached?.close() }
    }
    private fun <T> withManualNodeProbeArtifact(record: ManualSourceRecord,
        read: (ByteArray, List<ConnectionChoice>) -> T): T? {
        if (record.kind !in setOf(ManualSourceKind.ImportedLocal, ManualSourceKind.ImportedRemote, ManualSourceKind.ExternalRemote) ||
            record.revision != authority.selectionSourceRevision || record.kind != authority.selectionSourceKind ||
            sourceBinding(record) != authority.selectionBinding || record.validUntil != authority.selectionUntil ||
            record.observedStatus?.let { it != LocalSubscriptionStatus.READY } == true ||
            !sourceTimeValid(record.kind, record.validUntil)) return null
        val bytes = record.copyBytes() ?: return null
        try {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            try { if (authority.selectionRevision?.contentEquals(digest) != true) return null } finally { digest.fill(0) }
            val parsed = manualSnapshot(bytes, record)
            val choices = authority.choices.toList()
            if (parsed.status != LocalSubscriptionStatus.READY || parsed.nodes.size != choices.size ||
                parsed.nodes.indices.any { parsed.nodes[it].name != choices[it].name || parsed.nodes[it].protocol != choices[it].protocol }) return null
            return read(bytes, choices)
        } finally { bytes.fill(0) }
    }
    private fun capability(): Secret? {
        requireLegacySource()
        // A profile write and a receipt write are separate atomic files. Even when the
        // candidate receipt is present, an uncleared journal requires a fresh authenticated
        // model/resource check and verified commit. URL equality alone is not completion.
        if (delivery.readLegacyRecovery() != null) throw PortalFailure(PortalFailure.Reason.STORAGE)
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
    private fun sameCapability(binding: String): Boolean {
        val selectedSource = readManualSource()
        if (selectedSource != null) try {
            if (selectedSource.kind != ManualSourceKind.LegacyRemote) {
                if (selectedSource.kind == ManualSourceKind.None || sourceBinding(selectedSource) != binding ||
                    authority.selectionBinding != binding || selectedSource.kind != authority.selectionSourceKind ||
                    selectedSource.revision != authority.selectionSourceRevision ||
                    selectedSource.validUntil != authority.selectionUntil || selectedSource.observedStatus != null ||
                    !sourceTimeValid(selectedSource.kind, selectedSource.validUntil)) return false
                run {
                    val bytes = selectedSource.copyBytes() ?: return false
                    try { val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                        try { if (authority.selectionRevision?.contentEquals(digest) != true) return false }
                        finally { digest.fill(0) }
                    } finally { bytes.fill(0) }
                }
                return true
            }
            if (authority.selectionBinding == binding && authority.selectionSourceRevision != selectedSource.revision) return false
        } finally { selectedSource.close() }
        return capability()?.let(::sessionBinding) == binding
    }
    private fun observeContent(raw: ByteArray, binding: String, nodes: List<SafeProxyConfig.NodeSummary>, until: Instant?,
        kind: ManualSourceKind = ManualSourceKind.LegacyRemote, sourceRevision: String? = null) {
        val observedRevision = if (sourceRevision != null) sourceRevision else readManualSource()?.use { it.revision }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw)
        if (authority.selectionBinding != binding || authority.selectionClearVersion != authority.clearVersion ||
            authority.selectionSourceKind != kind || authority.selectionSourceRevision != observedRevision ||
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
        authority.selectionSourceKind = kind
        authority.selectionSourceRevision = observedRevision
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
        val nodes = if (allowHostnamePreparation) SafeProxyConfig.validateForPreparation(raw) else {
            val inspected = SafeProxyConfig.inspect(raw)
            SafeProxyConfig.project(raw, 0).fill(0)
            inspected
        }
        return LocalSubscriptionSnapshot(if (until != null && until <= now()) LocalSubscriptionStatus.EXPIRED else LocalSubscriptionStatus.READY,
            true, true, nodes, until)
    }
    /** Only called after acknowledged delivery and strict projection; never a connection admission. */
    fun remember(raw: ByteArray, url: Secret, until: Instant?): LocalSubscriptionSnapshot = synchronized(lock) {
        requireLegacySource()
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
    /** Repository-only legacy transaction; ordinary capability checks are never relaxed. */
    internal fun rememberRecoveredLegacy(raw: ByteArray, journal: LegacyRecoveryJournal, until: Instant?,
        checkCurrent: () -> Unit): LocalSubscriptionSnapshot = synchronized(lock) {
        requireLegacySource()
        require(journal.phase in setOf(LegacyRecoveryPhase.ACKNOWLEDGED, LegacyRecoveryPhase.PROFILE_COMMITTING))
        val candidate = journal.candidate
        require(!candidate.pendingAck && candidate.url != null && candidate.handoff != null)
        validateCapability(candidate.url)
        val binding = sessionBinding(candidate.url)
        val next = describe(raw, until)
        fun checkJournal(expected: LegacyRecoveryJournal) {
            checkCurrent()
            val actual = delivery.readLegacyRecovery()
            if (actual == null || actual.phase != expected.phase ||
                !sameDeliveryRecord(actual.original, expected.original) || !sameDeliveryRecord(actual.candidate, candidate) ||
                (!sameDeliveryRecord(delivery.read(), expected.original) && !sameDeliveryRecord(delivery.read(), candidate)))
                throw PortalFailure(PortalFailure.Reason.RECOVERY_REQUIRED, PortalFailure.RecoveryCause.JOURNAL_CONFLICT)
        }
        checkJournal(journal)
        revision++
        invalidateSelections(LocalSubscriptionStatus.STORAGE_ERROR, until)
        val committing = LegacyRecoveryJournal(journal.original, candidate, LegacyRecoveryPhase.PROFILE_COMMITTING)
        try {
            delivery.saveLegacyRecovery(committing)
            checkJournal(committing)
            val cached = CachedSubscription(binding, raw, until, candidate.url)
            try { profiles.save(cached) } finally { cached.close() }
            // This readback is required even for an injected store; durable production storage
            // also verifies its own atomic write. Keep the journal if either verification fails.
            val received = profiles.read() ?: throw PortalFailure(PortalFailure.Reason.STORAGE)
            try {
                val bytes = received.copyBytes()
                try {
                    if (received.binding != binding || received.url?.value != candidate.url.value ||
                        received.validUntil != until || received.observedStatus != null || !bytes.contentEquals(raw))
                        throw PortalFailure(PortalFailure.Reason.STORAGE)
                } finally { bytes.fill(0) }
            } finally { received.close() }
            checkJournal(committing)
            // Compare against the preserved original, or the exact already-committed candidate
            // after an interrupted cleanup. Never rebind an unrelated main receipt.
            if (!sameDeliveryRecord(delivery.read(), candidate)) delivery.save(candidate)
            if (!sameDeliveryRecord(delivery.read(), candidate)) throw PortalFailure(PortalFailure.Reason.STORAGE)
            checkJournal(committing)
            delivery.clearLegacyRecovery()
            if (delivery.readLegacyRecovery() != null) throw PortalFailure(PortalFailure.Reason.STORAGE)
            checkCurrent()
            if (next.status == LocalSubscriptionStatus.READY) observeContent(raw, binding, next.nodes, until)
            else invalidateSelections(next.status, until)
            state = next
            next
        } catch (failure: Exception) {
            invalidateSelections(LocalSubscriptionStatus.STORAGE_ERROR, until)
            state = next.copy(status = LocalSubscriptionStatus.STORAGE_ERROR)
            when (failure) {
                is CancellationException -> throw failure
                is PortalFailure -> throw failure
                else -> throw PortalFailure(PortalFailure.Reason.STORAGE)
            }
        }
    }
    private data class ClearTicket(val sourceRevision: String, val clearVersion: Long)
    private fun verifyLegacyAbsent() {
        val profile = profiles.read()
        try { if (profile != null || delivery.read() != null || delivery.readLegacyRecovery() != null)
            throw PortalFailure(PortalFailure.Reason.STORAGE)
        } finally { profile?.close() }
    }
    /** Tombstone and old-store deletion are durable before the owner begins its final cleanup. */
    private fun beginUnlink(): ClearTicket? {
        var observer: (() -> Unit)? = null
        try { return synchronized(lock) {
            revision++; authority.clearVersion++; invalidateSelections()
            observer = authority.clearObserver
            state = LocalSubscriptionSnapshot(LocalSubscriptionStatus.STORAGE_ERROR,
                sourceKind = if (manualSources == null) ManualSourceKind.LegacyRemote else ManualSourceKind.None)
            if (manualSources != null) {
                authority.incompleteClear = true
                authority.manualSourceInitialized = true
                try {
                    ManualSourceRecord(kind = ManualSourceKind.None, clearPending = true).use { tombstone ->
                        writeManualSource(tombstone)
                        authority.selectionSourceKind = ManualSourceKind.None
                        authority.selectionSourceRevision = tombstone.revision
                        try { profiles.clear() } finally { delivery.clear() }
                        verifyLegacyAbsent()
                        val finalSource = manualSources.read() ?: throw PortalFailure(PortalFailure.Reason.STORAGE)
                        try { if (!tombstone.sameEnvelope(finalSource)) throw PortalFailure(PortalFailure.Reason.STORAGE) }
                        finally { finalSource.close() }
                        return@synchronized ClearTicket(tombstone.revision, authority.clearVersion)
                    }
                } catch (_: Exception) {
                    authority.manualStorageBlocked = true
                    invalidateSelections(LocalSubscriptionStatus.STORAGE_ERROR)
                    throw PortalFailure(PortalFailure.Reason.STORAGE)
                }
            } else try { profiles.clear() } finally { delivery.clear() }
            state = LocalSubscriptionSnapshot(LocalSubscriptionStatus.MISSING)
            null
        } } finally { observer?.invoke() } // Never call the owner while holding the capability lock.
    }
    private fun finishUnlink(ticket: ClearTicket, commitFence: ((() -> Unit) -> Unit)) {
        var completed = false
        commitFence { synchronized(lock) {
            if (completed || authority.clearVersion != ticket.clearVersion) throw CancellationException("Local clear superseded")
            val selected = manualSources?.read() ?: throw PortalFailure(PortalFailure.Reason.STORAGE)
            try { if (selected.kind != ManualSourceKind.None || !selected.clearPending || selected.revision != ticket.sourceRevision)
                throw PortalFailure(PortalFailure.Reason.STORAGE)
            } finally { selected.close() }
            verifyLegacyAbsent()
            ManualSourceRecord(kind = ManualSourceKind.None).use { tombstone ->
                writeManualSource(tombstone)
                authority.selectionSourceKind = ManualSourceKind.None
                authority.selectionSourceRevision = tombstone.revision
            }
            authority.incompleteClear = false
            authority.manualStorageBlocked = false
            invalidateSelections(LocalSubscriptionStatus.MISSING)
            state = LocalSubscriptionSnapshot(LocalSubscriptionStatus.MISSING, sourceKind = ManualSourceKind.None)
            completed = true
        } }
        if (!completed) throw PortalFailure(PortalFailure.Reason.STORAGE)
    }
    /** Legacy no-sidecar callers retain their API. A manual source with a live owner must await cleanup. */
    fun unlink() {
        val ticket = beginUnlink() ?: return
        finishUnlink(ticket) { action -> synchronized(lock) {
            if (authority.clearObserver != null) throw PortalFailure(PortalFailure.Reason.STORAGE)
            action()
        } }
    }
    suspend fun unlinkAndAwait(cleanup: suspend () -> Unit, commitFence: ((() -> Unit) -> Unit)) {
        val ticket = beginUnlink()
        cleanup() // No authority lock/mutex is held while the actual owner stops and removes prepared material.
        currentCoroutineContext().ensureActive()
        if (ticket != null) finishUnlink(ticket, commitFence)
    }
    private fun rememberObservation(kind: LocalSubscriptionStatus) {
        if (kind !in setOf(LocalSubscriptionStatus.EXPIRED, LocalSubscriptionStatus.INVALID_LINK)) return
        val selectedSource = readManualSource()
        if (selectedSource != null) try {
            if (selectedSource.kind != ManualSourceKind.LegacyRemote) {
                if (selectedSource.kind in setOf(ManualSourceKind.ImportedRemote, ManualSourceKind.ExternalRemote)) {
                    val bytes = selectedSource.copyBytes() ?: throw PortalFailure(PortalFailure.Reason.STORAGE)
                    try { ManualSourceRecord(selectedSource.revision, selectedSource.kind, bytes, selectedSource.url,
                        selectedSource.validUntil, kind).use(::writeManualSource) } finally { bytes.fill(0) }
                }
                return
            }
        } finally { selectedSource.close() }
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
            val selectedSource = synchronized(lock) { readManualSource() }
            if (selectedSource != null) try {
                if (selectedSource.kind != ManualSourceKind.LegacyRemote) {
                    knownCapability = selectedSource.kind != ManualSourceKind.None
                    return@withLock restoreManualSource(selectedSource, epoch)
                }
            } finally { selectedSource.close() }
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
                            // A readable cached artifact can still be incompatible with this build.
                            // Keep its durable capability/profile; parsing rejection is not a store failure.
                            val parsed = try { describe(raw, cached.validUntil) }
                                catch (_: UnsafeProxyConfiguration) {
                                    val nodes = try { SafeProxyConfig.inspect(raw) } catch (_: Exception) { emptyList() }
                                    LocalSubscriptionSnapshot(LocalSubscriptionStatus.UNSUPPORTED,
                                        hasProfile = true, hasCapability = true, nodes = nodes, validUntil = cached.validUntil)
                                }
                            state = parsed.let { parsed ->
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
            refreshLocked()
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { synchronized(lock) {
            if (revision == epoch) {
                invalidateSelections(LocalSubscriptionStatus.STORAGE_ERROR, state.validUntil)
                state = LocalSubscriptionSnapshot(LocalSubscriptionStatus.STORAGE_ERROR, hasCapability = knownCapability)
            }
            state
        } }
    }
    suspend fun refresh(): LocalSubscriptionSnapshot = mutex.withLock { refreshLocked() }
    private fun requireSameSource(record: ManualSourceRecord, epoch: Long) {
        if (revision != epoch) throw CancellationException("Configuration source changed")
        val current = readManualSource()
        try { if (current == null || !record.sameEnvelope(current)) throw CancellationException("Configuration source changed") }
        finally { current?.close() }
    }
    private fun restoreManualSource(record: ManualSourceRecord, epoch: Long): LocalSubscriptionSnapshot = synchronized(lock) {
        requireSameSource(record, epoch)
        authority.selectionSourceKind = record.kind
        authority.selectionSourceRevision = record.revision
        if (record.kind == ManualSourceKind.None) {
            invalidateSelections(LocalSubscriptionStatus.MISSING)
            state = LocalSubscriptionSnapshot(LocalSubscriptionStatus.MISSING, sourceKind = record.kind)
            return@synchronized state
        }
        val bytes = record.copyBytes() ?: throw PortalFailure(PortalFailure.Reason.STORAGE)
        try {
            val parsed = try { manualSnapshot(bytes, record) } catch (_: UnsafeProxyConfiguration) {
                LocalSubscriptionSnapshot(LocalSubscriptionStatus.UNSUPPORTED, hasProfile = true, hasCapability = true,
                    validUntil = record.validUntil, sourceKind = record.kind,
                    validity = sourceValidity(record.kind, record.validUntil))
            }
            val blocked = authority.selectionStatus?.takeIf { it != LocalSubscriptionStatus.READY }
            state = (blocked ?: record.observedStatus)?.let { parsed.copy(status = it) } ?: parsed
            if (state.status == LocalSubscriptionStatus.READY && !authority.selectionRequiresRefresh)
                observeContent(bytes, sourceBinding(record), state.nodes, state.validUntil, record.kind, record.revision)
            else invalidateSelections(state.status, state.validUntil)
            state
        } finally { bytes.fill(0) }
    }
    private suspend fun refreshManualSource(record: ManualSourceRecord, epoch: Long,
        prepared: ((LocalSubscriptionSnapshot, ByteArray, String, Long) -> Unit)?): LocalSubscriptionSnapshot {
        if (record.kind == ManualSourceKind.None) return synchronized(lock) {
            requireSameSource(record, epoch)
            authority.selectionSourceKind = record.kind; authority.selectionSourceRevision = record.revision
            invalidateSelections(LocalSubscriptionStatus.MISSING)
            state = LocalSubscriptionSnapshot(LocalSubscriptionStatus.MISSING, sourceKind = record.kind); state
        }
        val downloaded = when (record.kind) {
            ManualSourceKind.ImportedRemote -> source.read(requireNotNull(record.url))
            ManualSourceKind.ExternalRemote -> (externalSource ?: throw UnsupportedManualSubscriptionLink()).read(requireNotNull(record.url))
            else -> null
        }
        val bytes = downloaded?.bytes ?: record.copyBytes() ?: throw PortalFailure(PortalFailure.Reason.STORAGE)
        try {
            currentCoroutineContext().ensureActive()
            val until = if (downloaded != null) downloaded.validUntil ?: record.validUntil else null
            if (record.kind == ManualSourceKind.ImportedRemote && until == null) throw PortalFailure(PortalFailure.Reason.SUBSCRIPTION_UNAVAILABLE)
            val fresh = ManualSourceRecord(record.revision, record.kind, bytes, record.url, until)
            try {
                val next = manualSnapshot(bytes, fresh)
                return synchronized(lock) {
                    requireSameSource(record, epoch)
                    if (downloaded != null) writeManualSource(fresh)
                    authority.selectionSourceKind = record.kind; authority.selectionSourceRevision = record.revision
                    if (next.status == LocalSubscriptionStatus.READY)
                        observeContent(bytes, sourceBinding(fresh), next.nodes, next.validUntil, fresh.kind, fresh.revision)
                    else invalidateSelections(next.status, next.validUntil)
                    state = next
                    prepared?.invoke(next, bytes, sourceBinding(fresh), epoch)
                    next
                }
            } finally { fresh.close() }
        } finally { bytes.fill(0) }
    }
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
    suspend fun prepare(intent: SelectionIntent, files: PreparedConfigStore,
        addressPreparer: ConfigAddressPreparer = LiteralConfigAddressPreparer): ConfigPreparation =
        prepareChecked(intent, files, addressPreparer, expectedEpoch = null)

    internal suspend fun prepareAtEpoch(intent: SelectionIntent, files: PreparedConfigStore,
        addressPreparer: ConfigAddressPreparer, expectedEpoch: Long): ConfigPreparation =
        prepareChecked(intent, files, addressPreparer, expectedEpoch)

    private suspend fun prepareChecked(intent: SelectionIntent, files: PreparedConfigStore,
        addressPreparer: ConfigAddressPreparer, expectedEpoch: Long?): ConfigPreparation {
        var result: ConfigPreparation? = null
        var material: AuthorizedConfig? = null
        var rawCopy: ByteArray? = null
        var address: PreparedAddressConfig? = null
        var leaseTransferred = false
        var binding = ""
        var epoch = -1L
        var index = -1
        var until: Instant? = null
        var selectionAtPreparation: ConnectionSelection? = null
        try {
            mutex.withLock {
                val snapshot = refreshLocked(expectedEpoch) { fresh, raw, freshBinding, freshEpoch ->
                    if (fresh.status != LocalSubscriptionStatus.READY) return@refreshLocked
                    val selected = (intent as? SelectionIntent.Selected)?.selection
                    index = authority.choices.indexOfFirst { it.selection === selected }
                    if (index < 0) {
                        result = ConfigPreparation.SelectionRequired(authority.choices.toList()); return@refreshLocked
                    }
                    binding = freshBinding; epoch = freshEpoch; until = fresh.validUntil
                    selectionAtPreparation = authority.selected
                    rawCopy = raw.copyOf()
                }
                if (result == null && rawCopy == null) result = ConfigPreparation.Unavailable(when (snapshot.status) {
                    LocalSubscriptionStatus.MISSING -> PreparationFailure.MISSING
                    LocalSubscriptionStatus.EXPIRED -> PreparationFailure.EXPIRED
                    LocalSubscriptionStatus.INVALID_LINK -> PreparationFailure.INVALID_LINK
                    LocalSubscriptionStatus.UNSUPPORTED -> PreparationFailure.UNSUPPORTED
                    LocalSubscriptionStatus.STORAGE_ERROR -> PreparationFailure.STORAGE
                    else -> PreparationFailure.NETWORK
                })
            }
            // Network waits hold neither the subscription monitor nor its operation mutex.
            // A refresh, unlink, changed selection or cancellation can revoke this epoch immediately.
            rawCopy?.let { raw ->
                address = addressPreparer.prepare(raw, index)
                currentCoroutineContext().ensureActive()
                val prepared = requireNotNull(address)
                fun current() {
                    requireCurrent(epoch, binding, until)
                    if (authority.selected !== selectionAtPreparation || prepared.lease?.isCurrent() == false)
                        throw PreparationRejected(PreparationFailure.STALE)
                }
                synchronized(lock) { current() }
                var consumed = false
                material = AuthorizedConfig("local-selection", prepared.bytes, until, prepared.lease) { action -> synchronized(lock) {
                    if (consumed) throw PreparationRejected(PreparationFailure.STALE)
                    consumed = true
                    current()
                    val copy = requireNotNull(material).copyMaterial()
                    try {
                        if (!requireNotNull(material).hasPreparedIdentity(copy)) throw PreparationRejected(PreparationFailure.STALE)
                        files.replace(copy)
                    } catch (failure: PreparationRejected) { throw failure } catch (_: Exception) {
                        throw PreparationRejected(PreparationFailure.STORAGE)
                    } finally { copy.fill(0) }
                    try { current() } catch (failure: Throwable) {
                        try { files.clear() } catch (_: Exception) { throw PreparationRejected(PreparationFailure.STORAGE) }
                        throw failure
                    }
                    action()
                } }
                leaseTransferred = true
                result = ConfigPreparation.Ready(requireNotNull(material))
            }
            currentCoroutineContext().ensureActive()
            return requireNotNull(result)
        } catch (failure: Throwable) { material?.close(); throw failure }
        finally {
            rawCopy?.fill(0)
            address?.bytes?.fill(0)
            if (!leaseTransferred) address?.lease?.close()
        }
    }
    private suspend fun refreshLocked(expectedEpoch: Long? = null,
        prepared: ((LocalSubscriptionSnapshot, ByteArray, String, Long) -> Unit)? = null): LocalSubscriptionSnapshot {
        val epoch = synchronized(lock) {
            if (expectedEpoch != null && revision != expectedEpoch) throw CancellationException("Subscription selection changed")
            ++revision
        }
        var knownCapability = false
        try {
            val selectedSource = synchronized(lock) { readManualSource() }
            if (selectedSource != null) try {
                if (selectedSource.kind != ManualSourceKind.LegacyRemote) {
                    knownCapability = selectedSource.kind != ManualSourceKind.None
                    return refreshManualSource(selectedSource, epoch, prepared)
                }
            } finally { selectedSource.close() }
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
