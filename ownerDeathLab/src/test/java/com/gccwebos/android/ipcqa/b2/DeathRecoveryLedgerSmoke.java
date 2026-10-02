package com.gccwebos.android.ipcqa.b2;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** JVM policy smoke; synthetic reported facts are never Android/OS death evidence. */
public final class DeathRecoveryLedgerSmoke {
    private static final long TICKET = (1L << 42) + 11L;

    private DeathRecoveryLedgerSmoke() { }

    public static void main(String[] args) throws Exception {
        explicitWipeAndDrain();
        ownerDeathKeepsEveryDebt();
        workerDeathIsNotWipe();
        externalCopiesRemainUnknown();
        exactKeysAndTickets();
        boundsAndOpaqueHandles();
        replacementAndLateEvidence();
        timeoutIsNotReturn();
        drainageEvidenceCanBeInvalidated();
        for (int i = 0; i < 32; i++) concurrentFenceAndClaim();
        System.out.println("DeathRecoveryLedger JVM policy smoke: PASS (no Android/OS execution evidence)");
    }

    private static void explicitWipeAndDrain() {
        DeathRecoveryLedger ledger = new DeathRecoveryLedger();
        DeathRecoveryLedger.Scope scope = scope(1);
        yes(ledger.grant(scope, true, true), "initial generation");
        no(ledger.reconcile(scope), "reservation is not cleanup");
        DeathRecoveryLedger.Claim claim = ledger.beginClaim(scope, false);
        DeathRecoveryLedger.Lease lease = ledger.addLease(scope);
        yes(claim != null && lease != null, "initial exact obligations");
        yes(ledger.fence(scope), "local fence");
        no(ledger.reconcile(scope), "fence is not cleanup");
        yes(ledger.senderWiped(scope, scope.owner.endpointToken, true), "sender scanned wipe fact");
        yes(ledger.workerFenced(scope, scope.worker.endpointToken, TICKET), "worker captured ticket");
        yes(ledger.workerWiped(scope, scope.worker.endpointToken, TICKET, true), "worker scanned wipe fact");
        no(ledger.workerQuiescent(scope, scope.worker.endpointToken, TICKET, 1), "remote copy still in stack");
        no(ledger.reconcile(scope), "local/remote obligations remain");
        yes(ledger.workerQuiescent(scope, scope.worker.endpointToken, TICKET, 0), "remote actual copy drainage fact");
        no(ledger.reconcile(scope), "remote drainage is not local call return");
        yes(ledger.finishClaim(claim), "actual local finally reported");
        no(ledger.reconcile(scope), "independent lease remains");
        yes(ledger.releaseLease(lease), "exact lease reported released");
        yes(ledger.reconcile(scope), "all scoped obligations reconciled explicitly");
        yes(ledger.state().newGenerationAllowed, "new generation only after reconcile");
        equal(DeathRecoveryLedger.Disposition.WIPED, ledger.state().senderDisposition, "sender wipe disposition");
        equal(DeathRecoveryLedger.Disposition.WIPED, ledger.state().receiverDisposition, "receiver wipe disposition");
    }

    private static void ownerDeathKeepsEveryDebt() {
        DeathRecoveryLedger ledger = new DeathRecoveryLedger();
        DeathRecoveryLedger.Scope scope = scope(1);
        ledger.grant(scope, true, true);
        DeathRecoveryLedger.Claim claim = ledger.beginClaim(scope, false);
        DeathRecoveryLedger.Lease lease = ledger.addLease(scope);
        yes(ledger.ownerDied(scope, scope.owner.endpointToken), "reported exact owner endpoint death");
        yes(ledger.ownerDied(scope, scope.owner.endpointToken), "duplicate death fact is idempotent");
        yes(ledger.state().quarantined, "owner death quarantines policy");
        equal(1, ledger.state().claims, "death retains actual call claim");
        equal(1, ledger.state().leases, "death retains exact extra lease");
        equal(DeathRecoveryLedger.Disposition.PENDING, ledger.state().senderDisposition, "death alone cannot dispose heap");
        equal(DeathRecoveryLedger.Disposition.PENDING, ledger.state().receiverDisposition, "owner death cannot dispose worker");
        no(ledger.grant(scope(2), true, true), "replacement owner denied before reconcile");
        no(ledger.disposeOwnerHostGone(scope), "missing local fence and outstanding call");
        ledger.fence(scope);
        no(ledger.disposeOwnerHostGone(scope), "fence cannot return outstanding call");
        ledger.finishClaim(claim);
        yes(ledger.disposeOwnerHostGone(scope), "explicit exclusive heap disposition after exact death and call return");
        equal(DeathRecoveryLedger.Disposition.HOST_GONE, ledger.state().senderDisposition, "host gone is separately named");
        no(ledger.state().senderScannedZero, "host gone is not scanned zero");
        no(ledger.reconcile(scope), "live worker and extra lease still unresolved");
        workerWipeAndDrain(ledger, scope);
        no(ledger.reconcile(scope), "worker cleanup cannot release extra lease");
        ledger.releaseLease(lease);
        yes(ledger.reconcile(scope), "explicit owner gone plus worker wipe and drainage");
    }

