#!/system/bin/sh
# Clone users and the main user's VPN (interface tun*).
#
#   clone-vpn.sh          apply once
#   clone-vpn.sh --watch  apply, then keep it applied (service.d)
#   clone-vpn.sh --off    remove the kill switch (debugging only)
#
# Optional overrides in /data/adb/clone-vpn.conf or environment:
#   VPN_PKG=com.example.vpn  restrict to a specific VPN package (default: any connected VPN)
#   TUN=tun0                 TUN interface name (default: tun0)
#
# 1. Kill switch. Traffic from clone uids may leave only through tun* or lo.
#    With the VPN down, or in the seconds before routing catches up, clone
#    apps get "connection refused" instead of leaking through the real IP.
# 2. Routing fallback. Put the clone uid ranges into the VPN network with
#    ndc. SysGuard's vpn-clones hook already does this inside system_server
#    when the VPN connects; this covers SysGuard not being loaded.
#
# Clone users are read from `cmd user list -v`, so a new clone is picked up
# within a minute. Secure Folder (user 150) is a private profile, not a
# clone, and is left alone.
NAME=clone-vpn
. /data/adb/clone-lib.sh
[ -f /data/adb/clone-vpn.conf ] && . /data/adb/clone-vpn.conf

VPN_PKG=${VPN_PKG:-}
TUN=${TUN:-tun0}
CHAIN=clone_vpn_guard
USERS_EVERY=12   # refresh the clone list every 12 rounds (1 minute)

# (Re)build the guard chain for the given clone users, hook it into OUTPUT.
guard() {
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

# Something (netd restart, firewall reload) may drop our jump. Put it back.
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

# Add clone ranges missing from the VPN's routing rules. Steady state is one
# `ip rule show`; dumpsys only runs when something is missing.
route() {
  ip link show "$TUN" 2>/dev/null | grep -q UP || return 0
  rules=$(ip rule show)
  missing=
  for u in "$@"; do
    r=$(uid_range "$u")
    case $rules in *"uidrange $r lookup $TUN"*) ;; *) missing="$missing $r" ;; esac
  done
  [ -n "$missing" ] || return 0
  id=$(vpn_netid)
  [ -n "$id" ] || return 0
  for r in $missing; do
    ndc network users add "$id" "$r" >/dev/null 2>&1 && log "route: added $r to net $id"
  done
}

case $1 in
  --off)
    # Stop the watcher first, or it re-adds the chain within 5 seconds.
    # service.d starts it again on the next boot.
    if running "$STATE/$NAME.pid" clone-vpn.sh; then
      kill "$pid" && log "stopped watcher ($pid)"
      rm -f "$STATE/$NAME.pid"
    fi
    unguard
    exit 0
    ;;
esac

users=$(clone_users)
# Early in boot `cmd user` may not answer yet. An empty list would build a
# guard that blocks nothing, so wait for it in watch mode.
while [ -z "$users" ] && [ "$1" = "--watch" ]; do
  sleep 2
  users=$(clone_users)
done
[ -n "$users" ] || { log "no clone users found"; exit 1; }
guard $users
route $users
[ "$1" = "--watch" ] || exit 0

echo $$ > "$STATE/$NAME.pid"
log "watching (pid $$)"
round=0
while sleep 5; do
  round=$((round + 1))
  if [ $((round % USERS_EVERY)) -eq 0 ]; then
    now=$(clone_users)
    if [ -n "$now" ] && [ "$now" != "$users" ]; then
      users=$now
      guard $users
    fi
  fi
  guard_hooked || guard $users
  route $users
done
