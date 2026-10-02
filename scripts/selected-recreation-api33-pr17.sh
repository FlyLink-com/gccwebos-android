#!/usr/bin/env bash
# Fresh API33 targeted selected-node Activity recreation for immutable 8cff137 ordinary APKs.
# Exactly two component methods; no complete-suite claim, rebuild, native/TUN lab or retry.
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
OUT="$PWD/.build-output/selected-recreation-api33/device"
mkdir -p "$OUT"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${SDK_ROOT:?Android SDK is required}"
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/avdmanager"
ADB="$SDK_ROOT/platform-tools/adb"
EMULATOR="$SDK_ROOT/emulator/emulator"
# API 33 AOSP runtime; immutable binary compile/target SDK remain 36. Keep ANR/foreground gates.
SYSTEM_IMAGE='system-images;android-33;default;x86_64'
APP_ID='com.gccwebos.android.debug'
ACTIVITY="$APP_ID/com.gccwebos.android.MainActivity"
TEST_RUNNER="$APP_ID.test/androidx.test.runner.AndroidJUnitRunner"
APK='app/build/outputs/apk/debug/app-debug.apk'
TEST_APK='app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
test -s "$APK" && test -s "$TEST_APK"
# Recheck APK identity from the verified receipt immediately before emulator setup.
python3 - <<'PY'
import hashlib, json
from pathlib import Path
receipt = json.loads(Path('.build-output/selected-recreation-api33/provenance.json').read_text())
assert receipt['provenance_verified'] is True and receipt['runtime_api_under_test'] == 33
assert receipt['complete_source_suite'] is False and receipt['targeted_component_suite'] is True
assert receipt['expected_source_cases'] == receipt['expected_instrumentation_cases'] == 132
assert receipt['expected_targeted_cases'] == 2
assert receipt['targeted_component_class'] == 'com.gccwebos.android.SelectedNodeActivityRecreationTest'
assert receipt['suite_timeout_seconds'] == 180
for name, expected in receipt['apk_sha256'].items():
    assert hashlib.sha256(Path(name).read_bytes()).hexdigest() == expected, 'APK changed after provenance verification'
PY
SUITE_TIMEOUT_SECONDS=$(python3 -c "import json; print(json.load(open('.build-output/selected-recreation-api33/provenance.json'))['suite_timeout_seconds'])")
[[ "$SUITE_TIMEOUT_SECONDS" == 180 ]]
readonly SUITE_TIMEOUT_SECONDS
log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*" | tee -a "$OUT/progress.log"; }
# Free unused preinstalled native build tools only on the disposable hosted runner.
# Both immutable APKs have already been downloaded and verified.
# Never perform this cleanup on local machines or self-hosted runners.
log 'Inspecting ephemeral runner disk capacity'
df -h "$SDK_ROOT" | tee "$OUT/disk-before.log"
if [ "${GITHUB_ACTIONS:-}" = true ] && [ "${RUNNER_ENVIRONMENT:-}" = github-hosted ] \
  && [ "$SDK_ROOT" = /usr/local/lib/android/sdk ] \
  && [ "$(readlink -f "$SDK_ROOT")" = "$SDK_ROOT" ]; then
  for directory in "$SDK_ROOT/ndk" "$SDK_ROOT/cmake"; do
    if [ -d "$directory" ] && [ ! -L "$directory" ]; then
      du -sh "$directory" | tee -a "$OUT/removed-unused-tools.log"
      sudo rm -rf -- "$directory"
      test ! -e "$directory"
    fi
  done
else
  log 'No SDK cleanup: not the exact GitHub-hosted test environment'
