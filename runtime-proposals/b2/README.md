# Approved B2 P7–P9 single cloud run — NOT_DISPATCHED

The user approved one test with the reply “同意进行测试”. Authorization evidence:
source_thread `01a0f0d1-54e3-77e0-98ae-eb87ae68d016`; Slack thread
`1790747278.924809`, assistant message `1790937353.025969`, approving user message
`1790937372.853329`. No user name or Slack user ID is stored in this public bundle.

Current activation state is `runtime_authorized=true`, with that nonempty
`approval_reference`, and `APPROVED_SINGLE_RUN_NOT_DISPATCHED`. Preparation has
performed no Android/SDK/ADB/emulator execution or network operation. The approved
scope is one fresh hosted official cached API33 x86_64/KVM AVD, exact frozen
f1e6722 APK pair, P7/P8/P9 once each, owner2/worker1 fault budget, failure STOP,
no default suite, rerun or retry, and mandatory owned teardown.

The original `output/b2-runtime-proposal-f1e6722` is immutable historical evidence.
At that earlier review stage its freeze had runtime_authorized=false and a null
approval reference; those statements describe the original proposal only.
This activation changes only authorization/status fields in a copied freeze and
this README; shell, helper and `.disabled` workflow retain exact original bytes.
All source/tree/run/artifact/archive/APK/certificate/manifest/source-map identities
and the reviewed fault and cleanup boundaries remain unchanged.

The original archive-bound collector reports `VERIFIED_BUILD_ONLY`; both actual
DEX gates report `VERIFIED`. Independent binary identity/isolation and strict DEX
reviews are PASS and their exact local receipts and hashes are in `freeze.json`.
These gates admit no exception. The fixture's source lineage remains `8042ab7`;
the exact build source and APK identities below belong to this new candidate.

| Frozen item | Exact identity |
| --- | --- |
| Repository | `gccwebos-com/gccwebos-android` |
| Source commit | `f1e6722920871280645fcbbeb5e03b3d4577d7b2` |
| Source tree | `c83b36ece434a1acb170a50a84aa99bf66f8e010` |
| Build run / artifact ID | `36992973378` / `11220247839` |
| Artifact name | `owner-death-build-f1e6722920871280645fcbbeb5e03b3d4577d7b2` |
| CI artifact ZIP | 495192 bytes; SHA256 `013c87c73a97c2ddc6024b39220b940c7967ed6728363cb62dc45bcc6de9a2e8` |
| Original candidate report | `.build-output/owner-death-build/candidate.json`; SHA256 `84a46725172b69189a437f8e860026df801e99c4f548fdd8f8988ea1a6b618a0` |
| Target APK | 39747 bytes; SHA256 `df3068cdb6366290ae3e1202169a474f19e2efa052409035788e2a9b5c46ba8a` |
| Test APK | 22036 bytes; SHA256 `ce1b8274302b0f372476a3977b665be7d95a455c43692593a58fabdff898081a` |
| Common single signer certificate | SHA256 `c18dabe07cc64cedf430f1cd3f3b5617eedfc6d9b5b1ab29d6f76806eb1b14ad` |
| Target compiled manifest | SHA256 `569d4a26a1686aee775ce5112b04862461e9eddb77c89b1c562591f0d64c7a04` |
| Test compiled manifest | SHA256 `80924b90d221d077ac0fb77722800272b1c62675b94537b1f174c9f9d6e2c470` |

`freeze.json` includes the original candidate's complete `source_file_sha256`
map, including all nine B2 main/test Java files. Before materialization the helper
binds the exact saved archive, original candidate, source/tree/map, two APK
members, common certificate and candidate-recorded compiled-manifest identities.
The local generator independently checks both APKs' raw compiled-manifest member
hashes; future helper verify repeats this raw member check after materialization.
The helper requires zero build
installations/process exits and Android execution `NOT_RUN`. Only the two frozen
APK members are materialized after both hashes pass. No `latest`, branch-head
artifact lookup or runtime APK build is allowed.

