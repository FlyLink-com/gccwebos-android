package com.gccwebos.android.portal

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

/** Source-confirmed origins; constructing a client does not contact production. */
internal fun productionRepository(context: Context): PortalRepository {
    val policy = OriginPolicy("https://portal.gccwebos.com", setOf("https://sub.gccwebos.com"))
    return PortalRepository(HttpsPortalTransport(policy), EncryptedDeliveryStore(context), policy, SystemClock::elapsedRealtime, sessions = EncryptedSessionStore(context), profiles = EncryptedLocalProfileStore(context))
}

/** Process-local ownership only; identity contains no account label or bearer data. */
internal class CommerceSessionIdentity

internal class AccountController(private val repository: PortalRepository, private val scope: CoroutineScope) {
    var account by mutableStateOf("")
    var password by mutableStateOf("")
    var answer by mutableStateOf("")
    var code by mutableStateOf("")
    var challenge by mutableStateOf<HumanChallenge?>(null)
    var challengeLoading by mutableStateOf(false)
        private set
    var challengeError by mutableStateOf<String?>(null)
        private set
    var challengeFailureReason by mutableStateOf<PortalFailure.Reason?>(null)
        private set
    var challengeExpired by mutableStateOf(false)
        private set
    var factorReference by mutableStateOf<String?>(null)
    var authSessionGeneration by mutableStateOf(0L)
    internal var commerceSessionIdentity by mutableStateOf<CommerceSessionIdentity?>(null)
        private set
    internal val retainingCommerceSession: Boolean get() = commerceSessionIdentity != null &&
        (restoring || foregroundCheckPending)
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
    private var acceptedAccountId by mutableStateOf<String?>(null)
    val authenticatedAccountId: String? get() = acceptedAccountId?.takeIf { authenticated }
    private var acceptedSessionBinding: String? = null
    private var usageAuthentication: AccountUsageAuthentication? = null
    /** Survives page disposal and exact same-session foreground validation, never another identity. */
    internal var referralGenerationGuard by mutableStateOf(ReferralGenerationGuard())
        private set
    private class EmailHint(val accountId: String, val session: String, val generation: Long, val email: String)
    private var emailHint by mutableStateOf<EmailHint?>(null)
    private var secondFactorEmail: Pair<String, String?>? = null
    /** A local display hint, never an account ID or an unverified MFA identity. */
    val displayEmail: String? get() = emailHint?.takeIf {
        authenticated && !restoring && !foregroundCheckPending && it.accountId == acceptedAccountId &&
            it.session == acceptedSessionBinding && it.generation == authSessionGeneration
    }?.email
    private var task: Job? = null
    private var expiryTask: Job? = null
    private var operation = 0L
    private var connectionPreparationOperation: Long? = null
    private var loginFormGeneration = 0L
    private var loginFormLease: Long? = null
    private var automaticQuestionLease: Long? = null
    private var questionRequest = 0L
    private var questionJob: Job? = null
    private var questionExpiryJob: Job? = null
    private var automaticQuestionJob: Job? = null
    val nodeSelection = NodeSelectionController(repository.localSubscriptions, scope,
        authGeneration = { authSessionGeneration },
        allowed = { !busy && !restoring && !foregroundCheckPending && !clearing },
        onSnapshot = { localSubscription = it; nodes = it.nodes })
    val accountUsage = AccountUsageController(scope,
        reader = { owner, epoch -> repository.readAccountOverview(owner, epoch) },
        onSessionExpired = { commerceSessionExpired() })
    fun refreshAccountUsage() = accountUsage.update()
    fun cancelAccountUsageUpdate() = accountUsage.cancel()
    private fun clearAccountUsage() {
        usageAuthentication = null
        accountUsage.bind(null, null, authSessionGeneration)
    }
    internal fun acceptAccountOverviewObservation(overview: BoundAccountOverview,
        expectedAuthentication: AccountUsageAuthentication, expectedAuthEpoch: Long): Boolean {
        if (!authenticated || expectedAuthEpoch != authSessionGeneration || expectedAuthentication != usageAuthentication ||
            overview.context.owner != expectedAuthentication.owner || overview.context.repositoryEpoch != expectedAuthentication.repositoryEpoch)
            return false
        if (!accountUsage.observeExternal(overview, expectedAuthEpoch)) return false
        resource = overview.resource
        return true
    }
    fun onForeground() {
        // Handler delays can pause during sleep; the repository's elapsed clock cannot.
        expireLoginChallengeIfNeeded()
        // Authenticator app round trips must retain the still-bounded TOTP ticket.
        if (factorReference != null || !authenticated) return
        accountUsage.suspendForVerification()
        foregroundCheckPending = true
        if (!busy) revalidateAccount()
    }
    fun restore() {
        if (busy) return
        clearAccountUsage()
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
            } finally { restoring = false; foregroundCheckPending = false; nodeSelection.sync() }
        }
    }
    fun restoreAccount() = checkAccount(force = false)
    private fun revalidateAccount() = checkAccount(force = true)
    private fun checkAccount(force: Boolean) {
        if (busy || factorReference != null || (authenticated && !force)) return
        accountUsage.suspendForVerification()
        val checkOperation = operation + 1
        expiryTask?.cancel(); authenticated = false; security = null
        restoring = true; restorationPending = true
        run {
            try {
                val saved = repository.restoreSession()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (operation != checkOperation) return@run
                restorationPending = false
                if (saved != null) { acceptAuthenticated(saved); loadResource() }
            } finally {
                if (operation == checkOperation) {
                    if (!authenticated) { commerceSessionIdentity = null; clearAccountUsage() }
                    restoring = false; foregroundCheckPending = false
                }
            }
        }
    }
    fun readSecurity() = run { security = null; security = repository.security() }
    fun refreshSubscription() = nodeSelection.refresh()
    /** Attaching the form must not race AppShell's initial saved-session restoration. */
    fun enterLoginForm(): Long {
        cancelLoginQuestionWork()
        automaticQuestionLease = null
        return (++loginFormGeneration).also { loginFormLease = it }
    }
    fun leaveLoginForm(lease: Long) {
        if (loginFormLease != lease) return
        loginFormLease = null; automaticQuestionLease = null
        cancelLoginQuestionWork()
    }
    fun ensureLoginChallenge(lease: Long) {
        if (loginFormLease != lease || automaticQuestionLease == lease) return
        automaticQuestionLease = lease
        automaticQuestionJob = scope.launch {
            androidx.compose.runtime.snapshotFlow { !busy && !restoring && !foregroundCheckPending && !clearing }
                .first { it }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (loginFormLease == lease && !authenticated && factorReference == null) requestLoginQuestion(lease)
        }
    }
    /** Explicit refresh also supports legacy controller callers with no mounted form. */
    fun challenge() {
        if (!canRequestLoginQuestion()) return
        automaticQuestionLease = loginFormLease
        automaticQuestionJob?.cancel(); automaticQuestionJob = null
        requestLoginQuestion(loginFormLease)
    }
    fun refreshLoginChallenge(lease: Long) {
        if (loginFormLease == lease) challenge()
    }
    private fun canRequestLoginQuestion() = !busy && !restoring && !foregroundCheckPending && !clearing &&
        !authenticated && factorReference == null && !challengeLoading
    private fun questionOwnerCurrent(request: Long, lease: Long?, epoch: Long) = questionRequest == request &&
        (lease == null || loginFormLease == lease) && authSessionGeneration == epoch &&
        !authenticated && factorReference == null && !restoring && !foregroundCheckPending && !clearing
    private fun requestLoginQuestion(lease: Long?) {
        if (!canRequestLoginQuestion() || (lease != null && loginFormLease != lease)) return
        val request = ++questionRequest; val epoch = authSessionGeneration
        questionExpiryJob?.cancel(); questionExpiryJob = null
        challenge = null; answer = ""; challengeExpired = false; challengeError = null; challengeFailureReason = null; error = null
        challengeLoading = true
        questionJob = scope.launch {
            try {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!questionOwnerCurrent(request, lease, epoch)) return@launch
                val fresh = repository.humanChallenge()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!questionOwnerCurrent(request, lease, epoch)) return@launch
                val remaining = repository.remainingHumanChallengeMillis(fresh)
                if (remaining == 0L) { challengeExpired = true; return@launch }
                challenge = fresh
                if (fresh.expiresElapsed != Long.MAX_VALUE) questionExpiryJob = scope.launch {
                    kotlinx.coroutines.delay(remaining)
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (questionOwnerCurrent(request, lease, epoch) && challenge == fresh) {
                        challenge = null; answer = ""; challengeExpired = true
                    }
                }
            } catch (failure: CancellationException) { throw failure }
            catch (failure: PortalFailure) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (questionOwnerCurrent(request, lease, epoch)) {
                    challengeFailureReason = failure.reason
                    challengeError = when (failure.reason) {
                        PortalFailure.Reason.NETWORK -> "无网络"
                        PortalFailure.Reason.RATE_LIMITED -> "请求过于频繁，请稍后刷新。"
                        PortalFailure.Reason.PROTOCOL -> "题目格式暂不受支持，请刷新。"
                        else -> "题目读取失败，请刷新重试。"
                    }
                }
            } catch (_: Exception) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (questionOwnerCurrent(request, lease, epoch)) challengeError = "验证题读取失败，请刷新重试。"
            } finally { if (questionRequest == request) challengeLoading = false }
        }
    }
    private fun cancelLoginQuestionWork() {
        questionRequest++
        automaticQuestionJob?.cancel(); automaticQuestionJob = null
        questionJob?.cancel(); questionJob = null
        questionExpiryJob?.cancel(); questionExpiryJob = null
        challenge = null; answer = ""; challengeLoading = false; challengeError = null; challengeFailureReason = null; challengeExpired = false
    }
    private fun expireLoginChallengeIfNeeded(): Boolean {
        val current = challenge ?: return false
        if (repository.remainingHumanChallengeMillis(current) != 0L) return false
        cancelLoginQuestionWork(); automaticQuestionLease = loginFormLease; challengeExpired = true
        return true
    }
    fun login() {
        if (busy || challengeLoading) return
        if (expireLoginChallengeIfNeeded()) return
        val human = challenge ?: return
        val submittedAccount = account.trim()
        val submittedEmail = submittedAccount.takeIf { Regex("[^\\s@]+@[^\\s@]+").matches(it) }
        emailHint = null; secondFactorEmail = null
        restorationPending = false
        val submittedPassword = password; password = ""
        val submittedAnswer = answer; answer = ""
        // A submitted challenge may be consumed even when the response is lost or rejected.
        cancelLoginQuestionWork(); automaticQuestionLease = loginFormLease
        run {
            when(val result = repository.login(submittedAccount, submittedPassword, human, submittedAnswer)) {
                is LoginResult.Authenticated -> { acceptAuthenticated(result.session, submittedEmail); challenge = null; loadResource() }
                is LoginResult.TotpRequired -> { secondFactorEmail = result.reference to submittedEmail; factorReference = result.reference; challenge = null }
            }
        }
    }
    fun totp() {
        if (busy) return
        val reference = factorReference ?: return
        val submittedEmail = secondFactorEmail?.takeIf { it.first == reference }?.second
        val submitted = code; code = ""
        run {
            val result = repository.totp(reference, submitted)
            factorReference = null; secondFactorEmail = null; acceptAuthenticated(result.session, submittedEmail); loadResource()
        }
    }
    private suspend fun acceptAuthenticated(value: Session, submittedEmail: String? = null) {
        val remaining = repository.remainingSessionMillis()
        val usageIdentity = repository.captureAccountUsageAuthentication(AccountUsageOwner.fromSession(value))
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (acceptedAccountId != value.accountId) purchaseReturnRef = null
        val binding = sessionBinding(value.token)
        if (commerceSessionIdentity == null || acceptedAccountId != value.accountId || acceptedSessionBinding != binding)
            commerceSessionIdentity = CommerceSessionIdentity()
        if (acceptedAccountId != value.accountId || acceptedSessionBinding != binding)
            referralGenerationGuard = ReferralGenerationGuard()
        val email = submittedEmail ?: emailHint?.takeIf { it.accountId == value.accountId && it.session == binding }?.email
        acceptedAccountId = value.accountId; acceptedSessionBinding = binding
        nodeSelection.invalidate()
        security = null; account = email.orEmpty(); authenticated = true; authSessionGeneration++
        usageAuthentication = usageIdentity
        accountUsage.bind(usageIdentity.owner, usageIdentity.repositoryEpoch, authSessionGeneration)
        nodeSelection.sync()
        emailHint = email?.let { EmailHint(value.accountId, binding, authSessionGeneration, it) }
        localSubscription = repository.localSubscriptions.snapshot(); nodes = localSubscription.nodes
        expiryTask?.cancel()
        val authEpoch = authSessionGeneration
        expiryTask = scope.launch {
            kotlinx.coroutines.delay(remaining)
            if (authSessionGeneration == authEpoch && authenticated) {
                clearAccountUsage()
                nodeSelection.invalidate()
                account = displayEmail.orEmpty()
                referralGenerationGuard = ReferralGenerationGuard()
                commerceSessionIdentity = null
                authenticated = false; security = null; resource = null; nodes = localSubscription.nodes; importStatus = null; deliveryReceived = false
                emailHint = null; secondFactorEmail = null
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
    /** One explicit connection intent may prepare existing entitlement before the UI dispatches.
     * This callback belongs only to this operation/auth generation; it does not start a VPN.
     * busy remains true during callback, so callers must validate the request rather than waive
     * the general busy guard for another action.
     */
    fun prepareForConnection(onPrepared: () -> Unit): Long? {
        if (!authenticated || busy || restoring || foregroundCheckPending || clearing) return null
        val expectedAuth = authSessionGeneration
        val expectedAccount = acceptedAccountId
        val expectedSession = acceptedSessionBinding
        val expectedOperation = operation + 1
        connectionPreparationOperation = expectedOperation
        run {
            try {
                var acceptedOverview: BoundAccountOverview? = null
                loadResource { acceptedOverview = it }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val preparedOverview = acceptedOverview ?: return@run
                val currentUsage = accountUsage.state.value
                if (preparedOverview.resource !is Resource.Available || resource !== preparedOverview.resource ||
                    currentUsage.resource !== preparedOverview.resource || !currentUsage.authenticated ||
                    currentUsage.verifying || currentUsage.error != null) return@run
                if (operation != expectedOperation || !authenticated || authSessionGeneration != expectedAuth ||
                    acceptedAccountId != expectedAccount || acceptedSessionBinding != expectedSession ||
                    restoring || foregroundCheckPending || clearing) return@run
                val current = localSubscription
                if (current.status == LocalSubscriptionStatus.READY && current.hasProfile && current.hasCapability &&
                    current.validUntil != null) onPrepared()
            } finally {
                if (connectionPreparationOperation == expectedOperation) connectionPreparationOperation = null
            }
        }
        return expectedOperation.takeIf { connectionPreparationOperation == it }
    }
    fun isConnectionPreparationPending(request: Long): Boolean =
        connectionPreparationOperation == request && operation == request && busy
    /** Cancel only this preparation request, keeping the session and durable delivery intact. */
    fun cancelConnectionPreparation(request: Long) {
        if (connectionPreparationOperation != request || operation != request) return
        connectionPreparationOperation = null
        operation++
        task?.cancel(); task = null
        busy = false; error = "准备已取消"
        if (foregroundCheckPending && authenticated) revalidateAccount()
    }
    fun restoreLocal() = run { localSubscription = repository.localSubscriptions.restore(); nodes = localSubscription.nodes }

    suspend fun commerce(path: String, body: String? = null, key: String? = null, expectedSession: String? = null) = repository.commerce(path, body, key, expectedSession)
    suspend fun commerceSessionBinding() = repository.commerceSessionBinding()
    suspend fun commerceAccountBinding() = repository.commerceAccountBinding()
    internal suspend fun referral(path: String, body: String? = null, expectedSession: String? = null) = repository.referral(path, body, expectedSession)
    fun commerceSessionExpired() { clearAccountUsage(); nodeSelection.invalidate(); expiryTask?.cancel(); account = displayEmail.orEmpty(); commerceSessionIdentity = null; authenticated = false; security = null; referralGenerationGuard = ReferralGenerationGuard(); emailHint = null; secondFactorEmail = null; resource = null; nodes = localSubscription.nodes; importStatus = null; deliveryReceived = false; error = "登录已到期，请重新登录" }

    private suspend fun loadResource(onAcceptedOverview: (BoundAccountOverview) -> Unit = {}) {
        val expectedUsage = usageAuthentication
        val expectedAuthEpoch = authSessionGeneration
        fun publish(overview: BoundAccountOverview) {
            // Original typed read context is retained even for singleton None/Reconciliation.
            if (!authenticated || expectedAuthEpoch != authSessionGeneration || expectedUsage == null ||
                overview.context.owner != expectedUsage.owner || overview.context.repositoryEpoch != expectedUsage.repositoryEpoch)
                throw CancellationException("Resource observation owner changed")
            // Auth matching alone is insufficient: the original read must also win the
            // resource/cycle publication fence before the legacy resource field changes.
            if (acceptAccountOverviewObservation(overview, expectedUsage, expectedAuthEpoch)) onAcceptedOverview(overview)
        }
        importStatus = null; deliveryReceived = false; resource = null
        try {
            if (localSubscription.hasProfile) {
                // Wallet login never silently replaces a separately saved local VPN capability.
                publish(repository.refreshWithObservation())
            } else {
                val snapshot = repository.refreshAndImportObserved(onOverview = ::publish, onDeliveryReceived = { deliveryReceived = true })
                if (!authenticated || expectedAuthEpoch != authSessionGeneration || expectedUsage != usageAuthentication)
                    throw CancellationException("Resource observation owner changed")
                // onOverview already published the ordered resource. Do not overwrite it
                // again after setup/download with a possibly superseded snapshot.
                importStatus = snapshot.status
                nodes = snapshot.nodes
            }
        } finally {
            localSubscription = repository.localSubscriptions.snapshot()
            if (localSubscription.hasProfile) nodes = localSubscription.nodes
            nodeSelection.sync()
        }
    }

    fun logout() = reset(clearLocal = false)
    fun clearLocal() = reset(clearLocal = true)
    fun cancelAuthentication() = reset(clearLocal = false)
    private fun reset(clearLocal: Boolean) {
        clearAccountUsage()
        commerceSessionIdentity = null
        nodeSelection.invalidate()
        cancelLoginQuestionWork(); automaticQuestionLease = loginFormLease
        if (authenticated) account = displayEmail.orEmpty()
        val current = ++operation
        task?.cancel(); expiryTask?.cancel()
        restoring = false; restorationPending = false; foregroundCheckPending = false
        if (clearLocal) localSubscription = LocalSubscriptionSnapshot(LocalSubscriptionStatus.MISSING)
        purchaseReturnRef = null; acceptedAccountId = null; acceptedSessionBinding = null; emailHint = null; secondFactorEmail = null
        referralGenerationGuard = ReferralGenerationGuard()
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
        // Account/security work cannot race a local selection read or revive its late result.
        if (nodeSelection.busy) nodeSelection.cancel()
        val current = ++operation
        busy = true; error = null
        task = scope.launch {
            try { action() }
            catch (failure: CancellationException) { throw failure }
            catch (failure: PortalFailure) {
                if (current != operation) return@launch
                if (failure.reason == PortalFailure.Reason.SESSION_EXPIRED) {
                    account = if (authenticated) displayEmail.orEmpty() else ""
                    referralGenerationGuard = ReferralGenerationGuard()
                }
                if (failure.reason in setOf(PortalFailure.Reason.SESSION_EXPIRED, PortalFailure.Reason.LOGIN_FAILED, PortalFailure.Reason.STORAGE) || !authenticated) {
                    emailHint = null; secondFactorEmail = null
                }
                error = when(failure.reason) {
                    PortalFailure.Reason.SESSION_EXPIRED -> { clearAccountUsage(); commerceSessionIdentity = null; nodeSelection.invalidate(); restorationPending = false; authenticated = false; security = null; resource = null; nodes = localSubscription.nodes; importStatus = null; deliveryReceived = false; "登录已到期，请重新登录" }
                    PortalFailure.Reason.LOGIN_FAILED -> { challenge = null; factorReference = null; "验证失败，请刷新题目后重试" }
                    PortalFailure.Reason.RECONCILIATION -> "资源正在对账，请稍后重试"
                    PortalFailure.Reason.RECOVERY_REQUIRED -> when (failure.recoveryCause) {
                        PortalFailure.RecoveryCause.RESOURCE_CHANGED -> "资源已变化，现有交付无法安全重绑，请联系支持"
                        PortalFailure.RecoveryCause.SESSION_PENDING -> "上次交付未完成，当前会话无法确认，请联系支持"
                        PortalFailure.RecoveryCause.MODEL_CHANGED -> "交付模型已变化，恢复已停止，请联系支持"
                        PortalFailure.RecoveryCause.JOURNAL_CONFLICT -> "本地恢复记录已变化，请重新检查后重试"
                        null -> "上次交付需要恢复，请联系支持；不会重复领取"
                    }
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
internal fun AccountPanel(state: AccountController, compactLogin: Boolean = false,
                          registrationOpener: ((String) -> Unit)? = null,
                          loginStartupReady: Boolean = true,
                          registrationOverride: RegistrationController? = null,
                          startRegistration: Boolean = false) {
    var confirmReset by remember { mutableStateOf(false) }
    var registering by remember(state, startRegistration) { mutableStateOf(startRegistration) }
    val registrationScope = rememberCoroutineScope()
    val registration = remember(state, registrationOverride) {
        registrationOverride ?: RegistrationController(registrationScope, productionRegistrationGateway(),
            authGeneration = { state.authSessionGeneration },
            registrationAllowed = { !state.authenticated && !state.busy && !state.restoring &&
                !state.foregroundCheckPending && !state.clearing && state.factorReference == null })
    }
    if (compactLogin && !state.authenticated && state.factorReference == null) {
        if (loginStartupReady) {
            if (registering) RegistrationPanel(registration, registrationOpener, onLogin = {
                state.account = registration.completedLoginEmail() ?: registration.email
                registering = false
            }) else CompactLoginPanel(state, onRegister = {
                registration.email = state.account
                state.password = ""
                registering = true
            })
        }
        else CircularProgressIndicator(Modifier.size(24.dp).testTag("login-starting"), color = Brand.Cyan)
    } else AccountPanelContent(useCard = !compactLogin || state.authenticated) {
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
            BrandButton("登录", state::login, enabled = !state.busy && state.challenge != null && state.account.isNotBlank() && normalizedLoginPassword(state.password).isNotEmpty() && state.answer.isNotBlank(),
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

internal const val OFFICIAL_REGISTRATION_URL = "https://portal.gccwebos.com/portal/register/"

/** Production login and second-factor forms share the background without an outer card. */
@Composable
private fun AccountPanelContent(useCard: Boolean, content: @Composable ColumnScope.() -> Unit) {
    if (useCard) BrandCard(Modifier.testTag("account-panel"), content)
    else Column(Modifier.fillMaxWidth().testTag("account-panel"),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/** Form entry owns only the question lifecycle, never authentication or account storage. */
@Composable
private fun CompactLoginPanel(state: AccountController, onRegister: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val topGap = if (WindowInsets.ime.getBottom(LocalDensity.current) > 0) 0.dp else 36.dp
    var loginLease by remember(state) { mutableStateOf<Long?>(null) }
    DisposableEffect(state) {
        val lease = state.enterLoginForm()
        loginLease = lease
        onDispose { state.leaveLoginForm(lease) }
    }
    LaunchedEffect(state, loginLease, state.busy, state.restoring, state.foregroundCheckPending) {
        loginLease?.let(state::ensureLoginChallenge)
    }
    Column(Modifier.fillMaxWidth().padding(top = topGap).testTag("plain-login-layout"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.fillMaxWidth().testTag("login-form"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("欢迎回来", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f).testTag("login-welcome"))
            }
            CompactLoginField(state.account, { state.account = it }, "邮箱", "login-account",
                enabled = !state.busy, keyboardType = KeyboardType.Email)
            CompactLoginField(state.password, { state.password = it }, "密码", "login-password",
                enabled = !state.busy, keyboardType = KeyboardType.Password, password = true)
            BoxWithConstraints(Modifier.fillMaxWidth().testTag("login-answer-question-row")) {
                val answerField: @Composable (Modifier) -> Unit = { modifier ->
                    CompactLoginField(state.answer, { state.answer = it }, "输入算术题答案", "login-answer",
                        enabled = !state.busy && !state.challengeLoading && state.challenge != null,
                        keyboardType = KeyboardType.Text, modifier = modifier,
                        imeAction = ImeAction.Done, onDone = { keyboard?.hide() })
                }
                val question: @Composable (Modifier) -> Unit = { modifier ->
                    LoginQuestionPanel(state, modifier, onRefresh = { loginLease?.let(state::refreshLoginChallenge) },
                        refreshEnabled = loginLease != null && !state.busy && !state.challengeLoading && !state.clearing)
                }
                if (maxWidth < 300.dp || LocalDensity.current.fontScale > 1.3f) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        answerField(Modifier.fillMaxWidth())
                        question(Modifier.fillMaxWidth())
                    }
                } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    answerField(Modifier.weight(.44f))
                    question(Modifier.weight(.56f))
                }
            }
            if (state.busy) CircularProgressIndicator(Modifier.size(24.dp).testTag("account-loading"), color = Brand.Cyan)
            val status = state.error ?: if (state.busy) "正在处理，请稍候" else null
            status?.let {
                Text(it, color = if (state.error != null) MaterialTheme.colorScheme.error else Brand.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(if (state.error != null) "account-error" else "account-status")
                        .semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (state.restorationPending && !state.busy) {
                SupportingText("已保存的登录需要服务器重新验证。")
                BrandButton("重新验证已保存登录", state::restoreAccount, modifier = Modifier.testTag("session-restore-retry"))
            }
            BrandButton("登录", state::login,
                enabled = !state.busy && !state.challengeLoading && state.challenge != null && state.account.isNotBlank() && normalizedLoginPassword(state.password).isNotEmpty() && state.answer.isNotBlank(),
                modifier = Modifier.testTag("login-submit"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRegister, enabled = !state.busy && !state.restoring && !state.foregroundCheckPending,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("login-register")) { Text("注册") }
            }
        }
    }
}

/** Exactly one visible state occupies the refresh target; network is never a fallback placeholder. */
internal fun loginQuestionText(state: AccountController): String = when {
    state.challengeLoading || state.busy -> "加载中…"
    state.challengeFailureReason == PortalFailure.Reason.NETWORK -> "无网络"
    state.challengeExpired || state.challengeError != null -> "刷新重试"
    state.challenge != null -> state.challenge!!.prompt
    else -> "刷新重试"
}

@Composable
private fun LoginQuestionPanel(state: AccountController, modifier: Modifier, onRefresh: () -> Unit,
                               refreshEnabled: Boolean) {
    val display = loginQuestionText(state)
    TextButton(onClick = onRefresh, enabled = refreshEnabled,
        modifier = modifier.heightIn(min = 48.dp).testTag("login-challenge")
            .semantics { contentDescription = if (refreshEnabled) "$display，点击刷新验证题" else display }) {
        Text(display, color = Brand.Cyan, fontSize = 16.sp,
            modifier = Modifier.testTag("login-challenge-prompt").semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/** A 48dp minimum, single-line field that can grow with the user's font setting. */
@Composable
internal fun CompactLoginField(value: String, onValueChange: (String) -> Unit, label: String, tag: String,
                              enabled: Boolean, keyboardType: KeyboardType, modifier: Modifier = Modifier,
                              password: Boolean = false, imeAction: ImeAction = ImeAction.Next,
                              onDone: (() -> Unit)? = null) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(value, onValueChange, enabled = enabled, singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = if (enabled) Brand.Text else Brand.Muted, fontSize = 14.sp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        interactionSource = interaction, cursorBrush = SolidColor(Brand.Cyan),
        modifier = modifier.heightIn(min = 48.dp).clip(shape)
            .background(Brand.Background.copy(alpha = .35f))
            .border(1.dp, if (focused) Brand.Cyan else Brand.Border, shape)
            .testTag(tag).semantics { contentDescription = label },
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(label, color = Brand.Muted, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp))
                inner()
            }
        })
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
