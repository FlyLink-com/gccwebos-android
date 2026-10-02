package com.gccwebos.android.ipcqa.b2;

import android.os.IBinder;
import android.os.RemoteException;
import com.gccwebos.android.ipcqa.CopyLedger;
import com.gccwebos.android.ipcqa.RemoteCopyClient;
import com.gccwebos.android.ipcqa.Wire;
import java.util.IdentityHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/** Surviving, process-singleton admission for this synthetic lab only. No production authority. */
public final class DeathSessionGuard {
    private static final class Endpoint {
        final IBinder binder;
        final Object capability = new Object();
        final Wire.Frame hello;
        EndpointWatch watch;
        Endpoint(IBinder binder, Wire.Frame hello) {
            this.binder = binder; this.hello = B2Control.metadata(hello);
        }
    }

    public static final class Recovery {
        private static final Recovery INSTANCE = new Recovery();
        private final DeathRecoveryLedger ledger = new DeathRecoveryLedger();
        private final IdentityHashMap<IBinder, Endpoint> endpoints = new IdentityHashMap<>();
        private Handle active;
        private long generation;
        private boolean admissionBusy, admissionUnknown;
        private Recovery() {}
        /** Bind once for the lifetime of the surviving instrumentation process. */
        public static Recovery forHarness() { return INSTANCE; }

        private Endpoint endpoint(IBinder binder, Wire.Frame hello) throws RemoteException {
            synchronized (this) {
                Endpoint existing = endpoints.get(binder);
                if (existing != null) {
                    if (!B2Control.sameEndpoint(existing.hello, hello))
                        throw new RemoteException("ENDPOINT_INCARNATION_UNKNOWN");
                    return existing;
                }
                if (endpoints.size() >= 8) throw new RemoteException("ENDPOINT_CAPACITY");
            }
            final Endpoint candidate = new Endpoint(binder, hello);
            // No blocking Binder call under the surviving authority lock.
            candidate.watch = new EndpointWatch(binder, () -> death(candidate));
            boolean published = false;
            try {
                synchronized (this) {
                    Endpoint raced = endpoints.get(binder);
                    if (raced != null) {
                        if (!B2Control.sameEndpoint(raced.hello, hello))
                            throw new RemoteException("ENDPOINT_INCARNATION_UNKNOWN");
                        return raced;
                    }
                    if (endpoints.size() >= 8) throw new RemoteException("ENDPOINT_CAPACITY");
                    endpoints.put(binder, candidate);
                    published = true;
                    return candidate;
                }
            } finally {
                if (!published) candidate.watch.unlinkUnpublished();
            }
        }

        private synchronized void death(Endpoint endpoint) {
            // Endpoint death revokes the currently admitted scope even after adapter reconstruction.
            // Scope-specific replay below never redirects to mutable current.
            Handle handle = active;
            if (handle == null) return;
            if (handle.owner == endpoint) ledger.ownerDied(handle.scope, endpoint.binder);
            if (handle.worker == endpoint) ledger.workerDied(handle.scope, endpoint.binder);
        }

        private boolean canAdmit() {
            return !admissionUnknown && !admissionBusy &&
                (active == null || ledger.snapshot(active.scope).newGenerationAllowed);
        }
    }

    public static final class Handle {
        private final Recovery recovery;
        private final Endpoint owner, worker;
        private final DeathQaClient.Reservation reservation;
        private final DeathRecoveryLedger.Scope scope;
        private boolean sent, workerCleanupBusy;
        private Handle(Recovery recovery, Endpoint owner, Endpoint worker,
                DeathQaClient.Reservation reservation, DeathRecoveryLedger.Scope scope) {
            this.recovery = recovery; this.owner = owner; this.worker = worker;
            this.reservation = reservation; this.scope = scope;
        }
        public Wire.Frame ownerScope() { return reservation.ownerScope(); }
        public Wire.Frame workerScope() { return reservation.workerScope(); }
    }

