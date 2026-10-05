// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/metacubex/mihomo/config"
	MC "github.com/metacubex/mihomo/constant"
)

var protocolTestProxies = map[string]string{
	"ss":        `{"name":"GCC_SELECTED_PROXY","type":"ss","server":"192.0.2.10","port":443,"cipher":"aes-128-gcm","password":"synthetic-only","udp":false}`,
	"vless":     `{"name":"GCC_SELECTED_PROXY","type":"vless","server":"192.0.2.10","port":443,"uuid":"11111111-1111-4111-8111-111111111111","udp":false,"tls":true,"network":"tcp","skip-cert-verify":false,"servername":"fixture.example.invalid"}`,
	"hysteria2": `{"name":"GCC_SELECTED_PROXY","type":"hysteria2","server":"192.0.2.10","port":443,"password":"synthetic-only","skip-cert-verify":false,"sni":"fixture.example.invalid"}`,
	"tuic":      `{"name":"GCC_SELECTED_PROXY","type":"tuic","server":"192.0.2.10","port":443,"uuid":"22222222-2222-4222-8222-222222222222","password":"synthetic-only","skip-cert-verify":false,"sni":"fixture.example.invalid"}`,
}

func protocolDocument(proxy string) []byte {
	return []byte(`{"schemaVersion":1,"proxies":[` + proxy + `],"rules":["MATCH,GCC_SELECTED_PROXY"]}`)
}

func changedProxy(t *testing.T, kind, key string, value any, remove bool) []byte {
	t.Helper()
	var proxy map[string]any
	if err := json.Unmarshal([]byte(protocolTestProxies[kind]), &proxy); err != nil {
		t.Fatal(err)
	}
	if remove {
		delete(proxy, key)
	} else {
		proxy[key] = value
	}
	encoded, err := json.Marshal(proxy)
	if err != nil {
		t.Fatal(err)
	}
	return protocolDocument(string(encoded))
}

// Each file is the actual Kotlin projection of a backend renderer fixture. CI can
// additionally supply freshly exported Kotlin outputs without invoking a TUN or
// connecting to any proxy. The pinned core parses each contract independently.
func TestNativeProtocolGolden(t *testing.T) {
	dirs := []string{filepath.Join("testdata", "protocol-contract", "accepted")}
	if exported, set := os.LookupEnv("GCC_PROTOCOL_GOLDENS"); set {
		if exported == "" {
			t.Fatal("GCC_PROTOCOL_GOLDENS is set but empty")
		}
		dirs = append(dirs, exported)
	}
	var committedNames []string
	beforeProtected, beforeRejected := protectedSockets.Load(), rejectedSockets.Load()
	seen := make(map[string]bool)
	for _, dir := range dirs {
		files, err := filepath.Glob(filepath.Join(dir, "*.json"))
		if err != nil || len(files) == 0 {
			t.Fatalf("golden directory missing JSON fixtures: %s", dir)
		}
		names := make([]string, len(files))
		for i, file := range files {
			names[i] = filepath.Base(file)
		}
		if committedNames == nil {
			committedNames = names
		} else if !reflect.DeepEqual(committedNames, names) {
			t.Fatal("fresh Kotlin golden filenames/count differ from committed contract fixtures")
		}
		for _, file := range files {
			t.Run(filepath.Base(dir)+"/"+filepath.Base(file), func(t *testing.T) {
				data, err := os.ReadFile(file)
				if err != nil {
					t.Fatal(err)
				}
				in, err := parseInput(data)
				if err != nil {
					t.Fatal("Kotlin golden rejected by native contract")
				}
				seen[in.Proxies[0].Type] = true
				if in.Proxies[0].RealityOpts != nil {
					seen["reality"] = true
				}
				cfg, err := makeConfig(in)
				if err != nil {
					t.Fatal("Kotlin golden rejected by pinned core")
				}
				assertOfflineControlledConfig(t, in, cfg)
			})
		}
	}
	for _, kind := range []string{"ss", "vless", "reality", "hysteria2", "tuic"} {
		if !seen[kind] {
			t.Errorf("no golden fixture for %s", kind)
		}
	}
	if protectedSockets.Load() != beforeProtected || rejectedSockets.Load() != beforeRejected || active.Load() {
		t.Fatal("offline contract validation attempted a socket or activated the engine")
	}
}

