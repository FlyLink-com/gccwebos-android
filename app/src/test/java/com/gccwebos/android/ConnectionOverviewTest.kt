package com.gccwebos.android

import com.gccwebos.android.portal.LocalSubscriptionSnapshot
import com.gccwebos.android.portal.LocalSubscriptionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant

class ConnectionOverviewTest {
    private val now = Instant.parse("2026-10-01T00:00:00Z")
    private val ready = LocalSubscriptionSnapshot(LocalSubscriptionStatus.READY, hasProfile = true,
        hasCapability = true, validUntil = now.plusSeconds(90))

    @Test fun blueRequiresProfileCapabilityAndAConfirmedFutureValidity() {
        assertEquals(ConnectionVisualTone.Available, connectionOverviewPresentation(ready, now).tone)
        listOf(ready.copy(hasProfile = false), ready.copy(hasCapability = false), ready.copy(validUntil = null)).forEach {
            assertEquals(ConnectionVisualTone.Neutral, connectionOverviewPresentation(it, now).tone)
            assertEquals("订阅待确认", connectionOverviewPresentation(it, now).title)
        }
    }

    @Test fun expiryAtTheExactBoundaryTurnsKnownReadyRedWithoutNegativeCountdown() {
        listOf(now, now.minusNanos(1)).forEach { until ->
            val result = connectionOverviewPresentation(ready.copy(validUntil = until), now)
            assertEquals(LocalSubscriptionStatus.EXPIRED, result.status)
            assertEquals(ConnectionVisualTone.Expired, result.tone)
            assertEquals("已到期", result.countdown)
        }
        assertEquals(ConnectionVisualTone.Available,
            connectionOverviewPresentation(ready.copy(validUntil = now.plusNanos(1)), now).tone)
    }

    @Test fun retryRevocationStorageAndUnsupportedNeverBorrowExpiryFromAnOldTimestamp() {
        listOf(LocalSubscriptionStatus.MISSING, LocalSubscriptionStatus.RETRY_REQUIRED,
            LocalSubscriptionStatus.INVALID_LINK, LocalSubscriptionStatus.STORAGE_ERROR,
            LocalSubscriptionStatus.UNSUPPORTED).forEach { status ->
            val result = connectionOverviewPresentation(ready.copy(status = status, validUntil = Instant.EPOCH), now)
            assertEquals(status, result.status)
            assertEquals(ConnectionVisualTone.Neutral, result.tone)
            assertNotEquals("订阅已过期", result.title)
            assertEquals(null, result.countdown)
        }
    }

    @Test fun explicitExpiredEvidenceIsRedEvenWhenItsTimestampIsUnavailable() {
        val result = connectionOverviewPresentation(LocalSubscriptionSnapshot(LocalSubscriptionStatus.EXPIRED), now)
        assertEquals(ConnectionVisualTone.Expired, result.tone)
        assertEquals("订阅已过期", result.title)
        assertEquals("已到期", result.countdown)
    }

    @Test fun localCapabilityHasNoInventedTrafficAndValidityRemainsOnlyATimeProjection() {
        val result = connectionOverviewPresentation(ready.copy(validUntil = now.plusSeconds(61).plusNanos(1)), now)
        assertEquals("暂不可用", result.usageText)
        assertEquals("剩余 0天 0小时 1分 2秒", result.countdown)
        val missing = connectionOverviewPresentation(LocalSubscriptionSnapshot(LocalSubscriptionStatus.MISSING), now)
        assertEquals("暂不可用", missing.usageText)
        assertEquals(null, missing.countdown)
    }

    @Test fun incompleteReadyCannotBorrowAnOldExpiryOrPublishATimeProjection() {
        listOf(ready.copy(hasProfile = false), ready.copy(hasCapability = false)).forEach { incomplete ->
            val result = connectionOverviewPresentation(incomplete.copy(validUntil = now.minusSeconds(1)), now)
            assertEquals(LocalSubscriptionStatus.READY, result.status)
            assertEquals(ConnectionVisualTone.Neutral, result.tone)
            assertEquals("订阅待确认", result.title)
            assertEquals(null, result.countdown)
        }
    }

    @Test fun explicitExpiryCannotDisplayPositiveTimeFromARetainedFutureTimestamp() {
        val result = connectionOverviewPresentation(ready.copy(status = LocalSubscriptionStatus.EXPIRED,
            validUntil = now.plusSeconds(86_400)), now)
        assertEquals(ConnectionVisualTone.Expired, result.tone)
        assertEquals("已到期", result.countdown)
    }
}
