package com.gccwebos.android.portal

import com.gccwebos.android.vpn.SafeProxyConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Selection and selected material always belong to one atomic envelope. */
enum class ManualSourceKind { LegacyRemote, ImportedRemote, ImportedLocal, None }

/** This record owns its bytes; it does not grant connection authority or validate YAML. */
class ManualSourceRecord(
    val revision: String = UUID.randomUUID().toString(),
    val kind: ManualSourceKind,
    bytes: ByteArray? = null,
    val url: Secret? = null,
    val validUntil: Instant? = null,
    val observedStatus: LocalSubscriptionStatus? = null,
    /** Only a payload-free None may durably block an interrupted full clear across restart. */
    val clearPending: Boolean = false
) : AutoCloseable {
    private var material: ByteArray?
    private var closed = false
    val contentIdentity: String?

    init {
        require(REVISION.matches(revision) && UUID.fromString(revision).toString() == revision)
        require(bytes == null || bytes.size in 1..SafeProxyConfig.MAX_BYTES)
        require(observedStatus == null || observedStatus == LocalSubscriptionStatus.EXPIRED ||
            observedStatus == LocalSubscriptionStatus.INVALID_LINK)
        require(!clearPending || kind == ManualSourceKind.None)
        when (kind) {
            ManualSourceKind.LegacyRemote, ManualSourceKind.None ->
                require(bytes == null && url == null && validUntil == null && observedStatus == null)
            ManualSourceKind.ImportedLocal ->
                require(bytes != null && url == null && validUntil == null && observedStatus == null)
            ManualSourceKind.ImportedRemote -> {
                require(bytes != null && url != null && validUntil != null)
                validateUrlShape(url.value)
            }
        }
        material = bytes?.copyOf()
        contentIdentity = material?.let { value ->
            MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
        }
    }

    @Synchronized fun copyBytes(): ByteArray? {
        check(!closed)
        return material?.copyOf()
    }

    @Synchronized override fun close() {
        material?.fill(0)
        material = null
        closed = true
    }

    override fun toString() = "ManualSourceRecord([REDACTED])"

    internal fun duplicate(): ManualSourceRecord {
        val raw = copyBytes()
        try { return ManualSourceRecord(revision, kind, raw, url, validUntil, observedStatus, clearPending) }
        finally { raw?.fill(0) }
    }

    /** Readback compares all fields and complete bytes, not just selector or content digest. */
    internal fun sameEnvelope(other: ManualSourceRecord): Boolean {
        val expected = copyBytes()
        try {
            val actual = other.copyBytes()
            try {
                return revision == other.revision && kind == other.kind && url?.value == other.url?.value &&
                    validUntil == other.validUntil && observedStatus == other.observedStatus && clearPending == other.clearPending &&
                    contentIdentity == other.contentIdentity &&
                    if (expected == null) actual == null else actual != null && MessageDigest.isEqual(expected, actual)
            } finally { actual?.fill(0) }
        } finally { expected?.fill(0) }
    }

    companion object {
        private val REVISION = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private fun validateUrlShape(value: String) {
            require(value.length in 1..SafeProxyConfig.MAX_BYTES && value.all { it.code in 0x21..0x7e })
            val parsed = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException() }
            require(parsed.scheme == "https" && parsed.host != null && parsed.rawUserInfo == null &&
                parsed.rawQuery == null && parsed.rawFragment == null && parsed.port in listOf(-1, 443) &&
                Regex("/sub/a/[A-Za-z0-9_-]+(?:\\.yaml)?").matches(parsed.rawPath.orEmpty()))
            // The import/authority boundary applies its configured official-origin allowlist.
        }
    }
}

interface ManualSourceStore {
    /** Null means genuinely absent, never corrupt, unknown or undecryptable. */
    fun read(): ManualSourceRecord?
    /** None is a durable tombstone; deleting an envelope is not a clear operation. */
    fun save(record: ManualSourceRecord)
}

class MemoryManualSourceStore(initial: ManualSourceRecord? = ManualSourceRecord(kind = ManualSourceKind.LegacyRemote)) : ManualSourceStore {
    private var value = initial?.duplicate()
    @Synchronized override fun read(): ManualSourceRecord? = value?.duplicate()
    @Synchronized override fun save(record: ManualSourceRecord) {
        val replacement = record.duplicate()
        value?.close()
        value = replacement
    }
}

