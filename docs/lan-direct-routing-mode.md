# LAN DIRECT routing mode

Product-owned routing modes inside the existing select-node → global-proxy shell. Not part of the 0.4.2 signing gate.

## Modes

- **全球 (`global`, default):** today's behavior — all IPv4 traffic matches the selected proxy; IPv6 is rejected.
- **规则 (`rule`):** RFC1918 / loopback / link-local IPv4 CIDRs are `DIRECT,no-resolve`; all other IPv4 still matches the selected proxy. IPv6 `::/0 REJECT` and final `MATCH,REJECT` stay.

Subscription YAML rules, rule-providers, geox-url, proxy-groups and FindProcessMode are still discarded. Mode switch stops the tunnel and starts a new `:vpn` process; there is no Clash hot reload.

## Surfaces

- `SafeProxyConfig` schemaVersion **2** adds `routingMode` (unknown/missing → fail-closed `global`)
- `native/bridge` `makeConfig` inserts the five LAN CIDRs only in rule mode
- `AndroidRoutingModeStore` + Mine 「路由模式」 UI
- `ConnectionOwnerService.setRoutingMode` persists and reconnects when connected