    private static void workerDeathIsNotWipe() {
        DeathRecoveryLedger ledger = new DeathRecoveryLedger();
        DeathRecoveryLedger.Scope scope = scope(1);
        ledger.grant(scope, true, true);
        DeathRecoveryLedger.Claim claim = ledger.beginClaim(scope, false);
        DeathRecoveryLedger.Lease lease = ledger.addLease(scope);
        ledger.fence(scope);
        ledger.workerDied(scope, scope.worker.endpointToken);
        equal(1, ledger.state().claims, "worker death retains local claim");
        equal(1, ledger.state().leases, "worker death retains extra lease");
        equal(DeathRecoveryLedger.Disposition.PENDING, ledger.state().receiverDisposition, "death alone cannot release receiver");
        no(ledger.disposeWorkerHostGone(scope), "worker death cannot return actual local call");
        ledger.finishClaim(claim);
        yes(ledger.disposeWorkerHostGone(scope), "explicit exact worker host gone");
        equal(DeathRecoveryLedger.Disposition.HOST_GONE, ledger.state().receiverDisposition, "worker gone separately named");
        no(ledger.state().receiverScannedZero || ledger.state().workerQuiescent, "death is neither scan nor copy drainage ACK");
        no(ledger.reconcile(scope), "live owner still owes sender");
        no(ledger.senderWiped(scope, scope.owner.endpointToken, false), "missing sender actual scan rejected");
        yes(ledger.senderWiped(scope, scope.owner.endpointToken, true), "live owner explicit wipe fact");
        no(ledger.reconcile(scope), "worker host gone still cannot release extra lease");
        ledger.releaseLease(lease);
        yes(ledger.reconcile(scope), "owner wipe plus exclusive worker host gone");
    }

    private static void externalCopiesRemainUnknown() {
        DeathRecoveryLedger ownerUnsafe = new DeathRecoveryLedger();
        DeathRecoveryLedger.Scope a = scope(1);
        ownerUnsafe.grant(a, false, true);
        ownerUnsafe.fence(a);
        ownerUnsafe.ownerDied(a, a.owner.endpointToken);
        no(ownerUnsafe.disposeOwnerHostGone(a), "external/native/persistent owner ownership cannot use host gone");
        workerWipeAndDrain(ownerUnsafe, a);
        no(ownerUnsafe.reconcile(a), "unsafe owner remains unresolved");
        no(ownerUnsafe.state().newGenerationAllowed, "unsafe owner replacement remains quarantined");
        yes(ownerUnsafe.senderWiped(a, a.owner.endpointToken, true), "private owner scan may be recorded");
        no(ownerUnsafe.reconcile(a), "private scan cannot dispose unknown external owner ownership");

        DeathRecoveryLedger workerUnsafe = new DeathRecoveryLedger();
        DeathRecoveryLedger.Scope b = scope(1);
        workerUnsafe.grant(b, true, false);
        workerUnsafe.fence(b);
        workerUnsafe.workerDied(b, b.worker.endpointToken);
        no(workerUnsafe.disposeWorkerHostGone(b), "nonexclusive worker cannot use host gone");
        workerUnsafe.senderWiped(b, b.owner.endpointToken, true);
        no(workerUnsafe.reconcile(b), "unsafe worker remains unresolved");
        workerWipeAndDrain(workerUnsafe, b);
        no(workerUnsafe.reconcile(b), "private receiver scan and drainage cannot dispose external worker ownership");
        no(workerUnsafe.state().newGenerationAllowed, "nonexclusive worker blocks replacement");
    }

