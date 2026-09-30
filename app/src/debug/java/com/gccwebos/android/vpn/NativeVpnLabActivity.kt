package com.gccwebos.android.vpn

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gccwebos.android.BuildConfig

/** No launcher/deep link/export. Explicit gated engineering screen, not the product Connect button. */
class NativeVpnLabActivity : ComponentActivity() {
    private var status by mutableStateOf("尚未启动。此功能仅用于隔离实验。")
    private var requested = false
    private var hasStarted by mutableStateOf(false)
    private val permissionAttempt = LabPermissionAttempt()
    private var permissionTicket: Long? = null
    private var permissionPending by mutableStateOf(false)
    private var notificationPending by mutableStateOf(false)
    private var vpnGranted by mutableStateOf(false)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationPending = false
        status = "通知权限流程已结束；启动仍需点击并授予 VPN 权限"
    }
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val ticket = permissionTicket
        permissionTicket = null
        permissionPending = false
        if (ticket != null && permissionAttempt.complete(ticket)) {
            vpnGranted = it.resultCode == RESULT_OK && VpnService.prepare(this) == null
            status = if (vpnGranted) "VPN 权限已授予；请再次点击启动隔离实验" else "未授予 VPN 权限，未启动实验"
        }
        // Never start from a system permission callback, including a late result after Stop/Back.
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            status = when (intent?.getStringExtra("state")) {
                "STARTING" -> "正在创建真实 TUN 和原生内核"
                "ENGINE_RUNNING_UNVERIFIED" -> "内核已接管本应用 TUN；尚未验证隧道流量或真实服务"
                "STOPPING" -> "正在停止内核并释放 TUN"
                "IDLE" -> "原生资源已释放，实验已停止"
                "CLEANUP_FAILED" -> "原生清理失败，正在终止隔离进程"
                else -> "实验未完成，已请求停止；请查看隔离测试报告"
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG || !BuildConfig.NATIVE_VPN_LAB) { finish(); return }
        val filter = IntentFilter(LabVpnService.ACTION_STATE)
        ContextCompat.registerReceiver(this, receiver, filter, "$packageName.permission.VPN_LAB_STATE",
            null, ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent { MaterialTheme { Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("原生 VPN 隔离实验", style = MaterialTheme.typography.headlineSmall)
            Text("仅 Debug 显式启用。只接管本应用流量；可能暂时中断本应用联网。使用本机回环假数据，不连接生产节点、读取订阅或保证实际可用。离开此页面会停止。")
            Text(status)
            if (Build.VERSION.SDK_INT >= 33) OutlinedButton(onClick = {
                notificationPending = true
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }, enabled = !hasStarted && !permissionPending && !notificationPending) { Text("申请实验通知权限（可选）") }
            Button(onClick = {
                if (VpnService.prepare(this@NativeVpnLabActivity) == null) startLab() else prepareVpn()
            }, enabled = !hasStarted && !permissionPending && !notificationPending) {
                Text(if (vpnGranted) "启动隔离实验" else "申请 VPN 权限")
            }
            if (hasStarted) Text("再次实验请停止并返回后重新进入此页面")
            OutlinedButton(onClick = { stopLab() }) { Text("停止实验") }
        } } }
    }
    private fun prepareVpn() {
        val permission = VpnService.prepare(this)
        if (permission == null) { vpnGranted = true; return }
        val ticket = permissionAttempt.begin() ?: return
        permissionTicket = ticket
        permissionPending = true
        vpnPermission.launch(permission)
    }
    private fun startLab() {
        requested = true
        hasStarted = true
        status = "正在启动隔离 VPN 进程"
        try { startForegroundService(Intent(this, LabVpnService::class.java).setAction(LabVpnService.ACTION_START)) }
        catch (_: Exception) { requested = false; status = "系统拒绝启动，未建立实验 VPN" }
    }
    private fun stopLab() {
        permissionAttempt.cancel()
        if (!requested) { status = "已取消启动；授权结果不会自动建立 VPN"; return }
        requested = false
        status = "已请求停止；等待原生清理确认"
        try { startService(Intent(this, LabVpnService::class.java).setAction(LabVpnService.ACTION_STOP)) }
        catch (_: Exception) { status = "无法确认停机，请在 Android VPN 设置断开本应用" }
    }
    override fun onStop() { stopLab(); super.onStop() }
    override fun onResume() { super.onResume(); vpnGranted = VpnService.prepare(this) == null }
    override fun onDestroy() {
        if (BuildConfig.NATIVE_VPN_LAB) unregisterReceiver(receiver)
        super.onDestroy()
    }
}