fi
df -h "$SDK_ROOT" | tee "$OUT/disk-after.log"
log 'SDK image installation'
timeout 300 "$SDKMANAGER" --sdk_root="$SDK_ROOT" emulator "$SYSTEM_IMAGE" 2>&1 | tee "$OUT/sdk-install.log"
# Bind AVD creation and lookup to the same isolated directory. Never reuse a
# hosted runner's pre-existing emulator profile or a developer's AVD.
AVD_ROOT="$(mktemp -d "${RUNNER_TEMP:-/tmp}/gccwebos-m0-avd.XXXXXX")"
export ANDROID_USER_HOME="$AVD_ROOT/.android"
export ANDROID_EMULATOR_HOME="$ANDROID_USER_HOME"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
export ANDROID_SDK_HOME="$AVD_ROOT"
mkdir -p "$ANDROID_AVD_HOME"
printf '%s\n' "ANDROID_USER_HOME=$ANDROID_USER_HOME" "ANDROID_AVD_HOME=$ANDROID_AVD_HOME" > "$OUT/avd-paths.log"
log 'KVM and AVD preparation'
# This changes only the hosted runner's ephemeral device permission.
test -c /dev/kvm
sudo chmod a+rw /dev/kvm
timeout 30 "$EMULATOR" -accel-check 2>&1 | tee "$OUT/acceleration.log"
printf 'no\n' | timeout 60 "$AVDMANAGER" --verbose create avd --force \
  --name gccwebos-m0 --package "$SYSTEM_IMAGE" --device pixel_7 \
  --path "$ANDROID_AVD_HOME/gccwebos-m0.avd" 2>&1 | tee "$OUT/avd-create.log"
timeout 30 "$AVDMANAGER" list avd 2>&1 | tee "$OUT/avd-manager-list.log"
test -s "$ANDROID_AVD_HOME/gccwebos-m0.ini"
test -s "$ANDROID_AVD_HOME/gccwebos-m0.avd/config.ini"
timeout 30 "$EMULATOR" -list-avds 2>&1 | tee "$OUT/avd-emulator-list.log"
grep -Fxq gccwebos-m0 "$OUT/avd-emulator-list.log"
df -h "$ANDROID_AVD_HOME" | tee "$OUT/disk-space.log"
log 'Recording host resources and selecting bounded emulator resources'
getconf _NPROCESSORS_ONLN | tee "$OUT/host-cpus.log"
free -m | tee "$OUT/host-memory.log"
EMU_CORES=$(getconf _NPROCESSORS_ONLN)
(( EMU_CORES > 4 )) && EMU_CORES=4
EMU_MEMORY=2560
HOST_MEMORY_KB=$(awk '/MemTotal:/ { print $2 }' /proc/meminfo)
(( HOST_MEMORY_KB >= 6291456 )) && EMU_MEMORY=3072
printf 'cores=%s memory_mb=%s\n' "$EMU_CORES" "$EMU_MEMORY" | tee "$OUT/emulator-resources.log"
log 'AVD exists and is discoverable; launching emulator'
"$EMULATOR" -avd gccwebos-m0 -port 5554 -no-window -no-audio -no-boot-anim \
  -no-snapshot -no-metrics -gpu swiftshader_indirect -camera-back none -camera-front none \
  -accel on -cores "$EMU_CORES" -memory "$EMU_MEMORY" > "$OUT/emulator.log" 2>&1 &
