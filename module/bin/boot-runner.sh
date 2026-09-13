#!/system/bin/sh
# Bound blocking startup work. This wrapper never changes hosts itself.
MODDIR=${0%/*}/..
. "$MODDIR/bin/runtime.sh" || exit 1
bichen_runtime || exit 1
case "${1:-}" in
  boot) LIMIT=8;;
  boot-check) LIMIT=30;;
  *) exit 1;;
esac
ASH_STANDALONE=1 "$BB" timeout -k 2 "$LIMIT" "$BB" ash "$MODDIR/bin/bichen" "$1" >/dev/null 2>&1
RESULT=$?
if [ "$RESULT" != 0 ] && [ -d /data/adb/bichen ]; then
  # One compact record per startup stage, no background retry/polling loop.
  umask 077
  printf '%s 启动阶段 %s 未完成（退出码 %s）；App 中可重新应用并运行诊断\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$1" "$RESULT" >> /data/adb/bichen/operations.log
fi
exit "$RESULT"
