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
gles=$(tr -d '\r' < "$OUT/$label-gles.log")
case "$gles" in
  196608) minor=0 ;;
  196609) minor=1 ;;
  196610) minor=2 ;;
  *) printf 'FAIL: guest must advertise a known GLES 3.x version\n' >&2; exit 1 ;;
esac
feature=$(grep -E "Feature 'Vulkan' \([0-9]+\) (state set to |is overridden to )" \
  "$OUT/emulator.log" | tail -n 1 | tr -d '\r')
[[ "$feature" == *'state set to disabled' || "$feature" == *"is overridden to 'disabled'" ]]
mode=$(grep -F 'emuglConfig_init: vulkan_mode_selected:' "$OUT/emulator.log" | tail -n 1 | tr -d '\r')
[[ "$mode" == *'vulkan_mode_selected:swiftshader gles_mode_selected:swangle' ]]
adapter=$(grep -F 'Graphics Adapter ' "$OUT/emulator.log" | tail -n 1 | tr -d '\r')
[[ "$adapter" == *'ANGLE ('* && "$adapter" == *'SwiftShader'* ]]
api=$(grep -F 'Graphics API Version ' "$OUT/emulator.log" | tail -n 1 | tr -d '\r')
[[ "$api" =~ Graphics\ API\ Version\ OpenGL\ ES\ 3[.]${minor}([.][0-9]+)?([[:space:]]|$) ]]
if grep -Eq 'Bad feature name|Unknown feature|Initializing VkEmulation features:|Selecting Vulkan device:|androidboot.hardware.vulkan=ranchu' \
  "$OUT/emulator.log"; then
  printf 'FAIL: Vulkan initialization or unrecognized feature in host log\n' >&2
  exit 1
fi
printf 'PASS: API36 original Pixel7 display, SwANGLE GLES3 and disabled guest Vulkan readbacks\n'
