# Shared installer/runtime selection. Only root-framework-owned BusyBox paths.
# The framework saved by customize.sh survives App su calls without KSU/APATCH env.
bichen_runtime() {
  BICHEN_FRAMEWORK=${1:-auto}
  BB=
  if [ "$BICHEN_FRAMEWORK" = auto ]; then
    case "${KSU:-false}" in true|1) BICHEN_FRAMEWORK=kernelsu;; esac
    if [ "$BICHEN_FRAMEWORK" = auto ]; then
      case "${APATCH:-false}" in true|1) BICHEN_FRAMEWORK=apatch;; esac
    fi
    if [ "$BICHEN_FRAMEWORK" = auto ] && [ -r "$MODDIR/root-framework" ]; then
      read -r BICHEN_FRAMEWORK < "$MODDIR/root-framework"
      case "$BICHEN_FRAMEWORK" in magisk|kernelsu|apatch) ;; *) BICHEN_FRAMEWORK=auto;; esac
    fi
    if [ "$BICHEN_FRAMEWORK" = auto ]; then
      if [ -x /data/adb/ksu/bin/busybox ]; then BICHEN_FRAMEWORK=kernelsu
      elif [ -x /data/adb/ap/bin/busybox ]; then BICHEN_FRAMEWORK=apatch
      else BICHEN_FRAMEWORK=magisk; fi
    fi
  fi
  case "$BICHEN_FRAMEWORK" in
    kernelsu) BB=/data/adb/ksu/bin/busybox;;
    apatch) BB=/data/adb/ap/bin/busybox;;
    magisk)
      BB=/data/adb/magisk/busybox
      if [ ! -x "$BB" ]; then
        MP=$(magisk --path 2>/dev/null)
        [ -z "$MP" ] || BB="$MP/.magisk/busybox"
      fi;;
    *) return 1;;
  esac
  [ -x "$BB" ]
}
