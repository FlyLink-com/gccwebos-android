#!/usr/bin/env bash
# Approved frozen B1 only. Hosted disposable AVD; no APK build, VPN or fault injection.
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
OUT="$PWD/.build-output/b1-runtime"
mkdir -p "$OUT"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${SDK_ROOT:?Android SDK required}"
: "${RUNNER_TEMP:?Hosted temporary directory required}"
: "${GITHUB_RUN_ID:?Run identity required}"
: "${GITHUB_RUN_ATTEMPT:?Run attempt required}"
test "${GITHUB_ACTIONS:-}" = true
test "${RUNNER_ENVIRONMENT:-}" = github-hosted
test "${GITHUB_REPOSITORY:-}" = gccwebos-com/gccwebos-android
ADB="$SDK_ROOT/platform-tools/adb"
EMULATOR="$SDK_ROOT/emulator/emulator"
AVDMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/avdmanager"
SERIAL=emulator-5554
AVD_NAME="gccwebos-b1-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT"
SYSTEM_IMAGE='system-images;android-33;default;x86_64'
TARGET=com.gccwebos.android.ipcqa
TEST_PACKAGE=com.gccwebos.android.ipcqa.test
RUNNER="$TEST_PACKAGE/com.gccwebos.android.ipcqa.CopyBoundaryRunner"
CLASS=com.gccwebos.android.ipcqa.CopyBoundaryIpcTest
APK="$RUNNER_TEMP/b1-frozen/copyBoundaryLab/build/outputs/apk/debug/copyBoundaryLab-debug.apk"
TEST_APK="$RUNNER_TEMP/b1-frozen/copyBoundaryLab/build/outputs/apk/androidTest/debug/copyBoundaryLab-debug-androidTest.apk"
METHODS=(p1RemoteIdentity p2OwnedCopyAndWipe p3BoundedActualParcels p4ClearBeforeCopyAndLateAck p5OriginalStopTicketAcrossGeneration p6UnknownKeepsClaimUntilCallReturns)
test -s "$OUT/identity.json"
printf '%s  %s\n' 8e7875cbefbef040a41e76e7aaa5ea4f4b092491c00d1cc376ad5635413a02a1 "$APK" 2e061fcf66726e7b537edb3dc4c8f3e728c19af70c28c8323f16405cc9e621a1 "$TEST_APK" | sha256sum -c - | tee "$OUT/preinstall-hashes.log"
python3 - "$OUT" "${METHODS[@]}" <<'PY'
import json, sys
from pathlib import Path
root = Path(sys.argv[1])
for method in sys.argv[2:]:
    (root / (method + '.json')).write_text(json.dumps({'method':method,'status':'NOT_RUN','started':False,'completed':False}) + '\n')
