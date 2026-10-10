package com.gccwebos.android.portal

import android.content.Context
import android.os.SystemClock
import com.gccwebos.android.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import com.gccwebos.android.R
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
    return PortalRepository(HttpsPortalTransport(policy), EncryptedDeliveryStore(context), policy, SystemClock::elapsedRealtime,
        sessions = EncryptedSessionStore(context), profiles = EncryptedLocalProfileStore(context),
        allowHostnamePreparation = BuildConfig.CONNECTION_ENDPOINT_HOSTNAME,
        manualSources = EncryptedManualSourceStore(context),
        nodeChoices = EncryptedNodeChoiceStore(context))
}

/** Process-local ownership only; identity contains no account label or bearer data. */
internal class CommerceSessionIdentity

internal class AccountController(private val repository: PortalRepository, private val scope: CoroutineScope,
    nodeLatencyProbe: NodeLatencyProbe? = null,
    sourceCommitFence: ((() -> Unit) -> Unit) = { error("Connection owner is unavailable") },
    /** 退出登录: revokes the session and forgets this device's account subscription; imported configurations stay. */
    private val logoutClearingAccount: suspend () -> Unit = { repository.logoutClearingAccountSubscription() }) {
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
    var authenticationSubmitting by mutableStateOf(false)
        private set
    var clearing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var purchaseReturnRef by mutableStateOf<String?>(null)
    var security by mutableStateOf<AccountSecurity?>(null)
    var resource by mutableStateOf<Resource?>(null)
    var deliveryReceived by mutableStateOf(false)
    var importStatus by mutableStateOf<ImportStatus?>(null)
    var importRejection by mutableStateOf<com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason?>(null)
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
    val nodeSelection: NodeSelectionController = NodeSelectionController(repository.localSubscriptions, scope,
        authGeneration = { authSessionGeneration },
        allowed = { !busy && !restoring && !foregroundCheckPending && !clearing && !manualImport.busy && !configurationLibrary.busy },
        onSnapshot = { localSubscription = it; nodes = it.nodes })
    val accountUsage = AccountUsageController(scope,
        reader = { owner, epoch -> repository.readAccountOverview(owner, epoch) },
        onSessionExpired = { commerceSessionExpired() })
    val nodeLatency: NodeLatencyController = NodeLatencyController(repository.localSubscriptions, scope, nodeLatencyProbe,
        authGeneration = { authSessionGeneration },
        allowed = { !busy && !restoring && !foregroundCheckPending && !clearing && !manualImport.busy && !configurationLibrary.busy })
    val configurationLibrary: ConfigurationLibraryController = ConfigurationLibraryController(repository.localSubscriptions, scope,
        allowed = { !busy && !restoring && !foregroundCheckPending && !clearing && !manualImport.busy && !nodeSelection.busy },
        commitFence = sourceCommitFence,
        onSnapshot = { nodeLatency.cancel(); localSubscription = it; nodes = it.nodes; nodeSelection.expireIfNeeded() })
    val manualImport: ManualImportController = ManualImportController(repository.localSubscriptions, scope,
        allowed = { !busy && !restoring && !foregroundCheckPending && !clearing && !nodeSelection.busy &&
            !accountUsage.state.value.busy && !accountUsage.state.value.verifying && !configurationLibrary.busy },
        commitFence = sourceCommitFence,
        onSnapshot = {
            nodeLatency.cancel()
            localSubscription = it; nodes = it.nodes; nodeSelection.expireIfNeeded()
        }, onSettled = { scope.launch {
            configurationLibrary.refresh()
            if (foregroundCheckPending && authenticated && !busy && !manualImport.busy && !clearing) revalidateAccount()
        } })
    fun refreshAccountUsage() { if (!manualImport.busy) accountUsage.update() }
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
        configurationLibrary.cancel()
        // Handler delays can pause during sleep; the repository's elapsed clock cannot.
        expireLoginChallengeIfNeeded()
        // Authenticator app round trips must retain the still-bounded TOTP ticket.
        if (factorReference != null || !authenticated) return
        accountUsage.suspendForVerification()
        foregroundCheckPending = true
        if (!busy && !manualImport.busy) revalidateAccount()
    }
    fun restore() {
        if (busy || manualImport.busy) return
        clearAccountUsage()
        expiryTask?.cancel()
        authenticated = false; security = null; resource = null; nodes = emptyList(); importStatus = null; importRejection = null
        restoring = true; restorationPending = true
        run {
            try {
                localSubscription = repository.localSubscriptions.restore()
                nodes = localSubscription.nodes
                // Cached connection state and a saved account must both restore on a fresh launch.
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
        if (busy || manualImport.busy || factorReference != null || (authenticated && !force)) return
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
    fun readSecurity() = run { security = repository.security() }
    fun refreshSubscription() {
        if (busy || manualImport.busy || restoring || foregroundCheckPending || clearing) return
        val recoveryPending = try { authenticated && repository.hasPendingLegacyRecovery() }
        catch (failure: PortalFailure) { run { throw failure }; return }
        if (recoveryPending) run { loadResource() }
        else nodeSelection.refresh()
    }
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
        run(authenticationSubmission = true) {
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
        run(authenticationSubmission = true) {
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
                authenticated = false; security = null; resource = null; nodes = localSubscription.nodes; importStatus = null; importRejection = null; deliveryReceived = false
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
                    !accountUsage.isCurrentAcceptedObservation(preparedOverview, expectedAuth) || !currentUsage.authenticated ||
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
    fun commerceSessionExpired() { clearAccountUsage(); nodeSelection.invalidate(); expiryTask?.cancel(); account = displayEmail.orEmpty(); commerceSessionIdentity = null; authenticated = false; security = null; referralGenerationGuard = ReferralGenerationGuard(); emailHint = null; secondFactorEmail = null; resource = null; nodes = localSubscription.nodes; importStatus = null; importRejection = null; deliveryReceived = false; error = "登录已到期，请重新登录" }

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
        importStatus = null; importRejection = null; deliveryReceived = false; resource = null
        try {
            if (repository.localSubscriptions.sourceKind() != ManualSourceKind.LegacyRemote ||
                localSubscription.hasProfile && !repository.hasPendingLegacyRecovery()) {
                // Wallet login never silently replaces a separately saved local VPN capability.
                publish(repository.refreshWithObservation())
            } else {
                val snapshot = repository.refreshAndImportObserved(onOverview = ::publish, onDeliveryReceived = { deliveryReceived = true })
                if (!authenticated || expectedAuthEpoch != authSessionGeneration || expectedUsage != usageAuthentication)
                    throw CancellationException("Resource observation owner changed")
                // onOverview already published the ordered resource. Do not overwrite it
                // again after setup/download with a possibly superseded snapshot.
                importStatus = snapshot.status
                importRejection = snapshot.rejection
                nodes = snapshot.nodes
            }
        } finally {
            localSubscription = repository.localSubscriptions.snapshot()
            if (localSubscription.hasProfile) nodes = localSubscription.nodes
            nodeSelection.sync()
        }
    }

    /** 退出登录: log out and clear this device's account subscription (not manually imported configurations, not the server account). */
    fun logout() = reset(clearAccountSubscription = true)
    /** Abandon an in-progress sign-in; keeps whatever local subscription exists. */
    fun cancelAuthentication() = reset(clearAccountSubscription = false)
    private fun reset(clearAccountSubscription: Boolean) {
        manualImport.cancel()
        configurationLibrary.clearDisplay()
        nodeLatency.cancel()
        clearAccountUsage()
        commerceSessionIdentity = null
        nodeSelection.invalidate()
        cancelLoginQuestionWork(); automaticQuestionLease = loginFormLease
        if (authenticated) account = displayEmail.orEmpty()
        val current = ++operation
        task?.cancel(); expiryTask?.cancel()
        authenticationSubmitting = false
        restoring = false; restorationPending = false; foregroundCheckPending = false
        if (clearAccountSubscription) localSubscription = LocalSubscriptionSnapshot(LocalSubscriptionStatus.STORAGE_ERROR,
            sourceKind = ManualSourceKind.None)
        purchaseReturnRef = null; acceptedAccountId = null; acceptedSessionBinding = null; emailHint = null; secondFactorEmail = null
        referralGenerationGuard = ReferralGenerationGuard()
        password = ""; answer = ""; code = ""; factorReference = null; challenge = null
        authenticated = false; security = null; resource = null; nodes = localSubscription.nodes; importStatus = null; importRejection = null; deliveryReceived = false
        // Keep sign-in disabled until previous operation and local deletion complete.
        busy = true; clearing = true
        task = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                if (clearAccountSubscription) { logoutClearingAccount(); localSubscription = repository.localSubscriptions.snapshot() } else repository.signOutAccount()
                nodes = localSubscription.nodes
                error = null
            }
            catch (_: PortalFailure) { error = "本地安全存储清理失败，请重试退出" }
            finally { if (current == operation) { busy = false; clearing = false } }
        }
    }
    private fun run(authenticationSubmission: Boolean = false, action: suspend () -> Unit) {
        if (busy || manualImport.busy) return
        // Account/security work cannot race a local selection read or revive its late result.
        if (nodeSelection.busy) nodeSelection.cancel()
        nodeLatency.cancel()
        val current = ++operation
        busy = true; error = null
        authenticationSubmitting = authenticationSubmission
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
                    PortalFailure.Reason.SESSION_EXPIRED -> { clearAccountUsage(); commerceSessionIdentity = null; nodeSelection.invalidate(); restorationPending = false; authenticated = false; security = null; resource = null; nodes = localSubscription.nodes; importStatus = null; importRejection = null; deliveryReceived = false; "登录已到期，请重新登录" }
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
                    authenticationSubmitting = false
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
    var confirmLogout by remember { mutableStateOf(false) }
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
        else Spacer(Modifier.size(24.dp).testTag("login-starting"))
    } else AccountPanelContent(useCard = !compactLogin || state.authenticated) {
        Eyebrow("账户与资源")
        Text(if (state.authenticated) "已登录" else if (state.factorReference != null) "完成身份验证" else "欢迎回来",
            style = MaterialTheme.typography.titleLarge)
        val statusMessage = when {
            !state.authenticated && state.error != null -> state.error
            state.factorReference != null -> "需要身份验证器中的动态验证码"
            else -> null
        }
        statusMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
            color = if (state.error != null) MaterialTheme.colorScheme.error else Brand.Muted,
            modifier = Modifier.testTag(if (state.error != null) "account-error" else "account-status")
                .semantics { liveRegion = LiveRegionMode.Polite }) }
        if (state.authenticated) {
            ResourceCard(state, showOperationState = false, showReadProgress = false)
            TransientReadError(state.error ?: when (state.importStatus) {
                ImportStatus.UNSUPPORTED_CONFIGURATION -> state.importRejection?.let(::subscriptionRejectionMessage)
                    ?: "订阅配置暂不兼容"
                ImportStatus.WAITING_DELIVERY -> "订阅暂未交付"
                else -> null
            }, Modifier.testTag("account-read-error"))
            TextButton(onClick = { confirmLogout = true }, enabled = !state.clearing,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("account-logout")) { Text(LOGOUT_LABEL) }
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
    }
    if (confirmLogout) LogoutDialog(onConfirm = { confirmLogout = false; state.logout() }, onDismiss = { confirmLogout = false })
}

