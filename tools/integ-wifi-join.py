#!/usr/bin/env python3
"""The integ harness's fake wifi-join helper (plan 0020): what
`$WATA_WIFI_JOIN` points at so the wifi-cmd scenario can prove the join
contract without a device — the ssid arrives as the ONE argv argument, the
PSK arrives on STDIN (never argv), and the capture file lets the scenario
assert both, including that no argv carried the secret.

Writes `$WATA_WIFI_CAPTURE` as JSON {ssid, psk, argv} and prints the one
detail line the device reports back.
"""

import json
import os
import sys


def main():
    ssid = sys.argv[1] if len(sys.argv) > 1 else ""
    psk = sys.stdin.read()
    capture = os.environ.get("WATA_WIFI_CAPTURE")
    if capture:
        with open(capture, "w") as f:
            json.dump({"ssid": ssid, "psk": psk, "argv": sys.argv[1:]}, f)
    print(f"joined {ssid}")


if __name__ == "__main__":
    main()
