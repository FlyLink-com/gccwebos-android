// SPDX-License-Identifier: GPL-3.0-or-later
package main

/*
#include <stdlib.h>
#include "callbacks.h"
*/
import "C"

import (
	"errors"
	"io"
	"net/netip"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"syscall"
	"unsafe"

	"github.com/metacubex/mihomo/component/dialer"
	"github.com/metacubex/mihomo/component/process"
	"github.com/metacubex/mihomo/component/resolver"
	"github.com/metacubex/mihomo/config"
	MC "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/hub/executor"
	LC "github.com/metacubex/mihomo/listener/config"
	"github.com/metacubex/mihomo/listener/sing_tun"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
	"github.com/metacubex/mihomo/tunnel/statistic"
	logrus "github.com/sirupsen/logrus"
	"golang.org/x/sys/unix"
)

const coreVersion = "mihomo/v1.19.31@ab405bad5beeeac8b003bb01f60f134f6df54471;gcc-bridge/1"
const maxConfigBytes = 65536

var lifecycle sync.Mutex
var active atomic.Bool
var listener io.Closer
var consumed bool
var protectedSockets atomic.Uint64
var rejectedSockets atomic.Uint64
var errProtect = errors.New("SOCKET_PROTECT_FAILED")

func init() {
	// No native log payload, credentials, destinations or upstream parser error is surfaced.
	logrus.SetOutput(io.Discard)
	log.SetLevel(log.SILENT)
	dialer.DefaultSocketHook = protectSocket
}

func protectSocket(network, address string, conn syscall.RawConn) error {
	if !active.Load() {
		rejectedSockets.Add(1)
		return errProtect
	}
	ok := false
	err := conn.Control(func(fd uintptr) { ok = active.Load() && C.gcc_protect_socket(C.int(fd)) == 1 })
	if err != nil || !ok {
		rejectedSockets.Add(1)
		return errProtect
	}
	protectedSockets.Add(1)
	return nil
}

func makeConfig(input inputConfig) (*config.Config, error) {
	raw := config.DefaultRawConfig()
	raw.Mode = tunnel.Rule
	raw.IPv6 = false // IPv6 is captured by the Android TUN and rejected inside it.
	raw.AllowLan = false
	raw.BindAddress = "127.0.0.1"
	raw.LogLevel = log.SILENT
	raw.FindProcessMode = process.FindProcessOff
	raw.GeoAutoUpdate = false
	raw.GeoXUrl = config.RawGeoXUrl{}
	raw.ExternalUIURL = ""
	raw.Profile.StoreSelected = false
	raw.Profile.StoreFakeIP = false
	raw.Tun.Enable = false // We own a separate listener to receive startup errors.
	raw.Tun.AutoRoute = false
	raw.Tun.AutoDetectInterface = false
	raw.DNS.Enable = true
	raw.DNS.IPv6 = false
	raw.DNS.Listen = "" // No DNS listener socket; the TUN hijack dispatches in process.
	raw.DNS.EnhancedMode = MC.DNSMapping
	raw.DNS.UseHosts = false
	raw.DNS.UseSystemHosts = false
	raw.DNS.Fallback = nil
	raw.DNS.FallbackFilter.GeoIP = false
	raw.DNS.FallbackFilter.GeoSite = nil
	raw.DNS.FakeIPFilter = nil
	raw.DNS.NameServer = []string{"tcp://1.1.1.1:53#GCC_SELECTED_PROXY"}
	raw.DNS.DefaultNameserver = []string{"1.1.1.1"} // Literal proxy endpoint needs no bootstrap.
	raw.DNS.ProxyServerNameserver = nil
	raw.DNS.DirectNameServer = nil
	p := input.Proxies[0]
	raw.Proxy = []map[string]any{{"name": p.Name, "type": "ss", "server": p.Server, "port": p.Port, "cipher": p.Cipher, "password": p.Password, "udp": p.UDP}}
	raw.ProxyGroup = nil
	raw.Rule = []string{"IP-CIDR6,::/0,REJECT,no-resolve", "MATCH,GCC_SELECTED_PROXY", "MATCH,REJECT"}
	return config.ParseRawConfig(raw)
}

