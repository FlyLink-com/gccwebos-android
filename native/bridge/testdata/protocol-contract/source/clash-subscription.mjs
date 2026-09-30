function yamlQuote(value) {
  return JSON.stringify(String(value ?? ""));
}

function clashName(server, index) {
  return server.name || `proxy-${index + 1}`;
}

function text(value) {
  return String(value ?? "").trim();
}

function metadataToken(value) {
  return text(value).toLowerCase().replace(/[\s_]+/g, "-");
}

function bool(value) {
  return value === true || value === "true";
}

const GROUP_TEST_URL = "https://www.gstatic.com/generate_204";
const DEFAULT_PROVIDER_NAME = "globalcircle-pool";
const DEFAULT_PROVIDER_PATH = "./providers/globalcircle-pool.yaml";
const DEFAULT_PROVIDER_FETCH_INTERVAL = 3600;
const DEFAULT_PROVIDER_HEALTH_INTERVAL = 300;
const CONTROLLED_NODE_DOMAIN_SUFFIXES = [
  "globalcirclecat.com",
  "globalcirclecat.cloud",
  "gccwebos.com"
];
const INACTIVE_PROVIDER_STATUSES = new Set([
  "disabled",
  "maintenance",
  "draining",
  "drained",
  "degraded",
  "held",
  "hold",
  "hold-for-investigation",
  "inactive",
  "offline",
  "retired"
]);

function unique(values) {
  return [...new Set(values.filter(Boolean))];
}

function normalizedDomainHost(value) {
  const normalized = text(value).toLowerCase().replace(/\.$/, "");
  if (
    !normalized ||
    normalized === "localhost" ||
    normalized.includes(":") ||
    /^\d{1,3}(?:\.\d{1,3}){3}$/.test(normalized) ||
    /[^a-z0-9.-]/.test(normalized) ||
    normalized.includes("..")
  ) {
    return "";
  }
  const labels = normalized.split(".");
  if (labels.length < 2) {
    return "";
  }
  if (labels.some((label) => !label || label.startsWith("-") || label.endsWith("-"))) {
    return "";
  }
  return normalized;
}

function controlledNodeSuffix(host) {
  return CONTROLLED_NODE_DOMAIN_SUFFIXES.find((suffix) => host === suffix || host.endsWith(`.${suffix}`)) || "";
}

function nodeDomainProtection(servers) {
  const exact = [];
  const suffixes = [];

  for (const server of servers) {
    const host = normalizedDomainHost(server.host);
    if (!host) {
      continue;
    }
    const suffix = controlledNodeSuffix(host);
    if (!suffix) {
      continue;
    }
    exact.push(host);
    suffixes.push(suffix);
  }

  return {
    exact: unique(exact),
    suffixes: unique(suffixes)
  };
}

function nodeDomainProtectionDnsLines(servers) {
  const { exact, suffixes } = nodeDomainProtection(servers);
  const filters = unique([
    ...suffixes.map((suffix) => `+.${suffix}`),
    ...exact
  ]);
  if (filters.length === 0) {
    return [];
  }
  return [
    "dns:",
    "  enable: true",
    "  enhanced-mode: fake-ip",
    "  fake-ip-filter-mode: blacklist",
    "  fake-ip-filter:",
    ...filters.map((entry) => `    - ${yamlQuote(entry)}`)
  ];
}

function nodeDomainProtectionRuleLines(servers) {
  const { exact, suffixes } = nodeDomainProtection(servers);
  return [
    ...exact.map((host) => `  - DOMAIN,${host},DIRECT`),
    ...suffixes.map((suffix) => `  - DOMAIN-SUFFIX,${suffix},DIRECT`)
  ];
}

function normalizeProtocol(server) {
  const raw = text(server.protocol || server.clashType || server.type || "ss").toLowerCase();
  if (raw === "ss" || raw === "shadowsocks" || raw === "shadowsocks-libev" || raw === "shadowsocks-rust") {
    return "ss";
  }
  if (raw === "vless") {
    return "vless";
  }
  if (raw === "hysteria2" || raw === "hy2") {
    return "hysteria2";
  }
  if (raw === "tuic") {
    return "tuic";
  }
  throw new Error(`unsupported Clash proxy protocol: ${raw || "[blank]"}`);
}

