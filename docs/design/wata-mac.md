# wata-mac — design notes

The macOS client (plan 0032): every wata screen is already a pure
`wataui` body, and the retained interpreter that renders those bodies
as an NSView tree on a scaled 160×128 stage is Sgola too — `MacStage`
(`wata-mac/src/main/scala/interp.scala`, plan 0038), over facades on
the generated appkit bindings. `wata-mac/` drives it with the SAME
`WataLogic` bodies and input logic the device runs, a frame pump over
`ClientHandle`, and `go-pkgs/macshell` as the thin AppKit shell. Audio is real (plan 0033): the device's own audio thread
over a macOS backend. Transport is plain TCP by default and embedded
iroh when configured (plan 0034, below) — which is the case the mac
exists for: a parent away from home, behind a NAT, with no route to the
family's Pi.

## The layers

| layer | what it is |
|---|---|
| `go-pkgs/appleptt/appkit` | generated AppKit bindings (bindgen target `appkit`; see [bindgen.md](bindgen.md) for what was refused and why it shaped the backend) |
| `go-pkgs/nativeui` | plain Go GLUE under the Sgola interpreter: the dispatch seam + pool brackets, the raw-code key view, and `glue.go` (cross-class casts, named-scalar wrappers, the raw-RGBA bitmap crossing) — each function's header states why it cannot be a facade binding |
| `go-pkgs/macshell` | plain Go: the shell `wata-mac` binds — the window (or the headless flag), the raw key queue, TreeDump, and the NATIVE CHROME (login sheet, menu bar, Settings and Devices windows, notifications + Dock badge) |
| `go-pkgs/macaudio` | plain Go, no cgo: the Opus codec (AudioToolbox's C AudioConverter over purego) and the capture/playback engine (AVFAudio), presenting `go-pkgs/audio`'s API |
| `wata-mac/` | the Sgola module: caps, the frame pump, the retained interpreter (`MacStage` + pixels/glyphs/keys), the appkit + glue facades, the headless command loop, `interptest` |

## Running it

```
just mac-build
WATA_MAC_USER=alice WATA_MAC_PASS=testpass123 just mac     # the window
just mac-smoke                                             # the headless gate
```

Login is from `WATA_MAC_HS`, `WATA_MAC_USER`, `WATA_MAC_PASS`, positional
arguments overriding — but only on the FIRST run. After that the app
logs itself in from its own stores (below), so `just mac` alone is
enough. `WATA_MAC_SCALE` sets the stage's integer scale (default 4 → a
640×512 window); `WATA_MAC_HEADLESS=1` selects the smoke's mode (below).
Startup prints `ready <userId>` or `login failed`.

## What the app remembers (`config.scala`, plan 0036)

Three stores, split by what each thing is:

| what | where | why not elsewhere |
|------|-------|-------------------|
| access token, password | login Keychain, service `wata`, account `<user>@<homeserver>` | a secret does not belong in a file or an environment variable |
| homeserver, username, user id, preferences (including the walkie-talkie toggle, `notify_mode`) | `~/Library/Application Support/wata/config.json`, 0600 | not secret, and readable when something is wrong |
| queued voice messages | `…/wata/outbox/eN.msg` | it was `MemOutbox`: a recording queued during an outage died with the window |

Resolution order for one run: an explicit argument or environment
variable, then the stored identity, then the default homeserver **last**
— a default applied earlier silently wins over the stored homeserver, and
since the keychain account is keyed by homeserver, every lookup then
misses in a way indistinguishable from having stored nothing.

The **password** is stored, not just the token, because this window has
nowhere to type one: the screens are wata-fb's bodies, a keyboard-driven
contact list with no text entry. A token that expires or is invalidated
server-side would otherwise strand the app on its boot screen with no
user-reachable fix, so the recovery path has to be unattended.
`WATA_MAC_NO_SAVE_PASSWORD` opts out.

`WATA_MAC_NO_KEYCHAIN=1` turns the keychain off entirely. Every harness
sets it (`MacSession` does, so mac-iroh-smoke inherits it) along with a
scratch `WATA_MAC_CONFIG` — a smoke that already has credentials in its
environment must not leave an item per run, keyed by a random scratch
port, in the developer's login keychain.

**Gotcha: keychain ACLs are keyed to the binary's code signature.** An
unsigned build — what `just mac-build` produces — gets a fresh identity
every rebuild, so macOS re-prompts and "Always Allow" does not stick
across builds. That is the cost of not having a signed bundle, not a
bug; signing and bundling are their own piece of work.

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

## The chrome (plan 0037)