func assertOfflineControlledConfig(t *testing.T, in inputConfig, cfg *config.Config) {
	t.Helper()
	p := cfg.Proxies["GCC_SELECTED_PROXY"]
	if p == nil {
		t.Fatal("selected proxy missing")
	}
	t.Cleanup(func() { _ = p.Close() })
	types := map[string]MC.AdapterType{"ss": MC.Shadowsocks, "vless": MC.Vless, "hysteria2": MC.Hysteria2, "tuic": MC.Tuic}
	if p.Type() != types[in.Proxies[0].Type] {
		t.Fatal("core selected wrong protocol")
	}
	if udp := in.Proxies[0].UDP; udp != nil && p.SupportUDP() != *udp {
		t.Fatal("explicit UDP value changed")
	}
	if cfg.Controller.ExternalController != "" || cfg.Controller.ExternalControllerTLS != "" || cfg.Controller.ExternalControllerUnix != "" || cfg.Controller.ExternalUIURL != "" || cfg.Controller.ExternalDohServer != "" {
		t.Fatal("management surface enabled")
	}
	if cfg.General.GeoAutoUpdate || cfg.General.Tun.Enable || cfg.General.Port != 0 || cfg.General.SocksPort != 0 || cfg.General.MixedPort != 0 || cfg.General.RedirPort != 0 || cfg.General.TProxyPort != 0 || cfg.General.AllowLan || cfg.General.IPv6 || cfg.General.Sniffing {
		t.Fatal("uncontrolled ingress or route enabled")
	}
	if cfg.DNS.Listen != "" || !cfg.DNS.Enable || cfg.DNS.IPv6 || len(cfg.DNS.NameServer) != 1 || cfg.DNS.NameServer[0].ProxyName != "GCC_SELECTED_PROXY" || len(cfg.DNS.Fallback) != 0 || len(cfg.DNS.ProxyServerNameserver) != 0 || len(cfg.DNS.DirectNameServer) != 0 {
		t.Fatal("DNS not pinned through selected proxy")
	}
	if cfg.NTP.Enable || len(cfg.RuleProviders) > 0 || len(cfg.Listeners) > 0 || cfg.Profile.StoreSelected || cfg.Profile.StoreFakeIP || cfg.IPTables.Enable {
		t.Fatal("unexpected background or persistent behavior")
	}
	if len(cfg.Rules) != 3 || cfg.Rules[0].Adapter() != "REJECT" || cfg.Rules[1].Adapter() != "GCC_SELECTED_PROXY" || cfg.Rules[2].Adapter() != "REJECT" {
		t.Fatal("fail-closed rules changed")
	}
}

func TestNativeProtocolPositiveVariants(t *testing.T) {
	for kind, proxy := range protocolTestProxies {
		t.Run(kind, func(t *testing.T) {
			in, err := parseInput(protocolDocument(proxy))
			if err != nil {
				t.Fatal(err)
			}
			cfg, err := makeConfig(in)
			if err != nil {
				t.Fatal(err)
			}
			assertOfflineControlledConfig(t, in, cfg)
		})
	}
	for _, cipher := range strings.Fields("aes-128-gcm aes-192-gcm aes-256-gcm chacha20-ietf-poly1305") {
		t.Run(cipher, func(t *testing.T) {
			in, err := parseInput(changedProxy(t, "ss", "cipher", cipher, false))
			if err != nil {
				t.Fatal(err)
			}
			cfg, err := makeConfig(in)
			if err != nil {
				t.Fatal(err)
			}
			assertOfflineControlledConfig(t, in, cfg)
		})
	}
	for _, kind := range []string{"ss", "vless"} {
		in, err := parseInput(changedProxy(t, kind, "udp", true, false))
		if err != nil {
			t.Fatal(err)
		}
		cfg, err := makeConfig(in)
		if err != nil {
			t.Fatal(err)
		}
		assertOfflineControlledConfig(t, in, cfg)
	}
}

// Default-equivalent renderer fields are validated and omitted by Kotlin. Even
// their exact defaults must remain unknown to the native DTO, for every protocol.
func TestNativeTuicClientOnlyDefaultsStayOutsideDto(t *testing.T) {
	beforeProtected, beforeRejected := protectedSockets.Load(), rejectedSockets.Load()
	fields := []struct {
		key   string
		value any
	}{
		{"disable-sni", false},
		{"reduce-rtt", false},
		{"fast-open", false},
		{"heartbeat-interval", 10000},
		{"max-udp-relay-packet-size", 1252},
		{"max-open-streams", 100},
		{"heartbeat-interval", 0},
		{"max-udp-relay-packet-size", 0},
		{"max-open-streams", 0},
	}
	for _, kind := range []string{"ss", "vless", "hysteria2", "tuic"} {
		for _, field := range fields {
			t.Run(kind+"/"+field.key, func(t *testing.T) {
				assertRejected(t, changedProxy(t, kind, field.key, field.value, false))
			})
		}
	}
	if protectedSockets.Load() != beforeProtected || rejectedSockets.Load() != beforeRejected || active.Load() {
		t.Fatal("client-only field validation attempted a socket or activated the engine")
	}
}

