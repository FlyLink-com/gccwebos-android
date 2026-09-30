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
import kotlinx.coroutines.ensureActive

/** Source-confirmed origins; constructing a client does not contact production. */
internal fun productionRepository(context: Context): PortalRepository {
    val policy = OriginPolicy("https://portal.gccwebos.com", setOf("https://sub.gccwebos.com"))
    return PortalRepository(HttpsPortalTransport(policy), EncryptedDeliveryStore(context), policy, SystemClock::elapsedRealtime, sessions = EncryptedSessionStore(context), profiles = EncryptedLocalProfileStore(context))
}

internal class AccountController(private val repository: PortalRepository, private val scope: CoroutineScope) {
    var account by mutableStateOf("")
    var password by mutableStateOf("")
    var answer by mutableStateOf("")
    var code by mutableStateOf("")
    var challenge by mutableStateOf<HumanChallenge?>(null)
    var factorReference by mutableStateOf<String?>(null)
    var authSessionGeneration by mutableStateOf(0L)
    var authenticated by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var clearing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var purchaseReturnRef by mutableStateOf<String?>(null)
    var security by mutableStateOf<AccountSecurity?>(null)
    var resource by mutableStateOf<Resource?>(null)
    var deliveryReceived by mutableStateOf(false)
    var importStatus by mutableStateOf<ImportStatus?>(null)
    var nodes by mutableStateOf<List<com.gccwebos.android.vpn.SafeProxyConfig.NodeSummary>>(emptyList())
    var restoring by mutableStateOf(false)
    var restorationPending by mutableStateOf(false)
    var foregroundCheckPending by mutableStateOf(false)
    var localSubscription by mutableStateOf(LocalSubscriptionSnapshot(LocalSubscriptionStatus.MISSING))
    private var acceptedAccountId: String? = null
    private var task: Job? = null
    private var expiryTask: Job? = null
    private var operation = 0L
    fun onForeground() {
        // Authenticator app round trips must retain the still-bounded TOTP ticket.
        if (factorReference != null || !authenticated) return
        foregroundCheckPending = true
        if (!busy) revalidateAccount()
    }
    fun restore() {
        if (busy) return
        expiryTask?.cancel()
        authenticated = false; security = null; resource = null; nodes = emptyList(); importStatus = null
        restoring = true; restorationPending = true
        run {
            try {
                localSubscription = repository.localSubscriptions.restore()
                nodes = localSubscription.nodes
                if (localSubscription.hasProfile) { restorationPending = false; return@run }
                val saved = repository.restoreSession()
                restorationPending = false
                if (saved != null) {
                    acceptAuthenticated(saved)
                    loadResource()
                }
            } finally { restoring = false; foregroundCheckPending = false }
        }
    }
    fun restoreAccount() = checkAccount(force = false)
    private fun revalidateAccount() = checkAccount(force = true)
    private fun checkAccount(force: Boolean) {
        if (busy || (authenticated && !force)) return
        expiryTask?.cancel(); authenticated = false; security = null
        restoring = true; restorationPending = true
        run {
            try {
                val saved = repository.restoreSession()
                restorationPending = false
                if (saved != null) { acceptAuthenticated(saved); loadResource() }
            } finally { restoring = false; foregroundCheckPending = false }
        }
    }
    fun readSecurity() = run { security = null; security = repository.security() }
    fun refreshSubscription() = run {
        localSubscription = repository.localSubscriptions.refresh(); nodes = localSubscription.nodes
    }
    fun challenge() = run {
        challenge = repository.humanChallenge()
    }
    fun login() {
        val human = challenge ?: return
        restorationPending = false
        val submittedPassword = password; password = ""
        val submittedAnswer = answer; answer = ""
        run {
            when(val result = repository.login(account.trim(), submittedPassword, human, submittedAnswer)) {
                is LoginResult.Authenticated -> { acceptAuthenticated(result.session); challenge = null; loadResource() }
                is LoginResult.TotpRequired -> { factorReference = result.reference; challenge = null }
            }
        }
    }
    fun totp() {
        val reference = factorReference ?: return
        val submitted = code; code = ""
        run {
            val result = repository.totp(reference, submitted)
            factorReference = null; acceptAuthenticated(result.session); loadResource()
        }
    }
    private suspend fun acceptAuthenticated(value: Session) {
        val remaining = repository.remainingSessionMillis()
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (acceptedAccountId != value.accountId) purchaseReturnRef = null
        acceptedAccountId = value.accountId
        security = null; account = value.accountId; authenticated = true; authSessionGeneration++
        localSubscription = repository.localSubscriptions.snapshot(); nodes = localSubscription.nodes
        expiryTask?.cancel()
        val authEpoch = authSessionGeneration
        expiryTask = scope.launch {
            kotlinx.coroutines.delay(remaining)
            if (authSessionGeneration == authEpoch && authenticated) {
                authenticated = false; security = null; resource = null; nodes = localSubscription.nodes; importStatus = null; deliveryReceived = false
                restoring = false; restorationPending = false; foregroundCheckPending = false
                error = "登录已到期，请重新登录"
                repository.cancelAuthentication()
            }
        }
    }
    fun refresh() = run {
        if (authenticated) loadResource() else {
            localSubscription = repository.localSubscriptions.refresh(); nodes = localSubscription.nodes
        }
    }
    fun restoreLocal() = run { localSubscription = repository.localSubscriptions.restore(); nodes = localSubscription.nodes }

