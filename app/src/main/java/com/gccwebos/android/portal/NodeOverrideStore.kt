package com.gccwebos.android.portal

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * A local, per-node display tweak of an imported node: a user alias and/or "deleted" (hidden).
 * It is display-only. It never edits the subscription, never authorizes anything and never reaches
 * the generated connection config: a hidden node simply stops being offered or auto-picked.
 */
data class NodeOverride(val alias: String? = null, val hidden: Boolean = false) {
    val isDefault: Boolean get() = alias == null && !hidden
}

/**
 * Scope -> one-way node key -> override. The scope and key are the same ones the remembered node choice
 * uses (`account` / `entry:<id>`, [NodeChoiceKey.persisted]), so a refreshed subscription that keeps a
 * node's identity keeps its alias and its deletion. Every failure reads as "no overrides" (fail open for
 * display only, never for admission).
 */
interface NodeOverrideStore {
    fun read(): Map<String, Map<String, NodeOverride>>
    /** Stores [value]; a default value removes the entry. */
    fun put(scope: String, key: String, value: NodeOverride)
    fun removeScope(scope: String)
    fun clear()
}

class MemoryNodeOverrideStore : NodeOverrideStore {
    private val values = LinkedHashMap<String, LinkedHashMap<String, NodeOverride>>()
    @Synchronized override fun read(): Map<String, Map<String, NodeOverride>> = values.mapValues { LinkedHashMap(it.value) }
    @Synchronized override fun put(scope: String, key: String, value: NodeOverride) {
        val map = values.getOrPut(scope) { LinkedHashMap() }
        if (value.isDefault) map.remove(key) else map[key] = value
        if (map.isEmpty()) values.remove(scope)
    }
    @Synchronized override fun removeScope(scope: String) { values.remove(scope) }
    @Synchronized override fun clear() { values.clear() }
}

/** AES-GCM sidecar in no-backup storage, format {"v":1,"nodes":[{"s":scope,"k":key,"a":alias?,"h":bool}]}. */
class EncryptedNodeOverrideStore internal constructor(
    directory: File,
    private val keyProvider: (create: Boolean) -> SecretKey,
) : NodeOverrideStore {
    constructor(context: Context) : this(context.noBackupFilesDir, ::nodeOverrideKey)

    private val file = AtomicFile(File(directory, FILE_NAME))

    @Synchronized override fun read(): Map<String, Map<String, NodeOverride>> = load()
    @Synchronized override fun put(scope: String, key: String, value: NodeOverride) {
        val map = LinkedHashMap(load().mapValues { LinkedHashMap(it.value) })
        val inner = map.getOrPut(scope) { LinkedHashMap() }
        if (value.isDefault) inner.remove(key) else inner[key] = value
        if (inner.isEmpty()) map.remove(scope)
        write(map)
    }
    @Synchronized override fun removeScope(scope: String) {
        val map = LinkedHashMap(load().mapValues { LinkedHashMap(it.value) })
        if (map.remove(scope) != null) write(map)
    }
    @Synchronized override fun clear() { file.delete() }

    private fun load(): Map<String, Map<String, NodeOverride>> {
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
                val list = json.getJSONArray("nodes")
                require(list.length() <= MAX_ENTRIES)
                val out = LinkedHashMap<String, LinkedHashMap<String, NodeOverride>>()
                for (i in 0 until list.length()) {
                    val item = list.getJSONObject(i)
                    val value = NodeOverride(if (item.has("a")) item.getString("a") else null, item.optBoolean("h", false))
                    if (!value.isDefault) out.getOrPut(item.getString("s")) { LinkedHashMap() }[item.getString("k")] = value
                }
                out
            } finally { plain.fill(0) }
        } catch (_: Exception) { emptyMap() } finally { encrypted.fill(0) }
    }

    private fun write(map: Map<String, Map<String, NodeOverride>>) {
        val array = JSONArray()
        var count = 0
        map.forEach { (scope, inner) -> inner.forEach { (key, value) ->
            count++
            array.put(JSONObject().put("s", scope).put("k", key).put("h", value.hidden)
                .also { entry -> value.alias?.let { entry.put("a", it) } })
        } }
        require(count <= MAX_ENTRIES)
        val plain = JSONObject().put("v", 1).put("nodes", array).toString().toByteArray(Charsets.UTF_8)
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
        const val FILE_NAME = "node-override.bin"
        const val KEY_ALIAS = "gccwebos.node.override.v1"
        const val AAD = "gccwebos.node.override.v1"
        const val MAX_ENTRIES = 512
        private const val MAX_BYTES = 128 * 1024
    }
}

private fun nodeOverrideKey(create: Boolean): SecretKey {
    val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(EncryptedNodeOverrideStore.KEY_ALIAS, null) as? SecretKey)?.let { return it }
    check(create)
    return javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(android.security.keystore.KeyGenParameterSpec.Builder(EncryptedNodeOverrideStore.KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build())
    }.generateKey()
}
