# Synthetic renderer contract fixtures

Source: https://github.com/gccwebos-com/proxy-gateway-minimal/blob/2a166a7d7f5bcf8ba0b7e1dc7ef2a9a804533adf/server/services/clash-subscription.mjs

Renderer was retrieved read-only through the GitHub connector. The retrieved source SHA256 is `ac423a7efbc509641f20a3b678a7c81dcc1c583543a0a6074160f009b06b7175`. The vendored test-only snapshot removes one trailing blank line; executable source is unchanged. Run `node generate.mjs` to reproduce fixtures. No production account, server configuration, credential, node, or network was used. Public IPs are documentation addresses and hostnames use `.invalid`. Synthetic Reality key is a parse fixture, not a working server identity.

Fixtures establish renderer shape, NOT acceptance by an Android projector or core, and NOT interoperability. In particular the `options`, `defaults`, `token-ambiguous`, `hostname`, and `provider-only` cases intentionally illustrate output that a bounded implementation may reject. Review proposed acceptance separately.

Four additional candidate positive variants were requested for the bounded implementation: `vless-tls` (explicit TCP/TLS/SNI and UDP false), `hysteria2-bounded-options` (Mbps rates, salamander, SNI, h3), `tuic-v5-bounded-options` (bbr, quic UDP relay, SNI, h3), and `ss-aes192`. Each uses the same unmodified pinned renderer and has its own input JSON plus Clash/Mihomo output. Actual positive acceptance must be established by the worker's tests; these are not interoperability claims.

## Exact output defaults and restrictions

- SS requires method/cipher and password. Renderer emits neither `udp` nor plugin fields. Existing Kotlin accepts three AEAD ciphers, Go also accepts aes-192-gcm; reconcile intentionally. Do not silently override explicitly supplied `udp:false` in non-renderer input.
- VLESS requires uuid. Renderer always emits `udp`, default false. `tls`, `network`, `flow`, `servername`, `packet-encoding`, `client-fingerprint` and `reality-opts` are optional. Reality object is emitted if either publicKey or shortId is present; partial objects need client validation.
- HY2 requires password. `up`/`down` are quoted strings when supplied, absent otherwise. `ports`, `hop-interval`, obfs, obfs-password, sni, skip-cert-verify, fingerprint, alpn, bbr-profile are optional. `hop-interval` is raw YAML, possibly integer, whereas pinned core Hysteria2Option uses string. Do not accept arbitrary raw scalar coercion.
- TUIC renderer requires UUID and password, but can additionally emit token. Pinned core selects v4 when token is nonempty; explicit v5 support must reject token. Tuning fields, ALPN, SNI and booleans are optional and absent unless supplied. Rejecting optional tuning in first slice is a valid advertised subset, not full renderer compatibility.
- `disable-sni:true` in pinned TUIC forcibly sets TLS InsecureSkipVerify true. Reject true even if skip-cert-verify is false. Also reject skip-cert-verify:true for a verified-TLS subset.
- The Mihomo template adds root unified-delay, tcp-concurrent, global-client-fingerprint, and profile.store-selected; recognizing and discarding these is distinct from forwarding them. VLESS omitted per-proxy fingerprint cannot inherit renderer's discarded global fingerprint implicitly.
- Provider-only templates have external URL/path/health-check controls and no inline proxies; remain unsupported. Hostname bootstrap, IPv6 endpoints, port hopping, VLESS alternate transports, certificate files, unknown keys and arbitrary download paths remain outside the bounded slice.

## Pinned core observations

Source: MetaCubeX/mihomo ab405bad5beeeac8b003bb01f60f134f6df54471, inspected locally in android-vpn-native/third_party/mihomo, source marker matches Android native/source-lock.json.

- adapter/outbound/reality.go requires raw URL-base64 public key decoding to 32 bytes; short-id is even-length hex, up to 8 bytes, and may be empty. Missing public key returns nil config rather than rejecting, so validate Reality dependencies before invoking core.
- adapter/outbound/vless.go truncates long flow strings to 16 characters and defaults unknown packet-encoding to XUDP. Strict projectors should reject unsupported flow/encoding instead of depending on these permissive behaviors.
- common/utils/mbps.go accepts integer strings as Mbps, otherwise `^(\\d+)\\s*([KMGT]?)([Bb])ps$`. Units are decimal; bits divide by 8. Invalid strings silently yield zero. Validate syntax, positive values and multiplication overflow rather than silently disabling the limit.
- TUIC default request timeout 8000 milliseconds, heartbeat interval 10000 milliseconds, max-open-streams 100, ALPN h3, max UDP relay packet size 1252 before internal overhead/clamping. HY2/TUIC support UDP inherently. These core defaults are not renderer-emitted fields.

## Suggested synthetic negative additions

Use renderer output as immutable golden baselines; separately mutate copies for unknown keys, YAML anchors/tags/duplicates, malformed UUID/Reality key/short ID, false UDP, insecure TLS flags, huge bandwidth/timeout values, wrong scalar types, mixed supported+unsupported nodes, root provider URLs and malicious file paths. Test Kotlin output in Go parser and makeConfig without opening sockets/TUN.
