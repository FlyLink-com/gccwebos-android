package com.gccwebos.android.portal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedLocalProfileStore(context: Context) : LocalProfileStore {
    private val directory = context.noBackupFilesDir
    private val file = AtomicFile(File(directory, "portal-profile.bin"))
    private val alias = "gccwebos.portal.profile.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun read(): CachedSubscription? = storage(StoreTarget.Profile, StoreOperation.Read) {
        val bytes = try { file.openRead().use { input ->
            require(input.channel.size() <= 131072)
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= 131072) {
                val count = input.read(buffer, 0, minOf(buffer.size, 131073 - output.size()))
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
        require(bytes.size in 29..131072)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD("gccwebos.profile.v1".toByteArray(Charsets.UTF_8))
        val plain = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
        try {
            val value = JSONObject(plain.toString(Charsets.UTF_8))
            require(value.keys().asSequence().toSet() == setOf("version", "binding", "body", "validUntil", "url", "observedStatus"))
            require(value.get("version") == 1)
            val binding = value.get("binding") as String; require(binding.matches(Regex("[a-f0-9]{64}")))
            val bytes = java.util.Base64.getDecoder().decode(value.get("body") as String)
            try {
                require(bytes.size in 1..com.gccwebos.android.vpn.SafeProxyConfig.MAX_BYTES)
                val observed = if (value.isNull("observedStatus")) null else LocalSubscriptionStatus.valueOf(value.get("observedStatus") as String)
                require(observed == null || observed in setOf(LocalSubscriptionStatus.EXPIRED, LocalSubscriptionStatus.INVALID_LINK))
                CachedSubscription(binding, bytes, if (value.isNull("validUntil")) null else java.time.Instant.parse(value.get("validUntil") as String),
                    if (value.isNull("url")) null else Secret(value.get("url") as String), observed)
            } finally { bytes.fill(0) }
        } finally { plain.fill(0) }
    }
    override fun save(record: CachedSubscription) = storage(StoreTarget.Profile, StoreOperation.Save) {
        val raw = record.copyBytes()
        val plain = try {
            require(raw.size in 1..com.gccwebos.android.vpn.SafeProxyConfig.MAX_BYTES)
            JSONObject().put("version", 1).put("binding", record.binding)
                .put("body", java.util.Base64.getEncoder().encodeToString(raw))
                .put("validUntil", record.validUntil?.toString() ?: JSONObject.NULL)
                .put("url", record.url?.value ?: JSONObject.NULL).put("observedStatus", record.observedStatus?.name ?: JSONObject.NULL).toString().toByteArray(Charsets.UTF_8)
        } finally { raw.fill(0) }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD("gccwebos.profile.v1".toByteArray(Charsets.UTF_8))
            val bytes = cipher.iv + cipher.doFinal(plain)
            require(bytes.size in 29..131072)
            val output = file.startWrite()
            try {
                output.write(bytes)
                output.fd.sync() // AtomicFile alone suppresses some sync/close errors on older API levels.
                file.finishWrite(output)
            } catch (failure: Exception) { file.failWrite(output); throw failure }
            syncDirectory()
            val received = read() ?: throw PortalFailure(PortalFailure.Reason.STORAGE)
            try {
                val expected = record.copyBytes(); val actual = received.copyBytes()
                try { require(received.binding == record.binding && received.validUntil == record.validUntil && received.url?.value == record.url?.value && received.observedStatus == record.observedStatus && expected.contentEquals(actual)) }
                finally { expected.fill(0); actual.fill(0) }
            } finally { received.close() }
        } finally { plain.fill(0) }
    }
    override fun clear() = storage(StoreTarget.Profile, StoreOperation.Clear) {
        file.delete()
        syncDirectory()
        require(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists() && !File(file.baseFile.path + ".new").exists())
        Unit
    }
    private fun syncDirectory() {
        val fd = android.system.Os.open(directory.path,
            android.system.OsConstants.O_RDONLY, 0)
        try {
            require(android.system.OsConstants.S_ISDIR(android.system.Os.fstat(fd).st_mode))
            android.system.Os.fsync(fd)
        } finally { android.system.Os.close(fd) }
    }
    private inline fun <T> storage(target: StoreTarget, operation: StoreOperation, block: () -> T): T = try { block() }
        catch (failure: Exception) {
            AndroidStorageDiagnostics.report(target, operation, failure)
            throw PortalFailure(PortalFailure.Reason.STORAGE)
        }
}
