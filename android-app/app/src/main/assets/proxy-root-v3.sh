#!/system/bin/sh
# Bichen Root transparent proxy controller v3. JSON only on stdout.
set -u
umask 077

BASE=/data/adb/bichen/proxy
RUN="$BASE/run"
PIDFILE="$RUN/core.pid"
MODEFILE="$RUN/mode"
SESSION="$RUN/session.state"
LOG="$RUN/core.log"
CHECKLOG="$RUN/config-check.log"
IPV6_STATE="$RUN/ipv6.state"
NET_STATE="$RUN/net.state"
WATCHDOG_PID="$RUN/watchdog.pid"
WATCHDOG_LOG="$RUN/watchdog.log"
CRASH_STATE="$RUN/last-crash"
LOCK_DIR="$RUN/.txn.lock"

BYPASS_MARK=0x08000000
BYPASS_MASK=0x08000000
PROBE_MARK=0x04000000
PROBE_MASK=0x04000000
MARK=""
MASK=""
TABLE=""
PREF=""
LOCK_HELD=0

LEGACY_MARK=0x2333
LEGACY_MASK=0xffff
LEGACY_TABLE=100
LEGACY_PREF=10000

MOUT=BICHEN_MOUT
MPRE=BICHEN_MPRE
NOUT=BICHEN_NOUT
NPRE=BICHEN_NPRE
DNSOUT=BICHEN_DNSOUT
DNSPRE=BICHEN_DNSPRE
QUICOUT=BICHEN_QUICOUT
QUICFWD=BICHEN_QUICFWD
V6OUT=BICHEN_V6OUT
V6FWD=BICHEN_V6FWD
KOUT=BICHEN_KOUT
KFWD=BICHEN_KFWD

ok(){ printf '{"ok":true,"message":"%s"}\n' "$1"; }
fail(){ printf '{"ok":false,"message":"%s"}\n' "$1"; exit 1; }
root(){ [ "$(id -u)" = 0 ] || fail "需要 Root 权限"; }
has(){ command -v "$1" >/dev/null 2>&1; }
port(){ case "${1:-}" in ''|*[!0-9]*) return 1;; esac; [ "$1" -ge 1 ] && [ "$1" -le 65535 ]; }
mode(){ case "${1:-}" in tproxy|redirect|enhance) return 0;; *) return 1;; esac; }
ipv6mode(){ case "${1:-}" in enable|bypass|strict|disable) return 0;; *) return 1;; esac; }
dnsmode(){ case "${1:-}" in off|tproxy|redirect) return 0;; *) return 1;; esac; }
scope(){ case "${1:-}" in core|blacklist|whitelist) return 0;; *) return 1;; esac; }
bool(){ case "${1:-}" in 0|1) return 0;; *) return 1;; esac; }

release_lock(){
  if [ "$LOCK_HELD" = 1 ]; then rm -rf "$LOCK_DIR" >/dev/null 2>&1 || true; LOCK_HELD=0; fi
}
trap 'release_lock' EXIT

acquire_lock(){
  mkdir -p "$RUN" || return 1
  N=0
  while ! mkdir "$LOCK_DIR" >/dev/null 2>&1; do
    OWNER=$(cat "$LOCK_DIR/pid" 2>/dev/null || true)
    case "$OWNER" in ''|*[!0-9]*) ;; *) if ! kill -0 "$OWNER" >/dev/null 2>&1; then rm -rf "$LOCK_DIR" >/dev/null 2>&1 || true; continue; fi;; esac
    N=$((N+1)); [ "$N" -lt 100 ] || return 1; sleep 0.05
  done
  printf '%s\n' "$$" > "$LOCK_DIR/pid"; LOCK_HELD=1
}

state_value(){ [ -r "$NET_STATE" ] || return 1; sed -n "s/^${1}=//p" "$NET_STATE" 2>/dev/null | head -n 1; }
loadnet(){
  [ -r "$NET_STATE" ] || return 1
  M=$(state_value MARK || true); K=$(state_value MASK || true); T=$(state_value TABLE || true); P=$(state_value PREF || true)
  case "$M" in 0x*) ;; *) return 1;; esac; case "$K" in 0x*) ;; *) return 1;; esac
  case "$T" in ''|*[!0-9]*) return 1;; esac; case "$P" in ''|*[!0-9]*) return 1;; esac
  MARK="$M"; MASK="$K"; TABLE="$T"; PREF="$P"
}
savenet(){
  mkdir -p "$RUN" || return 1; TMP="$NET_STATE.new.$$"
  { printf 'MARK=%s\n' "$MARK"; printf 'MASK=%s\n' "$MASK"; printf 'TABLE=%s\n' "$TABLE"; printf 'PREF=%s\n' "$PREF"; } > "$TMP" || return 1
  chmod 600 "$TMP" >/dev/null 2>&1 || true; mv -f "$TMP" "$NET_STATE"
}
markused(){
  C="$1"
  ip rule show 2>/dev/null | grep -qi "fwmark ${C}" && return 0
  ip -6 rule show 2>/dev/null | grep -qi "fwmark ${C}" && return 0
  has iptables-save && iptables-save -t mangle 2>/dev/null | grep -qi "${C}" && return 0
  has ip6tables-save && ip6tables-save -t mangle 2>/dev/null | grep -qi "${C}" && return 0
  return 1
}
tableused(){
  C="$1"
  ip rule show 2>/dev/null | grep -Eq "(lookup|table)[[:space:]]+${C}([[:space:]]|$)" && return 0
  ip -6 rule show 2>/dev/null | grep -Eq "(lookup|table)[[:space:]]+${C}([[:space:]]|$)" && return 0
  ip route show table "$C" 2>/dev/null | grep -q . && return 0
  ip -6 route show table "$C" 2>/dev/null | grep -q . && return 0
  return 1
}
prefused(){ C="$1"; ip rule show 2>/dev/null | grep -Eq "^[[:space:]]*${C}:" && return 0; ip -6 rule show 2>/dev/null | grep -Eq "^[[:space:]]*${C}:" && return 0; return 1; }
allocnet(){
  MARK=""; MASK=""; TABLE=""; PREF=""
  for C in 0x200000 0x400000 0x800000 0x1000000 0x2000000 0x4000000 0x10000000; do if ! markused "$C"; then MARK="$C"; MASK="$C"; break; fi; done
  [ -n "$MARK" ] || return 1
  T=20260; while [ "$T" -le 20299 ]; do if ! tableused "$T"; then TABLE="$T"; break; fi; T=$((T+1)); done; [ -n "$TABLE" ] || return 1
  P=28700; while [ "$P" -le 28799 ]; do if ! prefused "$P"; then PREF="$P"; break; fi; P=$((P+1)); done; [ -n "$PREF" ] || return 1
  savenet
}