    private static void exactKeysAndTickets() {
        DeathRecoveryLedger ledger = new DeathRecoveryLedger();
        DeathRecoveryLedger.Scope scope = scope(1);
        ledger.grant(scope, true, true);
        ledger.fence(scope);
        DeathRecoveryLedger.EndpointKey owner = scope.owner;
        DeathRecoveryLedger.EndpointKey wrongInc = new DeathRecoveryLedger.EndpointKey(owner.endpointToken,
                owner.capabilityToken, owner.incarnation ^ (1L << 32), owner.revision, owner.request);
        DeathRecoveryLedger.EndpointKey wrongRev = new DeathRecoveryLedger.EndpointKey(owner.endpointToken,
                owner.capabilityToken, owner.incarnation, owner.revision + (1L << 32), owner.request);
        DeathRecoveryLedger.EndpointKey wrongReq = new DeathRecoveryLedger.EndpointKey(owner.endpointToken,
                owner.capabilityToken, owner.incarnation, owner.revision, owner.request + (1L << 32));
        DeathRecoveryLedger.EndpointKey wrongCap = new DeathRecoveryLedger.EndpointKey(owner.endpointToken,
                new Object(), owner.incarnation, owner.revision, owner.request);
        for (DeathRecoveryLedger.EndpointKey wrong : new DeathRecoveryLedger.EndpointKey[] {wrongInc, wrongRev, wrongReq, wrongCap}) {
            DeathRecoveryLedger.Scope forged = new DeathRecoveryLedger.Scope(scope.generationToken, scope.generation, wrong, scope.worker);
            no(ledger.ownerDied(forged, owner.endpointToken), "changed owner key refused exactly");
            yes(ledger.snapshot(forged) == null, "changed key cannot expose active state");
        }
        no(ledger.ownerDied(scope, new Object()), "death callback token must be captured endpoint");
        no(ledger.workerDied(scope, owner.endpointToken), "owner token cannot report worker death");
        no(ledger.workerFenced(scope, scope.worker.endpointToken, 0), "clear ticket must exist");
        yes(ledger.workerFenced(scope, scope.worker.endpointToken, TICKET), "original worker fence accepted");
        yes(ledger.workerFenced(scope, scope.worker.endpointToken, TICKET), "same fence idempotent");
        no(ledger.workerFenced(scope, scope.worker.endpointToken, TICKET + (1L << 32)), "full long clear ticket cannot be replaced");
        no(ledger.workerWiped(scope, scope.worker.endpointToken, TICKET + 1, true), "wrong clear ticket cannot wipe");
        no(ledger.workerWiped(scope, new Object(), TICKET, true), "wrong worker cannot acknowledge wipe");
        no(ledger.workerWiped(scope, scope.worker.endpointToken, TICKET, false), "missing receiver actual scan refused");
        no(ledger.workerQuiescent(scope, scope.worker.endpointToken, TICKET, 0), "drainage needs scanned receiver wipe first");
        yes(ledger.workerWiped(scope, scope.worker.endpointToken, TICKET, true), "exact receiver wipe");
        no(ledger.workerQuiescent(scope, scope.worker.endpointToken, TICKET + 1, 0), "quiescence also binds ticket");
        no(ledger.workerQuiescent(scope, scope.worker.endpointToken, TICKET, -1), "invalid remote copy count refused");
        yes(ledger.workerQuiescent(scope, scope.worker.endpointToken, TICKET, 0), "exact independent copy drainage");
    }

