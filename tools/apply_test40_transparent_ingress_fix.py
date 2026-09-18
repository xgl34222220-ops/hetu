from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "android-app/app/build.gradle.kts"
STARTUP = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/hetu/MihomoStartupConfig.java"
SCRIPT = ROOT / "android-app/app/src/main/assets/proxy-root-v3.sh"

# ---------------------------------------------------------------------------
# Version
# ---------------------------------------------------------------------------
build = BUILD.read_text(encoding="utf-8")
build = build.replace('versionCode = 439', 'versionCode = 440', 1)
build = build.replace('versionName = "0.4.0-test.39"', 'versionName = "0.4.0-test.40"', 1)
if 'versionCode = 440' not in build or 'versionName = "0.4.0-test.40"' not in build:
    raise SystemExit("test40: version patch failed")
BUILD.write_text(build, encoding="utf-8")

# ---------------------------------------------------------------------------
# Private runtime ingress isolation.
# The user's real YAML intentionally has allow-lan:false + bind-address:127.0.0.1.
# That is fine for a local mixed port, but it also makes Mihomo's TPROXY listener bind
# to loopback. Linux TPROXY preserves the original destination, therefore the transparent
# socket must accept non-loopback local destinations. Force only Hetu's private runtime
# copy to wildcard ingress; the user's source YAML stays untouched.
# ---------------------------------------------------------------------------
startup = STARTUP.read_text(encoding="utf-8")
anchor = '''        yaml=removeTopLevelScalar(yaml,"mixed-port");
        yaml=removeTopLevelScalar(yaml,"socks-port");
        yaml=removeTopLevelScalar(yaml,"port");'''
replacement = '''        yaml=removeTopLevelScalar(yaml,"mixed-port");
        yaml=removeTopLevelScalar(yaml,"socks-port");
        yaml=removeTopLevelScalar(yaml,"port");
        // Root TPROXY must listen on a wildcard transparent socket. The source config may
        // deliberately bind ordinary HTTP/SOCKS listeners to loopback; do not inherit that
        // restriction into Hetu's private transparent runtime.
        yaml=removeTopLevelScalar(yaml,"allow-lan");
        yaml=removeTopLevelScalar(yaml,"bind-address");'''
if anchor not in startup:
    raise SystemExit("test40: listener isolation anchor missing")
startup = startup.replace(anchor, replacement, 1)

old_dns = '''        if(profile.dnsHijack==ProxyRuntimeProfile.DnsHijack.REDIRECT)
            yaml=ensureDnsListener(yaml,DNS_PORT);'''
new_dns = '''        // Both Root DNS modes terminate DNS in Mihomo's private built-in resolver.
        // This preserves fake-ip / respect-rules and prevents plaintext DNS from being sent
        // as an ordinary transparent UDP flow. The source's 1053 listener is never reused.
        if(profile.dnsHijack!=ProxyRuntimeProfile.DnsHijack.OFF)
            yaml=ensureDnsListener(yaml,DNS_PORT);'''
if old_dns not in startup:
    raise SystemExit("test40: DNS listener condition missing")
startup = startup.replace(old_dns, new_dns, 1)

override_anchor = '''        override.append("routing-mark: ").append(OUTBOUND_ROUTING_MARK).append('\\n');
        override.append("find-process-mode: strict\\n");'''
override_replacement = '''        // The transparent listener must accept packets re-routed to loopback while retaining
        // their original destination. No HTTP/SOCKS/Mixed listener is present in this private
        // runtime, so enabling wildcard ingress does not expose the user's former 7890 proxy.
        override.append("allow-lan: true\\n");
        override.append("bind-address: '*'\\n");
        override.append("routing-mark: ").append(OUTBOUND_ROUTING_MARK).append('\\n');
        override.append("find-process-mode: strict\\n");'''
if override_anchor not in startup:
    raise SystemExit("test40: runtime override anchor missing")
startup = startup.replace(override_anchor, override_replacement, 1)
STARTUP.write_text(startup, encoding="utf-8")