Two surfaces, kept apart on purpose. The **stage** is 160×128, wata-fb's
own bodies, the 10-key vocabulary — the handset's contract, and nothing
is added to it for the sake of a desktop. Everything a mac user needs
that the handset does not have is **native chrome around it**: the login
sheet (`macshell/login.go`), the menu bar (`menu.go`), the Settings window
(`prefs.go`), the Devices window (`devices.go`) and the arrival
notifications (`notify.go`). iOS reuses the
first and rewrites the second, which is what this app exists to prove.

The menu bar is mostly not features. Without one there is no ⌘Q, no
About, and — the one that matters — no ⌘V, so a login sheet cannot be
pasted into and every password manager is useless. Almost every item
targets **nil**, which sends the action down the responder chain to
whatever is focused: `terminate:`, `hide:`, `paste:` and
`performMiniaturize:` are AppKit's own, and nil-targeting is how Edit
reaches the sheet's text fields without macshell knowing they exist.

The two items that are ours — Settings and Sign Out — cannot work that
way, because signing out means ending the session and clearing the
stores, which belongs to the Sgola side. They push a string onto a
**command queue** (`NextCommand`, the same shape as the key queue) that
the pump polls once a frame. A menu action must not block the main thread
waiting for the pump, and this way it does not.

`Pump.windowedSession`'s outer loop therefore has three endings rather
than two: `quit` ends the app, while `rejected` (the server refused the
account) and `signout` (the user asked) both forget the secrets and
return to the sheet. They share every line except who decided.

### Arrival notifications and the Dock badge

A message landing while the window is behind another one used to be
invisible. Now: a `UNUserNotificationCenter` banner naming the sender, the
Dock tile badged with the unplayed count, and a **walkie-talkie toggle** in
Settings — play it right away, or announce it and let the user press OK.

**The model is `wataclient`'s, not the mac's** (`notify.scala`:
`NotifyMode`, `Arrival`, `Notify.step`). Both clients answer the same two
questions — which arrival is worth announcing, and what an arrival *does* —
so the handset half reuses this and only the presentation differs.

- **The edge is the unplayed count rising with a NEW newest unplayed
  message** (plan 0043). The count is what the sync engine already computes
  and what the contact list already badges, so the banner, the Dock badge
  and the screen cannot disagree — there is one number, not a second
  notification channel through the sync engine to drift away from it. The
  newest-id half is what keeps backfill quiet: the runtime's backfill walk
  appends older unplayed history long after a room's first snapshot, raising
  the count with no live event behind it — the newest stays the newest, so
  the badge moves and nothing announces. The pump carries the previous marks
  in `PumpSt.marks` and runs `Notify.step` once a frame, off the snapshot
  the frame already read.
- **Priming is once per session, latched on sync-caught-up.** The first
  snapshot with `caughtUp` true — the first fully processed `/sync` round,
  conversations or not — records the marks silently, so a launch with a
  backlog badges it instead of bannering every message in it, even when the
  engine delivers that backlog across several snapshots. A fresh account
  primes on an empty picture. After priming, a room seen for the first time
  counts from zero — which matters more than it looks: **a DM room is
  created by its first message**, so the room, the conversation and the
  message all appear together, and priming per-conversation would silence
  exactly the arrival most worth having.
- **Frontmost silences the banner, not the walkie-talkie.** Someone looking
  at the window has already been told, and an app that banners over itself is
  what people turn notifications off for. Playing is different: a
  walkie-talkie does not go quiet because you happen to be holding it.
- **Auto-play is the OK path, not a second one.** `announce` sends the same
  `ActPlay` the applet's `playSelected` sends and marks the applet as playing
  (`WataLogic.withPlaying`), so the existing `AePlaybackDone` arm sends the
  read receipt — an auto-played message really becomes played rather than
  badging forever. It waits rather than queueing when something is already
  playing or PTT is held: the audio thread does one thing at a time, and a
  burst playing back to back over itself is worse than one the user presses
  OK on.
- **Every arrival prints one decision line**, in both drivers:
  `notify: play|banner|suppressed "<title>" "<body>" badge=<n>`. That is the
  assertable part — whether macOS drew a banner is macOS's business — and it
  is worth having in the windowed log for the same reason the `net:` lines
  are.
- **Both AppKit halves are gated on a bundle.** `UNUserNotificationCenter`
  reads the process's bundle proxy on its first call and raises when there is
  none, so an unbundled build (`just mac-build`, every harness) must never
  touch it; the Dock tile is the same story for a different reason, since
  headless brings up no `NSApplication` at all. `macshell.Notify` therefore
  answers a REASON string rather than failing silently, and the pump logs it
  (`(no bundle)` is what a harness run shows). Authorization is asked for
  once, from `Start`, so the prompt is part of launching rather than a dialog
  that interrupts the first arrival.
