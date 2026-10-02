package com.gccwebos.android.ipcqa.b2;

import com.gccwebos.android.ipcqa.CopyLedger;
import com.gccwebos.android.ipcqa.Wire;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** B2 receiver fixture. No network/VPN; self-exit is future explicitly authorized QA only. */
public final class WorkerDeathQaService extends Service {
    private final SecureRandom random = new SecureRandom();
    private final long incarnation = positiveRandom();
    private final CopyLedger ledger = new CopyLedger(incarnation);
    private final Object bindingLock = new Object();
    private Endpoint activeEndpoint;
    private CopyLedger.Scope activeScope;
    private final AtomicInteger actualCopies = new AtomicInteger();

    private long positiveRandom() {
        long value;
        do { value = random.nextLong() & Long.MAX_VALUE; } while (value == 0);
        return value;
    }

    @Override public IBinder onBind(Intent ignored) { return new Endpoint(); }

    private final class Endpoint extends Binder {
        private final long capHi = positiveRandom(), capLo = positiveRandom();
        private final AtomicInteger calls = new AtomicInteger();
        private final GateController gates = new GateController();
        private final LinkedHashSet<CopyLedger.Scope> ownedScopes = new LinkedHashSet<>();
        private OwnerLink ownerLink;
        private boolean attachingOwner;
        private CopyLedger.Scope exitScope;
        private long exitNonce;

        private final class OwnerLink implements IBinder.DeathRecipient {
            final IBinder binder;
            boolean gone;
            OwnerLink(IBinder binder) { this.binder = binder; }
            @Override public void binderDied() {
                synchronized (bindingLock) {
                    gone = true;
                    if (ownerLink == this && activeEndpoint == Endpoint.this && activeScope != null)
                        ledger.clear(activeScope);
                }
            }
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            // Capture real Binder identity at the transaction entry, before any
            // decoding, wait or clearCallingIdentity. PID is diagnostic only.
            final int callerUid = Binder.getCallingUid();
            if (callerUid != Process.myUid()) throw new SecurityException("CALLER_UID");
            // Native Binder may append transport flags to a synchronous call.
            // Only one-way requests lack the reply contract used by this codec.
            if ((code != Wire.TRANSACTION && code != B2Control.TRANSACTION) ||
                reply == null || (flags & IBinder.FLAG_ONEWAY) != 0)
                return false;
            Wire.Frame request = null;
            if (calls.incrementAndGet() > 8) {
                calls.decrementAndGet();
                throw new RemoteException("CALL_BUDGET");
            }
            // Three bounded header ints register the actual COPY before scratch allocation.
            final boolean copyCall = code == Wire.TRANSACTION && isCopy(data);
            if (copyCall) actualCopies.incrementAndGet();
            try {
                if (code == B2Control.TRANSACTION) return control(data, reply);
                try { request = Wire.read(data); }
                catch (IllegalArgumentException malformed) {
                    Wire.Frame invalid = new Wire.Frame();
                    invalid.operation = Wire.HELLO; invalid.status = CopyLedger.INVALID;
                    Wire.write(reply, invalid); return true;
                }
                if (request.status != 0) {
                    writeResult(reply, request, new CopyLedger.Result(CopyLedger.INVALID,
                        scope(request), 0, 0)); return true;
                }
                if (request.operation == Wire.HELLO) {
                    Wire.Frame hello = new Wire.Frame();
                    hello.operation = Wire.HELLO; hello.status = 0;
                    hello.capHi = capHi; hello.capLo = capLo; hello.incarnation = incarnation;
                    hello.arg = Process.myPid(); hello.request = callerUid;
                    hello.revision = Process.myUid();
                    Wire.write(reply, hello); return true;
                }
                if (request.capHi != capHi || request.capLo != capLo ||
                        request.incarnation != incarnation) {
                    writeResult(reply, request, new CopyLedger.Result(CopyLedger.SCOPE,
                        scope(request), 0, 0)); return true;
                }
                CopyLedger.Scope scope = scope(request);
                if (request.operation != Wire.GRANT && request.operation != Wire.GATE_STATE &&
                    request.operation != Wire.RELEASE_GATE && !owns(scope)) {
                    writeResult(reply, request, new CopyLedger.Result(CopyLedger.SCOPE, scope, 0, 0));
                    return true;
                }
                CopyLedger.Result result;
                switch (request.operation) {
                    case Wire.GRANT:
                        synchronized (bindingLock) {
                            result = !ownerLive() || (activeEndpoint != null && activeEndpoint != this)
                                ? new CopyLedger.Result(CopyLedger.OCCUPIED, scope, 0, 0)
                                : ledger.grant(request.request);
                            if (result.status == CopyLedger.GRANTED) {
                                activeEndpoint = this; activeScope = result.scope;
                                remember(result.scope);
                            }
                        }
                        break;
                    case Wire.COPY:
                        if ((request.arg & ~Wire.ARG_DROP_REPLY) != 0) {
                            result = new CopyLedger.Result(CopyLedger.INVALID, scope, 0, 0); break;
                        }
                        gates.await(scope, Wire.BEFORE_COPY);
                        synchronized (bindingLock) {
                            result = ownerLive() ? ledger.copy(scope, request.payload)
                                : new CopyLedger.Result(CopyLedger.REVOKED, scope, 0, 0);
                        }
                        // Decoder scratch is erased before the after-copy gate
                        // and before an ACK can escape. Ledger owns its clone.
                        Arrays.fill(request.payload, (byte) 0);
                        gates.await(scope, Wire.AFTER_COPY);
                        if ((request.arg & Wire.ARG_DROP_REPLY) != 0 &&
                                result.status == CopyLedger.COPIED)
                            throw new RemoteException("CONTROLLED_REPLY_LOSS");
                        break;
                    case Wire.CLEAR:
                        // This immutable result is captured under the original
                        // ledger lock; never re-read current entry after a gate.
                        synchronized (bindingLock) { result = ledger.clear(scope); }
                        gates.await(scope, Wire.AFTER_CLEAR); break;
                    case Wire.WIPE:
                        synchronized (bindingLock) {
                            result = ledger.wipe(scope, request.ticket);
                            if (result.status == CopyLedger.WIPED && activeEndpoint == this &&
                                scope.equals(activeScope)) {
                                activeEndpoint = null; activeScope = null;
                                if (scope.equals(exitScope)) { exitScope = null; exitNonce = 0; }
                            }
                        }
                        break;
                    case Wire.PROBE: result = ledger.probe(scope, request.arg); break;
                    case Wire.SNAPSHOT: result = ledger.snapshot(scope); break;
                    case Wire.TIMEOUT_MARKER:
                        result = ledger.timeoutMarker(scope, request.ticket); break;
                    case Wire.ARM_GATE:
                        result = ledger.snapshot(scope);
                        if (result.status >= 0) {
                            boolean armed = gates.arm(scope, request.arg);
                            result = new CopyLedger.Result(armed ? 0 : CopyLedger.INVALID,
                                scope, 0, 0);
                        }
                        break;
                    case Wire.GATE_STATE:
                        result = new CopyLedger.Result(0, scope, 0, gates.state()); break;
                    case Wire.RELEASE_GATE:
                        gates.release(); result = new CopyLedger.Result(0, scope, 0, 0); break;
                    default: result = new CopyLedger.Result(CopyLedger.INVALID, scope, 0, 0);
                }
                writeResult(reply, request, result);
                return true;
            } finally {
                if (request != null) Arrays.fill(request.payload, (byte) 0);
                if (copyCall) actualCopies.decrementAndGet();
                calls.decrementAndGet();
            }
        }

