package com.gccwebos.android.portal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A whole verified account snapshot; it never describes the local configuration or a node. */
internal data class AccountUsageSnapshot(
    val resource: Resource? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val notUpdated: Boolean = false,
    val verifying: Boolean = false,
    val authenticated: Boolean = false,
) {
    override fun toString() = "AccountUsageSnapshot([REDACTED])"
}

/** Owns only bounded, in-memory overview state. The supplied reader must perform the narrow GET. */
internal class AccountUsageController(
    private val scope: CoroutineScope,
    private val reader: suspend (AccountUsageOwner, Long) -> BoundAccountOverview,
    private val onSessionExpired: () -> Unit = {},
    /** Upper bound for one overview read; a hung portal GET ends as a recoverable error instead of endless busy. */
    private val readTimeoutMillis: Long = ReadTimeoutMillis,
) {
    companion object {
        const val ReadTimeoutMillis = 45_000L
        const val ReadTimeoutMessage = "读取账户超时，请检查网络后重试"
    }
    private val lock = Any()
    private val mutableState = MutableStateFlow(AccountUsageSnapshot())
    val state: StateFlow<AccountUsageSnapshot> = mutableState.asStateFlow()

    private var owner: AccountUsageOwner? = null
    private var repositoryEpoch: Long? = null
    private var uiAuthEpoch = 0L
    private var verifying = false
    private var closed = false
    private var publishEpoch = 0L
    private var requestSequence = 0L
    private var task: Job? = null
    private var busy = false
    private var resource: Resource? = null
    private var error: String? = null
    private var notUpdated = false
    private var acceptedReadSequence = 0L
    private var resourceRevision = -1L
    private var resourceIdentity: AccountUsageResourceIdentity? = null

    /** Caller supplies one verified, atomic repository/accepted-auth context, never an email hint. */
    fun bind(owner: AccountUsageOwner?, repositoryEpoch: Long?, uiAuthEpoch: Long) = synchronized(lock) {
        require((owner == null) == (repositoryEpoch == null))
        if (closed) return@synchronized
        val sameContext = this.owner == owner && this.repositoryEpoch == repositoryEpoch
        if (!sameContext || this.uiAuthEpoch != uiAuthEpoch || verifying) {
            invalidateRequestLocked()
        }
        if (!sameContext) clearCacheLocked()
        this.owner = owner
        this.repositoryEpoch = repositoryEpoch
        this.uiAuthEpoch = uiAuthEpoch
        verifying = false
        if (owner == null) clearCacheLocked()
        emitLocked()
    }

    /** Hide values while foreground authentication is being verified; only bind can reopen them. */
    fun suspendForVerification() = synchronized(lock) {
        if (closed) return@synchronized
        invalidateRequestLocked()
        verifying = true
        emitLocked()
    }

    /** Metadata is the original successful read's metadata, not the callback's current identity. */
    fun observeExternal(bound: BoundAccountOverview, expectedUiAuthEpoch: Long): Boolean = synchronized(lock) {
        if (!readyLocked() || expectedUiAuthEpoch != uiAuthEpoch || !acceptableLocked(bound)) return@synchronized false
        val identity = AccountUsageResourceIdentity.of(bound.resource)
        if (identity != resourceIdentity) invalidateRequestLocked()
        acceptLocked(bound)
        emitLocked()
        true
    }

    /** Check the accepted read itself; StateFlow may retain an older, structurally equal value. */
    fun isCurrentAcceptedObservation(bound: BoundAccountOverview, expectedUiAuthEpoch: Long): Boolean = synchronized(lock) {
        val context = bound.context
        readyLocked() && expectedUiAuthEpoch == uiAuthEpoch && context.owner == owner &&
            context.repositoryEpoch == repositoryEpoch && context.readSequence > 0 &&
            context.readSequence == acceptedReadSequence && context.resourceRevision >= 0 &&
            context.resourceRevision == resourceRevision && resource === bound.resource &&
            resourceIdentity != null && resourceIdentity == AccountUsageResourceIdentity.of(bound.resource)
    }

    /** Explicit, non-reentrant read. No automatic retry, setup, import, order or connection action. */
    fun update() {
        val launchTask = synchronized(lock) {
            if (busy || !readyLocked()) return
            val captured = Request(requireNotNull(owner), requireNotNull(repositoryEpoch), uiAuthEpoch,
                publishEpoch, ++requestSequence, acceptedReadSequence)
            busy = true
            emitLocked()
            scope.launch(start = CoroutineStart.LAZY) { read(captured) }.also { task = it }
        }
        launchTask.start()
    }

    /** Cancellation does not turn a previously failed refresh into a successful one. */
    fun cancel() = synchronized(lock) {
        if (closed) return@synchronized
        invalidateRequestLocked()
        emitLocked()
    }

    fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        invalidateRequestLocked()
        owner = null
        repositoryEpoch = null
        verifying = false
        clearCacheLocked()
        emitLocked()
    }

    private suspend fun read(request: Request) {
        var expired = false
        try {
            currentCoroutineContext().ensureActive()
            if (!synchronized(lock) { currentLocked(request) }) return
            val result = kotlinx.coroutines.withTimeout(readTimeoutMillis) {
                reader(request.owner, request.repositoryEpoch)
            }
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (currentLocked(request) && acceptableLocked(result)) {
                    val changedIdentity = AccountUsageResourceIdentity.of(result.resource) != resourceIdentity
                    acceptLocked(result)
                    // Fence any publisher which captured the previous resource or period.
                    if (changedIdentity) publishEpoch++
                    busy = false
                    emitLocked()
                }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (currentLocked(request)) {
                    // Same recovery path as an ordinary failure: keep the prior sample when present.
                    if (acceptedReadSequence == request.acceptedSequenceAtStart) {
                        error = ReadTimeoutMessage
                        notUpdated = resource != null
                        busy = false
                    }
                    emitLocked()
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (currentLocked(request)) {
                    if (failure is PortalFailure && failure.reason == PortalFailure.Reason.SESSION_EXPIRED) {
                        invalidateRequestLocked()
                        owner = null
                        repositoryEpoch = null
                        clearCacheLocked()
                        error = "登录已到期，请重新登录"
                        expired = true
                    } else if (acceptedReadSequence == request.acceptedSequenceAtStart) {
                        // Preserve the whole old sample, including its server timestamp and period.
                        error = "读取失败，请重试"
                        notUpdated = resource != null
                        busy = false
                    }
                    emitLocked()
                }
            }
        } finally {
            synchronized(lock) {
                if (requestSequence == request.sequence) {
                    task = null
                    busy = false
                    emitLocked()
                }
            }
        }
        // Auth invalidation belongs to the account owner. No local subscription operation is here.
        if (expired) onSessionExpired()
    }

    private fun acceptableLocked(bound: BoundAccountOverview): Boolean {
        val context = bound.context
        if (context.owner != owner || context.repositoryEpoch != repositoryEpoch ||
            context.readSequence <= acceptedReadSequence || context.readSequence <= 0 ||
            context.resourceRevision < 0 || context.resourceRevision < resourceRevision) return false
        val identity = AccountUsageResourceIdentity.of(bound.resource)
        // A changed reference/state/period requires the repository's successful identity revision.
        return resourceIdentity == null || identity == resourceIdentity || context.resourceRevision > resourceRevision
    }

    private fun acceptLocked(bound: BoundAccountOverview) {
        acceptedReadSequence = bound.context.readSequence
        resourceRevision = bound.context.resourceRevision
        resourceIdentity = AccountUsageResourceIdentity.of(bound.resource)
        resource = bound.resource
        error = null
        notUpdated = false
    }

    private fun currentLocked(request: Request) = readyLocked() && owner == request.owner &&
        repositoryEpoch == request.repositoryEpoch && uiAuthEpoch == request.uiAuthEpoch &&
        publishEpoch == request.publishEpoch && requestSequence == request.sequence

    private fun readyLocked() = !closed && !verifying && owner != null && repositoryEpoch != null && scope.isActive

    private fun invalidateRequestLocked() {
        publishEpoch++
        requestSequence++
        task?.cancel()
        task = null
        busy = false
    }

    private fun clearCacheLocked() {
        resource = null
        error = null
        notUpdated = false
        acceptedReadSequence = 0L
        resourceRevision = -1L
        resourceIdentity = null
    }

    private fun emitLocked() {
        mutableState.value = if (verifying) AccountUsageSnapshot(verifying = true)
        else AccountUsageSnapshot(resource, busy, error, notUpdated, authenticated = owner != null && !closed)
    }

    private data class Request(val owner: AccountUsageOwner, val repositoryEpoch: Long,
        val uiAuthEpoch: Long, val publishEpoch: Long, val sequence: Long, val acceptedSequenceAtStart: Long)
}
