#!/system/bin/sh
# 辟尘 Root TPROXY runtime controller. JSON only on stdout.
set -u
umask 077
BASE=/data/adb/bichen/proxy
RUN="$BASE/run"
PIDFILE="$RUN/mihomo.pid"
LOG="$RUN/mihomo.log"
TABLE=100
MARK=0x2333
MASK=0xffff
PREF=10000
CHAIN_OUT=BICHEN_OUTPUT
CHAIN_PRE=BICHEN_PREROUTING

json_ok() { printf '{"ok":true,"message":"%s"}\n' "$1"; }
json_fail() { printf '{"ok":false,"message":"%s"}\n' "$1"; exit 1; }
need_root() { [ "$(id -u)" = 0 ] || json_fail "需要 Root 权限"; }
valid_port() { case "${1:-}" in ''|*[!0-9]*) return 1;; esac; [ "$1" -ge 1 ] && [ "$1" -le 65535 ]; }
has_cmd() { command -v "$1" >/dev/null 2>&1; }

ipt4() { iptables "$@"; }
ipt6() { ip6tables "$@"; }

cleanup_v4() {
  ipt4 -t mangle -D OUTPUT -j "$CHAIN_OUT" >/dev/null 2>&1 || true
  ipt4 -t mangle -D PREROUTING -j "$CHAIN_PRE" >/dev/null 2>&1 || true
  ipt4 -t mangle -F "$CHAIN_OUT" >/dev/null 2>&1 || true
  ipt4 -t mangle -X "$CHAIN_OUT" >/dev/null 2>&1 || true
  ipt4 -t mangle -F "$CHAIN_PRE" >/dev/null 2>&1 || true
  ipt4 -t mangle -X "$CHAIN_PRE" >/dev/null 2>&1 || true
  ip rule del pref "$PREF" fwmark "$MARK/$MASK" table "$TABLE" >/dev/null 2>&1 || true
  ip route flush table "$TABLE" >/dev/null 2>&1 || true
}

cleanup_v6() {
  has_cmd ip6tables || return 0
  ipt6 -t mangle -D OUTPUT -j "$CHAIN_OUT" >/dev/null 2>&1 || true
  ipt6 -t mangle -D PREROUTING -j "$CHAIN_PRE" >/dev/null 2>&1 || true
  ipt6 -t mangle -F "$CHAIN_OUT" >/dev/null 2>&1 || true
  ipt6 -t mangle -X "$CHAIN_OUT" >/dev/null 2>&1 || true
  ipt6 -t mangle -F "$CHAIN_PRE" >/dev/null 2>&1 || true
  ipt6 -t mangle -X "$CHAIN_PRE" >/dev/null 2>&1 || true
  ip -6 rule del pref "$PREF" fwmark "$MARK/$MASK" table "$TABLE" >/dev/null 2>&1 || true
  ip -6 route flush table "$TABLE" >/dev/null 2>&1 || true
}

cleanup_rules() { cleanup_v4; cleanup_v6; }

stop_core() {
  if [ -f "$PIDFILE" ]; then
    PID=$(cat "$PIDFILE" 2>/dev/null || true)
    case "$PID" in ''|*[!0-9]*) ;; *)
      if kill -0 "$PID" >/dev/null 2>&1; then
        kill "$PID" >/dev/null 2>&1 || true
        N=0
        while kill -0 "$PID" >/dev/null 2>&1 && [ "$N" -lt 20 ]; do sleep 0.1; N=$((N+1)); done
        kill -9 "$PID" >/dev/null 2>&1 || true
      fi
    esac
    rm -f "$PIDFILE"
  fi
}

ipv6_active() {
  [ -r /proc/net/if_inet6 ] && [ -s /proc/net/if_inet6 ] && ip -6 route show default 2>/dev/null | grep -q '^default'
}

