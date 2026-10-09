// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bytes"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/netip"
	"strconv"
	"strings"
	"unicode/utf8"
)

// This is a versioned internal DTO, not a Mihomo configuration or a subscription.
// Presence-sensitive fields use pointers: missing, null, and false are distinct.
// No upstream options are forwarded without this layer's independent validation.
type inputProxy struct {
	Name                 string        `json:"name"`
	Type                 string        `json:"type"`
	Server               string        `json:"server"`
	Port                 int           `json:"port"`
	Cipher               *string       `json:"cipher,omitempty"`
	Password             *string       `json:"password,omitempty"`
	UDP                  *bool         `json:"udp,omitempty"`
	UUID                 *string       `json:"uuid,omitempty"`
	TLS                  *bool         `json:"tls,omitempty"`
	Network              *string       `json:"network,omitempty"`
	ServerName           *string       `json:"servername,omitempty"`
	ClientFingerprint    *string       `json:"client-fingerprint,omitempty"`
	Flow                 *string       `json:"flow,omitempty"`
	PacketEncoding       *string       `json:"packet-encoding,omitempty"`
	RealityOpts          *inputReality `json:"reality-opts,omitempty"`
	SNI                  *string       `json:"sni,omitempty"`
	SkipCertVerify       *bool         `json:"skip-cert-verify,omitempty"`
	ALPN                 []string      `json:"alpn,omitempty"`
	Up                   *string       `json:"up,omitempty"`
	Down                 *string       `json:"down,omitempty"`
	Obfs                 *string       `json:"obfs,omitempty"`
	ObfsPassword         *string       `json:"obfs-password,omitempty"`
	CongestionController *string       `json:"congestion-controller,omitempty"`
	UDPRelayMode         *string       `json:"udp-relay-mode,omitempty"`
	BBRProfile           *string       `json:"bbr-profile,omitempty"`
	RequestTimeout       *int          `json:"request-timeout,omitempty"`
}

type inputReality struct {
	PublicKey string  `json:"public-key"`
	ShortID   *string `json:"short-id,omitempty"`
}

type inputConfig struct {
	SchemaVersion int          `json:"schemaVersion"`
	Proxies       []inputProxy `json:"proxies"`
	Rules         []string     `json:"rules"`
	RoutingMode   string       `json:"routingMode,omitempty"`
}

var errConfig = errors.New("CONFIG_REJECTED")

var protocolFields = map[string]string{
	"ss":        "name type server port cipher password udp",
	"vless":     "name type server port uuid udp tls network servername client-fingerprint flow packet-encoding reality-opts skip-cert-verify",
	"hysteria2": "name type server port password sni skip-cert-verify alpn up down obfs obfs-password bbr-profile",
	"tuic":      "name type server port uuid password sni skip-cert-verify alpn congestion-controller udp-relay-mode bbr-profile request-timeout",
}

func parseInput(data []byte) (inputConfig, error) {
	var cfg inputConfig
	if len(data) == 0 || len(data) > maxConfigBytes || !utf8.Valid(data) || bytes.IndexByte(data, 0) >= 0 || !validJSONUnicode(data) {
		return cfg, errConfig
	}
	// Reject duplicates (including escaped-equivalent names), null, excessive
	// nesting, and multiple documents before typed decoding can lose information.
	check := json.NewDecoder(bytes.NewReader(data))
	if err := checkValue(check, 0); err != nil {
		return cfg, errConfig
	}
	if _, err := check.Token(); err != io.EOF {
		return cfg, errConfig
	}
	// encoding/json matches struct fields case-insensitively. Inspect exact key
	// spellings separately; DisallowUnknownFields alone does not close that gap.
	root, err := objectFields(data, "schemaVersion proxies rules routingMode")
	if err != nil || len(root) < 3 || len(root) > 4 {
		return cfg, errConfig
	}
	for _, key := range []string{"schemaVersion", "proxies", "rules"} {
		if _, ok := root[key]; !ok {
			return cfg, errConfig
		}
	}
	var proxies []json.RawMessage
	if json.Unmarshal(root["proxies"], &proxies) != nil || len(proxies) != 1 {
		return cfg, errConfig
	}
	var typeOnly struct {
		Type string `json:"type"`
	}
	if json.Unmarshal(proxies[0], &typeOnly) != nil {
		return cfg, errConfig
	}
	fields, ok := protocolFields[typeOnly.Type]
	if !ok {
		return cfg, errConfig
	}
	proxy, err := objectFields(proxies[0], fields)
	if err != nil {
		return cfg, errConfig
	}
	if reality, ok := proxy["reality-opts"]; ok {
		if _, err := objectFields(reality, "public-key short-id"); err != nil {
			return cfg, errConfig
		}
	}
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if dec.Decode(&cfg) != nil || validateInput(cfg) != nil {
		return inputConfig{}, errConfig
	}
	return cfg, nil
}

