package com.gccwebos.android.ipcqa;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Offline receiver-component smoke only. Passing this is not Binder or OS evidence. */
public final class CopyLedgerSmoke {
    private static final long INCARNATION = 0x7000000012345678L;
    private static final int SEED = 73;

    private CopyLedgerSmoke() { }

    public static void main(String[] args) throws Exception {
        cloneAndIdempotency();
        fenceAndActualWipe();
        delayedReceiptAndScopeIsolation();
        boundedRetainedRecords();
        noAllocationCleanup();
        concurrentCopies();
        for (int i = 0; i < 32; ++i) concurrentCopyAndClear();
        System.out.println("CopyLedger component smoke: PASS (no Binder or OS evidence)");
    }

    private static void cloneAndIdempotency() {
        CopyLedger ledger = new CopyLedger(INCARNATION);
        CopyLedger.Scope scope = granted(ledger, 1L);
        equal(CopyLedger.INVALID, ledger.copy(scope, new byte[0]).status, "empty refused");
        equal(CopyLedger.INVALID, ledger.copy(scope,
                new byte[CopyLedger.MAX_CONFIG_BYTES + 1]).status, "oversize refused");
        byte[] sender = pattern(CopyLedger.MAX_CONFIG_BYTES);
        CopyLedger.Result copied = ledger.copy(scope, sender);
        equal(CopyLedger.COPIED, copied.status, "copy accepted");
        Arrays.fill(sender, (byte) 0);
        flag(ledger.probe(scope, SEED), CopyLedger.FLAG_MATCH, true, "private clone survives sender erase");
        CopyLedger.Result duplicate = ledger.copy(scope, pattern(CopyLedger.MAX_CONFIG_BYTES));
        equal(CopyLedger.COPIED, duplicate.status, "duplicate accepted");
        equal(copied.ticket, duplicate.ticket, "duplicate retains marker");
        equal(1, CopyLedger.markerCount(ledger.snapshot(scope).arg), "one commit");
        byte[] conflict = pattern(CopyLedger.MAX_CONFIG_BYTES);
        conflict[conflict.length - 1] ^= 1;
        equal(CopyLedger.CONFLICT, ledger.copy(scope, conflict).status, "changed duplicate refused");
        flag(ledger.probe(scope, SEED), CopyLedger.FLAG_MATCH, true, "conflict leaves allocation intact");
    }

    private static void fenceAndActualWipe() {
        CopyLedger ledger = new CopyLedger(INCARNATION);
        CopyLedger.Scope scope = granted(ledger, 1L);
        ledger.copy(scope, pattern(256));
        CopyLedger.Result fence = ledger.clear(scope);
        equal(CopyLedger.FENCED, fence.status, "fence accepted");
        truth(fence.ticket != 0L, "fence has ticket");
        flag(fence, CopyLedger.FLAG_REVOKED, true, "fence revokes");
        flag(fence, CopyLedger.FLAG_HELD, true, "fence holds receiver lease");
        flag(fence, CopyLedger.FLAG_ZEROED, false, "fence has not wiped");
        flag(fence, CopyLedger.FLAG_RECEIVER_RELEASED, false, "fence is not receiver cleanup");
        equal(CopyLedger.REVOKED, ledger.copy(scope, pattern(256)).status, "late copy refused");
        equal(CopyLedger.OCCUPIED, ledger.grant(2L).status, "fence retains occupancy");
        equal(CopyLedger.TICKET, ledger.wipe(scope, fence.ticket + 1L).status, "wrong ticket refused");
        flag(ledger.probe(scope, SEED), CopyLedger.FLAG_MATCH, true, "wrong ticket preserves allocation");
        CopyLedger.Result wiped = ledger.wipe(scope, fence.ticket);
        equal(CopyLedger.WIPED, wiped.status, "explicit wipe accepted");
        flag(wiped, CopyLedger.FLAG_HELD, false, "receiver lease released");
        flag(wiped, CopyLedger.FLAG_ZEROED, true, "actual allocation zero-filled");
        flag(wiped, CopyLedger.FLAG_RECEIVER_RELEASED, true, "receiver-only cleanup");
        flag(ledger.probe(scope, SEED), CopyLedger.FLAG_MATCH, false, "old pattern absent");
        equal(fence.ticket, ledger.clear(scope).ticket, "clear retry retains original ticket");
        equal(fence.ticket, ledger.wipe(scope, fence.ticket).ticket, "wipe retry retains original ticket");
    }

