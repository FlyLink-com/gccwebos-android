package com.gccwebos.android.portal

import org.json.JSONObject
import java.time.Instant

/** Plaintext codec used only inside the authenticated, atomic encrypted record. */
internal object SessionRecordJson {
    fun decode(text: String): SavedSession {
        val value = JSONObject(text)
        val fields = setOf("version", "token", "account", "expires", "remaining", "savedAt", "elapsed")
        val version = value.get("version")
        require(version == 1 || version == 2)
        require(value.keys().asSequence().toSet() == fields + if (version == 2) setOf("refreshGrant") else emptySet())
        fun text(key: String) = value.get(key) as String
        fun number(value: JSONObject, key: String): Long {
            val n = value.get(key); require(n is Int || n is Long); return (n as Number).toLong()
        }
        val grant = if (version == 1 || value.isNull("refreshGrant")) null else {
            val g = value.getJSONObject("refreshGrant")
            require(g.keys().asSequence().toSet() == setOf("token", "issuedAt", "expiresAt", "receivedAt", "elapsed"))
            fun instant(key: String) = Instant.parse(g.get(key) as String)
            NativeRefreshGrant(Secret(g.get("token") as String), instant("issuedAt"), instant("expiresAt"),
                instant("receivedAt"), number(g, "elapsed")).also { require(it.valid(it.receivedAt, it.receivedElapsed)) }
        }
        return SavedSession(Secret(text("token")), text("account"), Instant.parse(text("expires")),
            number(value, "remaining"), Instant.parse(text("savedAt")), number(value, "elapsed"), grant)
            .also { require(it.restore(it.savedAt, it.savedElapsed) != null) }
    }
    fun encode(record: SavedSession): String = JSONObject().put("version", 2)
        .put("token", record.token.value).put("account", record.accountId)
        .put("expires", record.expiresAt.toString()).put("remaining", record.remainingMillis)
        .put("savedAt", record.savedAt.toString()).put("elapsed", record.savedElapsed)
        .put("refreshGrant", record.refreshGrant?.let { g -> JSONObject().put("token", g.token.value)
            .put("issuedAt", g.issuedAt.toString()).put("expiresAt", g.expiresAt.toString())
            .put("receivedAt", g.receivedAt.toString()).put("elapsed", g.receivedElapsed) } ?: JSONObject.NULL).toString()
}
