package com.gccwebos.android.portal

import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ManualImportErrorMessageTest {
    private val manager = LocalSubscriptionManager(MemoryDeliveryStore(), MemoryLocalProfileStore(), SubscriptionSource { error("no network") },
        OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { Instant.parse("2026-10-14T00:00:00Z") },
        LocalSubscriptionAuthority(), manualSources = MemoryManualSourceStore())

    private suspend fun rejection(input: ByteArray): ManualImportInputRejected.Reason {
        try { manager.importManual(input) { it() }; fail("accepted") } catch (e: ManualImportInputRejected) { return e.reason }
        error("unreachable")
    }

    @Test fun emptyWhitespaceOversizeAndBadEncodingAreDistinguished() = runTest {
        assertEquals(ManualImportInputRejected.Reason.EMPTY, rejection(ByteArray(0)))
        assertEquals(ManualImportInputRejected.Reason.EMPTY, rejection("  \n\t ".toByteArray()))
        assertEquals(ManualImportInputRejected.Reason.TOO_LARGE, rejection(ByteArray(64 * 1024 + 1) { 'a'.code.toByte() }))
        assertEquals(ManualImportInputRejected.Reason.ENCODING, rejection(byteArrayOf(0xC3.toByte(), 0x28)))
        assertEquals(ManualImportInputRejected.Reason.ENCODING, rejection("proxies:\u0000".toByteArray()))
    }

    @Test fun rejectionsKeepTheIllegalArgumentContract() {
        assertTrue(ManualImportInputRejected(ManualImportInputRejected.Reason.EMPTY) is IllegalArgumentException)
    }

    @Test fun everyFailureMapsToASpecificActionableMessage() {
        val messages = listOf(
            ManualImportInputRejected(ManualImportInputRejected.Reason.EMPTY),
            ManualImportInputRejected(ManualImportInputRejected.Reason.TOO_LARGE),
            ManualImportInputRejected(ManualImportInputRejected.Reason.ENCODING),
            PortalFailure(PortalFailure.Reason.RATE_LIMITED),
            PortalFailure(PortalFailure.Reason.UNAVAILABLE),
            PortalFailure(PortalFailure.Reason.PROTOCOL),
            PortalFailure(PortalFailure.Reason.NETWORK),
        ).map(::manualImportErrorMessage)
        assertEquals("each failure has its own message", messages.size, messages.toSet().size)
        assertTrue(messages[1].contains("64 KB"))
    }

    @Test fun messagesNeverEchoExceptionText() {
        val secret = "https://sub.invalid/sub/a/secret-token"
        listOf(IllegalArgumentException(secret), RuntimeException(secret), IllegalStateException(secret))
            .forEach { assertFalse(manualImportErrorMessage(it).contains("secret")) }
    }
}