function requireText(value, fieldName) {
  const normalized = text(value);
  if (!normalized) {
    throw new Error(`Clash proxy ${fieldName} is required`);
  }
  return normalized;
}

function requirePort(value) {
  const port = Number(value);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error("Clash proxy port must be an integer TCP port");
  }
  return port;
}

function optionalTextField(server, ...keys) {
  for (const key of keys) {
    const value = text(server[key]);
    if (value) return value;
  }
  return "";
}

function optionalRawField(server, ...keys) {
  for (const key of keys) {
    if (server[key] !== undefined && server[key] !== null && String(server[key]).trim() !== "") {
      return server[key];
    }
  }
  return null;
}

function optionalBoolField(server, ...keys) {
  for (const key of keys) {
    if (server[key] !== undefined && server[key] !== null) {
      return bool(server[key]);
    }
  }
  return null;
}

function optionalStringListField(server, ...keys) {
  for (const key of keys) {
    const value = server[key];
    if (Array.isArray(value)) {
      return value.map((entry) => text(entry)).filter(Boolean);
    }
    const normalized = text(value);
    if (normalized) {
      return [normalized];
    }
  }
  return [];
}

function optionalRealityOpts(server) {
  const source =
    server.realityOpts ||
    server["reality-opts"] ||
    server.realityOptions ||
    {};
  const publicKey = optionalTextField(source, "publicKey", "public-key");
  const shortId = optionalTextField(source, "shortId", "short-id", "shortID");
  if (!publicKey && !shortId) {
    return null;
  }
  return { publicKey, shortId };
}

function clashProxy(server, index) {
  const protocol = normalizeProtocol(server);
  const base = {
    name: clashName(server, index),
    type: protocol,
    host: requireText(server.host, "server"),
    port: requirePort(server.port),
    qualityClass: metadataToken(server.qualityClass || server.quality || server.channelQuality),
    channelRole: metadataToken(server.channelRole || server.role),
    autoEligible: optionalBoolField(server, "autoEligible", "auto-eligible") !== false
  };

  if (protocol === "ss") {
    return {
      ...base,
      cipher: requireText(server.method || server.cipher, "cipher"),
      password: requireText(server.password, "password")
    };
  }

  if (protocol === "vless") {
    return {
      ...base,
      uuid: requireText(server.uuid, "uuid"),
      udp: bool(server.udp),
      packetEncoding: optionalTextField(server, "packetEncoding", "packet-encoding"),
      tls: optionalBoolField(server, "tls"),
      serverName: optionalTextField(server, "serverName", "servername", "sni"),
      network: optionalTextField(server, "network"),
      flow: optionalTextField(server, "flow"),
      realityOpts: optionalRealityOpts(server),
      clientFingerprint: optionalTextField(server, "clientFingerprint", "client-fingerprint")
    };
  }

  if (protocol === "hysteria2") {
    return {
      ...base,
      password: requireText(server.password, "password"),
      ports: optionalTextField(server, "ports"),
      hopInterval: optionalRawField(server, "hopInterval", "hop-interval"),
      up: optionalTextField(server, "up"),
      down: optionalTextField(server, "down"),
      obfs: optionalTextField(server, "obfs"),
      obfsPassword: optionalTextField(server, "obfsPassword", "obfs-password"),
      sni: optionalTextField(server, "sni", "servername", "serverName"),
      skipCertVerify: optionalBoolField(server, "skipCertVerify", "skip-cert-verify"),
      fingerprint: optionalTextField(server, "fingerprint"),
      alpn: optionalStringListField(server, "alpn"),
      bbrProfile: optionalTextField(server, "bbrProfile", "bbr-profile")
    };
  }

  return {
    ...base,
    uuid: requireText(server.uuid, "uuid"),
    password: requireText(server.password, "password"),
    token: optionalTextField(server, "token"),
    udpRelayMode: optionalTextField(server, "udpRelayMode", "udp-relay-mode"),
    congestionController: optionalTextField(server, "congestionController", "congestion-controller"),
    bbrProfile: optionalTextField(server, "bbrProfile", "bbr-profile"),
    sni: optionalTextField(server, "sni", "servername", "serverName"),
    alpn: optionalStringListField(server, "alpn"),
    disableSni: optionalBoolField(server, "disableSni", "disable-sni"),
    reduceRtt: optionalBoolField(server, "reduceRtt", "reduce-rtt"),
    requestTimeout: optionalRawField(server, "requestTimeout", "request-timeout"),
    heartbeatInterval: optionalRawField(server, "heartbeatInterval", "heartbeat-interval"),
    maxUdpRelayPacketSize: optionalRawField(server, "maxUdpRelayPacketSize", "max-udp-relay-packet-size"),
    fastOpen: optionalBoolField(server, "fastOpen", "fast-open"),
    skipCertVerify: optionalBoolField(server, "skipCertVerify", "skip-cert-verify"),
    maxOpenStreams: optionalRawField(server, "maxOpenStreams", "max-open-streams")
  };
}

