package com.gccwebos.android.portal

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.gccwebos.android.ResourceCard
import com.gccwebos.android.design.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** Source-confirmed origins; constructing a client does not contact production. */
internal fun productionRepository(context: Context): PortalRepository {
    val policy = OriginPolicy("https://portal.gccwebos.com", setOf("https://sub.gccwebos.com"))
    return PortalRepository(HttpsPortalTransport(policy), EncryptedDeliveryStore(context), policy, SystemClock::elapsedRealtime)
}

internal class AccountController(private val repository: PortalRepository, private val scope: CoroutineScope) {
    var account by mutableStateOf("")
    var password by mutableStateOf("")
    var answer by mutableStateOf("")
    var code by mutableStateOf("")
    var challenge by mutableStateOf<HumanChallenge?>(null)
    var factorReference by mutableStateOf<String?>(null)
    var authenticated by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var clearing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var resource by mutableStateOf<Resource?>(null)
    var deliveryReady by mutableStateOf(false)
    private var task: Job? = null
    private var operation = 0L
    fun challenge() = run {
        challenge = repository.humanChallenge()
    }
    fun login() {
        val human = challenge ?: return
        val submittedPassword = password; password = ""
        val submittedAnswer = answer; answer = ""
        run {
            when(val result = repository.login(account.trim(), submittedPassword, human, submittedAnswer)) {
                is LoginResult.Authenticated -> { authenticated = true; challenge = null; loadResource() }
                is LoginResult.TotpRequired -> { factorReference = result.reference; challenge = null }
            }
        }
    }
    fun totp() {
        val reference = factorReference ?: return
        val submitted = code; code = ""
        run {
            repository.totp(reference, submitted)
            factorReference = null; authenticated = true; loadResource()
        }
    }
    fun refresh() = run { loadResource() }
    private suspend fun loadResource() {
        deliveryReady = false
        val current = repository.refresh(); resource = current
        if (current is Resource.Available && current.setupAvailable) {
            repository.acquire(current); deliveryReady = true
        }
    }
    fun logout() = reset(clearLocal = true)
    fun cancelAuthentication() = reset(clearLocal = false)
    private fun reset(clearLocal: Boolean) {
        val current = ++operation
        task?.cancel()
        password = ""; answer = ""; code = ""; factorReference = null; challenge = null
        authenticated = false; resource = null; deliveryReady = false
        // Keep sign-in disabled until previous operation and local deletion complete.
        busy = true; clearing = true
        task = scope.launch {
            try {
                if (clearLocal) repository.logout() else repository.cancelAuthentication()
                error = null
            }
            catch (_: PortalFailure) { error = "本地安全存储清理失败，请重试退出" }
            finally { if (current == operation) { busy = false; clearing = false } }
        }
    }
    private fun run(action: suspend () -> Unit) {
        if (busy) return
        val current = ++operation
        busy = true; error = null
        task = scope.launch {
            try { action() }
            catch (failure: CancellationException) { throw failure }
            catch (failure: PortalFailure) {
                if (current != operation) return@launch
                error = when(failure.reason) {
                    PortalFailure.Reason.SESSION_EXPIRED -> { authenticated = false; resource = null; "登录已到期，请重新登录" }
                    PortalFailure.Reason.LOGIN_FAILED -> { challenge = null; factorReference = null; "验证失败，请重新获取验证题并登录" }
                    PortalFailure.Reason.RECONCILIATION -> "资源正在对账，请稍后重试"
                    PortalFailure.Reason.RECOVERY_REQUIRED -> "上次交付需要恢复，请稍后重试或联系支持；不会重复领取"
                    PortalFailure.Reason.STORAGE -> "本地安全存储失败，交付状态需要恢复，请重试"
                    PortalFailure.Reason.RATE_LIMITED -> "请求过于频繁，请稍后重试"
                    PortalFailure.Reason.NETWORK -> "网络连接失败，请重试"
                    PortalFailure.Reason.PROTOCOL -> "服务响应格式不受支持，请稍后重试"
                    else -> "服务暂不可用，请稍后重试"
                }
            } finally { if (current == operation) busy = false }
        }
    }
}