- **The mode is persisted with the other preferences**, as `notify_mode` in
  `config.json` — never in the keychain, it is not a secret. It is
  deliberately NOT a third `FbPrefs` field: the shared settings applet
  constructs that record positionally, so a field added for a control the
  handset does not have yet would have to appear on the device too.
  `config.scala` holds it in a cell that every write path re-emits, primed
  from the file by `Main` before anything else runs.
- **The checkbox reports, it does not act.** Like the menu items, it pushes
  `notify:play`/`notify:quiet` onto the command queue and the pump persists
  the choice; the chrome keeps only enough to draw the control
  (`SetNotifyPlay`).

Bindings: `usernotifications` is a bindgen target of its own and
`NSDockTile`/`NSBundle` joined `appkit`, so none of this is raw `objc.Send`.
The block risk plan 0037 flagged did not bite — see
[bindgen.md](bindgen.md).

### The Devices window — the admin surface

What `wata-tui` does with `wifi` / `join` / `wifi off` and the `/admin`
enrolment API, as a window (`macshell/devices.go` + `wata-mac`'s
`devices.scala`): pick a handset, see what its radio can find, hand it a
network and a password, drop its wifi for ten minutes to prove the cellular
fallback works, and approve or deny a handset that has just announced itself.
`Wata ▸ Devices…` (⌘D) opens it.

Nothing new goes on the wire — every one of those is the device-command
mailbox (plans 0020/0031) or the admin enrolment API (plan 0027), the same
requests the tui makes, including the skew-free report wait (a report carries
a server-stamped `seq`, and the answer to *this* scan is the seq moving past
what it was before the queue).

- **The chrome holds no logic and does no I/O.** A button reads its controls
  and pushes a command string onto the queue the menu items already use
  (`NextCommand`); the setters (`SetHandsets`, `SetNetworks`, `SetPending`,
  `SetRoster`, `SetDevStatus`) are how the answer comes back.
- **The work runs on its own goroutine**, forked beside the audio thread. A
  scan may take a minute — the handset has to hear the command, run a scan and
  report — and a pump that waited for it would freeze the stage and stop the
  window redrawing. The pump only relays: `trySend`, never a blocking send, so
  a busy worker cannot stall a frame.
- **The password lives in an `NSSecureTextField` and nowhere else.** It is
  never in a command string, never in a log line, never in the config. The
  session collects it with `TakePSK`, which reads the field and CLEARS it in
  the same call, and it goes into the request body only. The printed line says
  `psk=<n> chars` — enough to tell an empty field from a typed one without
  saying what was typed. This is wata-fb's own reasoning: its join helper
  pipes the PSK over stdin because argv and the environment are world-readable
  (`wata-fb/netexec.scala`), and a queue string some future log line prints
  would undo it.
- **Approve and Deny state the whole decision before the click.** Both are
  irreversible from the user's side — denying a handset a parent has just
  unboxed sends them back to the box — so a sentence beside the buttons names
  the device (the leading node-id digits, which is what the handset's own
  enrolment screen shows) and the account it will be bound to, and says what
  Deny costs. An account name that is not on the roster creates it, which is
  plan 0027's ruling: a casually minted name is renameable, an interrupted
  onboarding is not.
- **The status line states outcomes, not verbs** — plan 0027's field
  follow-up: a parent read a terse successful approve as an error.
- **The three lists are `NSPopUpButton`s, not `NSTableView`s.** A table needs
  a data source whose delegate answers rows; a popup is a native list you pick
  one thing out of, which is what all three are for, and it reads back as
  titles and a selected index — which is what makes the window assertable with
  no mouse. A redraw rebuilds every control, so the selections and the typed
  account are explicitly carried across it: a scan report arriving must not
  move the handset the user picked out from under them.
- **Headless has no window, and that is fine.** An `NSWindow` cannot be
  instantiated off the main thread, so headless builds only the content view —
  the whole assertable surface — the same builder/installer split `login.go`
  and `prefs.go` use.

**Gotcha, and it is not visible in a signature: AppKit's convenience
factories block off the main thread.** `+[NSButton buttonWithTitle:target:
action:]` is generated and correct, and hangs forever when called from the
headless stage thread or a test goroutine — the factories configure the
control through the appearance machinery, which waits on the main runloop.
`-alloc` + `-initWithFrame:` does not, and is what this window uses.
`-[NSPopUpButton initWithFrame:pullsDown:]` is fine headless.