function clashServers(proxyConfig) {
  return (proxyConfig.servers || [])
    .filter((server) => server.host && server.port)
    .map(clashProxy);
}

function isActiveProviderServer(server) {
  if (
    server.enabled === false ||
    server.active === false ||
    bool(server.disabled) ||
    bool(server.maintenance)
  ) {
    return false;
  }
  const status = metadataToken(server.status || server.lifecycleStatus || server.nodeStatus || "active");
  return !INACTIVE_PROVIDER_STATUSES.has(status);
}

function clashProviderServers(proxyConfig) {
  return (proxyConfig.servers || [])
    .filter((server) => isActiveProviderServer(server))
    .filter((server) => server.host && server.port)
    .map(clashProxy);
}

function requireProviderUrl(value) {
  const normalized = text(value);
  if (!normalized) {
    throw new Error("providerUrl is required for Clash provider-mode subscription");
  }
  return normalized;
}

function providerText(value, fallback) {
  const normalized = text(value);
  return normalized || fallback;
}

function safeLifecycleText(value, maxLength = 80) {
  const normalized = text(value);
  if (!normalized || normalized.length > maxLength) {
    return "";
  }
  if (
    /https?:\/\//i.test(normalized) ||
    /\b(token|secret|password|credential|apikey|api_key|private[_-]?key)\b/i.test(normalized) ||
    /\b[A-Za-z0-9_-]{40,}={0,2}\b/.test(normalized)
  ) {
    return "";
  }
  return normalized;
}

function safeLifecycleIso(value) {
  const normalized = text(value);
  if (!normalized) {
    return "";
  }
  const date = new Date(normalized);
  if (Number.isNaN(date.getTime())) {
    return "";
  }
  return date.toISOString();
}

function lifecycleCommentLines(options = {}) {
  const lifecycle = options.lifecycle || options.subscriptionLifecycle;
  if (!lifecycle || typeof lifecycle !== "object") {
    return [];
  }

  const status = safeLifecycleText(lifecycle.status);
  const expiresAt = safeLifecycleIso(lifecycle.expiresAt || lifecycle.validUntil);
  const validUntil = safeLifecycleIso(lifecycle.validUntil || lifecycle.expiresAt);
  const lines = [];

  if (status) {
    lines.push(`# globalcircle-subscription-status: ${yamlQuote(status)}`);
  }
  if (expiresAt) {
    lines.push(`# globalcircle-subscription-expires-at: ${yamlQuote(expiresAt)}`);
  }
  if (validUntil) {
    lines.push(`# globalcircle-subscription-valid-until: ${yamlQuote(validUntil)}`);
  }

  return lines;
}

function yamlWithLifecycle(lines, options = {}) {
  const comments = lifecycleCommentLines(options);
  return [...comments, ...lines].join("\n");
}

function providerOptions(options = {}) {
  return {
    name: providerText(options.providerName, DEFAULT_PROVIDER_NAME),
    url: requireProviderUrl(options.providerUrl),
    path: providerText(options.providerPath, DEFAULT_PROVIDER_PATH),
    interval: Number.isInteger(Number(options.providerInterval))
      ? Number(options.providerInterval)
      : DEFAULT_PROVIDER_FETCH_INTERVAL,
    healthCheckUrl: providerText(options.healthCheckUrl, GROUP_TEST_URL),
    healthCheckInterval: Number.isInteger(Number(options.healthCheckInterval))
      ? Number(options.healthCheckInterval)
      : DEFAULT_PROVIDER_HEALTH_INTERVAL,
    healthCheckLazy: options.healthCheckLazy === undefined ? true : bool(options.healthCheckLazy)
  };
}

function pushOptionalText(lines, key, value) {
  if (value) {
    lines.push(`    ${key}: ${yamlQuote(value)}`);
  }
}

