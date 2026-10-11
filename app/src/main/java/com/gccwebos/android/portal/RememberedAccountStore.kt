package com.gccwebos.android.portal

import android.content.Context
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.File
import java.io.FileNotFoundException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 「记住账号」: remembers ONLY the account identifier the user typed (an email or user name) so the login form can
 * prefill it. It never holds a password, a token, a session, a challenge answer or any other credential, and it is
 * never read from or written to the clipboard. Every failure reads as "nothing remembered".
 *
 * Logout keeps it (退出登录 clears the account subscription only). Unticking 「记住账号」 clears it.
 */
interface RememberedAccountStore {
    fun read(): String?
    fun put(identifier: String)
    fun clear()
}

internal object RememberedAccountRules {
    const val MAX_LENGTH = 254
    /** Trimmed, single line, no control characters, bounded; anything else is not remembered. */
    fun normalize(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty() || value.length > MAX_LENGTH) return null
        if (value.any { it.isISOControl() || it == '\u2028' || it == '\u2029' }) return null
        return value
    }
}

class MemoryRememberedAccountStore(initial: String? = null) : RememberedAccountStore {
    private var value: String? = initial
    @Synchronized override fun read(): String? = value
    @Synchronized override fun put(identifier: String) { RememberedAccountRules.normalize(identifier)?.let { value = it } }
    @Synchronized override fun clear() { value = null }
}

/**
 * AES-GCM sidecar in no-backup storage (same approach as [EncryptedNodeChoiceStore]): the Keystore key never leaves
 * the device and the file is excluded from backups. Format v1 is the UTF-8 identifier itself under AAD [AAD].
 */
class EncryptedRememberedAccountStore internal constructor(
    private val directory: File,
    private val keyProvider: (create: Boolean) -> SecretKey,
) : RememberedAccountStore {
    constructor(context: Context) : this(context.noBackupFilesDir, ::rememberedAccountKey)

    private val file = File(directory, FILE_NAME)

    @Synchronized override fun read(): String? {
        val encrypted = try { file.readBytes() } catch (_: FileNotFoundException) { return null } catch (_: Exception) { return null }
        return try {
            require(encrypted.size in 29..MAX_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(false), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
            cipher.updateAAD(AAD.toByteArray(Charsets.US_ASCII))
            val plain = cipher.doFinal(encrypted, 12, encrypted.size - 12)
            try { RememberedAccountRules.normalize(String(plain, Charsets.UTF_8)) } finally { plain.fill(0) }
        } catch (_: Exception) { null } finally { encrypted.fill(0) }
    }

    @Synchronized override fun put(identifier: String) {
        val value = RememberedAccountRules.normalize(identifier) ?: return
        val plain = value.toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keyProvider(true))
            cipher.updateAAD(AAD.toByteArray(Charsets.US_ASCII))
            val encrypted = cipher.iv + cipher.doFinal(plain)
            // Write a sibling temp file, sync it, then move it over the old one in one step.
            val temp = File(directory, "$FILE_NAME.tmp")
            FileOutputStream(temp).use { it.write(encrypted); it.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            // Best effort: failing to remember an account never blocks sign-in.
        } finally { plain.fill(0) }
    }

    @Synchronized override fun clear() { try { file.delete(); File(directory, "$FILE_NAME.tmp").delete() } catch (_: Exception) {} }

    internal companion object {
        const val FILE_NAME = "remembered-account.bin"
        const val KEY_ALIAS = "gccwebos.remembered.account.v1"
        const val AAD = "gccwebos.remembered.account.v1"
        private const val MAX_BYTES = 2 * 1024
    }
}

private fun rememberedAccountKey(create: Boolean): SecretKey {
    val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(EncryptedRememberedAccountStore.KEY_ALIAS, null) as? SecretKey)?.let { return it }
    check(create)
    return javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(android.security.keystore.KeyGenParameterSpec.Builder(EncryptedRememberedAccountStore.KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build())
    }.generateKey()
}
