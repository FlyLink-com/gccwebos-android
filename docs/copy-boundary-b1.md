# E2-B1 copy-only Binder candidate

Baseline: `a615f77c7895027c338443b9ec772919ef0562d8`, tree
`973699c720151a5a573154e9c187d5a7c3d6b5d4`. The baseline captures the original
stop ticket before callbacks; it is not the older 34984f snapshot. Git blob IDs
of its three E2-A files match the verified Library archive. Checkout CRLF bytes
differ from archive LF bytes; this is line-ending conversion, not a source change.

This opt-in `:copyBoundaryLab` application does not depend on `:app`. Ordinary
application/UI sources, permission declarations and production entry points are
unchanged. Release variants of the QA module are disabled. Its package IDs are
`com.gccwebos.android.ipcqa` and `com.gccwebos.android.ipcqa.test`, version 1 /
`0.1-e2b-lab`. There is one non-exported ordinary bound service in `:ipcqa`.
The target and dependency-free native instrumentation runner request no permissions.
Actual APK manifests and signatures must pass the collector before installation.
The CI job builds and inspects only; it has no emulator, ADB install or test run.
Its ordinary debug signing identity is a candidate identity, not a release key.

## Wire and authority

The custom synchronous Binder transaction uses 72 fixed header bytes and unsigned
octets encoded as ints. Payload budget is 16 KiB, encoded budget 65,608 bytes.
Receiver validates schema/opcode, fixed header, descriptor absence, declared length
and exact remaining encoded size before allocating its payload array. Invalid
octets erase that scratch. There is no Bundle, Serializable or automatic AIDL array
decoder. This bounds application allocation; it does not bound the kernel's prior
Binder parcel allocation or prove its buffers erased.

Each transaction captures real Binder caller UID immediately, before decode or
gate waits. It must equal the app UID. The private endpoint capability, service
incarnation and exact 64-bit scope must also match. A newly created endpoint cannot
claim a live scope belonging to an earlier endpoint. Same-intent bindings may share
an Android-cached Binder; wrapping that Binder again does not create a new identity.
PID is checked only as a diagnostic. The lab is same-UID, not an isolated UID or a
cross-application authentication test.

COPY, marker, grant and clear share the receiver ledger's short memory lock.
Test gates and Binder waits are outside it. ACK follows a private clone and lease
registration; decoded scratch is erased before the reply gate. Only one active
scope and two wiped tombstones are retained, with at most 48 KiB of owned arrays.
The endpoint admits at most eight concurrent transactions and one controlled gate;
the gate has a 15-second fixture escape budget, not a hard Binder deadline.

FENCED revokes the old synthetic authority and blocks later old COPY commits.
WIPED confirms only the receiver's exact registered array and lease. Neither is
full cleanup. The client Recovery retains a separate private sender, exact opaque
leases and actual call claims. Await timeout or adapter reconstruction does not
return a claim. Actual RPC task exit erases its sender and returns the claim.
Explicit reconcile additionally requires the exact fence and receiver release.
UNKNOWN blocks new generations and automatic replay.

The low-level P5 fixture deliberately bypasses the normal guard after receiver A
wipe, while A's clear reply is held, to exercise return provenance across B. The
guard's ordinary path rejects a new generation until claims drain. A's immutable
clear receipt is captured at A's linearization point and cannot be replaced by B.
TIMEOUT_MARKER only marks addressed metadata; it cannot kill, stop an engine or
release any resource. Wrong scope/ticket/capability and changed-content duplicates
are rejected. Old retained evidence can affect its own tombstone, never current B.

## Execution candidate (not executed)

Runner: `com.gccwebos.android.ipcqa.test/com.gccwebos.android.ipcqa.CopyBoundaryRunner`.
Select `-e class com.gccwebos.android.ipcqa.CopyBoundaryIpcTest#METHOD` with exactly:

| Method | Evidence sought |
| --- | --- |
| p1RemoteIdentity | Actual BinderProxy, distinct harness/service PID, system UID |
| p2OwnedCopyAndWipe | Independent receiver array, scratch gone, real private zeroing |
| p3BoundedActualParcels | Malformed real Parcel, wrong cap, exact payload limit |
| p4ClearBeforeCopyAndLateAck | Both COPY/clear orders and stale ACK after fence |
| p5OriginalStopTicketAcrossGeneration | Held A clear return, B stop, original A ticket |
| p6UnknownKeepsClaimUntilCallReturns | Real blocked RPC, await timeout, shared Recovery, exact lease |

Run P1 and P2 separately first, then P3-P6 only after their results are reviewed.
All payloads are public synthetic patterns; no subscription URL, token, account,
payment, production state, FD, TUN, JNI, socket or native engine is used. Replies
and runner logs contain fixed safe labels and metadata only. Cancelled UI await is
represented by detaching the wait; no actual UI source is modified. Runner failures
are status -2 and must be parsed; shell exit status alone is insufficient.

Actual installation/execution of these two new packages requires confirmation of
the built identities, existing same-package state and target device. Do not update,
uninstall or clear an existing package automatically. No such operation occurred
during preparation. Compiling the test APK is not passing its six device cases.

B2 remains a separate gate: surviving harness guard, :ownerqa and :workerqa, with
three precise death/recovery cases. No exit endpoint is implemented here. Obtain a
specific process-exit scope before implementing it. Binder death is proof of the
hosting endpoint going away, not explicit sender zeroing or another process's wipe.
Full guard-process death, persistent recovery and endpoint/process restart are
not proved by B1 adapter reconstruction. No Connected state is emitted: the maximum
copy phase is COPIED_NOT_ACTIVE. Production connection and colored power-switch UI
must continue to receive their existing disabled behavior until actual engine
admission, activation and resource/OS evidence are separately implemented.

Protocol details were checked against official [Parcel API](https://developer.android.com/reference/android/os/Parcel),
[Binder caller identity](https://developer.android.com/reference/android/os/Binder#getCallingUid()),
and [service process/exported semantics](https://developer.android.com/guide/topics/manifest/service-element).