    private static void delayedReceiptAndScopeIsolation() {
        CopyLedger ledger = new CopyLedger(INCARNATION);
        CopyLedger.Scope a = granted(ledger, (1L << 40) + 1L);
        ledger.copy(a, pattern(64));
        CopyLedger.Result delayedA = ledger.clear(a);
        ledger.wipe(a, delayedA.ticket);
        CopyLedger.Scope b = granted(ledger, a.request + 1L);
        equal(a.revision + 1L, b.revision, "revision advanced exactly once");
        ledger.copy(b, pattern(128));
        CopyLedger.Result stoppingB = ledger.clear(b);
        truth(delayedA.scope.equals(a), "late A receipt keeps A scope");
        truth(delayedA.ticket != stoppingB.ticket, "A receipt cannot become B ticket");
        CopyLedger.Scope sameLowIncarnation = new CopyLedger.Scope(a.incarnation ^ (1L << 32),
                b.revision, b.request);
        CopyLedger.Scope sameLowRevision = new CopyLedger.Scope(b.incarnation,
                b.revision + (1L << 32), b.request);
        CopyLedger.Scope sameLowRequest = new CopyLedger.Scope(b.incarnation,
                b.revision, b.request + (1L << 32));
        equal(CopyLedger.SCOPE, ledger.clear(sameLowIncarnation).status, "incarnation uses full long");
        equal(CopyLedger.SCOPE, ledger.clear(sameLowRevision).status, "revision uses full long");
        equal(CopyLedger.SCOPE, ledger.clear(sameLowRequest).status, "request uses full long");
        equal(CopyLedger.TICKET, ledger.wipe(b, delayedA.ticket).status, "A ticket cannot wipe B");
        equal(CopyLedger.TIMEOUT_MARKED, ledger.timeoutMarker(a, delayedA.ticket).status,
                "old timeout can address only A tombstone");
        flag(ledger.snapshot(b), CopyLedger.FLAG_TIMEOUT, false, "A timeout cannot mark B");
        flag(ledger.snapshot(b), CopyLedger.FLAG_HELD, true, "B receiver remains held");
        flag(ledger.probe(b, SEED), CopyLedger.FLAG_MATCH, true, "B bytes survive stale operations");
        equal(delayedA.ticket, ledger.clear(new CopyLedger.Scope(a.incarnation, a.revision,
                a.request)).ticket, "exact value scope retries original A ticket");
    }

    private static void boundedRetainedRecords() {
        CopyLedger ledger = new CopyLedger(-19L);
        CopyLedger.Scope[] scopes = new CopyLedger.Scope[3];
        for (int i = 0; i < scopes.length; ++i) {
            scopes[i] = granted(ledger, i + 1L);
            ledger.copy(scopes[i], pattern(CopyLedger.MAX_CONFIG_BYTES));
            CopyLedger.Result fence = ledger.clear(scopes[i]);
            ledger.wipe(scopes[i], fence.ticket);
        }
        equal(CopyLedger.SCOPE, ledger.snapshot(scopes[0]).status, "oldest tombstone evicted");
        flag(ledger.probe(scopes[1], SEED), CopyLedger.FLAG_ZEROED, true, "second tombstone actual zero");
        flag(ledger.probe(scopes[2], SEED), CopyLedger.FLAG_ZEROED, true, "third tombstone actual zero");
        equal(CopyLedger.REVOKED, ledger.grant(1L).status, "eviction cannot reuse old request");
        equal(48 * 1024, CopyLedger.MAX_OWNED_BYTES, "receiver resident allocation bound");
    }

