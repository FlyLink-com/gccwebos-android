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

class EncryptedDeliveryStore(context: Context) : DeliveryStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "portal-delivery.bin"))
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
                val received = read() ?: throw PortalFailure(PortalFailure.Reason.STORAGE)
                require(received.accountId == record.accountId && received.sessionBinding == record.sessionBinding &&
                    received.resource == record.resource && received.operationKey == record.operationKey &&
                    received.url?.value == record.url?.value && received.handoff?.value == record.handoff?.value &&
                    received.pendingAck == record.pendingAck)
        } finally { plain.fill(0) }
    }
    override fun clear() = storage {
        file.delete()
        require(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists() && !File(file.baseFile.path + ".new").exists())
        Unit
    }
    private inline fun <T> storage(block: () -> T): T = try { block() }
        catch (_: Exception) { throw PortalFailure(PortalFailure.Reason.STORAGE) }
}
