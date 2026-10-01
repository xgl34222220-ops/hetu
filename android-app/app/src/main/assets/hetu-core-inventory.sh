#!/system/bin/sh
# Read-only, bounded process metadata. Never read command lines or config files.
D_PROC=${1:-/proc}
D_NET=${2:-/sys/class/net}
D_OWN=${3:-/data/adb/hetu/bin/core}
D_SCANNED=0; D_MISSING=0; D_FOUND=0; D_LIMIT=0

d_ticks() (
  set -f
  IFS= read -r D_STAT < "$1/stat" 2>/dev/null || exit 1
  case "$D_STAT" in *') '*) ;; *) exit 1;; esac
  D_FIELDS=${D_STAT##*) }; set -- $D_FIELDS
  [ "$#" -ge 20 ] || exit 1
  shift 19
  case "$1" in ''|*[!0-9]*|0) exit 1;; esac
  printf '%s' "$1"
)

printf 'inventory\t1\n'
D_BOOT=unknown
IFS= read -r D_BOOT < "$D_PROC/sys/kernel/random/boot_id" 2>/dev/null || D_BOOT=unknown
case "$D_BOOT" in *[!a-f0-9-]*|'') D_BOOT=unknown;; esac
printf 'boot\t%s\n' "$D_BOOT"
for D_PATH in "$D_PROC"/[0-9]*; do
  [ -d "$D_PATH" ] || continue
  D_SCANNED=$((D_SCANNED + 1))
  if [ "$D_SCANNED" -gt 2048 ]; then D_LIMIT=1; break; fi
  IFS= read -r D_NAME < "$D_PATH/comm" 2>/dev/null || { D_MISSING=$((D_MISSING + 1)); continue; }
  case "$D_NAME" in mihomo|mihomo-smart|clash|clash.meta|sing-box|singbox|xray|v2ray|hysteria|hysteria2|core) ;; *) continue;; esac
  D_BEFORE=$(d_ticks "$D_PATH") || { D_MISSING=$((D_MISSING + 1)); continue; }
  D_EXE=$(readlink "$D_PATH/exe" 2>/dev/null) || D_EXE=
  D_ORIGIN=unknown
  case "$D_EXE" in "$D_OWN") D_ORIGIN=hetu;; /data/adb/modules/*|/data/adb/modules_update/*) D_ORIGIN=module;; /data/user/*|/data/data/*) D_ORIGIN=app-private;; esac
  # Generic 'core' is only evidence when it names Hetu's own executable.
  [ "$D_NAME" != core ] || [ "$D_ORIGIN" = hetu ] || continue
  D_BINARY=${D_EXE##*/}
  case "$D_BINARY" in core|mihomo|mihomo-smart|clash|clash.meta|sing-box|singbox|xray|v2ray|hysteria|hysteria2) ;; *) D_BINARY=unknown;; esac
  D_UID=unknown; D_PARENT=unknown
  while read -r D_KEY D_REAL D_EFFECTIVE D_REST; do
    case "$D_KEY" in Uid:) D_UID=$D_EFFECTIVE;; PPid:) D_PARENT=$D_REAL;; esac
  done < "$D_PATH/status" 2>/dev/null
  case "$D_UID" in ''|*[!0-9]*) D_UID=unknown;; esac
  case "$D_PARENT" in ''|*[!0-9]*) D_PARENT=unknown;; esac
  D_PARENT_NAME=unknown
  if [ "$D_PARENT" != unknown ]; then
    IFS= read -r D_PARENT_NAME < "$D_PROC/$D_PARENT/comm" 2>/dev/null || D_PARENT_NAME=unknown
    case "$D_PARENT_NAME" in ''|*[!A-Za-z0-9_.:-]*) D_PARENT_NAME=unknown;; esac
    [ "${#D_PARENT_NAME}" -le 64 ] || D_PARENT_NAME=unknown
  fi
  D_AFTER=$(d_ticks "$D_PATH") || D_AFTER=
  D_EXE_AFTER=$(readlink "$D_PATH/exe" 2>/dev/null) || D_EXE_AFTER=
  if [ "$D_BEFORE" != "$D_AFTER" ] || [ "$D_EXE" != "$D_EXE_AFTER" ]; then D_MISSING=$((D_MISSING + 1)); continue; fi
  D_FOUND=$((D_FOUND + 1))
  if [ "$D_FOUND" -gt 16 ]; then D_LIMIT=1; break; fi
  printf 'process\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "${D_PATH##*/}" "$D_BEFORE" "$D_UID" "$D_PARENT" "$D_NAME" "$D_BINARY" "$D_ORIGIN" "$D_PARENT_NAME"
done
D_TUN=0
for D_PATH in "$D_NET"/*; do
  [ -f "$D_PATH/tun_flags" ] || continue
  D_INTERFACE=${D_PATH##*/}
  case "$D_INTERFACE" in ''|*[!A-Za-z0-9_.:-]*) continue;; esac
  [ "${#D_INTERFACE}" -le 32 ] || continue
  D_TUN=$((D_TUN + 1)); [ "$D_TUN" -le 16 ] || { D_LIMIT=1; break; }
  printf 'tun\t%s\n' "$D_INTERFACE"
done
printf 'complete\t%s\t%s\t%s\n' "$D_SCANNED" "$D_MISSING" "$D_LIMIT"