// encoding/json silently replaces lone escaped UTF-16 surrogates. Reject those
// before decoding, instead of changing credentials or other contract values.
// Skipping both bytes of other escapes also handles a literal \u correctly.
func validJSONUnicode(data []byte) bool {
	for i := 0; i < len(data); i++ {
		if data[i] != '\\' {
			continue
		}
		i++
		if i >= len(data) {
			return false
		}
		if data[i] != 'u' {
			continue
		}
		if i+4 >= len(data) {
			return false
		}
		value, err := strconv.ParseUint(string(data[i+1:i+5]), 16, 16)
		if err != nil {
			return false
		}
		i += 4
		if value >= 0xd800 && value <= 0xdbff {
			if i+6 >= len(data) || data[i+1] != '\\' || data[i+2] != 'u' {
				return false
			}
			low, err := strconv.ParseUint(string(data[i+3:i+7]), 16, 16)
			if err != nil || low < 0xdc00 || low > 0xdfff {
				return false
			}
			i += 6
		} else if value >= 0xdc00 && value <= 0xdfff {
			return false
		}
	}
	return true
}

func objectFields(data []byte, allowed string) (map[string]json.RawMessage, error) {
	var object map[string]json.RawMessage
	if json.Unmarshal(data, &object) != nil || object == nil {
		return nil, errConfig
	}
	for key := range object {
		if !containsWord(allowed, key) {
			return nil, errConfig
		}
	}
	return object, nil
}

func containsWord(words, value string) bool {
	for _, word := range strings.Fields(words) {
		if value == word {
			return true
		}
	}
	return false
}

func validateInput(cfg inputConfig) error {
	if cfg.SchemaVersion != 2 || len(cfg.Proxies) != 1 || len(cfg.Rules) != 1 || cfg.Rules[0] != "MATCH,GCC_SELECTED_PROXY" {
		return errConfig
	}
	_, err := safeProxyMap(cfg.Proxies[0])
	return err
}

// Unknown or empty routingMode fails closed to global; only exact "rule" enables LAN DIRECT.
func effectiveRoutingMode(cfg inputConfig) string {
	if strings.EqualFold(strings.TrimSpace(cfg.RoutingMode), "rule") {
		return "rule"
	}
	return "global"
}

