package com.gccwebos.android.ipcqa;

import android.os.RemoteException;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Surviving in-memory metadata guard. Adapter reconstruction must reuse Recovery. */
public final class CopySessionGuard {
    public enum Phase { EMPTY, INFLIGHT, COPIED_NOT_ACTIVE, UNKNOWN, FENCED, CLEANUP_CONFIRMED }
    public static final class Recovery {
        private final Object lock = new Object();
        private Entry entry;
        private boolean reserving;
    }
    private static final class Entry {
        final Wire.Frame scope;
        final byte[] sender;
        int claims;
        final Set<Lease> leases = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean senderWiped, receiverWiped, fenced, unknown, copied, reconciled;
        Wire.Frame clearReceipt;
        Entry(Wire.Frame scope, byte[] sender) { this.scope = new Wire.Frame(scope); this.sender = sender; }
    }
    /** Opaque exact lease identity. Cannot be fabricated from wire fields. */
    public static final class Lease {
        private Lease() {}
    }
    public static final class State {
        public final Phase phase;
        public final int claims, leases;
        public final boolean senderHeld, receiverReleased;
        State(Phase phase, Entry e) {
            this.phase = phase; claims = e == null ? 0 : e.claims; leases = e == null ? 0 : e.leases.size();
            senderHeld = e != null && !e.senderWiped;
            receiverReleased = e != null && e.receiverWiped;
        }
    }
    private final RemoteCopyClient client;
    private final Recovery recovery;
    private final ExecutorService calls;
    public CopySessionGuard(RemoteCopyClient client, Recovery recovery, ExecutorService calls) {
        this.client = client; this.recovery = recovery; this.calls = calls;
    }

    /** Returns null while any prior debt/claim remains; never performs a retry. */
    public Wire.Frame grant(long request) throws RemoteException {
        synchronized (recovery.lock) {
            Entry prior = recovery.entry;
            if (recovery.reserving || (prior != null && (!prior.reconciled || prior.claims != 0 ||
                    !prior.senderWiped || !prior.receiverWiped || !prior.leases.isEmpty()))) return null;
            recovery.reserving = true;
        }
        Wire.Frame scope;
        try {
            scope = client.grant(request);
            synchronized (recovery.lock) {
                if (scope.status != CopyLedger.GRANTED) return null;
                recovery.entry = new Entry(scope, new byte[0]);
                recovery.entry.senderWiped = true;
                return new Wire.Frame(scope);
            }
        }
        catch (RemoteException | RuntimeException unknown) {
            // Failed grant is an unknown remote reservation, not proof of absence.
            synchronized (recovery.lock) {
                Wire.Frame unresolved = new Wire.Frame();
                unresolved.incarnation = client.session.incarnation; unresolved.request = request;
                unresolved.capHi = client.session.capHi; unresolved.capLo = client.session.capLo;
                Entry e = new Entry(unresolved, new byte[0]); e.senderWiped = true; e.unknown = true;
                recovery.entry = e;
            }
            throw unknown;
        } finally { synchronized (recovery.lock) { recovery.reserving = false; } }
    }

    /** Takes a private bounded sender copy; UI caller retains its fixture unchanged. */
    public Future<Wire.Frame> copy(Wire.Frame scope, byte[] fixture, boolean dropReply) {
        if (fixture == null || fixture.length == 0 || fixture.length > Wire.MAX_PAYLOAD)
            throw new IllegalArgumentException("PAYLOAD_SIZE");
        final Entry entry;
        synchronized (recovery.lock) {
            Entry reserved = addressed(scope);
            if (reserved == null || reserved.claims != 0 || reserved.copied || reserved.fenced || reserved.unknown)
                throw new IllegalStateException("OCCUPIED");
            entry = new Entry(reserved.scope, fixture.clone());
            entry.claims = 1; entry.leases.addAll(reserved.leases);
            recovery.entry = entry;
        }
        try {
            Future<Wire.Frame> actualCall = calls.submit(() -> {
                try {
                    Wire.Frame receipt = client.call(Wire.COPY, entry.scope,
                        dropReply ? Wire.ARG_DROP_REPLY : 0, entry.sender);
                    synchronized (recovery.lock) {
                        if (receipt.status == CopyLedger.COPIED) entry.copied = true;
                        else entry.unknown = true;
                    }
                    return receipt;
                } catch (RemoteException | RuntimeException unknown) {
                    synchronized (recovery.lock) { entry.unknown = true; }
                    throw unknown;
                } finally {
                    synchronized (recovery.lock) {
                        // Only the actual RPC task's exit returns this claim. A
                        // timeout/cancelled UI wait does not reach this block.
                        Arrays.fill(entry.sender, (byte) 0); entry.senderWiped = true;
                        --entry.claims;
                    }
                }
            });
            return new NonCancellingCall(actualCall);
        } catch (RuntimeException notScheduled) {
            synchronized (recovery.lock) {
                Arrays.fill(entry.sender, (byte) 0); entry.senderWiped = true;
                --entry.claims; entry.unknown = true;
            }
            throw notScheduled;
        }
    }

