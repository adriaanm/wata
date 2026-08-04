# 0006 — device-client parity with the Zig fbclient

Status: done

`[FB-PARITY]`

## Problem

Phase 3 of [0003](0003-parity-and-beyond.md). The Zig client
(`src/fbclient/`, in-tree, read-only) is the behavioral spec: it was
feature-complete against the TUI. The Sgola `wata-fb` has the shell, two
applets, and the send path, but not the full feature set — and now has
the simulator + `fb-ui-tests` harness (plan 0004) to develop against.

## Decision

Audit-first: derive the gap list from the Zig applet code
(`src/fbclient/src/applets/wata.zig`, `settings.zig`, `main.zig`,
`shell.zig`, `config.zig`) against `wata-fb`'s `applets.scala` /
`shell.scala` / `ui.scala`, and record it in `docs/design/wata-fb.md`
before implementing. Known headline gaps to expect:

- **Session persistence / auto-login** — the Zig client stores the
  access token (`/etc/wata/config.json` on device, a dev path on host)
  and boots without arguments; `wata-fb ui` takes base/user/pass each
  run. A boot-into-wata device needs this (phase 4 depends on it).
- **Conversation view completeness** — play selected message, delete
  (F2 → redact), unplayed-count badges, played/receipt marks, ordering
  and scrolling past the visible window.
- **Settings completeness** — echo test, brightness (sysfs write-through
  + persist), screen-timeout, display-name preset picker (get/set via
  Matrix).
- **Feedback + chrome** — recording overlay timer, SENT/SEND FAILED/
  PLAY FAILED banners, connection status line; verify against the Zig
  rendering, not reinvented.

Non-goals from the Zig client: snake/clock/charmap applets (toys; port
only if trivial and last), FreeType text rendering (bitmap font stays
until a real need).

## What changes

- `wata-fb/src/main/scala/`: `applets.scala`, `shell.scala`, new
  `config.scala` (JSON session/config persistence; path from
  `WATA_FB_CONFIG`, device default `/etc/wata/config.json`).
- One `fb-ui-tests` scenario + goldens per feature block (persistence,
  conversation actions, settings) — the harness is the oracle.
- `docs/design/wata-fb.md`: the audit table, then updated per landing.
- `TODO.jsonl`: `[FB-M8-LITERALS]` closes if the audit touches those
  frames (regenerate goldens in the same commit).

## Verification

`just ci` green throughout (includes `fb-ui-tests`); each feature block
lands with its scenario; a final manual `just fb-sim` walkthrough
against `just server` mirrors the Zig client's flows.

## Out of scope

- Boot-into-wata rootfs integration (phase 4).
- Audio beyond the existing device path and sim stub.
- Any server change (phase 2 is closed; file gaps as TODO items).

## Outcome

The audit is the table in
[docs/design/wata-fb.md](../design/wata-fb.md#parity-with-the-zig-fbclient),
which is now gap-free. It found less missing than expected: the
conversation view and the settings menu were already at feature parity
item for item, so most of this plan turned into the coverage that was
supposed to be proving them, plus the three defects writing that
coverage exposed.

What landed: the config store (`config.scala`) and boot-without-
arguments; a cursor that survives a list shrinking under it; a settings
detail block sized to the landscape grid instead of the Zig client's
portrait one; preferences that persist; battery percent in Device Info;
and four `fb-ui-tests` scenarios where there was one.

The snake/clock/charmap applets and FreeType stayed out, as planned.
Still open, and recorded in the design doc rather than here: the
`SEND FAILED` / `PLAY FAILED` flashes have no coverage, because they
need a server that fails on demand rather than another script.
