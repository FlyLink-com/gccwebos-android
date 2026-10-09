package com.gccwebos.android.portal

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * Remembers which node the user chose per configuration, so the choice survives an app upgrade or
 * process death. It stores only a one-way node key (see [NodeChoiceKey]) per configuration scope:
 * no handle, no URL, no credential and no node name. It never authorizes anything: a restored
 * choice is only an ordinary UI selection that must still be matched against the freshly validated
 * registry, and full connection admission runs unchanged afterwards.
 *
 * Every failure is swallowed by the caller and reads as "nothing remembered" (fail closed).
 */
interface NodeChoiceStore {
    /** Scope -> persisted node key. Empty when nothing is stored. */
    fun read(): Map<String, String>
    fun put(scope: String, key: String)
    fun remove(scope: String)
    fun clear()
}

class MemoryNodeChoiceStore : NodeChoiceStore {
    private val values = LinkedHashMap<String, String>()
    @Synchronized override fun read(): Map<String, String> = LinkedHashMap(values)
    @Synchronized override fun put(scope: String, key: String) { values.remove(scope); values[scope] = key }
    @Synchronized override fun remove(scope: String) { values.remove(scope) }
    @Synchronized override fun clear() { values.clear() }
}

internal object NodeChoiceKey {
    private const val DOMAIN = "gccwebos.node-choice.v1"
    /** Derives the persisted key from the process-only selection key; the two are never equal. */
    fun persisted(selectionKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(DOMAIN.toByteArray(Charsets.UTF_8)); digest.update(0)
        digest.update(selectionKey.toByteArray(Charsets.UTF_8))
        val bytes = digest.digest()
        return try { Base64.getEncoder().encodeToString(bytes) } finally { bytes.fill(0) }
    }
}

/**
 * AES-GCM sidecar in no-backup storage. Format v1 is the JSON object
 * {"v":1,"choices":[{"s":scope,"k":key},...]} (oldest first, at most [MAX_SCOPES]).
 * An older app build ignores this file entirely, so a downgrade only loses the remembered choice.
 */
class EncryptedNodeChoiceStore internal constructor(
    private val directory: File,
    private val keyProvider: (create: Boolean) -> SecretKey,
) : NodeChoiceStore {
    constructor(context: Context) : this(context.noBackupFilesDir, ::nodeChoiceKey)

    private val file = AtomicFile(File(directory, FILE_NAME))

    @Synchronized override fun read(): Map<String, String> = load()
    @Synchronized override fun put(scope: String, key: String) {
        val map = LinkedHashMap(load()); map.remove(scope); map[scope] = key
        while (map.size > MAX_SCOPES) map.remove(map.keys.first())
        write(map)
    }
    @Synchronized override fun remove(scope: String) {
        val map = LinkedHashMap(load())
        if (map.remove(scope) != null) write(map)
    }
    @Synchronized override fun clear() { file.delete() }

    private fun load(): Map<String, String> {
        val encrypted = try { file.openRead().use { it.readBytes() } } catch (_: FileNotFoundException) { return emptyMap() }
        return try {
            require(encrypted.size in 29..MAX_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(false), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
            cipher.updateAAD(AAD.toByteArray(Charsets.US_ASCII))
            val plain = cipher.doFinal(encrypted, 12, encrypted.size - 12)
            try {
                val json = JSONObject(String(plain, Charsets.UTF_8))
                require(json.getInt("v") == 1)
                val list = json.getJSONArray("choices")
                require(list.length() <= MAX_SCOPES)
                val out = LinkedHashMap<String, String>()
                for (i in 0 until list.length()) {
                    val item = list.getJSONObject(i)
                    out[item.getString("s")] = item.getString("k")
                }
                out
            } finally { plain.fill(0) }
        } catch (_: Exception) { emptyMap() } finally { encrypted.fill(0) }
    }

    private fun write(map: Map<String, String>) {
        val array = org.json.JSONArray()
        map.forEach { (scope, key) -> array.put(JSONObject().put("s", scope).put("k", key)) }
        val plain = JSONObject().put("v", 1).put("choices", array).toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keyProvider(true))
            cipher.updateAAD(AAD.toByteArray(Charsets.US_ASCII))
            val encrypted = cipher.iv + cipher.doFinal(plain)
            require(encrypted.size <= MAX_BYTES)
            val output = file.startWrite()
            try { output.write(encrypted); output.fd.sync(); file.finishWrite(output) }
            catch (failure: Exception) { file.failWrite(output); throw failure }
        } finally { plain.fill(0) }
    }

    internal companion object {
        const val FILE_NAME = "node-choice.bin"
        const val KEY_ALIAS = "gccwebos.node.choice.v1"
        const val AAD = "gccwebos.node.choice.v1"
        const val MAX_SCOPES = 32
        private const val MAX_BYTES = 16 * 1024
    }
}

private fun nodeChoiceKey(create: Boolean): SecretKey {
    val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(EncryptedNodeChoiceStore.KEY_ALIAS, null) as? SecretKey)?.let { return it }
    check(create)
    return javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(android.security.keystore.KeyGenParameterSpec.Builder(EncryptedNodeChoiceStore.KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build())
    }.generateKey()
}
