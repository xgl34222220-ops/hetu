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
# Publish the owner atomically before creating compatibility lock metadata.
# Reaping is serialized and rereads the owner, so two stale-lock contenders
# cannot remove a winner's freshly created directory.
OWNER_FILE="$BOOT/restore.owner"
OWNER_SELF="$BOOT/restore.owner.$$"
printf '%s %s\n' "$BOOT_ID" "$$" > "$OWNER_SELF" || exit 1
release_owner(){
  [ "$(cat "$OWNER_FILE" 2>/dev/null)" != "$BOOT_ID $$" ] || rm -f "$OWNER_FILE"
  rm -f "$OWNER_SELF"
}
trap 'release_owner' EXIT
if ! ln "$OWNER_SELF" "$OWNER_FILE" 2>/dev/null; then
  mkdir "$BOOT/restore.reap" 2>/dev/null || exit 0
  read -r OWNER_BOOT OWNER_PID < "$OWNER_FILE" 2>/dev/null || { rmdir "$BOOT/restore.reap"; exit 0; }
  OWNER_LIVE=0
  if [ "$OWNER_BOOT" = "$BOOT_ID" ]; then
    case "$OWNER_PID" in ''|*[!0-9]*) OWNER_LIVE=1;; *) kill -0 "$OWNER_PID" 2>/dev/null && OWNER_LIVE=1;; esac
  fi
  if [ "$OWNER_LIVE" = 1 ]; then rmdir "$BOOT/restore.reap"; exit 0; fi
  rm -f "$OWNER_FILE"
  rmdir "$BOOT/restore.reap"
  ln "$OWNER_SELF" "$OWNER_FILE" 2>/dev/null || exit 0
fi
# A stale PID from a previous boot must never suppress this boot's restore.
if ! mkdir "$LOCK" 2>/dev/null; then
  OWNER_BOOT=$(cat "$LOCK/boot-id" 2>/dev/null)
  OWNER_PID=$(cat "$LOCK/pid" 2>/dev/null)
  # An absent owner is an initializing worker, never a stale lock.
  [ -n "$OWNER_BOOT" ] || exit 0
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
trap '[ "$(cat "$LOCK/pid" 2>/dev/null)" != "$$" ] || rm -rf "$LOCK"; release_owner' EXIT
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
healthy(){
  alive || return 1
  HEALTH=$(timeout -s TERM -k 2 12 /system/bin/sh "$BASE/hetu-root.sh" network-health 2>/dev/null) || return 1
  printf '%s\n' "$HEALTH" | grep -q '"networkIntegrity":"healthy"' &&
    printf '%s\n' "$HEALTH" | grep -q '"dataPlaneHealthy":true'
}
notify_app(){
  # The native proxy is already running; app/service restrictions cannot undo it.
  am start-foreground-service --user 0 -n io.github.xgl34222220.hetu/.ProxyNetworkMatchService \
    -a io.github.xgl34222220.hetu.ROOT_BOOT_RUNNING >/dev/null 2>&1 || true
}
command -v timeout >/dev/null 2>&1 || { state executor-missing; exit 1; }
state waiting-system
N=0
while [ "$(getprop sys.boot_completed)" != 1 ]; do
  allowed || { state cancelled; exit 0; }
  N=$((N+1)); [ "$N" -le 300 ] || { state system-timeout; exit 1; }
  sleep 2
done
# Native state/config is in /data/adb and needs no app launch or credential storage.
# Wait for a route rather than assuming network exists at BOOT_COMPLETED.
N=0; ATTEMPTS=0
while allowed; do
  if healthy; then allowed || { state cancelled; exit 0; }; state running; notify_app; exit 0; fi
  ROUTE=$(ip -4 route get 1.1.1.1 2>/dev/null; ip -6 route get 2606:4700:4700::1111 2>/dev/null)
  if ! printf '%s\n' "$ROUTE" | grep -q ' dev '; then
    state waiting-network
  elif [ ! -r "$BOOT/start.sh" ]; then
    state missing-plan; exit 1
  else
    [ "$ATTEMPTS" -lt 3 ] || { state restore-timeout; exit 1; }
    ATTEMPTS=$((ATTEMPTS+1))
    state restoring
    export HETU_BOOT_RESTORE_ID="$BOOT_ID"
    /system/bin/sh "$BOOT/start.sh" >> "$BOOT/restore.log" 2>&1
    RC=$?
    # Bound logs even when a provider/network failure keeps the core unready.
    tail -c 16384 "$BOOT/restore.log" > "$BOOT/restore.log.new.$$" && mv -f "$BOOT/restore.log.new.$$" "$BOOT/restore.log"
    if [ "$RC" = 0 ] && healthy; then allowed || { state cancelled; exit 0; }; state running; notify_app; exit 0; fi
    state retrying
  fi
  N=$((N+1)); [ "$N" -le 120 ] || { state restore-timeout; exit 1; }
  sleep 10
done
state cancelled
