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
# rp_filter / ip_forward values changed for this session (path<TAB>original<TAB>set), restored on stop.
SYSCTL_NET=/proc/sys/net
SYSCTL_TUNE_STATE="$RUN/sysctl-tune.state"
# Opt-in cgroup limits for the core (memcg memory.max / io weight, v2 first, v1 fallback).
CG_MOUNTS=/proc/mounts
CG_V1_MEMORY="/dev/memcg /sys/fs/cgroup/memory"
CG_V1_BLKIO="/dev/blkio /sys/fs/cgroup/blkio"
CG_STATE="$RUN/cgroup.state"
START_CG_MEM=0
START_CG_IO=0
NS_NOTES=""
V6_CONF=/proc/sys/net/ipv6/conf
NET_STATE="$RUN/net.state"
WATCHDOG_PID="$RUN/watchdog.pid"
WATCHDOG_LOG="$RUN/watchdog.log"
CRASH_STATE="$RUN/last-crash"
START_STATE="$RUN/start-state"
START_ERROR="$RUN/last-start-error"
START_TIMING="$RUN/startup-timing"
LOCK_DIR="$RUN/.txn.lock"
# Which private core $BASE/bin/core is (the App writes it together with the binary). Absent or
# unknown means Mihomo, so every existing deployment and stored boot plan keeps its exact CLI.
CORE_KIND_FILE="$RUN/state/core.kind"
CORE_ASSETS="$BASE/bin/assets"
CORE_KIND_OVERRIDE=""
VC_DIR="$RUN"
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

# CNIP kernel bypass (ipset). The App writes $BASE/policy/cnip (V4=0|1, V6=0|1, FORCE=uid ranges);
# the address lists are the core's own CN providers in $RUN/ruleset (APK snapshot first, then
# refreshed daily from gaoyifan/china-operator-ip by the core). Without ipset the core RULE-SET
# keeps doing the job and status reports cnip=degraded.
CN_SET4=hetu_cn4
CN_SET6=hetu_cn6
CN_FILE4="$RUN/ruleset/hetu-cn-v4.txt"
CN_FILE6="$RUN/ruleset/hetu-cn-v6.txt"
CN4_ON=0
CN6_ON=0
CN_FORCE=""
CN_TCPONLY=0
CN_CHAIN=""
CNIP_STATE=off
CNIP_REASON=""
# Without the ipset binary (stock ColorOS/OxygenOS and many other ROMs ship none) the same bypass
# runs as an iptables CIDR tree in mangle: OUTPUT/PREROUTING -> HETU_CNC -> HETU_CNL (one rule per
# /8 or /16 block, nested HETU_CN_<bits>_<key> chains of at most ~32 entries) -> HETU_CNY. Only a
# connection's first packet (ctstate NEW, verdict not yet stored) walks the tree; the verdict is
# kept in two conntrack-mark bits (CN_CT_CHK = classified, CN_CT_CN = CN destination), so every
# later packet skips it and the capture/guard chains only test the CN bit. status: cnip=iptables.
CN_CT_CN=0x20000000
CN_CT_CHK=0x40000000
CN_CT_BOTH=0x60000000

# Opt-in eBPF rule matcher (default off). The App writes $BASE/policy/ebpf (ENABLED=1) and deploys
# the loader and object from assets/hetu-bpf to $BASE/bin. When enabled and every probe passes, the
# per-UID owner rules and the custom bypass networks collapse into one `-m bpf --object-pinned`
# rule each, backed by pinned maps that ebpf-update rewrites without touching iptables. Any probe
# failure keeps the established owner/CIDR rules unchanged (status ebpf=fallback reason=...).
EBPF_PIN=/sys/fs/bpf/hetu
EBPF_TOOL="$BASE/bin/hetu-bpf"
EBPF_OBJECT="$BASE/bin/hetu_match.o"
EBPF_LISTS="$RUN/state/ebpf-lists"
# Where ColorOS pins netd's per-UID network block maps (hetu-bpf vendor-uid reads them there).
GOOGLE_VENDOR_BPF=/sys/fs/bpf
EBPF_STATE=off
EBPF_REASON=""
# 1 only while capture rules are built for an active matcher; the Kill Switch guard never uses it.
EBPF_RULES=0

# Self-heal (2026-10 network-loss fix). State: $RUN/state/start.args (what a restart replays),
# $RUN/state/selfheal-budget, $RUN/selfheal-state, $RUN/liveness.kick, $RUN/cgroup-escape.
# The App process (and its uid_/pid_ cgroup) may be frozen
# by the cached-app freezer / Millet or killed as a process group; the core, watchdog and monitor
# leave that cgroup (root cgroup, v2 plus Android's v1 hierarchies) and run in their own session.
# The watchdog probes the core (frozen state, controller, DNS) and the proxy egress, restarts the
# core from the saved start arguments, and removes capture (Kill Switch: keeps its guards) when a
# restart is impossible. Every action is logged to $WATCHDOG_LOG.
CG_ESCAPE_GATE=/system/bin
CG_ESCAPE_V1="/acct /dev/freezer /sys/fs/cgroup/freezer /dev/cpuctl /dev/memcg"
CG_ESCAPED=""
SETSID=""
SETSID_CHECKED=0
# Android's network stack (NetworkMonitor validation) resolves outside DNS takeover.
NETSTACK_UID=1073
WD_HTTP_TOOLS="curl wget nc"
WD_HTTP_TIMEOUT=3
WD_LIVE_GRACE=60
WD_LIVE_EVERY=15
WD_LIVE_RETRY=5
WD_LIVE_LIMIT=3
WD_DNS_EVERY=60
WD_DNS_LIMIT=3
WD_EGRESS_GRACE=180
WD_EGRESS_EVERY=120
WD_EGRESS_RETRY=30
WD_EGRESS_LIMIT=3
WD_EGRESS_BACKOFF=300
WD_EGRESS_BACKOFF_MAX=3600
WD_RESTART_MAX=3
WD_RESTART_WINDOW=1800
WD_LOG_MAX=65536
WD_PROXY_URL=http://www.gstatic.com/generate_204
# 3.3.3: DIRECT checks use probe hosts that answer from mainland China (connectivitycheck.gstatic.com times
# out direct there, which made every outage look like "DIRECT fails too").
WD_DIRECT_URLS="http://connect.rom.miui.com/generate_204 http://www.qualcomm.cn/generate_204 http://www.google.cn/generate_204"
WD_HEAL_REASON=""
# Data-plane probe (3.0.0, real-device outage on ColorOS: core and controller alive, the Google group's
# node stalled, the MATCH-group delay check saw nothing). As root the watchdog asks the core's DNS for a
# fake IP and fetches it, so the request really goes TPROXY/REDIRECT -> core -> proxy; a CN host with a
# fake IP (normally DIRECT) and the proxy groups' own delay tell a dead node from a broken data plane.
WD_DP_GRACE=120
WD_DP_EVERY=90
WD_DP_RETRY=30
WD_DP_LIMIT=2
WD_DP_TIMEOUT=6
WD_DP_HOST=www.gstatic.com
WD_DP_PATH=/generate_204
WD_DP_CN_HOSTS="www.baidu.com www.qq.com www.taobao.com"
WD_DP_GROUP_MAX=12
WD_DP_BUDGET=30
# 6.0.0: Hetu's loopback HTTP listener in every core sits this far above the controller port
# (MihomoStartupConfig.EGRESS_PROBE_PORT_OFFSET). The probe request sent through it takes the core's own
# rules, groups and nodes with no TPROXY and no fake IP: it tells a capture / fake-IP fault from a routing one.
WD_DP_EGRESS_OFFSET=60
# 5.1.0: before a node is healed its connections are read twice this many seconds apart; one whose
# download counter grew means the node is moving data (a probe that timed out behind a busy download).
WD_BUSY_GAP=2
# 3.0.0-3.3.3 armed a 120 s post-stop REJECT of the whole fake-IP range (filter OUTPUT, every UID) in this
# chain. It also cut off any other proxy app using the same 198.18/15 fake-IP range, and when ColorOS
# killed the detached expiry timer the REJECT stayed until the next Hetu start. It is no longer armed:
# every stop, start, cleanup and status removes it (fake_reject_remove), and the stop verification
# reports it. The resolver flush after a stop (net_recover) is what makes apps resolve again.
FAKE_REJECT_CHAIN=HXFAKE_REJECT
# sing-tun / Mihomo TUN auto-route defaults (Hetu's TUN mode never overrides them): ip rules from
# TUN_RULE_PREF_MIN.., routes in table TUN_ROUTE_TABLE. A SIGKILLed core leaves the rules behind.
TUN_ROUTE_TABLE=2022
TUN_RULE_PREF_MIN=9000
TUN_RULE_PREF_MAX=9099

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
  net_sysctl_restore; cgroup_release
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
  cn_drop_tails xt4; share_drop_tails xt4
  if [ "$PRESERVE_KILL" != 1 ] && ! cleanup_chain_absent xt4 mangle HETU_CNC; then cnip_xt_drop 4 || true; fi
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
  cn_drop_tails xt6; share_drop_tails xt6
  if [ "$PRESERVE_KILL" != 1 ] && ! cleanup_chain_absent xt6 mangle HETU_CNC; then cnip_xt_drop 6 || true; fi
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
    function kept(c){ return keep == "1" && (c == "HETU_KOUT" || c == "HETU_KFWD" || c == "HETU_KOUT_T" || c == "HETU_KFWD_S" || c ~ /^HETU_CN(C|Y|L|_)/) }
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
cleanup(){ MARK=""; MASK=""; TABLE=""; PREF=""; loadnet >/dev/null 2>&1 || true; cleanup_snapshot_begin; sweep_rules; cleanup4; cleanup6; cleanlegacy; sweep_policy_routes; CLEAN_SNAPSHOT_ACTIVE=0; cnip_sweep_sets keep; ebpf_unload || true; ip link del hetu0 >/dev/null 2>&1 || true; foreign_safe_cleanup; rm -f "$NET_STATE"; MARK=""; MASK=""; TABLE=""; PREF=""; }
# Android only (host fixtures keep their exact command lists): the legacy post-stop fake-IP REJECT and
# the dead TUN auto-route rules of a killed Hetu TUN core. Both cut every app's network, other proxy
# apps included, so they never outlive a cleanup.
foreign_safe_cleanup(){
  [ -d "$CG_ESCAPE_GATE" ] || return 0
  fake_reject_remove
  tun_rule_sweep
  return 0
}
# Dead TUN auto-route rules: priorities TUN_RULE_PREF_MIN..MAX that look up TUN_ROUTE_TABLE, jump inside
# that block (goto 90xx) or are unreachable/blackhole/prohibit, removed only while TUN_ROUTE_TABLE has no
# route left in that family (its TUN device is gone). A running TUN of another app keeps its routes, so
# its rules are never touched.
tun_rule_lines(){
  awk -v lo="$TUN_RULE_PREF_MIN" -v hi="$TUN_RULE_PREF_MAX" -v t="$TUN_ROUTE_TABLE" '
    { p = $1; sub(/:$/, "", p); if (p !~ /^[0-9]+$/ || p + 0 < lo || p + 0 > hi) next
      dead = 0
      for (i = 2; i <= NF; i++) {
        if (($i == "lookup" || $i == "table") && $(i+1) == t) dead = 1
        if ($i == "goto" && $(i+1) ~ /^[0-9]+$/ && $(i+1) + 0 >= lo && $(i+1) + 0 <= hi) dead = 1
        if ($i == "unreachable" || $i == "blackhole" || $i == "prohibit") dead = 1
      }
      if (dead) { line = $0; sub(/^[0-9]+:[[:space:]]*/, "", line); print p "\t" line } }'
}
tun_rule_sweep(){
  TRS_TAB=$(printf '\t')
  for TRS_F in 4 6; do
    if [ "$TRS_F" = 6 ] && ! v6supported; then continue; fi
    ip -"$TRS_F" route show table "$TUN_ROUTE_TABLE" 2>/dev/null | grep -q . && continue
    TRS_LIST=$(ip -"$TRS_F" rule show 2>/dev/null | tun_rule_lines) || continue
    [ -n "$TRS_LIST" ] || continue
    TRS_N=0
    while IFS="$TRS_TAB" read -r TRS_P TRS_RULE; do
      [ -n "$TRS_P" ] || continue
      # shellcheck disable=SC2086
      ip -"$TRS_F" rule del pref "$TRS_P" $TRS_RULE >/dev/null 2>&1 || true
      TRS_N=$((TRS_N+1))
    done <<EOF_TRS
$TRS_LIST
EOF_TRS
    wd_log "cleanup: removed $TRS_N dead TUN auto-route rule(s) (IPv$TRS_F, table $TUN_ROUTE_TABLE empty)"
  done
  return 0
}

cleanup_verify(){
  for CC_BIN in xt4 xt6; do
    if [ "$CC_BIN" = xt6 ] && ! has ip6tables; then continue; fi
    # Not CC_TABLE: that name holds the journaled routing table checked below.
    for CC_XT in mangle nat filter; do
      CC_RULES=$(cleanup_snapshot_read "$CC_BIN" "$CC_XT") || return 1
      if printf '%s\n' "$CC_RULES" | grep -Eq '(^-N |^-A | -j | -g )(HETU_|BICHEN_)'; then return 1; fi
      if printf '%s\n' "$CC_RULES" | grep -q -- '--match-set hetu_'; then return 1; fi
      if printf '%s\n' "$CC_RULES" | grep -qF -- "--object-pinned $EBPF_PIN/"; then return 1; fi
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
  # ipset destroy only after the sweep was verified: no rule references a hetu_ set any more.
  # A Kill Switch guard kept in place still references them, so they stay with it.
  if [ "$PRESERVE_KILL" != 1 ] && ! cnip_rules_reference_sets; then cnip_sweep_sets all; fi
}

stop_transaction(){
  # Revoke before waiting for the lock, including a start whose watchdog has not
  # yet appeared. A stopped receipt is not a successful-cleanup receipt.
  cancel_boot || return 1
  acquire_lock || return 1
  printf 'stopped-%s\n' "$$" > "$RUN/generation" || return 1
  ST_FAKE4=$(sed -n 's/^FAKE_IP_V4=//p' "$SESSION" 2>/dev/null | head -n 1)
  ST_FAKE6=$(sed -n 's/^FAKE_IP_V6=//p' "$SESSION" 2>/dev/null | head -n 1)
  stopwatchdog
  SC_FAILED=0
  stopcore || SC_FAILED=1
  cleanup_confirmed || SC_FAILED=1
  restorev6 || SC_FAILED=1
  net_sysctl_restore; cgroup_release
  [ "$SC_FAILED" = 0 ] || return 1
  stop_verify
  rm -f "$SESSION"
  # No post-stop fake-IP REJECT any more (it broke other proxy apps on 198.18/15). Apps that still hold a
  # fake IP re-resolve after the resolver flush below; ST_FAKE4/ST_FAKE6 only go to the log.
  wd_log "stop: fake-IP range ${ST_FAKE4%%,*}${ST_FAKE6:+ ${ST_FAKE6%%,*}} released without a reject window; flushing resolver caches"
  net_recover_async stop
}
# 3.0.0 post-stop audit, independent of net.state: no fwmark rule or route of a Hetu table
# (20260-20299), no HETU_/BICHEN_ chain or hook, no eBPF pin directory. Anything left gets one more
# sweep; the result goes to watchdog.log. Android only (the host fixtures keep their exact calls).
stop_leftovers(){
  SL_OUT=""
  for SL_F in 4 6; do
    if [ "$SL_F" = 6 ] && ! v6supported; then continue; fi
    [ -z "$(ip -"$SL_F" rule show 2>/dev/null | owned_policy_rules)" ] || SL_OUT="$SL_OUT ip$SL_F-rule"
    if ip -"$SL_F" route show table all 2>/dev/null | awk '{ for (i = 1; i < NF; i++) if ($i == "table" && $(i+1) ~ /^[0-9]+$/ && $(i+1) + 0 >= 20260 && $(i+1) + 0 <= 20299) f = 1 } END { exit !f }'; then SL_OUT="$SL_OUT ip$SL_F-table"; fi
  done
  for SL_BIN in xt4q xt6q; do
    if [ "$SL_BIN" = xt6q ] && ! has ip6tables; then continue; fi
    for SL_T in mangle nat filter; do
      if "$SL_BIN" -t "$SL_T" -S 2>/dev/null | grep -Eq '(^-N |^-A | -j | -g )(HETU_|BICHEN_)'; then SL_OUT="$SL_OUT $SL_BIN-$SL_T"; fi
    done
  done
  [ ! -d "$EBPF_PIN" ] || SL_OUT="$SL_OUT ebpf-pin"
  for SL_BIN in xt4q xt6q; do
    if [ "$SL_BIN" = xt6q ] && ! has ip6tables; then continue; fi
    if "$SL_BIN" -t filter -S 2>/dev/null | grep -Eq "(^-N | -j )$FAKE_REJECT_CHAIN( |\$)"; then SL_OUT="$SL_OUT $SL_BIN-fake-reject"; fi
  done
  for SL_F in 4 6; do
    if [ "$SL_F" = 6 ] && ! v6supported; then continue; fi
    ip -"$SL_F" route show table "$TUN_ROUTE_TABLE" 2>/dev/null | grep -q . && continue
    [ -z "$(ip -"$SL_F" rule show 2>/dev/null | tun_rule_lines)" ] || SL_OUT="$SL_OUT ip$SL_F-tun-rule"
  done
  printf '%s' "${SL_OUT# }"
}
stop_verify(){
  [ -d "$CG_ESCAPE_GATE" ] || return 0
  SV_LEFT=$(stop_leftovers)
  if [ -z "$SV_LEFT" ]; then wd_log "stop-verify: clean (no Hetu ip rule, table 20260-20299 route, HETU_ chain, eBPF pin, fake-IP reject or dead TUN rule)"; return 0; fi
  wd_log "stop-verify: left after stop: $SV_LEFT; sweeping once more"
  cleanup_confirmed || true; ebpf_unload || true
  case " $SV_LEFT " in *-mangle*) cnip_xt_drop 4 || true; cnip_xt_drop 6 || true;; esac
  SV_LEFT=$(stop_leftovers)
  if [ -z "$SV_LEFT" ]; then wd_log "stop-verify: clean after the second sweep"; else wd_log "stop-verify: still left after the second sweep: $SV_LEFT"; fi
  return 0
}
# Removes the legacy post-stop fake-IP REJECT chain (3.0.0-3.3.3) in both families, whether or not the
# old marker file is still there, and checks that it is gone: a lost marker or a lock timeout used to
# leave it (and every fake-IP connection of every app) blocked. Returns 1 if it could not be removed.
fake_reject_drop(){
  while "$1" -t filter -C OUTPUT -j "$FAKE_REJECT_CHAIN" >/dev/null 2>&1; do "$1" -t filter -D OUTPUT -j "$FAKE_REJECT_CHAIN" >/dev/null 2>&1 || break; done
  "$1" -t filter -F "$FAKE_REJECT_CHAIN" >/dev/null 2>&1 || true; "$1" -t filter -X "$FAKE_REJECT_CHAIN" >/dev/null 2>&1 || true
}
fake_reject_present(){ "$1" -t filter -S "$FAKE_REJECT_CHAIN" >/dev/null 2>&1; }
fake_reject_remove(){
  rm -f "$RUN/fake-reject" 2>/dev/null
  FRR_RC=0
  for FRR_BIN in xt4q xt6q; do
    if [ "$FRR_BIN" = xt6q ] && ! has ip6tables; then continue; fi
    if [ "$FRR_BIN" = xt4q ] && ! has iptables; then continue; fi
    fake_reject_present "$FRR_BIN" || continue
    FRR_N=0
    while fake_reject_present "$FRR_BIN"; do
      fake_reject_drop "$FRR_BIN"
      FRR_N=$((FRR_N+1)); [ "$FRR_N" -lt 3 ] || break
      fake_reject_present "$FRR_BIN" && sleep 1
    done
    if fake_reject_present "$FRR_BIN"; then FRR_RC=1; wd_log "fake-IP reject chain $FAKE_REJECT_CHAIN still present ($FRR_BIN)"
    else wd_log "removed the legacy fake-IP reject chain $FAKE_REJECT_CHAIN ($FRR_BIN)"; fi
  done
  return "$FRR_RC"
}
# 3.0.0: after a stop or start the system resolver may still hold the core's answers (fake IPs) and
# NetworkMonitor may keep a network "无法连接到网络" in its back-off. Flush every network's DNS cache and
# ask for a re-evaluation (Android 13+ `cmd connectivity reevaluate <netId>`). Best effort, logged.
# The App additionally reports every network to ConnectivityManager (NetworkRevalidation).
net_recover(){
  NR_NETS=$(timeout 8 dumpsys connectivity 2>/dev/null | net_recover_ids)
  NR_OUT=""
  for NR_ID in $NR_NETS; do
    NR_F=no; NR_R=no
    if has ndc && NR_X=$(timeout 3 ndc resolver flushnet "$NR_ID" 2>&1); then case "$NR_X" in *[Ee]rror*|*[Uu]nknown*|*[Ff]ail*|*[Uu]sage*) ;; *) NR_F=ok;; esac; fi
    if has cmd && NR_X=$(timeout 4 cmd connectivity reevaluate "$NR_ID" 2>&1); then case "$NR_X" in *[Ee]rror*|*[Uu]nknown*|*[Ff]ail*|*[Ee]xception*|*[Uu]sage*) ;; *) NR_R=ok;; esac; fi
    NR_OUT="$NR_OUT net$NR_ID:flush=$NR_F,reevaluate=$NR_R"
  done
  wd_log "$1: system network recovery${NR_OUT:- (no network listed by dumpsys connectivity)}"
}
# netIds (network{N}) of the NetworkAgentInfo lines of `dumpsys connectivity` on stdin, unique, at most 16.
net_recover_ids(){ awk '/NetworkAgentInfo/ { if (match($0, /[{ ]network[{][0-9]+[}]/)) print substr($0, RSTART + 9, RLENGTH - 10) }' | sort -un | head -n 16; }
net_recover_async(){
  [ -d "$CG_ESCAPE_GATE" ] || return 0
  detach_probe
  $SETSID "$0" net-recover "$1" </dev/null >/dev/null 2>&1 &
  cg_escape "$!"
  return 0
}
# ---- Wi-Fi recovery driven by a real probe (3.3.3) -----------------------------------------
# Real-device report (OnePlus PLK110 / ColorOS): after a while the whole phone lost the network on
# Wi-Fi; stopping or restarting Hetu did not help (flushnet + reevaluate ran fine, ColorOS kept the
# Wi-Fi bad), only Wi-Fi off/on did. After a stop, start or restart (net-recover) and when the
# watchdog sees a whole-phone outage, an HTTP 204 is fetched over the Wi-Fi route outside Hetu
# (core identity root:net_admin, which every capture chain returns; real addresses from public DNS).
# A failing probe or a Wi-Fi the system marks not validated escalates: flushnet + reevaluate, then
# `cmd wifi reconnect`, then Wi-Fi off/on. On by default ($WIFI_HEAL_OFF turns the reconnect off,
# written by the App setting), at most once per WIFI_HEAL_GAP s and WIFI_HEAL_HOUR_MAX per hour,
# logged to watchdog.log, with a notification when Wi-Fi is reconnected.
WIFI_HEAL_URLS="http://www.google.cn/generate_204 http://connect.rom.miui.com/generate_204 http://www.qualcomm.cn/generate_204"
WIFI_HEAL_DNS="223.5.5.5 119.29.29.29 114.114.114.114"
WIFI_HEAL_OFF="$BASE/policy/wifi-reconnect-off"
WIFI_HEAL_GAP=600
WIFI_HEAL_HOUR_MAX=3
WIFI_HEAL_SETTLE=12
WIFI_HEAL_WAIT=20
WIFI_HEAL_OFFLINE_LIMIT=2
# "<interface> <validated 0|1>" of the first Wi-Fi network with INTERNET in `dumpsys connectivity` on stdin.
wifi_nai(){
  awk 'index($0, "NetworkAgentInfo") && index($0, "ni{WIFI") {
    i = ""; if (match($0, /InterfaceName: [A-Za-z0-9_.-]+/)) i = substr($0, RSTART + 15, RLENGTH - 15)
    c = ""; if (match($0, /Capabilities: [A-Z0-9_&]+/)) c = substr($0, RSTART + 14, RLENGTH - 14)
    n = split(c, a, "&"); v = 0; net = 0
    for (k = 1; k <= n; k++) { if (a[k] == "VALIDATED") v = 1; if (a[k] == "INTERNET") net = 1 }
    if (i != "" && net) { print i, v; exit } }'
}
# WP_BB = BusyBox, WP_RUN = "BusyBox setuidgid <spec>" running as uid 0 / gid net_admin (the core identity
# every Hetu capture and DNS chain returns), empty when it cannot be had (then plain root).
wifi_runner(){
  WP_BB=""; WP_RUN=""
  for WR_B in /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox /data/adb/magisk/busybox; do [ -x "$WR_B" ] && { WP_BB="$WR_B"; break; }; done
  [ -n "$WP_BB" ] || return 0
  for WR_S in root:net_admin "0:$CORE_GROUP_ID"; do
    [ "$("$WP_BB" setuidgid "$WR_S" "$WP_BB" id -g 2>/dev/null)" = "$CORE_GROUP_ID" ] && { WP_RUN="$WP_BB setuidgid $WR_S"; return 0; }
  done
  return 0
}
# First IPv4 address of $1 from public resolvers (outside the core's DNS); BusyBox nslookup output on stdin.
wifi_addr(){ awk '/^Name:/ {n = 1; next} n && /^Address/ { for (i = 2; i <= NF; i++) if ($i ~ /^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$/) { print $i; exit } }'; }
wifi_lookup(){
  [ -n "$WP_BB" ] || return 1
  for WL_D in $WIFI_HEAL_DNS; do
    WL_IP=$(timeout 6 $WP_RUN "$WP_BB" nslookup "$1" "$WL_D" 2>/dev/null | wifi_addr)
    [ -z "$WL_IP" ] || { printf '%s\n' "$WL_IP"; return 0; }
  done
  return 1
}
# HTTP status of GET http://$1$3 with Host $2 over interface $4 (curl binds it; BusyBox wget follows the route).
wifi_get(){
  if has curl; then timeout 8 $WP_RUN curl -s -m 6 --interface "$4" -o /dev/null -w '%{http_code}' -H "Host: $2" "http://$1$3" 2>/dev/null; return 0; fi
  [ -n "$WP_BB" ] || return 0
  timeout 8 $WP_RUN "$WP_BB" wget -S -q -T 6 -O /dev/null --header "Host: $2" "http://$1$3" 2>&1 | awk '$1 ~ /^HTTP\// {c = $2} END {print c}'
}
# 0 = a 204 came back over Wi-Fi $1, 1 = no 204 from any probe host, 2 = could not test (no tool, the
# route to the probe host is not the Wi-Fi). WP_HOW says what was seen.
wifi_probe(){
  WP_IF="$1"; WP_HOW=""
  wifi_runner
  if [ -z "$WP_BB" ] && ! has curl; then WP_HOW=no-http-tool; return 2; fi
  for WP_U in $WIFI_HEAL_URLS; do
    WP_H=${WP_U#http://}; WP_P="/${WP_H#*/}"; WP_H=${WP_H%%/*}
    if [ -n "$WP_BB" ]; then
      WP_IP=$(wifi_lookup "$WP_H") || { WP_HOW="${WP_HOW:+$WP_HOW,}$WP_H=no-dns"; continue; }
      if ! has curl; then
        case "$(ip route get "$WP_IP" 2>/dev/null | head -n 1) " in *" dev $WP_IF "*) ;; *) WP_HOW="${WP_HOW:+$WP_HOW,}route-not-$WP_IF"; return 2;; esac
      fi
    else WP_IP="$WP_H"; fi
    WP_C=$(wifi_get "$WP_IP" "$WP_H" "$WP_P" "$WP_IF")
    [ "$WP_C" != 204 ] || { WP_HOW="${WP_HOW:+$WP_HOW,}$WP_H=204"; return 0; }
    WP_HOW="${WP_HOW:+$WP_HOW,}$WP_H=${WP_C:-none}"
  done
  return 1
}
# WC_STATE: none (no Wi-Fi network) | ok | bad. Bad = no 204 over Wi-Fi, or the system shows the Wi-Fi
# not validated (an untestable probe then decides nothing on its own).
wifi_check(){
  WC_NAI=$(timeout 8 dumpsys connectivity 2>/dev/null | wifi_nai)
  WC_IF=${WC_NAI% *}; WC_VALID=${WC_NAI##* }
  if [ -z "$WC_NAI" ]; then WC_STATE=none; WC_DETAIL="no Wi-Fi network"; return 0; fi
  wifi_probe "$WC_IF"; WC_RC=$?
  WC_DETAIL="$WC_IF validated=$WC_VALID probe=$WP_HOW"
  if [ "$WC_RC" = 1 ] || [ "$WC_VALID" != 1 ]; then WC_STATE=bad; else WC_STATE=ok; fi
}
# 0 when another Wi-Fi reconnect is allowed now (WIFI_HEAL_GAP since the last, < WIFI_HEAL_HOUR_MAX in an hour).
wifi_heal_allowed(){
  WHA_NOW=$(date +%s 2>/dev/null); case "$WHA_NOW" in ''|*[!0-9]*) return 1;; esac
  WHA_F="$RUN/state/wifi-reconnects"
  WHA_LAST=0; WHA_N=0
  if [ -r "$WHA_F" ]; then
    while read -r WHA_T; do
      case "$WHA_T" in ''|*[!0-9]*) continue;; esac
      [ "$WHA_T" -le "$WHA_NOW" ] || continue
      [ "$WHA_T" -le "$WHA_LAST" ] || WHA_LAST="$WHA_T"
      [ $((WHA_NOW-WHA_T)) -ge 3600 ] || WHA_N=$((WHA_N+1))
    done < "$WHA_F"
  fi
  [ $((WHA_NOW-WHA_LAST)) -ge "$WIFI_HEAL_GAP" ] || { WHA_WHY="last reconnect $((WHA_NOW-WHA_LAST))s ago (at most one per ${WIFI_HEAL_GAP}s)"; return 1; }
  [ "$WHA_N" -lt "$WIFI_HEAL_HOUR_MAX" ] || { WHA_WHY="$WHA_N reconnects in the last hour (at most $WIFI_HEAL_HOUR_MAX)"; return 1; }
  return 0
}
wifi_heal_record(){
  mkdir -p "$RUN/state" 2>/dev/null || true
  { { tail -n 9 "$RUN/state/wifi-reconnects" 2>/dev/null; date +%s; } > "$RUN/state/wifi-reconnects.new.$$" &&
    mv -f "$RUN/state/wifi-reconnects.new.$$" "$RUN/state/wifi-reconnects"; } 2>/dev/null || rm -f "$RUN/state/wifi-reconnects.new.$$" 2>/dev/null
  return 0
}
wifi_cmd_ok(){ case "$1" in *[Ee]rror*|*[Uu]nknown*|*[Ee]xception*|*[Uu]sage*|*[Ff]ail*|*"not found"*) return 1;; esac; return 0; }
# $1 reason (start | stop | restart | watchdog), $2 "flushed" when net_recover just ran.
wifi_heal(){
  WHL_R="$1"
  mkdir -p "$RUN" 2>/dev/null || true
  if ! mkdir "$RUN/wifi-heal.lock" 2>/dev/null; then
    WHL_P=$(cat "$RUN/wifi-heal.lock/pid" 2>/dev/null)
    if [ -n "$WHL_P" ] && kill -0 "$WHL_P" 2>/dev/null; then return 0; fi
    rm -rf "$RUN/wifi-heal.lock"; mkdir "$RUN/wifi-heal.lock" 2>/dev/null || return 0
  fi
  printf '%s\n' "$$" > "$RUN/wifi-heal.lock/pid" 2>/dev/null || true
  wifi_heal_body "$WHL_R" "${2:-}"
  rm -rf "$RUN/wifi-heal.lock"
  return 0
}
wifi_heal_body(){
  sleep "$WIFI_HEAL_SETTLE"
  wifi_check
  case "$WC_STATE" in
    none) [ "$1" != watchdog ] || wd_log "wifi-heal ($1): $WC_DETAIL; nothing to reconnect"; return 0;;
    ok) wd_log "wifi-heal ($1): Wi-Fi works ($WC_DETAIL)"; return 0;;
  esac
  wd_log "wifi-heal ($1): Wi-Fi has no internet ($WC_DETAIL)"
  if [ "$2" != flushed ]; then
    net_recover "wifi-heal ($1)"
    sleep "$WIFI_HEAL_SETTLE"; wifi_check
    [ "$WC_STATE" = bad ] || { wd_log "wifi-heal ($1): Wi-Fi works again after flushnet + reevaluate ($WC_DETAIL)"; return 0; }
  fi
  if [ -f "$WIFI_HEAL_OFF" ]; then wd_log "wifi-heal ($1): Wi-Fi reconnect is turned off in the settings; not reconnecting"; return 0; fi
  wifi_heal_allowed || { wd_log "wifi-heal ($1): not reconnecting Wi-Fi: $WHA_WHY"; return 0; }
  wifi_heal_record
  WHB_HOW=""
  if has cmd; then
    WHB_X=$(timeout 8 cmd wifi reconnect 2>&1); WHB_RC=$?
    if [ "$WHB_RC" = 0 ] && wifi_cmd_ok "$WHB_X"; then
      WHB_HOW="cmd wifi reconnect"
      sleep "$WIFI_HEAL_WAIT"; wifi_check
    fi
  fi
  if [ -z "$WHB_HOW" ] || [ "$WC_STATE" = bad ]; then
    WHB_HOW="${WHB_HOW:+$WHB_HOW, then }Wi-Fi off/on"
    if has svc; then timeout 8 svc wifi disable >/dev/null 2>&1; sleep 2; timeout 8 svc wifi enable >/dev/null 2>&1
    elif has cmd; then timeout 8 cmd wifi set-wifi-enabled disabled >/dev/null 2>&1; sleep 2; timeout 8 cmd wifi set-wifi-enabled enabled >/dev/null 2>&1
    else wd_log "wifi-heal ($1): no svc or cmd to switch Wi-Fi"; return 0; fi
    sleep "$WIFI_HEAL_WAIT"; wifi_check
  fi
  wd_log "wifi-heal ($1): reconnected Wi-Fi ($WHB_HOW); now $WC_STATE ($WC_DETAIL)"
  wd_notify "wifi-reconnect $1" "检测到 Wi‑Fi 无法联网，河图已自动重连 Wi‑Fi（$WHB_HOW）。可在 代理高级设置 › 网络恢复 中关闭。"
  return 0
}
# Watchdog: a detached wifi-heal (the probe and the reconnect take up to a minute). Android only.
wifi_heal_async(){
  [ -d "$CG_ESCAPE_GATE" ] || return 1
  detach_probe
  $SETSID "$0" wifi-heal "$1" </dev/null >/dev/null 2>&1 &
  cg_escape "$!"
  return 0
}
# Probe URLs NetworkMonitor uses on this ROM, best effort (the App keeps them for the next start).
probe_urls(){
  {
    if has device_config; then for PU_K in captive_portal_http_url captive_portal_https_url captive_portal_fallback_url captive_portal_other_fallback_urls; do timeout 3 device_config get connectivity "$PU_K" 2>/dev/null; done; fi
    if has dumpsys; then timeout 6 dumpsys network_stack 2>/dev/null | grep -i -E 'https?://' | head -n 2000; timeout 6 dumpsys connectivity 2>/dev/null | grep -i -E 'https?://' | head -n 500; fi
    if has logcat; then timeout 4 logcat -d -t 4000 2>/dev/null | grep -i -E 'NetworkMonitor|captive|validat' | grep -i -E 'https?://' | head -n 500; fi
  } | grep -o -E 'https?://[A-Za-z0-9._-]+(:[0-9]+)?/[A-Za-z0-9._/-]*' | grep -i -E '/(generate_?204|gen_204)' | sort -u | head -n 32
  return 0
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
  # The monitor belongs to the same session; every path that ends the watchdog ends it too.
  stopmonitor
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
# 6.0.0 (field report: only foreign sites fail, node delays are fine, stop/start does not help). Mihomo opens
# $RUN/cache.db (selected nodes, the persisted fake-IP pool) with a 1 s lock wait and, when another process
# still holds the lock, runs without it: every fake-IP connection then fails for the core's whole life while
# real-IP traffic and node delay tests keep working. sing-box refuses to start instead. Host-reproduced with
# Mihomo v1.19.31 and sing-box 1.14.3. PIDs holding a lock on file $1 (/proc/locks by inode, then checked
# against the holder's open files).
cache_lock_pids(){
  CLP_I=$(ls -i "$1" 2>/dev/null | awk 'NR == 1 {print $1}')
  case "$CLP_I" in ''|*[!0-9]*) return 0;; esac
  for CLP_P in $(awk -v ino="$CLP_I" '{ for (i = 2; i <= NF; i++) if ($i ~ /^[0-9a-fA-F]+:[0-9a-fA-F]+:[0-9]+$/) { split($i, a, ":"); if (a[3] == ino && $(i-1) ~ /^[0-9]+$/) print $(i-1); break } }' "${CACHE_LOCKS:-/proc/locks}" 2>/dev/null | sort -u); do
    for CLP_FD in "${CORE_PROCFS:-/proc}/$CLP_P/fd/"*; do
      if [ "$CLP_FD" -ef "$1" ]; then printf '%s\n' "$CLP_P"; break; fi
    done
  done
}
# Before a Mihomo or sing-box core starts: Hetu's own core processes still holding $RUN/cache.db (a leftover
# core, a config check) end; a lock that stays held moves the file aside (cache.db.held-<time>, kept) so the
# new core starts on a fresh one. CACHE_GUARD_NOTE reports it for the start message.
core_cache_guard(){
  CACHE_GUARD_NOTE=""
  case "${START_KIND:-mihomo}" in mihomo|sing-box) ;; *) return 0;; esac
  CCG_F="$RUN/cache.db"; [ -f "$CCG_F" ] || return 0
  CCG_P=$(cache_lock_pids "$CCG_F" | tr '\n' ' '); CCG_P=${CCG_P% }
  [ -n "$CCG_P" ] || return 0
  CCG_OWN=""
  for CCG_X in $CCG_P; do
    case "$(readlink "${CORE_PROCFS:-/proc}/$CCG_X/exe" 2>/dev/null)" in "$BASE/bin/core"|"$BASE/bin/core (deleted)") CCG_OWN="$CCG_OWN $CCG_X"; kill "$CCG_X" 2>/dev/null || true;; esac
  done
  CCG_N=0
  while [ -n "$CCG_OWN" ] && [ "$CCG_N" -lt 40 ]; do
    [ -n "$(cache_lock_pids "$CCG_F")" ] || break
    [ "$CCG_N" != 20 ] || for CCG_X in $CCG_OWN; do kill -9 "$CCG_X" 2>/dev/null || true; done
    sleep 0.05; CCG_N=$((CCG_N+1))
  done
  CCG_LEFT=$(cache_lock_pids "$CCG_F" | tr '\n' ' '); CCG_LEFT=${CCG_LEFT% }
  if [ -z "$CCG_LEFT" ]; then
    wd_log "start: $CCG_F was locked by Hetu's own process(es)$CCG_OWN; ended them"
    return 0
  fi
  CCG_TO="$RUN/cache.db.held-$(date +%s)"
  if mv "$CCG_F" "$CCG_TO" 2>/dev/null; then
    wd_log "start: $CCG_F stays locked by pid(s) $CCG_LEFT; moved to $CCG_TO, the core starts with a new one"
    CACHE_GUARD_NOTE="核心缓存文件被其他进程（PID $CCG_LEFT）占用，已换用新的缓存文件，之前选择的节点可能恢复为默认"
  else
    wd_log "start: $CCG_F stays locked by pid(s) $CCG_LEFT and could not be moved"
    CACHE_GUARD_NOTE="核心缓存文件被其他进程（PID $CCG_LEFT）占用且无法替换，外网连接可能失败；请重启手机"
  fi
  return 0
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