| Approved scope | Exact boundary |
| --- | --- |
| CI principal | Job token with current repository `contents:read` and `actions:read` |
| Host | Fresh GitHub-hosted `ubuntu-24.04`, Linux only, maximum 30 minutes |
| Target | `com.gccwebos.android.ipcqa.b2`, version code 1 / name `0.1-e2b-b2-lab`, min26 / target36 |
| Test / runner | `com.gccwebos.android.ipcqa.b2.test` / `com.gccwebos.android.ipcqa.b2.OwnerDeathRunner` |
| Target components | Private `.OwnerDeathQaService` in `:ownerqa` and `.WorkerDeathQaService` in `:workerqa`; exported=false, stopWithTask=false, no isolated process |
| Test components | Instrumentation only; empty application; no activity/service/receiver/provider |
| Both APKs | Same single signer; zero requested/declared permissions; no shared UID/native libraries; backup and cleartext disabled |
| Material | Public synthetic owner-generated pattern; metadata-only harness; no user configuration, token, subscription, registration/payment or production operation |
| Fault budget | P7 owner self-exit once; P8 owner self-exit once; P9 worker self-exit once; total owner2/worker1 |

The fixture's exact Binder/session one-shot fault token is the only fault
authority. PID observations do not authorize faults. The host harness has no
Android shell kill, force-stop, PID signal, package uninstall, VPN or guest network
command. Its host network scope is read-only GitHub metadata and exact saved
artifact download using the Actions token. Normal instrumentation target lifecycle remains separate from those
three explicitly armed fixture faults.

## Cached environment and ownership preflight

Use only an existing official cached
`system-images;android-33;default;x86_64` image, emulator/platform-tools,
`cmdline-tools/latest/avdmanager`, SDK build-tools 36.0.0, Java17, Python3,
`gh`, `timeout`, `ss` and readable/writable `/dev/kvm`. Validate image
`source.properties` and `package.xml` API33/default/x86_64 identity and retain
their hashes, tool executable hashes and live versions. Cached metadata records
provenance; it is not an independent supply-chain signature.

Missing cached tool/image/KVM access means STOP before APK installation.
No sdkmanager/apt, executable downloads, license acceptance, sudo chmod or
permission repair occurs. Return inventory/failure evidence if the cached-only
environment is unavailable; provisioning needs a separate reviewed scope.
The helper's later fresh binary inspection invokes only cached aapt2/apksigner
against the immutable frozen bytes and checks actual compiled manifest policy.
Source manifest declarations alone are insufficient.

The harness starts its own foreground ADB daemon child on a freshly minted
`localfilesystem:<private-root>/adb-server.sock` socket that is absent before
launch. Every ADB call supplies `-L` with that socket, including daemon startup,
queries, exact-serial commands and its own kill-server. It never starts, stops or
restarts the host's global ADB server. Private daemon identity, child starttime
and job ownership must match; shutdown and actual wait0 are mandatory.

Reject any existing device, including offline/unauthorized transports, and
occupied console/ADB TCP ports5554/5555. Create exactly one private API33 x86_64
AVD, no snapshots/audio/window/cameras/metrics, two cores and 2560MiB. Require boot
completion, API33/x86_64, exact minted console AVD name and actual spawned child
starttime before any guest write. Later operations use only `emulator-5554`.

All APK/ZIP bytes, pulled installed APKs, Android home and AVD reside below one
fresh `$RUNNER_TEMP/gccwebos-b2-runtime.XXXXXX` subtree. Evidence upload contains
metadata/logs/verification/results only, never APKs, ZIPs, native binaries or AVDs.

## P7–P9 once each

Install the two frozen debug APKs without replacement flags on that disposable
AVD only. Refuse existing QA package identities. Pull each installed base.apk
into the private subtree and verify its frozen bytes/hash; retain package
metadata. Execute the following exact methods once each in this order, with
a 180-second host limit:

1. `p7OwnerDeathKeepsWorkerAndQuarantine`: actual worker COPY remains in stack at
   count1 when owner dies and the harness call returns. Receiver scanned WIPED
   still cannot establish quiescence; new owner remains blocked. Release the gate,
   observe actual COPY0, then perform explicit reconciliation.
2. `p8ExactEvidenceWaitsForCallAndDisposition`: exact worker fence/wipe/drain does
   not release owner heap, harness claim or extra lease. Independently await
   actual owner death and actual SEND finally; explicit HOST_GONE and exact lease
   release are both required.
3. `p9WorkerDeathAndStaleEvidenceStayScoped`: actual worker loss leaves the alive
   owner's sender debt. Owner scanned WIPED and worker HOST_GONE stay distinct;
   fresh worker on the same alive owner refuses old ACK/death/lease evidence.

