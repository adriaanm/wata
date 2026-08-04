# 0006 — device-client parity with the Zig fbclient

Status: accepted

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