# TPROXY replies and shared (tethered) traffic arrive on an interface the reverse-path check may
# reject when rp_filter is strict (1) somewhere. The kernel uses max(all, iface), so loose mode is
# all=2. Only a strict setup is touched, ip_forward only when it is off for sharing, and the journal
# is written before the first change. Restore puts back an original only while the value is still
# the one written here (netd or the user may have changed it since). Never fails a start or stop.
net_sysctl_apply(){
  NS_NOTES=""
  NS_RP=0; case "$START_MODE" in tproxy|enhance) NS_RP=1;; esac
  [ "${NEED_TP:-0}" != 1 ] || NS_RP=1; [ "$START_SHARE" != 1 ] || NS_RP=1
  NS_J=""; NS_TAB=$(printf '\t')
  if [ "$NS_RP" = 1 ] && [ -w "$SYSCTL_NET/ipv4/conf/all/rp_filter" ]; then
    NS_STRICT=0
    for NS_P in "$SYSCTL_NET"/ipv4/conf/*/rp_filter; do
      [ -e "$NS_P" ] || continue; NS_V=""; read -r NS_V < "$NS_P" 2>/dev/null || continue
      [ "$NS_V" != 1 ] || NS_STRICT=1
    done
    NS_ALL=""; read -r NS_ALL < "$SYSCTL_NET/ipv4/conf/all/rp_filter" 2>/dev/null || NS_ALL=""
    case "$NS_ALL" in 0|1) [ "$NS_STRICT" != 1 ] || NS_J="${NS_J}$SYSCTL_NET/ipv4/conf/all/rp_filter${NS_TAB}$NS_ALL${NS_TAB}2
";; esac
  fi
  if [ "$START_SHARE" = 1 ] && [ -w "$SYSCTL_NET/ipv4/ip_forward" ]; then
    NS_F=""; read -r NS_F < "$SYSCTL_NET/ipv4/ip_forward" 2>/dev/null || NS_F=""
    [ "$NS_F" != 0 ] || NS_J="${NS_J}$SYSCTL_NET/ipv4/ip_forward${NS_TAB}0${NS_TAB}1
"
  fi
  [ -n "$NS_J" ] || return 0
  printf '%s' "$NS_J" > "$SYSCTL_TUNE_STATE.new.$$" && mv -f "$SYSCTL_TUNE_STATE.new.$$" "$SYSCTL_TUNE_STATE" || { rm -f "$SYSCTL_TUNE_STATE.new.$$"; NS_NOTES="rp_filter/ip_forward 未调整：无法记录原值"; return 0; }
  while IFS="$NS_TAB" read -r NS_P NS_OLD NS_SET; do
    [ -n "$NS_P" ] || continue
    if printf '%s\n' "$NS_SET" > "$NS_P" 2>/dev/null; then NS_NOTES="${NS_NOTES:+$NS_NOTES；}${NS_P#$SYSCTL_NET/ipv4/} $NS_OLD→$NS_SET（停止后恢复）"
    else NS_NOTES="${NS_NOTES:+$NS_NOTES；}${NS_P#$SYSCTL_NET/ipv4/} 写入失败，保持 $NS_OLD"; fi
  done < "$SYSCTL_TUNE_STATE"
  return 0
}
net_sysctl_restore(){
  [ -f "$SYSCTL_TUNE_STATE" ] || return 0
  NR_TAB=$(printf '\t'); NR_FAILED=0
  while IFS="$NR_TAB" read -r NR_P NR_OLD NR_SET; do
    case "$NR_P" in "$SYSCTL_NET"/ipv4/conf/*/rp_filter|"$SYSCTL_NET"/ipv4/ip_forward) ;; *) continue;; esac
    case "$NR_OLD:$NR_SET" in [0-2]:[0-2]) ;; *) continue;; esac
    [ -e "$NR_P" ] || continue
    NR_NOW=""; read -r NR_NOW < "$NR_P" 2>/dev/null || { NR_FAILED=1; continue; }
    [ "$NR_NOW" = "$NR_SET" ] || continue
    printf '%s\n' "$NR_OLD" > "$NR_P" 2>/dev/null || NR_FAILED=1
  done < "$SYSCTL_TUNE_STATE"
  # A failed write keeps the journal so the next stop/start retries it.
  [ "$NR_FAILED" != 0 ] || rm -f "$SYSCTL_TUNE_STATE"
  return 0
}
# START_MEM / START_IO carry a "cg:" prefix when 资源限制 → cgroup 硬限制 is on (App setting, default off).
tuning_cgroup_flags(){
  START_CG_MEM=0; START_CG_IO=0
  case "$START_MEM" in cg:*) START_MEM=${START_MEM#cg:}; START_CG_MEM=1;; esac
  case "$START_IO" in cg:*) START_IO=${START_IO#cg:}; START_CG_IO=1;; esac
  [ -n "$START_MEM" ] || START_CG_MEM=0; [ -n "$START_IO" ] || START_CG_IO=0
  return 0
}
cg_v1_dir(){
  for CV_D in $1; do [ -d "$CV_D" ] && [ -e "$CV_D/$2" ] && { printf '%s' "$CV_D"; return 0; }; done
  return 1
}
cg_join(){
  # $1 cgroup dir, $2 pid. v2 has cgroup.procs; v1 accepts cgroup.procs or tasks.
  if [ -e "$1/cgroup.procs" ]; then printf '%s\n' "$2" > "$1/cgroup.procs" 2>/dev/null && return 0; fi
  [ -e "$1/tasks" ] && printf '%s\n' "$2" > "$1/tasks" 2>/dev/null
}
cg_record(){ grep -qxF "$1" "$CG_STATE" 2>/dev/null || printf '%s\n' "$1" >> "$CG_STATE" 2>/dev/null || true; }
core_cgroup(){
  CG_PID="$1"; CG_REPORT=""; CG_MEM_DONE=0; CG_IO_DONE=0
  [ "$START_CG_MEM" = 1 ] || [ "$START_CG_IO" = 1 ] || return 0
  CG_V2=$(awk '$3=="cgroup2" {print $2; exit}' "$CG_MOUNTS" 2>/dev/null)
  if [ -n "$CG_V2" ] && [ -d "$CG_V2" ]; then
    CG_DIR="$CG_V2/hetu_core"
    [ "$START_CG_MEM" != 1 ] || printf '+memory\n' > "$CG_V2/cgroup.subtree_control" 2>/dev/null || true
    [ "$START_CG_IO" != 1 ] || printf '+io\n' > "$CG_V2/cgroup.subtree_control" 2>/dev/null || true
    if mkdir -p "$CG_DIR" 2>/dev/null; then
      cg_record "$CG_DIR"
      CG_MEM_HIGH=0
      # memory.high throttles and reclaims at the limit instead of OOM-killing or wedging the core
      # in memcg reclaim; memory.max stays as a hard ceiling 50 % above it.
      if [ "$START_CG_MEM" = 1 ] && [ -e "$CG_DIR/memory.max" ] && [ -e "$CG_DIR/memory.high" ] && printf '%s\n' "$START_MEM_BYTES" > "$CG_DIR/memory.high" 2>/dev/null; then
        printf '%s\n' "$((START_MEM_BYTES + START_MEM_BYTES / 2))" > "$CG_DIR/memory.max" 2>/dev/null || true
        CG_MEM_DONE=1; CG_MEM_HIGH=1
      elif [ "$START_CG_MEM" = 1 ] && [ -e "$CG_DIR/memory.max" ] && printf '%s\n' "$START_MEM_BYTES" > "$CG_DIR/memory.max" 2>/dev/null; then CG_MEM_DONE=1; fi
      if [ "$START_CG_IO" = 1 ] && [ -e "$CG_DIR/io.weight" ] && printf 'default %s\n' "$(( (8-START_IO)*25 ))" > "$CG_DIR/io.weight" 2>/dev/null; then CG_IO_DONE=1; fi
      if [ "$CG_MEM_DONE$CG_IO_DONE" != 00 ]; then
        if cg_join "$CG_DIR" "$CG_PID"; then
          [ "$CG_MEM_DONE" != 1 ] || CG_REPORT="${CG_REPORT}memcg=v2:$START_MEM_BYTES "
          [ "$CG_MEM_DONE$CG_MEM_HIGH" != 11 ] || CG_REPORT="${CG_REPORT}memhigh=v2 "
          [ "$CG_IO_DONE" != 1 ] || CG_REPORT="${CG_REPORT}iocg=v2:$(( (8-START_IO)*25 )) "
        else CG_MEM_DONE=0; CG_IO_DONE=0; fi
      fi
    fi
  fi
  if [ "$START_CG_MEM" = 1 ] && [ "$CG_MEM_DONE" != 1 ]; then
    CG_BASE=$(cg_v1_dir "$CG_V1_MEMORY" memory.limit_in_bytes) && CG_DIR="$CG_BASE/hetu_core" && mkdir -p "$CG_DIR" 2>/dev/null &&
      cg_record "$CG_DIR" && printf '%s\n' "$START_MEM_BYTES" > "$CG_DIR/memory.limit_in_bytes" 2>/dev/null && cg_join "$CG_DIR" "$CG_PID" &&
      { CG_MEM_DONE=1; CG_REPORT="${CG_REPORT}memcg=v1:$START_MEM_BYTES "; }
    [ "$CG_MEM_DONE" = 1 ] || CG_REPORT="${CG_REPORT}memcg=failed "
  fi
  if [ "$START_CG_IO" = 1 ] && [ "$CG_IO_DONE" != 1 ]; then
    CG_BASE=$(cg_v1_dir "$CG_V1_BLKIO" blkio.weight) && CG_DIR="$CG_BASE/hetu_core" && mkdir -p "$CG_DIR" 2>/dev/null &&
      cg_record "$CG_DIR" && printf '%s\n' "$(( (8-START_IO)*125 ))" > "$CG_DIR/blkio.weight" 2>/dev/null && cg_join "$CG_DIR" "$CG_PID" &&
      { CG_IO_DONE=1; CG_REPORT="${CG_REPORT}iocg=v1:$(( (8-START_IO)*125 )) "; }
    [ "$CG_IO_DONE" = 1 ] || CG_REPORT="${CG_REPORT}iocg=failed "
  fi
  CG_TUNED=$(head -n 1 "$RUN/tuning-status" 2>/dev/null || true); [ "$CG_TUNED" != none ] || CG_TUNED=""
  printf '%s\n' "$CG_TUNED$CG_REPORT" > "$RUN/tuning-status" 2>/dev/null || true
  printf '[河图] cgroup 资源限制：%s\n' "$CG_REPORT" >> "$LOG" 2>/dev/null || true
  return 0
}
# After the core exited: remove the (now empty) Hetu cgroups. Best effort, never fails a stop.
cgroup_release(){
  [ -f "$CG_STATE" ] || return 0
  CR_LEFT=""
  while read -r CR_D; do
    case "$CR_D" in */hetu_core) ;; *) continue;; esac
    [ -d "$CR_D" ] || continue
    rmdir "$CR_D" 2>/dev/null || CR_LEFT="$CR_LEFT$CR_D
"
  done < "$CG_STATE"
  if [ -n "$CR_LEFT" ]; then printf '%s' "$CR_LEFT" > "$CG_STATE" 2>/dev/null || true; else rm -f "$CG_STATE"; fi
  return 0
}
# Lines the App put into the startup copy (dangling-reference cleanup) and this start's own notes
# go to the top of the fresh core log, so the core log explains why the core saw a changed group.
start_log_notices(){
  grep '^# HETU_NOTICE: ' "$1" 2>/dev/null | head -n 64 | sed 's/^# HETU_NOTICE: /[河图] /' >> "$LOG" 2>/dev/null || true
  [ -z "$NS_NOTES" ] || printf '[河图] 网络参数：%s\n' "$NS_NOTES" >> "$LOG" 2>/dev/null || true
  return 0
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
# ---------------------------------------------------------------- shared network ingress scope
# The App writes $BASE/policy/share (IFACES=tether interfaces, MODE=direct|only, MACS=only-list).
# Without it (or with an empty list in direct mode) the shared chains keep the legacy, unscoped
# form. Scoped: every shared PREROUTING/FORWARD chain dispatches only its tether ingress (and lo
# for the local TPROXY re-route) with -g into "<chain>_S"; everything else RETURNs, so upstream
# replies (wlan0/rmnet IPv6 without NAT) and foreign ingress never reach TPROXY or a guard.
SHR_IFACES=""; SHR_IFACES_W=""; SHR_MODE=direct; SHR_ONLY=""; SHR_ONLY_W=""; SHR_DROPPED=""; SHR_SCOPED=0; SHR_CHAIN=""
share_policy_value(){ sed -n "s/^$1=//p" "$BASE/policy/share" 2>/dev/null | head -n 1; }
# Interfaces carrying the default route right now (v4/v6). Never a shared ingress.
share_upstream_ifaces(){
  for SHU_F in 4 6; do
    ip -"$SHU_F" route get "$( [ "$SHU_F" = 4 ] && echo 8.8.8.8 || echo 2001:4860:4860::8888 )" 2>/dev/null |
      awk '{ for (i = 1; i < NF; i++) if ($i == "dev") print $(i+1) }'
  done | sort -u | tr '\n' ' '
}
share_iface_upstream(){
  SHI_X="$1"
  for SHI_U in $2; do
    [ "$SHI_X" != "$SHI_U" ] || return 0
    case "$SHI_X" in *+) case "$SHI_U" in "${SHI_X%+}"*) return 0;; esac;; esac
  done
  return 1
}
share_apply(){
  SHR_IFACES="$1"; SHR_MODE="${2:-direct}"; SHR_ONLY="$3"; SHR_SCOPED="${4:-0}"
  SHR_IFACES_W=$(printf '%s' "$SHR_IFACES" | tr ',' ' '); SHR_ONLY_W=$(printf '%s' "$SHR_ONLY" | tr ',' ' ')
}
# Start: read and validate the policy. Upstream, cellular, loopback and virtual interfaces are
# dropped (reported, never captured). A whitelist that this iptables cannot match fails the start:
# degrading would proxy every client instead of the listed ones.
share_prepare(){
  share_apply "" direct "" 0; SHR_DROPPED=""
  [ "$START_SHARE" = 1 ] && [ -r "$BASE/policy/share" ] || return 0
  SHP_I=$(share_policy_value IFACES); SHP_M=$(share_policy_value MODE); SHP_O=$(share_policy_value MACS)
  split_safe_ifaces "$SHP_I" || fail "共享接口列表无效"
  split_safe_macs "$SHP_O" || fail "共享网络 MAC 白名单无效"
  case "$SHP_M" in ''|direct) SHP_M=direct;; only) ;; *) fail "共享网络 MAC 模式无效";; esac
  SHP_UP=$(share_upstream_ifaces); SHP_KEEP=""
  for SHP_X in $(printf '%s' "$SHP_I" | tr ',' ' '); do
    case "$SHP_X" in lo*|rmnet*|ccmni*|dummy*|tun*|hetu*|ifb*) SHR_DROPPED="${SHR_DROPPED:+$SHR_DROPPED,}$SHP_X"; continue;; esac
    if share_iface_upstream "$SHP_X" "$SHP_UP"; then SHR_DROPPED="${SHR_DROPPED:+$SHR_DROPPED,}$SHP_X"; continue; fi
    case ",$SHP_KEEP," in *",$SHP_X,"*) ;; *) SHP_KEEP="${SHP_KEEP:+$SHP_KEEP,}$SHP_X";; esac
  done
  SHP_SCOPED=0; if [ -n "$SHP_I" ] || [ "$SHP_M" = only ]; then SHP_SCOPED=1; fi
  if [ "$SHP_M" = only ]; then
    # The direct list has no meaning in whitelist mode.
    START_SHARED_MACS=""
    if [ -n "$SHP_O" ]; then probemac "$(first_mac "$SHP_O")" || fail "当前 iptables 不支持按 MAC 匹配，共享网络白名单模式无法安全启用"; fi
  fi
  share_apply "$SHP_KEEP" "$SHP_M" "$SHP_O" "$SHP_SCOPED"
  [ -z "$SHR_DROPPED" ] || printf '%s share dropped=%s upstream=%s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$SHR_DROPPED" "$SHP_UP" >> "$RUN/share.log" 2>/dev/null || true
}
share_load_session(){
  share_apply "$(sed -n 's/^SHARE_IFACES=//p' "$SESSION" 2>/dev/null | head -n 1)" \
    "$(sed -n 's/^SHARE_MAC_MODE=//p' "$SESSION" 2>/dev/null | head -n 1)" \
    "$(sed -n 's/^SHARE_ONLY_MACS=//p' "$SESSION" 2>/dev/null | head -n 1)" \
    "$(sed -n 's/^SHARE_SCOPED=//p' "$SESSION" 2>/dev/null | head -n 1)"
  SHR_DROPPED=$(sed -n 's/^SHARE_DROPPED=//p' "$SESSION" 2>/dev/null | head -n 1)
  split_safe_ifaces "$SHR_IFACES" && split_safe_macs "$SHR_ONLY" || share_apply "" direct "" 1
  case "$SHR_MODE" in direct|only) ;; *) SHR_MODE=direct;; esac
  [ "$SHR_SCOPED" = 1 ] || SHR_SCOPED=0
}
# In a shared chain, after its bypass RETURNs: legacy = the direct MAC RETURNs only. Scoped =
# dispatch to "<chain>_S" (caller appends the rest to $SHR_CHAIN). $5 reuse: Kill Switch guard.
share_scope(){
  SHS_BIN="$1"; SHS_T="$2"; SHS_C="$3"; SHS_MACS="$4"; SHR_CHAIN="$SHS_C"
  if [ "$SHR_SCOPED" != 1 ]; then shared_mac_returns "$SHS_BIN" "$SHS_T" "$SHS_C" "$SHS_MACS"; return; fi
  SHS_TAIL="${SHS_C}_S"
  if [ "${5:-}" = reuse ]; then "$SHS_BIN" -t "$SHS_T" -N "$SHS_TAIL" >/dev/null 2>&1 || true; "$SHS_BIN" -t "$SHS_T" -F "$SHS_TAIL" || return 1
  else "$SHS_BIN" -t "$SHS_T" -N "$SHS_TAIL" || return 1; fi
  # PREROUTING chains also carry this device's own re-routed (marked) traffic on lo.
  if [ "$SHS_T" != filter ]; then "$SHS_BIN" -t "$SHS_T" -A "$SHS_C" -i lo -g "$SHS_TAIL" || return 1; fi
  if [ "$SHR_MODE" = only ]; then
    for SHS_M in $SHR_ONLY_W; do
      if [ -n "$SHR_IFACES_W" ]; then
        for SHS_X in $SHR_IFACES_W; do "$SHS_BIN" -t "$SHS_T" -A "$SHS_C" -i "$SHS_X" -m mac --mac-source "$SHS_M" -g "$SHS_TAIL" || return 1; done
      else "$SHS_BIN" -t "$SHS_T" -A "$SHS_C" -m mac --mac-source "$SHS_M" -g "$SHS_TAIL" || return 1; fi
    done
  else
    shared_mac_returns "$SHS_BIN" "$SHS_T" "$SHS_C" "$SHS_MACS" || return 1
    for SHS_X in $SHR_IFACES_W; do "$SHS_BIN" -t "$SHS_T" -A "$SHS_C" -i "$SHS_X" -g "$SHS_TAIL" || return 1; done
  fi
  "$SHS_BIN" -t "$SHS_T" -A "$SHS_C" -j RETURN || return 1
  SHR_CHAIN="$SHS_TAIL"
}
share_drop_tails(){
  SDT_BIN="$1"
  cn_drop_tail "$SDT_BIN" mangle "${MPRE}_S"; cn_drop_tail "$SDT_BIN" nat "${NPRE}_S"; cn_drop_tail "$SDT_BIN" nat "${DNSPRE}_S"
  cn_drop_tail "$SDT_BIN" filter "${WRFWD}_S"; cn_drop_tail "$SDT_BIN" filter "${QUICFWD}_S"; cn_drop_tail "$SDT_BIN" filter "${V6FWD}_S"
  if [ "$PRESERVE_KILL" != 1 ]; then cn_drop_tail "$SDT_BIN" filter "${KFWD}_S"; fi
}
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
blacklist_returns(){ BIN="$1"; T="$2"; C="$3"; S="$4"; LIST="$5"; [ "$S" = blacklist ] || return 0; [ "$EBPF_RULES" != 1 ] || { ebpf_return "$BIN" "$T" "$C" hetu_uid_list; return; }; [ -z "$LIST" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS; for U in "$@"; do owner_return "$BIN" "$T" "$C" --uid-owner "$U" || return 1; done; }
direct_uid_returns(){ BIN="$1"; T="$2"; C="$3"; LIST="$4"; [ "$EBPF_RULES" != 1 ] || { ebpf_return "$BIN" "$T" "$C" hetu_uid_direct; return; }; [ -z "$LIST" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS; for U in "$@"; do owner_return "$BIN" "$T" "$C" --uid-owner "$U" || return 1; done; }
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
  if [ "$S" = whitelist ] && [ "$EBPF_RULES" = 1 ]; then if [ -n "$DPORT" ]; then "$BIN" -t "$T" -A "$C" -m bpf --object-pinned "$EBPF_PIN/prog_hetu_uid_list" -p "$PROTO" --dport "$DPORT" -j MARK --set-xmark "$MARKV" || return 1; else "$BIN" -t "$T" -A "$C" -m bpf --object-pinned "$EBPF_PIN/prog_hetu_uid_list" -p "$PROTO" -j MARK --set-xmark "$MARKV" || return 1; fi
  elif [ "$S" = whitelist ]; then OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS; for U in "$@"; do if [ -n "$DPORT" ]; then "$BIN" -t "$T" -A "$C" -m owner --uid-owner "$U" -p "$PROTO" --dport "$DPORT" -j MARK --set-xmark "$MARKV" || return 1; else "$BIN" -t "$T" -A "$C" -m owner --uid-owner "$U" -p "$PROTO" -j MARK --set-xmark "$MARKV" || return 1; fi; done
  else if [ -n "$DPORT" ]; then "$BIN" -t "$T" -A "$C" -p "$PROTO" --dport "$DPORT" -j MARK --set-xmark "$MARKV" || return 1; else "$BIN" -t "$T" -A "$C" -p "$PROTO" -j MARK --set-xmark "$MARKV" || return 1; fi; fi
}
scoped_redirect(){
  BIN="$1"; T="$2"; C="$3"; S="$4"; LIST="$5"; PROTO="$6"; DPORT="$7"; TO="$8"
  if [ "$S" = whitelist ] && [ "$EBPF_RULES" = 1 ]; then if [ -n "$DPORT" ]; then "$BIN" -t "$T" -A "$C" -m bpf --object-pinned "$EBPF_PIN/prog_hetu_uid_list" -p "$PROTO" --dport "$DPORT" -j REDIRECT --to-ports "$TO" || return 1; else "$BIN" -t "$T" -A "$C" -m bpf --object-pinned "$EBPF_PIN/prog_hetu_uid_list" -p "$PROTO" -j REDIRECT --to-ports "$TO" || return 1; fi
  elif [ "$S" = whitelist ]; then OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS; for U in "$@"; do if [ -n "$DPORT" ]; then "$BIN" -t "$T" -A "$C" -m owner --uid-owner "$U" -p "$PROTO" --dport "$DPORT" -j REDIRECT --to-ports "$TO" || return 1; else "$BIN" -t "$T" -A "$C" -m owner --uid-owner "$U" -p "$PROTO" -j REDIRECT --to-ports "$TO" || return 1; fi; done
  else if [ -n "$DPORT" ]; then "$BIN" -t "$T" -A "$C" -p "$PROTO" --dport "$DPORT" -j REDIRECT --to-ports "$TO" || return 1; else "$BIN" -t "$T" -A "$C" -p "$PROTO" -j REDIRECT --to-ports "$TO" || return 1; fi; fi
}
# Explicit protocol disabling should report an unavailable UDP path immediately.
# Silent DROP leaves clients retrying a blocked QUIC path until their own timeout;
# REJECT (the default ICMP/ICMPv6 port-unreachable) permits prompt error handling.
scoped_reject_quic(){
  BIN="$1"; C="$2"; S="$3"; LIST="$4"
  if [ "$S" = whitelist ] && [ "$EBPF_RULES" = 1 ]; then "$BIN" -t filter -A "$C" -m bpf --object-pinned "$EBPF_PIN/prog_hetu_uid_list" -p udp --dport 443 -j REJECT || return 1
  elif [ "$S" = whitelist ]; then OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS; for U in "$@"; do "$BIN" -t filter -A "$C" -m owner --uid-owner "$U" -p udp --dport 443 -j REJECT || return 1; done
  else "$BIN" -t filter -A "$C" -p udp --dport 443 -j REJECT || return 1; fi
}
scoped_reject_all(){
  BIN="$1"; C="$2"; S="$3"; LIST="$4"
  if [ "$S" = whitelist ] && [ "$EBPF_RULES" = 1 ]; then "$BIN" -t filter -A "$C" -m bpf --object-pinned "$EBPF_PIN/prog_hetu_uid_list" -j REJECT || return 1
  elif [ "$S" = whitelist ]; then OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS; for U in "$@"; do "$BIN" -t filter -A "$C" -m owner --uid-owner "$U" -j REJECT || return 1; done
  else "$BIN" -t filter -A "$C" -j REJECT || return 1; fi
}
scoped_reject_unmarked_udp(){
  BIN="$1"; C="$2"; S="$3"; LIST="$4"
  if [ "$S" = whitelist ] && [ "$EBPF_RULES" = 1 ]; then
    "$BIN" -t filter -A "$C" -m bpf --object-pinned "$EBPF_PIN/prog_hetu_uid_list" -p udp -j REJECT || return 1
  elif [ "$S" = whitelist ]; then
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
  [ "$EBPF_RULES" != 1 ] || { ebpf_return xt4 "$T" "$C" hetu_bypass4; return; }
  [ -z "$CIDRS" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $CIDRS; IFS=$OLDIFS; for NET in "$@"; do case "$NET" in *:*) ;; *) xt4 -t "$T" -A "$C" -d "$NET" -j RETURN || return 1;; esac; done
}
bypass6(){
  C="$1"; T="$2"; CIDRS="$3"
  OLDIFS=$IFS; IFS=,; set -- $LAN_RETURN_V6; IFS=$OLDIFS
  for NET in "$@"; do xt6 -t "$T" -A "$C" -d "$NET" -j RETURN || return 1; done
  [ "$EBPF_RULES" != 1 ] || { ebpf_return xt6 "$T" "$C" hetu_bypass6; return; }
  [ -z "$CIDRS" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $CIDRS; IFS=$OLDIFS; for NET in "$@"; do case "$NET" in *:*) xt6 -t "$T" -A "$C" -d "$NET" -j RETURN || return 1;; esac; done
}

# ---------------------------------------------------------------- CNIP (ipset)
cnip_policy_value(){ sed -n "s/^$1=//p" "$BASE/policy/cnip" 2>/dev/null | head -n 1; }
cnip_load_policy(){
  CNP_V4=0; CNP_V6=0; CN_FORCE=""; CN_TCPONLY=0; CN_XT=0
  [ -r "$BASE/policy/cnip" ] || return 1
  # The iptables CIDR tree (no ipset binary) is opt-in only: on ColorOS it left ~1000 mangle
  # chains behind and slowed every later iptables call until starts timed out (4.0.0).
  [ "$(cnip_policy_value XT)" = 1 ] && CN_XT=1
  CNP_V4=$(cnip_policy_value V4); CNP_V6=$(cnip_policy_value V6); CN_FORCE=$(cnip_policy_value FORCE)
  # Optional (default off): only TCP to CN addresses bypasses the core; UDP/QUIC still reaches it.
  [ "$(cnip_policy_value TCPONLY)" = 1 ] && CN_TCPONLY=1
  bool "$CNP_V4" || CNP_V4=0; bool "$CNP_V6" || CNP_V6=0
  split_safe_uids "$CN_FORCE" || CN_FORCE=""
  [ "$CNP_V4" = 1 ] || [ "$CNP_V6" = 1 ]
}
# What the running session installed (watchdog Kill Switch, status, reload read it back).
cnip_load_session(){
  CNIP_STATE=$(sed -n 's/^CNIP=//p' "$SESSION" 2>/dev/null | head -n 1); [ -n "$CNIP_STATE" ] || CNIP_STATE=off
  CN4_ON=0; CN6_ON=0; CN_FORCE=""; CN_TCPONLY=0
  [ "$CNIP_STATE" = ipset ] || [ "$CNIP_STATE" = iptables ] || return 0
  [ "$(sed -n 's/^CNIP_TCPONLY=//p' "$SESSION" 2>/dev/null | head -n 1)" != 1 ] || CN_TCPONLY=1
  [ "$(sed -n 's/^CNIP_V4=//p' "$SESSION" 2>/dev/null | head -n 1)" != 1 ] || CN4_ON=1
  [ "$(sed -n 's/^CNIP_V6=//p' "$SESSION" 2>/dev/null | head -n 1)" != 1 ] || CN6_ON=1
  CN_FORCE=$(sed -n 's/^CNIP_FORCE=//p' "$SESSION" 2>/dev/null | head -n 1)
  split_safe_uids "$CN_FORCE" || CN_FORCE=""
}
cnip_ipset(){ if [ "$START_ACTIVE" = 1 ]; then timeout -s TERM -k 1 10 ipset "$@"; else ipset "$@"; fi; }
cnip_set_exists(){ cnip_ipset list -n "$1" >/dev/null 2>&1; }
# The kernel must offer hash:net sets AND the iptables set match, else nothing is installed.
cnip_capable(){
  has ipset || return 1
  cnip_ipset destroy hetu_probe >/dev/null 2>&1 || true
  cnip_ipset create hetu_probe hash:net family inet -exist >/dev/null 2>&1 || return 1
  CC_OK=0
  if xt4 -t mangle -N HETU_PROBE >/dev/null 2>&1 || xt4 -t mangle -F HETU_PROBE >/dev/null 2>&1; then
    xt4 -t mangle -A HETU_PROBE -m set --match-set hetu_probe dst -j RETURN >/dev/null 2>&1 && CC_OK=1
    xt4 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true; xt4 -t mangle -X HETU_PROBE >/dev/null 2>&1 || true
  fi
  cnip_ipset destroy hetu_probe >/dev/null 2>&1 || true
  [ "$CC_OK" = 1 ]
}
cnip_file_sig(){ cksum < "$1" 2>/dev/null | awk '{print $1 ":" $2}'; }
# Import one family into hetu_cn4/hetu_cn6. Unchanged data (same cksum as the stamp) and an
# existing set are reused across starts; otherwise a fresh *_new set is restored and swapped in
# atomically (or renamed on first use), so rules referencing the set never see a partial list.
cnip_import(){
  CI_F="$1"; CN_IMPORTED=0
  if [ "$CI_F" = 6 ]; then CI_SET=$CN_SET6; CI_FAM=inet6; CI_FILE=$CN_FILE6; CI_MIN=500; CI_MAX=30000
  else CI_SET=$CN_SET4; CI_FAM=inet; CI_FILE=$CN_FILE4; CI_MIN=1000; CI_MAX=20000; fi
  [ -s "$CI_FILE" ] || { CNIP_REASON="${CNIP_REASON:+$CNIP_REASON,}v$CI_F-data-missing"; return 1; }
  CI_SIG=$(cnip_file_sig "$CI_FILE"); [ -n "$CI_SIG" ] || return 1
  CI_STAMP="$RUN/state/cnip$CI_F.stamp"
  if cnip_set_exists "$CI_SET" && [ "$(cat "$CI_STAMP" 2>/dev/null)" = "$CI_SIG" ]; then return 0; fi
  CI_TMP="${CI_SET}_new"; CI_BATCH="$RUN/.cnip$CI_F.$$"
  cnip_ipset destroy "$CI_TMP" >/dev/null 2>&1 || true
  awk -v s="$CI_TMP" -v f="$CI_F" '
    { sub(/\r$/, ""); sub(/[ \t]*#.*/, ""); gsub(/^[ \t]+|[ \t]+$/, ""); if ($0 == "") next
      if (f == 4) { if ($0 !~ /^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+(\/[0-9]+)?$/) next
        n = split($0, a, "/"); split(a[1], o, "."); for (i = 1; i <= 4; i++) if (o[i] + 0 > 255) next
        if (n == 2 && (a[2] + 0 < 8 || a[2] + 0 > 32)) next }
      else { if ($0 !~ /^[0-9A-Fa-f:]+(\/[0-9]+)?$/ || $0 !~ /:/) next
        n = split($0, a, "/"); if (n == 2 && (a[2] + 0 < 16 || a[2] + 0 > 128)) next }
      print "add " s " " $0 }' "$CI_FILE" > "$CI_BATCH.add" 2>/dev/null || { rm -f "$CI_BATCH.add"; return 1; }
  CI_COUNT=$(wc -l < "$CI_BATCH.add" | tr -d ' ')
  if [ "$CI_COUNT" -lt "$CI_MIN" ] || [ "$CI_COUNT" -gt "$CI_MAX" ]; then
    rm -f "$CI_BATCH.add"; CNIP_REASON="${CNIP_REASON:+$CNIP_REASON,}v$CI_F-data-invalid"; return 1
  fi
  { printf 'create %s hash:net family %s hashsize 4096 maxelem 65536\n' "$CI_TMP" "$CI_FAM"; cat "$CI_BATCH.add"; } > "$CI_BATCH"
  rm -f "$CI_BATCH.add"
  if ! cnip_ipset restore -exist < "$CI_BATCH" >/dev/null 2>&1; then
    rm -f "$CI_BATCH"; cnip_ipset destroy "$CI_TMP" >/dev/null 2>&1 || true; return 1
  fi
  rm -f "$CI_BATCH"
  if cnip_set_exists "$CI_SET"; then
    cnip_ipset swap "$CI_TMP" "$CI_SET" >/dev/null 2>&1 || { cnip_ipset destroy "$CI_TMP" >/dev/null 2>&1 || true; return 1; }
    cnip_ipset destroy "$CI_TMP" >/dev/null 2>&1 || true
  else
    cnip_ipset rename "$CI_TMP" "$CI_SET" >/dev/null 2>&1 || { cnip_ipset destroy "$CI_TMP" >/dev/null 2>&1 || true; return 1; }
  fi
  mkdir -p "$RUN/state" 2>/dev/null || true
  printf '%s\n' "$CI_SIG" > "$CI_STAMP" 2>/dev/null || true
  CN_IMPORTED=1
}
# Start: decide which families bypass in the kernel. Never fails the start; degrades instead.
cnip_prepare(){
  CN4_ON=0; CN6_ON=0; CNIP_STATE=off; CNIP_REASON=""
  cnip_load_policy || { CN_FORCE=""; return 0; }
  case "$START_MODE" in tun|ebpf) CNIP_STATE=degraded; CNIP_REASON=mode-$START_MODE; CN_FORCE=""; return 0;; esac
  if ! cnip_capable; then
    # No ipset: the iptables CIDR tree (installed by cnip_xt_install after the start's cleanup).
    CNIP_REASON=ipset-unavailable
    if [ "$CN_XT" != 1 ]; then CNIP_STATE=degraded; CN_FORCE=""; return 0; fi
    if ! cnip_xt_capable; then CNIP_STATE=degraded; CNIP_REASON="$CNIP_REASON,xt-connmark-unavailable"; CN_FORCE=""; return 0; fi
    if [ "$CNP_V4" = 1 ]; then if [ -s "$CN_FILE4" ]; then CN4_ON=1; else CNIP_REASON="$CNIP_REASON,v4-data-missing"; fi; fi
    if [ "$CNP_V6" = 1 ]; then
      if { [ "$START_V6" = enable ] || [ "$START_V6" = strict ]; } && v6supported && has ip6tables && has ip6tables-restore; then
        if [ -s "$CN_FILE6" ]; then CN6_ON=1; else CNIP_REASON="$CNIP_REASON,v6-data-missing"; fi
      else CNIP_REASON="$CNIP_REASON,v6-mode-$START_V6"; fi
    fi
    if [ "$CN4_ON" = 1 ] || [ "$CN6_ON" = 1 ]; then CNIP_STATE=iptables; else CNIP_STATE=degraded; CN_FORCE=""; fi
    return 0
  fi
  if [ "$CNP_V4" = 1 ]; then cnip_import 4 && CN4_ON=1; fi
  if [ "$CNP_V6" = 1 ]; then
    if { [ "$START_V6" = enable ] || [ "$START_V6" = strict ]; } && v6supported && has ip6tables; then cnip_import 6 && CN6_ON=1
    else CNIP_REASON="${CNIP_REASON:+$CNIP_REASON,}v6-mode-$START_V6"; fi
  fi
  if [ "$CN4_ON" = 1 ] || [ "$CN6_ON" = 1 ]; then CNIP_STATE=ipset; else CNIP_STATE=degraded; CN_FORCE=""; fi
}
# After the bypass/LAN returns of a capture or guard chain: CN destinations RETURN (no proxy, no
# guard). $4: owner = local chain, exempt UIDs jump with -g to "<chain>_T" which holds every
# later rule (the caller appends to $CN_CHAIN); mark = PREROUTING, our own marked packets must
# still reach TPROXY; plain = forwarded clients. $5 reuse: the tail may already exist (Kill Switch).
cn_split(){
  CS_BIN="$1"; CS_T="$2"; CS_C="$3"; CS_KIND="$4"; CN_CHAIN="$CS_C"
  case "$CS_BIN" in xt6*) [ "$CN6_ON" = 1 ] || return 0; CS_SET=$CN_SET6;; *) [ "$CN4_ON" = 1 ] || return 0; CS_SET=$CN_SET4;; esac
  CS_P=""; [ "$CN_TCPONLY" != 1 ] || CS_P="-p tcp"
  # ipset: the set match; iptables tree: the CN verdict bit HETU_CNC stored on the connection.
  CS_M="-m set --match-set $CS_SET dst"; [ "$CNIP_STATE" != iptables ] || CS_M="-m connmark --mark $CN_CT_CN/$CN_CT_CN"
  if [ "$CS_KIND" = owner ] && [ -n "$CN_FORCE" ]; then
    CS_TAIL="${CS_C}_T"
    if [ "${5:-}" = reuse ]; then "$CS_BIN" -t "$CS_T" -N "$CS_TAIL" >/dev/null 2>&1 || true; "$CS_BIN" -t "$CS_T" -F "$CS_TAIL" || return 1
    else "$CS_BIN" -t "$CS_T" -N "$CS_TAIL" || return 1; fi
    CS_OLDIFS=$IFS; IFS=,; set -- $CN_FORCE; IFS=$CS_OLDIFS
    for CS_U in "$@"; do "$CS_BIN" -t "$CS_T" -A "$CS_C" -m owner --uid-owner "$CS_U" -g "$CS_TAIL" || return 1; done
    "$CS_BIN" -t "$CS_T" -A "$CS_C" $CS_P $CS_M -j RETURN || return 1
    "$CS_BIN" -t "$CS_T" -A "$CS_C" -g "$CS_TAIL" || return 1
    CN_CHAIN="$CS_TAIL"; return 0
  fi
  if [ "$CS_KIND" = mark ] && [ -n "$MARK" ]; then
    "$CS_BIN" -t "$CS_T" -A "$CS_C" $CS_P -m mark ! --mark "$MARK/$MASK" $CS_M -j RETURN; return
  fi
  "$CS_BIN" -t "$CS_T" -A "$CS_C" $CS_P $CS_M -j RETURN
}
# Per-chain fallback: an unreferenced "<chain>_T" left by a failed batch sweep.
cn_drop_tail(){ cleanup_chain_absent "$1" "$2" "$3" && return 0; "$1" -t "$2" -F "$3" >/dev/null 2>&1 || true; "$1" -t "$2" -X "$3" >/dev/null 2>&1 || true; }
cn_drop_tails(){
  CDT_BIN="$1"
  cn_drop_tail "$CDT_BIN" mangle "${MOUT}_T"; cn_drop_tail "$CDT_BIN" nat "${NOUT}_T"
  cn_drop_tail "$CDT_BIN" filter "${WROUT}_T"; cn_drop_tail "$CDT_BIN" filter "${QUICOUT}_T"; cn_drop_tail "$CDT_BIN" filter "${V6OUT}_T"
  if [ "$PRESERVE_KILL" != 1 ]; then cn_drop_tail "$CDT_BIN" filter "${KOUT}_T"; fi
}
# Rules no longer reference a hetu_ set once the sweep succeeded; only then may sets go.
# keep: start-time cleanup keeps hetu_cn4/hetu_cn6 for reuse and drops temporary sets only.
cnip_sweep_sets(){
  has ipset || return 0
  CSS_ALL="${1:-all}"
  [ "$PRESERVE_KILL" != 1 ] || CSS_ALL=keep
  for CSS_S in $(cnip_ipset list -n 2>/dev/null | grep -E '^hetu_(cn[46](_new)?|probe)$'); do
    case "$CSS_ALL:$CSS_S" in keep:hetu_cn4|keep:hetu_cn6) continue;; esac
    cnip_ipset destroy "$CSS_S" >/dev/null 2>&1 || CSS_LEFT=1
  done
}
cnip_rules_reference_sets(){
  for CRR_BIN in xt4q xt6q; do
    if [ "$CRR_BIN" = xt6q ] && ! has ip6tables; then continue; fi
    for CRR_T in mangle nat filter; do
      "$CRR_BIN" -t "$CRR_T" -S 2>/dev/null | grep -q -- '--match-set hetu_' && return 0
    done
  done
  return 1
}
cnip_set_size(){ cnip_ipset list "$1" -t 2>/dev/null | awk -F': *' '/^Number of entries/ {print $2; f=1} END {if (!f) print 0}'; }
# Hot swap after the core refreshed its CN provider files (or the App deployed new ones). The
# rules keep referencing the same set names, so only the set contents change. "auto" (the
# watchdog) returns at once while both stamps still match.
cnip_reload(){ (
  trap 'release_lock' EXIT
  cnip_load_session
  [ "$CNIP_STATE" != iptables ] || cnip_xt_reload
  if [ "$CNIP_STATE" != ipset ]; then printf '{"ok":true,"reloaded":false,"cnip":"%s","message":"内核 CNIP 未生效，数据已保存，下次启动使用"}\n' "$CNIP_STATE"; exit 0; fi
  CR_DUE=0
  if [ "$CN4_ON" = 1 ] && [ "$(cnip_file_sig "$CN_FILE4")" != "$(cat "$RUN/state/cnip4.stamp" 2>/dev/null)" ]; then CR_DUE=1; fi
  if [ "$CN6_ON" = 1 ] && [ "$(cnip_file_sig "$CN_FILE6")" != "$(cat "$RUN/state/cnip6.stamp" 2>/dev/null)" ]; then CR_DUE=1; fi
  if [ "$CR_DUE" = 0 ]; then printf '{"ok":true,"reloaded":false,"cnip":"ipset","message":"CNIP 数据未变化"}\n'; exit 0; fi
  acquire_lock || { printf '{"ok":false,"message":"运行事务正在执行，请稍后重试"}\n'; exit 0; }
  CR_FAILED=0; CNIP_REASON=""
  if [ "$CN4_ON" = 1 ]; then cnip_import 4 || CR_FAILED=1; fi
  if [ "$CN6_ON" = 1 ]; then cnip_import 6 || CR_FAILED=1; fi
  printf '%s cnip-reload failed=%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$CR_FAILED" "$CNIP_REASON" >> "$RUN/cnip.log" 2>/dev/null || true
  if [ "$CR_FAILED" = 0 ]; then printf '{"ok":true,"reloaded":true,"cnip":"ipset","message":"CNIP ipset 已热更新"}\n'
  else printf '{"ok":false,"reloaded":false,"cnip":"ipset","message":"CNIP 新数据无效或导入失败，继续使用原集合"}\n'; fi
); }

