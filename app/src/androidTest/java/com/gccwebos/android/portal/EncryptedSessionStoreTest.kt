package com.gccwebos.android.portal

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

/** Disposable instrumentation values only; no production token or account is used. */
class EncryptedSessionStoreTest {
    @Test fun encryptedSessionRoundTripClearAndReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EncryptedSessionStore(context)
        try {
            store.clear()
            val at = Instant.parse("2026-09-30T00:00:00Z")
            val saved = SavedSession(Secret("synthetic-native-token-only"), "fixture@example.invalid", at.plusSeconds(1800), 1800000, at, 1000)
            store.save(saved)
            val raw = File(context.noBackupFilesDir, "portal-session.bin").readBytes()
            assertFalse(raw.toString(Charsets.ISO_8859_1).contains("synthetic-native-token-only"))
            assertFalse(raw.toString(Charsets.ISO_8859_1).contains("fixture@example.invalid"))
            val reopened = EncryptedSessionStore(context).read()!!
            assertEquals(saved.token.value, reopened.token.value); assertEquals(saved.expiresAt, reopened.expiresAt)
            store.clear(); assertNull(EncryptedSessionStore(context).read())
        } finally { store.clear() }
    }
    @Test fun tamperedCiphertextFailsClosedWithoutCredentialLeak() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EncryptedSessionStore(context)
        try {
            store.clear(); val at = Instant.now()
            store.save(SavedSession(Secret("synthetic-only"), "fixture", at.plusSeconds(1800), 1800000, at, 0))
            val file = File(context.noBackupFilesDir, "portal-session.bin")
            val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
            try { store.read(); fail() } catch (failure: PortalFailure) {
                assertEquals(PortalFailure.Reason.STORAGE, failure.reason)
                assertFalse(failure.toString().contains("synthetic")); assertNull(failure.cause)
            }
        } finally { store.clear() }
    }    @Test fun invalidatedLocalKeyDoesNotAdmitOldSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EncryptedSessionStore(context)
        try {
            store.clear(); val at = Instant.now()
            store.save(SavedSession(Secret("synthetic-only"), "fixture", at.plusSeconds(1800), 1800000, at, 0))
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("gccwebos.portal.session.v1") }
            try { store.read(); fail() } catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.STORAGE, failure.reason) }
        } finally { store.clear() }
    }

}