/** The one logout action: plain words, this device only, server account untouched. */
internal const val LOGOUT_LABEL = "退出登录"
internal const val LOGOUT_TITLE = "是否退出登录并清除当前设备订阅配置？"
internal const val LOGOUT_BODY = "将退出登录，并清除这台设备上保存的账户订阅配置。只影响当前设备，不会删除或更改服务器上的账户；" +
    "你自己导入的配置会保留。账户订阅清除后，重新登录即可再次获取。"
internal const val LOGOUT_CONFIRM = "退出登录"

@Composable
internal fun LogoutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(LOGOUT_TITLE, modifier = Modifier.testTag("logout-title")) },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            Text(LOGOUT_BODY, modifier = Modifier.testTag("logout-body"))
        } },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("logout-confirm")) { Text(LOGOUT_CONFIRM) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("logout-cancel")) { Text("取消") } })
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
            val status = state.error
            status?.let {
                Text(it, color = if (state.error != null) MaterialTheme.colorScheme.error else Brand.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(if (state.error != null) "account-error" else "account-status")
                        .semantics { liveRegion = LiveRegionMode.Polite })
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
    state.challengeLoading || state.busy -> state.challenge?.prompt ?: "验证题"
    state.challengeFailureReason == PortalFailure.Reason.NETWORK -> "无网络"
    state.challengeExpired || state.challengeError != null -> "换一道题"
    state.challenge != null -> state.challenge!!.prompt
    else -> "换一道题"
}

