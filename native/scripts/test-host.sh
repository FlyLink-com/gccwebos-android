#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
GO=${GO:-"$ROOT/toolchain/go/bin/go"}
export GOTOOLCHAIN=local GOPATH=${GOPATH:-"$ROOT/build/gopath"} GOCACHE=${GOCACHE:-"$ROOT/build/gocache"}
"$GO" test -mod=readonly -count=1 -tags 'cmfa with_gvisor' ./bridge
"$GO" build -mod=readonly -trimpath -buildvcs=false -tags 'cmfa with_gvisor' -buildmode=c-shared -o build/libgccmihomo-host.so ./bridge
python3 scripts/test-host-tun.py
