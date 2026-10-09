package com.gccwebos.android.portal

import java.time.Duration
import java.time.Instant
import java.util.Base64

/** A rotating native grant, never a browser cookie or a reusable password. */
class NativeRefreshGrant(val token: Secret, val issuedAt: Instant, val expiresAt: Instant,
    val receivedAt: Instant, val receivedElapsed: Long) {
    override fun toString() = "NativeRefreshGrant([REDACTED])"
    fun valid(now: Instant, elapsed: Long): Boolean = try {
        require(token.value.matches(Regex("pnr1_[A-Za-z0-9_-]{43}")))
        val encoded = token.value.removePrefix("pnr1_")
        val bytes = Base64.getUrlDecoder().decode(encoded)
        require(bytes.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == encoded)
        require(Duration.between(issuedAt, expiresAt).toMillis() == MAX_LIFETIME_MILLIS)
        require(receivedAt >= issuedAt && receivedAt < expiresAt && receivedElapsed >= 0 && elapsed >= 0)
        val wallAge = Duration.between(receivedAt, now).toMillis()
        require(wallAge >= 0)
        val monotonicAge = if (elapsed >= receivedElapsed) elapsed - receivedElapsed else 0L
        now < expiresAt && maxOf(wallAge, monotonicAge) < Duration.between(receivedAt, expiresAt).toMillis()
    } catch (_: Exception) { false }
    internal fun sameRecord(other: NativeRefreshGrant?): Boolean = other != null &&
        token.value == other.token.value && issuedAt == other.issuedAt && expiresAt == other.expiresAt &&
        receivedAt == other.receivedAt && receivedElapsed == other.receivedElapsed
    companion object { const val MAX_LIFETIME_MILLIS = 7 * 24 * 60 * 60 * 1000L }
}

/** Native bearer TTL is server-issued; storage never turns it into browser remember-me. */
class SavedSession(val token: Secret, val accountId: String, val expiresAt: Instant,
    val remainingMillis: Long, val savedAt: Instant, val savedElapsed: Long,
    val refreshGrant: NativeRefreshGrant? = null) {
    override fun toString() = "SavedSession([REDACTED])"
    fun restore(now: Instant, elapsed: Long): Session? = try {
        require(token.value.matches(Regex("[A-Za-z0-9._:-]{1,8192}")) &&
            !token.value.startsWith("portalctx_") && !token.value.startsWith("portalbrowser_"))
        require(accountId.isNotBlank() && accountId.toByteArray().size <= 1024 && accountId.none { it.isISOControl() } && Charsets.UTF_8.newEncoder().canEncode(accountId))
        require(remainingMillis in 1..MAX_NATIVE_SESSION_MILLIS && savedElapsed >= 0 && elapsed >= 0)
        val wallAge = Duration.between(savedAt, now).toMillis()
        require(wallAge >= 0) // Clock rollback cannot extend the persisted session.
        val monotonicAge = if (elapsed >= savedElapsed) elapsed - savedElapsed else 0L // Reboot still requires server validation.
        val remaining = minOf(remainingMillis - maxOf(wallAge, monotonicAge), Duration.between(now, expiresAt).toMillis())
        require(remaining > 0)
        Session(token, accountId, expiresAt, remaining, elapsed)
    } catch (_: Exception) { null }
    companion object {
        const val MAX_NATIVE_SESSION_MILLIS = 30 * 60 * 1000L
        fun capture(value: Session, now: Instant, elapsed: Long, refreshGrant: NativeRefreshGrant? = null): SavedSession {
            require(value.valid(now, elapsed))
            val remaining = minOf(MAX_NATIVE_SESSION_MILLIS, value.lifetimeMillis - (elapsed - value.receivedElapsed), Duration.between(now, value.expiresAt).toMillis())
            require(remaining > 0)
            require(refreshGrant == null || refreshGrant.valid(now, elapsed))
            return SavedSession(value.token, value.accountId, value.expiresAt, remaining, now, elapsed, refreshGrant).also { require(it.restore(now, elapsed) != null) }
        }
    }
}
interface SessionStore {
    fun read(): SavedSession?
    fun save(value: SavedSession)
    fun clear()
}
/** Test/default caller isolation; production explicitly installs the encrypted store. */
class MemorySessionStore : SessionStore {
    private var value: SavedSession? = null
    override fun read() = value
    override fun save(value: SavedSession) { this.value = value }
    override fun clear() { value = null }
}