cleanlegacy(){
  ip rule del pref "$LEGACY_PREF" fwmark "$LEGACY_MARK/$LEGACY_MASK" table "$LEGACY_TABLE" >/dev/null 2>&1 || true
  ip -6 rule del pref "$LEGACY_PREF" fwmark "$LEGACY_MARK/$LEGACY_MASK" table "$LEGACY_TABLE" >/dev/null 2>&1 || true
  ip route del local 0.0.0.0/0 dev lo table "$LEGACY_TABLE" >/dev/null 2>&1 || true
  ip -6 route del local ::/0 dev lo table "$LEGACY_TABLE" >/dev/null 2>&1 || true
}
unhook(){
  BIN="$1"; T="$2"; BASECHAIN="$3"; CHAIN="$4"
  while "$BIN" -t "$T" -C "$BASECHAIN" -j "$CHAIN" >/dev/null 2>&1; do "$BIN" -t "$T" -D "$BASECHAIN" -j "$CHAIN" >/dev/null 2>&1 || break; done
  "$BIN" -t "$T" -F "$CHAIN" >/dev/null 2>&1 || true; "$BIN" -t "$T" -X "$CHAIN" >/dev/null 2>&1 || true
}
cleanup4(){
  unhook iptables mangle OUTPUT "$MOUT"; unhook iptables mangle PREROUTING "$MPRE"
  unhook iptables nat OUTPUT "$DNSOUT"; unhook iptables nat PREROUTING "$DNSPRE"
  unhook iptables nat OUTPUT "$NOUT"; unhook iptables nat PREROUTING "$NPRE"
  unhook iptables filter OUTPUT "$QUICOUT"; unhook iptables filter FORWARD "$QUICFWD"
  unhook iptables filter OUTPUT "$KOUT"; unhook iptables filter FORWARD "$KFWD"
  if [ -n "$MARK" ] && [ -n "$MASK" ] && [ -n "$TABLE" ] && [ -n "$PREF" ]; then
    ip rule del pref "$PREF" fwmark "$MARK/$MASK" table "$TABLE" >/dev/null 2>&1 || true
    ip route del local 0.0.0.0/0 dev lo table "$TABLE" >/dev/null 2>&1 || true
  fi
}
cleanup6(){
  has ip6tables || return 0
  unhook ip6tables mangle OUTPUT "$MOUT"; unhook ip6tables mangle PREROUTING "$MPRE"
  unhook ip6tables nat OUTPUT "$DNSOUT"; unhook ip6tables nat PREROUTING "$DNSPRE"
  unhook ip6tables nat OUTPUT "$NOUT"; unhook ip6tables nat PREROUTING "$NPRE"
  unhook ip6tables filter OUTPUT "$QUICOUT"; unhook ip6tables filter FORWARD "$QUICFWD"
  unhook ip6tables filter OUTPUT "$V6OUT"; unhook ip6tables filter FORWARD "$V6FWD"
  unhook ip6tables filter OUTPUT "$KOUT"; unhook ip6tables filter FORWARD "$KFWD"
  if [ -n "$MARK" ] && [ -n "$MASK" ] && [ -n "$TABLE" ] && [ -n "$PREF" ]; then
    ip -6 rule del pref "$PREF" fwmark "$MARK/$MASK" table "$TABLE" >/dev/null 2>&1 || true
    ip -6 route del local ::/0 dev lo table "$TABLE" >/dev/null 2>&1 || true
  fi
}
cleanup(){ MARK=""; MASK=""; TABLE=""; PREF=""; loadnet >/dev/null 2>&1 || true; cleanup4; cleanup6; cleanlegacy; rm -f "$NET_STATE"; MARK=""; MASK=""; TABLE=""; PREF=""; }

pidcore(){
  P="$1"; [ -d "/proc/$P" ] || return 1
  CMD=$(tr '\000' ' ' < "/proc/$P/cmdline" 2>/dev/null || true)
  EXE=$(readlink "/proc/$P/exe" 2>/dev/null || true)
  case "$CMD $EXE" in *"$BASE/bin/core"*) return 0;; *) return 1;; esac
}
stopwatchdog(){
  [ -f "$WATCHDOG_PID" ] || return 0; W=$(cat "$WATCHDOG_PID" 2>/dev/null || true)
  case "$W" in ''|*[!0-9]*) ;; *) if [ "$W" != "$$" ] && kill -0 "$W" >/dev/null 2>&1; then kill "$W" >/dev/null 2>&1 || true; fi;; esac
  rm -f "$WATCHDOG_PID"
}
stopcore(){
  if [ -f "$PIDFILE" ]; then P=$(cat "$PIDFILE" 2>/dev/null || true); case "$P" in ''|*[!0-9]*) ;; *)
    if pidcore "$P" && kill -0 "$P" >/dev/null 2>&1; then kill "$P" >/dev/null 2>&1 || true; N=0; while kill -0 "$P" >/dev/null 2>&1 && [ "$N" -lt 20 ]; do sleep 0.1; N=$((N+1)); done; if pidcore "$P" && kill -0 "$P" >/dev/null 2>&1; then kill -9 "$P" >/dev/null 2>&1 || true; fi; fi;; esac; fi
  # Recover orphaned Bichen cores left by a killed/reinstalled app. Match the private
  # absolute path only; do not touch Mihomo/Clash processes owned by other apps.
  for PROC in /proc/[0-9]*; do
    OPID=${PROC#/proc/}; [ "$OPID" != "$$" ] || continue; [ -r "$PROC/cmdline" ] || continue
    OCMD=$(tr '\000' ' ' < "$PROC/cmdline" 2>/dev/null || true)
    OEXE=$(readlink "$PROC/exe" 2>/dev/null || true)
    case "$OCMD $OEXE" in *"$BASE/bin/core"*)
      kill "$OPID" >/dev/null 2>&1 || true
      N=0; while kill -0 "$OPID" >/dev/null 2>&1 && [ "$N" -lt 20 ]; do sleep 0.1; N=$((N+1)); done
      kill -0 "$OPID" >/dev/null 2>&1 && kill -9 "$OPID" >/dev/null 2>&1 || true
    ;; esac
  done
  rm -f "$PIDFILE" "$MODEFILE"
}