func startEngine(buf []byte, home string, fd int) (result string) {
	lifecycle.Lock()
	defer lifecycle.Unlock()
	// Mihomo has process-wide goroutines/singletons. Every successful start gets a fresh
	// Android :vpn process; stopping is terminal, never an unsafe in-process reload.
	if consumed {
		return "PROCESS_RESTART_REQUIRED"
	}
	input, err := parseInput(buf)
	if err != nil {
		return "CONFIG_REJECTED"
	}
	if fd < 0 {
		return "INVALID_TUN_FD"
	}
	if !filepath.IsAbs(home) || len(home) > 4096 {
		return "INVALID_HOME"
	}
	if st, err := os.Stat(home); err != nil || !st.IsDir() {
		return "INVALID_HOME"
	}
	dup, err := unix.FcntlInt(uintptr(fd), unix.F_DUPFD_CLOEXEC, 3)
	if err != nil {
		return "INVALID_TUN_FD"
	}
	// Native owns this duplicate until NewWithOwnedFD takes it, including errors.
	owned := true
	defer func() {
		if owned {
			_ = unix.Close(dup)
		}
		if recover() != nil {
			active.Store(false)
			result = "NATIVE_START_FAILED"
			consumed = true
		}
	}()
	MC.SetHomeDir(home)
	cfg, err := makeConfig(input)
	if err != nil {
		return "CONFIG_REJECTED"
	}
	consumed = true
	active.Store(true)
	executor.ApplyConfig(cfg, true)
	options := tunOptions(dup)
	owned = false // NewWithOwnedFD consumes the duplicate on success and failure.
	l, err := sing_tun.NewWithOwnedFD(options, tunnel.Tunnel)
	if err != nil {
		active.Store(false)
		executor.Shutdown()
		return "TUN_START_FAILED"
	}
	listener = l
	return ""
}

func tunOptions(fd int) LC.Tun {
	return LC.Tun{
		Enable: true, Device: "gcc-tun", Stack: MC.TunGvisor,
		DNSHijack: []string{"0.0.0.0:53", "[::]:53"},
		AutoRoute: false, AutoDetectInterface: false,
		Inet4Address: []netip.Prefix{netip.MustParsePrefix("172.19.0.1/30")},
		Inet6Address: []netip.Prefix{netip.MustParsePrefix("fdfe:dcba:9876::1/126")},
		MTU:          1500, FileDescriptor: fd,
		DisableICMPForwarding: true,
	}
}

func stopEngine() {
	lifecycle.Lock()
	defer lifecycle.Unlock()
	active.Store(false) // Reject all new socket creation before tearing down existing work.
	if listener != nil {
		_ = listener.Close()
		listener = nil
	}
	statistic.DefaultManager.Range(func(conn statistic.Tracker) bool {
		_ = conn.Close()
		return true
	})
	resolver.ResetConnection()
	executor.Shutdown()
}

//export GCCStart
func GCCStart(data *C.char, size C.int, home *C.char, fd C.int) *C.char {
	if size <= 0 || size > maxConfigBytes || data == nil || home == nil {
		return C.CString("CONFIG_REJECTED")
	}
	return C.CString(startEngine(C.GoBytes(unsafe.Pointer(data), size), C.GoString(home), int(fd)))
}

//export GCCStop
func GCCStop() { stopEngine() }

//export GCCVersion
func GCCVersion() *C.char { return C.CString(coreVersion) }

//export GCCFree
func GCCFree(ptr unsafe.Pointer) { C.free(ptr) }

//export GCCProtectedSockets
func GCCProtectedSockets() C.ulonglong { return C.ulonglong(protectedSockets.Load()) }

//export GCCRejectedSockets
func GCCRejectedSockets() C.ulonglong { return C.ulonglong(rejectedSockets.Load()) }

func main() {}