# ---------------------------------------------------------------------------
# Root DNS interception.
# test.39's DnsHijack.TPROXY sent :53 traffic to the generic TPROXY ingress. That bypasses
# Mihomo's internal DNS server and can deadlock a respect-rules/fake-ip configuration.
# For the unified Root backend, both UI DNS interception choices now terminate on the
# dedicated localhost DNS listener. Keep the name for preference compatibility.
# ---------------------------------------------------------------------------
script = SCRIPT.read_text(encoding="utf-8")

old_preflight = '''  [ "$DNS" = tproxy ] && NEED_TP=1
  [ "$DNS" = redirect ] && { probered4 "$DP" tcp || fail "当前 iptables 不支持 TCP DNS REDIRECT"; probered4 "$DP" udp || fail "当前 iptables 不支持 UDP DNS REDIRECT"; }'''
new_preflight = '''  if [ "$DNS" = tproxy ] || [ "$DNS" = redirect ]; then
    probered4 "$DP" tcp || fail "当前 iptables 不支持 TCP DNS REDIRECT"
    probered4 "$DP" udp || fail "当前 iptables 不支持 UDP DNS REDIRECT"
  fi'''
if old_preflight not in script:
    raise SystemExit("test40: preflight DNS block missing")
script = script.replace(old_preflight, new_preflight, 1)

old_preflight6 = '''    if [ "$DNS" = redirect ]; then probered6 "$DP" tcp || fail "IPv6 TCP DNS REDIRECT 不可用"; probered6 "$DP" udp || fail "IPv6 UDP DNS REDIRECT 不可用"; fi'''
new_preflight6 = '''    if [ "$DNS" = tproxy ] || [ "$DNS" = redirect ]; then probered6 "$DP" tcp || fail "IPv6 TCP DNS REDIRECT 不可用"; probered6 "$DP" udp || fail "IPv6 UDP DNS REDIRECT 不可用"; fi'''
if old_preflight6 not in script:
    raise SystemExit("test40: IPv6 preflight DNS block missing")
script = script.replace(old_preflight6, new_preflight6, 1)

# check_start_ports: DNS owns the private DP listener, never the TPROXY ingress.
old_check = '''  if [ "$DNS" = tproxy ]; then
    tcp_listen "$TP" && fail "DNS/TPROXY TCP 端口 $TP 已被其他程序占用"
    udp_listen "$TP" && fail "DNS/TPROXY UDP 端口 $TP 已被其他程序占用"
  elif [ "$DNS" = redirect ]; then
    tcp_listen "$DP" && fail "DNS TCP 端口 $DP 已被其他程序占用"
    udp_listen "$DP" && fail "DNS UDP 端口 $DP 已被其他程序占用"
  fi'''
new_check = '''  if [ "$DNS" = tproxy ] || [ "$DNS" = redirect ]; then
    tcp_listen "$DP" && fail "DNS TCP 端口 $DP 已被其他程序占用"
    udp_listen "$DP" && fail "DNS UDP 端口 $DP 已被其他程序占用"
  fi'''
if old_check not in script:
    raise SystemExit("test40: check_start_ports DNS block missing")
script = script.replace(old_check, new_check, 1)

# ready(): require the dedicated DNS listener for either interception preference.
old_ready_dns = '''  if [ "$DNS" = tproxy ]; then tcp_listen "$TP" || return 1; udp_listen "$TP" || return 1; fi; if [ "$DNS" = redirect ]; then tcp_listen "$DP" || return 1; udp_listen "$DP" || return 1; fi; return 0'''
new_ready_dns = '''  if [ "$DNS" = tproxy ] || [ "$DNS" = redirect ]; then tcp_listen "$DP" || return 1; udp_listen "$DP" || return 1; fi; return 0'''
if old_ready_dns not in script:
    raise SystemExit("test40: ready DNS block missing")
script = script.replace(old_ready_dns, new_ready_dns, 1)

