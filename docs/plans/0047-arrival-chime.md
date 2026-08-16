# 0047 — an arrival chimes; auto-play becomes a future flag

Status: accepted

## Problem

Owner field report (2026-08-16, messaging Alma's handset from the mac):
screen off, a message arrived, and the green LED did not blink; screen
on, a second message played out loud immediately. Both are the same
fact: the device default notify mode is `play` (plan 0037's
walkie-talkie default), and auto-play does not consult the screen — the
arrival auto-played, was marked played, and `unplayed` never stayed
above 0 for the LED arbiter to blink on. The blink mechanism itself is
fine (verified on hardware this morning with a genuinely unplayed
message).

The owner's ruling: playing a message out loud the moment it arrives is
a defensible walkie-talkie design, but it is not the starting point —
it belongs behind a deliberate flag later (the shape of a phone's focus
modes: where she is decides whether a message speaks). What the device
should do today on an arrival: play a short notification **chime**
(reusing the startup chirp asset), and blink the green LED for as long
as messages are unplayed.

## Decision

**A third notify mode, `chime`, becomes the device default; `play`
stays in the code but is unreachable from the device UI until the
focus-modes work deliberately reintroduces it.**

- The shared model (`wataclient/notify.scala`) gains `NotifyChime` /
  `MODE_CHIME`. `playsNow` keeps meaning auto-play (only `play`).
- The device default flips to chime, and a STORED `play` (the old
  default, or the settings toggle's old on-state) loads as chime on the
  device — nobody chose auto-play knowingly, and the flag that brings
  it back should be a new, deliberate act. The settings applet's toggle
  now writes chime/quiet.
- `announce` (ui.scala) grows the chime arm: on an arrival that is not
  auto-played and not suppressed (already viewing that conversation on
  a lit screen), chime mode sends a `CmdChime` to the audio thread —
  which owns the pcm device and does one thing at a time, so a chime
  never cuts into a recording or a playback — and the quiet banner is
  set exactly as before. The decision line grows the word: `notify:
  chime "…" "…" unplayed=n`.
- The chime is the startup chirp (`Chirp.play()`, same decode+play path
  as a voice message) — one audio path, one reviewable asset.
- The LED needs no change: with auto-play out of the default path,
  arrivals stay unplayed and the existing arbiter blinks, screen on or
  off (the frame loop ticks LEDs with the display blanked).
- **The volume knob is analog, pre-amplifier** (audio_experiments.md):
  software cannot read it, and at 0 it silences the speaker in
  hardware. So "chime only when the knob is not at 0" is satisfied by
  always playing the chime — the knob is the mute switch, exactly as it
  is for voice playback.
- The mac keeps its play/quiet checkbox and behavior unchanged — the
  adult opted into walkie-talkie mode explicitly there, and the report
  is about the handset.

## What changes (file-level)

- `wataclient/notify.scala`: `NotifyChime`/`MODE_CHIME`, parse/spell;
  `chimes(m)` beside `playsNow(m)`.
- `wataclient/audiocmd.scala`: `CmdChime` (the mac's audio thread
  shares the enum; it just never receives one).
- `wata-fb/src/main/scala/config.scala`: default `chime`; stored `play`
  loads as chime (comment names this plan).
- `wata-fb/src/main/scala/audiothread.scala`: the `CmdChime` arm calls
  `Chirp.play()`.
- `wata-fb/src/main/scala/ui.scala`: the chime arm in `announce`.
- `wata-fb/src/main/scala/applets.scala`: the settings toggle maps to
  chime/quiet.
- fb-ui-tests: existing notify scenarios keep passing (they set their
  mode explicitly or ride the new default); a chime scenario asserts
  the `notify: chime` line and the blink probe (`notifyled` == 2) on an
  arrival with the default mode.

## Verification

`just ci` (fb-ui-tests carry the behavior; golden untouched — no
rendering change besides none). On hardware: send a message from the
mac with the handset's screen off — expect the chime and a blinking
green LED; play the message — expect the LED steady again.

## Out of scope

- The focus-modes design that reintroduces auto-play deliberately
  (per-contact/per-place policy). The `play` mode stays as its seam.
- A software-mixed chime volume, or reading the knob — analog hardware.
- Any mac behavior change.