v6active(){ [ -r /proc/net/if_inet6 ] && [ -s /proc/net/if_inet6 ] && ip -6 route show default 2>/dev/null | grep -q '^default'; }
savev6(){
  mkdir -p "$RUN" || return 1; : > "$IPV6_STATE" || return 1; FOUND=0
  for P in /proc/sys/net/ipv6/conf/*/disable_ipv6; do [ -r "$P" ] || continue; V=$(cat "$P" 2>/dev/null) || continue; printf '%s\t%s\n' "$P" "$V" >> "$IPV6_STATE" || return 1; FOUND=1; done
  [ "$FOUND" = 1 ]
}
restorev6(){
  [ -f "$IPV6_STATE" ] || return 0
  while IFS="$(printf '\t')" read -r P V; do case "$P" in /proc/sys/net/ipv6/conf/*/disable_ipv6) ;; *) continue;; esac; case "$V" in 0|1) [ -w "$P" ] && printf '%s\n' "$V" > "$P" 2>/dev/null || true;; esac; done < "$IPV6_STATE"
  rm -f "$IPV6_STATE"
}
disablev6(){ savev6 || return 1; CH=0; for P in /proc/sys/net/ipv6/conf/*/disable_ipv6; do [ -w "$P" ] || continue; printf '1\n' > "$P" 2>/dev/null || { restorev6; return 1; }; CH=1; done; [ "$CH" = 1 ] || { restorev6; return 1; }; }

split_safe_uids(){
  LIST="$1"; [ -z "$LIST" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS
  for U in "$@"; do case "$U" in ''|*[!0-9-]*) return 1;; esac; case "$U" in *-*) A=${U%-*}; B=${U#*-};; *) A=$U; B=$U;; esac; case "$A$B" in *[!0-9]*) return 1;; esac; [ "$A" -ge 10000 ] && [ "$B" -ge "$A" ] || return 1; done
}
split_safe_cidrs(){
  LIST="$1"; [ -z "$LIST" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS
  for X in "$@"; do case "$X" in ''|*[!0-9A-Fa-f:./]*|*//*|/*|*/) return 1;; esac; case "$X" in */*) ;; *) return 1;; esac; done
}
split_safe_ifaces(){
  LIST="$1"; [ -z "$LIST" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS
  for X in "$@"; do case "$X" in ''|*[!A-Za-z0-9_.:@+-]*) return 1;; esac; [ "$X" != lo ] && [ "$X" != 'lo+' ] || return 1; done
}
first_uid(){ LIST="$1"; OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS; printf '%s' "${1:-}"; }

iface_out(){ BIN="$1"; T="$2"; C="$3"; LIST="$4"; [ -z "$LIST" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS; for X in "$@"; do "$BIN" -t "$T" -A "$C" -o "$X" -j RETURN || return 1; done; }
iface_in(){ BIN="$1"; T="$2"; C="$3"; LIST="$4"; [ -z "$LIST" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS; for X in "$@"; do "$BIN" -t "$T" -A "$C" -i "$X" -j RETURN || return 1; done; }
blacklist_returns(){ BIN="$1"; T="$2"; C="$3"; S="$4"; LIST="$5"; [ "$S" = blacklist ] || return 0; [ -z "$LIST" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS; for U in "$@"; do "$BIN" -t "$T" -A "$C" -m owner --uid-owner "$U" -j RETURN || return 1; done; }
append_scoped(){
  BIN="$1"; T="$2"; C="$3"; S="$4"; LIST="$5"; shift 5
  if [ "$S" = whitelist ]; then OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS; for U in "$@"; do :; done; fi
}
# append_scoped cannot keep both a parsed UID list and the original rule arguments in pure POSIX sh,
# so the concrete scoped rule helpers below keep the rule shape explicit and audit-friendly.
scoped_mark(){
  BIN="$1"; T="$2"; C="$3"; S="$4"; LIST="$5"; PROTO="$6"; DPORT="$7"; MARKV="$8"
  if [ "$S" = whitelist ]; then OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS; for U in "$@"; do if [ -n "$DPORT" ]; then "$BIN" -t "$T" -A "$C" -m owner --uid-owner "$U" -p "$PROTO" --dport "$DPORT" -j MARK --set-xmark "$MARKV" || return 1; else "$BIN" -t "$T" -A "$C" -m owner --uid-owner "$U" -p "$PROTO" -j MARK --set-xmark "$MARKV" || return 1; fi; done
  else if [ -n "$DPORT" ]; then "$BIN" -t "$T" -A "$C" -p "$PROTO" --dport "$DPORT" -j MARK --set-xmark "$MARKV" || return 1; else "$BIN" -t "$T" -A "$C" -p "$PROTO" -j MARK --set-xmark "$MARKV" || return 1; fi; fi
}
scoped_redirect(){
  BIN="$1"; T="$2"; C="$3"; S="$4"; LIST="$5"; PROTO="$6"; DPORT="$7"; TO="$8"
  if [ "$S" = whitelist ]; then OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS; for U in "$@"; do if [ -n "$DPORT" ]; then "$BIN" -t "$T" -A "$C" -m owner --uid-owner "$U" -p "$PROTO" --dport "$DPORT" -j REDIRECT --to-ports "$TO" || return 1; else "$BIN" -t "$T" -A "$C" -m owner --uid-owner "$U" -p "$PROTO" -j REDIRECT --to-ports "$TO" || return 1; fi; done
  else if [ -n "$DPORT" ]; then "$BIN" -t "$T" -A "$C" -p "$PROTO" --dport "$DPORT" -j REDIRECT --to-ports "$TO" || return 1; else "$BIN" -t "$T" -A "$C" -p "$PROTO" -j REDIRECT --to-ports "$TO" || return 1; fi; fi
}
scoped_drop_quic(){
  BIN="$1"; C="$2"; S="$3"; LIST="$4"
  if [ "$S" = whitelist ]; then OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS; for U in "$@"; do "$BIN" -t filter -A "$C" -m owner --uid-owner "$U" -p udp --dport 443 -j DROP || return 1; done
  else "$BIN" -t filter -A "$C" -p udp --dport 443 -j DROP || return 1; fi
}
scoped_reject_all(){
  BIN="$1"; C="$2"; S="$3"; LIST="$4"
  if [ "$S" = whitelist ]; then OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS; for U in "$@"; do "$BIN" -t filter -A "$C" -m owner --uid-owner "$U" -j REJECT || return 1; done
  else "$BIN" -t filter -A "$C" -j REJECT || return 1; fi
}

bypass4(){
  C="$1"; T="$2"; CIDRS="$3"
  for NET in 0.0.0.0/8 10.0.0.0/8 100.64.0.0/10 127.0.0.0/8 169.254.0.0/16 172.16.0.0/12 192.168.0.0/16 224.0.0.0/4 240.0.0.0/4; do iptables -t "$T" -A "$C" -d "$NET" -j RETURN || return 1; done
  [ -z "$CIDRS" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $CIDRS; IFS=$OLDIFS; for NET in "$@"; do case "$NET" in *:*) ;; *) iptables -t "$T" -A "$C" -d "$NET" -j RETURN || return 1;; esac; done
}
bypass6(){
  C="$1"; T="$2"; CIDRS="$3"
  for NET in ::1/128 fc00::/7 fe80::/10 ff00::/8; do ip6tables -t "$T" -A "$C" -d "$NET" -j RETURN || return 1; done
  [ -z "$CIDRS" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $CIDRS; IFS=$OLDIFS; for NET in "$@"; do case "$NET" in *:*) ip6tables -t "$T" -A "$C" -d "$NET" -j RETURN || return 1;; esac; done
}

probetp4(){ P="$1"; iptables -t mangle -N BICHEN_PROBE >/dev/null 2>&1 || true; iptables -t mangle -F BICHEN_PROBE >/dev/null 2>&1 || true; iptables -t mangle -A BICHEN_PROBE -p udp -j TPROXY --on-port "$P" --tproxy-mark "$PROBE_MARK/$PROBE_MASK" >/dev/null 2>&1; R=$?; iptables -t mangle -F BICHEN_PROBE >/dev/null 2>&1 || true; iptables -t mangle -X BICHEN_PROBE >/dev/null 2>&1 || true; return "$R"; }
probetp6(){ P="$1"; has ip6tables || return 1; ip6tables -t mangle -N BICHEN_PROBE >/dev/null 2>&1 || true; ip6tables -t mangle -F BICHEN_PROBE >/dev/null 2>&1 || true; ip6tables -t mangle -A BICHEN_PROBE -p udp -j TPROXY --on-port "$P" --tproxy-mark "$PROBE_MARK/$PROBE_MASK" >/dev/null 2>&1; R=$?; ip6tables -t mangle -F BICHEN_PROBE >/dev/null 2>&1 || true; ip6tables -t mangle -X BICHEN_PROBE >/dev/null 2>&1 || true; return "$R"; }
probered4(){ P="$1"; PROTO="${2:-tcp}"; iptables -t nat -N BICHEN_PROBE >/dev/null 2>&1 || true; iptables -t nat -F BICHEN_PROBE >/dev/null 2>&1 || true; iptables -t nat -A BICHEN_PROBE -p "$PROTO" -j REDIRECT --to-ports "$P" >/dev/null 2>&1; R=$?; iptables -t nat -F BICHEN_PROBE >/dev/null 2>&1 || true; iptables -t nat -X BICHEN_PROBE >/dev/null 2>&1 || true; return "$R"; }
probered6(){ P="$1"; PROTO="${2:-tcp}"; has ip6tables || return 1; ip6tables -t nat -N BICHEN_PROBE >/dev/null 2>&1 || true; ip6tables -t nat -F BICHEN_PROBE >/dev/null 2>&1 || true; ip6tables -t nat -A BICHEN_PROBE -p "$PROTO" -j REDIRECT --to-ports "$P" >/dev/null 2>&1; R=$?; ip6tables -t nat -F BICHEN_PROBE >/dev/null 2>&1 || true; ip6tables -t nat -X BICHEN_PROBE >/dev/null 2>&1 || true; return "$R"; }
probeowner(){
  U="$1"; [ -n "$U" ] || return 0
  iptables -t mangle -N BICHEN_PROBE >/dev/null 2>&1 || true; iptables -t mangle -F BICHEN_PROBE >/dev/null 2>&1 || true
  iptables -t mangle -A BICHEN_PROBE -m owner --uid-owner "$U" -j RETURN >/dev/null 2>&1; R=$?
  iptables -t mangle -F BICHEN_PROBE >/dev/null 2>&1 || true; iptables -t mangle -X BICHEN_PROBE >/dev/null 2>&1 || true; return "$R"
}
probecidrs(){
  LIST="$1"; [ -z "$LIST" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS
  for X in "$@"; do
    if echo "$X" | grep -q ':'; then has ip6tables || return 1; ip6tables -t mangle -N BICHEN_PROBE >/dev/null 2>&1 || true; ip6tables -t mangle -F BICHEN_PROBE >/dev/null 2>&1 || true; ip6tables -t mangle -A BICHEN_PROBE -d "$X" -j RETURN >/dev/null 2>&1 || { ip6tables -t mangle -F BICHEN_PROBE >/dev/null 2>&1 || true; ip6tables -t mangle -X BICHEN_PROBE >/dev/null 2>&1 || true; return 1; }; ip6tables -t mangle -F BICHEN_PROBE >/dev/null 2>&1 || true; ip6tables -t mangle -X BICHEN_PROBE >/dev/null 2>&1 || true
    else iptables -t mangle -N BICHEN_PROBE >/dev/null 2>&1 || true; iptables -t mangle -F BICHEN_PROBE >/dev/null 2>&1 || true; iptables -t mangle -A BICHEN_PROBE -d "$X" -j RETURN >/dev/null 2>&1 || { iptables -t mangle -F BICHEN_PROBE >/dev/null 2>&1 || true; iptables -t mangle -X BICHEN_PROBE >/dev/null 2>&1 || true; return 1; }; iptables -t mangle -F BICHEN_PROBE >/dev/null 2>&1 || true; iptables -t mangle -X BICHEN_PROBE >/dev/null 2>&1 || true; fi
  done
}

preflight(){
  M="$1"; TP="$2"; RP="$3"; V6="$4"; TCP="$5"; UDP="$6"; DNS="$7"; QUIC="$8"; DP="$9"; CP="${10}"; SCOPE="${11}"; UIDS="${12}"; SHARE="${13}"; KILL="${14}"; CIDRS="${15}"; IFACES="${16}"
  root; mode "$M" || fail "运行模式无效"; ipv6mode "$V6" || fail "IPv6 模式无效"; dnsmode "$DNS" || fail "DNS 劫持模式无效"; scope "$SCOPE" || fail "应用范围无效"
  bool "$TCP" || fail "TCP 开关无效"; bool "$UDP" || fail "UDP 开关无效"; bool "$QUIC" || fail "QUIC 开关无效"; bool "$SHARE" || fail "共享网络开关无效"; bool "$KILL" || fail "Kill Switch 开关无效"
  port "$DP" || fail "DNS 监听端口无效"; port "$CP" || fail "控制接口端口无效"; has ip || fail "系统缺少 ip 命令"; has iptables || fail "系统缺少 iptables"
  split_safe_uids "$UIDS" || fail "应用 UID 列表无效"; split_safe_cidrs "$CIDRS" || fail "CIDR 绕过列表无效"; split_safe_ifaces "$IFACES" || fail "接口绕过列表无效"
  [ "$SCOPE" != whitelist ] || [ -n "$UIDS" ] || fail "仅所选应用代理模式没有可用 UID"
  if [ "$SCOPE" != core ] && [ -n "$UIDS" ]; then U=$(first_uid "$UIDS"); probeowner "$U" || fail "当前 iptables 不支持 owner UID 匹配"; fi
  probecidrs "$CIDRS" || fail "CIDR 绕过列表包含当前系统不支持的地址"

  NEED_TP=0; NEED_RP=0
  case "$M" in tproxy) if [ "$TCP" = 1 ] || [ "$UDP" = 1 ]; then NEED_TP=1; fi;; redirect) [ "$TCP" = 1 ] && NEED_RP=1;; enhance) [ "$TCP" = 1 ] && NEED_RP=1; [ "$UDP" = 1 ] && NEED_TP=1;; esac
  if [ "$DNS" = tproxy ] || [ "$DNS" = redirect ]; then
    probered4 "$DP" tcp || fail "当前 iptables 不支持 TCP DNS REDIRECT"
    probered4 "$DP" udp || fail "当前 iptables 不支持 UDP DNS REDIRECT"
  fi
  [ "$NEED_TP" = 1 ] && { port "$TP" || fail "TPROXY 端口无效"; probetp4 "$TP" || fail "当前内核或 iptables 不支持 TPROXY"; }
  [ "$NEED_RP" = 1 ] && { port "$RP" || fail "Redirect 端口无效"; probered4 "$RP" tcp || fail "当前 iptables 不支持 REDIRECT"; }
  if [ "$NEED_TP" = 0 ] && [ "$NEED_RP" = 0 ] && [ "$DNS" = off ]; then fail "TCP、UDP 与 DNS 接管均已关闭，代理没有可接管流量"; fi

  if [ "$V6" = enable ] && v6active; then
    [ "$NEED_TP" = 0 ] || probetp6 "$TP" || fail "IPv6 TPROXY 不可用，可改用严格 IPv4 或 IPv6 不进核心"
    [ "$NEED_RP" = 0 ] || probered6 "$RP" tcp || fail "IPv6 REDIRECT 不可用，可改用严格 IPv4 或 IPv6 不进核心"
    if [ "$DNS" = tproxy ] || [ "$DNS" = redirect ]; then probered6 "$DP" tcp || fail "IPv6 TCP DNS REDIRECT 不可用"; probered6 "$DP" udp || fail "IPv6 UDP DNS REDIRECT 不可用"; fi
  fi
  if [ "$V6" = strict ] && v6active; then has ip6tables || fail "严格 IPv4 需要 ip6tables"; fi
  if [ "$V6" = disable ]; then TESTED=0; for P in /proc/sys/net/ipv6/conf/*/disable_ipv6; do [ -w "$P" ] && TESTED=1 && break; done; [ "$TESTED" = 1 ] || fail "系统不允许临时禁用 IPv6"; fi
  ok "Root 代理预检通过"
}

