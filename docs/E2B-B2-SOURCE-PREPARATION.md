# E2-B / B2: Binder host death preparation

This records the initial source-only stage at `8042ab7`. The subsequently authorized
build-only stage is documented in `E2B-B2-BUILD-ONLY.md`; runtime remains separate.

Baseline: main merge `e4b3cf3a06e0e6a8add01ac895b3ed524847a845`, tree
`1fc83fa6042fd055455b21445c6ec1cbbfbd1165` (B1 PR16 already merged).
Independent branch: `codex/lucky-b2-preparation`; independent directory `lucky-b2`.

This change prepares real Android Binder source and a future instrumentation fixture.
Host JVM tests validate only metadata policy. Source manifests and Java compilation
are not APK merged-manifest, signature, remote-IPC or OS-death evidence. This round
does not build, install or run APKs, trigger process faults, or change network/VPN.

## Scope and reuse

`ownerDeathLab` is included only with `-PownerDeathLab=true`. It has no dependency
on `:app`, native engine, AndroidX, credentials, files, socket, TUN or network code.
Ordinary application manifests, permissions, UI and disabled production VPN are
unchanged. The highest copy result is `COPIED_NOT_ACTIVE`, never connected.

The build copies precisely the unchanged B1 `Wire`, `CopyLedger` and
`RemoteCopyClient` Java sources into generated sources. It does not copy B1 service,
guard or tests. Source verification locks their LF-normalized hashes.

Candidate identity (source declarations, not an inspected APK):

| Item | Declared value |
| --- | --- |
| Application / namespace | `com.gccwebos.android.ipcqa.b2` |
| Instrumentation package | `com.gccwebos.android.ipcqa.b2.test` |
| Runner | `com.gccwebos.android.ipcqa.b2.OwnerDeathRunner` |
| Services | private `.OwnerDeathQaService` in `:ownerqa`; private `.WorkerDeathQaService` in `:workerqa` |
| SDK / version | min 26, target/compile 36; versionCode 1, `0.1-e2b-b2-lab` |
| Source permissions | none; no launcher, exported endpoint, FGS or VpnService |
| Signing, APK hashes, merged manifests | **not built / not verified** |

## Recovery contract

The surviving harness binds one process-singleton `Recovery`. New adapters and new
owner/worker services reuse this authority; constructor HELLO can inspect a new
quarantined endpoint, but cannot attach/open/reserve across old debt. This is trusted
same-UID QA admission, not a production security seal against arbitrary fixture code.

Admission captures exact strong Binder objects, fresh endpoint capabilities, full
64-bit incarnation/revision/request values, and an opaque monotonically increasing
generation. Death registration and service publication check the same captured
endpoint. API33 uses no-argument `binderDied()` with a captured strong handle.
Failed registration, disconnection, service destruction, PID reports, self-exit
return, timeouts and Future cancellation never manufacture a death observation.

An actual endpoint callback revokes the currently admitted scope using that endpoint
(including reconstructed adapters). Replayed old scope events and ACKs can update
only their original scope, never a new generation. A different old endpoint cannot
revoke the new endpoint. Capability revocation leaves exact-scope cleanup available.
Worker owner-death fencing and worker grant/copy commit use the same boundary lock.
Binder calls and bounded gates run outside authority/state locks.

Three debts remain distinct:

1. Harness SEND/control calls finish only in their actual task `finally`. SEND has
   one admitted task, no second queued material task and no cancellation release.
2. Owner tracks its real outgoing worker calls and retains its private sender until
   actual zero scan/wipe or the explicitly limited host-loss disposition.
3. Worker registers real COPY before decoding allocation and decrements only after
   scratch erasure in its actual `finally`, including gate waits. Receiver wipe and
   COPY drain are separate facts; worker drain never substitutes for harness exit.

The local fence prevents future SEND; worker fence prevents late copy commit.
Future P7/P8 gates establish that the sole exact material call already entered the
specified worker/owner dispatch. A momentary counter zero does not prove arbitrary
queued Binder work or unenumerated producers are drained.

