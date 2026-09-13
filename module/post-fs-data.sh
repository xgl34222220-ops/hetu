#!/system/bin/sh
MODDIR=${0%/*}
# No network or rules parsing on boot. Restore the last validated generation only.
sh "$MODDIR/bin/boot-runner.sh" boot