// safeProxyMap creates a new map of only this contract's validated fields. It is
// also used by makeConfig, so typed callers cannot bypass the contract validator.
func safeProxyMap(p inputProxy) (map[string]any, error) {
	ip, err := netip.ParseAddr(p.Server)
	if err != nil || !ip.Is4() || ip.IsUnspecified() || ip.IsMulticast() || p.Name != "GCC_SELECTED_PROXY" || p.Port < 1 || p.Port > 65535 {
		return nil, errConfig
	}
	allowed, ok := protocolFields[p.Type]
	if p.ALPN != nil && !containsWord(allowed, "alpn") {
		return nil, errConfig
	}
	if !ok {
		return nil, errConfig
	}
	// Defend against cross-protocol fields even for an in-process typed caller.
	encoded, err := json.Marshal(p)
	if err != nil {
		return nil, errConfig
	}
	if _, err := objectFields(encoded, allowed); err != nil {
		return nil, errConfig
	}
	out := map[string]any{"name": p.Name, "type": p.Type, "server": p.Server, "port": p.Port}
	switch p.Type {
	case "ss":
		if p.Cipher == nil || !containsWord("aes-128-gcm aes-192-gcm aes-256-gcm chacha20-ietf-poly1305", *p.Cipher) || !validSecret(p.Password) || p.UDP == nil {
			return nil, errConfig
		}
		out["cipher"], out["password"], out["udp"] = *p.Cipher, *p.Password, *p.UDP
	case "vless":
		if !validUUID(p.UUID) || p.UDP == nil || p.TLS == nil || !*p.TLS || p.Network == nil || *p.Network != "tcp" || !validServerName(p.ServerName) || !verificationEnabled(p.SkipCertVerify) {
			return nil, errConfig
		}
		if p.ClientFingerprint != nil && !containsWord("chrome firefox safari ios edge", *p.ClientFingerprint) {
			return nil, errConfig
		}
		if p.Flow != nil && *p.Flow != "xtls-rprx-vision" {
			return nil, errConfig
		}
		if p.PacketEncoding != nil && (!*p.UDP || !containsWord("xudp packetaddr", *p.PacketEncoding)) {
			return nil, errConfig
		}
		if p.RealityOpts != nil {
			if p.ClientFingerprint == nil || !validReality(*p.RealityOpts) {
				return nil, errConfig
			}
			reality := map[string]any{"public-key": p.RealityOpts.PublicKey}
			if p.RealityOpts.ShortID != nil {
				reality["short-id"] = *p.RealityOpts.ShortID
			}
			out["reality-opts"] = reality
		}
		out["uuid"], out["udp"], out["tls"], out["network"] = *p.UUID, *p.UDP, true, "tcp"
		out["servername"], out["skip-cert-verify"] = *p.ServerName, false
		putOptional(out, "client-fingerprint", p.ClientFingerprint)
		putOptional(out, "flow", p.Flow)
		putOptional(out, "packet-encoding", p.PacketEncoding)
	case "hysteria2", "tuic":
		if !validSecret(p.Password) || !validServerName(p.SNI) || !verificationEnabled(p.SkipCertVerify) || !validALPN(p.ALPN) || (p.BBRProfile != nil && !containsWord("conservative standard aggressive", *p.BBRProfile)) {
			return nil, errConfig
		}
		out["password"], out["sni"], out["skip-cert-verify"] = *p.Password, *p.SNI, false
		putOptional(out, "bbr-profile", p.BBRProfile)
		if p.ALPN != nil {
			out["alpn"] = append([]string{}, p.ALPN...)
		}
		if p.Type == "hysteria2" {
			if !validBandwidth(p.Up) || !validBandwidth(p.Down) {
				return nil, errConfig
			}
			if (p.Obfs == nil) != (p.ObfsPassword == nil) || (p.Obfs != nil && (*p.Obfs != "salamander" || !validSecret(p.ObfsPassword))) {
				return nil, errConfig
			}
			putOptional(out, "up", p.Up)
			putOptional(out, "down", p.Down)
			putOptional(out, "obfs", p.Obfs)
			putOptional(out, "obfs-password", p.ObfsPassword)
		} else {
			if p.RequestTimeout != nil && (*p.RequestTimeout < 0 || *p.RequestTimeout > 30000) {
				return nil, errConfig
			}
			if !validUUID(p.UUID) || (p.CongestionController != nil && !containsWord("cubic new_reno bbr", *p.CongestionController)) || (p.UDPRelayMode != nil && !containsWord("native quic", *p.UDPRelayMode)) {
				return nil, errConfig
			}
			out["uuid"] = *p.UUID // Token is never accepted, selecting TUIC v5 only.
			putOptional(out, "congestion-controller", p.CongestionController)
			putOptional(out, "udp-relay-mode", p.UDPRelayMode)
			if p.RequestTimeout != nil {
				// Format compatibility only: pinned TUIC v5 does not consume this
				// millisecond option. Preserve explicit zero; missing stays absent.
				out["request-timeout"] = *p.RequestTimeout
			}
		}
	}
	return out, nil
}