# ---------------------------------------------------------------- CNIP (iptables tree, no ipset)
# The kernel needs conntrack + connmark matches and the CONNMARK target in mangle (both are used
# by Android netd itself); iptables-restore installs the whole tree in one commit per family.
cnip_xt_capable(){
  has iptables-restore || return 1
  CX_OK=0
  if xt4 -t mangle -N HETU_PROBE >/dev/null 2>&1 || xt4 -t mangle -F HETU_PROBE >/dev/null 2>&1; then
    xt4 -t mangle -A HETU_PROBE -m conntrack --ctstate NEW -m connmark ! --mark "$CN_CT_CHK/$CN_CT_CHK" -j CONNMARK --set-xmark "$CN_CT_CHK/$CN_CT_CHK" >/dev/null 2>&1 && CX_OK=1
    xt4 -t mangle -F HETU_PROBE >/dev/null 2>&1 || true; xt4 -t mangle -X HETU_PROBE >/dev/null 2>&1 || true
  fi
  [ "$CX_OK" = 1 ]
}
cnip_xt_full_restore(){ if [ "$START_ACTIVE" = 1 ]; then timeout -s TERM -k 1 20 "$1" -w 5 -T mangle; else "$1" -w 5 -T mangle; fi; }
cnip_xt_restore(){ if [ "$START_ACTIVE" = 1 ]; then timeout -s TERM -k 1 20 "$1" -w 5 --noflush; else "$1" -w 5 --noflush; fi; }
# iptables-restore body (mangle) of one family's tree, read from the CN list on stdin; the number
# of accepted networks goes to $5. Same validation and limits as the ipset import; exit 3 when the
# count is out of range. Entries stay in the deepest block chain (<= 32 per chain where the
# prefix allows), direct entries before block jumps, so overlapping lists still match.
cnip_xt_tree(){
  awk -v f="$1" -v lo="$2" -v hi="$3" -v proto="$4" -v countfile="$5" -v top=HETU_CNL -v both="$CN_CT_BOTH" -v chk="$CN_CT_CHK" '
    function hexval(s,   i, c, v) { v = 0; s = tolower(s); if (s == "" || length(s) > 4) return -1
      for (i = 1; i <= length(s); i++) { c = index("0123456789abcdef", substr(s, i, 1)); if (c == 0) return -1; v = v * 16 + c - 1 }
      return v }
    function pow2(n,   r) { r = 1; while (n-- > 0) r *= 2; return r }
    function block(v, b,   x, h1, h2, h3) {
      if (f == 4) { x = v * pow2(32 - b); h1 = int(x / 16777216); x -= h1 * 16777216; h2 = int(x / 65536); x -= h2 * 65536; h3 = int(x / 256); x -= h3 * 256
        return sprintf("%d.%d.%d.%d/%d", h1, h2, h3, x, b) }
      x = v * pow2(48 - b); h1 = int(x / 4294967296); x -= h1 * 4294967296; h2 = int(x / 65536); x -= h2 * 65536
      return sprintf("%x:%x:%x::/%d", h1, h2, x, b) }
    { sub(/\r$/, ""); sub(/[ \t]*#.*/, ""); gsub(/^[ \t]+|[ \t]+$/, ""); if ($0 == "") next
      n = split($0, a, "/")
      if (f == 4) {
        if ($0 !~ /^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+(\/[0-9]+)?$/) next
        split(a[1], o, "."); bad = 0; for (i = 1; i <= 4; i++) if (o[i] + 0 > 255) bad = 1; if (bad) next
        p = (n == 2) ? a[2] + 0 : 32; if (p < 8 || p > 32) next
        key = ((o[1] * 256 + o[2]) * 256 + o[3]) * 256 + o[4]; W = 32
      } else {
        if ($0 !~ /^[0-9A-Fa-f:]+(\/[0-9]+)?$/ || $0 !~ /:/) next
        p = (n == 2) ? a[2] + 0 : 128; if (p < 16 || p > 128) next
        if (substr(a[1], 1, 1) == ":") next
        d = index(a[1], "::"); head = (d > 0) ? substr(a[1], 1, d - 1) : a[1]
        m = split(head, h, ":"); if (d == 0 && m != 8) next; if (m > 8) next
        for (i = m + 1; i <= 3; i++) h[i] = "0"
        v1 = hexval(h[1]); v2 = hexval(h[2]); v3 = hexval(h[3]); if (v1 < 0 || v2 < 0 || v3 < 0) next
        key = (v1 * 65536 + v2) * 65536 + v3; W = 48
      }
      N++; net[N] = $0; pl[N] = p; k[N] = key; ch[N] = top
    }
    END {
      if (N < lo || N > hi) exit 3
      nl = split(f == 4 ? "8 12 16 20 24" : "16 20 24 28 32 36 40 44 48", lv, " ")
      for (l = 1; l <= nl; l++) {
        b = lv[l] + 0; split("", cnt)
        for (i = 1; i <= N; i++) cnt[ch[i]]++
        for (i = 1; i <= N; i++) {
          if (cnt[ch[i]] <= 32 || pl[i] <= b) continue
          kb = int(k[i] / pow2(W - b)); c = sprintf("HETU_CN_%d_%.0f", b, kb)
          if (!(c in par)) { par[c] = ch[i]; cidr[c] = block(kb, b); order[++nc] = c }
          ch[i] = c
        }
      }
      printf "-N HETU_CNC\n-N HETU_CNY\n-N %s\n", top
      for (j = 1; j <= nc; j++) printf "-N %s\n", order[j]
      printf "-A HETU_CNY -j CONNMARK --set-xmark %s/%s\n", both, both
      for (i = 1; i <= N; i++) printf "-A %s -d %s -g HETU_CNY\n", ch[i], net[i]
      for (j = 1; j <= nc; j++) printf "-A %s -d %s -g %s\n", par[order[j]], cidr[order[j]], order[j]
      printf "-A HETU_CNC %s-m conntrack --ctstate NEW -m connmark ! --mark %s/%s -j %s\n", proto, chk, chk, top
      printf "-A HETU_CNC -m conntrack --ctstate NEW -j CONNMARK --set-xmark %s/%s\n", chk, chk
      printf "-I PREROUTING 1 -j HETU_CNC\n-I OUTPUT 1 -j HETU_CNC\n"
      printf "%d\n", N > countfile
    }
  '
}
# Removes one family's tree and its hooks (one restore, verified). Kept with a preserved Kill
# Switch only through sweep_plan; this function always removes.
cnip_xt_drop(){
  CXD_BIN=xt$1; CXD_RESTORE=iptables-restore
  if [ "$1" = 6 ]; then has ip6tables || return 0; CXD_RESTORE=ip6tables-restore; fi
  CXD_RULES=$(cleanup_snapshot_read "$CXD_BIN" mangle) || return 1
  CXD_PLAN=$(printf '%s\n' "$CXD_RULES" | awk '
    function own(c){ return c ~ /^HETU_CN(C|Y|L|_)/ }
    $1 == "-N" && own($2) { n++; chain[n] = $2; next }
    $1 == "-A" && !own($2) { for (i = 3; i < NF; i++) if (($i == "-j" || $i == "-g") && own($(i+1))) { line = $0; sub(/^-A /, "-D ", line); print line; break } }
    END { for (i = 1; i <= n; i++) print "-F " chain[i]; for (i = 1; i <= n; i++) print "-X " chain[i] }')
  [ -n "$CXD_PLAN" ] || return 0
  has "$CXD_RESTORE" || return 1
  if ! printf '*mangle\n%s\nCOMMIT\n' "$CXD_PLAN" | cnip_xt_restore "$CXD_RESTORE" >/dev/null 2>&1; then
    # One stale line aborts the whole restore: rewrite mangle without the tree in one commit,
    # else remove piece by piece.
    CXD_SAVE=iptables-save; [ "$1" != 6 ] || CXD_SAVE=ip6tables-save
    if has "$CXD_SAVE" && CXD_DUMP=$("$CXD_SAVE" -t mangle 2>/dev/null) && printf '%s\n' "$CXD_DUMP" | grep -q '^\*mangle'; then
      printf '%s\n' "$CXD_DUMP" | grep -Ev '(^:|^-A |-j |-g )HETU_CN(C|Y|L|_)' | cnip_xt_full_restore "$CXD_RESTORE" >/dev/null 2>&1 || true
      CXD_RULES=$(cleanup_snapshot_read "$CXD_BIN" mangle) || return 1
      printf '%s\n' "$CXD_RULES" | grep -Eq '(^-N | -j | -g )HETU_CN(C|Y|L|_)' || return 0
    fi
    printf '%s\n' "$CXD_PLAN" | while IFS= read -r CXD_LINE; do
      [ -n "$CXD_LINE" ] || continue
      # shellcheck disable=SC2086
      "$CXD_BIN" -t mangle $CXD_LINE >/dev/null 2>&1 || true
    done
  fi
  CXD_RULES=$(cleanup_snapshot_read "$CXD_BIN" mangle) || return 1
  ! printf '%s\n' "$CXD_RULES" | grep -Eq '(^-N | -j | -g )HETU_CN(C|Y|L|_)'
}
# (Re)builds one family: the new data is validated and rendered before the old tree goes, so a bad
# list keeps the installed tree; the stamp/count record what is live.
cnip_xt_build(){
  CXB_F="$1"; CXB_FILE=$CN_FILE4; CXB_LO=1000; CXB_HI=20000; CXB_RESTORE=iptables-restore; CXB_BIN=xt4
  if [ "$CXB_F" = 6 ]; then CXB_FILE=$CN_FILE6; CXB_LO=500; CXB_HI=30000; CXB_RESTORE=ip6tables-restore; CXB_BIN=xt6; fi
  [ -s "$CXB_FILE" ] || { CNIP_REASON="${CNIP_REASON:+$CNIP_REASON,}v$CXB_F-data-missing"; return 1; }
  has "$CXB_RESTORE" || { CNIP_REASON="${CNIP_REASON:+$CNIP_REASON,}v$CXB_F-restore-missing"; return 1; }
  CXB_SIG=$(cnip_file_sig "$CXB_FILE"); [ -n "$CXB_SIG" ] || return 1
  mkdir -p "$RUN/state" 2>/dev/null || true
  CXB_BATCH="$RUN/.cnipxt$CXB_F.$$"; CXB_P=""; [ "$CN_TCPONLY" != 1 ] || CXB_P="-p tcp "
  printf '*mangle\n' > "$CXB_BATCH" || return 1
  cnip_xt_tree "$CXB_F" "$CXB_LO" "$CXB_HI" "$CXB_P" "$CXB_BATCH.count" < "$CXB_FILE" >> "$CXB_BATCH"; CXB_RC=$?
  if [ "$CXB_RC" != 0 ]; then
    rm -f "$CXB_BATCH" "$CXB_BATCH.count"
    if [ "$CXB_RC" = 3 ]; then CNIP_REASON="${CNIP_REASON:+$CNIP_REASON,}v$CXB_F-data-invalid"; fi
    return 1
  fi
  printf 'COMMIT\n' >> "$CXB_BATCH"
  if ! cnip_xt_drop "$CXB_F"; then rm -f "$CXB_BATCH" "$CXB_BATCH.count"; CNIP_REASON="${CNIP_REASON:+$CNIP_REASON,}v$CXB_F-iptables-failed"; return 1; fi
  if ! cnip_xt_restore "$CXB_RESTORE" < "$CXB_BATCH" >/dev/null 2>&1 || ! "$CXB_BIN" -t mangle -C OUTPUT -j HETU_CNC >/dev/null 2>&1; then
    rm -f "$CXB_BATCH" "$CXB_BATCH.count"; cnip_xt_drop "$CXB_F" || true
    CNIP_REASON="${CNIP_REASON:+$CNIP_REASON,}v$CXB_F-iptables-failed"; return 1
  fi
  mv -f "$CXB_BATCH.count" "$RUN/state/cnip-xt$CXB_F.count" 2>/dev/null || true
  printf '%s\n' "$CXB_SIG" > "$RUN/state/cnip-xt$CXB_F.stamp" 2>/dev/null || true
  rm -f "$CXB_BATCH"
}
# Start stage after the start's cleanup and before any capture rule: install what cnip_prepare
# decided; a family that cannot be installed is switched off before a capture rule tests its bit.
cnip_xt_install(){
  if [ "$CNIP_STATE" != iptables ]; then
    # A preserved bootstrap Kill Switch keeps an older tree through cleanup; drop it.
    if [ "$PRESERVE_KILL" = 1 ]; then cnip_xt_drop 4 || true; cnip_xt_drop 6 || true; fi
    return 0
  fi
  if [ "$CN4_ON" = 1 ]; then cnip_xt_build 4 || CN4_ON=0; else cnip_xt_drop 4 || true; fi
  if [ "$CN6_ON" = 1 ]; then cnip_xt_build 6 || CN6_ON=0; else cnip_xt_drop 6 || true; fi
  if [ "$CN4_ON" != 1 ] && [ "$CN6_ON" != 1 ]; then CNIP_STATE=degraded; CN_FORCE=""; fi
  return 0
}
cnip_xt_reload(){
  CR_DUE=0
  if [ "$CN4_ON" = 1 ] && [ "$(cnip_file_sig "$CN_FILE4")" != "$(cat "$RUN/state/cnip-xt4.stamp" 2>/dev/null)" ]; then CR_DUE=1; fi
  if [ "$CN6_ON" = 1 ] && [ "$(cnip_file_sig "$CN_FILE6")" != "$(cat "$RUN/state/cnip-xt6.stamp" 2>/dev/null)" ]; then CR_DUE=1; fi
  if [ "$CR_DUE" = 0 ]; then printf '{"ok":true,"reloaded":false,"cnip":"iptables","message":"CNIP 数据未变化"}\n'; exit 0; fi
  acquire_lock || { printf '{"ok":false,"message":"运行事务正在执行，请稍后重试"}\n'; exit 0; }
  CR_FAILED=0; CNIP_REASON=""
  if [ "$CN4_ON" = 1 ] && [ "$(cnip_file_sig "$CN_FILE4")" != "$(cat "$RUN/state/cnip-xt4.stamp" 2>/dev/null)" ]; then cnip_xt_build 4 || CR_FAILED=1; fi
  if [ "$CN6_ON" = 1 ] && [ "$(cnip_file_sig "$CN_FILE6")" != "$(cat "$RUN/state/cnip-xt6.stamp" 2>/dev/null)" ]; then cnip_xt_build 6 || CR_FAILED=1; fi
  printf '%s cnip-reload(iptables) failed=%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$CR_FAILED" "$CNIP_REASON" >> "$RUN/cnip.log" 2>/dev/null || true
  if [ "$CR_FAILED" = 0 ]; then printf '{"ok":true,"reloaded":true,"cnip":"iptables","message":"CNIP iptables 规则已热更新"}\n'
  else printf '{"ok":false,"reloaded":false,"cnip":"iptables","message":"CNIP 新数据无效或规则更新失败，内核绕过可能暂时经核心处理"}\n'; fi
  exit 0
}

# ---------------------------------------------------------------- eBPF rule matcher (opt-in)
ebpf_policy_enabled(){ [ "$(sed -n 's/^ENABLED=//p' "$BASE/policy/ebpf" 2>/dev/null | head -n 1)" = 1 ]; }
ebpf_tool(){ if [ "$START_ACTIVE" = 1 ]; then timeout -s TERM -k 1 10 "$EBPF_TOOL" "$@"; else "$EBPF_TOOL" "$@"; fi; }
# The loader prints one reason token on failure; keep only safe characters of its last line.
ebpf_reason(){ printf '%s\n' "$1" | tail -n 1 | tr -cd 'a-z0-9-' | cut -c1-48; }
ebpf_fallback(){ EBPF_STATE=fallback; EBPF_REASON="${1:-error}"; EBPF_RULES=0; ebpf_unload; }
# xt_bpf must accept a pinned program in every table family this session will use.
ebpf_xt_probe(){
  EX_BIN="$1"; EX_PROG="$2"
  "$EX_BIN" -t mangle -N HETU_PROBE >/dev/null 2>&1 || true; "$EX_BIN" -t mangle -F HETU_PROBE >/dev/null 2>&1 || true
  "$EX_BIN" -t mangle -A HETU_PROBE -m bpf --object-pinned "$EBPF_PIN/prog_$EX_PROG" -j RETURN >/dev/null 2>&1; EX_RC=$?
  "$EX_BIN" -t mangle -F HETU_PROBE >/dev/null 2>&1 || true; "$EX_BIN" -t mangle -X HETU_PROBE >/dev/null 2>&1 || true
  return "$EX_RC"
}
# Map contents for one session: direct UIDs (bit 0), the app-scope list (bit 1, only for
# blacklist/whitelist) and the custom bypass networks.
ebpf_sync(){
  ES_LIST="$2"; [ "$4" != core ] || ES_LIST=""
  ES_OUT=$(ebpf_tool sync-uid "$EBPF_PIN" "$1" "$ES_LIST" 2>/dev/null) || { EBPF_REASON=$(ebpf_reason "$ES_OUT"); return 1; }
  ES_OUT=$(ebpf_tool sync-cidr "$EBPF_PIN" "$3" 2>/dev/null) || { EBPF_REASON=$(ebpf_reason "$ES_OUT"); return 1; }
  mkdir -p "$RUN/state" 2>/dev/null || true
  printf 'DIRECT=%s\nLIST=%s\nCIDRS=%s\nSCOPE=%s\n' "$1" "$2" "$3" "$4" > "$EBPF_LISTS.new.$$" 2>/dev/null && mv -f "$EBPF_LISTS.new.$$" "$EBPF_LISTS" 2>/dev/null || true
}
# Start: decide whether this session's capture rules use the matcher. Never fails the start.
ebpf_prepare(){
  EBPF_STATE=off; EBPF_REASON=""; EBPF_RULES=0
  ebpf_policy_enabled || return 0
  case "$START_MODE" in tproxy|redirect|enhance) ;; *) ebpf_fallback "mode-$START_MODE"; return 0;; esac
  { [ -x "$EBPF_TOOL" ] && [ -r "$EBPF_OBJECT" ]; } || { ebpf_fallback payload-missing; return 0; }
  # bpf(2), socket-uid helper, LPM maps, a mounted bpffs and pin/get under SELinux.
  EP_OUT=$(ebpf_tool probe "$EBPF_PIN" 2>/dev/null) || { ebpf_fallback "$(ebpf_reason "$EP_OUT")"; return 0; }
  EP_OUT=$(ebpf_tool load "$EBPF_OBJECT" "$EBPF_PIN" 2>/dev/null) || { ebpf_fallback "$(ebpf_reason "$EP_OUT")"; return 0; }
  ebpf_xt_probe xt4 hetu_uid_direct && ebpf_xt_probe xt4 hetu_bypass4 || { ebpf_fallback xt-bpf-unsupported; return 0; }
  if { [ "$START_V6" = enable ] || [ "$START_V6" = strict ]; } && v6supported && has ip6tables; then
    ebpf_xt_probe xt6 hetu_uid_direct && ebpf_xt_probe xt6 hetu_bypass6 || { ebpf_fallback xt6-bpf-unsupported; return 0; }
  fi
  ebpf_sync "$START_DIRECT_UIDS" "$START_UIDS" "$START_CIDRS" "$START_SCOPE" || { ebpf_fallback "$EBPF_REASON"; return 0; }
  EBPF_STATE=active; EBPF_REASON=""; EBPF_RULES=1
}
# One rule in place of a per-UID/per-network loop. Same fake-IP exclusion as owner_return.
ebpf_return(){
  ER_BIN="$1"; ER_T="$2"; ER_C="$3"; ER_PROG="$4"; ER_FAKE=$(fake_range "$ER_BIN")
  if [ -n "$ER_FAKE" ] && [ "$ER_PROG" != hetu_bypass4 ] && [ "$ER_PROG" != hetu_bypass6 ]; then
    "$ER_BIN" -t "$ER_T" -A "$ER_C" ! -d "$ER_FAKE" -m bpf --object-pinned "$EBPF_PIN/prog_$ER_PROG" -j RETURN
  else "$ER_BIN" -t "$ER_T" -A "$ER_C" -m bpf --object-pinned "$EBPF_PIN/prog_$ER_PROG" -j RETURN; fi
}
ebpf_rules_reference_pins(){
  for ERP_BIN in xt4q xt6q; do
    if [ "$ERP_BIN" = xt6q ] && ! has ip6tables; then continue; fi
    for ERP_T in mangle nat filter; do
      ERP_RULES=$("$ERP_BIN" -t "$ERP_T" -S 2>&1); ERP_RC=$?
      if [ "$ERP_RC" != 0 ]; then case "$ERP_RULES" in *"Table does not exist"*) continue;; *) return 0;; esac; fi
      printf '%s\n' "$ERP_RULES" | grep -qF -- "--object-pinned $EBPF_PIN/" && return 0
    done
  done
  return 1
}
# Pins go only after no rule references them: xtables re-opens every pinned path whenever a
# table is replaced, so a dangling path would block later changes to that table. Only this
# matcher's names are removed.
ebpf_unload(){
  [ -d "$EBPF_PIN" ] || return 0
  ! ebpf_rules_reference_pins || return 1
  if [ -x "$EBPF_TOOL" ] && "$EBPF_TOOL" unload "$EBPF_PIN" >/dev/null 2>&1; then return 0; fi
  rm -f "$EBPF_PIN"/prog_* "$EBPF_PIN"/map_* "$EBPF_PIN"/probe_* >/dev/null 2>&1 || true
  rmdir "$EBPF_PIN" >/dev/null 2>&1 || true
  [ ! -d "$EBPF_PIN" ]
}
ebpf_load_session(){
  EBPF_STATE=$(sed -n 's/^EBPF_MATCH=//p' "$SESSION" 2>/dev/null | head -n 1)
  case "$EBPF_STATE" in active|fallback) ;; *) EBPF_STATE=off;; esac
  EBPF_REASON=$(sed -n 's/^EBPF_REASON=//p' "$SESSION" 2>/dev/null | head -n 1 | tr -cd 'a-z0-9-')
}
# Hot update: rewrite the pinned maps of the running session; iptables stays untouched.
# No arguments re-syncs the lists recorded by the last start/update.
ebpf_update(){ (
  trap 'release_lock' EXIT
  ebpf_load_session
  if [ "$EBPF_STATE" != active ]; then printf '{"ok":true,"updated":false,"ebpf":"%s","ebpfReason":"%s","message":"eBPF 匹配未生效，新列表在下次启动时应用"}\n' "$EBPF_STATE" "$EBPF_REASON"; exit 0; fi
  EU_SCOPE=$(sed -n 's/^APP_SCOPE=//p' "$SESSION" 2>/dev/null | head -n 1); scope "$EU_SCOPE" || EU_SCOPE=core
  if [ "$#" = 3 ]; then EU_DIRECT="$1"; EU_LIST="$2"; EU_CIDRS="$3"
  else
    [ -r "$EBPF_LISTS" ] || { printf '{"ok":false,"updated":false,"ebpf":"active","message":"缺少 eBPF 列表记录，请重启代理"}\n'; exit 0; }
    EU_DIRECT=$(sed -n 's/^DIRECT=//p' "$EBPF_LISTS" | head -n 1); EU_LIST=$(sed -n 's/^LIST=//p' "$EBPF_LISTS" | head -n 1); EU_CIDRS=$(sed -n 's/^CIDRS=//p' "$EBPF_LISTS" | head -n 1)
  fi
  split_safe_uids "$EU_DIRECT" && split_safe_uids "$EU_LIST" && split_safe_cidrs "$EU_CIDRS" || { printf '{"ok":false,"updated":false,"ebpf":"active","message":"eBPF 更新列表无效，保留原映射"}\n'; exit 0; }
  [ "$EU_SCOPE" != whitelist ] || [ -n "$EU_LIST" ] || { printf '{"ok":false,"updated":false,"ebpf":"active","message":"仅所选应用代理模式没有可用 UID，保留原映射"}\n'; exit 0; }
  acquire_lock || { printf '{"ok":false,"message":"运行事务正在执行，请稍后重试"}\n'; exit 0; }
  ebpf_load_session
  [ "$EBPF_STATE" = active ] || { printf '{"ok":true,"updated":false,"ebpf":"%s","message":"eBPF 匹配未生效"}\n' "$EBPF_STATE"; exit 0; }
  if ! ebpf_sync "$EU_DIRECT" "$EU_LIST" "$EU_CIDRS" "$EU_SCOPE"; then
    printf '%s ebpf-update failed %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$EBPF_REASON" >> "$RUN/ebpf.log" 2>/dev/null || true
    printf '{"ok":false,"updated":false,"ebpf":"active","ebpfReason":"%s","message":"eBPF 映射更新失败"}\n' "$EBPF_REASON"; exit 0
  fi
  EU_COUNT=$("$EBPF_TOOL" count "$EBPF_PIN" 2>/dev/null | head -n 1 | tr -cd 'a-z0-9= ')
  printf '%s ebpf-update ok %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$EU_COUNT" >> "$RUN/ebpf.log" 2>/dev/null || true
  printf '{"ok":true,"updated":true,"ebpf":"active","ebpfEntries":"%s","message":"eBPF 映射已热更新，iptables 规则未改动"}\n' "$EU_COUNT"
); }

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
# A fake-IP destination has no real peer: such a flow (e.g. a conntrack entry recreated
# without our connmark after a long sleep) must keep going to the core, never direct.
preserve_existing_tcp(){
  PET_FAKE=$(fake_range "$1"); PET_FAKE=${PET_FAKE%%,*}
  if [ -n "$PET_FAKE" ]; then "$1" -t mangle -A "$2" -p tcp -m conntrack --ctstate ESTABLISHED ! --ctorigdst "$PET_FAKE" -m connmark ! --mark "$MARK/$MASK" -j RETURN
  else "$1" -t mangle -A "$2" -p tcp -m conntrack --ctstate ESTABLISHED -m connmark ! --mark "$MARK/$MASK" -j RETURN; fi
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
  MP4="$MPRE"; if [ "$SHARE" = 1 ]; then share_scope xt4 mangle "$MPRE" "$MACS" || return 1; MP4="$SHR_CHAIN"; fi
  [ "$SHARE" = 0 ] || local_destination_return xt4 "$MP4" -4 || return 1
  if [ "$DNS" = tproxy ] || [ "$DNS" = redirect ]; then
    xt4 -t mangle -A "$MOUT" -p tcp --dport 53 -j RETURN || return 1
    xt4 -t mangle -A "$MOUT" -p udp --dport 53 -j RETURN || return 1
  fi
  bypass4 "$MOUT" mangle "$CIDRS" || return 1; bypass4 "$MP4" mangle "$CIDRS" || return 1
  cn_split xt4 mangle "$MOUT" owner || return 1; MO4="$CN_CHAIN"; cn_split xt4 mangle "$MP4" mark || return 1
  if [ "$M" = tproxy ] && [ "$TCP" = 1 ]; then
    preserve_existing_tcp xt4 "$MO4" || return 1
    if [ "$SHARE" = 1 ]; then preserve_existing_tcp xt4 "$MP4" || return 1; remember_shared_tcp xt4 "$MP4" || return 1; fi
  fi
  if [ "$M" = tproxy ]; then
    [ "$TCP" = 0 ] || { scoped_mark xt4 mangle "$MO4" "$S" "$UIDS" tcp "" "$MARK/$MASK" || return 1; fake_mark xt4 "$MO4" "$S" tcp || return 1; if [ "$SHARE" = 1 ]; then xt4 -t mangle -A "$MP4" -p tcp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; else xt4 -t mangle -A "$MP4" -m mark --mark "$MARK/$MASK" -p tcp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; fi; }
    [ "$UDP" = 0 ] || { scoped_mark xt4 mangle "$MO4" "$S" "$UIDS" udp "" "$MARK/$MASK" || return 1; fake_mark xt4 "$MO4" "$S" udp || return 1; if [ "$SHARE" = 1 ]; then xt4 -t mangle -A "$MP4" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; else xt4 -t mangle -A "$MP4" -m mark --mark "$MARK/$MASK" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; fi; }
  elif [ "$M" = enhance ] && [ "$UDP" = 1 ]; then scoped_mark xt4 mangle "$MO4" "$S" "$UIDS" udp "" "$MARK/$MASK" || return 1; fake_mark xt4 "$MO4" "$S" udp || return 1; if [ "$SHARE" = 1 ]; then xt4 -t mangle -A "$MP4" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; else xt4 -t mangle -A "$MP4" -m mark --mark "$MARK/$MASK" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; fi; fi
  if [ "$M" = tproxy ] && [ "$TCP" = 1 ]; then remember_local_tcp xt4 "$MO4" || return 1; fi
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
  MP6="$MPRE"; if [ "$SHARE" = 1 ]; then share_scope xt6 mangle "$MPRE" "$MACS" || return 1; MP6="$SHR_CHAIN"; fi
  [ "$SHARE" = 0 ] || local_destination_return xt6 "$MP6" -6 || return 1
  if [ "$DNS" = tproxy ] || [ "$DNS" = redirect ]; then
    xt6 -t mangle -A "$MOUT" -p tcp --dport 53 -j RETURN || return 1
    xt6 -t mangle -A "$MOUT" -p udp --dport 53 -j RETURN || return 1
  fi
  bypass6 "$MOUT" mangle "$CIDRS" || return 1; bypass6 "$MP6" mangle "$CIDRS" || return 1
  cn_split xt6 mangle "$MOUT" owner || return 1; MO6="$CN_CHAIN"; cn_split xt6 mangle "$MP6" mark || return 1
  if [ "$M" = tproxy ] && [ "$TCP" = 1 ]; then
    preserve_existing_tcp xt6 "$MO6" || return 1
    if [ "$SHARE" = 1 ]; then preserve_existing_tcp xt6 "$MP6" || return 1; remember_shared_tcp xt6 "$MP6" || return 1; fi
  fi
  if [ "$M" = tproxy ]; then
    [ "$TCP" = 0 ] || { scoped_mark xt6 mangle "$MO6" "$S" "$UIDS" tcp "" "$MARK/$MASK" || return 1; fake_mark xt6 "$MO6" "$S" tcp || return 1; if [ "$SHARE" = 1 ]; then xt6 -t mangle -A "$MP6" -p tcp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; else xt6 -t mangle -A "$MP6" -m mark --mark "$MARK/$MASK" -p tcp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; fi; }
    [ "$UDP" = 0 ] || { scoped_mark xt6 mangle "$MO6" "$S" "$UIDS" udp "" "$MARK/$MASK" || return 1; fake_mark xt6 "$MO6" "$S" udp || return 1; if [ "$SHARE" = 1 ]; then xt6 -t mangle -A "$MP6" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; else xt6 -t mangle -A "$MP6" -m mark --mark "$MARK/$MASK" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; fi; }
  elif [ "$M" = enhance ] && [ "$UDP" = 1 ]; then scoped_mark xt6 mangle "$MO6" "$S" "$UIDS" udp "" "$MARK/$MASK" || return 1; fake_mark xt6 "$MO6" "$S" udp || return 1; if [ "$SHARE" = 1 ]; then xt6 -t mangle -A "$MP6" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; else xt6 -t mangle -A "$MP6" -m mark --mark "$MARK/$MASK" -p udp -j TPROXY --on-port "$P" --tproxy-mark "$MARK/$MASK" || return 1; fi; fi
  if [ "$M" = tproxy ] && [ "$TCP" = 1 ]; then remember_local_tcp xt6 "$MO6" || return 1; fi
  xt6 -t mangle -A OUTPUT -j "$MOUT" || return 1; xt6 -t mangle -A PREROUTING -j "$MPRE" || return 1
}

