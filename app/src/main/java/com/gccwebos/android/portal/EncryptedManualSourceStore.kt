package com.gccwebos.android.portal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Additive sidecar only: portal-profile.bin v1 and its Keystore key/AAD are never touched. */
class EncryptedManualSourceStore internal constructor(
    private val directory: File,
    private val keyProvider: (create: Boolean) -> SecretKey,
    private val writeObserver: ((ManualSourceWritePhase) -> Unit)? = null
) : ManualSourceStore {
    constructor(context: Context) : this(context.noBackupFilesDir, ::manualSourceKey)

    private val file = AtomicFile(File(directory, FILE_NAME))

    @Synchronized override fun read(): ManualSourceRecord? = storage {
        val encrypted = try {
            file.openRead().use { input ->
                require(input.channel.size() in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES.toLong())
                val buffer = ByteArray(8192)
                val output = ByteArrayOutputStream()
                try {
                    while (output.size() <= MAX_CIPHERTEXT_BYTES) {
                        val count = input.read(buffer, 0, minOf(buffer.size, MAX_CIPHERTEXT_BYTES + 1 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } finally { buffer.fill(0) }
            }
        } catch (_: FileNotFoundException) {
            require(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists() &&
                !File(file.baseFile.path + ".new").exists())
            return@storage null
        }
        try {
            require(encrypted.size in MIN_CIPHERTEXT_BYTES.toInt()..MAX_CIPHERTEXT_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(false), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
            cipher.updateAAD(AAD.toByteArray(Charsets.US_ASCII))
            val plain = cipher.doFinal(encrypted, 12, encrypted.size - 12)
            try { ManualSourceCodec.decode(plain) } finally { plain.fill(0) }
        } finally { encrypted.fill(0) }
    }

    @Synchronized override fun save(record: ManualSourceRecord) = storage {
        val plain = ManualSourceCodec.encode(record)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keyProvider(true))
            cipher.updateAAD(AAD.toByteArray(Charsets.US_ASCII))
            val ciphertext = cipher.doFinal(plain)
            val encrypted = cipher.iv + ciphertext
            ciphertext.fill(0)
            try {
                require(cipher.iv.size == 12 && encrypted.size in MIN_CIPHERTEXT_BYTES.toInt()..MAX_CIPHERTEXT_BYTES)
                val output = file.startWrite()
                try {
                    output.write(encrypted)
                    writeObserver?.invoke(ManualSourceWritePhase.BeforeSync)
                    output.fd.sync()
                    writeObserver?.invoke(ManualSourceWritePhase.BeforeFinish)
                    file.finishWrite(output)
                } catch (failure: Exception) { file.failWrite(output); throw failure }
                writeObserver?.invoke(ManualSourceWritePhase.AfterFinish)
                syncDirectory()
                writeObserver?.invoke(ManualSourceWritePhase.BeforeReadback)
                val received = read() ?: throw IllegalStateException()
                try { require(record.sameEnvelope(received)) } finally { received.close() }
            } finally { encrypted.fill(0) }
        } finally { plain.fill(0) }
    }

    private fun syncDirectory() {
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try {
            require(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode))
            Os.fsync(descriptor)
        } finally { Os.close(descriptor) }
    }

    private inline fun <T> storage(operation: () -> T): T = try { operation() }
        catch (_: Exception) { throw PortalFailure(PortalFailure.Reason.STORAGE) }

    internal companion object {
        const val FILE_NAME = "manual-source.bin"
        const val KEY_ALIAS = "gccwebos.manual.source.v1"
        const val AAD = "gccwebos.manual.source.v1"
        private const val MIN_CIPHERTEXT_BYTES = 29L
        private const val MAX_CIPHERTEXT_BYTES = ManualSourceCodec.MAX_ENCODED_BYTES + 28
    }
}

/** Deterministic test fault points; absent from the public production constructor. */
internal enum class ManualSourceWritePhase { BeforeSync, BeforeFinish, AfterFinish, BeforeReadback }

private fun manualSourceKey(create: Boolean): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(EncryptedManualSourceStore.KEY_ALIAS, null) as? SecretKey)?.let { return it }
    check(create)
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(EncryptedManualSourceStore.KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
    }.generateKey()
}
