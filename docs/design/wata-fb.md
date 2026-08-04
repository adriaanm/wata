# wata-fb — design notes

`wata-fb` is the client that runs on the physical device: a small
handheld (product code name BQ268, a Qualcomm MSM8909 board) with a
160x128 ST7735S-class color LCD, a keypad, a push-to-talk button, a
microphone, and a speaker. From a user's point of view it is a
walkie-talkie: pick a contact or the family room, hold a button to
record and send a voice clip, and incoming clips show up as a list
you can scroll and play back. All of it rides Matrix as the transport
— sending a voice message is uploading an Opus/Ogg file and posting an
`m.audio`-shaped event into a room; receiving is `/sync` plus
download. The device also has a "family room" concept (a shared room
everyone auto-joins) alongside 1:1 DMs.

The module is a Sgola (restricted Scala 3 → Go) application. It is
built with `../tools/sgo build` from `wata-fb/`, links `core`
(implicit), `json`, and `wataclient` (the portable Matrix client
engine that lives in a sibling module and is NOT owned by this repo
area), and pulls in one cgo Go dependency, `go-pkgs/audio`, for Opus
and ALSA (tinyalsa) access. `wata-fb/sgo.build` and `sgo.deps`
describe this; see `wata-fb/go.mod` for the Go-level requires.

wata-fb builds two ways:

- **Native (dev host, e.g. darwin)**: `sgo build`/`sgo run` compile
  everything, including the framebuffer/evdev/LED code (Linux
  syscalls happen to also exist as no-ops or errors on darwin because
  `go.syscall` binds the `syscall` package, present on both), but the
  `go-pkgs/audio` cgo package is replaced by `audio_stub.go` (see
  `go-pkgs/audio/audio_stub.go:1`), whose build tag excludes
  `linux && arm`. Every audio entry point returns an error
  immediately, so the process doesn't hang or crash, but nothing can
  actually run end to end without the device.
- **Cross-compiled (linux/arm, the actual device)**: built with
  `zig cc -target arm-linux-musleabihf` as the C compiler (see
  `tools/fb-deploy.sh:29`), which lets the toolchain cross-compile
  cgo without a native ARM sysroot. `go-pkgs/audio/audio_linux.go`
  and the vendored, prebuilt static libs under
  `go-pkgs/audio/clib/arm/` provide the real opus + tinyalsa objects.

## Process structure

`main.scala` is a flat command dispatcher (`object Main.main`,
`wata-fb/src/main/scala/main.scala:15`): it inspects `args(0)` and
routes to one of about a dozen subcommands — there is no subcommand
framework, just a chain of `if/else if`. The two "real product"
entry points are:

- `wata-fb ui [base] [user] [pass]` — the actual on-device client
  (`Ui.run`). Every credential is optional: what the arguments do not
  give comes from the stored session (see "Session persistence").
- `wata-fb login|voicesend|voiceplay|audiosoak ...` — scripted,
  non-interactive drivers used for development and load testing
  (`DevCli`, `devcli.scala:20`).

Everything else (`synctest`, `oggtest`, `oggforeign`, `fbdump`,
`fbsmoke`, `syncfix`, `integ`, `sim`, `uitest`, `--selftest`) is
test/oracle tooling, covered under "dev/test surface" below.

`Ui.runUi` is the process shape for the live client: it opens and
mmaps `/dev/fb0`, builds a `wataclient` `MatrixClient` via
`Runtime.makeWithAudio`, then runs everything inside one
`sgo.supervised { ... }` structured-concurrency scope. Inside that
scope:

- the **audio thread** runs as a `fork` of `AudioThread.mainLoop`
  (`audiothread.scala:50`), driven by a command channel
  (`sgo.Chan[AudioCmd]`) and reporting back on an event channel
  (`sgo.Chan[AudioEvt]`);
- `Runtime.start(c)` (owned by `wataclient`, not this module) spins up
  the sync loop and the action-queue loop as further forks;
- the **UI/main loop** runs on the calling goroutine itself — it is
  not forked. `Ui.frameStep` is ONE frame: it polls the newest state
  snapshot, drains UI events, polls input, updates the shell/applet
  state machine, renders, and presents, then sleeps ~33ms (~30fps).
  `Ui.frameLoop` is the device's run-until-quit driver over
  `frameStep`.

If any of the sibling forks fails, the whole `supervised` scope is
cancelled (structured concurrency) and the process unwinds through
the ordinary teardown path (screen cleared, LEDs off, fb unmapped).

Shared mutable state between the UI loop and the forked threads is
held in module-level `sgo.Atomic` cells in `Ui` (`stateC`, `connC`,
`idleC`, `offC`, plus `snapC`/`lastMsC`, the frame carry-over the loop
used to hold in local vars), read only by the UI goroutine by
convention and written from event handlers. `Ui.resetCells` clears
them all, which is what lets one process run several sequential UI
sessions — the scripted harness does exactly that.
`shell.scala:56-63` documents the same discipline for `ShellState`,
which additionally derives `Shareable` so it type-checks as safe to
publish across the atomic cell.

### The `UiDevice` seam

The frame loop's only contact with hardware is the `UiDevice` trait
(`ui.scala`): **input poll, present, LEDs, button/panel backlight, and
the frame pace**. Time is not part of it — the loop already takes a
`Clock` capability from `wataclient`, and `UiDevice` owns only the one
sleep. It is deliberately NOT `Shareable`: a `UiDevice` never crosses a
goroutine boundary, which is what lets the real implementation hold the
mmap'd framebuffer slice as a plain field.

Three implementations:

| impl | file | edges |
|---|---|---|
| `FbUiDevice` | `ui.scala` | the real thing: `Evdev.poll` over `/dev/input/event{0,1,2}`, `FbTest.present` into the mmap'd `/dev/fb0`, `Led.*` over sysfs. |
| `SimDevice` | `sim.scala` | a terminal: ANSI truecolor half-blocks out, raw stdin in, LEDs as colored cells in a status row. |
| `ScriptDevice` | `uiscript.scala` | deterministic: input from a script's injection cell, no display (the driver encodes the pixel buffer itself at a checkpoint). |

One sgola shape to know: `pollInput` returns a `KeyBatch`, a
non-generic record wrapping `List[KeyEvent]`, because the emitter
stamps a generic result type at the IMPL (`List__KeyEvent`) but leaves
the interface declaration a bare, undefined `List` — a `go build`
failure, not a compiler diagnostic. Any trait method here that wants a
collection result needs the same boxing.