    private static void boundsAndOpaqueHandles() {
        DeathRecoveryLedger ledger = new DeathRecoveryLedger();
        DeathRecoveryLedger other = new DeathRecoveryLedger();
        DeathRecoveryLedger.Scope scope = scope(1);
        ledger.grant(scope, true, true);
        DeathRecoveryLedger.Claim[] claims = new DeathRecoveryLedger.Claim[4];
        DeathRecoveryLedger.Lease[] leases = new DeathRecoveryLedger.Lease[4];
        for (int i = 0; i < 4; i++) {
            claims[i] = ledger.beginClaim(scope, false);
            leases[i] = ledger.addLease(scope);
            yes(claims[i] != null && leases[i] != null, "bounded exact obligation registered");
        }
        yes(ledger.beginClaim(scope, false) == null && ledger.addLease(scope) == null, "claim and lease limits enforced");
        no(other.finishClaim(claims[0]) || other.releaseLease(leases[0]), "foreign ledger cannot discharge handles");
        ledger.ownerDied(scope, scope.owner.endpointToken);
        ledger.fence(scope);
        equal(4, ledger.state().claims, "death and fence retain all claims");
        equal(4, ledger.state().leases, "death and fence retain all leases");
        for (int i = 0; i < 4; i++) {
            yes(ledger.finishClaim(claims[i]), "exact claim returned once");
            no(ledger.finishClaim(claims[i]), "duplicate finally cannot return claim twice");
            yes(ledger.releaseLease(leases[i]), "exact lease returned once");
            no(ledger.releaseLease(leases[i]), "duplicate release rejected");
        }
        yes(ledger.beginClaim(scope, false) == null && ledger.addLease(scope) == null, "revoked authority blocks material and new leases");
        DeathRecoveryLedger.Claim cleanup = ledger.beginClaim(scope, true);
        yes(cleanup != null, "trusted cleanup RPC still registers actual debt");
        no(ledger.disposeOwnerHostGone(scope), "cleanup RPC debt also blocks host gone");
        ledger.finishClaim(cleanup);
        ledger.disposeOwnerHostGone(scope);
        workerWipeAndDrain(ledger, scope);
        yes(ledger.reconcile(scope), "bounded obligations explicitly resolved");
    }

    private static void replacementAndLateEvidence() {
        DeathRecoveryLedger ledger = new DeathRecoveryLedger();
        DeathRecoveryLedger.Scope a = scope(1);
        ledger.grant(a, true, true);
        ledger.fence(a);
        ledger.ownerDied(a, a.owner.endpointToken);
        ledger.disposeOwnerHostGone(a);
        workerWipeAndDrain(ledger, a);
        ledger.reconcile(a);
        DeathRecoveryLedger.Scope reusedToken = new DeathRecoveryLedger.Scope(a.generationToken, 2, scope(2).owner, a.worker);
        no(ledger.grant(reusedToken, true, true), "generation token cannot be reused");
        DeathRecoveryLedger.Scope deadOwner = new DeathRecoveryLedger.Scope(new Object(), 2, a.owner, a.worker);
        no(ledger.grant(deadOwner, true, true), "known dead owner cannot be reopened");
        DeathRecoveryLedger.Scope b = new DeathRecoveryLedger.Scope(new Object(), 2, scope(2).owner, a.worker);
        yes(ledger.grant(b, true, true), "new owner and healthy existing worker after reconciliation");
        no(ledger.ownerDied(a, a.owner.endpointToken), "old owner death callback cannot affect new generation");
        no(ledger.workerDied(a, a.worker.endpointToken), "old scope callback cannot mark reused worker current generation");
        no(ledger.workerWiped(a, a.worker.endpointToken, TICKET, true), "old ACK cannot release new receiver");
        no(ledger.senderWiped(a, a.owner.endpointToken, true), "old sender ACK cannot release new owner");
        no(ledger.state().ownerDead || ledger.state().workerDead || ledger.state().fenced, "late evidence leaves new authority untouched");
        equal(DeathRecoveryLedger.Disposition.PENDING, ledger.state().receiverDisposition, "new receiver debt preserved");
    }

    private static void timeoutIsNotReturn() {
        DeathRecoveryLedger ledger = new DeathRecoveryLedger();
        DeathRecoveryLedger.Scope scope = scope(1);
        ledger.grant(scope, true, true);
        DeathRecoveryLedger.Claim claim = ledger.beginClaim(scope, false);
        ledger.unknown(scope);
        equal(1, ledger.state().claims, "timeout retains actual call");
        no(ledger.state().newGenerationAllowed, "UNKNOWN never admits replacement");
        yes(ledger.beginClaim(scope, false) == null, "UNKNOWN forbids replay");
        ledger.fence(scope);
        ledger.senderWiped(scope, scope.owner.endpointToken, true);
        workerWipeAndDrain(ledger, scope);
        no(ledger.reconcile(scope), "all remote evidence cannot return local call");
        ledger.finishClaim(claim);
        yes(ledger.reconcile(scope), "actual return and explicit reconcile resolve UNKNOWN");
    }

