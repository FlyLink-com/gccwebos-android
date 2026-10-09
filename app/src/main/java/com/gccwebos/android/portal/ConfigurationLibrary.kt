package com.gccwebos.android.portal

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.UUID

/** Display names are not capabilities and are never derived from a secret URL. */
object ConfigurationName {
    const val MAX_CODE_POINTS = 48
    const val MAX_BYTES = 192
    fun normalize(value: String): String = try {
        val name = value.trim()
        require(name.isNotEmpty() && name.codePointCount(0, name.length) <= MAX_CODE_POINTS)
        require(name.none { Character.isISOControl(it) || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' ||
            it == '\u200e' || it == '\u200f' || it == '\u061c' || it == '\u2028' || it == '\u2029' })
        require(!Regex("[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(name))
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(name))
        try { require(encoded.remaining() <= MAX_BYTES) }
        finally { if (encoded.hasArray()) encoded.array().fill(0) }
        name
    } catch (_: Exception) { throw IllegalArgumentException() }

    /** Display-name comparison only (ids stay authoritative): NFKC, trimmed, collapsed spaces, case-insensitive. */
    fun comparable(value: String): String = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
        .trim().replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT)
    /** True when a user-chosen name would repeat another saved name. Existing stored duplicates are tolerated. */
    fun conflicts(candidate: String, others: Iterable<String>): Boolean {
        val wanted = comparable(candidate)
        return wanted.isNotEmpty() && others.any { comparable(it) == wanted }
    }
    const val DUPLICATE_MESSAGE = "已有同名订阅，请重新命名你的订阅"
}

enum class ConfigurationSelector { Account, None, Entry }

/** An entry owns a copy; callers close only the copies returned by copySource(). */
class ConfigurationEntry(val id: String, name: String, source: ManualSourceRecord) : AutoCloseable {
    val name: String = ConfigurationName.normalize(name)
    val sourceRevision: String = source.revision
    private val record: ManualSourceRecord
    private var closed = false
    init {
        requireLibraryUuid(id)
        require(source.kind in MANUAL_KINDS && !source.clearPending)
        record = source.duplicate()
    }
    @Synchronized fun copySource(): ManualSourceRecord { check(!closed); return record.duplicate() }
    @Synchronized override fun close() { if (!closed) { record.close(); closed = true } }
    internal fun duplicate(): ConfigurationEntry = copySource().use { ConfigurationEntry(id, name, it) }
    internal fun sameEntry(other: ConfigurationEntry): Boolean = id == other.id && name == other.name &&
        copySource().use { left -> other.copySource().use(left::sameEnvelope) }
    override fun toString() = "ConfigurationEntry([REDACTED])"
    internal companion object {
        val MANUAL_KINDS = setOf(ManualSourceKind.ImportedRemote, ManualSourceKind.ImportedLocal, ManualSourceKind.ExternalRemote)
    }
}

