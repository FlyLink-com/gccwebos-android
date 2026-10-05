#!/usr/bin/env bash
# Disposable Android emulator only. No real VPN, backend or signing credentials.
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
OUT="$PWD/.build-output/device"
mkdir -p "$OUT"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${SDK_ROOT:?Android SDK is required}"
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/avdmanager"
ADB="$SDK_ROOT/platform-tools/adb"
EMULATOR="$SDK_ROOT/emulator/emulator"
# Pure API 36 AOSP: this app does not use Google services. Keep all ANR/foreground gates.
SYSTEM_IMAGE='system-images;android-36;default;x86_64'
APP_ID='com.gccwebos.android.debug'
ACTIVITY="$APP_ID/com.gccwebos.android.MainActivity"
TEST_RUNNER="$APP_ID.test/androidx.test.runner.AndroidJUnitRunner"
APK='app/build/outputs/apk/debug/app-debug.apk'
TEST_APK='app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
test -s "$APK" && test -s "$TEST_APK"
log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*" | tee -a "$OUT/progress.log"; }
# The API 36 image enforces a data-image minimum even when config.ini requests
# 2G. Free unused preinstalled native build tools, AFTER both APKs are built.
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
python3 scripts/test_m0_renderer.py
cp "$ANDROID_AVD_HOME/gccwebos-m0.avd/config.ini" "$OUT/avd-config.ini"
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
  -no-snapshot -no-metrics -gpu swiftshader_indirect -feature -Vulkan -debug init -camera-back none -camera-front none \
  -accel on -cores "$EMU_CORES" -memory "$EMU_MEMORY" > "$OUT/emulator.log" 2>&1 &
EMU_PID=$!
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
verify_renderer() {
  local label="$1"
  adb shell getprop ro.build.version.sdk > "$OUT/$label-api.log"
  adb shell wm size > "$OUT/$label-size.log"
  adb shell wm density > "$OUT/$label-density.log"
  adb shell getprop ro.boot.hardware.vulkan > "$OUT/$label-boot-vulkan.log"
  adb shell getprop ro.hardware.vulkan > "$OUT/$label-vulkan.log"
  adb shell getprop debug.hwui.renderer > "$OUT/$label-hwui.log"
  adb shell getprop ro.opengles.version > "$OUT/$label-gles.log"
  adb shell getprop debug.renderengine.backend > "$OUT/$label-renderengine.log"
  bash scripts/verify-m0-renderer.sh "$OUT" "$label"
}
sleep 1
kill -0 "$EMU_PID" || { cat "$OUT/emulator.log"; exit 1; }
log 'Waiting for emulator transport'
timeout 180 "$ADB" -s emulator-5554 wait-for-device
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
# API36 first boot repeatedly stalls SystemUIService on this disposable two-core host.
# Retain the first-boot failure evidence, then warm-boot ONCE before any APK installation.
# No ANR dialog is dismissed and no app/foreground/instrumentation gate is bypassed.
log 'Preserving initial API36 boot diagnostics before one bounded warm boot'
sleep 60
adb shell cat /proc/sys/kernel/random/boot_id > "$OUT/first-boot-id.log"
FIRST_BOOT_ID=$(tr -d '\r\n' < "$OUT/first-boot-id.log")
test -n "$FIRST_BOOT_ID"
adb logcat -b main -b system -b events -b crash -d > "$OUT/first-boot-logcat.log"
adb shell dumpsys activity lastanr > "$OUT/first-boot-last-anr.log"
adb shell dumpsys window > "$OUT/first-boot-window.log"
adb exec-out screencap -p > "$OUT/first-boot.png"
verify_renderer first-boot
log 'Rebooting only the owned disposable AVD once; application is not installed yet'
adb reboot
timeout 180 "$ADB" -s emulator-5554 wait-for-device
warm_booted=false
deadline=$((SECONDS + 240))
while (( SECONDS < deadline )); do
  current_boot_id=$(timeout 5 "$ADB" -s emulator-5554 shell cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n' || true)
  completed=$(timeout 5 "$ADB" -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n' || true)
  if [ -n "$current_boot_id" ] && [ "$current_boot_id" != "$FIRST_BOOT_ID" ] && [ "$completed" = 1 ]; then
    warm_booted=true
    printf '%s\n' "$current_boot_id" > "$OUT/post-warmboot-id.log"
    break
  fi
  kill -0 "$EMU_PID"
  sleep 2
done
[ "$warm_booted" = true ] || { log 'FAIL: single warm boot did not complete'; exit 1; }
sleep 30
adb logcat -b main -b system -b events -b crash -d > "$OUT/post-warmboot-logcat.log"
adb shell dumpsys activity lastanr > "$OUT/post-warmboot-last-anr.log"
adb shell dumpsys window > "$OUT/post-warmboot-window.log"
adb exec-out screencap -p > "$OUT/post-warmboot.png"
verify_renderer post-warmboot
if grep -Eq 'am_anr[[:space:]]*:|ANR in ' "$OUT/post-warmboot-logcat.log"; then
  log 'FAIL: ANR after the single warm boot; no further retry'
  exit 1
fi
grep -E 'mCurrentFocus|mFocusedApp' "$OUT/post-warmboot-window.log" > "$OUT/post-warmboot-focus.log"
if grep -Eiq 'Application Not Responding|Application Error|isn.t responding' "$OUT/post-warmboot-focus.log"; then
  log 'FAIL: system error window after warm boot'
  exit 1
fi
printf '%s\n' 'One pre-install disposable-AVD warm boot completed; first-boot evidence retained; normal gates still required.' \
  > "$OUT/warmboot-result.log"
log 'Android boot complete; allowing package manager and SystemUI to settle'
sleep 10
adb shell service check package | tee "$OUT/package-service.log"
grep -q 'found' "$OUT/package-service.log"
log 'Installing APKs with a separate 180-second budget for large native packages'
adb shell input keyevent 82
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell getprop ro.build.version.sdk | tee "$OUT/api-level.log"
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
log 'Running instrumented tests'
# At eed8ed2, API36 reached case 161/254 at the 720s suite deadline; the
# complete API33 suite took 1054.569s. Allow 1800s for the expanded API36 suite.
# Per-test/boot deadlines, ANR gates and the 45-minute workflow limit stay unchanged.
# Evidence and remaining CI failures: docs/ci-ui-suite-budget-2026-10-04.md.
timeout 1800 "$ADB" -s emulator-5554 shell am instrument -w -r "$TEST_RUNNER" \
  | tee "$OUT/instrumentation.log"
grep -Eq 'OK \([1-9][0-9]* tests?\)' "$OUT/instrumentation.log"
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$OUT/instrumentation.log"; then exit 1; fi
if [ "${REQUIRE_NATIVE_JNI:-false}" = true ]; then python3 scripts/verify_native_instrumentation.py "$OUT/instrumentation.log"; fi
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
# Capture actual narrow-phone and tablet rendering; reset only this disposable AVD.
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
sha256sum "$APK" "$TEST_APK" | tee "$OUT/installed-apks.sha256"
printf '%s\n' 'PASS: same Debug APK installed and launched on API 36 emulator; instrumentation passed.' \
  'NOT TESTED: physical devices, real VPN, backend, payments.' | tee "$OUT/result.log"
