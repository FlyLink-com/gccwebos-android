package com.gccwebos.android.manualimport

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

internal enum class ManualImportInputKind { Link, Document, Scan }
internal enum class ManualImportFailure(val caption: String) {
    Cancelled("已取消导入"), CameraPermission("未允许相机权限"), CameraUnavailable("相机不可用"),
    DocumentUnavailable("无法读取配置文件"), TooLarge("配置超过 64 KiB"), InvalidInput("配置内容无效"),
    LaunchUnavailable("无法打开导入入口")
}
internal class ManualInputException(val failure: ManualImportFailure) : Exception(failure.name)

/** Bounded byte ownership only. Authority, URL allowlist and full YAML validation remain downstream. */
internal object ManualInputBytes {
    const val Limit = 64 * 1024
    fun read(stream: InputStream): ByteArray {
        val buffer = ByteArray(Limit + 1)
        try {
            var count = 0
            while (count < buffer.size) {
                val read = stream.read(buffer, count, buffer.size - count)
                if (read < 0) break
                if (read == 0) {
                    val one = stream.read()
                    if (one < 0) break
                    buffer[count++] = one.toByte()
                } else count += read
            }
            if (count > Limit) throw ManualInputException(ManualImportFailure.TooLarge)
            if (count == 0) throw ManualInputException(ManualImportFailure.InvalidInput)
            val chars = CharBuffer.allocate(count)
            try {
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                val decoded = decoder.decode(ByteBuffer.wrap(buffer, 0, count), chars, true)
                if (decoded.isError || decoded.isOverflow || decoder.flush(chars).isError)
                    throw ManualInputException(ManualImportFailure.InvalidInput)
            } finally { chars.array().fill('\u0000') }
            return buffer.copyOf(count)
        } finally { buffer.fill(0) }
    }

    fun fromText(text: String): ByteArray {
        if (text.isEmpty()) throw ManualInputException(ManualImportFailure.InvalidInput)
        if (text.length > Limit) throw ManualInputException(ManualImportFailure.TooLarge)
        val output = ByteBuffer.allocate(Limit + 1)
        try {
            val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val result = encoder.encode(CharBuffer.wrap(text), output, true)
            if (result.isOverflow || output.position() > Limit) throw ManualInputException(ManualImportFailure.TooLarge)
            if (result.isError || encoder.flush(output).isError) throw ManualInputException(ManualImportFailure.InvalidInput)
            return output.array().copyOf(output.position())
        } finally { output.array().fill(0) }
    }
}

/** Process-only one-shot handoff. Neither Intent nor SavedState contains decoded text or bytes. */
internal object ManualScanMailbox {
    private val entries = mutableMapOf<String, ByteArray?>()
    @Synchronized fun open(): String = UUID.randomUUID().toString().also { entries[it] = null }
    @Synchronized fun exists(receipt: String): Boolean = entries.containsKey(receipt)
    @Synchronized fun publish(receipt: String, bytes: ByteArray): Boolean {
        if (!entries.containsKey(receipt) || entries[receipt] != null) { bytes.fill(0); return false }
        entries[receipt] = bytes
        return true
    }
    @Synchronized fun take(receipt: String): ByteArray? = entries.remove(receipt)
    @Synchronized fun cancel(receipt: String) { entries.remove(receipt)?.fill(0) }
}