install_redirect4(){
  P="$1"; M="$2"; TCP="$3"; S="$4"; UIDS="$5"; SHARE="$6"; CIDRS="$7"; IFACES="$8"; DUIDS="$9"; DGIDS="${10:-}"; MACS="${11:-}"; [ "$TCP" = 1 ] || return 0; case "$M" in redirect|enhance) ;; *) return 0;; esac
  xt4 -t nat -N "$NOUT" || return 1; xt4 -t nat -A "$NOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; core_return xt4 nat "$NOUT" || return 1; system_uid_return xt4 nat "$NOUT" "$S" || return 1; direct_uid_returns xt4 nat "$NOUT" "$DUIDS" || return 1; direct_gid_returns xt4 nat "$NOUT" "$DGIDS" || return 1; iface_out xt4 nat "$NOUT" "$IFACES" || return 1; blacklist_returns xt4 nat "$NOUT" "$S" "$UIDS" || return 1; bypass4 "$NOUT" nat "$CIDRS" || return 1; cn_split xt4 nat "$NOUT" owner || return 1; NO4="$CN_CHAIN"; scoped_redirect xt4 nat "$NO4" "$S" "$UIDS" tcp "" "$P" || return 1; fake_redirect xt4 "$NO4" "$S" "$P" || return 1; xt4 -t nat -A OUTPUT -j "$NOUT" || return 1
  if [ "$SHARE" = 1 ]; then xt4 -t nat -N "$NPRE" || return 1; xt4 -t nat -A "$NPRE" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_in xt4 nat "$NPRE" "$IFACES" || return 1; share_scope xt4 nat "$NPRE" "$MACS" || return 1; bypass4 "$SHR_CHAIN" nat "$CIDRS" || return 1; cn_split xt4 nat "$SHR_CHAIN" plain || return 1; xt4 -t nat -A "$SHR_CHAIN" -p tcp -j REDIRECT --to-ports "$P" || return 1; xt4 -t nat -A PREROUTING -j "$NPRE" || return 1; fi
}
install_redirect6(){
  P="$1"; M="$2"; TCP="$3"; S="$4"; UIDS="$5"; SHARE="$6"; CIDRS="$7"; IFACES="$8"; DUIDS="$9"; DGIDS="${10:-}"; MACS="${11:-}"; v6supported || return 0; [ "$TCP" = 1 ] || return 0; case "$M" in redirect|enhance) ;; *) return 0;; esac
  xt6 -t nat -N "$NOUT" || return 1; xt6 -t nat -A "$NOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; core_return xt6 nat "$NOUT" || return 1; system_uid_return xt6 nat "$NOUT" "$S" || return 1; direct_uid_returns xt6 nat "$NOUT" "$DUIDS" || return 1; direct_gid_returns xt6 nat "$NOUT" "$DGIDS" || return 1; iface_out xt6 nat "$NOUT" "$IFACES" || return 1; blacklist_returns xt6 nat "$NOUT" "$S" "$UIDS" || return 1; bypass6 "$NOUT" nat "$CIDRS" || return 1; cn_split xt6 nat "$NOUT" owner || return 1; NO6="$CN_CHAIN"; scoped_redirect xt6 nat "$NO6" "$S" "$UIDS" tcp "" "$P" || return 1; fake_redirect xt6 "$NO6" "$S" "$P" || return 1; xt6 -t nat -A OUTPUT -j "$NOUT" || return 1
  if [ "$SHARE" = 1 ]; then xt6 -t nat -N "$NPRE" || return 1; xt6 -t nat -A "$NPRE" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_in xt6 nat "$NPRE" "$IFACES" || return 1; share_scope xt6 nat "$NPRE" "$MACS" || return 1; bypass6 "$SHR_CHAIN" nat "$CIDRS" || return 1; cn_split xt6 nat "$SHR_CHAIN" plain || return 1; xt6 -t nat -A "$SHR_CHAIN" -p tcp -j REDIRECT --to-ports "$P" || return 1; xt6 -t nat -A PREROUTING -j "$NPRE" || return 1; fi
}

# Who is left out of DNS takeover: the core alone when it runs as root:net_admin,
# otherwise every root socket as before (and with them the system resolver).
dns_owner_return(){
  if [ "$SYSTEM_DNS" = captured ]; then
    core_return "$1" nat "$DNSOUT" || return 1
    # Network validation (connectivity/captive-portal probes) must not depend on the core:
    # the resolver attributes NetworkMonitor's queries to the network stack UID.
    "$1" -t nat -A "$DNSOUT" -m owner --uid-owner "$NETSTACK_UID" -j RETURN
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
  if [ "$SHARE" = 1 ]; then xt4 -t nat -N "$DNSPRE" || return 1; iface_in xt4 nat "$DNSPRE" "$IFACES" || return 1; share_scope xt4 nat "$DNSPRE" "$MACS" || return 1; for X in $DNS_PROTOS; do xt4 -t nat -A "$SHR_CHAIN" -p "$X" --dport 53 -j REDIRECT --to-ports "$P" || return 1; done; xt4 -t nat -I PREROUTING 1 -j "$DNSPRE" || return 1; fi
}
install_dns_redirect6(){
  P="$1"; S="$2"; UIDS="$3"; SHARE="$4"; IFACES="$5"; MACS="${6:-}"; v6supported || return 0; xt6 -t nat -N "$DNSOUT" || return 1; xt6 -t nat -A "$DNSOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; dns_owner_return xt6 || return 1; iface_out xt6 nat "$DNSOUT" "$IFACES" || return 1; blacklist_returns xt6 nat "$DNSOUT" "$S" "$UIDS" || return 1
  for X in $DNS_PROTOS; do scoped_redirect xt6 nat "$DNSOUT" "$S" "$UIDS" "$X" 53 "$P" || return 1; dns_resolver_redirect xt6 "$S" "$X" "$P" || return 1; done; xt6 -t nat -I OUTPUT 1 -j "$DNSOUT" || return 1
  if [ "$SHARE" = 1 ]; then xt6 -t nat -N "$DNSPRE" || return 1; iface_in xt6 nat "$DNSPRE" "$IFACES" || return 1; share_scope xt6 nat "$DNSPRE" "$MACS" || return 1; for X in $DNS_PROTOS; do xt6 -t nat -A "$SHR_CHAIN" -p "$X" --dport 53 -j REDIRECT --to-ports "$P" || return 1; done; xt6 -t nat -I PREROUTING 1 -j "$DNSPRE" || return 1; fi
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
  cn_split xt4 filter "$WROUT" owner || return 1; WO4="$CN_CHAIN"
  scoped_reject_unmarked_udp xt4 "$WO4" "$S" "$UIDS" || return 1
  xt4 -t filter -I OUTPUT 1 -j "$WROUT" || return 1
  if [ "$SHARE" = 1 ]; then
    xt4 -t filter -N "$WRFWD" || return 1
    if [ -n "$MARK" ]; then xt4 -t filter -A "$WRFWD" -m mark --mark "$MARK/$MASK" -j RETURN || return 1; fi
    iface_in xt4 filter "$WRFWD" "$IFACES" || return 1
    share_scope xt4 filter "$WRFWD" "$MACS" || return 1
    bypass4 "$SHR_CHAIN" filter "$CIDRS" || return 1
    cn_split xt4 filter "$SHR_CHAIN" plain || return 1
    xt4 -t filter -A "$SHR_CHAIN" -p udp -j REJECT || return 1
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
  cn_split xt6 filter "$WROUT" owner || return 1; WO6="$CN_CHAIN"
  scoped_reject_unmarked_udp xt6 "$WO6" "$S" "$UIDS" || return 1
  xt6 -t filter -I OUTPUT 1 -j "$WROUT" || return 1
  if [ "$SHARE" = 1 ]; then
    xt6 -t filter -N "$WRFWD" || return 1
    if [ -n "$MARK" ]; then xt6 -t filter -A "$WRFWD" -m mark --mark "$MARK/$MASK" -j RETURN || return 1; fi
    iface_in xt6 filter "$WRFWD" "$IFACES" || return 1
    share_scope xt6 filter "$WRFWD" "$MACS" || return 1
    bypass6 "$SHR_CHAIN" filter "$CIDRS" || return 1
    cn_split xt6 filter "$SHR_CHAIN" plain || return 1
    xt6 -t filter -A "$SHR_CHAIN" -p udp -j REJECT || return 1
    xt6 -t filter -I FORWARD 1 -j "$WRFWD" || return 1
  fi
}

install_quic4(){
  S="$1"; UIDS="$2"; SHARE="$3"; CIDRS="$4"; IFACES="$5"; DUIDS="$6"; DGIDS="${7:-}"; MACS="${8:-}"; xt4 -t filter -N "$QUICOUT" || return 1; xt4 -t filter -A "$QUICOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; system_uid_return xt4 filter "$QUICOUT" "$S" || return 1; direct_uid_returns xt4 filter "$QUICOUT" "$DUIDS" || return 1; direct_gid_returns xt4 filter "$QUICOUT" "$DGIDS" || return 1; iface_out xt4 filter "$QUICOUT" "$IFACES" || return 1; blacklist_returns xt4 filter "$QUICOUT" "$S" "$UIDS" || return 1; bypass4 "$QUICOUT" filter "$CIDRS" || return 1; cn_split xt4 filter "$QUICOUT" owner || return 1; QO4="$CN_CHAIN"; scoped_reject_quic xt4 "$QO4" "$S" "$UIDS" || return 1; xt4 -t filter -A OUTPUT -j "$QUICOUT" || return 1
  if [ "$SHARE" = 1 ]; then xt4 -t filter -N "$QUICFWD" || return 1; iface_in xt4 filter "$QUICFWD" "$IFACES" || return 1; share_scope xt4 filter "$QUICFWD" "$MACS" || return 1; bypass4 "$SHR_CHAIN" filter "$CIDRS" || return 1; cn_split xt4 filter "$SHR_CHAIN" plain || return 1; xt4 -t filter -A "$SHR_CHAIN" -p udp --dport 443 -j REJECT || return 1; xt4 -t filter -A FORWARD -j "$QUICFWD" || return 1; fi
}
install_quic6(){
  S="$1"; UIDS="$2"; SHARE="$3"; CIDRS="$4"; IFACES="$5"; DUIDS="$6"; DGIDS="${7:-}"; MACS="${8:-}"; v6supported || return 0; xt6 -t filter -N "$QUICOUT" || return 1; xt6 -t filter -A "$QUICOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; system_uid_return xt6 filter "$QUICOUT" "$S" || return 1; direct_uid_returns xt6 filter "$QUICOUT" "$DUIDS" || return 1; direct_gid_returns xt6 filter "$QUICOUT" "$DGIDS" || return 1; iface_out xt6 filter "$QUICOUT" "$IFACES" || return 1; blacklist_returns xt6 filter "$QUICOUT" "$S" "$UIDS" || return 1; bypass6 "$QUICOUT" filter "$CIDRS" || return 1; cn_split xt6 filter "$QUICOUT" owner || return 1; QO6="$CN_CHAIN"; scoped_reject_quic xt6 "$QO6" "$S" "$UIDS" || return 1; xt6 -t filter -A OUTPUT -j "$QUICOUT" || return 1
  if [ "$SHARE" = 1 ]; then xt6 -t filter -N "$QUICFWD" || return 1; iface_in xt6 filter "$QUICFWD" "$IFACES" || return 1; share_scope xt6 filter "$QUICFWD" "$MACS" || return 1; bypass6 "$SHR_CHAIN" filter "$CIDRS" || return 1; cn_split xt6 filter "$SHR_CHAIN" plain || return 1; xt6 -t filter -A "$SHR_CHAIN" -p udp --dport 443 -j REJECT || return 1; xt6 -t filter -A FORWARD -j "$QUICFWD" || return 1; fi
}

install_v6_strict(){
  S="$1"; UIDS="$2"; SHARE="$3"; CIDRS="$4"; IFACES="$5"; DUIDS="$6"; DGIDS="${7:-}"; MACS="${8:-}"; v6supported || return 0; xt6 -t filter -N "$V6OUT" || return 1; xt6 -t filter -A "$V6OUT" -o lo -j RETURN || return 1; xt6 -t filter -A "$V6OUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_out xt6 filter "$V6OUT" "$IFACES" || return 1; direct_uid_returns xt6 filter "$V6OUT" "$DUIDS" || return 1; direct_gid_returns xt6 filter "$V6OUT" "$DGIDS" || return 1; blacklist_returns xt6 filter "$V6OUT" "$S" "$UIDS" || return 1; bypass6 "$V6OUT" filter "$CIDRS" || return 1; cn_split xt6 filter "$V6OUT" owner || return 1; VO6="$CN_CHAIN"; scoped_reject_all xt6 "$VO6" "$S" "$UIDS" || return 1; xt6 -t filter -A OUTPUT -j "$V6OUT" || return 1
  if [ "$SHARE" = 1 ]; then xt6 -t filter -N "$V6FWD" || return 1; iface_in xt6 filter "$V6FWD" "$IFACES" || return 1; share_scope xt6 filter "$V6FWD" "$MACS" || return 1; bypass6 "$SHR_CHAIN" filter "$CIDRS" || return 1; cn_split xt6 filter "$SHR_CHAIN" plain || return 1; xt6 -t filter -A "$SHR_CHAIN" -j REJECT || return 1; xt6 -t filter -A FORWARD -j "$V6FWD" || return 1; fi
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
  # The guard outlives capture rules and pins (crash, rollback): always plain owner/CIDR rules.
  EBPF_RULES=0
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
  S="$1"; UIDS="$2"; SHARE="$3"; CIDRS="$4"; IFACES="$5"; DUIDS="$6"; DGIDS="${7:-}"; MACS="${8:-}"; xt4 -t filter -N "$KOUT" >/dev/null 2>&1 || true; xt4 -t filter -F "$KOUT" || return 1; xt4 -t filter -A "$KOUT" -o lo -j RETURN || return 1; startup_core_return xt4 || return 1; iface_out xt4 filter "$KOUT" "$IFACES" || return 1; direct_uid_returns xt4 filter "$KOUT" "$DUIDS" || return 1; direct_gid_returns xt4 filter "$KOUT" "$DGIDS" || return 1; blacklist_returns xt4 filter "$KOUT" "$S" "$UIDS" || return 1; bypass4 "$KOUT" filter "$CIDRS" || return 1; cn_split xt4 filter "$KOUT" owner reuse || return 1; KO4="$CN_CHAIN"; scoped_reject_all xt4 "$KO4" "$S" "$UIDS" || return 1; xt4 -t filter -I OUTPUT 1 -j "$KOUT" || return 1
  if [ "$SHARE" = 1 ]; then xt4 -t filter -N "$KFWD" >/dev/null 2>&1 || true; xt4 -t filter -F "$KFWD" || return 1; iface_in xt4 filter "$KFWD" "$IFACES" || return 1; share_scope xt4 filter "$KFWD" "$MACS" reuse || return 1; bypass4 "$SHR_CHAIN" filter "$CIDRS" || return 1; cn_split xt4 filter "$SHR_CHAIN" plain || return 1; xt4 -t filter -A "$SHR_CHAIN" -j REJECT || return 1; xt4 -t filter -I FORWARD 1 -j "$KFWD" || return 1; fi
}
install_kill6(){
  S="$1"; UIDS="$2"; SHARE="$3"; CIDRS="$4"; IFACES="$5"; DUIDS="$6"; DGIDS="${7:-}"; MACS="${8:-}"; v6supported || return 0; has ip6tables || return 1; xt6 -t filter -N "$KOUT" >/dev/null 2>&1 || true; xt6 -t filter -F "$KOUT" || return 1; xt6 -t filter -A "$KOUT" -o lo -j RETURN || return 1; startup_core_return xt6 || return 1; iface_out xt6 filter "$KOUT" "$IFACES" || return 1; direct_uid_returns xt6 filter "$KOUT" "$DUIDS" || return 1; direct_gid_returns xt6 filter "$KOUT" "$DGIDS" || return 1; blacklist_returns xt6 filter "$KOUT" "$S" "$UIDS" || return 1; bypass6 "$KOUT" filter "$CIDRS" || return 1; cn_split xt6 filter "$KOUT" owner reuse || return 1; KO6="$CN_CHAIN"; scoped_reject_all xt6 "$KO6" "$S" "$UIDS" || return 1; xt6 -t filter -I OUTPUT 1 -j "$KOUT" || return 1
  if [ "$SHARE" = 1 ]; then xt6 -t filter -N "$KFWD" >/dev/null 2>&1 || true; xt6 -t filter -F "$KFWD" || return 1; iface_in xt6 filter "$KFWD" "$IFACES" || return 1; share_scope xt6 filter "$KFWD" "$MACS" reuse || return 1; bypass6 "$SHR_CHAIN" filter "$CIDRS" || return 1; cn_split xt6 filter "$SHR_CHAIN" plain || return 1; xt6 -t filter -A "$SHR_CHAIN" -j REJECT || return 1; xt6 -t filter -I FORWARD 1 -j "$KFWD" || return 1; fi
}

core_kind(){
  CK="$CORE_KIND_OVERRIDE"; [ -n "$CK" ] || CK=$(head -n 1 "$CORE_KIND_FILE" 2>/dev/null || true)
  case "$CK" in sing-box|xray|v2ray|hysteria) printf '%s' "$CK";; *) printf mihomo;; esac
}
# Only Mihomo and sing-box (experimental.clash_api) answer on the controller port.
kind_has_api(){ case "$1" in mihomo|sing-box) return 0;; *) return 1;; esac; }
kind_label(){ case "$1" in sing-box) printf sing-box;; xray) printf Xray;; v2ray) printf V2Fly;; hysteria) printf Hysteria;; *) printf Mihomo;; esac; }
# The native config the core reads: the private startup copy minus Hetu's terminal fake-IP
# metadata block (JSON has no comments), with the extension the core picks its parser by.
core_payload(){
  CP_SRC="$1"; CP_KIND="$2"; CP_DIR="$3"
  case "$CP_KIND" in hysteria) CP_EXT=yaml;; sing-box|xray|v2ray) CP_EXT=json;; *) printf '%s' "$CP_SRC"; return 0;; esac
  mkdir -p "$CP_DIR" || return 1
  CP_OUT="$CP_DIR/hetu-core.$CP_EXT"
  if [ "$(tail -n 5 "$CP_SRC" 2>/dev/null | sed -n '1p')" = '# HETU_FAKE_IP_POLICY=1' ]; then
    CP_LINES=$(wc -l < "$CP_SRC") || return 1
    head -n $((CP_LINES-5)) "$CP_SRC" > "$CP_OUT.new.$$" || { rm -f "$CP_OUT.new.$$"; return 1; }
  else cat "$CP_SRC" > "$CP_OUT.new.$$" || { rm -f "$CP_OUT.new.$$"; return 1; }; fi
  # Leading reference-cleanup notices are Hetu comments (copied into core.log), not core input.
  if grep -q '^# HETU_NOTICE: ' "$CP_OUT.new.$$" 2>/dev/null; then
    sed '/^# HETU_NOTICE: /d' "$CP_OUT.new.$$" > "$CP_OUT.new.$$.n" && mv -f "$CP_OUT.new.$$.n" "$CP_OUT.new.$$" || { rm -f "$CP_OUT.new.$$" "$CP_OUT.new.$$.n"; return 1; }
  fi
  chmod 600 "$CP_OUT.new.$$" 2>/dev/null || true
  mv -f "$CP_OUT.new.$$" "$CP_OUT" || return 1
  printf '%s' "$CP_OUT"
}
# Geo databases shipped next to Xray/V2Fly; harmless for the other cores.
core_env(){ XRAY_LOCATION_ASSET="$CORE_ASSETS"; V2RAY_LOCATION_ASSET="$CORE_ASSETS"; export XRAY_LOCATION_ASSET V2RAY_LOCATION_ASSET; }
validate_native(){
  VN_BIN="$1"; VN_KIND="$3"
  VN_CFG=$(core_payload "$2" "$VN_KIND" "$VC_DIR") || { printf '%s\n' "无法准备 $(kind_label "$VN_KIND") 配置" >> "$CHECKLOG"; return 1; }
  case "$VN_KIND" in
    sing-box) set -- check -c "$VN_CFG" -D "$VC_DIR" --disable-color;;
    xray) set -- run -test -c "$VN_CFG";;
    v2ray) set -- test -c "$VN_CFG";;
    # The Hysteria client has no check command; the App validates the YAML before deployment.
    hysteria) if [ -s "$VN_CFG" ] && grep -q '^server:' "$VN_CFG"; then return 0; fi; printf '%s\n' "Hysteria 配置缺少 server" >> "$CHECKLOG"; return 1;;
    *) return 1;;
  esac
  core_env
  if [ "$START_ACTIVE" = 1 ]; then
    transaction_current || return 1
    read -r VC_NOW VC_UNUSED < /proc/uptime || return 1
    VC_NOW=${VC_NOW%%.*}; VC_LEFT=$((TXN_DEADLINE-VC_NOW))
    [ "$VC_LEFT" -gt 0 ] || return 1
    timeout -s TERM -k 1 "$VC_LEFT" "$VN_BIN" "$@" >>"$CHECKLOG" 2>&1
  else "$VN_BIN" "$@" >>"$CHECKLOG" 2>&1; fi
}
validatecfg(){
  BIN="$1"; CFG="$2"; : > "$CHECKLOG"
  VC_KIND=$(core_kind)
  if [ "$VC_KIND" != mihomo ]; then validate_native "$BIN" "$CFG" "$VC_KIND"; return $?; fi
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
  PID="$1"; M="$2"; TP="$3"; RP="$4"; TCP="$5"; UDP="$6"; DNS="$7"; DP="$8"; CP="$9"; core_maybe_alive "$PID" && kill -0 "$PID" >/dev/null 2>&1 || return 1; { [ "${START_API:-1}" = 0 ] || tcp_listen "$CP"; } || return 1
  case "$M" in tproxy) [ "$TCP" = 0 ] || tcp_listen "$TP" || return 1; [ "$UDP" = 0 ] || udp_listen "$TP" || return 1;; redirect) [ "$TCP" = 0 ] || tcp_listen "$RP" || return 1;; enhance) [ "$TCP" = 0 ] || tcp_listen "$RP" || return 1; [ "$UDP" = 0 ] || udp_listen "$TP" || return 1;; tun|ebpf) ip link show hetu0 >/dev/null 2>&1 || return 1;; esac
  if [ "$DNS" = tproxy ] || [ "$DNS" = redirect ]; then tcp_listen "$DP" || return 1; udp_listen "$DP" || return 1; fi; return 0
}
# ---- Rules of other proxy apps (Android only) ----------------------------------------------------
# Run by start after Hetu's own cleanup, before anything is installed. Blocking (start fails with a clear
# message instead of a silent no-network): a REJECT/DROP in the filter table whose destination overlaps
# Hetu's fake-IP range (every fake-IP connection would die), and TPROXY rules of another app in the mangle
# table (its marks route Hetu's packets to its own dead or competing listener). Only reported: NAT
# REDIRECT/DNAT of another app (AdGuard and similar root filters use it) and another app's TUN/VPN
# interface. FOREIGN_WARN collects the report for the start message and watchdog.log.
foreign_rule_owner(){ case "$1" in HETU_*|BICHEN_*|"$FAKE_REJECT_CHAIN") return 0;; esac; return 1; }
# iptables -S output on stdin; awk prelude marking the chains reachable from the built-in chains in
# ROOTS (jumps/gotos followed), so an unhooked leftover chain is never reported.
FOREIGN_AWK_REACH='
  { L[NR] = $0 }
  $1 == "-A" { for (i = 3; i < NF; i++) if ($i == "-j" || $i == "-g") { e[$2] = e[$2] " " $(i+1) } }
  function reach(   n, r, k, c, t, q, x) {
    n = split(ROOTS, r, " "); for (k = 1; k <= n; k++) { R[r[k]] = 1; q[++t] = r[k] }
    for (x = 1; x <= t; x++) { c = split(e[q[x]], nx, " "); for (k = 1; k <= c; k++) if (!(nx[k] in R)) { R[nx[k]] = 1; q[++t] = nx[k] } } }
  function own(c) { return c ~ /^(HETU_|BICHEN_)/ || c == OWN }'
