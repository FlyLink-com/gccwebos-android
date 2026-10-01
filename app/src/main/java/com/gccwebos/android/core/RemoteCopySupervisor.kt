package com.gccwebos.android.core

import java.util.IdentityHashMap

/** Unwired JVM fake protocol. A copied receipt is never authorization, activation or OS cleanup. */
class RemoteCopySupervisor(private val limits: Limits, private val endpoint: BoundEndpoint,
    private val port: CopyPort, private val recovery: RecoveryQuarantine) {
    data class Limits(val maxHeaderBytes: Int, val maxPayloadBytes: Int, val maxRequests: Int,
        val maxClaims: Int, val maxLeases: Int, val maxObservers: Int, val maxSequence: Long) {
        init {
            require(maxHeaderBytes in 1..16384 && maxPayloadBytes in 1..16777216 && maxRequests in 1..32 &&
                maxClaims in 3..32 && maxLeases in 2..32 && maxObservers in 1..32 && maxSequence > 0) { "Invalid copy limits" }
        }
    }
    enum class Phase { IDLE, RESERVED, COPYING, COPIED_NOT_ACTIVE, UNKNOWN, STOPPING, CLEANUP_FAILED,
        CLEANUP_CONFIRMED, QUARANTINED, CLOSED }
    enum class Reason { INVALID_FRAME, INVALID_BINDING, OCCUPIED, LIMIT, SEQUENCE_EXHAUSTED, CONFLICT,
        REMOTE_REJECTED, TRANSPORT_UNKNOWN, STOP_REQUESTED, OWNER_LOST, OWNER_CLOSED, ENDPOINT_GONE, TIMEOUT }
    data class Snapshot(val phase: Phase, val reason: Reason?)
    enum class DispatchAttempt { NOT_DISPATCHED, ATTEMPTED }
    internal class FactorySeal {
        val lock = Any()
        var recovery: RecoveryQuarantine? = null
    }
    class OwnerIncarnation internal constructor(internal val seal: Any) {
        @Volatile internal var closed = false
        override fun toString() = "CopyOwner(<opaque>)"
    }
    class BoundEndpoint internal constructor(internal val owner: OwnerIncarnation, internal val seal: Any) {
        @Volatile internal var exhausted = false
        internal var sequence = 0L
        override fun toString() = "BoundCopyEndpoint(<opaque>)"
    }
    class CopyTicket internal constructor(internal val endpoint: BoundEndpoint, internal val sequence: Long) {
        override fun toString() = "CopyTicket(<opaque>)"
    }
    class Lease internal constructor(internal val key: CopyTicket, internal val copy: Boolean, internal val sender: Boolean = false) { override fun toString() = "CopyLease(<opaque>)" }
    class StopTicket internal constructor(internal val key: CopyTicket) { override fun toString() = "CopyCleanup(<opaque>)" }
    class Awaiter internal constructor() { override fun toString() = "CopyAwaiter(<opaque>)" }
    class CopyFrame internal constructor(internal val ticket: CopyTicket, internal val version: Int,
        internal val operation: Int, internal val declaredLength: Int, internal val header: ByteArray,
        internal val payload: ByteArray) { override fun toString() = "CopyFrame(<redacted>)" }
    class CopyReceipt internal constructor(internal val endpoint: BoundEndpoint, internal val key: CopyTicket,
        internal val seal: Any) { override fun toString() = "CopyReceipt(<opaque>)" }
    class RejectionReceipt internal constructor(internal val endpoint: BoundEndpoint, internal val key: CopyTicket,
        internal val seal: Any, internal val definite: Boolean) { override fun toString() = "CopyRejection(<opaque>)" }
    class ReleaseProof internal constructor(internal val endpoint: BoundEndpoint, internal val key: CopyTicket,
        internal val lease: Lease, internal val ticket: StopTicket, internal val seal: Any, internal val wipedCopy: Boolean) {
        override fun toString() = "CopyRelease(<opaque>)"
    }
    class QuiescenceProof internal constructor(internal val endpoint: BoundEndpoint, internal val key: CopyTicket,
        internal val ticket: StopTicket, internal val seal: Any) { override fun toString() = "CopyQuiescence(<opaque>)" }
    class ExitWitness internal constructor(internal val endpoint: BoundEndpoint, internal val seal: Any) {
        override fun toString() = "CopyEndpointExit(<opaque>)"
    }
    sealed interface Reservation {
        class Accepted(val ticket: CopyTicket) : Reservation { override fun toString() = "CopyReserved(<opaque>)" }
        data class Rejected(val reason: Reason) : Reservation
    }
    sealed interface ReceiverReply {
        class Copied(val receipt: CopyReceipt) : ReceiverReply { override fun toString() = "CopyReply(<opaque>)" }
        class Rejected(val receipt: RejectionReceipt) : ReceiverReply { override fun toString() = "CopyRefusal(<opaque>)" }
    }
    interface CopyPort {
        /** Synchronous read-only borrow. Retaining/queueing material requires an independent copy and registered lease. */
        fun sendCopy(frame: CopyFrame)
        fun requestStop(endpoint: BoundEndpoint, key: CopyTicket, ticket: StopTicket, copyLease: Lease)
        fun requestForce(endpoint: BoundEndpoint, key: CopyTicket, ticket: StopTicket)
    }

    /** A concrete trusted FAKE source surviving supervisor-object loss, NOT process loss or persistent storage.
     * Contains ownership metadata only. No material, URL, account, callback, or wire encoding is stored here. */
    class RecoveryQuarantine(val limits: Limits) {
        internal val lock = Any()
        internal var owner: Any? = null
        internal var entry: Entry? = null
        internal var quarantined = false
        internal var confirmed = false
        override fun toString() = "CopyRecovery(<opaque>)"
    }
    internal class Entry(val key: CopyTicket, val copyLease: Lease) {
        val senderLease = Lease(key, false, true)
        val leases = IdentityHashMap<Lease, Boolean>().apply { put(copyLease, false); put(senderLease, false) }
        var phase = Phase.RESERVED
        var reason: Reason? = null
        var claims = 0
        var dispatched = false
        var copied = false
        var conflict = false
        var stopping = false
        var terminal = false
        var stop: StopTicket? = null
        var stopSent = false
        var forced = false
    }

    /** Explicit synthetic authority/receiver. It does not authenticate Android callers or prove OS exit. */
    class FakeEndpointFactory(private val limits: Limits) {
        private val seal = FactorySeal()
        private val lock = Any()
        private class Record(val key: CopyTicket, val version: Int, val operation: Int, val declared: Int,
            val header: ByteArray, val payload: ByteArray, var stopped: Boolean = false)
        private val records = IdentityHashMap<BoundEndpoint, Record>()
        private val bindings = IdentityHashMap<BoundEndpoint, Boolean>()
        private val counts = IdentityHashMap<BoundEndpoint, Int>()
        fun newOwner() = OwnerIncarnation(seal)
        fun bind(owner: OwnerIncarnation): BoundEndpoint? = synchronized(lock) {
            if (owner.seal !== seal || owner.closed || bindings.size >= limits.maxRequests) null
            else BoundEndpoint(owner, seal).also { bindings[it] = true }
        }
        fun copy(frame: CopyFrame): ReceiverReply = synchronized(lock) {
            val target = frame.ticket.endpoint
            val old = records[target]
            fun reject(definite: Boolean): ReceiverReply.Rejected {
                // A definitive pre-copy refusal also fences a later delivery of this exact key.
                if (definite && (old == null || (old.stopped && frame.ticket.sequence > old.key.sequence)))
                    records[target] = Record(frame.ticket, frame.version, frame.operation, frame.declaredLength, byteArrayOf(), byteArrayOf(), true)
                return ReceiverReply.Rejected(RejectionReceipt(target, frame.ticket, seal, definite))
            }
            if (target.seal !== seal || target.owner.closed || target.exhausted || bindings[target] != true) return@synchronized reject(false)
            if (frame.ticket.sequence < 1 || frame.ticket.sequence > limits.maxSequence) return@synchronized reject(true)
            if (old != null && old.key === frame.ticket) {
                if (old.stopped) return@synchronized reject(true)
                if (old.version != frame.version || old.operation != frame.operation || old.declared != frame.declaredLength ||
                    !old.header.contentEquals(frame.header) || !old.payload.contentEquals(frame.payload)) return@synchronized reject(false)
                return@synchronized ReceiverReply.Copied(CopyReceipt(target, frame.ticket, seal))
            }
            if (old != null && (!old.stopped || frame.ticket.sequence <= old.key.sequence)) return@synchronized reject(false)
            if (!valid(limits, frame.version, frame.operation, frame.declaredLength, frame.header, frame.payload))
                return@synchronized reject(true)
            // Full independent copies precede the receipt. No queue or copied-length assertion is an ACK.
            records[target] = Record(frame.ticket, frame.version, frame.operation, frame.declaredLength,
                frame.header.copyOf(), frame.payload.copyOf())
            counts[target] = (counts[target] ?: 0) + 1
            ReceiverReply.Copied(CopyReceipt(target, frame.ticket, seal))
        }
        fun discard(key: CopyTicket, ticket: StopTicket, lease: Lease): ReleaseProof? = synchronized(lock) {
            val target = key.endpoint
            if (target.seal !== seal || !bindings.containsKey(target) || ticket.key !== key || lease.key !== key || !lease.copy) return@synchronized null
            val old = records[target]
            if (old != null && old.key !== key && (!old.stopped || key.sequence <= old.key.sequence)) return@synchronized null
            if (old != null && old.key === key) { old.header.fill(0); old.payload.fill(0); old.stopped = true }
            else records[target] = Record(key, 1, 1, 0, byteArrayOf(), byteArrayOf(), true)
            ReleaseProof(target, key, lease, ticket, seal, true)
        }
        fun released(key: CopyTicket, lease: Lease, ticket: StopTicket): ReleaseProof? =
            if (lease.copy || lease.sender || lease.key !== key || ticket.key !== key) null else ReleaseProof(key.endpoint, key, lease, ticket, seal, false)
        fun quiescent(key: CopyTicket, ticket: StopTicket) = QuiescenceProof(key.endpoint, key, ticket, seal)
        fun simulateExit(endpoint: BoundEndpoint): ExitWitness? = synchronized(lock) {
            if (endpoint.seal !== seal || !bindings.containsKey(endpoint)) null
            else { bindings[endpoint] = false; ExitWitness(endpoint, seal) }
        }
        fun materialEquals(key: CopyTicket, expected: ByteArray): Boolean = synchronized(lock) {
            records[key.endpoint]?.takeIf { it.key === key && !it.stopped }?.payload?.contentEquals(expected) == true
        }
        fun copyCount(endpoint: BoundEndpoint): Int = synchronized(lock) { counts[endpoint] ?: 0 }
        override fun toString() = "FakeCopyAuthority(<opaque>)"
    }

    private val owner = Any()
    private var frame: CopyFrame? = null
    private var closed = false
    private var copyInFlight = false
    private var erasePending: CopyTicket? = null
    private val observers = IdentityHashMap<Awaiter, (Snapshot) -> Unit>()
    private var notice = false
    private var publication = Any()
    private var pumping = false
    init {
        require(limits == recovery.limits) { "Copy limit mismatch" }
        require(endpoint.owner.seal === endpoint.seal) { "Invalid copy binding" }
        val authority = endpoint.seal as? FactorySeal ?: throw IllegalArgumentException("Invalid copy binding")
        synchronized(authority.lock) {
            require(authority.recovery == null || authority.recovery === recovery) { "Recovery source mismatch" }
            authority.recovery = recovery
        }
        synchronized(recovery.lock) {
            recovery.owner = owner
            recovery.entry?.let { it.key.endpoint.owner.closed = true; beginStopLocked(it, Reason.OWNER_LOST); recovery.quarantined = true }
        }
    }
    override fun toString() = "RemoteCopySupervisor(<opaque>)"
    fun snapshot(): Snapshot = synchronized(recovery.lock) { snapshotLocked() }
    private fun snapshotLocked(): Snapshot = when {
        closed -> Snapshot(Phase.CLOSED, Reason.OWNER_CLOSED)
        recovery.owner !== owner || recovery.quarantined -> Snapshot(Phase.QUARANTINED, Reason.OWNER_LOST)
        else -> recovery.entry?.let { Snapshot(it.phase, it.reason) }
            ?: Snapshot(if (recovery.confirmed) Phase.CLEANUP_CONFIRMED else Phase.IDLE, null)
    }
    /** Caller must not mutate header/payload during this call. No external call or listener enumeration. */
    fun reserveCopy(version: Int, operation: Int, declaredLength: Int, header: ByteArray, payload: ByteArray): Reservation =
        synchronized(recovery.lock) {
            if (!valid(limits, version, operation, declaredLength, header, payload)) return@synchronized Reservation.Rejected(Reason.INVALID_FRAME)
            if (closed || endpoint.owner.closed) return@synchronized Reservation.Rejected(Reason.OWNER_CLOSED)
            if (recovery.owner !== owner || recovery.quarantined) return@synchronized Reservation.Rejected(Reason.OWNER_LOST)
            if (recovery.entry != null) return@synchronized Reservation.Rejected(Reason.OCCUPIED)
            if (endpoint.exhausted || endpoint.sequence == limits.maxSequence) {
                endpoint.exhausted = true
                return@synchronized Reservation.Rejected(Reason.SEQUENCE_EXHAUSTED)
            }
            val key = CopyTicket(endpoint, ++endpoint.sequence)
            frame = CopyFrame(key, version, operation, declaredLength, header.copyOf(), payload.copyOf())
            recovery.entry = Entry(key, Lease(key, true)) // Quarantine fact and possible remote-copy lease precede dispatch.
            recovery.confirmed = false
            notifyLocked()
            Reservation.Accepted(key)
        }
    fun dispatchCopy(key: CopyTicket): DispatchAttempt {
        val dispatch = synchronized(recovery.lock) {
            val e = currentLocked(key) ?: return DispatchAttempt.NOT_DISPATCHED
            if (e.dispatched || e.stopping || !claimLocked(e)) return DispatchAttempt.NOT_DISPATCHED
            e.dispatched = true; copyInFlight = true; e.phase = Phase.COPYING; notifyLocked()
            requireNotNull(frame)
        }
        try { port.sendCopy(dispatch) }
        catch (_: Throwable) { synchronized(recovery.lock) { recovery.entry?.takeIf { it.key === key }?.let { unknownLocked(it) } } }
        finally { finishClaim(key, copy = true) }
        dispatchObservers()
        return DispatchAttempt.ATTEMPTED
    }
    fun copied(receipt: CopyReceipt): Boolean = synchronized(recovery.lock) {
        val e = recovery.entry ?: return false
        if (!proofMatches(e, receipt.endpoint, receipt.key, receipt.seal) || e.stopping || e.copied || e.conflict) return false
        e.copied = true
        if (e.claims == 0) { e.phase = Phase.COPIED_NOT_ACTIVE; e.reason = null }
        notifyLocked(); true
    }
    fun rejected(receipt: RejectionReceipt): Boolean = synchronized(recovery.lock) {
        val e = recovery.entry ?: return false
        if (!proofMatches(e, receipt.endpoint, receipt.key, receipt.seal) || e.stopping) return false
        if (!receipt.definite) { e.conflict = true; unknownLocked(e); e.reason = Reason.CONFLICT; return true }
        beginStopLocked(e, Reason.REMOTE_REJECTED)
        e.leases[e.copyLease] = true; e.terminal = true
        eraseFrame(e.key); completeLocked(e); true
    }
    fun registerLease(key: CopyTicket): Lease? = synchronized(recovery.lock) {
        val e = currentLocked(key) ?: return null
        if (e.stopping || e.leases.size >= limits.maxLeases) null else Lease(key, false).also { e.leases[it] = false }
    }
    fun stop(): StopTicket? {
        val dispatch = synchronized(recovery.lock) {
            if (recovery.owner !== owner) return null
            val e = recovery.entry ?: return null
            beginStopLocked(e, Reason.STOP_REQUESTED); eraseFrame(e.key)
            if (e.stopSent || !claimLocked(e)) null else { e.stopSent = true; e }
        }
        if (dispatch != null) {
            try { port.requestStop(dispatch.key.endpoint, dispatch.key, requireNotNull(dispatch.stop), dispatch.copyLease) }
            catch (_: Throwable) { synchronized(recovery.lock) { if (recovery.entry === dispatch) unknownLocked(dispatch, true) } }
            finally { finishClaim(dispatch.key) }
        }
        dispatchObservers()
        return synchronized(recovery.lock) { recovery.entry?.stop ?: dispatch?.stop }
    }
    fun timeout(ticket: StopTicket): Boolean {
        val e = synchronized(recovery.lock) {
            val next = recovery.entry ?: return false
            if (recovery.owner !== owner || next.stop !== ticket || next.forced || !claimLocked(next)) return false
            next.forced = true; next.phase = Phase.CLEANUP_FAILED; next.reason = Reason.TIMEOUT; notifyLocked(); next
        }
        try { port.requestForce(e.key.endpoint, e.key, ticket) }
        catch (_: Throwable) { synchronized(recovery.lock) { if (recovery.entry === e) unknownLocked(e, true) } }
        finally { finishClaim(e.key) }
        dispatchObservers(); return true
    }
    fun released(proof: ReleaseProof): Boolean = synchronized(recovery.lock) {
        val e = recovery.entry ?: return false
        if (!proofMatches(e, proof.endpoint, proof.key, proof.seal) || e.stop !== proof.ticket ||
            (proof.lease.copy && !proof.wipedCopy) ||
            proof.lease.sender ||
            !e.leases.containsKey(proof.lease) || e.leases[proof.lease] == true) return false
        e.leases[proof.lease] = true; completeLocked(e); true
    }
    fun quiescent(proof: QuiescenceProof): Boolean = synchronized(recovery.lock) {
        val e = recovery.entry ?: return false
        if (!proofMatches(e, proof.endpoint, proof.key, proof.seal) || e.stop !== proof.ticket || e.terminal) return false
        e.terminal = true; completeLocked(e); true
    }
    fun endpointGone(witness: ExitWitness): Boolean = synchronized(recovery.lock) {
        val e = recovery.entry ?: return false
        if (e.key.endpoint !== witness.endpoint || witness.seal !== endpointSeal(e)) return false
        beginStopLocked(e, Reason.ENDPOINT_GONE); e.phase = Phase.CLEANUP_FAILED; e.terminal = true
        eraseFrame(e.key); completeLocked(e); true // No lease is released by death alone.
    }
    fun ownerLost() {
        synchronized(recovery.lock) {
            closed = true
            frame?.ticket?.let { key -> eraseFrame(key); recovery.entry?.takeIf { it.key === key }?.let { completeLocked(it) } }
            if (recovery.owner !== owner) return@synchronized
            endpoint.owner.closed = true
            recovery.entry?.let { beginStopLocked(it, Reason.OWNER_LOST); eraseFrame(it.key) }
            recovery.quarantined = true; notifyLocked()
        }
        dispatchObservers()
    }
    fun reconcileRecovery(): Boolean = synchronized(recovery.lock) {
        if (closed || recovery.owner !== owner || !recovery.quarantined || recovery.entry != null || !recovery.confirmed) false
        else { recovery.quarantined = false; notifyLocked(); true }
    }
    fun ownerClose() {
        synchronized(recovery.lock) {
            closed = true
            frame?.ticket?.let { key -> eraseFrame(key); recovery.entry?.takeIf { it.key === key }?.let { completeLocked(it) } }
            if (recovery.owner === owner) endpoint.owner.closed = true
        }
        stop(); dispatchObservers()
    }
    fun observe(callback: (Snapshot) -> Unit): Awaiter? = synchronized(recovery.lock) {
        if (observers.size >= limits.maxObservers) null else Awaiter().also { observers[it] = callback; notifyLocked() }
    }
    fun detach(awaiter: Awaiter): Boolean = synchronized(recovery.lock) { observers.remove(awaiter) != null }
    /** Explicit pump outside all admission fences; advisory callbacks may block, but cannot block reserve internally. */
    fun dispatchObservers() {
        synchronized(recovery.lock) { if (pumping) return; pumping = true }
        try {
            val item = synchronized(recovery.lock) {
                if (!notice) null else { notice = false; Triple(publication, snapshotLocked(), observers.entries.map { it.key to it.value }) }
            } ?: return
            item.third.forEach { (key, callback) ->
                val deliver = synchronized(recovery.lock) { observers[key] === callback && publication === item.first && snapshotLocked() == item.second }
                if (deliver) try { callback(item.second) } catch (_: Throwable) { /* No secret exception text. */ }
            }
        } finally { synchronized(recovery.lock) { pumping = false } }
    }
    private fun currentLocked(key: CopyTicket): Entry? = recovery.entry?.takeIf {
        recovery.owner === owner && !recovery.quarantined && !closed && it.key === key && key.endpoint === endpoint
    }
    private fun endpointSeal(e: Entry) = e.key.endpoint.seal
    private fun proofMatches(e: Entry, endpoint: BoundEndpoint, key: CopyTicket, seal: Any) =
        e.key === key && e.key.endpoint === endpoint && endpoint.seal === seal
    private fun claimLocked(e: Entry): Boolean {
        if (e.claims >= limits.maxClaims) { e.phase = Phase.CLEANUP_FAILED; e.reason = Reason.LIMIT; notifyLocked(); return false }
        e.claims++; return true
    }
    private fun beginStopLocked(e: Entry, reason: Reason) {
        if (e.stopping) return
        e.stopping = true; e.stop = StopTicket(e.key); e.phase = Phase.STOPPING; e.reason = reason; notifyLocked()
    }
    private fun unknownLocked(e: Entry, cleanup: Boolean = false) {
        e.phase = if (cleanup || e.stopping) Phase.CLEANUP_FAILED else Phase.UNKNOWN
        e.reason = Reason.TRANSPORT_UNKNOWN; e.terminal = false; notifyLocked()
    }
    private fun finishClaim(key: CopyTicket, copy: Boolean = false) = synchronized(recovery.lock) {
        if (copy) {
            copyInFlight = false
            if (erasePending === key || recovery.owner !== owner || endpoint.owner.closed || recovery.entry?.stopping == true) eraseFrame(key)
        }
        recovery.entry?.takeIf { it.key === key }?.let {
            it.claims--
            if (copy && !it.stopping && it.phase == Phase.COPYING) {
                it.phase = if (it.copied) Phase.COPIED_NOT_ACTIVE else Phase.UNKNOWN
                it.reason = if (it.copied) null else Reason.TRANSPORT_UNKNOWN
            }
            completeLocked(it)
        }
    }
    private fun completeLocked(e: Entry) {
        if (recovery.entry !== e || !e.stopping || e.stop == null || e.claims != 0 || !e.terminal || e.leases.values.any { !it }) return
        eraseFrame(e.key); recovery.entry = null; recovery.confirmed = true; notifyLocked()
    }
    private fun notifyLocked() { notice = true; publication = Any() }
    private fun eraseFrame(key: CopyTicket) {
        val owned = frame?.takeIf { it.ticket === key } ?: return
        if (copyInFlight) { erasePending = key; return }
        owned.header.fill(0); owned.payload.fill(0); frame = null; erasePending = null
        recovery.entry?.takeIf { it.key === key }?.let { it.leases[it.senderLease] = true }
    }
    companion object {
        private fun valid(limits: Limits, version: Int, operation: Int, declared: Int, header: ByteArray, payload: ByteArray) =
            version == 1 && operation == 1 && header.size in 1..limits.maxHeaderBytes && payload.size in 1..limits.maxPayloadBytes && declared == payload.size
    }
}