    private final Recovery recovery;
    private final Endpoint owner, worker;
    private final DeathQaClient ownerClient, workerClient;
    private final RemoteCopyClient wireClient;
    private boolean opened;

    DeathSessionGuard(IBinder ownerBinder, IBinder workerBinder, Recovery recovery)
            throws RemoteException {
        if (recovery != Recovery.forHarness()) throw new IllegalArgumentException("HARNESS_AUTHORITY");
        this.recovery = recovery;
        ownerClient = new DeathQaClient(ownerBinder);
        workerClient = new DeathQaClient(workerBinder);
        if (ownerClient.session.scope.arg != B2Control.OWNER ||
                workerClient.session.scope.arg != B2Control.WORKER)
            throw new RemoteException("ENDPOINT_ROLE_UNKNOWN");
        wireClient = new RemoteCopyClient(workerBinder);
        if (!B2Control.sameEndpoint(wireClient.session, workerClient.session.scope))
            throw new RemoteException("WORKER_CAPABILITY_UNKNOWN");
        owner = recovery.endpoint(ownerBinder, ownerClient.session.scope);
        worker = recovery.endpoint(workerBinder, workerClient.session.scope);
    }

    public boolean open() throws RemoteException {
        synchronized (recovery) {
            if (!recovery.canAdmit() || owner.watch.isDead() || worker.watch.isDead()) return false;
            recovery.admissionBusy = true;
        }
        boolean success = false;
        try {
            B2Control.Message attached = ownerClient.attachWorker(worker.binder);
            if (attached.status != B2Control.ATTACHED_OK ||
                    !B2Control.sameEndpoint(attached.worker, worker.hello)) return false;
            B2Control.Message reply = ownerClient.open(ownerClient.session.nonce);
            success = reply.status == B2Control.OPENED;
            synchronized (recovery) {
                opened = success && !owner.watch.isDead() && !worker.watch.isDead();
                return opened;
            }
        } catch (RemoteException failure) {
            synchronized (recovery) { recovery.admissionUnknown = true; }
            throw failure;
        } finally {
            synchronized (recovery) { recovery.admissionBusy = false; }
        }
    }

    public Handle reserve(long request) throws RemoteException {
        synchronized (recovery) {
            if (!opened || !recovery.canAdmit() || owner.watch.isDead() || worker.watch.isDead()) return null;
            recovery.admissionBusy = true;
        }
        try {
            DeathQaClient.Reservation reservation = ownerClient.reserve(request);
            if (reservation == null) {
                // A refused owner reservation may already have allocated remote
                // metadata debt. Without both scopes, never invent its cleanup address.
                synchronized (recovery) { recovery.admissionUnknown = true; }
                return null;
            }
            synchronized (recovery) {
                if (recovery.generation == Long.MAX_VALUE) {
                    recovery.admissionUnknown = true; return null;
                }
                Wire.Frame o = reservation.ownerScope(), w = reservation.workerScope();
                DeathRecoveryLedger.Scope scope = new DeathRecoveryLedger.Scope(new Object(),
                    ++recovery.generation,
                    new DeathRecoveryLedger.EndpointKey(owner.binder, owner.capability,
                        o.incarnation, o.revision, o.request),
                    new DeathRecoveryLedger.EndpointKey(worker.binder, worker.capability,
                        w.incarnation, w.revision, w.request));
                // Fixed, reviewed service classes own all application material allocations;
                // callers cannot supply the exclusivity flags or substitute an arbitrary resource graph.
                if (!recovery.ledger.grant(scope, true, true)) {
                    recovery.admissionUnknown = true; return null;
                }
                Handle handle = new Handle(recovery, owner, worker, reservation, scope);
                recovery.active = handle;
                if (owner.watch.isDead()) recovery.ledger.ownerDied(scope, owner.binder);
                if (worker.watch.isDead()) recovery.ledger.workerDied(scope, worker.binder);
                return handle;
            }
        } catch (RemoteException failure) {
            synchronized (recovery) { recovery.admissionUnknown = true; }
            throw failure;
        } finally {
            synchronized (recovery) { recovery.admissionBusy = false; }
        }
    }