# Filter-table rules on stdin: "<chain> <dst>" of REJECT/DROP rules reachable from OUTPUT, outside Hetu
# chains, whose IPv4 destination overlaps $1 (a.b.c.d/n).
foreign_fake_blocks(){
  awk -v fake="$1" -v OWN="$FAKE_REJECT_CHAIN" -v ROOTS="OUTPUT" "$FOREIGN_AWK_REACH"'
    function ip2n(a,   p) { split(a, p, "."); return ((p[1] * 256 + p[2]) * 256 + p[3]) * 256 + p[4] }
    function lo(c,   a, n, b) { split(c, a, "/"); n = (a[2] == "") ? 32 : a[2] + 0; b = 2 ^ (32 - n); return int(ip2n(a[1]) / b) * b }
    function hi(c,   a, n) { split(c, a, "/"); n = (a[2] == "") ? 32 : a[2] + 0; return lo(c) + 2 ^ (32 - n) - 1 }
    END {
      if (fake !~ /^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+(\/[0-9]+)?$/) exit
      flo = lo(fake); fhi = hi(fake); reach()
      for (l = 1; l <= NR; l++) {
        nf = split(L[l], f, " "); if (f[1] != "-A" || own(f[2]) || !(f[2] in R)) continue
        d = ""; j = ""
        for (i = 3; i < nf; i++) { if (f[i] == "-d") d = f[i+1]; if (f[i] == "-j") j = f[i+1] }
        if ((j != "REJECT" && j != "DROP") || d !~ /^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+(\/[0-9]+)?$/) continue
        if (lo(d) <= fhi && hi(d) >= flo) print f[2] " " d } }'
}
# Rules on stdin: reachable chains outside Hetu (and outside netd's own prefixes for NAT) whose rules jump to
# one of the targets in $1 (TPROXY, or REDIRECT DNAT).
foreign_target_chains(){
  awk -v want=" $1 " -v OWN="$FAKE_REJECT_CHAIN" -v ROOTS="PREROUTING OUTPUT" "$FOREIGN_AWK_REACH"'
    END { reach()
      for (l = 1; l <= NR; l++) {
        nf = split(L[l], f, " "); if (f[1] != "-A" || own(f[2]) || !(f[2] in R)) continue
        if (f[2] ~ /^(oem_|tetherctrl_|natctrl_|idletimer_|bw_|fw_|st_)/) continue
        for (i = 3; i < nf; i++) if (f[i] == "-j" && index(want, " " f[i+1] " ")) { print f[2]; break } } }' | sort -u
}
foreign_tproxy_chains(){ foreign_target_chains TPROXY; }
foreign_redirect_chains(){ foreign_target_chains "REDIRECT DNAT"; }
foreign_tun_links(){ ip -o link show up 2>/dev/null | awk -F': ' '{ n = $2; sub(/@.*/, "", n); if (n ~ /^(tun|utun|ppp|tap|wg|clash|mihomo|meta|sing|box|nekoray|v2ray|xray)/ && n != "hetu0") print n }' | sort -u; }
foreign_capture_check(){
  FOREIGN_WARN=""
  [ -d "$CG_ESCAPE_GATE" ] || return 0
  # The legacy chain is Hetu's own: it must be gone before anything else.
  if ! fake_reject_remove; then fail "无法移除旧版河图停止后留下的 fake-IP 拦截规则（$FAKE_REJECT_CHAIN），请重试启动或重启手机"; fi
  FC_FAKE="${FAKE_IP_V4%%,*}"; [ -n "$FC_FAKE" ] || FC_FAKE=198.18.0.0/15
  FC_FILTER=$(xt4q -t filter -S 2>/dev/null || true)
  FC_BLOCK=$(printf '%s\n' "$FC_FILTER" | foreign_fake_blocks "$FC_FAKE" | head -n 4 | tr '\n' ' ')
  if [ -n "$FC_BLOCK" ]; then
    wd_log "start: refused: another app's firewall rule blocks the fake-IP range $FC_FAKE: ${FC_BLOCK% }"
    fail "检测到其他应用的防火墙规则拦截了 fake-IP 网段，河图启动后将无法上网；请先停止或卸载该代理/防火墙应用，或重启手机后再启动"
  fi
  FC_TP=""
  for FC_BIN in xt4q xt6q; do
    if [ "$FC_BIN" = xt6q ] && ! has ip6tables; then continue; fi
    FC_TP="$FC_TP $("$FC_BIN" -t mangle -S 2>/dev/null | foreign_tproxy_chains | tr '\n' ' ')"
  done
  FC_TP=$(printf '%s\n' $FC_TP | sort -u | tr '\n' ' '); FC_TP=${FC_TP% }
  if [ -n "$FC_TP" ]; then
    wd_log "start: refused: TPROXY rules of another proxy app are active (mangle chains: $FC_TP)"
    fail "检测到其他代理应用的透明代理（TPROXY）规则仍在生效，河图与其同时运行会断网；请先在该应用中停止代理，或重启手机后再启动河图"
  fi
  FC_NAT=$(xt4q -t nat -S 2>/dev/null | foreign_redirect_chains | tr '\n' ' '); FC_NAT=${FC_NAT% }
  FC_TUN=$(foreign_tun_links | tr '\n' ' '); FC_TUN=${FC_TUN% }
  [ -z "$FC_NAT" ] || FOREIGN_WARN="${FOREIGN_WARN:+$FOREIGN_WARN；}其他应用的 NAT 重定向规则仍在生效（$FC_NAT）"
  [ -z "$FC_TUN" ] || FOREIGN_WARN="${FOREIGN_WARN:+$FOREIGN_WARN；}其他 VPN/TUN 接口在线（$FC_TUN），其流量可能不经过河图"
  [ -z "$FOREIGN_WARN" ] || wd_log "start: other proxy/VPN state found: nat=${FC_NAT:-none} tun=${FC_TUN:-none}"
  return 0
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
# ---- self-heal helpers (watchdog) --------------------------------------------------------
# setsid only when it keeps the PID of a background child (it forks for a group leader).
detach_probe(){
  [ "$SETSID_CHECKED" != 1 ] || return 0
  SETSID_CHECKED=1; SETSID=""
  has setsid || return 0
  DP_FILE="$RUN/.setsid.$$"
  setsid sh -c 'printf "%s\n" "$$"' 2>/dev/null > "$DP_FILE" &
  DP_PID=$!
  wait "$DP_PID" 2>/dev/null
  [ "$(cat "$DP_FILE" 2>/dev/null)" != "$DP_PID" ] || SETSID=setsid
  rm -f "$DP_FILE"
  return 0
}
# Move a process into the root cgroup of every hierarchy that freezes or kills Android apps by
# cgroup (v2 uid_/pid_ groups, v1 acct/freezer/cpuctl/memcg). Children inherit it. Best effort.
cg_escape(){
  CE_PID="$1"; CG_ESCAPED=""
  [ -d "$CG_ESCAPE_GATE" ] || return 0
  CE_V2=$(awk '$3=="cgroup2" {print $2; exit}' "$CG_MOUNTS" 2>/dev/null)
  if [ -n "$CE_V2" ] && [ -e "$CE_V2/cgroup.procs" ] && printf '%s\n' "$CE_PID" 2>/dev/null > "$CE_V2/cgroup.procs"; then CG_ESCAPED="v2"; fi
  for CE_D in $CG_ESCAPE_V1; do
    [ "$CE_D" != "$CE_V2" ] || continue
    [ -e "$CE_D/cgroup.procs" ] || [ -e "$CE_D/tasks" ] || continue
    if { cg_join "$CE_D" "$CE_PID"; } 2>/dev/null; then CG_ESCAPED="${CG_ESCAPED:+$CG_ESCAPED,}${CE_D#/}"; fi
  done
  return 0
}
wd_log(){ { wd_log_body "$@"; } 2>/dev/null; return 0; }
wd_log_body(){
  printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*" >> "$WATCHDOG_LOG" || return 0
  WL_SIZE=$(wc -c < "$WATCHDOG_LOG" 2>/dev/null || echo 0)
  case "$WL_SIZE" in ''|*[!0-9]*) return 0;; esac
  [ "$WL_SIZE" -le "$WD_LOG_MAX" ] || { tail -n 300 "$WATCHDOG_LOG" > "$WATCHDOG_LOG.new.$$" 2>/dev/null && mv -f "$WATCHDOG_LOG.new.$$" "$WATCHDOG_LOG" 2>/dev/null; } || rm -f "$WATCHDOG_LOG.new.$$"
  return 0
}
wd_now(){ read -r WD_NOW WD_NOW_REST < /proc/uptime 2>/dev/null || WD_NOW=0; WD_NOW=${WD_NOW%%.*}; case "$WD_NOW" in ''|*[!0-9]*) WD_NOW=0;; esac; }
# Best effort user-visible notice that does not need the App process (cmd notification).
wd_notify(){
  { printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$1" > "$RUN/selfheal-state"; } 2>/dev/null || true
  has cmd || return 0
  cmd notification post -S bigtext -t '河图' hetu-selfheal "$2" >/dev/null 2>&1 &
  return 0
}
# $1 method, $2 path, $3 body file, $4 "hold" keeps the request side open (nc) for slow answers.
# Returns 0 when the core answered (any HTTP status, WD_CODE), 1 when it did not, 2 without a tool.
wd_http(){
  WH_URL="http://127.0.0.1:$WD_CP$2"; WD_CODE=000; : > "$3"
  for WH_TOOL in $WD_HTTP_TOOLS; do
    case "$WH_TOOL" in
      curl) has curl || continue
        if [ -n "$WD_SECRET" ]; then WD_CODE=$(curl -sS -m "$WD_HTTP_TIMEOUT" -X "$1" -H "Authorization: Bearer $WD_SECRET" -o "$3" -w '%{http_code}' "$WH_URL" 2>/dev/null); WH_RC=$?
        else WD_CODE=$(curl -sS -m "$WD_HTTP_TIMEOUT" -X "$1" -o "$3" -w '%{http_code}' "$WH_URL" 2>/dev/null); WH_RC=$?; fi
        case "$WD_CODE" in [1-5][0-9][0-9]) return 0;; esac
        WD_CODE=000
        # Accepted and closed by the peer: the process is running its accept loop.
        case "$WH_RC" in 52|56) return 0;; esac
        return 1;;
      wget) [ "$1" = GET ] || continue
        WH_BB=""
        for WH_B in /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox /data/adb/magisk/busybox; do [ -x "$WH_B" ] && { WH_BB="$WH_B"; break; }; done
        [ -n "$WH_BB" ] || continue
        if [ -n "$WD_SECRET" ]; then timeout "$((WD_HTTP_TIMEOUT+1))" "$WH_BB" wget -S -q -T "$WD_HTTP_TIMEOUT" -O "$3" --header "Authorization: Bearer $WD_SECRET" "$WH_URL" 2> "$3.hdr"
        else timeout "$((WD_HTTP_TIMEOUT+1))" "$WH_BB" wget -S -q -T "$WD_HTTP_TIMEOUT" -O "$3" "$WH_URL" 2> "$3.hdr"; fi
        WD_CODE=$(awk '$1 ~ /^HTTP\// {c=$2} END {print c}' "$3.hdr" 2>/dev/null); rm -f "$3.hdr"
        case "$WD_CODE" in [1-5][0-9][0-9]) return 0;; esac
        WD_CODE=000; return 1;;
      nc) has nc || continue
        WH_HOLD=0; [ "${4:-}" != hold ] || WH_HOLD="$WD_HTTP_TIMEOUT"
        { printf '%s %s HTTP/1.0\r\nHost: 127.0.0.1\r\nContent-Length: 0\r\n' "$1" "$2"
          [ -z "$WD_SECRET" ] || printf 'Authorization: Bearer %s\r\n' "$WD_SECRET"
          printf '\r\n'; [ "$WH_HOLD" = 0 ] || sleep "$WH_HOLD"; } 2>/dev/null \
          | timeout "$((WD_HTTP_TIMEOUT+1))" nc -w "$WD_HTTP_TIMEOUT" 127.0.0.1 "$WD_CP" > "$3.raw" 2>/dev/null
        WD_CODE=$(head -n 1 "$3.raw" 2>/dev/null | awk '{print $2}')
        awk 'b {print; next} /^\r?$/ {b=1}' "$3.raw" > "$3" 2>/dev/null; rm -f "$3.raw"
        case "$WD_CODE" in [1-5][0-9][0-9]) return 0;; esac
        WD_CODE=000; return 1;;
    esac
  done
  return 2
}
# DNS over TCP to the core's DNS listener (any answer, NXDOMAIN included, proves it is serving).
wd_dns_probe(){
  case "$WD_DP" in ''|0|*[!0-9]*) return 2;; esac
  case " $WD_DNS_PROTOS " in *" tcp "*) ;; *) return 2;; esac
  has nc || return 2
  WDP_N=$(printf '\000\047\110\124\001\000\000\001\000\000\000\000\000\000\015hetu-liveness\007invalid\000\000\001\000\001' \
    | timeout "$((WD_HTTP_TIMEOUT+1))" nc -w "$WD_HTTP_TIMEOUT" 127.0.0.1 "$WD_DP" 2>/dev/null | wc -c)
  case "$WDP_N" in ''|*[!0-9]*) return 1;; esac
  [ "$WDP_N" -gt 0 ]
}
# 0 when the core is frozen (cgroup v2 freezer, v1 freezer) or stopped; WD_FROZEN_HOW says how.
wd_core_frozen(){
  WD_FROZEN_HOW=""
  WCF_STATE=$(sed 's/^.*) //' "/proc/$1/stat" 2>/dev/null | awk '{print $1}')
  [ "$WCF_STATE" != T ] || { WD_FROZEN_HOW=stopped; return 0; }
  WCF_V2=$(awk '$3=="cgroup2" {print $2; exit}' "$CG_MOUNTS" 2>/dev/null)
  WCF_P=$(sed -n 's/^0:://p' "/proc/$1/cgroup" 2>/dev/null | head -n 1)
  if [ -n "$WCF_V2" ] && [ -n "$WCF_P" ] && grep -qx 'frozen 1' "$WCF_V2${WCF_P%/}/cgroup.events" 2>/dev/null; then WD_FROZEN_HOW="cgroup2:$WCF_P"; return 0; fi
  WCF_FZ=$(awk -F: '$2 ~ /(^|,)freezer(,|$)/ {print $3; exit}' "/proc/$1/cgroup" 2>/dev/null)
  if [ -n "$WCF_FZ" ]; then
    for WCF_D in /dev/freezer /sys/fs/cgroup/freezer; do
      grep -q FROZEN "$WCF_D${WCF_FZ%/}/freezer.state" 2>/dev/null && { WD_FROZEN_HOW="freezer:$WCF_FZ"; return 0; }
    done
  fi
  return 1
}
# Session facts the probes need; the controller secret comes from the private startup copy.
wd_selfheal_init(){
  WD_CP=$(sed -n 's/^CONTROLLER_PORT=//p' "$SESSION" 2>/dev/null | head -n 1); port "$WD_CP" || WD_CP=0
  WD_DP=$(sed -n 's/^ACTIVE_DNS_PORT=//p' "$SESSION" 2>/dev/null | head -n 1); port "$WD_DP" || WD_DP=0
  WD_DNS_PROTOS=$(sed -n 's/^DNS_PROTOS=//p' "$SESSION" 2>/dev/null | head -n 1)
  WD_TMP="$RUN/.wd.$$"
  WD_CFG=$(sed -n '2p' "$RUN/state/start.args" 2>/dev/null)
  WD_SECRET=""
  if [ -n "$WD_CFG" ] && [ -r "$WD_CFG" ]; then
    WD_SECRET=$(sed -n "s/^secret:[[:space:]]*'\\(.*\\)'[[:space:]]*\$/\\1/p" "$WD_CFG" 2>/dev/null | head -n 1 | sed "s/''/'/g")
    [ -n "$WD_SECRET" ] || WD_SECRET=$(sed -n 's/^secret:[[:space:]]*"\(.*\)"[[:space:]]*$/\1/p' "$WD_CFG" 2>/dev/null | head -n 1)
    [ -n "$WD_SECRET" ] || WD_SECRET=$(grep -o '"secret"[[:space:]]*:[[:space:]]*"[^"]*"' "$WD_CFG" 2>/dev/null | head -n 1 | sed 's/.*"\([^"]*\)"$/\1/')
  fi
  WD_KIND=$(core_kind 2>/dev/null)
  WD_EG_ON=0
  if [ "$WD_CP" != 0 ] && [ -n "$WD_SECRET" ] && kind_has_api "$WD_KIND"; then WD_EG_ON=1; fi
  wd_now
  WD_LIVE_AT=$((WD_NOW+WD_LIVE_GRACE)); WD_LIVE_FAIL=0; WD_DNS_AT=$((WD_NOW+WD_LIVE_GRACE+WD_DNS_EVERY)); WD_DNS_FAIL=0
  WD_EG_AT=$((WD_NOW+WD_EGRESS_GRACE)); WD_EG_FAIL=0; WD_EG_LEVEL=0; WD_EG_TARGET=""; WD_EG_NEXT_ACTION=0; WD_EG_BACKOFF="$WD_EGRESS_BACKOFF"
  WD_NET_SIG=$(wd_net_sig)
  WD_NOTOOL=0
  # Data-plane probe: needs the controller (WD_EG_ON: the verdict uses the group delays and DIRECT), the
  # core's DNS listener, TCP capture and a root flow to a fake IP that is really captured. The watchdog is
  # uid 0: the uid 0-9999 RETURN rules let it go direct unless owner_return added `! -d <fake range>`,
  # which it does only while fake_range is non-empty (captured resolver + fake range in this session).
  # Otherwise WD_DP_ON stays 0 and the controller delay check (egress) stays in charge.
  WD_TAB=$(printf '\t'); WD_DP_ON=0; WD_DP_FAIL=0; WD_DP_LAST=""; WD_DP_LEVEL=0; WD_DP_NEXT=0; WD_DP_NOTIFIED=0; WD_NODE_NOTIFIED=0; WD_ROUTE_NOTIFIED=0; WD_FAKEIP_NOTIFIED=0; WD_DP_BAD_NAMES=""
  WD_OFF_N=0; WD_OFF_NEXT=0; WD_CACHE_SEEN=0
  WD_DP_BACKOFF="$WD_EGRESS_BACKOFF"; WD_DP_AT=$((WD_NOW+${WD_DP_GRACE:-120}))
  WD_FAKE4=$(wd_session_fake_range); WD_FAKE4="${WD_FAKE4%%,*}"
  if [ "$WD_EG_ON" = 1 ] && [ "$WD_DP" != 0 ] && [ -n "$WD_FAKE4" ] && wd_root_fake_captured "$WD_FAKE4" &&
    [ "$(sed -n 's/^TCP=//p' "$SESSION" 2>/dev/null | head -n 1)" = 1 ]; then
    case "$(sed -n 's/^MODE=//p' "$SESSION" 2>/dev/null | head -n 1)" in tproxy|redirect|enhance) WD_DP_ON=1;; esac
  fi
}
# fake_range as the session's rules were built: the v4 fake range when the resolver was captured, else empty.
wd_session_fake_range(){
  WSF_SD="$SYSTEM_DNS"; WSF_F4="$FAKE_IP_V4"
  SYSTEM_DNS=$(sed -n 's/^SYSTEM_DNS=//p' "$SESSION" 2>/dev/null | head -n 1)
  FAKE_IP_V4=$(sed -n 's/^FAKE_IP_V4=//p' "$SESSION" 2>/dev/null | head -n 1)
  WSF_OUT=$(fake_range xt4q)
  SYSTEM_DNS="$WSF_SD"; FAKE_IP_V4="$WSF_F4"
  printf '%s' "$WSF_OUT"
}
# Android only: no live uid 0-9999 RETURN rule of a HETU_ chain may lack the `! -d <fake>` exclusion
# (unreadable tables do not veto; the session facts above already decided).
wd_root_fake_captured(){
  [ -d "$CG_ESCAPE_GATE" ] || return 0
  for WRC_T in mangle nat; do
    WRC_S=$(xt4q -t "$WRC_T" -S 2>/dev/null) || continue
    if printf '%s\n' "$WRC_S" | grep -E '^-A (HETU_|BICHEN_)' | grep -e '--uid-owner 0-9999' | grep -e '-j RETURN' | grep -qv -e '! -d '; then return 1; fi
  done
  return 0
}
wd_net_sig(){ ip -o -4 addr show scope global 2>/dev/null | awk '{printf "%s=%s ", $2, $4}'; }
wd_net_watch(){
  WNW_SIG=$(wd_net_sig)
  [ "$WNW_SIG" != "$WD_NET_SIG" ] || return 0
  WD_NET_SIG="$WNW_SIG"; wd_now
  wd_log "network change (${WNW_SIG:-offline}): re-checking core liveness"
  WD_LIVE_AT=0; WD_EG_FAIL=0; WD_EG_TARGET=""; [ "$WD_EG_AT" -le $((WD_NOW+20)) ] || WD_EG_AT=$((WD_NOW+20))
  WD_DP_FAIL=0; [ "$WD_DP_AT" -le $((WD_NOW+20)) ] || WD_DP_AT=$((WD_NOW+20))
}
wd_liveness(){
  WD_LIVE_REASON=""
  if wd_core_frozen "$COREPID"; then
    WLV_HOW="$WD_FROZEN_HOW"
    cg_escape "$COREPID"; kill -CONT "$COREPID" 2>/dev/null || true
    sleep 0.3
    if wd_core_frozen "$COREPID"; then WD_LIVE_REASON="frozen:$WLV_HOW"; return 1; fi
    wd_log "self-heal: core $COREPID was frozen ($WLV_HOW); moved to the root cgroup (${CG_ESCAPED:-none}) and resumed"
  fi
  if [ "$WD_CP" != 0 ]; then
    WD_HTTP_TIMEOUT=3; wd_http GET /version "$WD_TMP.live"; WLV_RC=$?; rm -f "$WD_TMP.live"
    case "$WLV_RC" in
      0) return 0;;
      2) [ "$WD_NOTOOL" = 1 ] || { WD_NOTOOL=1; wd_log "liveness: no curl/wget/nc to probe the controller"; }; return 0;;
    esac
    WD_LIVE_REASON="controller-$WD_CP-no-answer"; return 1
  fi
  WD_HTTP_TIMEOUT=3; wd_dns_probe
  [ "$?" != 1 ] || { WD_LIVE_REASON="dns-$WD_DP-no-answer"; return 1; }
  return 0
}
# At most WD_RESTART_MAX restarts per WD_RESTART_WINDOW seconds and boot; a manual start resets it.
wd_restart_allowed(){
  wd_now; WRA_BOOT=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null || true)
  WRA_C=0; WRA_S="$WD_NOW"; WRA_B=""
  if [ -r "$RUN/state/selfheal-budget" ]; then read -r WRA_B WRA_C WRA_S < "$RUN/state/selfheal-budget" 2>/dev/null || true; fi
  case "$WRA_C:$WRA_S" in *[!0-9:]*|:*|*:) WRA_C=0; WRA_S="$WD_NOW";; esac
  if [ "$WRA_B" != "$WRA_BOOT" ] || [ $((WD_NOW-WRA_S)) -ge "$WD_RESTART_WINDOW" ] || [ "$WD_NOW" -lt "$WRA_S" ]; then WRA_C=0; WRA_S="$WD_NOW"; fi
  [ "$WRA_C" -lt "$WD_RESTART_MAX" ] || return 1
  mkdir -p "$RUN/state" 2>/dev/null || true
  { printf '%s %s %s\n' "$WRA_BOOT" "$((WRA_C+1))" "$WRA_S" > "$RUN/state/selfheal-budget"; } 2>/dev/null || true
}
# Re-run the recorded start (same arguments, fresh environment) in this watchdog's root cgroup.
# Success: a new session with its own watchdog owns everything. Failure after the start began
# changing the network: its rollback removed capture (Kill Switch: kept its guards).
wd_restart_core(){
  [ -r "$RUN/state/start.args" ] || { wd_log "self-heal: no saved start arguments, cannot restart the core"; return 1; }
  rm -f "$WATCHDOG_PID"
  # Output goes to a file: background children of start must not hold a pipe open.
  (
    unset HETU_BOOT_RESTORE_ID HETU_AUTOMATIC_RECOVERY_ID HETU_START_CANCEL_TOKEN HETU_GMS_APPIDS HETU_PRIVATE_DNS_MODE
    HETU_SELFHEAL_RESTART=1; export HETU_SELFHEAL_RESTART
    set --
    while IFS= read -r WRC_A || [ -n "$WRC_A" ]; do set -- "$@" "$WRC_A"; done < "$RUN/state/start.args"
    exec "$0" start "$@"
  ) </dev/null > "$WD_TMP.restart" 2>/dev/null
  WRC_OUT=$(cat "$WD_TMP.restart" 2>/dev/null); rm -f "$WD_TMP.restart"
  case "$WRC_OUT" in *'"ok":true'*) wd_log "self-heal: core restarted ($(cat "$PIDFILE" 2>/dev/null || echo '?'))"; return 0;; esac
  wd_log "self-heal: core restart failed: $(printf '%s' "$WRC_OUT" | tr -d '\n\r' | cut -c1-240)"
  [ -f "$WATCHDOG_PID" ] || printf '%s\n' "$$" > "$WATCHDOG_PID" 2>/dev/null || true
  # A rollback that already ended the session's core has also restored (or guarded) the network.
  if [ "$(cat "$PIDFILE" 2>/dev/null)" != "$COREPID" ]; then wd_heal_notice rollback; fi
  return 1
}
wd_heal_notice(){
  if [ "$KILL" = 1 ]; then
    wd_notify "core-unresponsive restart-failed killswitch-active ($1)" "河图核心无响应且自动重启失败。Kill Switch 已开启，网络保持阻断；请打开河图重新启动或停止代理。"
  else
    wd_notify "core-unresponsive restart-failed network-restored ($1)" "河图核心无响应且自动重启失败，已自动恢复直连网络（代理已停止）。请打开河图重新启动代理。"
  fi
}
# The core stopped answering: restart it; when that is impossible end it so the core-exit path
# below removes capture (or keeps the Kill Switch guards) exactly as for a crashed core.
wd_recover(){
  WD_HEAL_REASON="$1"
  wd_log "self-heal: core $COREPID unresponsive ($1)"
  cg_escape "$COREPID"; kill -CONT "$COREPID" 2>/dev/null || true
  if wd_restart_allowed; then
    wd_log "self-heal: restarting the core"
    if wd_restart_core; then rm -f "$RUN/watchdog.ready.$$" "$WD_TMP".*; exit 0; fi
  else
    wd_log "self-heal: restart limit reached ($WD_RESTART_MAX per ${WD_RESTART_WINDOW}s)"
  fi
  if [ "$(cat "$PIDFILE" 2>/dev/null)" = "$COREPID" ] && core_maybe_alive "$COREPID" && kill -0 "$COREPID" 2>/dev/null; then
    wd_log "self-heal: ending unresponsive core $COREPID"
    kill -9 "$COREPID" 2>/dev/null || true
  fi
  WD_LIVE_FAIL=0
}
# Proxy egress: the delay of the group the rules end in (MATCH, else the first non-builtin target).
wd_egress_target(){
  WD_EG_TARGET=""
  WD_HTTP_TIMEOUT=4; wd_http GET /configs "$WD_TMP.eg" || return 1
  case "$WD_CODE" in 200) ;; 401|403) WD_EG_ON=0; wd_log "egress check off: the controller refused the session secret"; return 1;; *) return 1;; esac
  WET_MODE=$(tr ',' '\n' < "$WD_TMP.eg" | sed -n 's/^[{[:space:]]*"mode"[[:space:]]*:[[:space:]]*"\([A-Za-z]*\)".*/\1/p' | head -n 1)
  case "$WET_MODE" in [Dd]irect) return 1;; [Gg]lobal) WD_EG_TARGET=GLOBAL; return 0;; esac
  wd_http GET /rules "$WD_TMP.eg" && [ "$WD_CODE" = 200 ] || return 1
  WD_EG_TARGET=$(tr '{' '\n' < "$WD_TMP.eg" | awk '
    function f(k,  i, s) { i = index($0, "\"" k "\":\""); if (!i) return ""; s = substr($0, i + length(k) + 4); i = index(s, "\""); return i ? substr(s, 1, i - 1) : "" }
    { p = f("proxy"); if (p == "") next
      gsub(/\\u0026/, "\\&", p); gsub(/\\u003c/, "<", p); gsub(/\\u003e/, ">", p)
      builtin = (p ~ /^(DIRECT|REJECT|REJECT-DROP|REJECT-TINY|PASS|COMPATIBLE|direct|block|dns-out)$/)
      if (!builtin && first == "") first = p
      if (f("type") == "Match" && !builtin) m = p }
    END { if (m != "") print m; else if (first != "") print first }')
  rm -f "$WD_TMP.eg"
  [ -n "$WD_EG_TARGET" ]
}
wd_urlenc(){ printf '%s' "$1" | od -An -v -tx1 | tr -d ' \n' | sed 's/../%&/g'; }
# 0 = delay measured, 1 = failed/timeout, 2 = unusable answer (target gone, secret refused).
wd_delay(){
  WD_HTTP_TIMEOUT=8
  wd_http GET "/proxies/$(wd_urlenc "$1")/delay?timeout=5000&url=$(wd_urlenc "$2")" "$WD_TMP.dl" hold; WDL_RC=$?
  WDL_BODY=$(cat "$WD_TMP.dl" 2>/dev/null); rm -f "$WD_TMP.dl"
  [ "$WDL_RC" != 2 ] || return 2
  case "$WD_CODE" in
    200) case "$WDL_BODY" in *'"delay":'[1-9]*) return 0;; esac; return 1;;
    401|403) WD_EG_ON=0; return 2;;
    400|404) return 2;;
  esac
  return 1
}
wd_egress_tick(){
  WD_EG_AT=$((WD_NOW+WD_EGRESS_EVERY))
  [ -n "$WD_EG_TARGET" ] || wd_egress_target || { WD_EG_AT=$((WD_NOW+900)); return 0; }
  wd_delay "$WD_EG_TARGET" "$WD_PROXY_URL"; WET_RC=$?
  case "$WET_RC" in
    0) if [ "$WD_EG_FAIL" != 0 ] || [ "$WD_EG_LEVEL" != 0 ]; then wd_log "proxy egress recovered via $WD_EG_TARGET"; fi
       WD_OFF_N=0; WD_EG_FAIL=0; WD_EG_LEVEL=0; WD_EG_BACKOFF="$WD_EGRESS_BACKOFF"; WD_NODE_NOTIFIED=0; return 0;;
    2) WD_EG_TARGET=""; return 0;;
  esac
  # Proxy failed. Only a working direct path makes it a proxy-side outage worth acting on.
  WET_DIRECT=1
  for WET_U in $WD_DIRECT_URLS; do wd_delay DIRECT "$WET_U" && { WET_DIRECT=0; break; }; done
  # 3.3.3: DIRECT failing too is a whole-phone outage, not "nothing to do": check the Wi-Fi outside Hetu.
  if [ "$WET_DIRECT" != 0 ]; then WD_EG_FAIL=0; wd_offline_seen "proxy egress via $WD_EG_TARGET and DIRECT fail"; return 0; fi
  WD_OFF_N=0
  WD_EG_FAIL=$((WD_EG_FAIL+1)); WD_EG_AT=$((WD_NOW+WD_EGRESS_RETRY))
  wd_log "proxy egress probe failed via $WD_EG_TARGET while direct works ($WD_EG_FAIL/$WD_EGRESS_LIMIT)"
  [ "$WD_EG_FAIL" -ge "$WD_EGRESS_LIMIT" ] || return 0
  WD_EG_FAIL=0; WD_EG_AT=$((WD_NOW+WD_EGRESS_EVERY))
  if [ "$WD_NOW" -lt "$WD_EG_NEXT_ACTION" ]; then wd_log "proxy egress still down; next self-heal action in $((WD_EG_NEXT_ACTION-WD_NOW))s (backoff)"; return 0; fi
  WD_EG_LEVEL=$((WD_EG_LEVEL+1))
  WD_EG_NEXT_ACTION=$((WD_NOW+WD_EG_BACKOFF)); WD_EG_BACKOFF=$((WD_EG_BACKOFF*2)); [ "$WD_EG_BACKOFF" -le "$WD_EGRESS_BACKOFF_MAX" ] || WD_EG_BACKOFF="$WD_EGRESS_BACKOFF_MAX"
  # Direct works and the controller answers: only the connections through the failing group are closed.
  rm -f "$WD_TMP.groups"
  wd_node_heal "$WD_EG_TARGET$WD_TAB" "$WD_EG_TARGET"
}
wd_selfheal_tick(){
  wd_now
  if [ -f "$RUN/liveness.kick" ]; then
    rm -f "$RUN/liveness.kick"
    wd_log "network change reported: re-checking core liveness"
    WD_LIVE_AT=0; WD_EG_FAIL=0; WD_EG_TARGET=""; [ "$WD_EG_AT" -le $((WD_NOW+20)) ] || WD_EG_AT=$((WD_NOW+20))
    WD_DP_FAIL=0; [ "$WD_DP_AT" -le $((WD_NOW+20)) ] || WD_DP_AT=$((WD_NOW+20))
  fi
  if [ "$WD_NOW" -ge "$WD_LIVE_AT" ]; then
    WD_LIVE_AT=$((WD_NOW+WD_LIVE_EVERY))
    if wd_liveness; then
      [ "$WD_LIVE_FAIL" = 0 ] || wd_log "core liveness recovered after $WD_LIVE_FAIL failed probe(s)"
      WD_LIVE_FAIL=0
    else
      WD_LIVE_FAIL=$((WD_LIVE_FAIL+1)); WD_LIVE_AT=$((WD_NOW+WD_LIVE_RETRY))
      wd_log "core liveness probe failed ($WD_LIVE_REASON) $WD_LIVE_FAIL/$WD_LIVE_LIMIT"
      [ "$WD_LIVE_FAIL" -lt "$WD_LIVE_LIMIT" ] || { wd_recover "$WD_LIVE_REASON"; return 0; }
    fi
  fi
  [ "$WD_LIVE_FAIL" = 0 ] || return 0
  # 6.0.0: a Mihomo that could not open its cache.db at start (another process held the lock) keeps no
  # fake-IP record for its whole life when the pool is persisted; checked once per core, at its first tick.
  if [ "$WD_CACHE_SEEN" = 0 ]; then
    WD_CACHE_SEEN=1
    if wd_cache_lost && grep -Eq '^[[:space:]]*store-fake-ip:[[:space:]]*true' "$WD_CFG" 2>/dev/null; then
      WD_DP_DETAIL="core log: cache file could not be opened at start"; wd_egress_state fakeip-lost
      wd_log "core $COREPID runs without its cache file; fake-IP connections cannot be mapped back"
      wd_fakeip_heal; return 0
    fi
  fi
  # The controller answers but the DNS listener may still be wedged.
  if [ "$WD_CP" != 0 ] && [ "$WD_NOW" -ge "$WD_DNS_AT" ]; then
    WD_DNS_AT=$((WD_NOW+WD_DNS_EVERY)); WD_HTTP_TIMEOUT=3; wd_dns_probe; WDT_RC=$?
    if [ "$WDT_RC" = 1 ]; then
      WD_DNS_FAIL=$((WD_DNS_FAIL+1)); WD_DNS_AT=$((WD_NOW+WD_LIVE_EVERY))
      wd_log "core DNS probe failed (port $WD_DP) $WD_DNS_FAIL/$WD_DNS_LIMIT"
      [ "$WD_DNS_FAIL" -lt "$WD_DNS_LIMIT" ] || { WD_DNS_FAIL=0; wd_recover "dns-$WD_DP-no-answer"; return 0; }
    else WD_DNS_FAIL=0; fi
  fi
  # One egress check, not two: the data-plane probe when it can run, else the controller delay check.
  if [ "$WD_DP_ON" = 1 ]; then
    [ "$WD_NOW" -lt "$WD_DP_AT" ] || wd_dataplane_tick
  elif [ "$WD_EG_ON" = 1 ] && [ "$WD_NOW" -ge "$WD_EG_AT" ]; then wd_egress_tick; fi
  return 0
}
# ---- data-plane probe (3.0.0) -----------------------------------------------------------
# A/IN query for $1 over TCP to the core's DNS listener; WD_RES_IP = the first A record.
wd_resolve(){
  WD_RES_IP=""
  WRS_Q=$(printf '%s' "$1" | awk -F. '{ for (i = 1; i <= NF; i++) if (length($i) > 0 && length($i) < 64) printf "\\%03o%s", length($i), $i }')
  # Length prefix = 12 header + encoded QNAME (length octet per label + root 0) + 4 (QTYPE, QCLASS).
  WRS_QL=$(printf '%s' "$1" | awk -F. '{ n = 1; for (i = 1; i <= NF; i++) if (length($i) > 0 && length($i) < 64) n += length($i) + 1; print n }')
  WRS_LEN=$((WRS_QL + 16))
  WRS_BYTES=$( { printf "\\$(printf %03o $((WRS_LEN / 256)))\\$(printf %03o $((WRS_LEN % 256)))"
      printf '\110\124\001\000\000\001\000\000\000\000\000\000'; printf "$WRS_Q"; printf '\000\000\001\000\001'; } 2>/dev/null \
    | timeout "$((WD_HTTP_TIMEOUT+1))" nc -w "$WD_HTTP_TIMEOUT" 127.0.0.1 "$WD_DP" 2>/dev/null | od -An -v -tu1)
  WD_RES_IP=$(printf '%s\n' "$WRS_BYTES" | awk '
    { for (i = 1; i <= NF; i++) b[++n] = $i + 0 }
    function skip(p) { while (p <= n) { if (b[p] == 0) return p + 1; if (b[p] >= 192) return p + 2; p += b[p] + 1 } return n + 1 }
    END { o = 3; if (n < o + 12) exit; qd = b[o+4] * 256 + b[o+5]; an = b[o+6] * 256 + b[o+7]; p = o + 12
      for (q = 0; q < qd; q++) p = skip(p) + 4
      for (a = 0; a < an && p <= n; a++) { p = skip(p); t = b[p] * 256 + b[p+1]; l = b[p+8] * 256 + b[p+9]
        if (t == 1 && l == 4 && p + 13 <= n) { print b[p+10] "." b[p+11] "." b[p+12] "." b[p+13]; exit }
        p += 10 + l } }')
  [ -n "$WD_RES_IP" ]
}
# 0 when IPv4 $1 lies inside CIDR $2.
wd_in_cidr(){
  awk -v ip="$1" -v c="$2" 'function v(a,  x) { if (split(a, x, ".") != 4) return -1; return ((x[1] * 256 + x[2]) * 256 + x[3]) * 256 + x[4] }
    BEGIN { if (split(c, y, "/") != 2) exit 1; m = y[2] + 0; if (m < 1 || m > 32) exit 1; s = 2 ^ (32 - m)
      if (v(ip) < 0 || v(y[1]) < 0) exit 1; exit !(int(v(ip) / s) == int(v(y[1]) / s)) }'
}
# HTTP GET http://$1$3 with Host $2 as root (captured like any app). 0 = an HTTP answer arrived,
# 1 = none within WD_DP_TIMEOUT, 2 = no tool (curl, busybox wget, nc).
wd_fetch(){
  WF_T="$WD_DP_TIMEOUT"
  if has curl; then
    WF_C=$(curl -sS -m "$WF_T" -o /dev/null -w '%{http_code}' -H "Host: $2" "http://$1$3" 2>/dev/null)
    case "$WF_C" in [1-5][0-9][0-9]) return 0;; esac; return 1
  fi
  WF_BB=""
  for WF_B in /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox /data/adb/magisk/busybox; do [ -x "$WF_B" ] && { WF_BB="$WF_B"; break; }; done
  if [ -n "$WF_BB" ]; then
    WF_C=$(timeout "$((WF_T+1))" "$WF_BB" wget -S -q -T "$WF_T" -O /dev/null --header "Host: $2" "http://$1$3" 2>&1 | awk '$1 ~ /^HTTP\// {c=$2} END {print c}')
    case "$WF_C" in [1-5][0-9][0-9]) return 0;; esac; return 1
  fi
  has nc || return 2
  # The request side stays open until the first answer bytes arrive (or WF_T), not always the full WF_T.
  WF_O="${WD_TMP:-/tmp/.hetu-wf.$$}.fetch"; : > "$WF_O" 2>/dev/null || return 1
  { printf 'GET %s HTTP/1.0\r\nHost: %s\r\nUser-Agent: hetu-watchdog\r\nConnection: close\r\n\r\n' "$3" "$2"
    WF_I=0; while [ "$WF_I" -lt $((WF_T*5)) ] && [ ! -s "$WF_O" ]; do sleep 0.2 2>/dev/null || sleep 1; WF_I=$((WF_I+1)); done
    [ ! -s "$WF_O" ] || sleep 0.2 2>/dev/null || true; } 2>/dev/null \
    | timeout "$((WF_T+1))" nc -w "$WF_T" "$1" 80 > "$WF_O" 2>/dev/null
  WF_L=$(head -n 1 "$WF_O" 2>/dev/null); rm -f "$WF_O"
  case "$WF_L" in HTTP/*' '[1-5][0-9][0-9]*) return 0;; esac
  return 1
}
# 6.0.0: HTTP GET http://$1$2 through the core's loopback HTTP listener on port $3. 0 = the site answered,
# 1 = no answer within WD_DP_TIMEOUT or a 5xx (Mihomo and sing-box answer 502 when their dial fails or a
# rule rejects), 2 = no tool.
wd_fetch_proxied(){
  WFP_T="$WD_DP_TIMEOUT"; WFP_C=""
  if has curl; then
    WFP_C=$(curl -sS -m "$WFP_T" -o /dev/null -w '%{http_code}' -x "http://127.0.0.1:$3" "http://$1$2" 2>/dev/null)
  else
    WFP_BB=""
    for WFP_B in /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox /data/adb/magisk/busybox; do [ -x "$WFP_B" ] && { WFP_BB="$WFP_B"; break; }; done
    if [ -n "$WFP_BB" ]; then
      WFP_C=$(http_proxy="http://127.0.0.1:$3" timeout "$((WFP_T+1))" "$WFP_BB" wget -S -q -T "$WFP_T" -O /dev/null "http://$1$2" 2>&1 | awk '$1 ~ /^HTTP\// {c=$2} END {print c}')
    else
      has nc || return 2
      WFP_O="${WD_TMP:-/tmp/.hetu-wf.$$}.pfetch"; : > "$WFP_O" 2>/dev/null || return 1
      { printf 'GET http://%s%s HTTP/1.0\r\nHost: %s\r\nUser-Agent: hetu-watchdog\r\nConnection: close\r\n\r\n' "$1" "$2" "$1"
        WFP_I=0; while [ "$WFP_I" -lt $((WFP_T*5)) ] && [ ! -s "$WFP_O" ]; do sleep 0.2 2>/dev/null || sleep 1; WFP_I=$((WFP_I+1)); done
        [ ! -s "$WFP_O" ] || sleep 0.2 2>/dev/null || true; } 2>/dev/null \
        | timeout "$((WFP_T+1))" nc -w "$WFP_T" 127.0.0.1 "$3" > "$WFP_O" 2>/dev/null
      WFP_C=$(head -n 1 "$WFP_O" 2>/dev/null | awk '$1 ~ /^HTTP\// {print $2}'); rm -f "$WFP_O"
    fi
  fi
  case "$WFP_C" in [1-4][0-9][0-9]) return 0;; esac
  return 1
}
# 6.0.0: what the core log says about the probe host $1 after byte $2 of $LOG (written while the request
# through the core's own listener ran). WD_ROUTE_VIA = the rule's target (group or DIRECT/REJECT), WD_ROUTE_NODE
# = the outbound actually used when logged, WD_ROUTE_RULE = the rule, WD_ROUTE_ERR = the dial error. Mihomo:
#   [TCP] dial G (match Domain/x) src --> host:80 error: ...      [TCP] src --> host:80 match R(x) using G[node]
# sing-box: open connection to host:80 using outbound/socks[tag]: ...
wd_route_seen(){
  WD_ROUTE_VIA=""; WD_ROUTE_NODE=""; WD_ROUTE_RULE=""; WD_ROUTE_ERR=""
  [ -r "$LOG" ] || return 1
  WRS_L=$(tail -c +"$(( ${2:-0} + 1 ))" "$LOG" 2>/dev/null | grep -F -e "--> $1:" -e "connection to $1:" | tail -n 1 | sed 's/^time="[^"]*" level=[a-z]* msg="//; s/"$//')
  [ -n "$WRS_L" ] || return 1
  case "$WRS_L" in
    *'] dial '*' (match '*') '*' --> '*' error: '*)
      WRS_R=${WRS_L#*'] dial '}; WD_ROUTE_VIA=${WRS_R%%' (match '*}
      WRS_R=${WRS_R#*' (match '}; WD_ROUTE_RULE=${WRS_R%%') '*}; WD_ROUTE_ERR=${WRS_L#*' error: '};;
    *'] dial '*' --> '*' error: '*)
      WRS_R=${WRS_L#*'] dial '}; WD_ROUTE_VIA=${WRS_R%% *}; WD_ROUTE_ERR=${WRS_L#*' error: '};;
    *' match '*' using '*)
      WRS_R=${WRS_L#*' match '}; WD_ROUTE_RULE=${WRS_R%%' using '*}; WRS_R=${WRS_L##*' using '}
      case "$WRS_R" in *'['*']') WD_ROUTE_VIA=${WRS_R%%'['*}; WD_ROUTE_NODE=${WRS_R#*'['}; WD_ROUTE_NODE=${WD_ROUTE_NODE%']'};; *) WD_ROUTE_VIA="$WRS_R";; esac;;
    *' using outbound/'*)
      WRS_R=${WRS_L#*' using outbound/'}; WRS_R=${WRS_R#*'['}; WD_ROUTE_VIA=${WRS_R%%']'*}
      case "$WRS_L" in *"]: "*) WD_ROUTE_ERR=${WRS_L#*"$WD_ROUTE_VIA]: "};; esac;;
    *' outbound/'*']: outbound connection to '*)
      WRS_R=${WRS_L#*' outbound/'}; WRS_R=${WRS_R#*'['}; WD_ROUTE_VIA=${WRS_R%%']'*};;
    *) return 1;;
  esac
  WD_ROUTE_RULE=${WD_ROUTE_RULE%/}; WD_ROUTE_ERR=$(printf '%s' "$WD_ROUTE_ERR" | cut -c1-160)
  [ -n "$WD_ROUTE_VIA" ]
}
# 6.0.0: 0 when this core's log says Mihomo could not open $RUN/cache.db (another process held its lock for more
# than Mihomo's 1 s wait). Mihomo then runs without it for its whole life and its persisted fake-IP pool keeps no
# record: every fake-IP connection fails while real-IP traffic and node delay tests still work. The log is
# emptied at each start, so a line belongs to the running core.
wd_cache_lost(){
  [ -r "$LOG" ] || return 1
  grep -F '[CacheFile] ' "$LOG" 2>/dev/null | grep -q "can't open cache file"
}
# Selector/URLTest/Fallback/LoadBalance groups from GET /proxies -> "$WD_TMP.groups"
# (type TAB name TAB now TAB fixed; fixed = the node the user pinned in an auto-select group, else empty).
wd_groups(){
  : > "$WD_TMP.groups"
  WD_HTTP_TIMEOUT=4; wd_http GET /proxies "$WD_TMP.gr" && [ "$WD_CODE" = 200 ] || { rm -f "$WD_TMP.gr"; return 1; }
  tr '{' '\n' < "$WD_TMP.gr" | awk -v max="${WD_DP_GROUP_MAX:-12}" '
    function f(k,  i, s) { i = index($0, "\"" k "\":\""); if (!i) return ""; s = substr($0, i + length(k) + 4); i = index(s, "\""); return i ? substr(s, 1, i - 1) : "" }
    function u(x) { gsub(/\\u0026/, "\\&", x); gsub(/\\u003c/, "<", x); gsub(/\\u003e/, ">", x); return x }
    { t = f("type"); n = u(f("name")); if (t !~ /^(Selector|URLTest|Fallback|LoadBalance)$/ || n == "" || n == "GLOBAL" || (n in seen)) next
      seen[n] = 1; if (++c <= max) print t "\t" n "\t" u(f("now")) "\t" u(f("fixed")) }' > "$WD_TMP.groups" 2>/dev/null
  rm -f "$WD_TMP.gr"
  return 0
}
# Delay of each group's current choice through the core (no TPROXY involved). WD_DP_BAD lists the groups
# that fail ("group→node"), WD_DP_SCANNED how many were measured.
wd_scan_groups(){
  WD_DP_BAD=""; WD_DP_BAD_NAMES=""; WD_DP_SCANNED=0; WSG_N=0
  wd_groups || return 0
  wd_budget_start
  while IFS= read -r WSG_L; do
    wd_budget_left || { wd_log "data-plane probe: group scan stopped (${WD_BUDGET_WHY})"; break; }
    WSG_R=${WSG_L#*"$WD_TAB"}; WSG_G=${WSG_R%%"$WD_TAB"*}; WSG_NOW=${WSG_R#*"$WD_TAB"}; WSG_NOW=${WSG_NOW%%"$WD_TAB"*}
    case "$WSG_NOW" in DIRECT|REJECT|REJECT-DROP|PASS) continue;; esac
    wd_delay "$WSG_G" "$WD_PROXY_URL" </dev/null; WSG_RC=$?
    [ "$WSG_RC" != 2 ] || continue
    WD_DP_SCANNED=$((WD_DP_SCANNED+1))
    if [ "$WSG_RC" = 1 ]; then WD_DP_BAD="${WD_DP_BAD:+$WD_DP_BAD, }$WSG_G${WSG_NOW:+→$WSG_NOW}"; WD_DP_BAD_NAMES="$WD_DP_BAD_NAMES$WSG_G$WD_TAB${WSG_NOW:+$WSG_NOW$WD_TAB}"; WSG_N=$((WSG_N+1)); [ "$WSG_N" -lt 3 ] || break; fi
  done < "$WD_TMP.groups"
  return 0
}
# Elapsed-time budget for the per-group loops (each delay call may take ~9 s): at most WD_DP_BUDGET s
# from wd_budget_start, and never while the core is gone.
wd_budget_start(){ wd_uptime; WD_BUDGET_T0="$WD_UP"; }
wd_budget_left(){
  WD_BUDGET_WHY=""
  if [ -n "${COREPID:-}" ] && ! kill -0 "$COREPID" 2>/dev/null; then WD_BUDGET_WHY="core $COREPID gone"; return 1; fi
  wd_uptime
  [ $((WD_UP-WD_BUDGET_T0)) -lt "${WD_DP_BUDGET:-30}" ] || { WD_BUDGET_WHY="${WD_DP_BUDGET:-30}s budget used"; return 1; }
  return 0
}
wd_uptime(){ read -r WD_UP WD_UP_REST < /proc/uptime 2>/dev/null || WD_UP=0; WD_UP=${WD_UP%%.*}; case "$WD_UP" in ''|*[!0-9]*) WD_UP=0;; esac; }
# One probe. WD_DP_CLASS: ok | proxy-down | dataplane-down | offline | skip (no verdict) | unavailable.
wd_dp_probe(){
  WD_DP_CLASS=skip; WD_DP_DETAIL=""; WD_DP_WHY=""; WD_DP_BAD=""; WD_DP_BAD_NAMES=""; WD_DP_SCANNED=0
  [ "$WD_EG_ON" = 1 ] || { WD_DP_CLASS=unavailable; WD_DP_WHY=no-controller; return 0; }
  case " $WD_DNS_PROTOS " in *" tcp "*) ;; *) WD_DP_CLASS=unavailable; WD_DP_WHY=dns-without-tcp; return 0;; esac
  has nc || { WD_DP_CLASS=unavailable; WD_DP_WHY=no-nc; return 0; }
  WD_HTTP_TIMEOUT=3
  # No answer at all is the DNS listener probe's business.
  wd_resolve "$WD_DP_HOST" || { WD_DP_DETAIL="no DNS answer for $WD_DP_HOST"; return 0; }
  WDP_IP="$WD_RES_IP"
  wd_in_cidr "$WDP_IP" "$WD_FAKE4" || { WD_DP_CLASS=unavailable; WD_DP_WHY="$WD_DP_HOST-real-ip-$WDP_IP"; return 0; }
  wd_fetch "$WDP_IP" "$WD_DP_HOST" "$WD_DP_PATH"; WDP_RC=$?
  [ "$WDP_RC" != 2 ] || { WD_DP_CLASS=unavailable; WD_DP_WHY=no-http-tool; return 0; }
  [ "$WDP_RC" != 0 ] || { WD_DP_CLASS=ok; return 0; }
  WD_DP_DETAIL="$WD_DP_HOST via $WDP_IP no answer"
  # The network itself: DIRECT through the core, no proxy node and no TPROXY involved.
  if [ "$WD_EG_ON" = 1 ]; then
    WDP_DIRECT=1
    for WDP_U in $WD_DIRECT_URLS; do wd_delay DIRECT "$WDP_U" && { WDP_DIRECT=0; break; }; done
    [ "$WD_EG_ON" = 1 ] || { WD_DP_CLASS=unavailable; WD_DP_WHY=no-controller; return 0; }
    [ "$WDP_DIRECT" = 0 ] || { WD_DP_CLASS=offline; WD_DP_DETAIL="$WD_DP_DETAIL; DIRECT fails too"; return 0; }
  fi
  # A CN host the core answers with a fake IP: TPROXY -> core -> (normally) DIRECT.
  WDP_CN=""
  for WDP_H in $WD_DP_CN_HOSTS; do
    wd_resolve "$WDP_H" && wd_in_cidr "$WD_RES_IP" "$WD_FAKE4" || continue
    if wd_fetch "$WD_RES_IP" "$WDP_H" /; then WDP_CN=ok; else WDP_CN=fail; fi
    WD_DP_DETAIL="$WD_DP_DETAIL; $WDP_H via $WD_RES_IP $WDP_CN"; break
  done
  wd_scan_groups
  # The controller refused the secret meanwhile: no verdict to act on.
  [ "$WD_EG_ON" = 1 ] || { WD_DP_CLASS=unavailable; WD_DP_WHY=no-controller; return 0; }
  [ -z "$WD_DP_BAD" ] || WD_DP_DETAIL="$WD_DP_DETAIL; no answer through $WD_DP_BAD"
  # 6.0.0: the same name again. A core that lost its fake-IP store hands out a new address on every query
  # and maps none of them back (host-reproduced: Mihomo with its cache.db locked by another process).
  WDP_LOST=0
  if wd_resolve "$WD_DP_HOST" && wd_in_cidr "$WD_RES_IP" "$WD_FAKE4" && [ "$WD_RES_IP" != "$WDP_IP" ]; then
    WDP_LOST=1; WD_DP_DETAIL="$WD_DP_DETAIL; $WD_DP_HOST now resolves to $WD_RES_IP (fake-IP record lost)"
  fi
  if wd_cache_lost; then WDP_LOST=1; WD_DP_DETAIL="$WD_DP_DETAIL; core log: cache file could not be opened"; fi
  # 6.0.0: the same request through the core's own HTTP listener (rules -> group -> node; no TPROXY, no fake
  # IP). Answering there means captured requests do not get through; failing there too, while every group
  # answers its delay test, means the rules send the site somewhere else (DIRECT, a reject, an unscanned group).
  WDP_EX=""; WD_ROUTE_VIA=""; WD_ROUTE_NODE=""; WD_ROUTE_RULE=""; WD_ROUTE_ERR=""
  if [ -z "$WD_DP_BAD" ] && [ "$WDP_CN" != fail ] && [ "$WDP_LOST" = 0 ]; then
    WDP_AT=$(wc -c < "$LOG" 2>/dev/null | tr -d ' '); case "$WDP_AT" in ''|*[!0-9]*) WDP_AT=0;; esac
    wd_fetch_proxied "$WD_DP_HOST" "$WD_DP_PATH" "$((WD_CP+WD_DP_EGRESS_OFFSET))"
    case "$?" in
      0) WDP_EX=ok; WD_DP_DETAIL="$WD_DP_DETAIL; through the core's own listener it answers";;
      1) WDP_EX=fail; WD_DP_DETAIL="$WD_DP_DETAIL; through the core's own listener it fails too"
         if wd_route_seen "$WD_DP_HOST" "$WDP_AT"; then
           WD_DP_DETAIL="$WD_DP_DETAIL (${WD_ROUTE_RULE:+rule $WD_ROUTE_RULE, }via $WD_ROUTE_VIA${WD_ROUTE_NODE:+[$WD_ROUTE_NODE]}${WD_ROUTE_ERR:+: $WD_ROUTE_ERR})"
         fi;;
    esac
  fi
  WD_DP_CLASS=$(wd_dp_classify "$WDP_CN" "$WD_DP_BAD" "$WD_DP_SCANNED" "$WDP_EX" "$WDP_LOST")
  # A probe that queued behind busy downloads looks the same (5.1.0): while proxied connections still receive
  # data there is no verdict.
  if [ "$WDP_CN" != fail ]; then
    case "$WD_DP_CLASS" in dataplane-down|route-down)
      wd_conn_busy "*proxied*"
      if [ "$WD_CLOSE_BUSY" -gt 0 ]; then WD_DP_CLASS=skip; WD_DP_DETAIL="$WD_DP_DETAIL; $WD_CLOSE_BUSY proxied connection(s) receiving data"; fi;;
    esac
  fi
  [ "$WD_DP_CLASS" != skip ] || [ -n "$WDP_EX" ] || WD_DP_DETAIL="$WD_DP_DETAIL; every proxy group answers through the controller"
  return 0
}
# $1 CN fetch through the data plane (ok | fail | empty), $2 failing groups, $3 groups measured,
# $4 (6.0.0) the probe through the core's own HTTP listener (ok | fail | empty), $5 (6.0.0) 1 = fake-IP record lost.
# A lost fake-IP record wins (fakeip-lost: only a new core helps), then a failing group (proxy-down);
# dataplane-down when the CN fetch failed or the core's own listener reached the site; route-down when that
# failed too. 5.1.0: without any of these there is no verdict (skip): every group answering through the
# controller while the probe timed out is what a probe queued behind a busy download looks like.
wd_dp_classify(){
  if [ "${5:-0}" = 1 ]; then echo fakeip-lost
  elif [ -n "$2" ]; then echo proxy-down
  elif [ "$1" = fail ]; then echo dataplane-down
  elif [ "${4:-}" = ok ]; then echo dataplane-down
  elif [ "${4:-}" = fail ]; then echo route-down
  else echo skip; fi
}
# A whole-phone outage verdict ($1 = what failed). After WIFI_HEAL_OFFLINE_LIMIT in a row a detached wifi-heal
# probes the Wi-Fi outside Hetu and escalates (flushnet + reevaluate, reconnect, Wi-Fi off/on; rate-limited there).
# A new check at most every WIFI_HEAL_GAP/2 s while the outage lasts.
wd_offline_seen(){
  WD_OFF_N=$((WD_OFF_N+1))
  [ "$WD_OFF_N" -ge "$WIFI_HEAL_OFFLINE_LIMIT" ] || return 0
  WD_OFF_N=0
  [ "$WD_NOW" -ge "$WD_OFF_NEXT" ] || return 0
  wifi_heal_async watchdog || return 0
  WD_OFF_NEXT=$((WD_NOW+WIFI_HEAL_GAP/2))
  wd_log "whole-phone outage ($1) while the core answers; checking Wi-Fi outside Hetu"
  return 0
}
wd_egress_state(){ { printf '%s %s %s\n' "$1" "$COREPID" "$(date +%s)" > "$RUN/egress-state.new.$$" && mv -f "$RUN/egress-state.new.$$" "$RUN/egress-state"; } 2>/dev/null || true; }
wd_repair_rules(){ "$0" repair-network "$COREPID" >/dev/null 2>&1 || true; }
# Core connections whose chain passes through one of the proxies/groups named in $1 (tab separated) ->
# $2, one "id TAB download-bytes" line each; read once (GET /connections, Clash API: Mihomo and sing-box).
# 1 = the list cannot be read. WD_CLOSE_SEEN = connections in the list.
WD_CLOSE_MAX=512
wd_conn_match(){
  WD_CLOSE_SEEN=-1; : > "$2"
  [ -n "$1" ] || return 1
  WD_HTTP_TIMEOUT=4; wd_http GET /connections "$WD_TMP.cn" && [ "$WD_CODE" = 200 ] || { rm -f "$WD_TMP.cn"; return 1; }
  # Objects split at "{": a connection's id, then (after its metadata) its counters and chains.
  WD_CLOSE_SEEN=$(tr '{' '\n' < "$WD_TMP.cn" | awk -v names="$1" -v max="$WD_CLOSE_MAX" -v ids="$2" '
    function u(x) { gsub(/\\u0026/, "\\&", x); gsub(/\\u003c/, "<", x); gsub(/\\u003e/, ">", x); gsub(/\\"/, "\"", x); return x }
    BEGIN { printf "" > ids; anyp = (names == "*proxied*"); n = split(names, a, "\t"); for (i = 1; i <= n; i++) if (a[i] != "") want[a[i]] = 1 }
    { if ((i = index($0, "\"id\":\"")) > 0) { s = substr($0, i + 6); j = index(s, "\""); id = j ? substr(s, 1, j - 1) : ""; dl = 0; seen++ }
      if (id != "" && (i = index($0, "\"download\":")) > 0) dl = substr($0, i + 11) + 0
      if (id != "" && (i = index($0, "\"chains\":[")) > 0) {
        s = substr($0, i + 10); j = index(s, "]"); if (j) s = substr(s, 1, j - 1)
        hit = 0; m = split(s, c, "\",\"")
        # 6.0.0 "*proxied*": every connection whose outbound (first chain entry) is not direct or a reject.
        if (anyp) { x = c[1]; gsub(/^"|"$/, "", x); hit = (x != "" && x !~ /^(DIRECT|direct|REJECT|REJECT-DROP|PASS|COMPATIBLE|block|reject)$/) }
        for (k = 1; k <= m && !anyp; k++) { x = c[k]; gsub(/^"|"$/, "", x); if (u(x) in want) hit = 1 }
        if (hit && taken < max) { print id "\t" dl > ids; taken++ }
        id = "" } }
    END { print seen + 0 }')
  rm -f "$WD_TMP.cn"
  return 0
}
# Close only the core connections through the names in $1 (wd_conn_match): a game or a domestic app on
# DIRECT, and other groups' connections, stay up; when the list cannot be read nothing is closed.
# WD_CLOSED / WD_CLOSE_SEEN report it.
wd_close_through(){
  WD_CLOSED=0
  wd_conn_match "$1" "$WD_TMP.ids" || { rm -f "$WD_TMP.ids"; return 1; }
  while IFS="$WD_TAB" read -r WCT_ID WCT_DL; do
    case "$WCT_ID" in ''|*[!0-9A-Za-z-]*) continue;; esac
    wd_http DELETE "/connections/$WCT_ID" "$WD_TMP.act" </dev/null; rm -f "$WD_TMP.act"
    WD_CLOSED=$((WD_CLOSED+1))
  done < "$WD_TMP.ids"
  rm -f "$WD_TMP.ids"
  return 0
}
# 5.1.0: WD_CLOSE_BUSY = connections through the names in $1 whose download counter grew over WD_BUSY_GAP s.
# A node that is receiving data is not dead, whatever a probe that queued behind it says.
wd_conn_busy(){
  WD_CLOSE_BUSY=0
  wd_conn_match "$1" "$WD_TMP.b1" && [ -s "$WD_TMP.b1" ] || { rm -f "$WD_TMP.b1"; return 0; }
  sleep "$WD_BUSY_GAP" 2>/dev/null || true
  if wd_conn_match "$1" "$WD_TMP.b2"; then
    WD_CLOSE_BUSY=$(awk -F '\t' 'FNR == NR { a[$1] = $2; next } ($1 in a) && $2 + 0 > a[$1] + 0 { n++ } END { print n + 0 }' "$WD_TMP.b1" "$WD_TMP.b2")
  fi
  rm -f "$WD_TMP.b1" "$WD_TMP.b2"
  return 0
}
# The names in $1 plus the group/node each named group currently uses ($WD_TMP.groups), so the auto-select
# group and the node behind a selector are part of the outage: 最终兜底 -> 故障转移 -> 香港 01.
wd_heal_chain(){
  WHC_NAMES="$1"
  [ -s "$WD_TMP.groups" ] || { printf '%s' "$WHC_NAMES"; return 0; }
  for WHC_I in 1 2 3 4; do
    WHC_MORE=$(awk -F '\t' -v names="$WHC_NAMES" 'BEGIN { n = split(names, a, "\t"); for (i = 1; i <= n; i++) if (a[i] != "") in_[a[i]] = 1 }
      ($2 in in_) && $3 != "" && !($3 in in_) && $3 !~ /^(DIRECT|REJECT|REJECT-DROP|PASS|COMPATIBLE)$/ { printf "%s\t", $3; in_[$3] = 1 }' "$WD_TMP.groups")
    [ -n "$WHC_MORE" ] || break
    WHC_NAMES="$WHC_NAMES$WHC_MORE"
  done
  printf '%s' "$WHC_NAMES"
}
# A proxy-side outage while the core and DIRECT work ($1 = failing groups/nodes, tab separated; $2 = what
# was seen). Nothing is done while connections through them still receive data. Otherwise the failing
# url-test/fallback/load-balance groups re-measure (and switch) first, then only the connections through
# the failing names are closed, and the user is told once per outage. A group where the user pinned a
# node is not re-measured (Mihomo's group delay test clears the pin). The core is never reloaded or
# restarted for this: a new core reaches the same node, and a restart drops every connection.
wd_node_heal(){
  WNH_NAMES="$1"
  if [ -z "$WNH_NAMES" ]; then wd_log "self-heal: proxy probe failed ($2) but no group or node failed its own check; nothing done"; return 0; fi
  [ -s "$WD_TMP.groups" ] || wd_groups || true
  WNH_NAMES=$(wd_heal_chain "$WNH_NAMES")
  WNH_SHOWN=$(printf '%s' "$WNH_NAMES" | tr '\t' ' ' | sed 's/ $//')
  wd_conn_busy "$WNH_NAMES"
  if [ "$WD_CLOSE_BUSY" -gt 0 ]; then
    wd_log "self-heal: probe through $WNH_SHOWN failed ($2) but $WD_CLOSE_BUSY connection(s) through it are receiving data; nothing closed"
    return 0
  fi
  WPH_N=0; WPH_PINNED=""
  if [ -s "$WD_TMP.groups" ]; then
    wd_budget_start
    while IFS= read -r WPH_L; do
      WPH_T=${WPH_L%%"$WD_TAB"*}; WPH_R=${WPH_L#*"$WD_TAB"}; WPH_G=${WPH_R%%"$WD_TAB"*}
      WPH_F=${WPH_R#*"$WD_TAB"}; case "$WPH_F" in *"$WD_TAB"*) WPH_F=${WPH_F#*"$WD_TAB"};; *) WPH_F="";; esac
      case "$WPH_T" in URLTest|Fallback|LoadBalance) ;; *) continue;; esac
      case "$WD_TAB$WNH_NAMES" in *"$WD_TAB$WPH_G$WD_TAB"*) ;; *) continue;; esac
      if [ -n "$WPH_F" ]; then WPH_PINNED="${WPH_PINNED:+$WPH_PINNED, }$WPH_G→$WPH_F"; continue; fi
      wd_budget_left || { wd_log "self-heal: group re-test stopped (${WD_BUDGET_WHY})"; break; }
      WD_HTTP_TIMEOUT=8; wd_http GET "/group/$(wd_urlenc "$WPH_G")/delay?timeout=5000&url=$(wd_urlenc "$WD_PROXY_URL")" "$WD_TMP.act" hold </dev/null
      rm -f "$WD_TMP.act"; WPH_N=$((WPH_N+1))
    done < "$WD_TMP.groups"
  fi
  if wd_close_through "$WNH_NAMES"; then WNH_CLOSED="closed $WD_CLOSED of $WD_CLOSE_SEEN core connection(s) through $WNH_SHOWN"
  else WNH_CLOSED="closed no connection (connection list unreadable)"; fi
  wd_log "self-heal: proxy node not responding ($2); re-tested $WPH_N auto-select group(s)${WPH_PINNED:+, kept the pinned $WPH_PINNED}, $WNH_CLOSED"
  if [ "$WD_NODE_NOTIFIED" = 0 ]; then
    WD_NODE_NOTIFIED=1
    wd_notify "proxy-down ${2:-unknown}" "代理节点无响应${2:+：$2}。已让自动选择的节点组重新测速切换，并只断开了经过该节点的连接，直连与其他节点的连接不受影响；手动选择的节点请在河图中更换。"
  fi
}
# Dead node seen by the data-plane probe.
wd_proxy_heal(){ wd_node_heal "$WD_DP_BAD_NAMES" "${WD_DP_BAD:-$WD_DP_DETAIL}"; }
# Broken data plane (core alive, its outbounds fine, captured requests go nowhere): re-check the capture
# rules first, then the existing restart path with its budget.
wd_dataplane_heal(){
  WD_DP_LEVEL=$((WD_DP_LEVEL+1))
  if [ "$WD_DP_LEVEL" = 1 ]; then
    # No node is at fault here; working connections are left alone while the rules are re-checked.
    wd_repair_rules
    wd_log "self-heal: data plane down ($WD_DP_DETAIL); re-checked the capture rules"
    return 0
  fi
  if wd_restart_allowed; then
    wd_log "self-heal: data plane still down; restarting the core"
    if wd_restart_core; then rm -f "$RUN/watchdog.ready.$$" "$WD_TMP".*; exit 0; fi
  else
    wd_log "self-heal: data plane still down; restart limit reached ($WD_RESTART_MAX per ${WD_RESTART_WINDOW}s), waiting"
    # 6.0.0: re-checked rules and restarted cores did not help; say so once, with what else takes the traffic.
    if [ "$WD_DP_NOTIFIED" = 0 ]; then
      WD_DP_NOTIFIED=1
      WDH_OTHER=$(foreign_tun_links 2>/dev/null | tr '\n' ' '); WDH_OTHER=${WDH_OTHER% }
      wd_notify "dataplane-down restart-limit" "外网请求没能经过河图核心：已重新检查透明代理规则并重启核心 $WD_RESTART_MAX 次，仍未恢复。${WDH_OTHER:+检测到其他 VPN/TUN 接口在线（$WDH_OTHER），它可能抢先接管了流量，请先关闭该应用。}请在河图中停止后重新启动代理；如果还不行，请重启手机。"
    fi
  fi
}
# 6.0.0 (field report: only foreign sites fail, node delays are fine, stop/start and restarts do not help).
# The core lost its fake-IP records: a new address for the same name on every query, or the log says its
# cache.db could not be opened. Every fake-IP connection fails until a new core runs; the start that restarts
# it first ends Hetu's own processes holding the file and moves a file that stays locked aside
# (core_cache_guard). No second probe and no backoff: the verdict is unambiguous.
wd_fakeip_heal(){
  if wd_restart_allowed; then
    wd_log "self-heal: the core lost its fake-IP records ($WD_DP_DETAIL); restarting the core"
    if wd_restart_core; then
      wd_notify "fakeip-lost restarted" "河图核心的假 IP 记录失效（核心缓存文件被占用），走代理的外网连接全部失败，节点测速却正常。已自动重启核心并换用可用的缓存文件。"
      rm -f "$RUN/watchdog.ready.$$" "$WD_TMP".*; exit 0
    fi
  else
    wd_log "self-heal: the core lost its fake-IP records; restart limit reached ($WD_RESTART_MAX per ${WD_RESTART_WINDOW}s), waiting"
  fi
  if [ "$WD_FAKEIP_NOTIFIED" = 0 ]; then
    WD_FAKEIP_NOTIFIED=1
    wd_notify "fakeip-lost restart-failed" "河图核心的假 IP 记录失效，走代理的外网连接全部失败，节点测速却正常；自动重启核心没有成功。请在河图中停止后重新启动代理；如果还不行，请重启手机。"
  fi
}
# 6.0.0: the probe site fails through the core's own listener too while every scanned group answers its delay
# test, so the rules send it somewhere the scan did not cover. Where the core log names a proxy group or node it
# is healed like a dead node (its auto-select groups re-test, only its connections close). DIRECT, a reject or
# an empty group (COMPATIBLE) is the configuration's choice: it is reported once per outage with the rule, and
# nothing is changed. Without a log line only the finding is reported.
wd_route_heal(){
  WRH_END="$WD_ROUTE_NODE"
  if [ -z "$WRH_END" ] && [ -n "$WD_ROUTE_VIA" ]; then
    # Follow the groups' current choice from the rule's target to the outbound actually used.
    [ -s "$WD_TMP.groups" ] || wd_groups || true
    WRH_END="$WD_ROUTE_VIA"; WRH_I=0
    while [ "$WRH_I" -lt 6 ]; do
      WRH_NEXT=$(awk -F '\t' -v g="$WRH_END" '$2 == g {print $3; exit}' "$WD_TMP.groups" 2>/dev/null)
      [ -n "$WRH_NEXT" ] && [ "$WRH_NEXT" != "$WRH_END" ] || break
      WRH_END="$WRH_NEXT"; WRH_I=$((WRH_I+1))
    done
  fi
  case "$WRH_END" in
    DIRECT|direct) WRH_WHAT="直连（DIRECT）";;
    REJECT|REJECT-DROP|block|reject) WRH_WHAT="拒绝（REJECT）";;
    COMPATIBLE) WRH_WHAT="空策略组（组里没有可用节点，等同直连）";;
    '') WRH_WHAT="";;
    *) wd_node_heal "$WD_ROUTE_VIA$WD_TAB${WD_ROUTE_NODE:+$WD_ROUTE_NODE$WD_TAB}" "$WD_DP_DETAIL"; return 0;;
  esac
  if [ -n "$WRH_WHAT" ]; then
    wd_log "self-heal: $WD_DP_HOST is routed to $WRH_END (${WD_ROUTE_RULE:+rule $WD_ROUTE_RULE, }target $WD_ROUTE_VIA); configuration choice, nothing changed"
    if [ "$WRH_END" = "$WD_ROUTE_VIA" ]; then
      WRH_MSG="外网不通：配置里的规则${WD_ROUTE_RULE:+「$WD_ROUTE_RULE」}把外网请求直接设为$WRH_WHAT，所以外网打不开，节点测速却正常。请检查当前配置的规则，或换用其他配置。"
    else
      WRH_MSG="外网不通：配置的规则${WD_ROUTE_RULE:+「$WD_ROUTE_RULE」}把外网请求分到了「$WD_ROUTE_VIA」，它现在实际走的是$WRH_WHAT，所以外网打不开，节点测速却正常。请在河图「面板 › 策略组」里把「$WD_ROUTE_VIA」改选为代理节点，或检查订阅是否为空。"
    fi
  else
    wd_log "self-heal: $WD_DP_HOST fails through the core's own listener too while every scanned group answers ($WD_DP_DETAIL); nothing changed"
    WRH_MSG="外网不通：直接通过河图核心访问 $WD_DP_HOST 也失败，但各策略组测速都正常。可能是规则把外网分到了直连、拒绝，或分到了未参与测速的策略组。请在河图「面板 › 策略组」里检查外网用的策略组当前选择。"
  fi
  if [ "$WD_ROUTE_NOTIFIED" = 0 ]; then WD_ROUTE_NOTIFIED=1; wd_notify "route-down ${WRH_END:-unknown}" "$WRH_MSG"; fi
}
wd_dataplane_tick(){
  WD_DP_AT=$((WD_NOW+WD_DP_EVERY))
  wd_dp_probe; WDT_C="$WD_DP_CLASS"
  case "$WDT_C" in
    unavailable)
      WD_DP_ON=0; WD_EG_AT="$WD_NOW"
      wd_log "data-plane probe unavailable ($WD_DP_WHY); using the proxy-group delay check"; return 0;;
    # No verdict: logged once while it lasts; it also ends a run of failures (two in a row are needed).
    skip) if [ -n "$WD_DP_DETAIL" ] && [ "$WD_DP_LAST" != skip ]; then wd_log "data-plane probe: no verdict ($WD_DP_DETAIL)"; fi
      [ -z "$WD_DP_DETAIL" ] || { WD_DP_FAIL=0; WD_DP_LAST=skip; }; return 0;;
    ok)
      if [ -n "$WD_DP_LAST" ] || [ "$WD_DP_LEVEL" != 0 ]; then wd_log "data-plane probe: requests through the core work again"; fi
      WD_DP_FAIL=0; WD_DP_LAST=""; WD_DP_LEVEL=0; WD_DP_NOTIFIED=0; WD_NODE_NOTIFIED=0; WD_ROUTE_NOTIFIED=0; WD_FAKEIP_NOTIFIED=0; WD_DP_BACKOFF="$WD_EGRESS_BACKOFF"; WD_DP_NEXT=0
      wd_egress_state ok; return 0;;
    offline)
      [ "$WD_DP_LAST" = offline ] || wd_log "data-plane probe: no network ($WD_DP_DETAIL)"
      WD_DP_FAIL=0; WD_DP_LAST=offline; wd_egress_state offline
      # 3.3.3: whole-phone outage (DIRECT fails too while core and rules answer): Wi-Fi check / reconnect.
      WD_DP_AT=$((WD_NOW+WD_DP_RETRY)); wd_offline_seen "$WD_DP_DETAIL"; return 0;;
  esac
  WD_OFF_N=0
  # 6.0.0: lost fake-IP records need a new core at once (wd_fakeip_heal: restart budget, no backoff).
  if [ "$WDT_C" = fakeip-lost ]; then
    WD_DP_FAIL=0; WD_DP_LAST="$WDT_C"; wd_egress_state "$WDT_C"; WD_DP_AT=$((WD_NOW+WD_DP_RETRY))
    wd_log "data-plane probe: $WDT_C ($WD_DP_DETAIL)"; wd_fakeip_heal; return 0
  fi
  if [ "$WDT_C" = "$WD_DP_LAST" ]; then WD_DP_FAIL=$((WD_DP_FAIL+1)); else WD_DP_FAIL=1; WD_DP_LAST="$WDT_C"; fi
  wd_egress_state "$WDT_C"
  wd_log "data-plane probe: $WDT_C ($WD_DP_DETAIL) $WD_DP_FAIL/$WD_DP_LIMIT"
  WD_DP_AT=$((WD_NOW+WD_DP_RETRY))
  [ "$WD_DP_FAIL" -ge "$WD_DP_LIMIT" ] || return 0
  WD_DP_FAIL=0; WD_DP_AT=$((WD_NOW+WD_DP_EVERY))
  if [ "$WD_NOW" -lt "$WD_DP_NEXT" ]; then wd_log "data-plane probe: $WDT_C persists; next self-heal action in $((WD_DP_NEXT-WD_NOW))s (backoff)"; return 0; fi
  WD_DP_NEXT=$((WD_NOW+WD_DP_BACKOFF)); WD_DP_BACKOFF=$((WD_DP_BACKOFF*2)); [ "$WD_DP_BACKOFF" -le "$WD_EGRESS_BACKOFF_MAX" ] || WD_DP_BACKOFF="$WD_EGRESS_BACKOFF_MAX"
  case "$WDT_C" in proxy-down) wd_proxy_heal;; route-down) wd_route_heal;; *) wd_dataplane_heal;; esac
}
watchdog(){
  COREPID="$1"; KILL="$2"; S="$3"; UIDS="$4"; SHARE="$5"; CIDRS="$6"; IFACES="$7"; DUIDS="$8"; DGIDS="${9:-}"; MACS="${10:-}"; WD_GENERATION="${11:-}"
  [ -n "$WD_GENERATION" ] && [ "$WD_GENERATION" = "$(cat "$RUN/generation" 2>/dev/null)" ] || exit 0
  WD_N=0
  while [ "$(cat "$WATCHDOG_PID" 2>/dev/null)" != "$$" ]; do
    WD_N=$((WD_N+1)); [ "$WD_N" -lt 40 ] || exit 0; sleep 0.025
  done
  load_session_fake_ip_policy || exit 0
  cnip_load_session
  share_load_session
  printf '%s\n' "$WD_GENERATION" > "$RUN/watchdog.ready.$$" || exit 0
  # Out of the App's cgroup even when started by an older path; repair-network calls from here
  # are not network-change reports.
  cg_escape "$$"
  HETU_WATCHDOG_CALL=1; export HETU_WATCHDOG_CALL
  wd_selfheal_init
  MISS=0; H_TICK=0
  while [ "$MISS" -lt 3 ]; do
    [ "$WD_GENERATION" = "$(cat "$RUN/generation" 2>/dev/null)" ] || exit 0
    if core_maybe_alive "$COREPID" && kill -0 "$COREPID" >/dev/null 2>&1; then
      MISS=0; H_TICK=$((H_TICK+1))
      if [ "$H_TICK" -ge 6 ]; then H_TICK=0; core_log_rotate; "$0" repair-network "$COREPID" >/dev/null 2>&1 || true
        case "$CNIP_STATE" in ipset|iptables) "$0" cnip-reload auto >/dev/null 2>&1 || true;; esac; wd_net_watch; fi
      wd_selfheal_tick
      sleep 2
    else MISS=$((MISS+1)); sleep 0.20; fi
  done
  acquire_lock || exit 0
  [ "$WD_GENERATION" = "$(cat "$RUN/generation" 2>/dev/null)" ] || exit 0
  # Holding the lock: nothing may cut the capture removal off half-way.
  trap '' TERM INT HUP
  stopmonitor
  REC=$(cat "$PIDFILE" 2>/dev/null || true)
  if [ "$REC" = "$COREPID" ]; then
    RESULT="network-restore-failed"
    if [ "$KILL" = 1 ]; then
      # Install guards before removing capture. Failure retains the old rules.
      if atomic_kill_guard 4 "$S" "$UIDS" "$SHARE" "$CIDRS" "$IFACES" "$DUIDS" "$DGIDS" "$MACS" && atomic_kill_guard 6 "$S" "$UIDS" "$SHARE" "$CIDRS" "$IFACES" "$DUIDS" "$DGIDS" "$MACS"; then RESULT="killswitch-active"; else RESULT="killswitch-failed"; fi
    elif cleanup_confirmed && restorev6; then
      RESULT="network-restored"; rm -f "$MODEFILE" "$SESSION"
      net_sysctl_restore
    fi
    cgroup_release
    rm -f "$PIDFILE"
    date '+%Y-%m-%dT%H:%M:%S%z core exited; '"$RESULT" > "$CRASH_STATE" 2>/dev/null || true
    printf '%s core=%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$COREPID" "$RESULT" >> "$WATCHDOG_LOG" 2>/dev/null || true
    [ -z "$WD_HEAL_REASON" ] || wd_heal_notice "$RESULT"
  fi
  rm -f "${WD_TMP:-$RUN/.wd.$$}".* 2>/dev/null
  [ "$(cat "$WATCHDOG_PID" 2>/dev/null)" != "$$" ] || rm -f "$WATCHDOG_PID"
  rm -f "$RUN/watchdog.ready.$$"
}
# info-level ad-block logging writes one line per connection. The core appends (>>, O_APPEND),
# so copy the newest 1 MiB to core.log.1 and truncate in place once core.log passes 8 MiB; the
# App's offset reader already restarts at 1 when the size drops below its offset.
LOG_ROTATE_BYTES=8388608
core_log_rotate(){
  [ -f "$LOG" ] || return 0
  LR_SIZE=$(stat -c %s "$LOG" 2>/dev/null || wc -c < "$LOG" 2>/dev/null || echo 0)
  case "$LR_SIZE" in ''|*[!0-9]*) return 0;; esac
  [ "$LR_SIZE" -gt "$LOG_ROTATE_BYTES" ] || return 0
  tail -c 1048576 "$LOG" > "$LOG.1.$$" 2>/dev/null && mv -f "$LOG.1.$$" "$LOG.1" 2>/dev/null || rm -f "$LOG.1.$$"
  : > "$LOG" 2>/dev/null || true
  printf '%s core.log rotated at %s bytes\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$LR_SIZE" >> "$WATCHDOG_LOG" 2>/dev/null || true
}
start_watchdog(){
  stopwatchdog
  WD_GENERATION="$$-$(health_core_birth "$$")"
  printf '%s\n' "$WD_GENERATION" > "$RUN/generation" || return 1
  detach_probe
  $SETSID "$0" watchdog "$@" "$WD_GENERATION" </dev/null >/dev/null 2>&1 &
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
# A verified runner (BusyBox path + spec, both owner probes passed) is reused within one boot:
# boot_id, the core group, IPv6 availability and the BusyBox inode/mtime/size must all match.
# Any mismatch or read failure re-runs the full probe; only a positive result is cached, and
# core_identity_confirm still checks the real core's /proc status on every start.
identity_cache_key(){
  ICK_BOOT=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null) || return 1
  [ -n "$ICK_BOOT" ] || return 1
  ICK_V6=0; if v6supported && has ip6tables; then ICK_V6=1; fi
  printf 'v1|%s|%s|%s' "$ICK_BOOT" "$CORE_GROUP_ID" "$ICK_V6"
}
identity_file_sig(){ stat -c '%i:%Y:%s' "$1" 2>/dev/null; }
identity_cache_hit(){
  [ -n "$CI_KEY" ] && [ -r "$RUN/state/identity.cache" ] || return 1
  IFS='|' read -r ICH_V ICH_BOOT ICH_GID ICH_V6 ICH_BB ICH_SPEC ICH_SIG < "$RUN/state/identity.cache" || return 1
  [ "$ICH_V|$ICH_BOOT|$ICH_GID|$ICH_V6" = "$CI_KEY" ] || return 1
  case "$ICH_SPEC" in root:net_admin|"0:$CORE_GROUP_ID") ;; *) return 1;; esac
  case "$ICH_BB" in /data/adb/ksu/bin/busybox|/data/adb/ap/bin/busybox|/data/adb/magisk/busybox|*/.magisk/busybox) ;; *) return 1;; esac
  [ -x "$ICH_BB" ] && [ -n "$ICH_SIG" ] && [ "$(identity_file_sig "$ICH_BB")" = "$ICH_SIG" ] || return 1
  CORE_RUNNER="$ICH_BB"; CORE_SPEC="$ICH_SPEC"
}
identity_cache_store(){
  rm -f "$RUN/state/identity.cache" 2>/dev/null || true
  [ -n "$CI_KEY" ] && [ -n "$CORE_RUNNER" ] || return 0
  ICS_SIG=$(identity_file_sig "$CORE_RUNNER") && [ -n "$ICS_SIG" ] || return 0
  mkdir -p "$RUN/state" 2>/dev/null || return 0
  { printf '%s|%s|%s\n' "$CI_KEY" "$CORE_RUNNER" "$CORE_SPEC|$ICS_SIG" > "$RUN/state/identity.cache.$$" &&
    mv -f "$RUN/state/identity.cache.$$" "$RUN/state/identity.cache"; } 2>/dev/null || rm -f "$RUN/state/identity.cache.$$" 2>/dev/null || true
}
core_identity_prepare(){
  CORE_GID=""; CORE_RUNNER=""; CORE_SPEC=""; SYSTEM_DNS=exempt; IDENTITY_SOURCE=probe
  [ ! -f "$BASE/policy/system-dns-direct" ] || return 0
  CI_KEY=$(identity_cache_key 2>/dev/null || true)
  if identity_cache_hit; then IDENTITY_SOURCE=cache; return 0; fi
  CORE_RUNNER=""; CORE_SPEC=""
  core_identity_probe
  identity_cache_store
  return 0
}
core_identity_probe(){
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
core_launch_native(){
  case "$1" in
    sing-box) set -- run -c "$2" -D "$RUN" --disable-color;;
    xray|v2ray) set -- run -c "$2";;
    hysteria) set -- client -c "$2" --disable-update-check;;
    *) return 1;;
  esac
  core_env
  detach_probe
  if [ -n "$CORE_RUNNER" ]; then $SETSID "$CORE_RUNNER" setuidgid "$CORE_SPEC" "$START_BIN" "$@" >>"$LOG" 2>&1 &
  else $SETSID "$START_BIN" "$@" >>"$LOG" 2>&1 & fi
  START_PID=$!
}
core_launch(){
  [ -z "${START_MEM_BYTES:-}" ] || { GOMEMLIMIT="$START_MEM_BYTES"; export GOMEMLIMIT; }
  detach_probe
  if [ "${START_KIND:-mihomo}" != mihomo ]; then core_launch_native "$START_KIND" "${START_PAYLOAD:-$START_CFG}" || return 1
  elif [ -n "$CORE_RUNNER" ]; then $SETSID "$CORE_RUNNER" setuidgid "$CORE_SPEC" "$START_BIN" -d "$RUN" -f "$START_CFG" >>"$LOG" 2>&1 &
  else $SETSID "$START_BIN" -d "$RUN" -f "$START_CFG" >>"$LOG" 2>&1 & fi
  [ "${START_KIND:-mihomo}" != mihomo ] || START_PID=$!
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
  # A start from the App passes the GMS/Play/GSF app IDs it resolved itself (START_GMS_APPIDS),
  # so the start path forks no pm/cmd Java process: one UID per app ID and /data/user/<id>.
  # Only the start transaction uses them; the watchdog's periodic upkeep resolves again via pm.
  if [ -n "${START_GMS_APPIDS:-}" ]; then
    GF_USERS=$(ls /data/user 2>/dev/null | grep -E '^[0-9]{1,6}$')
    [ -n "$GF_USERS" ] || GF_USERS=0
    for GF_USER in $GF_USERS; do
      for GF_APP in $(printf '%s\n' "$START_GMS_APPIDS" | tr ',' ' '); do
        case "$GF_APP" in ''|*[!0-9]*) continue;; esac
        [ "$GF_APP" -ge 10000 ] && [ "$GF_APP" -le 19999 ] || continue
        printf '%s\n' $((GF_USER*100000+GF_APP))
      done
    done | sort -nu
    return 0
  fi
  # Resolve each installed user/profile. Android can reassign app UIDs after reset.
  GF_USERS=$(pm list users 2>/dev/null | sed -n 's/.*UserInfo{\([0-9][0-9]*\):.*/\1/p')
  [ -n "$GF_USERS" ] || GF_USERS=0
  for GF_USER in $GF_USERS; do
    (cmd package list packages -U --user "$GF_USER" 2>/dev/null ||
      pm list packages -U --user "$GF_USER" 2>/dev/null) |
      awk '$1=="package:com.google.android.gms" || $1=="package:com.android.vending" || $1=="package:com.google.android.gsf" || $1=="package:com.google.android.configupdater" {
        for(i=2;i<=NF;i++) if($i ~ /^uid:[0-9]+$/) {sub(/^uid:/,"",$i); if($i>=10000) print $i}
      }'
  done | sort -nu
}
# ColorOS / Oplus netd lists the Google apps in its cgroup eBPF per-UID block maps (one per
# network type) while a transparent proxy runs, out of sight of iptables, and lists them again at
# boot and within seconds of an interface change. hetu-bpf deletes exactly the given UIDs there;
# every other entry is the user's own per-app "no WLAN / no mobile data" switch and stays.
google_firewall_vendor_maps(){
  [ -x "$EBPF_TOOL" ] || return 1
  ls "$GOOGLE_VENDOR_BPF" 2>/dev/null | grep -q '^map_oplus-netd_app_.*_socket_uid_limit_map$'
}
# google_firewall_ebpf check|unblock <uids, one per line>: run-file google-firewall-ebpf keeps the
# last pass (listed = Google entries found before deleting) and the session's removals so far.
# It is rewritten only when that changes, not on every quiet watchdog pass.
google_firewall_ebpf(){
  GE_MODE="$1"
  google_firewall_vendor_maps || return 0
  GE_LIST=$(printf '%s\n' "$2" | grep -E '^[0-9]+$' | tr '\n' ','); GE_LIST="${GE_LIST%,}"
  [ -n "$GE_LIST" ] || return 0
  GE_OUT=$(timeout -s TERM -k 1 5 "$EBPF_TOOL" vendor-uid "$GE_MODE" "$GE_LIST" 2>/dev/null | head -n 1 | tr -cd 'a-z0-9=, ')
  GE_MAPS=0; GE_PRESENT=0; GE_REMOVED=0; GE_FAILED=1; GE_NAMES=-
  for GE_F in $GE_OUT; do
    case "$GE_F" in
      maps=[0-9]*) GE_MAPS="${GE_F#maps=}";; present=[0-9]*) GE_PRESENT="${GE_F#present=}";;
      removed=[0-9]*) GE_REMOVED="${GE_F#removed=}";; failed=[0-9]*) GE_FAILED="${GE_F#failed=}";;
      names=?*) GE_NAMES="${GE_F#names=}";;
    esac
  done
  GE_OLD=$(head -n 1 "$RUN/google-firewall-ebpf" 2>/dev/null)
  GE_TOTAL=$(printf '%s\n' "$GE_OLD" | sed -n 's/.*bpfremoved=\([0-9][0-9]*\).*/\1/p')
  GE_TOTAL=$((${GE_TOTAL:-0}+GE_REMOVED))
  GE_LINE="bpfmode=$GE_MODE bpfmaps=$GE_MAPS bpfnames=$GE_NAMES bpflisted=$GE_PRESENT bpfremoved=$GE_TOTAL bpffailed=$GE_FAILED"
  [ "${GE_OLD% bpfat=*}" != "$GE_LINE" ] || return 0
  GE_AT=$(date '+%Y-%m-%dT%H:%M:%S%z')
  printf '%s bpfat=%s\n' "$GE_LINE" "$GE_AT" > "$RUN/google-firewall-ebpf" 2>/dev/null || true
  [ "$GE_REMOVED" = 0 ] || printf '%s ebpf %s removed=%s\n' "$GE_AT" "$GE_NAMES" "$GE_REMOVED" >> "$RUN/google-firewall.log"
}
google_firewall_cleanup(){
  # Caller owns the existing Root transaction lock. Never flush a system chain.
  GF_UIDS=$(google_firewall_uids)
  GF_PRESENT=""
  if [ -z "$GF_UIDS" ]; then
    rm -f "$RUN/google-firewall-uids"
    printf 'uids=0 chains= at=%s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" > "$RUN/google-firewall-detail" 2>/dev/null || true
    return 0
  fi
  # The watchdog's quick eBPF pass between two full passes reuses these instead of asking pm.
  [ "$(cat "$RUN/google-firewall-uids" 2>/dev/null)" = "$GF_UIDS" ] || printf '%s\n' "$GF_UIDS" > "$RUN/google-firewall-uids" 2>/dev/null || true
  google_firewall_ebpf unblock "$GF_UIDS"
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
# Switch on: a full pass (pm UIDs, vendor iptables chains, eBPF maps) once a minute and the eBPF
# maps alone with the cached UIDs on every other watchdog pass (~12 s), since ColorOS lists the
# Google apps again within seconds. Switch off: only a read-only eBPF check once a minute, with the
# UIDs the start resolved, so the settings page can say the restriction is there.
google_firewall_maintain(){ (
  trap 'release_lock' EXIT
  GF_PID="${1:-}"
  GF_CLEAN=$(sed -n 's/^GOOGLE_FIREWALL_CLEAN=//p' "$SESSION" 2>/dev/null)
  case "$GF_CLEAN" in 1) ;; 0) [ -s "$RUN/google-firewall-uids" ] || exit 0;; *) exit 0;; esac
  [ "$GF_PID" = "$(cat "$PIDFILE" 2>/dev/null)" ] && pidcore "$GF_PID" && kill -0 "$GF_PID" 2>/dev/null || exit 0
  monotonic_seconds || exit 0
  GF_NOW="$MONO_SECONDS"; GF_LAST=$(cat "$RUN/google-firewall-at" 2>/dev/null || true)
  GF_DUE=1
  case "$GF_LAST" in ''|*[!0-9]*) ;; *)
    if [ "$GF_NOW" -ge "$GF_LAST" ] && [ $((GF_NOW-GF_LAST)) -lt 60 ]; then GF_DUE=0; fi;;
  esac
  if [ "$GF_DUE" = 0 ]; then
    # Only Google's own entries leave a system map: no Hetu state changes, so no transaction lock.
    [ "$GF_CLEAN" = 1 ] && [ -s "$RUN/google-firewall-uids" ] || exit 0
    google_firewall_ebpf unblock "$(cat "$RUN/google-firewall-uids" 2>/dev/null)"
    exit 0
  fi
  acquire_lock || exit 0
  [ "$GF_PID" = "$(cat "$PIDFILE" 2>/dev/null)" ] && pidcore "$GF_PID" && kill -0 "$GF_PID" 2>/dev/null || exit 0
  [ "$(sed -n 's/^GOOGLE_FIREWALL_CLEAN=//p' "$SESSION" 2>/dev/null)" = "$GF_CLEAN" ] || exit 0
  printf '%s\n' "$GF_NOW" > "$RUN/google-firewall-at"
  if [ "$GF_CLEAN" = 1 ]; then google_firewall_cleanup
  else google_firewall_ebpf check "$(cat "$RUN/google-firewall-uids" 2>/dev/null)"; fi
); }
# Switch off: one read-only look at the eBPF maps at start, with the App's app IDs (no pm call).
google_firewall_detect(){
  [ -n "${START_GMS_APPIDS:-}" ] && google_firewall_vendor_maps || return 0
  GD_UIDS=$(google_firewall_uids)
  [ -n "$GD_UIDS" ] || return 0
  printf '%s\n' "$GD_UIDS" > "$RUN/google-firewall-uids" 2>/dev/null || true
  google_firewall_ebpf check "$GD_UIDS"
}

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
  SWEEP_ONLY="$MOUT $MPRE $NOUT $NPRE $DNSOUT $DNSPRE $WROUT $WRFWD $QUICOUT $QUICFWD ${MOUT}_T ${NOUT}_T ${WROUT}_T ${QUICOUT}_T ${MPRE}_S ${NPRE}_S ${DNSPRE}_S ${WRFWD}_S ${QUICFWD}_S"
  [ "$START_V6" != strict ] || SWEEP_ONLY="$SWEEP_ONLY $V6OUT $V6FWD ${V6OUT}_T ${V6FWD}_S"
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

