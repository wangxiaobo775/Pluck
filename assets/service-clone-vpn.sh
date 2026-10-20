#!/system/bin/sh
# KernelSU runs this after late load. Starts the VPN watcher once; the
# watcher writes its own pid file.
NAME=clone-vpn
. /data/adb/clone-lib.sh
running "$STATE/$NAME.pid" clone-vpn.sh && exit 0
setsid /system/bin/sh /data/adb/clone-vpn.sh --watch </dev/null >>"$STATE/$NAME.log" 2>&1 &
