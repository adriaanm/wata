# 0053 — kid settings: three rows and a hidden developer door

Status: done (field-test feedback 2026-08-16: "the settings applet
is too convoluted — this device is meant for use by kids now")

## The problem

The settings applet is 15 rows deep: echo test, brightness, screen
timeout, disconnect, device info, IP, cell info, net test, two radio
toggles behind confirm latches, three power actions, enroll, notify.
That surface grew out of system-menu absorption and device bring-up —
it is a developer's panel, and it is what a kid lands on one dot-press
from the talk screen.

## The decision

Split the applet in two:

- **Settings (the kid one, in the dot rotation where settings sits
  today)** — exactly three rows: **notify** (chime/quiet), **bright**
  (the backlight steps), **data** (off / wifi / cell). The bottom rows
  of the screen (the existing `DETAIL_ROW` convention) always show a
  short help/status line for the selected row — what it does and what
  it is set to now.
- **Developer settings (hidden)** — the ENTIRE current applet,
  untouched, reachable only by scrolling DOWN past the kid menu's last
  row: that lands on a `development` row (drawn only when selected, so
  the three-row screen stays three rows), and OK on it opens the
  developer applet. Red/Back returns to the kid settings (the
  snake-back convention). It is NOT in the dot rotation.

The data row is a TRI-STATE of the pipe, not two independent toggles:
`off` (wifi off, data off), `wifi` (wifi on, data off), `cell` (data
on, wifi off). OK cycles the TARGET; the change is applied only after
the selection SETTLES (~1s without a keypress), because the modem
accepts a single data call per boot (`toggleData`'s pin) — a kid
cycling through `cell` on the way to `off` must not spend it. The
displayed state is derived from the same diagnostics the dev applet
reads (wifi state + cell data state), so the row shows what IS, and
the pending target until it settles and applies. The help row carries
the warning when it matters ("cell works once per boot").

Notify and brightness reuse the existing persisted cells
(`FbConfig`/`FbPrefs`) — same values, same persistence, smaller room.

## What changes

- `wata-fb/src/main/scala/applets.scala` — new `KidSettingsLogic` +
  `KidSettingsApplet` (state: selected incl. the hidden dev row,
  pending data target + settle timer, mirrors of notify/brightness);
  the existing `SettingsLogic`/`SettingsApplet` becomes the developer
  applet unchanged except its title says `DEV SETTINGS`.
- `wata-fb/src/main/scala/shell.scala` — applet array grows a DEV slot
  appended after SNAKE; the dot rotation wraps over the first three
  only; OK-on-development activates DEV; Back in DEV returns to
  SETTINGS (extending the snake-back arm).
- Echo-audio routing (`routeAudio`) points at the DEV slot (the echo
  test lives there now).
- `tools/fb-ui-scripts/` — existing settings scenarios grow the
  navigation preamble (scroll past the kid rows, OK) to reach the dev
  applet; a new kid-settings scenario pins the three rows, the help
  text, the data-target cycle (targets only — off-device the apply is
  the existing "not on device" no-op), and the hidden-row unlock.
- Goldens regenerated; `docs/design/wata-fb.md` settings section
  rewritten.

## Verification

`just ci`; mac builds stay green (applets.scala is symlinked into
wata-mac — the new code must compile there; the mac instantiates
neither applet). On hardware: the kid panel shows three rows with help
text; scrolling past the bottom reveals `development`; OK opens the
old panel; red returns.

## Out of scope

- Any change to the developer rows themselves.
- Parental locking of the dev door (scroll-past is judged enough for
  now).
- The mac's settings surface (native, separate).
