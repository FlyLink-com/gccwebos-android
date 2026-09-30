package com.gccwebos.android.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gccwebos.android.BuildConfig

@Composable
fun UpdatePanel() {
    val scope = rememberCoroutineScope()
    val controller = remember(scope) {
        UpdateController(scope, UpdateRepository(), BuildConfig.VERSION_CODE.toLong(), BuildConfig.APPLICATION_ID)
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    var browserError by remember { mutableStateOf(false) }
    val result = (state as? UpdateState.Finished)?.result
    Column {
        Text("官网版本更新")
        if (BuildConfig.APPLICATION_ID != OfficialReleases.RELEASE_APPLICATION_ID) {
            Text("当前为测试包，不属于官网正式版升级链；请勿将正式包当作测试包的覆盖升级。")
        }
        Button(onClick = { browserError = false; controller.check() },
            enabled = state != UpdateState.Checking,
            modifier = Modifier.testTag("checkUpdate")) { Text(if (state == UpdateState.Checking) "正在检查…" else "检查更新") }
        if (state == UpdateState.Checking) TextButton(onClick = { controller.dismiss() }) { Text("取消检查") }
        val message = when (result) {
            UpdateResult.Unavailable -> "官网暂未发布正式 APK"
            UpdateResult.UpToDate -> "当前已是最新版本"
            UpdateResult.UnsupportedBuild -> "测试包不检查正式版覆盖升级"
            UpdateResult.NetworkError -> "检查失败，请稍后重试"
            UpdateResult.InvalidManifest -> "官网版本信息无效，已停止下载入口"
            else -> null
        }
        message?.let { Text(it, modifier = Modifier.testTag("updateStatus")) }
        if (browserError) Text("无法打开浏览器，请检查设备是否安装浏览器")
    }
    if (result is UpdateResult.NewVersion) {
        AlertDialog(
            onDismissRequest = { controller.dismiss() },
            title = { Text(if (result.belowMinimum) "当前版本已不受支持，请升级" else "发现新版本 ${result.release.versionName}") },
            text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text(result.release.notesZh.take(1200))
                Text("将打开官网 APK 地址，由浏览器下载并由你确认系统安装。")
                Text("官网 SHA-256：${result.release.sha256}\n此处展示清单值，未验证下载文件。")
            } },
            confirmButton = { TextButton(onClick = {
                // Revalidate at the boundary. Never request install permission or invoke a package installer.
                if (OfficialReleases.isDownloadPath(result.release.downloadPath)) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.release.downloadUrl))
                            .addCategory(Intent.CATEGORY_BROWSABLE))
                    } catch (_: ActivityNotFoundException) { browserError = true }
                    catch (_: SecurityException) { browserError = true }
                }
                controller.dismiss()
            }) { Text("前往官网下载") } },
            dismissButton = { TextButton(onClick = { controller.dismiss() }) { Text("稍后") } },
        )
    }
}