# What a watchdog restart replays, the self-heal budget (reset by every start that is not itself a
# self-heal restart) and where this session's processes ended up in the cgroup tree.
start_selfheal_record(){ { start_selfheal_body "$@"; } 2>/dev/null; return 0; }
start_selfheal_body(){
  mkdir -p "$RUN/state" 2>/dev/null || true
  { for SSR_A in "$@"; do printf '%s\n' "$SSR_A"; done; } > "$RUN/state/start.args.new.$$" 2>/dev/null && mv -f "$RUN/state/start.args.new.$$" "$RUN/state/start.args" 2>/dev/null || rm -f "$RUN/state/start.args.new.$$"
  [ -n "${HETU_SELFHEAL_RESTART:-}" ] || rm -f "$RUN/state/selfheal-budget" "$RUN/selfheal-state" 2>/dev/null
  SSR_CG=$(tr '\n' ' ' < "/proc/$START_PID/cgroup" 2>/dev/null | cut -c1-400)
  printf 'shell=%s setsid=%s core=%s\n' "${START_CG_ESCAPED:-none}" "${SETSID:-no}" "$SSR_CG" > "$RUN/cgroup-escape" 2>/dev/null || true
  wd_log "session start core=$START_PID cgroup-escape=${START_CG_ESCAPED:-none} setsid=${SETSID:-no}${HETU_SELFHEAL_RESTART:+ (self-heal restart)}"
  return 0
}
start(){
  START_BIN="$1"; START_CFG="$2"; START_MODE="$3"; START_TP="$4"; START_RP="$5"; START_V6="$6"; START_TCP="$7"; START_UDP="$8"; START_DNS="$9"; START_QUIC="${10}"; START_DP="${11}"; START_CP="${12}"; START_SCOPE="${13}"; START_UIDS="${14}"; START_SHARE="${15}"; START_KILL="${16}"; START_CIDRS="${17}"; START_IFACES="${18}"; START_DIRECT_UIDS="${19}"; START_PREVALIDATED="${20:-0}"; START_FAST_CAPS="${21:-0}"; START_DIRECT_GIDS="${22:-}"; START_SHARED_MACS="${23:-}"
  START_VENDOR_CLEAN="${30:-0}"
  # Values the App resolved in-process (no Java child processes on the start path). Shell-local:
  # the watchdog and everything spawned later fall back to settings/pm as before.
  START_GMS_APPIDS=""; case "${HETU_GMS_APPIDS:-}" in *[!0-9,]*) ;; *) START_GMS_APPIDS="${HETU_GMS_APPIDS:-}";; esac
  START_PRIVATE_DNS="${HETU_PRIVATE_DNS_MODE:-}"
  unset HETU_GMS_APPIDS HETU_PRIVATE_DNS_MODE
  START_DNS_TCP="${24:-1}"; START_DNS_UDP="${25:-1}"; START_PERF="${26:-0}"; START_CPU="${27:-}"; START_MEM="${28:-}"; START_IO="${29:-}"
  bool "$START_DNS_TCP" && bool "$START_DNS_UDP" && bool "$START_PERF" || fail "扩展开关必须是 0 或 1"
  tuning_cgroup_flags
  tuning_valid || fail "$TUNE_ERROR"
  DNS_PROTOS=""; [ "$START_DNS_TCP" = 0 ] || DNS_PROTOS=tcp; [ "$START_DNS_UDP" = 0 ] || DNS_PROTOS="${DNS_PROTOS:+$DNS_PROTOS }udp"
  # Neither protocol captured is the same as DNS takeover switched off.
  [ -n "$DNS_PROTOS" ] || START_DNS=off
  mkdir -p "$RUN" || fail "无法创建运行目录"
  # Before anything is spawned: the core, watchdog and monitor inherit the root cgroup, so freezing
  # or killing the App (its uid_/pid_ cgroup) never freezes or kills them.
  cg_escape "$$"; START_CG_ESCAPED="$CG_ESCAPED"
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
  [ -x "$START_BIN" ] || fail "核心文件不存在或不可执行"; [ -r "$START_CFG" ] || fail "启动配置不存在"; mkdir -p "$RUN" || fail "无法创建运行目录"; START_KIND=$(core_kind); START_LABEL=$(kind_label "$START_KIND"); START_API=1; kind_has_api "$START_KIND" || START_API=0
  if [ "$START_PREVALIDATED" != 1 ]; then validatecfg "$START_BIN" "$START_CFG" || fail "$START_LABEL 配置校验失败，当前网络未被接管"; fi
  load_start_fake_ip_policy "$START_CFG" || fail "启动配置的 fake-IP 路由投影无效，当前网络未被接管"
  START_PAYLOAD="$START_CFG"
  if [ "$START_KIND" != mihomo ]; then START_PAYLOAD=$(core_payload "$START_CFG" "$START_KIND" "$RUN") || fail "无法准备 $START_LABEL 运行配置，当前网络未被接管"; fi
  # A core without a controller publishes no controller port: health and the App skip it.
  START_SESSION_CP="$START_CP"; [ "$START_API" = 1 ] || START_SESSION_CP=0
  acquire_lock || fail "另一个代理网络事务正在执行，请稍后重试"
  start_stage "core-identity"
  core_identity_prepare
  # Before the Kill Switch bootstrap guard: guards and capture rules reference the CN sets.
  start_stage "cnip"
  cnip_prepare
  start_stage "share-scope"
  share_prepare
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
  net_sysctl_restore
  start_stage "stop-old-core"
  stopcore || fail "旧核心未确认退出"; rm -f "$CRASH_STATE" "$SESSION"
  cgroup_release
  start_stage "foreign-rules"
  foreign_capture_check
  start_stage "check-ports"
  check_start_ports "$START_MODE" "$START_TP" "$START_RP" "$START_TCP" "$START_UDP" "$START_DNS" "$START_DP" "$START_CP"
  markused "$BYPASS_MARK" && fail "安全出站 mark 已被其他网络规则占用，未接管网络"
  NEED_TP=0; case "$START_MODE" in tproxy) if [ "$START_TCP" = 1 ] || [ "$START_UDP" = 1 ]; then NEED_TP=1; fi;; enhance) [ "$START_UDP" = 1 ] && NEED_TP=1;; esac; if [ "$START_DNS" = tproxy ] && [ "$START_MODE" != tun ] && [ "$START_MODE" != ebpf ]; then NEED_TP=1; fi
  if [ "$NEED_TP" = 1 ]; then allocnet || { fail "找不到安全的 fwmark/路由表/规则优先级，已保持直连"; }; fi
  if [ "$START_V6" = disable ] && v6supported; then
    install_v6_disable "$START_SHARE" || { fail "IPv6 禁用保护安装失败，未启动代理"; }
    # No all/default/rmnet sysctl writes: netd owns the physical network.
  fi
  start_stage "rp-filter"
  net_sysctl_apply

  mkdir -p "$RUN/rules" "$RUN/proxy_provider" "$RUN/ruleset" "$RUN/ui" || { fail "无法创建 Mihomo 运行缓存目录"; }
  start_stage "launch-core"
  core_cache_guard
  : > "$LOG" || fail "无法准备核心日志"; start_log_notices "$START_CFG"; core_launch || fail "核心启动失败"; printf '%s\n' "$START_PID" > "$PIDFILE" || fail "无法记录核心PID"; printf '%s\n' "$START_MODE" > "$MODEFILE" || fail "无法记录运行模式"; write_session "$START_MODE" "$START_V6" "$START_DNS" "$START_DP" "$START_SCOPE" "$START_SHARE" "$START_KILL" "$START_SESSION_CP" "$START_DIRECT_UIDS" "$START_DIRECT_GIDS" "$START_SHARED_MACS" || fail "无法记录运行会话"
  core_identity_confirm "$START_PID" || { rm -f "$RUN/state/identity.cache" 2>/dev/null; fail "核心隔离身份验证失败"; }
  start_stage "wait-listeners"
  wait_ready "$START_PID" "$START_MODE" "$START_TP" "$START_RP" "$START_TCP" "$START_UDP" "$START_DNS" "$START_DP" "$START_CP"; READY_RC=$?
  if [ "$READY_RC" -ne 0 ]; then
    if [ "$READY_RC" -eq 2 ]; then READY_MSG="$START_LABEL 启动后提前退出，请查看核心日志"; else READY_MSG="$START_LABEL 初始化超过 90 秒，代理入站/DNS/API 监听仍未就绪；首次加载大量远程订阅或规则时请检查网络与核心日志"; fi
    fail "$READY_MSG"
  fi

  if [ "$START_MODE" = ebpf ]; then
    sleep 0.25
    if grep -Ei '(^|[^a-z])(e?bpf|bpf)([^a-z]|$)' "$LOG" 2>/dev/null | tail -n 20 | grep -Eqi 'error|failed|failure|not supported|operation not permitted|permission denied|attach.*fail'; then
      fail "eBPF attach 失败；当前内核/接口不兼容，请改用 TUN 或 TPROXY"
    fi
  fi

  core_tune "$START_PID"
  core_cgroup "$START_PID"
  # Capture rules are recorded and committed in one iptables-restore per family/table. If a
  # commit is refused (old iptables-restore, missing match module, ...) the partial state is
  # swept and the same rules are installed one by one, exactly as before.
  # Opt-in eBPF matcher: loaded after the start's cleanup (which drops stale pins) and before
  # any capture rule references it. Every failure falls back to the owner/CIDR rules.
  start_stage "ebpf-matcher"
  ebpf_prepare
  # CNIP without ipset: the iptables tree goes in before any capture rule tests its verdict bit.
  start_stage "cnip-chain"
  cnip_xt_install
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
    # The App passes Settings.Global private_dns_mode; only starts without it (boot) ask settings.
    PRIVATE_DNS=${START_PRIVATE_DNS:-}
    [ -n "$PRIVATE_DNS" ] || PRIVATE_DNS=$(settings get global private_dns_mode 2>/dev/null | head -n 1)
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
  printf 'CNIP=%s\nCNIP_V4=%s\nCNIP_V6=%s\nCNIP_FORCE=%s\nCNIP_REASON=%s\nCNIP_TCPONLY=%s\n' "$CNIP_STATE" "$CN4_ON" "$CN6_ON" "$CN_FORCE" "$CNIP_REASON" "$({ [ "$CNIP_STATE" = ipset ] || [ "$CNIP_STATE" = iptables ]; } && echo "$CN_TCPONLY" || echo 0)" >> "$SESSION" || { fail "无法记录 CNIP 状态，已停止本次启动"; }
  printf 'EBPF_MATCH=%s\nEBPF_REASON=%s\n' "$EBPF_STATE" "$EBPF_REASON" >> "$SESSION" || { fail "无法记录 eBPF 匹配状态，已停止本次启动"; }
  printf 'SHARE_SCOPED=%s\nSHARE_IFACES=%s\nSHARE_MAC_MODE=%s\nSHARE_ONLY_MACS=%s\nSHARE_DROPPED=%s\n' "$SHR_SCOPED" "$SHR_IFACES" "$SHR_MODE" "$SHR_ONLY" "$SHR_DROPPED" >> "$SESSION" || { fail "无法记录共享网络范围，已停止本次启动"; }
  printf 'GOOGLE_FIREWALL_CLEAN=%s\n' "$START_VENDOR_CLEAN" >> "$SESSION" || { fail "无法记录 Google 防火墙设置，已停止本次启动"; }
  transaction_current || fail "启动事务已撤销"
  if [ "$START_KILL" = 1 ]; then
    revoke_bootstrap || fail "无法撤销临时核心启动例外"
    unhook xt4 filter OUTPUT "$KOUT"; unhook xt4 filter FORWARD "$KFWD"; cn_drop_tail xt4 filter "${KOUT}_T"; cn_drop_tail xt4 filter "${KFWD}_S"
    if has ip6tables; then unhook xt6 filter OUTPUT "$KOUT"; unhook xt6 filter FORWARD "$KFWD"; cn_drop_tail xt6 filter "${KOUT}_T"; cn_drop_tail xt6 filter "${KFWD}_S"; fi
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
  start_selfheal_record "$@"
  rm -f "$RUN/google-firewall-at" "$RUN/google-firewall-ebpf" "$RUN/google-firewall-uids"
  if [ "$START_VENDOR_CLEAN" = 1 ]; then google_firewall_cleanup; fi
  [ "$START_VENDOR_CLEAN" = 1 ] || google_firewall_detect
  start_watchdog "$START_PID" "$START_KILL" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_CIDRS" "$START_IFACES" "$START_DIRECT_UIDS" "$START_DIRECT_GIDS" "$START_SHARED_MACS" || { fail "运行守护未确认就绪，已中止启动"; }
  transaction_current || fail "启动事务已撤销"
  # Optional session daemon (network match / traffic); its failure never fails the start.
  start_monitor >/dev/null 2>&1 || printf '%s monitor not started\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" >> "$WATCHDOG_LOG" 2>/dev/null || true
  rm -f "$START_ERROR"; start_stage "running"
  START_ACTIVE=0
  net_recover_async start
  DESC="systemDns=$SYSTEM_DNS,tcp=$START_TCP,udp=$START_UDP,dns=$START_DNS,ipv6=$START_V6,scope=$START_SCOPE,share=$START_SHARE,kill=$START_KILL,quicBlock=$START_QUIC,directUids=$START_DIRECT_UIDS,directGids=$START_DIRECT_GIDS,sharedMacs=$START_SHARED_MACS,sharedIfaces=$SHR_IFACES,sharedMacMode=$SHR_MODE,ebpf=$EBPF_STATE${EBPF_REASON:+,ebpfReason=$EBPF_REASON}"
  [ -z "$FOREIGN_WARN" ] || DESC="$DESC；注意：$FOREIGN_WARN"
  [ -z "${CACHE_GUARD_NOTE:-}" ] || DESC="$DESC；注意：$CACHE_GUARD_NOTE"
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
  net_sysctl_restore; cgroup_release
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

  # A 3.3.3 post-stop fake-IP reject whose timer was killed (marker left): remove it as soon as the App looks.
  if [ "$STATUS_RUNNING" = false ] && [ -f "$RUN/fake-reject" ] && [ -d "$CG_ESCAPE_GATE" ] && try_lock_once; then fake_reject_remove || true; release_lock; fi
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
  GFEV=$(head -n 1 "$RUN/google-firewall-ebpf" 2>/dev/null | tr -cd 'A-Za-z0-9=_,:+ -'); [ -z "$GFEV" ] || GFDV="${GFDV:+$GFDV }$GFEV"
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
  share_load_session; split_safe_ifaces "$SHR_DROPPED" || SHR_DROPPED=""
  cnip_load_session; CNIPR=$(sed -n 's/^CNIP_REASON=//p' "$SESSION" 2>/dev/null | head -n 1 | tr -cd 'A-Za-z0-9,._-')
  CNIP4N=0; CNIP6N=0
  if [ "$CNIP_STATE" = ipset ] && has ipset; then
    [ "$CN4_ON" != 1 ] || CNIP4N=$(cnip_set_size "$CN_SET4"); [ "$CN6_ON" != 1 ] || CNIP6N=$(cnip_set_size "$CN_SET6")
    case "$CNIP4N$CNIP6N" in *[!0-9]*) CNIP4N=0; CNIP6N=0;; esac
    if { [ "$CN4_ON" = 1 ] && ! cnip_set_exists "$CN_SET4"; } || { [ "$CN6_ON" = 1 ] && ! cnip_set_exists "$CN_SET6"; }; then CNIP_STATE=degraded; CNIPR="${CNIPR:+$CNIPR,}set-missing"; fi
  elif [ "$CNIP_STATE" = iptables ]; then
    # The tree is not in the integrity manifest (too large to diff each poll): check its hooks here.
    if [ "$CN4_ON" = 1 ]; then CNIP4N=$(cat "$RUN/state/cnip-xt4.count" 2>/dev/null); xt4q -t mangle -C OUTPUT -j HETU_CNC >/dev/null 2>&1 || CNIPR="${CNIPR:+$CNIPR,}v4-chain-missing"; fi
    if [ "$CN6_ON" = 1 ]; then CNIP6N=$(cat "$RUN/state/cnip-xt6.count" 2>/dev/null); xt6q -t mangle -C OUTPUT -j HETU_CNC >/dev/null 2>&1 || CNIPR="${CNIPR:+$CNIPR,}v6-chain-missing"; fi
    case "$CNIP4N" in ''|*[!0-9]*) CNIP4N=0;; esac; case "$CNIP6N" in ''|*[!0-9]*) CNIP6N=0;; esac
    case "$CNIPR" in *chain-missing*) CNIP_STATE=degraded;; esac
  fi
  ebpf_load_session; [ "$EBPF_STATE" != active ] || [ -d "$EBPF_PIN" ] || EBPF_REASON=pins-missing
  # Latest data-plane probe verdict of this core (ok | proxy-down | dataplane-down | route-down | fakeip-lost | offline), else empty.
  EGV=""; if [ "$STATUS_RUNNING" = true ] && [ -r "$RUN/egress-state" ]; then EGV=$(awk -v p="$STATUS_PID" '$2 == p {print $1; exit}' "$RUN/egress-state" 2>/dev/null | tr -cd 'a-z-'); fi
  printf '{"ok":true,"runtimeSchema":4,"networkIntegrity":"%s","networkFault":"%s","running":%s,"pid":%s,"mode":"%s","ipv4Rules":%s,"ipv6Rules":%s,"ipv6Mode":"%s","ipv6DisableGuard":%s,"dnsMode":"%s","ipv6DnsPolicy":"%s","dnsIpv4Rule":%s,"dnsIpv6Rule":%s,"dnsListenerReady":%s,"dataPlaneHealthy":%s,"killSwitchActive":%s,"ipv6DisabledByHetu":%s,"watchdog":%s,"recoveredStaleRules":%s,"staleRules":%s,"mark":"%s","table":"%s","pref":"%s","controllerPort":%s,"appScope":"%s","directUidRanges":"%s","directGidRanges":"%s","sharedBypassMacs":"%s","sharedNetwork":"%s","killSwitchRequested":"%s","systemDns":"%s","coreGroup":"%s","dotGuard":%s,"privateDns":"%s","vendorFirewall":"%s","vendorFirewallDetail":"%s","tuning":"%s","log":"%s","configCheckLog":"%s","cnip":"%s","cnipReason":"%s","cnipV4Entries":%s,"cnipV6Entries":%s,"cnipTcpOnly":%s,"sharedScoped":%s,"sharedInterfaces":"%s","sharedMacMode":"%s","sharedOnlyMacs":"%s","sharedDroppedInterfaces":"%s","ebpf":"%s","ebpfReason":"%s","egress":"%s"}\n' "$H_STATE" "$H_REASON" "$STATUS_RUNNING" "$STATUS_PID" "$STATUS_MODE" "$IPV4OK" "$IPV6OK" "$IPV6V" "$DISABLE6" "$DNSV" "$DNS6POLICY" "$DNS4" "$DNS6" "$DNSREADY" "$HEALTH" "$([ "$K4" = true ] || [ "$K6" = true ] && echo true || echo false)" "$V6OFF" "$WD" "$STATUS_HEALED" "$STALE" "$SM" "$ST" "$SP" "$CPV" "$SCOPEV" "$DIRECTV" "$DIRECTGIDV" "$SHAREMACV" "$SHAREV" "$KILLV" "$SYSDNSV" "$COREGIDV" "$DOTV" "$PDNSV" "$GFV" "$GFDV" "$TUNEV" "$LOG" "$CHECKLOG" "$CNIP_STATE" "$CNIPR" "$CNIP4N" "$CNIP6N" "$({ [ "$CNIP_STATE" = ipset ] || [ "$CNIP_STATE" = iptables ]; } && [ "$CN_TCPONLY" = 1 ] && echo true || echo false)" "$([ "$SHR_SCOPED" = 1 ] && echo true || echo false)" "$SHR_IFACES" "$SHR_MODE" "$SHR_ONLY" "$SHR_DROPPED" "$EBPF_STATE" "$EBPF_REASON" "$EGV"
}

