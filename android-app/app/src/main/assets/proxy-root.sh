#!/system/bin/sh
# Bichen Root transparent proxy controller. JSON only on stdout.
set -u
umask 077
BASE=/data/adb/bichen/proxy
RUN="$BASE/run"
PIDFILE="$RUN/core.pid"
MODEFILE="$RUN/mode"
LOG="$RUN/core.log"
IPV6_STATE="$RUN/ipv6.state"
TABLE=100
MARK=0x2333
MASK=0xffff
PREF=10000
MOUT=BICHEN_MOUT
MPRE=BICHEN_MPRE
NOUT=BICHEN_NOUT
NPRE=BICHEN_NPRE

ok(){ printf '{"ok":true,"message":"%s"}\n' "$1"; }
fail(){ printf '{"ok":false,"message":"%s"}\n' "$1"; exit 1; }
root(){ [ "$(id -u)" = 0 ] || fail "需要 Root 权限"; }
has(){ command -v "$1" >/dev/null 2>&1; }
port(){ case "${1:-}" in ''|*[!0-9]*) return 1;; esac; [ "$1" -ge 1 ] && [ "$1" -le 65535 ]; }
mode(){ case "${1:-}" in tproxy|redirect|enhance) return 0;; *) return 1;; esac; }
ipv6mode(){ case "${1:-}" in enable|bypass|disable) return 0;; *) return 1;; esac; }

cleanup4(){
 iptables -t mangle -D OUTPUT -j "$MOUT" >/dev/null 2>&1 || true
 iptables -t mangle -D PREROUTING -j "$MPRE" >/dev/null 2>&1 || true
 iptables -t mangle -F "$MOUT" >/dev/null 2>&1 || true; iptables -t mangle -X "$MOUT" >/dev/null 2>&1 || true
 iptables -t mangle -F "$MPRE" >/dev/null 2>&1 || true; iptables -t mangle -X "$MPRE" >/dev/null 2>&1 || true
 iptables -t nat -D OUTPUT -j "$NOUT" >/dev/null 2>&1 || true
 iptables -t nat -D PREROUTING -j "$NPRE" >/dev/null 2>&1 || true
 iptables -t nat -F "$NOUT" >/dev/null 2>&1 || true; iptables -t nat -X "$NOUT" >/dev/null 2>&1 || true
 iptables -t nat -F "$NPRE" >/dev/null 2>&1 || true; iptables -t nat -X "$NPRE" >/dev/null 2>&1 || true
 ip rule del pref "$PREF" fwmark "$MARK/$MASK" table "$TABLE" >/dev/null 2>&1 || true
 ip route flush table "$TABLE" >/dev/null 2>&1 || true
}
cleanup6(){
 has ip6tables || return 0
 ip6tables -t mangle -D OUTPUT -j "$MOUT" >/dev/null 2>&1 || true
 ip6tables -t mangle -D PREROUTING -j "$MPRE" >/dev/null 2>&1 || true
 ip6tables -t mangle -F "$MOUT" >/dev/null 2>&1 || true; ip6tables -t mangle -X "$MOUT" >/dev/null 2>&1 || true
 ip6tables -t mangle -F "$MPRE" >/dev/null 2>&1 || true; ip6tables -t mangle -X "$MPRE" >/dev/null 2>&1 || true
 ip6tables -t nat -D OUTPUT -j "$NOUT" >/dev/null 2>&1 || true
 ip6tables -t nat -D PREROUTING -j "$NPRE" >/dev/null 2>&1 || true
 ip6tables -t nat -F "$NOUT" >/dev/null 2>&1 || true; ip6tables -t nat -X "$NOUT" >/dev/null 2>&1 || true
 ip6tables -t nat -F "$NPRE" >/dev/null 2>&1 || true; ip6tables -t nat -X "$NPRE" >/dev/null 2>&1 || true
 ip -6 rule del pref "$PREF" fwmark "$MARK/$MASK" table "$TABLE" >/dev/null 2>&1 || true
 ip -6 route flush table "$TABLE" >/dev/null 2>&1 || true
}
cleanup(){ cleanup4; cleanup6; }

