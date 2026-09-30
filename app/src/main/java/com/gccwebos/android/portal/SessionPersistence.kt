package com.gccwebos.android.portal

import java.time.Duration
import java.time.Instant

/** Native bearer TTL is server-issued; storage never turns it into browser remember-me. */
class SavedSession(val token: Secret, val accountId: String, val expiresAt: Instant,
    val remainingMillis: Long, val savedAt: Instant, val savedElapsed: Long) {
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
        fun capture(value: Session, now: Instant, elapsed: Long): SavedSession {
            require(value.valid(now, elapsed))
            val remaining = minOf(MAX_NATIVE_SESSION_MILLIS, value.lifetimeMillis - (elapsed - value.receivedElapsed), Duration.between(now, value.expiresAt).toMillis())
            require(remaining > 0)
            return SavedSession(value.token, value.accountId, value.expiresAt, remaining, now, elapsed).also { require(it.restore(now, elapsed) != null) }
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
