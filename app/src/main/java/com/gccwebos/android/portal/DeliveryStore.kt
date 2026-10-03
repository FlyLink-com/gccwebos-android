package com.gccwebos.android.portal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedDeliveryStore(context: Context) : DeliveryStore {
    private val directory = context.noBackupFilesDir
    private val file = AtomicFile(File(directory, "portal-delivery.bin"))
    private val recoveryFile = AtomicFile(File(directory, "portal-legacy-recovery.bin"))
    private val recoveryAad = "gccwebos.portal.legacy-recovery.v1".toByteArray(Charsets.UTF_8)
    private val alias = "gccwebos.portal.delivery.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun read(): DeliveryRecord? = storage {
        val bytes = try { file.openRead().use { input ->
            require(input.channel.size() <= 65536)
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= 65536) {
                val count = input.read(buffer, 0, minOf(buffer.size, 65537 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } }
            catch (_: java.io.FileNotFoundException) {
                if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists())
                    throw PortalFailure(PortalFailure.Reason.STORAGE)
                return@storage null
            }
        require(bytes.size in 29..65536)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val plain = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
        try {
            val value = JSONObject(plain.toString(Charsets.UTF_8))
            DeliveryRecord(value.getString("account"), value.getString("sessionBinding"), value.getString("resource"),
                value.getString("key"), if(value.isNull("url")) null else Secret(value.getString("url")),
                if(value.isNull("handoff")) null else Secret(value.getString("handoff")), value.getBoolean("pending"))
        } finally { plain.fill(0) }
    }
    override fun save(record: DeliveryRecord) = storage {
        val plain = JSONObject().put("account", record.accountId).put("sessionBinding", record.sessionBinding)
            .put("resource", record.resource).put("key", record.operationKey)
            .put("url", record.url?.value ?: JSONObject.NULL).put("handoff", record.handoff?.value ?: JSONObject.NULL)
            .put("pending", record.pendingAck).toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
            val bytes = cipher.iv + cipher.doFinal(plain)
            require(bytes.size in 29..65536)
            val output = file.startWrite()
            try {
                output.write(bytes)
                output.fd.sync() // AtomicFile alone suppresses some sync/close errors on older API levels.
                file.finishWrite(output)
            } catch (failure: Exception) { file.failWrite(output); throw failure }
            syncDirectory()
            val received = read() ?: throw PortalFailure(PortalFailure.Reason.STORAGE)
            require(received.accountId == record.accountId && received.sessionBinding == record.sessionBinding &&
                received.resource == record.resource && received.operationKey == record.operationKey &&
                received.url?.value == record.url?.value && received.handoff?.value == record.handoff?.value &&
                received.pendingAck == record.pendingAck)
        } finally { plain.fill(0) }
    }
    override fun readLegacyRecovery(): LegacyRecoveryJournal? = storage {
        val bytes = try { recoveryFile.openRead().use { input ->
            require(input.channel.size() <= 65536)
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= 65536) {
                val count = input.read(buffer, 0, minOf(buffer.size, 65537 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } } catch (_: java.io.FileNotFoundException) {
            if (recoveryFile.baseFile.exists() || File(recoveryFile.baseFile.path + ".bak").exists() ||
                File(recoveryFile.baseFile.path + ".new").exists())
                throw PortalFailure(PortalFailure.Reason.STORAGE)
            return@storage null
        }
        require(bytes.size in 29..65536)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(recoveryAad)
        val plain = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plain)).toString()
            // Only app-generated fixed-schema JSON is stored; authenticated ciphertext is required first.
            val tokener = JSONTokener(text)
            val value = tokener.nextValue() as JSONObject
            require(tokener.nextClean() == '\u0000')
            decodeRecovery(value)
        }
        finally { plain.fill(0) }
    }
    override fun saveLegacyRecovery(journal: LegacyRecoveryJournal) = storage {
        validateRecovery(journal)
        val plain = JSONObject().put("version", 1).put("model", "LEGACY")
            .put("original", journal.original?.let(::encodeRecord) ?: JSONObject.NULL)
            .put("candidate", encodeRecord(journal.candidate)).put("phase", journal.phase.name)
            .toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(recoveryAad)
            val bytes = cipher.iv + cipher.doFinal(plain)
            require(bytes.size in 29..65536)
            val output = recoveryFile.startWrite()
            try {
                output.write(bytes); output.fd.sync(); recoveryFile.finishWrite(output)
            } catch (failure: Exception) { recoveryFile.failWrite(output); throw failure }
            syncDirectory()
            val received = readLegacyRecovery() ?: throw PortalFailure(PortalFailure.Reason.STORAGE)
            require(received.phase == journal.phase && sameDeliveryRecord(received.original, journal.original) &&
                sameDeliveryRecord(received.candidate, journal.candidate))
        } finally { plain.fill(0) }
    }
    override fun clearLegacyRecovery() = storage {
        deleteStoredFile(recoveryFile)
        syncDirectory()
        requireDeleted(recoveryFile)
        Unit
    }
    override fun clear() = storage {
        // Attempt both removals even if one fails, then verify the complete unlink.
        var failure: Exception? = null
        try { deleteStoredFile(file) } catch (error: Exception) { failure = error }
        try { deleteStoredFile(recoveryFile) } catch (error: Exception) { if (failure == null) failure = error }
        syncDirectory()
        requireDeleted(file); requireDeleted(recoveryFile)
        failure?.let { throw it }
        Unit
    }
    private fun deleteStoredFile(target: AtomicFile) { target.delete() }
    private fun requireDeleted(target: AtomicFile) {
        require(!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists() &&
            !File(target.baseFile.path + ".new").exists())
    }
    private fun encodeRecord(record: DeliveryRecord): JSONObject = JSONObject()
        .put("account", record.accountId).put("sessionBinding", record.sessionBinding)
        .put("resource", record.resource).put("key", record.operationKey)
        .put("url", record.url?.value ?: JSONObject.NULL).put("handoff", record.handoff?.value ?: JSONObject.NULL)
        .put("pending", record.pendingAck)
    private fun decodeRecord(value: JSONObject): DeliveryRecord {
        require(value.keys().asSequence().toSet() == setOf("account", "sessionBinding", "resource", "key", "url", "handoff", "pending"))
        return DeliveryRecord(value.get("account") as String, value.get("sessionBinding") as String,
            value.get("resource") as String, value.get("key") as String,
            if (value.isNull("url")) null else Secret(value.get("url") as String),
            if (value.isNull("handoff")) null else Secret(value.get("handoff") as String), value.get("pending") as Boolean)
    }
    private fun decodeRecovery(value: JSONObject): LegacyRecoveryJournal {
        require(value.keys().asSequence().toSet() == setOf("version", "model", "original", "candidate", "phase"))
        require(value.get("version") == 1 && value.get("model") == "LEGACY")
        val journal = LegacyRecoveryJournal(
            if (value.isNull("original")) null else decodeRecord(value.get("original") as JSONObject),
            decodeRecord(value.get("candidate") as JSONObject),
            LegacyRecoveryPhase.valueOf(value.get("phase") as String))
        validateRecovery(journal)
        return journal
    }
    private fun validateRecovery(journal: LegacyRecoveryJournal) {
        fun bounded(value: String, maximum: Int) {
            require(value.length in 1..maximum && value.isNotBlank() && value.none { it.isISOControl() })
        }
        fun record(value: DeliveryRecord) {
            bounded(value.accountId, 128)
            require(Regex("[a-f0-9]{64}").matches(value.sessionBinding))
            require(Regex("resource_[a-f0-9]{64}").matches(value.resource))
            require(Regex("[A-Za-z0-9][A-Za-z0-9._~-]{15,127}").matches(value.operationKey))
            value.url?.let { bounded(it.value, 8192) }
            value.handoff?.let { bounded(it.value, 512) }
        }
        journal.original?.let(::record); record(journal.candidate)
        require(journal.original == null || journal.original.accountId == journal.candidate.accountId)
        val candidate = journal.candidate
        when (journal.phase) {
            LegacyRecoveryPhase.ATTEMPT -> require(candidate.url == null && candidate.handoff == null && !candidate.pendingAck)
            LegacyRecoveryPhase.REVEALED -> require(candidate.url != null && candidate.handoff != null && candidate.pendingAck)
            LegacyRecoveryPhase.ACKNOWLEDGED, LegacyRecoveryPhase.PROFILE_COMMITTING ->
                require(candidate.url != null && candidate.handoff != null && !candidate.pendingAck)
        }
        candidate.handoff?.let { require(Regex("legacy_setup_[a-f0-9]{64}").matches(it.value)) }
    }
    private fun syncDirectory() {
        val fd = android.system.Os.open(directory.path,
            android.system.OsConstants.O_RDONLY, 0)
        try {
            require(android.system.OsConstants.S_ISDIR(android.system.Os.fstat(fd).st_mode))
            android.system.Os.fsync(fd)
        } finally { android.system.Os.close(fd) }
    }
    private inline fun <T> storage(block: () -> T): T = try { block() }
        catch (_: Exception) { throw PortalFailure(PortalFailure.Reason.STORAGE) }
}
