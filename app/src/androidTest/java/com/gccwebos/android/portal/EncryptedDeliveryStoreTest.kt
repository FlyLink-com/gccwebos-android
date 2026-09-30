package com.gccwebos.android.portal

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import java.io.File

class EncryptedDeliveryStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = EncryptedDeliveryStore(context)
    private val file get() = File(context.noBackupFilesDir, "portal-delivery.bin")
    private fun receipt() = DeliveryRecord("synthetic@example.invalid", sessionBinding(Secret("synthetic_session")),
        "resource_fixture", "operation_fixture_123", Secret("https://sub.invalid/sub/a/synthetic_only"), Secret("synthetic_handoff"), true)
    @After fun cleanup() { store.clear() }
    @Test fun encryptedRoundTripAndClear() {
        store.clear(); store.save(receipt())
        val restored = store.read()!!
        assertEquals(receipt().url!!.value, restored.url!!.value)
        assertTrue(restored.pendingAck)
        val disk = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(disk.contains("synthetic_session")); assertFalse(disk.contains("synthetic_only"))
        store.clear(); assertNull(store.read())
    }
    @Test fun recoversAtomicFileBackupWhenBaseMissing() {
        store.clear(); store.save(receipt())
        assertTrue(file.renameTo(File(file.path + ".bak")))
        assertFalse(file.exists())
        assertEquals(receipt().operationKey, store.read()!!.operationKey)
    }
    @Test fun oversizedReceiptDoesNotOverwritePriorReceipt() {
        store.clear(); store.save(receipt())
        try {
            store.save(DeliveryRecord("x".repeat(70000),"binding","resource","key"))
            fail("oversized receipt must fail before replacement")
        } catch(failure: PortalFailure) { assertEquals(PortalFailure.Reason.STORAGE,failure.reason) }
        assertEquals(receipt().operationKey,store.read()!!.operationKey)
    }
}
