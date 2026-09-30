#!/usr/bin/env bash
# Downloads only official upstream artifacts. No branch-head fetch or code execution.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
mkdir -p build/downloads third_party toolchain
printf 'module local.build\n\ngo 1.27.1\n' > build/go.mod
printf 'module local.toolchain\n\ngo 1.27.1\n' > toolchain/go.mod
fetch() {
  local url=$1 path=$2 checksum=$3
  if [[ ! -f "$path" ]]; then curl --proto '=https' --tlsv1.2 --fail --location --retry 3 "$url" -o "$path.tmp"; mv "$path.tmp" "$path"; fi
  echo "$checksum  $path" | sha256sum -c -
}
COMMIT=ab405bad5beeeac8b003bb01f60f134f6df54471
ARCHIVE="build/downloads/mihomo-$COMMIT.tar.gz"
fetch "https://codeload.github.com/MetaCubeX/mihomo/tar.gz/$COMMIT" "$ARCHIVE" adb7a6e4207e8ca44567330e1d563163112787d3044d848305b2bfeff0282590
if [[ ! -f third_party/mihomo/.gcc-patched-source ]]; then
  if [[ -e third_party/mihomo/go.mod ]]; then
    echo 'Existing unmarked third_party/mihomo; inspect or move it before preparing. No files overwritten.' >&2; exit 1
  fi
  mkdir -p third_party/mihomo
  tar -xzf "$ARCHIVE" -C third_party/mihomo --strip-components=1
  patch -d third_party/mihomo -p1 < patches/0001-owned-tun-fd.patch
  echo "$COMMIT" > third_party/mihomo/.gcc-patched-source
fi
# Verify every upstream file and our one patch, catching an altered cache/worktree.
python3 scripts/verify-upstream.py "$ARCHIVE"
if [[ -z "${GO:-}" && ! -x toolchain/go/bin/go ]]; then
  fetch https://go.dev/dl/go1.27.1.linux-amd64.tar.gz build/downloads/go1.27.1.linux-amd64.tar.gz 63d339f0da5ab53635a56f2490a7984dfe12dfcff22ad749f63edaf590168445
  tar -xzf build/downloads/go1.27.1.linux-amd64.tar.gz -C toolchain
fi
GO=${GO:-"$ROOT/toolchain/go/bin/go"}
[[ "$($GO version)" == 'go version go1.27.1 '* ]] || { echo 'Go1.27.1 required'; exit 1; }
export GOTOOLCHAIN=local GOPATH=${GOPATH:-"$ROOT/build/gopath"} GOCACHE=${GOCACHE:-"$ROOT/build/gocache"}
"$GO" mod download
"$GO" mod verify
# Deliberately does not auto-accept SDK terms. Supply ANDROID_NDK_HOME after accepting
# https://developer.android.com/studio/terms and installing NDK29.0.14206865.