# Mangle: DNS must not be marked into the generic TPROXY loop. Return :53 before the
# broad TCP/UDP mark rules, then NAT OUTPUT/PREROUTING redirects it to the DNS listener.
old_mangle_dns = '''  NEED=0; case "$M" in tproxy) if [ "$TCP" = 1 ] || [ "$UDP" = 1 ]; then NEED=1; fi;; enhance) [ "$UDP" = 1 ] && NEED=1;; esac; [ "$DNS" = tproxy ] && NEED=1; [ "$NEED" = 1 ] || return 0
  route4 || return 1; iptables -t mangle -N "$MOUT" || return 1; iptables -t mangle -N "$MPRE" || return 1
  iptables -t mangle -A "$MOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_out iptables mangle "$MOUT" "$IFACES" || return 1; blacklist_returns iptables mangle "$MOUT" "$S" "$UIDS" || return 1
  iptables -t mangle -A "$MPRE" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_in iptables mangle "$MPRE" "$IFACES" || return 1
  if [ "$DNS" = tproxy ]; then
    for X in tcp udp; do scoped_mark iptables mangle "$MOUT" "$S" "$UIDS" "$X" 53 "$MARK/$MASK" || return 1; if [ "$SHARE" = 1 ]; then iptables -t mangle -A "$MPRE" -p "$X" --dport 53 -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; else iptables -t mangle -A "$MPRE" -m mark --mark "$MARK/$MASK" -p "$X" --dport 53 -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; fi; done
  fi
  bypass4 "$MOUT" mangle "$CIDRS" || return 1; bypass4 "$MPRE" mangle "$CIDRS" || return 1'''
new_mangle_dns = '''  NEED=0; case "$M" in tproxy) if [ "$TCP" = 1 ] || [ "$UDP" = 1 ]; then NEED=1; fi;; enhance) [ "$UDP" = 1 ] && NEED=1;; esac; [ "$NEED" = 1 ] || return 0
  route4 || return 1; iptables -t mangle -N "$MOUT" || return 1; iptables -t mangle -N "$MPRE" || return 1
  iptables -t mangle -A "$MOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_out iptables mangle "$MOUT" "$IFACES" || return 1; blacklist_returns iptables mangle "$MOUT" "$S" "$UIDS" || return 1
  iptables -t mangle -A "$MPRE" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_in iptables mangle "$MPRE" "$IFACES" || return 1
  if [ "$DNS" = tproxy ] || [ "$DNS" = redirect ]; then
    iptables -t mangle -A "$MOUT" -p tcp --dport 53 -j RETURN || return 1
    iptables -t mangle -A "$MOUT" -p udp --dport 53 -j RETURN || return 1
  fi
  bypass4 "$MOUT" mangle "$CIDRS" || return 1; bypass4 "$MPRE" mangle "$CIDRS" || return 1'''
if old_mangle_dns not in script:
    raise SystemExit("test40: IPv4 mangle DNS block missing")
script = script.replace(old_mangle_dns, new_mangle_dns, 1)

old_mangle6_dns = '''  NEED=0; case "$M" in tproxy) if [ "$TCP" = 1 ] || [ "$UDP" = 1 ]; then NEED=1; fi;; enhance) [ "$UDP" = 1 ] && NEED=1;; esac; [ "$DNS" = tproxy ] && NEED=1; [ "$NEED" = 1 ] || return 0
  route6 || return 1; ip6tables -t mangle -N "$MOUT" || return 1; ip6tables -t mangle -N "$MPRE" || return 1
  ip6tables -t mangle -A "$MOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_out ip6tables mangle "$MOUT" "$IFACES" || return 1; blacklist_returns ip6tables mangle "$MOUT" "$S" "$UIDS" || return 1
  ip6tables -t mangle -A "$MPRE" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_in ip6tables mangle "$MPRE" "$IFACES" || return 1
  if [ "$DNS" = tproxy ]; then for X in tcp udp; do scoped_mark ip6tables mangle "$MOUT" "$S" "$UIDS" "$X" 53 "$MARK/$MASK" || return 1; if [ "$SHARE" = 1 ]; then ip6tables -t mangle -A "$MPRE" -p "$X" --dport 53 -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; else ip6tables -t mangle -A "$MPRE" -m mark --mark "$MARK/$MASK" -p "$X" --dport 53 -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; fi; done; fi
  bypass6 "$MOUT" mangle "$CIDRS" || return 1; bypass6 "$MPRE" mangle "$CIDRS" || return 1'''
