#!/system/bin/sh
# Per-clone VPN routing and kill switch (interface tun*).
#
#   clone-vpn.sh          apply once for enabled clones in /data/adb/clone-vpn.users
#   clone-vpn.sh --watch  apply, then keep it applied (service.d)
#   clone-vpn.sh --off    remove the kill switch and stop watcher
#
# Optional overrides in /data/adb/clone-vpn.conf or environment:
#   VPN_PKG=com.example.vpn  restrict to a specific VPN package (default: any connected VPN)
#   TUN=tun0                 TUN interface name (default: tun0)
#
# Enabled clone user IDs are stored in /data/adb/clone-vpn.users (space- or
# newline-separated). Only those clones are routed into the main user's VPN
# and guarded by the kill switch; clones with VPN off are removed from the
# VPN routing table so they connect directly.
NAME=clone-vpn
. /data/adb/clone-lib.sh
[ -f /data/adb/clone-vpn.conf ] && . /data/adb/clone-vpn.conf

VPN_PKG=${VPN_PKG:-}
TUN=${TUN:-tun0}
CHAIN=clone_vpn_guard
USERS_FILE=/data/adb/clone-vpn.users
USERS_EVERY=12

# Enabled clone users (intersection of live clone_users and USERS_FILE).
enabled_users() {
  all=$1
  if [ ! -f "$USERS_FILE" ]; then
    echo "$all"
    return
  fi
  want=$(tr '\n,' '  ' < "$USERS_FILE" 2>/dev/null)
  out=
  for u in $all; do
    case " $want " in
      *" $u "*) out="$out $u" ;;
    esac
  done
  echo ${out# }
}

guard() {
  if [ $# -eq 0 ]; then
    unguard
    return
  fi
  for ipt in iptables ip6tables; do
    $ipt -w -N $CHAIN 2>/dev/null
    $ipt -w -F $CHAIN
    $ipt -w -A $CHAIN -o lo -j RETURN
    $ipt -w -A $CHAIN -o tun+ -j RETURN
    for u in "$@"; do
      $ipt -w -A $CHAIN -m owner --uid-owner "$(uid_range "$u")" -j REJECT
    done
    $ipt -w -C OUTPUT -j $CHAIN 2>/dev/null || $ipt -w -I OUTPUT 1 -j $CHAIN
  done
  log "guard: clone users $* may only use tun*/lo"
}

guard_hooked() {
  iptables -w -C OUTPUT -j $CHAIN 2>/dev/null && ip6tables -w -C OUTPUT -j $CHAIN 2>/dev/null
}

unguard() {
  for ipt in iptables ip6tables; do
    while $ipt -w -D OUTPUT -j $CHAIN 2>/dev/null; do :; done
    $ipt -w -F $CHAIN 2>/dev/null
    $ipt -w -X $CHAIN 2>/dev/null
  done
  log "guard removed"
}

vpn_netid() {
  if [ -n "$VPN_PKG" ]; then
    dumpsys connectivity | sed -n "s/.*network{\([0-9][0-9]*\)}.*VPN:$VPN_PKG.*/\1/p" | head -n 1
  else
    dumpsys connectivity | sed -n 's/.*network{\([0-9][0-9]*\)}.*ni{VPN CONNECTED.*/\1/p' | head -n 1
  fi
}

# Sync VPN routing table: add enabled clones, remove disabled clones.
route() {
  all_clones=$1
  shift
  enabled="$*"
  ip link show "$TUN" 2>/dev/null | grep -q UP || return 0
  rules=$(/system/bin/ip rule show)
  missing=
  extra=
  for u in $enabled; do
    r=$(uid_range "$u")
    case $rules in *"uidrange $r lookup $TUN"*) ;; *) missing="$missing $r" ;; esac
  done
  for u in $all_clones; do
    case " $enabled " in
      *" $u "*) ;;
      *)
        r=$(uid_range "$u")
        case $rules in *"uidrange $r lookup $TUN"*) extra="$extra $r" ;; esac
        ;;
    esac
  done
  [ -n "$missing" ] || [ -n "$extra" ] || return 0
  id=$(vpn_netid)
  [ -n "$id" ] || return 0
  for r in $missing; do
    ndc network users add "$id" "$r" >/dev/null 2>&1 && log "route: added $r to net $id"
  done
  for r in $extra; do
    while /system/bin/ip rule show | grep -q "uidrange $r lookup $TUN"; do
      ndc network users remove "$id" "$r" >/dev/null 2>&1 || break
    done
    log "route: removed $r from net $id"
  done
}

case $1 in
  --off)
    if running "$STATE/$NAME.pid" clone-vpn.sh; then
      kill "$pid" && log "stopped watcher ($pid)"
      rm -f "$STATE/$NAME.pid"
    fi
    unguard
    all=$(clone_users)
    [ -n "$all" ] && route "$all"
    exit 0
    ;;
esac

all=$(clone_users)
while [ -z "$all" ] && [ "$1" = "--watch" ]; do
  sleep 2
  all=$(clone_users)
done
enabled=$(enabled_users "$all")
guard $enabled
route "$all" $enabled
[ "$1" = "--watch" ] || exit 0

echo $$ > "$STATE/$NAME.pid"
log "watching (pid $$)"
round=0
while sleep 5; do
  round=$((round + 1))
  if [ $((round % USERS_EVERY)) -eq 0 ]; then
    now_all=$(clone_users)
    [ -n "$now_all" ] && all=$now_all
  fi
  now_enabled=$(enabled_users "$all")
  if [ "$now_enabled" != "$enabled" ]; then
    enabled=$now_enabled
    guard $enabled
  elif [ -n "$enabled" ]; then
    guard_hooked || guard $enabled
  fi
  route "$all" $enabled
done
