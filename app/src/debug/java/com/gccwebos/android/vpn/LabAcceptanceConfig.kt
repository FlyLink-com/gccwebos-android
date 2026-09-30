package com.gccwebos.android.vpn

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.gccwebos.android.BuildConfig
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject

/** Acceptance-only single-use loopback fixture input, never a subscriber profile or Intent payload.
 * The instrumentation APK creates it in this disposable emulator app's private no-backup storage.
 */
internal object LabAcceptanceConfig {
    const val FILE = "native-tun-acceptance.json"
    fun consume(context: Context): ByteArray {
        check(BuildConfig.DEBUG && BuildConfig.NATIVE_VPN_LAB && BuildConfig.NATIVE_TUN_ACCEPTANCE)
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Emulator acceptance only" }
        val file = File(context.noBackupFilesDir, FILE)
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() in 1..4096)
        val raw = file.readBytes()
        try {
            check(file.delete()) { "Fixture input must be consumed once" }
            require(raw.size in 1..4096)
            val value = JSONObject(String(raw, Charsets.UTF_8))
            require(value.keys().asSequence().toSet() == setOf("port", "password", "deadlineElapsed"))
            val remaining = value.getLong("deadlineElapsed") - SystemClock.elapsedRealtime()
            require(remaining in 1..120_000)
            val port = value.getInt("port"); require(port in 1024..65535)
            val password = value.getString("password")
            require(password.matches(Regex("ephemeral-fixture-[0-9a-f]{32}")))
            val yamlJson = JSONObject().put("proxies", JSONArray().put(JSONObject()
                .put("name", "isolated-ephemeral-loopback").put("type", "ss")
                .put("server", "127.0.0.1").put("port", port).put("cipher", "aes-128-gcm")
                .put("password", password).put("udp", true))).toString().toByteArray()
            return try { SafeProxyConfig.project(yamlJson, 0) } finally { yamlJson.fill(0) }
        } finally { raw.fill(0); file.delete() }
    }
}
