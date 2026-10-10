#!/system/bin/sh
# Hetu Root transparent proxy controller v3 (runtime revision 154). JSON only on stdout.
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
V6_CONF=/proc/sys/net/ipv6/conf
NET_STATE="$RUN/net.state"
WATCHDOG_PID="$RUN/watchdog.pid"
WATCHDOG_LOG="$RUN/watchdog.log"
CRASH_STATE="$RUN/last-crash"
START_STATE="$RUN/start-state"
START_ERROR="$RUN/last-start-error"
START_TIMING="$RUN/startup-timing"
LOCK_DIR="$RUN/.txn.lock"
CLEAN_SNAPSHOT_ACTIVE=0
FAKE_IP_V4=""
FAKE_IP_V6=""
LAN_RETURN_V4="0.0.0.0/8,10.0.0.0/8,100.64.0.0/10,127.0.0.0/8,169.254.0.0/16,172.16.0.0/12,192.168.0.0/16,224.0.0.0/4,240.0.0.0/4"
LAN_RETURN_V6="::1/128,fc00::/7,fe80::/10,ff00::/8"

BYPASS_MARK=0x08000000
BYPASS_MASK=0x08000000
PROBE_MARK=0x04000000
PROBE_MASK=0x04000000
MARK=""
MASK=""
TABLE=""
PREF=""
read -r ENTRY_UPTIME ENTRY_UNUSED < /proc/uptime || ENTRY_UPTIME=0
ENTRY_UPTIME=${ENTRY_UPTIME%%.*}
START_ACTIVE=0
START_MUTATED=0
ENTRY_CANCEL_TOKEN=${HETU_START_CANCEL_TOKEN-$(cat "$RUN/start-cancel-generation" 2>/dev/null || true)}
START_ROLLBACK=0
START_BOOTSTRAP=0
PRESERVE_KILL=0
TXN_DEADLINE=0
TXN_TIMER=""
LOCK_HELD=0
# The PID that owns the transaction lock. A shielded child replaces it with its own.
SELF_PID=$$
XT_BATCH=0
XT_BATCH_DIR=""
# xt_owner sees the credentials of the process that created a socket. Android resolves
# names in netd, which is root, so "root sockets are the core" also exempts the system
# resolver from DNS takeover. The core therefore runs as root with the net_admin group
# whenever the Root manager's BusyBox can start it that way; nothing else is root:net_admin.
CORE_GROUP_ID=3005
CORE_GID=""
CORE_RUNNER=""
CORE_SPEC=""
SYSTEM_DNS=exempt
DNS_PROTOS="tcp udp"
DOT_GUARD=0
PRIVATE_DNS=unknown

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
DOTOUT=HETU_DOTOUT
QUICOUT=HETU_QUICOUT
QUICFWD=HETU_QUICFWD
WROUT=HETU_WROUT
WRFWD=HETU_WRFWD
V6OUT=HETU_V6OUT
V6FWD=HETU_V6FWD
KOUT=HETU_KOUT
KFWD=HETU_KFWD

ok(){ printf '{"ok":true,"message":"%s"}\n' "$1"; }
start_stage(){ transaction_current || fail "启动事务已超时或被撤销"; mkdir -p "$RUN" >/dev/null 2>&1 || true; printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$1" > "$START_STATE" 2>/dev/null || true; STAGE_UPTIME=unknown; read -r STAGE_UPTIME STAGE_UNUSED < /proc/uptime 2>/dev/null || true; printf '%s %s\n' "$STAGE_UPTIME" "$1" >> "$START_TIMING" 2>/dev/null || true; }
fail(){ MSG="$1"; mkdir -p "$RUN" >/dev/null 2>&1 || true; printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$MSG" > "$START_ERROR" 2>/dev/null || true; printf '{"ok":false,"message":"%s"}\n' "$MSG"; exit 1; }
root(){ [ "$(id -u)" = 0 ] || fail "需要 Root 权限"; }
has(){ command -v "$1" >/dev/null 2>&1; }
# Serialize with Android netd/other root firewalls on /system/etc/xtables.lock.
# iptables itself owns the lock; -w avoids racy fail/rollback while preserving atomic rules.
# Outside a start the wait stays at 2 s as well: stop/rollback/self-heal must finish inside
# the App's bounded Root dispatcher instead of queueing ~100 commands behind a 15 s lock.
xt4(){
  transaction_current || return 1
  if [ "$XT_BATCH" = 1 ]; then xt_record 4 "$@"; return; fi
  if [ "$START_ACTIVE" = 1 ]; then timeout -s TERM -k 1 3 iptables -w 2 "$@"; else command iptables -w 2 "$@"; fi
}
xt6(){
  transaction_current || return 1
  if [ "$XT_BATCH" = 1 ]; then xt_record 6 "$@"; return; fi
  if [ "$START_ACTIVE" = 1 ]; then timeout -s TERM -k 1 3 ip6tables -w 2 "$@"; else command ip6tables -w 2 "$@"; fi
}
# Batched rule installation: append-style rules are recorded per family/table and then
# committed with one iptables-restore --noflush per table (a handful of processes instead of
# one iptables process, one xtables lock and one full table read per rule).
xt_record(){
  XR_F="$1"; shift
  if [ "${1:-}" = -t ] && [ -n "${2:-}" ]; then XR_T="$2"; else XR_T=""; fi
  case "$XR_T:${3:-}" in mangle:-N|mangle:-A|mangle:-I|nat:-N|nat:-A|nat:-I|filter:-N|filter:-A|filter:-I) ;;
    *)
      # Anything else is not an append: commit what is pending, then run it for real.
      xt_batch_commit || return 1
      XT_BATCH=0; xt"$XR_F" "$@"; XR_RC=$?; XT_BATCH=1; xt_batch_begin || return 1; return "$XR_RC";;
  esac
  shift 2
  XR_OLDIFS=$IFS; IFS=' '; XR_LINE="$*"; IFS=$XR_OLDIFS
  printf '%s\n' "$XR_LINE" >> "$XT_BATCH_DIR/$XR_F-$XR_T"
}
xt_batch_begin(){
  [ -n "$XT_BATCH_DIR" ] && [ -d "$XT_BATCH_DIR" ] && return 0
  XT_BATCH_DIR="$RUN/.xt-batch.$SELF_PID"
  rm -rf "$XT_BATCH_DIR" >/dev/null 2>&1 || true
  mkdir -p "$XT_BATCH_DIR"
}
xt_restore(){
  # $1 restore binary; rules on stdin.
  if [ "$START_ACTIVE" = 1 ]; then timeout -s TERM -k 1 5 "$1" -w 2 --noflush; else "$1" -w 2 --noflush; fi
}
xt_batch_commit(){
  [ -n "$XT_BATCH_DIR" ] && [ -d "$XT_BATCH_DIR" ] || return 0
  XB_RC=0
  for XB_F in 4 6; do
    for XB_T in mangle nat filter; do
      XB_FILE="$XT_BATCH_DIR/$XB_F-$XB_T"; [ -s "$XB_FILE" ] || continue
      XB_RESTORE=iptables-restore; [ "$XB_F" = 4 ] || XB_RESTORE=ip6tables-restore
      if ! has "$XB_RESTORE" || ! transaction_current; then XB_RC=1; break 2; fi
      { printf '*%s\n' "$XB_T"; cat "$XB_FILE"; printf 'COMMIT\n'; } | xt_restore "$XB_RESTORE" >/dev/null 2>&1 || { XB_RC=1; break 2; }
    done
  done
  rm -rf "$XT_BATCH_DIR" >/dev/null 2>&1 || true; XT_BATCH_DIR=""
  return "$XB_RC"
}
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
  if [ "$LOCK_HELD" = 1 ]; then
    [ "$(cat "$LOCK_DIR/pid" 2>/dev/null || true)" != "$SELF_PID" ] || rm -rf "$LOCK_DIR" >/dev/null 2>&1 || true
    LOCK_HELD=0
  fi
}
trap 'finish_transaction' EXIT
trap 'if [ "$START_ACTIVE" = 1 ]; then fail "启动事务已超时或被撤销"; else exit 0; fi' TERM INT HUP

# The App dispatches every Root command as `timeout -s TERM -k 1 N ...`: TERM and, one second
# later, SIGKILL go to this shell's PID only. Network restoration (stop, start rollback, status
# self-heal) therefore runs in a child that ignores TERM/INT/HUP and owns the transaction lock
# under its own PID; this shell only waits. Killing the waiter can no longer leave TPROXY/
# REDIRECT/DNS rules half removed, and the lock is never reaped while the child still works.
shield_run(){
  trap '' TERM INT HUP
  SH_PARENT_HELD="$LOCK_HELD"
  (
    trap - EXIT
    trap '' TERM INT HUP
    SH_SELF=""; read -r SH_SELF SH_UNUSED < /proc/self/stat 2>/dev/null || SH_SELF=""
    case "$SH_SELF" in ''|*[!0-9]*) SH_SELF="$SELF_PID";; esac
    SELF_PID="$SH_SELF"
    if [ "$LOCK_HELD" = 1 ]; then printf '%s\n' "$SELF_PID" > "$LOCK_DIR/pid" 2>/dev/null || true; fi
    "$@"; SH_RC=$?
    if [ "$LOCK_HELD" = 1 ] && [ "$(cat "$LOCK_DIR/pid" 2>/dev/null || true)" = "$SELF_PID" ]; then
      if [ "$SH_PARENT_HELD" = 1 ] && kill -0 "$$" 2>/dev/null; then printf '%s\n' "$$" > "$LOCK_DIR/pid" 2>/dev/null || true
      else rm -rf "$LOCK_DIR" >/dev/null 2>&1 || true; fi
    fi
    exit "$SH_RC"
  ) </dev/null >/dev/null 2>&1 &
  SH_CHILD=$!
  wait "$SH_CHILD"; SH_RC=$?
  # A wait interrupted by a (now ignored) signal is retried until the child really exits.
  while kill -0 "$SH_CHILD" 2>/dev/null; do wait "$SH_CHILD"; SH_RC=$?; done
  return "$SH_RC"
}

transaction_current(){
  [ "$START_ACTIVE" = 1 ] || return 0
  if [ "$START_ROLLBACK" = 1 ]; then
    read -r TC_UP TC_UNUSED < /proc/uptime || return 1
    TC_UP=${TC_UP%%.*}; [ "$TC_UP" -lt "${ROLLBACK_DEADLINE:-0}" ]; return
  fi
  [ "$(cat "$RUN/start-cancel-generation" 2>/dev/null || true)" = "$START_CANCEL_TOKEN" ] || return 1
  read -r TC_UP TC_UNUSED < /proc/uptime || return 1
  TC_UP=${TC_UP%%.*}
  [ "$TC_UP" -lt "$TXN_DEADLINE" ]
}
transaction_begin(){
  read -r TB_UP TB_UNUSED < /proc/uptime || return 1
  TB_UP=${TB_UP%%.*}; TXN_DEADLINE=$((ENTRY_UPTIME+110))
  has timeout || return 1
  if [ -n "${RECOVERY_DEADLINE:-}" ] && [ "$TXN_DEADLINE" -gt $((RECOVERY_DEADLINE-20)) ]; then TXN_DEADLINE=$((RECOVERY_DEADLINE-20)); fi
  START_CANCEL_TOKEN="$ENTRY_CANCEL_TOKEN"
  START_ACTIVE=1
  transaction_current || return 1
  TB_BIRTH=$(health_core_birth "$$"); [ -n "$TB_BIRTH" ] || return 1
  "$0" txn-deadline "$$" "$TB_BIRTH" "$TXN_DEADLINE" "$START_CANCEL_TOKEN" >/dev/null 2>&1 &
  TXN_TIMER=$!
  TXN_TIMER_BIRTH=$(health_core_birth "$TXN_TIMER")
  TB_WAIT=0
  while [ "$(cat "$RUN/start-timer.$$" 2>/dev/null || true)" != "$TB_BIRTH" ]; do
    kill -0 "$TXN_TIMER" 2>/dev/null || return 1
    TB_WAIT=$((TB_WAIT+1)); [ "$TB_WAIT" -lt 40 ] || return 1; sleep 0.025
  done
}
transaction_deadline(){
  TD_PID="$1"; TD_BIRTH="$2"; TD_LIMIT="$3"; TD_CANCEL="$4"
  case "$TD_PID:$TD_BIRTH:$TD_LIMIT" in *[!0-9:]*|::*|:*|*:) exit 1;; esac
  [ "$(health_core_birth "$TD_PID")" = "$TD_BIRTH" ] || return 0
  printf '%s\n' "$TD_BIRTH" > "$RUN/start-timer.$TD_PID" || return 1
  while [ "$(health_core_birth "$TD_PID")" = "$TD_BIRTH" ] && kill -0 "$TD_PID" 2>/dev/null; do
    read -r TD_UP TD_UNUSED < /proc/uptime || exit 1
    TD_UP=${TD_UP%%.*}
    if [ "$TD_UP" -ge "$TD_LIMIT" ] || [ "$(cat "$RUN/start-cancel-generation" 2>/dev/null || true)" != "$TD_CANCEL" ]; then
      # Recheck the kernel birth immediately before signalling the owned shell.
      [ "$(health_core_birth "$TD_PID")" != "$TD_BIRTH" ] || kill -TERM "$TD_PID" 2>/dev/null
      exit 0
    fi
    sleep 0.25
  done
}
finish_transaction(){
  if [ "$START_ACTIVE" = 1 ]; then XT_BATCH=0; shield_run rollback_start; START_ACTIVE=0; fi
  if [ -n "$TXN_TIMER" ] && [ -n "${TXN_TIMER_BIRTH:-}" ] && [ "$TXN_TIMER_BIRTH" = "$(health_core_birth "$TXN_TIMER")" ]; then kill "$TXN_TIMER" 2>/dev/null || true; wait "$TXN_TIMER" 2>/dev/null || true; fi
  rm -f "$RUN/start-timer.$$"
  release_lock
}
rollback_start(){
  trap '' TERM INT HUP
  START_ROLLBACK=1; RB_FAILED=0
  read -r RB_NOW RB_UNUSED < /proc/uptime || RB_NOW=0
  RB_NOW=${RB_NOW%%.*}; ROLLBACK_DEADLINE=$((RB_NOW+20))
  if [ "$START_MUTATED" != 1 ]; then START_ACTIVE=0; return 0; fi
  # Remove the physical finite startup exception before any atomic restoration.
  # An atomic restore failure must not leave a verified-core bypass behind.
  revoke_bootstrap || RB_FAILED=1
  stopwatchdog
  stopcore || RB_FAILED=1
  if [ "$START_KILL" = 1 ]; then
    PRESERVE_KILL=1
    atomic_kill_guard 4 "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" "$START_DIRECT_UIDS" "$START_DIRECT_GIDS" "$START_SHARED_MACS" || RB_FAILED=1
    atomic_kill_guard 6 "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" "$START_DIRECT_UIDS" "$START_DIRECT_GIDS" "$START_SHARED_MACS" || RB_FAILED=1
    # Capture routes stay intact if a strict guard cannot be confirmed.
    [ "$RB_FAILED" != 0 ] || cleanup
  else
    cleanup_confirmed || RB_FAILED=1
  fi
  restorev6 || RB_FAILED=1
  if [ "$RB_FAILED" = 0 ]; then rm -f "$SESSION"; else
    printf '%s\n' 'rollback-incomplete' > "$RUN/rollback-state"
  fi
  START_ACTIVE=0
}

acquire_lock(){
  [ "$LOCK_HELD" != 1 ] || return 0
  mkdir -p "$RUN" || return 1
  N=0
  while ! mkdir "$LOCK_DIR" >/dev/null 2>&1; do
    OWNER=$(cat "$LOCK_DIR/pid" 2>/dev/null || true)
    case "$OWNER" in ''|*[!0-9]*) ;; *)
      if ! kill -0 "$OWNER" >/dev/null 2>&1 && mkdir "$RUN/.txn.reap" 2>/dev/null; then
        OWNER_NOW=$(cat "$LOCK_DIR/pid" 2>/dev/null || true)
        if [ "$OWNER_NOW" = "$OWNER" ] && ! kill -0 "$OWNER_NOW" 2>/dev/null; then rm -rf "$LOCK_DIR"; fi
        rmdir "$RUN/.txn.reap" 2>/dev/null || true
        continue
      fi;; esac
    N=$((N+1)); [ "$N" -lt 100 ] || return 1; sleep 0.05
  done
  printf '%s\n' "$SELF_PID" > "$LOCK_DIR/pid"; LOCK_HELD=1
}
# One attempt only (status polling must never wait): reap a lock whose owner is gone.
try_lock_once(){
  [ "$LOCK_HELD" != 1 ] || return 0
  mkdir -p "$RUN" >/dev/null 2>&1 || return 1
  if ! mkdir "$LOCK_DIR" >/dev/null 2>&1; then
    TL_OWNER=$(cat "$LOCK_DIR/pid" 2>/dev/null || true)
    case "$TL_OWNER" in ''|*[!0-9]*) return 1;; esac
    ! kill -0 "$TL_OWNER" >/dev/null 2>&1 || return 1
    mkdir "$RUN/.txn.reap" 2>/dev/null || return 1
    if [ "$(cat "$LOCK_DIR/pid" 2>/dev/null || true)" = "$TL_OWNER" ] && ! kill -0 "$TL_OWNER" 2>/dev/null; then rm -rf "$LOCK_DIR"; fi
    rmdir "$RUN/.txn.reap" 2>/dev/null || true
    mkdir "$LOCK_DIR" >/dev/null 2>&1 || return 1
  fi
  printf '%s\n' "$SELF_PID" > "$LOCK_DIR/pid"; LOCK_HELD=1
}

# Shared by app recovery and service.d under the same native transaction lock.
# The boot identifier comes from the queued request; an old request cannot create
# a ledger for a newer boot or erase an exhausted count.
recovery_claim(){
  RC_BOOT=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null) || return 1
  [ -n "$RC_BOOT" ] && [ "$1" = "$RC_BOOT" ] || return 1
  [ "$RC_BOOT" != "$(cat "$BASE/boot/stopped-boot" 2>/dev/null || true)" ] || return 1
  mkdir -p "$BASE/boot" || return 1
  read -r RC_NOW RC_UNUSED < /proc/uptime || return 1
  RC_NOW=${RC_NOW%%.*}; RC_START="$RC_NOW"; RC_END=$((RC_NOW+300)); RC_COUNT=0
  if [ -f "$BASE/boot/recovery-budget" ]; then
    read -r RC_SAVED RC_COUNT RC_START RC_END < "$BASE/boot/recovery-budget" || return 1
    if [ "$RC_SAVED" != "$RC_BOOT" ]; then RC_COUNT=0; RC_START="$RC_NOW"; RC_END=$((RC_NOW+300)); fi
  fi
  case "$RC_COUNT" in 0|1|2) ;; *) return 1;; esac
  case "$RC_START:$RC_END" in *[!0-9:]*|:*|*:) return 1;; esac
  [ "$RC_NOW" -ge "$RC_START" ] && [ $((RC_END-RC_NOW)) -ge 145 ] || return 1
  RECOVERY_DEADLINE="$RC_END"
  printf '%s %s %s %s\n' "$RC_BOOT" "$((RC_COUNT+1))" "$RC_START" "$RC_END" > "$BASE/boot/recovery-budget.new.$$" &&
    mv -f "$BASE/boot/recovery-budget.new.$$" "$BASE/boot/recovery-budget"
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

# Only our private chains are removed. One table snapshot avoids three separate
# iptables processes for every absent current/legacy chain on each start/stop.
# A failed snapshot is unknown, never evidence that a chain is absent.
cleanup_snapshot_read(){
  CS_RAW=$("$1" -t "$2" -S 2>&1); CS_RC=$?
  if [ "$CS_RC" = 0 ]; then printf '%s\n' "$CS_RAW"; return 0; fi
  case "$CS_RAW" in
    *"can't initialize"*"Table does not exist"*) return 0;;
    *) return 1;;
  esac
}
cleanup_snapshot_begin(){
  CLEAN4_MANGLE=$(cleanup_snapshot_read xt4 mangle) || CLEAN4_MANGLE='?'
  CLEAN4_NAT=$(cleanup_snapshot_read xt4 nat) || CLEAN4_NAT='?'
  CLEAN4_FILTER=$(cleanup_snapshot_read xt4 filter) || CLEAN4_FILTER='?'
  CLEAN6_MANGLE='?'; CLEAN6_NAT='?'; CLEAN6_FILTER='?'
  if has ip6tables; then
    CLEAN6_MANGLE=$(cleanup_snapshot_read xt6 mangle) || CLEAN6_MANGLE='?'
    CLEAN6_NAT=$(cleanup_snapshot_read xt6 nat) || CLEAN6_NAT='?'
    CLEAN6_FILTER=$(cleanup_snapshot_read xt6 filter) || CLEAN6_FILTER='?'
  fi
  CLEAN_SNAPSHOT_ACTIVE=1
}
cleanup_chain_absent(){
  [ "$CLEAN_SNAPSHOT_ACTIVE" = 1 ] || return 1
  case "$1:$2" in
    xt4:mangle|iptables:mangle) CLEAN_LOOKUP=$CLEAN4_MANGLE;;
    xt4:nat|iptables:nat) CLEAN_LOOKUP=$CLEAN4_NAT;;
    xt4:filter|iptables:filter) CLEAN_LOOKUP=$CLEAN4_FILTER;;
    xt6:mangle|ip6tables:mangle) CLEAN_LOOKUP=$CLEAN6_MANGLE;;
    xt6:nat|ip6tables:nat) CLEAN_LOOKUP=$CLEAN6_NAT;;
    xt6:filter|ip6tables:filter) CLEAN_LOOKUP=$CLEAN6_FILTER;;
    *) return 1;;
  esac
  [ "$CLEAN_LOOKUP" != '?' ] || return 1
  case "