    suspend fun commerce(path: String, body: String? = null, key: String? = null, expectedSession: String? = null) = repository.commerce(path, body, key, expectedSession)
    suspend fun commerceSessionBinding() = repository.commerceSessionBinding()
    suspend fun commerceAccountBinding() = repository.commerceAccountBinding()
    fun commerceSessionExpired() { expiryTask?.cancel(); authenticated = false; security = null; security = null; resource = null; nodes = localSubscription.nodes; importStatus = null; deliveryReceived = false; error = "登录已到期，请重新登录" }

    private suspend fun loadResource() {
        importStatus = null; deliveryReceived = false; resource = null
        try {
            if (localSubscription.hasProfile) {
                // Wallet login never silently replaces a separately saved local VPN capability.
                resource = repository.refresh()
            } else {
                val snapshot = repository.refreshAndImport(onResource = { resource = it }, onDeliveryReceived = { deliveryReceived = true })
                resource = snapshot.resource; importStatus = snapshot.status
                nodes = snapshot.nodes
            }
        } finally {
            localSubscription = repository.localSubscriptions.snapshot()
            if (localSubscription.hasProfile) nodes = localSubscription.nodes
        }
    }

    fun logout() = reset(clearLocal = false)
    fun clearLocal() = reset(clearLocal = true)
    fun cancelAuthentication() = reset(clearLocal = false)
    private fun reset(clearLocal: Boolean) {
        val current = ++operation
        task?.cancel(); expiryTask?.cancel()
        restoring = false; restorationPending = false; foregroundCheckPending = false
        if (clearLocal) localSubscription = LocalSubscriptionSnapshot(LocalSubscriptionStatus.MISSING)
        purchaseReturnRef = null; acceptedAccountId = null
        password = ""; answer = ""; code = ""; factorReference = null; challenge = null
        authenticated = false; security = null; resource = null; nodes = localSubscription.nodes; importStatus = null; deliveryReceived = false
        // Keep sign-in disabled until previous operation and local deletion complete.
        busy = true; clearing = true
        task = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                if (clearLocal) { repository.clearLocalData(); localSubscription = LocalSubscriptionSnapshot(LocalSubscriptionStatus.MISSING) } else repository.signOutAccount()
                nodes = localSubscription.nodes
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
                    PortalFailure.Reason.SESSION_EXPIRED -> { restorationPending = false; authenticated = false; security = null; resource = null; nodes = localSubscription.nodes; importStatus = null; deliveryReceived = false; "登录已到期，请重新登录" }
                    PortalFailure.Reason.LOGIN_FAILED -> { challenge = null; factorReference = null; "验证失败，请重新获取验证题并登录" }
                    PortalFailure.Reason.RECONCILIATION -> "资源正在对账，请稍后重试"
                    PortalFailure.Reason.RECOVERY_REQUIRED -> "上次交付需要恢复，请稍后重试或联系支持；不会重复领取"
                    PortalFailure.Reason.STORAGE -> "本地安全存储失败，交付状态需要恢复，请重试"
                    PortalFailure.Reason.RATE_LIMITED -> "请求过于频繁，请稍后重试"
                    PortalFailure.Reason.NETWORK -> "网络连接失败，请重试"
                    PortalFailure.Reason.PROTOCOL -> "服务响应格式不受支持，请稍后重试"
                    PortalFailure.Reason.SUBSCRIPTION_UNAVAILABLE -> "订阅暂不可用，请确认账户权益后重试"
                    PortalFailure.Reason.SUBSCRIPTION_REVOKED -> "订阅链接失效，请登录恢复或联系支持"
                    PortalFailure.Reason.SUBSCRIPTION_EXPIRED -> "订阅已到期，请前往套餐购买或续订"
                    else -> "服务暂不可用，请稍后重试"
                }
            } finally {
                if (current == operation) {
                    busy = false
                    if (foregroundCheckPending && authenticated && factorReference == null) revalidateAccount()
                }
            }
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
        SupportingText(if (state.authenticated) "订阅按当前账号权益同步，无需手动导入。" else "安全登录后查看余额、套餐和订阅线路。登录会加密保存在本机，服务端到期或失效后需重新验证。")
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
        if (state.restorationPending && !state.busy) {
            SupportingText("已保存的登录尚未通过服务器验证，主页保持锁定。网络恢复后可重试，或使用账号密码重新登录。")
            BrandButton("重新验证已保存登录", state::restoreAccount, modifier = Modifier.testTag("session-restore-retry"))
        }
        if (state.authenticated) {
            ResourceCard(state, showOperationState = false)
            state.importStatus?.let { status -> SupportingText(subscriptionImportMessage(status)) }
            if (state.deliveryReceived && state.importStatus == null) SupportingText(
                if (state.busy) "订阅已安全保存，正在下载并校验配置"
                else "订阅已安全保存；自动导入尚未完成，可刷新重试")
            BrandButton("刷新／重试", state::refresh, enabled = !state.busy, modifier = Modifier.testTag("account-refresh"))
            TextButton(onClick = { confirmReset = true }, enabled = !state.clearing,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("account-reset")) { Text("清除本地订阅与登录") }
            TextButton(onClick = state::logout, enabled = !state.clearing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("account-signout")) { Text("退出账户（保留本地订阅）") }
            SupportingText("退出钱包账户不会删除已保存的订阅。只有明确确认清除本地订阅才会移除它；本地清除不等于服务器撤销。")
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
        confirmButton = { TextButton(onClick = { confirmReset = false; state.clearLocal() }, modifier = Modifier.testTag("reset-confirm")) { Text("确认清除") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }, modifier = Modifier.testTag("reset-cancel")) { Text("取消") } })
}