@Composable
private fun LoginQuestionPanel(state: AccountController, modifier: Modifier, onRefresh: () -> Unit,
                               refreshEnabled: Boolean) {
    val display = loginQuestionText(state)
    TextButton(onClick = onRefresh, enabled = refreshEnabled,
        modifier = modifier.heightIn(min = 48.dp).testTag("login-challenge")
            .semantics { contentDescription = if (refreshEnabled) "$display，点击更换验证题" else display }) {
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
    ImportStatus.IMPORTED -> "订阅已导入，可在节点页选择后连接"
}

/** Fixed categories only; never expose a configuration value or parser exception. */
internal fun subscriptionRejectionMessage(reason: com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason): String = when (reason) {
    com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.InputSyntax -> "订阅配置格式暂不受支持"
    com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.TopLevelOptions -> "配置包含暂不支持的全局选项"
    com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.Protocol -> "配置包含暂不支持的协议"
    com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.NodeOptions -> "配置包含暂不支持的节点选项"
    com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.NodeOptionUdp -> "节点 UDP 选项暂不受支持"
    com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.NodeOptionTfo -> "节点 TCP 快速打开选项暂不受支持"
    com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.NodeOptionIpVersion -> "节点 IP 版本选项暂不受支持"
    com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.NodeOptionPorts -> "节点多端口选项暂不受支持"
    com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.NodeOptionFingerprint -> "节点证书指纹选项暂不受支持"
    com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.NodeOptionHopInterval -> "节点端口跳跃间隔选项暂不受支持"
    com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.NodeOptionBbrProfile -> "节点 BBR 配置选项暂不受支持"
    com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.NodeOptionToken -> "节点认证选项暂不受支持"
    com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.NodeOptionDisableSni -> "节点 TLS 域名选项暂不受支持"
    com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.NodeOptionReduceRtt -> "节点往返时延优化选项暂不受支持"
    com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.NodeOptionRequestTimeout -> "节点请求超时选项暂不受支持"
    com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.NodeOptionHeartbeatInterval -> "节点心跳间隔选项暂不受支持"
    com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.NodeOptionMaxUdpRelayPacketSize -> "节点 UDP 中继包大小选项暂不受支持"
    com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.NodeOptionFastOpen -> "节点快速连接选项暂不受支持"
    com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.NodeOptionMaxOpenStreams -> "节点并发流数量选项暂不受支持"
    com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.Endpoint -> "节点地址格式暂不受支持"
    com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.EndpointHostname -> "节点使用域名地址，当前尚未支持"
    com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.Port -> "节点端口格式不正确"
    com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.ProtocolOptions -> "节点协议参数暂不兼容"
    com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.Selection -> "所选节点不可用，请重新选择"
}

@Composable
internal fun SecurityStatusPanel(state: AccountController, autoLoad: Boolean, refreshRequest: Int = 0) {
    var entryReadStarted by remember(state, state.authSessionGeneration, state.authenticated) { mutableStateOf(false) }
    var handledRequest by remember(state, state.authSessionGeneration) { mutableStateOf(refreshRequest) }
    LaunchedEffect(state, state.authSessionGeneration, state.authenticated, autoLoad) {
        if (autoLoad && state.authenticated) {
            // One read per entry/verified owner; account restoration can still own the busy slot.
            snapshotFlow { !state.busy && !state.restoring && !state.foregroundCheckPending && !state.clearing }
                .first { it }
            if (!entryReadStarted) { entryReadStarted = true; state.readSecurity() }
        }
    }
    LaunchedEffect(state, state.authSessionGeneration, autoLoad, refreshRequest) {
        if (handledRequest != refreshRequest) {
            handledRequest = refreshRequest
            // Navigation while busy coalesces into the active read; it does not cancel or queue it.
            if (autoLoad && state.authenticated && !state.busy && !state.restoring &&
                !state.foregroundCheckPending && !state.clearing) {
                entryReadStarted = true
                state.readSecurity()
            }
        }
    }
    if (!state.authenticated || state.restoring || state.foregroundCheckPending) return
    // 账户与安全 has exactly two functions: 通行密钥 and 绑定身份验证器. No account-resource detail here.
    val status = state.security
    var confirmLogout by remember { mutableStateOf(false) }
    SectionCard(Modifier.testTag("account-security-state"), contentPadding = PaddingValues(horizontal = GccSpacing.Medium, vertical = GccSpacing.ExtraSmall),
        verticalSpacing = 0.dp) {
        if (status == null && state.busy) {
            // First read in flight: the shell is already on screen with placeholders for both rows.
            SkeletonCards(count = 2, cardHeight = 56.dp, spacing = GccSpacing.Small, shape = GccShapes.medium, lines = 0,
                modifier = Modifier.padding(vertical = GccSpacing.Small).testTag("security-skeleton"))
        } else {
            SecurityRow(R.drawable.brand_lock, "通行密钥", status?.let { if (it.passkeyEnabled) "已启用" else "未启用" },
                on = status?.passkeyEnabled == true, tag = "security-passkey")
            HorizontalDivider(color = Brand.Border.copy(alpha = .35f))
            SecurityRow(R.drawable.brand_shield, "绑定身份验证器", status?.let { if (it.authenticatorBound) "已绑定" else "未绑定" },
                on = status?.authenticatorBound == true, tag = "security-authenticator")
        }
        // Kept (not a third feature): 退出登录 = log out and forget this device's account subscription.
        HorizontalDivider(color = Brand.Border.copy(alpha = .35f))
        TextButton(onClick = { confirmLogout = true }, enabled = !state.clearing,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("account-logout")) {
            Text(LOGOUT_LABEL, style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
        }
    }
    if (status == null && !state.busy) {
        SupportingText("暂时无法读取安全状态。", Modifier.testTag("security-unavailable"))
        TextButton(onClick = state::readSecurity, modifier = Modifier.heightIn(min = 48.dp).testTag("security-retry")) { Text("重新读取") }
    }
    if (confirmLogout) LogoutDialog(onConfirm = { confirmLogout = false; state.logout() }, onDismiss = { confirmLogout = false })
}

/** One status line; [value] null means "not read yet" and shows a dash rather than a guess. */
@Composable
private fun SecurityRow(icon: Int, title: String, value: String?, on: Boolean, tag: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = GccSpacing.ExtraSmall).testTag(tag)
        .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GccSpacing.Medium)) {
        androidx.compose.material3.Icon(painterResource(icon), null, tint = if (on) Brand.Cyan else Brand.Muted, modifier = Modifier.size(22.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, color = Brand.Text, modifier = Modifier.weight(1f))
        Surface(shape = GccShapes.pill, color = (if (on) Brand.Cyan else Brand.Muted).copy(alpha = .12f)) {
            Text(value ?: "—", style = MaterialTheme.typography.labelMedium, color = if (on) Brand.Cyan else Brand.Muted,
                modifier = Modifier.padding(horizontal = GccSpacing.Medium, vertical = GccSpacing.ExtraSmall).testTag("$tag-status"))
        }
    }
}

/** Read failures are announced once per visible result; revisiting the page owns retrying the read. */
@Composable
internal fun TransientReadError(message: String?, modifier: Modifier = Modifier) {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.takeIf { it.isNotBlank() }?.let { host.showSnackbar(it, duration = SnackbarDuration.Short) }
    }
    SnackbarHost(host, modifier)
}
