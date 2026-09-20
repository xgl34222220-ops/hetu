#!/usr/bin/env python3
from pathlib import Path
import hashlib,json
r=Path(__file__).resolve().parents[1]
f=r/'android-app/app/src/main/assets/hetu-root.sh'
assert hashlib.sha256(f.read_bytes()).hexdigest()=='98606d280715ce748d58afeb7398671745cf591b5af8af8034f19686cf64f900'
t=f.read_text()
def rep(a,b):
 global t
 assert t.count(a)==1,(t.count(a),a[:120]);t=t.replace(a,b)
rep('pidcore(){', '''# Cheap built-in prefilter: do not fork readlink for every Android process.
# Same-inode catches renamed comm; tracked PIDs always bypass this prefilter.
core_candidate(){
  [ "$BASE/bin/core" -ef "${CORE_PROCFS:-/proc}/$1/exe" ] && return 0
  CORE_COMM=''
  read -r CORE_COMM < "${CORE_PROCFS:-/proc}/$1/comm" 2>/dev/null || return 1
  case "$CORE_COMM" in core|mihomo|mihomo-*) return 0;; *) return 1;; esac
}
pidcore(){''')
rep('    if pidcore "$CAND" && kill -0 "$CAND" >/dev/null 2>&1; then', '    if core_candidate "$CAND" && pidcore "$CAND" && kill -0 "$CAND" >/dev/null 2>&1; then')
rep('    if pidcore "$OPID" && kill -0 "$OPID" >/dev/null 2>&1; then', '    if core_candidate "$OPID" && pidcore "$OPID" && kill -0 "$OPID" >/dev/null 2>&1; then')
rep('''    probered6 "$DP" tcp || fail "IPv6 TCP DNS 接管不可用"
    probered6 "$DP" udp || fail "IPv6 UDP DNS 接管不可用"
''','''    # DNS redirection is optional in disable mode; filter REJECT remains mandatory.
    # Re-evaluate the optional NAT capability in start(), even with cached preflight.
''')
rep('preflight(){','''# This decision is deliberately outside the cached preflight fast path.
# Never load kernel modules or change netd's DNS/IPv6 settings to obtain NAT.
select_dns6_policy(){
  START_DNS6=off
  [ "$START_DNS" != off ] && v6supported || return 0
  case "$START_MODE" in tun|ebpf) START_DNS6=core; return 0;; esac
  case "$START_V6" in enable) START_DNS6=redirect; return 0;; disable) ;; *) return 0;; esac
  has ip6tables || fail "IPv6 DNS 防泄漏需要 ip6tables"
  DNS6_PROBE=$(xt6q -t nat -S 2>&1); DNS6_PROBE_RC=$?
  if [ "$DNS6_PROBE_RC" != 0 ]; then
    case "$DNS6_PROBE" in
      *"Table does not exist"*|*"table does not exist"*) START_DNS6=blocked-no-nat; return 0;;
      *) fail "IPv6 DNS 能力读取失败，未改动当前代理；请查看 Root 权限或防火墙锁";;
    esac
  fi
  if probered6 "$START_DP" tcp && probered6 "$START_DP" udp; then
    START_DNS6=redirect
  else
    START_DNS6=blocked-no-redirect
  fi
}
install_disabled_dns6(){
  case "$START_DNS6" in
    redirect) install_dns_redirect6 "$START_DP" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_IFACES";;
    blocked-no-nat|blocked-no-redirect)
      # Verify the actual fail-closed guard; do not treat optional NAT failure as
      # permission to send system/app IPv6 DNS directly to an external resolver.
      v6_disable_guard_ready "$START_SHARE" || return 1
      xt6q -t filter -C "$V6OUT" -p udp --dport 53 -j REJECT >/dev/null 2>&1 || return 1
      xt6q -t filter -C "$V6OUT" -p tcp --dport 53 -j REJECT >/dev/null 2>&1 || return 1
      ;;
    off|core) return 0;;
    *) return 1;;
  esac
}

preflight(){''')
rep('''  [ -x "$START_BIN" ] || fail "核心文件不存在或不可执行";''','''  start_stage "ipv6-dns-capability"
  select_dns6_policy
  [ -x "$START_BIN" ] || fail "核心文件不存在或不可执行";''')
rep('''    install_dns_redirect6 "$START_DP" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_IFACES" || { cleanup; stopcore; rm -f "$SESSION"; fail "IPv6 DNS 本地接管失败，未放行直连 DNS"; }''','''    install_disabled_dns6 || { cleanup; stopcore; rm -f "$SESSION"; fail "IPv6 DNS 防泄漏安装失败，未放行直连 DNS"; }''')
rep('''    printf 'TCP=%s\\nUDP=%s\\n' "$START_TCP" "$START_UDP"''','''    printf 'DNS6_POLICY=%s\\n' "${START_DNS6:-redirect}"
    printf 'TCP=%s\\nUDP=%s\\n' "$START_TCP" "$START_UDP"''')
