package com.gccwebos.android.portal

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Isolated debug-owned synthetic directory; neither a real account nor a network request. */
class NativeSessionStorageTest {
    private fun isolated(test: (Context) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        require(target.packageName.endsWith(".debug"))
        val directory = File(target.noBackupFilesDir, "native-session-fixture-${UUID.randomUUID()}")
        require(directory.mkdir())
        val context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir() = directory
            override fun getApplicationContext(): Context = this
        }
        try { test(context) } finally { require(directory.deleteRecursively()) }
    }
    private val at = Instant.parse("2026-10-06T12:00:00Z")
    private fun record(now: Instant = at, elapsed: Long = 1000) = SavedSession(Secret("synthetic_native_access"),
        "synthetic_native_account", now.plusSeconds(1800), 1800000, now, elapsed,
        NativeRefreshGrant(Secret("pnr1_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 3 })),
            now, now.plusSeconds(604800), now, elapsed))

    @Test fun encryptedAtomicPairRoundTripsWithoutPlaintextAndClearsTogether() = isolated { context ->
        val store = EncryptedSessionStore(context); val saved = record(); store.save(saved)
        val raw = File(context.noBackupFilesDir, "portal-session.bin").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains(saved.token.value)); assertFalse(raw.contains(saved.refreshGrant!!.token.value))
        assertFalse(raw.contains(saved.accountId))
        val reopened = EncryptedSessionStore(context).read()!!
        assertEquals(saved.token.value, reopened.token.value)
        assertTrue(saved.refreshGrant.sameRecord(reopened.refreshGrant))
        store.clear(); assertNull(EncryptedSessionStore(context).read())
    }

    @Test fun actualVersionOneCiphertextReadsAndUpgradesWithoutInventingGrant() = isolated { context ->
        val store = EncryptedSessionStore(context); val saved = record(); store.save(saved)
        val legacy = JSONObject(SessionRecordJson.encode(saved)).put("version", 1).apply { remove("refreshGrant") }
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .getKey("gccwebos.portal.session.v1", null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key); updateAAD("gccwebos.session.v1".toByteArray())
        }
        val encrypted = cipher.doFinal(legacy.toString().toByteArray())
        File(context.noBackupFilesDir, "portal-session.bin").writeBytes(cipher.iv + encrypted)
        val reopened = EncryptedSessionStore(context).read()!!
        assertNull(reopened.refreshGrant); assertEquals(saved.token.value, reopened.token.value)
        store.save(reopened)
        assertNull(EncryptedSessionStore(context).read()!!.refreshGrant)
    }

    @Test fun productionFactoryEnablesNativeRenewalWithTheEncryptedStore() = isolated { context ->
        val current = Instant.now(); val elapsed = android.os.SystemClock.elapsedRealtime()
        EncryptedSessionStore(context).save(record(current, elapsed))
        // Client construction/read has no HTTP. The grant lives only in this synthetic test directory.
        assertTrue(productionRepository(context).hasRenewableSession())
    }
}