route4(){ ip route replace local 0.0.0.0/0 dev lo table "$TABLE" || return 1; ip rule add pref "$PREF" fwmark "$MARK/$MASK" table "$TABLE" || return 1; }
route6(){ ip -6 route replace local ::/0 dev lo table "$TABLE" || return 1; ip -6 rule add pref "$PREF" fwmark "$MARK/$MASK" table "$TABLE" || return 1; }

install_mangle4(){
  P="$1"; M="$2"; TCP="$3"; UDP="$4"; DNS="$5"; S="$6"; UIDS="$7"; SHARE="$8"; CIDRS="$9"; IFACES="${10}"
  NEED=0; case "$M" in tproxy) if [ "$TCP" = 1 ] || [ "$UDP" = 1 ]; then NEED=1; fi;; enhance) [ "$UDP" = 1 ] && NEED=1;; esac; [ "$NEED" = 1 ] || return 0
  route4 || return 1; iptables -t mangle -N "$MOUT" || return 1; iptables -t mangle -N "$MPRE" || return 1
  iptables -t mangle -A "$MOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_out iptables mangle "$MOUT" "$IFACES" || return 1; blacklist_returns iptables mangle "$MOUT" "$S" "$UIDS" || return 1
  iptables -t mangle -A "$MPRE" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_in iptables mangle "$MPRE" "$IFACES" || return 1
  if [ "$DNS" = tproxy ] || [ "$DNS" = redirect ]; then
    iptables -t mangle -A "$MOUT" -p tcp --dport 53 -j RETURN || return 1
    iptables -t mangle -A "$MOUT" -p udp --dport 53 -j RETURN || return 1
  fi
  bypass4 "$MOUT" mangle "$CIDRS" || return 1; bypass4 "$MPRE" mangle "$CIDRS" || return 1
  if [ "$M" = tproxy ]; then
    [ "$TCP" = 0 ] || { scoped_mark iptables mangle "$MOUT" "$S" "$UIDS" tcp "" "$MARK/$MASK" || return 1; if [ "$SHARE" = 1 ]; then iptables -t mangle -A "$MPRE" -p tcp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; else iptables -t mangle -A "$MPRE" -m mark --mark "$MARK/$MASK" -p tcp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; fi; }
    [ "$UDP" = 0 ] || { scoped_mark iptables mangle "$MOUT" "$S" "$UIDS" udp "" "$MARK/$MASK" || return 1; if [ "$SHARE" = 1 ]; then iptables -t mangle -A "$MPRE" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; else iptables -t mangle -A "$MPRE" -m mark --mark "$MARK/$MASK" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; fi; }
  elif [ "$M" = enhance ] && [ "$UDP" = 1 ]; then scoped_mark iptables mangle "$MOUT" "$S" "$UIDS" udp "" "$MARK/$MASK" || return 1; if [ "$SHARE" = 1 ]; then iptables -t mangle -A "$MPRE" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; else iptables -t mangle -A "$MPRE" -m mark --mark "$MARK/$MASK" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; fi; fi
  iptables -t mangle -A OUTPUT -j "$MOUT" || return 1; iptables -t mangle -A PREROUTING -j "$MPRE" || return 1
}