rep('''  for H_F in 4 6; do
    [ "$H_F" != 6 ] || has ip6tables || continue
    for H_T in mangle nat filter; do
      # Read only tables that this session uses. Empty tables need no repair.
''','''  H_IPV6=$(sed -n 's/^IPV6=//p' "$SESSION")
  H_DNS6=$(sed -n 's/^DNS6_POLICY=//p' "$SESSION")
  for H_F in 4 6; do
    [ "$H_F" != 6 ] || has ip6tables || continue
    for H_T in mangle nat filter; do
      # Missing optional IPv6 tables are not a failed session. Required tables
      # still fail closed on ANY read error and remain checked by the watchdog.
      if [ "$H_F" = 6 ]; then
        [ "$H_IPV6" != bypass ] || continue
        [ "$H_T" != mangle ] || [ "$H_IPV6" = enable ] || continue
        [ "$H_T" != nat ] || [ "$H_IPV6" = enable ] || [ "$H_DNS6" = redirect ] || continue
      fi
''')
rep('''  H_STATE=healthy; H_REASON=''; H_UNKNOWN=0
  if ! health_session_current;''','''  H_STATE=healthy; H_REASON=''; H_UNKNOWN=0
  if [ ! -r "$SESSION" ] && [ ! -r "$PIDFILE" ]; then H_STATE=stopped; H_REASON=not-running; return; fi
  if ! health_session_current;''')
rep('''  printf '{"ok":true,"networkIntegrity":"%s","networkFault":"%s","dataPlaneHealthy":%s}\\n' "$H_STATE" "$H_REASON" "$([ "$H_STATE" = healthy ] && echo true || echo false)"''','''  H_DNS6=$(sed -n 's/^DNS6_POLICY=//p' "$SESSION" 2>/dev/null || true)
  case "$H_DNS6" in redirect|blocked-no-nat|blocked-no-redirect|off|core) ;; *) H_DNS6=unknown;; esac
  printf '{"ok":true,"networkIntegrity":"%s","networkFault":"%s","ipv6DnsPolicy":"%s","dataPlaneHealthy":%s}\\n' "$H_STATE" "$H_REASON" "$H_DNS6" "$([ "$H_STATE" = healthy ] && echo true || echo false)"''')
rep('''  DNSV=$(sed -n 's/^DNS=//p' "$SESSION" 2>/dev/null | head -n 1); [ -n "$DNSV" ] || DNSV=off''','''  DNSV=$(sed -n 's/^DNS=//p' "$SESSION" 2>/dev/null | head -n 1); [ -n "$DNSV" ] || DNSV=off
  DNS6POLICY=$(sed -n 's/^DNS6_POLICY=//p' "$SESSION" 2>/dev/null | head -n 1)
  case "$DNS6POLICY" in redirect|blocked-no-nat|blocked-no-redirect|off|core) ;; *) DNS6POLICY=unknown;; esac''')
rep('''        if v6supported && { [ "$IPV6V" = enable ] || [ "$IPV6V" = disable ]; }; then
          DNS6=$D6O
          [ "$SHAREV" != 1 ] || [ "$D6P" = true ] || DNS6=false
        fi''','''        if v6supported && { [ "$IPV6V" = enable ] || [ "$IPV6V" = disable ]; }; then
          case "$DNS6POLICY" in
            blocked-no-nat|blocked-no-redirect)
              DNS6=false
              if v6_disable_guard_ready "$SHAREV" &&
                 xt6q -t filter -C "$V6OUT" -p tcp --dport 53 -j REJECT >/dev/null 2>&1 &&
                 xt6q -t filter -C "$V6OUT" -p udp --dport 53 -j REJECT >/dev/null 2>&1; then DNS6=true; fi;;
            *) DNS6=$D6O; [ "$SHAREV" != 1 ] || [ "$D6P" = true ] || DNS6=false;;
          esac
        fi''')
rep('''"dnsMode":"%s","dnsIpv4Rule"''','''"dnsMode":"%s","ipv6DnsPolicy":"%s","dnsIpv4Rule"''')
rep('''"$DISABLE6" "$DNSV" "$DNS4"''','''"$DISABLE6" "$DNSV" "$DNS6POLICY" "$DNS4"''')
rep('''  for P in $STOP_PIDS; do kill "$P" >/dev/null 2>&1 || true; done''','''  if [ -d "$RUN" ]; then start_stage "stop-core-signals"; fi
  for P in $STOP_PIDS; do kill "$P" >/dev/null 2>&1 || true; done''')
f.write_text(t)
f=r/'android-app/app/build.gradle.kts';t=f.read_text();assert 'versionCode = 498' in t;t=t.replace('versionCode = 498','versionCode = 499').replace('0.4.0-test.98','0.4.0-test.99');f.write_text(t)
f=r/'tests/ui-runtime-baseline.json';d=json.loads(f.read_text());key='android-app/app/src/main/assets/hetu-root.sh';d[key]=hashlib.sha256((r/key).read_bytes()).hexdigest();f.write_text(json.dumps(d,indent=2)+'\n')
f=r/'tools/test_network_namespace.py';t=f.read_text();needle="print('Real Linux netns:";i=t.index(needle);t=t[:i]+(r/'maintenance/network99-kernel.txt').read_text()+t[i:];f.write_text(t)
print('test99 applied; root SHA256',d[key])
