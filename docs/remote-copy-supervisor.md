# E2-A: remote-copy supervisor, synthetic JVM contract

This independent slice inherits main `b9688ade88fef41f5466e5f3568973ec2a9a4d86`,
tree `7879cc5961105185832f545974ff7bd468e6032f` (the frozen E1 source tree).
It adds one source file, one focused JUnit file and this document. It changes no
existing coordinator, authorization, UI, native code, service, permission or CI.
Its highest positive phase is **COPIED_NOT_ACTIVE**. It has no START, COMMIT,
ACTIVATE, connected output or `LifecycleTunnel.accept` implementation.

## Copy boundary

All limits are explicit: actual control-header bytes, actual payload bytes,
factory binding/request capacity, simultaneous external claims, resource leases,
observers and sequence ceiling. Constructor ceilings only bound this synthetic
model; there are no production defaults or claims about Binder capacity. This is
a typed frame model, not a Parcel/codec implementation. Version and opcode must
both be the supported copy value; declared payload length must equal actual
length. Empty/oversized/mismatched frames are rejected before copying or dispatch.
The caller must not mutate input during `reserveCopy`; post-return independence
does not prove an atomic snapshot of concurrent writes.

`reserveCopy` creates private sender copies and atomically records unresolved
ownership in the independent `RecoveryQuarantine`. It invokes no port or observer,
does not enumerate listeners, and only marks one pending notification slot.
RESERVED means local staging only. `dispatchCopy` claims its call before invoking
the port outside the recovery monitor. Its return is merely a DispatchAttempt.
The concrete **FakeEndpointFactory** receiver independently validates bounds and
fully copies both header and payload before minting a bound CopyReceipt. Only
that same endpoint/request receipt can advance to COPIED_NOT_ACTIVE; a boolean,
length assertion, queued task or normal method return cannot do so.

CopyPort is a synchronous, read-only borrow. It may not retain, mutate or queue the
sender array. Any retained work needs its own independent copy and pre-registered
lease. Stop/loss during an active borrow marks erasure pending; the old supervisor
retains its frame until the copy call returns, wipes it, then retires its claim.
A sender-copy lease cannot be released through remote evidence. If the old object
disappears without its buffer-erasure path running, that lease remains unresolved.

Duplicate same-key identical frames do not recopy. Same-key changed fields/bytes
are a conflict and leave UNKNOWN occupied. A higher/different key cannot overwrite
a live receiver record or mint its release. Endpoint sequence is retained across
supervisor-object replacement, never wraps, and exhaustion closes that binding.
Definitive pre-copy receiver rejection also tombstones the exact key, so a later
delivery cannot create an untracked receiver copy after that refusal.
Public handles/frame/replies have redacted or opaque string forms; snapshots and
errors contain only safe phase/reason, not payload, identity, digest or sequence.

## Unknown, cleanup and fake evidence

An exception, sender cancellation, normal dispatch without a receipt, lost reply,
or conflict remains UNKNOWN. The model never automatically replays payload or
opens another generation. A late valid first receipt may establish the exact
current copy; stopped, conflicting, duplicate and stale receipts cannot revive it.
Transaction-too-large/reply-loss tests simulate an already copied receiver and
an unavailable reply; they are not Android IPC tests.

Stop closes registration and creates an exact request-bound ticket. The ledger
includes the sender buffer, possible receiver copy and each additional synthetic
resource. Receiver discard actually wipes its own arrays before minting a copy
release proof. Generic fake resource release cannot release sender or receiver
copy leases. Quiescence, all individual leases, the current ticket and every
claimed copy/stop/force call must be complete before logical cleanup. Reentrant
early ACKs are recorded but cannot admit a new generation while a call is in stack.
Throwing boundaries invalidate early quiescence and keep UNKNOWN/CLEANUP_FAILED.

A timeout notification requests force at most once and leaves CLEANUP_FAILED;
returning from force is not an exit witness. A bound fake endpoint-exit witness
does not release any lease: local/transferred duplicates may still exist. Wrong
endpoint/request/ticket, duplicate or missing proofs cannot clear current work.
These proofs are minted by trusted synthetic helpers, not by OS observation.
Auxiliary release and quiescence helpers assume a truthful fake evidence source;
they cannot certify an actual worker, fd, PFD, native duplicate or TUN.

## Object-loss quarantine

RecoveryQuarantine stores opaque ownership/ledger metadata only. It does not hold
payload, an inner supervisor, callbacks, URLs, accounts or a secret-owning epoch.
The fake authority seal binds once to one recovery source, including newly bound
endpoints: replacing it with a fresh empty store is rejected before changing the
old owner/ledger. A new Factory plus empty source represents a separate trusted
synthetic cold-start world, never actual recovery after a process died.

A replacement supervisor fences the old unresolved request and stays QUARANTINED.
Receiver proofs alone do not erase a surviving old sender frame. The old object's
exact-key erasure or in-flight-copy finally must drain that sender debt. After all
old evidence and claims finish, the new owner still needs explicit
`reconcileRecovery`. Rebuilding an object or changing endpoint incarnation is not
cleanup. Stale ownerLost/ownerClose may erase only their own old frame; they cannot
close the current owner's reused binding or release a new request. A current
ownerClose permanently closes that owner incarnation. UI awaiter detach removes
an observer and never stops the owner.

Every observer pump dispatches at most the current bounded batch. Reentrant
notifications remain in the single pending slot for a later explicit pump;
obsolete publication identities are dropped. Callbacks and port calls run outside
the monitor. An arbitrary callback/port may still block: no hard wall-clock bound,
thread executor, native interruption or actual timer is claimed.

## Validation boundary

`RemoteCopySupervisorTest` contains 14 methods mapped directly to G1–G14 in
`output/engine-e2-preparation/E2-REVIEW-GATES.md`: actual frame bounds, private
copies, bound provenance, duplicates/conflict, no activation/sequence exhaustion,
unknown replies, stop ordering, claim drain, full ledger, endpoint death, owner
recovery, force once, UI/exceptions/bounded pump, and safe output. They use only
synthetic bytes, memory fake ports and fake evidence. Until the owner records the
actual run, these are source fixtures, not passed tests. No build, test, Git,
device, network, Binder, OS kill, signed APK or CI action was performed by the
implementation agent.

Real caller authentication, marshalling allocation limits, Binder multithreading,
process-death durability, complete descriptor ownership, physical kill/deadline,
VPN consent/revoke and traffic evidence remain E2-B and later. The independent
memory source survives JVM object recreation, not whole-process loss. It cannot
recover lost OS facts or persistent credentials. The COPY-ACK-to-ACTIVATE clear
race is intentionally unresolved: FIFO/sequence cannot prevent old COMMIT from
executing after local clear returned. This slice therefore provides no activation
command and never turns startIntent, Binder return or copied bytes into accepted
native traffic or Connected.
