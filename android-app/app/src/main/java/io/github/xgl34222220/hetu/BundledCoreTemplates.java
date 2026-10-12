package io.github.xgl34222220.hetu;

/**
 * Built-in starter configs for the non-Mihomo cores, the counterpart of Mihomo's 河图内置-TPROXY.yaml.
 *
 * <p>Each template is a minimal native config for its core: the TPROXY / Redirect / DNS ingress on
 * hetu-root.sh's ports (19898 / 19797 / 11053, see {@link MihomoStartupConfig}), split DNS, China
 * direct rules, the clash_api controller where the core has one, and one placeholder node on the
 * reserved {@code example.invalid} domain. No real server, password or UUID is shipped.
 * {@link ProxyCoreConfig} still replaces the ingress and controller it owns at start, exactly as for a
 * user's own native file, so the ports here only document what the runtime uses.
 *
 * <p>Pure JVM (no Android API): the host template test reads this file directly.
 */
final class BundledCoreTemplates {
    private BundledCoreTemplates() {}

    /** The reserved host of every placeholder node; its presence means the template is still unedited. */
    static final String PLACEHOLDER_HOST = "example.invalid";

    static final String SING_BOX_NAME = "河图内置-sing-box.json";
    static final String XRAY_NAME = "河图内置-Xray.json";
    static final String V2FLY_NAME = "河图内置-V2Fly.json";
    static final String HYSTERIA_NAME = "河图内置-Hysteria.yaml";

    /** File name of the built-in template for {@code core}, or null for the Mihomo family (own bundled YAML). */
    static String name(ProxyRuntimeProfile.Core core) {
        switch (core) {
            case SING_BOX: case SING_BOX_REF1ND: return SING_BOX_NAME;
            case XRAY: return XRAY_NAME;
            case V2FLY: return V2FLY_NAME;
            case HYSTERIA: return HYSTERIA_NAME;
            default: return null;
        }
    }

    /** Template text for {@code core}, or null for the Mihomo family. */
    static String text(ProxyRuntimeProfile.Core core) {
        switch (core) {
            case SING_BOX: case SING_BOX_REF1ND: return SING_BOX;
            case XRAY: return XRAY;
            case V2FLY: return V2FLY;
            case HYSTERIA: return HYSTERIA;
            default: return null;
        }
    }

    /** True for a non-Mihomo built-in template name: like Mihomo's built-in, it cannot be renamed or deleted. */
    static boolean isBundledName(String name) {
        return SING_BOX_NAME.equals(name) || XRAY_NAME.equals(name) || V2FLY_NAME.equals(name) || HYSTERIA_NAME.equals(name);
    }

    /** The source still holds the shipped placeholder node instead of the user's own server. */
    static boolean hasPlaceholderNode(String source) {
        return source != null && source.contains(PLACEHOLDER_HOST);
    }

    // sing-box 1.12+ schema (new DNS server objects, rule actions), checked with `sing-box check` 1.14.3.
    static final String SING_BOX = """
{
  "log": { "level": "info", "timestamp": true },
  "dns": {
    "servers": [
      { "type": "udp", "tag": "dns-direct", "server": "223.5.5.5" },
      { "type": "https", "tag": "dns-remote", "server": "1.1.1.1", "detour": "PROXY" }
    ],
    "rules": [
      { "rule_set": ["geosite-cn"], "action": "route", "server": "dns-direct" }
    ],
    "final": "dns-remote"
  },
  "inbounds": [
    { "type": "tproxy", "tag": "hetu-tproxy", "listen": "::", "listen_port": 19898 },
    { "type": "redirect", "tag": "hetu-redirect", "listen": "::", "listen_port": 19797 },
    { "type": "direct", "tag": "hetu-dns-in", "listen": "::", "listen_port": 11053 }
  ],
  "outbounds": [
    { "type": "selector", "tag": "PROXY", "outbounds": ["示例节点-VLESS", "示例节点-Shadowsocks", "DIRECT"], "default": "示例节点-VLESS" },
    {
      "type": "vless", "tag": "示例节点-VLESS",
      "server": "example.invalid", "server_port": 443,
      "uuid": "00000000-0000-0000-0000-000000000000",
      "tls": { "enabled": true, "server_name": "example.invalid" }
    },
    {
      "type": "shadowsocks", "tag": "示例节点-Shadowsocks",
      "server": "example.invalid", "server_port": 8388,
      "method": "2022-blake3-aes-128-gcm", "password": "AAAAAAAAAAAAAAAAAAAAAA=="
    },
    { "type": "direct", "tag": "DIRECT" }
  ],
  "route": {
    "rules": [
      { "inbound": ["hetu-tproxy", "hetu-redirect", "hetu-dns-in"], "action": "sniff" },
      { "protocol": "dns", "action": "hijack-dns" },
      { "ip_is_private": true, "action": "route", "outbound": "DIRECT" },
      { "rule_set": ["geosite-cn", "geoip-cn"], "action": "route", "outbound": "DIRECT" }
    ],
    "rule_set": [
      { "type": "remote", "tag": "geosite-cn", "format": "binary", "url": "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-cn.srs", "download_detour": "PROXY", "update_interval": "72h" },
      { "type": "remote", "tag": "geoip-cn", "format": "binary", "url": "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-cn.srs", "download_detour": "PROXY", "update_interval": "72h" }
    ],
    "final": "PROXY",
    "auto_detect_interface": true,
    "default_domain_resolver": "dns-direct"
  },
  "experimental": {
    "clash_api": { "external_controller": "127.0.0.1:29090", "secret": "", "default_mode": "rule" },
    "cache_file": { "enabled": true, "path": "cache.db", "store_fakeip": true }
  }
}
""";

