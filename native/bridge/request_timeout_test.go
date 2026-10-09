// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"encoding/json"
	"reflect"
	"strconv"
	"strings"
	"testing"
)

// This is TUIC v5 format compatibility. The pinned v5 client does not consume
// request-timeout; these tests must not be reported as timeout behavior proof.
func TestNativeRequestTimeoutRoundTrip(t *testing.T) {
	beforeProtected, beforeRejected := protectedSockets.Load(), rejectedSockets.Load()
	t.Cleanup(func() {
		if protectedSockets.Load() != beforeProtected || rejectedSockets.Load() != beforeRejected || active.Load() {
			t.Fatal("offline compatibility validation attempted a socket or activated the engine")
		}
	})
	base, err := parseInput(protocolDocument(protocolTestProxies["tuic"]))
	if err != nil {
		t.Fatal("synthetic TUIC base contract rejected")
	}
	baseMap, err := safeProxyMap(base.Proxies[0])
	if err != nil {
		t.Fatal("synthetic TUIC base map rejected")
	}
	for _, value := range []int{0, 1, 8000, 30000} {
		t.Run(strconv.Itoa(value), func(t *testing.T) {
			in, err := parseInput(changedProxy(t, "tuic", "request-timeout", value, false))
			if err != nil {
				t.Fatal("bounded TUIC compatibility value rejected")
			}
			out, err := safeProxyMap(in.Proxies[0])
			if err != nil || out["request-timeout"] != value || in.SchemaVersion != 2 {
				t.Fatal("explicit compatibility integer or schema changed")
			}
			encoded, err := json.Marshal(in.Proxies[0])
			var fields map[string]json.RawMessage
			if err != nil || json.Unmarshal(encoded, &fields) != nil || string(fields["request-timeout"]) != strconv.Itoa(value) {
				t.Fatal("explicit compatibility integer was omitted or changed")
			}
			delete(out, "request-timeout")
			if !reflect.DeepEqual(baseMap, out) {
				t.Fatal("compatibility field changed another protocol field")
			}
			cfg, err := makeConfig(in)
			if err != nil {
				t.Fatal("bounded compatibility field rejected by pinned core construction")
			}
			assertOfflineControlledConfig(t, in, cfg)
		})
	}
}

func TestNativeRequestTimeoutOmittedStaysOmitted(t *testing.T) {
	in, err := parseInput(protocolDocument(protocolTestProxies["tuic"]))
	if err != nil {
		t.Fatal("omitted compatibility field rejected")
	}
	out, err := safeProxyMap(in.Proxies[0])
	if err != nil {
		t.Fatal("omitted compatibility field map rejected")
	}
	if _, present := out["request-timeout"]; present {
		t.Fatal("omitted compatibility field was invented")
	}
	encoded, err := json.Marshal(in.Proxies[0])
	if err != nil || strings.Contains(string(encoded), `"request-timeout"`) {
		t.Fatal("nil compatibility field was serialized")
	}
}

func TestNativeRequestTimeoutRejectsInvalidValues(t *testing.T) {
	for _, test := range []struct{ name, raw string }{
		{"negative", "-1"}, {"above_bound", "30001"}, {"null", "null"},
		{"decimal_zero", "0.0"}, {"fraction", "1.5"}, {"true", "true"}, {"false", "false"},
		{"integer_string", `"8000"`}, {"duration_string", `"8s"`}, {"empty_string", `""`},
		{"array", "[]"}, {"object", "{}"}, {"max_int", "9223372036854775807"},
		{"signed_overflow", "9223372036854775808"}, {"unsigned_overflow", "18446744073709551615"},
		{"exponent", "1e3"}, {"decimal_integer", "8000.0"},
	} {
		t.Run(test.name, func(t *testing.T) {
			proxy := strings.TrimSuffix(protocolTestProxies["tuic"], "}") + `,"request-timeout":` + test.raw + "}"
			assertRejected(t, protocolDocument(proxy))
		})
	}
}