func TestNativeProtocolStrictRejections(t *testing.T) {
	tests := []struct {
		name, kind, key string
		value           any
		remove          bool
	}{
		{"missing_udp", "ss", "udp", nil, true},
		{"null_udp", "ss", "udp", nil, false},
		{"string_udp", "ss", "udp", "false", false},
		{"missing_password", "ss", "password", nil, true},
		{"empty_password", "ss", "password", "", false},
		{"numeric_password", "ss", "password", 123, false},
		{"nul_password", "ss", "password", "sentinel\x00secret", false},
		{"long_password", "ss", "password", strings.Repeat("é", 513), false},
		{"legacy_cipher", "ss", "cipher", "aes-256-cfb", false},
		{"stream_cipher", "ss", "cipher", "rc4-md5", false},
		{"case_key", "ss", "UDP", false, false},
		{"plugin", "ss", "plugin", "v2ray-plugin", false},
		{"plugin_opts", "ss", "plugin-opts", map[string]any{}, false},
		{"ipv6", "ss", "server", "::1", false},
		{"mapped_ipv6", "ss", "server", "::ffff:192.0.2.1", false},
		{"unspecified", "ss", "server", "0.0.0.0", false},
		{"multicast", "ss", "server", "224.0.0.1", false},
		{"hostname", "ss", "server", "fixture.example.invalid", false},
		{"leading_zero_ip", "ss", "server", "192.000.2.1", false},
		{"zero_port", "ss", "port", 0, false},
		{"large_port", "ss", "port", 65536, false},
		{"string_port", "ss", "port", "443", false},
		{"float_port", "ss", "port", 443.5, false},
		{"cross_protocol", "ss", "uuid", "11111111-1111-4111-8111-111111111111", false},
		{"missing_vless_udp", "vless", "udp", nil, true},
		{"missing_tls", "vless", "tls", nil, true},
		{"plaintext", "vless", "tls", false, false},
		{"string_tls", "vless", "tls", "true", false},
		{"missing_network", "vless", "network", nil, true},
		{"websocket", "vless", "network", "ws", false},
		{"ws_opts", "vless", "ws-opts", map[string]any{}, false},
		{"grpc", "vless", "grpc-opts", map[string]any{}, false},
		{"empty_flow", "vless", "flow", "", false},
		{"flow_suffix", "vless", "flow", "xtls-rprx-vision-udp443", false},
		{"arbitrary_uuid", "vless", "uuid", "user-secret", false},
		{"nil_uuid", "vless", "uuid", "00000000-0000-0000-0000-000000000000", false},
		{"missing_sni", "vless", "servername", nil, true},
		{"url_sni", "vless", "servername", "https://fixture.example.invalid", false},
		{"empty_label", "vless", "servername", "fixture..invalid", false},
		{"unicode_sni", "vless", "servername", "é.example.invalid", false},
		{"trailing_dot", "vless", "servername", "fixture.example.invalid.", false},
		{"long_label", "vless", "servername", strings.Repeat("a", 64) + ".invalid", false},
		{"tls_bypass", "vless", "skip-cert-verify", true, false},
		{"missing_verification", "vless", "skip-cert-verify", nil, true},
		{"missing_hy2_verification", "hysteria2", "skip-cert-verify", nil, true},
		{"missing_tuic_verification", "tuic", "skip-cert-verify", nil, true},
		{"unknown_fingerprint", "vless", "client-fingerprint", "random", false},
		{"udp_dependency", "vless", "packet-encoding", "xudp", false},
		{"encryption", "vless", "encryption", "none", false},
		{"tls_realm", "hysteria2", "realm-opts", map[string]any{"enable": true}, false},
		{"port_hopping", "hysteria2", "ports", "443,8443", false},
		{"insecure_hy2", "hysteria2", "skip-cert-verify", true, false},
		{"hy2_udp", "hysteria2", "udp", false, false},
		{"zero_bandwidth", "hysteria2", "up", "0 Mbps", false},
		{"bare_bandwidth", "hysteria2", "up", "10", false},
		{"float_bandwidth", "hysteria2", "up", "10.5 Mbps", false},
		{"wrong_unit", "hysteria2", "up", "10 Gbps", false},
		{"unbounded_bandwidth", "hysteria2", "down", "10001 Mbps", false},
		{"obfs_missing_secret", "hysteria2", "obfs", "salamander", false},
		{"obfs_missing_type", "hysteria2", "obfs-password", "synthetic-only", false},
		{"alpn_empty", "hysteria2", "alpn", []string{}, false},
		{"alpn_null", "hysteria2", "alpn", nil, false},
		{"alpn_not_array", "hysteria2", "alpn", "h3", false},
		{"alpn_duplicate", "hysteria2", "alpn", []string{"h3", "h3"}, false},
		{"alpn_control", "hysteria2", "alpn", []string{"h3\n"}, false},
		{"alpn_long", "hysteria2", "alpn", []string{strings.Repeat("a", 33)}, false},
		{"alpn_many", "hysteria2", "alpn", []string{"1", "2", "3", "4", "5"}, false},
		{"tuic_v4_token", "tuic", "token", "synthetic-only", false},
		{"tuic_sni_disabled", "tuic", "disable-sni", false, false},
		{"tuic_endpoint_override", "tuic", "ip", "192.0.2.11", false},
		{"tuic_reduce_rtt", "tuic", "reduce-rtt", true, false},
		{"tuic_insecure", "tuic", "skip-cert-verify", true, false},
		{"tuic_udp", "tuic", "udp", true, false},
		{"tuic_cc", "tuic", "congestion-controller", "arbitrary", false},
		{"tuic_relay", "tuic", "udp-relay-mode", "automatic", false},
		{"tuic_uuid", "tuic", "uuid", "00000000-0000-0000-0000-000000000000", false},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) { assertRejected(t, changedProxy(t, test.kind, test.key, test.value, test.remove)) })
	}
	for _, kind := range []string{"ss", "vless", "hysteria2", "tuic"} {
		for _, key := range []string{"certificate", "private-key", "dialer-proxy", "interface-name", "routing-mark", "proxy-providers", "providers", "listeners", "script", "geodata-mode", "external-controller"} {
			t.Run(kind+"/"+key, func(t *testing.T) { assertRejected(t, changedProxy(t, kind, key, "sentinel-secret", false)) })
		}
	}
}