function pushOptionalBool(lines, key, value) {
  if (value !== null && value !== undefined) {
    lines.push(`    ${key}: ${value ? "true" : "false"}`);
  }
}

function pushOptionalRaw(lines, key, value) {
  if (value !== null && value !== undefined) {
    lines.push(`    ${key}: ${value}`);
  }
}

function pushOptionalStringList(lines, key, values) {
  if (!Array.isArray(values) || values.length === 0) {
    return;
  }
  lines.push(`    ${key}:`);
  for (const value of values) {
    lines.push(`      - ${yamlQuote(value)}`);
  }
}

function pushProxyGroup(lines, { name, type, servers, tolerance }) {
  if (!Array.isArray(servers) || servers.length === 0) {
    return false;
  }

  lines.push(
    `  - name: ${name}`,
    `    type: ${type}`,
    "    url: \"https://www.gstatic.com/generate_204\"",
    "    interval: 300"
  );
  if (tolerance !== undefined) {
    lines.push(`    tolerance: ${tolerance}`);
  }
  lines.push("    proxies:");

  for (const server of servers) {
    lines.push(`      - ${yamlQuote(server.name)}`);
  }

  return true;
}

function pushProviderGroup(lines, { name, type, providerName, tolerance }) {
  lines.push(
    `  - name: ${name}`,
    `    type: ${type}`,
    `    url: ${yamlQuote(GROUP_TEST_URL)}`,
    "    interval: 300",
    "    lazy: true"
  );
  if (tolerance !== undefined) {
    lines.push(`    tolerance: ${tolerance}`);
  }
  lines.push(
    "    use:",
    `      - ${yamlQuote(providerName)}`
  );
}

function qualityGroupServers(servers, qualityClasses) {
  const allowed = new Set(qualityClasses);
  return servers.filter((server) => allowed.has(server.qualityClass));
}

function autoGroupServers(servers) {
  return servers.filter((server) => server.autoEligible !== false);
}

function pushProxy(lines, server) {
  lines.push(
    `  - name: ${yamlQuote(server.name)}`,
    `    type: ${server.type}`,
    `    server: ${yamlQuote(server.host)}`,
    `    port: ${server.port}`
  );

  if (server.type === "ss") {
    lines.push(
      `    cipher: ${yamlQuote(server.cipher)}`,
      `    password: ${yamlQuote(server.password)}`
    );
    return;
  }

  if (server.type === "hysteria2") {
    lines.push(`    password: ${yamlQuote(server.password)}`);
    pushOptionalText(lines, "ports", server.ports);
    pushOptionalRaw(lines, "hop-interval", server.hopInterval);
    pushOptionalText(lines, "up", server.up);
    pushOptionalText(lines, "down", server.down);
    pushOptionalText(lines, "obfs", server.obfs);
    pushOptionalText(lines, "obfs-password", server.obfsPassword);
    pushOptionalText(lines, "sni", server.sni);
    pushOptionalBool(lines, "skip-cert-verify", server.skipCertVerify);
    pushOptionalText(lines, "fingerprint", server.fingerprint);
    pushOptionalStringList(lines, "alpn", server.alpn);
    pushOptionalText(lines, "bbr-profile", server.bbrProfile);
    return;
  }

  lines.push(`    uuid: ${yamlQuote(server.uuid)}`);
  if (server.type === "tuic") {
    lines.push(`    password: ${yamlQuote(server.password)}`);
    pushOptionalText(lines, "token", server.token);
    pushOptionalText(lines, "udp-relay-mode", server.udpRelayMode);
    pushOptionalText(lines, "congestion-controller", server.congestionController);
    pushOptionalText(lines, "bbr-profile", server.bbrProfile);
    pushOptionalText(lines, "sni", server.sni);
    pushOptionalStringList(lines, "alpn", server.alpn);
    pushOptionalBool(lines, "disable-sni", server.disableSni);
    pushOptionalBool(lines, "reduce-rtt", server.reduceRtt);
    pushOptionalRaw(lines, "request-timeout", server.requestTimeout);
    pushOptionalRaw(lines, "heartbeat-interval", server.heartbeatInterval);
    pushOptionalRaw(lines, "max-udp-relay-packet-size", server.maxUdpRelayPacketSize);
    pushOptionalBool(lines, "fast-open", server.fastOpen);
    pushOptionalBool(lines, "skip-cert-verify", server.skipCertVerify);
    pushOptionalRaw(lines, "max-open-streams", server.maxOpenStreams);
    return;
  }

  pushOptionalBool(lines, "udp", server.udp);
  pushOptionalText(lines, "packet-encoding", server.packetEncoding);
  pushOptionalBool(lines, "tls", server.tls);
  pushOptionalText(lines, "servername", server.serverName);
  pushOptionalText(lines, "network", server.network);
  pushOptionalText(lines, "flow", server.flow);
  if (server.realityOpts) {
    lines.push("    reality-opts:");
    if (server.realityOpts.publicKey) {
      lines.push(`      public-key: ${yamlQuote(server.realityOpts.publicKey)}`);
    }
    if (server.realityOpts.shortId) {
      lines.push(`      short-id: ${yamlQuote(server.realityOpts.shortId)}`);
    }
  }
  pushOptionalText(lines, "client-fingerprint", server.clientFingerprint);
}

