package com.gccwebos.android.ipcqa;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Binder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Six device-side B1 fixtures. They use only public synthetic byte patterns.
 * These assertions do not establish production activation, VPN or OS erasure. */
public final class CopyBoundaryIpcTest {
    private static final long WAIT_SECONDS = 10;
    private static final AtomicLong REQUESTS = new AtomicLong(SystemClock.elapsedRealtimeNanos());
    private final Instrumentation instrumentation;

    public CopyBoundaryIpcTest(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    void run(String method) throws Exception {
        switch (method) {
            case "p1RemoteIdentity": p1RemoteIdentity(); break;
            case "p2OwnedCopyAndWipe": p2OwnedCopyAndWipe(); break;
            case "p3BoundedActualParcels": p3BoundedActualParcels(); break;
            case "p4ClearBeforeCopyAndLateAck": p4ClearBeforeCopyAndLateAck(); break;
            case "p5OriginalStopTicketAcrossGeneration": p5OriginalStopTicketAcrossGeneration(); break;
            case "p6UnknownKeepsClaimUntilCallReturns": p6UnknownKeepsClaimUntilCallReturns(); break;
            default: throw new AssertionError("Unsupported B1 method");
        }
    }

    public void p1RemoteIdentity() throws Exception {
        try (Binding binding = bind()) {
            RemoteCopyClient client = binding.client;
            check(client.binder.queryLocalInterface(CopyBoundaryQaService.class.getName()) == null
                    && !(client.binder instanceof Binder),
                "P1 requires a remote Binder proxy");
            check(client.binder.isBinderAlive(), "P1 remote Binder must be live");
            Wire.Frame hello = client.session;
            check(hello.arg > 0 && hello.arg != Process.myPid(),
                "P1 service and harness must have different actual PIDs");
            check(hello.request == Process.myUid(),
                "P1 captured system caller UID must equal harness UID");
            check(hello.revision == instrumentation.getTargetContext().getApplicationInfo().uid,
                "P1 service UID must equal target application UID");
            check(hello.incarnation != 0L && (hello.capHi != 0L || hello.capLo != 0L),
                "P1 live endpoint must issue a nonempty capability and incarnation");
            noMaterial(hello);
        }
    }

    public void p2OwnedCopyAndWipe() throws Exception {
        try (Binding binding = bind()) {
            RemoteCopyClient client = binding.client;
            Wire.Frame scope = binding.grant();
            int seed = 23;
            byte[] fixture = pattern(127, seed);
            Wire.Frame copied = client.copy(scope, fixture);
            status(copied, CopyLedger.COPIED, "P2 receiver copy ACK");
            has(copied, CopyLedger.FLAG_HELD, "P2 ACK must follow receiver lease registration");
            check(CopyLedger.markerCount(copied.arg) == 1, "P2 one actual copy marker");
            Wire.Frame probe = client.probe(scope, seed);
            status(probe, CopyLedger.PROBED, "P2 actual receiver probe");
            has(probe, CopyLedger.FLAG_HELD | CopyLedger.FLAG_MATCH,
                "P2 receiver must independently own matching synthetic material");

            Arrays.fill(fixture, (byte) 0x5a);
            byte[] fixtureAfterCallerEdit = fixture.clone();
            has(client.probe(scope, seed), CopyLedger.FLAG_HELD | CopyLedger.FLAG_MATCH,
                "P2 editing caller fixture must not change receiver allocation");
            Wire.Frame clear = client.clear(scope);
            status(clear, CopyLedger.FENCED, "P2 clear must return an authority fence");
            has(clear, CopyLedger.FLAG_REVOKED | CopyLedger.FLAG_HELD,
                "P2 FENCED must retain receiver debt before wipe");
            status(client.grant(nextRequest()), CopyLedger.OCCUPIED,
                "P2 FENCED alone must not admit another request");
            Wire.Frame wiped = client.wipe(clear);
            status(wiped, CopyLedger.WIPED, "P2 matching receiver wipe");
            has(wiped, CopyLedger.FLAG_ZEROED | CopyLedger.FLAG_RECEIVER_RELEASED,
                "P2 wipe must zero the receiver allocation before release");
            lacks(wiped, CopyLedger.FLAG_HELD, "P2 receiver lease must be released after zeroing");
            probe = client.probe(scope, seed);
            has(probe, CopyLedger.FLAG_ZEROED | CopyLedger.FLAG_RECEIVER_RELEASED,
                "P2 post-wipe actual receiver probe");
            lacks(probe, CopyLedger.FLAG_MATCH, "P2 erased bytes must not match the original pattern");
            check(Arrays.equals(fixture, fixtureAfterCallerEdit),
                "P2 receiver cleanup must not erase the caller fixture");
            noMaterial(copied); noMaterial(probe); noMaterial(wiped);
        }
    }

    public void p3BoundedActualParcels() throws Exception {
        try (Binding binding = bind()) {
            RemoteCopyClient client = binding.client;
            Wire.Frame scope = binding.grant();
            Wire.Frame sample = copyFrame(scope, pattern(8, 17));
            rejectedParcel(client, scope, parcel -> {}, "P3 missing actual control header");
            rejectedParcel(client, scope, parcel -> Wire.write(parcel, copyFrame(scope, new byte[0])),
                "P3 empty actual COPY payload");
            rejectedParcel(client, scope, parcel -> {
                Wire.write(parcel, copyFrame(scope, new byte[0]));
                parcel.setDataPosition(Wire.HEADER_BYTES - 4);
                parcel.writeInt(Wire.MAX_PAYLOAD + 1);
                for (int i = 0; i <= Wire.MAX_PAYLOAD; i++) parcel.writeInt(17);
            }, "P3 one byte beyond lab encoded budget");
            rejectedParcel(client, scope, parcel -> {
                Wire.write(parcel, sample); parcel.setDataPosition(4); parcel.writeInt(Wire.SCHEMA + 1);
            }, "P3 unsupported actual schema");
            rejectedParcel(client, scope, parcel -> {
                Wire.write(parcel, sample); parcel.setDataPosition(8); parcel.writeInt(127);
            }, "P3 unsupported actual opcode");
            rejectedParcel(client, scope, parcel -> {
                Wire.write(parcel, sample); parcel.setDataPosition(Wire.HEADER_BYTES - 4);
                parcel.writeInt(sample.payload.length + 1);
            }, "P3 declared and encoded payload length mismatch");
            rejectedParcel(client, scope, parcel -> {
                Wire.write(parcel, sample); parcel.writeInt(1);
            }, "P3 forbidden trailing encoded data");
            rejectedParcel(client, scope, parcel -> {
                Wire.write(parcel, sample); parcel.setDataPosition(Wire.HEADER_BYTES); parcel.writeInt(256);
            }, "P3 invalid positive encoded octet");
            rejectedParcel(client, scope, parcel -> {
                Wire.write(parcel, sample); parcel.setDataPosition(Wire.HEADER_BYTES); parcel.writeInt(-1);
            }, "P3 invalid negative encoded octet");
            boolean senderRejected = false;
            try { client.copy(scope, new byte[Wire.MAX_PAYLOAD + 1]); }
            catch (IllegalArgumentException expected) { senderRejected = true; }
            check(senderRejected, "P3 sender codec must reject beyond its payload budget");
            noReceiverCopy(client, scope, "P3 local rejection must not copy remotely");
            Wire.Frame wrongCapability = client.rawTransaction(parcel -> {
                Wire.write(parcel, sample); parcel.setDataPosition(16);
                parcel.writeLong(sample.capHi ^ Long.MIN_VALUE);
            });
            status(wrongCapability, CopyLedger.SCOPE,
                "P3 actual transaction with wrong endpoint capability must be refused");
            noReceiverCopy(client, scope, "P3 bad capability must not create a receiver copy");

            int seed = 31;
            Wire.Frame exact = client.copy(scope, pattern(Wire.MAX_PAYLOAD, seed));
            status(exact, CopyLedger.COPIED, "P3 exact lab limit must copy");
            has(client.probe(scope, seed), CopyLedger.FLAG_HELD | CopyLedger.FLAG_MATCH,
                "P3 exact-limit ACK must reflect real receiver allocation");
            check(CopyLedger.markerCount(exact.arg) == 1,
                "P3 malformed Parcels must not create unregistered or extra copies");
        }
    }

    public void p4ClearBeforeCopyAndLateAck() throws Exception {
        try (Binding binding = bind()) {
            RemoteCopyClient client = binding.client;
            Wire.Frame first = binding.grant();
            client.armGate(first, Wire.BEFORE_COPY);
            Future<Wire.Frame> before = binding.async(() -> client.copy(first, pattern(43, 41)));
            awaitGate(client, Wire.BEFORE_COPY);
            Wire.Frame firstClear = client.clear(first);
            status(firstClear, CopyLedger.FENCED, "P4 clear must linearize before gated copy");
            check(CopyLedger.markerCount(firstClear.arg) == 0, "P4 pre-copy fence has no admission marker");
            has(firstClear, CopyLedger.FLAG_REVOKED, "P4 first authority must be revoked");
            status(client.grant(nextRequest()), CopyLedger.OCCUPIED,
                "P4 a fence without cleanup keeps the first request occupied");
            client.releaseGate();
            status(result(before), CopyLedger.REVOKED, "P4 old gated COPY must be refused after fence");
            check(CopyLedger.markerCount(client.snapshot(first).arg) == 0,
                "P4 rejected old copy must not add a marker");
            status(client.wipe(firstClear), CopyLedger.WIPED, "P4 no-copy request requires explicit release");

            Wire.Frame second = binding.grant();
            client.armGate(second, Wire.AFTER_COPY);
            Future<Wire.Frame> after = binding.async(() -> client.copy(second, pattern(47, 43)));
            awaitGate(client, Wire.AFTER_COPY);
            has(client.probe(second, 43), CopyLedger.FLAG_HELD | CopyLedger.FLAG_MATCH,
                "P4 reverse order must actually copy before delayed ACK");
            Wire.Frame secondClear = client.clear(second);
            status(secondClear, CopyLedger.FENCED, "P4 fence during delayed copy ACK");
            has(secondClear, CopyLedger.FLAG_REVOKED | CopyLedger.FLAG_HELD,
                "P4 delayed ACK does not erase receiver debt");
            check(CopyLedger.markerCount(secondClear.arg) == 1, "P4 exactly one earlier marker");
            client.releaseGate();
            Wire.Frame lateAck = result(after);
            status(lateAck, CopyLedger.COPIED, "P4 late ACK describes only the earlier independent copy");
            sameScope(second, lateAck, "P4 late ACK must retain its earlier scope");
            Wire.Frame state = client.snapshot(second);
            has(state, CopyLedger.FLAG_REVOKED | CopyLedger.FLAG_HELD,
                "P4 late ACK must not revive remote authority");
            lacks(state, CopyLedger.FLAG_CURRENT, "P4 revoked scope is not current after late ACK");
            status(client.copy(second, pattern(47, 43)), CopyLedger.REVOKED,
                "P4 late ACK must not permit another old COPY");
            check(CopyLedger.markerCount(client.snapshot(second).arg) == 1,
                "P4 late ACK must not increase marker count");
            status(client.wipe(secondClear), CopyLedger.WIPED, "P4 release second receiver allocation");
        }
    }

    public void p5OriginalStopTicketAcrossGeneration() throws Exception {
        try (Binding binding = bind()) {
            RemoteCopyClient client = binding.client;
            Wire.Frame a = binding.grant();
            status(client.copy(a, pattern(53, 47)), CopyLedger.COPIED, "P5 actual A copy");
            client.armGate(a, Wire.AFTER_CLEAR);
            Future<Wire.Frame> oldStop = binding.async(() -> client.clear(a));
            awaitGate(client, Wire.AFTER_CLEAR);
            // Snapshot observes the already captured A ticket while A's original
            // Binder call remains in stack. It is not a synthetic stop return.
            Wire.Frame aFence = client.snapshot(a);
            status(aFence, CopyLedger.SNAPSHOT, "P5 inspect addressed A fence");
            has(aFence, CopyLedger.FLAG_REVOKED, "P5 A must already be fenced");
            check(aFence.ticket != 0L, "P5 A ticket must exist at its linearization point");
            status(client.wipe(aFence), CopyLedger.WIPED, "P5 addressed A receiver wipe");
            Wire.Frame b = binding.grant();
            Wire.Frame bCopy = client.copy(b, pattern(59, 53));
            status(bCopy, CopyLedger.COPIED, "P5 actual B copy before A returns");
            Wire.Frame duplicate = client.copy(b, pattern(59, 53));
            status(duplicate, CopyLedger.COPIED, "P5 identical duplicate B ACK");
            check(duplicate.ticket == bCopy.ticket && CopyLedger.markerCount(duplicate.arg) == 1,
                "P5 identical duplicate must not recopy or change marker");
            status(client.copy(b, pattern(59, 54)), CopyLedger.CONFLICT,
                "P5 same scope with changed material must conflict");
            has(client.probe(b, 53), CopyLedger.FLAG_HELD | CopyLedger.FLAG_MATCH,
                "P5 conflicting duplicate must not replace B material");
            Wire.Frame bFence = client.clear(b);
            status(bFence, CopyLedger.FENCED, "P5 B fence before A original return");
            check(bFence.ticket != 0L && bFence.ticket != aFence.ticket,
                "P5 A and B must retain distinct original tickets");
            client.releaseGate();
            Wire.Frame returnedA = result(oldStop);
            status(returnedA, CopyLedger.FENCED, "P5 delayed original A return");
            sameScope(a, returnedA, "P5 stop A must return A original scope");
            check(returnedA.ticket == aFence.ticket && returnedA.ticket != bFence.ticket,
                "P5 stop A must capture A original ticket before B exists");
            status(client.timeoutMarker(returnedA), CopyLedger.TIMEOUT_MARKED, "P5 old A timeout marker");
            status(client.timeoutMarker(returnedA), CopyLedger.TIMEOUT_MARKED, "P5 duplicate old A timeout marker");
            Wire.Frame wrongIncarnation = new Wire.Frame(bFence);
            wrongIncarnation.incarnation ^= Long.MIN_VALUE;
            status(client.timeoutMarker(wrongIncarnation), CopyLedger.SCOPE,
                "P5 wrong incarnation cannot mark current B");
            Wire.Frame wrongTicket = new Wire.Frame(bFence);
            wrongTicket.ticket = returnedA.ticket;
            status(client.wipe(wrongTicket), CopyLedger.TICKET, "P5 A ticket cannot wipe B");
            status(client.wipe(returnedA), CopyLedger.WIPED, "P5 duplicate A wipe stays addressed to A");
            Wire.Frame bState = client.snapshot(b);
            has(bState, CopyLedger.FLAG_HELD | CopyLedger.FLAG_REVOKED,
                "P5 old and duplicate A evidence must preserve B debt");
            lacks(bState, CopyLedger.FLAG_TIMEOUT | CopyLedger.FLAG_RECEIVER_RELEASED,
                "P5 old A evidence cannot mark or release B");
            check(bState.ticket == bFence.ticket && CopyLedger.markerCount(bState.arg) == 1,
                "P5 current B ledger must retain its original marker and ticket");
            status(client.wipe(bFence), CopyLedger.WIPED, "P5 exact B ticket releases only B receiver");
        }
    }

    public void p6UnknownKeepsClaimUntilCallReturns() throws Exception {
        try (Binding binding = bind()) {
            RemoteCopyClient client = binding.client;
            CopySessionGuard.Recovery recovery = new CopySessionGuard.Recovery();
            CopySessionGuard oldGuard = new CopySessionGuard(client, recovery, binding.executor);
            Wire.Frame a = oldGuard.grant(nextRequest());
            check(a != null, "P6 initial guard grant must be admitted");
            status(a, CopyLedger.GRANTED, "P6 initial actual receiver grant");
            binding.current = a;
            byte[] fixture = pattern(61, 59);
            client.armGate(a, Wire.AFTER_COPY);
            Future<Wire.Frame> oldCall = oldGuard.copy(a, fixture, true);
            binding.calls.add(oldCall);
            awaitGate(client, Wire.AFTER_COPY);
            has(client.probe(a, 59), CopyLedger.FLAG_HELD | CopyLedger.FLAG_MATCH,
                "P6 receiver must actually copy before controlled reply loss");
            CopySessionGuard.Lease extraLease = oldGuard.addLease(a);
            check(extraLease != null, "P6 exact additional harness lease must register before fence");
            Arrays.fill(fixture, (byte) 0x5a);
            byte[] fixtureAfterCallerEdit = fixture.clone();
            boolean waitTimedOut = false;
            try { oldCall.get(100, TimeUnit.MILLISECONDS); }
            catch (TimeoutException expected) { waitTimedOut = true; }
            check(waitTimedOut && !oldCall.isDone() && !oldCall.isCancelled(),
                "P6 timeout must leave the real synchronous Binder call in stack");
            check(!oldCall.cancel(true) && !oldCall.isCancelled(),
                "P6 cancelling an await handle must not cancel the actual RPC claim");
            oldGuard.abandonAwait(a);
            CopySessionGuard.State abandoned = oldGuard.state();
            check(abandoned.phase == CopySessionGuard.Phase.UNKNOWN && abandoned.claims == 1
                    && abandoned.leases == 1 && abandoned.senderHeld,
                "P6 abandoning UI await must retain unknown claim and sender debt");

            RemoteCopyClient replacementClient = new RemoteCopyClient(client.binder);
            CopySessionGuard replacement = new CopySessionGuard(replacementClient, recovery, binding.executor);
            CopySessionGuard.State rebuilt = replacement.state();
            check(rebuilt.phase == CopySessionGuard.Phase.UNKNOWN && rebuilt.claims == 1
                    && rebuilt.leases == 1 && rebuilt.senderHeld,
                "P6 adapter reconstruction must reuse surviving recovery ownership");
            check(replacement.grant(nextRequest()) == null,
                "P6 reconstructed adapter must not admit a generation over unknown work");
            boolean replayRejected = false;
            try { replacement.copy(a, pattern(61, 59), false); }
            catch (IllegalStateException expected) { replayRejected = true; }
            check(replayRejected, "P6 adapter must not replay the unresolved COPY");
            check(CopyLedger.markerCount(replacementClient.snapshot(a).arg) == 1,
                "P6 reconstruction and await timeout must not create another receiver copy");
            Wire.Frame wiped = replacement.clearAndWipe(a);
            status(wiped, CopyLedger.WIPED, "P6 exact remote fence and actual receiver wipe");
            has(wiped, CopyLedger.FLAG_ZEROED | CopyLedger.FLAG_RECEIVER_RELEASED,
                "P6 actual receiver wipe precedes receiver release");
            CopySessionGuard.State cleanedReceiver = replacement.state();
            check(cleanedReceiver.claims == 1 && cleanedReceiver.senderHeld
                    && cleanedReceiver.receiverReleased && cleanedReceiver.leases == 1,
                "P6 remote cleanup cannot erase sender or return blocked call claim");
            check(!replacement.reconcile(a) && replacement.grant(nextRequest()) == null,
                "P6 matching receiver cleanup must still wait for actual call return");

            client.releaseGate();
            boolean controlledReplyLost = false;
            try { result(oldCall); }
            catch (ExecutionException expected) {
                controlledReplyLost = expected.getCause() instanceof RemoteException;
            }
            check(controlledReplyLost && oldCall.isDone() && !oldCall.isCancelled(),
                "P6 controlled missing ACK must surface only after real Binder call returns");
            CopySessionGuard.State returned = replacement.state();
            check(returned.phase == CopySessionGuard.Phase.UNKNOWN && returned.claims == 0
                    && !returned.senderHeld && returned.receiverReleased && returned.leases == 1,
                "P6 only actual call finally may erase sender and retire its claim");
            check(!replacement.reconcile(a),
                "P6 remaining exact harness lease must prevent cleanup confirmation");
            check(replacement.releaseLease(extraLease), "P6 exact harness lease may release once");
            check(!replacement.releaseLease(extraLease), "P6 duplicate lease release must be refused");
            check(replacement.reconcile(a)
                    && replacement.state().phase == CopySessionGuard.Phase.CLEANUP_CONFIRMED,
                "P6 explicit reconciliation requires every matching debt and actual claim exit");
            check(replacement.clearAndWipe(a) == null && replacement.state().claims == 0,
                "P6 terminal A cannot reopen an old clear claim before B grant");
            check(Arrays.equals(fixture, fixtureAfterCallerEdit),
                "P6 guard may erase only its private sender copy, never the caller fixture");
            Wire.Frame b = replacement.grant(nextRequest());
            check(b != null, "P6 fresh generation is admitted only after explicit reconciliation");
            status(b, CopyLedger.GRANTED, "P6 next actual receiver generation");
            binding.current = b;
            status(replacement.clearAndWipe(b), CopyLedger.WIPED, "P6 final new-generation cleanup");
            check(replacement.reconcile(b), "P6 final new-generation explicit reconciliation");
        }
    }

    private Binding bind() throws Exception { return new Binding(instrumentation.getTargetContext()); }
    private static long nextRequest() { return REQUESTS.incrementAndGet(); }

    private static byte[] pattern(int length, int seed) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = CopyLedger.patternByte(seed, i);
        return bytes;
    }

