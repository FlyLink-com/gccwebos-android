# Offline protocol contract v1

This contract projects a bounded set of subscription shapes into an independently validated native DTO. The [one-click candidate](one-click-vpn-connection.md) now has an Activity/owner/service/JNI connection path; this offline contract does not itself grant connection authority or establish endpoint interoperability. The 2026-10-04 BBR-only change is under validation. New native libraries, APKs and device acceptance must be verified before a BBR-capable candidate is promoted.

## Sources and provenance

- Backend renderer: `proxy-gateway-minimal/server/services/clash-subscription.mjs` at [2a166a7d](https://github.com/gccwebos-com/proxy-gateway-minimal/blob/2a166a7d7f5bcf8ba0b7e1dc7ef2a9a804533adf/server/services/clash-subscription.mjs), SHA256 `ac423a7efbc509641f20a3b678a7c81dcc1c583543a0a6074160f009b06b7175`
- Native: existing authenticated Mihomo `ab405bad5beeeac8b003bb01f60f134f6df54471`; upstream source/version pins, patch and toolchain unchanged. The local DTO/bridge source changes for BBR require freshly verified binaries
- Fixtures: `native/bridge/testdata/protocol-contract/renderer`; only documentation IPv4 addresses, `.invalid` names, fixed synthetic credentials and a parse-only Reality key. No production API, account, node or credential was used

The fixtures preserve complete ordinary Clash and Mihomo inline renderer output, including routing/groups and the extra `unified-delay`, `tcp-concurrent`, `global-client-fingerprint`, `profile` root fields. Recognition never means execution. Root DNS, DIRECT/global rules, URL-test/fallback groups, health-check URLs, listeners, profile persistence and global fingerprint are discarded. A proxy cannot inherit the discarded global fingerprint. Unknown root fields fail closed; providers are not fetched.

## Versioned sanitized DTO

The internal JSON root is exactly `schemaVersion: 1`, a single normalized `GCC_SELECTED_PROXY` in `proxies`, and `rules: ["MATCH,GCC_SELECTED_PROXY"]`. This is not a public Mihomo config. Native independently rejects unknown, missing, duplicate, case-aliased, null or mistyped fields. It constructs a fresh safe core map and owned DNS/route configuration; neither JSON nor YAML options are forwarded wholesale.

The native DTO and ordinary offline `project` require canonical IPv4 endpoints, ports 1..65535, no unspecified/multicast address, byte-bounded credentials, and non-secret errors. Candidate-only `CONNECTION_ENDPOINT_HOSTNAME` enables a separate preparation path: `validateForPreparation` validates every node with a bounded ASCII hostname, then `AndroidConfigAddressPreparer` resolves TYPE_A on the reserved physical INTERNET/NOT_VPN network and projects only the selected IPv4 address. It retains the original TLS verification name and uses a finite network lease through admission/start; that deadline is not a connected-session TTL. Ordinary debug and release keep the hostname feature disabled. IPv6 endpoints remain unsupported. Input size is 64 KiB, YAML nesting/event/node counts are bounded, and anchors, aliases, explicit tags, duplicate keys, malformed UTF-8 and multiple documents are rejected.

| Protocol | Accepted bounded shape | Defaults and constraints |
| --- | --- | --- |
| Shadowsocks | AES-128/192/256-GCM or chacha20-ietf-poly1305, password, optional boolean UDP | Absent UDP uses the existing importer contract's true; explicit false remains false. No plugin, SS2022, stream cipher or arbitrary options |
| VLESS | TCP, TLS=true, UUID, explicit servername, UDP, optional exact flow `xtls-rprx-vision`, packet encoding `xudp`/`packetaddr`, client fingerprint, Reality | Missing network normalizes to TCP; absent UDP is false like the renderer. Packet encoding requires UDP=true. Fingerprint is one of chrome/firefox/safari/ios/edge. No prefix/truncation or unknown-encoding fallback |
| VLESS Reality | Above plus complete `reality-opts` with public-key and short-id, explicit per-node client fingerprint | Canonical unpadded URL-base64 32-byte key; even hex short ID 0..16 characters. Partial Reality cannot downgrade to ordinary TLS. Parse-only keys do not prove a working identity |
| Hysteria2 | Password, explicit SNI, optional ALPN, up/down, paired salamander obfs/password, optional bbr-profile | Quoted positive integer Mbps or canonical `N Mbps`, normalized to decimal Mbps, 1..10000. BBR profile is exactly conservative/standard/aggressive. UDP is intrinsic; no UDP override |
| TUIC v5 | UUID/password, explicit SNI, optional ALPN, congestion-controller cubic/new_reno/bbr, relay-mode native/quic, optional bbr-profile and request-timeout | Token is rejected so v4 cannot be selected. BBR profile is exactly conservative/standard/aggressive; it never adds or rewrites congestion-controller. Request-timeout is a strict integer 0..30000 milliseconds, a client compatibility bound; pinned v5 does not consume it |

`bbr-profile` is optional and presence-sensitive: absence remains omitted; null, empty, whitespace-padded, case-aliased, unknown and non-string values are rejected. Only HY2/TUIC may carry it. SS/VLESS, unknown root/nested keys and any other unsupported node option still fail closed. Kotlin validates all nodes in offline projection and hostname preparation, including unselected nodes; native must independently enforce the same three-value string contract rather than relying on permissive upstream decoding.

`request-timeout` is optional only on TUIC v5. Missing remains omitted and an explicit zero is preserved; Kotlin and native independently reject null, strings, floats, booleans, aliases, duplicate keys and integers outside 0..30000. This range is a client policy, not an upstream limit. The pinned [TUIC adapter](https://github.com/MetaCubeX/mihomo/blob/ab405bad5beeeac8b003bb01f60f134f6df54471/adapter/outbound/tuic.go#L271) uses this option only in its token/v4 branch; the UUID/password v5 branch does not consume it. The compatible DTO field adds no v5 timeout behavior and does not open v4 or change authentication/TLS. All other timing/stream options remain rejected.

Pinned Mihomo calls the BBR controller for HY2. TUIC preserves the supplied controller or its existing missing-field default: the BBR profile is used by the BBR branch, while cubic/new_reno/default do not switch to BBR because a profile is present. Missing profile retains the pinned default BBR configuration. These facts come from exact-commit [HY2](https://github.com/MetaCubeX/mihomo/blob/ab405bad5beeeac8b003bb01f60f134f6df54471/adapter/outbound/hysteria2.go#L216), [TUIC](https://github.com/MetaCubeX/mihomo/blob/ab405bad5beeeac8b003bb01f60f134f6df54471/adapter/outbound/tuic.go#L104), [controller dispatch](https://github.com/MetaCubeX/mihomo/blob/ab405bad5beeeac8b003bb01f60f134f6df54471/transport/tuic/common/congestion.go#L14) and [profile definitions](https://github.com/MetaCubeX/mihomo/blob/ab405bad5beeeac8b003bb01f60f134f6df54471/transport/tuic/congestion_v2/bbr_sender.go#L86). BBR tuning does not add certificate pinning, disable verification or change authentication.

Every TLS protocol rejects `skip-cert-verify:true` and emits explicit false. TUIC `disable-sni` is rejected entirely because true disables verification in the pinned core. SNI/servername must be an ASCII DNS-shaped name, 1..253 characters with 1..63-character labels; trailing dots, wildcard names and URLs are rejected. ALPN is 1..4 distinct printable ASCII identifiers of 1..32 bytes. UUIDs must be canonical hyphenated, non-nil values. Passwords are 1..1024 UTF-8 bytes, with no NUL.

## Deliberately unsupported shapes

- Hostname endpoints in ordinary offline projection or with the candidate feature disabled; IPv6/IPv4-mapped-IPv6 endpoints; unspecified/multicast IPv4; provider-only/remote providers
- VLESS non-TLS, missing verification name, WebSocket/HTTP2/gRPC/XHTTP/other transports, unsupported flow/packet encoding/fingerprint, partial Reality or Reality without per-node fingerprint
- Hysteria2 port lists/hopping, hop-interval, invalid or alternative bandwidth units, incomplete/unknown obfs, certificate pin/files, ECH, realm, other QUIC/window/timeout tuning
- TUIC token/v4, disable-SNI, reduce-RTT/fast-open, request/heartbeat timeout, stream/window/MTU limits, UDP-over-stream, any BBR profile outside the exact three-value contract and any other tuning outside the table
- TLS verification bypass, certificates/private keys, plugins, smux, interface/routing/dialer overrides, unknown fields and cross-protocol fields
- Any subscription mixing a supported node with one of these shapes, even when index 0 is supported

These become the explicit unsupported-configuration import state. Public diagnostics use only fixed enum categories and fixed UI text, never raw option names, values or exception causes. Unknown keys remain generic. Unsupported nodes are not silently skipped, coerced or described as connected.

## Selection boundary

Import validates **all** nodes. The legacy offline preview identifier `:offline-preview-index-0` is not a stable line ID, recommendation or activation authority. The current one-click gateway uses process-local opaque selection and a fresh subscription read for each attempt, with explicit selection for multiple nodes or changed content. A single-node first attempt uses another fresh read before preparation. Cached bytes, an import preview and native RUNNING never substitute for owner/generation/capability/network admission and the bound traffic probe. BBR support does not change these fences or create cached activation.

## Verification

`ProtocolContractTest` compares actual Kotlin projection against shared synthetic golden JSON, and in CI exports those actual bytes to `.build-output/protocol-contract`. Native tests consume exactly that directory and validate both the strict DTO and pinned core construction without opening TUN or connecting. Committed goldens remain useful for standalone Go diagnostics. Core parsing is not a handshake or end-to-end interoperability test.

The BBR change adds eight synthetic renderer variants and sixteen shared golden DTOs: all three profiles for HY2/TUIC plus TUIC cubic and missing-controller cases. The unmodified pinned renderer produces Clash and Mihomo output; Kotlin must match the independently specified DTO, and native tests consume the actual exported Kotlin bytes. Existing minimal DTOs remain unchanged.

Request-timeout compatibility adds four synthetic TUIC v5 renderer inputs for 0, 1, 8000 and 30000 milliseconds, producing eight additional Clash/Mihomo DTOs. All 48 actual Kotlin projections are consumed by native tests; the prior 40 golden outputs remain byte-identical.

On 2026-10-04, standalone Kotlin/JUnit BBR tests produced a behavior RED (8 tests, 6 failures) before implementation. The subsequent focused suite passed 65 tests, including BBR, diagnostic categories, shared contracts, hostname preparation and rejection boundaries. Root subsequently reported Gradle focused tests at 298 tests with zero failures/errors/skips, normal-candidate Android-test compilation and lint passing. These are Kotlin/build checks; native rebuild and new BBR device results remain pending and must be recorded independently.

The native workflow has a source-identity-checked library reuse path with a fresh-build fallback. BBR changes the native DTO/bridge and projector boundary, so old JNI libraries cannot be accepted as BBR-capable artifacts. Rebuild both ABIs, verify their new raw/package digests, run loopback host tests and fresh API33 JNI/UI acceptance without skipping native load. The ordinary workflow separately requires Gradle/JUnit/lint and API36; physical one-click and network-lease gates remain required for the candidate. No historical binary or test result establishes acceptance of this new bridge.
