package com.gccwebos.android.portal

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gccwebos.android.design.Brand
import com.gccwebos.android.design.BrandButton
import com.gccwebos.android.design.SupportingText

/** Editing is local. Missing verified legal documents block both anonymous mutations. */
@Composable
internal fun RegistrationPanel(state: RegistrationController, documentOpener: ((String) -> Unit)?,
                               onLogin: () -> Unit) {
    val context = LocalContext.current
    var documentError by remember { mutableStateOf(false) }
    DisposableEffect(state) {
        val lease = state.enterRegistrationForm()
        onDispose { state.leaveRegistrationForm(lease) }
    }
    BackHandler(onBack = onLogin)
    Column(Modifier.fillMaxWidth().testTag("registration-form"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("注册", style = MaterialTheme.typography.headlineSmall)
        if (state.receipt != null) {
            Text("注册成功，请登录", modifier = Modifier.testTag("registration-success")
                .semantics { liveRegion = LiveRegionMode.Polite })
        } else {
            CompactLoginField(state.email, { state.email = it }, "邮箱", "registration-email",
                enabled = !state.busy, keyboardType = KeyboardType.Email, modifier = Modifier.fillMaxWidth())
            CompactLoginField(state.password, { state.password = it }, "新密码", "registration-password",
                enabled = !state.busy, keyboardType = KeyboardType.Password, password = true, modifier = Modifier.fillMaxWidth())
            SupportingText("8–160位，含大写字母、数字和符号")
            CompactLoginField(state.confirmPassword, { state.confirmPassword = it }, "确认密码", "registration-confirm-password",
                enabled = !state.busy, keyboardType = KeyboardType.Password, password = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                CompactLoginField(state.verificationCode, { state.verificationCode = it }, "邮箱验证码", "registration-code",
                    enabled = !state.busy, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                TextButton(onClick = state::requestEmailVerification,
                    enabled = !state.busy && !state.pendingUnknown,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("registration-send-code")) {
                    Text("获取")
                }
            }
            if (state.verificationRemainingSeconds > 0) {
                val seconds = state.verificationRemainingSeconds
                SupportingText("验证码有效时间 ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                    Modifier.testTag("registration-code-expiry"))
            } else if (state.verificationExpired) SupportingText("验证码已过期，请重新获取",
                Modifier.testTag("registration-code-expiry"))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.legalAccepted, onCheckedChange = { state.legalAccepted = it },
                    enabled = state.legalAvailable && !state.busy, modifier = Modifier.testTag("registration-legal"))
                Text(if (state.legalAvailable) "同意服务条款与隐私政策" else "服务条款暂不可用",
                    style = MaterialTheme.typography.bodySmall, color = Brand.Muted, modifier = Modifier.weight(1f))
            }
            state.legalPolicy?.takeIf { it.configured }?.let { policy ->
                fun openDocument(url: String) {
                    documentError = false
                    try {
                        if (documentOpener != null) documentOpener(url)
                        else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: ActivityNotFoundException) { documentError = true }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { openDocument(policy.termsUrl) }, enabled = !state.busy) { Text("服务条款") }
                    TextButton(onClick = { openDocument(policy.privacyUrl) }, enabled = !state.busy) { Text("隐私政策") }
                }
            }
            state.error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("registration-error").semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (documentError) SupportingText("无法打开条款")
            BrandButton("注册", state::register,
                enabled = !state.busy && !state.pendingUnknown, modifier = Modifier.testTag("registration-submit"))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onLogin, modifier = Modifier.heightIn(min = 48.dp).testTag("registration-login")) { Text("登录") }
        }
    }
}
