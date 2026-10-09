package com.gccwebos.android.portal

import java.time.Instant
import java.util.Locale

/**
 * Traffic counters reported by one subscription download's own `Subscription-Userinfo` response header.
 * They describe only that saved configuration as reported by its server. They are never account identity,
 * an account plan/quota, Portal data, or connection admission. A missing or zero total is unknown (null),
 * never "0 remaining".
 */
class SubscriptionUsageReport internal constructor(val uploadBytes: Long, val downloadBytes: Long, val totalBytes: Long) {
    init { require(uploadBytes >= 0 && downloadBytes >= 0 && totalBytes > 0) }
    val usedBytes: Long get() = if (Long.MAX_VALUE - uploadBytes < downloadBytes) Long.MAX_VALUE else uploadBytes + downloadBytes
    override fun equals(other: Any?) = other is SubscriptionUsageReport && uploadBytes == other.uploadBytes &&
        downloadBytes == other.downloadBytes && totalBytes == other.totalBytes
    override fun hashCode() = ((uploadBytes * 31 + downloadBytes) * 31 + totalBytes).hashCode()
    override fun toString() = "SubscriptionUsageReport([REDACTED])"
}

/** A report stamped with the local time it was observed. Stored only inside the encrypted manual-source record. */
class SubscriptionUsage(val uploadBytes: Long, val downloadBytes: Long, val totalBytes: Long, val observedAt: Instant) {
    init { require(uploadBytes >= 0 && downloadBytes >= 0 && totalBytes > 0) }
    val usedBytes: Long get() = if (Long.MAX_VALUE - uploadBytes < downloadBytes) Long.MAX_VALUE else uploadBytes + downloadBytes
    /** Reported exhaustion is a real zero; unknown usage never reaches this class. */
    val remainingBytes: Long get() = (totalBytes - usedBytes).coerceAtLeast(0)
    override fun equals(other: Any?) = other is SubscriptionUsage && uploadBytes == other.uploadBytes &&
        downloadBytes == other.downloadBytes && totalBytes == other.totalBytes && observedAt == other.observedAt
    override fun hashCode() = ((uploadBytes * 31 + downloadBytes) * 31 + totalBytes).hashCode() * 31 + observedAt.hashCode()
    override fun toString() = "SubscriptionUsage([REDACTED])"
    companion object {
        fun observed(report: SubscriptionUsageReport, at: Instant) =
            SubscriptionUsage(report.uploadBytes, report.downloadBytes, report.totalBytes, at)
    }
}

/** Parsed `Subscription-Userinfo`; any field can independently be unknown. */
internal class SubscriptionUserinfo(val expiry: Instant?, val usage: SubscriptionUsageReport?) {
    override fun toString() = "SubscriptionUserinfo([REDACTED])"
}

/** One strict parser shared by the official and third-party subscription transports. */
internal object SubscriptionUserinfoParser {
    const val MAX_HEADER_CHARS = 1024
    const val MAX_UNIX_SECONDS = 253402300799L // Last second of year 9999.
    private val key = Regex("[A-Za-z][A-Za-z0-9_-]{0,31}")
    private val number = Regex("0|[1-9][0-9]{0,18}")

    /** Throws IllegalArgumentException for malformed, duplicate or out-of-range values; never echoes input. */
    fun parse(text: String): SubscriptionUserinfo {
        try {
            require(text.length in 1..MAX_HEADER_CHARS && text.all { it.code in 0x20..0x7e })
            val parts = text.split(';')
            require(parts.size in 1..16)
            val seen = mutableSetOf<String>()
            val numbers = mutableMapOf<String, Long>()
            for (part in parts) {
                val pair = part.trim().split('=', limit = 2)
                require(pair.size == 2 && key.matches(pair[0]))
                val name = pair[0].lowercase(Locale.ROOT)
                require(seen.add(name))
                val value = pair[1].trim()
                require(value.isNotEmpty() && value.length <= 128 && value.none { it == ',' })
                if (name in setOf("expire", "upload", "download", "total")) {
                    require(number.matches(value))
                    numbers[name] = value.toLong() // 19 digits can still overflow Long.
                }
            }
            val expire = numbers["expire"]
            if (expire != null) require(expire <= MAX_UNIX_SECONDS)
            val upload = numbers["upload"]; val download = numbers["download"]; val total = numbers["total"]
            // All-zero, a zero total or any missing counter is unknown, never "0 remaining".
            val usage = if (upload != null && download != null && total != null && total > 0)
                SubscriptionUsageReport(upload, download, total) else null
            return SubscriptionUserinfo(expire?.takeIf { it > 0 }?.let(Instant::ofEpochSecond), usage)
        } catch (_: Exception) { throw IllegalArgumentException() }
    }

    /** Official downloads keep their existing expiry contract; unusable usage metadata is simply unknown. */
    fun lenientUsage(values: List<String>): SubscriptionUsageReport? =
        values.singleOrNull()?.let { try { parse(it).usage } catch (_: IllegalArgumentException) { null } }
}
