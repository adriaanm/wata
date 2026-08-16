# 0054 — a battery row on the kid panel; the dev menu de-duped

Status: accepted (owner follow-up to plan 0053, 2026-08-16)

## The problem

The kid panel answers "is it working" questions except the one a kid
actually asks on a walkie-talkie: how much battery is left. And the
developer panel still carries rows the kid panel now owns — brightness,
notify, and the two radio toggles the data tri-state replaced — so the
same preference has two doors, one of them fifteen rows deep.

## The decision

- **Kid panel row 4: Battery** — read-only, before the hidden
  development row. The percentage from the same `DiagSnap` refresh the
  data row uses (`n/a` off-device, keeping the goldens deterministic).
  Help rows explain it; OK does nothing.
- **Dev menu drops BRIGHTNESS, NOTIFY, WIFI_TOGGLE, DATA_TOGGLE.**
  Remaining order: Audio Echo, Screen off, Disconnect, [Enroll],
  Device Info, IP, Cell info, Net test, Power off, Reboot bootloader,
  Reboot EDL. Item IDS stay stable; only the position→id mapping
  shrinks. The radio controls' granular form (independent wifi/data
  toggles) is retired in favor of the kid tri-state — the combinations
  it cannot express (both radios up at once) were never an on-device
  need.
- `Shell.syncPrefs` STAYS: the dev applet's screen-timeout save writes
  the whole `FbPrefs` record, so it must keep a live brightness mirror
  even with no brightness row (and the kid save needs the timeout
  mirror the same way).

## What changes

`applets.scala` (kid row + dev menu mapping), the dev-walk UI scripts
(navigation counts shrink; brightness/notify dev checkpoints retire —
the kid goldens carry those pins now; the settings-restored scenario
asserts brightness restore on the KID panel, timeout on the dev one),
goldens regenerated, `docs/design/wata-fb.md` updated.

## Verification

`just ci`, `just mac-build`, fb-ui suite green, frames eyeballed.

## Out of scope

Charging state on the battery row (the diag snapshot has no charging
field; add one only if the row proves insufficient).
