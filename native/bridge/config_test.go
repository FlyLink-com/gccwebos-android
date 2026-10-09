// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	MC "github.com/metacubex/mihomo/constant"
	LC "github.com/metacubex/mihomo/listener/config"
	"github.com/metacubex/mihomo/listener/sing_tun"
	"github.com/metacubex/mihomo/tunnel"
	"golang.org/x/sys/unix"
	"net/netip"
	"os"
	"runtime"
	"strings"
	"testing"
)

const fixtureConfig = `{"schemaVersion":2,"proxies":[{"name":"GCC_SELECTED_PROXY","type":"ss","server":"127.0.0.1","port":18388,"cipher":"aes-128-gcm","password":"lab-only-password","udp":true}],"rules":["MATCH,GCC_SELECTED_PROXY"],"routingMode":"global"}`

func TestStrictConfig(t *testing.T) {
	if _, err := parseInput([]byte(fixtureConfig)); err != nil {
		t.Fatal(err)
	}
	tests := []string{
		``, `[]`, `null`, `{}`, fixtureConfig + `{}`, strings.Repeat("a", 65537),
		strings.Replace(fixtureConfig, `"proxies":`, `"external-controller":"127.0.0.1:9090","proxies":`, 1),
		strings.Replace(fixtureConfig, `"rules":`, `"proxy-providers":{},"rules":`, 1),
		strings.Replace(fixtureConfig, `"rules":`, `"rule-providers":{},"rules":`, 1),
		strings.Replace(fixtureConfig, `"rules":`, `"listeners":[],"rules":`, 1),
		strings.Replace(fixtureConfig, `"rules":`, `"script":{},"rules":`, 1),
		strings.Replace(fixtureConfig, `"rules":`, `"geodata-mode":true,"rules":`, 1),
		strings.Replace(fixtureConfig, `"rules":`, `"external-ui":"file","rules":`, 1),
		strings.Replace(fixtureConfig, `"port":18388`, `"port":18388,"port":18389`, 1),
		strings.Replace(fixtureConfig, `"udp":true`, `"udp":true,"plugin":"v2ray-plugin"`, 1),
		strings.Replace(fixtureConfig, `"server":"127.0.0.1"`, `"server":"example.org"`, 1),
		strings.Replace(fixtureConfig, `"port":18388`, `"port":0`, 1),
		strings.Replace(fixtureConfig, `"cipher":"aes-128-gcm"`, `"cipher":"none"`, 1),
		strings.Replace(fixtureConfig, `MATCH,GCC_SELECTED_PROXY`, `MATCH,DIRECT`, 1),
	}
	for i, data := range tests {
		if _, err := parseInput([]byte(data)); err == nil {
			t.Errorf("case %d accepted", i)
		}
	}
}
func TestControlledCoreConfig(t *testing.T) {
	in, err := parseInput([]byte(fixtureConfig))
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := makeConfig(in)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Controller.ExternalController != "" {
		t.Fatal("controller enabled")
	}
	if cfg.General.GeoAutoUpdate || cfg.General.Tun.Enable || cfg.General.Port != 0 || cfg.General.SocksPort != 0 || cfg.General.MixedPort != 0 || cfg.General.AllowLan {
		t.Fatal("uncontrolled ingress enabled")
	}
	if cfg.DNS.Listen != "" || !cfg.DNS.Enable || len(cfg.DNS.NameServer) != 1 || cfg.DNS.NameServer[0].ProxyName != "GCC_SELECTED_PROXY" {
		t.Fatalf("DNS not pinned through proxy: %#v", cfg.DNS)
	}
	if cfg.NTP.Enable || len(cfg.RuleProviders) > 0 || len(cfg.Listeners) > 0 {
		t.Fatal("unexpected background activity")
	}
}
func TestInvalidFdPreservesSource(t *testing.T) {
	f, err := os.CreateTemp(t.TempDir(), "invalid-tun")
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if got := startEngine([]byte(fixtureConfig), t.TempDir(), -1); got != "INVALID_TUN_FD" {
		t.Fatal(got)
	}
	if got := startEngine([]byte(fixtureConfig), "relative", int(f.Fd())); got != "INVALID_HOME" {
		t.Fatal(got)
	}
	if _, err := f.Write([]byte("still-open")); err != nil {
		t.Fatal(err)
	}
}

