# Offline protocol contract v1

This slice safely projects a bounded set of subscription shapes. It does **not** enable production VPN, establish protocol interoperability, authorize a real node connection, or change backend recovery behavior. The production connection button and service gate stay closed. Existing native lab tests use only synthetic loopback SS fixtures.

## Sources and provenance

- Backend renderer: `proxy-gateway-minimal/server/services/clash-subscription.mjs` at [2a166a7d](https://github.com/gccwebos-com/proxy-gateway-minimal/blob/2a166a7d7f5bcf8ba0b7e1dc7ef2a9a804533adf/server/services/clash-subscription.mjs), SHA256 `ac423a7efbc509641f20a3b678a7c81dcc1c583543a0a6074160f009b06b7175`
- Native: existing authenticated Mihomo `ab405bad5beeeac8b003bb01f60f134f6df54471`; source lock, patch and toolchain unchanged
- Fixtures: `native/bridge/testdata/protocol-contract/renderer`; only documentation IPv4 addresses, `.invalid` names, fixed synthetic credentials and a parse-only Reality key. No production API, account, node or credential was used

The fixtures preserve complete ordinary Clash and Mihomo inline renderer output, including routing/groups and the extra `unified-delay`, `tcp-concurrent`, `global-client-fingerprint`, `profile` root fields. Recognition never means execution. Root DNS, DIRECT/global rules, URL-test/fallback groups, health-check URLs, listeners, profile persistence and global fingerprint are discarded. A proxy cannot inherit the discarded global fingerprint. Unknown root fields fail closed; providers are not fetched.

## Versioned sanitized DTO

The internal JSON root is exactly `schemaVersion: 1`, a single normalized `GCC_SELECTED_PROXY` in `proxies`, and `rules: ["MATCH,GCC_SELECTED_PROXY"]`. This is not a public Mihomo config. Native independently rejects unknown, missing, duplicate, case-aliased, null or mistyped fields. It constructs a fresh safe core map and owned DNS/route configuration; neither JSON nor YAML options are forwarded wholesale.

Both layers enforce fixed canonical IPv4 endpoints, ports 1..65535, no unspecified/multicast address, byte-bounded credentials, and non-secret errors. Hostname/IPv6 endpoint resolution remains unsupported; TLS SNI is a verification name, not the endpoint resolver. Input size is 64 KiB, YAML nesting/event/node counts are bounded, and anchors, aliases, explicit tags, duplicate keys, malformed UTF-8 and multiple documents are rejected.

| Protocol | Accepted bounded shape | Defaults and constraints |
| --- | --- | --- |
| Shadowsocks | AES-128/192/256-GCM or chacha20-ietf-poly1305, password, optional boolean UDP | Absent UDP uses the existing importer contract's true; explicit false remains false. No plugin, SS2022, stream cipher or arbitrary options |
| VLESS | TCP, TLS=true, UUID, explicit servername, UDP, optional exact flow `xtls-rprx-vision`, packet encoding `xudp`/`packetaddr`, client fingerprint, Reality | Missing network normalizes to TCP; absent UDP is false like the renderer. Packet encoding requires UDP=true. Fingerprint is one of chrome/firefox/safari/ios/edge. No prefix/truncation or unknown-encoding fallback |
| VLESS Reality | Above plus complete `reality-opts` with public-key and short-id, explicit per-node client fingerprint | Canonical unpadded URL-base64 32-byte key; even hex short ID 0..16 characters. Partial Reality cannot downgrade to ordinary TLS. Parse-only keys do not prove a working identity |
| Hysteria2 | Password, explicit SNI, optional ALPN, up/down, paired salamander obfs/password | Quoted positive integer Mbps or canonical `N Mbps`, normalized to decimal Mbps, 1..10000. No silent malformed-bandwidth fallback. UDP is intrinsic; no UDP override |
| TUIC v5 | UUID/password, explicit SNI, optional ALPN, congestion-controller cubic/new_reno/bbr, relay-mode native/quic | Token is rejected so v4 cannot be selected. UDP is intrinsic. Core's pinned default timing/stream limits apply, with no unvalidated tuning forwarded |

Every TLS protocol rejects `skip-cert-verify:true` and emits explicit false. TUIC `disable-sni` is rejected entirely because true disables verification in the pinned core. SNI/servername must be an ASCII DNS-shaped name, 1..253 characters with 1..63-character labels; trailing dots, wildcard names and URLs are rejected. ALPN is 1..4 distinct printable ASCII identifiers of 1..32 bytes. UUIDs must be canonical hyphenated, non-nil values. Passwords are 1..1024 UTF-8 bytes, with no NUL.

## Deliberately unsupported shapes

- Hostname, IPv6 or IPv4-mapped-IPv6 endpoints; unspecified/multicast IPv4; provider-only/remote providers
- VLESS non-TLS, missing verification name, WebSocket/HTTP2/gRPC/XHTTP/other transports, unsupported flow/packet encoding/fingerprint, partial Reality or Reality without per-node fingerprint
- Hysteria2 port lists/hopping, hop-interval, invalid or alternative bandwidth units, incomplete/unknown obfs, certificate pin/files, ECH, realm, BBR/QUIC/window/timeout tuning
- TUIC token/v4, disable-SNI, reduce-RTT/fast-open, request/heartbeat timeout, stream/window/MTU limits, UDP-over-stream, BBR-profile and any other tuning outside the table
- TLS verification bypass, certificates/private keys, plugins, smux, interface/routing/dialer overrides, unknown fields and cross-protocol fields
- Any subscription mixing a supported node with one of these shapes, even when index 0 is supported

These become the existing explicit unsupported-configuration import state, with a generic non-secret error. They are not silently skipped, coerced or described as connected.

## Selection boundary

Import still validates **all** nodes and retains only index 0 as an offline compatibility preview. Its internal identifier now says `:offline-preview-index-0`; it is not a stable line ID, server recommendation or authorization token. No production `ConfigGateway` or `VpnService` consumes this profile. Account/resource/generation isolation, cancellation wiping and fresh copies remain unchanged. Stable identity, user selection, switching, revocation continuity and real VPN admission remain later work.

## Verification

`ProtocolContractTest` compares actual Kotlin projection against shared synthetic golden JSON, and in CI exports those actual bytes to `.build-output/protocol-contract`. Native tests consume exactly that directory and validate both the strict DTO and pinned core construction without opening TUN or connecting. Committed goldens remain useful for standalone Go diagnostics. Core parsing is not a handshake or end-to-end interoperability test.

The native workflow rebuilds the pinned core, runs existing loopback packet-path tests, builds fresh JNI/APKs and executes API33 native instrumentation. The ordinary Android workflow separately runs Gradle/JUnit/lint and API36. No old pinned APK artifact is reused for these acceptance results. Exact-head CI and independent review are required before merge; this document does not pre-claim those results.