probe_tproxy4() {
  PORT="$1"
  ipt4 -t mangle -N BICHEN_TPROXY_PROBE >/dev/null 2>&1 || true
  ipt4 -t mangle -F BICHEN_TPROXY_PROBE >/dev/null 2>&1 || true
  if ! ipt4 -t mangle -A BICHEN_TPROXY_PROBE -p tcp -j TPROXY --on-port "$PORT" --tproxy-mark "$MARK/$MASK" >/dev/null 2>&1; then
    ipt4 -t mangle -F BICHEN_TPROXY_PROBE >/dev/null 2>&1 || true
    ipt4 -t mangle -X BICHEN_TPROXY_PROBE >/dev/null 2>&1 || true
    return 1
  fi
  ipt4 -t mangle -F BICHEN_TPROXY_PROBE >/dev/null 2>&1 || true
  ipt4 -t mangle -X BICHEN_TPROXY_PROBE >/dev/null 2>&1 || true
}

probe_tproxy6() {
  PORT="$1"
  has_cmd ip6tables || return 1
  ipt6 -t mangle -N BICHEN_TPROXY_PROBE >/dev/null 2>&1 || true
  ipt6 -t mangle -F BICHEN_TPROXY_PROBE >/dev/null 2>&1 || true
  if ! ipt6 -t mangle -A BICHEN_TPROXY_PROBE -p tcp -j TPROXY --on-port "$PORT" --tproxy-mark "$MARK/$MASK" >/dev/null 2>&1; then
    ipt6 -t mangle -F BICHEN_TPROXY_PROBE >/dev/null 2>&1 || true
    ipt6 -t mangle -X BICHEN_TPROXY_PROBE >/dev/null 2>&1 || true
    return 1
  fi
  ipt6 -t mangle -F BICHEN_TPROXY_PROBE >/dev/null 2>&1 || true
  ipt6 -t mangle -X BICHEN_TPROXY_PROBE >/dev/null 2>&1 || true
}

preflight() {
  PORT="$1"
  need_root
  valid_port "$PORT" || json_fail "TPROXY 监听端口无效"
  has_cmd ip || json_fail "系统缺少 ip 命令"
  has_cmd iptables || json_fail "系统缺少 iptables"
  probe_tproxy4 "$PORT" || json_fail "当前内核/iptables 不支持 IPv4 TPROXY"
  if ipv6_active; then
    probe_tproxy6 "$PORT" || json_fail "设备正在使用 IPv6，但 ip6tables TPROXY 不可用；为避免 IPv6 泄漏已拒绝启动"
  fi
  json_ok "TPROXY 预检通过"
}

bypass4() {
  C="$1"
  for NET in 0.0.0.0/8 10.0.0.0/8 100.64.0.0/10 127.0.0.0/8 169.254.0.0/16 172.16.0.0/12 192.168.0.0/16 224.0.0.0/4 240.0.0.0/4; do
    ipt4 -t mangle -A "$C" -d "$NET" -j RETURN || return 1
  done
}

bypass6() {
  C="$1"
  for NET in ::1/128 fc00::/7 fe80::/10 ff00::/8; do
    ipt6 -t mangle -A "$C" -d "$NET" -j RETURN || return 1
  done
}

install_v4() {
  PORT="$1"
  ip route replace local 0.0.0.0/0 dev lo table "$TABLE" || return 1
  ip rule add pref "$PREF" fwmark "$MARK/$MASK" table "$TABLE" >/dev/null 2>&1 || true
  ipt4 -t mangle -N "$CHAIN_OUT" || return 1
  ipt4 -t mangle -N "$CHAIN_PRE" || return 1
  ipt4 -t mangle -A "$CHAIN_OUT" -m owner --uid-owner 0 -j RETURN || return 1
  bypass4 "$CHAIN_OUT" || return 1
  ipt4 -t mangle -A "$CHAIN_OUT" -p tcp -j MARK --set-xmark "$MARK/$MASK" || return 1
  ipt4 -t mangle -A "$CHAIN_OUT" -p udp -j MARK --set-xmark "$MARK/$MASK" || return 1
  bypass4 "$CHAIN_PRE" || return 1
  ipt4 -t mangle -A "$CHAIN_PRE" -p tcp -j TPROXY --on-port "$PORT" --tproxy-mark "$MARK/$MASK" || return 1
  ipt4 -t mangle -A "$CHAIN_PRE" -p udp -j TPROXY --on-port "$PORT" --tproxy-mark "$MARK/$MASK" || return 1
  ipt4 -t mangle -A OUTPUT -j "$CHAIN_OUT" || return 1
  ipt4 -t mangle -A PREROUTING -j "$CHAIN_PRE" || return 1
}

