#!/bin/bash
# Deploy the unified APK in place without triggering a system_server crash.
#
#   ./deploy.sh                    deploy build/pluck.apk
#   ./deploy.sh deployed/<file>    roll back to a saved copy
#
# Never `pm install -r` the Xposed module while it is loaded in system_server.
# The new file is written next to base.apk and renamed over it, so the
# running system_server keeps its mapping of the old inode.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")" && pwd)
NEW=${1:-$ROOT/build/pluck.apk}

[ -f "$NEW" ] || { echo "missing $NEW (run ./build.sh)"; exit 1; }

PKG=$(aapt dump badging "$NEW" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n 1)
[ -n "$PKG" ] || { echo "cannot read package name from $NEW"; exit 1; }

replace_in_place() {
  local apk=$1 pkg=$2 base=$3
  local installed_cert new_cert saved tmp
  installed_cert=$(apksigner verify --print-certs "$base" | sed -n 's/.*SHA-256 digest: \([0-9a-f]*\).*/\1/p' | head -n 1)
  new_cert=$(apksigner verify --print-certs "$apk" | sed -n 's/.*SHA-256 digest: \([0-9a-f]*\).*/\1/p' | head -n 1)
  [ -n "$installed_cert" ] && [ "$installed_cert" = "$new_cert" ] \
    || { echo "refusing $apk: signing cert ($new_cert) does not match installed $pkg ($installed_cert)"; exit 1; }

  if cmp -s "$apk" "$base"; then
    echo "already in place: $base"
    return 0
  fi

  mkdir -p "$ROOT/deployed"
  saved=$ROOT/deployed/$(date +%Y%m%d-%H%M%S)-${pkg}-base.apk
  cp -p "$base" "$saved"

  tmp=$base.new
  cp "$apk" "$tmp"
  chown 1000:1000 "$tmp"
  chmod 0644 "$tmp"
  chcon u:object_r:apk_data_file:s0 "$tmp"
  mv -f "$tmp" "$base"
  sync
  cmp "$apk" "$base"

  echo "previous apk saved: $saved"
  echo "in place:           $base"
}

BASE=$({ /system/bin/pm path --user 0 "$PKG" 2>/dev/null || true; } | sed -n 's/^package://p' | head -n 1)
if { [ -z "$BASE" ] || [ ! -f "$BASE" ]; } && [ $# -eq 0 ]; then
  UPDATED_LEGACY=0
  for LPKG in io.github.icepony.alwayscreateuser com.local.cloneui; do
    LBASE=$({ /system/bin/pm path --user 0 "$LPKG" 2>/dev/null || true; } | sed -n 's/^package://p' | head -n 1)
    if [ -n "$LBASE" ] && [ -f "$LBASE" ]; then
      echo "Updating installed legacy package $LPKG in place..."
      PKG=$LPKG "$ROOT/build.sh" >/dev/null
      replace_in_place "$ROOT/build/pluck.apk" "$LPKG" "$LBASE"
      if [ "$LPKG" = "com.local.cloneui" ]; then
        /system/bin/am force-stop com.local.cloneui 2>/dev/null || true
      fi
      UPDATED_LEGACY=1
    fi
  done
  if [ "$UPDATED_LEGACY" -eq 1 ]; then
    "$ROOT/build.sh" >/dev/null
    echo "activate Xposed hooks: KernelSU manager -> soft reboot, then:"
    echo "  grep -a 'SysGuard' \$(ls -t /data/adb/lspd/log/modules_*.log | head -1) | tail -30"
    exit 0
  fi
fi
if [ -z "$BASE" ] || [ ! -f "$BASE" ]; then
  echo "$PKG is not installed yet; installing for user 0..."
  cp "$NEW" /data/local/tmp/pluck.apk
  chmod 0644 /data/local/tmp/pluck.apk
  /system/bin/su 2000 -c 'PATH=/system/bin:/system/xbin; /system/bin/pm install -r --user 0 /data/local/tmp/pluck.apk'
  rm -f /data/local/tmp/pluck.apk
  exit 0
fi

replace_in_place "$NEW" "$PKG" "$BASE"
echo "activate Xposed hooks: KernelSU manager -> soft reboot, then:"
echo "  grep -a 'SysGuard' \$(ls -t /data/adb/lspd/log/modules_*.log | head -1) | tail -30"