$CLEAN_LOOKUP
" in *"
-N $3
"*) return 1;; *) return 0;; esac
}
legacy_command(){
  transaction_current || return 1
  if [ "$START_ACTIVE" = 1 ]; then timeout -s TERM -k 1 2 "$@"; else "$@"; fi
}
legacy_unhook(){
  B="$1"; T="$2"; BASECHAIN="$3"; CHAIN="$4"; N=0
  cleanup_chain_absent "$B" "$T" "$CHAIN" && return 0
  while legacy_command "$B" -w 1 -t "$T" -C "$BASECHAIN" -j "$CHAIN" >/dev/null 2>&1; do
    legacy_command "$B" -w 1 -t "$T" -D "$BASECHAIN" -j "$CHAIN" >/dev/null 2>&1 || break
    N=$((N+1)); [ "$N" -lt 8 ] || break
  done
  legacy_command "$B" -w 1 -t "$T" -F "$CHAIN" >/dev/null 2>&1 || true
  legacy_command "$B" -w 1 -t "$T" -X "$CHAIN" >/dev/null 2>&1 || true
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
  cleanup_chain_absent "$BIN" "$T" "$CHAIN" && return 0
  while "$BIN" -t "$T" -C "$BASECHAIN" -j "$CHAIN" >/dev/null 2>&1; do "$BIN" -t "$T" -D "$BASECHAIN" -j "$CHAIN" >/dev/null 2>&1 || break; done
  "$BIN" -t "$T" -F "$CHAIN" >/dev/null 2>&1 || true; "$BIN" -t "$T" -X "$CHAIN" >/dev/null 2>&1 || true
}
cleanup4(){
  unhook xt4 mangle OUTPUT "$MOUT"; unhook xt4 mangle PREROUTING "$MPRE"
  unhook xt4 nat OUTPUT "$DNSOUT"; unhook xt4 nat PREROUTING "$DNSPRE"
  unhook xt4 nat OUTPUT "$NOUT"; unhook xt4 nat PREROUTING "$NPRE"
  unhook xt4 filter OUTPUT "$QUICOUT"; unhook xt4 filter FORWARD "$QUICFWD"
  unhook xt4 filter OUTPUT "$DOTOUT"
  unhook xt4 filter OUTPUT "$WROUT"; unhook xt4 filter FORWARD "$WRFWD"
  if [ "$PRESERVE_KILL" != 1 ]; then unhook xt4 filter OUTPUT "$KOUT"; unhook xt4 filter FORWARD "$KFWD"; fi
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
  unhook xt6 filter OUTPUT "$DOTOUT"
  unhook xt6 filter OUTPUT "$WROUT"; unhook xt6 filter FORWARD "$WRFWD"
  unhook xt6 filter OUTPUT "$V6OUT"; unhook xt6 filter FORWARD "$V6FWD"
  if [ "$PRESERVE_KILL" != 1 ]; then unhook xt6 filter OUTPUT "$KOUT"; unhook xt6 filter FORWARD "$KFWD"; fi
  if [ -n "$MARK" ] && [ -n "$MASK" ] && [ -n "$TABLE" ] && [ -n "$PREF" ]; then
    ip -6 rule del pref "$PREF" fwmark "$MARK/$MASK" table "$TABLE" >/dev/null 2>&1 || true
    ip -6 route del local ::/0 dev lo table "$TABLE" >/dev/null 2>&1 || true
  fi
}
# Batch sweep: one -S snapshot per family/table, then ONE iptables-restore --noflush that
# deletes every rule in a foreign chain (OUTPUT/PREROUTING/FORWARD or anything else) jumping to
# a HETU_/BICHEN_ chain, including duplicated hooks, and flushes/deletes those chains. It needs
# no net.state, so a lost journal or a killed transaction cannot leave capture behind.
# PRESERVE_KILL=1 keeps HETU_KOUT/HETU_KFWD and their hooks. A failed restore leaves the old
# snapshot in place, so the per-chain unhook fallback below still does the work.
sweep_plan(){
  awk -v keep="$PRESERVE_KILL" -v want="$1" -v only="${SWEEP_ONLY:-}" '
    function own(c){ if (only != "") return index(" " only " ", " " c " ") > 0; return c ~ /^(HETU|BICHEN)_/ }
    function kept(c){ return keep == "1" && (c == "HETU_KOUT" || c == "HETU_KFWD") }
    function hook(   i){ for (i = 3; i < NF; i++) if (($i == "-j" || $i == "-g") && own($(i+1)) && !kept($(i+1))) return 1; return 0 }
    $1 == "-N" && own($2) && !kept($2) { n++; chain[n] = $2; next }
    $1 == "-A" && own($2) && !kept($2) { next }
    $1 == "-A" && !own($2) && hook() { d++; line = $0; sub(/^-A /, "-D ", line); del[d] = line; next }
    { if (want == "rest") print }
    END { if (want == "plan") { for (i = 1; i <= d; i++) print del[i]; for (i = 1; i <= n; i++) print "-F " chain[i]; for (i = 1; i <= n; i++) print "-X " chain[i] } }'
}
sweep_table(){
  # $1 snapshot variable, $2 xt4|xt6, $3 table
  eval "SW_RULES=\${$1}"
  [ "$SW_RULES" != '?' ] || return 1
  SW_PLAN=$(printf '%s\n' "$SW_RULES" | sweep_plan plan)
  [ -n "$SW_PLAN" ] || return 0
  SW_RESTORE=iptables-restore; [ "$2" = xt4 ] || SW_RESTORE=ip6tables-restore
  has "$SW_RESTORE" || return 1
  transaction_current || return 1
  { printf '*%s\n' "$3"; printf '%s\n' "$SW_PLAN"; printf 'COMMIT\n'; } | xt_restore "$SW_RESTORE" >/dev/null 2>&1 || return 1
  SW_LEFT=$(printf '%s\n' "$SW_RULES" | sweep_plan rest)
  eval "$1=\$SW_LEFT"
}
sweep_rules(){
  sweep_table CLEAN4_MANGLE xt4 mangle || true; sweep_table CLEAN4_NAT xt4 nat || true; sweep_table CLEAN4_FILTER xt4 filter || true
  if has ip6tables; then sweep_table CLEAN6_MANGLE xt6 mangle || true; sweep_table CLEAN6_NAT xt6 nat || true; sweep_table CLEAN6_FILTER xt6 filter || true; fi
}
# Policy routing owned by Hetu: fwmark rules looking up tables 20260..20299 (v4 and v6), and
# the local routes inside those tables. Found from the kernel, not from net.state.
owned_policy_rules(){
  awk '/fwmark/ { p = $1; sub(/:$/, "", p); for (i = 2; i < NF; i++) if (($i == "lookup" || $i == "table") && $(i+1) ~ /^[0-9]+$/ && $(i+1) + 0 >= 20260 && $(i+1) + 0 <= 20299) print p ":" $(i+1) }'
}
sweep_policy_routes(){
  for SR_F in 4 6; do
    if [ "$SR_F" = 6 ] && ! v6supported; then continue; fi
    SR_TABLES=" "; [ -z "$TABLE" ] || SR_TABLES=" $TABLE "
    SR_RULES=$(ip -"$SR_F" rule show 2>/dev/null) || SR_RULES=""
    for SR_E in $(printf '%s\n' "$SR_RULES" | owned_policy_rules); do
      SR_P=${SR_E%%:*}; SR_T=${SR_E#*:}
      ip -"$SR_F" rule del pref "$SR_P" table "$SR_T" >/dev/null 2>&1 || true
      case "$SR_TABLES" in *" $SR_T "*) ;; *) SR_TABLES="$SR_TABLES$SR_T ";; esac
    done
    for SR_T in $(ip -"$SR_F" route show table all 2>/dev/null | awk '{ for (i = 1; i < NF; i++) if ($i == "table" && $(i+1) ~ /^[0-9]+$/ && $(i+1) + 0 >= 20260 && $(i+1) + 0 <= 20299) print $(i+1) }'); do
      case "$SR_TABLES" in *" $SR_T "*) ;; *) SR_TABLES="$SR_TABLES$SR_T ";; esac
    done
    for SR_T in $SR_TABLES; do ip -"$SR_F" route flush table "$SR_T" >/dev/null 2>&1 || true; done
  done
}
cleanup(){ MARK=""; MASK=""; TABLE=""; PREF=""; loadnet >/dev/null 2>&1 || true; cleanup_snapshot_begin; sweep_rules; cleanup4; cleanup6; cleanlegacy; sweep_policy_routes; CLEAN_SNAPSHOT_ACTIVE=0; ip link del hetu0 >/dev/null 2>&1 || true; rm -f "$NET_STATE"; MARK=""; MASK=""; TABLE=""; PREF=""; }

cleanup_verify(){
  for CC_BIN in xt4 xt6; do
    if [ "$CC_BIN" = xt6 ] && ! has ip6tables; then continue; fi
    # Not CC_TABLE: that name holds the journaled routing table checked below.
    for CC_XT in mangle nat filter; do
      CC_RULES=$(cleanup_snapshot_read "$CC_BIN" "$CC_XT") || return 1
      if printf '%s\n' "$CC_RULES" | grep -Eq '(^-N |^-A | -j )(HETU_|BICHEN_)'; then return 1; fi
    done
  done
  # Independent of net.state: no fwmark rule may still point at a Hetu table.
  for CC_FAMILY in 4 6; do
    if [ "$CC_FAMILY" = 6 ] && ! v6supported; then continue; fi
    CC_RULES=$(ip -"$CC_FAMILY" rule show 2>/dev/null) || return 1
    [ -z "$(printf '%s\n' "$CC_RULES" | owned_policy_rules)" ] || return 1
  done
  if [ -n "$CC_MARK" ] && [ -n "$CC_TABLE" ] && [ -n "$CC_PREF" ]; then
    for CC_FAMILY in 4 6; do
      if [ "$CC_FAMILY" = 6 ] && ! v6supported; then continue; fi
      CC_RULES=$(ip -"$CC_FAMILY" rule show 2>/dev/null) || return 1
      if printf '%s\n' "$CC_RULES" | grep -E "^[[:space:]]*$CC_PREF:.*fwmark $CC_MARK/$CC_MASK.*(lookup|table) $CC_TABLE([[:space:]]|$)" >/dev/null; then return 1; fi
      CC_ROUTES=$(ip -"$CC_FAMILY" route show table "$CC_TABLE" 2>&1); CC_RC=$?
      if [ "$CC_RC" != 0 ]; then
        case "$CC_ROUTES" in *'FIB table does not exist'*|*'No such file'*) CC_ROUTES='';; *) return 1;; esac
      fi
      if printf '%s\n' "$CC_ROUTES" | grep -Eq '^local (default|0.0.0.0/0|::/0).*dev lo'; then return 1; fi
    done
  fi
}
cleanup_confirmed(){
  CC_MARK=$(state_value MARK || true); CC_MASK=$(state_value MASK || true)
  CC_TABLE=$(state_value TABLE || true); CC_PREF=$(state_value PREF || true)
  CC_SAVED_NET=$(cat "$NET_STATE" 2>/dev/null || true)
  cleanup
  if ! cleanup_verify; then
    [ -z "$CC_SAVED_NET" ] || printf '%s\n' "$CC_SAVED_NET" > "$NET_STATE"
    return 1
  fi
}

stop_transaction(){
  # Revoke before waiting for the lock, including a start whose watchdog has not
  # yet appeared. A stopped receipt is not a successful-cleanup receipt.
  cancel_boot || return 1
  acquire_lock || return 1
  printf 'stopped-%s\n' "$$" > "$RUN/generation" || return 1
  stopwatchdog
  SC_FAILED=0
  stopcore || SC_FAILED=1
  cleanup_confirmed || SC_FAILED=1
  restorev6 || SC_FAILED=1
  [ "$SC_FAILED" = 0 ] || return 1
  rm -f "$SESSION"
}
cancel_boot(){
  CB_BOOT=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null) || return 1
  [ -n "$CB_BOOT" ] || return 1
  mkdir -p "$BASE/boot" || return 1
  printf '%s\n' "$CB_BOOT" > "$BASE/boot/stopped-boot" || return 1
  mkdir -p "$RUN" || return 1
  printf '%s-%s\n' "$$" "$(health_core_birth "$$")" > "$RUN/start-cancel-generation.new.$$" &&
    mv -f "$RUN/start-cancel-generation.new.$$" "$RUN/start-cancel-generation"
}

# Cheap built-in prefilter: do not fork readlink for every Android process.
# Same-inode catches renamed comm; tracked PIDs always bypass this prefilter.
core_candidate(){
  case "$1" in ''|*[!0-9]*) return 1;; esac
  [ "$BASE/bin/core" -ef "${CORE_PROCFS:-/proc}/$1/exe" ] && return 0
  CORE_COMM=''
  # Redirect stderr before opening procfs: processes can exit between operations.
  read -r CORE_COMM 2>/dev/null < "${CORE_PROCFS:-/proc}/$1/comm" || return 1
  case "$CORE_COMM" in core|mihomo|mihomo-*) return 0;; *) return 1;; esac
}
pidcore(){
  case "$1" in ''|*[!0-9]*) return 1;; esac
  P="$1"; [ -d "/proc/$P" ] || return 1
  EXE=$(readlink "/proc/$P/exe" 2>/dev/null) || { [ -d "/proc/$P" ] && return 2; return 1; }
  # A su/timeout/shell command line may contain the core path as an argument.
  # It must never be reported as the core or killed during orphan cleanup.
  case "$EXE" in "$BASE/bin/core"|"$BASE/bin/core (deleted)") return 0;; *) return 1;; esac
}
# A readlink permission/race error is not proof that a tracked core has died.
# Monitoring tolerates unknown identity; destructive cleanup still requires 0.
core_maybe_alive(){ pidcore "$1"; case "$?" in 0|2) return 0;; *) return 1;; esac; }
findcorepid(){
  for PROC in /proc/[0-9]*; do
    CAND=${PROC#/proc/}; case "$CAND" in ''|*[!0-9]*) continue;; esac
    if core_candidate "$CAND" && pidcore "$CAND" && kill -0 "$CAND" >/dev/null 2>&1; then printf '%s\n' "$CAND"; return 0; fi
  done
  return 1
}
stopwatchdog(){
  [ -f "$WATCHDOG_PID" ] || return 0; W=$(cat "$WATCHDOG_PID" 2>/dev/null || true)
  case "$W" in ''|*[!0-9]*) ;; *)
    if [ "$W" != "$$" ] && kill -0 "$W" >/dev/null 2>&1; then
      W_CMD=$(tr '\000' '\n' < "/proc/$W/cmdline" 2>/dev/null || true)
      if printf '%s\n' "$W_CMD" | awk -v s="$BASE/hetu-root.sh" '$0==s {if(getline>0 && $0=="watchdog") found=1} END {exit !found}'; then
        kill "$W" >/dev/null 2>&1 || true
      fi
    fi;; esac
  rm -f "$WATCHDOG_PID"
}
process_exited(){
  kill -0 "$1" 2>/dev/null || return 0
  PE_STATE=$(sed 's/^.*) //' "/proc/$1/stat" 2>/dev/null | awk '{print $1}')
  [ "$PE_STATE" = Z ]
}
stopcore(){
  # Terminate every Hetu-private core in parallel. Older revisions waited up to
  # two seconds for the tracked PID and then another two seconds for each orphan,
  # which could turn a restart into an 8-10 second stall.
  STOP_PIDS=""
  if [ -f "$PIDFILE" ]; then
    P=$(cat "$PIDFILE" 2>/dev/null || true)
    pidcore "$P"; STOP_ID=$?
    if [ "$STOP_ID" = 2 ] && ! process_exited "$P"; then return 1; fi
    case "$P" in ''|*[!0-9]*) ;; *) if pidcore "$P" && kill -0 "$P" >/dev/null 2>&1; then STOP_PIDS="$P"; fi;; esac
  fi
  # Recover orphaned Hetu cores left by a killed/reinstalled app. Match only the
  # private executable path so unrelated Mihomo/Clash processes are untouched.
  for PROC in /proc/[0-9]*; do
    OPID=${PROC#/proc/}; case "$OPID" in ''|*[!0-9]*) continue;; esac
    [ "$OPID" != "$$" ] || continue
    if core_candidate "$OPID" && pidcore "$OPID" && kill -0 "$OPID" >/dev/null 2>&1; then
      case " $STOP_PIDS " in *" $OPID "*) ;; *) STOP_PIDS="$STOP_PIDS $OPID";; esac
    fi
  done
  if [ -d "$RUN" ]; then start_stage "stop-core-signals"; fi
  for P in $STOP_PIDS; do kill "$P" >/dev/null 2>&1 || true; done
  N=0
  while [ "$N" -lt 10 ]; do
    STOP_ALIVE=0
    for P in $STOP_PIDS; do if pidcore "$P" && kill -0 "$P" >/dev/null 2>&1; then STOP_ALIVE=1; break; fi; done
    [ "$STOP_ALIVE" = 1 ] || break
    sleep 0.05
    N=$((N+1))
  done
  for P in $STOP_PIDS; do
    if pidcore "$P" && kill -0 "$P" >/dev/null 2>&1; then kill -9 "$P" >/dev/null 2>&1 || true; fi
  done
  STOP_WAIT=0
  while [ "$STOP_WAIT" -lt 10 ]; do
    STOP_REMAIN=0
    for P in $STOP_PIDS; do if ! process_exited "$P"; then STOP_REMAIN=1; fi; done
    [ "$STOP_REMAIN" = 1 ] || break
    STOP_WAIT=$((STOP_WAIT+1)); sleep 0.05
  done
  [ "$STOP_REMAIN" = 0 ] || return 1
  rm -f "$PIDFILE" "$MODEFILE"
}

# procfs reports a zero stat size even when this file contains IPv6 addresses.
# Install the selected IPv6 policy whenever the kernel supports IPv6, including
# before the first IPv6 network appears. Android keeps physical-network routes
# in per-interface tables, so neither stat size nor the main default route can
# decide whether IPv6 traffic needs interception/protection.
v6supported(){ [ -r /proc/net/if_inet6 ]; }
restorev6(){
  [ -f "$IPV6_STATE" ] || return 0
  V6_TAB=$(printf '\t'); V6_DEFAULT=0; V6_FAILED=0
  while IFS="$V6_TAB" read -r V6_P V6_V; do
    [ "$V6_P" != "$V6_CONF/default/disable_ipv6" ] || V6_DEFAULT="$V6_V"
    # all changes every interface and default; restore it before individual values.
    if [ "$V6_P" = "$V6_CONF/all/disable_ipv6" ]; then
      case "$V6_V" in 0|1) printf '%s\n' "$V6_V" > "$V6_P" 2>/dev/null || V6_FAILED=1;; esac
    fi
  done < "$IPV6_STATE"
  case "$V6_DEFAULT" in 0|1) ;; *) return 1;; esac
  for V6_P in "$V6_CONF"/*/disable_ipv6; do
    [ -e "$V6_P" ] || continue; [ "$V6_P" != "$V6_CONF/all/disable_ipv6" ] || continue
    V6_OLD="$V6_DEFAULT"
    while IFS="$V6_TAB" read -r V6_SAVED_P V6_SAVED_V; do
      if [ "$V6_P" = "$V6_SAVED_P" ]; then V6_OLD="$V6_SAVED_V"; break; fi
    done < "$IPV6_STATE"
    case "$V6_OLD" in 0|1) printf '%s\n' "$V6_OLD" > "$V6_P" 2>/dev/null || V6_FAILED=1;; *) V6_FAILED=1;; esac
  done
  # Keep the journal if a real restoration failed, so a later stop can retry.
  [ "$V6_FAILED" = 0 ] || return 1
  rm -f "$IPV6_STATE"
}
v6disabled(){
  V6_FOUND=0
  for V6_P in "$V6_CONF"/*/disable_ipv6; do
    [ -e "$V6_P" ] || continue; V6_FOUND=1
    read -r V6_V < "$V6_P" 2>/dev/null || return 1
    [ "$V6_V" = 1 ] || return 1
  done
  [ "$V6_FOUND" = 1 ]
}

split_safe_uids(){
  LIST="$1"; [ -z "$LIST" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS
  for U in "$@"; do case "$U" in ''|*[!0-9-]*) return 1;; esac; case "$U" in *-*) A=${U%-*}; B=${U#*-};; *) A=$U; B=$U;; esac; case "$A$B" in *[!0-9]*) return 1;; esac; [ "$A" -ge 10000 ] && [ "$B" -ge "$A" ] || return 1; done
}
split_safe_cidrs(){
  LIST="$1"; [ -z "$LIST" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS
  for X in "$@"; do case "$X" in ''|*[!0-9A-Fa-f:./]*|*//*|/*|*/) return 1;; esac; case "$X" in */*) ;; *) return 1;; esac; done
}
# Validate only numeric literals; never resolve a hostname or execute configuration text.
fake_ip_cidrs_valid(){
  [ "${#2}" -le 16384 ] || return 1
  printf '%s\n' "$2" | awk -v family="$1" '
    BEGIN {ok=1}
    {
      if($0=="")next;
      n=split($0,items,",");if(n>256){ok=0;exit}
      for(i=1;i<=n;i++){
        if(split(items[i],parts,"/")!=2 || parts[2]!~/^[0-9]+$/){ok=0;exit}
        max=family==4?32:128;if(parts[2]+0>max){ok=0;exit}
        addr=parts[1];
        if(family==4){
          if(split(addr,words,"\\.")!=4){ok=0;exit}
          for(j=1;j<=4;j++)if(words[j]!~/^[0-9]+$/ || words[j]+0>255){ok=0;exit}
        }else{
          if(addr!~/^[0-9a-fA-F:]+$/ || index(addr,":::")>0){ok=0;exit}
          if((substr(addr,1,1)==":" && substr(addr,1,2)!="::") ||
             (substr(addr,length(addr),1)==":" && substr(addr,length(addr)-1)!="::")){ok=0;exit}
          copy=addr;compressed=gsub(/::/,":",copy);if(compressed>1){ok=0;exit}
          num=split(copy,words,":");segments=0;
          for(j=1;j<=num;j++){
            if(words[j]==""){if(!compressed){ok=0;exit}}else{
              if(length(words[j])>4){ok=0;exit};segments++
            }
          }
          if((compressed && segments>=8) || (!compressed && segments!=8)){ok=0;exit}
        }
      }
    }
    END {exit !ok}'
}
fake_ip_policy_valid(){
  case "$FAKE_IP_V4$FAKE_IP_V6" in *,*) return 1;; esac
  fake_ip_cidrs_valid 4 "$FAKE_IP_V4" && fake_ip_cidrs_valid 6 "$FAKE_IP_V6" &&
    fake_ip_cidrs_valid 4 "$LAN_RETURN_V4" && fake_ip_cidrs_valid 6 "$LAN_RETURN_V6"
}
load_start_fake_ip_policy(){
  FIP_FILE="$1"
  FIP_TAIL=$(tail -n 5 "$FIP_FILE") || return 1
  if [ "$(printf '%s\n' "$FIP_TAIL" | sed -n '1p')" != '# HETU_FAKE_IP_POLICY=1' ]; then
    # Only the fixed terminal block is metadata. YAML scalar content elsewhere
    # may contain identical text and cannot override an appended private policy.
    printf '%s\n' "$FIP_TAIL" | grep -Eq '^# HETU_(FAKE_IP_POLICY|FAKE_IP_V4|FAKE_IP_V6|LAN_RETURN_V4|LAN_RETURN_V6)=' && return 1
    return 0 # Legacy private copies keep their established defaults.
  fi
  FIP_2=$(printf '%s\n' "$FIP_TAIL" | sed -n '2p'); case "$FIP_2" in '# HETU_FAKE_IP_V4='*) FAKE_IP_V4=${FIP_2#*=};; *) return 1;; esac
  FIP_3=$(printf '%s\n' "$FIP_TAIL" | sed -n '3p'); case "$FIP_3" in '# HETU_FAKE_IP_V6='*) FAKE_IP_V6=${FIP_3#*=};; *) return 1;; esac
  FIP_4=$(printf '%s\n' "$FIP_TAIL" | sed -n '4p'); case "$FIP_4" in '# HETU_LAN_RETURN_V4='*) LAN_RETURN_V4=${FIP_4#*=};; *) return 1;; esac
  FIP_5=$(printf '%s\n' "$FIP_TAIL" | sed -n '5p'); case "$FIP_5" in '# HETU_LAN_RETURN_V6='*) LAN_RETURN_V6=${FIP_5#*=};; *) return 1;; esac
  fake_ip_policy_valid
}
load_session_fake_ip_policy(){
  grep -q '^FAKE_IP_POLICY=' "$SESSION" || return 0
  # A stopped core does not invalidate the immutable session/rule checksum.
  # Refuse a different or damaged session instead of guessing a Kill Switch policy.
  health_session_current || return 1
  [ "$(grep -c '^FAKE_IP_POLICY=' "$SESSION")" = 1 ] &&
    [ "$(sed -n 's/^FAKE_IP_POLICY=//p' "$SESSION")" = 1 ] || return 1
  for FIP_KEY in FAKE_IP_V4 FAKE_IP_V6 LAN_RETURN_V4 LAN_RETURN_V6; do
    [ "$(grep -c "^$FIP_KEY=" "$SESSION")" = 1 ] || return 1
  done
  FAKE_IP_V4=$(sed -n 's/^FAKE_IP_V4=//p' "$SESSION")
  FAKE_IP_V6=$(sed -n 's/^FAKE_IP_V6=//p' "$SESSION")
  LAN_RETURN_V4=$(sed -n 's/^LAN_RETURN_V4=//p' "$SESSION")
  LAN_RETURN_V6=$(sed -n 's/^LAN_RETURN_V6=//p' "$SESSION")
  fake_ip_policy_valid
}
split_safe_ifaces(){
  LIST="$1"; [ -z "$LIST" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS
  for X in "$@"; do case "$X" in ''|*[!A-Za-z0-9_.:@+-]*) return 1;; esac; [ "$X" != lo ] && [ "$X" != 'lo+' ] || return 1; done
}
split_safe_macs(){
  LIST="$1"; [ -z "$LIST" ] && return 0
  OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS
  for X in "$@"; do
    printf '%s\n' "$X" | grep -Eiq '^[0-9a-f][0-9a-f]:[0-9a-f][0-9a-f]:[0-9a-f][0-9a-f]:[0-9a-f][0-9a-f]:[0-9a-f][0-9a-f]:[0-9a-f][0-9a-f]$' || return 1
    case "$X" in 00:00:00:00:00:00|ff:ff:ff:ff:ff:ff|FF:FF:FF:FF:FF:FF) return 1;; esac
  done
}
first_uid(){ LIST="$1"; OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS; printf '%s' "${1:-}"; }
first_mac(){ LIST="$1"; OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS; printf '%s' "${1:-}"; }

