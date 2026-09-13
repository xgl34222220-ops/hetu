#!/system/bin/sh
MODDIR=${0%/*}
# One late check/restore after the root framework's module mounts; no resident retry loop.
sh "$MODDIR/bin/boot-runner.sh" boot-check