Use selector `-e class
com.gccwebos.android.ipcqa.b2.OwnerDeathIpcTest#METHOD` and the fixed runner.
No default suite, failed-case rerun or timed-out SEND retry. Stop on the first
nonzero host result, runner/parser/telemetry failure or lost ownership; later
cases remain NOT_RUN. The fixture may poll read-only gates and perform bounded
idempotent ATTACH/OPEN; it never replays RESERVE/SEND after transport loss.

Clear only this owned emulator's logcat before each case. Retain raw
instrumentation, exact-tag B2QA log, package/service metadata and sampled QA-only
UID/PID rows. B2QA must match selected case, harness PID, expected stages/order,
actual awaited callback role, claims/leases, dispositions, zero-scan booleans and
worker COPY counts. PID is diagnostic and reuse alone is not failure. The fixture
logs no capability, incarnation, revision, request, ticket, nonce, seed, length,
digest or bytes.

Runner acceptance requires exact identity/counts, status1 then0, final
`INSTRUMENTATION_CODE: -1`, tests1/failures0/passed1 and one last-line
`B2_CALL_EXIT=0`. Status-2, wrong method/class, duplicates, missing endings,
crash/timeout text or host exit124/nonzero fail. ADB exit0 alone cannot pass.
Observations do not prove erasure of hidden OS/kernel/VM copies or VPN resources.

## Owned teardown is mandatory

The EXIT trap runs after success or failure. Before the only emulator shutdown
request, `adb -L <private-socket> -s emulator-5554 emu kill`, revalidate the actual
spawned child/starttime, sole exact serial and minted AVD name. Unknown/changed
ownership refuses shutdown and reports failure. No app/arbitrary-PID signal or
fallback process kill occurs.

Require console request exit0, the actual owned child to stop and be reaped by
wait0, then serial absence with an otherwise empty device list. Console OK alone
does not prove stop. Private ADB shutdown/wait0 must also succeed. Only after
these proofs may the helper delete the canonical direct-child minted subtree
under original RUNNER_TEMP, with no symlink ancestors/members and confirmed
deletion. SDK, workspace and global Android home are never deletion targets.
Preflight failure before launch retains the subtree: NOT_STARTED is insufficient
deletion authority. If identity/shutdown proof fails, retain the directory and
mark cleanup unconfirmed. Runner disposal is not fabricated teardown evidence.

Overall PASS requires all three runner and telemetry records PASS, host exit0,
actual child stop/wait0, serial absence, private ADB stop/wait0 and directory
removal. Abrupt job/VM termination can prevent the trap; the always evidence step
then emits FAILED/missing-summary with cleanup flags false.

## Activation and dispatch boundary

The user has approved this exact single run. The five core proposal files are
bound by the new `harness-sha256.json`; its whole-file hash and the activation
freeze hash are embedded in the separate active shim. `approval.json` records
the authorization and exact scope separately from that five-file manifest.
The `.disabled` workflow is the original byte-for-byte historical proposal;
its old approval comments and PENDING values are historical only.

The active shim reuses `.github/workflows/android-native-runtime-api33.yml`, an
entry already registered on default main. Root will place the new shim only on
`codex/lucky-b2-runtime`; main's existing native workflow behavior is unchanged.
Only workflow_dispatch is allowed. There are no push or PR runtime triggers.
Root must commit the reviewed files and dispatch exactly that full commit through
`reviewed_harness_commit`; checkout equality and both exact hash guards remain.
Preparation and source build approval do not mean this run has executed.

The shim's preceding cached inventory step only reads tool/image/KVM metadata
and writes bounded JSON. It starts no SDK tool, installs/downloads nothing and
repairs no permissions. Missing cached prerequisite or KVM means STOP. The
unchanged runtime shell is invoked at most once; each P7/P8/P9 case runs at most
once in order. Any failure stops without rerun or retry. Owned teardown remains
mandatory; no fallback process kill or fabricated cleanup evidence is allowed.

Evidence artifact identity remains `owner-death-b2-runtime-<full harness commit>`,
14-day retention with a 28MiB cap. Evidence includes metadata/logs/results only.
No APK/ZIP/AVD or user configuration enters evidence upload. This preparation
sends no messages and performs no repository, production or Android operation.
