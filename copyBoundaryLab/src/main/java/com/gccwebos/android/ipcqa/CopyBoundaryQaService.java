package com.gccwebos.android.ipcqa;

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

/** Test-only ordinary bound service. No engine, network, VPN or process-exit path. */
public final class CopyBoundaryQaService extends Service {
    private final SecureRandom random = new SecureRandom();
    private final long incarnation = positiveRandom();
    private final CopyLedger ledger = new CopyLedger(incarnation);
    private final Object bindingLock = new Object();
    private Endpoint activeEndpoint;
    private CopyLedger.Scope activeScope;

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

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            // Capture real Binder identity at the transaction entry, before any
            // decoding, wait or clearCallingIdentity. PID is diagnostic only.
            final int callerUid = Binder.getCallingUid();
            if (callerUid != Process.myUid()) throw new SecurityException("CALLER_UID");
            // Native Binder may append transport flags to a synchronous call.
            // Only one-way requests lack the reply contract used by this codec.
            if (code != Wire.TRANSACTION || reply == null || (flags & IBinder.FLAG_ONEWAY) != 0)
                return false;
            Wire.Frame request = null;
            if (calls.incrementAndGet() > 8) {
                calls.decrementAndGet();
                throw new RemoteException("CALL_BUDGET");
            }
            try {
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
                            result = activeEndpoint != null && activeEndpoint != this
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
                        result = ledger.copy(scope, request.payload);
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
                        result = ledger.clear(scope);
                        gates.await(scope, Wire.AFTER_CLEAR); break;
                    case Wire.WIPE:
                        synchronized (bindingLock) {
                            result = ledger.wipe(scope, request.ticket);
                            if (result.status == CopyLedger.WIPED && activeEndpoint == this &&
                                scope.equals(activeScope)) {
                                activeEndpoint = null; activeScope = null;
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
                calls.decrementAndGet();
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