/** Fixed, bounded binary schema. Any extra field, short input or unknown code is rejected. */
internal object ManualSourceCodec {
    const val MAX_ENCODED_BYTES = SafeProxyConfig.MAX_BYTES * 2 + 256
    private const val MAGIC = 0x474d5331
    private const val VERSION = 1

    fun encode(record: ManualSourceRecord): ByteArray {
        val raw = record.copyBytes()
        val buffer = WipingOutput()
        try {
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC); output.writeInt(VERSION)
                writeString(output, record.revision, 36)
                output.writeInt(when (record.kind) {
                    ManualSourceKind.LegacyRemote -> 0
                    ManualSourceKind.ImportedRemote -> 1
                    ManualSourceKind.ImportedLocal -> 2
                    ManualSourceKind.None -> 3
                })
                output.writeInt(raw?.size ?: -1)
                if (raw != null) output.write(raw)
                writeString(output, record.url?.value, SafeProxyConfig.MAX_BYTES)
                output.writeBoolean(record.validUntil != null)
                record.validUntil?.let { output.writeLong(it.epochSecond); output.writeInt(it.nano) }
                output.writeInt(when (record.observedStatus) {
                    null -> 0
                    LocalSubscriptionStatus.EXPIRED -> 1
                    LocalSubscriptionStatus.INVALID_LINK -> 2
                    else -> throw IllegalArgumentException()
                })
                writeString(output, record.contentIdentity, 64)
                output.writeBoolean(record.clearPending)
            }
            require(buffer.size() <= MAX_ENCODED_BYTES)
            return buffer.toByteArray()
        } finally { raw?.fill(0); buffer.wipe() }
    }

    fun decode(plain: ByteArray): ManualSourceRecord {
        require(plain.size in 1..MAX_ENCODED_BYTES)
        var raw: ByteArray? = null
        try {
            val input = DataInputStream(ByteArrayInputStream(plain))
            require(input.readInt() == MAGIC && input.readInt() == VERSION)
            val revision = requireNotNull(readString(input, 36))
            val kind = when (input.readInt()) {
                0 -> ManualSourceKind.LegacyRemote
                1 -> ManualSourceKind.ImportedRemote
                2 -> ManualSourceKind.ImportedLocal
                3 -> ManualSourceKind.None
                else -> throw IllegalArgumentException()
            }
            val length = input.readInt()
            require(length == -1 || length in 1..SafeProxyConfig.MAX_BYTES)
            if (length != -1) raw = ByteArray(length).also { input.readFully(it) }
            val url = readString(input, SafeProxyConfig.MAX_BYTES)?.let(::Secret)
            val hasExpiry = input.readUnsignedByte().also { require(it in 0..1) } == 1
            val expiry = if (hasExpiry) {
                val seconds = input.readLong(); val nanos = input.readInt()
                require(nanos in 0..999_999_999)
                Instant.ofEpochSecond(seconds, nanos.toLong())
            } else null
            val status = when (input.readInt()) {
                0 -> null
                1 -> LocalSubscriptionStatus.EXPIRED
                2 -> LocalSubscriptionStatus.INVALID_LINK
                else -> throw IllegalArgumentException()
            }
            val identity = readString(input, 64)
            val clearPending = input.readUnsignedByte().also { require(it in 0..1) } == 1
            require(input.available() == 0)
            val record = ManualSourceRecord(revision, kind, raw, url, expiry, status, clearPending)
            try { require(record.contentIdentity == identity); return record }
            catch (failure: Exception) { record.close(); throw failure }
        } catch (_: Exception) {
            // Parser details can contain imported metadata; only a fixed failure leaves this boundary.
            throw IllegalArgumentException()
        } finally { raw?.fill(0) }
    }

    private fun writeString(output: DataOutputStream, value: String?, maximum: Int) {
        val bytes = value?.toByteArray(Charsets.UTF_8)
        try {
            require(bytes == null || bytes.size in 1..maximum)
            output.writeInt(bytes?.size ?: -1)
            if (bytes != null) output.write(bytes)
        } finally { bytes?.fill(0) }
    }

    private fun readString(input: DataInputStream, maximum: Int): String? {
        val length = input.readInt()
        require(length == -1 || length in 1..maximum)
        if (length == -1) return null
        val bytes = ByteArray(length)
        try {
            input.readFully(bytes)
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } finally { bytes.fill(0) }
    }

    private class WipingOutput : ByteArrayOutputStream() {
        fun wipe() { buf.fill(0); reset() }
    }
}
