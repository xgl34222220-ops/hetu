#!/system/bin/sh
# Hetu Root transparent proxy controller v3. JSON only on stdout.
set -u
umask 077

BASE=/data/adb/hetu
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
START_STATE="$RUN/start-state"
START_ERROR="$RUN/last-start-error"
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

MOUT=HETU_MOUT
MPRE=HETU_MPRE
NOUT=HETU_NOUT
NPRE=HETU_NPRE
DNSOUT=HETU_DNSOUT
DNSPRE=HETU_DNSPRE
QUICOUT=HETU_QUICOUT
QUICFWD=HETU_QUICFWD
WROUT=HETU_WROUT
WRFWD=HETU_WRFWD
V6OUT=HETU_V6OUT
V6FWD=HETU_V6FWD
KOUT=HETU_KOUT
KFWD=HETU_KFWD

ok(){ printf '{"ok":true,"message":"%s"}\n' "$1"; }
start_stage(){ mkdir -p "$RUN" >/dev/null 2>&1 || true; printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$1" > "$START_STATE" 2>/dev/null || true; }
fail(){ MSG="$1"; mkdir -p "$RUN" >/dev/null 2>&1 || true; printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$MSG" > "$START_ERROR" 2>/dev/null || true; printf '{"ok":false,"message":"%s"}\n' "$MSG"; exit 1; }
root(){ [ "$(id -u)" = 0 ] || fail "需要 Root 权限"; }
has(){ command -v "$1" >/dev/null 2>&1; }
# Serialize with Android netd/other root firewalls on /system/etc/xtables.lock.
# iptables itself owns the lock; -w avoids racy fail/rollback while preserving atomic rules.
xt4(){ command iptables -w 15 "$@"; }
xt6(){ command ip6tables -w 15 "$@"; }
# Status polling must never sit behind Android/netd's xtables lock for 15 seconds.
xt4q(){ command iptables -w 1 "$@"; }
xt6q(){ command ip6tables -w 1 "$@"; }
port(){ case "${1:-}" in ''|*[!0-9]*) return 1;; esac; [ "$1" -ge 1 ] && [ "$1" -le 65535 ]; }
mode(){ case "${1:-}" in tproxy|redirect|enhance|tun|ebpf) return 0;; *) return 1;; esac; }
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
  # Keep Android VPN/lockdown guards ahead of Hetu, but run before ordinary
  # network selection. 14500..14949 is intentionally a free searched range on
  # current Android 16 and prefused() still avoids OEM/custom collisions.
  P=14500; while [ "$P" -le 14949 ]; do if ! prefused "$P"; then PREF="$P"; break; fi; P=$((P+1)); done; [ -n "$PREF" ] || return 1
  savenet
}

legacy_unhook(){
  B="$1"; T="$2"; BASECHAIN="$3"; CHAIN="$4"; N=0
  while "$B" -w 1 -t "$T" -C "$BASECHAIN" -j "$CHAIN" >/dev/null 2>&1; do
    "$B" -w 1 -t "$T" -D "$BASECHAIN" -j "$CHAIN" >/dev/null 2>&1 || break
    N=$((N+1)); [ "$N" -lt 8 ] || break
  done
  "$B" -w 1 -t "$T" -F "$CHAIN" >/dev/null 2>&1 || true
  "$B" -w 1 -t "$T" -X "$CHAIN" >/dev/null 2>&1 || true
}
cleanlegacy(){
  ip rule del pref "$LEGACY_PREF" fwmark "$LEGACY_MARK/$LEGACY_MASK" table "$LEGACY_TABLE" >/dev/null 2>&1 || true
  ip -6 rule del pref "$LEGACY_PREF" fwmark "$LEGACY_MARK/$LEGACY_MASK" table "$LEGACY_TABLE" >/dev/null 2>&1 || true
  ip route del local 0.0.0.0/0 dev lo table "$LEGACY_TABLE" >/dev/null 2>&1 || true
  ip -6 route del local ::/0 dev lo table "$LEGACY_TABLE" >/dev/null 2>&1 || true
  if has iptables; then
    legacy_unhook iptables mangle OUTPUT BICHEN_MOUT; legacy_unhook iptables mangle PREROUTING BICHEN_MPRE
    legacy_unhook iptables nat OUTPUT BICHEN_DNSOUT; legacy_unhook iptables nat PREROUTING BICHEN_DNSPRE
    legacy_unhook iptables nat OUTPUT BICHEN_NOUT; legacy_unhook iptables nat PREROUTING BICHEN_NPRE
    legacy_unhook iptables filter OUTPUT BICHEN_QUICOUT; legacy_unhook iptables filter FORWARD BICHEN_QUICFWD
    legacy_unhook iptables filter OUTPUT BICHEN_KOUT; legacy_unhook iptables filter FORWARD BICHEN_KFWD
  fi
  if has ip6tables; then
    legacy_unhook ip6tables mangle OUTPUT BICHEN_MOUT; legacy_unhook ip6tables mangle PREROUTING BICHEN_MPRE
    legacy_unhook ip6tables nat OUTPUT BICHEN_DNSOUT; legacy_unhook ip6tables nat PREROUTING BICHEN_DNSPRE
    legacy_unhook ip6tables nat OUTPUT BICHEN_NOUT; legacy_unhook ip6tables nat PREROUTING BICHEN_NPRE
    legacy_unhook ip6tables filter OUTPUT BICHEN_QUICOUT; legacy_unhook ip6tables filter FORWARD BICHEN_QUICFWD
    legacy_unhook ip6tables filter OUTPUT BICHEN_V6OUT; legacy_unhook ip6tables filter FORWARD BICHEN_V6FWD
    legacy_unhook ip6tables filter OUTPUT BICHEN_KOUT; legacy_unhook ip6tables filter FORWARD BICHEN_KFWD
  fi
  ip link del bichen0 >/dev/null 2>&1 || true
}
unhook(){
  BIN="$1"; T="$2"; BASECHAIN="$3"; CHAIN="$4"
  while "$BIN" -t "$T" -C "$BASECHAIN" -j "$CHAIN" >/dev/null 2>&1; do "$BIN" -t "$T" -D "$BASECHAIN" -j "$CHAIN" >/dev/null 2>&1 || break; done
  "$BIN" -t "$T" -F "$CHAIN" >/dev/null 2>&1 || true; "$BIN" -t "$T" -X "$CHAIN" >/dev/null 2>&1 || true
}
cleanup4(){
  unhook xt4 mangle OUTPUT "$MOUT"; unhook xt4 mangle PREROUTING "$MPRE"
  unhook xt4 nat OUTPUT "$DNSOUT"; unhook xt4 nat PREROUTING "$DNSPRE"
  unhook xt4 nat OUTPUT "$NOUT"; unhook xt4 nat PREROUTING "$NPRE"
  unhook xt4 filter OUTPUT "$QUICOUT"; unhook xt4 filter FORWARD "$QUICFWD"
  unhook xt4 filter OUTPUT "$WROUT"; unhook xt4 filter FORWARD "$WRFWD"
  unhook xt4 filter OUTPUT "$KOUT"; unhook xt4 filter FORWARD "$KFWD"
  if [ -n "$MARK" ] && [ -n "$MASK" ] && [ -n "$TABLE" ] && [ -n "$PREF" ]; then
    ip rule del pref "$PREF" fwmark "$MARK/$MASK" table "$TABLE" >/dev/null 2>&1 || true
    ip route del local 0.0.0.0/0 dev lo table "$TABLE" >/dev/null 2>&1 || true
  fi
}
cleanup6(){
  has ip6tables || return 0
  unhook xt6 mangle OUTPUT "$MOUT"; unhook xt6 mangle PREROUTING "$MPRE"
  unhook xt6 nat OUTPUT "$DNSOUT"; unhook xt6 nat PREROUTING "$DNSPRE"
  unhook xt6 nat OUTPUT "$NOUT"; unhook xt6 nat PREROUTING "$NPRE"
  unhook xt6 filter OUTPUT "$QUICOUT"; unhook xt6 filter FORWARD "$QUICFWD"
  unhook xt6 filter OUTPUT "$WROUT"; unhook xt6 filter FORWARD "$WRFWD"
  unhook xt6 filter OUTPUT "$V6OUT"; unhook xt6 filter FORWARD "$V6FWD"
  unhook xt6 filter OUTPUT "$KOUT"; unhook xt6 filter FORWARD "$KFWD"
  if [ -n "$MARK" ] && [ -n "$MASK" ] && [ -n "$TABLE" ] && [ -n "$PREF" ]; then
    ip -6 rule del pref "$PREF" fwmark "$MARK/$MASK" table "$TABLE" >/dev/null 2>&1 || true
    ip -6 route del local ::/0 dev lo table "$TABLE" >/dev/null 2>&1 || true
  fi
}
cleanup(){ MARK=""; MASK=""; TABLE=""; PREF=""; loadnet >/dev/null 2>&1 || true; cleanup4; cleanup6; cleanlegacy; ip link del hetu0 >/dev/null 2>&1 || true; rm -f "$NET_STATE"; MARK=""; MASK=""; TABLE=""; PREF=""; }

