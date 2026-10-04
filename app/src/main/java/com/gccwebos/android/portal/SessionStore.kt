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

class EncryptedSessionStore(context: Context) : SessionStore {
    private val directory = context.noBackupFilesDir
    private val file = AtomicFile(File(directory, "portal-session.bin"))
    private val alias = "gccwebos.portal.session.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun read(): SavedSession? = storage(StoreTarget.Session, StoreOperation.Read) {
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
        cipher.updateAAD("gccwebos.session.v1".toByteArray(Charsets.UTF_8))
        val plain = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
        try {
            val value = JSONObject(plain.toString(Charsets.UTF_8))
            require(value.keys().asSequence().toSet() == setOf("version", "token", "account", "expires", "remaining", "savedAt", "elapsed"))
            require(value.get("version") == 1)
            fun text(key: String) = value.get(key) as String
            fun number(key: String): Long { val n = value.get(key); require(n is Int || n is Long); return (n as Number).toLong() }
            SavedSession(Secret(text("token")), text("account"), java.time.Instant.parse(text("expires")),
                number("remaining"), java.time.Instant.parse(text("savedAt")), number("elapsed"))
        } finally { plain.fill(0) }
    }
    override fun save(record: SavedSession) = storage(StoreTarget.Session, StoreOperation.Save) {
        val plain = JSONObject().put("version", 1).put("token", record.token.value).put("account", record.accountId)
            .put("expires", record.expiresAt.toString()).put("remaining", record.remainingMillis)
            .put("savedAt", record.savedAt.toString()).put("elapsed", record.savedElapsed)
            .toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD("gccwebos.session.v1".toByteArray(Charsets.UTF_8))
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
            require(received.accountId == record.accountId && received.token.value == record.token.value &&
                received.expiresAt == record.expiresAt && received.remainingMillis == record.remainingMillis &&
                received.savedAt == record.savedAt && received.savedElapsed == record.savedElapsed)
        } finally { plain.fill(0) }
    }
    override fun clear() = storage(StoreTarget.Session, StoreOperation.Clear) {
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