**Settings shows the account; it does not edit it.** The token is scoped
to the (server, name) pair, the Keychain items are keyed by it, and the
running client is bound to it — an editable field would pretend a text
edit could do what only a re-login can. So the window names the account
and offers `Sign Out…`, which returns to the sheet prefilled with what
was there. That is also the "switch account" path.

Main-thread rules bit twice here and shaped the code: AppKit throws on a
`setMainMenu:` off the main thread, and refuses to instantiate an
`NSWindow` at all. Both are therefore split builder-from-installer
(`buildMainMenu`, `fillPrefsView`) so the structure can be asserted from
a test goroutine — the same bargain `login.go` struck, and for the same
reason: this machine has no screen-recording grant, so the structure is
the oracle.

## The frame pump (`main.scala`, `Pump`)

One pump shape, two drivers. Each frame: drain macshell's key queue
into `WataLogic.handleInput` (the queue carries RAW macOS virtual key
codes; `MacKeys.translate` runs at the drain, so the window path and
the harness's injected codes share one table), tick `WataLogic.update`,
read `Handle.snapshot()`/`connection()`, run `NetStatus.poll`, build
the body, then `Diff.diff` against the last tree and hand the script to
`MacStage` by DIRECT call (`submitTree`/`submitScript` — the interpreter
is Sgola, so nothing is encoded; the frame wire is gone). Bodies and
the diff run on the pump goroutine; only the stage apply touches
AppKit.

- **Windowed** (the default): `macshell.Start` runs on the main
  goroutine — macshell's package init pins it to the main OS thread —
  then `MacStage.create` builds the stage on that same thread and
  `macshell.AdoptRoot` splices its root below the key view; the pump is
  forked and `macshell.RunApp` (NSApplication.run) owns the main
  thread. Frames tick on `Handle.waitEvent(33ms)`: the dirty-flag
  channel is the wake-up, the deadline keeps held-key timers advancing.
  An apply PUBLISHES its patches into `MacStage`'s pending cell and
  `nativeui.OnMain` dispatches a module-registered `go.callback`
  trampoline onto the MAIN QUEUE, which drains and applies — one frame
  per queue turn, each inside the dispatcher's autorelease pool. The
  two-step quit (Back on the contact list, again within 2s — the
  device's own gesture) leaves through `macshell.Terminate`.
- **Headless** (`WATA_MAC_HEADLESS=1`): no NSApplication, no window, no
  runloop — and no second thread: the main goroutine (locked by
  macshell's init) IS the stage's thread. Applies run INLINE,
  pool-bracketed, and the main goroutine is a line
  command loop: `wait <ms>` pumps frames and prints every applied patch
  (`patch <script line>`, `DiffOracle.showPatch`'s own rendering — the
  smoke's window onto the differ), `tree` prints the live NATIVE
  hierarchy (class, frame, label text per view, group descent only),
  `key <name> <press|release|repeat>` injects a macOS virtual key code
  through the real translation table, the `dev …` family drives the Devices
  window with no mouse (`dev show`, `dev sel <list> <i>`, `dev psk` — which
  reads the password from the NEXT line, as wata-tui's `join` prompt does —
  `dev acct <name>`, `dev click <button>`, `dev decision`, each click ending
  in a `dev done <button>` terminator because an operation answers with a
  variable number of lines), `quit` winds down. This is what
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

## The retained interpreter (`MacStage`, Sgola — plan 0038)

The interpreter is Sgola (`wata-mac/src/main/scala/interp.scala`),
consuming wataui's `View`/`Patch` directly over facades on the
generated appkit bindings: `appkit.scala` is the FACADE-VALUE-STRUCT
spelling (geometry as value-struct case classes, ObjC handles as
zero-field bound-subset case classes), and `go.nativeui` binds the Go
glue for what a facade cannot say — cross-class casts, methods whose Go
signatures carry defined scalar types (FACADE-GO-NAMED-SCALAR), the
raw-RGBA bitmap crossing, the pool brackets and the main-queue seam.
There is NO mirror of the algebra anywhere and no wire: the differ's
script is applied as the values it already is.

- **The stage is the unit.** `MacStage.create(scale, windowed)` makes
  one container NSView of `scale·160 × scale·128` (default scale 4 →
  640×512); `submitTree` mounts a view tree (a root `PSet` in the patch
  vocabulary), `submitScript` runs a differ script in script order.
  The retained node tree is IMMUTABLE (`MacNode` — view, native handle,
  kids), rebuilt along the patched spine; the whole stage state lives
  in one atomic cell only the stage's thread touches. `mirror()` is the
  plain view tree the native tree currently shows — `Patches.applyOne`
  per patch, and the invariant `interptest` holds against the real
  hierarchy.
- **Element table:** VText/VGlyph → `NSTextField` label, VRect → `NSBox`
  (custom, borderless, `fillColor`; NOT a layer-backed view — CGColorRef
  is unmappable, and NSBox draws via `drawRect:` so offscreen renders
  work), VImage → `NSImageView` over RGB565→RGBA widening +
  nearest-neighbour pre-scaling (pixels.scala), handed across as raw
  RGBA into ONE `NSBitmapImageRep` (`glue.ImageFromRGBA` — the
  `initWithBitmapDataPlanes:` shape is a bindgen refusal, so this is
  the one raw-pointer crossing, in objcrt style; the PNG detour is
  gone), VGroup → plain container NSView spanning the full stage.
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
  (`glyphs.scala`: ✓ ▶ ★ ↑ ⚠ ≈ Ψ for the codes wata's bodies emit);
  anything unmapped renders `□`, visibly wrong on purpose.
- **Paint order = subview order**, AppKit's own rule; `PInsert` uses
  `addSubview:positioned:NSWindowBelow relativeTo:` to splice at an
  index, `PSet` mutates properties in place when the constructor is
  unchanged and `replaceSubview:with:` otherwise.
- **The key view** (`keyview.go`, still Go — class synthesis): a
  synthesized `WataKeyView` NSView subclass — acceptsFirstResponder
  YES, keyDown:/keyUp: forwarding RAW keyCodes with press/release/
  repeat phases (autorepeat arrives as `PhaseRepeat`, not a second
  press — the hold gestures need real edges). Synthesis follows
  bindgen.md's encoding rules: these selectors take only objects/BOOL,
  so purego's derived encodings ARE the true ones. Translation is the
  Sgola drain's (`mackeys.scala`: arrows, return/keypad-enter, esc,
  space=PTT); codes the table does not know are dropped there, and
  nothing is forwarded up the responder chain either way. macshell
  overlays the view on the stage and makes it the window's first
  responder.

## Threading and lifetime facts

- **`MacStage` functions run on the AppKit thread and never hop by
  themselves.** `submit*` owns the seam per mode: windowed, the pump
  publishes into the pending cell and `nativeui.OnMain` dispatches the
  module-registered `go.callback` trampoline onto the main queue
  (`nativeui.MainQueue().Async`, libdispatch over purego — proven
  headless on a private serial queue in `dispatch_test.go`); headless,
  the caller IS the stage's thread and the apply runs inline. The
  chrome's `onStageSync` follows the same per-mode rule (inline
  headless, main-queue-and-wait windowed).
- **Headless AppKit works.** View construction, mutation and
  `cacheDisplayInRect:` offscreen rendering all run with NO
  NSApplication, no runloop, no window, on a locked OS thread inside an
  autorelease pool — `mac-smoke` and `interptest` assert hierarchies
  without ever opening a window. (Headless there is no second thread at
  all any more: the locked main goroutine is the stage's thread, which
  is what makes TreeDump-after-`wait` coherent by construction.)
- **Autorelease pools are the caller's job**, one per frame/apply —
  the Go dispatcher wraps the windowed callback, `MacStage.submit`
  brackets the headless inline apply, macshell wraps every chrome
  excursion. Corollary found the first time two pools ran: an object
  the stage KEEPS must not be autoreleased-only. `MacStage.create`'s
  font comes from a factory (autoreleased) and is explicitly retained
  (`glue.RetainFont`); everything else the interpreter holds is either
  `-alloc`-owned or retained by its superview before the pool pops.
  Chunk 1's tests never caught this because each test ran inside one
  pool — a one-pool suite cannot see cross-pool lifetime bugs.
- **`-init` may return a different object than `-alloc`** — always adopt
  the returned id (the interpreter and macshell do; new wiring code must
  too).
- **`sgo build` sees godep-only changes now** (fixed upstream
  2026-08-08, consumed by the current pin): the go-build stage hashes
  godep sources, and its SKIP line names input categories with counts
  (`… 6 godep trees/146 files`) — verified live during the interp port.
  The old failure mode (a stale binary after a `go-pkgs/*` edit) is
  history; the tell, if it ever recurs, is a Go BuildID that does not
  move between two builds that should differ, and the escape hatch is
  deleting `.sgo/build-<goos>-<goarch>.hash`.
- **A godep's `replace` lines don't reach the app**: Go honors `replace`
  only in the MAIN module's go.mod, so macshell's local-sibling deps
  (nativeui, appleptt) each need their own `godep` line in `sgo.build` —
  a godep line is exactly a require + local replace in the emitted
  module.
- **Probing rendered output:** `bitmapImageRepForCachingDisplayInRect:`
  + `colorAtX:y:`, addressing through the rep's `pixelsWide/High` so a
  non-1 backing scale cannot skew probe coordinates (`render_test.go`).

## Growth while idle — CLOSED (`MAC-IDLE-LEAK`)

**Resolution (2026-08-08): sgola retired `slab List` from core's default
build, landed at `65e8bae`; the pin moved `1c6d6ed -> 65e8bae` and the
verdict series is flat.** With the new pin, `sgolaSlabPool` no longer
appears anywhere in wata-mac's emitted Go (was 60 occurrences), and the
committed judge — the live heap after each GC — reads dead flat on both
the diffonly arm and the full untouched app:

```
diffonly:  live heap after each GC (MB): 0 0 0 0 0 0 0 0
full app:  live heap after each GC (MB): 0 0 0 0 0 0 0 0
```

(Pre-fix, diffonly read `1 1 1 2 2 3 3 4 5 6 5 7 8 9 10`.) Full ci green
on the same pin, including the objc-spike oracle. The rest of this
section is the record of the mechanism and the eliminations; the bisect
arms below stay committed for regression re-checks.

Long-horizon confirmation (2026-08-08, pin `7c228f9`): a 20000-round
windowed soak — ~16000s of frames — held the live heap after GC at
0 MB -> 0 MB across 783 collections:

```
live heap after each GC (MB): 0 0 0 0 0 0 0 0 0 0 0
```

RSS fell over the run, 84.0 -> 60.6 MB (peak 89.0 reclaimed). Verdict
steady.

The app grew without bound while doing nothing. macOS paused a
long-running instance at **26 GB** (owner, 2026-08-08). **The cause is
sgola's `slab List` allocator** (core/sgo.build, OPT-D tier), proven by a
controlled on/off experiment: cons cells are carved 256 at a time out of
one buffer, the GC scans a slab as ONE object, and a semantically dead
cell's pointer fields are never zeroed — so a slab is as live as its
livest cell, and dead cells' referents are retained with it. In the
pump that composes into cross-generation chaining: frame N's live tree
cells share slabs with frame N's transient diff-mirror cells, whose
fields reference frame N-1's tree, which pins frame N-1's slabs, and so
on — every frame's tree held forever. Removing `slab List` from the
pinned core and rebuilding turns the diff-only arm's 1→9 MB climb and
the full app's unbounded growth into a dead-flat `0 0 0 0 0 0 0 0`,
with nothing else changed. Filed upstream as
`SLAB-DEAD-CELLS-RETAIN`; no consumer-side workaround existed (the knob
was core's), so the leak shipped until the retirement landed and the
pin moved (the resolution above).

The bisect arms that found it are COMMITTED, env-gated by
`WATA_MAC_LEAK_ARM` and driven by `just mac-leak --arm
build|diffonly|diffself|consttree`: build = tree only (flat), diffonly =
diff against the previous frame, nothing sent (leaks — the profiling
state), diffself = `Diff.diff(v, v)` (flat), consttree = the full
pipeline over a constant tree (flat). Idle frames emit ZERO patches
(consecutive trees are equal), the standalone differ loop is clean on
every shape (`tools/diff-spike`, six arms), and the pump, seam and
retained backend are all exonerated — the leak needed exactly: slabs
on, real trees, and the previous frame as the `old` argument. Note for
whoever verifies the upstream fix: the tight single-goroutine spike
does NOT reproduce the chain even with slabs on — judge the fix on
`just mac-leak --arm diffonly`, never on a microbenchmark.

The rest of this section records what was ruled out along the way, so
the eliminations survive.

`just mac-leak` drives pure idle frames and samples three numbers,
because it is reading them TOGETHER that localizes the growth:

```
RSS          43.0 MB -> 53.8 MB   (peak 54.3, trough after peak 53.8)
Go live heap  1 MB -> 10 MB       (GODEBUG=gctrace=1)
OS threads    6 -> 14             (monotonic, GODEBUG=schedtrace)
VERDICT: GROWING
  (low-water 43.0 MB -> 50.4 MB: the troughs rise)
```

**It is a real leak, it is Go objects, and the diff step is what causes
the retention.** Confirmed in the WINDOWED app — the shape the 26 GB
happened in — over ~11 minutes. The number that settles it is the live
heap after each collection, which is what the app is still holding once
everything unreachable has been removed:

```
live heap after each GC (MB): 1 2 4 6 8 11 14 18 21 24 27 31 34
```

A straight line across 39 collections, no plateau. ~3.4 MB/min is
~200 MB/hour, which reaches 26 GB in about five days and matches "days of
running". A bounded working set plateaus; this does not.

**The bisect**, headless (which leaks the same way, so it is the cheap rig
— see below for why that is now trustworthy). Each step ran ~220 rounds
and the series is the live heap after GC:

| what ran | series | verdict |
|---|---|---|
| everything | `1 1 2 3 4 5 7 9` | leaking |
| tree built, `patchTo`/`setTree` skipped | `0 0 0 1 0` | **flat** |
| `Diff.diff` + `Wire.encode*`, nothing sent to Go | `1 1 2 2 3 3 4 6 8` | leaking |
| `Diff.diff` only, no encode, nothing sent | `1 1 1 2 2 3 3 4 5 6 5 7 8 9 10` | leaking |

So building a fresh view tree every frame costs nothing that a collection
does not reclaim — the flat row is the control, and it also rules out
`WataLogic` and the client snapshot. `macshell.Apply` and the retained
AppKit backend are ruled out too: the leak is identical with nothing
handed across the seam. What is left is `Diff.diff`.

The heap profile taken in that last state names what is retained, and it
is **not** the patches:

```
4149kB 47.36%  main.sgolaNewColon2__Keyed
1536kB 17.53%  main.WataLogic_bodyLive.func2 (inline)
1536kB 17.53%  main.WataLogic_contactRowView      (cum 64.89%)
 515kB  5.88%  main.sgolaNewColon2__I   <- Diff_pathOf, the only Diff cost
```

The retained objects are the VIEW NODES; `Diff` itself accounts for half a
megabyte of path lists. So diffing a tree is what makes that tree stay
reachable, and each frame's tree is then held forever. The pump retains
exactly one (`st.last`), and `contactRowsView` builds only `visibleRows()`
rows, so neither the tree nor the pump explains it.

The retaining edge turned out to be BELOW all of this — the slab
allocator, per the head of this section. `wataui/diff.scala` has no
global state (every binding in it is a local `var`), the standalone
differ loop is flat on every shape it can spell, and the diff call
matters only because its transient mirror lists are what interleave
references to the previous frame's tree into the live tree's slabs.

**RSS here is a sawtooth, and that invalidates any short run.** It climbs
for a couple of minutes and then the scavenger hands pages back, dropping
it to near where it started. A 60-round run is ~48 seconds and sits
entirely on a rising edge, so it reads GROWING every single time, and
first-minus-last over such a run is the slope of that edge rather than a
leak rate. An earlier reading of "+4-5 MB per ~40s, so ~400 MB/hour, so
~60h to 26 GB, which matches the uptime" was that arithmetic done on a
rising edge; the agreement with the uptime was a coincidence. What
separates a leak from the sawtooth is whether the TROUGHS rise, which is
what the verdict now compares (low-water of the first quarter against the
last), and a run with no reclaim in it at all now reports INCONCLUSIVE
rather than GROWING. The default is 200 rounds because fewer cannot
contain a reclaim.

**Ruled out — goroutines.** `just mac-leak --goroutines` SIGQUITs the app
at the end and groups the dump by creation site. There are **23**
goroutines after a full run and the largest group is the runtime's own 8
GC workers; every application site appears once or twice. So the thread
count is not goroutines that never finish, which was the leading
hypothesis, and threads climbing to 14 and stopping is a plateau rather
than a leak. (A signal traceback spells its header `goroutine 12 gp=0x…
m=nil mp=nil [select]:`, not the `goroutine 12 [select]:` that
`runtime.Stack` produces — a parser anchored to the latter reports an
empty dump for a dump that arrived in full.)

**Go objects — confirmed, and now explained.** The earlier "live heap is
flat at 1-2 MB" was measured over ~48s, which is too short: over 200s it
reaches 3-10 MB. `--heap` writes periodic heap profiles and prints what
grew between the first and last (`go tool pprof -base`, since a single
profile is dominated by steady-state objects and says nothing). What it
names is view-tree cons cells (`sgolaNewColon2__Keyed` under
`Pump_frame`) — which is exactly what the slab mechanism at the head of
this section retains: the profile's alloc sites are the trees, the
retainer is their slabs.

Retained ObjC objects stay ruled out: two `heap <pid>` censuses 60 rounds
apart are class-for-class identical (diff the `COUNT`/`BYTES` table under
the `CLASS_NAME` header).

`--vmmap` diffs the dirty size of each region between two points in the
run, which separates thread stacks from a malloc zone from the Go arenas.

**The caveat that did NOT survive.** It used to be recorded here, and in
the ticket, that `mac-leak` runs headless where main-queue work is never
drained, so `nativeui/dispatch.go`'s `pending` map grows — a growth source
the windowed app lacks. **That is false.** Both `MainQueue().Async` call
sites in this package (`shell.go`'s `onStage` and `login.go`'s
`onStageSync`) open with `if hl { … ; return }` and hand the work to the
headless stage thread over a channel instead, so headless never reaches
the main queue at all and `pending` never grows there. The caveat was
costing the harness credibility it had not lost.

What remains true is the narrower half: headless draws no real view tree
and runs no runloop, and the 26 GB was a **windowed** run. That is why the
heap profiler is in this package behind `WATA_MAC_HEAP_PROFILE` rather
than in the harness — a profile that can only be taken headless cannot
settle a windowed bug. Pointing it at a windowed run over hours is the
next probe, and the only one that can.

## Verification

- `just nativeui-tests` (macOS-only, beside `bindgen-runtime`; not in
  ci — ci has no darwin-only leg). Two halves now. The Sgola half is
  `wata-mac interptest` (interptest.scala, an argv mode of the app
  binary — the binary exits 0 either way, so the recipe greps the exact
  verdict line): the retained invariant on the real toolkit (hierarchy
  classes/order/frames/labels mirror `applyAll` after both
  build-from-scratch and patch scripts), patch splicing and in-place
  mutation (native identity via `glue.SameView`), the offscreen probe
  render, the applyAll hand-expectation cases, and the pure
  pixel/glyph/key tables. The Go half: the dispatch seam
  (`dispatch_test.go`), the key view's RAW-code forwarding and phases
  (`keyview_test.go`, driven by a synthesized stand-in event class),
  the login sheet's controls (`login_test.go`), and the menu bar
  + Settings content (`menu_test.go`: every item's title and key
  equivalent, that Quit reaches `terminate:` through the responder chain
  while ours target our own object, that Edit's items target nil so they
  reach the focused field, and that refilling Settings replaces its
  labels rather than stacking a second set, that the walkie-talkie checkbox
  SHOWS the session's mode and reports the new one onto the command queue,
  and that headless `Frontmost`/`Notify`/`SetBadge` decline rather than
  reaching for an NSApplication or a bundle that is not there), and the
  Devices window (`devices_test.go`: the lists carry what the setters handed
  them and say whether a network needs a password, the password field really
  is an `NSSecureTextField` and reading it empties it, the join command
  carries the handset and the ssid and NOT the password, scan/off carry the
  picked handset and the off window, the decision sentence names the device
  and the account and what Deny costs, an empty window commits nothing, and a
  redraw keeps the selection and the typed account).
- `just mac-creds-smoke` (macOS-only, not in ci; touches the login
  keychain and cleans up after itself): three headless runs against one
  server — with a password in the environment, with NOTHING in it (which
  must still reach `ready`, off the stored identity and token), and with
  the stored token no longer valid (the server is restarted under it),
  which must recover through the stored password. Plus the assertion the
  split exists for: no `access_token` key in the config file.
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
- `just mac-notify-smoke` (~3min, macOS-only, not in ci): the arrival
  DECISION, headless — a banner naming bob while not frontmost, the same
  arrival suppressed while frontmost, a DM room created by its first message
  announcing, the badge adding up across conversations and coming back to 0
  once everything is played, walkie-talkie mode playing instead of bannering
  (proved by the NEXT arrival's badge reading 1, which means the auto-played
  one was really receipted), and the mode surviving a restart that does not
  re-announce the backlog it opens with. Two headless REPL commands exist for
  it: `front 0|1` (headless has no NSApplication to ask) and
  `mode play|quiet`.
- `just mac-devices-smoke` (~40s, macOS-only, not in ci): the admin surface
  end to end — one fresh wata-server, a harness thread playing bob's handset
  on the command mailbox, two handsets announced through the enrolment API,
  and alice's headless session driving the window's REAL controls (`dev sel`
  moves a popup, `dev psk` types into the secure field, `dev click join` calls
  the function the Join button's action calls). It asserts the REQUESTS, not
  the pixels: a scan aimed at the picked handset whose report becomes the
  window's rows, a join carrying the ssid and — read from the device side, the
  one place it legitimately arrives — the exact password, a SECOND join
  finding the field empty (so a password cannot be reused on a network it was
  not typed for), `wifi off` carrying its ten minutes and the verdict coming
  back, and an approve that lands the node id in the allowlist file bound to
  an account that now exists. The last check is the one the slice exists for:
  every line the app printed and the whole server log are grepped for the
  password, and a hit fails the run.
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