install_mangle6(){
  P="$1"; M="$2"; TCP="$3"; UDP="$4"; DNS="$5"; S="$6"; UIDS="$7"; SHARE="$8"; CIDRS="$9"; IFACES="${10}"; v6active || return 0
  NEED=0; case "$M" in tproxy) if [ "$TCP" = 1 ] || [ "$UDP" = 1 ]; then NEED=1; fi;; enhance) [ "$UDP" = 1 ] && NEED=1;; esac; [ "$NEED" = 1 ] || return 0
  route6 || return 1; ip6tables -t mangle -N "$MOUT" || return 1; ip6tables -t mangle -N "$MPRE" || return 1
  ip6tables -t mangle -A "$MOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_out ip6tables mangle "$MOUT" "$IFACES" || return 1; blacklist_returns ip6tables mangle "$MOUT" "$S" "$UIDS" || return 1
  ip6tables -t mangle -A "$MPRE" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_in ip6tables mangle "$MPRE" "$IFACES" || return 1
  if [ "$DNS" = tproxy ] || [ "$DNS" = redirect ]; then
    ip6tables -t mangle -A "$MOUT" -p tcp --dport 53 -j RETURN || return 1
    ip6tables -t mangle -A "$MOUT" -p udp --dport 53 -j RETURN || return 1
  fi
  bypass6 "$MOUT" mangle "$CIDRS" || return 1; bypass6 "$MPRE" mangle "$CIDRS" || return 1
  if [ "$M" = tproxy ]; then
    [ "$TCP" = 0 ] || { scoped_mark ip6tables mangle "$MOUT" "$S" "$UIDS" tcp "" "$MARK/$MASK" || return 1; if [ "$SHARE" = 1 ]; then ip6tables -t mangle -A "$MPRE" -p tcp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; else ip6tables -t mangle -A "$MPRE" -m mark --mark "$MARK/$MASK" -p tcp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; fi; }
    [ "$UDP" = 0 ] || { scoped_mark ip6tables mangle "$MOUT" "$S" "$UIDS" udp "" "$MARK/$MASK" || return 1; if [ "$SHARE" = 1 ]; then ip6tables -t mangle -A "$MPRE" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; else ip6tables -t mangle -A "$MPRE" -m mark --mark "$MARK/$MASK" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; fi; }
  elif [ "$M" = enhance ] && [ "$UDP" = 1 ]; then scoped_mark ip6tables mangle "$MOUT" "$S" "$UIDS" udp "" "$MARK/$MASK" || return 1; if [ "$SHARE" = 1 ]; then ip6tables -t mangle -A "$MPRE" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; else ip6tables -t mangle -A "$MPRE" -m mark --mark "$MARK/$MASK" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; fi; fi
  ip6tables -t mangle -A OUTPUT -j "$MOUT" || return 1; ip6tables -t mangle -A PREROUTING -j "$MPRE" || return 1
}

install_redirect4(){
  P="$1"; M="$2"; TCP="$3"; S="$4"; UIDS="$5"; SHARE="$6"; CIDRS="$7"; IFACES="$8"; [ "$TCP" = 1 ] || return 0; case "$M" in redirect|enhance) ;; *) return 0;; esac
  iptables -t nat -N "$NOUT" || return 1; iptables -t nat -A "$NOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_out iptables nat "$NOUT" "$IFACES" || return 1; blacklist_returns iptables nat "$NOUT" "$S" "$UIDS" || return 1; bypass4 "$NOUT" nat "$CIDRS" || return 1; scoped_redirect iptables nat "$NOUT" "$S" "$UIDS" tcp "" "$P" || return 1; iptables -t nat -A OUTPUT -j "$NOUT" || return 1
  if [ "$SHARE" = 1 ]; then iptables -t nat -N "$NPRE" || return 1; iptables -t nat -A "$NPRE" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_in iptables nat "$NPRE" "$IFACES" || return 1; bypass4 "$NPRE" nat "$CIDRS" || return 1; iptables -t nat -A "$NPRE" -p tcp -j REDIRECT --to-ports "$P" || return 1; iptables -t nat -A PREROUTING -j "$NPRE" || return 1; fi
}
install_redirect6(){
  P="$1"; M="$2"; TCP="$3"; S="$4"; UIDS="$5"; SHARE="$6"; CIDRS="$7"; IFACES="$8"; v6active || return 0; [ "$TCP" = 1 ] || return 0; case "$M" in redirect|enhance) ;; *) return 0;; esac
  ip6tables -t nat -N "$NOUT" || return 1; ip6tables -t nat -A "$NOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_out ip6tables nat "$NOUT" "$IFACES" || return 1; blacklist_returns ip6tables nat "$NOUT" "$S" "$UIDS" || return 1; bypass6 "$NOUT" nat "$CIDRS" || return 1; scoped_redirect ip6tables nat "$NOUT" "$S" "$UIDS" tcp "" "$P" || return 1; ip6tables -t nat -A OUTPUT -j "$NOUT" || return 1
  if [ "$SHARE" = 1 ]; then ip6tables -t nat -N "$NPRE" || return 1; ip6tables -t nat -A "$NPRE" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_in ip6tables nat "$NPRE" "$IFACES" || return 1; bypass6 "$NPRE" nat "$CIDRS" || return 1; ip6tables -t nat -A "$NPRE" -p tcp -j REDIRECT --to-ports "$P" || return 1; ip6tables -t nat -A PREROUTING -j "$NPRE" || return 1; fi
}

install_dns_redirect4(){
  P="$1"; S="$2"; UIDS="$3"; SHARE="$4"; IFACES="$5"; iptables -t nat -N "$DNSOUT" || return 1; iptables -t nat -A "$DNSOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_out iptables nat "$DNSOUT" "$IFACES" || return 1; blacklist_returns iptables nat "$DNSOUT" "$S" "$UIDS" || return 1
  for X in tcp udp; do scoped_redirect iptables nat "$DNSOUT" "$S" "$UIDS" "$X" 53 "$P" || return 1; done; iptables -t nat -I OUTPUT 1 -j "$DNSOUT" || return 1
  if [ "$SHARE" = 1 ]; then iptables -t nat -N "$DNSPRE" || return 1; iface_in iptables nat "$DNSPRE" "$IFACES" || return 1; for X in tcp udp; do iptables -t nat -A "$DNSPRE" -p "$X" --dport 53 -j REDIRECT --to-ports "$P" || return 1; done; iptables -t nat -I PREROUTING 1 -j "$DNSPRE" || return 1; fi
}
install_dns_redirect6(){
  P="$1"; S="$2"; UIDS="$3"; SHARE="$4"; IFACES="$5"; v6active || return 0; ip6tables -t nat -N "$DNSOUT" || return 1; ip6tables -t nat -A "$DNSOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_out ip6tables nat "$DNSOUT" "$IFACES" || return 1; blacklist_returns ip6tables nat "$DNSOUT" "$S" "$UIDS" || return 1
  for X in tcp udp; do scoped_redirect ip6tables nat "$DNSOUT" "$S" "$UIDS" "$X" 53 "$P" || return 1; done; ip6tables -t nat -I OUTPUT 1 -j "$DNSOUT" || return 1
  if [ "$SHARE" = 1 ]; then ip6tables -t nat -N "$DNSPRE" || return 1; iface_in ip6tables nat "$DNSPRE" "$IFACES" || return 1; for X in tcp udp; do ip6tables -t nat -A "$DNSPRE" -p "$X" --dport 53 -j REDIRECT --to-ports "$P" || return 1; done; ip6tables -t nat -I PREROUTING 1 -j "$DNSPRE" || return 1; fi
}

