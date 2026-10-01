package com.gccwebos.android.ipcqa;

import android.os.IBinder;
import android.os.Parcel;
import java.util.Arrays;

/** Small lab codec. Bounds application decoding, not kernel Binder allocation. */
public final class Wire {
    private Wire() {}
    public static final int MAGIC = 0x43425141, SCHEMA = 1;
    public static final int MAX_PAYLOAD = 16 * 1024, HEADER_BYTES = 72;
    public static final int MAX_ENCODED_BYTES = HEADER_BYTES + MAX_PAYLOAD * 4;
    public static final int TRANSACTION = IBinder.FIRST_CALL_TRANSACTION;
    public static final int HELLO = 1, GRANT = 2, COPY = 3, CLEAR = 4, WIPE = 5,
        PROBE = 6, SNAPSHOT = 7, TIMEOUT_MARKER = 8, ARM_GATE = 9,
        GATE_STATE = 10, RELEASE_GATE = 11;
    public static final int BEFORE_COPY = 1, AFTER_COPY = 2, AFTER_CLEAR = 3;
    public static final int ARG_DROP_REPLY = 1;

    public static final class Frame {
        public int operation, status, arg;
        public long capHi, capLo, incarnation, revision, request, ticket;
        public byte[] payload = new byte[0];
        public Frame() {}
        public Frame(Frame f) {
            operation = f.operation; status = f.status; arg = f.arg;
            capHi = f.capHi; capLo = f.capLo; incarnation = f.incarnation;
            revision = f.revision; request = f.request; ticket = f.ticket;
            if (f.payload == null || f.payload.length > MAX_PAYLOAD)
                throw new IllegalArgumentException("PAYLOAD_SIZE");
            payload = f.payload.clone();
        }
    }

    public static Frame read(Parcel p) {
        int size = p.dataAvail();
        if (size < HEADER_BYTES || size > MAX_ENCODED_BYTES || p.hasFileDescriptors())
            throw new IllegalArgumentException("FRAME_SIZE");
        if (p.readInt() != MAGIC || p.readInt() != SCHEMA)
            throw new IllegalArgumentException("SCHEMA");
        Frame f = new Frame();
        f.operation = p.readInt(); f.status = p.readInt();
        if (f.operation < HELLO || f.operation > RELEASE_GATE)
            throw new IllegalArgumentException("OPCODE");
        f.capHi = p.readLong(); f.capLo = p.readLong();
        f.incarnation = p.readLong(); f.revision = p.readLong();
        f.request = p.readLong(); f.ticket = p.readLong();
        f.arg = p.readInt();
        int length = p.readInt();
        // No createByteArray/readBundle/Serializable: length and exact encoded
        // remainder are checked before allocating the decoded byte array.
        if (length < 0 || length > MAX_PAYLOAD || p.dataAvail() != length * 4)
            throw new IllegalArgumentException("PAYLOAD_SIZE");
        if (f.operation != COPY && length != 0)
            throw new IllegalArgumentException("UNEXPECTED_PAYLOAD");
        f.payload = new byte[length];
        for (int i = 0; i < length; i++) {
            int octet = p.readInt();
            if (octet < 0 || octet > 255) {
                Arrays.fill(f.payload, (byte) 0);
                throw new IllegalArgumentException("OCTET");
            }
            f.payload[i] = (byte) octet;
        }
        return f;
    }

    public static void write(Parcel p, Frame f) {
        if (f.payload == null || f.payload.length > MAX_PAYLOAD)
            throw new IllegalArgumentException("PAYLOAD_SIZE");
        if (f.operation < HELLO || f.operation > RELEASE_GATE ||
            (f.operation != COPY && f.payload.length != 0))
            throw new IllegalArgumentException("OPCODE_PAYLOAD");
        p.writeInt(MAGIC); p.writeInt(SCHEMA);
        p.writeInt(f.operation); p.writeInt(f.status);
        p.writeLong(f.capHi); p.writeLong(f.capLo);
        p.writeLong(f.incarnation); p.writeLong(f.revision);
        p.writeLong(f.request); p.writeLong(f.ticket);
        p.writeInt(f.arg); p.writeInt(f.payload.length);
        for (byte b : f.payload) p.writeInt(b & 255);
        if (p.dataSize() > MAX_ENCODED_BYTES) throw new IllegalArgumentException("FRAME_SIZE");
    }
}