Two device edges are NOT behind the seam and stay best-effort no-ops
off-device: `SettingsLogic.setBl` writes the backlight sysfs node
directly from the settings applet, and the `Led.*` sysfs writes never
throw. They are not blindly silent though: `Led.writeSysfs` keeps a
per-node status cell (a `val`-held `Atomic[Int]`), and the FIRST write
to a node is the hardware probe — a first-write failure marks the node
absent (every dev host; silent no-op forever), while a failure on a
node that previously wrote successfully is a real device fault, logged
once per node and then suppressed so the ~30fps frame loop cannot spam.
The write error itself is visible because `go.syscall.writeChecked`
binds `syscall.Write` a second time WITH the `(n, error)` throws
lowering (the plain `write` binding stays error-dropping for the
stdout/PNG dump sinks).

## The display stack

Geometry is fixed at 160x128 (`display.scala:17`) — there is no ioctl
probe of the framebuffer's actual size. The pixel buffer is a
`go.Bytes` of `W*H*2` bytes in RGB565, little-endian
(`display.scala:19`), so:

- presenting a frame to hardware is a raw byte copy into the mmap'd
  `/dev/fb0` (`FbTest.present`, `fbtest.scala:60`);
- the same buffer, read back on the host where there is no real
  framebuffer, becomes a PNG via a hand-rolled encoder
  (`png.scala`) for golden-frame testing.

`Draw` (`display.scala:39`) is a set of free functions over a passed
buffer — `setPixel`, `clear`, `fillRect`, `hline`, `strokeRect` — not
methods on a class; there's no object wrapping the buffer.
`Color` (`display.scala:23`) holds precomputed RGB565 constants plus
an `rgb()` helper for dynamic colors.

Text uses a 5x8 bitmap font (`Font`, `display.scala:92`), a 256-glyph
table baked in as an `IArray[Int]` literal (`display.scala:112`) —
mostly ASCII plus a handful of custom icon glyphs at 0x80-0x8B
(battery, checkmark, wifi) and a couple of block-element glyphs. There
is no vector/TrueType font path; the font table comment says it was
generated by a `scratchpad/genfont.py` script that is not present in
this module. `Font.drawText`/`drawTextCentered` lay text out on a
26-column x 15-row character grid (`display.scala:96-97`) below a
1-pixel status line.

`Png.scala` is a minimal, deliberately simple PNG encoder: single
IDAT chunk, stored (uncompressed) DEFLATE blocks chunked at the
format's 65535-byte-per-block cap (one block at the current 160x128
geometry, so the golden bytes match a single-block encoder), standard
zlib/PNG CRC-32 and Adler-32 checksums. It exists purely so the host
build can produce a byte-stable "golden frame" without needing a real
display or a general-purpose compression library. The multi-block
path — which the goldens never reach — is held green by `PngCheck`
(`wata-fb pngtest`, run by fb-smoke): encodes past the cap, then an
independent walker re-parses block headers/payloads, the Adler-32,
and the block count.

A frame gets composed like this each UI tick (`Ui.frameLoop`,
`ui.scala:96`): pick up the latest `StateSnapshot` from
`wataclient`'s Runtime, drain UI events (connection state, send/play
results) into LED and status updates, build a `FrameCtx` bundling
that frame's snapshot/connection/queues, poll input, route it into
the shell/applet state machine (`Shell.handleInput`,
`shell.scala:109`), tick per-applet timers (`Shell.update`,
`shell.scala:149`), and finally `Shell.render` writes into the pixel
buffer and `FbTest.present` blits it to the framebuffer — skipped
entirely while the screen is blanked by the idle timeout.

The **UI/applet model** (`shell.scala`, `applets.scala`) is a small,
explicit state machine, not a generic widget framework:

- `Shell` (`shell.scala:65`) holds an `IArray[Applet]` and an active
  index; `Applet` (`applets.scala:506`) is a three-method interface
  (`handleInput`, `update`, `render`) implemented by immutable,
  wither-style state records rather than mutation. There are exactly
  two applets today: `WataApplet`/`WataLogic` (contacts + conversation
  view, PTT recording, playback — `applets.scala:38-479`) and
  `SettingsApplet`/`SettingsLogic` (menu: audio echo test,
  brightness, screen timeout, display name, disconnect, info —
  `applets.scala:532-769`).
- Input routing has two special cases outside the generic per-applet
  dispatch: the PTT button always targets the wata applet regardless
  of which applet is active, and the two "dot" buttons cycle the
  active applet (`Shell.handleInput`, `shell.scala:109`).
- The shell owns the `AudioEvt` mailbox's SINGLE per-frame drain
  (`Shell.drainAudio` -> `routeAudio`): echo events go to the settings
  applet, recording/playback events to the wata applet, whichever
  applet is active. This is load-bearing, not tidiness — when each
  applet ran its own drain on the shared channel, each discarded the
  other's events, and an `AeRecordingDone` landing between the two
  drains inside one `Shell.update` was silently eaten: a recording
  dropped on the floor, at a scheduler-dependent ~1% per send that
  grew under machine load (docs/plans/0009-audio-event-routing.md).
  Every applet's `update` is still called every frame even when
  inactive, for its timers and cursor clamping.

## Input

`Evdev` (`input.scala`) opens exactly `/dev/input/event0`,
`/dev/input/event1`, `/dev/input/event2` non-blocking
(`Evdev.open`, `input.scala:92`), reads raw 16-byte
`struct input_event`s (32-bit-ARM layout: 4+4+2+2+4 bytes,
`input.scala:34`), and maps kernel key codes to an app-level `Key`
sum type (`input.scala:11-22`): d-pad, enter/back, PTT (F1), a
headset/spare PTT (F2), and two "dot" buttons (F3/F10) used to switch
applets. `KeyState` distinguishes pressed/released/repeat.

**Known gap, called out in the code itself**: only three input
devices are opened. `shell.scala:21-27` documents that this mirrors a
gap in the original device firmware this was ported from — the
reference client also only opens `event0..2` and therefore also never
discovers whichever bus the second "dot" button's hardware sits on,
if it's on a fourth device node. This module reproduces that
behavior rather than fixing it; `WATA-TODO.md` tracks it as an open
item ("dot2 input bus undiscovered").

## Audio

Audio is split into a Sgola-side facade (`audio.scala`, binding
`github.com/adriaanm/wata/go-pkgs/audio` via `@go.bind`) and the actual
Go/cgo package at `go-pkgs/audio/`, which this repo may read but not edit.