    private static void noAllocationCleanup() {
        CopyLedger ledger = new CopyLedger(INCARNATION);
        CopyLedger.Scope scope = granted(ledger, 1L);
        CopyLedger.Result fence = ledger.clear(scope);
        CopyLedger.Result wiped = ledger.wipe(scope, fence.ticket);
        flag(wiped, CopyLedger.FLAG_RECEIVER_RELEASED, true, "explicit absence releases receiver slot");
        flag(wiped, CopyLedger.FLAG_ZEROED, false, "no allocation cannot claim actual erase");
        equal(0, CopyLedger.markerCount(wiped.arg), "no synthetic copy marker");
        granted(ledger, 2L);
    }

    private static void concurrentCopies() throws Exception {
        final CopyLedger ledger = new CopyLedger(INCARNATION);
        final CopyLedger.Scope scope = granted(ledger, 1L);
        final CopyLedger.Result[] replies = new CopyLedger.Result[8];
        final CountDownLatch go = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] threads = new Thread[replies.length];
        for (int i = 0; i < threads.length; ++i) {
            final int index = i;
            threads[i] = task(go, failure, new Runnable() {
                @Override public void run() { replies[index] = ledger.copy(scope, pattern(1024)); }
            });
        }
        go.countDown();
        finish(threads, failure);
        for (CopyLedger.Result reply : replies) {
            equal(CopyLedger.COPIED, reply.status, "concurrent duplicate copied");
            equal(replies[0].ticket, reply.ticket, "concurrent duplicates retain one marker");
        }
        equal(1, CopyLedger.markerCount(ledger.snapshot(scope).arg), "concurrent one commit");
    }

    private static void concurrentCopyAndClear() throws Exception {
        final CopyLedger ledger = new CopyLedger(INCARNATION);
        final CopyLedger.Scope scope = granted(ledger, 1L);
        final CopyLedger.Result[] replies = new CopyLedger.Result[2];
        final CountDownLatch go = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread copy = task(go, failure, new Runnable() {
            @Override public void run() { replies[0] = ledger.copy(scope, pattern(2048)); }
        });
        Thread clear = task(go, failure, new Runnable() {
            @Override public void run() { replies[1] = ledger.clear(scope); }
        });
        go.countDown();
        finish(new Thread[] {copy, clear}, failure);
        truth(replies[0].status == CopyLedger.COPIED || replies[0].status == CopyLedger.REVOKED,
                "race has an ordered copy result");
        equal(CopyLedger.FENCED, replies[1].status, "race fences");
        equal(CopyLedger.REVOKED, ledger.copy(scope, pattern(2048)).status, "post-fence copy cannot commit");
        equal(replies[0].status == CopyLedger.COPIED ? 1 : 0,
                CopyLedger.markerCount(ledger.snapshot(scope).arg), "race marker matches commit order");
        ledger.wipe(scope, replies[1].ticket);
        granted(ledger, 2L);
    }

    private static Thread task(final CountDownLatch go, final AtomicReference<Throwable> failure,
            final Runnable body) {
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    go.await();
                    body.run();
                } catch (Throwable problem) { failure.compareAndSet(null, problem); }
            }
        }, "copy-ledger-component-smoke");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void finish(Thread[] threads, AtomicReference<Throwable> failure) throws Exception {
        for (Thread thread : threads) {
            thread.join(5000L);
            truth(!thread.isAlive(), "component operation completed");
        }
        if (failure.get() != null) throw new AssertionError("Component worker failed", failure.get());
    }

    private static CopyLedger.Scope granted(CopyLedger ledger, long request) {
        CopyLedger.Result result = ledger.grant(request);
        equal(CopyLedger.GRANTED, result.status, "grant accepted");
        truth(result.scope != null, "grant carries scope");
        return result.scope;
    }

    private static byte[] pattern(int length) {
        byte[] value = new byte[length];
        for (int i = 0; i < value.length; ++i) value[i] = CopyLedger.patternByte(SEED, i);
        return value;
    }

    private static void flag(CopyLedger.Result result, long flag, boolean expected, String label) {
        truth(((result.arg & flag) != 0L) == expected, label);
    }

    private static void equal(long expected, long actual, String label) {
        truth(expected == actual, label);
    }

    private static void truth(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