func assertRejected(t *testing.T, data []byte) {
	t.Helper()
	if _, err := parseInput(data); err != errConfig || err.Error() != "CONFIG_REJECTED" {
		t.Fatal("expected fixed nonsecret CONFIG_REJECTED")
	}
}

func TestNativeContractEnvelopeAndDuplicates(t *testing.T) {
	base := string(protocolDocument(protocolTestProxies["ss"]))
	for name, input := range map[string]string{
		"version_missing":   strings.Replace(base, `"schemaVersion":1,`, "", 1),
		"version_future":    strings.Replace(base, `"schemaVersion":1`, `"schemaVersion":2`, 1),
		"version_string":    strings.Replace(base, `"schemaVersion":1`, `"schemaVersion":"1"`, 1),
		"version_float":     strings.Replace(base, `"schemaVersion":1`, `"schemaVersion":1.0`, 1),
		"version_case":      strings.Replace(base, `"schemaVersion"`, `"SchemaVersion"`, 1),
		"root_case":         strings.Replace(base, `"proxies"`, `"Proxies"`, 1),
		"proxy_case":        strings.Replace(base, `"server"`, `"Server"`, 1),
		"duplicate_root":    strings.Replace(base, `"schemaVersion":1`, `"schemaVersion":1,"schemaVersion":1`, 1),
		"duplicate_proxy":   strings.Replace(base, `"udp":false`, `"udp":false,"udp":true`, 1),
		"escaped_duplicate": strings.Replace(base, `"udp":false`, `"udp":false,"\u0075dp":true`, 1),
		"second_document":   base + base,
		"wrong_rule":        strings.Replace(base, "MATCH,GCC_SELECTED_PROXY", "MATCH,DIRECT", 1),
		"empty_rule":        strings.Replace(base, `"MATCH,GCC_SELECTED_PROXY"`, "", 1),
		"root_null":         "null", "root_array": "[]", "missing_all": "{}", "empty": "",
		"secret_unknown": strings.Replace(base, `"schemaVersion":1`, `"secret":"sentinel-secret","schemaVersion":1`, 1),
	} {
		t.Run(name, func(t *testing.T) { assertRejected(t, []byte(input)) })
	}
	assertRejected(t, append([]byte(base), 0xff))
	assertRejected(t, []byte(strings.Repeat(" ", maxConfigBytes)+base))
	assertRejected(t, []byte(strings.Repeat("[", 10)+strings.Repeat("]", 10)))
}

