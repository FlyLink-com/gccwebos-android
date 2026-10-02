package com.gccwebos.android.ipcqa.b2;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import com.gccwebos.android.ipcqa.CopyLedger;
import com.gccwebos.android.ipcqa.RemoteCopyClient;
import com.gccwebos.android.ipcqa.Wire;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Isolated synthetic sender host; no engine, files, network, VPN or production authority. */
public final class OwnerDeathQaService extends Service {
    private final SecureRandom random = new SecureRandom();
    private final long incarnation = positiveRandom();
    private final Object stateLock = new Object();
    private final ArrayDeque<Entry> retained = new ArrayDeque<>();
    private long nextRevision = 1, lastRequest;
    private Entry active;

    private long positiveRandom() {
        long value;
        do { value = random.nextLong() & Long.MAX_VALUE; } while (value == 0);
        return value;
    }

    @Override public IBinder onBind(Intent ignored) { return new Endpoint(); }

    private final class Entry {
        final Endpoint endpoint;
        final Wire.Frame scope;
        final RemoteCopyClient client;
        final DeathQaClient control;
        Wire.Frame worker;
        byte[] sender;
        int claims;
        boolean senderWiped, copied, unknown, fenced, workerWiped, workerGone, retired;
        Entry(Endpoint endpoint, Wire.Frame scope, RemoteCopyClient client, DeathQaClient control) {
            this.endpoint = endpoint; this.scope = B2Control.metadata(scope);
            this.client = client; this.control = control;
            worker = B2Control.metadata(client.session); worker.request = scope.request;
        }
    }

    private final class Endpoint extends Binder {
        final long capHi = positiveRandom(), capLo = positiveRandom(), openNonce = positiveRandom();
        final AtomicInteger calls = new AtomicInteger();
        boolean opened, attaching;
        RemoteCopyClient worker;
        DeathQaClient workerControl;
        WorkerLink workerLink;
        Gate gate;
        Wire.Frame exitScope;
        long exitNonce;

        private final class WorkerLink implements IBinder.DeathRecipient {
            final IBinder binder;
            boolean gone;
            WorkerLink(IBinder binder) { this.binder = binder; }
            @Override public void binderDied() {
                synchronized (stateLock) {
                    gone = true;
                    if (workerLink == this && active != null && active.endpoint == Endpoint.this) {
                        // Death is not a wipe receipt and never returns a local claim.
                        active.workerGone = true; active.unknown = true; active.fenced = true;
                    }
                }
            }
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            final int callerUid = Binder.getCallingUid();
            if (callerUid != Process.myUid()) throw new SecurityException("CALLER_UID");
            if (code != B2Control.TRANSACTION || reply == null || (flags & IBinder.FLAG_ONEWAY) != 0)
                return false;
            if (calls.incrementAndGet() > 8) {
                calls.decrementAndGet(); throw new RemoteException("CALL_BUDGET");
            }
            try {
                final B2Control.Message in;
                try { in = B2Control.read(data); }
                catch (IllegalArgumentException malformed) { throw new RemoteException("CONTROL_INVALID"); }
                if (in.status != 0 || in.arg != 0 || in.claims != 0 || in.pid != 0 || in.uid != 0 ||
                    in.callerUid != 0 || (in.operation != B2Control.ATTACH && in.attachment != null))
                    throw new RemoteException("CONTROL_REQUEST_FIELDS");
                if (in.operation == B2Control.HELLO) {
                    B2Control.Message hello = endpointMessage(in.operation, null, 0);
                    hello.scope.arg = B2Control.OWNER; hello.nonce = openNonce;
                    B2Control.write(reply, hello); return true;
                }
                if (in.scope.capHi != capHi || in.scope.capLo != capLo || in.scope.incarnation != incarnation) {
                    B2Control.write(reply, endpointMessage(in.operation, in.scope, CopyLedger.SCOPE)); return true;
                }
                B2Control.Message out;
                switch (in.operation) {
                    case B2Control.ATTACH: out = attach(in); break;
                    case B2Control.OPEN:
                        synchronized (stateLock) {
                            int status = in.nonce == openNonce && worker != null && !attaching &&
                                workerLink != null && !workerLink.gone ? B2Control.OPENED : CopyLedger.REVOKED;
                            if (status == B2Control.OPENED) opened = true;
                            out = endpointMessage(in.operation, in.scope, status);
                        }
                        break;
                    case B2Control.RESERVE: out = reserve(in); break;
                    case B2Control.SEND: out = send(in); break;
                    case B2Control.CLEAR_WORKER: out = clearWorker(in); break;
                    case B2Control.RETIRE: out = retire(in); break;
                    default: out = local(in);
                }
                B2Control.write(reply, out); return true;
            } finally { calls.decrementAndGet(); }
        }