        private boolean ownerLive() { return ownerLink != null && !ownerLink.gone; }

        private boolean control(Parcel data, Parcel reply) throws RemoteException {
            final B2Control.Message in;
            try { in = B2Control.read(data); }
            catch (IllegalArgumentException bad) { throw new RemoteException("CONTROL_INVALID"); }
            if (in.status != 0 || in.arg != 0 || in.claims != 0 || in.pid != 0 || in.uid != 0 ||
                in.callerUid != 0 || in.length != 0 || in.seed != 0)
                throw new RemoteException("CONTROL_REQUEST_FIELDS");
            B2Control.Message out = new B2Control.Message(); out.operation = in.operation;
            out.scope.capHi = capHi; out.scope.capLo = capLo; out.scope.incarnation = incarnation;
            out.pid = Process.myPid(); out.uid = Process.myUid(); out.callerUid = Binder.getCallingUid();
            if (in.operation == B2Control.HELLO) {
                out.scope.arg = B2Control.WORKER; B2Control.write(reply, out); return true;
            }
            CopyLedger.Scope addressed = scope(in.scope);
            out.scope.revision = addressed.revision; out.scope.request = addressed.request;
            if (in.scope.capHi != capHi || in.scope.capLo != capLo || in.scope.incarnation != incarnation) {
                out.status = CopyLedger.SCOPE; B2Control.write(reply, out); return true;
            }
            if (in.operation == B2Control.ATTACH) {
                out.status = attachOwner(in.attachment); B2Control.write(reply, out); return true;
            }
            if (in.attachment != null || !owns(addressed)) {
                out.status = CopyLedger.SCOPE; B2Control.write(reply, out); return true;
            }
            switch (in.operation) {
                case B2Control.DRAIN:
                    if (in.nonce != 0) { out.status = CopyLedger.INVALID; break; }
                    CopyLedger.Result snapshot = ledger.snapshot(addressed);
                    out.status = snapshot.status; out.claims = actualCopies.get();
                    out.scope.ticket = snapshot.ticket; out.scope.arg = Math.toIntExact(snapshot.arg);
                    break;
                case B2Control.ARM_EXIT:
                    if (in.nonce != 0 || Binder.getCallingPid() == Process.myPid()) {
                        out.status = CopyLedger.INVALID; break;
                    }
                    synchronized (bindingLock) {
                        if (activeEndpoint != this || !addressed.equals(activeScope)) out.status = CopyLedger.SCOPE;
                        else if (exitNonce != 0) out.status = CopyLedger.OCCUPIED;
                        else { exitScope = addressed; exitNonce = positiveRandom(); out.nonce = exitNonce; }
                    }
                    break;
                case B2Control.SELF_EXIT:
                    boolean authorized;
                    synchronized (bindingLock) {
                        authorized = Binder.getCallingPid() != Process.myPid() && in.nonce != 0 &&
                            in.nonce == exitNonce && addressed.equals(exitScope) &&
                            activeEndpoint == this && addressed.equals(activeScope);
                        if (authorized) { exitNonce = 0; exitScope = null; }
                    }
                    if (!authorized) { out.status = CopyLedger.SCOPE; break; }
                    // No caller-supplied PID, delayed task, shutdown callback or wipe ACK.
                    Process.killProcess(Process.myPid());
                    throw new RemoteException("SELF_EXIT_DID_NOT_TERMINATE");
                default: out.status = CopyLedger.INVALID;
            }
            B2Control.write(reply, out); return true;
        }