**The cgo boundary.** `go-pkgs/audio/audio.go` is hand-written,
ordinary Go — not code generated by any Sgola tooling. It defines the
shared constants (48kHz mono S16_LE, 960-sample/20ms Opus frames,
40ms/1920-frame ALSA periods, an 8-period/320ms playback ring) and a
handful of build-tag-independent helpers (`Tone`, `EncodeFrameAt`,
`DecodeFrame`, `PlayMessage`, `StateName`) that are shared between two
mutually exclusive implementations selected by Go build tags:
`audio_linux.go` (linux/arm, real cgo over opus + tinyalsa) and
`audio_stub.go` (every other platform, `go-pkgs/audio/audio_stub.go:1`
— every call returns an error immediately). The Sgola side never
knows which one it's linked against; `audio.scala` types
(`Encoder`, `Decoder`, `Capture`) are opaque cgo handles
(`private[go]` constructors, `??? `-bodied binds resolved by the
`@go.bind` annotation).

`DecodeFrame`'s output buffer is sized for `MaxDecodeSamples = 5760`
(120ms), not the device's own 20ms encode frame
(`go-pkgs/audio/audio.go:38`) — a fix for a real bug: a foreign
encoder (a different client implementation) can send 60ms@16kHz
packets that decode to 2880 samples at 48kHz, which is longer than
this device's own 20ms frames and used to make `opus_decode` return
`OPUS_BUFFER_TOO_SMALL`. `oggforeign.scala` and
`go-pkgs/audio/foreign_decode_test.go` exist specifically to guard
this: a pinned Ogg fixture from that foreign encoder
(`go-pkgs/audio/testdata/tui-foreign.ogg`) is decoded on-device and
checked byte-for-byte.

**Playback discipline.** `go.audio.playMessage` /
`go-pkgs/audio/audio.go:140` (`PlayMessage`) encodes a specific,
carefully tuned ALSA usage pattern rather than the more naive
one-period-at-a-time write the code was originally ported from:
open the volume control once up front (never mid-stream — opening it
walks ~700 mixer controls on this hardware), set
`start_threshold = stop_threshold = ring size`, prime the entire ring
before starting playback so the kernel auto-starts once the threshold
is met, only call an explicit `Start()` for messages shorter than the
ring, apply volume only after the stream is running, and write the
remaining body in fixed 4-period (160ms) chunks because a single
large write can hit `ETIMEDOUT` on this ADSP. `audiothread.scala:11-14`
notes this deliberately diverges from the original playback code's
one-period-start approach — the Opus recording/playback code in this
module was ported from a prior implementation, and that implementation
had at least three quirks this port deliberately does NOT
reproduce (`audiothread.scala:25-33`):
1. the original encodes only one 960-sample Opus subframe per
   1920-frame period read, silently dropping the second half of every
   period during normal recording (its own echo-test path did not
   have this bug) — this port encodes both subframes;
2. the original truncates playback to a whole period, discarding up
   to 40ms of trailing audio — this port zero-pads instead;
3. a quit-mid-record edge case that left the original's main loop
   blocked until a queue was closed — this port returns a quit code
   and exits directly.

**The audio thread** (`AudioThread`, `audiothread.scala:38`) is a
single goroutine driven by a command mailbox (`AudioCmd`) and an
event mailbox (`AudioEvt`), both capacity-16 channels, defined in the
portable `wataclient` module (not owned by this repo area). It runs
`go.audio.setupMixer()` exactly once at startup — switching playback
and capture mixer routes per-recording used to crash the audio DSP
(`audiothread.scala:52`) — then loops on `cmds.recv()` dispatching to
`doRecord`, `doPlay`, or `doEcho` (`audiothread.scala:59`). Recording
reads one 40ms period at a time from `go.audio.Capture`, Opus-encodes
both 20ms halves, and accumulates encoded frames; on a normal stop it
Ogg-muxes them (`Ogg.writeStream`, in `wataclient`) and emits
`AeRecordingDone`. Playback does the reverse: split the incoming Ogg
into Opus packets (`Ogg.readFrames`), decode each into PCM, and hand
the whole buffer to `playMessage`. `AcStopPlayback` is only honored
between decode steps — once `playMessage` starts writing to the
speaker, the message plays to completion, matching the same
uninterruptible-write shape used elsewhere in this codebase's
lower-level write loop.

**The vendored tinyalsa patch.** `go-pkgs/audio/vendor/tinyalsa/src/pcm.c`
carries a local patch (search for `SGOLA PATCH`) to `pcm_start` and
`pcm_state`. Background: `pcm_sync_ptr`'s flags argument controls
which fields of the kernel/userspace shared state are *pushed*
(userspace → kernel) versus merely *read* (kernel → userspace) —
`flags = 0` pushes everything, including `appl_ptr`. That's correct
on the MMAP path (userspace owns and advances `appl_ptr` directly via
`pcm_mmap_commit`), but wrong on non-MMAP ("RW"/ioctl) hardware where
the fallback `sync_ptr` path is used instead — `appl_ptr` is never
advanced locally, so pushing zeroes the kernel's copy on every
`pcm_start`/`pcm_state` call, which manifests as `EPIPE` and a
corrupted ring buffer (repeated xrun-restarts, i.e. playback stutter
and roughly doubled wall-clock playtime). The patch makes those two
functions only push on true MMAP handles and pull (GET-only) flags
otherwise, matching normal kernel `sync_ptr` semantics.

The patch is not going upstream. `go-pkgs/audio/vendor/tinyalsa/` is a
**maintained in-repo fork**, not a pristine vendored copy waiting to be
un-forked: the fix is specific to non-MMAP hardware that upstream does
not target, and carrying it here costs less than tracking an upstream
review. Changes to the fork are ordinary commits in this repo, marked
`SGOLA PATCH` in the C source so a re-vendor from upstream can find and
re-apply them.

**Selftest** (`selftest.scala`, `wata-fb --selftest echo|play|all`)
spawns the *real* production `AudioThread` and drives it through its
normal public command mailbox rather than calling audio functions
directly, specifically so a passing selftest is evidence the
production path works end to end. `echo` records 2 seconds and plays
it back (a "hear your own voice" check); `play` synthesizes a 1.5s
440Hz tone through the production encoder and Ogg writer, then
decodes and plays it (a tone is easier to verify with a sound meter
than speech).

## LEDs and peripherals

`Led` (`led.scala`) drives backlight, red LED, green LED, and button
backlight via sysfs writes (`/sys/class/leds/.../brightness`) through
`go.syscall` (`open`/`write`/`close`, `syscall.scala`). Every call is
best-effort: failures are silently swallowed (`led.scala:31`), which
means there is no way to detect from this code whether an LED write
actually succeeded — deliberate, since the dev host has no such sysfs
tree at all and the code needs to run unmodified there.