# Session-bound network integrity. No remote reachability failure restarts the core.
# iptables-restore --noflush commits only the named Hetu chains, never netd tables.
health_owned(){
  awk '($1=="-N" || $1=="-A") && $2 ~ /^HETU_(MOUT|MPRE|NOUT|NPRE|DNSOUT|DNSPRE|DOTOUT|QUICOUT|QUICFWD|WROUT|WRFWD|V6OUT|V6FWD|MOUT_T|NOUT_T|WROUT_T|QUICOUT_T|V6OUT_T|MPRE_S|NPRE_S|DNSPRE_S|WRFWD_S|QUICFWD_S|V6FWD_S)$/ {print;next}
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
  # Capture rules of an active eBPF matcher reference its pins; a lost pin cannot be rebuilt here.
  if [ "$(sed -n 's/^EBPF_MATCH=//p' "$SESSION" | head -n 1)" = active ] && [ ! -d "$EBPF_PIN" ]; then health_fault ebpf-pins; fi
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
  H_CHANGED=0; H_REFILL=""
  for H_C in $(awk '$1=="-N" {print $2}' "$H_E"); do
    H_EXPECTED=$(awk -v c="$H_C" '($1=="-N" || $1=="-A") && $2==c' "$H_E")
    H_ACTUAL=$(printf '%s\n' "$H_RAW" | awk -v c="$H_C" '($1=="-N" || $1=="-A") && $2==c')
    [ "$H_EXPECTED" != "$H_ACTUAL" ] || continue
    if printf '%s\n' "$H_RAW" | grep -Fqx -- "-N $H_C"; then printf -- '-F %s\n' "$H_C" >> "$H_BATCH"
    else printf -- '-N %s\n' "$H_C" >> "$H_BATCH"; fi
    H_REFILL="$H_REFILL $H_C"
    H_CHANGED=1
  done
  # A CNIP head jumps (-g) to its "<chain>_T": declare every chain first, then refill them.
  for H_C in $H_REFILL; do awk -v c="$H_C" '$1=="-A" && $2==c' "$H_E" >> "$H_BATCH"; done
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
  case "$H_REASON" in *routing-journal*|*listener-*|*native-device*|*core-*|*watchdog-*|*ebpf-*) exit 0;; esac
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

# ------------------------------------------------------------------ Root monitor daemon
# Network-match automation and traffic accounting keep running when the App process is killed.
# Session scoped: `start` spawns it after the watchdog is ready, only when the App exported
# $BASE/policy/monitor with NETMATCH=1 or TRAFFIC=1. It never touches netfilter, routes or the
# transaction lock; stopwatchdog() (stop, rollback, start, status self-heal) and the watchdog's
# core-exit path stop it. Identity is the command line "<script> monitor", one instance at a time
# (lock directory + pidfile), and it exits as soon as the session generation changes.
MONITOR_PID="$RUN/monitor.pid"
MONITOR_LOCK="$RUN/.monitor.lock"
MONITOR_DIR="$RUN/monitor"
MONITOR_POLICY="$BASE/policy/monitor"
MONITOR_SPOOL="$MONITOR_DIR/traffic.spool"
MONITOR_SPOOL_MAX=2097152
# Hot per-poll state lives in tmpfs, never on flash; falls back to the run directory.
MONITOR_TMP_ROOT=/dev/hetu-monitor
MONITOR_HTTP_TOOLS="curl wget nc"
MONITOR_APP_SERVICE=io.github.xgl34222220.hetu/.ProxyNetworkMatchService
MONITOR_APP_ACTION=io.github.xgl34222220.hetu.DAEMON_NETWORK_ACTION
MONITOR_SCRIPTS="$BASE/scripts"
MONITOR_SCRIPTS_LOG="$RUN/scripts.log"

