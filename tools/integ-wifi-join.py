#!/usr/bin/env python3
"""The integ harness's fake wifi-join helper (plans 0020/0031): what
`$WATA_WIFI_JOIN` points at so the wifi-cmd scenario can prove the join
contract without a device — the ssid arrives as the ONE argv argument, the
PSK arrives on STDIN (never argv), and the capture file lets the scenario
assert both, including that no argv carried the secret.

Exit 0 means CONFIG APPLIED, mirroring the real helper's contract; whether
the join *worked* is the association state the poller then probes. This
fake "associates" only `HomeNet` — for that ssid it writes the ssid into
`$WATA_WIFI_STATE`, which the fake wpa_cli's `status` verb reads — so a
join to any other ssid applies cleanly but never associates, driving the
poller's auth-failed verdict without a timeout-length wait
(`WATA_WIFI_ASSOC_MS` is set short by the harness).

Writes `$WATA_WIFI_CAPTURE` as JSON {ssid, psk, argv} and prints the one
detail line a config-apply reports.
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
    state = os.environ.get("WATA_WIFI_STATE")
    if state and ssid == "HomeNet":
        with open(state, "w") as f:
            f.write(ssid)
    print(f"wifi \"{ssid}\" saved")


if __name__ == "__main__":
    main()