func TestOwnedFdFailureClosesOnlyDuplicate(t *testing.T) {
	for i := 0; i < 50; i++ {
		f, err := os.CreateTemp(t.TempDir(), "source-fd")
		if err != nil {
			t.Fatal(err)
		}
		dup, err := unix.FcntlInt(f.Fd(), unix.F_DUPFD_CLOEXEC, 3)
		if err != nil {
			t.Fatal(err)
		}
		_, err = sing_tun.NewWithOwnedFD(LC.Tun{Enable: true, Device: "gcc-test", Stack: MC.TUNStack(255), FileDescriptor: dup, MTU: 1500, Inet4Address: []netip.Prefix{netip.MustParsePrefix("172.19.0.1/30")}, DisableICMPForwarding: true}, tunnel.Tunnel)
		if err == nil {
			t.Fatal("invalid stack accepted")
		}
		if _, err = unix.FcntlInt(uintptr(dup), unix.F_GETFD, 0); err != unix.EBADF {
			t.Fatalf("transferred duplicate not closed: %v", err)
		}
		// Force the freed descriptor number to be reused. Caller owns this new descriptor,
		// and no delayed failure cleanup/finalizer may close it.
		reused, err := unix.FcntlInt(f.Fd(), unix.F_DUPFD_CLOEXEC, dup)
		if err != nil {
			t.Fatal(err)
		}
		if reused != dup {
			t.Fatalf("expected reuse %d got %d", dup, reused)
		}
		runtime.GC()
		if _, err = unix.FcntlInt(uintptr(reused), unix.F_GETFD, 0); err != nil {
			t.Fatalf("reused fd closed: %v", err)
		}
		unix.Close(reused)
		if _, err = f.Write([]byte("caller-still-owns-source")); err != nil {
			t.Fatal(err)
		}
		f.Close()
	}
}

func TestTunSafetyDefaults(t *testing.T) {
	options := tunOptions(123)
	if !options.DisableICMPForwarding || options.AutoRoute || options.AutoDetectInterface || options.MTU != 1500 || options.FileDescriptor != 123 || options.Stack != MC.TunGvisor {
		t.Fatal("unsafe TUN defaults")
	}
	if options.Inet4Address[0].String() != "172.19.0.1/30" || options.Inet6Address[0].String() != "fdfe:dcba:9876::1/126" {
		t.Fatal("TUN address mismatch")
	}
	in, _ := parseInput([]byte(fixtureConfig))
	cfg, err := makeConfig(in)
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.Rules) != 3 || cfg.Rules[0].Adapter() != "REJECT" || cfg.Rules[1].Adapter() != "GCC_SELECTED_PROXY" || cfg.Rules[2].Adapter() != "REJECT" {
		t.Fatal("missing fail-closed IPv6 and final REJECT rules")
	}
}

func TestRuleModeLanDirect(t *testing.T) {
	rule := strings.Replace(fixtureConfig, `"routingMode":"global"`, `"routingMode":"rule"`, 1)
	in, err := parseInput([]byte(rule))
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := makeConfig(in)
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.Rules) != 8 {
		t.Fatalf("expected 8 rules in rule mode, got %d", len(cfg.Rules))
	}
	if cfg.Rules[0].Adapter() != "REJECT" {
		t.Fatal("IPv6 REJECT must stay first")
	}
	for i := 1; i <= 5; i++ {
		if cfg.Rules[i].Adapter() != "DIRECT" {
			t.Fatalf("LAN DIRECT missing at %d: %s", i, cfg.Rules[i].Adapter())
		}
	}
	if cfg.Rules[6].Adapter() != "GCC_SELECTED_PROXY" || cfg.Rules[7].Adapter() != "REJECT" {
		t.Fatal("MATCH proxy and final REJECT missing")
	}
	// Unknown mode fails closed to global (3 rules).
	unknown := strings.Replace(fixtureConfig, `"routingMode":"global"`, `"routingMode":"bypass-all"`, 1)
	in, err = parseInput([]byte(unknown))
	if err != nil {
		t.Fatal(err)
	}
	cfg, err = makeConfig(in)
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.Rules) != 3 || cfg.Rules[1].Adapter() != "GCC_SELECTED_PROXY" {
		t.Fatal("unknown routingMode must fail closed to global")
	}
	// Missing routingMode also fail-closed global.
	missing := strings.Replace(fixtureConfig, `,"routingMode":"global"`, "", 1)
	in, err = parseInput([]byte(missing))
	if err != nil {
		t.Fatal(err)
	}
	cfg, err = makeConfig(in)
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.Rules) != 3 {
		t.Fatal("missing routingMode must fail closed to global")
	}
}
