package com.gccwebos.android.portal

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Separate anonymous registration form; never accepts or creates AccountController authentication. */
internal class RegistrationController(private val scope: CoroutineScope, private val gateway: RegistrationGateway,
    val legalPolicy: RegistrationLegalPolicy? = null,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val authGeneration: () -> Long = { 0L },
    private val registrationAllowed: () -> Boolean = { true }) {
    private var emailValue by mutableStateOf("")
    var email: String
        get() = emailValue
        set(value) {
            if (emailValue == value) return
            invalidateWork()
            emailValue = value; receipt = null; completedEmail = null; error = null
        }
    var password by mutableStateOf("")
    var confirmPassword by mutableStateOf("")
    var verificationCode by mutableStateOf("")
    var legalAccepted by mutableStateOf(false)
    var verificationLoading by mutableStateOf(false)
        private set
    var registering by mutableStateOf(false)
        private set
    val busy: Boolean get() = verificationLoading || registering
    var error by mutableStateOf<String?>(null)
        private set
    var receipt by mutableStateOf<RegistrationReceipt?>(null)
        private set
    var completedEmail by mutableStateOf<String?>(null)
        private set
    var verificationExpiresInSeconds by mutableStateOf<Long?>(null)
        private set
    var verificationRemainingSeconds by mutableStateOf(0L)
        private set
    var verificationExpired by mutableStateOf(false)
        private set
    private var verification: EmailVerificationReceipt? = null
    private var verificationEmail: String? = null
    private var verificationExpiresElapsed = 0L
    private var requestId = 0L
    private var nextLease = 0L
    private var lease: Long? = null
    private var entryAuthGeneration = 0L
    private var task: Job? = null
    private var expiryTask: Job? = null
    // Memory-only uncertainty survives leaving/reentering this controller, never a stored token/order.
    private val unknownEmails = mutableSetOf<String>()
    private var unknownRevision by mutableStateOf(0L)
    val pendingUnknown: Boolean get() {
        unknownRevision
        val normalized = try { RegistrationRules.email(email) } catch (_: Exception) { return false }
        return normalized in unknownEmails
    }
    val legalAvailable: Boolean get() = legalPolicy?.configured == true
    val canRequestEmailVerification: Boolean get() = ready() && !busy && legalAvailable && !pendingUnknown && receipt == null
    val canRegister: Boolean get() = canRequestEmailVerification && verification != null &&
        verificationExpiresElapsed > elapsed()

    fun enterRegistrationForm(): Long {
        invalidateWork(); clearSensitive()
        receipt = null; completedEmail = null; error = null
        entryAuthGeneration = authGeneration()
        return (++nextLease).also { lease = it }
    }
    fun leaveRegistrationForm(currentLease: Long) {
        if (lease != currentLease) return
        lease = null; invalidateWork(); clearSensitive()
        receipt = null; completedEmail = null; error = null
    }
    /** Cancels local work only; it is neither a server cancellation nor a registration retry. */
    fun cancel() {
        invalidateWork(); clearSensitive(); receipt = null; completedEmail = null; error = null
    }
    fun completedLoginEmail(): String? = completedEmail?.takeIf { receipt != null && ready() }

    fun requestEmailVerification() {
        if (!ready() || busy || receipt != null) return
        val configuration = configuredLegal() ?: return
        if (pendingUnknown) { error = unknownMessage; return }
        val submittedEmail: String
        try {
            if (!legalAccepted) throw RegistrationFailure(RegistrationFailure.Kind.LEGAL_REQUIRED)
            submittedEmail = RegistrationRules.email(email)
            val submittedPassword = RegistrationRules.password(password)
            if (submittedPassword != normalizedLoginPassword(confirmPassword))
                throw RegistrationFailure(RegistrationFailure.Kind.PASSWORD_CONFIRMATION)
        } catch (failure: RegistrationFailure) { error = message(failure); return }
        // Reading configuration above is the fail-closed boundary for both production requests.
        check(configuration.configured)
        invalidateVerification()
        val request = ++requestId; val currentLease = requireNotNull(lease); val epoch = entryAuthGeneration
        verificationLoading = true; error = null
        task = scope.launch {
            try {
                ensureCurrent(request, currentLease, epoch, submittedEmail)
                val result = gateway.requestEmailVerification(submittedEmail)
                ensureCurrent(request, currentLease, epoch, submittedEmail)
                verification = result; verificationEmail = submittedEmail
                verificationExpiresInSeconds = result.expiresInSeconds
                val now = elapsed()
                val milliseconds = if (result.expiresInSeconds > Long.MAX_VALUE / 1000) Long.MAX_VALUE else result.expiresInSeconds * 1000
                verificationExpiresElapsed = if (milliseconds > Long.MAX_VALUE - now) Long.MAX_VALUE else now + milliseconds
                updateRemaining()
                expiryTask = scope.launch {
                    while (true) {
                        ensureCurrent(request, currentLease, epoch, submittedEmail)
                        updateRemaining()
                        if (verificationRemainingSeconds == 0L) {
                            invalidateVerification(); verificationExpired = true
                            break
                        }
                        delay(minOf(1000L, (verificationExpiresElapsed - elapsed()).coerceAtLeast(1L)))
                    }
                }
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                ensureCurrent(request, currentLease, epoch, submittedEmail)
                error = if (failure is RegistrationFailure) message(failure) else "验证码发送结果尚未确认，请稍后显式重试。"
            } finally { if (requestId == request) verificationLoading = false }
        }
    }

    fun register() {
        if (!ready() || busy || receipt != null) return
        val policy = configuredLegal() ?: return
        if (pendingUnknown) { error = unknownMessage; return }
        val submittedEmail: String; val submittedPassword: String; val submittedCode: String
        val currentVerification = verification
        try {
            if (!legalAccepted) throw RegistrationFailure(RegistrationFailure.Kind.LEGAL_REQUIRED)
            submittedEmail = RegistrationRules.email(email)
            submittedPassword = RegistrationRules.password(password)
            if (submittedPassword != normalizedLoginPassword(confirmPassword))
                throw RegistrationFailure(RegistrationFailure.Kind.PASSWORD_CONFIRMATION)
            if (currentVerification == null || verificationEmail != submittedEmail || verificationExpiresElapsed <= elapsed())
                throw RegistrationFailure(RegistrationFailure.Kind.VERIFICATION_REQUIRED)
            submittedCode = RegistrationRules.code(verificationCode)
        } catch (failure: RegistrationFailure) {
            if (failure.kind == RegistrationFailure.Kind.VERIFICATION_REQUIRED) {
                invalidateVerification(); verificationExpired = true
            }
            error = message(failure); return
        }
        val input = RegistrationSubmission(submittedEmail, submittedPassword, policy,
            requireNotNull(currentVerification).verificationRef, submittedCode)
        // Correct OTPs are consumed before account creation, including later server failures.
        invalidateVerification(); password = ""; confirmPassword = ""
        val request = ++requestId; val currentLease = requireNotNull(lease); val epoch = entryAuthGeneration
        registering = true; error = null
        task = scope.launch {
            try {
                ensureCurrent(request, currentLease, epoch, submittedEmail)
                setUnknown(submittedEmail, true) // Once dispatched, cancellation cannot prove no account was created.
                val result = gateway.register(input)
                ensureCurrent(request, currentLease, epoch, submittedEmail)
                setUnknown(submittedEmail, false)
                receipt = result; completedEmail = submittedEmail
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                ensureCurrent(request, currentLease, epoch, submittedEmail)
                if (failure is RegistrationFailure && !failure.outcomeUnknown) {
                    setUnknown(submittedEmail, false); error = message(failure) + " 请重新获取验证码。"
                } else { error = unknownMessage }
            } finally { if (requestId == request) registering = false }
        }
    }

    private fun configuredLegal(): RegistrationLegalPolicy? = legalPolicy?.takeIf { it.configured }.also {
        if (it == null) error = "注册条款尚未配置，暂不可发送验证码或注册。"
    }
    private fun ready() = scope.isActive && lease != null && registrationAllowed() && authGeneration() == entryAuthGeneration
    private suspend fun ensureCurrent(request: Long, currentLease: Long, epoch: Long, submittedEmail: String) {
        currentCoroutineContext().ensureActive()
        if (!ready() || requestId != request || lease != currentLease || entryAuthGeneration != epoch ||
            RegistrationRules.email(email) != submittedEmail) throw CancellationException("Registration form no longer current")
    }
    private fun updateRemaining() {
        val remaining = (verificationExpiresElapsed - elapsed()).coerceAtLeast(0L)
        verificationRemainingSeconds = remaining / 1000 + if (remaining % 1000 == 0L) 0 else 1
    }
    private fun setUnknown(email: String, value: Boolean) {
        if (value) unknownEmails.add(email) else unknownEmails.remove(email)
        unknownRevision++
    }
    private fun invalidateVerification() {
        expiryTask?.cancel(); expiryTask = null
        verification = null; verificationEmail = null; verificationCode = ""
        verificationExpiresInSeconds = null; verificationRemainingSeconds = 0; verificationExpiresElapsed = 0
        verificationExpired = false
    }
    private fun invalidateWork() {
        requestId++; task?.cancel(); task = null
        invalidateVerification(); verificationLoading = false; registering = false
    }
    private fun clearSensitive() { password = ""; confirmPassword = ""; verificationCode = ""; legalAccepted = false }
    private val unknownMessage = "注册结果尚未确认，请先尝试正常登录；不会重复提交或自动发送验证码。"
    private fun message(failure: RegistrationFailure) = when (failure.kind) {
        RegistrationFailure.Kind.LEGAL_UNAVAILABLE -> "注册条款尚未配置，暂不可发送验证码或注册。"
        RegistrationFailure.Kind.LEGAL_REQUIRED -> "请先同意服务条款和隐私政策。"
        RegistrationFailure.Kind.EMAIL -> "请输入有效邮箱。"
        RegistrationFailure.Kind.PASSWORD -> "注册密码需 8–160 位，包含大写字母、数字和符号。"
        RegistrationFailure.Kind.PASSWORD_CONFIRMATION -> "两次密码不一致。"
        RegistrationFailure.Kind.CODE -> "请输入 6 位邮箱验证码。"
        RegistrationFailure.Kind.VERIFICATION_REQUIRED, RegistrationFailure.Kind.VERIFICATION_FAILED -> "验证码不可用或已过期。"
        RegistrationFailure.Kind.ALREADY_EXISTS -> "该邮箱已注册，请返回登录。"
        RegistrationFailure.Kind.RATE_LIMITED -> "请求过于频繁，请稍后重试。"
        RegistrationFailure.Kind.UNAVAILABLE -> "邮箱验证服务暂不可用，请稍后重试。"
        RegistrationFailure.Kind.PROTOCOL -> "注册响应暂不受支持。"
        RegistrationFailure.Kind.UNKNOWN -> "请求结果尚未确认，请稍后重试。"
    }
}
