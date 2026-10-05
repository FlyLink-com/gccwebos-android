package com.gccwebos.android

import com.gccwebos.android.portal.LocalSubscriptionSnapshot
import com.gccwebos.android.portal.LocalSubscriptionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant

class ConnectionOverviewTest {
    @Test fun externalUnreportedExpiryRequiresItsOwnTypedSourceAndNeverClaimsPermanentValidity() {
        val clock = Instant.parse("2026-10-05T00:00:00Z")
        val control = ConnectionControlState(ConnectionPhase.Disconnected, canConnect = true)
        val external = LocalSubscriptionSnapshot(LocalSubscriptionStatus.READY, hasProfile = true, hasCapability = true,
            sourceKind = com.gccwebos.android.portal.ManualSourceKind.ExternalRemote,
            validity = com.gccwebos.android.portal.LocalSourceValidity.ExternalUnreported)
        val result = connectionOverviewPresentation(external, clock, control)
        assertEquals(ConnectionPowerAction.Connect, result.action)
        assertEquals("第三方订阅", result.title); assertEquals("未提供有效期", result.countdown)
        for (invalid in listOf(external.copy(sourceKind = com.gccwebos.android.portal.ManualSourceKind.LegacyRemote),
            external.copy(sourceKind = com.gccwebos.android.portal.ManualSourceKind.ImportedRemote),
            external.copy(validity = com.gccwebos.android.portal.LocalSourceValidity.LocalNoServerExpiry),
            external.copy(validity = com.gccwebos.android.portal.LocalSourceValidity.RemoteExpiry(null)),
            external.copy(validUntil = clock.plusSeconds(60)), external.copy(hasCapability = false))) {
            assertEquals(ConnectionPowerAction.None, connectionOverviewPresentation(invalid, clock, control).action)
        }
        val until = clock.plusSeconds(60)
        val known = external.copy(validUntil = until,
            validity = com.gccwebos.android.portal.LocalSourceValidity.ExternalExpiry(until))
        assertEquals(ConnectionPowerAction.Connect, connectionOverviewPresentation(known, clock, control).action)
        assertEquals(ConnectionPowerAction.None, connectionOverviewPresentation(known, until, control).action)
    }
    @Test fun onlyTypedLocalSourceCanConnectWithoutServerExpiry() {
        val clock = Instant.parse("2026-10-04T00:00:00Z")
        val control = ConnectionControlState(ConnectionPhase.Disconnected, canConnect = true)
        val local = LocalSubscriptionSnapshot(LocalSubscriptionStatus.READY, hasProfile = true, hasCapability = true,
            sourceKind = com.gccwebos.android.portal.ManualSourceKind.ImportedLocal,
            validity = com.gccwebos.android.portal.LocalSourceValidity.LocalNoServerExpiry)
        val result = connectionOverviewPresentation(local, clock, control)
        assertEquals(ConnectionPowerAction.Connect, result.action)
        assertEquals("本地配置", result.title)
        assertEquals(null, result.countdown)
        for (invalid in listOf(local.copy(sourceKind = com.gccwebos.android.portal.ManualSourceKind.ImportedRemote),
            local.copy(validity = com.gccwebos.android.portal.LocalSourceValidity.RemoteExpiry(null)),
            local.copy(hasCapability = false), local.copy(status = LocalSubscriptionStatus.STORAGE_ERROR))) {
            assertEquals(ConnectionPowerAction.None, connectionOverviewPresentation(invalid, clock, control).action)
        }
    }
    private val now = Instant.parse("2026-10-01T00:00:00Z")
    private val ready = LocalSubscriptionSnapshot(LocalSubscriptionStatus.READY, hasProfile = true,
        hasCapability = true, validUntil = now.plusSeconds(90))

    @Test fun savedReadyConfigurationNeverImpliesAnEngineConnectionOrAction() {
        val result = connectionOverviewPresentation(ready, now)
        assertEquals(ConnectionVisualTone.Neutral, result.tone)
        assertEquals("未连接", result.connectionText)
        assertEquals("连接暂不可用", result.title)
        assertEquals(ConnectionPowerAction.None, result.action)
        assertEquals("连接暂不可用", result.powerLabel)
    }