    // Xray JSON (v4 schema), checked with `xray run -test` 26.3.27. geoip.dat/geosite.dat ship in the core zip.
    static final String XRAY = """
{
  "log": { "loglevel": "warning" },
  "dns": {
    "servers": [
      { "address": "223.5.5.5", "domains": ["geosite:cn"], "expectIPs": ["geoip:cn"] },
      "1.1.1.1"
    ],
    "queryStrategy": "UseIP"
  },
  "inbounds": [
    {
      "tag": "hetu-tproxy", "listen": "::", "port": 19898, "protocol": "dokodemo-door",
      "settings": { "network": "tcp,udp", "followRedirect": true },
      "streamSettings": { "sockopt": { "tproxy": "tproxy" } },
      "sniffing": { "enabled": true, "destOverride": ["http", "tls", "quic"], "routeOnly": true }
    },
    {
      "tag": "hetu-redirect", "listen": "::", "port": 19797, "protocol": "dokodemo-door",
      "settings": { "network": "tcp", "followRedirect": true },
      "streamSettings": { "sockopt": { "tproxy": "redirect" } },
      "sniffing": { "enabled": true, "destOverride": ["http", "tls", "quic"], "routeOnly": true }
    },
    {
      "tag": "hetu-dns-in", "listen": "::", "port": 11053, "protocol": "dokodemo-door",
      "settings": { "address": "1.1.1.1", "port": 53, "network": "tcp,udp" }
    }
  ],
  "outbounds": [
    {
      "tag": "PROXY", "protocol": "vless",
      "settings": { "vnext": [ { "address": "example.invalid", "port": 443,
        "users": [ { "id": "00000000-0000-0000-0000-000000000000", "encryption": "none" } ] } ] },
      "streamSettings": { "network": "tcp", "security": "tls", "tlsSettings": { "serverName": "example.invalid" } }
    },
    { "tag": "DIRECT", "protocol": "freedom" },
    { "tag": "REJECT", "protocol": "blackhole" },
    { "tag": "hetu-dns-out", "protocol": "dns" }
  ],
  "routing": {
    "domainStrategy": "IPIfNonMatch",
    "rules": [
      { "type": "field", "ip": ["geoip:private"], "outboundTag": "DIRECT" },
      { "type": "field", "domain": ["geosite:cn"], "outboundTag": "DIRECT" },
      { "type": "field", "ip": ["geoip:cn"], "outboundTag": "DIRECT" },
      { "type": "field", "network": "tcp,udp", "outboundTag": "PROXY" }
    ]
  }
}
""";

    // V2Fly v5 binary reading the v4 JSON schema, checked with `v2ray test` 5.40.0.
    static final String V2FLY = """
{
  "log": { "loglevel": "warning" },
  "dns": {
    "servers": [
      { "address": "223.5.5.5", "domains": ["geosite:cn"], "expectIPs": ["geoip:cn"] },
      "1.1.1.1"
    ],
    "queryStrategy": "UseIP"
  },
  "inbounds": [
    {
      "tag": "hetu-tproxy", "listen": "::", "port": 19898, "protocol": "dokodemo-door",
      "settings": { "network": "tcp,udp", "followRedirect": true },
      "streamSettings": { "sockopt": { "tproxy": "tproxy" } },
      "sniffing": { "enabled": true, "destOverride": ["http", "tls", "quic"], "routeOnly": true }
    },
    {
      "tag": "hetu-redirect", "listen": "::", "port": 19797, "protocol": "dokodemo-door",
      "settings": { "network": "tcp", "followRedirect": true },
      "streamSettings": { "sockopt": { "tproxy": "redirect" } },
      "sniffing": { "enabled": true, "destOverride": ["http", "tls", "quic"], "routeOnly": true }
    },
    {
      "tag": "hetu-dns-in", "listen": "::", "port": 11053, "protocol": "dokodemo-door",
      "settings": { "address": "1.1.1.1", "port": 53, "network": "tcp,udp" }
    }
  ],
  "outbounds": [
    {
      "tag": "PROXY", "protocol": "vmess",
      "settings": { "vnext": [ { "address": "example.invalid", "port": 443,
        "users": [ { "id": "00000000-0000-0000-0000-000000000000", "alterId": 0, "security": "auto" } ] } ] },
      "streamSettings": { "network": "ws", "security": "tls",
        "tlsSettings": { "serverName": "example.invalid" }, "wsSettings": { "path": "/" } }
    },
    { "tag": "DIRECT", "protocol": "freedom" },
    { "tag": "REJECT", "protocol": "blackhole" },
    { "tag": "hetu-dns-out", "protocol": "dns" }
  ],
  "routing": {
    "domainStrategy": "IPIfNonMatch",
    "rules": [
      { "type": "field", "ip": ["geoip:private"], "outboundTag": "DIRECT" },
      { "type": "field", "domain": ["geosite:cn"], "outboundTag": "DIRECT" },
      { "type": "field", "ip": ["geoip:cn"], "outboundTag": "DIRECT" },
      { "type": "field", "network": "tcp,udp", "outboundTag": "PROXY" }
    ]
  }
}
""";

    // Hysteria 2 client YAML. Single server: no rule routing or controller (Hetu adds the TPROXY,
    // Redirect and DNS forwarding listeners plus the resolver at start).
    static final String HYSTERIA = """
# 河图内置 Hysteria 2 客户端模板：把 server / auth / tls.sni 换成你自己的节点后启动。
# 入站由河图在启动时写入（TPROXY 19898 / Redirect 19797 / DNS 11053），这里无需填写。
server: example.invalid:443
auth: replace-with-your-password
tls:
  sni: example.invalid
  insecure: false
bandwidth:
  up: 20 mbps
  down: 100 mbps
fastOpen: true
""";
}
