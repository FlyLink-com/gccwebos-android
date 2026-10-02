package com.gccwebos.android.ipcqa;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.util.Arrays;
import java.util.function.Consumer;

/** Synchronous real Binder proxy; normal use goes through CopySessionGuard. */
public final class RemoteCopyClient {
    public final IBinder binder;
    public final Wire.Frame session;
    public RemoteCopyClient(IBinder binder) throws RemoteException {
        this.binder = binder;
        Wire.Frame hello = new Wire.Frame(); hello.operation = Wire.HELLO;
        this.session = transact(hello);
        if (session.operation != Wire.HELLO || session.status != 0 || session.incarnation <= 0 ||
                session.capHi == 0 || session.capLo == 0)
            throw new RemoteException("HELLO_REJECTED");
    }
    private Wire.Frame transact(Wire.Frame request) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            Wire.write(data, request);
            if (!binder.transact(Wire.TRANSACTION, data, reply, 0))
                throw new RemoteException("TRANSACTION_REJECTED");
            Wire.Frame result;
            try { result = Wire.read(reply); }
            catch (IllegalArgumentException badReply) { throw new RemoteException("REPLY_UNKNOWN"); }
            if (result.payload.length != 0) {
                Arrays.fill(result.payload, (byte) 0);
                throw new RemoteException("REPLY_MATERIAL_UNKNOWN");
            }
            if (request.operation != Wire.HELLO &&
                (result.operation != request.operation || result.capHi != session.capHi ||
                 result.capLo != session.capLo || result.incarnation != request.incarnation ||
                 result.request != request.request ||
                 (request.operation != Wire.GRANT && result.revision != request.revision)))
                throw new RemoteException("REPLY_SCOPE_UNKNOWN");
            return result;
        } finally { data.recycle(); reply.recycle(); }
    }
    public Wire.Frame call(int op, Wire.Frame scope, int arg, byte[] bytes) throws RemoteException {
        if (bytes != null && bytes.length > Wire.MAX_PAYLOAD)
            throw new IllegalArgumentException("PAYLOAD_SIZE");
        if (op < Wire.HELLO || op > Wire.RELEASE_GATE ||
                (op != Wire.COPY && bytes != null && bytes.length != 0) ||
                (scope != null && (scope.payload == null || scope.payload.length != 0)))
            throw new IllegalArgumentException("OPCODE_PAYLOAD");
        Wire.Frame f = scope == null ? new Wire.Frame() : new Wire.Frame(scope);
        f.operation = op; f.status = 0; f.arg = arg;
        f.capHi = session.capHi; f.capLo = session.capLo;
        if (scope == null) f.incarnation = session.incarnation;
        f.payload = bytes == null ? new byte[0] : bytes.clone();
        try { return transact(f); }
        finally { Arrays.fill(f.payload, (byte) 0); }
    }
    public Wire.Frame grant(long request) throws RemoteException {
        Wire.Frame f = new Wire.Frame(); f.incarnation = session.incarnation; f.request = request;
        return call(Wire.GRANT, f, 0, null);
    }
    public Wire.Frame copy(Wire.Frame scope, byte[] bytes) throws RemoteException {
        return call(Wire.COPY, scope, 0, bytes);
    }
    public Wire.Frame clear(Wire.Frame scope) throws RemoteException {
        return call(Wire.CLEAR, scope, 0, null);
    }
    public Wire.Frame wipe(Wire.Frame receipt) throws RemoteException {
        return call(Wire.WIPE, receipt, 0, null);
    }
    public Wire.Frame probe(Wire.Frame scope, int seed) throws RemoteException {
        return call(Wire.PROBE, scope, seed, null);
    }
    public Wire.Frame snapshot(Wire.Frame scope) throws RemoteException {
        return call(Wire.SNAPSHOT, scope, 0, null);
    }
    public Wire.Frame timeoutMarker(Wire.Frame receipt) throws RemoteException {
        return call(Wire.TIMEOUT_MARKER, receipt, 0, null);
    }
    public Wire.Frame armGate(Wire.Frame scope, int phase) throws RemoteException {
        return call(Wire.ARM_GATE, scope, phase, null);
    }
    public Wire.Frame gateState() throws RemoteException {
        return call(Wire.GATE_STATE, null, 0, null);
    }
    public Wire.Frame releaseGate() throws RemoteException {
        return call(Wire.RELEASE_GATE, null, 0, null);
    }
    /** QA-only malformed encoding probe. Does not make a normal copy receipt. */
    public Wire.Frame rawTransaction(Consumer<Parcel> encoder) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            encoder.accept(data);
            if (!binder.transact(Wire.TRANSACTION, data, reply, 0))
                throw new RemoteException("TRANSACTION_REJECTED");
            return Wire.read(reply);
        } finally { data.recycle(); reply.recycle(); }
    }
}