install_v6() {
  PORT="$1"
  ipv6_active || return 0
  ip -6 route replace local ::/0 dev lo table "$TABLE" || return 1
  ip -6 rule add pref "$PREF" fwmark "$MARK/$MASK" table "$TABLE" >/dev/null 2>&1 || true
  ipt6 -t mangle -N "$CHAIN_OUT" || return 1
  ipt6 -t mangle -N "$CHAIN_PRE" || return 1
  ipt6 -t mangle -A "$CHAIN_OUT" -m owner --uid-owner 0 -j RETURN || return 1
  bypass6 "$CHAIN_OUT" || return 1
  ipt6 -t mangle -A "$CHAIN_OUT" -p tcp -j MARK --set-xmark "$MARK/$MASK" || return 1
  ipt6 -t mangle -A "$CHAIN_OUT" -p udp -j MARK --set-xmark "$MARK/$MASK" || return 1
  bypass6 "$CHAIN_PRE" || return 1
  ipt6 -t mangle -A "$CHAIN_PRE" -p tcp -j TPROXY --on-port "$PORT" --tproxy-mark "$MARK/$MASK" || return 1
  ipt6 -t mangle -A "$CHAIN_PRE" -p udp -j TPROXY --on-port "$PORT" --tproxy-mark "$MARK/$MASK" || return 1
  ipt6 -t mangle -A OUTPUT -j "$CHAIN_OUT" || return 1
  ipt6 -t mangle -A PREROUTING -j "$CHAIN_PRE" || return 1
}

start_all() {
  BIN="$1"; CFG="$2"; PORT="$3"
  preflight "$PORT" >/dev/null
  [ -x "$BIN" ] || json_fail "Root Mihomo 可执行文件不存在"
  [ -r "$CFG" ] || json_fail "TPROXY 配置文件不存在"
  mkdir -p "$RUN" || json_fail "无法创建 TPROXY 运行目录"
  cleanup_rules
  stop_core
  : > "$LOG"
  "$BIN" -d "$RUN" -f "$CFG" >>"$LOG" 2>&1 &
  PID=$!
  printf '%s\n' "$PID" > "$PIDFILE"
  sleep 1
  if ! kill -0 "$PID" >/dev/null 2>&1; then
    rm -f "$PIDFILE"
    json_fail "Mihomo Root 进程启动失败，请查看日志"
  fi
  if ! install_v4 "$PORT" || ! install_v6 "$PORT"; then
    cleanup_rules
    stop_core
    json_fail "TPROXY 防火墙/策略路由安装失败，已回滚"
  fi
  json_ok "Root TPROXY 已启动"
}

status_all() {
  need_root
  RUNNING=false; PID=0
  if [ -f "$PIDFILE" ]; then
    P=$(cat "$PIDFILE" 2>/dev/null || true)
    case "$P" in ''|*[!0-9]*) ;; *) if kill -0 "$P" >/dev/null 2>&1; then RUNNING=true; PID="$P"; fi;; esac
  fi
  V4=false; ipt4 -t mangle -C OUTPUT -j "$CHAIN_OUT" >/dev/null 2>&1 && V4=true
  V6=false; has_cmd ip6tables && ipt6 -t mangle -C OUTPUT -j "$CHAIN_OUT" >/dev/null 2>&1 && V6=true
  printf '{"ok":true,"running":%s,"pid":%s,"ipv4Rules":%s,"ipv6Rules":%s,"log":"%s"}\n' "$RUNNING" "$PID" "$V4" "$V6" "$LOG"
}

case "${1:-status}" in
  preflight) [ "$#" = 2 ] || json_fail "参数错误"; preflight "$2" ;;
  start) [ "$#" = 4 ] || json_fail "参数错误"; need_root; start_all "$2" "$3" "$4" ;;
  stop) need_root; cleanup_rules; stop_core; json_ok "Root TPROXY 已停止" ;;
  status) status_all ;;
  *) json_fail "未知 TPROXY 操作" ;;
esac