install_quic4(){
  S="$1"; UIDS="$2"; SHARE="$3"; CIDRS="$4"; IFACES="$5"; iptables -t filter -N "$QUICOUT" || return 1; iptables -t filter -A "$QUICOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_out iptables filter "$QUICOUT" "$IFACES" || return 1; blacklist_returns iptables filter "$QUICOUT" "$S" "$UIDS" || return 1; bypass4 "$QUICOUT" filter "$CIDRS" || return 1; scoped_drop_quic iptables "$QUICOUT" "$S" "$UIDS" || return 1; iptables -t filter -A OUTPUT -j "$QUICOUT" || return 1
  if [ "$SHARE" = 1 ]; then iptables -t filter -N "$QUICFWD" || return 1; iface_in iptables filter "$QUICFWD" "$IFACES" || return 1; bypass4 "$QUICFWD" filter "$CIDRS" || return 1; iptables -t filter -A "$QUICFWD" -p udp --dport 443 -j DROP || return 1; iptables -t filter -A FORWARD -j "$QUICFWD" || return 1; fi
}
install_quic6(){
  S="$1"; UIDS="$2"; SHARE="$3"; CIDRS="$4"; IFACES="$5"; v6active || return 0; ip6tables -t filter -N "$QUICOUT" || return 1; ip6tables -t filter -A "$QUICOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_out ip6tables filter "$QUICOUT" "$IFACES" || return 1; blacklist_returns ip6tables filter "$QUICOUT" "$S" "$UIDS" || return 1; bypass6 "$QUICOUT" filter "$CIDRS" || return 1; scoped_drop_quic ip6tables "$QUICOUT" "$S" "$UIDS" || return 1; ip6tables -t filter -A OUTPUT -j "$QUICOUT" || return 1
  if [ "$SHARE" = 1 ]; then ip6tables -t filter -N "$QUICFWD" || return 1; iface_in ip6tables filter "$QUICFWD" "$IFACES" || return 1; bypass6 "$QUICFWD" filter "$CIDRS" || return 1; ip6tables -t filter -A "$QUICFWD" -p udp --dport 443 -j DROP || return 1; ip6tables -t filter -A FORWARD -j "$QUICFWD" || return 1; fi
}

install_v6_strict(){
  S="$1"; UIDS="$2"; SHARE="$3"; CIDRS="$4"; IFACES="$5"; v6active || return 0; ip6tables -t filter -N "$V6OUT" || return 1; ip6tables -t filter -A "$V6OUT" -o lo -j RETURN || return 1; ip6tables -t filter -A "$V6OUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_out ip6tables filter "$V6OUT" "$IFACES" || return 1; blacklist_returns ip6tables filter "$V6OUT" "$S" "$UIDS" || return 1; bypass6 "$V6OUT" filter "$CIDRS" || return 1; scoped_reject_all ip6tables "$V6OUT" "$S" "$UIDS" || return 1; ip6tables -t filter -A OUTPUT -j "$V6OUT" || return 1
  if [ "$SHARE" = 1 ]; then ip6tables -t filter -N "$V6FWD" || return 1; iface_in ip6tables filter "$V6FWD" "$IFACES" || return 1; bypass6 "$V6FWD" filter "$CIDRS" || return 1; ip6tables -t filter -A "$V6FWD" -j REJECT || return 1; ip6tables -t filter -A FORWARD -j "$V6FWD" || return 1; fi
}

install_kill4(){
  S="$1"; UIDS="$2"; SHARE="$3"; CIDRS="$4"; IFACES="$5"; iptables -t filter -N "$KOUT" >/dev/null 2>&1 || true; iptables -t filter -F "$KOUT" || return 1; iptables -t filter -A "$KOUT" -o lo -j RETURN || return 1; iface_out iptables filter "$KOUT" "$IFACES" || return 1; blacklist_returns iptables filter "$KOUT" "$S" "$UIDS" || return 1; bypass4 "$KOUT" filter "$CIDRS" || return 1; scoped_reject_all iptables "$KOUT" "$S" "$UIDS" || return 1; iptables -t filter -I OUTPUT 1 -j "$KOUT" || return 1
  if [ "$SHARE" = 1 ]; then iptables -t filter -N "$KFWD" >/dev/null 2>&1 || true; iptables -t filter -F "$KFWD" || return 1; iface_in iptables filter "$KFWD" "$IFACES" || return 1; bypass4 "$KFWD" filter "$CIDRS" || return 1; iptables -t filter -A "$KFWD" -j REJECT || return 1; iptables -t filter -I FORWARD 1 -j "$KFWD" || return 1; fi
}
install_kill6(){
  S="$1"; UIDS="$2"; SHARE="$3"; CIDRS="$4"; IFACES="$5"; has ip6tables || return 0; v6active || return 0; ip6tables -t filter -N "$KOUT" >/dev/null 2>&1 || true; ip6tables -t filter -F "$KOUT" || return 1; ip6tables -t filter -A "$KOUT" -o lo -j RETURN || return 1; iface_out ip6tables filter "$KOUT" "$IFACES" || return 1; blacklist_returns ip6tables filter "$KOUT" "$S" "$UIDS" || return 1; bypass6 "$KOUT" filter "$CIDRS" || return 1; scoped_reject_all ip6tables "$KOUT" "$S" "$UIDS" || return 1; ip6tables -t filter -I OUTPUT 1 -j "$KOUT" || return 1
  if [ "$SHARE" = 1 ]; then ip6tables -t filter -N "$KFWD" >/dev/null 2>&1 || true; ip6tables -t filter -F "$KFWD" || return 1; iface_in ip6tables filter "$KFWD" "$IFACES" || return 1; bypass6 "$KFWD" filter "$CIDRS" || return 1; ip6tables -t filter -A "$KFWD" -j REJECT || return 1; ip6tables -t filter -I FORWARD 1 -j "$KFWD" || return 1; fi
}

