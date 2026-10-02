package com.gccwebos.android.ipcqa;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service-owned, bounded B1 receiver ledger. This class has no Android, transport, VPN,
 * process-exit or persistence authority. Its receipts describe this receiver's private
 * memory only; COPIED is never connected and FENCED is never complete cleanup.
 *
 * The Binder boundary must validate the caller capability before invoking this ledger.
 * Sender debt, extra leases and in-flight client calls belong to the client guard and
 * cannot be released by a result from this class. An incarnation must be fresh for each
 * service lifetime; no state here survives process death.
 */
public final class CopyLedger {
    public static final int MAX_CONFIG_BYTES = 16 * 1024;
    public static final int MAX_ACTIVE = 1;
    public static final int MAX_RETAINED = 2;
    /** Includes retained, actually zero-filled arrays used by the QA probe. */
    public static final int MAX_OWNED_BYTES = (MAX_ACTIVE + MAX_RETAINED) * MAX_CONFIG_BYTES;

    public static final int GRANTED = 1;
    public static final int COPIED = 2;
    public static final int FENCED = 3;
    public static final int WIPED = 4;
    public static final int PROBED = 5;
    public static final int SNAPSHOT = 6;
    public static final int TIMEOUT_MARKED = 7;
    public static final int INVALID = -1;
    public static final int SCOPE = -2;
    public static final int OCCUPIED = -3;
    public static final int REVOKED = -4;
    public static final int CONFLICT = -5;
    public static final int CAPACITY = -6;
    public static final int EXHAUSTED = -7;
    public static final int TICKET = -8;

    public static final long FLAG_CURRENT = 1L;
    public static final long FLAG_REVOKED = 1L << 1;
    public static final long FLAG_HELD = 1L << 2;
    public static final long FLAG_MATCH = 1L << 3;
    public static final long FLAG_ZEROED = 1L << 4;
    public static final long FLAG_RECEIVER_RELEASED = 1L << 5;
    public static final long FLAG_TIMEOUT = 1L << 6;
    public static final int MARKER_COUNT_SHIFT = 16;
    public static final long MARKER_COUNT_MASK = 0xffffL << MARKER_COUNT_SHIFT;

    /** Wire identifiers are exact 64-bit values, never narrowed to an int or a hash. */
    public static final class Scope {
        public final long incarnation;
        public final long revision;
        public final long request;

