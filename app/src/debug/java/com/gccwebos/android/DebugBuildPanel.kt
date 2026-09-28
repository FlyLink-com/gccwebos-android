package com.gccwebos.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gccwebos.android.core.AccessDecision
import com.gccwebos.android.core.AccountGateway
import com.gccwebos.android.core.AuthorizedConfig
import com.gccwebos.android.core.BusinessBlock
import com.gccwebos.android.core.ConfigGateway
import com.gccwebos.android.core.ConnectionCoordinator
import com.gccwebos.android.core.ConnectionState
import com.gccwebos.android.core.EntitlementGateway
import com.gccwebos.android.core.PermissionGateway
import com.gccwebos.android.core.TrafficProbe
import com.gccwebos.android.core.TunnelGateway
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Debug-only engineering control. No socket, TUN, VpnService, account or payment is created. */
@Composable
internal fun DebugBuildPanel() {
    val scope = rememberCoroutineScope()
    val fake = remember { LocalDiagnosticGateways() }
    val controller = remember(scope) {
        ConnectionCoordinator(fake, fake, fake, fake, fake, fake, parentScope = scope)
    }
    val state by controller.state.collectAsState()
    var scenario by remember { mutableIntStateOf(0) }
    val scenarios = listOf("正常模拟", "配置等待（用于取消）", "流量探测失败", "无套餐阻断")
    val busy = state is ConnectionState.Working || state == ConnectionState.Disconnecting
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("debug-diagnostics")) {
        HorizontalDivider()
        Text("仅 Debug：本地状态机自检", style = MaterialTheme.typography.titleMedium)
        Text("以下全部为模拟。不连接互联网、不注册账户、不建立 VPN，也不表示实际服务可用。")
        Text(scenarios[scenario])
        OutlinedButton(enabled = !busy && state !is ConnectionState.Connected, onClick = {
            scenario = (scenario + 1) % scenarios.size
            fake.scenario = scenario
        }) { Text("切换模拟场景") }
        Text(when (val value = state) {
            is ConnectionState.Connected -> "模拟流程完成（未建立 VPN）：${value.lineId}"
            is ConnectionState.Working -> "模拟检查：${value.operation} / ${value.step}"
            is ConnectionState.Blocked -> "模拟业务阻断：${value.reason}"
            is ConnectionState.Failed -> "模拟失败：${value.reason}"
            ConnectionState.Disconnecting -> "正在清理模拟资源"
            ConnectionState.Disconnected -> "模拟资源已停止"
        })
        Button(onClick = { scope.launch { controller.connect("demo-a") } }) { Text("运行模拟 A") }
        OutlinedButton(onClick = { scope.launch { controller.switchLine("demo-b") } }) { Text("切换模拟 B") }
        OutlinedButton(onClick = { scope.launch { controller.reconnect() } }) { Text("模拟重连") }
        OutlinedButton(onClick = { scope.launch { controller.disconnect() } }) { Text("取消／停止模拟") }
    }
}

private class LocalDiagnosticGateways : AccountGateway, EntitlementGateway, ConfigGateway,
    PermissionGateway, TunnelGateway, TrafficProbe {
    var scenario: Int = 0
    private var running = false
    override suspend fun isAuthenticated(): Boolean { delay(250); return true }
    override suspend fun current(): AccessDecision {
        delay(250)
        return AccessDecision(if (scenario == 3) BusinessBlock.NO_PLAN else null)
    }
    override suspend fun forLine(lineId: String?): AuthorizedConfig {
        if (scenario == 1) awaitCancellation()
        delay(250)
        return AuthorizedConfig(lineId ?: "demo-auto", byteArrayOf())
    }
    override suspend fun hasVpnPermission(): Boolean { delay(250); return true }
    override suspend fun start(config: AuthorizedConfig): Boolean {
        running = true // An in-memory flag only; deliberately no Android VPN service.
        delay(250)
        return true
    }
    override suspend fun stop() { running = false }
    override suspend fun isUsable(): Boolean { delay(400); return running && scenario != 2 }
}