PY
log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*" | tee -a "$OUT/progress.log"; }
adb() { timeout 20 "$ADB" -s "$SERIAL" "$@"; }
EMU_PID=''
OWN_CONFIRMED=false
AVD_ROOT=$(mktemp -d "$RUNNER_TEMP/gccwebos-b1-avd.XXXXXX")
AVD_NAME="$AVD_NAME-${AVD_ROOT##*.}"
printf '%s\n' "$AVD_NAME" > "$OUT/avd-name.txt"
export ANDROID_USER_HOME="$AVD_ROOT/.android"
export ANDROID_EMULATOR_HOME="$ANDROID_USER_HOME"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
export ANDROID_SDK_HOME="$AVD_ROOT"
mkdir -p "$ANDROID_AVD_HOME"
owns_emulator() {
  [ -n "$EMU_PID" ] && kill -0 "$EMU_PID" 2>/dev/null || return 1
  timeout 5 "$ADB" devices -l > "$OUT/emulator-owned-devices.log" 2>&1 || return 1
  python3 - "$OUT/emulator-owned-devices.log" "$SERIAL" <<'PY'
import sys
from pathlib import Path
rows = [s.split() for s in Path(sys.argv[1]).read_text().splitlines() if s.strip() and not s.startswith('List of devices')]
assert len(rows) == 1 and rows[0][:2] == [sys.argv[2], 'device'], 'Unexpected device or transport state'
PY
  [ "$?" = 0 ] || return 1
  timeout 5 "$ADB" -s "$SERIAL" emu avd name > "$OUT/emulator-avd-name.log" 2>&1 || return 1
  python3 - "$OUT/emulator-avd-name.log" "$AVD_NAME" <<'PY'
import sys
from pathlib import Path
lines = [s.strip() for s in Path(sys.argv[1]).read_text().splitlines() if s.strip()]
assert lines == [sys.argv[2], 'OK'], 'Console AVD identity mismatch'
PY
}
cleanup() {
  local original=$? shutdown=NOT_STARTED removed=false final_exit
  set +e
  log "CLEANUP_BEGIN original_exit=$original"
  if [ -n "$EMU_PID" ] && ! kill -0 "$EMU_PID" 2>/dev/null; then
    wait "$EMU_PID" 2>/dev/null
    shutdown=STOPPED
  elif [ -n "$EMU_PID" ] && [ "$OWN_CONFIRMED" = true ] && owns_emulator; then
    adb logcat -b main -b system -b crash -d -v threadtime -s CopyBoundaryRunner ActivityManager AndroidRuntime > "$OUT/logcat-selected.log" 2>&1
    adb shell dumpsys activity lastanr > "$OUT/last-anr.log" 2>&1
    adb shell dumpsys activity services "$TARGET" > "$OUT/final-services.log" 2>&1
    adb shell ps -A -o UID,PID,PPID,NAME | awk -v p="$TARGET" 'NR==1 || $NF==p || $NF==p ":ipcqa"' > "$OUT/final-processes.log"
    # Console shutdown of this new emulator only; never shell kill/force-stop an app.
    if owns_emulator; then
      timeout 10 "$ADB" -s "$SERIAL" emu kill > "$OUT/emulator-shutdown.log" 2>&1
      local deadline=$((SECONDS + 30))
      while kill -0 "$EMU_PID" 2>/dev/null && (( SECONDS < deadline )); do sleep 1; done
      if kill -0 "$EMU_PID" 2>/dev/null; then
        shutdown=FAILED
      else
        wait "$EMU_PID" 2>/dev/null
        shutdown=STOPPED
      fi
    else
      shutdown=FAILED
      log 'Refused console shutdown after diagnostics: ownership changed'
    fi
  elif [ -n "$EMU_PID" ]; then
    shutdown=FAILED
    log 'Refused console shutdown: this emulator ownership is not confirmed'
  fi
  # Only delete the newly created AVD after it has stopped; validate both roots.
  local resolved_temp resolved_avd
  resolved_temp=$(readlink -f "$RUNNER_TEMP")
  resolved_avd=$(readlink -f "$AVD_ROOT")
  case "$resolved_avd" in
    "$resolved_temp"/gccwebos-b1-avd.*)
      if [ "$shutdown" != FAILED ] && [ -d "$resolved_avd" ] && [ ! -L "$AVD_ROOT" ]; then
        rm -rf -- "$resolved_avd"
        [ ! -e "$resolved_avd" ] && removed=true
      fi ;;
    *) log 'Refused AVD cleanup outside the owned temporary prefix' ;;
  esac
  final_exit=$original
  if [ "$shutdown" = FAILED ] || [ "$removed" != true ]; then final_exit=1; fi
  python3 - "$OUT" "$original" "$shutdown" "$removed" "$final_exit" <<'PY'
import json, sys
from pathlib import Path
out = Path(sys.argv[1])
methods = ['p1RemoteIdentity','p2OwnedCopyAndWipe','p3BoundedActualParcels','p4ClearBeforeCopyAndLateAck','p5OriginalStopTicketAcrossGeneration','p6UnknownKeepsClaimUntilCallReturns']
records = []
for m in methods:
    p = out/(m+'.json')
    d = json.loads(p.read_text())
    if d['status'] == 'RUNNING':
        d.update(status='INTERRUPTED', completed=False, reason='Invocation interrupted; cleanup of Binder claims is unconfirmed')
        p.write_text(json.dumps(d,indent=2)+'\n')
    records.append(d)