`Ui.onConn` (`ui.scala:212`) maps connection state onto the LEDs: green
while syncing/connected, red on error/disconnected. The backlight
brightness is user-configurable through the settings applet
(`SettingsLogic.brightnessUp/Down`, `applets.scala:664-676`,
30-second-to-never idle screen-off timeout,
`SettingsLogic.timeoutSecs`, `applets.scala:564`) and the idle timer
in `Ui.tickIdle` (`ui.scala:180`) turns the backlight and button
backlight off after that timeout, restoring them on the next input
(`Ui.wake`, `ui.scala:172`).

## Session persistence

The device is meant to boot straight into the client with nobody
typing a homeserver, so the credentials survive restarts in a config
file. `wataclient` owns the record and its JSON (`Session`,
`Sessions`) but deliberately no file IO — where a config lives is the
app layer's decision. `config.scala` is that decision:

- **Path**: `$WATA_FB_CONFIG` when set, otherwise
  `/etc/wata/config.json`. One binary therefore covers the device and
  a dev host, and every test run points the env var at its own file
  rather than sharing the operator's.
- **Contents**: `{homeserver, username, access_token, user_id,
  device_id}` — the Zig client's file shape — plus three fields the
  settings applet owns: `brightness`, `screen_timeout_idx`,
  `name_idx`. Those are the device's preferences, not the account's:
  someone who set the backlight low and the timeout long should not
  have to set them again after a reboot. Session and preferences are
  written together, and each writer reads the other half back first,
  so neither clobbers the other.
- **Read** is best-effort in every direction: absent file, unreadable
  file and malformed JSON all resolve to the empty session, which
  `Sessions.isValid` rejects, which sends the sync loop down the
  password-login path exactly as if nothing were stored.
- **Write** is best-effort too: the parent directory is created if it
  can be, the file is opened 0600, and any failure is a silent no-op.
  A device that cannot persist still runs; it logs in again next boot.
- **When**: the session at `Ui.persistSession`, on the first
  `Connected` event of a session and only when `Runtime.lastAuth`
  carries a non-empty token — once per session, so a stale store is
  never overwritten with nothing. The preferences at
  `SettingsLogic.persisted`, which every settings keypress goes
  through, so a changed preference is on disk immediately and there is
  one place that decides when to write.
- **Back**: `Shell.initial` takes the stored preferences and builds the
  settings applet from them, and the device applies the restored
  brightness at startup instead of a hardcoded maximum.

`FbConfig.resolve` is what every UI entry point builds its
`ClientConfig` from. Explicitly given arguments win; a slot that is
empty or `-` falls back to the store, so `wata-fb ui` and `wata-fb
sim` take their credentials positionally and all of them are now
optional. `-` exists because those arguments are positional and an
unset one still needs a spelling — it is what the scripted driver
passes for a phase that must resume rather than log in.

The stored session is offered to `loginOrResume` only when its
homeserver **and** its username match the run's, so naming a different
user explicitly forces a password login instead of resuming on the
previous user's token. That rule is what lets two phases of one
scripted scenario share a config file without impersonating each
other.

## Matrix integration

This module does not implement the Matrix protocol itself — that
logic (HTTP client, `/sync` long-poll loop, room/event model, the
`MatrixClient`/`Runtime`/`StateSnapshot` types, `AudioCmd`/`AudioEvt`,
etc.) lives entirely in the `wataclient` module, which is out of
scope for this repo area (read-only). `wata-fb` supplies:

- **Capability implementations** (`caps.scala`): `FbClock` over
  `go.time`, and `FbHttp` over Go's `net/http` client, both
  implementing traits `wataclient` defines. A thrown `GoError` from
  the HTTP path is turned into `HttpResponse(0, "")` rather than
  propagated — `wataclient`'s portable core is never allowed to see a
  raw Go error (`caps.scala:20-21`).
- **The UI's use of the client**: `Ui.runUi` constructs a
  `MatrixClient` via `Runtime.makeWithAudio`, starts it, and each
  frame calls `Runtime.pollSnap` (take the newest immutable state
  snapshot) and `Runtime.pollEvent` (drain UI-relevant events —
  connection changes, send/play completion or failure) to update the
  shell and LEDs. Outgoing actions (`ActSendVoice`, `ActPlay`,
  `ActReceipt`, `ActRedact`, `ActSetName`) are enqueued via
  `Runtime.sendAction` from applet input handlers
  (e.g. `WataLogic.uploadRecording`, `applets.scala:253`;
  `WataLogic.playSelected`, `applets.scala:185`).

Received messages become UI state purely through the snapshot: the
sync loop (inside `wataclient`) updates server state and publishes a
new `StateSnapshot`; the UI loop picks it up next frame and the
applet render functions (`WataLogic.renderContactRows`,
`renderMsgRows`, `applets.scala:293-353`) draw from it directly —
there is no separate "apply message" step in this module. Playing a
received clip is a full round-trip: `ActPlay(mxcUrl)` goes to
`wataclient`, which downloads and hands PCM/Ogg bytes back through the
`AudioEvt` channel, and `WataLogic.onAudioEvent`
(`applets.scala:241`) reacts to `AePlaybackDone`/`AePlaybackError`.

## Dev/test surface

All of these are subcommands dispatched from `main.scala`:

| command | file | purpose |
|---|---|---|
| `synctest` | uses `wataclient`'s `SyncOracle` | prints a fixed report from a portable unit-test oracle for the sync engine; no device needed. |
| `syncfix f1 f2 ...` | `syncfixdriver.scala` | feeds captured `/sync` JSON fixture files (`<selfUserId>=<path>`) to `wataclient`'s `SyncDescribe.fixtureReport`; this app layer only does the file I/O, the portable module does the describing. |
| `oggtest` | uses `wataclient`'s `OggOracle` | byte-oracle check of the Ogg/Opus container writer, run again here (Go-emitted) after already running once on the reference JVM implementation, to catch codegen-only drift. |
| `oggforeign <fixture.ogg>` | `oggforeign.scala` | decodes a pinned Ogg fixture produced by a different (foreign) encoder and prints a report — the host-side half of the `OPUS_BUFFER_TOO_SMALL` regression guard described above; the actual on-device Opus decode is exercised separately by `go-pkgs/audio/foreign_decode_test.go`. |
| `fbdump` | `fbtest.scala:41` | draws the deterministic test pattern into an in-memory buffer and writes a PNG to stdout — the host-side "golden frame" check, no real display involved. |
| `fbsmoke` | `fbtest.scala:49` | on-device only: opens the real framebuffer, draws the pattern, blinks LEDs, and echoes evdev key presses for ~20s — a manual hardware smoke test. |
| `integ <scenario> <baseUrl>` | `integ.scala` | ten scenarios (login, two-user sync, voice send/receive, read receipts, ordering, redaction, download-byte-equality, the family room, session resume) run against a live `wata-server`, each driven through `wataclient`'s real `Runtime`/action queue, printing `INTEG PASS/FAIL <scenario>`. |
| `--selftest [echo\|play\|all]` | `selftest.scala` | on-device audio-thread selftest described above. |
| `login\|voicesend\|voiceplay\|audiosoak ...` | `devcli.scala` | scripted, non-interactive actions against a live server: provision/login a user, record-and-send a clip, sync-and-play the newest clip, or run a long record/send/sync/download/play soak loop (intended to run under `GODEBUG=gctrace=1` to watch GC pressure — `devcli.scala:105`). |
| `sim [base] [user] [pass] [--once]` | `sim.scala` | the host simulator: the real frame loop drawn into a terminal — see below. |
| `uitest <script> <base> <user> <pass> <outdir>` (`-` in a credential slot = resume from the store) | `uiscript.scala` | one scripted, deterministic UI session with PNG checkpoints — see below. |
| `ui [base] [user] [pass]` | `ui.scala` | the actual product: the full on-device client. |

