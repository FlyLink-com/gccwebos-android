package com.gccwebos.android.ipcqa.b2;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Bounded, surviving-harness metadata policy only. No Android or OS fact is inferred
 * here, and no bytes, process, network, timeout task or persistent state are owned.
 * The trusted adapter validates exact wire capabilities/scopes and captures strong
 * endpoint tokens before reporting a real Binder death or scanned wipe receipt.
 * HOST_GONE is the narrow disposition of an exclusive QA Java heap whose exact
 * hosting endpoint was observed dead. It never means zeroization or generic cleanup
 * of external, native, shared or persistent copies.
 */
public final class DeathRecoveryLedger {
    public static final int MAX_ACTIVE = 1;
    public static final int MAX_CLAIMS = 4;
    public static final int MAX_LEASES = 4;

    public enum Phase { EMPTY, RESERVED, FENCED, UNKNOWN, CLEANUP_CONFIRMED }
    public enum Disposition { PENDING, WIPED, HOST_GONE }

    /** The token references are trusted, locally captured identities, not wire IDs,
     * hashes or values accepted from a remote caller. Each long is compared exactly. */
    public static final class EndpointKey {
        public final Object endpointToken;
        public final Object capabilityToken;
        public final long incarnation;
        public final long revision;
        public final long request;

        public EndpointKey(Object endpointToken, Object capabilityToken,
                long incarnation, long revision, long request) {
            this.endpointToken = endpointToken;
            this.capabilityToken = capabilityToken;
            this.incarnation = incarnation;
            this.revision = revision;
            this.request = request;
        }

        @Override public String toString() { return "DeathEndpoint(<opaque>)"; }
    }

    public static final class Scope {
        public final Object generationToken;
        public final long generation;
        public final EndpointKey owner;
        public final EndpointKey worker;

        public Scope(Object generationToken, long generation, EndpointKey owner, EndpointKey worker) {
            this.generationToken = generationToken;
            this.generation = generation;
            this.owner = owner;
            this.worker = worker;
        }

        @Override public String toString() { return "DeathScope(<opaque>)"; }
    }

    /** Opaque exact claim: only the actual RPC task's finally may finish it. */
    public static final class Claim {
        private final DeathRecoveryLedger ledger;
        private final Entry entry;
        private Claim(DeathRecoveryLedger ledger, Entry entry) {
            this.ledger = ledger;
            this.entry = entry;
        }
        @Override public String toString() { return "DeathClaim(<opaque>)"; }
    }

    /** Independent opaque lease; a death, timeout or wipe never releases it. */
    public static final class Lease {
        private final DeathRecoveryLedger ledger;
        private final Entry entry;
        private Lease(DeathRecoveryLedger ledger, Entry entry) {
            this.ledger = ledger;
            this.entry = entry;
        }
        @Override public String toString() { return "DeathLease(<opaque>)"; }
    }

    /** Safe, immutable metadata. Scanned-zero facts are not sender/OS memory probes
     * performed by this ledger: the trusted adapter supplies their validated evidence. */
    public static final class State {
        public final Scope scope;
        public final Phase phase;
        public final int claims;
        public final int leases;
        public final boolean fenced;
        public final boolean unknown;
        public final boolean ownerDead;
        public final boolean workerDead;
        public final boolean ownerExclusiveJavaHeap;
        public final boolean workerExclusiveJavaHeap;
        public final boolean senderScannedZero;
        public final boolean receiverScannedZero;
        public final boolean workerQuiescent;
        public final boolean reconciled;
        public final boolean quarantined;
        public final boolean newGenerationAllowed;
        public final long workerClearTicket;
        public final Disposition senderDisposition;
        public final Disposition receiverDisposition;