    public void abandonAwait(Wire.Frame scope) {
        synchronized (recovery.lock) {
            Entry e = addressed(scope); if (e != null && !e.reconciled) e.unknown = true;
        }
    }
    public Lease addLease(Wire.Frame scope) {
        synchronized (recovery.lock) {
            Entry e = addressed(scope);
            if (e == null || e.fenced || e.leases.size() >= 4) return null;
            Lease lease = new Lease(); e.leases.add(lease); return lease;
        }
    }
    public boolean releaseLease(Lease lease) {
        synchronized (recovery.lock) {
            Entry e = recovery.entry;
            return e != null && lease != null && e.leases.remove(lease);
        }
    }
    public Wire.Frame clearAndWipe(Wire.Frame scope) throws RemoteException {
        final Entry e;
        synchronized (recovery.lock) {
            e = addressed(scope);
            if (e == null || e.reconciled || e.claims >= 4) return null;
            ++e.claims;
        }
        try {
            Wire.Frame fence = client.clear(e.scope);
            synchronized (recovery.lock) {
                if (fence.status != CopyLedger.FENCED) { e.unknown = true; return fence; }
                e.fenced = true; e.clearReceipt = new Wire.Frame(fence);
            }
            Wire.Frame wiped = client.wipe(fence);
            synchronized (recovery.lock) {
                if (wiped.status == CopyLedger.WIPED && wiped.ticket == fence.ticket &&
                        (wiped.arg & CopyLedger.FLAG_RECEIVER_RELEASED) != 0)
                    e.receiverWiped = true;
                else e.unknown = true;
            }
            return wiped;
        } catch (RemoteException | RuntimeException unknown) {
            synchronized (recovery.lock) { e.unknown = true; }
            throw unknown;
        } finally { synchronized (recovery.lock) { --e.claims; } }
    }
    public boolean reconcile(Wire.Frame scope) {
        synchronized (recovery.lock) {
            Entry e = addressed(scope);
            if (e == null || !e.fenced || !e.receiverWiped || !e.senderWiped ||
                    e.claims != 0 || !e.leases.isEmpty() || recovery.reserving) return false;
            e.reconciled = true; return true;
        }
    }
    public State state() {
        synchronized (recovery.lock) {
            Entry e = recovery.entry;
            Phase phase = e == null ? Phase.EMPTY : e.reconciled ? Phase.CLEANUP_CONFIRMED
                : e.unknown ? Phase.UNKNOWN : e.fenced ? Phase.FENCED
                : e.claims != 0 ? Phase.INFLIGHT : e.copied ? Phase.COPIED_NOT_ACTIVE : Phase.EMPTY;
            return new State(phase, e);
        }
    }
    private Entry addressed(Wire.Frame scope) {
        Entry e = recovery.entry;
        return e != null && scope != null && e.scope.incarnation == scope.incarnation &&
            e.scope.revision == scope.revision && e.scope.request == scope.request &&
            e.scope.capHi == scope.capHi && e.scope.capLo == scope.capLo ? e : null;
    }
    /** Awaiters may detach; cancellation never removes a queued or in-stack RPC. */
    private static final class NonCancellingCall implements Future<Wire.Frame> {
        private final Future<Wire.Frame> actual;
        NonCancellingCall(Future<Wire.Frame> actual) { this.actual = actual; }
        @Override public boolean cancel(boolean interrupt) { return false; }
        @Override public boolean isCancelled() { return false; }
        @Override public boolean isDone() { return actual.isDone(); }
        @Override public Wire.Frame get() throws InterruptedException, ExecutionException {
            return actual.get();
        }
        @Override public Wire.Frame get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return actual.get(timeout, unit);
        }
    }
}