func TestNativeRealityCrossFields(t *testing.T) {
	var base map[string]any
	if err := json.Unmarshal([]byte(protocolTestProxies["vless"]), &base); err != nil {
		t.Fatal(err)
	}
	base["client-fingerprint"] = "chrome"
	for name, reality := range map[string]any{
		"short_key":        map[string]any{"public-key": "AA"},
		"padded_key":       map[string]any{"public-key": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE="},
		"noncanonical_key": map[string]any{"public-key": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAF"},
		"empty_key":        map[string]any{"public-key": ""},
		"missing_short_id": map[string]any{"public-key": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE"},
		"missing_key":      map[string]any{"short-id": "00"},
		"odd_id":           map[string]any{"public-key": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE", "short-id": "0"},
		"long_id":          map[string]any{"public-key": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE", "short-id": "001122334455667788"},
		"nonhex_id":        map[string]any{"public-key": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE", "short-id": "GG"},
		"unknown_key":      map[string]any{"public-key": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE", "support-x25519mlkem768": true},
		"wrong_case":       map[string]any{"Public-key": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE"},
		"null":             nil,
	} {
		t.Run(name, func(t *testing.T) {
			base["reality-opts"] = reality
			data, err := json.Marshal(base)
			if err != nil {
				t.Fatal(err)
			}
			assertRejected(t, protocolDocument(string(data)))
		})
	}
	base["reality-opts"] = map[string]any{"public-key": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE", "short-id": ""}
	encoded, _ := json.Marshal(base)
	in, err := parseInput(protocolDocument(string(encoded)))
	if err != nil {
		t.Fatal("valid empty short-id rejected")
	}
	cfg, err := makeConfig(in)
	if err != nil {
		t.Fatal(err)
	}
	assertOfflineControlledConfig(t, in, cfg)
	duplicate := strings.Replace(string(encoded), `"short-id":""`, `"short-id":"","short-id":"00"`, 1)
	assertRejected(t, protocolDocument(duplicate))
	delete(base, "client-fingerprint")
	encoded, _ = json.Marshal(base)
	assertRejected(t, protocolDocument(string(encoded)))
}

func TestNativeSafeMapIsFreshAndMakeConfigRevalidates(t *testing.T) {
	in, err := parseInput(protocolDocument(protocolTestProxies["hysteria2"]))
	if err != nil {
		t.Fatal(err)
	}
	in.Proxies[0].ALPN = []string{"h3"}
	first, err := safeProxyMap(in.Proxies[0])
	if err != nil {
		t.Fatal(err)
	}
	second, err := safeProxyMap(in.Proxies[0])
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(first, second) || first["skip-cert-verify"] != false {
		t.Fatal("safe map lost TLS verification")
	}
	first["server"] = "203.0.113.99"
	first["alpn"].([]string)[0] = "changed"
	if in.Proxies[0].ALPN[0] != "h3" || second["alpn"].([]string)[0] != "h3" || second["server"] != "192.0.2.10" {
		t.Fatal("safe map aliases input or another config")
	}
	in.SchemaVersion = 2
	if _, err := makeConfig(in); err != errConfig {
		t.Fatal("makeConfig bypassed version validation")
	}
	in.SchemaVersion = 1
	in.Proxies[0].Server = "0.0.0.0"
	if _, err := makeConfig(in); err != errConfig {
		t.Fatal("makeConfig bypassed endpoint validation")
	}
	if _, err := makeConfig(inputConfig{}); err != errConfig {
		t.Fatal("empty typed input accepted")
	}
}

func TestNativeUnicodeEscapes(t *testing.T) {
	for _, value := range []string{`\ud800`, `\udfff`, `\ud800plain`, `\ud800\ud800`, `\udfff\ud800`, `prefix\udc00suffix`} {
		t.Run(value, func(t *testing.T) {
			proxy := strings.Replace(protocolTestProxies["ss"], `"synthetic-only"`, `"`+value+`"`, 1)
			assertRejected(t, protocolDocument(proxy))
		})
	}
	for value, want := range map[string]string{`\ud83d\ude00`: "😀", `\\ud800`: `\ud800`, `literal-�`: "literal-�", `\u0061`: "a"} {
		t.Run(value, func(t *testing.T) {
			proxy := strings.Replace(protocolTestProxies["ss"], `"synthetic-only"`, `"`+value+`"`, 1)
			in, err := parseInput(protocolDocument(proxy))
			if err != nil {
				t.Fatal("valid Unicode string rejected")
			}
			if *in.Proxies[0].Password != want {
				t.Fatal("valid Unicode credential changed")
			}
		})
	}
}
