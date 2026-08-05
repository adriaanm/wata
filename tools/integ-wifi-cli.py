#!/usr/bin/env python3
"""The integ harness's fake wpa_cli (plan 0020): what `$WATA_WIFI_CLI` points
at so the wifi-cmd scenario can run the device's whole scan path on a host.

Answers the two invocations `WifiCmd` makes: `scan` (an OK), and
`scan_results` (the tab-separated table wpa_cli prints — a header row, then
bssid/frequency/signal/flags/ssid). The canned table deliberately carries a
duplicate ssid across two bands (the parser must keep the stronger row), an
open network, and a hidden (empty-ssid) row the parser must drop.
"""

import sys

RESULTS = "\t".join(["bssid / frequency / signal level / flags / ssid"]) + "\n" + "\n".join([
    "\t".join(["aa:bb:cc:dd:ee:01", "2412", "-55", "[WPA2-PSK-CCMP][ESS]", "HomeNet"]),
    "\t".join(["aa:bb:cc:dd:ee:02", "5180", "-48", "[WPA2-PSK-CCMP][ESS]", "HomeNet"]),
    "\t".join(["bb:cc:dd:ee:ff:01", "2437", "-70", "[ESS]", "CafeOpen"]),
    "\t".join(["cc:dd:ee:ff:aa:01", "2452", "-80", "[WPA2-PSK-CCMP][ESS]", ""]),
]) + "\n"


def main():
    verb = sys.argv[1] if len(sys.argv) > 1 else ""
    if verb == "scan":
        print("OK")
    elif verb == "scan_results":
        sys.stdout.write(RESULTS)
    else:
        sys.exit(f"integ-wifi-cli: unexpected verb {verb!r}")


if __name__ == "__main__":
    main()
