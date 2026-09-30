// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/netip"
	"strings"
	"unicode/utf8"
)

type inputProxy struct {
	Name     string `json:"name"`
	Type     string `json:"type"`
	Server   string `json:"server"`
	Port     int    `json:"port"`
	Cipher   string `json:"cipher"`
	Password string `json:"password"`
	UDP      bool   `json:"udp"`
}
type inputConfig struct {
	Proxies []inputProxy `json:"proxies"`
	Rules   []string     `json:"rules"`
}

var errConfig = errors.New("CONFIG_REJECTED")

func parseInput(data []byte) (inputConfig, error) {
	var cfg inputConfig
	if len(data) == 0 || len(data) > maxConfigBytes || !utf8.Valid(data) || bytes.IndexByte(data, 0) >= 0 {
		return cfg, errConfig
	}
	// Token-pass rejects duplicate keys (including escaped-equivalent keys), excessive
	// nesting, and multiple JSON documents before strict typed decoding.
	check := json.NewDecoder(bytes.NewReader(data))
	if err := checkValue(check, 0); err != nil {
		return cfg, errConfig
	}
	if _, err := check.Token(); err != io.EOF {
		return cfg, errConfig
	}
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&cfg); err != nil {
		return cfg, errConfig
	}
	if len(cfg.Proxies) != 1 || len(cfg.Rules) != 1 || cfg.Rules[0] != "MATCH,GCC_SELECTED_PROXY" {
		return cfg, errConfig
	}
	p := cfg.Proxies[0]
	ip, err := netip.ParseAddr(p.Server)
	if err != nil || !ip.Is4() || ip.IsUnspecified() || ip.IsMulticast() {
		return cfg, errConfig
	}
	if !p.UDP || p.Name != "GCC_SELECTED_PROXY" || p.Type != "ss" || p.Port < 1 || p.Port > 65535 || len(p.Password) == 0 || len(p.Password) > 1024 || strings.ContainsRune(p.Password, '\x00') {
		return cfg, errConfig
	}
	switch p.Cipher {
	case "aes-128-gcm", "aes-192-gcm", "aes-256-gcm", "chacha20-ietf-poly1305":
	default:
		return cfg, errConfig
	}
	return cfg, nil
}
func checkValue(d *json.Decoder, depth int) error {
	if depth > 8 {
		return errConfig
	}
	token, err := d.Token()
	if err != nil {
		return err
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