pidcore(){
  P="$1"; [ -d "/proc/$P" ] || return 1
  CMD=$(tr '\000' ' ' < "/proc/$P/cmdline" 2>/dev/null || true)
  EXE=$(readlink "/proc/$P/exe" 2>/dev/null || true)
  case "$CMD $EXE" in *"$BASE/bin/core"*) return 0;; *) return 1;; esac
}
findcorepid(){
  for PROC in /proc/[0-9]*; do
    CAND=${PROC#/proc/}; case "$CAND" in ''|*[!0-9]*) continue;; esac
    if pidcore "$CAND" && kill -0 "$CAND" >/dev/null 2>&1; then printf '%s\n' "$CAND"; return 0; fi
  done
  return 1
}
stopwatchdog(){
  [ -f "$WATCHDOG_PID" ] || return 0; W=$(cat "$WATCHDOG_PID" 2>/dev/null || true)
  case "$W" in ''|*[!0-9]*) ;; *) if [ "$W" != "$$" ] && kill -0 "$W" >/dev/null 2>&1; then kill "$W" >/dev/null 2>&1 || true; fi;; esac
  rm -f "$WATCHDOG_PID"
}
stopcore(){
  if [ -f "$PIDFILE" ]; then P=$(cat "$PIDFILE" 2>/dev/null || true); case "$P" in ''|*[!0-9]*) ;; *)
    if pidcore "$P" && kill -0 "$P" >/dev/null 2>&1; then kill "$P" >/dev/null 2>&1 || true; N=0; while kill -0 "$P" >/dev/null 2>&1 && [ "$N" -lt 20 ]; do sleep 0.1; N=$((N+1)); done; if pidcore "$P" && kill -0 "$P" >/dev/null 2>&1; then kill -9 "$P" >/dev/null 2>&1 || true; fi; fi;; esac; fi
  # Recover orphaned Hetu cores left by a killed/reinstalled app. Match the private
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
direct_uid_returns(){ BIN="$1"; T="$2"; C="$3"; LIST="$4"; [ -z "$LIST" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS; for U in "$@"; do "$BIN" -t "$T" -A "$C" -m owner --uid-owner "$U" -j RETURN || return 1; done; }
system_uid_return(){ BIN="$1"; T="$2"; C="$3"; S="$4"; [ "$S" = whitelist ] && return 0; "$BIN" -t "$T" -A "$C" -m owner --uid-owner 0-9999 -j RETURN || return 1; }
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
scoped_reject_unmarked_udp(){
  BIN="$1"; C="$2"; S="$3"; LIST="$4"
  if [ "$S" = whitelist ]; then
    OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS
    for U in "$@"; do "$BIN" -t filter -A "$C" -m owner --uid-owner "$U" -p udp -j REJECT || return 1; done
  else
    "$BIN" -t filter -A "$C" -p udp -j REJECT || return 1
  fi
}

bypass4(){bypass4(){
  C="$1"; T="$2"; CIDRS="$3"
  for NET in 0.0.0.0/8 10.0.0.0/8 100.64.0.0/10 127.0.0.0/8 169.254.0.0/16 172.16.0.0/12 192.168.0.0/16 224.0.0.0/4 240.0.0.0/4; do xt4 -t "$T" -A "$C" -d "$NET" -j RETURN || return 1; done
  [ -z "$CIDRS" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $CIDRS; IFS=$OLDIFS; for NET in "$@"; do case "$NET" in *:*) ;; *) xt4 -t "$T" -A "$C" -d "$NET" -j RETURN || return 1;; esac; done
}
bypass6(){
  C="$1"; T="$2"; CIDRS="$3"
  for NET in ::1/128 fc00::/7 fe80::/10 ff00::/8; do xt6 -t "$T" -A "$C" -d "$NET" -j RETURN || return 1; done
  [ -z "$CIDRS" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $CIDRS; IFS=$OLDIFS; for NET in "$@"; do case "$NET" in *:*) xt6 -t "$T" -A "$C" -d "$NET" -j RETURN || return 1;; esac; done
}

probetp4(){ P="$1"; xt4 -t mangle -N HETU_PROBE >/dev/null 2>&1 || true; xt4 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true; xt4 -t mangle -A HETU_PROBE -p udp -j TPROXY --on-port "$P" --tproxy-mark "$PROBE_MARK/$PROBE_MASK" >/dev/null 2>&1; R=$?; xt4 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true; xt4 -t mangle -X HETU_PROBE >/dev/null 2>&1 || true; return "$R"; }
probetp6(){ P="$1"; has ip6tables || return 1; xt6 -t mangle -N HETU_PROBE >/dev/null 2>&1 || true; xt6 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true; xt6 -t mangle -A HETU_PROBE -p udp -j TPROXY --on-port "$P" --tproxy-mark "$PROBE_MARK/$PROBE_MASK" >/dev/null 2>&1; R=$?; xt6 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true; xt6 -t mangle -X HETU_PROBE >/dev/null 2>&1 || true; return "$R"; }
probered4(){ P="$1"; PROTO="${2:-tcp}"; xt4 -t nat -N HETU_PROBE >/dev/null 2>&1 || true; xt4 -t nat -F HETU_PROBE >/dev/null 2>&1 || true; xt4 -t nat -A HETU_PROBE -p "$PROTO" -j REDIRECT --to-ports "$P" >/dev/null 2>&1; R=$?; xt4 -t nat -F HETU_PROBE >/dev/null 2>&1 || true; xt4 -t nat -X HETU_PROBE >/dev/null 2>&1 || true; return "$R"; }
probered6(){ P="$1"; PROTO="${2:-tcp}"; has ip6tables || return 1; xt6 -t nat -N HETU_PROBE >/dev/null 2>&1 || true; xt6 -t nat -F HETU_PROBE >/dev/null 2>&1 || true; xt6 -t nat -A HETU_PROBE -p "$PROTO" -j REDIRECT --to-ports "$P" >/dev/null 2>&1; R=$?; xt6 -t nat -F HETU_PROBE >/dev/null 2>&1 || true; xt6 -t nat -X HETU_PROBE >/dev/null 2>&1 || true; return "$R"; }
probeowner(){
  U="$1"; [ -n "$U" ] || return 0
  xt4 -t mangle -N HETU_PROBE >/dev/null 2>&1 || true; xt4 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true
  xt4 -t mangle -A HETU_PROBE -m owner --uid-owner "$U" -j RETURN >/dev/null 2>&1; R=$?
  xt4 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true; xt4 -t mangle -X HETU_PROBE >/dev/null 2>&1 || true; return "$R"
}
probecidrs(){
  LIST="$1"; [ -z "$LIST" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS
  for X in "$@"; do
    if echo "$X" | grep -q ':'; then has ip6tables || return 1; xt6 -t mangle -N HETU_PROBE >/dev/null 2>&1 || true; xt6 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true; xt6 -t mangle -A HETU_PROBE -d "$X" -j RETURN >/dev/null 2>&1 || { xt6 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true; xt6 -t mangle -X HETU_PROBE >/dev/null 2>&1 || true; return 1; }; xt6 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true; xt6 -t mangle -X HETU_PROBE >/dev/null 2>&1 || true
    else xt4 -t mangle -N HETU_PROBE >/dev/null 2>&1 || true; xt4 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true; xt4 -t mangle -A HETU_PROBE -d "$X" -j RETURN >/dev/null 2>&1 || { xt4 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true; xt4 -t mangle -X HETU_PROBE >/dev/null 2>&1 || true; return 1; }; xt4 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true; xt4 -t mangle -X HETU_PROBE >/dev/null 2>&1 || true; fi
  done
}

preflight(){
  M="$1"; TP="$2"; RP="$3"; V6="$4"; TCP="$5"; UDP="$6"; DNS="$7"; QUIC="$8"; DP="$9"; CP="${10}"; SCOPE="${11}"; UIDS="${12}"; SHARE="${13}"; KILL="${14}"; CIDRS="${15}"; IFACES="${16}"; DIRECT_UIDS="${17}"
  root; mode "$M" || fail "运行模式无效"; ipv6mode "$V6" || fail "IPv6 模式无效"; dnsmode "$DNS" || fail "DNS 劫持模式无效"; scope "$SCOPE" || fail "应用范围无效"
  bool "$TCP" || fail "TCP 开关无效"; bool "$UDP" || fail "UDP 开关无效"; bool "$QUIC" || fail "QUIC 开关无效"; bool "$SHARE" || fail "共享网络开关无效"; bool "$KILL" || fail "Kill Switch 开关无效"
  port "$DP" || fail "DNS 监听端口无效"; port "$CP" || fail "控制接口端口无效"; has ip || fail "系统缺少 ip 命令"; has iptables || fail "系统缺少 iptables"
  split_safe_uids "$UIDS" || fail "应用 UID 列表无效"; split_safe_uids "$DIRECT_UIDS" || fail "DIRECT UID 列表无效"; split_safe_cidrs "$CIDRS" || fail "CIDR 绕过列表无效"; split_safe_ifaces "$IFACES" || fail "接口绕过列表无效"
  [ "$SCOPE" != whitelist ] || [ -n "$UIDS" ] || fail "仅所选应用代理模式没有可用 UID"
  if [ "$SCOPE" != core ] && [ -n "$UIDS" ]; then U=$(first_uid "$UIDS"); probeowner "$U" || fail "当前 iptables 不支持 owner UID 匹配"; fi
  if [ -n "$DIRECT_UIDS" ]; then DU=$(first_uid "$DIRECT_UIDS"); probeowner "$DU" || fail "当前 iptables 不支持 DIRECT UID 直连"; fi
  [ "$SCOPE" = whitelist ] || probeowner "0-9999" || fail "当前 iptables 不支持系统 UID 范围绕过"
  probecidrs "$CIDRS" || fail "CIDR 绕过列表包含当前系统不支持的地址"
  if [ "$M" = tun ] || [ "$M" = ebpf ]; then
    { [ -c /dev/tun ] || [ -c /dev/net/tun ]; } || fail "当前设备没有可用 TUN 字符设备"
  fi
  if [ "$M" = ebpf ]; then
    [ -d /sys/fs/bpf ] || fail "eBPF 需要已挂载的 /sys/fs/bpf"
    grep -qw bpf /proc/filesystems 2>/dev/null || fail "当前内核未启用 BPF 文件系统支持"
  fi

  NEED_TP=0; NEED_RP=0
  case "$M" in tproxy) if [ "$TCP" = 1 ] || [ "$UDP" = 1 ]; then NEED_TP=1; fi;; redirect) [ "$TCP" = 1 ] && NEED_RP=1;; enhance) [ "$TCP" = 1 ] && NEED_RP=1; [ "$UDP" = 1 ] && NEED_TP=1;; esac
  if [ "$DNS" = tproxy ] || [ "$DNS" = redirect ]; then
    probered4 "$DP" tcp || fail "当前 iptables 不支持 TCP DNS REDIRECT"
    probered4 "$DP" udp || fail "当前 iptables 不支持 UDP DNS REDIRECT"
  fi
  [ "$NEED_TP" = 1 ] && { port "$TP" || fail "TPROXY 端口无效"; probetp4 "$TP" || fail "当前内核或 iptables 不支持 TPROXY"; }
  [ "$NEED_RP" = 1 ] && { port "$RP" || fail "Redirect 端口无效"; probered4 "$RP" tcp || fail "当前 iptables 不支持 REDIRECT"; }
  if [ "$M" != tun ] && [ "$M" != ebpf ] && [ "$NEED_TP" = 0 ] && [ "$NEED_RP" = 0 ] && [ "$DNS" = off ]; then fail "TCP、UDP 与 DNS 接管均已关闭，代理没有可接管流量"; fi

  if [ "$V6" = enable ] && v6active; then
    [ "$NEED_TP" = 0 ] || probetp6 "$TP" || fail "IPv6 TPROXY 不可用，可改用严格 IPv4 或 IPv6 不进核心"
    [ "$NEED_RP" = 0 ] || probered6 "$RP" tcp || fail "IPv6 REDIRECT 不可用，可改用严格 IPv4 或 IPv6 不进核心"
    if [ "$DNS" = tproxy ] || [ "$DNS" = redirect ]; then probered6 "$DP" tcp || fail "IPv6 TCP DNS REDIRECT 不可用"; probered6 "$DP" udp || fail "IPv6 UDP DNS REDIRECT 不可用"; fi
  fi
  if [ "$V6" = strict ] && v6active; then has ip6tables || fail "严格 IPv4 需要 xt6"; fi
  if [ "$V6" = disable ]; then TESTED=0; for P in /proc/sys/net/ipv6/conf/*/disable_ipv6; do [ -w "$P" ] && TESTED=1 && break; done; [ "$TESTED" = 1 ] || fail "系统不允许临时禁用 IPv6"; fi
  ok "Root 代理预检通过"
}

route4(){ ip route replace local 0.0.0.0/0 dev lo table "$TABLE" || return 1; ip rule add pref "$PREF" fwmark "$MARK/$MASK" table "$TABLE" || return 1; }
route6(){ ip -6 route replace local ::/0 dev lo table "$TABLE" || return 1; ip -6 rule add pref "$PREF" fwmark "$MARK/$MASK" table "$TABLE" || return 1; }

install_mangle4(){
  P="$1"; M="$2"; TCP="$3"; UDP="$4"; DNS="$5"; S="$6"; UIDS="$7"; SHARE="$8"; CIDRS="$9"; IFACES="${10}"; DUIDS="${11}"
  NEED=0; case "$M" in tproxy) if [ "$TCP" = 1 ] || [ "$UDP" = 1 ]; then NEED=1; fi;; enhance) [ "$UDP" = 1 ] && NEED=1;; esac; [ "$NEED" = 1 ] || return 0
  route4 || return 1; xt4 -t mangle -N "$MOUT" || return 1; xt4 -t mangle -N "$MPRE" || return 1
  xt4 -t mangle -A "$MOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1
  system_uid_return xt4 mangle "$MOUT" "$S" || return 1
  direct_uid_returns xt4 mangle "$MOUT" "$DUIDS" || return 1
  iface_out xt4 mangle "$MOUT" "$IFACES" || return 1; blacklist_returns xt4 mangle "$MOUT" "$S" "$UIDS" || return 1
  xt4 -t mangle -A "$MPRE" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_in xt4 mangle "$MPRE" "$IFACES" || return 1
  if [ "$DNS" = tproxy ] || [ "$DNS" = redirect ]; then
    xt4 -t mangle -A "$MOUT" -p tcp --dport 53 -j RETURN || return 1
    xt4 -t mangle -A "$MOUT" -p udp --dport 53 -j RETURN || return 1
  fi
  bypass4 "$MOUT" mangle "$CIDRS" || return 1; bypass4 "$MPRE" mangle "$CIDRS" || return 1
  if [ "$M" = tproxy ]; then
    [ "$TCP" = 0 ] || { scoped_mark xt4 mangle "$MOUT" "$S" "$UIDS" tcp "" "$MARK/$MASK" || return 1; if [ "$SHARE" = 1 ]; then xt4 -t mangle -A "$MPRE" -p tcp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; else xt4 -t mangle -A "$MPRE" -m mark --mark "$MARK/$MASK" -p tcp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; fi; }
    [ "$UDP" = 0 ] || { scoped_mark xt4 mangle "$MOUT" "$S" "$UIDS" udp "" "$MARK/$MASK" || return 1; if [ "$SHARE" = 1 ]; then xt4 -t mangle -A "$MPRE" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; else xt4 -t mangle -A "$MPRE" -m mark --mark "$MARK/$MASK" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; fi; }
  elif [ "$M" = enhance ] && [ "$UDP" = 1 ]; then scoped_mark xt4 mangle "$MOUT" "$S" "$UIDS" udp "" "$MARK/$MASK" || return 1; if [ "$SHARE" = 1 ]; then xt4 -t mangle -A "$MPRE" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; else xt4 -t mangle -A "$MPRE" -m mark --mark "$MARK/$MASK" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; fi; fi
  xt4 -t mangle -A OUTPUT -j "$MOUT" || return 1; xt4 -t mangle -A PREROUTING -j "$MPRE" || return 1
}

install_mangle6(){
  P="$1"; M="$2"; TCP="$3"; UDP="$4"; DNS="$5"; S="$6"; UIDS="$7"; SHARE="$8"; CIDRS="$9"; IFACES="${10}"; DUIDS="${11}"; v6active || return 0
  NEED=0; case "$M" in tproxy) if [ "$TCP" = 1 ] || [ "$UDP" = 1 ]; then NEED=1; fi;; enhance) [ "$UDP" = 1 ] && NEED=1;; esac; [ "$NEED" = 1 ] || return 0
  route6 || return 1; xt6 -t mangle -N "$MOUT" || return 1; xt6 -t mangle -N "$MPRE" || return 1
  xt6 -t mangle -A "$MOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1
  system_uid_return xt6 mangle "$MOUT" "$S" || return 1
  direct_uid_returns xt6 mangle "$MOUT" "$DUIDS" || return 1
  iface_out xt6 mangle "$MOUT" "$IFACES" || return 1; blacklist_returns xt6 mangle "$MOUT" "$S" "$UIDS" || return 1
  xt6 -t mangle -A "$MPRE" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_in xt6 mangle "$MPRE" "$IFACES" || return 1
  if [ "$DNS" = tproxy ] || [ "$DNS" = redirect ]; then
    xt6 -t mangle -A "$MOUT" -p tcp --dport 53 -j RETURN || return 1
    xt6 -t mangle -A "$MOUT" -p udp --dport 53 -j RETURN || return 1
  fi
  bypass6 "$MOUT" mangle "$CIDRS" || return 1; bypass6 "$MPRE" mangle "$CIDRS" || return 1
  if [ "$M" = tproxy ]; then
    [ "$TCP" = 0 ] || { scoped_mark xt6 mangle "$MOUT" "$S" "$UIDS" tcp "" "$MARK/$MASK" || return 1; if [ "$SHARE" = 1 ]; then xt6 -t mangle -A "$MPRE" -p tcp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; else xt6 -t mangle -A "$MPRE" -m mark --mark "$MARK/$MASK" -p tcp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; fi; }
    [ "$UDP" = 0 ] || { scoped_mark xt6 mangle "$MOUT" "$S" "$UIDS" udp "" "$MARK/$MASK" || return 1; if [ "$SHARE" = 1 ]; then xt6 -t mangle -A "$MPRE" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; else xt6 -t mangle -A "$MPRE" -m mark --mark "$MARK/$MASK" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; fi; }
  elif [ "$M" = enhance ] && [ "$UDP" = 1 ]; then scoped_mark xt6 mangle "$MOUT" "$S" "$UIDS" udp "" "$MARK/$MASK" || return 1; if [ "$SHARE" = 1 ]; then xt6 -t mangle -A "$MPRE" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; else xt6 -t mangle -A "$MPRE" -m mark --mark "$MARK/$MASK" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; fi; fi
  xt6 -t mangle -A OUTPUT -j "$MOUT" || return 1; xt6 -t mangle -A PREROUTING -j "$MPRE" || return 1
}

install_redirect4(){
  P="$1"; M="$2"; TCP="$3"; S="$4"; UIDS="$5"; SHARE="$6"; CIDRS="$7"; IFACES="$8"; DUIDS="$9"; [ "$TCP" = 1 ] || return 0; case "$M" in redirect|enhance) ;; *) return 0;; esac
  xt4 -t nat -N "$NOUT" || return 1; xt4 -t nat -A "$NOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; system_uid_return xt4 nat "$NOUT" "$S" || return 1; direct_uid_returns xt4 nat "$NOUT" "$DUIDS" || return 1; iface_out xt4 nat "$NOUT" "$IFACES" || return 1; blacklist_returns xt4 nat "$NOUT" "$S" "$UIDS" || return 1; bypass4 "$NOUT" nat "$CIDRS" || return 1; scoped_redirect xt4 nat "$NOUT" "$S" "$UIDS" tcp "" "$P" || return 1; xt4 -t nat -A OUTPUT -j "$NOUT" || return 1
  if [ "$SHARE" = 1 ]; then xt4 -t nat -N "$NPRE" || return 1; xt4 -t nat -A "$NPRE" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_in xt4 nat "$NPRE" "$IFACES" || return 1; bypass4 "$NPRE" nat "$CIDRS" || return 1; xt4 -t nat -A "$NPRE" -p tcp -j REDIRECT --to-ports "$P" || return 1; xt4 -t nat -A PREROUTING -j "$NPRE" || return 1; fi
}
install_redirect6(){
  P="$1"; M="$2"; TCP="$3"; S="$4"; UIDS="$5"; SHARE="$6"; CIDRS="$7"; IFACES="$8"; DUIDS="$9"; v6active || return 0; [ "$TCP" = 1 ] || return 0; case "$M" in redirect|enhance) ;; *) return 0;; esac
  xt6 -t nat -N "$NOUT" || return 1; xt6 -t nat -A "$NOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; system_uid_return xt6 nat "$NOUT" "$S" || return 1; direct_uid_returns xt6 nat "$NOUT" "$DUIDS" || return 1; iface_out xt6 nat "$NOUT" "$IFACES" || return 1; blacklist_returns xt6 nat "$NOUT" "$S" "$UIDS" || return 1; bypass6 "$NOUT" nat "$CIDRS" || return 1; scoped_redirect xt6 nat "$NOUT" "$S" "$UIDS" tcp "" "$P" || return 1; xt6 -t nat -A OUTPUT -j "$NOUT" || return 1
  if [ "$SHARE" = 1 ]; then xt6 -t nat -N "$NPRE" || return 1; xt6 -t nat -A "$NPRE" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_in xt6 nat "$NPRE" "$IFACES" || return 1; bypass6 "$NPRE" nat "$CIDRS" || return 1; xt6 -t nat -A "$NPRE" -p tcp -j REDIRECT --to-ports "$P" || return 1; xt6 -t nat -A PREROUTING -j "$NPRE" || return 1; fi
}

install_dns_redirect4(){
  P="$1"; S="$2"; UIDS="$3"; SHARE="$4"; IFACES="$5"; xt4 -t nat -N "$DNSOUT" || return 1; xt4 -t nat -A "$DNSOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; xt4 -t nat -A "$DNSOUT" -m owner --uid-owner 0 -j RETURN || return 1; iface_out xt4 nat "$DNSOUT" "$IFACES" || return 1; blacklist_returns xt4 nat "$DNSOUT" "$S" "$UIDS" || return 1
  for X in tcp udp; do scoped_redirect xt4 nat "$DNSOUT" "$S" "$UIDS" "$X" 53 "$P" || return 1; done; xt4 -t nat -I OUTPUT 1 -j "$DNSOUT" || return 1
  if [ "$SHARE" = 1 ]; then xt4 -t nat -N "$DNSPRE" || return 1; iface_in xt4 nat "$DNSPRE" "$IFACES" || return 1; for X in tcp udp; do xt4 -t nat -A "$DNSPRE" -p "$X" --dport 53 -j REDIRECT --to-ports "$P" || return 1; done; xt4 -t nat -I PREROUTING 1 -j "$DNSPRE" || return 1; fi
}
install_dns_redirect6(){
  P="$1"; S="$2"; UIDS="$3"; SHARE="$4"; IFACES="$5"; v6active || return 0; xt6 -t nat -N "$DNSOUT" || return 1; xt6 -t nat -A "$DNSOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; xt6 -t nat -A "$DNSOUT" -m owner --uid-owner 0 -j RETURN || return 1; iface_out xt6 nat "$DNSOUT" "$IFACES" || return 1; blacklist_returns xt6 nat "$DNSOUT" "$S" "$UIDS" || return 1
  for X in tcp udp; do scoped_redirect xt6 nat "$DNSOUT" "$S" "$UIDS" "$X" 53 "$P" || return 1; done; xt6 -t nat -I OUTPUT 1 -j "$DNSOUT" || return 1
  if [ "$SHARE" = 1 ]; then xt6 -t nat -N "$DNSPRE" || return 1; iface_in xt6 nat "$DNSPRE" "$IFACES" || return 1; for X in tcp udp; do xt6 -t nat -A "$DNSPRE" -p "$X" --dport 53 -j REDIRECT --to-ports "$P" || return 1; done; xt6 -t nat -I PREROUTING 1 -j "$DNSPRE" || return 1; fi
}

install_udp_leak_guard4(){
  S="$1"; UIDS="$2"; SHARE="$3"; CIDRS="$4"; IFACES="$5"; DUIDS="$6"
  xt4 -t filter -N "$WROUT" || return 1
  xt4 -t filter -A "$WROUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1
  if [ -n "$MARK" ]; then xt4 -t filter -A "$WROUT" -m mark --mark "$MARK/$MASK" -j RETURN || return 1; fi
  system_uid_return xt4 filter "$WROUT" "$S" || return 1
  direct_uid_returns xt4 filter "$WROUT" "$DUIDS" || return 1
  iface_out xt4 filter "$WROUT" "$IFACES" || return 1
  blacklist_returns xt4 filter "$WROUT" "$S" "$UIDS" || return 1
  bypass4 "$WROUT" filter "$CIDRS" || return 1
  scoped_reject_unmarked_udp xt4 "$WROUT" "$S" "$UIDS" || return 1
  xt4 -t filter -I OUTPUT 1 -j "$WROUT" || return 1
  if [ "$SHARE" = 1 ]; then
    xt4 -t filter -N "$WRFWD" || return 1
    if [ -n "$MARK" ]; then xt4 -t filter -A "$WRFWD" -m mark --mark "$MARK/$MASK" -j RETURN || return 1; fi
    iface_in xt4 filter "$WRFWD" "$IFACES" || return 1
    bypass4 "$WRFWD" filter "$CIDRS" || return 1
    xt4 -t filter -A "$WRFWD" -p udp -j REJECT || return 1
    xt4 -t filter -I FORWARD 1 -j "$WRFWD" || return 1
  fi
}
install_udp_leak_guard6(){
  S="$1"; UIDS="$2"; SHARE="$3"; CIDRS="$4"; IFACES="$5"; DUIDS="$6"; v6active || return 0
  xt6 -t filter -N "$WROUT" || return 1
  xt6 -t filter -A "$WROUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1
  if [ -n "$MARK" ]; then xt6 -t filter -A "$WROUT" -m mark --mark "$MARK/$MASK" -j RETURN || return 1; fi
  system_uid_return xt6 filter "$WROUT" "$S" || return 1
  direct_uid_returns xt6 filter "$WROUT" "$DUIDS" || return 1
  iface_out xt6 filter "$WROUT" "$IFACES" || return 1
  blacklist_returns xt6 filter "$WROUT" "$S" "$UIDS" || return 1
  bypass6 "$WROUT" filter "$CIDRS" || return 1
  scoped_reject_unmarked_udp xt6 "$WROUT" "$S" "$UIDS" || return 1
  xt6 -t filter -I OUTPUT 1 -j "$WROUT" || return 1
  if [ "$SHARE" = 1 ]; then
    xt6 -t filter -N "$WRFWD" || return 1
    if [ -n "$MARK" ]; then xt6 -t filter -A "$WRFWD" -m mark --mark "$MARK/$MASK" -j RETURN || return 1; fi
    iface_in xt6 filter "$WRFWD" "$IFACES" || return 1
    bypass6 "$WRFWD" filter "$CIDRS" || return 1
    xt6 -t filter -A "$WRFWD" -p udp -j REJECT || return 1
    xt6 -t filter -I FORWARD 1 -j "$WRFWD" || return 1
  fi
}

install_quic4(){install_quic4(){
  S="$1"; UIDS="$2"; SHARE="$3"; CIDRS="$4"; IFACES="$5"; DUIDS="$6"; xt4 -t filter -N "$QUICOUT" || return 1; xt4 -t filter -A "$QUICOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; system_uid_return xt4 filter "$QUICOUT" "$S" || return 1; direct_uid_returns xt4 filter "$QUICOUT" "$DUIDS" || return 1; iface_out xt4 filter "$QUICOUT" "$IFACES" || return 1; blacklist_returns xt4 filter "$QUICOUT" "$S" "$UIDS" || return 1; bypass4 "$QUICOUT" filter "$CIDRS" || return 1; scoped_drop_quic xt4 "$QUICOUT" "$S" "$UIDS" || return 1; xt4 -t filter -A OUTPUT -j "$QUICOUT" || return 1
  if [ "$SHARE" = 1 ]; then xt4 -t filter -N "$QUICFWD" || return 1; iface_in xt4 filter "$QUICFWD" "$IFACES" || return 1; bypass4 "$QUICFWD" filter "$CIDRS" || return 1; xt4 -t filter -A "$QUICFWD" -p udp --dport 443 -j DROP || return 1; xt4 -t filter -A FORWARD -j "$QUICFWD" || return 1; fi
}
install_quic6(){
  S="$1"; UIDS="$2"; SHARE="$3"; CIDRS="$4"; IFACES="$5"; DUIDS="$6"; v6active || return 0; xt6 -t filter -N "$QUICOUT" || return 1; xt6 -t filter -A "$QUICOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; system_uid_return xt6 filter "$QUICOUT" "$S" || return 1; direct_uid_returns xt6 filter "$QUICOUT" "$DUIDS" || return 1; iface_out xt6 filter "$QUICOUT" "$IFACES" || return 1; blacklist_returns xt6 filter "$QUICOUT" "$S" "$UIDS" || return 1; bypass6 "$QUICOUT" filter "$CIDRS" || return 1; scoped_drop_quic xt6 "$QUICOUT" "$S" "$UIDS" || return 1; xt6 -t filter -A OUTPUT -j "$QUICOUT" || return 1
  if [ "$SHARE" = 1 ]; then xt6 -t filter -N "$QUICFWD" || return 1; iface_in xt6 filter "$QUICFWD" "$IFACES" || return 1; bypass6 "$QUICFWD" filter "$CIDRS" || return 1; xt6 -t filter -A "$QUICFWD" -p udp --dport 443 -j DROP || return 1; xt6 -t filter -A FORWARD -j "$QUICFWD" || return 1; fi
}

install_v6_strict(){
  S="$1"; UIDS="$2"; SHARE="$3"; CIDRS="$4"; IFACES="$5"; DUIDS="$6"; v6active || return 0; xt6 -t filter -N "$V6OUT" || return 1; xt6 -t filter -A "$V6OUT" -o lo -j RETURN || return 1; xt6 -t filter -A "$V6OUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_out xt6 filter "$V6OUT" "$IFACES" || return 1; direct_uid_returns xt6 filter "$V6OUT" "$DUIDS" || return 1; blacklist_returns xt6 filter "$V6OUT" "$S" "$UIDS" || return 1; bypass6 "$V6OUT" filter "$CIDRS" || return 1; scoped_reject_all xt6 "$V6OUT" "$S" "$UIDS" || return 1; xt6 -t filter -A OUTPUT -j "$V6OUT" || return 1
  if [ "$SHARE" = 1 ]; then xt6 -t filter -N "$V6FWD" || return 1; iface_in xt6 filter "$V6FWD" "$IFACES" || return 1; bypass6 "$V6FWD" filter "$CIDRS" || return 1; xt6 -t filter -A "$V6FWD" -j REJECT || return 1; xt6 -t filter -A FORWARD -j "$V6FWD" || return 1; fi
}

install_kill4(){
  S="$1"; UIDS="$2"; SHARE="$3"; CIDRS="$4"; IFACES="$5"; DUIDS="$6"; xt4 -t filter -N "$KOUT" >/dev/null 2>&1 || true; xt4 -t filter -F "$KOUT" || return 1; xt4 -t filter -A "$KOUT" -o lo -j RETURN || return 1; iface_out xt4 filter "$KOUT" "$IFACES" || return 1; direct_uid_returns xt4 filter "$KOUT" "$DUIDS" || return 1; blacklist_returns xt4 filter "$KOUT" "$S" "$UIDS" || return 1; bypass4 "$KOUT" filter "$CIDRS" || return 1; scoped_reject_all xt4 "$KOUT" "$S" "$UIDS" || return 1; xt4 -t filter -I OUTPUT 1 -j "$KOUT" || return 1
  if [ "$SHARE" = 1 ]; then xt4 -t filter -N "$KFWD" >/dev/null 2>&1 || true; xt4 -t filter -F "$KFWD" || return 1; iface_in xt4 filter "$KFWD" "$IFACES" || return 1; bypass4 "$KFWD" filter "$CIDRS" || return 1; xt4 -t filter -A "$KFWD" -j REJECT || return 1; xt4 -t filter -I FORWARD 1 -j "$KFWD" || return 1; fi
}
install_kill6(){
  S="$1"; UIDS="$2"; SHARE="$3"; CIDRS="$4"; IFACES="$5"; DUIDS="$6"; has ip6tables || return 0; v6active || return 0; xt6 -t filter -N "$KOUT" >/dev/null 2>&1 || true; xt6 -t filter -F "$KOUT" || return 1; xt6 -t filter -A "$KOUT" -o lo -j RETURN || return 1; iface_out xt6 filter "$KOUT" "$IFACES" || return 1; direct_uid_returns xt6 filter "$KOUT" "$DUIDS" || return 1; blacklist_returns xt6 filter "$KOUT" "$S" "$UIDS" || return 1; bypass6 "$KOUT" filter "$CIDRS" || return 1; scoped_reject_all xt6 "$KOUT" "$S" "$UIDS" || return 1; xt6 -t filter -I OUTPUT 1 -j "$KOUT" || return 1
  if [ "$SHARE" = 1 ]; then xt6 -t filter -N "$KFWD" >/dev/null 2>&1 || true; xt6 -t filter -F "$KFWD" || return 1; iface_in xt6 filter "$KFWD" "$IFACES" || return 1; bypass6 "$KFWD" filter "$CIDRS" || return 1; xt6 -t filter -A "$KFWD" -j REJECT || return 1; xt6 -t filter -I FORWARD 1 -j "$KFWD" || return 1; fi
}

validatecfg(){ BIN="$1"; CFG="$2"; : > "$CHECKLOG"; "$BIN" -t -d "$RUN" -f "$CFG" >>"$CHECKLOG" 2>&1; }
hexport(){ printf '%04X' "$1" 2>/dev/null; }
tcp_listen(){ P="$1"; if has ss && ss -lnt 2>/dev/null | grep -Eq "[:.]${P}([[:space:]]|$)"; then return 0; fi; if has netstat && netstat -lnt 2>/dev/null | grep -Eq "[:.]${P}([[:space:]]|$)"; then return 0; fi; H=$(hexport "$P") || return 1; awk -v x=":$H" '$2 ~ x"$" && $4=="0A" {found=1} END{exit(found?0:1)}' /proc/net/tcp /proc/net/tcp6 2>/dev/null; }
udp_listen(){ P="$1"; if has ss && ss -lnu 2>/dev/null | grep -Eq "[:.]${P}([[:space:]]|$)"; then return 0; fi; if has netstat && netstat -lnu 2>/dev/null | grep -Eq "[:.]${P}([[:space:]]|$)"; then return 0; fi; H=$(hexport "$P") || return 1; awk -v x=":$H" '$2 ~ x"$" {found=1} END{exit(found?0:1)}' /proc/net/udp /proc/net/udp6 2>/dev/null; }
ready(){
  PID="$1"; M="$2"; TP="$3"; RP="$4"; TCP="$5"; UDP="$6"; DNS="$7"; DP="$8"; CP="$9"; pidcore "$PID" && kill -0 "$PID" >/dev/null 2>&1 || return 1; tcp_listen "$CP" || return 1
  case "$M" in tproxy) [ "$TCP" = 0 ] || tcp_listen "$TP" || return 1; [ "$UDP" = 0 ] || udp_listen "$TP" || return 1;; redirect) [ "$TCP" = 0 ] || tcp_listen "$RP" || return 1;; enhance) [ "$TCP" = 0 ] || tcp_listen "$RP" || return 1; [ "$UDP" = 0 ] || udp_listen "$TP" || return 1;; tun|ebpf) ip link show hetu0 >/dev/null 2>&1 || return 1;; esac
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
  # run inside Hetu's private HomeDir their caches are cold; Mihomo keeps the process
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

write_session(){ M="$1"; V6="$2"; DNS="$3"; DP="$4"; S="$5"; SHARE="$6"; KILL="$7"; CP="$8"; DUIDS="$9"; { printf 'MODE=%s\n' "$M"; printf 'IPV6=%s\n' "$V6"; printf 'DNS=%s\n' "$DNS"; printf 'DNS_PORT=%s\n' "$DP"; printf 'APP_SCOPE=%s\n' "$S"; printf 'SHARE=%s\n' "$SHARE"; printf 'KILL=%s\n' "$KILL"; printf 'CONTROLLER_PORT=%s\n' "$CP"; printf 'DIRECT_UIDS=%s\n' "$DUIDS"; } > "$SESSION.new.$" && mv -f "$SESSION.new.$" "$SESSION"; }
watchdog(){
  COREPID="$1"; KILL="$2"; S="$3"; UIDS="$4"; SHARE="$5"; CIDRS="$6"; IFACES="$7"; DUIDS="$8"
  mkdir -p "$RUN" || exit 0; printf '%s\n' "$$" > "$WATCHDOG_PID"; MISS=0; while [ "$MISS" -lt 3 ]; do if pidcore "$COREPID" && kill -0 "$COREPID" >/dev/null 2>&1; then MISS=0; sleep 2; else MISS=$((MISS+1)); sleep 0.20; fi; done; acquire_lock || exit 0
  REC=$(cat "$PIDFILE" 2>/dev/null || true)
  if [ "$REC" = "$COREPID" ]; then
    cleanup; restorev6; rm -f "$PIDFILE"
    RESULT="network-restored"
    if [ "$KILL" = 1 ]; then if install_kill4 "$S" "$UIDS" "$SHARE" "$CIDRS" "$IFACES" "$DUIDS" && install_kill6 "$S" "$UIDS" "$SHARE" "$CIDRS" "$IFACES" "$DUIDS"; then RESULT="killswitch-active"; else RESULT="killswitch-failed"; fi; else rm -f "$MODEFILE" "$SESSION"; fi
    date '+%Y-%m-%dT%H:%M:%S%z core exited; '"$RESULT" > "$CRASH_STATE" 2>/dev/null || true; printf '%s core=%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$COREPID" "$RESULT" >> "$WATCHDOG_LOG" 2>/dev/null || true
  fi
  rm -f "$WATCHDOG_PID"
}
start_watchdog(){ COREPID="$1"; KILL="$2"; S="$3"; UIDS="$4"; SHARE="$5"; CIDRS="$6"; IFACES="$7"; DUIDS="$8"; stopwatchdog; "$0" watchdog "$COREPID" "$KILL" "$S" "$UIDS" "$SHARE" "$CIDRS" "$IFACES" "$DUIDS" >/dev/null 2>&1 & }

start(){
  START_BIN="$1"; START_CFG="$2"; START_MODE="$3"; START_TP="$4"; START_RP="$5"; START_V6="$6"; START_TCP="$7"; START_UDP="$8"; START_DNS="$9"; START_QUIC="${10}"; START_DP="${11}"; START_CP="${12}"; START_SCOPE="${13}"; START_UIDS="${14}"; START_SHARE="${15}"; START_KILL="${16}"; START_CIDRS="${17}"; START_IFACES="${18}"; START_DIRECT_UIDS="${19}"
  rm -f "$START_ERROR"; start_stage "preflight"
  preflight "$START_MODE" "$START_TP" "$START_RP" "$START_V6" "$START_TCP" "$START_UDP" "$START_DNS" "$START_QUIC" "$START_DP" "$START_CP" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_KILL" "$START_CIDRS" "$START_IFACES" "$START_DIRECT_UIDS" >/dev/null
  [ -x "$START_BIN" ] || fail "核心文件不存在或不可执行"; [ -r "$START_CFG" ] || fail "启动配置不存在"; mkdir -p "$RUN" || fail "无法创建运行目录"; validatecfg "$START_BIN" "$START_CFG" || fail "Mihomo 配置校验失败，当前网络未被接管"; acquire_lock || fail "另一个代理网络事务正在执行，请稍后重试"
  stopwatchdog; cleanup; restorev6; stopcore; sleep 0.20; rm -f "$CRASH_STATE" "$SESSION"
  check_start_ports "$START_MODE" "$START_TP" "$START_RP" "$START_TCP" "$START_UDP" "$START_DNS" "$START_DP" "$START_CP"
  markused "$BYPASS_MARK" && fail "安全出站 mark 已被其他网络规则占用，未接管网络"
  NEED_TP=0; case "$START_MODE" in tproxy) if [ "$START_TCP" = 1 ] || [ "$START_UDP" = 1 ]; then NEED_TP=1; fi;; enhance) [ "$START_UDP" = 1 ] && NEED_TP=1;; esac; if [ "$START_DNS" = tproxy ] && [ "$START_MODE" != tun ] && [ "$START_MODE" != ebpf ]; then NEED_TP=1; fi
  if [ "$NEED_TP" = 1 ]; then allocnet || { cleanup; fail "找不到安全的 fwmark/路由表/规则优先级，已保持直连"; }; fi
  if [ "$START_V6" = disable ]; then disablev6 || { cleanup; fail "禁用系统 IPv6 失败，已恢复原状态"; }; fi

  mkdir -p "$RUN/rules" "$RUN/proxy_provider" "$RUN/ruleset" "$RUN/ui" || { cleanup; restorev6; rm -f "$SESSION"; fail "无法创建 Mihomo 运行缓存目录"; }
  start_stage "launch-core"
  : > "$LOG"; "$START_BIN" -d "$RUN" -f "$START_CFG" >>"$LOG" 2>&1 & START_PID=$!; printf '%s\n' "$START_PID" > "$PIDFILE"; printf '%s\n' "$START_MODE" > "$MODEFILE"; write_session "$START_MODE" "$START_V6" "$START_DNS" "$START_DP" "$START_SCOPE" "$START_SHARE" "$START_KILL" "$START_CP" "$START_DIRECT_UIDS"
  start_stage "wait-listeners"
  wait_ready "$START_PID" "$START_MODE" "$START_TP" "$START_RP" "$START_TCP" "$START_UDP" "$START_DNS" "$START_DP" "$START_CP"; READY_RC=$?
  if [ "$READY_RC" -ne 0 ]; then
    if [ "$READY_RC" -eq 2 ]; then READY_MSG="Mihomo 启动后提前退出，请查看核心日志"; else READY_MSG="Mihomo 初始化超过 90 秒，代理入站/DNS/API 监听仍未就绪；首次加载大量远程订阅或规则时请检查网络与核心日志"; fi
    stopcore; cleanup; restorev6; rm -f "$SESSION"; fail "$READY_MSG"
  fi

  if [ "$START_MODE" = ebpf ]; then
    sleep 0.25
    if grep -Ei '(^|[^a-z])(e?bpf|bpf)([^a-z]|$)' "$LOG" 2>/dev/null | tail -n 20 | grep -Eqi 'error|failed|failure|not supported|operation not permitted|permission denied|attach.*fail'; then
      stopcore; cleanup; restorev6; rm -f "$SESSION"; fail "eBPF attach 失败；当前内核/接口不兼容，请改用 TUN 或 TPROXY"
    fi
  fi

  start_stage "install-ipv4-tproxy"
  install_mangle4 "$START_TP" "$START_MODE" "$START_TCP" "$START_UDP" "$START_DNS" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" "$START_DIRECT_UIDS" || { cleanup; stopcore; restorev6; rm -f "$SESSION"; fail "IPv4 TPROXY 规则安装失败，已回滚"; }
  start_stage "install-ipv4-redirect"
  install_redirect4 "$START_RP" "$START_MODE" "$START_TCP" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" "$START_DIRECT_UIDS" || { cleanup; stopcore; restorev6; rm -f "$SESSION"; fail "IPv4 Redirect 规则安装失败，已回滚"; }
  start_stage "install-ipv4-dns"
  if [ "$START_MODE" != tun ] && [ "$START_MODE" != ebpf ] && [ "$START_DNS" != off ]; then install_dns_redirect4 "$START_DP" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_IFACES" || { cleanup; stopcore; restorev6; rm -f "$SESSION"; fail "IPv4 DNS 劫持安装失败，已回滚"; }; fi
  start_stage "install-udp-leak-guard"
  if [ "$START_UDP" = 1 ]; then
    case "$START_MODE" in
      tproxy|enhance)
        install_udp_leak_guard4 "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" "$START_DIRECT_UIDS" || { cleanup; stopcore; restorev6; rm -f "$SESSION"; fail "IPv4 UDP 防裸连规则安装失败，已回滚"; }
        if [ "$START_V6" = enable ]; then install_udp_leak_guard6 "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" "$START_DIRECT_UIDS" || { cleanup; stopcore; restorev6; rm -f "$SESSION"; fail "IPv6 UDP 防裸连规则安装失败，已回滚"; }; fi
        ;;
    esac
  fi
  start_stage "install-ipv4-quic"
  [ "$START_QUIC" = 0 ] || install_quic4 "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" "$START_DIRECT_UIDS" || { cleanup; stopcore; restorev6; rm -f "$SESSION"; fail "IPv4 QUIC 策略安装失败，已回滚"; }
  start_stage "install-ipv6"
  if [ "$START_V6" = enable ]; then
    install_mangle6 "$START_TP" "$START_MODE" "$START_TCP" "$START_UDP" "$START_DNS" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" "$START_DIRECT_UIDS" || { cleanup; stopcore; restorev6; rm -f "$SESSION"; fail "IPv6 TPROXY 规则安装失败，已回滚"; }
    install_redirect6 "$START_RP" "$START_MODE" "$START_TCP" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" "$START_DIRECT_UIDS" || { cleanup; stopcore; restorev6; rm -f "$SESSION"; fail "IPv6 Redirect 规则安装失败，已回滚"; }
    if [ "$START_MODE" != tun ] && [ "$START_MODE" != ebpf ] && [ "$START_DNS" != off ]; then install_dns_redirect6 "$START_DP" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_IFACES" || { cleanup; stopcore; restorev6; rm -f "$SESSION"; fail "IPv6 DNS 劫持安装失败，已回滚"; }; fi
    [ "$START_QUIC" = 0 ] || install_quic6 "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" "$START_DIRECT_UIDS" || { cleanup; stopcore; restorev6; rm -f "$SESSION"; fail "IPv6 QUIC 策略安装失败，已回滚"; }
  elif [ "$START_V6" = strict ]; then install_v6_strict "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" "$START_DIRECT_UIDS" || { cleanup; stopcore; restorev6; rm -f "$SESSION"; fail "严格 IPv4 防泄漏规则安装失败，已回滚"; }; fi

  start_stage "start-watchdog"
  start_watchdog "$START_PID" "$START_KILL" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" "$START_DIRECT_UIDS"
  rm -f "$START_ERROR"; start_stage "running"
  DESC="tcp=$START_TCP,udp=$START_UDP,dns=$START_DNS,ipv6=$START_V6,scope=$START_SCOPE,share=$START_SHARE,kill=$START_KILL,quicBlock=$START_QUIC,directUids=$START_DIRECT_UIDS"
  if [ -n "$MARK" ]; then ok "Root $START_MODE 已启动（$DESC，mark=$MARK，table=$TABLE）"; else ok "Root $START_MODE 已启动（$DESC）"; fi
}

status(){
  root
  STATUS_RUNNING=false; STATUS_PID=0
  if [ -f "$PIDFILE" ]; then
    X=$(cat "$PIDFILE" 2>/dev/null || true)
    case "$X" in ''|*[!0-9]*) ;; *) if pidcore "$X" && kill -0 "$X" >/dev/null 2>&1; then STATUS_RUNNING=true; STATUS_PID="$X"; fi;; esac
  fi
  if [ "$STATUS_RUNNING" = false ]; then
    RECOVER_PID=$(findcorepid 2>/dev/null || true)
    case "$RECOVER_PID" in ''|*[!0-9]*) ;; *) STATUS_RUNNING=true; STATUS_PID="$RECOVER_PID"; printf '%s\n' "$RECOVER_PID" > "$PIDFILE" 2>/dev/null || true;; esac
  fi

  STATUS_MODE=$(cat "$MODEFILE" 2>/dev/null || echo none)
  SCOPEV=$(sed -n 's/^APP_SCOPE=//p' "$SESSION" 2>/dev/null | head -n 1)
  DIRECTV=$(sed -n 's/^DIRECT_UIDS=//p' "$SESSION" 2>/dev/null | head -n 1)
  SHAREV=$(sed -n 's/^SHARE=//p' "$SESSION" 2>/dev/null | head -n 1)
  KILLV=$(sed -n 's/^KILL=//p' "$SESSION" 2>/dev/null | head -n 1)
  DNSV=$(sed -n 's/^DNS=//p' "$SESSION" 2>/dev/null | head -n 1); [ -n "$DNSV" ] || DNSV=off
  DPV=$(sed -n 's/^DNS_PORT=//p' "$SESSION" 2>/dev/null | head -n 1); case "$DPV" in ''|*[!0-9]*) DPV=1053;; esac
  IPV6V=$(sed -n 's/^IPV6=//p' "$SESSION" 2>/dev/null | head -n 1); [ -n "$IPV6V" ] || IPV6V=enable
  CPV=$(sed -n 's/^CONTROLLER_PORT=//p' "$SESSION" 2>/dev/null | head -n 1)
  case "$CPV" in ''|*[!0-9]*) CPV=0;; esac
  if [ "$CPV" = 0 ]; then
    CPV=$(sed -n 's/^[[:space:]]*external-controller:[[:space:]]*127\.0\.0\.1:\([0-9][0-9]*\)[[:space:]]*$/\1/p' "$RUN/state/startup-config" 2>/dev/null | tail -n 1)
    case "$CPV" in ''|*[!0-9]*) CPV=0;; esac
  fi

  M4O=false; M4P=false; N4O=false; N4P=false; D4O=false; D4P=false
  M6O=false; M6P=false; N6O=false; N6P=false; D6O=false; D6P=false
  xt4q -t mangle -C OUTPUT -j "$MOUT" >/dev/null 2>&1 && M4O=true
  xt4q -t mangle -C PREROUTING -j "$MPRE" >/dev/null 2>&1 && M4P=true
  xt4q -t nat -C OUTPUT -j "$NOUT" >/dev/null 2>&1 && N4O=true
  xt4q -t nat -C PREROUTING -j "$NPRE" >/dev/null 2>&1 && N4P=true
  xt4q -t nat -C OUTPUT -j "$DNSOUT" >/dev/null 2>&1 && D4O=true
  xt4q -t nat -C PREROUTING -j "$DNSPRE" >/dev/null 2>&1 && D4P=true
  K4=false; xt4q -t filter -C OUTPUT -j "$KOUT" >/dev/null 2>&1 && K4=true

  if has ip6tables; then
    xt6q -t mangle -C OUTPUT -j "$MOUT" >/dev/null 2>&1 && M6O=true
    xt6q -t mangle -C PREROUTING -j "$MPRE" >/dev/null 2>&1 && M6P=true
    xt6q -t nat -C OUTPUT -j "$NOUT" >/dev/null 2>&1 && N6O=true
    xt6q -t nat -C PREROUTING -j "$NPRE" >/dev/null 2>&1 && N6P=true
    xt6q -t nat -C OUTPUT -j "$DNSOUT" >/dev/null 2>&1 && D6O=true
    xt6q -t nat -C PREROUTING -j "$DNSPRE" >/dev/null 2>&1 && D6P=true
  fi
  K6=false; has ip6tables && xt6q -t filter -C OUTPUT -j "$KOUT" >/dev/null 2>&1 && K6=true

  MODE4=false; MODE6=false
  case "$STATUS_MODE" in
    tproxy|enhance) [ "$M4O" = true ] && MODE4=true; [ "$M6O" = true ] && MODE6=true;;
    redirect) [ "$N4O" = true ] && MODE4=true; [ "$N6O" = true ] && MODE6=true;;
    tun|ebpf)
      if ip link show hetu0 >/dev/null 2>&1; then MODE4=true; MODE6=true; fi
      ;;
  esac
  if [ "$SHAREV" = 1 ]; then
    case "$STATUS_MODE" in
      tproxy|enhance) [ "$M4P" = true ] || MODE4=false;;
      redirect) [ "$N4P" = true ] || MODE4=false;;
    esac
  fi

  DNS4=true; DNS6=true; DNSREADY=true
  if [ "$DNSV" != off ]; then
    # TUN/eBPF owns DNS interception inside Mihomo's TUN device; Root DNS chains are
    # only required by transparent TPROXY/REDIRECT/ENHANCE modes.
    case "$STATUS_MODE" in
      tun|ebpf) ;;
      *)
        DNS4=$D4O
        [ "$SHAREV" != 1 ] || [ "$D4P" = true ] || DNS4=false
        if v6active && [ "$IPV6V" = enable ]; then
          DNS6=$D6O
          [ "$SHAREV" != 1 ] || [ "$D6P" = true ] || DNS6=false
        fi
        ;;
    esac
    DNSREADY=false
    if has ss; then
      ss -lnut 2>/dev/null | grep -Eq "(:|])${DPV}[[:space:]]" && DNSREADY=true
    elif has netstat; then
      netstat -lnut 2>/dev/null | grep -Eq "(:|])${DPV}[[:space:]]" && DNSREADY=true
    else
      DNSREADY=true
    fi
  fi

  IPV4OK=$MODE4
  IPV6OK=true
  case "$IPV6V" in
    enable) if v6active; then IPV6OK=$MODE6; fi;;
    strict) [ "$K6" = true ] || IPV6OK=false;;
    bypass|disable) IPV6OK=true;;
  esac

  WD=false
  W=$(cat "$WATCHDOG_PID" 2>/dev/null || true)
  case "$W" in ''|*[!0-9]*) ;; *) kill -0 "$W" >/dev/null 2>&1 && WD=true;; esac
  V6OFF=false; [ -f "$IPV6_STATE" ] && V6OFF=true
  STALE=false
  if [ "$STATUS_RUNNING" = false ] && { [ "$M4O" = true ] || [ "$N4O" = true ] || [ "$D4O" = true ] || [ "$M6O" = true ] || [ "$N6O" = true ] || [ "$D6O" = true ] || [ "$K4" = true ] || [ "$K6" = true ] || [ "$V6OFF" = true ]; }; then STALE=true; fi

  HEALTH=false
  if [ "$STATUS_RUNNING" = true ] && [ "$IPV4OK" = true ] && [ "$IPV6OK" = true ] && [ "$DNS4" = true ] && [ "$DNS6" = true ] && [ "$DNSREADY" = true ] && [ "$WD" = true ]; then HEALTH=true; fi
  SM=""; ST=""; if loadnet >/dev/null 2>&1; then SM="$MARK"; ST="$TABLE"; fi
  SP=$(state_value PREF 2>/dev/null || true)
  printf '{"ok":true,"runtimeSchema":3,"running":%s,"pid":%s,"mode":"%s","ipv4Rules":%s,"ipv6Rules":%s,"dnsMode":"%s","dnsIpv4Rule":%s,"dnsIpv6Rule":%s,"dnsListenerReady":%s,"dataPlaneHealthy":%s,"killSwitchActive":%s,"ipv6DisabledByHetu":%s,"watchdog":%s,"recoveredStaleRules":false,"staleRules":%s,"mark":"%s","table":"%s","pref":"%s","controllerPort":%s,"appScope":"%s","directUidRanges":"%s","sharedNetwork":"%s","killSwitchRequested":"%s","log":"%s","configCheckLog":"%s"}\n' "$STATUS_RUNNING" "$STATUS_PID" "$STATUS_MODE" "$IPV4OK" "$IPV6OK" "$DNSV" "$DNS4" "$DNS6" "$DNSREADY" "$HEALTH" "$([ "$K4" = true ] || [ "$K6" = true ] && echo true || echo false)" "$V6OFF" "$WD" "$STALE" "$SM" "$ST" "$SP" "$CPV" "$SCOPEV" "$DIRECTV" "$SHAREV" "$KILLV" "$LOG" "$CHECKLOG"
}

case "${1:-status}" in
  preflight) [ "$#" = 18 ] || fail "参数错误"; preflight "$2" "$3" "$4" "$5" "$6" "$7" "$8" "$9" "${10}" "${11}" "${12}" "${13}" "${14}" "${15}" "${16}" "${17}" "${18}";;
  start) [ "$#" = 20 ] || fail "参数错误"; root; start "$2" "$3" "$4" "$5" "$6" "$7" "$8" "$9" "${10}" "${11}" "${12}" "${13}" "${14}" "${15}" "${16}" "${17}" "${18}" "${19}" "${20}";;
  stop) root; acquire_lock || fail "另一个代理网络事务正在执行，请稍后重试"; stopwatchdog; cleanup; stopcore; restorev6; rm -f "$SESSION"; ok "Root 代理已停止并恢复网络状态";;
  status) status;;
  watchdog) [ "$#" = 9 ] || exit 0; root; watchdog "$2" "$3" "$4" "$5" "$6" "$7" "$8" "$9";;
  *) fail "未知 Root 代理操作";;
esac
