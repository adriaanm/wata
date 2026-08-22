# 0073 — the charge-anomaly glyph

status: done

Verified in ci (charge-anomaly uitest scenario: debounce negative,
alert frame, recovery) and negatively on hardware 2026-08-22 (deployed
via `fb-deploy install`; handset on USB, `battery/status` = Charging;
`fb-shot` immediately after restart AND after 4+ minutes plugged shows
a clean header — zero red pixels in the mark's slot). The
POSITIVE hardware state — plugged, charger idle for 3+ minutes —
requires physically degrading the cradle contact and stays queued as a
hardware verification for the next time the anomaly occurs naturally
(or is provoked by hand at the bench).

## Problem

A failed cradle contact discharges the handset invisibly: VBUS present,
charger FSM idle, zero fastchg IRQs — the device sat docked 2+ hours
discharging (2026-08-22; rootfs telemetry spec in bq268-alpine
`docs/planning/charging-telemetry.md`). Plan 0072 put the instant
reading on the settings screens, but nobody opens settings at cradle
time. The failure must be visible on the main screen, where a kid (or
the owner) glances as the handset is docked.

## Decision

A CHARGE-ANOMALY mark in the header, beside the connectivity element:
the plug glyph plus an `X`, both black on a red field (inverse video is
the panel's alert treatment — nothing else in the header is inverse).
It appears only when `/sys/class/power_supply/usb/online` reads 1 AND
`battery/status` is neither `Charging` nor `Full`, SUSTAINED for three
minutes. The debounce is the point: a BC1.2 renegotiation or a brief
bounce at dock time must not flash it. Percentage is never consulted —
the boot-time BMS percentage is inflated when booted on a charger.

Mechanics, all in the existing idioms:

- `Diag.chargeAnomaly()` reads the same two sysfs nodes plan 0072's
  `chargeStat` reads, gated on `onDevice()` (false on every host, so
  off-device rendering is unchanged and the existing goldens hold). An
  empty `battery/status` does not alarm — no evidence of not-charging.
- `ChargeStatus` (netstatus.scala, beside `NetStatus` and shaped like
  it): polled once per frame by `Ui.frameStep`, re-reads every 150
  frames (~5s, the diagnostics cadence) and counts frames since the
  last clean read. Active at 5400 frames (~3min at 30fps). Frame counts
  are the codebase's timer idiom and the right one here: the wall clock
  STEPS on this device when NTP lands.
- `WataLogic.netView` draws the mark in a fixed two-cell slot left of
  the reconnect-dots slot — fixed so the blink never reflows it.
- Test seam, same shape as `netpipe`/`conn`: a `charge bad|ok|auto`
  uitest directive forces the READ (the debounce still runs — that is
  what the scenario tests), a `chargebad` probe reports the active
  flag. Scenario `charge-anomaly`: force bad, burn ~4500 frames in
  screensaver-safe chunks, assert NOT active (the debounce negative),
  burn past 5400, assert active + golden, force ok, assert cleared.

## Changes

- `wata-fb/src/main/scala/diag.scala` — `chargeAnomaly()` + pure `anomalyPair`.
- `wata-fb/src/main/scala/netstatus.scala` — `ChargeStatus` (shared into
  wata-mac by symlink; never active there — nothing polls it).
- `wata-fb/src/main/scala/display.scala` — `ICON_PLUG` (0x91, hand-drawn).
- `wata-fb/src/main/scala/applets.scala` — the mark in `netView`.
- `wata-fb/src/main/scala/ui.scala` — the per-frame poll + session reset.
- `wata-fb/src/main/scala/uiscript.scala` — directive + probe.
- `wata-mac/src/main/scala/stubs.scala` — `Diag.chargeAnomaly()` no-op.
- `tools/fb-ui-scripts/alice-charge.txt`, scenario + goldens.

wata-ios and wata-watch carry their own applets/netstatus copies and do
not take this feature: the anomaly is a handset-cradle fact. Out of
scope: any rootfs-side alarm (that is bq268-alpine's telemetry spec),
and alarming on percentage, ever.

## Verification

`just fb-smoke golden fb-ui-tests mac-build-check ios-build-check`;
hardware negative control via `fb-deploy install` + `fb-shot` with the
handset charging (no glyph). The debounce negative was seen to FAIL
first (an `expect chargebad 1` before the threshold fails; the golden
diff shows the mark) before the green run was believed.