    @Test fun incompleteReadyRemainsUnconfirmedWithoutAnExpiryOrTrafficProjection() {
        listOf(ready.copy(hasProfile = false), ready.copy(hasCapability = false), ready.copy(validUntil = null)).forEach {
            val result = connectionOverviewPresentation(it, now)
            assertEquals(ConnectionVisualTone.Neutral, result.tone)
            assertEquals("订阅待确认", result.title)
            assertEquals(null, result.countdown)
            assertEquals("暂不可用", result.usageText)
        }
    }

    @Test fun expiryAtTheExactBoundaryTurnsKnownReadyRedWithoutNegativeCountdown() {
        listOf(now, now.minusNanos(1)).forEach { until ->
            val result = connectionOverviewPresentation(ready.copy(validUntil = until), now)
            assertEquals(LocalSubscriptionStatus.EXPIRED, result.status)
            assertEquals(ConnectionVisualTone.Error, result.tone)
            assertEquals("订阅已过期", result.title)
            assertEquals("已到期", result.countdown)
        }
        assertEquals(ConnectionVisualTone.Neutral,
            connectionOverviewPresentation(ready.copy(validUntil = now.plusNanos(1)), now).tone)
    }

    @Test fun retryRevocationStorageAndUnsupportedNeverBorrowExpiryFromAnOldTimestamp() {
        listOf(LocalSubscriptionStatus.MISSING, LocalSubscriptionStatus.RETRY_REQUIRED,
            LocalSubscriptionStatus.INVALID_LINK, LocalSubscriptionStatus.STORAGE_ERROR,
            LocalSubscriptionStatus.UNSUPPORTED).forEach { status ->
            val result = connectionOverviewPresentation(ready.copy(status = status, validUntil = Instant.EPOCH), now)
            assertEquals(status, result.status)
            assertEquals(if (status == LocalSubscriptionStatus.MISSING) ConnectionVisualTone.Neutral else ConnectionVisualTone.Error,
                result.tone)
            assertNotEquals("订阅已过期", result.title)
            assertNotEquals("无网络", result.title)
            assertEquals(null, result.countdown)
        }
    }

    @Test fun explicitExpiredEvidenceIsRedEvenWhenItsTimestampIsUnavailable() {
        val result = connectionOverviewPresentation(LocalSubscriptionSnapshot(LocalSubscriptionStatus.EXPIRED), now)
        assertEquals(ConnectionVisualTone.Error, result.tone)
        assertEquals("订阅已过期", result.title)
        assertEquals("已到期", result.countdown)
    }

    @Test fun localCapabilityHasNoInventedTrafficAndValidityRemainsOnlyATimeProjection() {
        val result = connectionOverviewPresentation(ready.copy(validUntil = now.plusSeconds(61).plusNanos(1)), now)
        assertEquals("暂不可用", result.usageText)
        assertEquals("剩余 0天 0小时 1分 2秒", result.countdown)
        val missing = connectionOverviewPresentation(LocalSubscriptionSnapshot(LocalSubscriptionStatus.MISSING), now)
        assertEquals("暂不可用", missing.usageText)
        assertEquals("未配置", missing.title)
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
        assertEquals(ConnectionVisualTone.Error, result.tone)
        assertEquals("已到期", result.countdown)
    }

    @Test fun onlyExplicitControllerPhasesCanProduceBlueConnectingOrGreenConnected() {
        val connecting = connectionOverviewPresentation(ready, now, ConnectionControlState(ConnectionPhase.Connecting))
        assertEquals(ConnectionVisualTone.Connecting, connecting.tone)
        assertEquals("连接中", connecting.connectionText)
        assertEquals("", connecting.title)
        assertEquals(ConnectionPowerAction.None, connecting.action)
        val connected = connectionOverviewPresentation(ready, now, ConnectionControlState(ConnectionPhase.Connected))
        assertEquals(ConnectionVisualTone.Connected, connected.tone)
        assertEquals("已连接", connected.connectionText)
        assertEquals("暂不可用", connected.usageText)
        assertEquals(ConnectionPowerAction.None, connected.action)
    }