`integ` (ten scenarios, `wataclient`'s `Runtime` directly) and
`fb-ui-tests` (scripted runs of the real frame loop) are this module's
two end-to-end suites; both require a running `wata-server` and are
invoked by scripts in `tools/`, not from this module directly.

**Harness isolation.** Every live-server harness (`wataclient-integ.sh`,
`fb-ui-tests.py`, `wataclient-fixtures.sh`) picks a RANDOM port per run
(env overrides: `INTEG_PORT`, `FB_UI_PORT`, `FIXTURES_PORT`), and its
readiness probe requires the spawned process to BE the listener (`lsof`
pid match) before trusting an HTTP 200. Both halves matter: a
`wata-server` that loses a bind race prints the listen error but exits
ZERO (the subset has no `os.Exit` facade), and a foreign server
squatting on the port answers `/versions` indistinguishably — so a bare
curl probe silently runs the whole suite against another checkout's
server, whose own per-scenario restarts then appear here as mid-scenario
connection errors (sync-loop backoff, 1s..60s) that expire whichever
wait they happen to land in. That was the historical `integ` "fails
under load, a different scenario each time": the load (sgola's sbt gate)
correlated with a sibling checkout running these same harnesses on the
then-fixed ports. The matching `fb-ui-tests` flake (`wait msgs >= N`
expiring with `sendok = N-1`, connection healthy) was a real client bug
the same load widened — the audio-event drain race of plan 0009. The
wait budgets themselves — wall-clock 15–30s per wait in `integ`,
900–2000 frames at a real 10ms floor per frame in the ui scripts — hold
with ~50x headroom even at load average ~60, and a genuinely hung
client still fails within one budget; on a `wait`/`waitmax` timeout the
scripted driver appends the connection tag and the session's
send-ok/send-fail/conn-error tallies (`Ui.sendOks` etc.), so a timeout
log already classifies its cause. `wata-tests.sh` stays pinned to :8008
because the read-only TS suites hardcode that URL per file; it cannot
run concurrently across checkouts, and says so.

## The host simulator

`wata-fb ui` needs a real `/dev/fb0`, so for a long time the
applet/shell layer could only be exercised on the device. The
`UiDevice` seam gives the same frame loop two host front ends, so the
whole UI — input routing, applet state machines, rendering, the PTT
send path — runs on a dev box against nothing but a live
`wata-server`.

**`just fb-sim [BASE [USER [PASS]]]`** — the interactive one
(`tools/fb-sim.sh` + `sim.scala`). The 160x128 RGB565 frame is drawn as
ANSI truecolor half-blocks: one character cell is two stacked pixels
(`U+2580 UPPER HALF BLOCK`, foreground = upper pixel, background =
lower), giving a 160x64 character grid at the panel's true aspect
ratio. A frame is written only when the pixel buffer actually changed,
and within a frame an SGR sequence only where the fg/bg pair changes
from the previous cell — which collapses the flat regions that
dominate this UI. Keys come from raw stdin: arrows = d-pad, Enter =
select, Backspace/`b` = back, `z`/`x` = prev/next applet, `f` = F2,
Space = PTT, `q` = quit. The wrapper script owns nothing but the
terminal mode (`stty raw -echo min 0 time 0`, which is what makes the
per-frame stdin poll return immediately) and restoring it on every exit
path. **A terminal reports no key-up**, so PTT release is INFERRED: a
gap longer than 250ms in the space-key repeat stream is the release —
the same trick the TypeScript TUI's `usePtt` hook uses. Without a tty
the script renders one frame and returns, so the recipe stays
exercisable unattended.

**`just fb-ui-tests`** — the ci one (`tools/fb-ui-tests.py` +
`uiscript.scala`). Each scenario starts a FRESH `wata-server` and runs
its phases in order, one `wata-fb uitest` process per phase, each
driven by a script in `tools/fb-ui-scripts/`. Phases are sequential
rather than concurrent clients because `wataclient`'s `Runtime` is a
single-client-per-process engine; the server carries state between
them, exactly as `tools/wataclient-integ.sh` does it. Every checkpoint
dumps the live pixel buffer through the same deterministic PNG encoder
`fbdump` uses, byte-compared against `tools/fb-ui-golden/`; regenerate
those reviewed baselines with `just fb-ui-tests --update` and eyeball
the images.

What is virtual and what is not: **the UI loop's clock is virtual**
(`ScriptClock` advances exactly one frame per read), so every frame's
`dt` is 33ms of simulated time and the time-dependent pixels — the PTT
hold counter, the send/play status flash — are reproducible. The
CLIENT keeps the real clock and the real network, so a `wait` directive
advances frames (each pausing 10ms of real time) until the snapshot
catches up. Checkpoints are therefore taken from a SETTLED state: wait
for the thing, advance past every animation, then dump. Giving the
scripted client the virtual clock too would be wrong — the sync loop's
backoff and `wataclient`'s wait helpers would spin.

Audio on the host is `SimAudio` (`sim.scala`), which speaks the real
`AudioCmd`/`AudioEvt` mailbox protocol with no codec behind it: a
recording yields the canned Ogg payload `integ.scala` uses at a FIXED
duration (a scripted frame must be byte-reproducible, and the real hold
time is wall-clock), and playback succeeds silently. So the full send
path — PTT, upload, `m.audio`, the other client's timeline — runs
host-side; only the codec stays device-only.

Eight scripted scenarios, each a fresh server and a sequence of
one-user phases:

| scenario | what it pins |
|---|---|
| `voice-alice-to-bob` | the send path end to end: alice bootstraps the family room, holds PTT and sends; bob runs, auto-joins, opens the conversation and renders the message row. Goldens both contact lists, the post-send frame and the settings menu. |
| `conversation-actions` | the conversation view's own inputs: alice sends thirteen clips (one more than the twelve rows that fit), scrolls the selection to the bottom, and redacts one with F2; bob then receives the twelve and plays one. Goldens the full window, the scrolled window, the post-redaction list, and the played marks. |
| `dm-roundtrip` | the canonical-DM flow (plan 0007) rendered: alice selects bob's ROOMLESS roster row, the first PTT send resolves the room through `POST /_wata/v1/dm`, bob receives with an unplayed badge, receipts, plays, replies, and alice's second session pins the reply and the badge clearing. Goldens the roster before/after, both conversation views, and the badge lifecycle. |
| `family-three` | a third account (per-scenario `$WATA_USERS`): all three send into the family room. Goldens charlie's roster (the family plus TWO DM-able contacts) and the conversation with three-way sender attribution and interleaved ordering. |
| `badges-across-restart` | unplayed counts across a restart: bob sees family=1 / DM=2, resumes with no credentials, and the badge frame is byte-identical; playing out the DM clears only its own badge. |
| `send-play-failed` | the failure flashes, against a server failing on demand (`WATA_TEST_HOOKS=1` + the `failnext` directive): an armed upload 500 draws `SEND FAILED`, an armed download 500 draws `PLAY FAILED`, and the retry after each succeeds — the self-disarming counter is the disarm. |
| `settings-walk` | every settings item and its detail block: the echo test, brightness down two steps, the screen-timeout picker, the display-name preset round trip (`OK` sets it, the `nameset` probe waits for it to come back through `/sync`), network, and device info. A second phase with no credentials goldens the same menu with the changed preferences restored from the store. |
| `session-resume` | the config store: one phase logs in with arguments, the next starts with `-` in every credential slot and has to come up on the stored token. The phase running at all is as much the assertion as its frames. |

A few things the scripts need that are worth knowing. `waitmax` is the
mirror of `wait` — advance until a probe drops to a bound — because a
redaction shrinks a count, which an ordinary `wait` (a `>=` test)
already satisfies. `idle` runs frames with the real per-frame pause
switched off: a timer expiring needs simulated time, not network
progress, which is what makes the screensaver's half-minute of blanking
cost the suite nothing. A phase whose credentials are `-` cannot
also run the out-of-band `family` bootstrap, since that logs in
directly and a resumed run has no password: bootstrap in one phase,
resume in a later one. `failnext <n>` arms the server's
`WATA_TEST_HOOKS=1` fail-on-demand counter (wata-server's testhooks.scala;
the harness starts a scenario's server with the env var only when the
scenario opts in, and probes the hook route on EVERY server so the
production 404 is asserted each run), and the `sendfail`/`playfail`
probes over the session tallies are what a script waits on after
provoking a failure. And a scenario's `users` key writes a
`$WATA_USERS` accounts file, which is how a phase gets a third login.

### Use-case coverage

The product's use cases against the scenarios that pin them — the table
this suite is grown by (a missing row's flow is a missing scenario, not
a rediscovery):

| use case | covered by |
|---|---|
| boot with no credentials, resume the stored session | `session-resume`, `badges-across-restart` |
| first login writes the session store | `session-resume` |
| family room: auto-join, roster, send, receive | `voice-alice-to-bob`, `family-three` |
| DM: select a contact, first-send room resolution, receive, reply | `dm-roundtrip` |
| unplayed badges: accumulate, survive restart, clear per-conversation | `dm-roundtrip`, `badges-across-restart` |
| play a message: receipt + played mark round trip | `conversation-actions`, `dm-roundtrip` |
| delete a message (F2 redact) | `conversation-actions` |
| long conversation: scroll window, selection clamp | `conversation-actions` |
| sender attribution, >2 participants, interleaved ordering | `family-three` |
| send failure feedback (`SEND FAILED`) and recovery | `send-play-failed` |
| play failure feedback (`PLAY FAILED`) and recovery | `send-play-failed` |
| every settings item, preference persistence | `settings-walk` |
| screensaver blank + wake swallow | `settings-walk` |
| mid-phase network drop / degraded boot | NOT COVERED — needs a proxy or server pause; deferred to the connection-status UX work (plan 0011, out of scope) |

## Parity with the Zig fbclient

`src/fbclient/` (in-tree, read-only) is the behavioral spec for this
module: it was feature-complete against the TUI before the Sgola port
started. This table is the feature-by-feature comparison, derived by
reading `src/fbclient/src/applets/wata.zig`, `applets/settings.zig`,
`main.zig`, `shell.zig` and `config.zig` against `applets.scala`,
`shell.scala` and `ui.scala`.

**One divergence colors every row: panel orientation.** The Zig client
draws a 128x160 PORTRAIT panel (`font.cols` 21, `font.rows` 19); this
module drives the same hardware as 160x128 LANDSCAPE (`Font.COLS` 26,
`Font.ROWS` 15). Pixel coordinates and grid rows therefore never
transcribe directly — every "same" row below means the same
information in the equivalent place, not the same number.

| feature | Zig | wata-fb | status |
|---|---|---|---|
| **Session / boot** ||||
| session store (`config.json`: homeserver, username, access_token, user_id, device_id) | `config.zig` | `config.scala` | same file shape; `device_id` is written empty (see below) |
| config path | compile-time (`/etc/wata` vs a dev path) | `$WATA_FB_CONFIG`, else `/etc/wata/config.json` | an env var, so one binary serves both |
| boot with no credentials | yes | yes | same |
| session written after login | yes | yes | same |
| **Conversation view** ||||
| contact list: select, scroll window, family accent | yes | yes | same |
| unplayed-count badge, right-aligned | yes | yes | same |
| open conversation + receipt for the latest message | yes | yes | same |
| message rows: duration `m:ss`, sender, played check-mark, gray-when-played | yes | yes | same |
| OK = receipt (if unplayed) + download-and-play | yes | yes | same |
| F2 = redact the selected message | yes | yes | same |
| message scrolling past the visible window | yes | yes | same |
| a selection left past the end of a shrunk list | not reconciled | reconciled every frame | wata-fb only, see below |
| the rows above under test | n/a | yes | the `conversation-actions` scenario |
| **Settings** ||||
| echo test driven over the audio command mailbox | yes | yes | same |
| brightness ±5, clamped 0..40, sysfs write-through | yes | yes | same |
| screen-timeout picker 30s/1m/2m/5m/Never | yes | yes | same |
| display-name preset picker, OK sets it over Matrix | yes | yes | same |
| network disconnect (stop sync + actions, restart to reconnect) | yes | yes | same |
| brightness / screen-timeout / name survive a restart | no | yes | wata-fb only — the same config store the session lives in |
| battery percent in the Info detail | yes | yes | same, `Led.readBatteryPercent`; absent hardware reads -1 and the line is left out |
| detail area under the menu | rows 16..19 of 19 | rows 13..14 of 15 | same idea, sized to the landscape grid |
| every settings item under test | n/a | yes | the `settings-walk` scenario |
| **Chrome / feedback** ||||
| 1px status line colored by connection | yes | yes | same |
| header + connection indicator (`ok`/`..`/`ERR`/`off`) | yes | yes | same |
| pre-sync placeholder (`Connecting…`/`Syncing…`/…) | yes | yes | same |
| PTT overlay: red bar + hold timer | yes | yes | same, with a `REC` prefix |
| `SENT` / `SEND FAILED` / `PLAY FAILED` flash | yes | yes | same |
| screensaver blank + wake-swallows-the-keypress | yes | yes | same, and covered by `settings-walk` |
| **Not ported** ||||
| snake / clock / charmap applets | yes | no | toys, out of scope |
| FreeType text rendering | optional | no | bitmap font only |

Three things worth stating outright:

- **`device_id` is stored but never populated.** `wataclient`'s
  `loginOrResume` publishes `AuthCreds(accessToken, userId)` and drops
  the login response's device id, so this module writes `""` there.
  Nothing reads it back — `Sessions.isValid` wants a homeserver and a
  token — and the field is kept only because the Zig client's
  `config.json` has it.

- **The settings detail area is sized to the grid it draws on.** The
  menu is six items at two-row spacing (grid rows 2..12), which leaves
  rows 13 and 14 — the last two of the 15-row landscape grid — for the
  selected item's detail text, so two lines is what every item gets.
  The layout number this replaced, `2 + N_ITEMS * 2 + 1` = 15, was
  inherited from the portrait grid where 16 is a real row: it put the
  first detail line one row past the bottom and the second one off the
  panel entirely. (The Zig client has the same arithmetic and loses
  its own fourth echo-test line to it.)
- **The cursors are reconciled with the snapshot every frame.** Both
  lists can shrink under the selection with no input at all — a
  redaction drops a message row, a peer leaving drops a conversation —
  and a selection left past the end highlights nothing and plays
  nothing. `WataLogic.clampSelection`, called from `update`, pulls the
  contact and message cursors back onto the last row and drags the
  scroll window after them. The Zig client does not do this and has the
  same dead cursor after its own `F2`.
- **Every applet ticks every frame here; the Zig shell ticks only the
  active one.** That is a fix, not a divergence to undo: recording-done
  audio events must trigger the upload while the user is sitting in the
  settings menu — the shell's single audio drain (`Shell.drainAudio`)
  routes them to the wata applet regardless of which one is active.

## Cross-compilation and deployment

`tools/fb-deploy.sh` is the one-command path from source to a running
binary on the device: it builds `sgo` itself if needed, cross-builds
`wata-fb` for `linux/arm` with `zig cc -target arm-linux-musleabihf`
as the C compiler (this is what makes cross-compiling the cgo
dependency possible without a native ARM toolchain — see
`tools/fb-deploy.sh:29`), `scp`s the resulting binary to the device's
`/dev/shm` (a 192MB tmpfs — the device's root filesystem has only
about 9MB free), remounts `/dev/shm` executable (it's `noexec` by
default), runs it over ssh with output streamed back live, and then
deletes it. There is no persistent install path in this script —
deployment is deliberately transient; a binary surviving reboot would
need to be copied somewhere durable manually. `BQ268_HOST` and
`FB_CC` are overridable via environment variables; the device's SSH
host alias is expected to be `bq268` in the operator's `~/.ssh/config`.

The durable install on the device is `/opt/wata/wata-fb` plus
`/opt/wata/start.sh` (`exec /opt/wata/wata-fb ui` — the `ui`
subcommand is required; a bare invocation runs the skeleton
diagnostic and exits). The system menu launches wata by unbinding
the framebuffer console, running `start.sh`, and rebinding when it
exits; `ui` with no further arguments resolves the session from the
config store (`WATA_FB_CONFIG` or `/etc/wata/config.json`). Both
files are sourced from the `bq268-alpine` repo's rootfs overlay.

The static opus/tinyalsa libraries for the ARM target are prebuilt
and checked in under `go-pkgs/audio/clib/arm/` (built by
`go-pkgs/audio/mklibs.sh`), so a normal cross-build does not need to
recompile C/C++ vendor sources — a plain `linux/arm` cgo build against
the checked-in `.a` files and `zig cc` as the linker/compiler driver
is enough.

## File-by-file map

| file | lines | what it does |
|---|---|---|
| `main.scala` | 93 | Top-level subcommand dispatcher; also has the pre-device "skeleton" smoke check that exercises the cgo path with a synthesized tone. |
| `syscall.scala` | 52 | `go.syscall` facade: thin binds for `Open/Close/Read/Write/Mmap/Munmap/Mkdir` plus the flag/prot/map constants, used by every device-layer file that touches `/dev/fb0`, `/dev/input/*`, or sysfs. |
| `config.scala` | 190 | The session and preferences store: `$WATA_FB_CONFIG` / `/etc/wata/config.json` read and write over `go.sys`/`go.syscall`, and `FbConfig.resolve`, the arguments-override-the-store rule every UI entry point builds its `ClientConfig` with. |
| `caps.scala` | 83 | App-edge implementations of `wataclient`'s `Clock` and `HttpDo` capability traits, over `go.time` and Go's `net/http`; `WATA_IROH_CONFIG=<json>` swaps the underlying client for the embedded iroh transport (plan 0013), nothing above the capability line changing. |
| `irohnet.scala` | 20 | Sgola-side `@go.bind` facade over `go-pkgs/irohnet`: `newHTTPClient(config)`, an `*http.Client` whose connections are iroh streams (real only on darwin + `-tags iroh`; loud-error stub elsewhere). |
| `audio.scala` | 88 | Sgola-side `@go.bind` facade over the `go-pkgs/audio` Go package: constants, `Encoder`/`Decoder`/`Capture` opaque handles, `setupMixer`, `playMessage`, `tone`, `stateName`. |
| `display.scala` | 404 | RGB565 draw primitives (`Draw`), color constants (`Color`), the 5x8 bitmap font and glyph table (`Font`), fixed 160x128 geometry (`Display`). |
| `png.scala` | 127 | Minimal deterministic PNG encoder (CRC-32, Adler-32, one stored DEFLATE block) used only for the host-side golden-frame dump. |
| `input.scala` | 159 | `/dev/input/event{0,1,2}` reader: raw `input_event` decoding, kernel-keycode → `Key`/`KeyState` mapping, and `KeyBatch` (the non-generic box the `UiDevice` input edge needs). |
| `led.scala` | 63 | Backlight/LED control and the battery-capacity read, via sysfs; best-effort, errors swallowed, and a missing battery node reads -1 rather than failing. |
| `oggforeign.scala` | 22 | `wata-fb oggforeign` driver: reads a fixture file and prints `wataclient`'s foreign-Ogg oracle report. |
| `syncfixdriver.scala` | 28 | `wata-fb syncfix` driver: reads captured `/sync` fixture files and feeds them to `wataclient`'s sync-fixture oracle. |
| `audiothread.scala` | 342 | The background audio goroutine: record/playback/echo-test sessions over the `AudioCmd`/`AudioEvt` mailbox protocol, layered close-and-rethrow resource tiers around the cgo capture/encoder/decoder handles. |
| `fbtest.scala` | 102 | `fbdump` (host PNG golden) and `fbsmoke` (on-device fb/LED/evdev smoke test) drivers; also `FbTest.present`, the byte-copy blit used by the real UI loop too. |
| `selftest.scala` | 112 | `--selftest` driver: spawns the production audio thread and drives it through its real command mailbox for an echo test and a tone-playback test. |
| `shell.scala` | 157 | `ShellState`, the active-applet index, status-line coloring, and input routing/dispatch between applets (PTT-always-to-wata, dot-buttons switch applets, everything else goes to the active applet). |
| `applets.scala` | 840 | The `wata` and `settings` applets: their state records, wither-style update functions, input handling, and rendering; also the `Applet` interface and the shared `FrameCtx` per-frame context record. |
| `devcli.scala` | 288 | Non-interactive scripted actions against a live server: `login`, `voicesend`, `voiceplay`, `audiosoak`, each printing a greppable `PASS`/`FAIL` line. |
| `integ.scala` | 546 | Ten live-server integration scenarios exercising cross-user sync, voice send/receive, receipts, ordering, redaction, byte-exact download, the family room, and session resume. |
| `ui.scala` | 310 | The `UiDevice` seam and its real `FbUiDevice` impl, plus the product entry point: opens the framebuffer, wires the sync/action/audio threads together via `sgo.supervised`, and runs `frameStep` at ~30fps. |
| `sim.scala` | 352 | The interactive host front end: `SimAudio` (the mailbox-protocol audio stand-in), `SimTerm` (RGB565 → ANSI truecolor half-blocks), `SimDevice` (raw-stdin keys, inferred PTT release). |
| `uiscript.scala` | 480 | The deterministic scripted driver: virtual frame clock, script lexer and directives, live probes, PNG checkpoint dumps, and the out-of-band family-room bootstrap. |

## Known gaps / debt observed while reading

Items with a `[KEY]` tag have a line in `TODO.jsonl`; grep the key here
for the body. Beyond what `WATA-TODO.md` already tracks (the dot2/event-bus
gap, the `/dev/shm`-only deploy), a few things stood out during this read:

- **`Draw.newBuffer()` allocates a new 40960-byte buffer, but the UI
  loop only allocates it once** (`ui.scala:70`, passed into
  `frameLoop`) and clears+redraws it in place every frame
  (`ui.scala:130`); this is fine, just worth noting there's no
  double-buffering — `present()` (`fbtest.scala:60`) blits the same
  buffer that's actively being redrawn next frame, i.e. there is a
  window where the fb briefly shows a partially-drawn frame if the
  loop were ever interrupted mid-`render`. In a single-goroutine loop
  with no signal handling this is presently harmless, but it would
  matter if a signal handler or a second writer to `mem`/`px` were
  ever added.
- **`Font.drawText` breaks out of the whole string, not just the
  current character, once a character would overflow the display
  width** (`display.scala:396`, `going = false`) — this is correct
  today because all call sites pass fixed short strings, but nothing
  enforces that invariant; a longer string is silently truncated with
  no ellipsis or wrapping.
- **`Ui.wake()` restores brightness from the *current* settings state
  every time** (`ui.scala:172-177`) but there is no explicit
  "screen just turned back on" event separate from the general input
  event — the first keypress after wake is swallowed
  (`Ui.handleFrameInput`, `ui.scala:148`) specifically to prevent that
  keypress from also being routed to the active applet, which is a
  reasonable UX choice but is easy to miss reading `applyOne` in
  isolation.
- **`DevCli.audiosoak`'s peer-record/send/download/play loop has no
  backoff or jitter** (`devcli.scala:107-157`) between cycles; a
  failed cycle (`recFail`/`sendFail`/`playFail`) simply continues
  immediately into the next one until the wall-clock deadline, which
  is fine for a bounded soak test but would hot-loop indefinitely if
  ever reused as a long-running daemon.
- **`just fb-ui-tests` covers the frame loop and the product's use
  cases** (see the coverage table above), including the `SEND FAILED` /
  `PLAY FAILED` flashes via the server's `WATA_TEST_HOOKS=1`
  fail-on-demand hook. Adding coverage is a script file plus a golden,
  not code; the one flow still uncovered — a mid-phase network drop —
  is called out in the table and deferred to the connection-status UX
  work.
- **`Ui.frameStep`'s `dt` is the only thing the scripted clock
  virtualizes.** Anything a frame renders that depends on WALL-clock
  time or on network arrival order would still be non-deterministic;
  today nothing does, which is why the goldens hold. A future
  timestamp in the message rows, say, would break every golden and
  would need the client's clock virtualized too — which the sync
  loop's backoff cannot tolerate as things stand.

The untagged items are recorded as things worth knowing before touching
the surrounding code, not as work owed.