func putOptional(out map[string]any, key string, value *string) {
	if value != nil {
		out[key] = *value
	}
}

func validSecret(value *string) bool {
	return value != nil && len(*value) >= 1 && len(*value) <= 1024 && utf8.ValidString(*value) && !strings.ContainsRune(*value, '\x00')
}

func verificationEnabled(skip *bool) bool { return skip != nil && !*skip }

func validUUID(value *string) bool {
	if value == nil || len(*value) != 36 {
		return false
	}
	for i, c := range *value {
		if i == 8 || i == 13 || i == 18 || i == 23 {
			if c != '-' {
				return false
			}
		} else if !strings.ContainsRune("0123456789abcdefABCDEF", c) {
			return false
		}
	}
	return *value != "00000000-0000-0000-0000-000000000000"
}

func validServerName(value *string) bool {
	if value == nil || len(*value) < 1 || len(*value) > 253 {
		return false
	}
	for _, label := range strings.Split(*value, ".") {
		if len(label) < 1 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, c := range label {
			if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-') {
				return false
			}
		}
	}
	return true
}

func validReality(value inputReality) bool {
	if value.ShortID == nil {
		return false
	}
	key, err := base64.RawURLEncoding.Strict().DecodeString(value.PublicKey)
	if err != nil || len(key) != 32 || base64.RawURLEncoding.EncodeToString(key) != value.PublicKey {
		return false
	}
	if value.ShortID != nil {
		if len(*value.ShortID) > 16 || len(*value.ShortID)%2 != 0 {
			return false
		}
		if _, err := hex.DecodeString(*value.ShortID); err != nil {
			return false
		}
	}
	return true
}

func validALPN(value []string) bool {
	if value == nil {
		return true
	}
	if len(value) < 1 || len(value) > 4 {
		return false
	}
	seen := make(map[string]bool)
	for _, protocol := range value {
		if len(protocol) < 1 || len(protocol) > 32 || seen[protocol] {
			return false
		}
		seen[protocol] = true
		for _, c := range protocol {
			if c < 0x21 || c > 0x7e {
				return false
			}
		}
	}
	return true
}

func validBandwidth(value *string) bool {
	if value == nil {
		return true
	}
	if !strings.HasSuffix(*value, " Mbps") {
		return false
	}
	integer := strings.TrimSuffix(*value, " Mbps")
	n, err := strconv.Atoi(integer)
	return err == nil && n >= 1 && n <= 10000 && strconv.Itoa(n) == integer
}

func checkValue(d *json.Decoder, depth int) error {
	if depth > 8 {
		return errConfig
	}
	token, err := d.Token()
	if err != nil {
		return err
	}
	if token == nil {
		return errConfig
	}
	if text, ok := token.(string); ok && strings.ContainsRune(text, '\x00') {
		return errConfig
	}
	delim, isDelim := token.(json.Delim)
	if !isDelim {
		return nil
	}
	switch delim {
	case '{':
		keys := make(map[string]bool)
		for d.More() {
			t, err := d.Token()
			if err != nil {
				return err
			}
			key, ok := t.(string)
			if !ok || keys[key] {
				return errConfig
			}
			keys[key] = true
			if err := checkValue(d, depth+1); err != nil {
				return err
			}
		}
		end, err := d.Token()
		if err != nil || end != json.Delim('}') {
			return errConfig
		}
	case '[':
		for d.More() {
			if err := checkValue(d, depth+1); err != nil {
				return err
			}
		}
		end, err := d.Token()
		if err != nil || end != json.Delim(']') {
			return errConfig
		}
	default:
		return errConfig
	}
	return nil
}