iface_out(){ BIN="$1"; T="$2"; C="$3"; LIST="$4"; [ -z "$LIST" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS; for X in "$@"; do "$BIN" -t "$T" -A "$C" -o "$X" -j RETURN || return 1; done; }
iface_in(){ BIN="$1"; T="$2"; C="$3"; LIST="$4"; [ -z "$LIST" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS; for X in "$@"; do "$BIN" -t "$T" -A "$C" -i "$X" -j RETURN || return 1; done; }
shared_mac_returns(){ BIN="$1"; T="$2"; C="$3"; LIST="$4"; [ -z "$LIST" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS; for X in "$@"; do "$BIN" -t "$T" -A "$C" -m mac --mac-source "$X" -j RETURN || return 1; done; }
# Once the system resolver is answered by the core, any process can be handed an address
# from the fake-IP range, and such an address exists only inside the core. An owner-based
# exemption therefore keeps its direct path for every real address and lets these through.
fake_range(){
  [ "$SYSTEM_DNS" = captured ] || return 0
  case "$1" in xt6|xt6q) printf '%s' "$FAKE_IP_V6";; *) printf '%s' "$FAKE_IP_V4";; esac
}
owner_return(){
  OR_BIN="$1"; OR_T="$2"; OR_C="$3"; shift 3; OR_FAKE=$(fake_range "$OR_BIN")
  if [ -n "$OR_FAKE" ]; then "$OR_BIN" -t "$OR_T" -A "$OR_C" ! -d "$OR_FAKE" -m owner "$@" -j RETURN
  else "$OR_BIN" -t "$OR_T" -A "$OR_C" -m owner "$@" -j RETURN; fi
}
# The core's own sockets, told apart from every other root socket.
core_return(){
  [ -n "$CORE_GID" ] || return 0
  "$1" -t "$2" -A "$3" -m owner --uid-owner 0 --gid-owner "$CORE_GID" -j RETURN
}
blacklist_returns(){ BIN="$1"; T="$2"; C="$3"; S="$4"; LIST="$5"; [ "$S" = blacklist ] || return 0; [ -z "$LIST" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS; for U in "$@"; do owner_return "$BIN" "$T" "$C" --uid-owner "$U" || return 1; done; }
direct_uid_returns(){ BIN="$1"; T="$2"; C="$3"; LIST="$4"; [ -z "$LIST" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS; for U in "$@"; do owner_return "$BIN" "$T" "$C" --uid-owner "$U" || return 1; done; }
direct_gid_returns(){ BIN="$1"; T="$2"; C="$3"; LIST="$4"; [ -z "$LIST" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS; for G in "$@"; do owner_return "$BIN" "$T" "$C" --gid-owner "$G" || return 1; done; }
system_uid_return(){ BIN="$1"; T="$2"; C="$3"; S="$4"; [ "$S" = whitelist ] && return 0; owner_return "$BIN" "$T" "$C" --uid-owner 0-9999 || return 1; }
# 仅所选应用: the listed UIDs are marked one by one, so a fake-IP flow from any other
# local process needs its own way into the core. Only present while the system resolver
# is captured; otherwise unlisted applications are never handed such an address.
fake_mark(){
  FM_BIN="$1"; FM_C="$2"; FM_S="$3"; FM_PROTO="$4"; [ "$FM_S" = whitelist ] || return 0
  FM_FAKE=$(fake_range "$FM_BIN"); [ -n "$FM_FAKE" ] || return 0
  "$FM_BIN" -t mangle -A "$FM_C" -d "$FM_FAKE" -p "$FM_PROTO" -j MARK --set-xmark "$MARK/$MASK"
}
fake_redirect(){
  FR_BIN="$1"; FR_C="$2"; FR_S="$3"; FR_TO="$4"; [ "$FR_S" = whitelist ] || return 0
  FR_FAKE=$(fake_range "$FR_BIN"); [ -n "$FR_FAKE" ] || return 0
  "$FR_BIN" -t nat -A "$FR_C" -d "$FR_FAKE" -p tcp -j REDIRECT --to-ports "$FR_TO"
}
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
# Explicit protocol disabling should report an unavailable UDP path immediately.
# Silent DROP leaves clients retrying a blocked QUIC path until their own timeout;
# REJECT (the default ICMP/ICMPv6 port-unreachable) permits prompt error handling.
scoped_reject_quic(){
  BIN="$1"; C="$2"; S="$3"; LIST="$4"
  if [ "$S" = whitelist ]; then OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS; for U in "$@"; do "$BIN" -t filter -A "$C" -m owner --uid-owner "$U" -p udp --dport 443 -j REJECT || return 1; done
  else "$BIN" -t filter -A "$C" -p udp --dport 443 -j REJECT || return 1; fi
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

bypass4(){
  C="$1"; T="$2"; CIDRS="$3"
  OLDIFS=$IFS; IFS=,; set -- $LAN_RETURN_V4; IFS=$OLDIFS
  for NET in "$@"; do xt4 -t "$T" -A "$C" -d "$NET" -j RETURN || return 1; done
  [ -z "$CIDRS" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $CIDRS; IFS=$OLDIFS; for NET in "$@"; do case "$NET" in *:*) ;; *) xt4 -t "$T" -A "$C" -d "$NET" -j RETURN || return 1;; esac; done
}
bypass6(){
  C="$1"; T="$2"; CIDRS="$3"
  OLDIFS=$IFS; IFS=,; set -- $LAN_RETURN_V6; IFS=$OLDIFS
  for NET in "$@"; do xt6 -t "$T" -A "$C" -d "$NET" -j RETURN || return 1; done
  [ -z "$CIDRS" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $CIDRS; IFS=$OLDIFS; for NET in "$@"; do case "$NET" in *:*) xt6 -t "$T" -A "$C" -d "$NET" -j RETURN || return 1;; esac; done
}

probetp4(){ P="$1"; xt4 -t mangle -N HETU_PROBE >/dev/null 2>&1 || true; xt4 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true; xt4 -t mangle -A HETU_PROBE -p udp -j TPROXY --on-port "$P" --tproxy-mark "$PROBE_MARK/$PROBE_MASK" >/dev/null 2>&1; R=$?; xt4 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true; xt4 -t mangle -X HETU_PROBE >/dev/null 2>&1 || true; return "$R"; }
probetp6(){ P="$1"; has ip6tables || return 1; xt6 -t mangle -N HETU_PROBE >/dev/null 2>&1 || true; xt6 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true; xt6 -t mangle -A HETU_PROBE -p udp -j TPROXY --on-port "$P" --tproxy-mark "$PROBE_MARK/$PROBE_MASK" >/dev/null 2>&1; R=$?; xt6 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true; xt6 -t mangle -X HETU_PROBE >/dev/null 2>&1 || true; return "$R"; }
probe_tcp_ownership(){
  TCP_PROBE_BIN="$1"; TCP_PROBE_RESULT=0
  "$TCP_PROBE_BIN" -t mangle -N HETU_TCP_PROBE >/dev/null 2>&1 || return 1
  "$TCP_PROBE_BIN" -t mangle -A HETU_TCP_PROBE -p tcp -m conntrack --ctstate ESTABLISHED -m connmark ! --mark "$PROBE_MARK/$PROBE_MASK" -j RETURN >/dev/null 2>&1 || TCP_PROBE_RESULT=1
  "$TCP_PROBE_BIN" -t mangle -A HETU_TCP_PROBE -p tcp -j CONNMARK --set-xmark "$PROBE_MARK/$PROBE_MASK" >/dev/null 2>&1 || TCP_PROBE_RESULT=1
  "$TCP_PROBE_BIN" -t mangle -F HETU_TCP_PROBE >/dev/null 2>&1 || true
  "$TCP_PROBE_BIN" -t mangle -X HETU_TCP_PROBE >/dev/null 2>&1 || true
  return "$TCP_PROBE_RESULT"
}
probered4(){ P="$1"; PROTO="${2:-tcp}"; xt4 -t nat -N HETU_PROBE >/dev/null 2>&1 || true; xt4 -t nat -F HETU_PROBE >/dev/null 2>&1 || true; xt4 -t nat -A HETU_PROBE -p "$PROTO" -j REDIRECT --to-ports "$P" >/dev/null 2>&1; R=$?; xt4 -t nat -F HETU_PROBE >/dev/null 2>&1 || true; xt4 -t nat -X HETU_PROBE >/dev/null 2>&1 || true; return "$R"; }
probered6(){ P="$1"; PROTO="${2:-tcp}"; has ip6tables || return 1; xt6 -t nat -N HETU_PROBE >/dev/null 2>&1 || true; xt6 -t nat -F HETU_PROBE >/dev/null 2>&1 || true; xt6 -t nat -A HETU_PROBE -p "$PROTO" -j REDIRECT --to-ports "$P" >/dev/null 2>&1; R=$?; xt6 -t nat -F HETU_PROBE >/dev/null 2>&1 || true; xt6 -t nat -X HETU_PROBE >/dev/null 2>&1 || true; return "$R"; }
probeowner(){
  U="$1"; [ -n "$U" ] || return 0
  xt4 -t mangle -N HETU_PROBE >/dev/null 2>&1 || true; xt4 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true
  xt4 -t mangle -A HETU_PROBE -m owner --uid-owner "$U" -j RETURN >/dev/null 2>&1; R=$?
  xt4 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true; xt4 -t mangle -X HETU_PROBE >/dev/null 2>&1 || true; return "$R"
}
probegid(){
  G="$1"; [ -n "$G" ] || return 0
  xt4 -t mangle -N HETU_PROBE >/dev/null 2>&1 || true; xt4 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true
  xt4 -t mangle -A HETU_PROBE -m owner --gid-owner "$G" -j RETURN >/dev/null 2>&1; R=$?
  xt4 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true; xt4 -t mangle -X HETU_PROBE >/dev/null 2>&1 || true; return "$R"
}
probemac(){
  M="$1"; [ -n "$M" ] || return 0
  xt4 -t mangle -N HETU_PROBE >/dev/null 2>&1 || true; xt4 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true
  xt4 -t mangle -A HETU_PROBE -m mac --mac-source "$M" -j RETURN >/dev/null 2>&1; R=$?
  xt4 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true; xt4 -t mangle -X HETU_PROBE >/dev/null 2>&1 || true; return "$R"
}
probecidrs(){
  LIST="$1"; [ -z "$LIST" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS
  for X in "$@"; do
    if echo "$X" | grep -q ':'; then has ip6tables || return 1; xt6 -t mangle -N HETU_PROBE >/dev/null 2>&1 || true; xt6 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true; xt6 -t mangle -A HETU_PROBE -d "$X" -j RETURN >/dev/null 2>&1 || { xt6 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true; xt6 -t mangle -X HETU_PROBE >/dev/null 2>&1 || true; return 1; }; xt6 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true; xt6 -t mangle -X HETU_PROBE >/dev/null 2>&1 || true
    else xt4 -t mangle -N HETU_PROBE >/dev/null 2>&1 || true; xt4 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true; xt4 -t mangle -A HETU_PROBE -d "$X" -j RETURN >/dev/null 2>&1 || { xt4 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true; xt4 -t mangle -X HETU_PROBE >/dev/null 2>&1 || true; return 1; }; xt4 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true; xt4 -t mangle -X HETU_PROBE >/dev/null 2>&1 || true; fi
  done
}

probe_ingress(){
  M="$1"; TP="$2"; RP="$3"; V6="$4"; TCP="$5"; UDP="$6"; DNS="$7"; QUIC="$8"; KILL="$9"; DP="${10}"
  NEED_TP=0; NEED_RP=0; NEED_DNS_REDIRECT=0
  case "$M" in tproxy) if [ "$TCP" = 1 ] || [ "$UDP" = 1 ]; then NEED_TP=1; fi;; redirect) [ "$TCP" = 1 ] && NEED_RP=1;; enhance) [ "$TCP" = 1 ] && NEED_RP=1; [ "$UDP" = 1 ] && NEED_TP=1;; esac
  # Native TUN/eBPF owns DNS hijacking inside Mihomo. Only transparent
  # netfilter modes install Hetu's separate DNS REDIRECT chains.
  if [ "$M" != tun ] && [ "$M" != ebpf ] && [ "$DNS" != off ]; then
    NEED_DNS_REDIRECT=1
    probered4 "$DP" tcp || fail "当前 iptables 不支持 TCP DNS REDIRECT"
    probered4 "$DP" udp || fail "当前 iptables 不支持 UDP DNS REDIRECT"
  fi
  [ "$NEED_TP" = 1 ] && { port "$TP" || fail "TPROXY 端口无效"; probetp4 "$TP" || fail "当前内核或 iptables 不支持 TPROXY"; }
  if [ "$M" = tproxy ] && [ "$TCP" = 1 ]; then probe_tcp_ownership xt4 || fail "当前内核缺少 TCP 连接跟踪支持，无法安全接管已有网络"; fi
  [ "$NEED_RP" = 1 ] && { port "$RP" || fail "Redirect 端口无效"; probered4 "$RP" tcp || fail "当前 iptables 不支持 REDIRECT"; }
  if [ "$M" != tun ] && [ "$M" != ebpf ] && [ "$NEED_TP" = 0 ] && [ "$NEED_RP" = 0 ] && [ "$DNS" = off ]; then fail "TCP、UDP 与 DNS 接管均已关闭，代理没有可接管流量"; fi

  if [ "$V6" = enable ] && v6supported; then
    if [ "$NEED_TP" = 1 ] || [ "$NEED_RP" = 1 ] || [ "$NEED_DNS_REDIRECT" = 1 ] || [ "$QUIC" = 1 ]; then
      has ip6tables || fail "当前 IPv6 接管或过滤策略需要 ip6tables"
    fi
    [ "$NEED_TP" = 0 ] || probetp6 "$TP" || fail "IPv6 TPROXY 不可用，可改用严格 IPv4 或 IPv6 不进核心"
    if [ "$M" = tproxy ] && [ "$TCP" = 1 ]; then probe_tcp_ownership xt6 || fail "IPv6 TCP 连接跟踪不可用，可改用 IPv6 不进核心"; fi
    [ "$NEED_RP" = 0 ] || probered6 "$RP" tcp || fail "IPv6 REDIRECT 不可用，可改用严格 IPv4 或 IPv6 不进核心"
    if [ "$NEED_DNS_REDIRECT" = 1 ]; then probered6 "$DP" tcp || fail "IPv6 TCP DNS REDIRECT 不可用"; probered6 "$DP" udp || fail "IPv6 UDP DNS REDIRECT 不可用"; fi
  fi
  if [ "$V6" = disable ] && v6supported && [ "$NEED_DNS_REDIRECT" = 1 ]; then
    has ip6tables || fail "IPv6 DNS 防泄漏需要 ip6tables"
    # DNS redirection is optional in disable mode; filter REJECT remains mandatory.
    # Re-evaluate the optional NAT capability in start(), even with cached preflight.
  fi
  if v6supported && { [ "$V6" = strict ] || [ "$V6" = disable ] || [ "$KILL" = 1 ]; }; then
    has ip6tables || fail "IPv6 禁用/严格 IPv4 或 Kill Switch 需要 ip6tables"
  fi
}

# This decision is deliberately outside the cached preflight fast path.
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
    redirect) install_dns_redirect6 "$START_DP" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_IFACES" "${START_SHARED_MACS:-}";;
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

preflight(){
  M="$1"; TP="$2"; RP="$3"; V6="$4"; TCP="$5"; UDP="$6"; DNS="$7"; QUIC="$8"; DP="$9"; CP="${10}"; SCOPE="${11}"; UIDS="${12}"; SHARE="${13}"; KILL="${14}"; CIDRS="${15}"; IFACES="${16}"; DIRECT_UIDS="${17}"; DIRECT_GIDS="${18:-}"; SHARED_MACS="${19:-}"
  root; acquire_lock || fail "另一个代理网络事务正在执行，请稍后重试"; mode "$M" || fail "运行模式无效"; ipv6mode "$V6" || fail "IPv6 模式无效"; dnsmode "$DNS" || fail "DNS 劫持模式无效"; scope "$SCOPE" || fail "应用范围无效"
  bool "$TCP" || fail "TCP 开关无效"; bool "$UDP" || fail "UDP 开关无效"; bool "$QUIC" || fail "QUIC 开关无效"; bool "$SHARE" || fail "共享网络开关无效"; bool "$KILL" || fail "Kill Switch 开关无效"
  port "$DP" || fail "DNS 监听端口无效"; port "$CP" || fail "控制接口端口无效"; has ip || fail "系统缺少 ip 命令"; has iptables || fail "系统缺少 iptables"
  split_safe_uids "$UIDS" || fail "应用 UID 列表无效"; split_safe_uids "$DIRECT_UIDS" || fail "DIRECT UID 列表无效"; split_safe_uids "$DIRECT_GIDS" || fail "DIRECT GID 列表无效"; split_safe_cidrs "$CIDRS" || fail "CIDR 绕过列表无效"; split_safe_ifaces "$IFACES" || fail "接口绕过列表无效"; split_safe_macs "$SHARED_MACS" || fail "共享网络 MAC 直连列表无效"
  [ "$SCOPE" != whitelist ] || [ -n "$UIDS" ] || fail "仅所选应用代理模式没有可用 UID"
  if [ "$SCOPE" != core ] && [ -n "$UIDS" ]; then U=$(first_uid "$UIDS"); probeowner "$U" || fail "当前 iptables 不支持 owner UID 匹配"; fi
  if [ -n "$DIRECT_UIDS" ]; then DU=$(first_uid "$DIRECT_UIDS"); probeowner "$DU" || fail "当前 iptables 不支持 DIRECT UID 直连"; fi
  if [ -n "$DIRECT_GIDS" ]; then DG=$(first_uid "$DIRECT_GIDS"); probegid "$DG" || fail "当前 iptables 不支持 DIRECT GID 直连"; fi
  if [ "$SHARE" = 1 ] && [ -n "$SHARED_MACS" ]; then SM=$(first_mac "$SHARED_MACS"); probemac "$SM" || fail "当前 iptables 不支持按 MAC 匹配共享设备"; fi
  [ "$SCOPE" = whitelist ] || probeowner "0-9999" || fail "当前 iptables 不支持系统 UID 范围绕过"
  probecidrs "$CIDRS" || fail "CIDR 绕过列表包含当前系统不支持的地址"
  if [ "$M" = tun ] || [ "$M" = ebpf ]; then
    { [ -c /dev/tun ] || [ -c /dev/net/tun ]; } || fail "当前设备没有可用 TUN 字符设备"
  fi
  if [ "$M" = ebpf ]; then
    [ -d /sys/fs/bpf ] || fail "eBPF 需要已挂载的 /sys/fs/bpf"
    grep -qw bpf /proc/filesystems 2>/dev/null || fail "当前内核未启用 BPF 文件系统支持"
  fi

  probe_ingress "$M" "$TP" "$RP" "$V6" "$TCP" "$UDP" "$DNS" "$QUIC" "$KILL" "$DP"
  # Interface IPv6 is owned by Android/netd. Native app IPv6 is blocked by
  # the filter guard, while DNS is redirected to the core in both families.
  ok "Root 代理预检通过"
}

route4(){ ip route replace local 0.0.0.0/0 dev lo table "$TABLE" || return 1; ip rule add pref "$PREF" fwmark "$MARK/$MASK" table "$TABLE" || return 1; }
route6(){ ip -6 route replace local ::/0 dev lo table "$TABLE" || return 1; ip -6 rule add pref "$PREF" fwmark "$MARK/$MASK" table "$TABLE" || return 1; }

# With sharing enabled PREROUTING sees replies to the core's own outbound
# sockets as well as forwarded clients. Do not redirect those local-destination
# packets into TPROXY again. Excluding lo is essential: OUTPUT marks deliberately
# route app traffic through lo and the policy route itself has type LOCAL.
# NAT DNS interception runs separately, so port 53 remains protected.
local_destination_return(){
  LOCAL_BIN="$1"; LOCAL_CHAIN="$2"; LOCAL_FAMILY="$3"
  "$LOCAL_BIN" -t mangle -A "$LOCAL_CHAIN" ! -i lo -m addrtype --dst-type LOCAL -j RETURN >/dev/null 2>&1 && return 0
  # Older kernels can lack xt_addrtype. Fall back to the addresses currently
  # assigned to this device, never whole interface prefixes or remote ranges.
  # The reply-direction guard also covers addresses acquired after a handover;
  # without either dynamic matcher, shared interception cannot be installed
  # safely. DNS NAT rules still inspect these packets in their separate table.
  "$LOCAL_BIN" -t mangle -A "$LOCAL_CHAIN" ! -i lo -m conntrack --ctdir REPLY -j RETURN >/dev/null 2>&1 || return 1
  LOCAL_ROWS=$(ip -o "$LOCAL_FAMILY" address show 2>/dev/null) || return 1
  LOCAL_ADDRESSES=$(printf '%s\n' "$LOCAL_ROWS" | awk '{split($4,a,"/"); if(a[1]!="") print a[1]}')
  for LOCAL_ADDRESS in $LOCAL_ADDRESSES; do
    case "$LOCAL_ADDRESS" in *[!0-9a-fA-F:.]*|'') return 1;; esac
    "$LOCAL_BIN" -t mangle -A "$LOCAL_CHAIN" ! -i lo -d "$LOCAL_ADDRESS" -j RETURN || return 1
  done
}

