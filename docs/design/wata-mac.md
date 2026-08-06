# wata-mac — design notes

The macOS client (plan 0032): every wata screen is already a pure
`wataui` body, `go-pkgs/nativeui` renders those bodies as a retained
NSView tree on a scaled 160×128 stage, and `wata-mac/` is the Sgola app
that drives it — the SAME `WataLogic` bodies and input logic the device
runs, a frame pump over `ClientHandle`, and `go-pkgs/macshell` as the
AppKit shell. Audio is real (plan 0033): the device's own audio thread
over a macOS backend. Transport is plain TCP by default and embedded
iroh when configured (plan 0034, below) — which is the case the mac
exists for: a parent away from home, behind a NAT, with no route to the
family's Pi.

## The layers

| layer | what it is |
|---|---|
| `go-pkgs/appleptt/appkit` | generated AppKit bindings (bindgen target `appkit`; see [bindgen.md](bindgen.md) for what was refused and why it shaped the backend) |
| `go-pkgs/nativeui` | plain Go: the algebra mirror, the retained interpreter (`Stage`), pixels, glyphs, the key table + the synthesized key view, the dispatch seam |
| `go-pkgs/macshell` | plain Go: the shell `wata-mac` binds — window/headless stage, the wire decoder, the key queue, the per-mode apply seam |
| `go-pkgs/macaudio` | plain Go, no cgo: the Opus codec (AudioToolbox's C AudioConverter over purego) and the capture/playback engine (AVFAudio), presenting `go-pkgs/audio`'s API |
| `wata-mac/` | the Sgola module: caps, the frame pump, the wire encoder, the headless command loop |

## Running it

```
just mac-build
WATA_MAC_USER=alice WATA_MAC_PASS=testpass123 just mac     # the window
just mac-smoke                                             # the headless gate
```

Login is from `WATA_MAC_HS` (default `http://127.0.0.1:8008`),
`WATA_MAC_USER`, `WATA_MAC_PASS`, positional arguments overriding — the
same rule as wata-tui, prompt-for-password included. `WATA_MAC_SCALE`
sets the stage's integer scale (default 4 → a 640×512 window);
`WATA_MAC_HEADLESS=1` selects the smoke's mode (below). Startup prints
`ready <userId>` or `login failed`.

## The screens are wata-fb's own sources

`wata-mac/src/main/scala` holds SYMLINKS into `wata-fb/src/main/scala`
for the shared units — `applets.scala` (the bodies + the applet logic),
`display.scala`, `paint.scala`, `netstatus.scala`, `input.scala`,
`syscall.scala` — so the mac client runs the same `WataLogic.body` /
`handleInput` / `update` the handset runs, not a port of them. That is
what keeps "the fb goldens are the semantic oracle" a fact rather than
an aspiration: there is one source for what every screen shows.

The device-only objects those files reference (`Diag`, `Led`,
`FbConfig`, `Enrol`, `Shell`'s key predicates, `FbCaps`) are mac stubs
in `stubs.scala`, each answering exactly the documented OFF-DEVICE
answer ("n/a", −1, false) — the same answers a wata-fb host build gets,
so the rendered screens match the goldens' host runs. A new device
reference appearing in a shared file fails this module's build loudly;
that tripwire is the price list of the sharing, and it is cheap.
Enrolment/QR screens are fb-only (`Enrol.required()` is false here);
the settings applet compiles in but is not driven.

## The frame pump (`main.scala`, `Pump`)

One pump shape, two drivers. Each frame: drain macshell's key queue
into `WataLogic.handleInput`, tick `WataLogic.update`, read
`Handle.snapshot()`/`connection()`, run `NetStatus.poll`, build the
body, then `Diff.diff` against the last tree and hand the script to the
shell as ONE wire message (`wire.scala` encodes; macshell's `wire.go`
decodes — the grammar is pinned in both headers and in
`macshell/wire_test.go`). Bodies and the diff run on the pump
goroutine; only the shell's apply touches AppKit.

- **Windowed** (the default): `macshell.Start` runs on the main
  goroutine — macshell's package init pins it to the main OS thread —
  then the pump is forked and `macshell.RunApp` (NSApplication.run)
  owns the main thread. Frames tick on `Handle.waitEvent(33ms)`: the
  dirty-flag channel is the wake-up, the deadline keeps held-key timers
  advancing. Apply is dispatched to the MAIN QUEUE asynchronously, one
  frame per queue turn. The two-step quit (Back on the contact list,
  again within 2s — the device's own gesture) leaves through
  `macshell.Terminate`.
- **Headless** (`WATA_MAC_HEADLESS=1`): no NSApplication, no window, no
  runloop. The stage lives on macshell's dedicated locked OS thread,
  applies run there SYNCHRONOUSLY, and the main goroutine is a line
  command loop: `wait <ms>` pumps frames and prints every applied patch
  (`patch <script line>`, `DiffOracle.showPatch`'s own rendering — the
  smoke's window onto the differ), `tree` prints the live NATIVE
  hierarchy (class, frame, label text per view, group descent only),
  `key <name> <press|release|repeat>` injects a macOS virtual key code
  through the real translation table, `quit` winds down. This is what
  `tools/mac-smoke.py` drives, tui-smoke style.

Each frame drains TWO mailboxes, both in `frame` so the windowed and the
headless driver get them identically:

- the runtime's **`UiEvent`** queue, first: `EvSendComplete`/`EvSendFailed`
  → `WataLogic.notifySend`, `EvPlaybackError` → `WataLogic.notifyPlayError`,
  `EvOutbox` → the pump-carried `unsent`/`undelivered` (`PumpSt` fields,
  where wata-fb uses cells), which feed BOTH the `FrameCtx` and
  `WataLogic.body`. `EvConn` and `EvSnapshot` need nothing here —
  `h.connection()` and `h.snapshot()` already carry them, and the mac has no
  LEDs and no config store to react with.
- the audio thread's **`AudioEvt`** queue, after the key drain and before
  `WataLogic.update`, exactly ONCE (plan 0009: two drains on one channel eat
  each other's events). Echo events — the four `AeEcho*` that
  `Shell.isEchoEvt` names — are dropped, because nothing here drives the
  settings applet's echo test; everything else goes to
  `WataLogic.onAudioEvent`. The predicate is restated in `main.scala`
  because the mac's `Shell` stub carries only the key predicates.

## Audio (plan 0033)

The mac runs **wata-fb's own audio thread**: `audiothread.scala` is one of
the symlinked sources, like the screens. What differs is the Go package
under it — `go-pkgs/macaudio` instead of `go-pkgs/audio` — and the seam is
the `@go.bind` path on `wata-mac/src/main/scala/audio.scala`, whose
declarations are otherwise identical to wata-fb's `audio.scala`.

- **`just facade-check` is what makes the symlink safe.** Nothing in either
  compiler run notices a facade drifting: a member only one side declares
  breaks the other module only if the shared thread happens to call it, and
  a changed signature can keep compiling while meaning something else. The
  check (`tools/facade-check.py`, pure text, in `ci`) compares the two files'
  declarations with comments, blank lines and the `@go.bind` line ignored —
  the comments describe two different backends on purpose.
- **Client construction:** `Runtime.makeWithAudio` + `ClientHandle.startClient`
  (`Pump.startAudioClient`), not `ClientHandle.start`'s headless constructor —
  that is what wires the action loop's `AcPlay` to a thread. In-memory outbox:
  the mac logs in from the environment every run and persists nothing, so
  `makeWithAudioStored` would need a store this app does not have.
- **The thread is forked into a `sgo.supervised` scope** around each driver's
  loop, with the command channel hoisted out of the fork (the body needs the
  synchronizer, not the client record) and `AcQuit` sent on the way out — the
  same shape as `Ui.loopWithDevice`. Both drivers do it; the headless one
  wraps the command REPL.
- **Recording and playback must not overlap.** `macaudio.OpenCapture`
  restarts the shared AVAudioEngine to attach the input tap, which would cut
  a playback in progress. The audio thread dispatches commands strictly
  sequentially — one `AudioCmd` at a time out of `cmds.recv()`, and
  record/play sessions run to completion inside `dispatch` — so it holds.
  It is now a load-bearing property of that thread rather than an accident:
  a future thread that overlapped the two would break macOS audio while
  leaving the device unaffected.
- **`WATA_MAC_AUDIO=fake`** (read once in `SetupMixer`) replaces the
  microphone with a clock-paced 440Hz tone and the speaker with a clock, and
  nothing else — codec, period sizes, Ogg framing, mailbox protocol and every
  UI state stay real. That is what lets `mac-smoke` record, encode, upload,
  sync, decode and play unattended: a mic tap needs a TCC grant a CI-shaped
  run does not have.
- The settings applet's echo test compiles in and is never driven, so
  `AcEchoTest` never reaches the thread.

## Transport (plan 0034)

`WATA_IROH_CONFIG=<path>` swaps the `go.net.http.Client` under `HttpDo`
for one whose connections are iroh bidirectional streams to the peer the
config names (`MacCaps.httpDo`); unset is the plain `DefaultClient`.
Nothing above the capability line knows, which is the whole seam —
wata-tui's, copied. `wata-mac/src/main/scala/irohnet.scala` is the
facade, held declaration-identical to wata-tui's by `just facade-check`
(a second pair beside the audio one): both are the CLIENT surface over
one Go package, and wata-fb's facade is deliberately not the reference
because it also declares the handset's enrolment calls.

- **The build is opt-in and the ordinary one stays cargo-free.**
  `-tags iroh` is a SECOND `go build` over the emitted tree — it links
  the cgo implementation and a ~13 MB Rust staticlib — so it is its own
  recipe (`just mac-iroh-build` → `wata-mac/.sgo/wata-mac/wata-mac-iroh`)
  and `just mac-build` never needs cargo. Without the tag the app links
  irohnet's pure-Go stub, whose `NewHTTPClient` errors loudly; that is
  not a broken build, it is the "configured but unavailable" state
  below.
- **A failed init LATCHES, it does not downgrade.** `MacCaps.irohClient`
  follows wata-fb, not wata-tui. The tui downgrades to `DefaultClient`
  and prints a line, which is fine for an operator reading scrollback; a
  GUI client that does that sits on `waiting for network` forever
  against a transport that was never coming up. So the failure sets
  `MacCaps.transportDownC` and the boot screen says `transport
  unavailable` / `check config` outright. `FbCaps.transportUnavailable`
  in `stubs.scala` — once a hard-coded `false` — reads that cell, which
  is what turns an existing wata-fb screen honest here.
- **The cell is written at CLIENT CONSTRUCTION, and both drivers
  construct on the goroutine that then pumps.** `Pump.startAudioClient`
  calls `MacCaps.httpDo()` before the windowed driver forks its pump and
  before the headless driver enters its REPL, so the single write
  happens-before every frame's read; the `sgo.Atomic` is for the
  windowed case, where the frame runs on a different goroutine than the
  one that constructed.
- **A failed login keeps pumping in BOTH drivers.** The windowed driver
  always did (`login failed` then frames); the headless one used to skip
  its REPL entirely, which made the boot screen — the only place the
  transport verdict is visible — unobservable to a harness. It now runs
  the same scope either way. `WATA_MAC_CONNECT_MS` bounds the startup
  wait whose only effect is which of the two lines is printed.

Running it against a real deployment (the client's node id allowlisted
on the server, plan 0027 owns how it gets there):

```
just mac-iroh-build
WATA_IROH_CONFIG=~/.wata/iroh.json WATA_MAC_USER=… \
  wata-mac/.sgo/wata-mac/wata-mac-iroh http://wata.iroh
```

## The retained interpreter (`nativeui.Stage`)

- **The stage is the unit.** `NewStage(scale)` makes one container NSView
  of `scale·160 × scale·128` (default scale 4 → 640×512); `SetTree`
  mounts a view tree, `Apply` runs a differ script in script order.
  `Mirror()` is the plain view tree the native tree currently shows —
  `Patches.applyAll` semantics, ported (`view.go`), and the invariant the
  tests hold against the real hierarchy.
- **Element table:** VText/VGlyph → `NSTextField` label, VRect → `NSBox`
  (custom, borderless, `fillColor`; NOT a layer-backed view — CGColorRef
  is unmappable, and NSBox draws via `drawRect:` so offscreen renders
  work), VImage → `NSImageView` over RGB565→RGBA widening +
  nearest-neighbour pre-scaling + PNG → `initWithData:` (raw bitmap
  planes are refused pointers), VGroup → plain container NSView spanning
  the full stage.
- **Geometry:** semantic coordinates exactly as wataui defines them
  (VText on the 26×15 grid of 6×8 cells, text rows starting 1px down;
  the rest on stage pixels), scaled by the integer factor, then y-flipped
  once per leaf (AppKit's origin is bottom-left). Group containers all
  span the stage, so child coordinates stay stage-absolute and nesting
  adds no offsets.
- **Fonts:** monospaced system font at 6.8pt per scale unit — sized so
  the LINE fits the 8px row pitch. The advance (~0.6em) is narrower than
  the 6px cell; column alignment still holds because every VText is
  framed from its own grid cell. Only intra-string width shrinks, and
  the semantic oracle for appearance stays the fb goldens.
- **Glyphs:** icon codes past 0x7F map to Unicode equivalents
  (`glyphs.go`: ✓ ▶ ★ ↑ ⚠ ≈ Ψ for the codes wata's bodies emit);
  anything unmapped renders `□`, visibly wrong on purpose.
- **Paint order = subview order**, AppKit's own rule; `PInsert` uses
  `addSubview:positioned:NSWindowBelow relativeTo:` to splice at an
  index, `PSet` mutates properties in place when the constructor is
  unchanged and `replaceSubview:with:` otherwise.
- **The key view** (`keyview.go`): a synthesized `WataKeyView` NSView
  subclass — acceptsFirstResponder YES, keyDown:/keyUp: through the pure
  table (`keys.go`: arrows, return/keypad-enter, esc, space=PTT) with
  press/release/repeat phases (autorepeat arrives as `PhaseRepeat`, not
  a second press — the hold gestures need real edges). Synthesis follows
  bindgen.md's encoding rules: these selectors take only objects/BOOL,
  so purego's derived encodings ARE the true ones. Unknown keys are
  swallowed. macshell overlays it on the stage and makes it the window's
  first responder.

## Threading and lifetime facts

- **`Stage` methods run on the AppKit thread and never hop by
  themselves.** macshell owns the seam per mode: main queue
  (`nativeui.MainQueue().Async`, libdispatch over purego — proven
  headless on a private serial queue in `dispatch_test.go`) under the
  windowed runloop; the dedicated locked thread headless.
- **Headless AppKit works.** View construction, mutation and
  `cacheDisplayInRect:` offscreen rendering all run with NO
  NSApplication, no runloop, no window, on a locked non-main OS thread
  inside an autorelease pool — `mac-smoke` asserts hierarchies without
  ever opening a window.
- **Autorelease pools are the caller's job**, one per frame/apply —
  macshell wraps every excursion. Corollary found the first time two
  pools ran: an object the stage KEEPS must not be autoreleased-only.
  `NewStage`'s font comes from a factory (autoreleased) and is now
  explicitly retained; everything else the interpreter holds is either
  `-alloc`-owned or retained by its superview before the pool pops.
  Chunk 1's tests never caught this because each test ran inside one
  pool — a one-pool suite cannot see cross-pool lifetime bugs.
- **`-init` may return a different object than `-alloc`** — always adopt
  the returned id (the interpreter and macshell do; new wiring code must
  too).
- **`sgo build` does not see godep-only changes**: editing a `go-pkgs/*`
  package under an unchanged emitted tree skips the `go build` stage.
  `just mac-build` after a macshell/nativeui edit may need a manual
  `go build` in `wata-mac/.sgo/wata-mac` (or any Scala-side touch).
- **A godep's `replace` lines don't reach the app**: Go honors `replace`
  only in the MAIN module's go.mod, so macshell's local-sibling deps
  (nativeui, appleptt) each need their own `godep` line in `sgo.build` —
  a godep line is exactly a require + local replace in the emitted
  module.
- **Probing rendered output:** `bitmapImageRepForCachingDisplayInRect:`
  + `colorAtX:y:`, addressing through the rep's `pixelsWide/High` so a
  non-1 backing scale cannot skew probe coordinates (`render_test.go`).

## Verification

- `just nativeui-tests` (macOS-only, beside `bindgen-runtime`; not in
  ci — ci has no darwin-only leg): the retained invariant on the real
  toolkit (hierarchy classes/order/frames/labels mirror `applyAll`
  after both build-from-scratch and patch scripts), patch splicing and
  in-place mutation, the offscreen probe render, the dispatch seam, the
  key view's translation and phases (driven by a synthesized stand-in
  event class), the pure pixel/glyph/key tables, and macshell's wire
  grammar.
- `just mac-smoke` (~30s, standalone like tui-smoke; macOS-only, not in
  ci): one fresh wata-server; alice's headless session asserts the
  contact-list NATIVE hierarchy line by line, then bob (a tui session)
  sends a voice message MID-SESSION and the smoke asserts the printed
  differ script is EXACTLY the unplayed-badge insert into the family
  row — then the key path (real kVK codes through the real table) opens
  the conversation and the native tree shows the message row. Then the two
  AUDIO legs under `WATA_MAC_AUDIO=fake`: OK plays bob's message (the differ
  prints the play triangle then the played check) and PTT records one (the
  overlay counts up, alice's own row and the SENT flash appear, and a fresh
  bob session reads the ~1.2s message back off the server).
- `just mac-iroh-smoke` (~1min, macOS + cargo, not in ci): the same
  headless client over the iroh transport — one wata-server in iroh mode,
  three provisioned node keys, and a homeserver URL (`http://wata.iroh`)
  whose host RESOLVES NOWHERE, asserted up front, so no fallback to TCP
  can quietly rescue a broken iroh path. The contact list appearing is
  the proof that login, sync and room state all crossed iroh; bob (a tui
  session over the same transport) then sends a voice message and the
  differ patches exactly the badge in. Its second half is the negative
  that justifies the failure policy: `WATA_IROH_CONFIG` pointed at a
  config that cannot produce a client must show `transport unavailable`
  / `check config` and never `waiting for network`. Verified to be a real
  oracle — restoring the old hard-coded `false` fails it.
- `just macaudio-tests` (macOS-only, not in ci): go-pkgs/macaudio's own
  codec and fake-backend tests.
- `just facade-check` (in ci): the `go.audio` pair, and the `go.irohnet`
  pair against wata-tui's.
- The owner's leg: `just mac` against a live server — look at it,
  keyboard only, and actually talk to a handset.