export function generateClashProxyProvider(proxyConfig, options = {}) {
  const servers = clashProviderServers(proxyConfig);
  const lines = ["proxies:"];

  for (const server of servers) {
    pushProxy(lines, server);
  }

  lines.push("");
  return yamlWithLifecycle(lines, options);
}

export function generateClashProviderSubscription(proxyConfig, options = {}) {
  const provider = providerOptions(options);
  const lines = [
    "mixed-port: 7897",
    "allow-lan: false",
    "mode: global",
    "proxy-providers:",
    `  ${yamlQuote(provider.name)}:`,
    "    type: http",
    `    url: ${yamlQuote(provider.url)}`,
    `    path: ${yamlQuote(provider.path)}`,
    `    interval: ${provider.interval}`,
    "    health-check:",
    "      enable: true",
    `      url: ${yamlQuote(provider.healthCheckUrl)}`,
    `      interval: ${provider.healthCheckInterval}`,
    `      lazy: ${provider.healthCheckLazy ? "true" : "false"}`,
    "proxy-groups:"
  ];

  pushProviderGroup(lines, { name: "AUTO", type: "url-test", providerName: provider.name });
  pushProviderGroup(lines, {
    name: "WEAK-NETWORK",
    type: "url-test",
    providerName: provider.name,
    tolerance: 50
  });
  pushProviderGroup(lines, { name: "FALLBACK", type: "fallback", providerName: provider.name });

  lines.push(
    "  - name: GLOBAL",
    "    type: select",
    "    proxies:",
    "      - AUTO",
    "      - WEAK-NETWORK",
    "      - FALLBACK",
    "    use:",
    `      - ${yamlQuote(provider.name)}`,
    "rules:",
    "  - MATCH,GLOBAL",
    ""
  );

  return yamlWithLifecycle(lines, options);
}

function mihomoCoreHeader() {
  return [
    "mixed-port: 7897",
    "allow-lan: false",
    "mode: rule",
    "log-level: warning",
    "unified-delay: true",
    "tcp-concurrent: true",
    "global-client-fingerprint: chrome",
    "profile:",
    "  store-selected: true"
  ];
}

function pushInlineGlobalGroup(lines, servers) {
  lines.push(
    "  - name: GLOBAL",
    "    type: select",
    "    proxies:"
  );

  if (servers.length > 0) {
    lines.push("      - AUTO");
    if (qualityGroupServers(servers, ["stable"]).length > 0) {
      lines.push("      - STABLE");
    }
    if (qualityGroupServers(servers, ["weak-network", "long-haul", "high-loss"]).length > 0) {
      lines.push("      - WEAK-NETWORK");
    }
    lines.push("      - FALLBACK");
  }

  for (const server of servers) {
    lines.push(`      - ${yamlQuote(server.name)}`);
  }

}

