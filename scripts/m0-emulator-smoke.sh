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
SYSTEM_IMAGE='system-images;android-36;google_apis;x86_64'
APP_ID='com.gccwebos.android.debug'
ACTIVITY="$APP_ID/com.gccwebos.android.MainActivity"
TEST_RUNNER="$APP_ID.test/androidx.test.runner.AndroidJUnitRunner"
APK='app/build/outputs/apk/debug/app-debug.apk'
TEST_APK='app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
test -s "$APK" && test -s "$TEST_APK"
"$SDKMANAGER" --sdk_root="$SDK_ROOT" emulator "$SYSTEM_IMAGE" 2>&1 | tee "$OUT/sdk-install.log"
# This changes only the hosted runner's ephemeral device permission.
test -c /dev/kvm
sudo chmod a+rw /dev/kvm
"$EMULATOR" -accel-check 2>&1 | tee "$OUT/acceleration.log"
printf 'no\n' | "$AVDMANAGER" create avd --force --name gccwebos-m0 --package "$SYSTEM_IMAGE"
"$EMULATOR" -avd gccwebos-m0 -port 5554 -no-window -no-audio -no-boot-anim \
  -no-snapshot -gpu swiftshader_indirect -camera-back none -camera-front none \
  -accel on -cores 2 -memory 2048 > "$OUT/emulator.log" 2>&1 &
EMU_PID=$!
cleanup() {
  "$ADB" -s emulator-5554 logcat -b crash -d > "$OUT/crash.log" 2>&1 || true
  "$ADB" -s emulator-5554 emu kill >/dev/null 2>&1 || true
  kill "$EMU_PID" >/dev/null 2>&1 || true
  wait "$EMU_PID" 2>/dev/null || true
}
trap cleanup EXIT
adb() { "$ADB" -s emulator-5554 "$@"; }
timeout 300 "$ADB" -s emulator-5554 wait-for-device
booted=false
for _ in $(seq 1 120); do
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = '1' ]; then
    booted=true
    break
  fi
  kill -0 "$EMU_PID"
  sleep 2
done
[ "$booted" = true ] || { echo 'Emulator boot timed out' >&2; exit 1; }
adb shell input keyevent 82
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell getprop ro.build.version.sdk | tee "$OUT/api-level.log"
adb shell getprop ro.build.fingerprint | tee "$OUT/fingerprint.log"
adb install -r "$APK" | tee "$OUT/install-app.log"
grep -q 'Success' "$OUT/install-app.log"
adb install -r "$TEST_APK" | tee "$OUT/install-test.log"
grep -q 'Success' "$OUT/install-test.log"
adb logcat -c
adb shell am start -W -n "$ACTIVITY" | tee "$OUT/launch.log"
grep -q 'Status: ok' "$OUT/launch.log"
sleep 3
adb shell pidof "$APP_ID" | tee "$OUT/app-pid.log"
test -s "$OUT/app-pid.log"
adb exec-out screencap -p > "$OUT/launch.png"
adb shell uiautomator dump /sdcard/m0-window.xml > "$OUT/ui-dump.log"
adb pull /sdcard/m0-window.xml "$OUT/window.xml"
timeout 240 "$ADB" -s emulator-5554 shell am instrument -w -r "$TEST_RUNNER" \
  | tee "$OUT/instrumentation.log"
grep -Eq 'OK \([1-9][0-9]* tests?\)' "$OUT/instrumentation.log"
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$OUT/instrumentation.log"; then exit 1; fi
adb shell am start -W -n "$ACTIVITY" > "$OUT/relaunch.log"
sleep 2
adb shell pidof "$APP_ID" > "$OUT/final-pid.log"
test -s "$OUT/final-pid.log"
adb exec-out screencap -p > "$OUT/final.png"
sha256sum "$APK" "$TEST_APK" | tee "$OUT/installed-apks.sha256"
printf '%s\n' 'PASS: same Debug APK installed and launched on API 36 emulator; instrumentation passed.' \
  'NOT TESTED: physical devices, real VPN, backend, payments.' | tee "$OUT/result.log"
