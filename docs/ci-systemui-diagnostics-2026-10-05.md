# API36 pre-install ANR diagnostics

On `3aae91c`, [M0 run 37279433408](https://github.com/gccwebos-com/gccwebos-android/actions/runs/37279433408/job/111663787635)
passed capacity, build, 1001 JVM tests and Lint on the approved four-core runner,
then failed before installing either APK. After the single warm boot, logcat
records `com.android.systemui` failing to complete startup; both the screenshot
and focused window show its ANR dialog. `dumpsys activity lastanr` reports no ANR,
so that output cannot override the event log and visible error window.

The existing artifact did not include the generated ANR thread trace. Four cores
did not eliminate the failure; the underlying cause is still undetermined.

The B-class diagnostic follow-up adds collection only after the existing
pre-install ANR/error-window checks have already failed:

- Read `dumpsys dropbox --print system_app_anr`, bounded to 20 seconds.
- Run `adb -s emulator-5554 bugreport`, bounded to 90 seconds, to retain stack
  traces and other system evidence as described by the
  [Android bugreport documentation](https://developer.android.com/studio/debug/bug-report).
- Store raw output, the bugreport ZIP when produced, and individual return codes
  or timeout/error receipts under `.build-output/device/anr/`, which the existing
  always-run report artifact upload already includes.

Both commands address only the disposable AVD created by this script, before
application installation. No root, account, real device, dialog dismissal or
additional reboot is involved. A failed or timed-out collection does not change
the original `exit 1`. Even a successful collection does not establish that the
expected trace exists; inspect the returned artifact. The ordinary success path,
renderer checks, 45-minute job limit and instrumentation deadlines are unchanged.

Offline tests cover command destination and deadlines, successful output,
nonzero exits, partial output on timeout, both captures timing out, a missing
ADB executable and missing/truncated ZIPs. A shell harness executes the actual
two failure branches with a fake collector, proving collector failures/timeouts
still exit 1 and the successful boot path never collects. They do not invoke
ADB or an emulator. The initial check failed because the collector was absent;
after implementation all seven tests pass, together with source validation,
Bash syntax and diff checks. Hosted validation must separately establish
whether this environment supplies the needed thread trace.

Rollback: revert the collector, its two failure-branch calls and offline tests.
Keep the existing SystemUI rejection gates and the approved four-core runner.
