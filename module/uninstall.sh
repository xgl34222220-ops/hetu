#!/system/bin/sh
MODDIR=${0%/*}
# Unmount only this module's mount. Keep user rule snapshots for a reinstall.
sh "$MODDIR/bin/bichen" uninstall >/dev/null 2>&1