    private static Wire.Frame copyFrame(Wire.Frame scope, byte[] bytes) {
        Wire.Frame frame = new Wire.Frame(scope);
        frame.operation = Wire.COPY; frame.status = 0; frame.arg = 0; frame.payload = bytes;
        return frame;
    }

    private static void rejectedParcel(RemoteCopyClient client, Wire.Frame scope,
        Consumer<Parcel> encode, String label) throws Exception {
        status(client.rawTransaction(encode), CopyLedger.INVALID, label);
        noReceiverCopy(client, scope, label);
    }

    private static void noReceiverCopy(RemoteCopyClient client, Wire.Frame scope, String label)
        throws Exception {
        Wire.Frame state = client.snapshot(scope);
        status(state, CopyLedger.SNAPSHOT, label);
        lacks(state, CopyLedger.FLAG_HELD, label);
        check(CopyLedger.markerCount(state.arg) == 0, label);
    }

    private static void awaitGate(RemoteCopyClient client, int phase) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(WAIT_SECONDS);
        while (SystemClock.elapsedRealtime() < deadline) {
            if (client.gateState().arg == phase) return;
            Thread.sleep(10);
        }
        throw new AssertionError("B1 expected remote call did not reach its controlled gate");
    }

    private static Wire.Frame result(Future<Wire.Frame> future) throws Exception {
        return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static void status(Wire.Frame frame, int expected, String label) {
        check(frame.status == expected, label); noMaterial(frame);
    }
    private static void has(Wire.Frame frame, long flags, String label) {
        check((frame.arg & flags) == flags, label); noMaterial(frame);
    }
    private static void lacks(Wire.Frame frame, long flags, String label) {
        check((frame.arg & flags) == 0L, label); noMaterial(frame);
    }
    private static void sameScope(Wire.Frame expected, Wire.Frame actual, String label) {
        check(expected.incarnation == actual.incarnation && expected.revision == actual.revision
            && expected.request == actual.request, label);
    }
    private static void noMaterial(Wire.Frame frame) {
        check(frame.payload != null && frame.payload.length == 0,
            "B1 replies must not expose receiver material");
    }
    private static void check(boolean condition, String safeLabel) {
        if (!condition) throw new AssertionError(safeLabel);
    }

    private static final class Binding implements AutoCloseable {
        final Context context;
        final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "b1-binder-call");
            thread.setDaemon(true);
            return thread;
        });
        final List<Future<Wire.Frame>> calls = new ArrayList<>();
        final CountDownLatch connected = new CountDownLatch(1);
        final ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName component, IBinder binder) {
                endpoint = binder; connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName component) {}
            @Override public void onNullBinding(ComponentName component) { connected.countDown(); }
            @Override public void onBindingDied(ComponentName component) { connected.countDown(); }
        };
        volatile IBinder endpoint;
        RemoteCopyClient client;
        Wire.Frame current;
        boolean bound;

        Binding(Context context) throws Exception {
            this.context = context;
            try {
                Intent intent = new Intent(context, CopyBoundaryQaService.class);
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
                check(bound, "B1 explicit bind was not accepted");
                check(connected.await(WAIT_SECONDS, TimeUnit.SECONDS) && endpoint != null,
                    "B1 remote service did not provide a bound endpoint");
                client = new RemoteCopyClient(endpoint);
            } catch (Exception | AssertionError failure) {
                if (bound) context.unbindService(connection);
                executor.shutdown();
                throw failure;
            }
        }

        Wire.Frame grant() throws Exception {
            Wire.Frame accepted = client.grant(nextRequest());
            status(accepted, CopyLedger.GRANTED, "B1 next synthetic request must be admitted");
            current = accepted;
            return accepted;
        }

        Future<Wire.Frame> async(java.util.concurrent.Callable<Wire.Frame> call) {
            Future<Wire.Frame> future = executor.submit(call);
            calls.add(future);
            return future;
        }

        /** Release test gates and let actual calls return before cleanup or unbind.
         * Future.cancel is deliberately absent: interrupting an awaiter is not proof
         * that a synchronous Binder transaction has left its remote stack. */
        @Override public void close() throws Exception {
            try {
                if (client != null) {
                    client.releaseGate();
                    for (Future<Wire.Frame> call : calls) {
                        try { call.get(WAIT_SECONDS, TimeUnit.SECONDS); }
                        catch (ExecutionException expectedFailure) {
                            // The corresponding test observes its transport result.
                            // A completed exceptional call has nevertheless returned.
                        }
                    }
                    if (current != null) {
                        Wire.Frame fenced = client.clear(current);
                        status(fenced, CopyLedger.FENCED, "B1 finally must fence its addressed scope");
                        status(client.wipe(fenced), CopyLedger.WIPED,
                            "B1 finally must release its addressed receiver allocation");
                    }
                }
            } finally {
                executor.shutdown();
                if (bound) { context.unbindService(connection); bound = false; }
            }
        }
    }
}
