#!/system/bin/sh
# Installed only by the explicit Root autostart setting. late_start must not block.
if [ "${1:-}" != --worker ]; then
  unset ASH_STANDALONE
  /system/bin/sh /data/adb/service.d/hetu-autostart.sh --worker </dev/null >/dev/null 2>&1 &
  exit 0
fi
unset ASH_STANDALONE
PATH=/system/bin:/system/xbin:/vendor/bin:/data/adb/magisk:$PATH
export PATH
umask 077
BASE=/data/adb/hetu
BOOT="$BASE/boot"
LOCK="$BOOT/restore.lock"
BOOT_ID=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null) || exit 1
[ -n "$BOOT_ID" ] && [ -f "$BOOT/enabled" ] || exit 0
mkdir -p "$BOOT" || exit 1
# A stale PID from a previous boot must never suppress this boot's restore.
if ! mkdir "$LOCK" 2>/dev/null; then
  OWNER_BOOT=$(cat "$LOCK/boot-id" 2>/dev/null)
  OWNER_PID=$(cat "$LOCK/pid" 2>/dev/null)
  if [ "$OWNER_BOOT" = "$BOOT_ID" ]; then
    case "$OWNER_PID" in ''|*[!0-9]*) exit 0;; esac
    OWNER_CMD=$(tr '\000' ' ' < "/proc/$OWNER_PID/cmdline" 2>/dev/null)
    case "$OWNER_CMD" in *'/data/adb/service.d/hetu-autostart.sh --worker'*) exit 0;; esac
  fi
  rm -rf "$LOCK" || exit 1
  mkdir "$LOCK" 2>/dev/null || exit 0
fi
printf '%s\n' "$BOOT_ID" > "$LOCK/boot-id"
printf '%s\n' "$$" > "$LOCK/pid"
trap '[ "$(cat "$LOCK/pid" 2>/dev/null)" != "$$" ] || rm -rf "$LOCK"' EXIT
state(){
  printf '%s %s\n' "$BOOT_ID" "$1" > "$BOOT/status.new.$$" && mv -f "$BOOT/status.new.$$" "$BOOT/status"
}
allowed(){
  [ -f "$BOOT/enabled" ] && [ "$BOOT_ID" != "$(cat "$BOOT/stopped-boot" 2>/dev/null)" ]
}
alive(){
  PID=$(cat "$BASE/run/core.pid" 2>/dev/null)
  case "$PID" in ''|*[!0-9]*) return 1;; esac
  kill -0 "$PID" 2>/dev/null || return 1
  EXE=$(readlink "/proc/$PID/exe" 2>/dev/null)
  [ "$EXE" = "$BASE/bin/core" ] || [ "$EXE" = "$BASE/bin/core (deleted)" ]
}
notify_app(){
  # The native proxy is already running; app/service restrictions cannot undo it.
  am start-foreground-service --user 0 -n io.github.xgl34222220.hetu/.ProxyNetworkMatchService \
    -a io.github.xgl34222220.hetu.ROOT_BOOT_RUNNING >/dev/null 2>&1 || true
}
state waiting-system
N=0
while [ "$(getprop sys.boot_completed)" != 1 ]; do
  allowed || { state cancelled; exit 0; }
  N=$((N+1)); [ "$N" -le 300 ] || { state system-timeout; exit 1; }
  sleep 2
done
# Native state/config is in /data/adb and needs no app launch or credential storage.
# Wait for a route rather than assuming network exists at BOOT_COMPLETED.
N=0
while allowed; do
  if alive; then state running; notify_app; exit 0; fi
  ROUTE=$(ip -4 route get 1.1.1.1 2>/dev/null; ip -6 route get 2606:4700:4700::1111 2>/dev/null)
  if ! printf '%s\n' "$ROUTE" | grep -q ' dev '; then
    state waiting-network
  elif [ ! -r "$BOOT/start.sh" ]; then
    state missing-plan; exit 1
  else
    state restoring
    export HETU_BOOT_RESTORE_ID="$BOOT_ID"
    /system/bin/sh "$BOOT/start.sh" >> "$BOOT/restore.log" 2>&1
    RC=$?
    # Bound logs even when a provider/network failure keeps the core unready.
    tail -c 16384 "$BOOT/restore.log" > "$BOOT/restore.log.new.$$" && mv -f "$BOOT/restore.log.new.$$" "$BOOT/restore.log"
    if [ "$RC" = 0 ] && alive; then state running; notify_app; exit 0; fi
    state retrying
  fi
  N=$((N+1)); [ "$N" -le 120 ] || { state restore-timeout; exit 1; }
  sleep 10
done
state cancelled
