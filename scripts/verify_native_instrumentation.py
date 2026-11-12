#!/usr/bin/env python3
"""Native JNI case must run and pass; only registered design skips may be -4.

Policy (Yi, 2026-11-11): the GitHub-hosted emulator API 33 validate run has
three long-standing design skips, each gated by an assumption inside the
test. They are registered here as exact (class, test, reason) triples.
Anything else that skips, is ignored, fails, or goes missing still fails:

- an INSTRUMENTATION_STATUS_CODE: -4 whose (class, test, reason) is not
  registered -> fail;
- a registered skip appearing twice, or with a different reason -> fail;
- a registered test missing from the log entirely -> fail;
- a registered test that runs and passes (codes 1 then 0) is accepted: the
  skip is only excused, never required. On a real device, where the
  notification permission can be granted through the system prompt, the two
  RenewalNotification tests are expected to run instead of skipping.
- INSTRUMENTATION_STATUS_CODE: -3 (ignored) is never allowed.

This script is only invoked from the GitHub-hosted emulator validate path
(REQUIRE_NATIVE_JNI=true in android-native.yml /
android-native-runtime-api33.yml); the allowlist is scoped to that path.
It does not delete or weaken the -4 check: unregistered skips still fail.
"""
import sys
from pathlib import Path

# (fully-qualified class, test method, AssumptionViolatedException message)
EXPECTED_SKIPS = [
    (
        "com.gccwebos.android.InAppUpdateDeviceEvidenceTest",
        "thePublishedApkDownloadsVerifiesAndPassesTheRealCertificateCheck",
        "pass -e realDownload true",
    ),
    (
        "com.gccwebos.android.RenewalNotificationDeviceTest",
        "dailyTwentyHundredBeijingAlarmIsScheduledPostedOnceAndClearedOnSignOut",
        "notification permission not granted yet (run the popup test first)",
    ),
    (
        "com.gccwebos.android.RenewalNotificationDeviceTest",
        "outsideTheSevenDayWindowTheFirstAlarmWaitsForTheWindowAndNothingIsPosted",
        "notification permission not granted yet (run the popup test first)",
    ),
]

JNI_TEST = "actualJniLibraryLoadsAndRejectsUnsafeInputWithoutTakingCallerFd"
ASSUMPTION_PREFIX = "org.junit.AssumptionViolatedException: "


def parse_records(text):
    """One record per INSTRUMENTATION_STATUS_CODE line.

    Each test emits a start block (code 1) and a result block (code 0/-4/...);
    class/test/stack lines precede the code line within the same block.
    """
    records = []
    pending = {}
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: class="):
            pending["class"] = line.split("=", 1)[1].strip()
        elif line.startswith("INSTRUMENTATION_STATUS: test="):
            pending["test"] = line.split("=", 1)[1].strip()
        elif line.startswith("INSTRUMENTATION_STATUS: stack="):
            pending["stack"] = line.split("=", 1)[1].strip()
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            records.append(
                (
                    pending.get("class"),
                    pending.get("test"),
                    line.split(":", 1)[1].strip(),
                    pending.get("stack"),
                )
            )
            pending = {}
    return records


def main(log_path):
    text = Path(log_path).read_text(errors="replace")
    records = parse_records(text)

    # 1. The native JNI case must start and pass, not be missing/skipped/failed.
    jni_codes = [code for (_cls, test, code, _stack) in records if test == JNI_TEST]
    assert jni_codes == ["1", "0"], (
        f"Native JNI case must start and pass, not be missing/skipped/failed: {jni_codes}"
    )

    # 2. Ignored tests are never allowed.
    assert "INSTRUMENTATION_STATUS_CODE: -3" not in text, (
        "Native run must not ignore test cases"
    )

    # 3. Every skip must be a registered design skip, with the registered reason.
    by_test = {}
    for cls, test, code, stack in records:
        if code == "1" or test is None:
            continue
        by_test.setdefault((cls, test), []).append((code, stack))
    seen_skips = []
    for (cls, test), outcomes in sorted(by_test.items(), key=lambda kv: str(kv[0])):
        for code, stack in outcomes:
            if code == "0":
                continue
            if code != "-4":
                raise AssertionError(
                    f"Unexpected instrumentation result for {cls}.{test}: code {code}"
                )
            if stack is None or not stack.startswith(ASSUMPTION_PREFIX):
                raise AssertionError(
                    f"Skip for {cls}.{test} has no AssumptionViolatedException reason: {stack!r}"
                )
            reason = stack[len(ASSUMPTION_PREFIX):]
            triple = (cls, test, reason)
            if triple not in EXPECTED_SKIPS:
                raise AssertionError(
                    f"Unregistered skip: {cls}.{test} (reason: {reason!r})"
                )
            seen_skips.append(triple)
    duplicates = sorted({t for t in seen_skips if seen_skips.count(t) > 1}, key=str)
    assert not duplicates, f"Registered skip seen more than once: {duplicates}"

    # 4. Every registered test must be accounted for: skipped as registered, or passed.
    for cls, test, _reason in EXPECTED_SKIPS:
        outcomes = by_test.get((cls, test))
        assert outcomes, f"Registered test missing from the log: {cls}.{test}"
        assert all(code in ("0", "-4") for code, _stack in outcomes), (
            f"Registered test {cls}.{test} has an unexpected outcome: {outcomes}"
        )

    skipped = ", ".join(f"{t} ({r})" for _cls, t, r in seen_skips) or "(none skipped)"
    print(
        "PASS: actual native JNI test executed and passed; "
        f"registered design skips only ({len(seen_skips)}/3): {skipped}"
    )


if __name__ == "__main__":
    main(sys.argv[1])
