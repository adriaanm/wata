# 0042 — a live capture-level meter in the recording bar (FB-REC-LEVEL-METER)

Status: done

## The problem

The red recording overlay animates on the CLOCK (`tickTimers` adds `dt`
to `pttHoldTime` every frame), so it counts up identically over a live
microphone and a dead one. The motivating incident: `DEC1 MUX` reset to
`ZERO` and every message recorded four seconds of digital silence that
sent, arrived and played as nothing, while the overlay counted happily
throughout. The Go layer even knows about this failure (`RouteCtl`
reasserts the route and logs when the mic was dead) but nothing reaches
the person holding the button. A meter fed from the encoder's input
frames shows a flat bar the moment the kid speaks into a dead mic.

## The decisions

**The level is computed where the PCM already is, portable-side.** The
record loop is shared Scala (`audiothread.scala` `recordLoop`, symlinked
into wata-mac): each 40 ms period fills `buf` with S16_LE PCM before the
two Opus encodes. The peak absolute sample of that period, scaled to
0..32 (five bits is plenty for a 160-px panel), is computed right there —
between the read and the encode, on the audio thread. No Go change in
either `go-pkgs/audio` or `go-pkgs/macaudio`, no facade change (the two
`audio.scala` facades must stay declaration-identical; touching neither
is the smallest true change). Peak, not RMS: the question the bar answers
is "is sound arriving", and peak is the cheaper and more legible answer
on a 24-px bar.

**One new event, on the existing mailbox.** `AeCaptureLevel(level: Int)`
in the `Ae*` family, posted once per period (25 Hz) with `trySend` — the
event mailbox is a capacity-16 drop-on-full channel, and a dropped level
tick is harmless by design. It reaches both clients through the one
existing drain (`Shell.drainAudio` → `WataLogic.onAudioEvent`; the mac's
`isEchoEvt` whitelist does not swallow it). This is also an implicit
liveness signal: a record loop that stops reading stops ticking.

**`WataState` grows `captureLevel: Int`.** Reset to 0 on PTT press;
updated by the new `onAudioEvent` arm; the withers are positional
reconstructions so all of them grow the field (mechanical, the known
cost). The `selftest.scala` and `devcli.scala` event code/name tables
gain the arm too — the applet's `case _ =>` catch-all must not be what
handles it.

**The meter is one keyed rect inside `recordingView`.** A bright inner
bar (height ~6, y centered in the red bar, width `level * (W-8) / 32`,
min 1 so "flat" is visibly a sliver rather than absence) keyed `"lvl"`
between `"bar"` and `"time"`. One rect means one `patch set` per changed
period — legible in the differ output the smoke harness asserts on, and
cheap on the panel. No segment array, no FFT: the ticket's
"mini-spectrum" reading is out of scope; the honest signal is level.

**Same bar on both clients for free.** `applets.scala` and
`audiothread.scala` are the shared files; the mac's fake mic
(`WATA_MAC_AUDIO=fake`, a constant-amplitude 440 Hz tone at 16000)
produces a CONSTANT level, so mac-smoke can assert the meter's exact
rect. mac-smoke's existing recording assertions pin the overlay's
children and the `[1.1]` path index — the new child shifts them; those
expectations update in the same commit (the intended tripwire, as with
plan 0041's underline).

**Scripted determinism via a uiscript directive, not SimAudio timing.**
`SimAudio`'s recording is deliberately instantaneous (fixed `RecordMs`
so scripted frames are byte-reproducible); giving it a real 25 Hz
producer would trade that away. Instead a `caplevel <n>` force directive
posts `AeCaptureLevel(n)` through the normal event path, and a
`caplevel` probe reads `WataState.captureLevel` back. The golden pins
the drawn meter at a known level while PTT is held.

## What changes (file-level)

- `wataclient/src/main/scala/audiocmd.scala`: `AeCaptureLevel(level: Int)`.
- `wata-fb/src/main/scala/audiothread.scala` (shared): peak-of-period +
  scale + `trySend` in `recordLoop`.
- `wata-fb/src/main/scala/applets.scala` (shared): `captureLevel` field
  + withers + `initial` + PTT-press reset; the `onAudioEvent` arm; the
  `"lvl"` rect in `recordingView`.
- `wata-fb/src/main/scala/selftest.scala`, `devcli.scala`: event tables.
- `wata-fb/src/main/scala/uiscript.scala`: `caplevel` directive + probe.
- `tools/fb-ui-scripts/` + `tools/fb-ui-golden/`: a meter scenario
  (PTT held, inject two levels, checkpoint each).
- `tools/mac-smoke.py`: the recording-overlay patch/tree expectations.
- `docs/design/wata-fb.md`: the recording-overlay description grows the
  meter and its rationale.

## Verification

- New uiscript scenario: hold PTT, `caplevel 24` → probe reads 24 and a
  PNG checkpoint pins the wide bar; `caplevel 0` → checkpoint pins the
  sliver. Wired into fb-ui-tests.
- mac-smoke green with the updated overlay expectations — under the fake
  mic the level rect is constant and asserted exactly.
- Full `just ci` green; `wata-fb notifytest` untouched.

## Out of scope

- A real spectrum analyzer (FFT) — level is the signal that matters.
- Alerting/auto-retry on sustained silence (the route watchdog already
  reasserts; the meter makes the failure visible, which is this ticket).
- The handset hardware pass (rides the next fb-deploy; WATA-TODO debt
  line when this lands).
