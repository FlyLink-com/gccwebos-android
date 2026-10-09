package com.gccwebos.android.portal

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class EncryptedLocalProfileStoreTest {
    @Test fun urlBytesBindingAndTerminalObservationRoundTripEncrypted() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EncryptedLocalProfileStore(context)
        val url = Secret("https://sub.invalid/sub/a/synthetic-private")
        val raw = "synthetic-configuration-material".toByteArray()
        val saved = CachedSubscription(sessionBinding(url), raw, Instant.parse("2099-09-30T00:00:00Z"), url, LocalSubscriptionStatus.EXPIRED)
        try {
            store.clear(); store.save(saved)
            val disk = File(context.noBackupFilesDir, "portal-profile.bin").readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(disk.contains("synthetic-private")); assertFalse(disk.contains("synthetic-configuration-material"))
            val reopened = EncryptedLocalProfileStore(context).read()!!
            try {
                assertEquals(url.value, reopened.url!!.value); assertEquals(saved.binding, reopened.binding)
                assertEquals(LocalSubscriptionStatus.EXPIRED, reopened.observedStatus)
                val bytes = reopened.copyBytes(); try { assertArrayEquals(raw, bytes) } finally { bytes.fill(0) }
            } finally { reopened.close() }
            store.clear(); assertNull(store.read())
        } finally { saved.close(); raw.fill(0); store.clear() }
    }
    @Test fun reportedUsageRoundTripsEncryptedAndClearsBackToUnknown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EncryptedLocalProfileStore(context)
        val url = Secret("https://sub.invalid/sub/a/synthetic-private")
        val usage = SubscriptionUsage(1L shl 30, 2L shl 30, 10L shl 30, Instant.parse("2026-10-09T00:00:00Z"))
        val withUsage = CachedSubscription(sessionBinding(url), "synthetic-configuration-material".toByteArray(), null, url, usage = usage)
        val without = CachedSubscription(sessionBinding(url), "synthetic-configuration-material".toByteArray(), null, url)
        try {
            store.clear(); store.save(withUsage)
            EncryptedLocalProfileStore(context).read()!!.use { assertEquals(usage, it.usage) }
            store.save(without)
            EncryptedLocalProfileStore(context).read()!!.use { assertNull(it.usage) }
        } finally { withUsage.close(); without.close(); store.clear() }
    }
    @Test fun tamperedProfileFailsWithoutLeakingUrlOrRawConfiguration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EncryptedLocalProfileStore(context)
        val url = Secret("https://sub.invalid/sub/a/synthetic-private")
        val saved = CachedSubscription(sessionBinding(url), "synthetic-secret".toByteArray(), null, url)
        try {
            store.clear(); store.save(saved)
            val file = File(context.noBackupFilesDir, "portal-profile.bin"); val bytes = file.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
            try { store.read(); fail() } catch (failure: PortalFailure) {
                assertEquals(PortalFailure.Reason.STORAGE, failure.reason); assertNull(failure.cause)
                assertFalse(failure.toString().contains("synthetic"))
            }
        } finally { saved.close(); store.clear() }
    }
}