stopcore(){
 if [ -f "$PIDFILE" ]; then P=$(cat "$PIDFILE" 2>/dev/null || true); case "$P" in ''|*[!0-9]*) ;; *) if kill -0 "$P" >/dev/null 2>&1; then kill "$P" >/dev/null 2>&1 || true; N=0; while kill -0 "$P" >/dev/null 2>&1 && [ "$N" -lt 20 ]; do sleep 0.1; N=$((N+1)); done; kill -9 "$P" >/dev/null 2>&1 || true; fi;; esac; fi
 rm -f "$PIDFILE" "$MODEFILE"
}

v6active(){ [ -r /proc/net/if_inet6 ] && [ -s /proc/net/if_inet6 ] && ip -6 route show default 2>/dev/null | grep -q '^default'; }
savev6(){ mkdir -p "$RUN" || return 1; : > "$IPV6_STATE" || return 1; FOUND=0; for P in /proc/sys/net/ipv6/conf/*/disable_ipv6; do [ -r "$P" ] || continue; V=$(cat "$P" 2>/dev/null) || continue; printf '%s\t%s\n' "$P" "$V" >> "$IPV6_STATE" || return 1; FOUND=1; done; [ "$FOUND" = 1 ]; }
restorev6(){ [ -f "$IPV6_STATE" ] || return 0; while IFS="$(printf '\t')" read -r P V; do case "$P" in /proc/sys/net/ipv6/conf/*/disable_ipv6) ;; *) continue;; esac; case "$V" in 0|1) [ -w "$P" ] && printf '%s\n' "$V" > "$P" 2>/dev/null || true;; esac; done < "$IPV6_STATE"; rm -f "$IPV6_STATE"; }
disablev6(){ savev6 || return 1; CH=0; for P in /proc/sys/net/ipv6/conf/*/disable_ipv6; do [ -w "$P" ] || continue; printf '1\n' > "$P" 2>/dev/null || { restorev6; return 1; }; CH=1; done; [ "$CH" = 1 ] || { restorev6; return 1; }; }

bypass4(){ C="$1"; T="$2"; for NET in 0.0.0.0/8 10.0.0.0/8 100.64.0.0/10 127.0.0.0/8 169.254.0.0/16 172.16.0.0/12 192.168.0.0/16 224.0.0.0/4 240.0.0.0/4; do iptables -t "$T" -A "$C" -d "$NET" -j RETURN || return 1; done; }
bypass6(){ C="$1"; T="$2"; for NET in ::1/128 fc00::/7 fe80::/10 ff00::/8; do ip6tables -t "$T" -A "$C" -d "$NET" -j RETURN || return 1; done; }

probetp4(){ P="$1"; iptables -t mangle -N BICHEN_PROBE >/dev/null 2>&1 || true; iptables -t mangle -F BICHEN_PROBE >/dev/null 2>&1 || true; iptables -t mangle -A BICHEN_PROBE -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" >/dev/null 2>&1; R=$?; iptables -t mangle -F BICHEN_PROBE >/dev/null 2>&1 || true; iptables -t mangle -X BICHEN_PROBE >/dev/null 2>&1 || true; return "$R"; }
probered4(){ P="$1"; iptables -t nat -N BICHEN_PROBE >/dev/null 2>&1 || true; iptables -t nat -F BICHEN_PROBE >/dev/null 2>&1 || true; iptables -t nat -A BICHEN_PROBE -p tcp -j REDIRECT --to-ports "$P" >/dev/null 2>&1; R=$?; iptables -t nat -F BICHEN_PROBE >/dev/null 2>&1 || true; iptables -t nat -X BICHEN_PROBE >/dev/null 2>&1 || true; return "$R"; }
probetp6(){ P="$1"; has ip6tables || return 1; ip6tables -t mangle -N BICHEN_PROBE >/dev/null 2>&1 || true; ip6tables -t mangle -F BICHEN_PROBE >/dev/null 2>&1 || true; ip6tables -t mangle -A BICHEN_PROBE -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" >/dev/null 2>&1; R=$?; ip6tables -t mangle -F BICHEN_PROBE >/dev/null 2>&1 || true; ip6tables -t mangle -X BICHEN_PROBE >/dev/null 2>&1 || true; return "$R"; }
probered6(){ P="$1"; has ip6tables || return 1; ip6tables -t nat -N BICHEN_PROBE >/dev/null 2>&1 || true; ip6tables -t nat -F BICHEN_PROBE >/dev/null 2>&1 || true; ip6tables -t nat -A BICHEN_PROBE -p tcp -j REDIRECT --to-ports "$P" >/dev/null 2>&1; R=$?; ip6tables -t nat -F BICHEN_PROBE >/dev/null 2>&1 || true; ip6tables -t nat -X BICHEN_PROBE >/dev/null 2>&1 || true; return "$R"; }

preflight(){ M="$1"; TP="$2"; RP="$3"; V6="$4"; root; mode "$M" || fail "运行模式无效"; ipv6mode "$V6" || fail "IPv6 模式无效"; has ip || fail "系统缺少 ip 命令"; has iptables || fail "系统缺少 iptables"; case "$M" in tproxy) port "$TP" || fail "TPROXY 端口无效"; probetp4 "$TP" || fail "当前内核或 iptables 不支持 TPROXY";; redirect) port "$RP" || fail "Redirect 端口无效"; probered4 "$RP" || fail "当前 iptables 不支持 REDIRECT";; enhance) port "$TP" || fail "TPROXY 端口无效"; port "$RP" || fail "Redirect 端口无效"; probetp4 "$TP" || fail "当前内核或 iptables 不支持 TPROXY"; probered4 "$RP" || fail "当前 iptables 不支持 REDIRECT";; esac; if [ "$V6" = enable ] && v6active; then case "$M" in tproxy) probetp6 "$TP" || fail "IPv6 TPROXY 不可用，可选择 IPv6 不进核心";; redirect) probered6 "$RP" || fail "IPv6 REDIRECT 不可用，可选择 IPv6 不进核心";; enhance) probetp6 "$TP" || fail "IPv6 TPROXY 不可用"; probered6 "$RP" || fail "IPv6 REDIRECT 不可用";; esac; fi; ok "Root 代理预检通过"; }

route4(){ ip route replace local 0.0.0.0/0 dev lo table "$TABLE" || return 1; ip rule add pref "$PREF" fwmark "$MARK/$MASK" table "$TABLE" >/dev/null 2>&1 || true; }
route6(){ ip -6 route replace local ::/0 dev lo table "$TABLE" || return 1; ip -6 rule add pref "$PREF" fwmark "$MARK/$MASK" table "$TABLE" >/dev/null 2>&1 || true; }

tproxy4(){ P="$1"; PROTO="$2"; route4 || return 1; iptables -t mangle -N "$MOUT" || return 1; iptables -t mangle -N "$MPRE" || return 1; iptables -t mangle -A "$MOUT" -m owner --uid-owner 0 -j RETURN || return 1; bypass4 "$MOUT" mangle || return 1; bypass4 "$MPRE" mangle || return 1; for X in $PROTO; do iptables -t mangle -A "$MOUT" -p "$X" -j MARK --set-xmark "$MARK/$MASK" || return 1; iptables -t mangle -A "$MPRE" -p "$X" -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; done; iptables -t mangle -A OUTPUT -j "$MOUT" || return 1; iptables -t mangle -A PREROUTING -j "$MPRE" || return 1; }
tproxy6(){ P="$1"; PROTO="$2"; v6active || return 0; route6 || return 1; ip6tables -t mangle -N "$MOUT" || return 1; ip6tables -t mangle -N "$MPRE" || return 1; ip6tables -t mangle -A "$MOUT" -m owner --uid-owner 0 -j RETURN || return 1; bypass6 "$MOUT" mangle || return 1; bypass6 "$MPRE" mangle || return 1; for X in $PROTO; do ip6tables -t mangle -A "$MOUT" -p "$X" -j MARK --set-xmark "$MARK/$MASK" || return 1; ip6tables -t mangle -A "$MPRE" -p "$X" -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; done; ip6tables -t mangle -A OUTPUT -j "$MOUT" || return 1; ip6tables -t mangle -A PREROUTING -j "$MPRE" || return 1; }
redirect4(){ P="$1"; iptables -t nat -N "$NOUT" || return 1; iptables -t nat -N "$NPRE" || return 1; iptables -t nat -A "$NOUT" -m owner --uid-owner 0 -j RETURN || return 1; bypass4 "$NOUT" nat || return 1; bypass4 "$NPRE" nat || return 1; iptables -t nat -A "$NOUT" -p tcp -j REDIRECT --to-ports "$P" || return 1; iptables -t nat -A "$NPRE" -p tcp -j REDIRECT --to-ports "$P" || return 1; iptables -t nat -A OUTPUT -j "$NOUT" || return 1; iptables -t nat -A PREROUTING -j "$NPRE" || return 1; }
redirect6(){ P="$1"; v6active || return 0; ip6tables -t nat -N "$NOUT" || return 1; ip6tables -t nat -N "$NPRE" || return 1; ip6tables -t nat -A "$NOUT" -m owner --uid-owner 0 -j RETURN || return 1; bypass6 "$NOUT" nat || return 1; bypass6 "$NPRE" nat || return 1; ip6tables -t nat -A "$NOUT" -p tcp -j REDIRECT --to-ports "$P" || return 1; ip6tables -t nat -A "$NPRE" -p tcp -j REDIRECT --to-ports "$P" || return 1; ip6tables -t nat -A OUTPUT -j "$NOUT" || return 1; ip6tables -t nat -A PREROUTING -j "$NPRE" || return 1; }

start(){ BIN="$1"; CFG="$2"; M="$3"; TP="$4"; RP="$5"; V6="$6"; preflight "$M" "$TP" "$RP" "$V6" >/dev/null; [ -x "$BIN" ] || fail "核心文件不存在或不可执行"; [ -r "$CFG" ] || fail "启动配置不存在"; mkdir -p "$RUN" || fail "无法创建运行目录"; cleanup; restorev6; stopcore; [ "$V6" = disable ] && disablev6 || true; : > "$LOG"; "$BIN" -d "$RUN" -f "$CFG" >>"$LOG" 2>&1 & P=$!; printf '%s\n' "$P" > "$PIDFILE"; printf '%s\n' "$M" > "$MODEFILE"; sleep 1; if ! kill -0 "$P" >/dev/null 2>&1; then stopcore; restorev6; fail "核心启动失败，请查看运行日志"; fi; case "$M" in tproxy) tproxy4 "$TP" "tcp udp" || { cleanup; stopcore; restorev6; fail "TPROXY 规则安装失败，已回滚"; };; redirect) redirect4 "$RP" || { cleanup; stopcore; restorev6; fail "Redirect 规则安装失败，已回滚"; };; enhance) redirect4 "$RP" && tproxy4 "$TP" "udp" || { cleanup; stopcore; restorev6; fail "Enhance 规则安装失败，已回滚"; };; esac; if [ "$V6" = enable ]; then case "$M" in tproxy) tproxy6 "$TP" "tcp udp";; redirect) redirect6 "$RP";; enhance) redirect6 "$RP" && tproxy6 "$TP" "udp";; esac || { cleanup; stopcore; restorev6; fail "IPv6 透明代理规则安装失败，已回滚"; }; fi; ok "Root $M 已启动"; }
status(){ root; R=false; P=0; if [ -f "$PIDFILE" ]; then X=$(cat "$PIDFILE" 2>/dev/null || true); case "$X" in ''|*[!0-9]*) ;; *) if kill -0 "$X" >/dev/null 2>&1; then R=true;P="$X"; fi;; esac; fi; M=$(cat "$MODEFILE" 2>/dev/null || echo none); printf '{"ok":true,"running":%s,"pid":%s,"mode":"%s","log":"%s"}\n' "$R" "$P" "$M" "$LOG"; }

case "${1:-status}" in
 preflight) [ "$#" = 5 ] || fail "参数错误"; preflight "$2" "$3" "$4" "$5";;
 start) [ "$#" = 7 ] || fail "参数错误"; root; start "$2" "$3" "$4" "$5" "$6" "$7";;
 stop) root; cleanup; stopcore; restorev6; ok "Root 代理已停止并恢复网络状态";;
 status) status;;
 *) fail "未知 Root 代理操作";;
esac
