package com.gccwebos.android.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.gccwebos.android.portal.TransientReadError

@Composable
fun UpdatePanel(autoCheck: Boolean = true, refreshRequest: Int = 0) {
    val scope = rememberCoroutineScope()
    val controller = remember(scope) {
        UpdateController(scope, UpdateRepository(), BuildConfig.VERSION_CODE.toLong(), BuildConfig.APPLICATION_ID)
    }
    val context = LocalContext.current
    UpdatePanelContent(controller, BuildConfig.APPLICATION_ID, autoCheck, refreshRequest) { url ->
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    }
}

/** Reading the fixed manifest on entry never opens a URL or invokes the package installer. */
@Composable
internal fun UpdatePanelContent(controller: UpdateController, applicationId: String, autoCheck: Boolean = true,
    refreshRequest: Int = 0, openWebsite: (String) -> Unit) {
    val automatic = LocalAutomaticUpdateController.current
    DisposableEffect(automatic) {
        val suppression = automatic?.suppressForManualCheck()
        onDispose { suppression?.close() }
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    LaunchedEffect(controller, autoCheck, refreshRequest) { if (autoCheck) controller.check() }
    val state by controller.state.collectAsState()
    var browserError by remember { mutableStateOf(false) }
    val result = (state as? UpdateState.Finished)?.result
    Column {
        Text("官网版本更新")
        if (applicationId != OfficialReleases.RELEASE_APPLICATION_ID) {
            Text("测试包不提供正式版覆盖升级")
        }
        val message = when (result) {
            UpdateResult.Unavailable -> "官网暂未发布正式 APK"
            UpdateResult.UpToDate -> "当前已是最新版本"
            UpdateResult.InvalidManifest -> "官网版本信息无效，已停止下载入口"
            else -> null
        }
        message?.let { Text(it, modifier = Modifier.testTag("updateStatus")) }
        TransientReadError(when {
            browserError -> "无法打开浏览器"
            result == UpdateResult.NetworkError -> "版本信息暂不可用"
            else -> null
        }, Modifier.testTag("update-read-error"))
    }
    if (result is UpdateResult.NewVersion && applicationId == OfficialReleases.RELEASE_APPLICATION_ID) {
        OfficialUpdateDialog(result, onDismiss = controller::dismiss, openWebsite = openWebsite,
            onShown = { automatic?.manualPromptShown(result.release.versionCode) },
            onBrowserFailure = { browserError = true })
    }
}

/** Shared confirmation boundary for automatic and explicit checks. */
@Composable
internal fun OfficialUpdateDialog(result: UpdateResult.NewVersion, onDismiss: () -> Unit,
    openWebsite: (String) -> Unit, onShown: () -> Unit = {}, onBrowserFailure: () -> Unit = {}) {
    LaunchedEffect(result.release.versionCode) { onShown() }
    AlertDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier.testTag("official-update-prompt"),
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
                        openWebsite(result.release.downloadUrl)
                    } catch (_: ActivityNotFoundException) { onBrowserFailure() }
                    catch (_: SecurityException) { onBrowserFailure() }
                }
                onDismiss()
            }) { Text("前往官网下载") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } },
        )
}