export function generateMihomoRenderTemplate(proxyConfig, options = {}) {
  const servers = clashServers(proxyConfig);
  const lines = [
    ...mihomoCoreHeader(),
    ...nodeDomainProtectionDnsLines(servers),
    "proxies:"
  ];

  for (const server of servers) {
    pushProxy(lines, server);
  }

  lines.push("proxy-groups:");

  if (servers.length > 0) {
    pushProxyGroup(lines, { name: "AUTO", type: "url-test", servers: autoGroupServers(servers) });
    pushProxyGroup(lines, {
      name: "WEAK-NETWORK",
      type: "url-test",
      servers: qualityGroupServers(servers, ["weak-network", "long-haul", "high-loss"]),
      tolerance: 50
    });
    pushProxyGroup(lines, {
      name: "STABLE",
      type: "fallback",
      servers: qualityGroupServers(servers, ["stable"])
    });
    pushProxyGroup(lines, { name: "FALLBACK", type: "fallback", servers });
  }

  pushInlineGlobalGroup(lines, servers);

  lines.push(
    "rules:",
    ...nodeDomainProtectionRuleLines(servers),
    "  - MATCH,GLOBAL",
    ""
  );

  return yamlWithLifecycle(lines, options);
}

export function generateMihomoProviderTemplate(proxyConfig, options = {}) {
  const provider = providerOptions(options);
  const lines = [
    ...mihomoCoreHeader(),
    "proxy-providers:",
    `  ${yamlQuote(provider.name)}:`,
    "    type: http",
    `    url: ${yamlQuote(provider.url)}`,
    `    path: ${yamlQuote(provider.path)}`,
    `    interval: ${provider.interval}`,
    "    health-check:",
    "      enable: true",
    `      url: ${yamlQuote(provider.healthCheckUrl)}`,
    `      interval: ${provider.healthCheckInterval}`,
    `      lazy: ${provider.healthCheckLazy ? "true" : "false"}`,
    "proxy-groups:"
  ];

  pushProviderGroup(lines, { name: "AUTO", type: "url-test", providerName: provider.name });
  pushProviderGroup(lines, {
    name: "WEAK-NETWORK",
    type: "url-test",
    providerName: provider.name,
    tolerance: 50
  });
  pushProviderGroup(lines, { name: "FALLBACK", type: "fallback", providerName: provider.name });

  lines.push(
    "  - name: GLOBAL",
    "    type: select",
    "    proxies:",
    "      - AUTO",
    "      - WEAK-NETWORK",
    "      - FALLBACK",
    "    use:",
    `      - ${yamlQuote(provider.name)}`,
    "rules:",
    "  - MATCH,GLOBAL",
    ""
  );

  return yamlWithLifecycle(lines, options);
}

export function generateClashSubscription(proxyConfig, options = {}) {
  const servers = clashServers(proxyConfig);
  const lines = [
    "mixed-port: 7897",
    "allow-lan: false",
    "mode: global",
    ...nodeDomainProtectionDnsLines(servers),
    "proxies:"
  ];

  for (const server of servers) {
    pushProxy(lines, server);
  }

  lines.push("proxy-groups:");

  if (servers.length > 0) {
    pushProxyGroup(lines, { name: "AUTO", type: "url-test", servers: autoGroupServers(servers) });
    pushProxyGroup(lines, {
      name: "WEAK-NETWORK",
      type: "url-test",
      servers: qualityGroupServers(servers, ["weak-network", "long-haul", "high-loss"]),
      tolerance: 50
    });
    pushProxyGroup(lines, {
      name: "STABLE",
      type: "fallback",
      servers: qualityGroupServers(servers, ["stable"])
    });
    pushProxyGroup(lines, { name: "FALLBACK", type: "fallback", servers });
  }

  lines.push(
    "  - name: GLOBAL",
    "    type: select",
    "    proxies:"
  );

  if (servers.length > 0) {
    if (autoGroupServers(servers).length > 0) {
      lines.push("      - AUTO");
    }
    if (qualityGroupServers(servers, ["stable"]).length > 0) {
      lines.push("      - STABLE");
    }
    if (qualityGroupServers(servers, ["weak-network", "long-haul", "high-loss"]).length > 0) {
      lines.push("      - WEAK-NETWORK");
    }
    lines.push("      - FALLBACK");
  }

  for (const server of servers) {
    lines.push(`      - ${yamlQuote(server.name)}`);
  }

  lines.push(
    "rules:",
    ...nodeDomainProtectionRuleLines(servers),
    "  - MATCH,GLOBAL",
    ""
  );

  return yamlWithLifecycle(lines, options);
}

export function subscriptionSummary({
  proxyConfig
}) {
  const servers = clashServers(proxyConfig);

  return {
    ok: true,
    profileName: "Proxy Gateway Local",
    serverCount: servers.length,
    servers: servers.map((server) => ({
      name: server.name,
      host: server.host,
      port: server.port,
      type: server.type
    }))
  };
}
