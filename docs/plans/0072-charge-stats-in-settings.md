# 0072 — charge stats on the settings screens

status: done

Verified on hardware 2026-08-22 (deployed via `fb-deploy install`, walked
remotely by evdev injection, read with `fb-shot`, handset on the charger):
the kid battery row's help line reads `now: 90% chg 4.19V` and the
developer panel's Device Info detail reads `Bat:90% chg 4.18V` /
`Up:0h25m Mem:324M` — status verb and pack voltage live on both surfaces.
The `bat`/`usb` verbs were not observed (the handset stayed plugged and
charging); the verb choice is the pure `chargeText`, negative-probed at
the kid-battery golden.

## Problem

Both settings surfaces show only the battery percentage. Percentage alone
hides two things the owner just paid for not knowing: whether the charger
is actually running, and the pack voltage (the boot-time BMS percentage is
inflated when the charger is attached at boot, so the voltage is the
honest number). The concrete failure mode — device docked, VBUS present,
charger FSM idle, handset discharging invisibly — was diagnosed 2026-08-22
(bq268-alpine `docs/planning/charging-telemetry.md`); FB-CHARGE-ANOMALY-GLYPH
queues the status-bar glyph for it, this plan gives the settings screens
the raw facts.

## Decision

One compact stat string, read with the rest of the `DiagSnap` on the same
five-second cadence: a three-letter verb plus the pack voltage.

- `chg 4.19V` — `battery/status` is `Charging` (or `Full`): the charger runs.
- `bat 3.82V` — on battery, nothing plugged.
- `usb 3.82V` — `usb/online` is 1 but the charger is NOT running: the
  docked-but-idle anomaly, visible at a glance right when the handset is
  cradled. (No 3-minute debounce here — a settings row states the instant
  reading; the debounce belongs to the glyph item.)

Sources: `/sys/class/power_supply/battery/status`, `.../battery/voltage_now`
(microvolts; rendered via integer centivolts, no floats), and
`/sys/class/power_supply/usb/online`. All reads gated on `Diag.onDevice()`
like every other info row; the stat is `""` off-device, so every golden
frame is unchanged — the new text exists only where the hardware does.

## What changes

- `wata-fb/src/main/scala/diag.scala` — `chargeStat()` + the pure
  `chargeText`/`voltText` it renders through.
- `wata-fb/src/main/scala/applets.scala` — `DiagSnap` grows a `chg` field;
  the developer panel's Device Info detail pairs it with the percentage
  (`Bat:78% chg 4.19V`, uptime joining memory on the second line — only on
  the device, where the line exists); the kid battery row's help line
  appends it (`now: 78% chg 4.19V`).
- `docs/design/wata-fb.md` — the Device Info and battery-row descriptions.

## Verification

`just fb-smoke golden fb-ui-tests mac-build-check` (off-device the stat is
empty, so the goldens double as the no-regression probe), then deploy and
read the two screens on the charging handset (`just fb-shot` + evdev key
injection per the root learnings log).

## Out of scope

The status-bar glyph and its sustained-anomaly debounce
(FB-CHARGE-ANOMALY-GLYPH stays queued), any charging telemetry/logging
(rootfs work), current/temperature readings.