        private B2Control.Message attach(B2Control.Message in) throws RemoteException {
            if (in.attachment == null || in.attachment instanceof Binder || in.nonce != 0)
                return endpointMessage(in.operation, in.scope, CopyLedger.INVALID);
            synchronized (stateLock) {
                if (!attaching && worker != null && worker.binder == in.attachment &&
                    workerLink != null && !workerLink.gone) {
                    B2Control.Message existing = endpointMessage(in.operation, in.scope, B2Control.ATTACHED_OK);
                    existing.worker = B2Control.metadata(worker.session); return existing;
                }
                if (attaching || (worker != null && (workerLink == null || !workerLink.gone || active != null)) ||
                    (active != null && active.endpoint == this))
                    return endpointMessage(in.operation, in.scope, CopyLedger.OCCUPIED);
                attaching = true;
            }
            WorkerLink candidate = new WorkerLink(in.attachment);
            boolean linked = false, committed = false;
            try {
                RemoteCopyClient copy = new RemoteCopyClient(in.attachment);
                DeathQaClient control = new DeathQaClient(in.attachment);
                if (control.session.scope.arg != B2Control.WORKER || control.session.uid != Process.myUid() ||
                    control.session.pid == Process.myPid() || !B2Control.sameEndpoint(copy.session, control.session.scope))
                    return endpointMessage(in.operation, in.scope, CopyLedger.INVALID);
                in.attachment.linkToDeath(candidate, 0); linked = true;
                B2Control.Message ownerAttached = control.attachOwner(this);
                if (ownerAttached.status != B2Control.ATTACHED_OK)
                    return endpointMessage(in.operation, in.scope, ownerAttached.status);
                synchronized (stateLock) {
                    if (candidate.gone) return endpointMessage(in.operation, in.scope, CopyLedger.REVOKED);
                    worker = copy; workerControl = control; workerLink = candidate; opened = false; committed = true;
                    B2Control.Message out = endpointMessage(in.operation, in.scope, B2Control.ATTACHED_OK);
                    out.worker = B2Control.metadata(copy.session); return out;
                }
            } finally {
                synchronized (stateLock) { attaching = false; }
                if (linked && !committed) in.attachment.unlinkToDeath(candidate, 0);
            }
        }

        private B2Control.Message reserve(B2Control.Message in) throws RemoteException {
            final Entry entry;
            synchronized (stateLock) {
                if (!opened || attaching || worker == null || workerLink == null || workerLink.gone)
                    return endpointMessage(in.operation, in.scope, CopyLedger.REVOKED);
                if (active != null) return endpointMessage(in.operation, in.scope, CopyLedger.OCCUPIED);
                if (in.nonce != 0 || in.scope.request <= lastRequest || in.scope.request <= 0 ||
                    nextRevision == Long.MAX_VALUE)
                    return endpointMessage(in.operation, in.scope, CopyLedger.INVALID);
                Wire.Frame scope = endpointScope(); scope.revision = nextRevision++; scope.request = in.scope.request;
                entry = new Entry(this, scope, worker, workerControl); entry.claims = 1;
                active = entry; lastRequest = in.scope.request;
            }
            try {
                Wire.Frame granted = entry.client.grant(entry.scope.request);
                synchronized (stateLock) {
                    if (granted.status == CopyLedger.GRANTED) entry.worker = B2Control.metadata(granted);
                    else entry.unknown = true;
                    return entryMessage(in.operation, entry, granted.status);
                }
            } catch (RemoteException | RuntimeException unknown) {
                synchronized (stateLock) { entry.unknown = true; }
                throw unknown;
            } finally { synchronized (stateLock) { --entry.claims; } }
        }

