# 0055 — play mode in the kid notify row; data by left/right + OK

Status: accepted (owner follow-up 2026-08-16, with a hardware retest)

## The problem

Three things, all in the kid settings row set:

1. The notify row offers chime/quiet only. Plan 0047 parked the
   walkie-talkie PLAY mode (auto-play on arrival) behind future
   focus-modes work — the owner now wants it exposed as a third value.
   The plumbing already exists end to end: `Notify.MODE_PLAY`,
   `NotifyPlayNow`, and the device's arrival auto-play path (plan
   0041's `notifyWataPlaying` shim) all work today; only the row
   refuses to say "play".
2. The data row's help claims "cell works once per boot". RETESTED on
   the handset 2026-08-16 (the kernel bugs behind the old pin were
   fixed since): `pppd call cellular` came up three times in one boot
   (same address, real traffic verified with `ping -I ppp0`). The one
   failed attempt was an immediate redial right after `killall pppd` —
   the modem needs a few seconds of settle after hangup. The
   once-per-boot claim is dead; the settle fact replaces it.
3. The data gesture: OK-cycles-target plus a hidden 1s settle-apply
   confused nobody yet but will — the owner's ruling: LEFT/RIGHT cycle
   the shown target, OK confirms and applies. No timer.

## The decision

- **Notify row**: left/right (and OK, matching the other rows' cycle
  affordance) cycles play → chime → quiet. Persisted through the same
  `FbConfig.saveNotifyMode` path; the mac is untouched (its own
  settings already offer play). Help text explains the selected value
  ("play: messages speak on arrival" style, short).
- **Data row**: left/right cycles the PENDING target (yellow, as now);
  OK applies it — one `Diag` call per radio, still never auto-retried.
  Up/down moving the selection CLEARS an unconfirmed target: a choice
  walked away from must not linger. The settle timer is gone.
- **The once-per-boot pin is retired everywhere it is stated** (kid
  help row, state-record docs, design doc). The replacement fact: an
  immediate redial after a hangup can fail while the modem settles
  (~5s); the red failure report already shows it, and OK again is the
  deliberate retry. No automatic cooldown — a kid-visible failure plus
  a manual retry beats hidden state.
- The dev panel's notify mirror leaves `Shell.syncPrefs`: with no
  notify row in the dev menu the kid panel is the mode's only editor
  (brightness/timeout mirrors stay — both panels still edit those).

## What changes

`applets.scala` (kid notify tri-state + data gesture; retired-claim
comments), `shell.scala` (`syncPrefs` drops notify), the kid-settings
script/goldens (left/right directives; a play-mode checkpoint), design
doc. If the dev `SettingsState.notifyChime` field ends up written by
nobody, remove it and its wither plumbing.

## Verification

`just ci`, `just mac-build`, fb-ui suite green, frames eyeballed. On
hardware: the notify row set to play auto-plays an arrival; the data
row applies on OK only.

## Out of scope

- Focus-mode scheduling (play-during-daytime etc.).
- An automatic redial cooldown.
