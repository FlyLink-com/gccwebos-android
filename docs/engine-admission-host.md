# E1: standalone engine ownership contract

`EngineAdmissionHost` is an independent JVM contract. It is not wired to
`AuthorizedConfig`, `LocalSubscriptionManager`, a coordinator, UI, VPN service,
JNI, or `LifecycleTunnel`. It does not parse configuration, open a tunnel, or
report connected. Its highest running phase is `ENGINE_RUNNING_UNVERIFIED`.

Input is a `ByteArray` and an explicitly supplied `maxConfigBytes` in
`1..16 MiB`. There is no default size and no claim that this ceiling, the test's
64-byte limit, or an earlier experimental 64 KiB JSON limit is a production
configuration policy. The constructor rejects invalid limits. `admit` checks
empty and oversized input synchronously before copying accepted material into
host-owned storage. Rejected and accepted caller arrays are never erased by the
host. The caller must not mutate the input concurrently during `admit`; copying
establishes independence after return, not an atomic snapshot of concurrent writes.

The admission boundary performs a length-bounded copy and fixed bookkeeping
under the host monitor. It invokes no worker or observer, does not enumerate
listeners, and only updates a single latest notification slot. Observer capacity
is 32; attachment above capacity returns null. This bounds host bookkeeping,
not JVM scheduling, allocation pauses, monitor contention, or a hard wall-clock
deadline. `dispatchObservers` must be called outside any outer admission or
activation fence. Other lifecycle methods may dispatch observers. Worker and
observer calls run outside the host monitor and may block; E1 has no executor,
timer, hard timeout, or JNI termination mechanism.

Accepted admission, worker, resource, cleanup, budget and UI-await handles are
opaque capabilities. They belong to a host instance and are checked by object
identity. Snapshots contain only phase/reason; host and handle string output
contains no configuration, account, digest, or numeric generation. One occupied
generation excludes another admission, and an admission handle starts once.

## Worker protocol and cleanup

1. `start` reserves its external dispatch before any observer runs. The worker
   receives the private material and a worker handle. It must register a resource
   lease **before** attempting each external allocation, including partial starts.
2. `stop` creates one cleanup ticket, closes registration, erases host material
   and claims `requestStop` before dispatch. No later `started` callback can
   restore a running phase. A repeated ordinary stop returns the same ticket.
3. Each registered lease needs an individual `released(worker, lease, ticket)`
   acknowledgement. `workerTerminated(worker, ticket)` records logical worker
   termination. There is no external aggregate `allReleased` acknowledgement.
4. Cleanup requires the current ticket, worker termination, every registered
   lease released, and all claimed start/stop/force calls drained. Erased bytes,
   a successful stop enqueue, or an empty lease ledger alone cannot establish
   cleanup. Until confirmation the generation remains occupied.
5. Early acknowledgements are recorded but cannot reopen admission inside an
   external call. If a STARTING observer stops before worker dispatch, the sole
   stop acknowledgement can complete automatically when the reserved dispatch
   drains. `requestStop` must therefore also acknowledge a worker handle whose
   `start` was never invoked. If an actually invoked start returns false/throws
   after an early stop, its old terminal acknowledgement is invalidated and the
   host automatically requests one same-ticket, idempotent stop reconfirmation
   after that failed boundary. It does not rely on a test issuing a second ACK.
6. Throwing stop/force boundaries set `CLEANUP_FAILED` and require a later current
   terminal acknowledgement. A missing acknowledgement never releases ownership.

Explicit budget tickets are logical fake/external budget notifications.
Startup expiry records `CLEANUP_FAILED` and requests stop and force; cleanup
expiry records `CLEANUP_FAILED` and requests force. Force is requested at most once per generation. Repeating a
budget notification does not retry or release ownership. Valid current logical
acknowledgements can confirm cleanup after timeout; absent proof remains occupied.
Wrong-host, stale, duplicate, unregistered or incomplete acknowledgements cannot
release a current generation. `ownerClose` permanently closes admission, including
after valid cleanup. Detaching a UI awaiter only removes that observer and never
stops the owner. Advisory notifications coalesce obsolete publications; a reentrant
stop wins over a late started callback, and the synchronous snapshot is authoritative.

## Evidence and remaining physical boundary

`EngineAdmissionHostTest` has 20 focused synthetic methods covering admission
limits and queued observers, private copies, single ownership/start, registered
partial resources, incomplete/duplicate/cross-host/stale acknowledgements,
owner/UI separation, force-once budgets, secret-free outputs, lock-free callbacks,
stop before dispatch, and reentrant false/throw starts with automatic reconfirmation.
These are source fixtures until the owner records the actual test/build result.
This document claims no device, network, native-engine or production cleanup run.

The host trusts an in-process worker's protocol acknowledgements. A dishonest
worker can omit an allocation from the ledger, retain another secret copy, or
claim termination while a queued thread, native operation, fd or TUN still exists.
E1 cannot certify those claims. E2 must establish real worker/native termination,
OS-resource release, hard deadline/kill behavior, and physical evidence before a
real tunnel adapter can use this contract. Unsolicited engine-death, revoke/clear
integration and existing authorization-path admission are also outside this
standalone slice. No existing configuration path gains bounded admission merely
because this separate API exists.
