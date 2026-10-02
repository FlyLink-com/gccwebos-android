package com.gccwebos.android.ipcqa.b2;

import android.os.IBinder;
import android.os.Parcel;
import com.gccwebos.android.ipcqa.Wire;

/** Fixed metadata-only lab control codec. ATTACH alone carries one Binder handle. */
public final class B2Control {
    private B2Control() {}
    public static final int TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 1;
    public static final int MAGIC = 0x42325141, SCHEMA = 1, HEADER_BYTES = 164;
    private static final int MAX_BINDER_BYTES = 64;
    public static final int HELLO = 1, ATTACH = 2, OPEN = 3, RESERVE = 4, SEND = 5,
        SNAPSHOT = 6, SENDER_WIPE = 7, CLEAR_WORKER = 8, RETIRE = 9,
        ARM_OWNER_GATE = 10, OWNER_GATE_STATE = 11, RELEASE_OWNER_GATE = 12,
        DRAIN = 13, ARM_EXIT = 14, SELF_EXIT = 15, FENCE_OWNER = 16;
    public static final int OWNER = 1, WORKER = 2;
    public static final int QUARANTINED = 1, ATTACHED = 1 << 1, RESERVED = 1 << 2,
        SENDER_HELD = 1 << 3, SENDER_ZEROED = 1 << 4, COPIED_NOT_ACTIVE = 1 << 5,
        UNKNOWN = 1 << 6, LOCAL_FENCED = 1 << 7, WORKER_WIPED = 1 << 8,
        WORKER_GONE = 1 << 9, RETIRED = 1 << 10;
    public static final int OK = 0, OPENED = 10, ATTACHED_OK = 11, RETIRED_OK = 12;

    public static final class Message {
        public int operation, status, arg, claims, pid, uid, callerUid, length, seed;
        public long nonce;
        public Wire.Frame scope = new Wire.Frame(), worker = new Wire.Frame();
        public IBinder attachment;
        public Message() {}
        public Message(Message other) {
            operation = other.operation; status = other.status; arg = other.arg;
            claims = other.claims; pid = other.pid; uid = other.uid; callerUid = other.callerUid;
            length = other.length; seed = other.seed; nonce = other.nonce;
            scope = metadata(other.scope); worker = metadata(other.worker);
            attachment = other.attachment;
        }
    }

    public static Wire.Frame metadata(Wire.Frame frame) {
        if (frame == null || frame.payload == null || frame.payload.length != 0)
            throw new IllegalArgumentException("METADATA_ONLY");
        return new Wire.Frame(frame);
    }

    public static boolean exact(Wire.Frame a, Wire.Frame b) {
        return a != null && b != null && a.capHi == b.capHi && a.capLo == b.capLo &&
            a.incarnation == b.incarnation && a.revision == b.revision && a.request == b.request;
    }

    public static boolean sameEndpoint(Wire.Frame a, Wire.Frame b) {
        return a != null && b != null && a.capHi == b.capHi && a.capLo == b.capLo &&
            a.incarnation == b.incarnation;
    }

    public static Message read(Parcel parcel) {
        int size = parcel.dataAvail();
        if (size < HEADER_BYTES || size > HEADER_BYTES + MAX_BINDER_BYTES || parcel.hasFileDescriptors())
            throw new IllegalArgumentException("CONTROL_SIZE");
        if (parcel.readInt() != MAGIC || parcel.readInt() != SCHEMA)
            throw new IllegalArgumentException("CONTROL_SCHEMA");
        Message result = new Message();
        result.operation = parcel.readInt(); result.status = parcel.readInt();
        result.arg = parcel.readInt(); result.claims = parcel.readInt();
        result.pid = parcel.readInt(); result.uid = parcel.readInt();
        result.callerUid = parcel.readInt(); result.length = parcel.readInt(); result.seed = parcel.readInt();
        result.nonce = parcel.readLong(); result.scope = readMetadata(parcel); result.worker = readMetadata(parcel);
        if (result.operation < HELLO || result.operation > FENCE_OWNER)
            throw new IllegalArgumentException("CONTROL_OPCODE");
        if (result.operation == ATTACH) {
            if (parcel.dataAvail() < 4 || parcel.dataAvail() > MAX_BINDER_BYTES)
                throw new IllegalArgumentException("CONTROL_BINDER");
            result.attachment = parcel.readStrongBinder();
        }
        if (parcel.dataAvail() != 0 || result.length < 0 || result.length > Wire.MAX_PAYLOAD ||
            (result.operation != SEND && (result.length != 0 || result.seed != 0)))
            throw new IllegalArgumentException("CONTROL_REMAINDER");
        return result;
    }

    public static void write(Parcel parcel, Message message) {
        if (message.operation < HELLO || message.operation > FENCE_OWNER ||
            message.length < 0 || message.length > Wire.MAX_PAYLOAD ||
            (message.operation != SEND && (message.length != 0 || message.seed != 0)) ||
            (message.operation != ATTACH && message.attachment != null))
            throw new IllegalArgumentException("CONTROL_FIELDS");
        parcel.writeInt(MAGIC); parcel.writeInt(SCHEMA);
        parcel.writeInt(message.operation); parcel.writeInt(message.status);
        parcel.writeInt(message.arg); parcel.writeInt(message.claims);
        parcel.writeInt(message.pid); parcel.writeInt(message.uid); parcel.writeInt(message.callerUid);
        parcel.writeInt(message.length); parcel.writeInt(message.seed); parcel.writeLong(message.nonce);
        writeMetadata(parcel, message.scope); writeMetadata(parcel, message.worker);
        if (message.operation == ATTACH) parcel.writeStrongBinder(message.attachment);
        if (parcel.dataSize() > HEADER_BYTES + MAX_BINDER_BYTES)
            throw new IllegalArgumentException("CONTROL_SIZE");
    }

    private static Wire.Frame readMetadata(Parcel parcel) {
        Wire.Frame frame = new Wire.Frame();
        frame.capHi = parcel.readLong(); frame.capLo = parcel.readLong();
        frame.incarnation = parcel.readLong(); frame.revision = parcel.readLong();
        frame.request = parcel.readLong(); frame.ticket = parcel.readLong();
        frame.status = parcel.readInt(); frame.arg = parcel.readInt();
        return frame;
    }

    private static void writeMetadata(Parcel parcel, Wire.Frame input) {
        Wire.Frame frame = metadata(input);
        parcel.writeLong(frame.capHi); parcel.writeLong(frame.capLo);
        parcel.writeLong(frame.incarnation); parcel.writeLong(frame.revision);
        parcel.writeLong(frame.request); parcel.writeLong(frame.ticket);
        parcel.writeInt(frame.status); parcel.writeInt(frame.arg);
    }
}
