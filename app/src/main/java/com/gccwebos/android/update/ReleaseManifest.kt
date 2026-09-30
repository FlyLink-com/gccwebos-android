package com.gccwebos.android.update

import java.time.Instant

/** Public release metadata only. Schema 1 has no package name, minSdk or signing identity. */
sealed interface ReleaseManifest {
    data object Unavailable : ReleaseManifest
    data class Available(
        val versionCode: Long,
        val versionName: String,
        val minSupportedVersionCode: Long,
        val publishedAt: Instant,
        val downloadPath: String,
        val sha256: String,
        val notesZh: String,
        val notesEn: String,
    ) : ReleaseManifest {
        val downloadUrl: String get() = OfficialReleases.ORIGIN + downloadPath
    }
}

object OfficialReleases {
    // Canonical origin in gccwebos-site index.html at 1838d23e83d052c1013c36ddb568d30f5f9e8182.
    const val ORIGIN = "https://gccwebos.com"
    const val MANIFEST_URL = "$ORIGIN/android/releases/stable.json"
    const val RELEASE_APPLICATION_ID = "com.gccwebos.android"
    const val MAX_BYTES = 32 * 1024
    private val apkPath = Regex("/android/releases/[A-Za-z0-9][A-Za-z0-9._-]*\\.apk")
    fun isDownloadPath(path: String): Boolean = apkPath.matches(path) && !path.contains("..")
}

class InvalidReleaseManifest : IllegalArgumentException("Invalid official release manifest")

object ReleaseManifestParser {
    fun parse(text: String): ReleaseManifest {
        try {
            require(text.toByteArray(Charsets.UTF_8).size <= OfficialReleases.MAX_BYTES)
            val root = StrictReleaseJson(text).parse()
            require(root["schemaVersion"] == 1L && root["platform"] == "android" && root["channel"] == "stable")
            val common = setOf("schemaVersion", "platform", "channel", "status")
            if (root["status"] == "unavailable") {
                require(root.keys == common)
                return ReleaseManifest.Unavailable
            }
            require(root["status"] == "available")
            require(root.keys == common + setOf("versionCode", "versionName", "minSupportedVersionCode", "publishedAt", "downloadUrl", "sha256", "notes"))
            fun string(key: String): String = root[key] as? String ?: throw InvalidReleaseManifest()
            val code = root["versionCode"] as? Long ?: throw InvalidReleaseManifest()
            val minimum = root["minSupportedVersionCode"] as? Long ?: throw InvalidReleaseManifest()
            // Android versionCode's publishing limit; never compare display versionName strings.
            require(code in 1..2_100_000_000L && minimum in 1..code)
            val name = string("versionName")
            require(name.length <= 64 && Regex("[0-9]+(?:\\.[0-9]+){1,3}").matches(name))
            val published = string("publishedAt")
            require(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z").matches(published))
            val instant = Instant.parse(published)
            require(instant.toString() == published) // Reject normalization of impossible dates/leap seconds.
            val path = string("downloadUrl")
            require(OfficialReleases.isDownloadPath(path))
            val sha = string("sha256")
            require(Regex("[a-f0-9]{64}").matches(sha))
            val notes = root["notes"] as? Map<*, *> ?: throw InvalidReleaseManifest()
            require(notes.keys == setOf("zh", "en"))
            val zh = notes["zh"] as? String ?: throw InvalidReleaseManifest()
            val en = notes["en"] as? String ?: throw InvalidReleaseManifest()
            require(zh.isNotBlank() && en.isNotBlank() && zh.length <= 8000 && en.length <= 8000)
            return ReleaseManifest.Available(code, name, minimum, instant, path, sha, zh, en)
        } catch (_: Exception) {
            throw InvalidReleaseManifest()
        }
    }
}

/** Intentionally narrow schema grammar: objects, strings and unsigned integer literals only.
 * Rejects duplicate keys, coercions, comments, trailing tokens/commas, arrays and excessive depth.
 * This avoids Android org.json's permissive non-JSON syntax and JVM/Android parser differences.
 */
private class StrictReleaseJson(private val input: String) {
    private var pos = 0
    fun parse(): Map<String, Any> {
        val value = obj(0)
        whitespace()
        require(pos == input.length)
        return value
    }
    private fun whitespace() { while (pos < input.length && input[pos] in " \t\r\n") pos++ }
    private fun take(c: Char): Boolean { whitespace(); return if (pos < input.length && input[pos] == c) { pos++; true } else false }
    private fun obj(depth: Int): Map<String, Any> {
        require(depth < 3 && take('{'))
        val values = linkedMapOf<String, Any>()
        if (take('}')) return values
        do {
            whitespace()
            val key = string()
            require(!values.containsKey(key) && take(':'))
            whitespace()
            values[key] = when (input.getOrNull(pos)) {
                '"' -> string()
                '{' -> obj(depth + 1)
                in '0'..'9' -> number()
                else -> throw InvalidReleaseManifest()
            }
        } while (take(','))
        require(take('}'))
        return values
    }
    private fun number(): Long {
        val start = pos
        while (pos < input.length && input[pos] in '0'..'9') pos++
        val token = input.substring(start, pos)
        require(token.length == 1 || token[0] != '0')
        return token.toLong()
    }
    private fun string(): String {
        require(pos < input.length && input[pos++] == '"')
        val result = StringBuilder()
        while (pos < input.length) {
            val c = input[pos++]
            if (c == '"') return result.toString().also { value ->
                var i = 0
                while (i < value.length) {
                    val ch = value[i++]
                    if (ch.isHighSurrogate()) require(i < value.length && value[i++].isLowSurrogate())
                    else require(!ch.isLowSurrogate())
                }
            }
            require(c >= ' ')
            if (c != '\\') { result.append(c); continue }
            require(pos < input.length)
            result.append(when (val escape = input[pos++]) {
                '"', '\\', '/' -> escape
                'b' -> '\b'; 'f' -> '\u000C'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                'u' -> {
                    require(pos + 4 <= input.length)
                    val hex = input.substring(pos, pos + 4)
                    require(hex.all { it in "0123456789abcdefABCDEF" })
                    pos += 4
                    hex.toInt(16).toChar()
                }
                else -> throw InvalidReleaseManifest()
            })
        }
        throw InvalidReleaseManifest()
    }
}

sealed interface UpdateResult {
    data object Unavailable : UpdateResult
    data object UpToDate : UpdateResult
    data object UnsupportedBuild : UpdateResult
    data class NewVersion(val release: ReleaseManifest.Available, val belowMinimum: Boolean) : UpdateResult
    data object NetworkError : UpdateResult
    data object InvalidManifest : UpdateResult
}

fun evaluateRelease(manifest: ReleaseManifest, installedVersion: Long, applicationId: String): UpdateResult {
    if (applicationId != OfficialReleases.RELEASE_APPLICATION_ID) return UpdateResult.UnsupportedBuild
    return when (manifest) {
        ReleaseManifest.Unavailable -> UpdateResult.Unavailable
        is ReleaseManifest.Available -> if (manifest.versionCode > installedVersion) {
            UpdateResult.NewVersion(manifest, installedVersion < manifest.minSupportedVersionCode)
        } else UpdateResult.UpToDate
    }
}