internal fun subscriptionImportMessage(status: ImportStatus): String = when (status) {
    ImportStatus.NO_RESOURCE -> "账户暂无可导入资源；开通后刷新即可自动接收"
    ImportStatus.RECONCILIATION -> "资源正在对账，暂不导入"
    ImportStatus.EXPIRED -> "资源已到期，暂不导入"
    ImportStatus.QUOTA_EXHAUSTED -> "当前流量已用尽，暂不导入"
    ImportStatus.WAITING_DELIVERY -> "等待服务器交付；若曾清除本地订阅，请联系支持恢复"
    ImportStatus.UNSUPPORTED_CONFIGURATION -> "订阅已安全保存，但配置暂不兼容当前内核；不会尝试连接"
    ImportStatus.IMPORTED -> "订阅已通过离线兼容性校验，首条线路已导入预览；生产 VPN 尚未开放，暂不能连接"
}

@Composable
internal fun SecurityStatusPanel(state: AccountController, autoLoad: Boolean) {
    androidx.compose.runtime.LaunchedEffect(state.authSessionGeneration, state.authenticated) {
        if (autoLoad && state.authenticated) state.readSecurity()
    }
    if (!state.authenticated || state.restoring || state.foregroundCheckPending) return
    BrandCard(Modifier.testTag("account-security-state")) {
        Text("安全状态", style = MaterialTheme.typography.titleMedium)
        val status = state.security
        if (status == null) SupportingText(if (state.busy) "正在读取安全状态…" else "安全状态尚未确认，请重试读取。")
        else {
            SupportingText("身份验证器：${if (status.authenticatorBound) "已绑定" else "未绑定"}")
            SupportingText("通行密钥：${if (status.passkeyEnabled) "已启用" else "未启用"}")
            SupportingText("安全设置：${if (status.setupRequired) "待完成" else "无需补充"}")
        }
        BrandButton("重新读取安全状态", state::readSecurity, enabled = !state.busy)
        SupportingText("这里只展示服务器确认的状态，不修改安全凭据。")
    }
}