validatecfg(){ BIN="$1"; CFG="$2"; : > "$CHECKLOG"; "$BIN" -t -d "$RUN" -f "$CFG" >>"$CHECKLOG" 2>&1; }
hexport(){ printf '%04X' "$1" 2>/dev/null; }
tcp_listen(){ P="$1"; if has ss && ss -lnt 2>/dev/null | grep -Eq "[:.]${P}([[:space:]]|$)"; then return 0; fi; if has netstat && netstat -lnt 2>/dev/null | grep -Eq "[:.]${P}([[:space:]]|$)"; then return 0; fi; H=$(hexport "$P") || return 1; awk -v x=":$H" '$2 ~ x"$" && $4=="0A" {found=1} END{exit(found?0:1)}' /proc/net/tcp /proc/net/tcp6 2>/dev/null; }
udp_listen(){ P="$1"; if has ss && ss -lnu 2>/dev/null | grep -Eq "[:.]${P}([[:space:]]|$)"; then return 0; fi; if has netstat && netstat -lnu 2>/dev/null | grep -Eq "[:.]${P}([[:space:]]|$)"; then return 0; fi; H=$(hexport "$P") || return 1; awk -v x=":$H" '$2 ~ x"$" {found=1} END{exit(found?0:1)}' /proc/net/udp /proc/net/udp6 2>/dev/null; }
ready(){
  PID="$1"; M="$2"; TP="$3"; RP="$4"; TCP="$5"; UDP="$6"; DNS="$7"; DP="$8"; CP="$9"; pidcore "$PID" && kill -0 "$PID" >/dev/null 2>&1 || return 1; tcp_listen "$CP" || return 1
  case "$M" in tproxy) [ "$TCP" = 0 ] || tcp_listen "$TP" || return 1; [ "$UDP" = 0 ] || udp_listen "$TP" || return 1;; redirect) [ "$TCP" = 0 ] || tcp_listen "$RP" || return 1;; enhance) [ "$TCP" = 0 ] || tcp_listen "$RP" || return 1; [ "$UDP" = 0 ] || udp_listen "$TP" || return 1;; esac
  if [ "$DNS" = tproxy ] || [ "$DNS" = redirect ]; then tcp_listen "$DP" || return 1; udp_listen "$DP" || return 1; fi; return 0
}
check_start_ports(){
  M="$1"; TP="$2"; RP="$3"; TCP="$4"; UDP="$5"; DNS="$6"; DP="$7"; CP="$8"
  tcp_listen "$CP" && fail "控制接口端口 $CP 已被其他程序占用，请关闭冲突进程后重试"
  case "$M" in
    tproxy)
      [ "$TCP" = 0 ] || { tcp_listen "$TP" && fail "TPROXY TCP 端口 $TP 已被其他程序占用"; }
      [ "$UDP" = 0 ] || { udp_listen "$TP" && fail "TPROXY UDP 端口 $TP 已被其他程序占用"; }
    ;;
    redirect)
      [ "$TCP" = 0 ] || { tcp_listen "$RP" && fail "Redirect 端口 $RP 已被其他程序占用"; }
    ;;
    enhance)
      [ "$TCP" = 0 ] || { tcp_listen "$RP" && fail "Redirect 端口 $RP 已被其他程序占用"; }
      [ "$UDP" = 0 ] || { udp_listen "$TP" && fail "TPROXY UDP 端口 $TP 已被其他程序占用"; }
    ;;
  esac
  if [ "$DNS" = tproxy ] || [ "$DNS" = redirect ]; then
    tcp_listen "$DP" && fail "DNS TCP 端口 $DP 已被其他程序占用"
    udp_listen "$DP" && fail "DNS UDP 端口 $DP 已被其他程序占用"
  fi
}

wait_ready(){
  PID="$1"; M="$2"; TP="$3"; RP="$4"; TCP="$5"; UDP="$6"; DNS="$7"; DP="$8"; CP="$9"
  # A real user config may have dozens of remote proxy/rule providers. On the first
  # run inside Bichen's private HomeDir their caches are cold; Mihomo keeps the process
  # alive while initial configuration is still loading. Do not mistake that for a dead
  # listener after only 8 seconds. Keep network rules detached until every required
  # listener is actually ready, so a slow cold start cannot black-hole traffic.
  N=0
  while [ "$N" -lt 900 ]; do
    ready "$PID" "$M" "$TP" "$RP" "$TCP" "$UDP" "$DNS" "$DP" "$CP" && return 0
    pidcore "$PID" && kill -0 "$PID" >/dev/null 2>&1 || return 2
    sleep 0.1
    N=$((N+1))
  done
  return 3
}

write_session(){ M="$1"; V6="$2"; S="$3"; SHARE="$4"; KILL="$5"; { printf 'MODE=%s\n' "$M"; printf 'IPV6=%s\n' "$V6"; printf 'APP_SCOPE=%s\n' "$S"; printf 'SHARE=%s\n' "$SHARE"; printf 'KILL=%s\n' "$KILL"; } > "$SESSION.new.$$" && mv -f "$SESSION.new.$$" "$SESSION"; }
watchdog(){
  COREPID="$1"; KILL="$2"; S="$3"; UIDS="$4"; SHARE="$5"; CIDRS="$6"; IFACES="$7"
  mkdir -p "$RUN" || exit 0; printf '%s\n' "$$" > "$WATCHDOG_PID"; while pidcore "$COREPID" && kill -0 "$COREPID" >/dev/null 2>&1; do sleep 2; done; acquire_lock || exit 0
  REC=$(cat "$PIDFILE" 2>/dev/null || true)
  if [ "$REC" = "$COREPID" ]; then
    cleanup; restorev6; rm -f "$PIDFILE"
    RESULT="network-restored"
    if [ "$KILL" = 1 ]; then if install_kill4 "$S" "$UIDS" "$SHARE" "$CIDRS" "$IFACES" && install_kill6 "$S" "$UIDS" "$SHARE" "$CIDRS" "$IFACES"; then RESULT="killswitch-active"; else RESULT="killswitch-failed"; fi; else rm -f "$MODEFILE" "$SESSION"; fi
    date '+%Y-%m-%dT%H:%M:%S%z core exited; '"$RESULT" > "$CRASH_STATE" 2>/dev/null || true; printf '%s core=%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$COREPID" "$RESULT" >> "$WATCHDOG_LOG" 2>/dev/null || true
  fi
  rm -f "$WATCHDOG_PID"
}
start_watchdog(){ COREPID="$1"; KILL="$2"; S="$3"; UIDS="$4"; SHARE="$5"; CIDRS="$6"; IFACES="$7"; stopwatchdog; "$0" watchdog "$COREPID" "$KILL" "$S" "$UIDS" "$SHARE" "$CIDRS" "$IFACES" >/dev/null 2>&1 & }