        private State(Entry entry) {
            scope = entry == null ? null : entry.scope;
            phase = entry == null ? Phase.EMPTY : entry.reconciled ? Phase.CLEANUP_CONFIRMED
                    : entry.unknown ? Phase.UNKNOWN : entry.fenced ? Phase.FENCED : Phase.RESERVED;
            claims = entry == null ? 0 : entry.claims.size();
            leases = entry == null ? 0 : entry.leases.size();
            fenced = entry != null && entry.fenced;
            unknown = entry != null && entry.unknown;
            ownerDead = entry != null && entry.ownerDead;
            workerDead = entry != null && entry.workerDead;
            ownerExclusiveJavaHeap = entry != null && entry.ownerExclusiveJavaHeap;
            workerExclusiveJavaHeap = entry != null && entry.workerExclusiveJavaHeap;
            senderScannedZero = entry != null && entry.senderScannedZero;
            receiverScannedZero = entry != null && entry.receiverScannedZero;
            workerQuiescent = entry != null && entry.workerQuiescent;
            reconciled = entry != null && entry.reconciled;
            quarantined = entry != null && !entry.reconciled
                    && (entry.unknown || entry.ownerDead || entry.workerDead);
            newGenerationAllowed = entry == null || entry.reconciled;
            workerClearTicket = entry == null ? 0L : entry.workerClearTicket;
            senderDisposition = entry == null ? Disposition.PENDING : entry.senderDisposition;
            receiverDisposition = entry == null ? Disposition.PENDING : entry.receiverDisposition;
        }

        @Override public String toString() { return "DeathRecoveryState(<metadata>)"; }
    }

    private static final class Entry {
        final Scope scope;
        final boolean ownerExclusiveJavaHeap;
        final boolean workerExclusiveJavaHeap;
        final Set<Claim> claims = Collections.newSetFromMap(new IdentityHashMap<Claim, Boolean>());
        final Set<Lease> leases = Collections.newSetFromMap(new IdentityHashMap<Lease, Boolean>());
        boolean fenced;
        boolean unknown;
        boolean ownerDead;
        boolean workerDead;
        boolean senderScannedZero;
        boolean receiverScannedZero;
        boolean workerQuiescent;
        boolean reconciled;
        long workerClearTicket;
        Disposition senderDisposition = Disposition.PENDING;
        Disposition receiverDisposition = Disposition.PENDING;

        Entry(Scope scope, boolean ownerExclusiveJavaHeap, boolean workerExclusiveJavaHeap) {
            this.scope = scope;
            this.ownerExclusiveJavaHeap = ownerExclusiveJavaHeap;
            this.workerExclusiveJavaHeap = workerExclusiveJavaHeap;
        }
    }

    private final Object lock = new Object();
    private Entry active;
    private long lastGeneration;

    /** One active generation. Replacements default to denied/quarantined until an
     * explicit reconcile, and must use a fresh token and increasing generation.
     * The adapter must also validate fresh endpoints; this bounded ledger keeps no
     * unbounded historical endpoint registry and survives no harness process death. */
    public boolean grant(Scope scope, boolean ownerExclusiveJavaHeap, boolean workerExclusiveJavaHeap) {
        synchronized (lock) {
            if (!valid(scope) || scope.generation <= lastGeneration
                    || (active != null && !active.reconciled)) return false;
            if (active != null && (scope.generationToken == active.scope.generationToken
                    || (active.ownerDead && scope.owner.endpointToken == active.scope.owner.endpointToken)
                    || (active.workerDead && scope.worker.endpointToken == active.scope.worker.endpointToken))) {
                return false;
            }
            active = new Entry(scope, ownerExclusiveJavaHeap, workerExclusiveJavaHeap);
            lastGeneration = scope.generation;
            return true;
        }
    }

    /** cleanupCall is a trusted local operation classification, never a remote flag.
     * It registers control RPC debt even after a fence; it does not authorize COPY. */
    public Claim beginClaim(Scope scope, boolean cleanupCall) {
        synchronized (lock) {
            Entry entry = current(scope);
            if (entry == null || entry.claims.size() == MAX_CLAIMS
                    || (!cleanupCall && (entry.fenced || entry.unknown || entry.ownerDead || entry.workerDead))) {
                return null;
            }
            Claim claim = new Claim(this, entry);
            entry.claims.add(claim);
            return claim;
        }
    }

