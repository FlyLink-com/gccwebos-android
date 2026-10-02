#!/usr/bin/env bash
# DISABLED PROPOSAL. Future approved hosted AVD only; never run on a personal device.
# No SDK provisioning, APK rebuild, app shell kill, global adb restart or retry.
set -euo pipefail
HERE=$(cd -- "$(dirname -- "$0")" && pwd -P)
HELPER="$HERE/b2-runtime-evidence.py"
FREEZE="$HERE/freeze.json"
: "${APPROVED_FREEZE_SHA256:?A separately reviewed immutable freeze hash is required}"
: "${RUNNER_TEMP:?}"
: "${GITHUB_WORKSPACE:?}"
: "${GITHUB_RUN_ID:?}"
: "${GITHUB_RUN_ATTEMPT:?}"
[[ "$APPROVED_FREEZE_SHA256" =~ ^[0-9a-f]{64}$ ]]
printf '%s  %s\n' "$APPROVED_FREEZE_SHA256" "$FREEZE" | sha256sum -c -
python3 "$HELPER" check-ready "$FREEZE"
test "${GITHUB_ACTIONS:-}" = true
test "${RUNNER_ENVIRONMENT:-}" = github-hosted
test "${GITHUB_REPOSITORY:-}" = gccwebos-com/gccwebos-android
test "${RUNNER_OS:-}" = Linux
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${SDK_ROOT:?Cached Android SDK is required}"
ADB="$SDK_ROOT/platform-tools/adb"
EMULATOR="$SDK_ROOT/emulator/emulator"
AVDMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/avdmanager"
SERIAL=emulator-5554
IMAGE='system-images;android-33;default;x86_64'
TARGET=com.gccwebos.android.ipcqa.b2
TEST_PACKAGE="$TARGET.test"
RUNNER="$TEST_PACKAGE/$TARGET.OwnerDeathRunner"
CLASS="$TARGET.OwnerDeathIpcTest"
METHODS=(p7OwnerDeathKeepsWorkerAndQuarantine p8ExactEvidenceWaitsForCallAndDisposition p9WorkerDeathAndStaleEvidenceStayScoped)
OUT="$GITHUB_WORKSPACE/.build-output/b2-runtime"
mkdir -p "$OUT"
cp "$FREEZE" "$OUT/freeze.json"
PRIVATE_ROOT=$(mktemp -d "$RUNNER_TEMP/gccwebos-b2-runtime.XXXXXX")
ADB_SOCKET="localfilesystem:$PRIVATE_ROOT/adb-server.sock"
export ADB_SERVER_SOCKET="$ADB_SOCKET"
ADB_SERVER_PID=''
ADB_SERVER_START=''
ADB_LAUNCH_ATTEMPTED=false
AVD_NAME="gccwebos-b2-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT-${PRIVATE_ROOT##*.}"
EMU_PID=''
EMU_START=''
LAUNCH_ATTEMPTED=false
OWN_CONFIRMED=false
SAMPLE_PID=''
SAMPLER_ATTEMPTED=false
log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*" | tee -a "$OUT/progress.log"; }
adb() { private_adb_owned && timeout 20 "$ADB" -L "$ADB_SOCKET" -s "$SERIAL" "$@"; }
# Read-only identity of our actual spawned child; never signal a numeric PID.
process_key() {
  python3 - "$1" <<'PY'
import sys
from pathlib import Path
try:
    text = Path('/proc') .joinpath(sys.argv[1], 'stat').read_text()
    fields = text[text.rfind(')') + 2:].split()
    if fields[0] not in ('Z', 'X'):
        print(fields[19])  # /proc stat field22, actual process starttime
except (OSError, IndexError):
    pass
PY
}
child_running() {
  child_job_running && [ -n "$EMU_START" ] && [ "$(process_key "$EMU_PID")" = "$EMU_START" ]
}
process_live_or_unknown() {
  python3 - "$1" <<'PY'
import sys
from pathlib import Path
path = Path('/proc').joinpath(sys.argv[1], 'stat')
try:
    text = path.read_text()
    state = text[text.rfind(')') + 2:].split()[0]
    sys.exit(1 if state in ('Z', 'X') else 0)
except (OSError, IndexError):
    # Unreadable live identity is not evidence of exit.
    sys.exit(0 if path.parent.exists() else 1)
PY
}
child_job_running() { [ -n "$EMU_PID" ] && jobs -p | grep -Fxq "$EMU_PID" && process_live_or_unknown "$EMU_PID"; }
private_adb_job_running() { [ -n "$ADB_SERVER_PID" ] && jobs -p | grep -Fxq "$ADB_SERVER_PID" && process_live_or_unknown "$ADB_SERVER_PID"; }
private_adb_owned() {
  private_adb_job_running && [ -n "$ADB_SERVER_START" ] && [ "$(process_key "$ADB_SERVER_PID")" = "$ADB_SERVER_START" ]
}
owns_emulator() {
  child_running && private_adb_owned || return 1
  timeout 5 "$ADB" -L "$ADB_SOCKET" devices -l > "$OUT/ownership-devices.log" 2>&1 || return 1
  python3 - "$OUT/ownership-devices.log" "$SERIAL" <<'PY'
import sys
from pathlib import Path
rows = [s.split() for s in Path(sys.argv[1]).read_text().splitlines()
        if s.strip() and not s.startswith('List of devices')]
assert len(rows) == 1 and rows[0][:2] == [sys.argv[2], 'device']
PY
  [ "$?" = 0 ] || return 1
  timeout 5 "$ADB" -L "$ADB_SOCKET" -s "$SERIAL" emu avd name > "$OUT/ownership-avd-name.log" 2>&1 || return 1
  python3 - "$OUT/ownership-avd-name.log" "$AVD_NAME" <<'PY'
import sys
from pathlib import Path
assert [s.strip() for s in Path(sys.argv[1]).read_text().splitlines() if s.strip()] == [sys.argv[2], 'OK']
PY
}
serial_absent() {
  private_adb_owned || return 1
  timeout 5 "$ADB" -L "$ADB_SOCKET" devices -l > "$OUT/devices-after-shutdown.log" 2>&1 || return 1
  python3 - "$OUT/devices-after-shutdown.log" "$SERIAL" <<'PY'
import sys
from pathlib import Path
rows = [s.split() for s in Path(sys.argv[1]).read_text().splitlines()
        if s.strip() and not s.startswith('List of devices')]
assert not any(r[0] == sys.argv[2] for r in rows)
assert not rows  # Unexpected transport invalidates the isolated environment.
PY
}
cleanup() {
  local original=$? shutdown=NOT_STARTED child_stopped=false absent=false removed=false final_exit=1
  local shutdown_request_exit=NOT_REQUESTED child_wait_exit=NOT_REAPED sampler_confirmed=true
  local adb_stopped=false adb_shutdown_exit=NOT_REQUESTED adb_wait_exit=NOT_REAPED
  trap - EXIT INT TERM
  set +e
  log "CLEANUP_BEGIN harness_exit=$original"
  touch "$PRIVATE_ROOT/sample-stop"
  if [ -n "$SAMPLE_PID" ]; then wait "$SAMPLE_PID"; fi
  if [ "$SAMPLER_ATTEMPTED" = true ] && [ -z "$SAMPLE_PID" ]; then sampler_confirmed=false; fi
  if [ "$LAUNCH_ATTEMPTED" = true ] && [ -z "$EMU_PID" ]; then shutdown=FAILED; fi
  if [ -n "$EMU_PID" ]; then
    shutdown=FAILED
    if child_running && [ "$OWN_CONFIRMED" = true ] && owns_emulator; then
      # Sole permitted shutdown operation, revalidated exact owned console.
      timeout 10 "$ADB" -L "$ADB_SOCKET" -s "$SERIAL" emu kill > "$OUT/emulator-console-shutdown.log" 2>&1
      shutdown_request_exit=$?
      deadline=$((SECONDS + 40))
      while child_job_running && (( SECONDS < deadline )); do sleep 1; done
    elif child_job_running; then
      log 'Refuse shutdown: live child lacks exact serial/AVD ownership proof'
    fi
    if ! child_job_running; then
      wait "$EMU_PID" 2>/dev/null
      child_wait_exit=$?
      printf '%s\n' "$child_wait_exit" > "$OUT/owned-child-wait-exit.txt"
      child_stopped=true
      if serial_absent; then absent=true; fi
      if [ "$OWN_CONFIRMED" = true ] && [ "$shutdown_request_exit" = 0 ] && [ "$child_wait_exit" = 0 ] && [ "$absent" = true ]; then shutdown=STOPPED; fi
    fi
  fi
  # The private ADB daemon is our own foreground child on the minted Unix socket.
  # Every command, including shutdown, explicitly names that socket; never global.
  if [ -n "$ADB_SERVER_PID" ] && private_adb_owned; then
    timeout 10 "$ADB" -L "$ADB_SOCKET" kill-server > "$OUT/private-adb-shutdown.log" 2>&1
    adb_shutdown_exit=$?
    deadline=$((SECONDS + 20))
    while private_adb_job_running && (( SECONDS < deadline )); do sleep 1; done
    if ! private_adb_job_running; then
      wait "$ADB_SERVER_PID" 2>/dev/null
      adb_wait_exit=$?
      if [ "$adb_shutdown_exit" = 0 ] && [ "$adb_wait_exit" = 0 ]; then adb_stopped=true; fi
    fi
  fi
  # Preflight/unknown launches retain the root; unproved teardown never deletes it.
  if [ "$shutdown" = STOPPED ] && [ "$sampler_confirmed" = true ] && [ "$adb_stopped" = true ]; then
    python3 "$HELPER" remove-temp "$PRIVATE_ROOT" "$RUNNER_TEMP" "$shutdown"
    [ "$?" = 0 ] && [ ! -e "$PRIVATE_ROOT" ] && removed=true
  fi
  python3 "$HELPER" aggregate "$OUT" "$original" "$shutdown" "$child_stopped" "$absent" "$removed" "$shutdown_request_exit" "$child_wait_exit" "$adb_stopped" "$adb_shutdown_exit" "$adb_wait_exit"
  final_exit=$?
  log "CLEANUP_END shutdown=$shutdown child_stopped=$child_stopped serial_absent=$absent private_adb_stopped=$adb_stopped temp_removed=$removed overall_exit=$final_exit"
  exit "$final_exit"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
python3 - "$PRIVATE_ROOT" "$RUNNER_TEMP" <<'PY'
import sys
from pathlib import Path
root, parent = map(Path, sys.argv[1:])
assert root.is_absolute() and parent.is_absolute() and root.resolve().parent == parent.resolve()
assert all(not p.is_symlink() for p in (root, *root.parents))
PY
export ANDROID_USER_HOME="$PRIVATE_ROOT/.android"
export ANDROID_EMULATOR_HOME="$ANDROID_USER_HOME"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
export ANDROID_SDK_HOME="$PRIVATE_ROOT"
mkdir -p "$ANDROID_AVD_HOME"
for tool in "$ADB" "$EMULATOR" "$AVDMANAGER" "$SDK_ROOT/build-tools/36.0.0/aapt2" "$SDK_ROOT/build-tools/36.0.0/apksigner"; do
  test -x "$tool" || { log 'STOP cached SDK executable missing'; exit 1; }
done
for tool in timeout ss sha256sum python3 gh; do command -v "$tool" >/dev/null; done
test -n "${JAVA_HOME_17_X64:-}" && test -x "$JAVA_HOME_17_X64/bin/java"
export JAVA_HOME="$JAVA_HOME_17_X64"
export PATH="$JAVA_HOME/bin:$PATH"
test -c /dev/kvm && test -r /dev/kvm && test -w /dev/kvm || { log 'STOP existing KVM access unavailable'; exit 1; }
test -f "$SDK_ROOT/system-images/android-33/default/x86_64/system.img"
python3 - "$SDK_ROOT/system-images/android-33/default/x86_64" "$OUT" <<'PY'
import hashlib, json, sys, xml.etree.ElementTree as ET
from pathlib import Path
image, out = map(Path, sys.argv[1:])
props = dict(line.strip().split('=', 1) for line in (image/'source.properties').read_text().splitlines()
             if '=' in line and not line.lstrip().startswith('#'))
assert props['AndroidVersion.ApiLevel'] == '33' and props['SystemImage.Abi'] == 'x86_64'
assert props['SystemImage.TagId'] == 'default'
pkg = ET.parse(image/'package.xml').getroot()
packages = [n for n in pkg.iter() if n.tag.split('}')[-1] == 'localPackage']
assert len(packages) == 1 and packages[0].attrib['path'] == 'system-images;android-33;default;x86_64'
(out/'cached-image.json').write_text(json.dumps({'path':str(image.resolve()), 'properties':props,
    'package_xml_sha256':hashlib.sha256((image/'package.xml').read_bytes()).hexdigest(),
    'source_properties_sha256':hashlib.sha256((image/'source.properties').read_bytes()).hexdigest(),
    'trust_boundary':'official package identifier in controlled runner cache; no provisioning'}, indent=2)+'\n')
PY
sha256sum "$ADB" "$EMULATOR" "$AVDMANAGER" "$SDK_ROOT/build-tools/36.0.0/aapt2" "$SDK_ROOT/build-tools/36.0.0/apksigner" > "$OUT/cached-tool-hashes.txt"
timeout 10 "$ADB" -L "$ADB_SOCKET" version > "$OUT/adb-version.log"
timeout 10 "$EMULATOR" -version > "$OUT/emulator-version.log" 2>&1
timeout 30 "$EMULATOR" -accel-check > "$OUT/acceleration.log" 2>&1
test ! -e "$PRIVATE_ROOT/adb-server.sock"
ADB_LAUNCH_ATTEMPTED=true
"$ADB" -L "$ADB_SOCKET" nodaemon server > "$OUT/private-adb-server.log" 2>&1 &
ADB_SERVER_PID=$!
ADB_SERVER_START=$(process_key "$ADB_SERVER_PID")
test -n "$ADB_SERVER_START"
deadline=$((SECONDS + 20))
while private_adb_owned && [ ! -S "$PRIVATE_ROOT/adb-server.sock" ] && (( SECONDS < deadline )); do sleep 1; done
private_adb_owned
test -S "$PRIVATE_ROOT/adb-server.sock"
printf '%s\n' "$ADB_SERVER_PID" > "$OUT/private-adb-child-pid.txt"
timeout 5 "$ADB" -L "$ADB_SOCKET" devices -l > "$OUT/devices-before.log"
python3 - "$OUT/devices-before.log" <<'PY'
import sys
from pathlib import Path
assert not [s for s in Path(sys.argv[1]).read_text().splitlines()
            if s.strip() and not s.startswith('List of devices')]
PY
test -z "$(ss -H -ltn '( sport = :5554 or sport = :5555 )')" || { log 'STOP emulator ports already occupied'; exit 1; }
read -r artifact_id < <(python3 - "$FREEZE" <<'PY'
import json, sys
print(json.load(open(sys.argv[1]))['artifact_id'])
PY
)
: "${GH_TOKEN:?Read-only current repository actions token required}"
timeout 60 gh api "repos/gccwebos-com/gccwebos-android/actions/artifacts/$artifact_id" > "$PRIVATE_ROOT/artifact-metadata.json"
timeout 120 gh api "repos/gccwebos-com/gccwebos-android/actions/artifacts/$artifact_id/zip" > "$PRIVATE_ROOT/artifact.zip"
python3 "$HELPER" materialize "$FREEZE" "$PRIVATE_ROOT/artifact.zip" "$PRIVATE_ROOT/artifact-metadata.json" "$PRIVATE_ROOT/frozen"
python3 "$HELPER" verify "$FREEZE" "$SDK_ROOT" "$PRIVATE_ROOT/frozen"
cp "$PRIVATE_ROOT/frozen/artifact-metadata.json" "$PRIVATE_ROOT/frozen/provenance.json" "$PRIVATE_ROOT/frozen/identity.json" "$PRIVATE_ROOT/frozen/build-candidate.json" "$OUT/"
cp -r "$PRIVATE_ROOT/frozen/verification" "$OUT/"
APK="$PRIVATE_ROOT/frozen/ownerDeathLab/build/outputs/apk/debug/ownerDeathLab-debug.apk"
TEST_APK="$PRIVATE_ROOT/frozen/ownerDeathLab/build/outputs/apk/androidTest/debug/ownerDeathLab-debug-androidTest.apk"
printf 'no\n' | timeout 60 "$AVDMANAGER" create avd --name "$AVD_NAME" --package "$IMAGE" --device pixel_7 --path "$ANDROID_AVD_HOME/$AVD_NAME.avd" > "$OUT/avd-create.log" 2>&1
test -s "$ANDROID_AVD_HOME/$AVD_NAME.avd/config.ini"
log 'Starting sole owned API33 AOSP AVD'
LAUNCH_ATTEMPTED=true
"$EMULATOR" -avd "$AVD_NAME" -port 5554 -no-window -no-audio -no-boot-anim -no-snapshot -no-metrics -gpu swiftshader_indirect -camera-back none -camera-front none -accel on -cores 2 -memory 2560 > "$OUT/emulator.log" 2>&1 &
EMU_PID=$!
EMU_START=$(process_key "$EMU_PID")
test -n "$EMU_START"
printf '%s\n' "$EMU_PID" > "$OUT/owned-emulator-child-pid.txt"
timeout 180 "$ADB" -L "$ADB_SOCKET" -s "$SERIAL" wait-for-device
owns_emulator
OWN_CONFIRMED=true
deadline=$((SECONDS + 240))
booted=false
while child_running && (( SECONDS < deadline )); do
  if [ "$(timeout 5 "$ADB" -L "$ADB_SOCKET" -s "$SERIAL" shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; then booted=true; break; fi
  sleep 2
done
test "$booted" = true
owns_emulator
test "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" = 33
test "$(adb shell getprop ro.product.cpu.abi | tr -d '\r')" = x86_64
adb shell getprop ro.build.fingerprint > "$OUT/fingerprint.log"
adb shell pm list packages > "$OUT/packages-before.log"
if grep -Eq '^package:com\.gccwebos\.android\.ipcqa\.b2(\.test)?[[:space:]]*$' "$OUT/packages-before.log"; then log 'STOP refuse to replace installed QA packages'; exit 1; fi
owns_emulator
timeout 120 "$ADB" -L "$ADB_SOCKET" -s "$SERIAL" install "$APK" | tee "$OUT/install-target.log"
grep -Fxq Success "$OUT/install-target.log"
owns_emulator
timeout 120 "$ADB" -L "$ADB_SOCKET" -s "$SERIAL" install "$TEST_APK" | tee "$OUT/install-test.log"
grep -Fxq Success "$OUT/install-test.log"
for role in target test; do
  package="$TARGET"; [ "$role" = test ] && package="$TEST_PACKAGE"
  owns_emulator
  adb shell pm path "$package" > "$OUT/installed-$role-path.log"
  installed_path=$(python3 - "$OUT/installed-$role-path.log" <<'PY'
import re, sys
from pathlib import Path
lines = Path(sys.argv[1]).read_text().splitlines()
assert len(lines) == 1 and re.fullmatch(r'package:/data/app/[A-Za-z0-9_./+=-]+/base\.apk', lines[0])
print(lines[0][len('package:'):])
PY
)
  adb pull "$installed_path" "$PRIVATE_ROOT/installed-$role.apk" > "$OUT/installed-$role-pull.log" 2>&1
  python3 - "$FREEZE" "$role" "$PRIVATE_ROOT/installed-$role.apk" <<'PY'
import hashlib, json, sys
from pathlib import Path
spec = next(s for s in json.load(open(sys.argv[1]))['apks'] if s['role'] == sys.argv[2])
raw = Path(sys.argv[3]).read_bytes()
assert len(raw) == spec['bytes'] and hashlib.sha256(raw).hexdigest() == spec['sha256']
PY
  adb shell dumpsys package "$package" > "$OUT/installed-$role-package.log"
done
for method in "${METHODS[@]}"; do
  owns_emulator
  log "METHOD_BEGIN $method single_attempt=true"
  adb logcat -c  # Clear logs only on the newly owned isolated emulator.
  rm -f -- "$PRIVATE_ROOT/sample-stop"
  set +e
  timeout 180 "$ADB" -L "$ADB_SOCKET" -s "$SERIAL" shell am instrument -w -r -e class "$CLASS#$method" "$RUNNER" > "$OUT/$method.log" 2>&1 &
  call_pid=$!
  SAMPLER_ATTEMPTED=true
  (
    set -euo pipefail
    sample_deadline=$((SECONDS + 190))
    while [ ! -e "$PRIVATE_ROOT/sample-stop" ] && (( SECONDS < sample_deadline )); do
      date -u +%FT%TZ
      test "$(process_key "$ADB_SERVER_PID")" = "$ADB_SERVER_START"
      timeout 3 "$ADB" -L "$ADB_SOCKET" -s "$SERIAL" shell ps -A -o UID,PID,PPID,NAME | awk -v p="$TARGET" 'NR==1 || $NF==p || $NF==p ":ownerqa" || $NF==p ":workerqa"'
      sleep 0.5
    done
    test -e "$PRIVATE_ROOT/sample-stop"
  ) > "$OUT/$method.os-process-samples.log" 2>&1 &
  SAMPLE_PID=$!
  wait "$call_pid"
  call_exit=$?
  touch "$PRIVATE_ROOT/sample-stop"
  wait "$SAMPLE_PID"
  sample_exit=$?
  SAMPLE_PID=''
  SAMPLER_ATTEMPTED=false
  printf 'B2_CALL_EXIT=%s\n' "$call_exit" >> "$OUT/$method.log"
  python3 "$HELPER" parse "$method" "$OUT/$method.log" "$OUT/$method.json"
  parse_exit=$?
  owns_emulator
  owned_exit=$?
  trace_exit=1
  services_exit=1
  crash_exit=1
  if [ "$owned_exit" = 0 ]; then
    adb logcat -b main -d -v brief -s B2QA:I '*:S' > "$OUT/$method.b2qa.log" 2>&1
    logcat_exit=$?
    if [ "$logcat_exit" = 0 ]; then python3 "$HELPER" trace "$method" "$OUT/$method.b2qa.log" "$OUT/$method.trace.json"; trace_exit=$?; fi
    adb shell dumpsys activity services "$TARGET" > "$OUT/$method.services.log" 2>&1
    services_exit=$?
    adb logcat -b crash -d -v threadtime > "$OUT/$method.crash.log" 2>&1
    crash_exit=$?
  fi
  python3 - "$OUT/$method.host-checks.json" "$method" "$call_exit" "$parse_exit" "$trace_exit" "$owned_exit" "$sample_exit" "$services_exit" "$crash_exit" <<'PY'
import json, sys
from pathlib import Path
keys = ('call_exit','parse_exit','trace_exit','ownership_exit','sample_exit','services_exit','crash_exit')
checks = dict(zip(keys, map(int, sys.argv[3:])))
checks.update(method=sys.argv[2], invocation_count=1)
Path(sys.argv[1]).write_text(json.dumps(checks,indent=2)+'\n')
PY
  checks_exit=$?
  set -e
  log "METHOD_END $method host_exit=$call_exit parse_exit=$parse_exit trace_exit=$trace_exit sample_exit=$sample_exit services_exit=$services_exit crash_exit=$crash_exit"
  if [ "$call_exit" != 0 ] || [ "$parse_exit" != 0 ] || [ "$trace_exit" != 0 ] || [ "$owned_exit" != 0 ] || [ "$sample_exit" != 0 ] || [ "$services_exit" != 0 ] || [ "$crash_exit" != 0 ] || [ "$checks_exit" != 0 ]; then log "STOP_ON_FAILURE $method"; exit 1; fi
done
log 'P7-P9 evidence gates passed; cleanup remains required for overall PASS'
