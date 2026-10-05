# UI suite timeout evidence and adjustment

Date: 2026-10-04. Read-only GitHub inspection of PR #21 confirmed remote head
`eed8ed295b9e4f878b01de12a6b0586a48a76051`. These runs precede the manual-import
implementation in the working tree. They do not validate the new changes.

## Observed CI outcomes

- [Android M0 validate run 37234946127](https://github.com/gccwebos-com/gccwebos-android/actions/runs/37234946127/job/111532077445):
  Debug/JUnit/Lint/test-APK build succeeded. The API36 instrumentation suite started
  at `21:22:00Z`; case 160 finished and case 161 of 254 started at `21:34:00Z`.
  Its enclosing `timeout 720` terminated the invocation, and the step reported exit
  `124` at `21:34:04Z`. No failed test assertion was reported before termination.
  This is an incomplete suite, not a green test result or proof that every remaining
  case would pass.
- [Native lab run 37234946101](https://github.com/gccwebos-com/gccwebos-android/actions/runs/37234946101/job/111532077197):
  Native/app packaging succeeded. API33 instrumentation completed all 254 cases with
  `Time: 1,054.569` seconds and `Failures: 4`; the step exited `1`. Two
  `SelectedNodeActivityRecreationTest` cases failed to find `page-Connect`; two
  `WalletResumeUiTest` cases failed to find `commerce-signed-out`.
- `copy-boundary-compile` was skipped by its explicit `codex/lucky-e2b-` branch
  condition. The current PR branch does not match that condition. Both other jobs
  ran on provisioned runners; the evidence does not support a billing rejection.

## Minimal budget change

Only the complete-suite budgets change: `scripts/m0-emulator-smoke.sh` from 720 to
1800 seconds and `scripts/real-tun-runtime-api33.sh` from 1080 to 1800 seconds.
Individual test waits, emulator boot/install limits, ANR checks, failure detection,
and the separate real-TUN test's 360-second budget are unchanged. The workflow job
limits remain 45 minutes for Android M0 and 90 minutes for native-lab. No matrix or
the separate immutable-APK runtime workflow is changed.

The observed validate job spent about 12 minutes 24 seconds before instrumentation
and about 8 seconds after its old timeout. Replacing that one budget with 30 minutes
would total about 42 minutes 32 seconds with the same overhead, leaving roughly
2 minutes 28 seconds inside the existing job limit. This is an estimate, not a
guarantee for a later build or slower runner; the next exact-head run must verify it.

The old API33 suite limit left only about 25 seconds above the observed 254-case
runtime, insufficient headroom for the new tests. Native-lab spent 16 minutes
33 seconds before that suite on this run, which reused source-identical native
libraries. Reserving the new 30-minute suite limit, the full separate 6-minute TUN
budget, and one minute for subsequent evidence/relaunch work gives approximately
53 minutes 33 seconds against its existing 90-minute limit. No job-level increase
is needed on that evidence. Cold native builds and later runner performance are
not measured by this estimate; the new exact-head run remains the actual gate.

Git Bash `bash -n` and `git diff --check` passed for both edited scripts. No emulator,
Gradle build, workflow rerun or remote mutation was performed for this budget change.

## Separately corrected test contracts

The four native-lab failures are not suppressed or reclassified as infrastructure
failures. The working-tree tests were updated against current behavior:

- Wallet foreground verification intentionally returns without a signed-out
  placeholder. The tests now wait for real verification, assert the retained owner
  identity and absence of that placeholder, and retain the order recovery,
  GET-only, no repeated purchase and no repeated browser-open checks.
- Entering Nodes now performs a quiet fresh read. The old recreation fixture seeded
  only an encrypted v1 cache while leaving the production subscription downloader
  reachable. It could click during the disabled refresh interval. The fixture now
  uses the existing debug repository seam for a bounded synthetic subscription
  response, keeps the real MainActivity/service and encrypted v1 store, waits for
  enabled choices, and preserves opaque-identity and changed-content rejection
  assertions. Its owned fixture guards and owner-cleanup checks remain explicit.

## OPPO focused follow-up

The final targeted OPPO batch passed **32/32** cases, with no timeout or negative
instrumentation codes. Local receipt:
`.build-output/manual-import-oct4/manual-ui-final.json`.
The selected classes cover manual-import host/account/platform contracts, encrypted
manual and existing v1 stores, both corrected recreation/resume test classes, and
the official website component. This is focused device evidence; it does not claim
that the complete hosted-emulator suite or physical camera flow ran in this batch.

Exact-head CI validation remains pending. The old runs remain failed; neither the
timeout increase nor the focused OPPO result proves a green hosted UI suite. This
note records no customer data, configuration content or credentials.

## First manual-import head: source allowlist mismatch

Both jobs for `119ca07b987b8eaafe3376b084553c54a90bedf5` failed before compilation:
[validate 37268231598](https://github.com/gccwebos-com/gccwebos-android/actions/runs/37268231598/job/111629494148)
and [native-lab 37268231571](https://github.com/gccwebos-com/gccwebos-android/actions/runs/37268231571/job/111629493871).
Their `scripts/verify_source.py` exact manifest-permission assertion still listed the old
permissions and rejected the approved bundled scanner's CAMERA permission. Wrapper
verification succeeded; Gradle, native compilation and device suites did not run.

The follow-up adds CAMERA to that exact set and additionally requires optional camera
hardware, the non-exported scanner excluded from recents, and removal of the library's
generic CaptureActivity. It does not permit arbitrary extra permissions or remove the
VPN/service boundaries. `python scripts/verify_source.py` and `git diff --check` pass locally.
Only the source gate and this record change; the tested app/test APK inputs are unchanged.
The two failed runs remain failures. Hosted validation must be assessed on the follow-up head.