    public boolean finishClaim(Claim claim) {
        synchronized (lock) {
            return claim != null && claim.ledger == this && claim.entry == active
                    && active.claims.remove(claim);
        }
    }

    public Lease addLease(Scope scope) {
        synchronized (lock) {
            Entry entry = current(scope);
            if (entry == null || entry.fenced || entry.unknown || entry.ownerDead || entry.workerDead
                    || entry.leases.size() == MAX_LEASES) return null;
            Lease lease = new Lease(this, entry);
            entry.leases.add(lease);
            return lease;
        }
    }

    public boolean releaseLease(Lease lease) {
        synchronized (lock) {
            return lease != null && lease.ledger == this && lease.entry == active
                    && active.leases.remove(lease);
        }
    }

    /** Immediate local authority revocation only. It releases no debt or claim. */
    public boolean fence(Scope scope) {
        synchronized (lock) {
            Entry entry = current(scope);
            if (entry == null) return false;
            entry.fenced = true;
            return true;
        }
    }

    /** Timeout/transport uncertainty: no replay, release, implicit reconcile or grant. */
    public boolean unknown(Scope scope) {
        synchronized (lock) {
            Entry entry = current(scope);
            if (entry == null) return false;
            entry.unknown = true;
            return true;
        }
    }

    public boolean ownerDied(Scope scope, Object capturedEndpoint) {
        synchronized (lock) {
            Entry entry = current(scope);
            if (entry == null || capturedEndpoint != entry.scope.owner.endpointToken) return false;
            entry.ownerDead = true;
            entry.unknown = true;
            return true;
        }
    }

    public boolean workerDied(Scope scope, Object capturedEndpoint) {
        synchronized (lock) {
            Entry entry = current(scope);
            if (entry == null || capturedEndpoint != entry.scope.worker.endpointToken) return false;
            entry.workerDead = true;
            entry.unknown = true;
            return true;
        }
    }

    public boolean senderWiped(Scope scope, Object exactOwnerToken, boolean scannedZero) {
        synchronized (lock) {
            Entry entry = current(scope);
            if (entry == null || !entry.fenced || !scannedZero
                    || exactOwnerToken != entry.scope.owner.endpointToken
                    || entry.senderDisposition == Disposition.HOST_GONE) return false;
            entry.senderScannedZero = true;
            entry.senderDisposition = Disposition.WIPED;
            return true;
        }
    }

    /** Records only the exact worker FENCED receipt captured for this generation. */
    public boolean workerFenced(Scope scope, Object exactWorkerToken, long ticket) {
        synchronized (lock) {
            Entry entry = current(scope);
            if (entry == null || !entry.fenced || exactWorkerToken != entry.scope.worker.endpointToken
                    || ticket <= 0L || (entry.workerClearTicket != 0L && entry.workerClearTicket != ticket)) {
                return false;
            }
            entry.workerClearTicket = ticket;
            return true;
        }
    }

    public boolean workerWiped(Scope scope, Object exactWorkerToken, long ticket, boolean scannedZero) {
        synchronized (lock) {
            Entry entry = workerReceipt(scope, exactWorkerToken, ticket);
            if (entry == null || !scannedZero || entry.receiverDisposition == Disposition.HOST_GONE) return false;
            entry.receiverScannedZero = true;
            entry.receiverDisposition = Disposition.WIPED;
            return true;
        }
    }

    /** Worker COPY drainage is an independent remote fact, not local claim count.
     * A later matching nonzero observation invalidates an earlier drainage fact.
     * The adapter must serialize fact delivery and validate the actual worker fence;
     * a zero snapshot here never proves absence of queued/kernel Binder work. */
    public boolean workerQuiescent(Scope scope, Object exactWorkerToken, long ticket, int actualCopyInflight) {
        synchronized (lock) {
            Entry entry = workerReceipt(scope, exactWorkerToken, ticket);
            if (entry == null || actualCopyInflight < 0) return false;
            if (actualCopyInflight != 0) {
                entry.workerQuiescent = false;
                entry.unknown = true;
                return false;
            }
            if (entry.receiverDisposition != Disposition.WIPED || !entry.receiverScannedZero) return false;
            entry.workerQuiescent = true;
            return true;
        }
    }