monitor_is_ours(){
  case "${1:-}" in ''|*[!0-9]*) return 1;; esac
  kill -0 "$1" 2>/dev/null || return 1
  MI_CMD=$(tr '\000' '\n' < "/proc/$1/cmdline" 2>/dev/null) || return 1
  printf '%s\n' "$MI_CMD" | awk -v s="$BASE/hetu-root.sh" '$0==s {if(getline>0 && $0=="monitor") found=1} END {exit !found}'
}
stopmonitor(){
  SM_PID=$(cat "$MONITOR_PID" 2>/dev/null || true)
  if [ "$SM_PID" != "$$" ] && monitor_is_ours "$SM_PID"; then
    kill "$SM_PID" 2>/dev/null || true
    SM_N=0
    while monitor_is_ours "$SM_PID" && [ "$SM_N" -lt 60 ]; do sleep 0.05; SM_N=$((SM_N+1)); done
    if monitor_is_ours "$SM_PID"; then kill -9 "$SM_PID" 2>/dev/null || true; fi
  fi
  # A SIGKILLed monitor cannot reap its bounded child (script runner or controller fetch).
  case "$SM_PID" in ''|*[!0-9]*) ;; *)
    if [ "$SM_PID" != "$$" ] && ! monitor_is_ours "$SM_PID"; then
      for SM_TMP in "$MONITOR_TMP_ROOT/$SM_PID" "$MONITOR_DIR/tmp.$SM_PID"; do
        SM_CHILD=$(cat "$SM_TMP/child" 2>/dev/null || true)
        case "$SM_CHILD" in ''|*[!0-9]*) ;; *)
          SM_PPID=$(sed -n 's/^PPid:[[:space:]]*//p' "/proc/$SM_CHILD/status" 2>/dev/null)
          case "$SM_PPID" in 1|"$SM_PID") monitor_kill_tree "$SM_CHILD";; esac;; esac
        rm -rf "$SM_TMP"
      done
    fi;; esac
  if [ "$SM_PID" != "$$" ]; then rm -f "$MONITOR_PID"; rm -rf "$MONITOR_LOCK"; fi
}
monitor_policy_value(){ sed -n "s/^$1=//p" "$MONITOR_POLICY" 2>/dev/null | head -n 1; }
monitor_wanted(){
  [ -r "$MONITOR_POLICY" ] || return 1
  [ "$(monitor_policy_value NETMATCH)" = 1 ] || [ "$(monitor_policy_value TRAFFIC)" = 1 ]
}
start_monitor(){
  stopmonitor
  monitor_wanted || return 0
  SMO_GEN=$(cat "$RUN/generation" 2>/dev/null) || return 1
  [ -n "$SMO_GEN" ] || return 1
  mkdir -p "$MONITOR_DIR" 2>/dev/null || return 1
  detach_probe
  $SETSID "$0" monitor "$SMO_GEN" </dev/null >/dev/null 2>&1 &
  SMO_CHILD=$!
  printf '%s\n' "$SMO_CHILD" > "$MONITOR_PID" || return 1
  printf '%s started pid=%s generation=%s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$SMO_CHILD" "$SMO_GEN" >> "$MONITOR_DIR/monitor.log" 2>/dev/null || true
}
monitor_now_ms(){ MON_NOW=$(awk '{printf "%d", $1*1000}' /proc/uptime 2>/dev/null); case "$MON_NOW" in ''|*[!0-9]*) MON_NOW=0;; esac; }
# Every descendant of a PID (script runner -> timeout -> sh), deepest first, so none outlives us.
monitor_kill_tree(){
  case "${1:-}" in ''|*[!0-9]*) return 0;; esac
  for MKT_PATH in $(grep -l "^PPid:[[:space:]]*$1\$" /proc/[0-9]*/status 2>/dev/null); do
    MKT_PID=${MKT_PATH#/proc/}; MKT_PID=${MKT_PID%/status}
    [ "$MKT_PID" != "$$" ] || continue
    monitor_kill_tree "$MKT_PID"
  done
  kill -9 "$1" 2>/dev/null || true
}
# Runs a bounded child so a TERM reaches the trap at once and the child dies with the monitor.
monitor_child(){
  "$@" &
  MON_CHILD=$!
  printf '%s\n' "$MON_CHILD" > "$MON_TMP/child" 2>/dev/null || true
  wait "$MON_CHILD"; MC_RC=$?
  MON_CHILD=""
  return "$MC_RC"
}
monitor_sleep(){ sleep "$1" & MON_SLEEP=$!; wait "$MON_SLEEP" 2>/dev/null; MON_SLEEP=""; }
monitor_term(){
  trap '' TERM INT HUP
  [ -z "${MON_SLEEP:-}" ] || kill "$MON_SLEEP" 2>/dev/null || true
  [ -z "${MON_CHILD:-}" ] || monitor_kill_tree "$MON_CHILD"
  MON_CHILD=""
  monitor_flush_open
  exit 0
}
monitor_release(){
  [ -z "${MON_SLEEP:-}" ] || kill "$MON_SLEEP" 2>/dev/null || true
  [ -z "${MON_CHILD:-}" ] || monitor_kill_tree "$MON_CHILD"
  [ "$(cat "$MONITOR_LOCK/pid" 2>/dev/null || true)" != "$$" ] || rm -rf "$MONITOR_LOCK"
  [ "$(cat "$MONITOR_PID" 2>/dev/null || true)" != "$$" ] || rm -f "$MONITOR_PID"
  [ -z "${MON_TMP:-}" ] || rm -rf "$MON_TMP"
  printf '%s exited pid=%s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$$" >> "$MONITOR_DIR/monitor.log" 2>/dev/null || true
  tail -n 40 "$MONITOR_DIR/monitor.log" > "$MONITOR_DIR/monitor.log.new.$$" 2>/dev/null && mv -f "$MONITOR_DIR/monitor.log.new.$$" "$MONITOR_DIR/monitor.log" 2>/dev/null || rm -f "$MONITOR_DIR/monitor.log.new.$$"
}
monitor_load_policy(){
  MON_POLICY_SIG=$(cksum < "$MONITOR_POLICY" 2>/dev/null | awk '{print $1 ":" $2}')
  MON_NETMATCH=$(monitor_policy_value NETMATCH); MON_TRAFFIC=$(monitor_policy_value TRAFFIC)
  [ "$MON_NETMATCH" = 1 ] || MON_NETMATCH=0
  [ "$MON_TRAFFIC" = 1 ] || MON_TRAFFIC=0
  MON_MOBILE=$(monitor_policy_value MOBILE); [ "$MON_MOBILE" = 1 ] || MON_MOBILE=0
  MON_MATCH_ACTION=$(monitor_policy_value MATCH_ACTION); MON_UNMATCH_ACTION=$(monitor_policy_value UNMATCH_ACTION)
  [ -n "$MON_MATCH_ACTION" ] || MON_MATCH_ACTION=start
  [ -n "$MON_UNMATCH_ACTION" ] || MON_UNMATCH_ACTION=none
  MON_PROXY_MODE=$(monitor_policy_value MODE); MON_CONFIG=$(monitor_policy_value CONFIG)
  MON_API_PORT=$(monitor_policy_value API_PORT); MON_API_SECRET=$(monitor_policy_value API_SECRET)
  port "$MON_API_PORT" || MON_TRAFFIC=0
  kind_has_api "$(core_kind)" || MON_TRAFFIC=0
  MON_INTERVAL=$(monitor_policy_value TRAFFIC_INTERVAL)
  case "$MON_INTERVAL" in ''|*[!0-9]*) MON_INTERVAL=5;; esac
  [ "$MON_INTERVAL" -ge 3 ] || MON_INTERVAL=3
  [ "$MON_INTERVAL" -le 60 ] || MON_INTERVAL=60
  for MLP_KEY in SSID BSSID SLOT MCCMNC; do
    sed -n "s/^$MLP_KEY=//p" "$MONITOR_POLICY" 2>/dev/null > "$MON_TMP/cond.$MLP_KEY" || : > "$MON_TMP/cond.$MLP_KEY"
  done
  MON_NEEDS_SLOT=0; [ ! -s "$MON_TMP/cond.SLOT" ] || MON_NEEDS_SLOT=1
  MON_NEEDS_OP=0; [ ! -s "$MON_TMP/cond.MCCMNC" ] || MON_NEEDS_OP=1
}

# ---- environment (all reads are best effort; an unreadable value never matches a condition)
monitor_route_dev(){
  ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<NF;i++) if($i=="dev"){print $(i+1); exit}}'
}
monitor_kind_of(){
  case "$1" in '') printf offline;; wlan*|swlan*|wifi*|mlan*) printf wifi;;
    rmnet*|rev_rmnet*|ccmni*|seth_*|pdp*|ppp*|clat*|v4-rmnet*|v4-ccmni*|wwan*|rmnet_data*) printf cellular;;
    *) printf other;; esac
}
monitor_wifi_ident(){
  MW_SSID=""; MW_BSSID=""
  MW_LINE=$(timeout 5 cmd wifi status 2>/dev/null | grep -m 1 'SSID: ')
  [ -n "$MW_LINE" ] || MW_LINE=$(timeout 5 dumpsys wifi 2>/dev/null | grep -m 1 'mWifiInfo SSID: ')
  if [ -n "$MW_LINE" ]; then
    MW_SSID=$(printf '%s\n' "$MW_LINE" | sed -n 's/.*SSID: "\(.*\)", BSSID: .*/\1/p')
    [ -n "$MW_SSID" ] || MW_SSID=$(printf '%s\n' "$MW_LINE" | sed -n 's/.*SSID: \([^",][^,]*\), BSSID: .*/\1/p')
    MW_BSSID=$(printf '%s\n' "$MW_LINE" | sed -n 's/.*BSSID: \([0-9A-Fa-f:]\{17\}\).*/\1/p')
  fi
  if [ -z "$MW_SSID" ] && has iw; then
    MW_OUT=$(timeout 5 iw dev "$1" link 2>/dev/null)
    MW_SSID=$(printf '%s\n' "$MW_OUT" | sed -n 's/^[[:space:]]*SSID: //p' | head -n 1)
    [ -n "$MW_BSSID" ] || MW_BSSID=$(printf '%s\n' "$MW_OUT" | sed -n 's/^Connected to \([0-9A-Fa-f:]\{17\}\).*/\1/p' | head -n 1)
  fi
  case "$MW_SSID" in '<unknown ssid>') MW_SSID="";; esac
  case "$MW_BSSID" in 02:00:00:00:00:00|00:00:00:00:00:00) MW_BSSID="";; esac
}
monitor_slot_operator(){ printf '%s\n' "$1" | awk -F, -v s="$2" '{v=$s; gsub(/[-_ ]/,"",v); if (v ~ /^[0-9][0-9][0-9][0-9][0-9][0-9]?$/) print v}'; }
monitor_cell_ident(){
  MC_SLOT=-1; MC_SIMOP=""; MC_NETOP=""
  MC_SUB=$(settings get global multi_sim_data_call 2>/dev/null)
  case "$MC_SUB" in ''|*[!0-9]*) ;; *)
    MC_SIM=$(timeout 5 content query --uri content://telephony/siminfo --projection sim_id --where "_id=$MC_SUB" 2>/dev/null | sed -n 's/.*sim_id=\([0-9][0-9]*\).*/\1/p' | head -n 1)
    case "$MC_SIM" in 0|1) MC_SLOT=$((MC_SIM+1));; esac;; esac
  MC_SIMPROP=$(getprop gsm.sim.operator.numeric 2>/dev/null); MC_NETPROP=$(getprop gsm.operator.numeric 2>/dev/null)
  if [ "$MC_SLOT" = -1 ]; then
    # One loaded SIM decides the data slot on its own.
    MC_SLOT=$(getprop gsm.sim.state 2>/dev/null | awk -F, '{n=0; s=-1; for(i=1;i<=NF;i++) if($i=="LOADED"||$i=="READY"){n++; s=i} if(n==1 && s<=2) print s; else print -1}')
    case "$MC_SLOT" in 1|2) ;; *) MC_SLOT=-1;; esac
  fi
  if [ "$MC_SLOT" != -1 ]; then
    MC_SIMOP=$(monitor_slot_operator "$MC_SIMPROP" "$MC_SLOT"); MC_NETOP=$(monitor_slot_operator "$MC_NETPROP" "$MC_SLOT")
  fi
}
monitor_member(){ # value file ignorecase -> empty file matches anything
  [ -s "$2" ] || return 0
  if [ "$3" = 1 ]; then grep -Fxiq -- "$1" "$2"; else grep -Fxq -- "$1" "$2"; fi
}
monitor_sense(){
  ME_DEV=$(monitor_route_dev); ME_KIND=$(monitor_kind_of "$ME_DEV")
  if [ "$ME_KIND" != "$MON_LAST_KIND" ] || [ "$ME_DEV" != "$MON_LAST_DEV" ] || [ $((MON_NOW-MON_IDENT_AT)) -ge 15000 ]; then
    MON_SSID=""; MON_BSSID=""; MON_SLOT=-1; MON_SIMOP=""; MON_NETOP=""
    if [ "$ME_KIND" = wifi ]; then monitor_wifi_ident "$ME_DEV"; MON_SSID="$MW_SSID"; MON_BSSID="$MW_BSSID"
    elif [ "$ME_KIND" = cellular ] && { [ "$MON_NEEDS_SLOT" = 1 ] || [ "$MON_NEEDS_OP" = 1 ]; }; then
      monitor_cell_ident; MON_SLOT="$MC_SLOT"; MON_SIMOP="$MC_SIMOP"; MON_NETOP="$MC_NETOP"
    fi
    MON_LAST_KIND="$ME_KIND"; MON_LAST_DEV="$ME_DEV"; MON_IDENT_AT="$MON_NOW"
  fi
  ME_MATCH=0
  if [ "$ME_KIND" = wifi ] && monitor_member "$MON_SSID" "$MON_TMP/cond.SSID" 0 && monitor_member "$MON_BSSID" "$MON_TMP/cond.BSSID" 1; then ME_MATCH=1; fi
  if [ "$ME_KIND" = cellular ]; then
    [ "$MON_MOBILE" != 1 ] || ME_MATCH=1
    if [ "$MON_SLOT" != -1 ] && [ -s "$MON_TMP/cond.SLOT" ] && grep -Fxq -- "$MON_SLOT" "$MON_TMP/cond.SLOT"; then ME_MATCH=1; fi
    if [ -s "$MON_TMP/cond.MCCMNC" ]; then
      if [ -n "$MON_SIMOP" ] && grep -Fxq -- "$MON_SIMOP" "$MON_TMP/cond.MCCMNC"; then ME_MATCH=1; fi
      if [ -n "$MON_NETOP" ] && grep -Fxq -- "$MON_NETOP" "$MON_TMP/cond.MCCMNC"; then ME_MATCH=1; fi
    fi
  fi
  case "$ME_KIND" in
    wifi) ME_LABEL="Wi‑Fi${MON_SSID:+ · $MON_SSID}${MON_BSSID:+ · $MON_BSSID}";;
    cellular) ME_LABEL="移动数据"; [ "$MON_SLOT" = -1 ] || ME_LABEL="$ME_LABEL · 卡$MON_SLOT"
      ME_OP="$MON_SIMOP"; [ -n "$ME_OP" ] || ME_OP="$MON_NETOP"; [ -z "$ME_OP" ] || ME_LABEL="$ME_LABEL · $ME_OP";;
    *) ME_LABEL="其他/离线";;
  esac
  if [ "$ME_MATCH" = 1 ]; then ME_ACTION="$MON_MATCH_ACTION"; else ME_ACTION="$MON_UNMATCH_ACTION"; fi
}

# ---- actions
monitor_script_name_ok(){
  case "$1" in ''|.*|*[!A-Za-z0-9._-]*|pre-start.sh|post-stop.sh) return 1;; *.sh|*.SH|*.Sh|*.sH) ;; *) return 1;; esac
  [ "${#1}" -le 75 ]
}
monitor_run_script(){ (
  trap - EXIT
  MS_NAME="$1"; MS_EVENT="$2"; MS_PATH="$MONITOR_SCRIPTS/$MS_NAME"
  [ -f "$MS_PATH" ] || exit 44
  HETU_HOOK=network; HETU_EVENT="$MS_EVENT"; HETU_NETWORK="$3"; HETU_MODE="$MON_PROXY_MODE"; HETU_CONFIG="$MON_CONFIG"
  HETU_BASE="$BASE"; HETU_RUN_DIR="$RUN"; HETU_SCRIPT_DIR="$MONITOR_SCRIPTS"; HETU_ORIGIN=root-monitor
  export HETU_HOOK HETU_EVENT HETU_NETWORK HETU_MODE HETU_CONFIG HETU_BASE HETU_RUN_DIR HETU_SCRIPT_DIR HETU_ORIGIN
  { printf '\n[%s] network-match event=%s script=%s network=%s origin=root-monitor\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$MS_EVENT" "$MS_NAME" "$3"
    if has timeout; then timeout 12 sh "$MS_PATH" </dev/null; else sh "$MS_PATH" </dev/null; fi; MS_RC=$?
    printf '[network-match] exit=%s\n' "$MS_RC"; exit "$MS_RC"; } >> "$MONITOR_SCRIPTS_LOG" 2>&1
) }
monitor_handoff(){
  # Stop and config switching change App-owned intent (owner, wanted, generated config): the App
  # service executes them, woken by Root even after its process was killed.
  MH_OUT=$(timeout 10 am start-foreground-service -n "$MONITOR_APP_SERVICE" -a "$MONITOR_APP_ACTION" \
    --es kind "$1" --es target "$2" --es label "$3" --es id "$4" --el at "$MON_NOW" 2>&1); MH_RC=$?
  case "$MH_OUT" in *Error*|*error:*|*Exception*) MH_RC=1;; esac
  return "$MH_RC"
}
monitor_record(){
  printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$1" >> "$MONITOR_DIR/actions.log" 2>/dev/null || true
  tail -n 40 "$MONITOR_DIR/actions.log" > "$MONITOR_DIR/actions.log.new.$$" 2>/dev/null && mv -f "$MONITOR_DIR/actions.log.new.$$" "$MONITOR_DIR/actions.log" 2>/dev/null || rm -f "$MONITOR_DIR/actions.log.new.$$"
}
monitor_limiter_allow(){ # decision: same decision not within 30 s, at most 3 actions per 60 s
  ML_KEEP=""; ML_COUNT=0; ML_REPEAT=0
  for ML_ITEM in $MON_RECENT; do
    ML_AT=${ML_ITEM%%:*}
    [ $((MON_NOW-ML_AT)) -lt 60000 ] || continue
    ML_KEEP="$ML_KEEP $ML_ITEM"; ML_COUNT=$((ML_COUNT+1))
    if [ "${ML_ITEM#*:}" = "$1" ] && [ $((MON_NOW-ML_AT)) -lt 30000 ]; then ML_REPEAT=1; fi
  done
  MON_RECENT="$ML_KEEP"
  [ "$ML_REPEAT" = 0 ] && [ "$ML_COUNT" -lt 3 ] || return 1
  MON_RECENT="$MON_RECENT $MON_NOW:$1"
}
monitor_netmatch_tick(){
  monitor_sense
  MD_DECISION="$ME_LABEL|$ME_MATCH|$ME_ACTION"
  if [ "$MD_DECISION" != "$MON_CAND" ]; then MON_CAND="$MD_DECISION"; MON_CAND_AT="$MON_NOW"; return 0; fi
  [ $((MON_NOW-MON_CAND_AT)) -ge 2500 ] || return 0
  [ "$MD_DECISION" != "$MON_DONE" ] || return 0
  MON_DONE="$MD_DECISION"
  printf '%s\n' "$ME_LABEL" > "$MON_TMP/environment" 2>/dev/null || true
  if [ "$MON_BASELINED" != 1 ]; then MON_BASELINED=1; monitor_record "baseline match=$ME_MATCH action=$ME_ACTION network=$ME_LABEL"; return 0; fi
  MD_EVENT=network-unmatch; [ "$ME_MATCH" != 1 ] || MD_EVENT=network-match
  case "$ME_ACTION" in start|none|'') monitor_record "no-op match=$ME_MATCH action=${ME_ACTION:-none} network=$ME_LABEL"; return 0;; esac
  # The action signature alone limits: a flapping network cannot drive a switch/script loop.
  MD_SIG=$(printf '%s' "$ME_ACTION" | cksum | awk '{print $1}')
  if ! monitor_limiter_allow "$MD_SIG"; then monitor_record "rate-limited action=$ME_ACTION network=$ME_LABEL"; return 0; fi
  MON_SEQ=$((MON_SEQ+1)); MD_ID="$MON_GEN-$MON_SEQ"
  case "$ME_ACTION" in
    run_script:*)
      MD_NAME=${ME_ACTION#run_script:}
      if ! monitor_script_name_ok "$MD_NAME"; then monitor_record "refused script=$MD_NAME"; return 0; fi
      monitor_child monitor_run_script "$MD_NAME" "$MD_EVENT" "$ME_LABEL"; MD_RC=$?
      monitor_record "script=$MD_NAME exit=$MD_RC id=$MD_ID network=$ME_LABEL";;
    stop)
      if monitor_child monitor_handoff stop "" "$ME_LABEL" "$MD_ID"; then monitor_record "handoff=stop id=$MD_ID network=$ME_LABEL"
      else monitor_record "handoff-failed=stop id=$MD_ID network=$ME_LABEL"; fi;;
    switch_config:*)
      MD_NAME=${ME_ACTION#switch_config:}
      if monitor_child monitor_handoff switch "$MD_NAME" "$ME_LABEL" "$MD_ID"; then monitor_record "handoff=switch target=$MD_NAME id=$MD_ID network=$ME_LABEL"
      else monitor_record "handoff-failed=switch target=$MD_NAME id=$MD_ID network=$ME_LABEL"; fi;;
    *) monitor_record "ignored action=$ME_ACTION";;
  esac
}

# ---- traffic collector
monitor_http_get(){ # path -> body on stdout
  MHG_URL="http://127.0.0.1:$MON_API_PORT$1"
  for MHG_TOOL in $MONITOR_HTTP_TOOLS; do
    case "$MHG_TOOL" in
      curl) has curl || continue
        if [ -n "$MON_API_SECRET" ]; then curl -fsS -m 4 -H "Authorization: Bearer $MON_API_SECRET" "$MHG_URL" 2>/dev/null && return 0
        else curl -fsS -m 4 "$MHG_URL" 2>/dev/null && return 0; fi;;
      wget) for MHG_BB in /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox /data/adb/magisk/busybox; do
          [ -x "$MHG_BB" ] || continue
          if [ -n "$MON_API_SECRET" ]; then timeout 5 "$MHG_BB" wget -q -T 4 -O - --header "Authorization: Bearer $MON_API_SECRET" "$MHG_URL" 2>/dev/null && return 0
          else timeout 5 "$MHG_BB" wget -q -T 4 -O - "$MHG_URL" 2>/dev/null && return 0; fi
        done;;
      nc) has nc || continue
        { printf 'GET %s HTTP/1.0\r\nHost: 127.0.0.1\r\n' "$1"; [ -z "$MON_API_SECRET" ] || printf 'Authorization: Bearer %s\r\n' "$MON_API_SECRET"; printf '\r\n'; } \
          | timeout 5 nc -w 4 127.0.0.1 "$MON_API_PORT" 2>/dev/null | awk 'BEGIN{h=1; ok=0} h && NR==1 {ok=($2=="200")} h && /^\r?$/ {h=0; next} !h && ok {print} END {exit !ok}' && return 0;;
    esac
  done
  return 1
}
monitor_flush_open(){
  [ -n "${MON_TMP:-}" ] && [ -s "$MON_TMP/bucket" ] || return 0
  monitor_spool_append "$MON_TMP/bucket"; : > "$MON_TMP/bucket"
}
monitor_spool_append(){
  MSA_SIZE=$(stat -c %s "$MONITOR_SPOOL" 2>/dev/null || echo 0)
  case "$MSA_SIZE" in ''|*[!0-9]*) MSA_SIZE=0;; esac
  if [ "$MSA_SIZE" -gt "$MONITOR_SPOOL_MAX" ]; then mv -f "$MONITOR_SPOOL" "$MONITOR_SPOOL.old" 2>/dev/null || true; fi
  cat "$1" >> "$MONITOR_SPOOL" 2>/dev/null || true
}
# One awk pass: per-connection deltas against the previous poll, merged into the open minute
# bucket by app / host / destination IP / outbound / rule, plus global totals from the
# controller counters (which also count connections that closed between polls). A finished
# minute is written to $MON_TMP/flush; the caller appends it to the spool.
MONITOR_TRAFFIC_AWK='
function num(s, key) { if (match(s, "\"" key "\":[0-9]+")) return substr(s, RSTART + length(key) + 3, RLENGTH - length(key) - 3) + 0; return -1 }
function str(s, key,   v) { if (match(s, "\"" key "\":\"[^\"]*\"")) { v = substr(s, RSTART + length(key) + 4, RLENGTH - length(key) - 5); gsub(/[\t\r\n]/, " ", v); return substr(v, 1, 200) } return "" }
function add(dim, key, up, down, fresh,   k) { if (key == "") key = "-"; k = dim SUBSEP key; U[k] += up; D[k] += down; C[k] += fresh; K[k] = 1 }
FILENAME == prevf { if ($1 == "#TOTAL") { PTU = $2 + 0; PTD = $3 + 0; HAVE_T = 1 } else { PU[$1] = $2 + 0; PD[$1] = $3 + 0 } next }
FILENAME == bucketf { if ($1 == "M") BM = $2 + 0; else if ($1 == "T") { TU = $2 + 0; TD = $3 + 0 } else if ($1 == "B") { k = $2 SUBSEP $3; U[k] = $4 + 0; D[k] = $5 + 0; C[k] = $6 + 0; K[k] = 1 } next }
{ body = body $0 }
END {
  printf "" > prevout
  if (BM != "" && BM != minute) {
    printf "M\t%d\n", BM > flushf
    printf "T\t%d\t%d\n", TU, TD > flushf
    for (k in K) { split(k, p, SUBSEP); printf "B\t%s\t%s\t%d\t%d\t%d\n", p[1], p[2], U[k], D[k], C[k] > flushf }
    split("", U); split("", D); split("", C); split("", K); TU = 0; TD = 0
  }
  gu = num(body, "uploadTotal"); gd = num(body, "downloadTotal")
  if (gu >= 0 && gd >= 0) {
    if (HAVE_T && gu >= PTU && gd >= PTD) { TU += gu - PTU; TD += gd - PTD }
    else if (HAVE_T) { TU += gu; TD += gd }
    printf "#TOTAL\t%d\t%d\n", gu, gd > prevout
  }
  at = index(body, "\"connections\":[")
  n = 0
  if (at > 0) { rest = substr(body, at + 15); if (substr(rest, 1, 1) != "]") n = split(rest, part, "[}],[{]") }
  for (i = 1; i <= n; i++) {
    s = part[i]; id = str(s, "id"); if (id == "") continue
    up = num(s, "upload"); down = num(s, "download"); if (up < 0) up = 0; if (down < 0) down = 0
    printf "%s\t%d\t%d\n", id, up, down > prevout
    if (first) continue
    fresh = !(id in PU)
    du = fresh ? up : up - PU[id]; dd = fresh ? down : down - PD[id]
    if (du < 0) du = up; if (dd < 0) dd = down
    if (du == 0 && dd == 0 && !fresh) continue
    app = str(s, "process")
    if (app == "") { app = str(s, "processPath"); sub(/.*\//, "", app) }
    if (app == "") { u = num(s, "uid"); if (u >= 0) app = "uid:" u }
    host = str(s, "host"); if (host == "") host = str(s, "sniffHost")
    ip = str(s, "destinationIP")
    out = ""; if (match(s, "\"chains\":\\[\"[^\"]*\"")) { out = substr(s, RSTART + 11, RLENGTH - 12); gsub(/[\t\r\n]/, " ", out) }
    rule = str(s, "rule"); payload = str(s, "rulePayload"); if (payload != "") rule = rule "(" payload ")"
    add("app", app, du, dd, fresh); add("host", host, du, dd, fresh); add("ip", ip, du, dd, fresh)
    add("outbound", out, du, dd, fresh); add("rule", substr(rule, 1, 200), du, dd, fresh)
  }
  printf "M\t%d\n", minute > bucketout
  printf "T\t%d\t%d\n", TU, TD > bucketout
  for (k in K) { split(k, p, SUBSEP); printf "B\t%s\t%s\t%d\t%d\t%d\n", p[1], p[2], U[k], D[k], C[k] > bucketout }
}'
monitor_traffic_tick(){
  monitor_child monitor_http_get /connections > "$MON_TMP/body" 2>/dev/null || { MON_FETCH_FAIL=$((MON_FETCH_FAIL+1)); return 0; }
  MON_FETCH_FAIL=0
  MT_MINUTE=$(($(date +%s)/60*60))
  # The first poll only records what is already open: those bytes were sent before this session's
  # monitor watched them (or counted by an earlier instance).
  MT_FIRST=0; [ -f "$MON_TMP/prev" ] || { MT_FIRST=1; : > "$MON_TMP/prev"; }
  [ -f "$MON_TMP/bucket" ] || : > "$MON_TMP/bucket"
  rm -f "$MON_TMP/flush"
  awk -v prevf="$MON_TMP/prev" -v bucketf="$MON_TMP/bucket" -v prevout="$MON_TMP/prev.new" -v bucketout="$MON_TMP/bucket.new" \
    -v flushf="$MON_TMP/flush" -v minute="$MT_MINUTE" -v first="$MT_FIRST" "$MONITOR_TRAFFIC_AWK" "$MON_TMP/prev" "$MON_TMP/bucket" "$MON_TMP/body" 2>/dev/null || return 0
  mv -f "$MON_TMP/prev.new" "$MON_TMP/prev" 2>/dev/null; mv -f "$MON_TMP/bucket.new" "$MON_TMP/bucket" 2>/dev/null
  [ ! -s "$MON_TMP/flush" ] || monitor_spool_append "$MON_TMP/flush"
}

monitor_main(){
  MON_GEN="${1:-}"
  trap - EXIT
  [ -n "$MON_GEN" ] && [ "$MON_GEN" = "$(cat "$RUN/generation" 2>/dev/null)" ] || exit 0
  mkdir -p "$MONITOR_DIR" 2>/dev/null || exit 0
  if ! mkdir "$MONITOR_LOCK" 2>/dev/null; then
    MM_OWNER=$(cat "$MONITOR_LOCK/pid" 2>/dev/null || true)
    if [ "$MM_OWNER" != "$$" ] && monitor_is_ours "$MM_OWNER"; then exit 0; fi
    rm -rf "$MONITOR_LOCK"; mkdir "$MONITOR_LOCK" 2>/dev/null || exit 0
  fi
  printf '%s\n' "$$" > "$MONITOR_LOCK/pid" || { rm -rf "$MONITOR_LOCK"; exit 0; }
  # monitor-ensure runs from the App's su: leave its cgroup like the watchdog does.
  cg_escape "$$"
  MON_CHILD=""; MON_SLEEP=""; MON_TMP=""
  trap 'monitor_release' EXIT
  trap 'monitor_term' TERM INT HUP
  MM_N=0
  while [ "$(cat "$MONITOR_PID" 2>/dev/null)" != "$$" ]; do
    MM_N=$((MM_N+1)); [ "$MM_N" -lt 80 ] || exit 0; sleep 0.025
  done
  MON_TMP="$MONITOR_TMP_ROOT/$$"
  mkdir -p "$MON_TMP" 2>/dev/null && chmod 700 "$MONITOR_TMP_ROOT" "$MON_TMP" 2>/dev/null || { MON_TMP="$MONITOR_DIR/tmp.$$"; mkdir -p "$MON_TMP" || exit 0; }
  rm -rf "$MONITOR_DIR"/tmp.* 2>/dev/null; mkdir -p "$MON_TMP" 2>/dev/null || exit 0
  monitor_load_policy
  MON_LAST_KIND=""; MON_LAST_DEV=""; MON_IDENT_AT=-100000; MON_CAND=""; MON_CAND_AT=0; MON_DONE=""; MON_BASELINED=0
  MON_RECENT=""; MON_SEQ=0; MON_NEXT_TRAFFIC=0; MON_FETCH_FAIL=0
  MON_CORE=$(cat "$PIDFILE" 2>/dev/null || true)
  while :; do
    [ "$MON_GEN" = "$(cat "$RUN/generation" 2>/dev/null)" ] || exit 0
    [ "$(cat "$MONITOR_PID" 2>/dev/null)" = "$$" ] || exit 0
    # Core gone and no watchdog left to restore the network: run the status self-heal ourselves.
    { core_maybe_alive "$MON_CORE" && kill -0 "$MON_CORE" 2>/dev/null; } || { watchdog_alive || "$0" status >/dev/null 2>&1; exit 0; }
    [ -r "$MONITOR_POLICY" ] || exit 0
    MM_SIG=$(cksum < "$MONITOR_POLICY" 2>/dev/null | awk '{print $1 ":" $2}')
    if [ "$MM_SIG" != "$MON_POLICY_SIG" ]; then monitor_load_policy; MON_CAND=""; fi
    [ "$MON_NETMATCH" = 1 ] || [ "$MON_TRAFFIC" = 1 ] || exit 0
    monitor_now_ms
    [ "$MON_NETMATCH" != 1 ] || monitor_netmatch_tick
    if [ "$MON_TRAFFIC" = 1 ] && [ "$MON_NOW" -ge "$MON_NEXT_TRAFFIC" ]; then
      monitor_traffic_tick; MON_NEXT_TRAFFIC=$((MON_NOW+MON_INTERVAL*1000))
    fi
    monitor_sleep 2
  done
}
monitor_status_json(){
  MS_PID=$(cat "$MONITOR_PID" 2>/dev/null || true); MS_ALIVE=false
  if monitor_is_ours "$MS_PID" && [ -n "$(cat "$RUN/generation" 2>/dev/null)" ]; then MS_ALIVE=true; else MS_PID=0; fi
  case "$MS_PID" in ''|*[!0-9]*) MS_PID=0;; esac
  MS_NM=false; MS_TR=false
  [ "$(monitor_policy_value NETMATCH)" != 1 ] || MS_NM=true
  [ "$(monitor_policy_value TRAFFIC)" != 1 ] || MS_TR=true
  MS_GEN=$(monitor_policy_value GEN | tr -cd 'A-Za-z0-9._-' | cut -c1-64)
  MS_LAST=$(tail -n 1 "$MONITOR_DIR/actions.log" 2>/dev/null | tr -d '\000-\037"\\' | cut -c1-300)
  printf '{"ok":true,"alive":%s,"pid":%s,"netmatch":%s,"traffic":%s,"policy":"%s","lastAction":"%s"}\n' "$MS_ALIVE" "$MS_PID" "$MS_NM" "$MS_TR" "$MS_GEN" "$MS_LAST"
}
# The App changed the exported policy while a session runs: start the monitor if it is now wanted
# and not alive. Never during a transaction (one lock attempt), never without a live core+watchdog.
monitor_ensure(){
  try_lock_once || { ok "运行事务进行中，稍后生效"; return 0; }
  ME_CORE=$(cat "$PIDFILE" 2>/dev/null || true)
  if monitor_wanted && [ -r "$SESSION" ] && pidcore "$ME_CORE" && kill -0 "$ME_CORE" 2>/dev/null && watchdog_alive \
      && ! monitor_is_ours "$(cat "$MONITOR_PID" 2>/dev/null || true)"; then
    start_monitor >/dev/null 2>&1 || true
  fi
  release_lock
  ok "监控策略已应用"
}
# Hands the closed traffic minutes to the App: one "ready" batch at a time, kept until the App
# acknowledges its id, so an App killed between read and commit gets the same batch again.
traffic_take(){
  mkdir -p "$MONITOR_DIR" || fail "无法读取流量记录"
  if [ ! -s "$MONITOR_DIR/traffic.ready" ]; then
    TT_TMP="$MONITOR_DIR/traffic.taking.$$"
    : > "$TT_TMP"
    for TT_F in "$MONITOR_SPOOL.old" "$MONITOR_SPOOL"; do
      [ -s "$TT_F" ] || continue
      mv -f "$TT_F" "$TT_F.take.$$" 2>/dev/null || continue
      cat "$TT_F.take.$$" >> "$TT_TMP"; rm -f "$TT_F.take.$$"
    done
    if [ -s "$TT_TMP" ]; then
      { printf 'HETU-TRAFFIC\t1\t%s-%s\n' "$(date +%s)" "$$"; cat "$TT_TMP"; } > "$MONITOR_DIR/traffic.ready.new" && mv -f "$MONITOR_DIR/traffic.ready.new" "$MONITOR_DIR/traffic.ready"
    fi
    rm -f "$TT_TMP"
  fi
  [ -s "$MONITOR_DIR/traffic.ready" ] && cat "$MONITOR_DIR/traffic.ready"
  return 0
}
traffic_ack(){
  case "${1:-}" in ''|*[!0-9-]*) fail "批次参数错误";; esac
  TA_HEAD=$(head -n 1 "$MONITOR_DIR/traffic.ready" 2>/dev/null | awk -F '\t' '{print $3}')
  [ "$TA_HEAD" != "$1" ] || rm -f "$MONITOR_DIR/traffic.ready"
  ok "已确认流量批次"
}

validate_action(){
  case "$2" in mihomo|sing-box|xray|v2ray|hysteria) ;; *) fail "未知核心类型";; esac
  [ -x "$BASE/bin/core" ] || fail "核心文件不存在或不可执行"; [ -r "$1" ] || fail "配置文件不存在"
  CORE_KIND_OVERRIDE="$2"; VC_DIR="$RUN/validate.$$"; CHECKLOG="$VC_DIR.log"; mkdir -p "$VC_DIR" || fail "无法创建校验目录"
  if [ "$2" = mihomo ]; then validatecfg_rc(){ "$BASE/bin/core" -t -d "$VC_DIR" -f "$1" > "$CHECKLOG" 2>&1; }; else validatecfg_rc(){ validatecfg "$BASE/bin/core" "$1"; }; fi
  if validatecfg_rc "$1"; then rm -rf "$VC_DIR" "$CHECKLOG"; ok "$(kind_label "$2") 配置校验通过"; return 0; fi
  VA_MSG=$(tail -c 900 "$CHECKLOG" 2>/dev/null | tr -d '\033\000' | tr '\n\r\t"\\' '     ')
  rm -rf "$VC_DIR" "$CHECKLOG"
  fail "$(kind_label "$2") 配置校验失败：$VA_MSG"
}
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
  # Config check with the deployed core's own CLI (sing-box check, xray run -test, v2ray test).
  validate) [ "$#" = 3 ] || fail "参数错误"; root; validate_action "$2" "$3";;
  network-health) health_json;;
  # Hot swap of the CN sets (App after deploying new lists; watchdog with "auto").
  cnip-reload) { [ "$#" = 1 ] || [ "$#" = 2 ]; } || fail "参数错误"; root; cnip_reload;;
  # Hot update of the eBPF matcher maps (direct UIDs, app-scope UIDs, bypass networks); no arguments
  # re-syncs the recorded lists. iptables rules are never rebuilt here.
  ebpf-update) { [ "$#" = 1 ] || [ "$#" = 4 ]; } || fail "参数错误"; root; shift; ebpf_update "$@";;
  # Called by the App on every network change (and by the watchdog itself every ~12 s): an App call
  # also asks the watchdog to re-verify that the core answers, not only the rules.
  repair-network) [ "$#" = 2 ] || exit 1; root; [ -n "${HETU_WATCHDOG_CALL:-}" ] || { : > "$RUN/liveness.kick"; } 2>/dev/null || true; health_repair "$2";;
  monitor) [ "$#" = 2 ] || exit 0; root; monitor_main "$2";;
  # 3.0.0 system-side recovery (stop/start spawn it detached), probe URL discovery for the App, and the
  # end of the post-stop fake-IP reset window.
  net-recover) [ "$#" = 2 ] || exit 0; root; net_recover "$2"; wifi_heal "$2" flushed;;
  wifi-heal) [ "$#" = 2 ] || exit 0; root; wifi_heal "$2";;
  probe-urls) [ "$#" = 1 ] || fail "参数错误"; root; probe_urls;;
  # Kept for timers spawned by 3.0.0-3.3.3: no sleep, the legacy chain is removed at once.
  fake-reject-expire) [ "$#" = 2 ] || exit 0; root; fake_reject_remove || true;;
  monitor-status) root; monitor_status_json;;
  monitor-ensure) [ "$#" = 1 ] || fail "参数错误"; root; monitor_ensure;;
  traffic-take) [ "$#" = 1 ] || fail "参数错误"; root; traffic_take;;
  traffic-ack) [ "$#" = 2 ] || fail "参数错误"; root; traffic_ack "$2";;
  watchdog) { [ "$#" = 12 ]; } || exit 0; root; watchdog "$2" "$3" "$4" "$5" "$6" "$7" "$8" "$9" "${10}" "${11:-}" "${12:-}";;
  *) fail "未知 Root 代理操作";;
esac
