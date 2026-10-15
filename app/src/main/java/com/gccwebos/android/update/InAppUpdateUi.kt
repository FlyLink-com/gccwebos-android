package com.gccwebos.android.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import java.util.Locale

internal fun megabytes(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))

internal fun downloadingText(state: InAppUpdateState.Downloading): String {
    val received = megabytes(state.received)
    val head = if (state.resumedFrom > 0) "继续下载（已保留 ${megabytes(state.resumedFrom)}）\n" else "正在下载\n"
    val total = state.total
    return if (total != null && total > 0) {
        "$head$received / ${megabytes(total)}（${(state.received * 100 / total).coerceIn(0, 100)}%）"
    } else "$head$received"
}

internal fun failureText(failure: InAppFailure, httpCode: Int? = null, resumable: Boolean = false): String = when (failure) {
    InAppFailure.NETWORK -> if (resumable) "下载中断，已保留已下载部分，点「重试」可继续。" else "网络不可用或下载中断，请稍后重试。"
    InAppFailure.HTTP -> "官网暂时无法提供安装包${httpCode?.let { "（状态码 $it）" }.orEmpty()}，请稍后重试。"
    InAppFailure.REJECTED -> "下载地址或服务器响应不符合要求，已停止。"
    InAppFailure.SIZE_MISMATCH -> "下载的文件大小与服务器声明不一致，已删除，请重试。"
    InAppFailure.STORAGE_FULL -> "手机存储空间不足，请清理后重试。"
    InAppFailure.TOO_LARGE -> "安装包超出允许的大小，已停止。"
    InAppFailure.HASH_MISMATCH -> "文件校验未通过（SHA-256 与官网清单不一致），已删除，请重试。"
    InAppFailure.STORAGE -> "无法写入应用缓存目录，请检查存储空间后重试。"
    InAppFailure.UNREADABLE -> "无法读取安装包，已删除，请重试。"
    InAppFailure.WRONG_PACKAGE -> "该文件不是飞连VPN官方安装包，已删除，不会安装。"
    InAppFailure.NOT_NEWER -> "该安装包的版本不高于当前版本，已删除，不会安装。"
    InAppFailure.WRONG_VERSION -> "该安装包的版本与官网清单不一致，已删除，不会安装。"
    InAppFailure.SIGNER_MISMATCH -> "安装包的签名与当前应用不一致，已删除，不会安装。"
    InAppFailure.INSTALLER_UNAVAILABLE -> "无法打开系统安装程序，请改用官网下载。"
}

/** Body text of the update dialog for one in-app state. Fixed local copy only; no manifest text is executed. */
@Composable
internal fun InAppUpdateBody(state: InAppUpdateState) {
    when (state) {
        InAppUpdateState.Idle -> Text("下载完成后会校验 SHA-256、版本和签名，再交给系统安装程序确认，不会静默安装。")
        InAppUpdateState.Preparing -> Text("正在连接官网…", Modifier.testTag("in-app-update-status"))
        is InAppUpdateState.ConfirmMetered -> Text(
            "当前正在使用移动数据或计费网络。安装包${state.sizeBytes?.let { "约 ${megabytes(it)}" } ?: "较大"}，下载会消耗流量，是否继续？",
            Modifier.testTag("in-app-update-status"))
        is InAppUpdateState.Downloading -> Column(Modifier.testTag("in-app-update-status")) {
            Text(downloadingText(state), Modifier.testTag("in-app-update-progress-text"))
            val total = state.total
            if (total != null && total > 0) {
                LinearProgressIndicator(progress = { (state.received.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().testTag("in-app-update-progress"))
            }
        }
        InAppUpdateState.Verifying -> Text("正在校验文件（SHA-256、版本、签名）…", Modifier.testTag("in-app-update-status"))
        is InAppUpdateState.ReadyToInstall -> Text("已下载，并通过 SHA-256、版本和签名校验。点「安装」后由系统安装程序确认。",
            Modifier.testTag("in-app-update-status"))
        is InAppUpdateState.NeedsInstallPermission -> Text(
            "已下载并通过校验。安装前需要允许飞连VPN「安装未知应用」：点「去设置」开启后返回本页继续。",
            Modifier.testTag("in-app-update-status"))
        InAppUpdateState.InstallerLaunched -> Text("已打开系统安装程序，请按提示完成安装。", Modifier.testTag("in-app-update-status"))
        is InAppUpdateState.Failed -> Text(failureText(state.failure, state.httpCode, state.resumable), Modifier.testTag("in-app-update-status"))
    }
}

@Composable
internal fun InAppUpdateConfirm(state: InAppUpdateState, controller: InAppUpdateController, release: ReleaseManifest.Available) {
    when (state) {
        InAppUpdateState.Idle -> TextButton(onClick = { controller.start(release) }, modifier = Modifier.testTag("in-app-update-start")) { Text("下载并安装") }
        InAppUpdateState.Preparing, is InAppUpdateState.Downloading, InAppUpdateState.Verifying -> Unit
        is InAppUpdateState.ConfirmMetered -> TextButton(onClick = controller::continueOnMetered, modifier = Modifier.testTag("in-app-update-metered-continue")) { Text("继续下载") }
        is InAppUpdateState.ReadyToInstall -> TextButton(onClick = controller::install, modifier = Modifier.testTag("in-app-update-install")) { Text("安装") }
        is InAppUpdateState.NeedsInstallPermission -> TextButton(onClick = controller::openInstallSettings, modifier = Modifier.testTag("in-app-update-settings")) { Text("去设置") }
        InAppUpdateState.InstallerLaunched -> Unit
        is InAppUpdateState.Failed -> if (state.failure.retryable) {
            TextButton(onClick = controller::retry, modifier = Modifier.testTag("in-app-update-retry")) { Text("重试") }
        }
    }
}

internal fun InAppUpdateState.cancelLabel(): String = when (this) {
    InAppUpdateState.Idle -> "稍后"
    InAppUpdateState.Preparing, is InAppUpdateState.Downloading, InAppUpdateState.Verifying -> "取消"
    is InAppUpdateState.ConfirmMetered -> "取消"
    InAppUpdateState.InstallerLaunched, is InAppUpdateState.Failed -> "关闭"
    is InAppUpdateState.ReadyToInstall, is InAppUpdateState.NeedsInstallPermission -> "取消"
}