    private void require(Handle handle) {
        if (handle == null || handle.recovery != recovery || handle.owner != owner || handle.worker != worker)
            throw new IllegalArgumentException("EXACT_HANDLE_REQUIRED");
    }

    /** At most one admitted material task; no queued second SEND and no cancellation release. */
    public Future<B2Control.Message> sendAsync(Handle handle, int length, int seed) {
        require(handle);
        if (length <= 0 || length > Wire.MAX_PAYLOAD) throw new IllegalArgumentException("SYNTHETIC_SIZE");
        final DeathRecoveryLedger.Claim claim;
        synchronized (recovery) {
            if (handle.sent) throw new IllegalStateException("SEND_ALREADY_ADMITTED");
            claim = recovery.ledger.beginClaim(handle.scope, false);
            if (claim == null) throw new IllegalStateException("COPY_REVOKED");
            handle.sent = true;
        }
        FutureTask<B2Control.Message> task = new FutureTask<B2Control.Message>(() -> {
            try {
                B2Control.Message reply = ownerClient.send(handle.reservation, length, seed);
                if (reply.status != CopyLedger.COPIED) recovery.ledger.unknown(handle.scope);
                return reply;
            } catch (RemoteException failure) {
                recovery.ledger.unknown(handle.scope); throw failure;
            } finally {
                recovery.ledger.finishClaim(claim);
            }
        }) {
            @Override public boolean cancel(boolean mayInterrupt) { return false; }
        };
        Thread thread = new Thread(task, "b2-one-synthetic-send");
        thread.setDaemon(true);
        try { thread.start(); }
        catch (RuntimeException failure) {
            recovery.ledger.unknown(handle.scope); recovery.ledger.finishClaim(claim); throw failure;
        }
        return task;
    }

    public DeathRecoveryLedger.Lease addLease(Handle handle) {
        require(handle); return recovery.ledger.addLease(handle.scope);
    }
    public boolean releaseLease(DeathRecoveryLedger.Lease lease) {
        return recovery.ledger.releaseLease(lease);
    }

    /** Cleanup control remains available after copy revocation; no lock spans Binder. */
    public boolean fenceAndWipeWorker(Handle handle) throws RemoteException {
        require(handle); recovery.ledger.fence(handle.scope);
        final DeathRecoveryLedger.Claim claim;
        synchronized (recovery) {
            if (handle.workerCleanupBusy) return false;
            claim = recovery.ledger.beginClaim(handle.scope, true);
            if (claim == null) return false;
            handle.workerCleanupBusy = true;
        }
        try {
            Wire.Frame fenced = wireClient.clear(handle.workerScope());
            if (fenced.status != CopyLedger.FENCED || fenced.ticket <= 0 ||
                    !recovery.ledger.workerFenced(handle.scope, worker.binder, fenced.ticket)) return false;
            Wire.Frame wiped = wireClient.wipe(fenced);
            boolean zero = wiped.status == CopyLedger.WIPED && wiped.ticket == fenced.ticket &&
                (wiped.arg & (CopyLedger.FLAG_ZEROED | CopyLedger.FLAG_RECEIVER_RELEASED)) ==
                    (CopyLedger.FLAG_ZEROED | CopyLedger.FLAG_RECEIVER_RELEASED);
            if (!recovery.ledger.workerWiped(handle.scope, worker.binder, wiped.ticket, zero)) return false;
            B2Control.Message drain = workerClient.drain(handle.workerScope());
            return drain.status == CopyLedger.SNAPSHOT && B2Control.exact(drain.scope, handle.workerScope()) &&
                drain.scope.ticket == fenced.ticket &&
                (drain.scope.arg & (CopyLedger.FLAG_ZEROED | CopyLedger.FLAG_RECEIVER_RELEASED)) ==
                    (CopyLedger.FLAG_ZEROED | CopyLedger.FLAG_RECEIVER_RELEASED) &&
                recovery.ledger.workerQuiescent(handle.scope, worker.binder,
                fenced.ticket, drain.claims);
        } catch (RemoteException failure) {
            recovery.ledger.unknown(handle.scope); throw failure;
        } finally {
            recovery.ledger.finishClaim(claim);
            synchronized (recovery) { handle.workerCleanupBusy = false; }
        }
    }