new_mangle6_dns = '''  NEED=0; case "$M" in tproxy) if [ "$TCP" = 1 ] || [ "$UDP" = 1 ]; then NEED=1; fi;; enhance) [ "$UDP" = 1 ] && NEED=1;; esac; [ "$NEED" = 1 ] || return 0
  route6 || return 1; ip6tables -t mangle -N "$MOUT" || return 1; ip6tables -t mangle -N "$MPRE" || return 1
  ip6tables -t mangle -A "$MOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_out ip6tables mangle "$MOUT" "$IFACES" || return 1; blacklist_returns ip6tables mangle "$MOUT" "$S" "$UIDS" || return 1
  ip6tables -t mangle -A "$MPRE" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_in ip6tables mangle "$MPRE" "$IFACES" || return 1
  if [ "$DNS" = tproxy ] || [ "$DNS" = redirect ]; then
    ip6tables -t mangle -A "$MOUT" -p tcp --dport 53 -j RETURN || return 1
    ip6tables -t mangle -A "$MOUT" -p udp --dport 53 -j RETURN || return 1
  fi
  bypass6 "$MOUT" mangle "$CIDRS" || return 1; bypass6 "$MPRE" mangle "$CIDRS" || return 1'''
if old_mangle6_dns not in script:
    raise SystemExit("test40: IPv6 mangle DNS block missing")
script = script.replace(old_mangle6_dns, new_mangle6_dns, 1)

# Start transaction: install DNS REDIRECT for both DNS interception preferences.
script = script.replace(
    '''  [ "$START_DNS" != redirect ] || install_dns_redirect4 "$START_DP" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_IFACES" || { cleanup; stopcore; restorev6; rm -f "$SESSION"; fail "IPv4 DNS 劫持安装失败，已回滚"; }''',
    '''  [ "$START_DNS" = off ] || install_dns_redirect4 "$START_DP" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_IFACES" || { cleanup; stopcore; restorev6; rm -f "$SESSION"; fail "IPv4 DNS 劫持安装失败，已回滚"; }''',
    1,
)
script = script.replace(
    '''    [ "$START_DNS" != redirect ] || install_dns_redirect6 "$START_DP" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_IFACES" || { cleanup; stopcore; restorev6; rm -f "$SESSION"; fail "IPv6 DNS 劫持安装失败，已回滚"; }''',
    '''    [ "$START_DNS" = off ] || install_dns_redirect6 "$START_DP" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_IFACES" || { cleanup; stopcore; restorev6; rm -f "$SESSION"; fail "IPv6 DNS 劫持安装失败，已回滚"; }''',
    1,
)

SCRIPT.write_text(script, encoding="utf-8")

# ---------------------------------------------------------------------------
# Hard audit
# ---------------------------------------------------------------------------
checks = {
    BUILD: ['versionCode = 440', 'versionName = "0.4.0-test.40"'],
    STARTUP: [
        'removeTopLevelScalar(yaml,"allow-lan")',
        'removeTopLevelScalar(yaml,"bind-address")',
        'profile.dnsHijack!=ProxyRuntimeProfile.DnsHijack.OFF',
        'override.append("allow-lan: true\\n")',
        'override.append("bind-address: \'*\'\\n")',
        'TPROXY_PORT=19898', 'DNS_PORT=11053', 'CONTROLLER_PORT=29090',
        'ProxyAdblockRules.PROVIDER_PATH',
    ],
    SCRIPT: [
        'if [ "$DNS" = tproxy ] || [ "$DNS" = redirect ]; then',
        'iptables -t mangle -A "$MOUT" -p udp --dport 53 -j RETURN',
        'ip6tables -t mangle -A "$MOUT" -p udp --dport 53 -j RETURN',
        '[ "$START_DNS" = off ] || install_dns_redirect4',
        '[ "$START_DNS" = off ] || install_dns_redirect6',
        'START_BIN="$1"; START_CFG="$2"; START_MODE="$3"',
    ],
}
for path, needles in checks.items():
    body = path.read_text(encoding="utf-8")
    for needle in needles:
        if needle not in body:
            raise SystemExit(f"test40: missing invariant {needle} in {path}")

print("test.40 transparent listener + private DNS ingress fix applied")