data = {'harness_exit':int(sys.argv[2]),'emulator_shutdown':sys.argv[3],'owned_avd_removed':sys.argv[4]=='true',
        'physical_device_tested':False,'vpn_started':False,'real_subscription_used':False,'explicit_app_fault_injection':False,
        'methods':records,'overall_exit':int(sys.argv[5])}
(out/'result.json').write_text(json.dumps(data,indent=2)+'\n')
PY
  if [ "$?" != 0 ]; then final_exit=1; log 'Evidence summary write failed'; fi
  log "CLEANUP_END shutdown=$shutdown owned_avd_removed=$removed overall_exit=$final_exit"
  exit "$final_exit"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
log 'Verifying an empty hosted adb device list'
timeout 20 "$ADB" devices -l > "$OUT/devices-before.log"
python3 - "$OUT/devices-before.log" <<'PY'
import sys
from pathlib import Path
lines = [s for s in Path(sys.argv[1]).read_text().splitlines() if s.strip() and not s.startswith('List of devices')]
assert not lines, 'Refuse to operate when another device exists'
PY
test -c /dev/kvm
sudo chmod a+rw /dev/kvm
timeout 30 "$EMULATOR" -accel-check > "$OUT/acceleration.log" 2>&1
printf 'no\n' | timeout 60 "$AVDMANAGER" create avd --name "$AVD_NAME" --package "$SYSTEM_IMAGE" --device pixel_7 --path "$ANDROID_AVD_HOME/$AVD_NAME.avd" > "$OUT/avd-create.log" 2>&1
test -s "$ANDROID_AVD_HOME/$AVD_NAME.avd/config.ini"
timeout 20 "$EMULATOR" -list-avds > "$OUT/avd-list.log" 2>&1
grep -Fxq "$AVD_NAME" "$OUT/avd-list.log"
log 'Starting the new official API33 AOSP emulator'
"$EMULATOR" -avd "$AVD_NAME" -port 5554 -no-window -no-audio -no-boot-anim -no-snapshot -no-metrics -gpu swiftshader_indirect -camera-back none -camera-front none -accel on -cores 2 -memory 2560 > "$OUT/emulator.log" 2>&1 &
EMU_PID=$!
timeout 180 "$ADB" -s "$SERIAL" wait-for-device
owns_emulator
OWN_CONFIRMED=true
deadline=$((SECONDS + 240))
booted=false
while (( SECONDS < deadline )); do
  kill -0 "$EMU_PID"
  if [ "$(timeout 5 "$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then booted=true; break; fi
  sleep 2
done
[ "$booted" = true ] || { log 'BOOT_TIMEOUT'; exit 1; }
owns_emulator
adb shell getprop ro.build.version.sdk | tee "$OUT/api-level.log"
tr -d '\r' < "$OUT/api-level.log" | grep -Fxq 33
adb shell getprop ro.build.fingerprint > "$OUT/fingerprint.log"
adb shell service check package | tee "$OUT/package-service.log"
grep -q found "$OUT/package-service.log"
adb shell pm list packages > "$OUT/packages-before.log"
if grep -Eq '^package:com\.gccwebos\.android\.ipcqa(\.test)?[[:space:]]*$' "$OUT/packages-before.log"; then log 'Refuse to replace an existing package'; exit 1; fi
log 'Installing exact approved target and test APKs without replacement flags'
owns_emulator
timeout 120 "$ADB" -s "$SERIAL" install "$APK" | tee "$OUT/install-target.log"
grep -Fxq Success "$OUT/install-target.log"
owns_emulator
timeout 120 "$ADB" -s "$SERIAL" install "$TEST_APK" | tee "$OUT/install-test.log"
grep -Fxq Success "$OUT/install-test.log"
adb shell pm path "$TARGET" > "$OUT/installed-target-path.log"
adb shell pm path "$TEST_PACKAGE" > "$OUT/installed-test-path.log"
for role in target test; do
  installed_path=$(python3 - "$OUT/installed-$role-path.log" <<'PY'
import sys
from pathlib import Path
lines = Path(sys.argv[1]).read_text().splitlines()
assert len(lines) == 1 and lines[0].startswith('package:/data/app/') and lines[0].endswith('/base.apk')
print(lines[0][len('package:'):])
PY
  )
  adb pull "$installed_path" "$RUNNER_TEMP/b1-installed-$role.apk" > "$OUT/installed-$role-pull.log" 2>&1
done
printf '%s  %s\n' 8e7875cbefbef040a41e76e7aaa5ea4f4b092491c00d1cc376ad5635413a02a1 "$RUNNER_TEMP/b1-installed-target.apk" 2e061fcf66726e7b537edb3dc4c8f3e728c19af70c28c8323f16405cc9e621a1 "$RUNNER_TEMP/b1-installed-test.apk" | sha256sum -c - | tee "$OUT/installed-hashes.log"
adb shell dumpsys package "$TARGET" > "$OUT/installed-target-package.log"
adb shell dumpsys package "$TEST_PACKAGE" > "$OUT/installed-test-package.log"
for method in "${METHODS[@]}"; do
  owns_emulator
  log "METHOD_BEGIN $method"
  date -u +%FT%TZ > "$OUT/$method.begin.txt"
  python3 - "$OUT/$method.json" <<'PY'
import json, sys
from pathlib import Path
p = Path(sys.argv[1]); d = json.loads(p.read_text()); d.update(status='RUNNING',attempted=True,started=False,completed=False)
p.write_text(json.dumps(d,indent=2)+'\n')
PY
  set +e
  timeout 90 "$ADB" -s "$SERIAL" shell am instrument -w -r -e class "$CLASS#$method" "$RUNNER" > "$OUT/$method.log" 2>&1 &
  call_pid=$!
  (
    while kill -0 "$call_pid" 2>/dev/null; do
      date -u +%FT%TZ
      timeout 3 "$ADB" -s "$SERIAL" shell ps -A -o UID,PID,PPID,NAME | awk -v p="$TARGET" 'NR==1 || $NF==p || $NF==p ":ipcqa"'
      sleep 0.3
    done
  ) > "$OUT/$method.os-process-samples.log" 2>&1 &
  sample_pid=$!
  wait "$call_pid"
  call_exit=$?
  wait "$sample_pid" || true
  printf 'B1_CALL_EXIT=%s\n' "$call_exit" >> "$OUT/$method.log"
  python3 scripts/materialize-copy-boundary-b1.py parse "$method" "$OUT/$method.log" "$OUT/$method.json"
  parse_exit=$?
  set -e
  python3 - "$OUT/$method.json" "$call_exit" "$parse_exit" <<'PY'
import json, sys
from pathlib import Path
p = Path(sys.argv[1])
try:
    data = json.loads(p.read_text())
except (OSError, ValueError):
    data = {'method':p.stem,'started':False,'completed':False}
data.update(adb_exit=int(sys.argv[2]),parse_exit=int(sys.argv[3]),attempted=True,invocation_ended=True)
if data['parse_exit'] != 0 and data.get('status') not in ('FAILED','INCOMPLETE'):
    data.update(status='PARSE_ERROR',completed=False,reason='Invocation ended but runner evidence could not be verified')
if data['adb_exit'] != 0:
    data['status'] = 'TIMEOUT' if data['adb_exit'] == 124 else 'FAIL'
    data['reason'] = 'Host instrumentation timeout' if data['adb_exit'] == 124 else 'ADB invocation did not succeed'
p.write_text(json.dumps(data, indent=2)+'\n')
PY
  printf '%s\n' "$call_exit" > "$OUT/$method.adb-exit.txt"
  date -u +%FT%TZ > "$OUT/$method.end.txt"
  log "METHOD_END $method adb_exit=$call_exit parse_exit=$parse_exit"
  adb shell ps -A -o UID,PID,PPID,NAME | awk -v p="$TARGET" 'NR==1 || $NF==p || $NF==p ":ipcqa"' > "$OUT/$method.processes.log" 2>&1 || true
  adb shell dumpsys activity services "$TARGET" > "$OUT/$method.services.log" 2>&1 || true
  cat "$OUT/$method.log"
  if [ "$call_exit" != 0 ] || [ "$parse_exit" != 0 ]; then log "STOP_ON_FAILURE $method"; exit 1; fi
done
log 'P1-P6 all passed on the exact frozen APKs; B1 synthetic Binder copy/clear evidence only'
