package com.gccwebos.android

import androidx.test.platform.app.InstrumentationRegistry
import com.gccwebos.android.commerce.*
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Local synthetic no-backup records only; never connects to a service or initiates a transaction. */
class CommerceRecoveryInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val first = "a".repeat(64)
    private val second = "b".repeat(64)
    private fun store() = FileCommerceRecovery(context)
    @After fun cleanup() { listOf(first,second).forEach { account -> listOf("purchase","recharge").forEach { store().clear(account,it) } } }
    @Test fun durableKeySurvivesStoreRecreationBeforeAnyRequest() {
        val pending = PendingCommerce("purchase",CommerceModel.LEGACY,"quote_fixture","key_fixture")
        store().save(first,pending)
        assertEquals(pending,store().read(first,"purchase"))
        assertNull(store().read(second,"purchase"))
    }
    @Test fun resultReceiptUpdatesAtomicallyAndCanBeCleared() {
        val pending = PendingCommerce("recharge",null,"offer_fixture","key_fixture")
        store().save(first,pending)
        val received = pending.copy(resultRef="order_fixture")
        store().save(first,received)
        assertEquals(received,store().read(first,"recharge"))
        store().clear(first,"recharge")
        assertNull(store().read(first,"recharge"))
    }
    @Test fun corruptRecordFailsClosedRatherThanReturningNoPendingOrder() {
        File(context.noBackupFilesDir,"commerce-$first-purchase.json").writeText("{broken")
        try { store().read(first,"purchase"); fail("Corruption must block mutation recovery") } catch (_: org.json.JSONException) { }
    }
    @Test fun oversizedRecordFailsClosed() {
        File(context.noBackupFilesDir,"commerce-$first-purchase.json").writeBytes(ByteArray(4097))
        try { store().read(first,"purchase"); fail("Oversized record must fail") } catch (_: IllegalArgumentException) { }
    }
    @Test fun accountAndKindCannotTraverseStoragePaths() {
        for(binding in listOf("../outside", "account@example.invalid", "c".repeat(63))) {
            try { store().read(binding,"purchase"); fail("Invalid binding") } catch (_: IllegalArgumentException) { }
        }
        try { store().read(first,"../purchase"); fail("Invalid kind") } catch (_: IllegalArgumentException) { }
    }
}
