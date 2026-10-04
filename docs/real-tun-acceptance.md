# Isolated Android real-TUN acceptance

## Authorization and exact scope

This opt-in CI lane operates only a freshly created disposable GitHub-hosted API33 emulator.
It grants Android VPN permission for the test APKs, creates a real Android TUN, and sends
synthetic TCP, UDP and DNS traffic through the pinned Mihomo SS AEAD adapter to an emulator
loopback fixture. No user device, actual subscriber/account/configuration, production node,
backend mutation, material reset, purchase, persistent key, or external test server is used.

The debug service has only synthetic destination routes: `198.18.0.10/32`, `172.19.0.2/32`, and
`2001:db8::10/128`, plus its connected TUN address prefixes. `addAllowedApplication(packageName)`
limits inclusion to the target app. There are no default `0/0` or `::/0` forwarding routes.
Other applications and ordinary/default traffic are outside this acceptance scope. Passing
these tests is **not** proof of full-device leak prevention, production DNS behavior, general
Internet access, or interoperability for VLESS/Reality/Hysteria2/TUIC.

## Separation and ephemeral material

- `nativeTunAcceptance=true` also requires `nativeVpnLab=true`; both default off. Release sets
  both false and excludes the debug service/native library. Production Connect remains disabled
- Extra instrumentation sources, revoker components and loopback fixture are compiled only in
  the acceptance test APK. The pure fixture also has independent JVM tests; it is never app code
- The fixture listens only on `127.0.0.1` with an ephemeral port, per-run random password and
  proof nonce. It implements bounded SS AES-128-GCM framing, decodes only explicit test
  destinations, and synthesizes responses locally. It has **no forwarding or resolver path**
- DNS's `1.1.1.1:53` is only an encrypted inner destination, recognized and answered by that
  loopback fixture; the fixture never dials it. Android may also probe its configured TUN DNS
  address `172.19.0.2:853` for opportunistic private DNS. Exactly that destination is classified
  and rejected locally without a response or forwarding; it is never counted as a successful
  DNS proof. All other unexpected destinations/errors still fail the test. The actual test app query goes to TUN DNS
  `172.19.0.2`, exercising in-process hijack and DNS-over-proxy
- The service consumes one bounded, deadline-limited, permission-0600 fixture input from the
  app-private no-backup directory. Only loopback SS with an ephemeral-format password is
  accepted, only when the acceptance flag is enabled and the device is an emulator
- The input is deleted before startup; owned byte buffers are wiped. JVM/parser/provider
  internal copies cannot promise physical erasure. Test artifacts exclude fixture secrets

## What constitutes proof

The instrumented case verifies an Android VPN network and its narrow routes, then opens
ordinary test-app sockets to synthetic destination IPs. Those sockets never connect directly
to the fixture endpoint. The fixture must receive/decrypt the matching destinations and
per-run nonce, and the app must receive the unique TCP/UDP/DNS responses through the TUN.
Native library load or an ENGINE_RUNNING event alone does not count as traffic proof.

The same case then verifies:

1. Explicit stop removes the TUN and isolated `:vpn` process
2. A new start uses a new isolated process and new ephemeral fixture material, with fresh
   TCP/UDP/DNS packet proofs
3. Killing only the verified same-app `:vpn` PID removes the TUN; a 10-second quiet observation
   and filtered Android service dump verify no automatic or pending restart in that window
4. A separate test-APK activity uses Android's actual VPN consent transfer to trigger the
   original service's real `onRevoke` callback. That activity never establishes its own TUN.
   The standalone test-APK helper uses only platform Java, without depending on the target
   app's Kotlin runtime/classloader
5. A VPN permission result delivered after the explicit Stop handler does not start a service
   or create a TUN. To guarantee a fresh system dialog, only the disposable target app's
   previous VPN app-op is set to `ignore`; authorization is never granted through appops

Consent automation checks the real `com.android.vpndialogs` positive control and the expected
APK label. There is no production auto-consent path. Test-only revoker components must never
be distributed as a consumer feature. Platform references: [VpnService](https://developer.android.com/reference/android/net/VpnService),
[UiAutomation](https://developer.android.com/reference/android/app/UiAutomation).

## Evidence and remaining gates

`real-tun-runtime-api33.sh` runs the ordinary 40-case JNI/UI suite separately, then explicitly
selects the real-TUN test with its opt-in argument. A missing, skipped or failed test is a hard
failure. `verify_real_tun_acceptance.py` also requires the matching packet/lifecycle JSON proof.
Screenshots, instrumentation output, source/APK hashes, routes and sanitized counters are
retained in the exact-head native CI artifact. Cleanup always destroys the disposable emulator.
The separate historical immutable-APK replay script is unchanged; its provenance gate is not relaxed.

Results must be read from the exact published commit's CI, not inferred from host/JVM tests.
Production stable-line authorization, prepared-config/native integration, bounded handoff,
real-device behavior, other protocols and broader routing/DNS acceptance remain separate gates.
No client/device limit or automatic backend reset is introduced.

## Cost-bounded source-identical JNI reuse

Source-identical iterations may reuse the two native libraries from immutable artifact11304430266,
source 88fc9749978e3aa639323e8d9bbc2cb8e6f50f87, run37201117463. Its archive SHA256 is
24ddb8608c645860f4e6abbc3105974021d594f7465d8d000ac063a117080c90 and size is 211809425 bytes.
This fresh request-timeout compatibility source passed host success/deny packet tests, both ABI builds, 821 JVM tests,
185 API33 device tests including JNI, and one real-TUN traffic/lifecycle test. These are
synthetic CI results; OPPO normal-account interoperability remains a separate gate. The archive digest, repository/run identity,
entire native Git tree, Kotlin projector/JNI contract sources, and both raw library hashes must
match before extraction. Only explicit library/header/notice entries are copied; no old APK is
used. Every current app/test APK is rebuilt and its packaging, signatures, release exclusion,
JNI execution and actual TUN tests still run. The receipt separately names native source and
application source. Historical failures remain separate from this source's successful run.

The source-88fc API36 UI workflow separately failed after its 720-second suite budget: 136 passes,
one native-bridge assumption skip in the UI-only package, and 48 incomplete cases out of 185.
The final started case had at most 7.199 seconds before timeout cleanup, so this does not establish
an individual test hang. The failed API36 gate and real-account OPPO interoperability remain separate.

Changed native/projector inputs or an expired/missing artifact trigger the full original native
build/test path; corrupt or contradictory provenance fails closed. Prior host packet-path proof
is retained under reused-source-evidence, rather than presented as a new-head host execution.

## API36 startup stabilization

The separate API36 UI lane preserves first-boot logs, ANR/window state, screenshot and boot ID,
then reboots its owned disposable AVD exactly once before APK installation. It verifies the boot
ID changed, applies bounded startup deadlines, and rejects any post-warm-boot ANR/error window.
Existing APK identity, foreground, crash and instrumentation gates remain required. No dialog
is dismissed and first-boot SystemUI failures are retained rather than rewritten as app passes.
