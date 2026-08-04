# 0004 — wata-fb host simulator

Status: done

`[FB-SIM]`

## Problem

Phase 1 of [0003](0003-parity-and-beyond.md): `wata-fb ui` needs a real
`/dev/fb0`, so the applet/shell layer can only be exercised on the device,
and nothing automates it (`[FB-UI-UNTESTED]`). The Zig client's SDL dev
mode is the proof this loop matters; we want the equivalent with zero
system dependencies.

## Decision

One new seam, two host front ends. `Ui.frameLoop` is already pure against
`Shell`/`Runtime` except for four device edges: present, input poll, LEDs,
and the audio thread. Extract those behind a `UiDevice` capability (same
style as `HttpDo`/`Clock` in `wataclient`), with three implementations:

- **real** — the current fbdev/evdev/sysfs code, unchanged behavior.
- **sim** (`wata-fb sim <base> <user> <pass>`) — interactive host run.
  Rendering: ANSI truecolor half-block cells (`▀`, fg=upper px, bg=lower
  px) — 160×64 character grid, full frame only when the buffer changed.
  Input: raw bytes from stdin (the wrapper script sets/restores the
  terminal mode with `stty`); arrows = d-pad, Enter = select,
  Backspace/`b` = back, Space = PTT. Terminals have no key-up, so PTT
  release is inferred from a gap (>250ms) in key-repeat events — the same
  trick the TUI's `usePtt` uses. LEDs render as two colored cells in a
  status row under the frame.
- **script** (`wata-fb uitest <script>`) — deterministic driver for CI:
  a virtual `Clock`, key events and frame-advance steps read from a text
  script, PNG checkpoint dumps compared byte-exact against committed
  goldens (the `fbdump` golden contract, now over the *real applet UI*).

Audio on the host: a sim audio actor speaks the existing
`AudioCmd`/`AudioEvt` mailbox protocol — record returns a canned Ogg
fixture, play succeeds without sound. That makes the full send path
(PTT → upload → `m.audio` → other client) run host-side; the codec stays
device-only.

## What changes

- `wata-fb/src/main/scala/`: new `sim.scala` (terminal device + sim audio
  actor), `uiscript.scala` (script parser + virtual clock + checkpoint
  runner); `ui.scala` refactored over `UiDevice`; `main.scala` gains the
  two subcommands.
- `tools/fb-sim.sh` (stty wrapper + server address defaulting),
  `tools/fb-ui-tests.sh` (hermetic: fresh `wata-server` per scenario, like
  `wataclient-integ.sh`), golden PNGs under `tools/fb-ui-golden/`.
- `justfile`: `fb-sim`, `fb-ui-tests`; `ci` gains `fb-ui-tests`.
- `TODO.jsonl`: `FB-UI-UNTESTED` closes when the harness lands;
  `docs/design/wata-fb.md` gains the simulator section in the same commit.

## Verification

- `just fb-ui-tests` green and hermetic (no device, no network beyond
  localhost), added to `just ci`.
- Exit criterion from 0003 phase 1: a scripted scenario sends a voice
  message alice→bob and asserts the receiving client's conversation view
  renders the message row (golden frame).
- `just fb-deploy` on-device check that the real path is unchanged.

## Out of scope

- SDL or any windowed backend; host audio capture/playback.
- Text entry (display-name editing stays preset-picker only).
- Any change to device behavior.
