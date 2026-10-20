#!/usr/bin/env python3
"""Check the clone VPN kill switch without touching the active VPN.

Opens TCP connections whose socket owner is a given uid (via setfsuid, so
root keeps CAP_NET_RAW for SO_BINDTODEVICE):

  clone uid, normal routing      -> goes via tun0, must connect
  clone uid, forced out wlan0    -> what happens when the VPN is down, must be refused
  main-user uid, forced out wlan0 -> must connect (no collateral)

Use system app ids (1000 in user 0, 1501000 in clone 15): Android's own
per-app network firewall (standby, deep sleep) never blocks those, so a
timeout cannot be mistaken for the kill switch. The destination must be
reachable without the VPN (223.5.5.5, AliDNS).

  python3 test-killswitch.py [clone_uid main_uid wan_iface]
"""
import ctypes
import os
import re
import socket
import subprocess
import sys

libc = ctypes.CDLL(None, use_errno=True)
DEST = ("223.5.5.5", 443)


def default_clone_uid():
    try:
        out = subprocess.check_output(["/system/bin/cmd", "user", "list", "-v"], text=True)
        m = re.search(r"\bid=(\d+),.*type=profile\.CLONE,", out)
        if m:
            return str(int(m.group(1)) * 100000 + 1000)
    except Exception:
        pass
    return "1501000"


def default_wan_iface():
    try:
        out = subprocess.check_output(["/system/bin/ip", "-4", "route", "show", "table", "all"], text=True)
        for m in re.finditer(r"^default via \S+ dev (\S+)", out, re.MULTILINE):
            dev = m.group(1)
            if not dev.startswith(("tun", "lo", "dummy")):
                return dev
    except Exception:
        pass
    return "wlan0"


def attempt(uid, iface=None):
    pid = os.fork()
    if pid == 0:
        libc.setfsuid(uid)
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        if iface:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_BINDTODEVICE, iface.encode())
        s.settimeout(6)
        try:
            s.connect(DEST)
            code = 0
        except ConnectionRefusedError:
            code = 1
        except OSError:
            code = 2
        os._exit(code)
    _, status = os.waitpid(pid, 0)
    return {0: "connected", 1: "refused", 2: "error/timeout"}[os.WEXITSTATUS(status)]


args = sys.argv[1:] or [default_clone_uid(), "1000", default_wan_iface()]
clone_uid, main_uid, wan = int(args[0]), int(args[1]), args[2]
cases = [
    ("clone uid via VPN routing", attempt(clone_uid), "connected"),
    ("clone uid forced out " + wan, attempt(clone_uid, wan), "refused"),
    ("main uid forced out " + wan, attempt(main_uid, wan), "connected"),
]
ok = True
for name, got, want in cases:
    ok &= got == want
    print(f"{'PASS' if got == want else 'FAIL'}  {name}: {got} (want {want})")
sys.exit(0 if ok else 1)
