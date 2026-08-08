# 0041 — wata-fb arrival notifications (MSG-NOTIFICATION-DESIGN)

Status: accepted

## The problem

A message landing on the handset while nobody is looking at it announces
itself only as an unplayed-count digit in the contact list — a channel a
kid is unlikely to notice, and invisible outright while the screensaver
holds the panel. The MODEL is settled and shared
(`wataclient/src/main/scala/notify.scala`: `NotifyMode` with
`NotifyPlayNow`/`NotifyQuiet`, `Arrival`, `Notify.step` — the arrival
edge is a conversation's unplayed count rising, priming once per
session), pinned by `wata-fb notifytest` and consumed by wata-mac
(`docs/design/wata-mac.md`, "Arrival notifications"). What is missing is
wata-fb's PRESENTATION of the same two modes.

## The decisions

**Play-now is the walkie-talkie default.** On an arrival with the mode at
`play`, the frame loop sends the same `ActPlay` the applet's OK press
sends and marks the applet playing (`WataLogic.withPlaying`), so the
existing `AePlaybackDone` arm sends the read receipt — identical to the
mac's `announce` shape, including its `canAutoPlay` gate (`!playing &&
!pttHeld`; an arrival that loses the gate stays badged rather than
queueing — the audio thread does one thing at a time).

**The volume knob needs no software.** There is no software volume in the
client (`PlayVol` is a fixed 8192, `docs/design/wata-fb.md`); the pot is
analog and pre-PA, so its off position silences an auto-played message by
construction, exactly as it silences the chirp. Consequence worth
recording: a message auto-played into a knob-off speaker is still
receipted as played. That is the walkie-talkie contract (a radio does not
know its volume either); a parent who wants unheard messages to stay
unplayed sets quiet mode.

**Quiet mode announces on three channels, all derived from the one
number.** No second notification state threads through the sync engine;
everything below reads `Notify.step`'s arrivals for the edge and the
snapshot's unplayed counts for persistence:

- **LED**: the green LED blinks (~1 Hz, frame-loop driven — `led.scala`
  has no blink primitive) while `Notify.totalUnplayed > 0`. LED
  arbitration moves into one per-frame function — connection state today
  writes the LEDs from `Ui.onConn`; the arbiter keeps red = connection
  bad (steady) and gives green two meanings ordered by urgency: blinking
  = unplayed messages waiting, steady = connected and idle. One pure
  function `(connState, unplayed, frameTick) -> (green, red)` so the
  policy is testable without hardware.
- **Banner**: an in-app overlay (view-algebra rects + text, top of the
  panel) showing `Notify.title`/`Notify.body` for a few seconds after an
  arrival, drawn only while the screen is on and only outside the
  conversation being announced. It does NOT wake the screen — a handset
  in a dark bedroom staying dark is a feature; the LED is the
  screen-off channel.
- **Highlight**: the contact row of any conversation with
  `unplayedCount > 0` gets a visible treatment beyond the count digit — a
  colored underline/rect via a new `Keyed` sibling in `contactRowView`,
  threaded from `FrameCtx` the way `unsent`/`undelivered` already are.
  Persistent until played, because it renders the count, not the edge.

**Play-now uses the quiet channels too** when it cannot play: an arrival
suppressed by `canAutoPlay` (or one that lands mid-PTT) still blinks and
highlights, since the count is still up.

**The mode is device config, stored the mac's way.** A `notify_mode` key
in the config store with its own cell and load/save — NOT a new `FbPrefs`
field, for the reason the mac's `config.scala` records (the shared
settings applet constructs `FbPrefs` positionally; the mac deliberately
kept the mode out of it). Spellings are `Notify.MODE_PLAY`/`MODE_QUIET`
via `parseMode`/`spellMode`. Default: `play` — it is a walkie-talkie.
The settings applet gains a "Notify: play now / quiet" row wired through
the same seam pattern as brightness/timeout; the applet is shared, so the
mac's settings body grows the row too (its chrome commands
`notify:play`/`notify:quiet` remain — same constants, no drift possible).

**Every arrival prints one decision line**, same shape as the mac's:
`notify: play|quiet|suppressed "<title>" "<body>" unplayed=<n>` to the
app log — the assertable half of the presentation.

## What changes (file-level)

- `wata-fb/src/main/scala/ui.scala`: a `notifyC: Atomic[NotifyState]`
  cell beside the other pump cells (reset in `resetCells`), stepped once
  per frame after the snapshot pick-up; the LED arbiter (replacing
  `onConn`'s direct writes); the banner state cell + overlay in the
  frame's view build; the decision line.
- `wata-fb/src/main/scala/shell.scala`: a `withPlaying`-through-`stateC`
  shim (the mac mutates `PumpSt.wata` directly; wata-fb's applet state
  lives in a cell — pattern of `notifyWataSend`).
- `wata-fb/src/main/scala/applets.scala` (shared): the highlight in
  `contactRowView`, threaded like `unsent`; the settings row.
- `wata-fb/src/main/scala/config.scala`: `notify_mode` load/save + cell.
- `wata-fb/src/main/scala/uiscript.scala`: probes (`notifyled`,
  `notifybanner`, mode force directive) for the gate.
- `docs/design/wata-fb.md`: a new "Arrival notifications" section
  mirroring wata-mac.md's, stating the device policy above.

## Verification

- `wata-fb notifytest` (unchanged) keeps pinning the model.
- A new uiscript scenario drives an arrival (messages already flow
  through the script harness — the `msgs`/`played` probes exist):
  play mode asserts the `played` probe rises (ActPlay went out) and the
  decision line; quiet mode asserts `notifybanner`/`notifyled` probes and
  pins the banner + highlight with a PNG checkpoint in
  `tools/fb-ui-golden/`.
- The LED arbiter's pure function gets direct cases in the same script
  (probe reads the computed pair).
- Full `just ci` green; `just mac-smoke` still green (shared-file
  changes: `applets.scala` and the settings row cross to the mac).

## Out of scope

- Waking the screen on arrival (decided against, above).
- Any software volume or knob sensing (no hardware path exists).
- Sound effects for quiet mode (the LED and panel are the channels; a
  beep in quiet mode contradicts the mode's name).
- The handset hardware pass (rides the next `fb-deploy`; tracked as the
  usual device-pass debt in WATA-TODO.md when this lands).
