package com.gccwebos.android.portal

import com.gccwebos.android.core.AuthorizedConfig
import com.gccwebos.android.manualimport.ImportStage
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
import java.util.UUID
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.withLock

class CachedSubscription(val binding: String, bytes: ByteArray, val validUntil: Instant?, val url: Secret? = null,
    val observedStatus: LocalSubscriptionStatus? = null,
    /** Display-only usage reported by this capability's own last download; null is unknown. Never account data. */
    val usage: SubscriptionUsage? = null) : AutoCloseable {
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
    override fun read(): CachedSubscription? = value?.let { old -> val bytes = old.copyBytes(); try { CachedSubscription(old.binding, bytes, old.validUntil, old.url, old.observedStatus, old.usage) } finally { bytes.fill(0) } }
    override fun save(value: CachedSubscription) { val bytes = value.copyBytes(); try { this.value?.close(); this.value = CachedSubscription(value.binding, bytes, value.validUntil, value.url, value.observedStatus, value.usage) } finally { bytes.fill(0) } }
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
    val validity: LocalSourceValidity = LocalSourceValidity.RemoteExpiry(validUntil),
    /** Display-only usage reported by the selected remote source's own last download; never admission or account data. */
    val usage: SubscriptionUsage? = null)
/** Credential-free, process-local UI selection; never connection admission or a persisted ID. */
data class NodeSelectionSnapshot(val status: LocalSubscriptionStatus,
    val choices: List<ConnectionChoice> = emptyList(), val selected: ConnectionSelection? = null,
    val validUntil: Instant? = null,
    val sourceKind: ManualSourceKind = ManualSourceKind.LegacyRemote,
    val validity: LocalSourceValidity = LocalSourceValidity.RemoteExpiry(validUntil),
    /** A remembered choice for this configuration no longer matches any node: ask the user to choose again. */
    val selectionLost: Boolean = false)
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
    /** Memory-only node identities aligned with [choices]; digested, never logged or persisted. */
    internal var choiceKeys = emptyList<String>()
    /** The registry's nodes exactly as the source delivered them; local aliases/deletions are applied on top. */
    internal var choiceBase = emptyList<SafeProxyConfig.NodeSummary>()
    internal var selected: ConnectionSelection? = null
    /** Display-only: a remembered node choice was not found in the current registry. */
    internal var selectionLost = false
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
    private val externalSource: SubscriptionSource? = null,
    /** Remembers the user's node choice across restarts. Null disables persistence. */
    private val nodeChoices: NodeChoiceStore? = null,
    /** Local per-node alias / deletion of imported nodes. Display only; null disables it. */
    private val nodeOverrides: NodeOverrideStore? = null) {
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
    private val libraryOwner = Any()

    private fun readLibrary(): ConfigurationLibrary = synchronized(lock) {
        val active = readManualSource()
        try {
            if (manualSources is LibraryManualSourceStore) {
                try { manualSources.readLibrary() ?: throw PortalFailure(PortalFailure.Reason.STORAGE) }
                catch (_: Exception) {
                    authority.manualStorageBlocked = true
                    revision++; invalidateSelections(LocalSubscriptionStatus.STORAGE_ERROR)
                    throw PortalFailure(PortalFailure.Reason.STORAGE)
                }
            } else if (active != null) ConfigurationLibrary.fromLegacy(active)
            else ManualSourceRecord(revision = "00000000-0000-0000-0000-000000000000",
                kind = ManualSourceKind.LegacyRemote).use(ConfigurationLibrary::fromLegacy)
        } finally { active?.close() }
    }

    /** Summary reads never register choices or activate an inactive entry. */
    internal fun configurationLibrarySnapshot(accountSignedIn: Boolean = false): ConfigurationLibrarySnapshot = synchronized(lock) {
        PrepTrace.span("library-snapshot") { try {
            readLibrary().use { library ->
                val items = mutableListOf<ConfigurationItem>()
                fun handle(id: String?) = ConfigurationHandle(libraryOwner, library.revision, id, authority.clearVersion)
                val accountActive = library.selector == ConfigurationSelector.Account
                val cached = profiles.read()
                try {
                    if (cached != null) {
                        val raw = cached.copyBytes()
                        try {
                            val parsed = describeForLibrary(raw, cached.validUntil, cached.observedStatus)
                            // The account subscription is read-only: no alias, no deletion, so no node keys.
                            items += ConfigurationItem(handle(null), OFFICIAL_SOURCE_NAME, ManualSourceKind.LegacyRemote,
                                parsed.nodes, parsed.status, accountActive, contentKey = contentKey(raw))
                        } finally { raw.fill(0) }
                    } else if (accountActive || accountSignedIn || delivery.read()?.let { !it.pendingAck } == true) {
                        // A signed-in account always has a selectable (possibly not yet loaded) subscription row,
                        // so a previously imported configuration can never hide it. Activating it downloads.
                        items += ConfigurationItem(handle(null), OFFICIAL_SOURCE_NAME, ManualSourceKind.LegacyRemote,
                            if (accountActive) state.nodes else emptyList(),
                            if (accountActive) state.status else LocalSubscriptionStatus.MISSING, accountActive)
                    }
                } finally { cached?.close() }
                val overrides = try { nodeOverrides?.read() ?: emptyMap() } catch (_: Exception) { emptyMap() }
                for (entry in library.entries) entry.copySource().use { source ->
                    val raw = source.copyBytes() ?: throw PortalFailure(PortalFailure.Reason.STORAGE)
                    try {
                        val parsed = describeForLibrary(raw, source.validUntil, source.observedStatus)
                        val keys = libraryParse(raw).keys
                        items += ConfigurationItem(handle(entry.id), configurationDisplayName(entry.name), source.kind,
                            displayNodes(parsed.nodes, keys, overrides["entry:${entry.id}"].orEmpty()),
                            parsed.status, library.selector == ConfigurationSelector.Entry && library.selectedEntryId == entry.id,
                            nodeKeys = keys.orEmpty(), nodeAliased = aliasedPositions(keys, overrides["entry:${entry.id}"].orEmpty()),
                            nodeDeleted = hiddenPositions(parsed.nodes, keys, overrides["entry:${entry.id}"].orEmpty()),
                            contentKey = contentKey(raw))
                    } finally { raw.fill(0) }
                }
                ConfigurationLibrarySnapshot(items)
            }
        } catch (_: Exception) { ConfigurationLibrarySnapshot(storageError = true) } }
    }

    /** One-way node keys (the same ones the remembered choice uses); a key shared by two nodes cannot address either. */
    private fun persistedNodeKeys(raw: ByteArray, expected: Int): List<String?>? = try {
        val keys = SafeProxyConfig.selectionKeys(raw).map(NodeChoiceKey::persisted)
        if (expected >= 0 && keys.size != expected) null else {
            val counts = keys.groupingBy { it }.eachCount()
            keys.map { key -> key.takeIf { counts[it] == 1 } }
        }
    } catch (_: Exception) { null }
    /** Local alias / deletion on top of the delivered nodes. A deleted node becomes a non-selectable, non-listed entry. */
    private fun displayNodes(nodes: List<SafeProxyConfig.NodeSummary>, keys: List<String?>?,
        overrides: Map<String, NodeOverride>): List<SafeProxyConfig.NodeSummary> {
        if (keys == null || keys.size != nodes.size || overrides.isEmpty()) return nodes
        return nodes.mapIndexed { index, node ->
            val override = keys[index]?.let(overrides::get)
            when {
                override == null || node.info -> node
                override.hidden -> node.copy(info = true, name = override.alias ?: node.name)
                override.alias != null -> node.copy(name = override.alias)
                else -> node
            }
        }
    }
    private fun aliasedPositions(keys: List<String?>?, overrides: Map<String, NodeOverride>): Set<Int> =
        if (keys == null) emptySet() else keys.indices.filter { index -> keys[index]?.let { overrides[it]?.alias } != null }.toSet()
    private fun hiddenPositions(nodes: List<SafeProxyConfig.NodeSummary>, keys: List<String?>?, overrides: Map<String, NodeOverride>): Set<Int> =
        if (keys == null || keys.size != nodes.size) emptySet()
        else keys.indices.filter { index -> !nodes[index].info && keys[index]?.let { overrides[it]?.hidden } == true }.toSet()
    private fun overridesOf(scope: String?): Map<String, NodeOverride> =
        if (scope == null) emptyMap() else try { nodeOverrides?.read()?.get(scope).orEmpty() } catch (_: Exception) { emptyMap() }
    /** Re-derives the active registry's display names / deleted flags from the stored overrides, keeping every selection object. */
    private fun reapplyNodeOverrides() {
        val base = authority.choiceBase
        val keys = authority.choiceKeys
        if (base.isEmpty() || base.size != authority.choices.size || keys.size != base.size) return
        val scope = choiceScope(authority.selectionSourceKind, authority.selectionSourceRevision)
        val persisted = keys.map(NodeChoiceKey::persisted)
        val counts = persisted.groupingBy { it }.eachCount()
        val unique = persisted.map { key -> key.takeIf { counts[it] == 1 } }
        val view = displayNodes(base, unique, overridesOf(scope))
        authority.choices = authority.choices.mapIndexed { index, choice ->
            if (choice.name == view[index].name && choice.info == view[index].info) choice
            else choice.copy(name = view[index].name, info = view[index].info)
        }
        val selected = authority.selected
        if (selected != null && authority.choices.none { it.selection === selected && !it.info }) {
            authority.selected = null
            authority.selectionLost = false
            try { if (scope != null) nodeChoices?.remove(scope) } catch (_: Exception) { }
        }
        revision++
    }

    private fun describeForLibrary(raw: ByteArray, until: Instant?, status: LocalSubscriptionStatus?): LocalSubscriptionSnapshot {
        val cached = libraryParse(raw)
        val parsed = if (cached.unsupported) LocalSubscriptionSnapshot(LocalSubscriptionStatus.UNSUPPORTED, true, true, cached.nodes, until)
        else LocalSubscriptionSnapshot(if (until != null && until <= now()) LocalSubscriptionStatus.EXPIRED else LocalSubscriptionStatus.READY,
            true, true, cached.nodes, until)
        return if (until != null && until <= now()) parsed.copy(status = LocalSubscriptionStatus.EXPIRED)
            else status?.let { parsed.copy(status = it) } ?: parsed
    }

    /** What a library listing needs from one stored configuration. Pure function of the bytes. */
    private class LibraryParse(val unsupported: Boolean, val nodes: List<SafeProxyConfig.NodeSummary>, val keys: List<String?>?)
    /**
     * Parsed nodes and one-way node keys per configuration content (bounded, least recently used). A library
     * listing used to parse and hash every saved configuration again on each call; the same bytes now parse once.
     * Holds the same display-only metadata the active registry already keeps in memory; emptied on any clear.
     */
    private val libraryParses = object : LinkedHashMap<String, LibraryParse>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LibraryParse>?) = size > 32
    }
    private var libraryParsesClearVersion = -1L
    private fun libraryParse(raw: ByteArray): LibraryParse = synchronized(lock) {
        if (libraryParsesClearVersion != authority.clearVersion) { libraryParses.clear(); libraryParsesClearVersion = authority.clearVersion }
        val id = contentKey(raw)
        libraryParses[id]?.let { return it }
        val parsed = try { describe(raw, null).let { LibraryParse(false, it.nodes, null) } } catch (_: UnsafeProxyConfiguration) {
            LibraryParse(true, try { SafeProxyConfig.inspect(raw) } catch (_: Exception) { emptyList() }, null)
        }
        val value = LibraryParse(parsed.unsupported, parsed.nodes, persistedNodeKeys(raw, parsed.nodes.size))
        libraryParses[id] = value
        value
    }
    /** One-way identity of a stored configuration's bytes; equal bytes, equal key. Not a capability. */
    internal fun contentKey(raw: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw)
        try { return digest.take(16).joinToString("") { "%02x".format(it) } } finally { digest.fill(0) }
    }

    private companion object {
        const val MaxLibraryProbeNodesPerSource = 128
        const val MaxLibraryProbeNodes = 128
    }
    private fun requireLibraryHandle(handle: ConfigurationHandle, library: ConfigurationLibrary) {
        if (handle.owner !== libraryOwner || handle.clearVersion != authority.clearVersion ||
            handle.libraryRevision != library.revision || library.clearPending ||
            (handle.entryId != null && library.entries.none { it.id == handle.entryId }))
            throw CancellationException("Configuration library changed")
    }

    private fun writeLibrary(library: ConfigurationLibrary) {
        val store = manualSources as? LibraryManualSourceStore ?: throw PortalFailure(PortalFailure.Reason.STORAGE)
        try {
            store.saveLibrary(library)
            val received = store.readLibrary() ?: throw PortalFailure(PortalFailure.Reason.STORAGE)
            try { if (!library.sameEnvelope(received)) throw PortalFailure(PortalFailure.Reason.STORAGE) }
            finally { received.close() }
        } catch (_: Exception) {
            authority.manualStorageBlocked = true
            revision++; invalidateSelections(LocalSubscriptionStatus.STORAGE_ERROR)
            state = state.copy(status = LocalSubscriptionStatus.STORAGE_ERROR)
            throw PortalFailure(PortalFailure.Reason.STORAGE)
        }
    }

    internal suspend fun renameConfiguration(handle: ConfigurationHandle, name: String) = mutex.withLock {
        val normalized = ConfigurationName.normalize(name)
        val context = currentCoroutineContext()
        context.ensureActive()
        synchronized(lock) {
            context.ensureActive()
            readLibrary().use { library ->
                requireLibraryHandle(handle, library)
                val id = handle.entryId ?: throw IllegalArgumentException()
                val replacement = library.entries.map { entry ->
                    entry.copySource().use { ConfigurationEntry(entry.id, if (entry.id == id) normalized else entry.name, it) }
                }
                try { ConfigurationLibrary(entries = replacement, selector = library.selector,
                    selectedEntryId = library.selectedEntryId, selectorRevision = library.selectorRevision).use(::writeLibrary) }
                finally { replacement.forEach { it.close() } }
                // Metadata does not advance the active authority or content revision.
            }
        }
    }

    /**
     * Local alias / deletion of one node of an imported configuration. [key] comes from the item's own
     * [ConfigurationItem.nodeKeys]; the account subscription has no handle entry and is refused, and a key
     * that is not (uniquely) present in the stored configuration changes nothing. Display only: the
     * subscription, the generated connection config and connection admission are untouched.
     */
    internal suspend fun setNodeOverride(handle: ConfigurationHandle, key: String, value: NodeOverride) {
        val normalized = value.alias?.takeIf { it.isNotBlank() }?.let { ConfigurationName.normalize(it) }
        changeNodeOverride(handle, key) { NodeOverride(normalized, value.hidden) }
    }
    /** Deleting keeps a local alias, so a restored node comes back under the name the user gave it. */
    internal suspend fun setNodeHidden(handle: ConfigurationHandle, key: String, hidden: Boolean) =
        changeNodeOverride(handle, key) { it.copy(hidden = hidden) }

    private suspend fun changeNodeOverride(handle: ConfigurationHandle, key: String, change: (NodeOverride) -> NodeOverride) = mutex.withLock {
        val store = nodeOverrides ?: throw PortalFailure(PortalFailure.Reason.STORAGE)
        val context = currentCoroutineContext()
        context.ensureActive()
        synchronized(lock) {
            context.ensureActive()
            readLibrary().use { library ->
                requireLibraryHandle(handle, library)
                val id = handle.entryId ?: throw IllegalArgumentException()
                val entry = library.entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException()
                val present = entry.copySource().use { source ->
                    val raw = source.copyBytes() ?: throw PortalFailure(PortalFailure.Reason.STORAGE)
                    try { persistedNodeKeys(raw, -1).orEmpty().contains(key) } finally { raw.fill(0) }
                }
                if (!present) throw IllegalArgumentException()
                val scope = "entry:$id"
                try { store.put(scope, key, change(store.read()[scope]?.get(key) ?: NodeOverride())) }
                catch (_: Exception) { throw PortalFailure(PortalFailure.Reason.STORAGE) }
                val active = library.selector == ConfigurationSelector.Entry && library.selectedEntryId == id
                if (active) reapplyNodeOverrides()
            }
        }
    }

    internal suspend fun removeConfiguration(handle: ConfigurationHandle,
        commitFence: ((() -> Unit) -> Unit)): LocalSubscriptionSnapshot = mutex.withLock {
        val context = currentCoroutineContext()
        val active = synchronized(lock) { readLibrary().use { library ->
            requireLibraryHandle(handle, library)
            require(handle.entryId != null)
            library.selector == ConfigurationSelector.Entry && library.selectedEntryId == handle.entryId
        } }
        var committed = false
        val action = { synchronized(lock) {
            context.ensureActive()
            if (committed) throw CancellationException("Configuration operation consumed")
            readLibrary().use { library ->
                requireLibraryHandle(handle, library)
                val nextRevision = if (active) UUID.randomUUID().toString() else library.selectorRevision
                ConfigurationLibrary(entries = library.entries.filter { it.id != handle.entryId },
                    selector = if (active) ConfigurationSelector.None else library.selector,
                    selectedEntryId = if (active) null else library.selectedEntryId, selectorRevision = nextRevision).use { next ->
                    if (active) { revision++; invalidateSelections(LocalSubscriptionStatus.STORAGE_ERROR) }
                    writeLibrary(next)
                    try { nodeChoices?.remove("entry:${handle.entryId}") } catch (_: Exception) { }
                    try { nodeOverrides?.removeScope("entry:${handle.entryId}") } catch (_: Exception) { }
                    if (active) {
                        authority.selectionSourceKind = ManualSourceKind.None
                        authority.selectionSourceRevision = nextRevision
                        invalidateSelections(LocalSubscriptionStatus.MISSING)
                        state = LocalSubscriptionSnapshot(LocalSubscriptionStatus.MISSING, sourceKind = ManualSourceKind.None)
                    }
                    committed = true
                }
            }
        }; Unit }
        if (active) commitFence(action) else action()
        if (!committed) throw CancellationException("Configuration operation cancelled")
        snapshot()
    }

    internal suspend fun activateConfiguration(handle: ConfigurationHandle,
        commitFence: ((() -> Unit) -> Unit)): LocalSubscriptionSnapshot {
        val result = mutex.withLock {
            val library = synchronized(lock) { readLibrary() }
            try {
                synchronized(lock) { requireLibraryHandle(handle, library) }
                val (ticket, previous) = captureSourceCommit(null)
                previous.close()
                val entry = library.entries.firstOrNull { it.id == handle.entryId }
                if (handle.entryId == null) {
                    ManualSourceRecord(kind = ManualSourceKind.LegacyRemote).use { record ->
                        commitSource(record, null, ticket, commitFence) { current ->
                            requireLibraryHandle(handle, current)
                            ConfigurationLibrary(entries = current.entries, selector = ConfigurationSelector.Account,
                                selectorRevision = record.revision)
                        }
                    }
                } else requireNotNull(entry).copySource().use { old ->
                    if (old.observedStatus != null || old.validUntil?.let { it <= now() } == true)
                        throw PortalFailure(PortalFailure.Reason.SUBSCRIPTION_EXPIRED)
                    val downloaded = when (old.kind) {
                        ManualSourceKind.ImportedRemote -> source.read(requireNotNull(old.url))
                        // Tapping a saved source is a page load the user watches, not the connect step: it gets the import budget.
                        ManualSourceKind.ExternalRemote -> (externalSource ?: throw UnsupportedManualSubscriptionLink()).readForImport(requireNotNull(old.url))
                        else -> null
                    }
                    val raw = downloaded?.bytes ?: requireNotNull(old.copyBytes())
                    try {
                        val until = downloaded?.validUntil ?: old.validUntil
                        // Usage is only ever this download's own report; a download without it is unknown.
                        val usage = downloaded?.usage?.let { SubscriptionUsage.observed(it, now()) }
                        ManualSourceRecord(kind = old.kind, bytes = raw, url = old.url, validUntil = until, usage = usage).use { fresh ->
                            val staged = manualSnapshot(raw, fresh)
                            if (staged.status != LocalSubscriptionStatus.READY) throw PortalFailure(PortalFailure.Reason.SUBSCRIPTION_EXPIRED)
                            commitSource(fresh, staged, ticket, commitFence) { current ->
                                requireLibraryHandle(handle, current)
                                ConfigurationEntry(entry.id, entry.name, fresh).use { updated ->
                                    ConfigurationLibrary(entries = current.entries.map { if (it.id == entry.id) updated else it },
                                        selector = ConfigurationSelector.Entry, selectedEntryId = entry.id,
                                        selectorRevision = fresh.revision)
                                }
                            }
                        }
                    } finally { raw.fill(0) }
                }
                snapshot()
            } finally { library.close() }
        }
        return if (handle.entryId == null) refresh() else result
    }
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
    /** Debug only: when parsing rejects, logs the failure class and rejection enum name (never the message) and rethrows. */
    private inline fun <T> tracedParse(block: () -> T): T = try { block() }
    catch (failure: Exception) { PrepTrace.markLazy { ReadSummary.rejection(failure) }; throw failure }
    private fun manualSnapshot(raw: ByteArray, record: ManualSourceRecord): LocalSubscriptionSnapshot {
        val parsed = describe(raw, record.validUntil)
        return parsed.copy(sourceKind = record.kind, validity = sourceValidity(record.kind, record.validUntil), usage = record.usage)
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
            if (record.clearPending && store is LibraryManualSourceStore) {
                ConfigurationLibrary(selector = ConfigurationSelector.None, selectorRevision = record.revision,
                    clearPending = true).use(::writeLibrary)
            } else store.save(record)
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
    private data class SourceCommitTicket(val epoch: Long, val clearVersion: Long, val sourceRevision: String,
        val libraryRevision: String?)
    private fun captureSourceCommit(expected: ManualSourceVersion?, advanceEpoch: Boolean = true): Pair<SourceCommitTicket, ManualSourceRecord> = synchronized(lock) {
        val old = readManualSource(allowBlocked = true) ?: throw PortalFailure(PortalFailure.Reason.STORAGE)
        try {
            if (expected != null && (expected.clearVersion != authority.clearVersion || expected.revision != old.revision))
                throw CancellationException("Configuration source changed")
            val libraryRevision = (manualSources as? LibraryManualSourceStore)?.readLibrary()?.use { it.revision }
            if (manualSources is LibraryManualSourceStore && libraryRevision == null)
                throw PortalFailure(PortalFailure.Reason.STORAGE)
            if (advanceEpoch) revision++
            SourceCommitTicket(revision, authority.clearVersion, old.revision, libraryRevision) to old
        } catch (failure: Exception) {
            old.close()
            if (failure !is CancellationException) authority.manualStorageBlocked = true
            throw failure
        }
    }
    private suspend fun commitSource(record: ManualSourceRecord, staged: LocalSubscriptionSnapshot?, ticket: SourceCommitTicket,
        commitFence: ((() -> Unit) -> Unit), libraryEdit: ((ConfigurationLibrary) -> ConfigurationLibrary)? = null) {
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
                val edited = if (libraryEdit != null && manualSources is LibraryManualSourceStore) {
                    readLibrary().use { currentLibrary ->
                        if (currentLibrary.revision != ticket.libraryRevision) throw CancellationException("Configuration library changed")
                        libraryEdit(currentLibrary)
                    }
                } else null
                try {
                    revision++ // Persistence/readback failure also revokes every older admission.
                    invalidateSelections(LocalSubscriptionStatus.STORAGE_ERROR)
                    if (edited != null) writeLibrary(edited) else writeManualSource(record)
                } finally { edited?.close() }
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
        progress: (ImportStage) -> Unit = {}, commitFence: ((() -> Unit) -> Unit)): LocalSubscriptionSnapshot =
        importManualChange(input, expectedSource, null, commitFence, progress)

    internal suspend fun replaceConfiguration(input: ByteArray, handle: ConfigurationHandle,
        expectedSource: ManualSourceVersion? = null, progress: (ImportStage) -> Unit = {},
        commitFence: ((() -> Unit) -> Unit)): LocalSubscriptionSnapshot =
        importManualChange(input, expectedSource, handle, commitFence, progress)

    private suspend fun importManualChange(input: ByteArray, expectedSource: ManualSourceVersion?,
        replacement: ConfigurationHandle?, commitFence: ((() -> Unit) -> Unit),
        progress: (ImportStage) -> Unit): LocalSubscriptionSnapshot = mutex.withLock {
        val owned = input.copyOf()
        var old: ManualSourceRecord? = null
        var targetSource: ManualSourceRecord? = null
        var staged: ManualSourceRecord? = null
        try {
            if (owned.isEmpty()) throw ManualImportInputRejected(ManualImportInputRejected.Reason.EMPTY)
            if (owned.size > SafeProxyConfig.MAX_BYTES) throw ManualImportInputRejected(ManualImportInputRejected.Reason.TOO_LARGE)
            val text = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(owned)).toString().trim()
            } catch (_: java.nio.charset.CharacterCodingException) { throw ManualImportInputRejected(ManualImportInputRejected.Reason.ENCODING) }
            if (text.isEmpty()) throw ManualImportInputRejected(ManualImportInputRejected.Reason.EMPTY)
            if ('\u0000' in text) throw ManualImportInputRejected(ManualImportInputRejected.Reason.ENCODING)
            var replacementActive = false
            if (replacement != null) synchronized(lock) { readLibrary().use { library ->
                requireLibraryHandle(replacement, library)
                val id = replacement.entryId ?: throw IllegalArgumentException()
                targetSource = library.entries.single { it.id == id }.copySource()
                replacementActive = library.selector == ConfigurationSelector.Entry && library.selectedEntryId == id
            } }
            val (ticket, previous) = captureSourceCommit(expectedSource, replacement == null || replacementActive)
            old = previous
            val expirySource = targetSource ?: previous
            val next: LocalSubscriptionSnapshot
            if (Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(text)) {
                val official = try { policy.subscription(text) } catch (_: Exception) { null }
                val kind = if (official != null) ManualSourceKind.ImportedRemote else ManualSourceKind.ExternalRemote
                val url = Secret(official ?: try { ManualSubscriptionUrlPolicy.validate(text) }
                    catch (_: Exception) { throw UnsupportedManualSubscriptionLink() })
                val reader = if (kind == ManualSourceKind.ImportedRemote) source else externalSource
                    ?: throw UnsupportedManualSubscriptionLink()
                progress(ImportStage.Reading)
                val result = PrepTrace.span("import-read") { reader.readForImport(url) }
                try {
                    currentCoroutineContext().ensureActive()
                    val known = if (expirySource.kind == kind && expirySource.url?.value == url.value &&
                        (kind == ManualSourceKind.ExternalRemote || expirySource.observedStatus == null))
                        expirySource.validUntil else if (kind == ManualSourceKind.ImportedRemote && expirySource.kind == ManualSourceKind.LegacyRemote) {
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
                    // Usage is never carried over from an older record or URL: only this download's own report.
                    staged = ManualSourceRecord(kind = kind, bytes = result.bytes, url = url,
                        validUntil = until, usage = result.usage?.let { SubscriptionUsage.observed(it, now()) })
                    progress(ImportStage.Parsing)
                    next = PrepTrace.span("import-parse") { tracedParse { manualSnapshot(result.bytes, requireNotNull(staged)) } }
                } finally { result.bytes.fill(0) }
            } else {
                staged = ManualSourceRecord(kind = ManualSourceKind.ImportedLocal, bytes = owned)
                progress(ImportStage.Parsing)
                next = PrepTrace.span("import-parse") { tracedParse { manualSnapshot(owned, requireNotNull(staged)) } }
            }
            currentCoroutineContext().ensureActive()
            val edit: (ConfigurationLibrary) -> ConfigurationLibrary = { library ->
                val record = requireNotNull(staged)
                if (replacement == null) {
                    ConfigurationEntry(record.revision, nextConfigurationName(library.entries.map { it.name }), record).use { entry ->
                        ConfigurationLibrary(entries = library.entries + entry, selector = ConfigurationSelector.Entry,
                            selectedEntryId = entry.id, selectorRevision = record.revision)
                    }
                } else {
                    requireLibraryHandle(replacement, library)
                    val oldEntry = library.entries.single { it.id == replacement.entryId }
                    ConfigurationEntry(oldEntry.id, oldEntry.name, record).use { entry ->
                        ConfigurationLibrary(entries = library.entries.map { if (it.id == entry.id) entry else it },
                            selector = library.selector, selectedEntryId = library.selectedEntryId,
                            selectorRevision = if (replacementActive) record.revision else library.selectorRevision)
                    }
                }
            }
            progress(ImportStage.Saving)
            if (replacement == null || replacementActive) {
                PrepTrace.span("import-commit") { commitSource(requireNotNull(staged), next, ticket, commitFence, edit) }
                next
            } else {
                val context = currentCoroutineContext()
                var committed = false
                commitFence { synchronized(lock) {
                    context.ensureActive()
                    if (committed || revision != ticket.epoch || authority.clearVersion != ticket.clearVersion)
                        throw CancellationException("Configuration replacement cancelled")
                    readLibrary().use { library ->
                        if (library.revision != ticket.libraryRevision) throw CancellationException("Configuration library changed")
                        edit(library).use(::writeLibrary)
                    }
                    committed = true
                } }
                if (!committed) throw CancellationException("Configuration replacement cancelled")
                snapshot()
            }
        } finally { owned.fill(0); staged?.close(); targetSource?.close(); old?.close() }
    }
    suspend fun useAccountSource(commitFence: ((() -> Unit) -> Unit)): LocalSubscriptionSnapshot =
        replaceSourceSelector(ManualSourceKind.LegacyRemote, commitFence)
    suspend fun removeManualSource(commitFence: ((() -> Unit) -> Unit)): LocalSubscriptionSnapshot {
        if (manualSources is LibraryManualSourceStore) {
            val active = synchronized(lock) { readLibrary().use { library ->
                if (library.selector == ConfigurationSelector.Entry)
                    ConfigurationHandle(libraryOwner, library.revision, library.selectedEntryId, authority.clearVersion)
                else null
            } }
            if (active != null) return removeConfiguration(active, commitFence)
        }
        return replaceSourceSelector(ManualSourceKind.None, commitFence)
    }
    private suspend fun replaceSourceSelector(kind: ManualSourceKind, commitFence: ((() -> Unit) -> Unit)) = mutex.withLock {
        val (ticket, previous) = captureSourceCommit(null)
        try {
            currentCoroutineContext().ensureActive()
            ManualSourceRecord(kind = kind).use { record -> commitSource(record, null, ticket, commitFence) { library ->
                ConfigurationLibrary(entries = library.entries,
                    selector = if (kind == ManualSourceKind.LegacyRemote) ConfigurationSelector.Account else ConfigurationSelector.None,
                    selectorRevision = record.revision)
            } }
            state
        } finally { previous.close() }
    }
    /**
     * Rebuild opaque node-selection handles from local cached material only (no subscription HTTP).
     * Used when authentication revoked handles while a tunnel is still up: navigation must not
     * download through an accepted connection, but the Nodes page still needs selectable rows
     * instead of the misleading read-only fallback.
     */
    fun rebuildNodeSelectionFromLocal(): Boolean = synchronized(lock) {
        if (authority.manualStorageBlocked || authority.incompleteClear) return@synchronized false
        val selectedSource = readManualSource()
        if (selectedSource != null) try {
            if (selectedSource.kind == ManualSourceKind.None) return@synchronized false
            if (selectedSource.kind != ManualSourceKind.LegacyRemote) {
                val bytes = selectedSource.copyBytes() ?: return@synchronized false
                try {
                    val next = try { manualSnapshot(bytes, selectedSource) }
                        catch (_: UnsafeProxyConfiguration) { return@synchronized false }
                    if (next.status != LocalSubscriptionStatus.READY) return@synchronized false
                    observeContent(bytes, sourceBinding(selectedSource), next.nodes, next.validUntil,
                        selectedSource.kind, selectedSource.revision)
                    state = next
                    return@synchronized true
                } finally { bytes.fill(0) }
            }
        } finally { selectedSource.close() }
        val url = try { capability() } catch (_: Exception) { null } ?: return@synchronized false
        val binding = sessionBinding(url)
        val cached = profiles.read() ?: return@synchronized false
        try {
            if (cached.binding != binding) return@synchronized false
            val raw = cached.copyBytes()
            try {
                val next = try { describe(raw, cached.validUntil, cached.usage) }
                    catch (_: UnsafeProxyConfiguration) { return@synchronized false }
                if (next.status != LocalSubscriptionStatus.READY) return@synchronized false
                observeContent(raw, binding, next.nodes, cached.validUntil)
                state = next
                true
            } finally { raw.fill(0) }
        } finally { cached.close() }
    }
    /** Selection invalidation preserves the user's encrypted subscription and delivery receipt. */
    fun invalidateNodeSelection() = synchronized(lock) {
        revision++; invalidateSelections(); authority.selectionRequiresRefresh = true; Unit
    }
    /** UI-only clock/registry observation. This does not read stores or grant selection/admission. */
    internal fun nodeSelectionDisplaySnapshot(): NodeSelectionSnapshot = synchronized(lock) {
        if (authority.manualStorageBlocked || authority.incompleteClear)
            return@synchronized NodeSelectionSnapshot(LocalSubscriptionStatus.STORAGE_ERROR,
                validUntil = state.validUntil, sourceKind = authority.selectionSourceKind,
                validity = sourceValidity(authority.selectionSourceKind, state.validUntil))
        val observed = authority.selectionStatus ?: state.status
        val until = if (authority.selectionStatus != null) authority.selectionUntil else state.validUntil
        val status = if (observed == LocalSubscriptionStatus.READY && until?.let { it <= now() } == true)
            LocalSubscriptionStatus.EXPIRED else observed
        val ready = status == LocalSubscriptionStatus.READY
        NodeSelectionSnapshot(status, if (ready) authority.choices.toList() else emptyList(),
            if (ready) authority.selected else null, until, authority.selectionSourceKind,
            sourceValidity(authority.selectionSourceKind, until), ready && authority.selectionLost)
    }
    fun nodeSelectionSnapshot(): NodeSelectionSnapshot = synchronized(lock) {
        fun result(status: LocalSubscriptionStatus, choices: List<ConnectionChoice> = emptyList(),
            selected: ConnectionSelection? = null, until: Instant? = null) = NodeSelectionSnapshot(status, choices,
            selected, until, authority.selectionSourceKind, sourceValidity(authority.selectionSourceKind, until),
            status == LocalSubscriptionStatus.READY && authority.selectionLost)
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
    /** Where a remembered choice lives: the account source, or one library entry (stable id, not revision). */
    private fun choiceScope(kind: ManualSourceKind, sourceRevision: String?): String? = try {
        when (kind) {
            ManualSourceKind.LegacyRemote -> "account"
            ManualSourceKind.None -> null
            else -> {
                val store = manualSources as? LibraryManualSourceStore
                if (store == null) "source:${kind.name}" else store.readLibrary()?.use { library ->
                    library.entries.firstOrNull { it.sourceRevision == sourceRevision }?.id?.let { "entry:$it" }
                }
            }
        }
    } catch (_: Exception) { null }
    private fun persistChoice(selection: ConnectionSelection) {
        val store = nodeChoices ?: return
        try {
            val index = authority.choices.indexOfFirst { it.selection === selection && !it.info }
            if (index < 0 || authority.choiceKeys.size != authority.choices.size) return
            val scope = choiceScope(authority.selectionSourceKind, authority.selectionSourceRevision) ?: return
            store.put(scope, NodeChoiceKey.persisted(authority.choiceKeys[index]))
        } catch (_: Exception) { } // Remembering is best effort; the in-memory choice stands.
    }
    /** Re-match a remembered choice against the current registry. Never creates a handle for an unmatched node. */
    private fun restoreChoice(kind: ManualSourceKind, sourceRevision: String?) {
        authority.selectionLost = false
        val store = nodeChoices ?: return
        if (authority.selected != null) return
        try {
            val scope = choiceScope(kind, sourceRevision) ?: return
            val wanted = store.read()[scope] ?: return
            val keys = authority.choiceKeys
            val matches = if (keys.size != authority.choices.size) emptyList() else
                keys.indices.filter { !authority.choices[it].info && NodeChoiceKey.persisted(keys[it]) == wanted }
            if (matches.size == 1) authority.selected = authority.choices[matches.single()].selection
            else authority.selectionLost = true
        } catch (_: Exception) { }
    }
    /** Object identity from the current validated registry only. This does not perform HTTP or prepare. */
    fun selectNode(selection: ConnectionSelection): Boolean = synchronized(lock) {
        val current = nodeSelectionSnapshot()
        if (current.status != LocalSubscriptionStatus.READY || current.choices.none { it.selection === selection && !it.info }) return@synchronized false
        if (authority.selected !== selection) revision++
        authority.selected = selection
        authority.selectionLost = false
        persistChoice(selection)
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
            current.choices != expected.snapshot.choices || current.choices.none { it.selection === selection && !it.info }) return@synchronized null
        authority.selected = selection
        authority.selectionLost = false
        persistChoice(selection)
        ++revision
    }
    /** One cache read per batch; no refresh, selection, epoch change or connection admission. */
    internal fun captureNodeProbeTargets(): List<NodeProbeTarget> = synchronized(lock) {
        withNodeProbeArtifact { raw, choices ->
            val endpoints = SafeProxyConfig.probeEndpoints(raw)
            if (endpoints.size != choices.size || endpoints.indices.any { endpoints[it].protocol != choices[it].protocol })
                return@withNodeProbeArtifact emptyList()
            choices.mapIndexedNotNull { index, choice ->
                if (choice.info) return@mapIndexedNotNull null // Provider notices are never measured or auto-picked.
                val endpoint = endpoints[index]
                NodeProbeTarget(choice.selection, endpoint.endpoint, endpoint.protocol, endpoint.port, probeTransport(endpoint))
            }
        } ?: emptyList()
    }
    /**
     * Probe targets of every saved source that is NOT the active one (the account's cached subscription and each
     * imported configuration), so the unified node list can show latency for all its rows. One library read per
     * batch; display-only. Hidden (locally deleted) nodes, provider notices and unusable sources yield no target.
     */
    internal fun captureLibraryProbeTargets(): List<LibraryProbeTarget> = synchronized(lock) {
        PrepTrace.span("probe-library-capture") {
            try {
                if (authority.manualStorageBlocked || authority.incompleteClear) return@span emptyList()
                readLibrary().use { library ->
                    val overrides = try { nodeOverrides?.read() ?: emptyMap() } catch (_: Exception) { emptyMap() }
                    val out = mutableListOf<LibraryProbeTarget>()
                    fun add(sourceId: String, raw: ByteArray, hidden: Map<String, NodeOverride>) {
                        try {
                            val parse = libraryParse(raw)
                            if (parse.unsupported || parse.nodes.size > MaxLibraryProbeNodesPerSource) return
                            val endpoints = SafeProxyConfig.probeEndpoints(raw)
                            if (endpoints.size != parse.nodes.size) return
                            val key = contentKey(raw)
                            val deleted = hiddenPositions(parse.nodes, parse.keys, hidden)
                            parse.nodes.forEachIndexed { index, node ->
                                if (node.info || index in deleted) return@forEachIndexed
                                val endpoint = endpoints[index]
                                if (endpoint.protocol != node.protocol) return@forEachIndexed
                                out += LibraryProbeTarget(sourceId, index, key, endpoint.endpoint, endpoint.protocol,
                                    endpoint.port, probeTransport(endpoint))
                            }
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { } // An unusable source simply has no measurable nodes.
                    }
                    if (library.selector != ConfigurationSelector.Account) {
                        val cached = profiles.read()
                        try {
                            if (cached != null && cached.validUntil?.let { it > now() } == true &&
                                cached.observedStatus?.let { it == LocalSubscriptionStatus.READY } != false) {
                                val raw = cached.copyBytes()
                                try { add(AccountSourceId, raw, emptyMap()) } finally { raw.fill(0) }
                            }
                        } finally { cached?.close() }
                    }
                    for (entry in library.entries) {
                        if (library.selector == ConfigurationSelector.Entry && library.selectedEntryId == entry.id) continue
                        entry.copySource().use { source ->
                            if (source.observedStatus?.let { it != LocalSubscriptionStatus.READY } == true ||
                                !sourceTimeValid(source.kind, source.validUntil)) return@use
                            val raw = source.copyBytes() ?: return@use
                            try { add(entry.id, raw, overrides["entry:${entry.id}"].orEmpty()) } finally { raw.fill(0) }
                        }
                    }
                    if (out.size > MaxLibraryProbeNodes) out.subList(MaxLibraryProbeNodes, out.size).clear()
                    out.toList()
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { emptyList() }
        }
    }
    /** The active saved entry's id, or null when the account or nothing is active. One library read, no parsing. */
    internal fun activeConfigurationEntryId(): String? = synchronized(lock) {
        try {
            readLibrary().use { library ->
                if (library.selector == ConfigurationSelector.Entry) library.selectedEntryId else null
            }
        } catch (_: Exception) { null }
    }
    /** Recheck results before publication; a target never renews a removed or changed registry. */
    internal fun isCurrentNodeProbeTarget(target: NodeProbeTarget): Boolean = synchronized(lock) {
        if (authority.choices.none { it.selection === target.selection && !it.info }) return@synchronized false
        withNodeProbeArtifact { raw, choices ->
            val index = choices.indexOfFirst { it.selection === target.selection }
            if (index < 0 || choices[index].protocol != target.protocol) return@withNodeProbeArtifact false
            val endpoint = SafeProxyConfig.probeEndpoints(raw).getOrNull(index) ?: return@withNodeProbeArtifact false
            endpoint.endpoint == target.endpoint && endpoint.port == target.port && endpoint.protocol == target.protocol &&
                probeTransport(endpoint) == target.transport
        } == true
    }
    private fun probeTransport(endpoint: SafeProxyConfig.ProbeEndpoint): NodeProbeTransport = when (endpoint.protocol) {
        "ss", "vless" -> NodeProbeTransport.Tcp
        "tuic" -> NodeProbeTransport.Quic
        "hysteria2" -> if (endpoint.obfuscated) NodeProbeTransport.Icmp else NodeProbeTransport.Quic
        else -> throw UnsafeProxyConfiguration(SafeProxyConfig.RejectionReason.Protocol)
    }
    /** Memory-only display masking. Never sufficient permission to probe: workers must also
     * call isCurrentNodeProbeTarget on IO before/after measuring, plus their auth/network/TTL guards. */
    internal fun isNodeProbeTargetRegistryCurrent(target: NodeProbeTarget): Boolean = PrepTrace.lockWait("registry-current", lock) {
        registryCurrentLocked(target)
    }
    private fun registryCurrentLocked(target: NodeProbeTarget): Boolean {
        return authority.selectionStatus == LocalSubscriptionStatus.READY && !authority.selectionRequiresRefresh &&
            !authority.manualStorageBlocked && !authority.incompleteClear &&
            authority.selectionSchema == SafeProxyConfig.SCHEMA_VERSION &&
            authority.selectionClearVersion == authority.clearVersion && authority.selectionBinding != null &&
            authority.selectionRevision != null &&
            sourceTimeValid(authority.selectionSourceKind, authority.selectionUntil) &&
            authority.choices.any { it.selection === target.selection && it.protocol == target.protocol }
    }
    /** Caller holds the authority lock. Unlike nodeSelectionSnapshot, rejection is read-only. */
    private fun <T> withNodeProbeArtifact(read: (ByteArray, List<ConnectionChoice>) -> T): T? =
        PrepTrace.span("probe-artifact") { readNodeProbeArtifact(read) }
    private fun <T> readNodeProbeArtifact(read: (ByteArray, List<ConnectionChoice>) -> T): T? {
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
        val sameSource = authority.selectionBinding == binding && authority.selectionClearVersion == authority.clearVersion &&
            authority.selectionSourceKind == kind && authority.selectionSourceRevision == observedRevision &&
            authority.selectionSchema == SafeProxyConfig.SCHEMA_VERSION
        if (!sameSource || authority.selectionRevision?.contentEquals(digest) != true) {
            // Some providers reorder keys or rewrite notice entries on every download. The raw digest
            // still fences stored content; a node whose normalized identity is unchanged keeps its
            // opaque selection, and the user's choice survives while that node exists.
            val keys = try { SafeProxyConfig.selectionKeys(raw).takeIf { it.size == nodes.size } }
                catch (_: Exception) { null }
            val previous = if (sameSource && keys != null) uniqueKeys(authority.choiceKeys, authority.choices) else emptyMap()
            val reusable = keys?.groupingBy { it }?.eachCount()
            authority.selectionRevision?.fill(0)
            authority.selectionRevision = digest
            authority.selectionBinding = binding
            authority.selectionSchema = SafeProxyConfig.SCHEMA_VERSION
            authority.selectionClearVersion = authority.clearVersion
            val persistedKeys = keys?.map(NodeChoiceKey::persisted)
            val persistedCounts = persistedKeys?.groupingBy { it }?.eachCount()
            val shown = displayNodes(nodes, persistedKeys?.map { key -> key.takeIf { persistedCounts?.get(it) == 1 } },
                overridesOf(choiceScope(kind, observedRevision)))
            authority.choiceBase = nodes
            authority.choices = shown.mapIndexed { index, node ->
                val key = keys?.get(index)?.takeIf { reusable?.get(it) == 1 }
                val old = key?.let(previous::get)?.takeIf { it.protocol == node.protocol && !node.info }
                ConnectionChoice(old?.selection ?: ConnectionSelection(), node.name, node.protocol, node.info)
            }
            authority.choiceKeys = keys ?: emptyList()
            val selected = authority.selected
            authority.selected = if (selected != null && authority.choices.any { it.selection === selected && !it.info }) selected else null
            restoreChoice(kind, observedRevision)
        } else digest.fill(0)
        authority.selectionStatus = LocalSubscriptionStatus.READY
        authority.selectionSourceKind = kind
        authority.selectionSourceRevision = observedRevision
        authority.selectionUntil = until
        authority.selectionRequiresRefresh = false
    }
    /** Only a key that is unique in the old registry can carry its selection forward. */
    private fun uniqueKeys(keys: List<String>, choices: List<ConnectionChoice>): Map<String, ConnectionChoice> {
        if (keys.size != choices.size) return emptyMap()
        val counts = keys.groupingBy { it }.eachCount()
        return keys.indices.filter { counts[keys[it]] == 1 && !choices[it].info }.associate { keys[it] to choices[it] }
    }
    private fun invalidateSelections(status: LocalSubscriptionStatus? = null, until: Instant? = null) {
        authority.selectionRevision?.fill(0); authority.selectionRevision = null
        authority.selectionBinding = null; authority.choices = emptyList(); authority.choiceKeys = emptyList(); authority.choiceBase = emptyList()
        authority.selected = null; authority.selectionLost = false
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
    private fun describe(raw: ByteArray, until: Instant?, usage: SubscriptionUsage? = null): LocalSubscriptionSnapshot {
        val nodes = if (allowHostnamePreparation) SafeProxyConfig.validateForPreparation(raw) else {
            val inspected = SafeProxyConfig.inspect(raw)
            SafeProxyConfig.project(raw, 0).fill(0)
            inspected
        }
        return LocalSubscriptionSnapshot(if (until != null && until <= now()) LocalSubscriptionStatus.EXPIRED else LocalSubscriptionStatus.READY,
            true, true, nodes, until, usage = usage)
    }
    /** Only called after acknowledged delivery and strict projection; never a connection admission. */
    fun remember(raw: ByteArray, url: Secret, until: Instant?, usage: SubscriptionUsage? = null): LocalSubscriptionSnapshot = synchronized(lock) {
        requireLegacySource()
        val binding = sessionBinding(url)
        check(sameCapability(binding)) { "Subscription changed" }
        // Usage is only this download's own report (null = unknown); it is never carried from an older profile.
        val next = describe(raw, until, usage)
        revision++ // A newly observed artifact fences old admission even if durable save fails.
        val cached = CachedSubscription(binding, raw, until, url, usage = usage)
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
        usage: SubscriptionUsage? = null, checkCurrent: () -> Unit): LocalSubscriptionSnapshot = synchronized(lock) {
        requireLegacySource()
        require(journal.phase in setOf(LegacyRecoveryPhase.ACKNOWLEDGED, LegacyRecoveryPhase.PROFILE_COMMITTING))
        val candidate = journal.candidate
        require(!candidate.pendingAck && candidate.url != null && candidate.handoff != null)
        validateCapability(candidate.url)
        val binding = sessionBinding(candidate.url)
        val next = describe(raw, until, usage)
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
            val cached = CachedSubscription(binding, raw, until, candidate.url, usage = usage)
            try { profiles.save(cached) } finally { cached.close() }
            // This readback is required even for an injected store; durable production storage
            // also verifies its own atomic write. Keep the journal if either verification fails.
            val received = profiles.read() ?: throw PortalFailure(PortalFailure.Reason.STORAGE)
            try {
                val bytes = received.copyBytes()
                try {
                    if (received.binding != binding || received.url?.value != candidate.url.value ||
                        received.validUntil != until || received.observedStatus != null || received.usage != usage ||
                        !bytes.contentEquals(raw))
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
            try { nodeChoices?.clear() } catch (_: Exception) { } // A cleared app forgets the remembered node choice.
            try { nodeOverrides?.clear() } catch (_: Exception) { } // ... and every local node alias / deletion.
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
    /**
     * Logout: forget only the account subscription on this device (its cached configuration, delivery record and
     * remembered node). Imported / local library entries and their node edits are untouched. When the account is the
     * active source the selector moves to "none" (entries kept) after [ownerCleanup] has stopped the owner.
     * Without the library store (legacy single-source layout) there is nothing else to keep, so it falls back to the full unlink.
     */
    suspend fun clearAccountSubscription(ownerCleanup: suspend () -> Unit, commitFence: ((() -> Unit) -> Unit)) {
        if (manualSources !is LibraryManualSourceStore) return unlinkAndAwait(ownerCleanup, commitFence)
        var observer: (() -> Unit)? = null
        val clearVersion: Long
        val accountActive: Boolean
        try {
            synchronized(lock) {
                accountActive = readLibrary().use { it.selector == ConfigurationSelector.Account }
                val before = state // Read before the version moves: the getter shows MISSING for a stale stamp.
                revision++; authority.clearVersion++; invalidateSelections()
                clearVersion = authority.clearVersion
                try { nodeChoices?.remove("account") } catch (_: Exception) { }
                if (accountActive) {
                    observer = authority.clearObserver
                    state = LocalSubscriptionSnapshot(LocalSubscriptionStatus.STORAGE_ERROR, sourceKind = ManualSourceKind.LegacyRemote)
                } else state = before // Keep the selected imported source readable; only the version stamp moves.
                try { profiles.clear() } finally { delivery.clear() }
                verifyLegacyAbsent()
            }
        } catch (_: Exception) {
            synchronized(lock) { authority.manualStorageBlocked = true; invalidateSelections(LocalSubscriptionStatus.STORAGE_ERROR) }
            throw PortalFailure(PortalFailure.Reason.STORAGE)
        } finally { observer?.invoke() } // Never call the owner while holding the capability lock.
        if (!accountActive) return
        ownerCleanup() // Stops the tunnel / removes prepared material of the account source.
        currentCoroutineContext().ensureActive()
        var completed = false
        commitFence { synchronized(lock) {
            if (completed || authority.clearVersion != clearVersion) throw CancellationException("Account clear superseded")
            readLibrary().use { library ->
                if (library.selector != ConfigurationSelector.Account) { completed = true; return@use }
                val nextRevision = UUID.randomUUID().toString()
                ConfigurationLibrary(entries = library.entries, selector = ConfigurationSelector.None,
                    selectorRevision = nextRevision).use { next ->
                    writeLibrary(next)
                    authority.selectionSourceKind = ManualSourceKind.None
                    authority.selectionSourceRevision = nextRevision
                    invalidateSelections(LocalSubscriptionStatus.MISSING)
                    state = LocalSubscriptionSnapshot(LocalSubscriptionStatus.MISSING, sourceKind = ManualSourceKind.None)
                    completed = true
                }
            }
        } }
        if (!completed) throw PortalFailure(PortalFailure.Reason.STORAGE)
    }
    private fun rememberObservation(kind: LocalSubscriptionStatus) {
        if (kind !in setOf(LocalSubscriptionStatus.EXPIRED, LocalSubscriptionStatus.INVALID_LINK)) return
        val selectedSource = readManualSource()
        if (selectedSource != null) try {
            if (selectedSource.kind != ManualSourceKind.LegacyRemote) {
                if (selectedSource.kind in setOf(ManualSourceKind.ImportedRemote, ManualSourceKind.ExternalRemote)) {
                    val bytes = selectedSource.copyBytes() ?: throw PortalFailure(PortalFailure.Reason.STORAGE)
                    try { ManualSourceRecord(selectedSource.revision, selectedSource.kind, bytes, selectedSource.url,
                        selectedSource.validUntil, kind, usage = selectedSource.usage).use(::writeManualSource) } finally { bytes.fill(0) }
                }
                return
            }
        } finally { selectedSource.close() }
        val old = profiles.read() ?: return
        try {
            val raw = old.copyBytes()
            val updated = try { CachedSubscription(old.binding, raw, old.validUntil, old.url, kind, old.usage) } finally { raw.fill(0) }
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
                            val parsed = try { describe(raw, cached.validUntil, cached.usage) }
                                catch (_: UnsafeProxyConfiguration) {
                                    val nodes = try { SafeProxyConfig.inspect(raw) } catch (_: Exception) { emptyList() }
                                    LocalSubscriptionSnapshot(LocalSubscriptionStatus.UNSUPPORTED,
                                        hasProfile = true, hasCapability = true, nodes = nodes, validUntil = cached.validUntil,
                                        usage = cached.usage)
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
                    validity = sourceValidity(record.kind, record.validUntil), usage = record.usage)
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
        PrepTrace.mark("refresh-download-begin")
        val downloaded = when (record.kind) {
            ManualSourceKind.ImportedRemote -> source.read(requireNotNull(record.url))
            // `prepared != null` is the connect step (30 s, fresh read, unchanged); a plain refresh is a page load (import budget).
            ManualSourceKind.ExternalRemote -> (externalSource ?: throw UnsupportedManualSubscriptionLink()).let { external ->
                if (prepared == null) external.readForImport(requireNotNull(record.url)) else external.read(requireNotNull(record.url))
            }
            else -> null
        }
        PrepTrace.mark("refresh-download-end")
        val bytes = downloaded?.bytes ?: record.copyBytes() ?: throw PortalFailure(PortalFailure.Reason.STORAGE)
        try {
            currentCoroutineContext().ensureActive()
            val until = if (downloaded != null) downloaded.validUntil ?: record.validUntil else null
            if (record.kind == ManualSourceKind.ImportedRemote && until == null) throw PortalFailure(PortalFailure.Reason.SUBSCRIPTION_UNAVAILABLE)
            // A successful download replaces usage with its own report (or unknown); local files never carry usage.
            val usage = downloaded?.usage?.let { SubscriptionUsage.observed(it, now()) }
            val fresh = ManualSourceRecord(record.revision, record.kind, bytes, record.url, until, usage = usage)
            try {
                val next = manualSnapshot(bytes, fresh)
                return synchronized(lock) {
                    requireSameSource(record, epoch)
                    if (downloaded != null) writeManualSource(fresh)
                    PrepTrace.mark("refresh-stored")
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
        var preparedNode: com.gccwebos.android.core.PreparedNodeLabel? = null
        try {
            PrepTrace.mark("prepare-begin")
            mutex.withLock {
                PrepTrace.mark("prepare-mutex-held")
                val snapshot = refreshLocked(expectedEpoch) { fresh, raw, freshBinding, freshEpoch ->
                    if (fresh.status != LocalSubscriptionStatus.READY) return@refreshLocked
                    val selected = (intent as? SelectionIntent.Selected)?.selection
                    index = authority.choices.indexOfFirst { it.selection === selected && !it.info }
                    if (index < 0) {
                        result = ConfigPreparation.SelectionRequired(authority.choices.toList()); return@refreshLocked
                    }
                    binding = freshBinding; epoch = freshEpoch; until = fresh.validUntil
                    selectionAtPreparation = authority.selected
                    preparedNode = authority.choices[index].let {
                        com.gccwebos.android.core.PreparedNodeLabel(it.selection, it.name, it.protocol)
                    }
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
            PrepTrace.mark("prepare-refreshed")
            rawCopy?.let { raw ->
                address = addressPreparer.prepare(raw, index)
                PrepTrace.mark("prepare-address-ready")
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
                result = ConfigPreparation.Ready(requireNotNull(material), preparedNode)
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
                    // Only this download's own report; a download without usage is unknown, never the old value.
                    val usage = result.usage?.let { SubscriptionUsage.observed(it, now()) }
                    val next = try { describe(result.bytes, until, usage) } catch (_: UnsafeProxyConfiguration) {
                        invalidateSelections(LocalSubscriptionStatus.UNSUPPORTED, until)
                        val nodes = try { SafeProxyConfig.inspect(result.bytes) } catch (_: Exception) { emptyList() }
                        state = state.copy(status = LocalSubscriptionStatus.UNSUPPORTED, hasCapability = true, nodes = nodes,
                            usage = usage)
                        return state
                    }
                    val cached = CachedSubscription(binding, result.bytes, until, url, usage = usage)
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

/**
 * Default names are 「未知N」 (third-party sources), continuing after the highest existing default. Older builds named them
 * 「配置 N」; those are counted too so numbering never repeats. Stored names are never rewritten.
 */
internal fun nextConfigurationName(existing: List<String>): String {
    val pattern = Regex("(?:配置 |未知)([1-9][0-9]{0,8})")
    val highest = existing.mapNotNull { pattern.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull()
    return "未知${(highest ?: existing.size).toLong() + 1}"
}

/** Our own (account) source is always called 「官方」 on the nodes page. */
internal const val OFFICIAL_SOURCE_NAME = "官方"

/** Display only: a legacy default name 「配置 N」 is shown as 「未知N」; any other (user-chosen) name is shown as saved. */
internal fun configurationDisplayName(stored: String): String =
    Regex("配置 ([1-9][0-9]{0,8})").matchEntire(stored)?.let { "未知${it.groupValues[1]}" } ?: stored
