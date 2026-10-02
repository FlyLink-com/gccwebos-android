package com.gccwebos.android.ipcqa.b2;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.gccwebos.android.ipcqa.CopyLedger;
import com.gccwebos.android.ipcqa.RemoteCopyClient;
import com.gccwebos.android.ipcqa.Wire;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** Future, separately authorized P7-P9 device fixtures. Harness owns metadata only.
 * No test uses shell, PID-based termination, VPN, network, fd or real configuration.
 * HOST_GONE describes the reviewed exclusive Java heap, never a scanned zero ACK. */
public final class OwnerDeathIpcTest {
    private static final long WAIT_SECONDS = 10;
    private static final AtomicLong REQUESTS = new AtomicLong(SystemClock.elapsedRealtimeNanos());
    private final Instrumentation instrumentation;
    private String activeCase = "B2";

    public OwnerDeathIpcTest(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    void run(String method) throws Exception {
        switch (method) {
            case "p7OwnerDeathKeepsWorkerAndQuarantine": p7OwnerDeathKeepsWorkerAndQuarantine(); break;
            case "p8ExactEvidenceWaitsForCallAndDisposition": p8ExactEvidenceWaitsForCallAndDisposition(); break;
            case "p9WorkerDeathAndStaleEvidenceStayScoped": p9WorkerDeathAndStaleEvidenceStayScoped(); break;
            default: throw new AssertionError("B2 unsupported single-method selection");
        }
    }

    public void p7OwnerDeathKeepsWorkerAndQuarantine() throws Exception {
        activeCase = "p7OwnerDeathKeepsWorkerAndQuarantine";
        try (Fixture fixture = new Fixture(instrumentation.getTargetContext(), activeCase)) {
            DeathSessionGuard guard = fixture.initial;
            threeProcesses(guard);
            Entry a = fixture.reserve(guard);
            check(guard.wireClient().armGate(a.handle.workerScope(), Wire.AFTER_COPY).status == 0,
                "B2 P7 exact worker after-copy gate must arm");
            Future<B2Control.Message> blocked = fixture.send(a, 71, 23);
            awaitWorkerGate(guard, Wire.AFTER_COPY);
            ownerHas(guard.ownerClient().snapshot(a.handle.ownerScope()), B2Control.SENDER_HELD,
                "B2 P7 owner must retain sender while its actual worker call is blocked");
            matchingWorker(guard, a.handle, 23, "B2 P7 worker independently owns actual material");
            B2Control.Message beforeDeath = guard.workerClient().drain(a.handle.workerScope());
            workerTrace("worker-gated-before-owner-loss", beforeDeath);
            check(beforeDeath.status == CopyLedger.SNAPSHOT && beforeDeath.claims == 1
                    && guard.ownerClient().snapshot(a.handle.ownerScope()).claims == 1
                    && guard.state(a.handle).claims == 1 && !blocked.isDone(),
                "B2 P7 actual worker COPY, owner SEND and harness claim must all remain in stack");

            DeathQaClient.ExitToken oldExit = fault(guard.ownerClient(), a.handle.ownerScope());
            boolean death = guard.ownerWatch().await(WAIT_SECONDS, TimeUnit.SECONDS);
            deathTrace("owner", death);
            check(death,
                "B2 P7 requires actual exact-owner Binder death callback");
            boolean lostReply = false;
            try { await(blocked); }
            catch (ExecutionException expected) { lostReply = expected.getCause() instanceof RemoteException; }
            check(lostReply && blocked.isDone() && !blocked.isCancelled(),
                "B2 P7 actual harness-to-owner SEND must separately return through its finally");
            check(!guard.workerWatch().isDead() && guard.wireClient().binder.isBinderAlive(),
                "B2 P7 independent harness binding must keep worker endpoint live");
            matchingWorker(guard, a.handle, 23,
                "B2 P7 owner death must not erase the still-live worker copy");
            DeathRecoveryLedger.State lost = guard.state(a.handle);
            stateTrace("owner-death-and-local-return", lost);
            check(lost.ownerDead && !lost.workerDead && lost.unknown && lost.quarantined && lost.claims == 0
                    && lost.senderDisposition == DeathRecoveryLedger.Disposition.PENDING
                    && lost.receiverDisposition == DeathRecoveryLedger.Disposition.PENDING,
                "B2 P7 actual owner death alone must leave both disposition debts unresolved");
            check(!guard.fenceAndWipeWorker(a.handle),
                "B2 P7 scanned worker wipe cannot manufacture drainage of gated actual COPY");
            B2Control.Message afterWipe = guard.workerClient().drain(a.handle.workerScope());
            workerTrace("worker-wiped-still-copy-inflight", afterWipe);
            DeathRecoveryLedger.State wiped = guard.state(a.handle);
            stateTrace("receiver-zero-without-worker-drain", wiped);
            check(afterWipe.status == CopyLedger.SNAPSHOT && afterWipe.claims == 1
                    && wiped.receiverDisposition == DeathRecoveryLedger.Disposition.WIPED
                    && wiped.receiverScannedZero && !wiped.workerQuiescent && wiped.claims == 0,
                "B2 P7 worker COPY must survive owner death and its separate receiver wipe");
            check(!guard.reconcile(a.handle), "B2 P7 death callback alone cannot confirm cleanup");

            Bound rebuiltOwner = fixture.bind(B2Control.OWNER);
            DeathSessionGuard rebuilt = fixture.guard(rebuiltOwner, fixture.worker);
            freshEndpoint(guard.ownerClient().session.scope, rebuilt.ownerClient().session.scope,
                "B2 P7 new owner must have a fresh actual endpoint");
            ownerHas(rebuilt.ownerClient().session, B2Control.QUARANTINED,
                "B2 P7 newly created owner starts quarantined");
            check(!rebuilt.open() && rebuilt.reserve(nextRequest()) == null,
                "B2 P7 same surviving Recovery must reject a new owner over old worker debt");
            Wire.Frame stillZero = guard.wireClient().probe(a.handle.workerScope(), 23);
            wireHas(stillZero, CopyLedger.FLAG_ZEROED | CopyLedger.FLAG_RECEIVER_RELEASED,
                "B2 P7 rebuilding owner must preserve the exact old zeroed receiver record");
            check(guard.workerClient().drain(a.handle.workerScope()).claims == 1,
                "B2 P7 rebuilding owner must not retire the still-gated old worker COPY");
            check(DeathSessionGuard.Recovery.forHarness() == fixture.recovery,
                "B2 P7 reconstruction must reuse the sole process recovery authority");
            boolean oldTokenRejected = false;
            try { rebuilt.ownerClient().triggerSelfExit(oldExit); }
            catch (IllegalArgumentException expected) { oldTokenRejected = true; }
            check(oldTokenRejected && rebuilt.ownerClient().binder.isBinderAlive(),
                "B2 P7 old exact-owner fault token must not target a new owner");
        }
    }

    public void p8ExactEvidenceWaitsForCallAndDisposition() throws Exception {
        activeCase = "p8ExactEvidenceWaitsForCallAndDisposition";
        try (Fixture fixture = new Fixture(instrumentation.getTargetContext(), activeCase)) {
            DeathSessionGuard guard = fixture.initial;
            threeProcesses(guard);
            Entry a = fixture.reserve(guard);
            DeathRecoveryLedger.Lease extra = fixture.lease(a);
            check(guard.ownerClient().armOwnerGate(a.handle.ownerScope()).status == B2Control.OK,
                "B2 P8 after-worker-ACK owner gate must arm");
            Future<B2Control.Message> blocked = fixture.send(a, 73, 31);
            awaitOwnerGate(guard, a.handle);
            matchingWorker(guard, a.handle, 31, "B2 P8 worker copy must exist before owner gate");
            B2Control.Message own = guard.ownerClient().snapshot(a.handle.ownerScope());
            ownerHas(own, B2Control.SENDER_HELD | B2Control.COPIED_NOT_ACTIVE,
                "B2 P8 gate must follow actual worker ACK while owner retains its sender");
            check(own.claims == 1 && guard.state(a.handle).claims == 1,
                "B2 P8 remote SEND and surviving-harness claim must both remain in stack");
            ownerTrace("owner-gated-after-worker-ACK", own);
            boolean waitTimedOut = false;
            try { blocked.get(100, TimeUnit.MILLISECONDS); }
            catch (TimeoutException expected) { waitTimedOut = true; }
            check(waitTimedOut && !blocked.isDone() && !blocked.isCancelled(),
                "B2 P8 await timeout must not finish or cancel the actual Binder task");

            check(guard.fenceAndWipeWorker(a.handle),
                "B2 P8 exact worker fence, scanned wipe and actual COPY drain must complete");
            Wire.Frame zero = guard.wireClient().probe(a.handle.workerScope(), 31);
            wireHas(zero, CopyLedger.FLAG_ZEROED | CopyLedger.FLAG_RECEIVER_RELEASED,
                "B2 P8 actual receiver array must be zero before its lease releases");
            check((zero.arg & (CopyLedger.FLAG_HELD | CopyLedger.FLAG_MATCH)) == 0,
                "B2 P8 zeroed receiver must have no held lease or matching old material");
            B2Control.Message drain = guard.workerClient().drain(a.handle.workerScope());
            workerTrace("receiver-wiped-worker-copy-drained", drain);
            check(drain.status == CopyLedger.SNAPSHOT && drain.claims == 0,
                "B2 P8 worker COPY drainage is separate from blocked owner SEND");
            check(B2Control.exact(drain.scope, a.handle.workerScope())
                    && drain.scope.ticket == guard.state(a.handle).workerClearTicket,
                "B2 P8 drain must keep the exact worker scope and captured clear ticket");
            DeathRecoveryLedger.State receiverOnly = guard.state(a.handle);
            stateTrace("receiver-only-cleanup", receiverOnly);
            check(receiverOnly.claims == 1 && receiverOnly.leases == 1
                    && receiverOnly.receiverDisposition == DeathRecoveryLedger.Disposition.WIPED
                    && receiverOnly.receiverScannedZero && receiverOnly.workerQuiescent
                    && receiverOnly.senderDisposition == DeathRecoveryLedger.Disposition.PENDING,
                "B2 P8 worker evidence must preserve owner heap, harness call and extra lease debts");
            check(!guard.wipeSender(a.handle) && !guard.ownerHostGone(a.handle)
                    && !guard.workerHostGone(a.handle) && !guard.reconcile(a.handle)
                    && guard.reserve(nextRequest()) == null,
                "B2 P8 worker cleanup cannot manufacture sender wipe, death or call exit");
            check(!blocked.isDone() && guard.state(a.handle).claims == 1,
                "B2 P8 cleanup controls must not retire the actual blocked SEND claim");
            B2Control.Message refusedWipe = guard.ownerClient().snapshot(a.handle.ownerScope());
            ownerTrace("sender-wipe-refused-while-SEND-inflight", refusedWipe);
            check(refusedWipe.claims == 1 && (refusedWipe.arg & B2Control.SENDER_HELD) != 0
                    && (refusedWipe.arg & B2Control.SENDER_ZEROED) == 0,
                "B2 P8 refused sender wipe must leave the actual borrowed owner allocation intact");

            fault(guard.ownerClient(), a.handle.ownerScope());
            boolean death = guard.ownerWatch().await(WAIT_SECONDS, TimeUnit.SECONDS);
            deathTrace("owner", death);
            check(death,
                "B2 P8 exact-owner death callback must actually arrive");
            // Callback and Binder return may occur in either order. Await each;
            // never infer call finally from the callback, disconnection or timeout.
            boolean lostReply = false;
            try { await(blocked); }
            catch (ExecutionException expected) { lostReply = expected.getCause() instanceof RemoteException; }
            check(lostReply && blocked.isDone() && !blocked.isCancelled(),
                "B2 P8 blocked real SEND must return through its transport-loss finally");
            DeathRecoveryLedger.State returned = guard.state(a.handle);
            stateTrace("actual-owner-loss-and-SEND-finally", returned);
            check(returned.ownerDead && returned.unknown && returned.claims == 0
                    && returned.senderDisposition == DeathRecoveryLedger.Disposition.PENDING
                    && !returned.senderScannedZero,
                "B2 P8 callback plus actual call exit still cannot invent a scanned sender wipe");
            check(guard.ownerHostGone(a.handle),
                "B2 P8 exclusive owner heap requires explicit exact HOST_GONE disposition");
            DeathRecoveryLedger.State disposed = guard.state(a.handle);
            stateTrace("owner-HOST_GONE-with-independent-lease", disposed);
            check(disposed.senderDisposition == DeathRecoveryLedger.Disposition.HOST_GONE
                    && !disposed.senderScannedZero && disposed.leases == 1
                    && disposed.receiverDisposition == DeathRecoveryLedger.Disposition.WIPED,
                "B2 P8 HOST_GONE must stay distinct from WIPED and preserve the extra lease");
            check(!guard.reconcile(a.handle) && !guard.releaseLease(null),
                "B2 P8 remaining exact lease and wrong release must keep recovery blocked");
            check(guard.releaseLease(extra) && !guard.releaseLease(extra),
                "B2 P8 exact independent lease may release once only");
            check(guard.reconcile(a.handle), "B2 P8 all scoped facts need explicit reconciliation");
            a.reconciled = true;
            check(guard.state(a.handle).newGenerationAllowed,
                "B2 P8 complete explicit policy reconciliation permits a future generation");

            Bound newOwner = fixture.bind(B2Control.OWNER);
            DeathSessionGuard next = fixture.guard(newOwner, fixture.worker);
            freshEndpoint(guard.ownerClient().session.scope, next.ownerClient().session.scope,
                "B2 P8 new owner must use a fresh endpoint after actual old-owner death");
            ownerHas(next.ownerClient().session, B2Control.QUARANTINED,
                "B2 P8 new owner is not implicitly opened by ledger reconciliation");
            Entry b = fixture.reserve(next);
            copied(await(fixture.send(b, 37, 41)), b.handle,
                "B2 P8 guarded explicit open permits a new independent synthetic copy");
            matchingWorker(next, b.handle, 41, "B2 P8 old worker debt must not be adopted as new material");
        }
    }

    public void p9WorkerDeathAndStaleEvidenceStayScoped() throws Exception {
        activeCase = "p9WorkerDeathAndStaleEvidenceStayScoped";
        try (Fixture fixture = new Fixture(instrumentation.getTargetContext(), activeCase)) {
            DeathSessionGuard guard = fixture.initial;
            threeProcesses(guard);
            Entry a = fixture.reserve(guard);
            DeathRecoveryLedger.Lease extra = fixture.lease(a);
            copied(await(fixture.send(a, 79, 47)), a.handle, "B2 P9 actual old-worker copy ACK");
            matchingWorker(guard, a.handle, 47, "B2 P9 old worker holds its independent allocation");
            // Capture a real earlier worker reply. Fence alone intentionally leaves
            // its material held; this receipt cannot later be laundered into B.
            Wire.Frame oldFence = guard.wireClient().clear(a.handle.workerScope());
            check(oldFence.status == CopyLedger.FENCED && oldFence.ticket > 0,
                "B2 P9 old clear receipt must come from the real addressed worker");
            wireHas(oldFence, CopyLedger.FLAG_HELD | CopyLedger.FLAG_REVOKED,
                "B2 P9 old worker fence is not a wipe or process-death witness");
            DeathQaClient.ExitToken oldExit = fault(guard.workerClient(), a.handle.workerScope());
            boolean death = guard.workerWatch().await(WAIT_SECONDS, TimeUnit.SECONDS);
            deathTrace("worker", death);
            check(death,
                "B2 P9 requires actual exact-worker Binder death callback");
            check(!guard.ownerWatch().isDead() && guard.ownerClient().binder.isBinderAlive(),
                "B2 P9 owner endpoint must remain live after worker death");
            awaitOwnerFlags(guard, a.handle, B2Control.WORKER_GONE);
            ownerHas(guard.ownerClient().snapshot(a.handle.ownerScope()), B2Control.SENDER_HELD,
                "B2 P9 dead worker must not erase the live owner sender");
            DeathRecoveryLedger.State lost = guard.state(a.handle);
            stateTrace("actual-worker-loss-owner-still-live", lost);
            check(lost.workerDead && !lost.ownerDead && lost.claims == 0 && lost.leases == 1
                    && lost.senderDisposition == DeathRecoveryLedger.Disposition.PENDING
                    && lost.receiverDisposition == DeathRecoveryLedger.Disposition.PENDING,
                "B2 P9 exact worker death does not dispose live-owner or independent lease debt");
            check(!guard.ownerHostGone(a.handle) && !guard.reconcile(a.handle),
                "B2 P9 live owner cannot be disposed through worker death");
            check(guard.wipeSender(a.handle), "B2 P9 live owner requires its own scanned sender wipe");
            ownerHas(guard.ownerClient().snapshot(a.handle.ownerScope()), B2Control.SENDER_ZEROED,
                "B2 P9 sender zero must be measured on the actual live-owner allocation");
            check(guard.workerHostGone(a.handle),
                "B2 P9 exclusive dead-worker heap needs explicit HOST_GONE disposition");
            DeathRecoveryLedger.State disposed = guard.state(a.handle);
            stateTrace("owner-scanned-WIPED-worker-HOST_GONE", disposed);
            check(disposed.senderDisposition == DeathRecoveryLedger.Disposition.WIPED
                    && disposed.senderScannedZero
                    && disposed.receiverDisposition == DeathRecoveryLedger.Disposition.HOST_GONE
                    && !disposed.receiverScannedZero && !disposed.workerQuiescent,
                "B2 P9 worker HOST_GONE is not a scanned receiver wipe or drain ACK");
            check(!guard.reconcile(a.handle), "B2 P9 extra harness lease must still block recovery");
            check(guard.releaseLease(extra) && !guard.releaseLease(extra),
                "B2 P9 exact lease releases once without replacing death evidence");
            check(guard.reconcile(a.handle), "B2 P9 matching facts allow explicit old-generation reconciliation");
            a.reconciled = true;
            check(!guard.ownerWatch().isDead(),
                "B2 P9 old owner cleanup must succeed while its host is still live");

            // The owner remains bound and alive. After RETIRE, the same owner can
            // explicitly attach the fresh worker, then requires guarded OPEN again.
            fixture.worker.close();
            Bound newWorker = fixture.bind(B2Control.WORKER);
            DeathSessionGuard next = fixture.guard(fixture.owner, newWorker);
            freshEndpoint(guard.workerClient().session.scope, next.workerClient().session.scope,
                "B2 P9 rebind must obtain a fresh actual worker endpoint");
            check(B2Control.sameEndpoint(guard.ownerClient().session.scope, next.ownerClient().session.scope)
                    && guard.ownerClient().binder == next.ownerClient().binder
                    && guard.ownerWatch() == next.ownerWatch() && !next.ownerWatch().isDead(),
                "B2 P9 adapter reconstruction must retain the same actual live-owner endpoint and watch");
            threeProcesses(next);
            Entry b = fixture.reserve(next);
            copied(await(fixture.send(b, 83, 53)), b.handle, "B2 P9 actual new-generation copy");
            DeathRecoveryLedger.State beforeReplay = next.state(b.handle);
            check(!guard.reobserveRecordedWorkerDeath(a.handle),
                "B2 P9 replay of the already observed old death must be rejected for retired scope");
            check(!guard.releaseLease(extra), "B2 P9 old duplicate lease cannot release a new generation");
            Wire.Frame oldReply = next.wireClient().wipe(oldFence);
            check(oldReply.status == CopyLedger.SCOPE,
                "B2 P9 actual old worker receipt must be refused by the new endpoint");
            boolean staleExitRejected = false;
            try { next.workerClient().triggerSelfExit(oldExit); }
            catch (IllegalArgumentException expected) { staleExitRejected = true; }
            check(staleExitRejected && !next.workerWatch().isDead(),
                "B2 P9 old typed fault token must not address the new worker");
            sameState(beforeReplay, next.state(b.handle),
                "B2 P9 old ACK, death and lease evidence must not mutate new-generation debt");
            stateTrace("new-generation-after-old-evidence-refused", next.state(b.handle));
            matchingWorker(next, b.handle, 53,
                "B2 P9 new worker material remains held after stale evidence attempts");
        }
    }

    private static long nextRequest() { return REQUESTS.incrementAndGet(); }

    private void threeProcesses(DeathSessionGuard guard) {
        B2Control.Message owner = guard.ownerClient().session, worker = guard.workerClient().session;
        check(!(guard.ownerClient().binder instanceof Binder)
                && !(guard.workerClient().binder instanceof Binder)
                && guard.ownerClient().binder.queryLocalInterface(OwnerDeathQaService.class.getName()) == null
                && guard.workerClient().binder.queryLocalInterface(WorkerDeathQaService.class.getName()) == null,
            "B2 owner and worker must use actual remote Binder proxies");
        check(owner.pid > 0 && worker.pid > 0 && owner.pid != worker.pid
                && owner.pid != Process.myPid() && worker.pid != Process.myPid(),
            "B2 harness, owner and worker must have three different actual PIDs");
        int appUid = instrumentation.getTargetContext().getApplicationInfo().uid;
        check(owner.uid == appUid && worker.uid == appUid && appUid == Process.myUid()
                && owner.callerUid == Process.myUid() && worker.callerUid == Process.myUid(),
            "B2 actual endpoint and captured system caller UIDs must match the private target policy");
        check(!guard.ownerWatch().isDead() && !guard.workerWatch().isDead(),
            "B2 both exact registered endpoint watches must start live");
        Log.i("B2QA", "case=" + activeCase + " stage=three-processes pid_diagnostic_only=true"
            + " harness_pid=" + Process.myPid() + " harness_uid=" + Process.myUid()
            + " owner_pid=" + owner.pid + " owner_uid=" + owner.uid + " owner_caller_uid=" + owner.callerUid
            + " worker_pid=" + worker.pid + " worker_uid=" + worker.uid + " worker_caller_uid=" + worker.callerUid
            + " owner_remote=" + !(guard.ownerClient().binder instanceof Binder)
            + " worker_remote=" + !(guard.workerClient().binder instanceof Binder));
        noMaterial(owner); noMaterial(worker);
    }

    private void deathTrace(String role, boolean observed) {
        Log.i("B2QA", "case=" + activeCase + " stage=awaited-binder-death role=" + role
            + " actual_callback_observed=" + observed);
    }
    private void ownerTrace(String stage, B2Control.Message reply) {
        Log.i("B2QA", "case=" + activeCase + " stage=" + stage + " owner_claims=" + reply.claims
            + " sender_held=" + ((reply.arg & B2Control.SENDER_HELD) != 0)
            + " sender_scanned_zero=" + ((reply.arg & B2Control.SENDER_ZEROED) != 0));
    }
    private void workerTrace(String stage, B2Control.Message reply) {
        workerTrace(activeCase, stage, reply);
    }
    private static void workerTrace(String caseName, String stage, B2Control.Message reply) {
        Log.i("B2QA", "case=" + caseName + " stage=" + stage + " worker_actual_COPY_count=" + reply.claims
            + " receiver_held=" + ((reply.scope.arg & CopyLedger.FLAG_HELD) != 0)
            + " receiver_scanned_zero=" + ((reply.scope.arg & CopyLedger.FLAG_ZEROED) != 0)
            + " receiver_released=" + ((reply.scope.arg & CopyLedger.FLAG_RECEIVER_RELEASED) != 0));
    }
    private void stateTrace(String stage, DeathRecoveryLedger.State state) {
        stateTrace(activeCase, stage, state);
    }
    private static void stateTrace(String caseName, String stage, DeathRecoveryLedger.State state) {
        if (state == null) {
            Log.i("B2QA", "case=" + caseName + " stage=" + stage + " state_available=false");
            return;
        }
        Log.i("B2QA", "case=" + caseName + " stage=" + stage + " local_claims=" + state.claims
            + " extra_leases=" + state.leases + " owner_dead=" + state.ownerDead + " worker_dead=" + state.workerDead
            + " sender_disposition=" + state.senderDisposition + " receiver_disposition=" + state.receiverDisposition
            + " sender_scanned_zero=" + state.senderScannedZero + " receiver_scanned_zero=" + state.receiverScannedZero
            + " worker_COPY_quiescent=" + state.workerQuiescent + " reconciled=" + state.reconciled);
    }

    private static DeathQaClient.ExitToken fault(DeathQaClient client, Wire.Frame scope) throws Exception {
        DeathQaClient.ExitToken token = client.armSelfExit(scope);
        try { client.triggerSelfExit(token); }
        catch (RemoteException expectedTransportLoss) {
            // Only the independently awaited actual DeathRecipient proves loss.
        }
        return token;
    }

    private static B2Control.Message await(Future<B2Control.Message> call) throws Exception {
        return call.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static void awaitOwnerGate(DeathSessionGuard guard, DeathSessionGuard.Handle handle)
            throws Exception {
        long deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(WAIT_SECONDS);
        while (SystemClock.elapsedRealtime() < deadline) {
            if (guard.ownerClient().ownerGateState(handle.ownerScope()).status == 1) return;
            Thread.sleep(10);
        }
        throw new AssertionError("B2 real SEND did not reach the owner after-ACK gate");
    }

    private static void awaitWorkerGate(DeathSessionGuard guard, int phase) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(WAIT_SECONDS);
        while (SystemClock.elapsedRealtime() < deadline) {
            if (guard.wireClient().gateState().arg == phase) return;
            Thread.sleep(10);
        }
        throw new AssertionError("B2 real COPY did not reach its exact worker gate");
    }

    private static B2Control.Message awaitWorkerDrain(DeathSessionGuard guard, DeathSessionGuard.Handle handle)
            throws Exception {
        long deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(WAIT_SECONDS);
        while (SystemClock.elapsedRealtime() < deadline) {
            B2Control.Message drain = guard.workerClient().drain(handle.workerScope());
            check(drain.status == CopyLedger.SNAPSHOT && B2Control.exact(drain.scope, handle.workerScope()),
                "B2 worker drainage poll must retain the exact actual scope");
            if (drain.claims == 0) return drain;
            Thread.sleep(10);
        }
        throw new AssertionError("B2 actual worker COPY did not drain after controlled gate release");
    }

    private static void awaitOwnerFlags(DeathSessionGuard guard, DeathSessionGuard.Handle handle, int flags)
            throws Exception {
        long deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(WAIT_SECONDS);
        while (SystemClock.elapsedRealtime() < deadline) {
            B2Control.Message reply = guard.ownerClient().snapshot(handle.ownerScope());
            if ((reply.arg & flags) == flags) return;
            Thread.sleep(10);
        }
        throw new AssertionError("B2 live owner did not observe its exact worker loss");
    }

    private static void copied(B2Control.Message reply, DeathSessionGuard.Handle handle, String label) {
        check(reply.status == CopyLedger.COPIED
                && B2Control.exact(reply.scope, handle.ownerScope())
                && B2Control.exact(reply.worker, handle.workerScope()), label);
        noMaterial(reply);
    }

    private static void matchingWorker(DeathSessionGuard guard, DeathSessionGuard.Handle handle,
            int seed, String label) throws Exception {
        Wire.Frame probe = guard.wireClient().probe(handle.workerScope(), seed);
        check(probe.status == CopyLedger.PROBED && B2Control.exact(probe, handle.workerScope()), label);
        wireHas(probe, CopyLedger.FLAG_HELD | CopyLedger.FLAG_MATCH, label);
        check(CopyLedger.markerCount(probe.arg) == 1, label);
    }

    private static void ownerHas(B2Control.Message reply, int flags, String label) {
        check((reply.arg & flags) == flags, label); noMaterial(reply);
    }
    private static void wireHas(Wire.Frame reply, long flags, String label) {
        check((reply.arg & flags) == flags && reply.payload != null && reply.payload.length == 0, label);
    }
    private static void freshEndpoint(Wire.Frame before, Wire.Frame after, String label) {
        check(!B2Control.sameEndpoint(before, after) && before.incarnation != after.incarnation, label);
    }
    private static void noMaterial(B2Control.Message reply) {
        check(reply.length == 0 && reply.seed == 0 && reply.attachment == null
                && reply.scope.payload.length == 0 && reply.worker.payload.length == 0,
            "B2 control replies must expose metadata only");
    }
    private static void sameState(DeathRecoveryLedger.State a, DeathRecoveryLedger.State b, String label) {
        check(a != null && b != null && a.scope == b.scope && a.phase == b.phase
                && a.claims == b.claims && a.leases == b.leases && a.fenced == b.fenced
                && a.unknown == b.unknown && a.ownerDead == b.ownerDead && a.workerDead == b.workerDead
                && a.senderDisposition == b.senderDisposition && a.receiverDisposition == b.receiverDisposition
                && a.senderScannedZero == b.senderScannedZero && a.receiverScannedZero == b.receiverScannedZero
                && a.workerQuiescent == b.workerQuiescent && a.workerClearTicket == b.workerClearTicket
                && a.reconciled == b.reconciled && a.newGenerationAllowed == b.newGenerationAllowed, label);
    }
    private static void check(boolean condition, String safeLabel) {
        if (!condition) throw new AssertionError(safeLabel);
    }

    private static final class Entry {
        final DeathSessionGuard guard;
        final DeathSessionGuard.Handle handle;
        final List<DeathRecoveryLedger.Lease> leases = new ArrayList<>();
        boolean reconciled;
        Entry(DeathSessionGuard guard, DeathSessionGuard.Handle handle) {
            this.guard = guard; this.handle = handle;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Context context;
        final String caseName;
        final DeathSessionGuard.Recovery recovery = DeathSessionGuard.Recovery.forHarness();
        final List<Bound> bindings = new ArrayList<>();
        final List<Entry> entries = new ArrayList<>();
        final List<Future<B2Control.Message>> calls = new ArrayList<>();
        final Bound worker, owner;
        final DeathSessionGuard initial;

        Fixture(Context context, String caseName) throws Exception {
            this.context = context; this.caseName = caseName;
            // Surviving harness has a separate binding for the worker throughout
            // the owner-loss cases; owner loss cannot remove its last binding.
            try {
                worker = bind(B2Control.WORKER);
                owner = bind(B2Control.OWNER);
                initial = guard(owner, worker);
            } catch (Exception | AssertionError failure) {
                for (int i = bindings.size() - 1; i >= 0; i--) bindings.get(i).close();
                throw failure;
            }
        }

        Bound bind(int role) throws Exception {
            Bound binding = new Bound(context, role); bindings.add(binding); return binding;
        }
        DeathSessionGuard guard(Bound ownerBinding, Bound workerBinding) throws Exception {
            return new DeathSessionGuard(ownerBinding.endpoint, workerBinding.endpoint, recovery);
        }
        Entry reserve(DeathSessionGuard guard) throws Exception {
            // Only metadata ATTACH/OPEN is idempotently awaited. The surviving
            // guard checks all old debt on every attempt. RESERVE and material
            // SEND below are each issued once and never retried or replayed.
            long deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(WAIT_SECONDS);
            boolean opened = false;
            do {
                opened = guard.open();
                if (opened) break;
                Thread.sleep(10);
            } while (SystemClock.elapsedRealtime() < deadline);
            check(opened, "B2 only a debt-free surviving guard may explicitly open an owner");
            DeathSessionGuard.Handle handle = guard.reserve(nextRequest());
            check(handle != null, "B2 explicit guarded reservation must be admitted");
            Entry entry = new Entry(guard, handle); entries.add(entry); return entry;
        }
        Future<B2Control.Message> send(Entry entry, int length, int seed) {
            Future<B2Control.Message> call = entry.guard.sendAsync(entry.handle, length, seed);
            calls.add(call); return call;
        }
        DeathRecoveryLedger.Lease lease(Entry entry) {
            DeathRecoveryLedger.Lease lease = entry.guard.addLease(entry.handle);
            check(lease != null, "B2 independent harness lease must register before revocation");
            entry.leases.add(lease); return lease;
        }

        /** No extra fault, cancellation, forced executor shutdown or death inference.
         * Cleanup errors propagate, so a fixture never passes with unresolved debt. */
        @Override public void close() throws Exception {
            Exception failure = null;
            try {
                for (Bound binding : bindings) {
                    if (binding.role == B2Control.WORKER && binding.endpoint.isBinderAlive())
                        binding.wire.releaseGate();
                }
                for (Entry entry : entries) {
                    if (!entry.reconciled && entry.guard.ownerClient().binder.isBinderAlive())
                        entry.guard.ownerClient().releaseOwnerGate(entry.handle.ownerScope());
                }
                for (Future<B2Control.Message> call : calls) {
                    try { await(call); }
                    catch (ExecutionException completedFailure) {
                        // Exceptional completion is actual task return, not cleanup
                        // proof. The test and scoped dispositions decide its meaning.
                    }
                }
                for (Entry entry : entries) {
                    if (entry.reconciled) continue;
                    DeathSessionGuard guard = entry.guard;
                    if (guard.workerWatch().isDead()) {
                        check(guard.workerHostGone(entry.handle),
                            "B2 finally needs exact observed worker HOST_GONE disposition");
                    } else {
                        B2Control.Message drain = awaitWorkerDrain(guard, entry.handle);
                        workerTrace(caseName, "finally-actual-worker-COPY-drained", drain);
                        check(guard.fenceAndWipeWorker(entry.handle),
                            "B2 finally needs actual scoped worker wipe and COPY drain");
                    }
                    if (guard.ownerWatch().isDead()) {
                        check(guard.ownerHostGone(entry.handle),
                            "B2 finally needs exact observed owner HOST_GONE disposition");
                    } else {
                        if (guard.workerWatch().isDead()) awaitOwnerFlags(guard, entry.handle, B2Control.WORKER_GONE);
                        check(guard.wipeSender(entry.handle),
                            "B2 finally needs actual scoped live-owner sender scan and wipe");
                    }
                    for (DeathRecoveryLedger.Lease lease : entry.leases) guard.releaseLease(lease);
                    check(guard.reconcile(entry.handle),
                        "B2 finally must explicitly reconcile every completed scoped debt");
                    stateTrace(caseName, "finally-explicitly-reconciled", guard.state(entry.handle));
                    entry.reconciled = true;
                }
            } catch (Exception cleanupFailure) {
                failure = cleanupFailure;
            } finally {
                for (int i = bindings.size() - 1; i >= 0; i--) {
                    try { bindings.get(i).close(); }
                    catch (RuntimeException unbindFailure) {
                        if (failure == null) failure = unbindFailure;
                        else failure.addSuppressed(unbindFailure);
                    }
                }
            }
            if (failure != null) throw failure;
        }
    }

    private static final class Bound implements AutoCloseable {
        final Context context;
        final int role;
        final CountDownLatch connected = new CountDownLatch(1);
        final ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                // Retain the original exact Binder. A later automatic reconnect
                // cannot overwrite the old endpoint whose death is under test.
                if (endpoint == null) endpoint = binder;
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {}
            @Override public void onBindingDied(ComponentName name) { connected.countDown(); }
            @Override public void onNullBinding(ComponentName name) { connected.countDown(); }
        };
        volatile IBinder endpoint;
        RemoteCopyClient wire;
        boolean bound;

        Bound(Context context, int role) throws Exception {
            this.context = context; this.role = role;
            try {
                Class<?> service = role == B2Control.OWNER ? OwnerDeathQaService.class : WorkerDeathQaService.class;
                Intent intent = new Intent(context, service);
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
                check(bound, "B2 explicit private service bind must be accepted");
                check(connected.await(WAIT_SECONDS, TimeUnit.SECONDS) && endpoint != null,
                    "B2 explicit binding must deliver its initial remote endpoint");
                if (role == B2Control.WORKER) wire = new RemoteCopyClient(endpoint);
            } catch (Exception | AssertionError failure) {
                close(); throw failure;
            }
        }
        @Override public void close() {
            if (bound) { context.unbindService(connection); bound = false; }
        }
    }
}