    /** Explicit, non-zeroization disposition. Actual exact death and zero local call
     * debt are prerequisites; opaque extra leases remain outstanding independently. */
    public boolean disposeOwnerHostGone(Scope scope) {
        synchronized (lock) {
            Entry entry = current(scope);
            if (entry == null || !entry.fenced || !entry.ownerDead
                    || !entry.ownerExclusiveJavaHeap || !entry.claims.isEmpty()) return false;
            if (entry.senderDisposition == Disposition.PENDING) {
                entry.senderDisposition = Disposition.HOST_GONE;
            }
            return true;
        }
    }

    public boolean disposeWorkerHostGone(Scope scope) {
        synchronized (lock) {
            Entry entry = current(scope);
            if (entry == null || !entry.fenced || !entry.workerDead
                    || !entry.workerExclusiveJavaHeap || !entry.claims.isEmpty()) return false;
            if (entry.receiverDisposition != Disposition.WIPED || !entry.workerQuiescent) {
                entry.receiverDisposition = Disposition.HOST_GONE;
            }
            return true;
        }
    }

    /** Confirms only this fixed two-exclusive-Java-heap QA resource graph. Local
     * scans can still be recorded for a nonexclusive entry, but cannot dispose its
     * unknown external/native/persistent ownership or permit a new generation.
     * This is not VPN, kernel-zeroization or persistent-recovery confirmation. */
    public boolean reconcile(Scope scope) {
        synchronized (lock) {
            Entry entry = current(scope);
            if (entry == null || !entry.fenced || !entry.ownerExclusiveJavaHeap || !entry.workerExclusiveJavaHeap
                    || !entry.claims.isEmpty() || !entry.leases.isEmpty()
                    || entry.senderDisposition == Disposition.PENDING
                    || (entry.receiverDisposition != Disposition.HOST_GONE
                        && (entry.receiverDisposition != Disposition.WIPED || !entry.workerQuiescent))) {
                return false;
            }
            entry.reconciled = true;
            return true;
        }
    }

    public State state() {
        synchronized (lock) { return new State(active); }
    }

    /** A mismatched or retired generation never exposes current-generation state. */
    public State snapshot(Scope scope) {
        synchronized (lock) {
            return active != null && matches(active.scope, scope) ? new State(active) : null;
        }
    }

    private Entry current(Scope scope) {
        return active != null && !active.reconciled && matches(active.scope, scope) ? active : null;
    }

    private Entry workerReceipt(Scope scope, Object token, long ticket) {
        Entry entry = current(scope);
        return entry != null && entry.fenced && token == entry.scope.worker.endpointToken
                && ticket > 0L && entry.workerClearTicket == ticket ? entry : null;
    }

    private static boolean valid(Scope scope) {
        return scope != null && scope.generationToken != null && scope.generation > 0L
                && validKey(scope.owner) && validKey(scope.worker)
                && scope.owner.endpointToken != scope.worker.endpointToken;
    }

    private static boolean validKey(EndpointKey key) {
        return key != null && key.endpointToken != null && key.capabilityToken != null
                && key.incarnation != 0L && key.revision > 0L && key.request > 0L;
    }

    private static boolean matches(Scope expected, Scope supplied) {
        return supplied != null && expected.generationToken == supplied.generationToken
                && expected.generation == supplied.generation
                && matches(expected.owner, supplied.owner) && matches(expected.worker, supplied.worker);
    }

    private static boolean matches(EndpointKey expected, EndpointKey supplied) {
        return supplied != null && expected.endpointToken == supplied.endpointToken
                && expected.capabilityToken == supplied.capabilityToken
                && expected.incarnation == supplied.incarnation && expected.revision == supplied.revision
                && expected.request == supplied.request;
    }
}
