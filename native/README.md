# GCC Web OS Android Mihomo JNI bridge

This is a source-built native library, not a stub. It embeds Mihomo v1.19.31,
commit `ab405bad5beeeac8b003bb01f60f134f6df54471`, with the `cmfa,with_gvisor`
build tags and one small, recorded TUN descriptor-ownership patch.

The bridge was initially verified in the debug-only app-owned VPN lab. The current
[one-click candidate](../docs/one-click-vpn-connection.md) packages verified libraries
for debug and unsigned release, and has passed synthetic Shadowsocks traffic on OPPO.
External endpoint interoperability and store readiness remain unverified. The Android
app retains packaging/API gates and uses a fresh isolated `:vpn` process for each run.
A watchdog kills that process on blocked teardown.

## Reproduce

Prerequisites: Linux x86_64, Python 3.12+, curl, patch, tar, gcc, libc development
headers, and Python package `cryptography==50.0.0` (host packet-path tests only).
The prepare script downloads the official pinned Go toolchain if GO is unset.
To use an existing identical Go version: `GO=/absolute/path/to/go`.

1. `./scripts/prepare.sh` verifies an exact upstream commit archive, applies the
   documented patch, verifies every upstream source file and all Go module sums.
2. `python3 -m pip install -r scripts/requirements-host-test.txt`
3. `./scripts/test-host.sh`
4. After accepting the [Android SDK terms](https://developer.android.com/studio/terms),
   install `ndk;29.0.14206865` using Android SDK Manager. Set ANDROID_NDK_HOME to it.
5. `./scripts/build-native.sh arm64-v8a x86_64`
6. Keep `build/jniLibs/<abi>/libgccmihomo.so` in this generated directory. The app's
   main source set packages it with GPL notices for debug and release. Candidate
   builds require the approved library hashes; follow the linked candidate recipe.
   Do not commit toolchains, Go caches, fetched upstream source, or generated .so files.

The artifact is Android API26+, uses Go1.27.1 and NDK r29, is stripped, and has
16KiB ELF LOAD alignment. Both ABI builds use the same fixed timestamp, `-trimpath`,
empty build ID and pinned dependencies. The build emits ELF and SHA256 receipts.
These choices make the recipe reproducible; do not claim bit-identical reproduction
until a second clean build has actually been compared.

## Kotlin/JNI contract

Class: `com.gccwebos.android.vpn.MihomoNative` (Kotlin object with `@JvmStatic`).
Library: `System.loadLibrary("gccmihomo")`.

- `nativeStart(configYaml: String, homeDir: String, tunFd: Int, protector: SocketProtector): String?`
- `nativeStop()`
- `nativeVersion(): String`
- `SocketProtector.protectSocket(fd: Int): Boolean`

`null` is successful startup; all errors are fixed diagnostic codes. Never display
upstream parser errors or config text. R8 must keep the JNI class/method names and
`SocketProtector.protectSocket(int): boolean`. `nativeStart` and `nativeStop` are
synchronous; run off the Android main thread. `nativeStop` is terminal: process exit
is mandatory before another successful start.

Despite the parameter name, `configYaml` accepts only strict UTF-8 JSON (a YAML
subset), max64KiB. Duplicate keys, unknown root/nested keys, plugins, providers,
management listeners, geodata/rule inputs and nonliteral IPv4 proxy endpoints are
rejected. The proxy name and routing policy are fixed. Versioned protocol shapes
are limited to SS AEAD, VLESS TCP TLS/Reality, Hysteria2 and TUIC v5; see
[the strict field and unsupported-shape matrix](../docs/offline-protocol-contract.md).
Explicit SS/VLESS UDP=false is preserved; HY2/TUIC have intrinsic UDP support.
Optional `bbr-profile` is limited to Hysteria2/TUIC and the exact strings
`conservative`, `standard`, or `aggressive`; missing stays absent. Null, empty,
unknown, mistyped, case-alias, duplicate and cross-protocol inputs are rejected.
The pinned [HY2 adapter](https://github.com/MetaCubeX/mihomo/blob/ab405bad5beeeac8b003bb01f60f134f6df54471/adapter/outbound/hysteria2.go#L232)
supplies the profile to its BBR callback. The [TUIC controller dispatch](https://github.com/MetaCubeX/mihomo/blob/ab405bad5beeeac8b003bb01f60f134f6df54471/transport/tuic/common/congestion.go#L16)
uses the profile only for BBR; this bridge preserves an omitted or explicit TUIC
congestion controller and never switches it to BBR. Arbitrary congestion/window
tuning, TLS/authentication changes and other unsupported fields remain rejected.
TUIC v5 also accepts optional `request-timeout` as a strict integer from 0 to
30000 milliseconds, preserving an explicit zero and omitting a missing value.
This bound is client compatibility policy. The pinned
[adapter](https://github.com/MetaCubeX/mihomo/blob/ab405bad5beeeac8b003bb01f60f134f6df54471/adapter/outbound/tuic.go#L271)
only passes this option to its v4 client; its v5 client does not consume it.
Accepting this field does not add a v5 timeout feature or allow v4 authentication.
Null, strings, floats, booleans, negative/out-of-range values, duplicate/alias
keys and use on SS, VLESS or Hysteria2 remain rejected.
Parsing support alone does not prove endpoint interoperability. Example:

```json
{"schemaVersion":2,"proxies":[{"name":"GCC_SELECTED_PROXY","type":"ss","server":"127.0.0.1","port":18388,"cipher":"aes-128-gcm","password":"lab-only-password","udp":true}],"rules":["MATCH,GCC_SELECTED_PROXY"],"routingMode":"global"}
```

The native layer constructs its own full config. All controller/inbound socket
ports, providers, NTP, profile persistence, geodata update URLs, sniffer and external
UI are disabled. DNS is hijacked in process and sent as TCP DNS to `1.1.1.1:53`
**through the selected proxy**. Because the proxy endpoint must be literal IPv4,
there is no proxy-hostname bootstrap query. Host tests answer this destination in a
loopback encrypted fixture; they never dial 1.1.1.1 or another external server.

## Android TUN and ownership

VpnService builder settings must match native settings:

- MTU1500; IPv4 `172.19.0.1/30`; DNS `172.19.0.2`; route `0.0.0.0/0`
- IPv6 `fdfe:dcba:9876::1/126`; route `::/0`; no allowFamily bypass
- app-scoped allowed application only for Lab/synthetic acceptance; the normal
  one-click candidate uses the default routes without this application restriction
- gVisor stack; auto-route and interface autodetection off

Native duplicates the caller's TUN descriptor using F_DUPFD_CLOEXEC (minimum3).
Caller still owns its ParcelFileDescriptor. The duplicate is consumed exactly once
by `NewWithOwnedFD` on success **and** failure. The patch also registers the Tun and
stack with the listener before fallible setup so error cleanup can see them.

The socket hook is installed for process lifetime. It returns an error when inactive,
when Java throws, when attach fails, or when protectSocket returns false. There is
no fallback that unsets the hook. The JNI callback holds a stable global reference;
stop waits for outstanding callbacks before releasing it. Android protection and
underlying Network.bindSocket must succeed together; never use a no-op protector
outside the explicit host simulation.

Rules deny literal IPv6 before the selected proxy and have a final REJECT fallback.
ICMP forwarding is explicitly disabled because upstream otherwise routes ping
DIRECT. Upstream may produce a local fake ICMP echo; ping is never evidence of a
working proxy. Native logs go to io.Discard and no config/DNS/destination/credential
payload is returned across JNI.

## Verification

`test-host.sh` covers:

- strict config rejection, including executable/fetching inputs and udp=false
- controlled core and TUN settings, IPv6 rule and final REJECT fallback
- 50 failed TUN-initialization FD cycles, forced FD-number reuse, and GC
- caller FD preservation and terminal fresh-process restart requirement
- real synthetic IPv4 TCP/UDP TUN packets through gVisor and Shadowsocks AEAD
- HTTP response, UDP echo, DNS hijack via encrypted proxy TCP, no external traffic
- IPv6 and ICMP packets creating no outbound socket or proxy fixture request
- protector denial producing zero successfully protected sockets and no fixture traffic

This host simulation deliberately substitutes AF_UNIX datagram sockets for an
Android VPN descriptor. It validates packet paths and native lifecycle, **not**
Android VpnService consent, protected-loopback routing, underlying network changes,
Doze, reconnect, OEM behavior, Android JNI invocation, or actual Android tunnel traffic.
The one-click app reports Connected only after a probe bound to its verified VPN
Network succeeds. Host simulation alone cannot satisfy that runtime condition.

## Pins and licenses

- [Mihomo stable release](https://github.com/MetaCubeX/mihomo/releases/tag/v1.19.31)
- [Exact source commit](https://github.com/MetaCubeX/mihomo/tree/ab405bad5beeeac8b003bb01f60f134f6df54471)
- [CMFA reference commit](https://github.com/MetaCubeX/ClashMetaForAndroid/tree/559594d4f99600d592757b23b2ee8e9b924512e4)
- [CMFA TUN bridge reference](https://github.com/MetaCubeX/ClashMetaForAndroid/blob/559594d4f99600d592757b23b2ee8e9b924512e4/core/src/main/golang/native/tun/tun.go)
- [Mihomo socket-hook contract](https://github.com/MetaCubeX/mihomo/blob/ab405bad5beeeac8b003bb01f60f134f6df54471/component/dialer/socket_hook.go)

Mihomo and CMFA are GPLv3 projects. Full upstream license copies are in notices/.
This new bridge code is GPL-3.0-or-later. CMFA was used as an API/reference source;
its native files are not vendored or compiled into this wrapper. Mihomo source is
fetched and compiled; one patch is applied. Third-party module license texts remain
with their source packages. Go modules are pinned in go.mod/go.sum.

Before distributing a GPL-linked APK, provide the matching complete corresponding
source, modifications, build scripts, dependency sources/notices and applicable
installation information. A pinned source URL alone should not be treated as a
complete source-offer plan. This work does not publish source or change repository
visibility, and does not authorize a release. See source-lock.json for archive hashes.