@Composable
internal fun AccountPanel(state: AccountController) {
    var confirmReset by remember { mutableStateOf(false) }
    BrandCard(Modifier.testTag("account-panel")) {
        Eyebrow("账户与资源")
        Text(if (state.authenticated) "已登录" else if (state.factorReference != null) "完成身份验证" else "欢迎回来",
            style = MaterialTheme.typography.titleLarge)
        SupportingText("会话到期后需要重新登录。订阅由服务器确认权益后自动领取，无需手动导入。")
        if (state.busy) CircularProgressIndicator(modifier = Modifier.testTag("account-loading"))
        val statusMessage = state.error ?: when {
            state.busy -> "正在处理，请稍候"
            state.authenticated -> "登录成功，可查看当前资源"
            state.factorReference != null -> "需要身份验证器中的动态验证码"
            else -> "请登录你的账号"
        }
        Text(statusMessage, style = MaterialTheme.typography.bodyMedium,
            color = if (state.error != null) MaterialTheme.colorScheme.error else Brand.Muted,
            modifier = Modifier.testTag(if (state.error != null) "account-error" else "account-status")
                .semantics { liveRegion = LiveRegionMode.Polite })
        if (state.authenticated) {
            ResourceCard(state, showOperationState = false)
            if (state.deliveryReady) SupportingText("订阅已安全保存；生产 VPN 尚未开放，暂不能连接")
            BrandButton("刷新／重试", state::refresh, enabled = !state.busy, modifier = Modifier.testTag("account-refresh"))
            TextButton(onClick = { confirmReset = true }, enabled = !state.clearing,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("account-reset")) { Text("本地退出并清除订阅") }
            SupportingText("本地退出不等于服务器撤销会话或已发放凭据")
        } else if (state.factorReference != null) {
            SupportingText("请输入身份验证器中的 TOTP 验证码")
            OutlinedTextField(state.code, { state.code = it }, enabled = !state.busy,
                label = { Text("TOTP 验证码") }, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().testTag("totp-code"))
            BrandButton("验证并登录", state::totp, enabled = !state.busy && state.code.isNotBlank())
            TextButton(onClick = state::cancelAuthentication, enabled = !state.clearing,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("totp-cancel")) { Text("取消验证") }
        } else {
            OutlinedTextField(state.account, { state.account = it }, label = { Text("账号／邮箱") }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().testTag("login-account"))
            OutlinedTextField(state.password, { state.password = it }, label = { Text("密码") }, enabled = !state.busy,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("login-password"))
            state.challenge?.let { challenge ->
                SupportingText(challenge.prompt)
                OutlinedTextField(state.answer, { state.answer = it }, label = { Text("验证题答案") }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("login-answer"))
            }
            TextButton(onClick = state::challenge, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("login-challenge")) { Text("获取／更换验证题") }
            BrandButton("登录", state::login, enabled = !state.busy && state.challenge != null && state.account.isNotBlank() && state.password.isNotBlank() && state.answer.isNotBlank(),
                modifier = Modifier.testTag("login-submit"))
        }
        if (!state.authenticated) {
            TextButton(onClick = { confirmReset = true }, enabled = !state.clearing,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("account-reset")) { Text("清除本地订阅并重置登录") }
        }
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false },
        title = { Text("清除本地登录与订阅？") },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            Text("已领取的订阅删除后可能需要联系客服恢复。此操作仅清除本机材料，不会撤销服务器会话或凭据。")
        } },
        confirmButton = { TextButton(onClick = { confirmReset = false; state.logout() }, modifier = Modifier.testTag("reset-confirm")) { Text("确认清除") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }, modifier = Modifier.testTag("reset-cancel")) { Text("取消") } })
}
