package com.gccwebos.android.design

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadRetryPresentationTest {
    @Test fun knownResultAndEmptyResultHaveNoRoutineRefresh() {
        assertFalse(showReadRetry(false, true, false, false))
    }

    @Test fun failedReadCanRetryEvenWhenOlderRowsRemain() {
        assertTrue(showReadRetry(false, true, true, false))
    }

    @Test fun cancelledOrUnfinishedReadCanRetry() {
        assertTrue(showReadRetry(false, true, false, true))
    }

    @Test fun unknownGenerationKeepsOnlyReadRecoveryAvailable() {
        assertTrue(showReadRetry(false, true, false, false, unresolved = true))
    }

    @Test fun activeReadDoesNotExposeAnotherRead() {
        for (failed in listOf(false, true)) for (unread in listOf(false, true)) {
            assertFalse(showReadRetry(true, true, failed, unread, unresolved = true))
        }
    }

    @Test fun accountValidationOrOldAccountWithholdsRetry() {
        for (busy in listOf(false, true)) {
            assertFalse(showReadRetry(busy, false, true, true, unresolved = true))
        }
    }
}
