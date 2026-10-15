package com.gccwebos.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.gccwebos.android.portal.LocalSubscriptionSnapshot
import com.gccwebos.android.portal.LocalSubscriptionStatus
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectionRecoveryHintViewTest {
    @get:Rule val compose = createComposeRule()
    private val clock = Instant.parse("2026-10-14T00:00:00Z")

    @Test fun networkLossRendersTheNetworkHint() {
        val p = connectionOverviewPresentation(LocalSubscriptionSnapshot(LocalSubscriptionStatus.READY, hasProfile = true,
            hasCapability = true, validUntil = clock.plusSeconds(60)), clock,
            ConnectionControlState(ConnectionPhase.Failed, ConnectionFailure.NetworkUnavailable, canConnect = true))
        compose.setContent { p.recoveryHint?.let { ConnectionRecoveryHintLine(it) } }
        compose.onNodeWithTag("connection-recovery-hint").assertIsDisplayed()
            .assertTextEquals("请检查 Wi-Fi 或移动数据，恢复后点「重试连接」")
    }

    @Test fun storageErrorRendersItsHint() {
        val p = connectionOverviewPresentation(LocalSubscriptionSnapshot(LocalSubscriptionStatus.STORAGE_ERROR), clock)
        compose.setContent { p.recoveryHint?.let { ConnectionRecoveryHintLine(it) } }
        compose.onNodeWithTag("connection-recovery-hint").assertTextEquals("请重试；仍失败可重新导入配置或重新登录")
    }
}
