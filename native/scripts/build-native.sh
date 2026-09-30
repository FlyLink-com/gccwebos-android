#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
GO=${GO:-"$ROOT/toolchain/go/bin/go"}
NDK=${ANDROID_NDK_HOME:-"$ROOT/toolchain/android-ndk-r29"}
[[ -x "$GO" ]] || { echo 'Install pinned Go toolchain (see README)'; exit 1; }
[[ "$($GO version)" == 'go version go1.27.1 '* ]] || { echo 'Go 1.27.1 required'; exit 1; }
[[ -f "$NDK/source.properties" ]] && grep -q 'Pkg.Revision = 29.0.14206865' "$NDK/source.properties" || { echo 'NDK r29 revision29.0.14206865 required'; exit 1; }
export GOTOOLCHAIN=local
export GOPATH=${GOPATH:-"$ROOT/build/gopath"}
export GOCACHE=${GOCACHE:-"$ROOT/build/gocache"}
export CGO_ENABLED=1 GOOS=android
export CGO_CFLAGS='-O2 -g0 -fstack-protector-strong -D_FORTIFY_SOURCE=2'
export CGO_LDFLAGS='-Wl,-z,max-page-size=16384 -Wl,-z,relro -Wl,-z,now'
FLAGS='-s -w -buildid= -X github.com/metacubex/mihomo/constant.Version=v1.19.31-gcc1 -X github.com/metacubex/mihomo/constant.BuildTime=2026-09-30T00:00:00Z'
for abi in "${@:-arm64-v8a x86_64}"; do
  for arch in $abi; do
    case $arch in
      arm64-v8a) export GOARCH=arm64; triple=aarch64-linux-android26 ;;
      x86_64) export GOARCH=amd64; triple=x86_64-linux-android26 ;;
      *) echo "Unsupported ABI: $arch"; exit 1 ;;
    esac
    export CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/$triple-clang"
    mkdir -p "build/jniLibs/$arch"
    "$GO" build -mod=readonly -trimpath -buildvcs=false -tags 'cmfa with_gvisor' \
      -ldflags "$FLAGS" -buildmode=c-shared -o "build/jniLibs/$arch/libgccmihomo.so" ./bridge
    "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -lW "build/jniLibs/$arch/libgccmihomo.so" > "build/jniLibs/$arch/elf-program-headers.txt"
    "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -dW "build/jniLibs/$arch/libgccmihomo.so" > "build/jniLibs/$arch/elf-dynamic.txt"
    sha256sum "build/jniLibs/$arch/libgccmihomo.so"
  done
done