        private B2Control.Message send(B2Control.Message in) throws RemoteException {
            final Entry entry;
            synchronized (stateLock) {
                entry = find(in.scope);
                if (entry == null || entry != active || entry.fenced || entry.unknown || entry.copied ||
                    entry.claims != 0 || entry.sender != null || !B2Control.exact(in.worker, entry.worker) ||
                    in.nonce != 0 || in.length <= 0 || in.length > Wire.MAX_PAYLOAD)
                    return endpointMessage(in.operation, in.scope, CopyLedger.REVOKED);
                // Material originates only here. Harness never owned a fixture byte array.
                entry.sender = new byte[in.length];
                for (int i = 0; i < entry.sender.length; ++i)
                    entry.sender[i] = CopyLedger.patternByte(in.seed, i);
                ++entry.claims;
            }
            try {
                Wire.Frame copied = entry.client.copy(entry.worker, entry.sender);
                synchronized (stateLock) {
                    if (copied.status == CopyLedger.COPIED) entry.copied = true;
                    else entry.unknown = true;
                }
                awaitGate(entry.scope); // Outside stateLock, after real worker ACK.
                synchronized (stateLock) { return entryMessage(in.operation, entry, copied.status); }
            } catch (RemoteException | RuntimeException unknown) {
                synchronized (stateLock) { entry.unknown = true; }
                throw unknown;
            } finally {
                // Sender remains owned until an explicit scan/wipe or exact host loss.
                synchronized (stateLock) { --entry.claims; }
            }
        }

        private B2Control.Message clearWorker(B2Control.Message in) throws RemoteException {
            final Entry entry;
            synchronized (stateLock) {
                entry = find(in.scope);
                if (entry == null || entry.retired || entry.claims >= 4 || in.nonce != 0)
                    return endpointMessage(in.operation, in.scope, CopyLedger.SCOPE);
                entry.fenced = true; ++entry.claims;
            }
            try {
                Wire.Frame fence = entry.client.clear(entry.worker);
                Wire.Frame wiped = fence.status == CopyLedger.FENCED ? entry.client.wipe(fence) : fence;
                synchronized (stateLock) {
                    entry.worker = B2Control.metadata(wiped);
                    if (fence.status == CopyLedger.FENCED && wiped.status == CopyLedger.WIPED &&
                        wiped.ticket == fence.ticket && (wiped.arg & CopyLedger.FLAG_ZEROED) != 0 &&
                        (wiped.arg & CopyLedger.FLAG_RECEIVER_RELEASED) != 0) entry.workerWiped = true;
                    else entry.unknown = true;
                    return entryMessage(in.operation, entry, wiped.status);
                }
            } catch (RemoteException | RuntimeException unknown) {
                synchronized (stateLock) { entry.unknown = true; }
                throw unknown;
            } finally { synchronized (stateLock) { --entry.claims; } }
        }

