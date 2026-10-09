// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"encoding/json"
	"reflect"
	"strings"
	"testing"
)

func TestNativeBBRProfilesRoundTrip(t *testing.T) {
	beforeProtected, beforeRejected := protectedSockets.Load(), rejectedSockets.Load()
	for _, kind := range []string{"hysteria2", "tuic"} {
		base, err := parseInput(protocolDocument(protocolTestProxies[kind]))
		if err != nil {
			t.Fatal("synthetic base contract rejected")
		}
		baseMap, err := safeProxyMap(base.Proxies[0])
		if err != nil {
			t.Fatal("synthetic base map rejected")
		}
		for _, profile := range []string{"conservative", "standard", "aggressive"} {
			t.Run(kind+"/"+profile, func(t *testing.T) {
				in, err := parseInput(changedProxy(t, kind, "bbr-profile", profile, false))
				if err != nil {
					t.Fatal("exact BBR profile rejected")
				}
				out, err := safeProxyMap(in.Proxies[0])
				if err != nil || out["bbr-profile"] != profile || in.SchemaVersion != 2 {
					t.Fatal("BBR profile or schema changed")
				}
				delete(out, "bbr-profile")
				if !reflect.DeepEqual(baseMap, out) {
					t.Fatal("BBR profile changed another protocol field")
				}
				cfg, err := makeConfig(in)
				if err != nil {
					t.Fatal("BBR profile rejected by pinned core")
				}
				assertOfflineControlledConfig(t, in, cfg)
			})
		}
	}
	if protectedSockets.Load() != beforeProtected || rejectedSockets.Load() != beforeRejected || active.Load() {
		t.Fatal("offline BBR validation attempted a socket or activated the engine")
	}
}

func TestNativeBBROmittedStaysOmitted(t *testing.T) {
	for _, kind := range []string{"hysteria2", "tuic"} {
		in, err := parseInput(protocolDocument(protocolTestProxies[kind]))
		if err != nil {
			t.Fatal("omitted BBR profile rejected")
		}
		out, err := safeProxyMap(in.Proxies[0])
		if err != nil {
			t.Fatal("omitted BBR profile map rejected")
		}
		if _, present := out["bbr-profile"]; present {
			t.Fatal("omitted BBR profile was invented")
		}
		encoded, err := json.Marshal(in.Proxies[0])
		if err != nil || strings.Contains(string(encoded), `"bbr-profile"`) {
			t.Fatal("nil BBR profile was serialized")
		}
	}
}

func TestNativeBBRTuicControllerIsNotInventedOrOverridden(t *testing.T) {
	for _, controller := range []string{"", "cubic", "new_reno", "bbr"} {
		for _, profile := range []string{"conservative", "standard", "aggressive"} {
			t.Run("controller-"+controller+"/"+profile, func(t *testing.T) {
				var proxy map[string]any
				if json.Unmarshal([]byte(protocolTestProxies["tuic"]), &proxy) != nil {
					t.Fatal("synthetic TUIC fixture invalid")
				}
				proxy["bbr-profile"] = profile
				if controller != "" {
					proxy["congestion-controller"] = controller
				}
				encoded, err := json.Marshal(proxy)
				if err != nil {
					t.Fatal("synthetic TUIC fixture encoding failed")
				}
				in, err := parseInput(protocolDocument(string(encoded)))
				if err != nil {
					t.Fatal("strict TUIC profile rejected")
				}
				out, err := safeProxyMap(in.Proxies[0])
				if err != nil || out["bbr-profile"] != profile {
					t.Fatal("strict TUIC profile lost")
				}
				value, present := out["congestion-controller"]
				if present != (controller != "") || (present && value != controller) {
					t.Fatal("BBR profile changed the TUIC congestion controller")
				}
				cfg, err := makeConfig(in)
				if err != nil {
					t.Fatal("TUIC profile rejected by pinned core")
				}
				assertOfflineControlledConfig(t, in, cfg)
			})
		}
	}
}

func TestNativeBBRRejectsInvalidValuesAndAliases(t *testing.T) {
	values := []any{nil, "", "unknown", "Standard", "STANDARD", " standard", "standard ", "standard\n", false, 1, 1.5, []string{"standard"}, map[string]any{"profile": "standard"}}
	for _, kind := range []string{"hysteria2", "tuic"} {
		for _, value := range values {
			assertRejected(t, changedProxy(t, kind, "bbr-profile", value, false))
		}
		for _, key := range []string{"BBR-PROFILE", "Bbr-Profile", "bbr_profile", "bbr-profile "} {
			assertRejected(t, changedProxy(t, kind, key, "standard", false))
		}
	}
}

func TestNativeBBRDuplicateFieldsAreRejected(t *testing.T) {
	for _, kind := range []string{"hysteria2", "tuic"} {
		base := protocolTestProxies[kind]
		for _, duplicate := range []string{
			`,"bbr-profile":"standard","bbr-profile":"aggressive"`,
			`,"bbr-profile":"standard","bbr-\u0070rofile":"aggressive"`,
		} {
			assertRejected(t, protocolDocument(strings.TrimSuffix(base, "}")+duplicate+"}"))
		}
	}
}

func TestNativeBBRCrossProtocolAndUnsafeTuningRemainRejected(t *testing.T) {
	for _, kind := range []string{"ss", "vless"} {
		for _, profile := range []string{"conservative", "standard", "aggressive"} {
			assertRejected(t, changedProxy(t, kind, "bbr-profile", profile, false))
		}
	}
	for _, kind := range []string{"hysteria2", "tuic"} {
		for _, key := range []string{"cwnd", "initial-stream-receive-window", "max-stream-receive-window", "initial-connection-receive-window", "max-connection-receive-window", "recv-window", "recv-window-conn", "disable-mtu-discovery", "max-datagram-frame-size", "ports", "hop-interval", "token", "disable-sni", "fast-open"} {
			data := changedProxy(t, kind, key, 1, false)
			data = []byte(strings.Replace(string(data), `"port":443`, `"port":443,"bbr-profile":"standard"`, 1))
			assertRejected(t, data)
		}
		data := changedProxy(t, kind, "skip-cert-verify", true, false)
		data = []byte(strings.Replace(string(data), `"port":443`, `"port":443,"bbr-profile":"standard"`, 1))
		assertRejected(t, data)
	}
}

func TestNativeBBRTypedCallersAreRevalidated(t *testing.T) {
	for _, kind := range []string{"hysteria2", "tuic"} {
		for _, value := range []string{"", "unknown", "Standard", "standard "} {
			var in inputConfig
			if json.Unmarshal(changedProxy(t, kind, "bbr-profile", value, false), &in) != nil {
				t.Fatal("synthetic typed input decode failed")
			}
			if _, err := safeProxyMap(in.Proxies[0]); err != errConfig {
				t.Fatal("typed input bypassed strict BBR profile validation")
			}
			if _, err := makeConfig(in); err != errConfig {
				t.Fatal("makeConfig bypassed strict BBR profile validation")
			}
		}
	}
	for _, kind := range []string{"ss", "vless"} {
		var in inputConfig
		if json.Unmarshal(changedProxy(t, kind, "bbr-profile", "standard", false), &in) != nil {
			t.Fatal("synthetic cross-protocol typed input decode failed")
		}
		if _, err := safeProxyMap(in.Proxies[0]); err != errConfig {
			t.Fatal("typed input bypassed cross-protocol BBR rejection")
		}
	}
}
