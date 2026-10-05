# Manual-import CI follow-up

Risk: B, test fixtures, orchestration and evidence only. Application code, encrypted stores,
private-data guards and native code are unchanged.

## Observed failures on fcddaa9

Source: `fcddaa9c646dd02b16769f4812ab01032e46f519`. Both hosted jobs finished with
failure. The [sanitized receipt](evidence/ci-import/2026-10-05/fcddaa9-failures.json)
records the exact runs and nonpassing test identities.

- [Android M0 run 37268485149](https://github.com/gccwebos-com/gccwebos-android/actions/runs/37268485149/job/111630240159)
  passed source checks, Debug/test APK compilation, all **1001 JVM tests** and Lint
  with zero errors. The API36 emulator then raised a real SystemUI startup ANR
  after the one permitted warm boot. An ANR window remained in the foreground.
  The pre-install ANR guard correctly failed: neither APK installation nor app
  instrumentation ran. The host had two CPUs and the guest used two CPUs/3072 MB.
  CPU/graphics contention is a supported hypothesis, not a proven root cause.
- [Native run 37268485142](https://github.com/gccwebos-com/gccwebos-android/actions/runs/37268485142/job/111630240251)
  completed 278 instrumentation cases: **271 passed, five explicit opt-in cases
  skipped, two failed**. Actual JNI loading passed. Both failures were the
  `SelectedNodeActivityRecreationTest` precondition that all private stores be
  absent; their test bodies did not run. The separate real-TUN phase was not reached.

The CI script starts MainActivity before instrumentation. With manual import,
that normal startup now atomically initializes an empty `LegacyRemote` sidecar.
The two encrypted-v1 recreation fixtures intentionally require fresh storage.
Their earlier isolated OPPO runs started from that fresh state; the hosted suite
did not. Deleting the sidecar or accepting unknown pre-existing content would
weaken their protection and is not the fix.

The five skipped cases belong to `ManualImportDeviceFlowTest` (physical camera/SAF
paths) and `ManualImportUpgradeTest` (prepared same-signature upgrade fixture).
They require their own explicit arguments, package identity and synthetic setup.
The native verifier correctly rejects skipped tests, so these cannot be presented
as passing ordinary emulator coverage.

## Correction and preserved boundaries

On each fresh disposable AVD, install the same app/test APKs, then execute the two
recreation cases before the first ordinary MainActivity launch. Require both to
start and pass, with no skip, failure or missing case. Run the remaining regular
suite after the existing launch/foreground checks, excluding only that already
executed class and the two dedicated manual acceptance classes. API33 retains its
original separate real-TUN class exclusion and opt-in execution.

Both regular batches share the existing 1800-second budget, including the launch
checks between them. There is no additional per-batch 1800-second allowance.
Native JNI verification, skip rejection, boot/ANR/foreground checks and the
separate 360-second real-TUN budget remain unchanged. Both batch logs are retained.

The five dedicated manual cases retain their separate
[OPPO/upgrade receipts](manual-import-validation.md); they are excluded from ordinary
emulator selection and are not counted as hosted passes. No fixture reads,
overwrites or deletes a pre-existing selector to make a test pass.

The follow-up push starts a new hosted API36 runner with the same ANR gate. It is
a fresh environment attempt, not evidence that the earlier ANR has been solved.
If startup failure repeats, a runner with sufficient actual CPU capacity needs
evaluation; increasing virtual cores on a two-core host or hiding the ANR would
not establish a healthy test environment.

Source review also found an obsolete no-engine UI assertion in the recreation
fixture: it expected a removed explanatory title. The fixture now asserts that
the old title stays absent and exercises the actual account-choice CTA, verifies
its login/register choices, cancels it, and checks that no connection started.
The selected-node, opaque-handle, recreation and private-store assertions remain.

## Local validation of the correction

- Eleven offline transcript/partition/budget checks pass, including exact fresh
  identities, skipped/failed/missing/duplicate rejection, no overlapping batches,
  one shared deadline, timeout propagation and retained partial evidence.
- The six existing renderer readback/rejection checks pass unchanged.
- Bash syntax checks pass for both entrypoints and the shared helper;
  `python scripts/verify_source.py` and `git diff --check` pass.
- `:app:compileDebugAndroidTestKotlin` passes for the amended fixture. This is
  compilation, not an executed no-engine device result.

Independent review found no further concrete blocker in the partition or fixture
correction. Hosted execution on the follow-up head remains required; the earlier
1001 JVM passes and failing device results retain their original source identity.