        public Scope(long incarnation, long revision, long request) {
            this.incarnation = incarnation;
            this.revision = revision;
            this.request = request;
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Scope)) return false;
            Scope scope = (Scope) other;
            return incarnation == scope.incarnation && revision == scope.revision
                    && request == scope.request;
        }

        @Override public int hashCode() {
            int result = Long.hashCode(incarnation);
            result = 31 * result + Long.hashCode(revision);
            return 31 * result + Long.hashCode(request);
        }

        @Override public String toString() { return "CopyScope(<opaque>)"; }
    }

    /** Only metadata leaves the ledger. ticket is a copy marker for COPIED and a clear
     * ticket for FENCED/WIPED/PROBED/SNAPSHOT/TIMEOUT_MARKED; zero means not issued. */
    public static final class Result {
        public final int status;
        public final Scope scope;
        public final long ticket;
        public final long arg;

        public Result(int status, Scope scope, long ticket, long arg) {
            this.status = status;
            this.scope = scope;
            this.ticket = ticket;
            this.arg = arg;
        }

        @Override public String toString() { return "CopyLedgerResult(<metadata>)"; }
    }

    private static final class Entry {
        final Scope scope;
        byte[] owned;
        long copyMarker;
        long clearTicket;
        int markerCount;
        boolean revoked;
        boolean receiverLease;
        boolean wipePerformed;
        boolean receiverReleased;
        boolean timeout;

        Entry(Scope scope) { this.scope = scope; }
    }

    private final Object lock = new Object();
    private final long incarnation;
    private final LinkedHashMap<Scope, Entry> retained = new LinkedHashMap<>();
    private long revision = 1L;
    private long lastRequest;
    private long lastMarker;
    private long lastClearTicket;
    private Entry active;
    private boolean exhausted;

    public CopyLedger(long incarnation) {
        if (incarnation == 0L) throw new IllegalArgumentException("Zero incarnation");
        this.incarnation = incarnation;
    }

    /** Request numbers are positive, monotonically increasing within this incarnation.
     * Repeating the current grant is idempotent; a prior request cannot be granted anew. */
    public Result grant(long request) {
        synchronized (lock) {
            if (request <= 0L) return refusal(INVALID, null);
            if (exhausted) return refusal(EXHAUSTED, null);
            if (active != null) {
                if (!active.revoked && active.scope.request == request) {
                    return result(GRANTED, active, 0L);
                }
                return refusal(OCCUPIED, null);
            }
            if (request <= lastRequest) return refusal(REVOKED, null);
            Scope scope = new Scope(incarnation, revision, request);
            active = new Entry(scope);
            lastRequest = request;
            return result(GRANTED, active, 0L);
        }
    }

    /**
     * A synchronous read-only borrow: the caller must not mutate payload during this
     * method. The Binder service passes its decoded transaction-owned byte array.
     * Clone, receiver lease registration and one marker commit all precede the ACK.
     */
    public Result copy(Scope scope, byte[] payload) {
        synchronized (lock) {
            if (payload == null || payload.length == 0 || payload.length > MAX_CONFIG_BYTES) {
                return refusal(INVALID, scope);
            }
            Entry entry = find(scope);
            if (entry == null) return refusal(SCOPE, scope);
            if (entry != active || entry.revoked || scope.revision != revision || exhausted) {
                return refusal(REVOKED, scope);
            }
            if (entry.copyMarker != 0L) {
                if (!Arrays.equals(entry.owned, payload)) return refusal(CONFLICT, scope);
                return result(COPIED, entry, entry.copyMarker);
            }
            if (lastMarker == Long.MAX_VALUE) return refusal(EXHAUSTED, scope);
            byte[] independent = payload.clone();
            // No receipt exists until both the independent array and its lease exist.
            entry.owned = independent;
            entry.receiverLease = true;
            entry.copyMarker = ++lastMarker;
            entry.markerCount = 1;
            return result(COPIED, entry, entry.copyMarker);
        }
    }

    /**
     * Fence acceptance under the same monitor as grant and copy. The immutable receipt
     * captures this entry's original ticket, so a delayed A return cannot address B.
     * No material is erased and no receiver lease is released by this fence alone.
     */
    public Result clear(Scope scope) {
        synchronized (lock) {
            Entry entry = find(scope);
            if (entry == null) return refusal(SCOPE, scope);
            if (entry.revoked) {
                return entry.clearTicket == 0L ? refusal(EXHAUSTED, scope)
                        : result(FENCED, entry, entry.clearTicket);
            }
            if (entry != active || scope.revision != revision || exhausted) {
                return refusal(REVOKED, scope);
            }
            if (revision == Long.MAX_VALUE || lastClearTicket == Long.MAX_VALUE) {
                // Fail closed if a fence number cannot be issued. This cannot release
                // the current allocation or grant any later request.
                exhausted = true;
                entry.revoked = true;
                return refusal(EXHAUSTED, scope);
            }
            entry.clearTicket = ++lastClearTicket;
            entry.revoked = true;
            ++revision;
            return result(FENCED, entry, entry.clearTicket);
        }
    }

    /**
     * Explicitly erases this receiver's actual independent allocation before releasing
     * its lease. Retaining the zero-filled array permits a boolean actual-memory probe.
     * Only completed receiver entries become tombstones; at most two are retained.
     * This says nothing about client/sender material, kernel buffers or OS cleanup.
     */
    public Result wipe(Scope scope, long ticket) {
        synchronized (lock) {
            Entry entry = find(scope);
            if (entry == null) return refusal(SCOPE, scope);
            if (!entry.revoked || ticket == 0L || entry.clearTicket != ticket) {
                return refusal(TICKET, scope);
            }
            if (!entry.receiverReleased) {
                if (entry.owned != null) {
                    Arrays.fill(entry.owned, (byte) 0);
                    entry.wipePerformed = true;
                }
                entry.receiverLease = false;
                entry.receiverReleased = true;
                if (entry == active) {
                    active = null;
                    retainCompleted(entry);
                }
            }
            return result(WIPED, entry, entry.clearTicket);
        }
    }

    /** Probe uses only a public synthetic pattern. It returns no bytes or digest. */
    public Result probe(Scope scope, int publicPatternSeed) {
        synchronized (lock) {
            Entry entry = find(scope);
            if (entry == null) return refusal(SCOPE, scope);
            long arg = flags(entry);
            if (entry.owned != null && !entry.wipePerformed
                    && matchesPattern(entry.owned, publicPatternSeed)) {
                arg |= FLAG_MATCH;
            }
            return new Result(PROBED, entry.scope, entry.clearTicket, arg);
        }
    }

    /** Counts and flags are per addressed scope. No material or fingerprint is exposed. */
    public Result snapshot(Scope scope) {
        synchronized (lock) {
            Entry entry = find(scope);
            return entry == null ? refusal(SCOPE, scope) : result(SNAPSHOT, entry, entry.clearTicket);
        }
    }

    /** Synthetic timeout metadata only. This neither kills a process nor releases a lease. */
    public Result timeoutMarker(Scope scope, long ticket) {
        synchronized (lock) {
            Entry entry = find(scope);
            if (entry == null) return refusal(SCOPE, scope);
            if (!entry.revoked || ticket == 0L || entry.clearTicket != ticket) {
                return refusal(TICKET, scope);
            }
            entry.timeout = true;
            return result(TIMEOUT_MARKED, entry, entry.clearTicket);
        }
    }

    /** Shared only for constructing synthetic QA inputs; it is not a material digest. */
    public static byte patternByte(int publicPatternSeed, int index) {
        return (byte) (publicPatternSeed + 31 * index);
    }

    public static int markerCount(long arg) {
        return (int) ((arg & MARKER_COUNT_MASK) >>> MARKER_COUNT_SHIFT);
    }

    private Entry find(Scope scope) {
        if (scope == null || scope.incarnation != incarnation) return null;
        if (active != null && active.scope.equals(scope)) return active;
        return retained.get(scope);
    }

    private Result result(int status, Entry entry, long ticket) {
        return new Result(status, entry.scope, ticket, flags(entry));
    }

    private Result refusal(int status, Scope scope) {
        return new Result(status, scope, 0L, 0L);
    }

    private long flags(Entry entry) {
        long value = ((long) entry.markerCount) << MARKER_COUNT_SHIFT;
        if (entry == active && !entry.revoked && entry.scope.revision == revision && !exhausted) {
            value |= FLAG_CURRENT;
        }
        if (entry.revoked) value |= FLAG_REVOKED;
        if (entry.receiverLease) value |= FLAG_HELD;
        if (entry.wipePerformed && entry.owned != null && allZero(entry.owned)) value |= FLAG_ZEROED;
        if (entry.receiverReleased) value |= FLAG_RECEIVER_RELEASED;
        if (entry.timeout) value |= FLAG_TIMEOUT;
        return value;
    }

    private void retainCompleted(Entry entry) {
        retained.put(entry.scope, entry);
        if (retained.size() > MAX_RETAINED) {
            Iterator<Map.Entry<Scope, Entry>> oldest = retained.entrySet().iterator();
            oldest.next();
            // Every retained allocation was already actually wiped before insertion.
            oldest.remove();
        }
    }

    private static boolean allZero(byte[] value) {
        for (byte item : value) if (item != 0) return false;
        return true;
    }

    private static boolean matchesPattern(byte[] value, int seed) {
        for (int i = 0; i < value.length; ++i) {
            if (value[i] != patternByte(seed, i)) return false;
        }
        return true;
    }
}