# A TCP session established before interception belongs to its original remote
# socket. Sending its ACK/data to a fresh transparent listener cannot migrate
# that session: it drops/resets the stream until the app reconnects. Remember
# only Hetu-selected TCP flows in our private conntrack bit and preserve older
# unowned streams. Never return every ESTABLISHED flow: proxied ACK/data must
# continue through TPROXY. DNS NAT and UDP keep their independent policies.
preserve_existing_tcp(){
  "$1" -t mangle -A "$2" -p tcp -m conntrack --ctstate ESTABLISHED -m connmark ! --mark "$MARK/$MASK" -j RETURN
}
remember_local_tcp(){
  "$1" -t mangle -A "$2" -p tcp -m mark --mark "$MARK/$MASK" -j CONNMARK --set-xmark "$MARK/$MASK"
}
remember_shared_tcp(){
  "$1" -t mangle -A "$2" -p tcp -j CONNMARK --set-xmark "$MARK/$MASK"
}

install_mangle4(){
  P="$1"; M="$2"; TCP="$3"; UDP="$4"; DNS="$5"; S="$6"; UIDS="$7"; SHARE="$8"; CIDRS="$9"; IFACES="${10}"; DUIDS="${11}"; DGIDS="${12:-}"; MACS="${13:-}"
  NEED=0; case "$M" in tproxy) if [ "$TCP" = 1 ] || [ "$UDP" = 1 ]; then NEED=1; fi;; enhance) [ "$UDP" = 1 ] && NEED=1;; esac; [ "$NEED" = 1 ] || return 0
  route4 || return 1; xt4 -t mangle -N "$MOUT" || return 1; xt4 -t mangle -N "$MPRE" || return 1
  xt4 -t mangle -A "$MOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1
  core_return xt4 mangle "$MOUT" || return 1
  system_uid_return xt4 mangle "$MOUT" "$S" || return 1
  direct_uid_returns xt4 mangle "$MOUT" "$DUIDS" || return 1
  direct_gid_returns xt4 mangle "$MOUT" "$DGIDS" || return 1
  iface_out xt4 mangle "$MOUT" "$IFACES" || return 1; blacklist_returns xt4 mangle "$MOUT" "$S" "$UIDS" || return 1
  xt4 -t mangle -A "$MPRE" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_in xt4 mangle "$MPRE" "$IFACES" || return 1
  [ "$SHARE" = 0 ] || shared_mac_returns xt4 mangle "$MPRE" "$MACS" || return 1
  [ "$SHARE" = 0 ] || local_destination_return xt4 "$MPRE" -4 || return 1
  if [ "$DNS" = tproxy ] || [ "$DNS" = redirect ]; then
    xt4 -t mangle -A "$MOUT" -p tcp --dport 53 -j RETURN || return 1
    xt4 -t mangle -A "$MOUT" -p udp --dport 53 -j RETURN || return 1
  fi
  bypass4 "$MOUT" mangle "$CIDRS" || return 1; bypass4 "$MPRE" mangle "$CIDRS" || return 1
  if [ "$M" = tproxy ] && [ "$TCP" = 1 ]; then
    preserve_existing_tcp xt4 "$MOUT" || return 1
    if [ "$SHARE" = 1 ]; then preserve_existing_tcp xt4 "$MPRE" || return 1; remember_shared_tcp xt4 "$MPRE" || return 1; fi
  fi
  if [ "$M" = tproxy ]; then
    [ "$TCP" = 0 ] || { scoped_mark xt4 mangle "$MOUT" "$S" "$UIDS" tcp "" "$MARK/$MASK" || return 1; fake_mark xt4 "$MOUT" "$S" tcp || return 1; if [ "$SHARE" = 1 ]; then xt4 -t mangle -A "$MPRE" -p tcp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; else xt4 -t mangle -A "$MPRE" -m mark --mark "$MARK/$MASK" -p tcp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; fi; }
    [ "$UDP" = 0 ] || { scoped_mark xt4 mangle "$MOUT" "$S" "$UIDS" udp "" "$MARK/$MASK" || return 1; fake_mark xt4 "$MOUT" "$S" udp || return 1; if [ "$SHARE" = 1 ]; then xt4 -t mangle -A "$MPRE" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; else xt4 -t mangle -A "$MPRE" -m mark --mark "$MARK/$MASK" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; fi; }
  elif [ "$M" = enhance ] && [ "$UDP" = 1 ]; then scoped_mark xt4 mangle "$MOUT" "$S" "$UIDS" udp "" "$MARK/$MASK" || return 1; fake_mark xt4 "$MOUT" "$S" udp || return 1; if [ "$SHARE" = 1 ]; then xt4 -t mangle -A "$MPRE" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; else xt4 -t mangle -A "$MPRE" -m mark --mark "$MARK/$MASK" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; fi; fi
  if [ "$M" = tproxy ] && [ "$TCP" = 1 ]; then remember_local_tcp xt4 "$MOUT" || return 1; fi
  xt4 -t mangle -A OUTPUT -j "$MOUT" || return 1; xt4 -t mangle -A PREROUTING -j "$MPRE" || return 1
}

install_mangle6(){
  P="$1"; M="$2"; TCP="$3"; UDP="$4"; DNS="$5"; S="$6"; UIDS="$7"; SHARE="$8"; CIDRS="$9"; IFACES="${10}"; DUIDS="${11}"; DGIDS="${12:-}"; MACS="${13:-}"; v6supported || return 0
  NEED=0; case "$M" in tproxy) if [ "$TCP" = 1 ] || [ "$UDP" = 1 ]; then NEED=1; fi;; enhance) [ "$UDP" = 1 ] && NEED=1;; esac; [ "$NEED" = 1 ] || return 0
  route6 || return 1; xt6 -t mangle -N "$MOUT" || return 1; xt6 -t mangle -N "$MPRE" || return 1
  xt6 -t mangle -A "$MOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1
  core_return xt6 mangle "$MOUT" || return 1
  system_uid_return xt6 mangle "$MOUT" "$S" || return 1
  direct_uid_returns xt6 mangle "$MOUT" "$DUIDS" || return 1
  direct_gid_returns xt6 mangle "$MOUT" "$DGIDS" || return 1
  iface_out xt6 mangle "$MOUT" "$IFACES" || return 1; blacklist_returns xt6 mangle "$MOUT" "$S" "$UIDS" || return 1
  xt6 -t mangle -A "$MPRE" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_in xt6 mangle "$MPRE" "$IFACES" || return 1
  [ "$SHARE" = 0 ] || shared_mac_returns xt6 mangle "$MPRE" "$MACS" || return 1
  [ "$SHARE" = 0 ] || local_destination_return xt6 "$MPRE" -6 || return 1
  if [ "$DNS" = tproxy ] || [ "$DNS" = redirect ]; then
    xt6 -t mangle -A "$MOUT" -p tcp --dport 53 -j RETURN || return 1
    xt6 -t mangle -A "$MOUT" -p udp --dport 53 -j RETURN || return 1
  fi
  bypass6 "$MOUT" mangle "$CIDRS" || return 1; bypass6 "$MPRE" mangle "$CIDRS" || return 1
  if [ "$M" = tproxy ] && [ "$TCP" = 1 ]; then
    preserve_existing_tcp xt6 "$MOUT" || return 1
    if [ "$SHARE" = 1 ]; then preserve_existing_tcp xt6 "$MPRE" || return 1; remember_shared_tcp xt6 "$MPRE" || return 1; fi
  fi
  if [ "$M" = tproxy ]; then
    [ "$TCP" = 0 ] || { scoped_mark xt6 mangle "$MOUT" "$S" "$UIDS" tcp "" "$MARK/$MASK" || return 1; fake_mark xt6 "$MOUT" "$S" tcp || return 1; if [ "$SHARE" = 1 ]; then xt6 -t mangle -A "$MPRE" -p tcp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; else xt6 -t mangle -A "$MPRE" -m mark --mark "$MARK/$MASK" -p tcp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; fi; }
    [ "$UDP" = 0 ] || { scoped_mark xt6 mangle "$MOUT" "$S" "$UIDS" udp "" "$MARK/$MASK" || return 1; fake_mark xt6 "$MOUT" "$S" udp || return 1; if [ "$SHARE" = 1 ]; then xt6 -t mangle -A "$MPRE" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; else xt6 -t mangle -A "$MPRE" -m mark --mark "$MARK/$MASK" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; fi; }
  elif [ "$M" = enhance ] && [ "$UDP" = 1 ]; then scoped_mark xt6 mangle "$MOUT" "$S" "$UIDS" udp "" "$MARK/$MASK" || return 1; fake_mark xt6 "$MOUT" "$S" udp || return 1; if [ "$SHARE" = 1 ]; then xt6 -t mangle -A "$MPRE" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; else xt6 -t mangle -A "$MPRE" -m mark --mark "$MARK/$MASK" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; fi; fi
  if [ "$M" = tproxy ] && [ "$TCP" = 1 ]; then remember_local_tcp xt6 "$MOUT" || return 1; fi
  xt6 -t mangle -A OUTPUT -j "$MOUT" || return 1; xt6 -t mangle -A PREROUTING -j "$MPRE" || return 1
}

install_redirect4(){
  P="$1"; M="$2"; TCP="$3"; S="$4"; UIDS="$5"; SHARE="$6"; CIDRS="$7"; IFACES="$8"; DUIDS="$9"; DGIDS="${10:-}"; MACS="${11:-}"; [ "$TCP" = 1 ] || return 0; case "$M" in redirect|enhance) ;; *) return 0;; esac
  xt4 -t nat -N "$NOUT" || return 1; xt4 -t nat -A "$NOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; core_return xt4 nat "$NOUT" || return 1; system_uid_return xt4 nat "$NOUT" "$S" || return 1; direct_uid_returns xt4 nat "$NOUT" "$DUIDS" || return 1; direct_gid_returns xt4 nat "$NOUT" "$DGIDS" || return 1; iface_out xt4 nat "$NOUT" "$IFACES" || return 1; blacklist_returns xt4 nat "$NOUT" "$S" "$UIDS" || return 1; bypass4 "$NOUT" nat "$CIDRS" || return 1; scoped_redirect xt4 nat "$NOUT" "$S" "$UIDS" tcp "" "$P" || return 1; fake_redirect xt4 "$NOUT" "$S" "$P" || return 1; xt4 -t nat -A OUTPUT -j "$NOUT" || return 1
  if [ "$SHARE" = 1 ]; then xt4 -t nat -N "$NPRE" || return 1; xt4 -t nat -A "$NPRE" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_in xt4 nat "$NPRE" "$IFACES" || return 1; shared_mac_returns xt4 nat "$NPRE" "$MACS" || return 1; bypass4 "$NPRE" nat "$CIDRS" || return 1; xt4 -t nat -A "$NPRE" -p tcp -j REDIRECT --to-ports "$P" || return 1; xt4 -t nat -A PREROUTING -j "$NPRE" || return 1; fi
}
install_redirect6(){
  P="$1"; M="$2"; TCP="$3"; S="$4"; UIDS="$5"; SHARE="$6"; CIDRS="$7"; IFACES="$8"; DUIDS="$9"; DGIDS="${10:-}"; MACS="${11:-}"; v6supported || return 0; [ "$TCP" = 1 ] || return 0; case "$M" in redirect|enhance) ;; *) return 0;; esac
  xt6 -t nat -N "$NOUT" || return 1; xt6 -t nat -A "$NOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; core_return xt6 nat "$NOUT" || return 1; system_uid_return xt6 nat "$NOUT" "$S" || return 1; direct_uid_returns xt6 nat "$NOUT" "$DUIDS" || return 1; direct_gid_returns xt6 nat "$NOUT" "$DGIDS" || return 1; iface_out xt6 nat "$NOUT" "$IFACES" || return 1; blacklist_returns xt6 nat "$NOUT" "$S" "$UIDS" || return 1; bypass6 "$NOUT" nat "$CIDRS" || return 1; scoped_redirect xt6 nat "$NOUT" "$S" "$UIDS" tcp "" "$P" || return 1; fake_redirect xt6 "$NOUT" "$S" "$P" || return 1; xt6 -t nat -A OUTPUT -j "$NOUT" || return 1
  if [ "$SHARE" = 1 ]; then xt6 -t nat -N "$NPRE" || return 1; xt6 -t nat -A "$NPRE" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_in xt6 nat "$NPRE" "$IFACES" || return 1; shared_mac_returns xt6 nat "$NPRE" "$MACS" || return 1; bypass6 "$NPRE" nat "$CIDRS" || return 1; xt6 -t nat -A "$NPRE" -p tcp -j REDIRECT --to-ports "$P" || return 1; xt6 -t nat -A PREROUTING -j "$NPRE" || return 1; fi
}

# Who is left out of DNS takeover: the core alone when it runs as root:net_admin,
# otherwise every root socket as before (and with them the system resolver).
dns_owner_return(){
  if [ "$SYSTEM_DNS" = captured ]; then core_return "$1" nat "$DNSOUT"
  else "$1" -t nat -A "$DNSOUT" -m owner --uid-owner 0 -j RETURN; fi
}
# 仅所选应用 redirects DNS per listed UID; the resolver asks on behalf of every
# application and is root, so it needs a rule of its own.
dns_resolver_redirect(){
  [ "$2" = whitelist ] && [ "$SYSTEM_DNS" = captured ] || return 0
  "$1" -t nat -A "$DNSOUT" -m owner --uid-owner 0 -p "$3" --dport 53 -j REDIRECT --to-ports "$4"
}
# Opportunistic Private DNS moves the resolver to TLS on port 853 as soon as the network's
# DNS server passes validation, which would take it out of port-53 takeover some time
# after connecting. Refusing only the resolver's own probe keeps it on port 53. A Private
# DNS host name the user configured is a deliberate choice and is left alone.
install_dot_guard(){
  DG_BIN="$1"
  "$DG_BIN" -t filter -N "$DOTOUT" || return 1
  core_return "$DG_BIN" filter "$DOTOUT" || return 1
  "$DG_BIN" -t filter -A "$DOTOUT" -m owner --uid-owner 0 -p tcp --dport 853 -j REJECT --reject-with tcp-reset || return 1
  "$DG_BIN" -t filter -I OUTPUT 1 -j "$DOTOUT" || return 1
}
remove_dot_guard(){
  while "$1" -t filter -C OUTPUT -j "$DOTOUT" >/dev/null 2>&1; do "$1" -t filter -D OUTPUT -j "$DOTOUT" >/dev/null 2>&1 || break; done
  "$1" -t filter -F "$DOTOUT" >/dev/null 2>&1 || true; "$1" -t filter -X "$DOTOUT" >/dev/null 2>&1 || true
}

install_dns_redirect4(){
  P="$1"; S="$2"; UIDS="$3"; SHARE="$4"; IFACES="$5"; MACS="${6:-}"; xt4 -t nat -N "$DNSOUT" || return 1; xt4 -t nat -A "$DNSOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; dns_owner_return xt4 || return 1; iface_out xt4 nat "$DNSOUT" "$IFACES" || return 1; blacklist_returns xt4 nat "$DNSOUT" "$S" "$UIDS" || return 1
  for X in $DNS_PROTOS; do scoped_redirect xt4 nat "$DNSOUT" "$S" "$UIDS" "$X" 53 "$P" || return 1; dns_resolver_redirect xt4 "$S" "$X" "$P" || return 1; done; xt4 -t nat -I OUTPUT 1 -j "$DNSOUT" || return 1
  if [ "$SHARE" = 1 ]; then xt4 -t nat -N "$DNSPRE" || return 1; iface_in xt4 nat "$DNSPRE" "$IFACES" || return 1; shared_mac_returns xt4 nat "$DNSPRE" "$MACS" || return 1; for X in $DNS_PROTOS; do xt4 -t nat -A "$DNSPRE" -p "$X" --dport 53 -j REDIRECT --to-ports "$P" || return 1; done; xt4 -t nat -I PREROUTING 1 -j "$DNSPRE" || return 1; fi
}
install_dns_redirect6(){
  P="$1"; S="$2"; UIDS="$3"; SHARE="$4"; IFACES="$5"; MACS="${6:-}"; v6supported || return 0; xt6 -t nat -N "$DNSOUT" || return 1; xt6 -t nat -A "$DNSOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; dns_owner_return xt6 || return 1; iface_out xt6 nat "$DNSOUT" "$IFACES" || return 1; blacklist_returns xt6 nat "$DNSOUT" "$S" "$UIDS" || return 1
  for X in $DNS_PROTOS; do scoped_redirect xt6 nat "$DNSOUT" "$S" "$UIDS" "$X" 53 "$P" || return 1; dns_resolver_redirect xt6 "$S" "$X" "$P" || return 1; done; xt6 -t nat -I OUTPUT 1 -j "$DNSOUT" || return 1
  if [ "$SHARE" = 1 ]; then xt6 -t nat -N "$DNSPRE" || return 1; iface_in xt6 nat "$DNSPRE" "$IFACES" || return 1; shared_mac_returns xt6 nat "$DNSPRE" "$MACS" || return 1; for X in $DNS_PROTOS; do xt6 -t nat -A "$DNSPRE" -p "$X" --dport 53 -j REDIRECT --to-ports "$P" || return 1; done; xt6 -t nat -I PREROUTING 1 -j "$DNSPRE" || return 1; fi
}

install_udp_leak_guard4(){
  S="$1"; UIDS="$2"; SHARE="$3"; CIDRS="$4"; IFACES="$5"; DUIDS="$6"; DGIDS="${7:-}"; MACS="${8:-}"
  xt4 -t filter -N "$WROUT" || return 1
  xt4 -t filter -A "$WROUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1
  if [ -n "$MARK" ]; then xt4 -t filter -A "$WROUT" -m mark --mark "$MARK/$MASK" -j RETURN || return 1; fi
  system_uid_return xt4 filter "$WROUT" "$S" || return 1
  direct_uid_returns xt4 filter "$WROUT" "$DUIDS" || return 1
  direct_gid_returns xt4 filter "$WROUT" "$DGIDS" || return 1
  iface_out xt4 filter "$WROUT" "$IFACES" || return 1
  blacklist_returns xt4 filter "$WROUT" "$S" "$UIDS" || return 1
  bypass4 "$WROUT" filter "$CIDRS" || return 1
  scoped_reject_unmarked_udp xt4 "$WROUT" "$S" "$UIDS" || return 1
  xt4 -t filter -I OUTPUT 1 -j "$WROUT" || return 1
  if [ "$SHARE" = 1 ]; then
    xt4 -t filter -N "$WRFWD" || return 1
    if [ -n "$MARK" ]; then xt4 -t filter -A "$WRFWD" -m mark --mark "$MARK/$MASK" -j RETURN || return 1; fi
    iface_in xt4 filter "$WRFWD" "$IFACES" || return 1
    shared_mac_returns xt4 filter "$WRFWD" "$MACS" || return 1
    bypass4 "$WRFWD" filter "$CIDRS" || return 1
    xt4 -t filter -A "$WRFWD" -p udp -j REJECT || return 1
    xt4 -t filter -I FORWARD 1 -j "$WRFWD" || return 1
  fi
}
install_udp_leak_guard6(){
  S="$1"; UIDS="$2"; SHARE="$3"; CIDRS="$4"; IFACES="$5"; DUIDS="$6"; DGIDS="${7:-}"; MACS="${8:-}"; v6supported || return 0
  xt6 -t filter -N "$WROUT" || return 1
  xt6 -t filter -A "$WROUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1
  if [ -n "$MARK" ]; then xt6 -t filter -A "$WROUT" -m mark --mark "$MARK/$MASK" -j RETURN || return 1; fi
  system_uid_return xt6 filter "$WROUT" "$S" || return 1
  direct_uid_returns xt6 filter "$WROUT" "$DUIDS" || return 1
  direct_gid_returns xt6 filter "$WROUT" "$DGIDS" || return 1
  iface_out xt6 filter "$WROUT" "$IFACES" || return 1
  blacklist_returns xt6 filter "$WROUT" "$S" "$UIDS" || return 1
  bypass6 "$WROUT" filter "$CIDRS" || return 1
  scoped_reject_unmarked_udp xt6 "$WROUT" "$S" "$UIDS" || return 1
  xt6 -t filter -I OUTPUT 1 -j "$WROUT" || return 1
  if [ "$SHARE" = 1 ]; then
    xt6 -t filter -N "$WRFWD" || return 1
    if [ -n "$MARK" ]; then xt6 -t filter -A "$WRFWD" -m mark --mark "$MARK/$MASK" -j RETURN || return 1; fi
    iface_in xt6 filter "$WRFWD" "$IFACES" || return 1
    shared_mac_returns xt6 filter "$WRFWD" "$MACS" || return 1
    bypass6 "$WRFWD" filter "$CIDRS" || return 1
    xt6 -t filter -A "$WRFWD" -p udp -j REJECT || return 1
    xt6 -t filter -I FORWARD 1 -j "$WRFWD" || return 1
  fi
}

