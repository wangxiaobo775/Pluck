# Shared by the clone scripts in /data/adb. Sourced, not run.
# Android /system/bin/sh (mksh); runs in the Android mount namespace.
#
# Set NAME before sourcing. Logs and pid files go to $STATE/$NAME.{log,pid}.

PATH=/system/bin:/system/xbin
STATE=/data/local/tmp/clone
LOG_MAX=262144
mkdir -p "$STATE"

log() {
  f=$STATE/$NAME.log
  if [ -f "$f" ] && [ "$(stat -c %s "$f" 2>/dev/null || echo 0)" -gt "$LOG_MAX" ]; then
    mv -f "$f" "$f.1"
  fi
  echo "$(date '+%m-%d %H:%M:%S') $*" >> "$f"
}

# running PIDFILE PATTERN: a live process whose command line contains
# PATTERN owns PIDFILE.
running() {
  pid=$(cat "$1" 2>/dev/null)
  case $pid in ''|*[!0-9]*) return 1 ;; esac
  tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -q "$2"
}

# Ids of all clone profiles (type profile.CLONE), one per line.
clone_users() {
  cmd user list -v 2>/dev/null \
    | sed -n 's/.* id=\([0-9][0-9]*\),.* type=profile\.CLONE,.*/\1/p'
}

# uid range of one user, as iptables/ndc want it: 1500000-1599999
uid_range() {
  echo "$(($1 * 100000))-$(($1 * 100000 + 99999))"
}
