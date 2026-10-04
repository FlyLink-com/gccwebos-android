#!/usr/bin/env bash
# Read-only acceptance of the owned API36 GLES environment, before APK install.
set -euo pipefail
OUT="$1"
label="$2"
expect() {
  local file="$OUT/$label-$1.log"
  test -f "$file"
  if [ "$(tr -d '\r' < "$file")" != "$2" ]; then
    printf 'FAIL: unexpected renderer readback: %s\n' "$1" >&2
    exit 1
  fi
}
expect api 36
expect size 'Physical size: 1080x2400'
expect density 'Physical density: 420'
expect boot-vulkan ''
expect vulkan ''
expect hwui skiagl
expect gles 196608
feature=$(grep -E "Feature 'Vulkan' \([0-9]+\) (state set to |is overridden to )" \
  "$OUT/emulator.log" | tail -n 1 | tr -d '\r')
[[ "$feature" == *'state set to disabled' || "$feature" == *"is overridden to 'disabled'" ]]
grep -Fq 'Graphics Adapter Android Emulator OpenGL ES Translator (Google SwiftShader)' "$OUT/emulator.log"
grep -Fq 'Graphics API Version OpenGL ES 3.0' "$OUT/emulator.log"
if grep -Eq 'Bad feature name|Unknown feature|Initializing VkEmulation features:|Selecting Vulkan device:|androidboot.hardware.vulkan=ranchu' \
  "$OUT/emulator.log"; then
  printf 'FAIL: Vulkan initialization or unrecognized feature in host log\n' >&2
  exit 1
fi
printf 'PASS: API36 original Pixel7 display, GLES3 and disabled Vulkan readbacks\n'