install_quic4(){
  S="$1"; UIDS="$2"; SHARE="$3"; CIDRS="$4"; IFACES="$5"; DUIDS="$6"; DGIDS="${7:-}"; MACS="${8:-}"; xt4 -t filter -N "$QUICOUT" || return 1; xt4 -t filter -A "$QUICOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; system_uid_return xt4 filter "$QUICOUT" "$S" || return 1; direct_uid_returns xt4 filter "$QUICOUT" "$DUIDS" || return 1; direct_gid_returns xt4 filter "$QUICOUT" "$DGIDS" || return 1; iface_out xt4 filter "$QUICOUT" "$IFACES" || return 1; blacklist_returns xt4 filter "$QUICOUT" "$S" "$UIDS" || return 1; bypass4 "$QUICOUT" filter "$CIDRS" || return 1; scoped_reject_quic xt4 "$QUICOUT" "$S" "$UIDS" || return 1; xt4 -t filter -A OUTPUT -j "$QUICOUT" || return 1
  if [ "$SHARE" = 1 ]; then xt4 -t filter -N "$QUICFWD" || return 1; iface_in xt4 filter "$QUICFWD" "$IFACES" || return 1; shared_mac_returns xt4 filter "$QUICFWD" "$MACS" || return 1; bypass4 "$QUICFWD" filter "$CIDRS" || return 1; xt4 -t filter -A "$QUICFWD" -p udp --dport 443 -j REJECT || return 1; xt4 -t filter -A FORWARD -j "$QUICFWD" || return 1; fi
}
install_quic6(){
  S="$1"; UIDS="$2"; SHARE="$3"; CIDRS="$4"; IFACES="$5"; DUIDS="$6"; DGIDS="${7:-}"; MACS="${8:-}"; v6supported || return 0; xt6 -t filter -N "$QUICOUT" || return 1; xt6 -t filter -A "$QUICOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; system_uid_return xt6 filter "$QUICOUT" "$S" || return 1; direct_uid_returns xt6 filter "$QUICOUT" "$DUIDS" || return 1; direct_gid_returns xt6 filter "$QUICOUT" "$DGIDS" || return 1; iface_out xt6 filter "$QUICOUT" "$IFACES" || return 1; blacklist_returns xt6 filter "$QUICOUT" "$S" "$UIDS" || return 1; bypass6 "$QUICOUT" filter "$CIDRS" || return 1; scoped_reject_quic xt6 "$QUICOUT" "$S" "$UIDS" || return 1; xt6 -t filter -A OUTPUT -j "$QUICOUT" || return 1
  if [ "$SHARE" = 1 ]; then xt6 -t filter -N "$QUICFWD" || return 1; iface_in xt6 filter "$QUICFWD" "$IFACES" || return 1; shared_mac_returns xt6 filter "$QUICFWD" "$MACS" || return 1; bypass6 "$QUICFWD" filter "$CIDRS" || return 1; xt6 -t filter -A "$QUICFWD" -p udp --dport 443 -j REJECT || return 1; xt6 -t filter -A FORWARD -j "$QUICFWD" || return 1; fi
}

install_v6_strict(){
  S="$1"; UIDS="$2"; SHARE="$3"; CIDRS="$4"; IFACES="$5"; DUIDS="$6"; DGIDS="${7:-}"; MACS="${8:-}"; v6supported || return 0; xt6 -t filter -N "$V6OUT" || return 1; xt6 -t filter -A "$V6OUT" -o lo -j RETURN || return 1; xt6 -t filter -A "$V6OUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_out xt6 filter "$V6OUT" "$IFACES" || return 1; direct_uid_returns xt6 filter "$V6OUT" "$DUIDS" || return 1; direct_gid_returns xt6 filter "$V6OUT" "$DGIDS" || return 1; blacklist_returns xt6 filter "$V6OUT" "$S" "$UIDS" || return 1; bypass6 "$V6OUT" filter "$CIDRS" || return 1; scoped_reject_all xt6 "$V6OUT" "$S" "$UIDS" || return 1; xt6 -t filter -A OUTPUT -j "$V6OUT" || return 1
  if [ "$SHARE" = 1 ]; then xt6 -t filter -N "$V6FWD" || return 1; iface_in xt6 filter "$V6FWD" "$IFACES" || return 1; shared_mac_returns xt6 filter "$V6FWD" "$MACS" || return 1; bypass6 "$V6FWD" filter "$CIDRS" || return 1; xt6 -t filter -A "$V6FWD" -j REJECT || return 1; xt6 -t filter -A FORWARD -j "$V6FWD" || return 1; fi
}

# Disable native IPv6 for applications, not the underlying Android network.
# Keep NDP/PMTU and system CLAT/IMS transport alive; DNS must terminate locally.
# No application UID, public CIDR, or bypass selection escapes this guard.
install_v6_disable(){
  V6_SHARE="$1"; v6supported || return 0
  xt6 -t filter -N "$V6OUT" || return 1
  xt6 -t filter -A "$V6OUT" -o lo -j RETURN || return 1
  xt6 -t filter -A "$V6OUT" -p ipv6-icmp -j RETURN || return 1
  xt6 -t filter -A "$V6OUT" -p udp --dport 53 -j REJECT || return 1
  xt6 -t filter -A "$V6OUT" -p tcp --dport 53 -j REJECT || return 1
  xt6 -t filter -A "$V6OUT" -m owner --uid-owner 0-9999 -j RETURN || return 1
  xt6 -t filter -A "$V6OUT" -j REJECT || return 1
  xt6 -t filter -I OUTPUT 1 -j "$V6OUT" || return 1
  if [ "$V6_SHARE" = 1 ]; then
    xt6 -t filter -N "$V6FWD" || return 1
    xt6 -t filter -A "$V6FWD" -j REJECT || return 1
    xt6 -t filter -I FORWARD 1 -j "$V6FWD" || return 1
  fi
}
v6_disable_guard_ready(){
  V6_SHARE="$1"; v6supported || return 0
  has ip6tables || return 1
  xt6q -t filter -C OUTPUT -j "$V6OUT" >/dev/null 2>&1 || return 1
  xt6q -t filter -C "$V6OUT" -j REJECT >/dev/null 2>&1 || return 1
  if [ "$V6_SHARE" = 1 ]; then
    xt6q -t filter -C FORWARD -j "$V6FWD" >/dev/null 2>&1 || return 1
    xt6q -t filter -C "$V6FWD" -j REJECT >/dev/null 2>&1 || return 1
  fi
}

startup_core_return(){
  [ "$START_BOOTSTRAP" = 1 ] && [ "$START_ROLLBACK" != 1 ] && [ -n "$CORE_RUNNER" ] || return 0
  "$1" -t filter -A "$KOUT" -m owner --uid-owner 0 --gid-owner "$CORE_GROUP_ID" -j RETURN
}
revoke_bootstrap(){
  [ "$START_BOOTSTRAP" = 1 ] || return 0
  RV_FAILED=0
  for RV_BIN in xt4 xt6; do
    if [ "$RV_BIN" = xt6 ] && ! v6supported; then continue; fi
    "$RV_BIN" -t filter -S "$KOUT" >/dev/null 2>&1 || { RV_FAILED=1; continue; }
    while "$RV_BIN" -t filter -C "$KOUT" -m owner --uid-owner 0 --gid-owner "$CORE_GROUP_ID" -j RETURN >/dev/null 2>&1; do
      "$RV_BIN" -t filter -D "$KOUT" -m owner --uid-owner 0 --gid-owner "$CORE_GROUP_ID" -j RETURN || { RV_FAILED=1; break; }
    done
    RV_RULES=$("$RV_BIN" -t filter -S "$KOUT") || { RV_FAILED=1; continue; }
    if printf '%s\n' "$RV_RULES" | grep -F -- "--gid-owner $CORE_GROUP_ID -j RETURN" >/dev/null; then RV_FAILED=1; fi
  done
  [ "$RV_FAILED" = 0 ] || return 1
  START_BOOTSTRAP=0
}
# Build the already validated scoped rules without touching the live table,
# then let xtables commit the entire guard atomically. Never flush a live guard.
atomic_kill_guard(){ (
  KG_FAMILY="$1"; shift
  if [ "$KG_FAMILY" = 6 ] && ! v6supported; then exit 0; fi
  KG_BIN=xt4; KG_RESTORE=iptables-restore
  if [ "$KG_FAMILY" = 6 ]; then KG_BIN=xt6; KG_RESTORE=ip6tables-restore; fi
  KG_RULES=$("$KG_BIN" -t filter -S) || exit 1
  has "$KG_RESTORE" || exit 1
  KG_BATCH="$RUN/kill-$KG_FAMILY.new.$$"
  printf '*filter\n' > "$KG_BATCH" || exit 1
  guard_record(){
    [ "$1" = -t ] && [ "$2" = filter ] || return 1; shift 2
    if [ "$1" = -N ] && printf '%s\n' "$KG_RULES" | grep -Fx -- "-N $2" >/dev/null; then return 0; fi
    if [ "$1" = -I ]; then
      KG_CHAIN="$2"; shift 3
      if printf '%s\n' "$KG_RULES" | grep -Fx -- "-A $KG_CHAIN $*" >/dev/null; then return 0; fi
      set -- -I "$KG_CHAIN" 1 "$@"
    fi
    printf '%s ' "$@" >> "$KG_BATCH"; printf '\n' >> "$KG_BATCH"
  }
  xt4(){ guard_record "$@"; }; xt6(){ guard_record "$@"; }
  if [ "$KG_FAMILY" = 4 ]; then install_kill4 "$@"; else install_kill6 "$@"; fi || exit 1
  printf 'COMMIT\n' >> "$KG_BATCH" || exit 1
  if [ "$START_ACTIVE" = 1 ]; then
    transaction_current || exit 1
    timeout -s TERM -k 1 3 "$KG_RESTORE" -w 2 --noflush < "$KG_BATCH"; KG_RC=$?
  else "$KG_RESTORE" -w 2 --noflush < "$KG_BATCH"; KG_RC=$?; fi
  rm -f "$KG_BATCH"
  exit "$KG_RC"
); }

install_kill4(){
  S="$1"; UIDS="$2"; SHARE="$3"; CIDRS="$4"; IFACES="$5"; DUIDS="$6"; DGIDS="${7:-}"; MACS="${8:-}"; xt4 -t filter -N "$KOUT" >/dev/null 2>&1 || true; xt4 -t filter -F "$KOUT" || return 1; xt4 -t filter -A "$KOUT" -o lo -j RETURN || return 1; startup_core_return xt4 || return 1; iface_out xt4 filter "$KOUT" "$IFACES" || return 1; direct_uid_returns xt4 filter "$KOUT" "$DUIDS" || return 1; direct_gid_returns xt4 filter "$KOUT" "$DGIDS" || return 1; blacklist_returns xt4 filter "$KOUT" "$S" "$UIDS" || return 1; bypass4 "$KOUT" filter "$CIDRS" || return 1; scoped_reject_all xt4 "$KOUT" "$S" "$UIDS" || return 1; xt4 -t filter -I OUTPUT 1 -j "$KOUT" || return 1
  if [ "$SHARE" = 1 ]; then xt4 -t filter -N "$KFWD" >/dev/null 2>&1 || true; xt4 -t filter -F "$KFWD" || return 1; iface_in xt4 filter "$KFWD" "$IFACES" || return 1; shared_mac_returns xt4 filter "$KFWD" "$MACS" || return 1; bypass4 "$KFWD" filter "$CIDRS" || return 1; xt4 -t filter -A "$KFWD" -j REJECT || return 1; xt4 -t filter -I FORWARD 1 -j "$KFWD" || return 1; fi
}
install_kill6(){
  S="$1"; UIDS="$2"; SHARE="$3"; CIDRS="$4"; IFACES="$5"; DUIDS="$6"; DGIDS="${7:-}"; MACS="${8:-}"; v6supported || return 0; has ip6tables || return 1; xt6 -t filter -N "$KOUT" >/dev/null 2>&1 || true; xt6 -t filter -F "$KOUT" || return 1; xt6 -t filter -A "$KOUT" -o lo -j RETURN || return 1; startup_core_return xt6 || return 1; iface_out xt6 filter "$KOUT" "$IFACES" || return 1; direct_uid_returns xt6 filter "$KOUT" "$DUIDS" || return 1; direct_gid_returns xt6 filter "$KOUT" "$DGIDS" || return 1; blacklist_returns xt6 filter "$KOUT" "$S" "$UIDS" || return 1; bypass6 "$KOUT" filter "$CIDRS" || return 1; scoped_reject_all xt6 "$KOUT" "$S" "$UIDS" || return 1; xt6 -t filter -I OUTPUT 1 -j "$KOUT" || return 1
  if [ "$SHARE" = 1 ]; then xt6 -t filter -N "$KFWD" >/dev/null 2>&1 || true; xt6 -t filter -F "$KFWD" || return 1; iface_in xt6 filter "$KFWD" "$IFACES" || return 1; shared_mac_returns xt6 filter "$KFWD" "$MACS" || return 1; bypass6 "$KFWD" filter "$CIDRS" || return 1; xt6 -t filter -A "$KFWD" -j REJECT || return 1; xt6 -t filter -I FORWARD 1 -j "$KFWD" || return 1; fi
}

validatecfg(){
  BIN="$1"; CFG="$2"; : > "$CHECKLOG"
  if [ "$START_ACTIVE" = 1 ]; then
    transaction_current || return 1
    read -r VC_NOW VC_UNUSED < /proc/uptime || return 1
    VC_NOW=${VC_NOW%%.*}; VC_LEFT=$((TXN_DEADLINE-VC_NOW))
    [ "$VC_LEFT" -gt 0 ] || return 1
    timeout -s TERM -k 1 "$VC_LEFT" "$BIN" -t -d "$RUN" -f "$CFG" >>"$CHECKLOG" 2>&1
  else "$BIN" -t -d "$RUN" -f "$CFG" >>"$CHECKLOG" 2>&1; fi
}
hexport(){ printf '%04X' "$1" 2>/dev/null; }
# One ss invocation per readiness/port-check sample. /proc is a bounded fallback.
LISTEN_SNAPSHOT_VALID=0
LISTEN_PORTS=''
listen_snapshot(){
  LISTEN_SNAPSHOT_VALID=0; LISTEN_PORTS=''
  if has ss; then
    LS_RAW=$(ss -lnut 2>/dev/null); LS_RC=$?
    if [ "$LS_RC" = 0 ]; then
      LISTEN_PORTS=$(printf '%s\n' "$LS_RAW" | awk '
        ($1=="tcp" || $1=="udp" || $1=="tcp6" || $1=="udp6") && NF>=5 {
          proto=substr($1,1,3); n=split($5,a,":"); p=a[n];
          if(p ~ /^[0-9]+$/) printf " %s:%s ",proto,p
        }')
      LISTEN_SNAPSHOT_VALID=1; return 0
    fi
  fi
  [ -r /proc/net/tcp ] && [ -r /proc/net/udp ] || return 1
  LISTEN_PORTS=$(awk '
    function dec(h, i,n,c) {n=0; for(i=1;i<=length(h);i++){c=index("0123456789ABCDEF",toupper(substr(h,i,1)))-1; if(c<0)return -1; n=n*16+c} return n}
    FNR>1 && (FILENAME ~ /udp/ || $4=="0A") {
      n=split($2,a,":");p=dec(a[n]);if(p>=0)printf " %s:%s ",(FILENAME ~ /udp/?"udp":"tcp"),p
    }' /proc/net/tcp /proc/net/tcp6 /proc/net/udp /proc/net/udp6 2>/dev/null) || return 1
  LISTEN_SNAPSHOT_VALID=1
}
tcp_listen(){ [ "$LISTEN_SNAPSHOT_VALID" = 1 ] || listen_snapshot || return 1; case "$LISTEN_PORTS" in *" tcp:$1 "*) return 0;; *) return 1;; esac; }
udp_listen(){ [ "$LISTEN_SNAPSHOT_VALID" = 1 ] || listen_snapshot || return 1; case "$LISTEN_PORTS" in *" udp:$1 "*) return 0;; *) return 1;; esac; }

ready(){
  listen_snapshot || return 1
  PID="$1"; M="$2"; TP="$3"; RP="$4"; TCP="$5"; UDP="$6"; DNS="$7"; DP="$8"; CP="$9"; core_maybe_alive "$PID" && kill -0 "$PID" >/dev/null 2>&1 || return 1; tcp_listen "$CP" || return 1
  case "$M" in tproxy) [ "$TCP" = 0 ] || tcp_listen "$TP" || return 1; [ "$UDP" = 0 ] || udp_listen "$TP" || return 1;; redirect) [ "$TCP" = 0 ] || tcp_listen "$RP" || return 1;; enhance) [ "$TCP" = 0 ] || tcp_listen "$RP" || return 1; [ "$UDP" = 0 ] || udp_listen "$TP" || return 1;; tun|ebpf) ip link show hetu0 >/dev/null 2>&1 || return 1;; esac
  if [ "$DNS" = tproxy ] || [ "$DNS" = redirect ]; then tcp_listen "$DP" || return 1; udp_listen "$DP" || return 1; fi; return 0
}
check_start_ports(){
  listen_snapshot || fail "无法读取端口状态，未盲目启动核心"
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

monotonic_seconds(){
  read -r MONO_RAW MONO_UNUSED < /proc/uptime || return 1
  MONO_SECONDS=${MONO_RAW%%.*}
  case "$MONO_SECONDS" in ''|*[!0-9]*) return 1;; esac
}
wait_ready(){
  PID="$1"; M="$2"; TP="$3"; RP="$4"; TCP="$5"; UDP="$6"; DNS="$7"; DP="$8"; CP="$9"
  monotonic_seconds || return 3
  READY_DEADLINE=$((MONO_SECONDS+90))
  # 900 sleeps did NOT mean 90s: every old iteration performed several process
  # launches and socket queries. The outer timeout could kill startup before rollback.
  while :; do
    ready "$PID" "$M" "$TP" "$RP" "$TCP" "$UDP" "$DNS" "$DP" "$CP" && return 0
    core_maybe_alive "$PID" && kill -0 "$PID" >/dev/null 2>&1 || return 2
    monotonic_seconds || return 3
    [ "$MONO_SECONDS" -lt "$READY_DEADLINE" ] || return 3
    sleep 0.1
  done
}

write_session(){ M="$1"; V6="$2"; DNS="$3"; DP="$4"; S="$5"; SHARE="$6"; KILL="$7"; CP="$8"; DUIDS="$9"; DGIDS="${10:-}"; MACS="${11:-}"; { printf 'MODE=%s\n' "$M"; printf 'IPV6=%s\n' "$V6"; printf 'DNS=%s\n' "$DNS"; printf 'DNS_PORT=%s\n' "$DP"; printf 'APP_SCOPE=%s\n' "$S"; printf 'SHARE=%s\n' "$SHARE"; printf 'KILL=%s\n' "$KILL"; printf 'CONTROLLER_PORT=%s\n' "$CP"; printf 'DIRECT_UIDS=%s\n' "$DUIDS"; printf 'DIRECT_GIDS=%s\n' "$DGIDS"; printf 'SHARED_BYPASS_MACS=%s\n' "$MACS";
    printf 'FAKE_IP_POLICY=1\nFAKE_IP_V4=%s\nFAKE_IP_V6=%s\nLAN_RETURN_V4=%s\nLAN_RETURN_V6=%s\n' "$FAKE_IP_V4" "$FAKE_IP_V6" "$LAN_RETURN_V4" "$LAN_RETURN_V6"
    printf 'DNS6_POLICY=%s\n' "${START_DNS6:-redirect}"
    printf 'TCP=%s\nUDP=%s\n' "$START_TCP" "$START_UDP"
    L_TCP=0; L_UDP=0; L_DNS=0
    case "$M" in tproxy) [ "$START_TCP" != 1 ] || L_TCP="$START_TP"; [ "$START_UDP" != 1 ] || L_UDP="$START_TP";;
      enhance) [ "$START_TCP" != 1 ] || L_TCP="$START_RP"; [ "$START_UDP" != 1 ] || L_UDP="$START_TP";;
      redirect) [ "$START_TCP" != 1 ] || L_TCP="$START_RP";; esac
    [ "$DNS" = off ] || L_DNS="$DP"
    printf 'LISTENER_TCP_PORT=%s\nLISTENER_UDP_PORT=%s\nACTIVE_DNS_PORT=%s\n' "$L_TCP" "$L_UDP" "$L_DNS"
  } > "$SESSION.new.$$" && mv -f "$SESSION.new.$$" "$SESSION"; }
watchdog(){
  COREPID="$1"; KILL="$2"; S="$3"; UIDS="$4"; SHARE="$5"; CIDRS="$6"; IFACES="$7"; DUIDS="$8"; DGIDS="${9:-}"; MACS="${10:-}"; WD_GENERATION="${11:-}"
  [ -n "$WD_GENERATION" ] && [ "$WD_GENERATION" = "$(cat "$RUN/generation" 2>/dev/null)" ] || exit 0
  WD_N=0
  while [ "$(cat "$WATCHDOG_PID" 2>/dev/null)" != "$$" ]; do
    WD_N=$((WD_N+1)); [ "$WD_N" -lt 40 ] || exit 0; sleep 0.025
  done
  load_session_fake_ip_policy || exit 0
  printf '%s\n' "$WD_GENERATION" > "$RUN/watchdog.ready.$$" || exit 0
  MISS=0; H_TICK=0
  while [ "$MISS" -lt 3 ]; do
    [ "$WD_GENERATION" = "$(cat "$RUN/generation" 2>/dev/null)" ] || exit 0
    if core_maybe_alive "$COREPID" && kill -0 "$COREPID" >/dev/null 2>&1; then
      MISS=0; H_TICK=$((H_TICK+1))
      if [ "$H_TICK" -ge 6 ]; then H_TICK=0; "$0" repair-network "$COREPID" >/dev/null 2>&1 || true; fi
      sleep 2
    else MISS=$((MISS+1)); sleep 0.20; fi
  done
  acquire_lock || exit 0
  [ "$WD_GENERATION" = "$(cat "$RUN/generation" 2>/dev/null)" ] || exit 0
  # Holding the lock: nothing may cut the capture removal off half-way.
  trap '' TERM INT HUP
  REC=$(cat "$PIDFILE" 2>/dev/null || true)
  if [ "$REC" = "$COREPID" ]; then
    RESULT="network-restore-failed"
    if [ "$KILL" = 1 ]; then
      # Install guards before removing capture. Failure retains the old rules.
      if atomic_kill_guard 4 "$S" "$UIDS" "$SHARE" "$CIDRS" "$IFACES" "$DUIDS" "$DGIDS" "$MACS" && atomic_kill_guard 6 "$S" "$UIDS" "$SHARE" "$CIDRS" "$IFACES" "$DUIDS" "$DGIDS" "$MACS"; then RESULT="killswitch-active"; else RESULT="killswitch-failed"; fi
    elif cleanup_confirmed && restorev6; then
      RESULT="network-restored"; rm -f "$MODEFILE" "$SESSION"
    fi
    rm -f "$PIDFILE"
    date '+%Y-%m-%dT%H:%M:%S%z core exited; '"$RESULT" > "$CRASH_STATE" 2>/dev/null || true
    printf '%s core=%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$COREPID" "$RESULT" >> "$WATCHDOG_LOG" 2>/dev/null || true
  fi
  [ "$(cat "$WATCHDOG_PID" 2>/dev/null)" != "$$" ] || rm -f "$WATCHDOG_PID"
  rm -f "$RUN/watchdog.ready.$$"
}
start_watchdog(){
  stopwatchdog
  WD_GENERATION="$$-$(health_core_birth "$$")"
  printf '%s\n' "$WD_GENERATION" > "$RUN/generation" || return 1
  "$0" watchdog "$@" "$WD_GENERATION" >/dev/null 2>&1 &
  WD_CHILD=$!
  printf '%s\n' "$WD_CHILD" > "$WATCHDOG_PID" || return 1
  WD_N=0
  while [ "$(cat "$RUN/watchdog.ready.$WD_CHILD" 2>/dev/null)" != "$WD_GENERATION" ]; do
    kill -0 "$WD_CHILD" 2>/dev/null || return 1
    WD_N=$((WD_N+1)); [ "$WD_N" -lt 40 ] || return 1; sleep 0.025
  done
}

