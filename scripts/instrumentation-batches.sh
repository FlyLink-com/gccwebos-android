#!/usr/bin/env bash
# Sourced by disposable-emulator scripts. No device state is cleared or reused here.
FRESH_STORE_CLASS='com.gccwebos.android.SelectedNodeActivityRecreationTest'
# Camera/SAF and same-signature upgrade fixtures require separate explicit device
# arguments/packages. Their five tests are not hosted-suite passes or skips.
MANUAL_OPT_IN_CLASSES='com.gccwebos.android.ManualImportDeviceFlowTest,com.gccwebos.android.portal.ManualImportUpgradeTest'
INSTRUMENTATION_DEADLINE=''

instrumentation_exclusions() {
  printf '%s\n' "$FRESH_STORE_CLASS,$MANUAL_OPT_IN_CLASSES${1:+,$1}"
}

instrumentation_begin() {
  test -z "$INSTRUMENTATION_DEADLINE" || return 1
  INSTRUMENTATION_STARTED=$SECONDS
  INSTRUMENTATION_DEADLINE=$((SECONDS + 1800))
  : > "$OUT/instrumentation-all.log"
  printf '%s\n' 'budget_seconds=1800' > "$OUT/instrumentation-budget.log"
}

instrumentation_remaining() {
  local remaining=$((INSTRUMENTATION_DEADLINE - SECONDS))
  if (( remaining <= 0 )); then
    printf '%s\n' 'FAIL: shared instrumentation budget exhausted' >&2
    return 124
  fi
  printf '%s\n' "$remaining"
}

# The middle application launch/readback consumes this same deadline. Outside it,
# callers retain their original command cap (including the separate real-TUN phase).
instrumentation_timeout() {
  local cap="$1" remaining
  shift
  if [ -n "$INSTRUMENTATION_DEADLINE" ]; then
    remaining=$(instrumentation_remaining) || return $?
    (( remaining < cap )) && cap=$remaining
  fi
  timeout "$cap" "$@"
}

instrumentation_batch() {
  local label="$1" output
  shift
  case "$label" in
    fresh) output="$OUT/instrumentation-fresh.log" ;;
    main) output="$OUT/instrumentation.log" ;;
    *) return 1 ;;
  esac
  # pipefail in the caller keeps timeout/ADB/tee failure fatal. Accumulate even a
  # partial failed batch; do not replace a preceding batch's evidence.
  instrumentation_timeout 1800 "$ADB" -s emulator-5554 shell am instrument -w -r "$@" "$TEST_RUNNER" \
    | tee "$output" | tee -a "$OUT/instrumentation-all.log"
}

instrumentation_finish() {
  instrumentation_remaining >/dev/null || return $?
  printf 'elapsed_seconds=%s\n' "$((SECONDS - INSTRUMENTATION_STARTED))" >> "$OUT/instrumentation-budget.log"
  INSTRUMENTATION_DEADLINE=''
}