func TestNativeRequestTimeoutDuplicateAndAliasKeysAreRejected(t *testing.T) {
	for _, test := range []struct{ name, fields string }{
		{"duplicate", `,"request-timeout":0,"request-timeout":8000`},
		{"escaped_duplicate", `,"request-timeout":0,"request-\u0074imeout":8000`},
		{"escaped_duplicate_reversed", `,"request-\u0074imeout":0,"request-timeout":8000`},
		{"upper_case", `,"REQUEST-TIMEOUT":8000`},
		{"mixed_case", `,"Request-Timeout":8000`},
		{"underscore", `,"request_timeout":8000`},
		{"trailing_space", `,"request-timeout ":8000`},
		{"nested_extra", `,"extra":{"request-timeout":8000}`},
	} {
		t.Run(test.name, func(t *testing.T) {
			assertRejected(t, protocolDocument(strings.TrimSuffix(protocolTestProxies["tuic"], "}")+test.fields+"}"))
		})
	}
}

func TestNativeRequestTimeoutSingleEscapedCanonicalKey(t *testing.T) {
	// JSON escapes decode to the same canonical key, as in the existing contract.
	proxy := strings.TrimSuffix(protocolTestProxies["tuic"], "}") + `,"request-\u0074imeout":0}`
	in, err := parseInput(protocolDocument(proxy))
	if err != nil {
		t.Fatal("single escaped canonical compatibility key rejected")
	}
	out, err := safeProxyMap(in.Proxies[0])
	if err != nil || out["request-timeout"] != 0 {
		t.Fatal("escaped canonical compatibility key lost its explicit zero")
	}
}

func TestNativeRequestTimeoutCrossProtocolIsRejected(t *testing.T) {
	for _, kind := range []string{"ss", "vless", "hysteria2"} {
		for _, value := range []int{0, 8000} {
			t.Run(kind+"/"+strconv.Itoa(value), func(t *testing.T) {
				assertRejected(t, changedProxy(t, kind, "request-timeout", value, false))
			})
		}
	}
}

func TestNativeRequestTimeoutTypedCallersAreRevalidated(t *testing.T) {
	for _, test := range []struct {
		name, kind string
		value      int
	}{
		{"negative", "tuic", -1}, {"above_bound", "tuic", 30001},
		{"ss_zero", "ss", 0}, {"ss_positive", "ss", 8000},
		{"vless_zero", "vless", 0}, {"vless_positive", "vless", 8000},
		{"hy2_zero", "hysteria2", 0}, {"hy2_positive", "hysteria2", 8000},
	} {
		t.Run(test.name, func(t *testing.T) {
			var in inputConfig
			if json.Unmarshal(changedProxy(t, test.kind, "request-timeout", test.value, false), &in) != nil {
				t.Fatal("synthetic typed input decode failed")
			}
			if _, err := safeProxyMap(in.Proxies[0]); err != errConfig {
				t.Error("typed caller bypassed compatibility validation")
			}
			cfg, err := makeConfig(in)
			if cfg != nil {
				assertOfflineControlledConfig(t, in, cfg)
			}
			if err != errConfig {
				t.Error("makeConfig bypassed compatibility validation")
			}
		})
	}
}

func TestNativeRequestTimeoutDoesNotRelaxExistingGates(t *testing.T) {
	for _, test := range []struct {
		name, key string
		value     any
		remove    bool
	}{
		{"v4_token", "token", "synthetic-only", false},
		{"missing_uuid", "uuid", nil, true},
		{"invalid_uuid", "uuid", "00000000-0000-0000-0000-000000000000", false},
		{"missing_password", "password", nil, true},
		{"missing_sni", "sni", nil, true},
		{"tls_bypass", "skip-cert-verify", true, false},
		{"missing_verification", "skip-cert-verify", nil, true},
		{"endpoint_override", "ip", "192.0.2.11", false},
		{"hostname_endpoint", "server", "fixture.example.invalid", false},
		{"disable_sni", "disable-sni", false, false},
		{"fast_open", "fast-open", false, false},
		{"other_tuning", "heartbeat-interval", 10000, false},
		{"controller_alias", "congestion-controller", "BBR", false},
		{"profile_alias", "bbr-profile", "Standard", false},
		{"unknown", "synthetic-unknown", 0, false},
	} {
		t.Run(test.name, func(t *testing.T) {
			data := changedProxy(t, "tuic", test.key, test.value, test.remove)
			data = []byte(strings.Replace(string(data), `"port":443`, `"port":443,"request-timeout":8000`, 1))
			assertRejected(t, data)
		})
	}
}