start(){
  START_BIN="$1"; START_CFG="$2"; START_MODE="$3"; START_TP="$4"; START_RP="$5"; START_V6="$6"; START_TCP="$7"; START_UDP="$8"; START_DNS="$9"; START_QUIC="${10}"; START_DP="${11}"; START_CP="${12}"; START_SCOPE="${13}"; START_UIDS="${14}"; START_SHARE="${15}"; START_KILL="${16}"; START_CIDRS="${17}"; START_IFACES="${18}"
  preflight "$START_MODE" "$START_TP" "$START_RP" "$START_V6" "$START_TCP" "$START_UDP" "$START_DNS" "$START_QUIC" "$START_DP" "$START_CP" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_KILL" "$START_CIDRS" "$START_IFACES" >/dev/null
  [ -x "$START_BIN" ] || fail "核心文件不存在或不可执行"; [ -r "$START_CFG" ] || fail "启动配置不存在"; mkdir -p "$RUN" || fail "无法创建运行目录"; validatecfg "$START_BIN" "$START_CFG" || fail "Mihomo 配置校验失败，当前网络未被接管"; acquire_lock || fail "另一个代理网络事务正在执行，请稍后重试"
  stopwatchdog; cleanup; restorev6; stopcore; sleep 0.20; rm -f "$CRASH_STATE" "$SESSION"
  check_start_ports "$START_MODE" "$START_TP" "$START_RP" "$START_TCP" "$START_UDP" "$START_DNS" "$START_DP" "$START_CP"
  markused "$BYPASS_MARK" && fail "安全出站 mark 已被其他网络规则占用，未接管网络"
  NEED_TP=0; case "$START_MODE" in tproxy) if [ "$START_TCP" = 1 ] || [ "$START_UDP" = 1 ]; then NEED_TP=1; fi;; enhance) [ "$START_UDP" = 1 ] && NEED_TP=1;; esac; [ "$START_DNS" = tproxy ] && NEED_TP=1
  if [ "$NEED_TP" = 1 ]; then allocnet || { cleanup; fail "找不到安全的 fwmark/路由表/规则优先级，已保持直连"; }; fi
  if [ "$START_V6" = disable ]; then disablev6 || { cleanup; fail "禁用系统 IPv6 失败，已恢复原状态"; }; fi

  mkdir -p "$RUN/rules" "$RUN/proxy_provider" "$RUN/ruleset" "$RUN/ui" || { cleanup; restorev6; rm -f "$SESSION"; fail "无法创建 Mihomo 运行缓存目录"; }
  : > "$LOG"; "$START_BIN" -d "$RUN" -f "$START_CFG" >>"$LOG" 2>&1 & START_PID=$!; printf '%s\n' "$START_PID" > "$PIDFILE"; printf '%s\n' "$START_MODE" > "$MODEFILE"; write_session "$START_MODE" "$START_V6" "$START_SCOPE" "$START_SHARE" "$START_KILL"
  wait_ready "$START_PID" "$START_MODE" "$START_TP" "$START_RP" "$START_TCP" "$START_UDP" "$START_DNS" "$START_DP" "$START_CP"; READY_RC=$?
  if [ "$READY_RC" -ne 0 ]; then
    if [ "$READY_RC" -eq 2 ]; then READY_MSG="Mihomo 启动后提前退出，请查看核心日志"; else READY_MSG="Mihomo 初始化超过 90 秒，透明代理/DNS/API 监听仍未就绪；首次加载大量远程订阅或规则时请检查网络与核心日志"; fi
    stopcore; cleanup; restorev6; rm -f "$SESSION"; fail "$READY_MSG"
  fi

  install_mangle4 "$START_TP" "$START_MODE" "$START_TCP" "$START_UDP" "$START_DNS" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" || { cleanup; stopcore; restorev6; rm -f "$SESSION"; fail "IPv4 TPROXY 规则安装失败，已回滚"; }
  install_redirect4 "$START_RP" "$START_MODE" "$START_TCP" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" || { cleanup; stopcore; restorev6; rm -f "$SESSION"; fail "IPv4 Redirect 规则安装失败，已回滚"; }
  [ "$START_DNS" = off ] || install_dns_redirect4 "$START_DP" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_IFACES" || { cleanup; stopcore; restorev6; rm -f "$SESSION"; fail "IPv4 DNS 劫持安装失败，已回滚"; }
  [ "$START_QUIC" = 0 ] || install_quic4 "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" || { cleanup; stopcore; restorev6; rm -f "$SESSION"; fail "IPv4 QUIC 策略安装失败，已回滚"; }
  if [ "$START_V6" = enable ]; then
    install_mangle6 "$START_TP" "$START_MODE" "$START_TCP" "$START_UDP" "$START_DNS" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" || { cleanup; stopcore; restorev6; rm -f "$SESSION"; fail "IPv6 TPROXY 规则安装失败，已回滚"; }
    install_redirect6 "$START_RP" "$START_MODE" "$START_TCP" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" || { cleanup; stopcore; restorev6; rm -f "$SESSION"; fail "IPv6 Redirect 规则安装失败，已回滚"; }
    [ "$START_DNS" = off ] || install_dns_redirect6 "$START_DP" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_IFACES" || { cleanup; stopcore; restorev6; rm -f "$SESSION"; fail "IPv6 DNS 劫持安装失败，已回滚"; }
    [ "$START_QUIC" = 0 ] || install_quic6 "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" || { cleanup; stopcore; restorev6; rm -f "$SESSION"; fail "IPv6 QUIC 策略安装失败，已回滚"; }
  elif [ "$START_V6" = strict ]; then install_v6_strict "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" || { cleanup; stopcore; restorev6; rm -f "$SESSION"; fail "严格 IPv4 防泄漏规则安装失败，已回滚"; }; fi

  start_watchdog "$START_PID" "$START_KILL" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES"
  DESC="tcp=$START_TCP,udp=$START_UDP,dns=$START_DNS,ipv6=$START_V6,scope=$START_SCOPE,share=$START_SHARE,kill=$START_KILL,quicBlock=$START_QUIC"
  if [ -n "$MARK" ]; then ok "Root $START_MODE 已启动（$DESC，mark=$MARK，table=$TABLE）"; else ok "Root $START_MODE 已启动（$DESC）"; fi
}

status(){
  root; STATUS_RUNNING=false; STATUS_PID=0
  if [ -f "$PIDFILE" ]; then X=$(cat "$PIDFILE" 2>/dev/null || true); case "$X" in ''|*[!0-9]*) ;; *) if pidcore "$X" && kill -0 "$X" >/dev/null 2>&1; then STATUS_RUNNING=true; STATUS_PID="$X"; fi;; esac; fi
  STATUS_MODE=$(cat "$MODEFILE" 2>/dev/null || echo none); T4=false; T6=false; K4=false; K6=false
  for SPEC in "mangle OUTPUT $MOUT" "nat OUTPUT $NOUT" "nat OUTPUT $DNSOUT" "filter OUTPUT $QUICOUT"; do set -- $SPEC; iptables -t "$1" -C "$2" -j "$3" >/dev/null 2>&1 && T4=true; done; iptables -t filter -C OUTPUT -j "$KOUT" >/dev/null 2>&1 && K4=true
  if has ip6tables; then for SPEC in "mangle OUTPUT $MOUT" "nat OUTPUT $NOUT" "nat OUTPUT $DNSOUT" "filter OUTPUT $QUICOUT" "filter OUTPUT $V6OUT"; do set -- $SPEC; ip6tables -t "$1" -C "$2" -j "$3" >/dev/null 2>&1 && T6=true; done; ip6tables -t filter -C OUTPUT -j "$KOUT" >/dev/null 2>&1 && K6=true; fi
  V6OFF=false; [ -f "$IPV6_STATE" ] && V6OFF=true; RECOVERED=false
  if [ "$STATUS_RUNNING" = false ] && [ "$K4" = false ] && [ "$K6" = false ] && { [ "$T4" = true ] || [ "$T6" = true ] || [ "$V6OFF" = true ]; }; then if acquire_lock; then cleanup; restorev6; rm -f "$PIDFILE" "$MODEFILE" "$SESSION"; STATUS_MODE=none; T4=false; T6=false; V6OFF=false; RECOVERED=true; fi; fi
  WD=false; W=$(cat "$WATCHDOG_PID" 2>/dev/null || true); case "$W" in ''|*[!0-9]*) ;; *) kill -0 "$W" >/dev/null 2>&1 && WD=true;; esac
  SM=""; ST=""; if loadnet >/dev/null 2>&1; then SM="$MARK"; ST="$TABLE"; fi
  SCOPEV=$(sed -n 's/^APP_SCOPE=//p' "$SESSION" 2>/dev/null | head -n 1); SHAREV=$(sed -n 's/^SHARE=//p' "$SESSION" 2>/dev/null | head -n 1); KILLV=$(sed -n 's/^KILL=//p' "$SESSION" 2>/dev/null | head -n 1)
  printf '{"ok":true,"running":%s,"pid":%s,"mode":"%s","ipv4Rules":%s,"ipv6Rules":%s,"killSwitchActive":%s,"ipv6DisabledByBichen":%s,"watchdog":%s,"recoveredStaleRules":%s,"mark":"%s","table":"%s","appScope":"%s","sharedNetwork":"%s","killSwitchRequested":"%s","log":"%s","configCheckLog":"%s"}\n' "$STATUS_RUNNING" "$STATUS_PID" "$STATUS_MODE" "$T4" "$T6" "$([ "$K4" = true ] || [ "$K6" = true ] && echo true || echo false)" "$V6OFF" "$WD" "$RECOVERED" "$SM" "$ST" "$SCOPEV" "$SHAREV" "$KILLV" "$LOG" "$CHECKLOG"
}

case "${1:-status}" in
  preflight) [ "$#" = 17 ] || fail "参数错误"; preflight "$2" "$3" "$4" "$5" "$6" "$7" "$8" "$9" "${10}" "${11}" "${12}" "${13}" "${14}" "${15}" "${16}" "${17}";;
  start) [ "$#" = 19 ] || fail "参数错误"; root; start "$2" "$3" "$4" "$5" "$6" "$7" "$8" "$9" "${10}" "${11}" "${12}" "${13}" "${14}" "${15}" "${16}" "${17}" "${18}" "${19}";;
  stop) root; acquire_lock || fail "另一个代理网络事务正在执行，请稍后重试"; stopwatchdog; cleanup; stopcore; restorev6; rm -f "$SESSION"; ok "Root 代理已停止并恢复网络状态";;
  status) status;;
  watchdog) [ "$#" = 8 ] || exit 0; root; watchdog "$2" "$3" "$4" "$5" "$6" "$7" "$8";;
  *) fail "未知 Root 代理操作";;
esac