/** One bounded transaction contains all entries and the selected source. No account material. */
class ConfigurationLibrary(
    val revision: String = UUID.randomUUID().toString(),
    entries: List<ConfigurationEntry> = emptyList(),
    val selector: ConfigurationSelector = ConfigurationSelector.Account,
    val selectedEntryId: String? = null,
    val selectorRevision: String = UUID.randomUUID().toString(),
    val clearPending: Boolean = false,
) : AutoCloseable {
    private val owned: List<ConfigurationEntry>
    private var closed = false
    val entries: List<ConfigurationEntry> get() { check(!closed); return owned }
    init {
        requireLibraryUuid(revision); requireLibraryUuid(selectorRevision)
        require(entries.size <= MAX_ENTRIES)
        require(entries.map { it.id }.distinct().size == entries.size)
        require(entries.map { it.sourceRevision }.distinct().size == entries.size)
        if (selector == ConfigurationSelector.Entry) {
            requireLibraryUuid(requireNotNull(selectedEntryId))
            require(entries.singleOrNull { it.id == selectedEntryId }?.sourceRevision == selectorRevision)
        } else {
            require(selectedEntryId == null)
            require(entries.none { it.sourceRevision == selectorRevision })
        }
        require(!clearPending || (selector == ConfigurationSelector.None && entries.isEmpty()))
        val copies = ArrayList<ConfigurationEntry>(entries.size)
        try {
            entries.forEach { copies.add(it.duplicate()) }
            owned = Collections.unmodifiableList(copies)
            // Enforce the aggregate encoded bound at the model boundary as well as at decode.
            require(ConfigurationLibraryCodec.encodedSize(this) <= MAX_ENCODED_BYTES)
        } catch (failure: Exception) { copies.forEach { it.close() }; throw failure }
    }
    @Synchronized fun activeSource(): ManualSourceRecord {
        check(!closed)
        return when (selector) {
            ConfigurationSelector.Account -> ManualSourceRecord(selectorRevision, ManualSourceKind.LegacyRemote)
            ConfigurationSelector.None -> ManualSourceRecord(selectorRevision, ManualSourceKind.None, clearPending = clearPending)
            ConfigurationSelector.Entry -> owned.single { it.id == selectedEntryId }.copySource()
        }
    }
    @Synchronized override fun close() { if (!closed) { owned.forEach { it.close() }; closed = true } }
    fun duplicate(): ConfigurationLibrary = ConfigurationLibrary(revision, entries, selector, selectedEntryId, selectorRevision, clearPending)
    fun sameEnvelope(other: ConfigurationLibrary): Boolean = revision == other.revision && selector == other.selector &&
        selectedEntryId == other.selectedEntryId && selectorRevision == other.selectorRevision && clearPending == other.clearPending &&
        entries.size == other.entries.size && entries.indices.all { entries[it].sameEntry(other.entries[it]) }

    /** Compatibility writes preserve every inactive entry. An ordinary None only deselects. */
    internal fun withActiveSource(source: ManualSourceRecord): ConfigurationLibrary {
        check(!clearPending || (source.kind == ManualSourceKind.None && entries.isEmpty()))
        if (source.kind == ManualSourceKind.LegacyRemote || source.kind == ManualSourceKind.None) {
            require(!source.clearPending || entries.isEmpty()) // Full clear must explicitly commit an empty library.
            return ConfigurationLibrary(entries = entries, selector = if (source.kind == ManualSourceKind.LegacyRemote)
                ConfigurationSelector.Account else ConfigurationSelector.None, selectorRevision = source.revision,
                clearPending = source.clearPending)
        }
        val active = entries.singleOrNull { selector == ConfigurationSelector.Entry && it.id == selectedEntryId &&
            it.sourceRevision == source.revision }
        require(active != null || entries.none { it.sourceRevision == source.revision })
        val replacement = ConfigurationEntry(active?.id ?: source.revision, active?.name ?: defaultName(entries.size + 1), source)
        try {
            val updated = if (active == null) entries + replacement else entries.map { if (it.id == active.id) replacement else it }
            return ConfigurationLibrary(entries = updated, selector = ConfigurationSelector.Entry,
                selectedEntryId = replacement.id, selectorRevision = source.revision)
        } finally { replacement.close() }
    }
    override fun toString() = "ConfigurationLibrary([REDACTED])"
    companion object {
        const val MAX_ENTRIES = 20
        const val MAX_ENCODED_BYTES = 2 * 1024 * 1024
        fun defaultName(number: Int): String { require(number > 0); return "配置 $number" }
        /** Pure normalization only: caller must explicitly saveLibrary to migrate the disk format. */
        fun fromLegacy(source: ManualSourceRecord): ConfigurationLibrary {
            if (source.kind in ConfigurationEntry.MANUAL_KINDS) {
                return ConfigurationEntry(source.revision, defaultName(1), source).use { entry ->
                    ConfigurationLibrary(source.revision, listOf(entry), ConfigurationSelector.Entry, entry.id, source.revision)
                }
            }
            return ConfigurationLibrary(source.revision, selector = if (source.kind == ManualSourceKind.LegacyRemote)
                ConfigurationSelector.Account else ConfigurationSelector.None, selectorRevision = source.revision,
                clearPending = source.clearPending)
        }
    }
}

interface LibraryManualSourceStore : ManualSourceStore {
    /** Null means absent; corrupt/unknown state never normalizes to an empty library. */
    fun readLibrary(): ConfigurationLibrary?
    /** Explicit whole-envelope transaction. Caller owns CAS and the connection-owner fence. */
    fun saveLibrary(library: ConfigurationLibrary)
}

/** v3 embeds strict v1/v2/v4 source records. A bare (pre-library) envelope may be v1, v2 or v4.
 * Legacy reads never cause writes. */
internal object ConfigurationLibraryCodec {
    const val MAX_ENCODED_BYTES = ConfigurationLibrary.MAX_ENCODED_BYTES
    private const val MAGIC = 0x474d5331
    private const val VERSION = 3