EMU_PID=$!
LOGCAT_PID=''
cleanup() {
  local result=$?
  set +e
  log "Cleanup (original status $result)"
  timeout 10 "$ADB" -s emulator-5554 logcat -b main -b system -b events -b crash -d > "$OUT/logcat-all.log" 2>&1
  timeout 5 "$ADB" -s emulator-5554 logcat -b crash -d > "$OUT/crash.log" 2>&1
  timeout 5 "$ADB" -s emulator-5554 shell dumpsys activity lastanr > "$OUT/last-anr.log" 2>&1
  timeout 5 "$ADB" -s emulator-5554 shell dumpsys window > "$OUT/window-state.log" 2>&1
  timeout 5 "$ADB" -s emulator-5554 shell df -h /data > "$OUT/device-disk.log" 2>&1
  df -h "$SDK_ROOT" > "$OUT/host-disk-final.log" 2>&1
  timeout 10 "$ADB" -s emulator-5554 pull "/sdcard/Android/data/$APP_ID/files/ui-evidence" "$OUT/" > "$OUT/cleanup-ui-evidence-pull.log" 2>&1
  if [ -n "$LOGCAT_PID" ]; then kill "$LOGCAT_PID" >/dev/null 2>&1; wait "$LOGCAT_PID" 2>/dev/null; fi
  timeout 5 "$ADB" -s emulator-5554 emu kill >/dev/null 2>&1
  kill "$EMU_PID" >/dev/null 2>&1
  sleep 1
  kill -KILL "$EMU_PID" >/dev/null 2>&1
  wait "$EMU_PID" 2>/dev/null
  return "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# Offline adb commands must not wait indefinitely, including inside cleanup.
adb() { timeout 45 "$ADB" -s emulator-5554 "$@"; }
sleep 1
kill -0 "$EMU_PID" || { cat "$OUT/emulator.log"; exit 1; }
log 'Waiting for emulator transport'
timeout 180 "$ADB" -s emulator-5554 wait-for-device
# Retain logs continuously so an earlier ANR cannot disappear before final validation.
"$ADB" -s emulator-5554 logcat -b main -b system -b events -b crash > "$OUT/continuous-logcat.log" 2>&1 &
LOGCAT_PID=$!
log 'Waiting for Android boot completion'
booted=false
deadline=$((SECONDS + 240))
while (( SECONDS < deadline )); do
  if [ "$(timeout 5 "$ADB" -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = '1' ]; then
    booted=true
    break
  fi
  kill -0 "$EMU_PID"
  sleep 2
done
[ "$booted" = true ] || { log 'FAIL: emulator boot timed out'; exit 1; }
log 'Android boot complete; allowing package manager and SystemUI to settle'
sleep 10
adb shell service check package | tee "$OUT/package-service.log"
grep -q 'found' "$OUT/package-service.log"
# Preserve and reject boot ANRs before installation; no dismissal or warm-boot retry.
log 'Capturing pre-install Android health without hiding system failures'
adb logcat -b main -b system -b events -b crash -d > "$OUT/preinstall-logcat.log"
adb shell dumpsys activity lastanr > "$OUT/preinstall-last-anr.log"
adb shell dumpsys window > "$OUT/preinstall-window.log"
adb exec-out screencap -p > "$OUT/preinstall.png"
if grep -Eq 'am_anr[[:space:]]*:|ANR in ' "$OUT/preinstall-logcat.log"; then
  log 'FAIL: system ANR before installation; no app execution claimed'; exit 1
fi
grep -E 'mCurrentFocus|mFocusedApp' "$OUT/preinstall-window.log" > "$OUT/preinstall-focus.log"
if grep -Eiq 'Application Not Responding|Application Error|isn.t responding' "$OUT/preinstall-focus.log"; then
  log 'FAIL: system error window before installation'; exit 1
fi
log 'Installing immutable APKs with a separate 180-second budget'
adb shell input keyevent 82
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell getprop ro.build.version.sdk | tee "$OUT/api-level.log"
tr -d '\r' < "$OUT/api-level.log" | grep -Fxq 33
adb shell getprop ro.build.fingerprint | tee "$OUT/fingerprint.log"
timeout 180 "$ADB" -s emulator-5554 install -r "$APK" | tee "$OUT/install-app.log"
grep -q 'Success' "$OUT/install-app.log"
timeout 180 "$ADB" -s emulator-5554 install -r "$TEST_APK" | tee "$OUT/install-test.log"
grep -q 'Success' "$OUT/install-test.log"
log 'Launching application'
adb logcat -c
adb shell am start -W -n "$ACTIVITY" | tee "$OUT/launch.log"
grep -q 'Status: ok' "$OUT/launch.log"
sleep 3
adb shell pidof "$APP_ID" | tee "$OUT/app-pid.log"
test -s "$OUT/app-pid.log"
adb exec-out screencap -p > "$OUT/launch.png"
# Fail visibly rather than dismissing a SystemUI/app error dialog or trusting PID alone.
verify_foreground() {
  local label="$1"
  adb shell dumpsys window > "$OUT/$label-window-state.log"
  grep -E 'mCurrentFocus|mFocusedApp' "$OUT/$label-window-state.log" > "$OUT/$label-foreground.log"
  grep 'mCurrentFocus' "$OUT/$label-foreground.log" | grep -q "$APP_ID"
  if grep -Eiq 'Application Not Responding|Application Error|isn.t responding' "$OUT/$label-foreground.log"; then
    log 'FAIL: an error window covers the app'; return 1
  fi
}
verify_ui_xml() {
  local path="$1"
  grep -q "package=\"$APP_ID\"" "$path"
  if grep -Eiq 'isn.t responding|keeps stopping|无响应|停止运行|android:id/aerr_' "$path"; then
    log 'FAIL: system error dialog in UI capture'; return 1
  fi
}
log 'Capturing actual application UI hierarchy'
verify_foreground launch
adb shell uiautomator dump /sdcard/m0-window.xml > "$OUT/ui-dump.log"
adb pull /sdcard/m0-window.xml "$OUT/window.xml"
verify_ui_xml "$OUT/window.xml"
log 'Running exactly two selected-node Activity recreation methods (fixed first 180s budget)'
TARGETED_COMPONENT_CLASS='com.gccwebos.android.SelectedNodeActivityRecreationTest'
readonly TARGETED_COMPONENT_CLASS
timeout "$SUITE_TIMEOUT_SECONDS" "$ADB" -s emulator-5554 shell am instrument -w -r -e class "$TARGETED_COMPONENT_CLASS" "$TEST_RUNNER" \
  | tee "$OUT/instrumentation.log"
grep -Eq 'OK \([1-9][0-9]* tests?\)' "$OUT/instrumentation.log"
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$OUT/instrumentation.log"; then exit 1; fi
python3 - "$OUT/instrumentation.log" <<'PY'
import json, re, sys
from pathlib import Path

def verify(text, expected, source_cases):
    selected_class = 'com.gccwebos.android.SelectedNodeActivityRecreationTest'
    selected_expected = {
        selected_class + '#selectedSameNameSecondNodeSurvivesRealActivityRecreationWithTheSameOpaqueIdentity',
        selected_class + '#changedSameNameCachedContentClearsSelectionDuringRealActivityRecreationAndRejectsOldOpaque',
    }
    assert len(source_cases) == len(set(source_cases)) == 132, 'Incomplete immutable source roster'
    assert len(expected) == len(set(expected)) == 2 and set(expected) == selected_expected, 'Substituted component roster'
    assert set(expected).issubset(set(source_cases))
    assert {case for case in source_cases if case.startswith(selected_class + '#')} == selected_expected
    count = 2
    assert len(re.findall(r'^OK \(2 tests\)\s*$', text, re.M)) == 1, 'Expected exactly one two-method OK result'
    assert not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed', text)
    assert re.findall(r'^INSTRUMENTATION_CODE: (-?\d+)\s*$', text, re.M) == ['-1'], 'Abnormal or duplicated instrumentation completion'
    pending, records = {}, {}
    for line in text.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key, _, value = line.removeprefix('INSTRUMENTATION_STATUS: ').partition('=')
            assert key not in pending, 'Duplicate status field before status code'
            pending[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code = int(line.partition(': ')[2])
            assert code in (1, 0), f'Skipped or failed component test status {code}'
            assert pending.get('numtests') == '2', 'Unexpected component method count'
            cls, name = pending.get('class'), pending.get('test')
            assert cls == selected_class and name, 'Missing or wrong component identity'
            records.setdefault(cls + '#' + name, []).append(code)
            pending = {}
    assert not pending, 'Unterminated status block'
    assert set(records) == selected_expected, 'Missing, extra or substituted component methods'
    assert all(codes == [1, 0] for codes in records.values()), 'Component method skipped, duplicated or incomplete'
    return dict(tests=count, passed=count, assumption_skipped=0, complete_source_suite=False,
                targeted_component_suite=True, source_cases_enumerated=len(source_cases),
                targeted_component_class=selected_class, cases=records)

path = Path(sys.argv[1])
expected = json.loads(path.parent.parent.joinpath('expected-selected-instrumentation-cases.json').read_text())
source_cases = json.loads(path.parent.parent.joinpath('expected-instrumentation-cases.json').read_text())
result = verify(path.read_text(), expected, source_cases)
path.with_name('selected-recreation-test-result.json').write_text(json.dumps(result, indent=2) + '\n')
print(f"PASS: exactly {result['tests']} selected-node component methods passed, zero skips; {result['source_cases_enumerated']} source methods enumerated, complete source suite NOT executed")
PY
log 'Relaunching after tests'
adb shell am start -W -n "$ACTIVITY" > "$OUT/relaunch.log"
sleep 2
adb shell pidof "$APP_ID" > "$OUT/final-pid.log"
test -s "$OUT/final-pid.log"
verify_foreground final
adb exec-out screencap -p > "$OUT/final.png"
adb shell uiautomator dump /sdcard/m0-final-window.xml > "$OUT/final-ui-dump.log"
adb pull /sdcard/m0-final-window.xml "$OUT/final-window.xml"
verify_ui_xml "$OUT/final-window.xml"
adb pull "/sdcard/Android/data/$APP_ID/files/ui-evidence" "$OUT/" > "$OUT/ui-evidence-pull.log"
# Require all five screenshots produced by this exact component class on the fresh AVD.
# This gate checks PNG containers/dimensions/identity; human visual review remains separate.
python3 - "$OUT" <<'PY'
import hashlib, json, struct, sys
from pathlib import Path
root = Path(sys.argv[1])
expected = {
    'selected-node-before-real-activity-recreation.png',
    'selected-node-after-real-activity-recreation.png',
    'selected-node-connection-after-real-activity-recreation.png',
    'changed-cache-after-real-activity-recreation-nodes-unselected.png',
    'changed-cache-after-real-activity-recreation-unselected.png',
}
paths = {path.name: path for path in (root / 'ui-evidence').glob('*.png')}
assert set(paths) == expected, 'Missing, extra or reused component screenshot names'
records = []
for name in sorted(expected):
    path = paths[name]
    payload = path.read_bytes()
    assert len(payload) > 33 and payload[:8] == bytes([137, 80, 78, 71, 13, 10, 26, 10]), 'Invalid PNG signature'
    assert payload[8:16] == struct.pack('>I', 13) + b'IHDR', 'Missing PNG dimensions'
    width, height = struct.unpack('>II', payload[16:24])
    assert width > 0 and height > 0
    records.append(dict(path='ui-evidence/' + name, bytes=len(payload), sha256=hashlib.sha256(payload).hexdigest(), width=width, height=height))
receipt = json.loads(root.parent.joinpath('provenance.json').read_text())
data = dict(binary_source_commit=receipt['binary_source_commit'], binary_source_tree=receipt['binary_source_tree'],
            apk_sha256=receipt['apk_sha256'], targeted_component_class=receipt['targeted_component_class'],
            runtime_api=33, screenshots=records, human_visual_review_completed=False)
root.joinpath('selected-recreation-screenshots.json').write_text(json.dumps(data, indent=2) + '\n')
print('PASS: all five fresh selected-node Activity recreation screenshots have valid PNG headers/dimensions and recorded SHA-256')
PY
# Capture actual narrow-phone and tablet rendering; reset only this disposable AVD.
# These ancillary anonymous-launch captures are not selected-node recreation assertions.
for profile in narrow tablet; do
  if [ "$profile" = narrow ]; then size=640x1280; density=320; else size=1600x2560; density=240; fi
  adb shell wm size "$size"
  adb shell wm density "$density"
  adb shell am force-stop "$APP_ID"
  adb shell am start -W -n "$ACTIVITY" > "$OUT/$profile-launch.log"
  sleep 3
  verify_foreground "$profile"
  adb exec-out screencap -p > "$OUT/$profile.png"
  adb shell uiautomator dump /sdcard/ui-profile.xml > "$OUT/$profile-ui-dump.log"
  adb pull /sdcard/ui-profile.xml "$OUT/$profile.xml"
  verify_ui_xml "$OUT/$profile.xml"
done
adb shell wm size reset
adb shell wm density reset
# Do not accept an ANR that disappeared before the final screenshot.
adb logcat -b main -b system -b events -b crash -d > "$OUT/posttest-logcat.log"
adb shell dumpsys activity lastanr > "$OUT/posttest-last-anr.log"
if grep -Eq 'am_anr[[:space:]]*:|ANR in ' "$OUT/posttest-logcat.log" "$OUT/continuous-logcat.log"; then
  log 'FAIL: ANR during app/UI diagnostic'; exit 1
fi
sha256sum "$APK" "$TEST_APK" | tee "$OUT/installed-apks.sha256"
printf '%s\n' 'PASS: same prebuilt source-pinned ordinary APK pair installed and launched on API33; exactly two selected-node Activity recreation component methods passed, zero skips. The complete 132-method source suite was enumerated, not executed.' \
  'NOT TESTED: complete source suite, process death, physical devices, production backend/network, real TUN traffic, payments. API36 is not tested here; consult the source workflow result.' | tee "$OUT/result.log"
