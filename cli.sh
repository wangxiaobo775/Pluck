#!/bin/bash
# Maintenance commands of the 应用分身 backend, from a root shell:
#
#   ./cli.sh trim [--dry-run] [USER...]   default: every clone
#   ./cli.sh untrim USER...
#   ./cli.sh vpn [on|off|status]
#
# Runs the installed apk's RootMain (the same code the page runs) in
# app_process. APK=path overrides, e.g. APK=build/pluck.apk.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")" && pwd)
if [ -z "${APK:-}" ]; then
  for pkg in io.github.wangxiaobo775.pluck io.github.wangxiaobo775.cloneprofiles io.github.icepony.alwayscreateuser com.local.cloneui; do
    APK=$({ /system/bin/pm path --user 0 "$pkg" 2>/dev/null || true; } | sed -n 's/^package://p' | head -n 1)
    [ -n "$APK" ] && break
  done
fi
if [ -z "${APK:-}" ] && [ -f "$ROOT/build/pluck.apk" ]; then
  APK="$ROOT/build/pluck.apk"
fi
if [ -z "${APK:-}" ]; then
  echo "pluck is not installed and build/pluck.apk was not found" >&2
  exit 1
fi
exec /system/bin/app_process -Djava.class.path="$APK" /system/bin \
  --nice-name=clone-ui-cli com.local.cloneui.RootMain "$@"