    internal fun version(plain: ByteArray): Int {
        require(plain.size in 8..MAX_ENCODED_BYTES)
        val header = ByteBuffer.wrap(plain)
        require(header.int == MAGIC)
        return header.int.also { require(it in 1..VERSION || it == ManualSourceCodec.USAGE_VERSION) }
    }
    internal fun encodedSize(library: ConfigurationLibrary): Int {
        var size = 8 + 40 + 4 + 4 + (library.selectedEntryId?.length ?: 0) + 40 + 1 + 4
        library.entries.forEach { entry ->
            val name = entry.name.toByteArray(Charsets.UTF_8)
            try { entry.copySource().use { source ->
                val raw = ManualSourceCodec.encode(source)
                try { size += 40 + 4 + name.size + 4 + raw.size } finally { raw.fill(0) }
            } } finally { name.fill(0) }
        }
        return size
    }
    fun encode(library: ConfigurationLibrary): ByteArray {
        val buffer = WipingOutput()
        try {
            require(encodedSize(library) <= MAX_ENCODED_BYTES)
            DataOutputStream(buffer).use { out ->
                out.writeInt(MAGIC); out.writeInt(VERSION)
                writeString(out, library.revision)
                out.writeInt(when (library.selector) { ConfigurationSelector.Account -> 0; ConfigurationSelector.None -> 1; ConfigurationSelector.Entry -> 2 })
                writeString(out, library.selectedEntryId); writeString(out, library.selectorRevision)
                out.writeBoolean(library.clearPending); out.writeInt(library.entries.size)
                library.entries.forEach { entry ->
                    writeString(out, entry.id); writeString(out, entry.name)
                    entry.copySource().use { source ->
                        val raw = ManualSourceCodec.encode(source)
                        try { out.writeInt(raw.size); out.write(raw) } finally { raw.fill(0) }
                    }
                }
            }
            require(buffer.size() <= MAX_ENCODED_BYTES)
            return buffer.toByteArray()
        } finally { buffer.wipe() }
    }
    fun decode(plain: ByteArray): ConfigurationLibrary {
        val parsed = ArrayList<ConfigurationEntry>()
        try {
            if (version(plain) != VERSION) return ManualSourceCodec.decode(plain).use(ConfigurationLibrary::fromLegacy)
            val input = DataInputStream(ByteArrayInputStream(plain))
            input.readInt(); input.readInt()
            val revision = requireNotNull(readString(input, 36))
            val selector = when (input.readInt()) { 0 -> ConfigurationSelector.Account; 1 -> ConfigurationSelector.None
                2 -> ConfigurationSelector.Entry; else -> throw IllegalArgumentException() }
            val selected = readString(input, 36)
            val sourceRevision = requireNotNull(readString(input, 36))
            val clearPending = input.readUnsignedByte().also { require(it in 0..1) } == 1
            val count = input.readInt().also { require(it in 0..ConfigurationLibrary.MAX_ENTRIES) }
            repeat(count) {
                val id = requireNotNull(readString(input, 36))
                val name = requireNotNull(readString(input, ConfigurationName.MAX_BYTES))
                require(ConfigurationName.normalize(name) == name)
                val size = input.readInt().also { require(it in 1..ManualSourceCodec.MAX_ENCODED_BYTES && it <= input.available()) }
                val bytes = ByteArray(size)
                try {
                    input.readFully(bytes)
                    ManualSourceCodec.decode(bytes).use { source -> parsed.add(ConfigurationEntry(id, name, source)) }
                } finally { bytes.fill(0) }
            }
            require(input.available() == 0)
            return ConfigurationLibrary(revision, parsed, selector, selected, sourceRevision, clearPending)
        } catch (_: Exception) { throw IllegalArgumentException() }
        finally { parsed.forEach { it.close() } }
    }
    private fun writeString(out: DataOutputStream, value: String?) {
        val bytes = value?.toByteArray(Charsets.UTF_8)
        try { out.writeInt(bytes?.size ?: -1); bytes?.let(out::write) } finally { bytes?.fill(0) }
    }
    private fun readString(input: DataInputStream, maximum: Int): String? {
        val length = input.readInt()
        require(length == -1 || length in 1..maximum)
        if (length == -1) return null
        require(length <= input.available())
        val bytes = ByteArray(length)
        try {
            input.readFully(bytes)
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } finally { bytes.fill(0) }
    }
    private class WipingOutput : ByteArrayOutputStream() { fun wipe() { buf.fill(0); reset() } }
}

private fun requireLibraryUuid(value: String) {
    require(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}").matches(value) &&
        UUID.fromString(value).toString() == value)
}
