package com.gccwebos.android.portal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A verified session owns this memory latch; a missing GET link cannot resolve a dispatched POST. */
internal class ReferralGenerationGuard {
    var unknown by mutableStateOf(false)
        private set
    fun begin(): Boolean {
        if (unknown) return false
        unknown = true
        return true
    }
    fun confirmKnownLink(path: String) {
        ReferralJson.shareUrl(path)
        unknown = false
    }
}

/** Account-scoped invitation reads and explicit generation/copy actions. */
internal class ReferralController(
    private val account: AccountController,
    private val scope: CoroutineScope,
    private val copy: (String) -> Unit,
    initialRepository: ReferralRepository? = null,
) {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var profile by mutableStateOf<ReferralProfile?>(null)
        private set
    var performance by mutableStateOf<ReferralPerformance?>(null)
        private set
    var profileError by mutableStateOf<String?>(null)
        private set
    var performanceError by mutableStateOf<String?>(null)
        private set
    var copied by mutableStateOf(false)
        private set
    /** True after the share message (not the bare link) was copied. */
    var copiedMessage by mutableStateOf(false)
        private set

    private val epoch = account.authSessionGeneration
    private var repo = initialRepository
    private var task: Job? = null
    private var requestId = 0L
    private var disposed = false
    private val generationGuard = account.referralGenerationGuard

    val canGenerate: Boolean get() = !busy && ready() && distributionAvailable() &&
        !generationGuard.unknown && profile?.linkPath == null
    val canCopy: Boolean get() = !busy && ready() && distributionAvailable() &&
        profile?.linkPath?.let(::validLink) == true

    fun load() = run { repository, request ->
        profileError = null; performanceError = null
        readPart(request, { repository.profile() }, { profileError = it }) {
            profile = it
            if (it.linkPath != null) generationGuard.confirmKnownLink(it.linkPath)
            else if (generationGuard.unknown) error = unknownMessage
        }
        readPart(request, { repository.performance() }, { performanceError = it }) {
            performance = it
        }
    }

    fun generateLink() {
        if (busy || !ready()) return
        val current = profile
        if (current?.eligible != true || profileError != null) {
            error = "请先读取邀请资格；当前账户暂不可生成邀请链接。"
            return
        }
        if (!distributionAvailable()) {
            error = "邀请归因窗口尚未提供，请重新读取邀请资料。"
            return
        }
        if (generationGuard.unknown) {
            error = unknownMessage
            return
        }
        current.linkPath?.let { path ->
            try {
                ReferralJson.shareUrl(path)
                error = null; copied = false
            } catch (_: Exception) {
                error = "邀请链接尚未确认，请重新读取邀请资料。"
            }
            return // A known link is reused without another generation request.
        }
        run(generating = true) { repository, request ->
            ensureCurrent(request)
            if (!generationGuard.begin()) { error = unknownMessage; return@run }
            val result = repository.generateLink()
            ensureCurrent(request)
            require(result.eligible && result.linkPath != null && result.attributionDays != null)
            ReferralJson.shareUrl(requireNotNull(result.linkPath))
            generationGuard.confirmKnownLink(requireNotNull(result.linkPath))
            profile = result; profileError = null
        }
    }

    /** The confirmed share URL, or null whenever copying would be refused. */
    fun shareUrl(): String? = if (canCopy) profile?.linkPath?.let { runCatching { ReferralJson.shareUrl(it) }.getOrNull() } else null

    fun copyShareMessage() {
        if (busy || !ready()) return
        val url = shareUrl()
        if (url == null) {
            copiedMessage = false
            error = "邀请链接尚未确认，请先读取或生成邀请链接。"
            return
        }
        try {
            copy(ReferralPresentation.shareMessage(url))
            if (ready()) { copiedMessage = true; copied = false; error = null }
        } catch (_: Exception) {
            if (ready()) { copiedMessage = false; error = "邀请文案未能复制，请重试。" }
        }
    }

    fun copyLink() {
        if (busy || !ready()) return
        val current = profile
        copiedMessage = false
        if (!canCopy || current?.linkPath == null) {
            copied = false
            error = "邀请链接尚未确认，请先读取或生成邀请链接。"
            return
        }
        try {
            val url = ReferralJson.shareUrl(current.linkPath)
            copy(url)
            if (ready()) { copied = true; error = null }
        } catch (_: Exception) {
            if (ready()) { copied = false; error = "邀请链接未能复制，请重试。" }
        }
    }

    fun dispose() {
        disposed = true
        requestId++
        task?.cancel(); task = null
        busy = false; error = null; profile = null; performance = null
        profileError = null; performanceError = null; copied = false; copiedMessage = false
    }

    private fun ready() = !disposed && scope.isActive && account.authenticated &&
        account.authSessionGeneration == epoch && account.referralGenerationGuard === generationGuard &&
        !account.restoring && !account.foregroundCheckPending

    private val unknownMessage = "邀请链接生成结果尚未确认，请重新读取；未读到链接时不会重复生成。"

    private fun distributionAvailable() = profile?.eligible == true && profileError == null &&
        profile?.attributionDays?.let { it in 1L..365L } == true

    private fun validLink(path: String) = try {
        ReferralJson.shareUrl(path)
        true
    } catch (_: Exception) {
        false
    }

    private suspend fun ensureCurrent(request: Long) {
        currentCoroutineContext().ensureActive()
        if (!ready() || requestId != request) throw CancellationException("Invitation request no longer current")
    }

    private suspend fun repository(request: Long): ReferralRepository {
        repo?.let { return it }
        val binding = account.commerceSessionBinding()
        ensureCurrent(request)
        return ReferralRepository { path, body ->
            currentCoroutineContext().ensureActive()
            if (!ready()) throw CancellationException("Invitation account changed")
            account.referral(path, body, binding).also {
                currentCoroutineContext().ensureActive()
                if (!ready()) throw CancellationException("Invitation account changed")
            }
        }.also { repo = it }
    }

    private fun run(generating: Boolean = false, action: suspend (ReferralRepository, Long) -> Unit) {
        if (busy || !ready()) return
        val request = ++requestId
        busy = true; error = null; copied = false; copiedMessage = false
        task = scope.launch {
            try {
                ensureCurrent(request)
                val repository = repository(request)
                ensureCurrent(request)
                action(repository, request)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                ensureCurrent(request)
                error = failureMessage(failure, generating)
                expireSessionIfNeeded(failure)
            } finally {
                if (requestId == request) busy = false
            }
        }
    }

    private suspend fun <T> readPart(request: Long, read: suspend () -> T,
        failed: (String) -> Unit, publish: (T) -> Unit) {
        ensureCurrent(request)
        try {
            val result = read()
            ensureCurrent(request)
            publish(result)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            ensureCurrent(request)
            failed(failureMessage(failure, false))
            expireSessionIfNeeded(failure)
        }
    }

    private fun failureMessage(failure: Exception, generating: Boolean) = when {
        failure is PortalFailure && failure.reason == PortalFailure.Reason.SESSION_EXPIRED -> "登录已到期，请重新登录。"
        failure is ReferralFailure && failure.status == 404 -> "邀请功能当前未开放或暂不可用。"
        generating -> "邀请链接生成结果尚未确认，请重新读取邀请资料后核对。"
        else -> "邀请信息尚未读取，请重试查询。"
    }

    private fun expireSessionIfNeeded(failure: Exception) {
        if (failure is PortalFailure && failure.reason == PortalFailure.Reason.SESSION_EXPIRED) {
            profile = null; performance = null; copied = false; copiedMessage = false
            account.commerceSessionExpired()
        }
    }
}