# The combined owner match must exist before the core is started under the new identity.
probe_core_owner(){
  PCO_BIN="$1"
  "$PCO_BIN" -t mangle -N HETU_PROBE >/dev/null 2>&1 || true; "$PCO_BIN" -t mangle -F HETU_PROBE >/dev/null 2>&1 || true
  "$PCO_BIN" -t mangle -A HETU_PROBE -m owner --uid-owner 0 --gid-owner "$CORE_GROUP_ID" -j RETURN >/dev/null 2>&1; PCO_RC=$?
  "$PCO_BIN" -t mangle -F HETU_PROBE >/dev/null 2>&1 || true; "$PCO_BIN" -t mangle -X HETU_PROBE >/dev/null 2>&1 || true
  return "$PCO_RC"
}
# Decide how the core is started. Leaves CORE_RUNNER empty when the old identity (plain
# root) has to stay: no usable BusyBox setuidgid, no combined owner match, or the user
# asked for the system resolver to be left alone ($BASE/policy/system-dns-direct).
core_identity_prepare(){
  CORE_GID=""; CORE_RUNNER=""; CORE_SPEC=""; SYSTEM_DNS=exempt
  [ ! -f "$BASE/policy/system-dns-direct" ] || return 0
  CI_MAGISK=$(magisk --path 2>/dev/null || true)
  for CI_BB in /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox /data/adb/magisk/busybox "${CI_MAGISK:+$CI_MAGISK/.magisk/busybox}"; do
    [ -n "$CI_BB" ] && [ -x "$CI_BB" ] || continue
    for CI_SPEC in root:net_admin "0:$CORE_GROUP_ID"; do
      CI_IDS="$("$CI_BB" setuidgid "$CI_SPEC" "$CI_BB" id -u 2>/dev/null):$("$CI_BB" setuidgid "$CI_SPEC" "$CI_BB" id -g 2>/dev/null)"
      if [ "$CI_IDS" = "0:$CORE_GROUP_ID" ]; then CORE_RUNNER="$CI_BB"; CORE_SPEC="$CI_SPEC"; break 2; fi
    done
  done
  [ -n "$CORE_RUNNER" ] || return 0
  if ! probe_core_owner xt4; then CORE_RUNNER=""; CORE_SPEC=""; return 0; fi
  if v6supported && has ip6tables && ! probe_core_owner xt6; then CORE_RUNNER=""; CORE_SPEC=""; fi
  return 0
}
core_launch(){
  [ -z "${START_MEM_BYTES:-}" ] || { GOMEMLIMIT="$START_MEM_BYTES"; export GOMEMLIMIT; }
  if [ -n "$CORE_RUNNER" ]; then "$CORE_RUNNER" setuidgid "$CORE_SPEC" "$START_BIN" -d "$RUN" -f "$START_CFG" >>"$LOG" 2>&1 &
  else "$START_BIN" -d "$RUN" -f "$START_CFG" >>"$LOG" 2>&1 & fi
  START_PID=$!
  [ -n "$CORE_RUNNER" ] || return 0
  # setuidgid replaces itself with the core; identity checks wait for that, briefly.
  CL_N=0
  while [ "$CL_N" -lt 40 ]; do
    pidcore "$START_PID" && break
    kill -0 "$START_PID" >/dev/null 2>&1 || break
    sleep 0.025; CL_N=$((CL_N+1))
  done
}
# Trust what the kernel reports for the running core, not what was asked for.
core_identity_confirm(){
  CORE_GID=""; SYSTEM_DNS=exempt
  [ -n "$CORE_RUNNER" ] || return 0
  pidcore "$1" || return 1
  CI_IDS=$(awk -v gid="$CORE_GROUP_ID" '$1=="Uid:" {u=($2==0 && $3==0 && $4==0 && $5==0)} $1=="Gid:" {g=($2==gid && $3==gid && $4==gid && $5==gid)} END {if(u && g) print "verified"}' "/proc/$1/status" 2>/dev/null)
  [ "$CI_IDS" = verified ] || return 1
  CORE_GID="$CORE_GROUP_ID"
  [ "$START_DNS" != off ] || return 0
  case "$START_MODE" in tun|ebpf) ;; *) SYSTEM_DNS=captured;; esac
}

# 高级代理配置 → 资源限制 / 性能模式. Each is applied to the running core on a best-effort
# basis and reported in $RUN/tuning-status; none of them can fail a start once validated.
cpu_mask(){
  # Shell arithmetic only: not every awk on a rooted device has exponentiation.
  CM_MASK=0
  case "$1" in ''|*[!0-9,-]*) return 1;; esac
  OLDIFS=$IFS; IFS=,; set -- $1; IFS=$OLDIFS
  [ "$#" -ge 1 ] || return 1
  for CM_P in "$@"; do
    case "$CM_P" in
      ''|-*|*-|*-*-*) return 1;;
      *-*) CM_A=${CM_P%-*}; CM_B=${CM_P#*-};;
      *) CM_A=$CM_P; CM_B=$CM_P;;
    esac
    case "$CM_A:$CM_B" in 0?*:*|*:0?*|???*:*|*:???*) return 1;; esac
    [ "$CM_A" -le "$CM_B" ] && [ "$CM_B" -le 23 ] || return 1
    CM_C=$CM_A; while [ "$CM_C" -le "$CM_B" ]; do CM_MASK=$((CM_MASK | (1 << CM_C))); CM_C=$((CM_C+1)); done
  done
  [ "$CM_MASK" -gt 0 ] || return 1
  printf '%x\n' "$CM_MASK"
}
mem_bytes(){
  printf '%s\n' "$1" | awk '
    { v=toupper($0); sub(/I?B$/,"",v); u=0
      if(v ~ /^[0-9]+$/){n=v+0;u=1}
      else if(v ~ /^[0-9]+K$/){n=substr(v,1,length(v)-1)+0;u=1024}
      else if(v ~ /^[0-9]+M$/){n=substr(v,1,length(v)-1)+0;u=1048576}
      else if(v ~ /^[0-9]+G$/){n=substr(v,1,length(v)-1)+0;u=1073741824}
      if(u==0) exit 1
      b=n*u; if(b<33554432 || b>17179869184) exit 1
      printf "%.0f\n", b }'
}
tuning_valid(){
  START_CPU_MASK=""; START_MEM_BYTES=""; TUNE_ERROR=""
  if [ -n "$START_CPU" ]; then START_CPU_MASK=$(cpu_mask "$START_CPU") || { TUNE_ERROR="CPU 核心分配格式无效：请填写 0-7 或 0,2,4-6 这样的核心编号（0-23）"; return 1; }; fi
  if [ -n "$START_MEM" ]; then START_MEM_BYTES=$(mem_bytes "$START_MEM") || { TUNE_ERROR="内存限制格式无效：请填写 128M、1G 这样的值，且不低于 32M"; return 1; }; fi
  if [ -n "$START_IO" ]; then case "$START_IO" in [0-7]) ;; *) TUNE_ERROR="磁盘 I/O 权重应为 0-7"; return 1;; esac; fi
  return 0
}
core_tune(){
  CT_PID="$1"; CT_REPORT=""
  if [ "$START_PERF" = 1 ]; then
    CT_OK=1; for CT_T in /proc/"$CT_PID"/task/[0-9]*; do renice -n -10 -p "${CT_T##*/}" >/dev/null 2>&1 || CT_OK=0; done
    if [ "$CT_OK" = 1 ]; then CT_REPORT="${CT_REPORT}priority=applied "; else CT_REPORT="${CT_REPORT}priority=failed "; fi
  fi
  if [ -n "$START_CPU_MASK" ]; then
    CT_OK=1
    if ! taskset -ap "$START_CPU_MASK" "$CT_PID" >/dev/null 2>&1; then
      for CT_T in /proc/"$CT_PID"/task/[0-9]*; do taskset -p "$START_CPU_MASK" "${CT_T##*/}" >/dev/null 2>&1 || CT_OK=0; done
    fi
    if [ "$CT_OK" = 1 ]; then CT_REPORT="${CT_REPORT}cpu=applied:$START_CPU_MASK "; else CT_REPORT="${CT_REPORT}cpu=failed "; fi
  fi
  if [ -n "$START_IO" ]; then
    CT_OK=1; for CT_T in /proc/"$CT_PID"/task/[0-9]*; do ionice -c 2 -n "$START_IO" -p "${CT_T##*/}" >/dev/null 2>&1 || CT_OK=0; done
    if [ "$CT_OK" = 1 ]; then CT_REPORT="${CT_REPORT}io=applied:$START_IO "; else CT_REPORT="${CT_REPORT}io=failed "; fi
  fi
  [ -z "$START_MEM_BYTES" ] || CT_REPORT="${CT_REPORT}memory=applied:$START_MEM_BYTES "
  printf '%s\n' "${CT_REPORT:-none}" > "$RUN/tuning-status" 2>/dev/null || true
}

google_firewall_uids(){
  # Resolve each installed user/profile. Android can reassign app UIDs after reset.
  GF_USERS=$(pm list users 2>/dev/null | sed -n 's/.*UserInfo{\([0-9][0-9]*\):.*/\1/p')
  [ -n "$GF_USERS" ] || GF_USERS=0
  for GF_USER in $GF_USERS; do
    (cmd package list packages -U --user "$GF_USER" 2>/dev/null ||
      pm list packages -U --user "$GF_USER" 2>/dev/null) |
      awk '$1=="package:com.google.android.gms" || $1=="package:com.android.vending" || $1=="package:com.google.android.gsf" {
        for(i=2;i<=NF;i++) if($i ~ /^uid:[0-9]+$/) {sub(/^uid:/,"",$i); if($i>=10000) print $i}
      }'
  done | sort -nu
}
google_firewall_cleanup(){
  # Caller owns the existing Root transaction lock. Never flush a system chain.
  GF_UIDS=$(google_firewall_uids)
  GF_PRESENT=""
  if [ -z "$GF_UIDS" ]; then
    printf 'uids=0 chains= at=%s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" > "$RUN/google-firewall-detail" 2>/dev/null || true
    return 0
  fi
  GF_REMOVED=0; GF_FAILED=0; GF_CHECKED=0
  GF_FILE="$RUN/.google-firewall.$$"
  for GF_TOOL in xt4q xt6q; do
    for GF_CHAIN in fw_INPUT fw_OUTPUT fw_OUTPUT_oplus_dns zte_fw_gms; do
      if ! $GF_TOOL -t filter -S "$GF_CHAIN" > "$GF_FILE" 2>/dev/null; then continue; fi
      GF_CHECKED=$((GF_CHECKED+1))
      case ",$GF_PRESENT," in *",$GF_CHAIN,"*) ;; *) GF_PRESENT="${GF_PRESENT:+$GF_PRESENT,}$GF_CHAIN";; esac
      while IFS= read -r GF_RULE; do
        # Quotes/comments and inverted/range UID matches are deliberately left intact.
        case "$GF_RULE" in *\"*|*\'*) continue;; esac
        if ! printf '%s\n' "$GF_RULE" | awk -v c="$GF_CHAIN" -v uids="$GF_UIDS" '
          BEGIN{split(uids,ids,/\n/);for(i in ids) allowed[ids[i]]=1}
          $1=="-A" && $2==c {
            uid="";target="";inverted=0
            for(i=3;i<=NF;i++) {
              if($i=="!") inverted=1
              if($i=="--uid-owner") uid=$(i+1)
              if($i=="-j") target=$(i+1)
            }
            if(!inverted && uid ~ /^[0-9]+$/ && allowed[uid] && (target=="REJECT" || target=="DROP")) found=1
          }
          END{exit !found}'; then continue; fi
        # Delete the exact specification; line numbers can change under netd.
        case $- in *f*) GF_GLOB_OFF=1;; *) GF_GLOB_OFF=0; set -f;; esac
        set -- $GF_RULE
        shift 2
        if $GF_TOOL -t filter -D "$GF_CHAIN" "$@" 2>/dev/null; then
          GF_REMOVED=$((GF_REMOVED+1))
          printf '%s %s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$GF_TOOL" "$GF_RULE" >> "$RUN/google-firewall.log"
        else GF_FAILED=$((GF_FAILED+1)); fi
        [ "$GF_GLOB_OFF" = 1 ] || set +f
      done < "$GF_FILE"
    done
  done
  rm -f "$GF_FILE"
  printf 'checked=%s removed=%s failed=%s\n' "$GF_CHECKED" "$GF_REMOVED" "$GF_FAILED" > "$RUN/google-firewall-status"
  # Which vendor chains this device has at all: without any of them the switch does nothing.
  printf 'uids=%s chains=%s at=%s\n' "$(printf '%s\n' "$GF_UIDS" | grep -c .)" "$GF_PRESENT" "$(date '+%Y-%m-%dT%H:%M:%S%z')" > "$RUN/google-firewall-detail" 2>/dev/null || true
  if [ -f "$RUN/google-firewall.log" ]; then
    tail -n 60 "$RUN/google-firewall.log" > "$RUN/.google-log.$$" && mv "$RUN/.google-log.$$" "$RUN/google-firewall.log"
  fi
}
google_firewall_maintain(){ (
  trap 'release_lock' EXIT
  GF_PID="${1:-}"
  [ "$(sed -n 's/^GOOGLE_FIREWALL_CLEAN=//p' "$SESSION" 2>/dev/null)" = 1 ] || exit 0
  [ "$GF_PID" = "$(cat "$PIDFILE" 2>/dev/null)" ] && pidcore "$GF_PID" && kill -0 "$GF_PID" 2>/dev/null || exit 0
  monotonic_seconds || exit 0
  GF_NOW="$MONO_SECONDS"; GF_LAST=$(cat "$RUN/google-firewall-at" 2>/dev/null || true)
  case "$GF_LAST" in ''|*[!0-9]*) ;; *)
    if [ "$GF_NOW" -ge "$GF_LAST" ] && [ $((GF_NOW-GF_LAST)) -lt 60 ]; then exit 0; fi;;
  esac
  acquire_lock || exit 0
  [ "$GF_PID" = "$(cat "$PIDFILE" 2>/dev/null)" ] && pidcore "$GF_PID" && kill -0 "$GF_PID" 2>/dev/null || exit 0
  [ "$(sed -n 's/^GOOGLE_FIREWALL_CLEAN=//p' "$SESSION" 2>/dev/null)" = 1 ] || exit 0
  printf '%s\n' "$GF_NOW" > "$RUN/google-firewall-at"
  google_firewall_cleanup
); }

install_capture_rules(){
  start_stage "install-ipv4-tproxy"
  install_mangle4 "$START_TP" "$START_MODE" "$START_TCP" "$START_UDP" "$START_DNS" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" "$START_DIRECT_UIDS" "$START_DIRECT_GIDS" "$START_SHARED_MACS" || { fail "IPv4 TPROXY 规则安装失败，启动未完成，请检查停止状态"; }
  start_stage "install-ipv4-redirect"
  install_redirect4 "$START_RP" "$START_MODE" "$START_TCP" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" "$START_DIRECT_UIDS" "$START_DIRECT_GIDS" "$START_SHARED_MACS" || { fail "IPv4 Redirect 规则安装失败，启动未完成，请检查停止状态"; }
  start_stage "install-ipv4-dns"
  if [ "$START_MODE" != tun ] && [ "$START_MODE" != ebpf ] && [ "$START_DNS" != off ]; then install_dns_redirect4 "$START_DP" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_IFACES" "$START_SHARED_MACS" || { fail "IPv4 DNS 劫持安装失败，启动未完成，请检查停止状态"; }; fi
  start_stage "install-udp-leak-guard"
  if [ "$START_UDP" = 1 ]; then
    case "$START_MODE" in
      tproxy|enhance)
        install_udp_leak_guard4 "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" "$START_DIRECT_UIDS" "$START_DIRECT_GIDS" "$START_SHARED_MACS" || { fail "IPv4 UDP 防裸连规则安装失败，启动未完成，请检查停止状态"; }
        if [ "$START_V6" = enable ]; then install_udp_leak_guard6 "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" "$START_DIRECT_UIDS" "$START_DIRECT_GIDS" "$START_SHARED_MACS" || { fail "IPv6 UDP 防裸连规则安装失败，启动未完成，请检查停止状态"; }; fi
        ;;
    esac
  fi
  start_stage "install-ipv4-quic"
  [ "$START_QUIC" = 0 ] || install_quic4 "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" "$START_DIRECT_UIDS" "$START_DIRECT_GIDS" "$START_SHARED_MACS" || { fail "IPv4 QUIC 策略安装失败，启动未完成，请检查停止状态"; }
  start_stage "install-ipv6"
  if [ "$START_V6" = enable ]; then
    install_mangle6 "$START_TP" "$START_MODE" "$START_TCP" "$START_UDP" "$START_DNS" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" "$START_DIRECT_UIDS" "$START_DIRECT_GIDS" "$START_SHARED_MACS" || { fail "IPv6 TPROXY 规则安装失败，启动未完成，请检查停止状态"; }
    install_redirect6 "$START_RP" "$START_MODE" "$START_TCP" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" "$START_DIRECT_UIDS" "$START_DIRECT_GIDS" "$START_SHARED_MACS" || { fail "IPv6 Redirect 规则安装失败，启动未完成，请检查停止状态"; }
    if [ "$START_MODE" != tun ] && [ "$START_MODE" != ebpf ] && [ "$START_DNS" != off ]; then install_dns_redirect6 "$START_DP" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_IFACES" "$START_SHARED_MACS" || { fail "IPv6 DNS 劫持安装失败，启动未完成，请检查停止状态"; }; fi
    [ "$START_QUIC" = 0 ] || install_quic6 "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" "$START_DIRECT_UIDS" "$START_DIRECT_GIDS" "$START_SHARED_MACS" || { fail "IPv6 QUIC 策略安装失败，启动未完成，请检查停止状态"; }
  elif [ "$START_V6" = strict ]; then install_v6_strict "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" "$START_DIRECT_UIDS" "$START_DIRECT_GIDS" "$START_SHARED_MACS" || { fail "严格 IPv4 防泄漏规则安装失败，启动未完成，请检查停止状态"; }; fi

}
# Undo a refused batch commit before the one-by-one fallback: own chains/hooks (Kill Switch
# guard kept as configured) and this session's policy rule, which route4/route6 add again.
capture_unwind(){
  # Only the chains install_capture_rules creates; the IPv6-disable guard installed before
  # the core launch and the Kill Switch guard stay untouched.
  SWEEP_ONLY="$MOUT $MPRE $NOUT $NPRE $DNSOUT $DNSPRE $WROUT $WRFWD $QUICOUT $QUICFWD"
  [ "$START_V6" != strict ] || SWEEP_ONLY="$SWEEP_ONLY $V6OUT $V6FWD"
  cleanup_snapshot_begin; sweep_rules; CLEAN_SNAPSHOT_ACTIVE=0
  if [ -n "$MARK" ] && [ -n "$TABLE" ] && [ -n "$PREF" ]; then
    for CU_F in 4 6; do
      CU_N=0
      while [ "$CU_N" -lt 4 ] && ip -"$CU_F" rule del pref "$PREF" fwmark "$MARK/$MASK" table "$TABLE" >/dev/null 2>&1; do CU_N=$((CU_N+1)); done
    done
  fi
  for CU_BIN in xt4 xt6; do
    if [ "$CU_BIN" = xt6 ] && ! has ip6tables; then continue; fi
    for CU_T in mangle nat filter; do
      CU_RULES=$(cleanup_snapshot_read "$CU_BIN" "$CU_T") || { SWEEP_ONLY=""; return 1; }
      for CU_C in $SWEEP_ONLY; do
        if printf '%s\n' "$CU_RULES" | grep -Fxq -- "-N $CU_C"; then SWEEP_ONLY=""; return 1; fi
      done
    done
  done
  SWEEP_ONLY=""
}