        private B2Control.Message retire(B2Control.Message in) throws RemoteException {
            final Entry entry;
            boolean needsDrain;
            synchronized (stateLock) {
                entry = find(in.scope);
                // A successful remote retire may precede a failed surviving-ledger
                // reconcile. Retry the exact retained receipt without touching B.
                if (entry != null && entry.retired && in.nonce == 0)
                    return entryMessage(in.operation, entry, B2Control.RETIRED_OK);
                if (entry == null || entry != active || !entry.fenced || !entry.senderWiped ||
                    !allZero(entry.sender) || entry.claims != 0 || in.nonce != 0)
                    return endpointMessage(in.operation, in.scope, CopyLedger.OCCUPIED);
                needsDrain = !entry.workerGone; ++entry.claims;
            }
            boolean quiescent = false;
            try {
                if (needsDrain) {
                    // Surviving harness may have done cleanup directly. Re-read this
                    // exact scope's immutable fence ticket, never trust a caller flag.
                    Wire.Frame fence = entry.client.clear(entry.worker);
                    B2Control.Message drain = entry.control.drain(entry.worker);
                    quiescent = fence.status == CopyLedger.FENCED && fence.ticket > 0 &&
                        drain.status == CopyLedger.SNAPSHOT && drain.claims == 0 &&
                        drain.scope.ticket == fence.ticket &&
                        (drain.scope.arg & CopyLedger.FLAG_ZEROED) != 0 &&
                        (drain.scope.arg & CopyLedger.FLAG_RECEIVER_RELEASED) != 0;
                    if (quiescent) {
                        synchronized (stateLock) {
                            entry.worker = B2Control.metadata(drain.scope); entry.workerWiped = true;
                        }
                    }
                } else quiescent = true;
            } catch (RemoteException | RuntimeException unknown) {
                synchronized (stateLock) { entry.unknown = true; }
                throw unknown;
            } finally { synchronized (stateLock) { --entry.claims; } }
            synchronized (stateLock) {
                if (!quiescent || active != entry || entry.claims != 0)
                    return entryMessage(in.operation, entry, CopyLedger.OCCUPIED);
                entry.retired = true; active = null; retained.addLast(entry);
                if (B2Control.exact(exitScope, entry.scope)) { exitNonce = 0; exitScope = null; }
                while (retained.size() > CopyLedger.MAX_RETAINED) retained.removeFirst();
                return entryMessage(in.operation, entry, B2Control.RETIRED_OK);
            }
        }

        private B2Control.Message local(B2Control.Message in) throws RemoteException {
            boolean exit = false;
            B2Control.Message out;
            synchronized (stateLock) {
                Entry entry = find(in.scope);
                if (entry == null || in.operation != B2Control.SELF_EXIT && in.nonce != 0)
                    return endpointMessage(in.operation, in.scope, CopyLedger.SCOPE);
                int status = 0;
                switch (in.operation) {
                    case B2Control.SNAPSHOT: status = CopyLedger.SNAPSHOT; break;
                    case B2Control.FENCE_OWNER:
                        if (entry.retired) status = CopyLedger.REVOKED;
                        else { entry.fenced = true; status = CopyLedger.FENCED; }
                        break;
                    case B2Control.SENDER_WIPE:
                        if (!entry.fenced || entry.claims != 0 || entry.sender == null) status = CopyLedger.OCCUPIED;
                        else { Arrays.fill(entry.sender, (byte) 0); entry.senderWiped = allZero(entry.sender);
                            status = entry.senderWiped ? CopyLedger.WIPED : CopyLedger.INVALID; }
                        break;
                    case B2Control.ARM_OWNER_GATE:
                        if (entry != active || entry.sender != null || gate != null) status = CopyLedger.OCCUPIED;
                        else gate = new Gate(entry.scope);
                        break;
                    case B2Control.OWNER_GATE_STATE:
                        status = gate != null && B2Control.exact(gate.scope, entry.scope) && gate.entered ? 1 : 0;
                        break;
                    case B2Control.RELEASE_OWNER_GATE:
                        if (gate == null || !B2Control.exact(gate.scope, entry.scope)) status = CopyLedger.SCOPE;
                        else { gate.released.countDown(); gate = null; }
                        break;
                    case B2Control.ARM_EXIT:
                        if (entry != active || Binder.getCallingPid() == Process.myPid()) status = CopyLedger.SCOPE;
                        else if (exitNonce != 0) status = CopyLedger.OCCUPIED;
                        else { exitScope = B2Control.metadata(entry.scope); exitNonce = positiveRandom(); }
                        break;
                    case B2Control.SELF_EXIT:
                        exit = entry == active && Binder.getCallingPid() != Process.myPid() &&
                            in.nonce != 0 && in.nonce == exitNonce && B2Control.exact(exitScope, entry.scope);
                        if (exit) { exitNonce = 0; exitScope = null; } else status = CopyLedger.SCOPE;
                        break;
                    default: status = CopyLedger.INVALID;
                }
                out = entryMessage(in.operation, entry, status);
                if (in.operation == B2Control.ARM_EXIT && status == 0) out.nonce = exitNonce;
            }
            if (exit) {
                // Future fault requires exact scope and one-use server arm nonce, never a PID.
                Process.killProcess(Process.myPid());
                throw new RemoteException("SELF_EXIT_DID_NOT_TERMINATE");
            }
            return out;
        }