    @Test fun aConnectPermissionDoesNotInventAnAvailableEngineOrBypassMissingConfiguration() {
        assertEquals(ConnectionPowerAction.None, connectionOverviewPresentation(ready, now,
            ConnectionControlState(canConnect = true)).action)
        listOf(LocalSubscriptionSnapshot(LocalSubscriptionStatus.MISSING), ready.copy(hasCapability = false),
            ready.copy(validUntil = null), ready.copy(validUntil = now)).forEach {
            assertEquals(ConnectionPowerAction.None, connectionOverviewPresentation(it, now,
                ConnectionControlState(ConnectionPhase.Disconnected, canConnect = true)).action)
        }
        assertEquals(ConnectionPowerAction.Connect, connectionOverviewPresentation(ready, now,
            ConnectionControlState(ConnectionPhase.Disconnected, canConnect = true)).action)
    }

    @Test fun connectingCancellationAndConnectedDisconnectRequireSeparateExplicitCapabilities() {
        val connecting = ConnectionControlState(ConnectionPhase.Connecting, canConnect = true)
        assertEquals(ConnectionPowerAction.None, connectionOverviewPresentation(ready, now, connecting).action)
        assertEquals(ConnectionPowerAction.Cancel, connectionOverviewPresentation(ready, now, connecting.copy(canCancel = true)).action)
        val connected = ConnectionControlState(ConnectionPhase.Connected, canCancel = true)
        assertEquals(ConnectionPowerAction.None, connectionOverviewPresentation(ready, now, connected).action)
        assertEquals(ConnectionPowerAction.Disconnect,
            connectionOverviewPresentation(ready, now, connected.copy(canDisconnect = true)).action)
    }

    @Test fun pendingCommandsAndDisconnectingCannotAcceptAnotherPowerCommand() {
        listOf(ConnectionControlState(ConnectionPhase.Disconnected, canConnect = true),
            ConnectionControlState(ConnectionPhase.Connecting, canCancel = true),
            ConnectionControlState(ConnectionPhase.Connected, canDisconnect = true),
            ConnectionControlState(ConnectionPhase.Failed, canConnect = true)).forEach {
            assertEquals(ConnectionPowerAction.None, connectionOverviewPresentation(ready, now, it.copy(commandPending = true)).action)
        }
        val disconnecting = connectionOverviewPresentation(ready, now,
            ConnectionControlState(ConnectionPhase.Disconnecting, canConnect = true, canCancel = true, canDisconnect = true))
        assertEquals(ConnectionPowerAction.None, disconnecting.action)
        assertEquals(ConnectionVisualTone.Connecting, disconnecting.tone)
        assertEquals("断开中", disconnecting.connectionText)
    }

    @Test fun explicitControllerFailureKeepsItsShortReasonAndDoesNotInventExpiry() {
        val reasons = mapOf(ConnectionFailure.NetworkUnavailable to "无网络",
            ConnectionFailure.ResourceUnavailable to "订阅资源不可用", ConnectionFailure.ReadFailed to "读取失败",
            ConnectionFailure.Other to "连接异常")
        reasons.forEach { (failure, expected) ->
            val result = connectionOverviewPresentation(ready, now, ConnectionControlState(ConnectionPhase.Failed, failure))
            assertEquals(ConnectionVisualTone.Error, result.tone)
            assertEquals(expected, result.title)
            assertEquals("未连接", result.connectionText)
            assertEquals(LocalSubscriptionStatus.READY, result.status)
        }
    }

    @Test fun anExplicitLocalFaultOverridesGreenButNeverBlocksAControllerAuthorizedDisconnect() {
        val result = connectionOverviewPresentation(ready.copy(status = LocalSubscriptionStatus.EXPIRED), now,
            ConnectionControlState(ConnectionPhase.Connected, canDisconnect = true))
        assertEquals(ConnectionVisualTone.Error, result.tone)
        assertEquals("已连接", result.connectionText)
        assertEquals("订阅已过期", result.title)
        assertEquals(ConnectionPowerAction.Disconnect, result.action)
        assertEquals("已到期", result.countdown)
    }
}