start(){
  START_BIN="$1"; START_CFG="$2"; START_MODE="$3"; START_TP="$4"; START_RP="$5"; START_V6="$6"; START_TCP="$7"; START_UDP="$8"; START_DNS="$9"; START_QUIC="${10}"; START_DP="${11}"; START_CP="${12}"; START_SCOPE="${13}"; START_UIDS="${14}"; START_SHARE="${15}"; START_KILL="${16}"; START_CIDRS="${17}"; START_IFACES="${18}"; START_DIRECT_UIDS="${19}"; START_PREVALIDATED="${20:-0}"; START_FAST_CAPS="${21:-0}"; START_DIRECT_GIDS="${22:-}"; START_SHARED_MACS="${23:-}"
  START_VENDOR_CLEAN="${30:-0}"
  START_DNS_TCP="${24:-1}"; START_DNS_UDP="${25:-1}"; START_PERF="${26:-0}"; START_CPU="${27:-}"; START_MEM="${28:-}"; START_IO="${29:-}"
  bool "$START_DNS_TCP" && bool "$START_DNS_UDP" && bool "$START_PERF" || fail "扩展开关必须是 0 或 1"
  tuning_valid || fail "$TUNE_ERROR"
  DNS_PROTOS=""; [ "$START_DNS_TCP" = 0 ] || DNS_PROTOS=tcp; [ "$START_DNS_UDP" = 0 ] || DNS_PROTOS="${DNS_PROTOS:+$DNS_PROTOS }udp"
  # Neither protocol captured is the same as DNS takeover switched off.
  [ -n "$DNS_PROTOS" ] || START_DNS=off
  mkdir -p "$RUN" || fail "无法创建运行目录"
  acquire_lock || fail "另一个代理网络事务正在执行，请稍后重试"
  if [ -n "${HETU_BOOT_RESTORE_ID:-}" ]; then
    BOOT_NOW=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null || true)
    [ -n "$BOOT_NOW" ] && [ "$HETU_BOOT_RESTORE_ID" = "$BOOT_NOW" ] && [ -f "$BASE/boot/enabled" ] &&
      [ "$BOOT_NOW" != "$(cat "$BASE/boot/stopped-boot" 2>/dev/null || true)" ] || fail "开机恢复已取消"
    # A manual/app start may have won while the boot worker waited for this lock.
    BOOT_PID=$(cat "$PIDFILE" 2>/dev/null || true)
    if pidcore "$BOOT_PID" && kill -0 "$BOOT_PID" 2>/dev/null; then
      health_collect
      if [ "$H_STATE" = healthy ]; then ok "Root 代理已在运行"; return 0; fi
    fi
    recovery_claim "$HETU_BOOT_RESTORE_ID" || fail "本次开机自动恢复预算已耗尽或已撤销"
  fi
  if [ -n "${HETU_AUTOMATIC_RECOVERY_ID:-}" ] && [ -z "${HETU_BOOT_RESTORE_ID:-}" ]; then
    recovery_claim "$HETU_AUTOMATIC_RECOVERY_ID" || fail "本次自动恢复预算已耗尽或已撤销"
  fi
  transaction_begin || fail "无法建立启动事务截止时间或请求已撤销"
  : > "$START_TIMING"
  rm -f "$START_ERROR"; start_stage "preflight"
  if [ "$START_FAST_CAPS" != 1 ]; then
    preflight "$START_MODE" "$START_TP" "$START_RP" "$START_V6" "$START_TCP" "$START_UDP" "$START_DNS" "$START_QUIC" "$START_DP" "$START_CP" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_KILL" "$START_CIDRS" "$START_IFACES" "$START_DIRECT_UIDS" "$START_DIRECT_GIDS" "$START_SHARED_MACS" >/dev/null
  else
    start_stage "preflight-cached"
  fi
  start_stage "ipv6-dns-capability"
  select_dns6_policy
  [ -x "$START_BIN" ] || fail "核心文件不存在或不可执行"; [ -r "$START_CFG" ] || fail "启动配置不存在"; mkdir -p "$RUN" || fail "无法创建运行目录"; if [ "$START_PREVALIDATED" != 1 ]; then validatecfg "$START_BIN" "$START_CFG" || fail "Mihomo 配置校验失败，当前网络未被接管"; fi
  load_start_fake_ip_policy "$START_CFG" || fail "启动配置的 fake-IP 路由投影无效，当前网络未被接管"
  acquire_lock || fail "另一个代理网络事务正在执行，请稍后重试"
  start_stage "core-identity"
  core_identity_prepare
  if [ "$START_KILL" = 1 ] && [ -z "$CORE_RUNNER" ]; then
    fail "Kill Switch 启动缺少可验证的隔离核心身份，保留已有网络接管"
  fi
  START_MUTATED=1
  if [ "$START_KILL" = 1 ]; then
    START_BOOTSTRAP=1; PRESERVE_KILL=1
    atomic_kill_guard 4 "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" "$START_DIRECT_UIDS" "$START_DIRECT_GIDS" "$START_SHARED_MACS" || fail "IPv4 启动保护安装失败"
    atomic_kill_guard 6 "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" "$START_DIRECT_UIDS" "$START_DIRECT_GIDS" "$START_SHARED_MACS" || fail "IPv6 启动保护安装失败"
  fi
  start_stage "cleanup-network"
  stopwatchdog; cleanup; restorev6 || fail "上次 IPv6 状态尚未恢复，请重试停止后再启动"
  start_stage "stop-old-core"
  stopcore || fail "旧核心未确认退出"; rm -f "$CRASH_STATE" "$SESSION"
  start_stage "check-ports"
  check_start_ports "$START_MODE" "$START_TP" "$START_RP" "$START_TCP" "$START_UDP" "$START_DNS" "$START_DP" "$START_CP"
  markused "$BYPASS_MARK" && fail "安全出站 mark 已被其他网络规则占用，未接管网络"
  NEED_TP=0; case "$START_MODE" in tproxy) if [ "$START_TCP" = 1 ] || [ "$START_UDP" = 1 ]; then NEED_TP=1; fi;; enhance) [ "$START_UDP" = 1 ] && NEED_TP=1;; esac; if [ "$START_DNS" = tproxy ] && [ "$START_MODE" != tun ] && [ "$START_MODE" != ebpf ]; then NEED_TP=1; fi
  if [ "$NEED_TP" = 1 ]; then allocnet || { fail "找不到安全的 fwmark/路由表/规则优先级，已保持直连"; }; fi
  if [ "$START_V6" = disable ] && v6supported; then
    install_v6_disable "$START_SHARE" || { fail "IPv6 禁用保护安装失败，未启动代理"; }
    # No all/default/rmnet sysctl writes: netd owns the physical network.
  fi

  mkdir -p "$RUN/rules" "$RUN/proxy_provider" "$RUN/ruleset" "$RUN/ui" || { fail "无法创建 Mihomo 运行缓存目录"; }
  start_stage "launch-core"
  : > "$LOG" || fail "无法准备核心日志"; core_launch || fail "核心启动失败"; printf '%s\n' "$START_PID" > "$PIDFILE" || fail "无法记录核心PID"; printf '%s\n' "$START_MODE" > "$MODEFILE" || fail "无法记录运行模式"; write_session "$START_MODE" "$START_V6" "$START_DNS" "$START_DP" "$START_SCOPE" "$START_SHARE" "$START_KILL" "$START_CP" "$START_DIRECT_UIDS" "$START_DIRECT_GIDS" "$START_SHARED_MACS" || fail "无法记录运行会话"
  core_identity_confirm "$START_PID" || fail "核心隔离身份验证失败"
  start_stage "wait-listeners"
  wait_ready "$START_PID" "$START_MODE" "$START_TP" "$START_RP" "$START_TCP" "$START_UDP" "$START_DNS" "$START_DP" "$START_CP"; READY_RC=$?
  if [ "$READY_RC" -ne 0 ]; then
    if [ "$READY_RC" -eq 2 ]; then READY_MSG="Mihomo 启动后提前退出，请查看核心日志"; else READY_MSG="Mihomo 初始化超过 90 秒，代理入站/DNS/API 监听仍未就绪；首次加载大量远程订阅或规则时请检查网络与核心日志"; fi
    fail "$READY_MSG"
  fi

  if [ "$START_MODE" = ebpf ]; then
    sleep 0.25
    if grep -Ei '(^|[^a-z])(e?bpf|bpf)([^a-z]|$)' "$LOG" 2>/dev/null | tail -n 20 | grep -Eqi 'error|failed|failure|not supported|operation not permitted|permission denied|attach.*fail'; then
      fail "eBPF attach 失败；当前内核/接口不兼容，请改用 TUN 或 TPROXY"
    fi
  fi

  core_tune "$START_PID"
  # Capture rules are recorded and committed in one iptables-restore per family/table. If a
  # commit is refused (old iptables-restore, missing match module, ...) the partial state is
  # swept and the same rules are installed one by one, exactly as before.
  start_stage "install-capture-batch"
  xt_batch_begin || fail "无法准备防火墙批量规则"
  XT_BATCH=1; install_capture_rules; XT_BATCH=0
  if ! xt_batch_commit; then
    start_stage "install-capture-fallback"
    capture_unwind || fail "批量规则提交失败且无法撤销部分规则，请检查停止状态"
    install_capture_rules
  fi
  if [ "$START_V6" = disable ] && v6supported && [ "$START_MODE" != tun ] && [ "$START_MODE" != ebpf ] && [ "$START_DNS" != off ]; then
    install_disabled_dns6 || { fail "IPv6 DNS 防泄漏安装失败，未放行直连 DNS"; }
  fi
  start_stage "install-dot-guard"
  if [ "$SYSTEM_DNS" = captured ]; then
    PRIVATE_DNS=$(settings get global private_dns_mode 2>/dev/null | head -n 1)
    case "$PRIVATE_DNS" in off|opportunistic|hostname) ;; *) PRIVATE_DNS=unknown;; esac
    if [ "$PRIVATE_DNS" != hostname ]; then
      # Optional: a kernel without tcp-reset REJECT simply runs without the guard.
      if install_dot_guard xt4; then DOT_GUARD=1; else remove_dot_guard xt4; fi
      if [ "$DOT_GUARD" = 1 ] && { [ "$START_V6" = enable ] || [ "$START_V6" = disable ]; } && v6supported && has ip6tables; then
        install_dot_guard xt6 || remove_dot_guard xt6
      fi
    fi
  fi
  # Finish the immutable session before its integrity snapshot. Updating it after
  # health_record would invalidate every status check and disable owned-rule repair.
  printf 'CORE_GID=%s\nSYSTEM_DNS=%s\nDOT_GUARD=%s\nPRIVATE_DNS=%s\nDNS_PROTOS=%s\n' "$CORE_GID" "$SYSTEM_DNS" "$DOT_GUARD" "$PRIVATE_DNS" "$DNS_PROTOS" >> "$SESSION" || { fail "无法记录 DNS 接管状态，已停止本次启动"; }
  printf 'GOOGLE_FIREWALL_CLEAN=%s\n' "$START_VENDOR_CLEAN" >> "$SESSION" || { fail "无法记录 Google 防火墙设置，已停止本次启动"; }
  transaction_current || fail "启动事务已撤销"
  if [ "$START_KILL" = 1 ]; then
    revoke_bootstrap || fail "无法撤销临时核心启动例外"
    unhook xt4 filter OUTPUT "$KOUT"; unhook xt4 filter FORWARD "$KFWD"
    if has ip6tables; then unhook xt6 filter OUTPUT "$KOUT"; unhook xt6 filter FORWARD "$KFWD"; fi
    for GUARD_BIN in xt4 xt6; do
      if [ "$GUARD_BIN" = xt6 ] && ! has ip6tables; then continue; fi
      GUARD_RULES=$("$GUARD_BIN" -t filter -S) || fail "启动保护清理状态未知"
      if printf '%s\n' "$GUARD_RULES" | grep -Eq '(HETU_KOUT|HETU_KFWD)'; then fail "启动保护未完全撤销"; fi
    done
    PRESERVE_KILL=0
  fi
  # Record exactly what this session installed, not mutable app preferences.
  health_record || { fail "无法记录网络完整性基线，已停止本次启动"; }
  start_stage "start-watchdog"
  rm -f "$RUN/google-firewall-at"
  if [ "$START_VENDOR_CLEAN" = 1 ]; then google_firewall_cleanup; fi
  start_watchdog "$START_PID" "$START_KILL" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" "$START_DIRECT_UIDS" "$START_DIRECT_GIDS" "$START_SHARED_MACS" || { fail "运行守护未确认就绪，已中止启动"; }
  transaction_current || fail "启动事务已撤销"
  rm -f "$START_ERROR"; start_stage "running"
  START_ACTIVE=0
  DESC="systemDns=$SYSTEM_DNS,tcp=$START_TCP,udp=$START_UDP,dns=$START_DNS,ipv6=$START_V6,scope=$START_SCOPE,share=$START_SHARE,kill=$START_KILL,quicBlock=$START_QUIC,directUids=$START_DIRECT_UIDS,directGids=$START_DIRECT_GIDS,sharedMacs=$START_SHARED_MACS"
  if [ -n "$MARK" ]; then ok "Root $START_MODE 已启动（$DESC，mark=$MARK，table=$TABLE）"; else ok "Root $START_MODE 已启动（$DESC）"; fi
}

# Self-heal for a core that is gone while its capture rules stay (watchdog killed with the
# App, a stop/rollback SIGKILLed half-way, a lost net.state): the phone would otherwise
# have no network until a manual cleanup. Never for a Kill Switch session, never while a
# transaction or the session's watchdog is still alive, and only when a hook really exists.
watchdog_alive(){
  WA_PID=$(cat "$WATCHDOG_PID" 2>/dev/null || true)
  case "$WA_PID" in ''|*[!0-9]*) return 1;; esac
  kill -0 "$WA_PID" >/dev/null 2>&1 || return 1
  WA_CMD=$(tr '\000' '\n' < "/proc/$WA_PID/cmdline" 2>/dev/null || true)
  printf '%s\n' "$WA_CMD" | awk -v s="$BASE/hetu-root.sh" '$0==s {if(getline>0 && $0=="watchdog") found=1} END {exit !found}'
}
capture_hooks_present(){
  # $@: tables to scan (default all three).
  [ "$#" -gt 0 ] || set -- mangle nat filter
  for CH_BIN in xt4q xt6q; do
    if [ "$CH_BIN" = xt6q ] && ! has ip6tables; then continue; fi
    for CH_T in "$@"; do
      CH_RULES=$("$CH_BIN" -t "$CH_T" -S 2>/dev/null) || continue
      if printf '%s\n' "$CH_RULES" | awk '$1=="-A" && $2 !~ /^(HETU|BICHEN)_/ { for (i = 3; i < NF; i++) if (($i == "-j" || $i == "-g") && $(i+1) ~ /^(HETU|BICHEN)_/ && $(i+1) !~ /^HETU_K(OUT|FWD)$/) found = 1 } END { exit !found }'; then return 0; fi
    done
  done
  return 1
}
status_heal_body(){
  PRESERVE_KILL=0; HB_FAILED=0
  stopwatchdog
  cleanup_confirmed || HB_FAILED=1
  restorev6 || HB_FAILED=1
  [ "$HB_FAILED" = 0 ] || return 1
  rm -f "$MODEFILE" "$SESSION" "$PIDFILE"
  date '+%Y-%m-%dT%H:%M:%S%z core exited; network-restored by status self-heal' > "$CRASH_STATE" 2>/dev/null || true
  printf '%s status self-heal: core absent, capture rules removed\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" >> "$WATCHDOG_LOG" 2>/dev/null || true
}
status_self_heal(){
  [ "$KILLV" != 1 ] && [ "$K4" = false ] && [ "$K6" = false ] || return 1
  [ -d "$RUN" ] || return 1
  ! watchdog_alive || return 1
  # The -C probes status() already ran cover mangle/nat and the IPv6 guard; only the filter
  # guards (UDP leak, QUIC, DoT) still need a snapshot, so an idle status stays cheap.
  SHH_HOOK=false
  for SHH_F in "$M4O" "$M4P" "$N4O" "$N4P" "$D4O" "$D4P" "$M6O" "$M6P" "$N6O" "$N6P" "$D6O" "$D6P" "$STRICT6"; do [ "$SHH_F" != true ] || SHH_HOOK=true; done
  [ "$SHH_HOOK" = true ] || [ -f "$IPV6_STATE" ] || capture_hooks_present filter || return 1
  try_lock_once || return 1
  SHH_PID=$(cat "$PIDFILE" 2>/dev/null || true)
  if { core_maybe_alive "$SHH_PID" && kill -0 "$SHH_PID" >/dev/null 2>&1; } || findcorepid >/dev/null 2>&1; then release_lock; return 1; fi
  shield_run status_heal_body; SHH_RC=$?
  release_lock
  return "$SHH_RC"
}

status(){
  root
  STATUS_RUNNING=false; STATUS_PID=0
  if [ -f "$PIDFILE" ]; then
    X=$(cat "$PIDFILE" 2>/dev/null || true)
    case "$X" in ''|*[!0-9]*) ;; *) if core_maybe_alive "$X" && kill -0 "$X" >/dev/null 2>&1; then STATUS_RUNNING=true; STATUS_PID="$X"; fi;; esac
  fi
  if [ "$STATUS_RUNNING" = false ]; then
    RECOVER_PID=$(findcorepid 2>/dev/null || true)
    case "$RECOVER_PID" in ''|*[!0-9]*) ;; *) STATUS_RUNNING=true; STATUS_PID="$RECOVER_PID"; printf '%s\n' "$RECOVER_PID" > "$PIDFILE" 2>/dev/null || true;; esac
  fi

  STATUS_MODE=$(cat "$MODEFILE" 2>/dev/null || echo none)
  SCOPEV=$(sed -n 's/^APP_SCOPE=//p' "$SESSION" 2>/dev/null | head -n 1)
  DIRECTV=$(sed -n 's/^DIRECT_UIDS=//p' "$SESSION" 2>/dev/null | head -n 1)
  DIRECTGIDV=$(sed -n 's/^DIRECT_GIDS=//p' "$SESSION" 2>/dev/null | head -n 1)
  SHAREMACV=$(sed -n 's/^SHARED_BYPASS_MACS=//p' "$SESSION" 2>/dev/null | head -n 1)
  SHAREV=$(sed -n 's/^SHARE=//p' "$SESSION" 2>/dev/null | head -n 1)
  KILLV=$(sed -n 's/^KILL=//p' "$SESSION" 2>/dev/null | head -n 1)
  DNSV=$(sed -n 's/^DNS=//p' "$SESSION" 2>/dev/null | head -n 1); [ -n "$DNSV" ] || DNSV=off
  DNS6POLICY=$(sed -n 's/^DNS6_POLICY=//p' "$SESSION" 2>/dev/null | head -n 1)
  case "$DNS6POLICY" in redirect|blocked-no-nat|blocked-no-redirect|off|core) ;; *) DNS6POLICY=unknown;; esac
  DPV=$(sed -n 's/^DNS_PORT=//p' "$SESSION" 2>/dev/null | head -n 1); case "$DPV" in ''|*[!0-9]*) DPV=1053;; esac
  IPV6V=$(sed -n 's/^IPV6=//p' "$SESSION" 2>/dev/null | head -n 1); [ -n "$IPV6V" ] || IPV6V=enable
  SYSDNSV=$(sed -n 's/^SYSTEM_DNS=//p' "$SESSION" 2>/dev/null | head -n 1); case "$SYSDNSV" in captured|exempt) ;; *) SYSDNSV=unknown;; esac
  COREGIDV=$(sed -n 's/^CORE_GID=//p' "$SESSION" 2>/dev/null | head -n 1); case "$COREGIDV" in *[!0-9]*) COREGIDV='';; esac
  DOTV=false; [ "$(sed -n 's/^DOT_GUARD=//p' "$SESSION" 2>/dev/null | head -n 1)" != 1 ] || DOTV=true
  PDNSV=$(sed -n 's/^PRIVATE_DNS=//p' "$SESSION" 2>/dev/null | head -n 1); case "$PDNSV" in off|opportunistic|hostname) ;; *) PDNSV=unknown;; esac
  GFV=$(head -n 1 "$RUN/google-firewall-status" 2>/dev/null | tr -cd 'a-z0-9= '); GFDV=$(head -n 1 "$RUN/google-firewall-detail" 2>/dev/null | tr -cd 'A-Za-z0-9=_,:+ -')
  TUNEV=$(head -n 1 "$RUN/tuning-status" 2>/dev/null | tr -cd 'a-z0-9=: ')
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
  STRICT6=false
  if has ip6tables && xt6q -t filter -C OUTPUT -j "$V6OUT" >/dev/null 2>&1; then
    STRICT6=true
    [ "$SHAREV" != 1 ] || xt6q -t filter -C FORWARD -j "$V6FWD" >/dev/null 2>&1 || STRICT6=false
  fi
  STATUS_HEALED=false
  if [ "$STATUS_RUNNING" = false ] && status_self_heal; then
    STATUS_HEALED=true; STATUS_MODE=none
    M4O=false; M4P=false; N4O=false; N4P=false; D4O=false; D4P=false
    M6O=false; M6P=false; N6O=false; N6P=false; D6O=false; D6P=false; STRICT6=false
  fi

  MODE4=false; MODE6=false
  case "$STATUS_MODE" in
    tproxy) [ "$M4O" = true ] && [ "$M4P" = true ] && MODE4=true; [ "$M6O" = true ] && [ "$M6P" = true ] && MODE6=true;;
    enhance)
      MODE4=true; MODE6=true
      if grep -qx 'UDP=1' "$SESSION"; then [ "$M4O" = true ] && [ "$M4P" = true ] || MODE4=false; [ "$M6O" = true ] && [ "$M6P" = true ] || MODE6=false; fi
      if grep -qx 'TCP=1' "$SESSION"; then [ "$N4O" = true ] || MODE4=false; [ "$N6O" = true ] || MODE6=false; fi;;
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
        if v6supported && { [ "$IPV6V" = enable ] || [ "$IPV6V" = disable ]; }; then
          case "$DNS6POLICY" in
            blocked-no-nat|blocked-no-redirect)
              DNS6=false
              if v6_disable_guard_ready "$SHAREV" &&
                 xt6q -t filter -C "$V6OUT" -p tcp --dport 53 -j REJECT >/dev/null 2>&1 &&
                 xt6q -t filter -C "$V6OUT" -p udp --dport 53 -j REJECT >/dev/null 2>&1; then DNS6=true; fi;;
            *) DNS6=$D6O; [ "$SHAREV" != 1 ] || [ "$D6P" = true ] || DNS6=false;;
          esac
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
  V6OFF=false; [ -f "$IPV6_STATE" ] && v6disabled && V6OFF=true
  DISABLE6=false
  case "$IPV6V" in
    enable) if v6supported; then IPV6OK=$MODE6; fi;;
    strict) if v6supported; then IPV6OK=$STRICT6; fi;;
    disable)
      if v6supported; then
        v6_disable_guard_ready "$SHAREV" && DISABLE6=true
        # The firewall guard is the effective no-leak state. V6OFF separately
        # reports whether Android also accepted the best-effort sysctl shutdown.
        IPV6OK=$DISABLE6
      else DISABLE6=true; V6OFF=true; fi
      ;;
    bypass) IPV6OK=true;;
  esac

  WD=false
  W=$(cat "$WATCHDOG_PID" 2>/dev/null || true)
  case "$W" in ''|*[!0-9]*) ;; *) kill -0 "$W" >/dev/null 2>&1 && WD=true;; esac
  STALE=false
  if [ "$STATUS_RUNNING" = false ] && { [ "$M4O" = true ] || [ "$N4O" = true ] || [ "$D4O" = true ] || [ "$M6O" = true ] || [ "$N6O" = true ] || [ "$D6O" = true ] || [ "$K4" = true ] || [ "$K6" = true ] || [ "$STRICT6" = true ] || [ -f "$IPV6_STATE" ]; }; then STALE=true; fi

  health_collect
  HEALTH=false
  if [ "$STATUS_RUNNING" = true ] && [ "$H_STATE" = healthy ] && [ "$WD" = true ]; then HEALTH=true; fi
  SM=""; ST=""; if loadnet >/dev/null 2>&1; then SM="$MARK"; ST="$TABLE"; fi
  SP=$(state_value PREF 2>/dev/null || true)
  printf '{"ok":true,"runtimeSchema":4,"networkIntegrity":"%s","networkFault":"%s","running":%s,"pid":%s,"mode":"%s","ipv4Rules":%s,"ipv6Rules":%s,"ipv6Mode":"%s","ipv6DisableGuard":%s,"dnsMode":"%s","ipv6DnsPolicy":"%s","dnsIpv4Rule":%s,"dnsIpv6Rule":%s,"dnsListenerReady":%s,"dataPlaneHealthy":%s,"killSwitchActive":%s,"ipv6DisabledByHetu":%s,"watchdog":%s,"recoveredStaleRules":%s,"staleRules":%s,"mark":"%s","table":"%s","pref":"%s","controllerPort":%s,"appScope":"%s","directUidRanges":"%s","directGidRanges":"%s","sharedBypassMacs":"%s","sharedNetwork":"%s","killSwitchRequested":"%s","systemDns":"%s","coreGroup":"%s","dotGuard":%s,"privateDns":"%s","vendorFirewall":"%s","vendorFirewallDetail":"%s","tuning":"%s","log":"%s","configCheckLog":"%s"}\n' "$H_STATE" "$H_REASON" "$STATUS_RUNNING" "$STATUS_PID" "$STATUS_MODE" "$IPV4OK" "$IPV6OK" "$IPV6V" "$DISABLE6" "$DNSV" "$DNS6POLICY" "$DNS4" "$DNS6" "$DNSREADY" "$HEALTH" "$([ "$K4" = true ] || [ "$K6" = true ] && echo true || echo false)" "$V6OFF" "$WD" "$STATUS_HEALED" "$STALE" "$SM" "$ST" "$SP" "$CPV" "$SCOPEV" "$DIRECTV" "$DIRECTGIDV" "$SHAREMACV" "$SHAREV" "$KILLV" "$SYSDNSV" "$COREGIDV" "$DOTV" "$PDNSV" "$GFV" "$GFDV" "$TUNEV" "$LOG" "$CHECKLOG"
}

