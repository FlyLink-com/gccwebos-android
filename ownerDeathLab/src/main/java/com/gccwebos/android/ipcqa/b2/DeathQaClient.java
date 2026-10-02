package com.gccwebos.android.ipcqa.b2;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import com.gccwebos.android.ipcqa.CopyLedger;
import com.gccwebos.android.ipcqa.Wire;

/** Real synchronous Binder metadata proxy. Surviving harness guard owns admission. */
public final class DeathQaClient {
    public final IBinder binder;
    public final B2Control.Message session;
    private Wire.Frame attachedWorker;

    public static final class Reservation {
        private final Wire.Frame owner, worker;
        private Reservation(Wire.Frame owner, Wire.Frame worker) {
            this.owner = B2Control.metadata(owner); this.worker = B2Control.metadata(worker);
        }
        public Wire.Frame ownerScope() { return B2Control.metadata(owner); }
        public Wire.Frame workerScope() { return B2Control.metadata(worker); }
    }

    /** Bound to this exact local adapter/Binder handle, not reconstructible from a PID. */
    public static final class ExitToken {
        private final IBinder binder;
        private final Wire.Frame scope;
        private final long nonce;
        private boolean used;
        private ExitToken(IBinder binder, Wire.Frame scope, long nonce) {
            this.binder = binder; this.scope = B2Control.metadata(scope); this.nonce = nonce;
        }
    }

    public DeathQaClient(IBinder binder) throws RemoteException {
        if (binder == null) throw new IllegalArgumentException("BINDER_REQUIRED");
        this.binder = binder;
        B2Control.Message hello = new B2Control.Message(); hello.operation = B2Control.HELLO;
        session = transact(hello);
        if (session.status != 0 || session.scope.incarnation <= 0 || session.scope.capHi == 0 ||
            session.scope.capLo == 0 || session.pid <= 0 || session.uid < 0 ||
            (session.scope.arg != B2Control.OWNER && session.scope.arg != B2Control.WORKER))
            throw new RemoteException("CONTROL_HELLO_REJECTED");
    }

