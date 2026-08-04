# 0016 — wata-tui: the terminal client and admin interface

Status: done

## Problem

`[TUI-PORT]` The only Sgola client is `wata-fb`, which needs a device (or
the simulator). Development and administration want a cheap second client
on the host: something that logs in as a real user, shows what the family
sees (contacts, conversations, voice messages), can send and play voice,
and can poke the server directly. The TS tui (`src/tui/`, React/Ink,
~4.4k lines) fills this role today but rides the retired TypeScript stack.

## Decision

A new app module **`wata-tui/`** over `wataclient` + `json` — the same
shape as `wata-fb` minus every device layer. It is a **line-oriented
command REPL**, not a full-screen Ink port:

- An admin tool wants scriptability: a REPL reading commands from stdin
  and printing plain lines is drivable by a test script and a golden,
  exactly the oracle pattern this repo already trusts. A curses UI is
  neither (and the restricted dialect has no curses binding to lean on).
- The family-facing UI already has two full implementations (device +
  simulator); the tui does not need to be a third. "What the family sees"
  is served by printing the `StateSnapshot`.

Scope of the port: the TS tui's *function* (second client + admin), not
its views. The Ink component tree, profile selector, keytar storage, and
PvRecorder mic capture are explicitly not ported.

### Shape

- **Login** from flags/env (`WATA_TUI_HS` default `http://127.0.0.1:8008`,
  `WATA_TUI_USER`/`WATA_TUI_PASS`), no stored session file at first — an
  admin tool can log in each run; session persistence can come later if it
  earns its place.
- **Capabilities**: copy `wata-fb`'s `caps.scala` pattern (`FbHttp` over
  the net/http client facade — including the `WATA_IROH_CONFIG` transport
  swap, which makes the tui a second iroh client for free — and the
  `go.time` clock). No audio thread: the tui plays through an external
  player process.
- **Runtime**: `Runtime.make` + `start` in a `supervised` scope, snapshot
  polling; the REPL is the main goroutine.

### Commands (first cut)

| command | does |
|---|---|
| `snap` | print the current `StateSnapshot`: connection, self, contacts, conversations with unplayed counts |
| `msgs <conv#>` | list a conversation's voice messages (sender, duration, ts, played) |
| `send <conv#\|user> <file.ogg>` | upload + send a voice message (resolving the DM room via the server's endpoint when needed) |
| `play <conv#> <msg#>` | download the Ogg and hand it to `$WATA_TUI_PLAYER` (default: first of `mpv`, `ffplay -nodisp -autoexit` on `$PATH`); `mark`s it played |
| `mark <conv#> <msg#>` | send the read receipt only |
| `raw <METHOD> <path> [json]` | authenticated request straight at the server — the "poke the server" escape hatch; prints status + body |
| `wait <ms>` | poll snapshots/events for that long, printing changes — the scripting primitive that replaces "watch the screen" |
| `quit` | stop the client, exit |

Numbered references (`conv#`, `msg#`) index the last printed listing —
stable within a script, no ids to copy by hand (ids are still printed for
`raw` use). Mic capture is out of scope: `send` takes an Ogg file (the
repo's test fixtures provide canned ones); recording on the mac can later
shell out to ffmpeg the same way `play` shells out, if wanted.

### Files

- `wata-tui/{sgo.build,sgo.deps,go.mod}` — app module, mirroring
  `wata-fb`'s (minus device deps).
- `wata-tui/src/main/scala/caps.scala` — the two capability impls (copied
  pattern), plus an `os/exec` facade with args
  (`command(name, args: List[String])` — `wata-fb`'s is arg-less).
- `wata-tui/src/main/scala/repl.scala` — stdin line loop, command parse,
  listing/numbering state.
- `wata-tui/src/main/scala/main.scala` — login, runtime start, REPL run.
- `tools/tui-smoke.py` + `just tui-smoke` — the gate (below).
- `docs/design/wataclient.md` — gains a short "wata-tui" consumer section
  (it is the second consumer of the module; the doc currently says "only
  consumer today is `wata-fb`").

## Verification

Done: `tools/tui-smoke.py` (`just tui-smoke`) boots a fresh server, runs one tui as
bob sending a fixture Ogg to alice, then one as alice scripted over stdin:
`snap` shows the DM with 1 unplayed, `msgs` lists it, `play` (with
`WATA_TUI_PLAYER` pointed at a stub that records its argv) fetches
byte-identical Ogg and marks it played, `raw GET /_matrix/client/v3/...`
answers 200, and a re-run of `snap` shows 0 unplayed. Every assertion is on printed
lines. It runs in ~10s (the two `wait`s dominate), so it stayed standalone
like the iroh smokes rather than joining `just ci`.

Two things the implementation learned, both recorded in
[../design/wata-tui.md](../design/wata-tui.md):

- `exec.Command` is variadic and the dialect has no slice-spread lowering,
  so the args facade is five explicit arities of the same `@go.name`.
- A `Unit`-returning self-recursive list walk
  (`case h :: t => println(…); walk(t, i + 1)` / `case Nil => ()`) crashes
  `sgolaBackend` with "unsupported expression (Thicket)" over an
  `EmptyTree`. It does not reproduce from that def in isolation — the
  surrounding file is part of the trigger. Every walk in `wata-tui` is a
  `while` loop over a `var cur` instead, which is what the rest of the repo
  already does.

## Out of scope

- Mic capture / recording.
- Session persistence, multi-profile switching (TS `ProfileSelectorView`).
- Any full-screen rendering; colors are at most ANSI accents.
- Server-side admin verbs that don't exist yet (user management is
  `users.json`); `raw` is the admin surface for now.
- The wifi panel (`TUI-WIFI-PANEL`) — a follow-up once this exists.