# Session-bound network integrity. No remote reachability failure restarts the core.
# iptables-restore --noflush commits only the named Hetu chains, never netd tables.
health_owned(){
  awk '($1=="-N" || $1=="-A") && $2 ~ /^HETU_(MOUT|MPRE|NOUT|NPRE|DNSOUT|DNSPRE|DOTOUT|QUICOUT|QUICFWD|WROUT|WRFWD|V6OUT|V6FWD)$/ {print;next}
       $1=="-A" && $2 ~ /^(OUTPUT|PREROUTING|FORWARD)$/ && NF==4 && $3=="-j" && $4 ~ /^HETU_(MOUT|MPRE|NOUT|NPRE|DNSOUT|DNSPRE|DOTOUT|QUICOUT|QUICFWD|WROUT|WRFWD|V6OUT|V6FWD)$/ {print}'
}
health_record(){ (
  H_DIR="$RUN/network-manifest"; H_TMP="$H_DIR.new.$$"
  rm -rf "$H_TMP"; mkdir -p "$H_TMP" || exit 1
  cp "$SESSION" "$H_TMP/session" || exit 1
  cat "$PIDFILE" > "$H_TMP/pid" || exit 1
  [ ! -r "$NET_STATE" ] || cp "$NET_STATE" "$H_TMP/net" || exit 1
  H_IPV6=$(sed -n 's/^IPV6=//p' "$SESSION")
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
      H_RAW=$("xt${H_F}" -t "$H_T" -S 2>/dev/null) || { rm -rf "$H_TMP"; exit 1; }
      printf '%s\n' "$H_RAW" | health_owned > "$H_TMP/$H_F-$H_T"
    done
  done
  # Never record an incomplete successful start as a healthy baseline.
  case "$START_MODE" in
    tproxy|enhance)
      if [ "$START_UDP" = 1 ] || { [ "$START_MODE" = tproxy ] && [ "$START_TCP" = 1 ]; }; then
        grep -q -- '^-A PREROUTING -j HETU_MPRE$' "$H_TMP/4-mangle" || { rm -rf "$H_TMP"; exit 1; }
        [ -s "$H_TMP/net" ] || { rm -rf "$H_TMP"; exit 1; }
      fi;;
  esac
  (cd "$H_TMP" && { cksum [46]-* session pid; [ ! -f net ] || cksum net; }) > "$H_TMP/checksums" || { rm -rf "$H_TMP"; exit 1; }
  rm -rf "$H_DIR"; mv "$H_TMP" "$H_DIR"
); }
health_manifest_valid(){
  H_DIR="${H_BASELINE_DIR:-$RUN/network-manifest}"
  [ -r "$H_DIR/session" ] && [ -r "$H_DIR/pid" ] || return 1
  H_SUM=$(cd "$H_DIR" && { cksum [46]-* session pid; [ ! -f net ] || cksum net; }) || return 1
  [ "$H_SUM" = "$(cat "$H_DIR/checksums" 2>/dev/null)" ]
}
health_session_current(){
  health_manifest_valid || return 1
  cmp -s "$H_DIR/session" "$SESSION" && cmp -s "$H_DIR/pid" "$PIDFILE"
}
health_legacy_tail(){
  # Only the known r149 ordering defect is eligible. Never synthesize a missing
  # baseline or replace its saved network rules with a snapshot of today's rules.
  health_manifest_valid && cmp -s "$H_DIR/pid" "$PIDFILE" || return 1
  ! grep -q '^GOOGLE_FIREWALL_CLEAN=' "$H_DIR/session" || return 1
  H_TAIL=$(tail -n 1 "$SESSION" 2>/dev/null)
  case "$H_TAIL" in GOOGLE_FIREWALL_CLEAN=0|GOOGLE_FIREWALL_CLEAN=1) ;; *) return 1;; esac
  { cat "$H_DIR/session"; printf '%s\n' "$H_TAIL"; } | cmp -s - "$SESSION"
}
health_fault(){ [ "$H_STATE" = upgrade-required ] || H_STATE=degraded; H_REASON="${H_REASON:+$H_REASON,}$1"; }
health_unknown(){ H_UNKNOWN=1; H_REASON="${H_REASON:+$H_REASON,}$1"; }
health_core_and_watchdog(){
  H_PID=$(cat "$PIDFILE" 2>/dev/null || true)
  pidcore "$H_PID"; H_ID=$?
  case "$H_ID" in 0) kill -0 "$H_PID" 2>/dev/null || health_fault core-exited;;
    2) health_unknown core-identity-read;; *) health_fault core-identity;; esac
  H_WD=$(cat "$WATCHDOG_PID" 2>/dev/null || true)
  case "$H_WD" in ''|*[!0-9]*) health_fault watchdog-missing; return;; esac
  kill -0 "$H_WD" 2>/dev/null || { health_fault watchdog-exited; return; }
  H_WCMD=$(tr '\000' '\n' 2>/dev/null < "/proc/$H_WD/cmdline") || { health_unknown watchdog-identity-read; return; }
  [ -n "$H_WCMD" ] || { health_unknown watchdog-identity-read; return; }
  printf '%s\n' "$H_WCMD" | awk -v s="$BASE/hetu-root.sh" -v p="$H_PID" '
    $0==s {if(getline>0 && $0=="watchdog" && getline>0 && $0==p) found=1}
    END{exit !found}' || health_fault watchdog-identity
}
health_core_birth(){
  # /proc stat comm can contain spaces/parentheses. Start ticks are field 22,
  # or field 20 after the last closing parenthesis, not a PID alone.
  sed 's/^.*) //' "/proc/$1/stat" 2>/dev/null | awk 'NF>=20 && $20~/^[0-9]+$/ {print $20}'
}
health_routes(){
  H_F="$1"; H_RULES=$(ip -"$H_F" rule show 2>/dev/null) || { health_unknown "ipv$H_F-rule-read"; return; }
  H_RULE_OK=$(printf '%s\n' "$H_RULES" | awk -v p="$PREF:" -v m="$MARK/$MASK" -v t="$TABLE" '$1==p && $2=="from" && $3=="all" && $4=="fwmark" && $5==m && ($6=="lookup" || $6=="table") && $7==t {print "yes"}')
  [ "$H_RULE_OK" = yes ] || health_fault "ipv$H_F-policy-rule"
  # A removed table is a confirmed absence. Other netlink failures are unknown.
  H_ROUTES=$(ip -"$H_F" route show table "$TABLE" 2>&1); H_RC=$?
  if [ "$H_RC" != 0 ]; then
    case "$H_ROUTES" in *'FIB table does not exist'*|*'No such file'*) H_ROUTES='';; *) health_unknown "ipv$H_F-route-read"; return;; esac
  fi
  H_ROUTE_OK=$(printf '%s\n' "$H_ROUTES" | awk '$1=="local" && ($2=="default" || $2=="0.0.0.0/0" || $2=="::/0") && $3=="dev" && $4=="lo" {print "yes"}')
  [ "$H_ROUTE_OK" = yes ] || health_fault "ipv$H_F-local-route"
}
health_collect(){
  H_STATE=healthy; H_REASON=''; H_UNKNOWN=0; H_REPAIR_AVAILABLE=false; H_MANIFEST_STATE=current
  if [ ! -r "$SESSION" ] && [ ! -r "$PIDFILE" ]; then H_STATE=stopped; H_REASON=not-running; H_MANIFEST_STATE=stopped; return; fi
  if [ -d "$LOCK_DIR" ] && [ "$LOCK_HELD" != 1 ]; then H_STATE=unknown; H_REASON=transaction-in-progress; return; fi
  if ! health_session_current; then
    H_STATE=upgrade-required; H_REASON=session-manifest-missing; H_MANIFEST_STATE=missing-or-invalid
    if health_legacy_tail; then H_MANIFEST_STATE=legacy-tail-mismatch
    else health_core_and_watchdog; [ "$H_UNKNOWN" = 0 ] || H_STATE=unknown; return; fi
  fi
  health_core_and_watchdog
  for H_E in "$H_DIR"/[46]-*; do
    [ -s "$H_E" ] || continue
    H_KEY=${H_E##*/}; H_F=${H_KEY%%-*}; H_T=${H_KEY#*-}
    H_RAW=$("xt${H_F}q" -t "$H_T" -S 2>/dev/null) || { health_unknown "$H_KEY-read"; continue; }
    for H_C in $(awk '$1=="-N" {print $2}' "$H_E"); do
      H_EXPECTED=$(awk -v c="$H_C" '($1=="-N" || $1=="-A") && $2==c' "$H_E")
      H_ACTUAL=$(printf '%s\n' "$H_RAW" | awk -v c="$H_C" '($1=="-N" || $1=="-A") && $2==c')
      [ "$H_EXPECTED" = "$H_ACTUAL" ] || health_fault "$H_KEY-$H_C"
    done
    while IFS= read -r H_LINE; do
      case "$H_LINE" in '-A OUTPUT '*|'-A PREROUTING '*|'-A FORWARD '*)
        H_COUNT=$(printf '%s\n' "$H_RAW" | grep -Fxc -- "$H_LINE")
        [ "$H_COUNT" = 1 ] || health_fault "$H_KEY-hook";;
      esac
    done < "$H_E"
  done
  if [ -s "$H_DIR/net" ]; then
    if ! cmp -s "$NET_STATE" "$H_DIR/net" || ! loadnet; then
      health_fault routing-journal
    else
      [ ! -s "$H_DIR/4-mangle" ] || health_routes 4
      [ ! -s "$H_DIR/6-mangle" ] || health_routes 6
    fi
  fi
  H_MODE=$(sed -n 's/^MODE=//p' "$SESSION")
  case "$H_MODE" in tun|ebpf) ip link show hetu0 >/dev/null 2>&1 || health_fault native-device;; esac
  H_SOCKETS=$(ss -lnut 2>/dev/null); H_SS=$?
  if [ "$H_SS" != 0 ]; then health_unknown socket-read
  else
    for H_SPEC in CONTROLLER_PORT:tcp LISTENER_TCP_PORT:tcp LISTENER_UDP_PORT:udp ACTIVE_DNS_PORT:tcp ACTIVE_DNS_PORT:udp; do
      H_P=$(sed -n "s/^${H_SPEC%:*}=//p" "$SESSION"); H_PROTO=${H_SPEC#*:}
      case "$H_P" in ''|0) continue;; esac
      printf '%s\n' "$H_SOCKETS" | awk -v p="$H_P" -v proto="$H_PROTO" '$1 ~ ("^"proto) && $5 ~ (":"p"$") {found=1} END {exit !found}' || health_fault "listener-$H_P-$H_PROTO"
    done
  fi
  # Never mutate after an incomplete observation, even if another check failed.
  [ "$H_UNKNOWN" = 0 ] || H_STATE=unknown
  if [ "$H_MANIFEST_STATE" = legacy-tail-mismatch ] && [ "$H_STATE" = upgrade-required ] && [ "$H_REASON" = session-manifest-missing ]; then H_REPAIR_AVAILABLE=true; fi
}
health_repair_session(){ (
  root; acquire_lock || { printf '{"ok":false,"message":"运行事务正在执行，请稍后重试"}\n'; exit 0; }
  H_N=0
  while :; do
    H_TMP="$RUN/network-manifest.tail-new.$$.$H_N"; H_BACKUP="$RUN/network-manifest.pre-tail.$$.$H_N"
    [ -e "$H_TMP" ] || [ -e "$H_BACKUP" ] || break
    H_N=$((H_N+1)); [ "$H_N" -lt 100 ] || exit 1
  done
  H_MOVED=0
  trap '[ "$H_MOVED" != 1 ] || [ -d "$RUN/network-manifest" ] || mv "$H_BACKUP" "$RUN/network-manifest"; rm -rf "$H_TMP"; release_lock' EXIT
  health_collect
  if [ "$H_REPAIR_AVAILABLE" != true ]; then
    printf '{"ok":false,"message":"运行记录不符合安全修复条件，请查看网络诊断；未改动核心和网络规则"}\n'; exit 0
  fi
  H_EXPECTED_PID=$(cat "$PIDFILE"); H_BIRTH=$(health_core_birth "$H_EXPECTED_PID")
  [ -n "$H_BIRTH" ] || { printf '{"ok":false,"message":"无法确认核心身份，未修复运行记录"}\n'; exit 0; }
  cp -R "$RUN/network-manifest" "$H_TMP" && cp "$SESSION" "$H_TMP/session" || exit 1
  (cd "$H_TMP" && { cksum [46]-* session pid; [ ! -f net ] || cksum net; }) > "$H_TMP/checksums" || exit 1
  H_BASELINE_DIR="$H_TMP"; health_collect
  [ "$H_STATE" = healthy ] && [ "$H_EXPECTED_PID" = "$(cat "$PIDFILE")" ] && [ "$H_BIRTH" = "$(health_core_birth "$H_EXPECTED_PID")" ] || {
    printf '{"ok":false,"message":"校验期间运行状态发生变化，未修复运行记录"}\n'; exit 0;
  }
  # Keep the original evidence. A failed publish rolls back; even interrupted
  # publication must fail closed, never treat an absent manifest as healthy.
  unset H_BASELINE_DIR
  health_legacy_tail && cmp -s "$H_TMP/session" "$SESSION" || exit 1
  H_MOVED=1; mv "$RUN/network-manifest" "$H_BACKUP" || exit 1
  mv "$H_TMP" "$RUN/network-manifest" || exit 1; H_MOVED=0
  printf '%s pid=%s kind=legacy-google-tail rules=preserved\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$H_EXPECTED_PID" >> "$RUN/session-repair.log"
  unset H_BASELINE_DIR; health_collect
  printf '{"ok":true,"networkIntegrity":"%s","networkFault":"%s","message":"已修复旧版运行记录；核心和网络规则保持原状"}\n' "$H_STATE" "$H_REASON"
); }
health_restore_table(){
  H_E="$1"; H_KEY=${H_E##*/}; H_F=${H_KEY%%-*}; H_T=${H_KEY#*-}
  H_RAW=$("xt${H_F}q" -t "$H_T" -S 2>/dev/null) || return 1
  H_BATCH="$RUN/.repair-$H_KEY.$$"; printf '*%s\n' "$H_T" > "$H_BATCH" || return 1
  H_CHANGED=0
  for H_C in $(awk '$1=="-N" {print $2}' "$H_E"); do
    H_EXPECTED=$(awk -v c="$H_C" '($1=="-N" || $1=="-A") && $2==c' "$H_E")
    H_ACTUAL=$(printf '%s\n' "$H_RAW" | awk -v c="$H_C" '($1=="-N" || $1=="-A") && $2==c')
    [ "$H_EXPECTED" != "$H_ACTUAL" ] || continue
    if printf '%s\n' "$H_RAW" | grep -Fqx -- "-N $H_C"; then printf -- '-F %s\n' "$H_C" >> "$H_BATCH"
    else printf -- '-N %s\n' "$H_C" >> "$H_BATCH"; fi
    awk -v c="$H_C" '$1=="-A" && $2==c' "$H_E" >> "$H_BATCH"
    H_CHANGED=1
  done
  while IFS= read -r H_LINE; do
    case "$H_LINE" in '-A OUTPUT '*|'-A PREROUTING '*|'-A FORWARD '*)
      H_COUNT=$(printf '%s\n' "$H_RAW" | grep -Fxc -- "$H_LINE")
      [ "$H_COUNT" != 1 ] || continue
      while [ "$H_COUNT" -gt 0 ]; do printf '%s\n' "$H_LINE" | sed 's/^-A /-D /' >> "$H_BATCH"; H_COUNT=$((H_COUNT-1)); done
      # DNS/IPv6/UDP guards were installed at the front; preserve that contract.
      case "$H_LINE" in *HETU_DNS*|*HETU_DOT*|*HETU_V6*|*HETU_WR*) printf '%s\n' "$H_LINE" | sed 's/^-A /-I /' >> "$H_BATCH";; *) printf '%s\n' "$H_LINE" >> "$H_BATCH";; esac
      H_CHANGED=1;;
    esac
  done < "$H_E"
  printf 'COMMIT\n' >> "$H_BATCH"
  if [ "$H_CHANGED" = 1 ]; then
    H_RESTORE=iptables-restore; [ "$H_F" != 6 ] || H_RESTORE=ip6tables-restore
    # No unsafe line-by-line fallback: a failed commit must leave the table intact.
    "$H_RESTORE" -w 2 --noflush < "$H_BATCH" >> "$RUN/network-repair.log" 2>&1; H_RC=$?
  else H_RC=0; fi
  rm -f "$H_BATCH"; return "$H_RC"
}
health_repair_due(){
  monotonic_seconds || return 1
  H_NOW="$MONO_SECONDS"
  # Missing history is NOT a repair at boot time zero. A new session can fail
  # within the first 30 seconds of boot, especially during network creation.
  [ -r "$RUN/network-repair-at" ] || return 0
  read -r H_LAST < "$RUN/network-repair-at" || return 0
  case "$H_LAST" in ''|*[!0-9]*) return 0;; esac
  [ "$H_NOW" -ge "$H_LAST" ] || return 0
  [ $((H_NOW-H_LAST)) -ge 30 ]
}
health_repair(){ (
  trap 'release_lock' EXIT
  H_PID="${1:-}"; H_CURRENT=$(cat "$PIDFILE" 2>/dev/null || true)
  [ -n "$H_PID" ] && [ "$H_PID" = "$H_CURRENT" ] && pidcore "$H_PID" && kill -0 "$H_PID" 2>/dev/null || exit 0
  # OEM blocks are outside our HETU-chain manifest and can exist while it is healthy.
  google_firewall_maintain "$H_PID"
  health_collect
  [ "$H_STATE" = degraded ] || exit 0
  H_FIRST="$H_REASON"
  health_repair_due || exit 0
  acquire_lock || exit 0
  # Another repair may have finished while this one waited for the lock.
  health_repair_due || exit 0
  [ "$H_PID" = "$(cat "$PIDFILE" 2>/dev/null)" ] && health_session_current || exit 0
  sleep 0.25; health_collect
  [ "$H_STATE" = degraded ] && [ "$H_REASON" = "$H_FIRST" ] || exit 0
  pidcore "$H_PID" && kill -0 "$H_PID" 2>/dev/null || exit 0
  # Missing metadata or listeners cannot safely be reconstructed from preferences.
  # Keep the core and its existing connections; report instead of restart-looping.
  case "$H_REASON" in *routing-journal*|*listener-*|*native-device*|*core-*|*watchdog-*) exit 0;; esac
  printf '%s\n' "$H_NOW" > "$RUN/network-repair-at"
  printf '%s pid=%s before=%s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$H_PID" "$H_REASON" >> "$RUN/network-repair.log"
  if [ -s "$RUN/network-manifest/net" ]; then
    loadnet || exit 0
    for H_F in 4 6; do
      [ -s "$RUN/network-manifest/$H_F-mangle" ] || continue
      H_RULES=$(ip -"$H_F" rule show 2>/dev/null) || exit 0
      # If a different owner occupies our exact priority, do not overwrite it.
      H_AT_PREF=$(printf '%s\n' "$H_RULES" | awk -v p="$PREF:" '$1==p')
      H_RULE_OK=$(printf '%s\n' "$H_AT_PREF" | awk -v m="$MARK/$MASK" -v t="$TABLE" '$4=="fwmark" && $5==m && ($6=="lookup" || $6=="table") && $7==t {print "yes"}')
      [ -z "$H_AT_PREF" ] || [ "$H_RULE_OK" = yes ] || exit 0
      H_DEFAULT=0.0.0.0/0; [ "$H_F" != 6 ] || H_DEFAULT=::/0
      ip -"$H_F" route replace local "$H_DEFAULT" dev lo table "$TABLE" || exit 0
      [ "$H_RULE_OK" = yes ] || ip -"$H_F" rule add pref "$PREF" fwmark "$MARK/$MASK" table "$TABLE" || exit 0
    done
  fi
  # Guards and DNS first, OUTPUT marking last; never clear a global firewall table.
  for H_T in filter nat mangle; do for H_F in 4 6; do
    H_E="$RUN/network-manifest/$H_F-$H_T"; [ ! -s "$H_E" ] || health_restore_table "$H_E" || exit 0
  done; done
  health_collect
  printf '%s pid=%s after=%s faults=%s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$H_PID" "$H_STATE" "$H_REASON" >> "$RUN/network-repair.log"
  # Bound this diagnostic file without touching the Mihomo connection log.
  tail -n 60 "$RUN/network-repair.log" > "$RUN/network-repair.log.new.$$" && mv "$RUN/network-repair.log.new.$$" "$RUN/network-repair.log"
); }
health_json(){ (
  root; health_collect
  H_DNS6=$(sed -n 's/^DNS6_POLICY=//p' "$SESSION" 2>/dev/null || true)
  case "$H_DNS6" in redirect|blocked-no-nat|blocked-no-redirect|off|core) ;; *) H_DNS6=unknown;; esac
  printf '{"ok":true,"networkIntegrity":"%s","networkFault":"%s","sessionManifestState":"%s","baselineRepairAvailable":%s,"ipv6DnsPolicy":"%s","dataPlaneHealthy":%s}\n' "$H_STATE" "$H_REASON" "$H_MANIFEST_STATE" "$H_REPAIR_AVAILABLE" "$H_DNS6" "$([ "$H_STATE" = healthy ] && echo true || echo false)"
); }

case "${1:-status}" in
  repair-session) [ "$#" = 1 ] || fail "参数错误"; health_repair_session;;
  preflight) { [ "$#" = 19 ] || [ "$#" = 20 ]; } || fail "参数错误"; preflight "$2" "$3" "$4" "$5" "$6" "$7" "$8" "$9" "${10}" "${11}" "${12}" "${13}" "${14}" "${15}" "${16}" "${17}" "${18}" "${19}" "${20:-}";;
  # 31 fields: DNS protocols, priority, CPU, memory, I/O and vendor-firewall cleanup follow the
  # established 23/24. The values themselves are validated in start(), before any change.
  start)
    case "$#" in
      23|24) ;;
      31)
        bool "${25}" || fail "DNS TCP 劫持开关必须是 0 或 1"
        bool "${26}" || fail "DNS UDP 劫持开关必须是 0 或 1"
        bool "${27}" || fail "性能模式开关必须是 0 或 1"
        bool "${31}" || fail "厂商防火墙清理开关必须是 0 或 1"
        ;;
      *) fail "启动参数数量错误：收到 $#，预期 23/24/31（含操作名）";;
    esac
    root; shift; start "$@";;
  txn-deadline) [ "$#" = 5 ] || exit 1; root; transaction_deadline "$2" "$3" "$4" "$5";;
  cancel-boot) root; cancel_boot || fail "无法撤销本次自动恢复"; ok "自动恢复已撤销";;
  stop) root; trap '' TERM INT HUP; shield_run stop_transaction || fail "停止未完成：核心或网络清理尚未确认，请重试停止"; ok "Root 代理已停止并恢复网络状态";;
  status) status;;
  network-health) health_json;;
  repair-network) [ "$#" = 2 ] || exit 1; root; health_repair "$2";;
  watchdog) { [ "$#" = 12 ]; } || exit 0; root; watchdog "$2" "$3" "$4" "$5" "$6" "$7" "$8" "$9" "${10}" "${11:-}" "${12:-}";;
  *) fail "未知 Root 代理操作";;
esac