    private B2Control.Message transact(B2Control.Message request) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            B2Control.write(data, request);
            if (!binder.transact(B2Control.TRANSACTION, data, reply, 0))
                throw new RemoteException("CONTROL_TRANSACTION_REJECTED");
            B2Control.Message result;
            try { result = B2Control.read(reply); }
            catch (IllegalArgumentException bad) { throw new RemoteException("CONTROL_REPLY_UNKNOWN"); }
            if (result.operation != request.operation || result.attachment != null ||
                result.length != 0 || result.seed != 0)
                throw new RemoteException("CONTROL_REPLY_FIELDS_UNKNOWN");
            if (request.operation != B2Control.HELLO &&
                (!B2Control.sameEndpoint(result.scope, session.scope) ||
                 result.scope.request != request.scope.request ||
                 (request.operation != B2Control.RESERVE && result.scope.revision != request.scope.revision)))
                throw new RemoteException("CONTROL_REPLY_SCOPE_UNKNOWN");
            return result;
        } finally { data.recycle(); reply.recycle(); }
    }

    private B2Control.Message call(int op, Wire.Frame scope) throws RemoteException {
        B2Control.Message request = request(op, scope);
        return transact(request);
    }

    private B2Control.Message request(int operation, Wire.Frame scope) {
        B2Control.Message request = new B2Control.Message(); request.operation = operation;
        request.scope = B2Control.metadata(scope == null ? session.scope : scope);
        request.scope.status = 0; request.scope.arg = 0; request.scope.ticket = 0;
        return request;
    }

    public B2Control.Message attachWorker(IBinder worker) throws RemoteException {
        if (session.scope.arg != B2Control.OWNER) throw new IllegalStateException("OWNER_ONLY");
        B2Control.Message request = request(B2Control.ATTACH, null); request.attachment = worker;
        B2Control.Message reply = transact(request);
        if (reply.status == B2Control.ATTACHED_OK) {
            if (reply.worker.incarnation <= 0 || reply.worker.capHi == 0 || reply.worker.capLo == 0)
                throw new RemoteException("WORKER_IDENTITY_UNKNOWN");
            attachedWorker = B2Control.metadata(reply.worker);
        }
        return reply;
    }

    /** Used by the owner service to give the worker an exact owner death-watch handle. */
    public B2Control.Message attachOwner(IBinder owner) throws RemoteException {
        if (session.scope.arg != B2Control.WORKER) throw new IllegalStateException("WORKER_ONLY");
        B2Control.Message request = request(B2Control.ATTACH, null); request.attachment = owner;
        return transact(request);
    }

    /** QA open gate only. Callers must first obtain surviving guard authorization. */
    public B2Control.Message open(long nonce) throws RemoteException {
        B2Control.Message request = request(B2Control.OPEN, null); request.nonce = nonce;
        return transact(request);
    }

    public Reservation reserve(long requestNumber) throws RemoteException {
        B2Control.Message request = request(B2Control.RESERVE, null); request.scope.request = requestNumber;
        B2Control.Message reply = transact(request);
        if (reply.status != CopyLedger.GRANTED) return null;
        if (reply.scope.revision <= 0 || attachedWorker == null ||
            !B2Control.sameEndpoint(reply.worker, attachedWorker) ||
            reply.worker.revision <= 0 || reply.worker.request != requestNumber)
            throw new RemoteException("RESERVATION_SCOPE_UNKNOWN");
        return new Reservation(reply.scope, reply.worker);
    }

    /** Harness never owns material: only a bounded length and public synthetic seed cross here. */
    public B2Control.Message send(Reservation reservation, int length, int publicSeed)
            throws RemoteException {
        if (reservation == null || length <= 0 || length > Wire.MAX_PAYLOAD)
            throw new IllegalArgumentException("SYNTHETIC_SIZE");
        B2Control.Message request = request(B2Control.SEND, reservation.owner);
        request.worker = reservation.workerScope(); request.length = length; request.seed = publicSeed;
        return transact(request);
    }

    public B2Control.Message snapshot(Wire.Frame scope) throws RemoteException {
        return call(B2Control.SNAPSHOT, scope);
    }
    public B2Control.Message fenceOwner(Wire.Frame scope) throws RemoteException {
        return call(B2Control.FENCE_OWNER, scope);
    }
    public B2Control.Message wipeSender(Wire.Frame scope) throws RemoteException {
        return call(B2Control.SENDER_WIPE, scope);
    }
    public B2Control.Message clearWorker(Wire.Frame scope) throws RemoteException {
        return call(B2Control.CLEAR_WORKER, scope);
    }
    public B2Control.Message retire(Wire.Frame scope) throws RemoteException {
        return call(B2Control.RETIRE, scope);
    }
    public B2Control.Message armOwnerGate(Wire.Frame scope) throws RemoteException {
        return call(B2Control.ARM_OWNER_GATE, scope);
    }
    public B2Control.Message ownerGateState(Wire.Frame scope) throws RemoteException {
        return call(B2Control.OWNER_GATE_STATE, scope);
    }
    public B2Control.Message releaseOwnerGate(Wire.Frame scope) throws RemoteException {
        return call(B2Control.RELEASE_OWNER_GATE, scope);
    }
    public B2Control.Message drain(Wire.Frame workerScope) throws RemoteException {
        return call(B2Control.DRAIN, workerScope);
    }
    public ExitToken armSelfExit(Wire.Frame scope) throws RemoteException {
        B2Control.Message reply = call(B2Control.ARM_EXIT, scope);
        if (reply.status != 0 || reply.nonce == 0) throw new RemoteException("EXIT_ARM_REJECTED");
        return new ExitToken(binder, reply.scope, reply.nonce);
    }
    /** Future explicitly authorized test only. Death callback, never an ACK, proves endpoint loss. */
    public void triggerSelfExit(ExitToken token) throws RemoteException {
        if (token == null || token.binder != binder) throw new IllegalArgumentException("EXIT_ENDPOINT");
        synchronized (token) {
            if (token.used) throw new IllegalStateException("EXIT_ALREADY_USED");
            token.used = true;
        }
        B2Control.Message request = request(B2Control.SELF_EXIT, token.scope); request.nonce = token.nonce;
        transact(request);
        throw new RemoteException("EXIT_RETURNED_WITHOUT_DEATH");
    }
}
