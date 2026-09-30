package com.gccwebos.android.commerce

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

/** Account-hashed no-backup storage. Failure blocks mutations instead of issuing a fresh key. */
internal class FileCommerceRecovery(context: Context) : CommerceRecovery {
    private val directory = context.noBackupFilesDir
    private fun file(binding: String, kind: String): AtomicFile {
        require(Regex("[a-f0-9]{64}").matches(binding) && kind in setOf("purchase", "recharge"))
        return AtomicFile(File(directory, "commerce-$binding-$kind.json"))
    }
    override fun read(binding: String, kind: String): PendingCommerce? {
        val file = file(binding, kind)
        val bytes = try { file.openRead().use { require(it.channel.size() <= 4096); it.readBytes() } }
            catch (_: java.io.FileNotFoundException) { if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw CommerceFailure("storage"); return null }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        return PendingCommerce(json.getString("kind"), if (json.isNull("model")) null else CommerceModel.entries.single { it.wireValue == json.getString("model") },
            json.getString("inputRef"), json.getString("key"), if(json.isNull("resultRef")) null else json.getString("resultRef")).also { require(it.kind == kind) }
    }
    override fun save(binding: String, value: PendingCommerce) {
        val file = file(binding, value.kind)
        val bytes = JSONObject().put("kind", value.kind).put("model", value.model?.wireValue ?: JSONObject.NULL)
            .put("inputRef", value.inputRef).put("key", value.key).put("resultRef", value.resultRef ?: JSONObject.NULL).toString().toByteArray()
        val output = file.startWrite()
        try { output.write(bytes); output.fd.sync(); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
        syncDirectory()
        require(read(binding, value.kind) == value)
    }
    override fun clear(binding: String, kind: String) {
        val file = file(binding, kind); file.delete(); syncDirectory()
        require(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists() && !File(file.baseFile.path + ".new").exists())
    }
    private fun syncDirectory() {
        val fd = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
    }
}