Cleanup requires the captured remote `FENCED` ticket, matching `WIPED` reply with
actual receiver zero scan/release flags, and matching-ticket real COPY count zero;
wrong ticket/scope/capability is refused. Alive owner requires its own actual scan,
no owner calls, and explicit retire. Every surviving opaque lease is separately
returned. Death never decrements a local claim or lease. New admission follows only
explicit reconcile after all required facts; incomplete reservation/open remains
fail-closed UNKNOWN, with no reset-as-refresh shortcut.

`HOST_GONE` is distinct from `WIPED`. It may retire only the enumerated application
Java allocations exclusively hosted in the exact dead fixture process, after a
local fence and all surviving harness calls have actually returned. It does not
assert array zeroization, physical RAM erasure or erasure of framework Parcel,
native Binder, kernel, driver or external copies. Unknown/shared/native/persistent
resource graphs cannot reconcile under this contract.

Application allocation inventory: owner synthetic sender and temporary
`RemoteCopyClient` clone; worker bounded decode scratch and independent `CopyLedger`
clone, plus at most two actually wiped retained arrays. Harness passes only bounded
length/public seed and receives metadata, never configuration bytes or a material
digest. Each endpoint accepts at most eight concurrent calls; policy has one active
scope, four claims and four leases. Bounds cover only this audited QA source graph.

## Future fault matrix — not executed

| Case | Exact fault point | Required observations |
| --- | --- | --- |
| P7 `p7OwnerDeathKeepsWorkerAndQuarantine` | One owner self-exit after the sole COPY entered worker AFTER_COPY gate | Actual exact owner death; independent worker still alive with real held clone and in-flight COPY; old claims/leases/debt block new quarantined owner. Wipe alone does not establish drain. |
| P8 `p8ExactEvidenceWaitsForCallAndDisposition` | One owner self-exit while SEND is blocked after worker ACK | Matching worker fence/wipe/scan/drain does not clear owner sender, actual harness call or extra lease; actual call finally, explicit owner HOST_GONE, exact lease return and explicit reconcile are each required. |
| P9 `p9WorkerDeathAndStaleEvidenceStayScoped` | One worker self-exit with real held receiver and captured old clear receipt | Actual exact worker death; alive owner remains held until its own scanned wipe/retire; worker HOST_GONE plus exact lease return. Fresh worker with the same live owner and new generation rejects old receipt; replay of already observed old death is scoped local delivery, not new OS proof. |

The runner requires one exact `class=OwnerDeathIpcTest#method` selection. Missing,
unknown or whole-class selections execute zero cases. Each approved case runs once;
a failure stops the sequence. Service rebind/reconstruction is never labeled OS death.

## Validation and next gate

Local command: `python scripts/verify-owner-death-source.py`. It checks source
isolation and unchanged B1 reuse, compiles the pure ledger with JDK17 and runs its
policy smoke tests. With explicit existing platform-36 SDK, `--android-sdk PATH
--require-android` additionally compiles all Android main/test Java. The dedicated
push workflow for this exact branch performs only those source/JVM/API checks with
existing hosted tools; it has no SDK install, Gradle/APK task, emulator, ADB or fault.

Do not open a PR on this branch or dispatch the ordinary `android.yml`: its existing
non-B1 branch path includes Android execution. A source-only PR gate must be made
concrete and reviewed before any such operation.

The next build gate is limited to debug target/test APK compilation and lint, then
actual merged-manifest inspection, absence-of-permission/component checks, package
and SDK badging, both signatures/certificate equality and frozen SHA256/size. An
APK build does not authorize installation or faults.

Only after the parent separately approves those exact identities may a runtime
proposal select an existing controlled official Android13/API33 emulator, install
only this frozen package pair, and run the exact P7/P8/P9 selectors once each. The
requested fault budget would be exactly two self-exits of that QA owner and one of
that QA worker through scope/capability/one-use nonce control; no shell PID kill,
foreign process, physical device, VPN, network setting or real user material.
Framework instrumentation shutdown must be logged separately from injected faults.
Evidence must retain source/harness and APK identities, actual UID/PID/remote Binder
checks, death callbacks, gate/call counters, per-scope fence/wipe/scan/drain facts,
single-case start/pass/final codes, stop-on-failure and cleanup/unbind/AVD outcome.
The immutable runtime harness and its teardown commands are still a future review
deliverable. No runtime approval is inferred from this source preparation.