    public boolean wipeSender(Handle handle) throws RemoteException {
        require(handle); recovery.ledger.fence(handle.scope);
        DeathRecoveryLedger.Claim claim = recovery.ledger.beginClaim(handle.scope, true);
        if (claim == null) return false;
        try {
            B2Control.Message fenced = ownerClient.fenceOwner(handle.ownerScope());
            if ((fenced.arg & B2Control.LOCAL_FENCED) == 0) return false;
            B2Control.Message wiped = ownerClient.wipeSender(handle.ownerScope());
            return wiped.status == CopyLedger.WIPED && wiped.claims == 0 &&
                recovery.ledger.senderWiped(handle.scope, owner.binder,
                    (wiped.arg & B2Control.SENDER_ZEROED) != 0 && (wiped.arg & B2Control.SENDER_HELD) == 0);
        } catch (RemoteException failure) {
            recovery.ledger.unknown(handle.scope); throw failure;
        } finally { recovery.ledger.finishClaim(claim); }
    }

    public boolean ownerHostGone(Handle handle) {
        require(handle); recovery.ledger.fence(handle.scope);
        return owner.watch.isDead() && recovery.ledger.disposeOwnerHostGone(handle.scope);
    }
    public boolean workerHostGone(Handle handle) {
        require(handle); recovery.ledger.fence(handle.scope);
        return worker.watch.isDead() && recovery.ledger.disposeWorkerHostGone(handle.scope);
    }

    public boolean reconcile(Handle handle) throws RemoteException {
        require(handle);
        DeathRecoveryLedger.State state = recovery.ledger.snapshot(handle.scope);
        if (state == null || state.reconciled || !state.fenced || state.claims != 0 || state.leases != 0 ||
                state.senderDisposition == DeathRecoveryLedger.Disposition.PENDING ||
                state.receiverDisposition == DeathRecoveryLedger.Disposition.PENDING ||
                (state.receiverDisposition == DeathRecoveryLedger.Disposition.WIPED && !state.workerQuiescent))
            return false;
        if (!owner.watch.isDead()) {
            DeathRecoveryLedger.Claim claim = recovery.ledger.beginClaim(handle.scope, true);
            if (claim == null) return false;
            try {
                if (ownerClient.retire(handle.ownerScope()).status != B2Control.RETIRED_OK) return false;
            } catch (RemoteException failure) {
                recovery.ledger.unknown(handle.scope); throw failure;
            } finally { recovery.ledger.finishClaim(claim); }
        }
        return recovery.ledger.reconcile(handle.scope);
    }
    public DeathRecoveryLedger.State state(Handle handle) {
        require(handle); return recovery.ledger.snapshot(handle.scope);
    }
    /** Replay a recorded old fact for a scope-isolation negative test; never new OS evidence. */
    public boolean reobserveRecordedWorkerDeath(Handle oldHandle) {
        require(oldHandle);
        return oldHandle.worker.watch.isDead() &&
            recovery.ledger.workerDied(oldHandle.scope, oldHandle.worker.binder);
    }

    public EndpointWatch ownerWatch() { return owner.watch; }
    public EndpointWatch workerWatch() { return worker.watch; }
    public DeathQaClient ownerClient() { return ownerClient; }
    public DeathQaClient workerClient() { return workerClient; }
    public RemoteCopyClient wireClient() { return wireClient; }
}