        private int attachOwner(IBinder owner) throws RemoteException {
            if (owner == null || owner instanceof Binder) return CopyLedger.INVALID;
            synchronized (bindingLock) {
                if (ownerLink != null && ownerLink.binder == owner && !ownerLink.gone)
                    return B2Control.ATTACHED_OK;
                if (attachingOwner || (ownerLink != null && (!ownerLink.gone || activeScope != null ||
                    actualCopies.get() != 0))) return CopyLedger.OCCUPIED;
                attachingOwner = true;
            }
            OwnerLink candidate = new OwnerLink(owner);
            boolean linked = false, committed = false;
            try {
                // The remote HELLO and death registration occur outside bindingLock.
                DeathQaClient client = new DeathQaClient(owner);
                if (client.session.scope.arg != B2Control.OWNER || client.session.uid != Process.myUid() ||
                    client.session.pid == Process.myPid()) return CopyLedger.INVALID;
                owner.linkToDeath(candidate, 0); linked = true;
                synchronized (bindingLock) {
                    if (candidate.gone) return CopyLedger.REVOKED;
                    ownerLink = candidate; committed = true; return B2Control.ATTACHED_OK;
                }
            } finally {
                synchronized (bindingLock) { attachingOwner = false; }
                if (linked && !committed) owner.unlinkToDeath(candidate, 0);
            }
        }

        private void writeResult(Parcel reply, Wire.Frame request, CopyLedger.Result result) {
            Wire.Frame out = new Wire.Frame();
            out.operation = request.operation; out.status = result.status;
            out.capHi = capHi; out.capLo = capLo;
            CopyLedger.Scope addressed = result.scope == null ? scope(request) : result.scope;
            out.incarnation = addressed.incarnation; out.revision = addressed.revision;
            out.request = addressed.request; out.ticket = result.ticket;
            out.arg = Math.toIntExact(result.arg);
            Wire.write(reply, out);
        }
        private synchronized boolean owns(CopyLedger.Scope scope) { return ownedScopes.contains(scope); }
        private synchronized void remember(CopyLedger.Scope scope) {
            ownedScopes.add(scope);
            while (ownedScopes.size() > CopyLedger.MAX_RETAINED + CopyLedger.MAX_ACTIVE)
                ownedScopes.remove(ownedScopes.iterator().next());
        }
    }

    private static boolean isCopy(Parcel data) {
        if (data.dataAvail() < 12) return false;
        int position = data.dataPosition();
        try {
            return data.readInt() == Wire.MAGIC && data.readInt() == Wire.SCHEMA &&
                data.readInt() == Wire.COPY;
        } finally { data.setDataPosition(position); }
    }

    private static CopyLedger.Scope scope(Wire.Frame f) {
        return new CopyLedger.Scope(f.incarnation, f.revision, f.request);
    }

    private static final class GateController {
        private Gate gate;
        private static final class Gate {
            final CopyLedger.Scope scope;
            final int phase;
            final CountDownLatch released = new CountDownLatch(1);
            boolean entered;
            Gate(CopyLedger.Scope scope, int phase) { this.scope = scope; this.phase = phase; }
        }
        synchronized boolean arm(CopyLedger.Scope scope, int phase) {
            if (gate != null || phase < Wire.BEFORE_COPY || phase > Wire.AFTER_CLEAR) return false;
            gate = new Gate(scope, phase); return true;
        }
        void await(CopyLedger.Scope scope, int phase) throws RemoteException {
            Gate addressed;
            synchronized (this) {
                addressed = gate;
                if (addressed == null || addressed.phase != phase ||
                    addressed.scope.incarnation != scope.incarnation ||
                    addressed.scope.revision != scope.revision ||
                    addressed.scope.request != scope.request) return;
                addressed.entered = true;
            }
            try {
                if (!addressed.released.await(15, TimeUnit.SECONDS))
                    throw new RemoteException("GATE_BUDGET");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new RemoteException("GATE_INTERRUPTED");
            }
        }
        synchronized int state() { return gate != null && gate.entered ? gate.phase : 0; }
        synchronized void release() {
            if (gate != null) { gate.released.countDown(); gate = null; }
        }
    }
}