    private static void drainageEvidenceCanBeInvalidated() {
        DeathRecoveryLedger ledger = new DeathRecoveryLedger();
        DeathRecoveryLedger.Scope scope = scope(1);
        ledger.grant(scope, true, true);
        ledger.fence(scope);
        ledger.senderWiped(scope, scope.owner.endpointToken, true);
        workerWipeAndDrain(ledger, scope);
        yes(ledger.state().workerQuiescent, "first independent zero observation recorded");
        no(ledger.workerQuiescent(scope, scope.worker.endpointToken, TICKET + 1, 1), "wrong ticket cannot alter actual drainage fact");
        yes(ledger.state().workerQuiescent, "mismatched evidence leaves addressed generation untouched");
        no(ledger.workerQuiescent(scope, scope.worker.endpointToken, TICKET, 1), "matching nonzero observation is not quiescence");
        no(ledger.state().workerQuiescent, "later occupied worker invalidates prior drainage");
        yes(ledger.state().unknown, "contradictory actual observation quarantines");
        no(ledger.reconcile(scope), "stale zero observation cannot confirm cleanup");
        yes(ledger.workerQuiescent(scope, scope.worker.endpointToken, TICKET, 0), "fresh exact zero observation");
        yes(ledger.reconcile(scope), "fresh drainage plus explicit reconcile resolves uncertainty");
    }

    private static void concurrentFenceAndClaim() throws Exception {
        final DeathRecoveryLedger ledger = new DeathRecoveryLedger();
        final DeathRecoveryLedger.Scope scope = scope(1);
        ledger.grant(scope, true, true);
        final CountDownLatch go = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicReference<DeathRecoveryLedger.Claim> claim = new AtomicReference<>();
        Thread a = task(go, failure, () -> claim.set(ledger.beginClaim(scope, false)));
        Thread b = task(go, failure, () -> yes(ledger.fence(scope), "concurrent fence"));
        go.countDown();
        for (Thread thread : new Thread[] {a, b}) {
            thread.join(5000);
            yes(!thread.isAlive(), "bounded component operation returns");
        }
        if (failure.get() != null) throw new AssertionError("Policy task failed", failure.get());
        yes(ledger.beginClaim(scope, false) == null, "post-fence claim cannot authorize material call");
        equal(claim.get() == null ? 0 : 1, ledger.state().claims, "linearized claim/fence order preserved");
        if (claim.get() != null) ledger.finishClaim(claim.get());
    }

    private static Thread task(CountDownLatch go, AtomicReference<Throwable> failure, Runnable body) {
        Thread thread = new Thread(() -> {
            try { go.await(); body.run(); }
            catch (Throwable problem) { failure.compareAndSet(null, problem); }
        }, "death-policy-component-smoke");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static DeathRecoveryLedger.Scope scope(long generation) {
        DeathRecoveryLedger.EndpointKey owner = new DeathRecoveryLedger.EndpointKey(new IdentityToken(),
                new IdentityToken(), Long.MIN_VALUE + 41L, (1L << 36) + 1L, (1L << 44) + generation);
        DeathRecoveryLedger.EndpointKey worker = new DeathRecoveryLedger.EndpointKey(new IdentityToken(),
                new IdentityToken(), (1L << 60) + 17L, (1L << 38) + 3L, (1L << 46) + generation);
        return new DeathRecoveryLedger.Scope(new IdentityToken(), generation, owner, worker);
    }

    private static void workerWipeAndDrain(DeathRecoveryLedger ledger, DeathRecoveryLedger.Scope scope) {
        yes(ledger.workerFenced(scope, scope.worker.endpointToken, TICKET), "worker exact fence fact");
        yes(ledger.workerWiped(scope, scope.worker.endpointToken, TICKET, true), "worker exact scanned wipe fact");
        yes(ledger.workerQuiescent(scope, scope.worker.endpointToken, TICKET, 0), "independent worker copy drainage fact");
    }

    /** Any use of equals/hashCode would invalidate the strong-identity contract. */
    private static final class IdentityToken {
        @Override public boolean equals(Object ignored) { throw new AssertionError("Token equals was invoked"); }
        @Override public int hashCode() { throw new AssertionError("Token hash was invoked"); }
    }

    private static void equal(Object expected, Object actual, String label) { yes(expected == actual, label); }
    private static void equal(int expected, int actual, String label) { yes(expected == actual, label); }
    private static void no(boolean condition, String label) { yes(!condition, label); }
    private static void yes(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
}