        private Entry find(Wire.Frame scope) {
            if (active != null && active.endpoint == this && B2Control.exact(active.scope, scope)) return active;
            for (Entry entry : retained)
                if (entry.endpoint == this && B2Control.exact(entry.scope, scope)) return entry;
            return null;
        }

        private Wire.Frame endpointScope() {
            Wire.Frame scope = new Wire.Frame(); scope.capHi = capHi; scope.capLo = capLo;
            scope.incarnation = incarnation; return scope;
        }

        private B2Control.Message endpointMessage(int operation, Wire.Frame addressed, int status) {
            synchronized (stateLock) {
                B2Control.Message message = new B2Control.Message(); message.operation = operation; message.status = status;
                message.scope = endpointScope();
                if (addressed != null) { message.scope.revision = addressed.revision; message.scope.request = addressed.request; }
                message.pid = Process.myPid(); message.uid = Process.myUid(); message.callerUid = Binder.getCallingUid();
                if (!opened) message.arg |= B2Control.QUARANTINED;
                if (worker != null) message.arg |= B2Control.ATTACHED;
                return message;
            }
        }

        private B2Control.Message entryMessage(int operation, Entry entry, int status) {
            B2Control.Message out = endpointMessage(operation, entry.scope, status);
            out.worker = B2Control.metadata(entry.worker); out.claims = entry.claims; out.arg |= B2Control.RESERVED;
            if (entry.sender != null && !entry.senderWiped) out.arg |= B2Control.SENDER_HELD;
            if (entry.senderWiped && allZero(entry.sender)) out.arg |= B2Control.SENDER_ZEROED;
            if (entry.copied) out.arg |= B2Control.COPIED_NOT_ACTIVE;
            if (entry.unknown) out.arg |= B2Control.UNKNOWN;
            if (entry.fenced) out.arg |= B2Control.LOCAL_FENCED;
            if (entry.workerWiped) out.arg |= B2Control.WORKER_WIPED;
            if (entry.workerGone) out.arg |= B2Control.WORKER_GONE;
            if (entry.retired) out.arg |= B2Control.RETIRED;
            return out;
        }

        private void awaitGate(Wire.Frame scope) throws RemoteException {
            final Gate addressed;
            synchronized (stateLock) {
                addressed = gate;
                if (addressed == null || !B2Control.exact(addressed.scope, scope)) return;
                addressed.entered = true;
            }
            try {
                if (!addressed.released.await(15, TimeUnit.SECONDS)) throw new RemoteException("OWNER_GATE_BUDGET");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new RemoteException("OWNER_GATE_INTERRUPTED");
            }
        }
    }

    private static final class Gate {
        final Wire.Frame scope;
        final CountDownLatch released = new CountDownLatch(1);
        boolean entered;
        Gate(Wire.Frame scope) { this.scope = B2Control.metadata(scope); }
    }

    private static boolean allZero(byte[] bytes) {
        if (bytes == null) return false;
        for (byte item : bytes) if (item != 0) return false;
        return true;
    }
}
