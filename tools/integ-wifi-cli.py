#!/usr/bin/env python3
"""The integ harness's fake wpa_cli (plans 0020/0031): what `$WATA_WIFI_CLI`
points at so the wifi-cmd scenario can run the device's whole scan / join /
off path on a host.

Verbs, mirroring the invocations `WifiCmd` makes:

- `scan` — an OK (the trigger; results are canned, so the settle poll's
  baseline never moves and the harness runs with `WATA_WIFI_SETTLE_MS=0`).
- `scan_results` — the tab-separated table wpa_cli prints (header row, then
  bssid/frequency/signal/flags/ssid). The canned table deliberately carries
  a duplicate ssid across two bands (the parser must keep the stronger
  row), an open network, and a hidden (empty-ssid) row the parser must drop.
- `status` — the association state: `ssid=<x>` + `wpa_state=COMPLETED` when
  the fake is "associated" (the state file `$WATA_WIFI_STATE`, written by
  the fake join helper), else `wpa_state=SCANNING`. This is what the join
  verdict probes; the `bssid=` line is there so a naive substring match on
  `ssid=` would hit it — the parser must match per line.
- `disable_network` / `enable_network` / `reassociate` / `reconfigure` —
  OKs; the wifi_off scenario asserts them out of the invocation log.

Every invocation appends its argv to `$WATA_WIFI_CLI_LOG` (when set) — the
scenario's window into what the poller actually ran, e.g. that the
auto-restore timer fired `enable_network`.
"""

import os
import sys

RESULTS = "\t".join(["bssid / frequency / signal level / flags / ssid"]) + "\n" + "\n".join([
    "\t".join(["aa:bb:cc:dd:ee:01", "2412", "-55", "[WPA2-PSK-CCMP][ESS]", "HomeNet"]),
    "\t".join(["aa:bb:cc:dd:ee:02", "5180", "-48", "[WPA2-PSK-CCMP][ESS]", "HomeNet"]),
    "\t".join(["bb:cc:dd:ee:ff:01", "2437", "-70", "[ESS]", "CafeOpen"]),
    "\t".join(["cc:dd:ee:ff:aa:01", "2452", "-80", "[WPA2-PSK-CCMP][ESS]", ""]),
]) + "\n"


def log(argv):
    path = os.environ.get("WATA_WIFI_CLI_LOG")
    if path:
        with open(path, "a") as f:
            f.write(" ".join(argv) + "\n")


def status_text():
    state = os.environ.get("WATA_WIFI_STATE")
    ssid = ""
    if state and os.path.exists(state):
        with open(state) as f:
            ssid = f.read().strip()
    if ssid:
        return f"bssid=aa:bb:cc:dd:ee:02\nssid={ssid}\nwpa_state=COMPLETED\n"
    return "wpa_state=SCANNING\n"


def main():
    verb = sys.argv[1] if len(sys.argv) > 1 else ""
    log(sys.argv[1:])
    if verb == "scan":
        print("OK")
    elif verb == "scan_results":
        sys.stdout.write(RESULTS)
    elif verb == "status":
        sys.stdout.write(status_text())
    elif verb in ("disable_network", "enable_network", "reassociate", "reconfigure"):
        print("OK")
    else:
        sys.exit(f"integ-wifi-cli: unexpected verb {verb!r}")


if __name__ == "__main__":
    main()
