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
(implicit), `json`, `wataclient` (the portable Matrix client
engine that lives in a sibling module and is NOT owned by this repo
area) and `wataui` (the backend-free view algebra whose framebuffer
interpreter is `paint.scala`), and pulls in one cgo Go dependency,
`go-pkgs/audio`, for Opus and ALSA (tinyalsa) access. `wata-fb/sgo.build` and `sgo.deps`
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

**Some of this module's sources are shared with `wata-mac`** (plan
0032): the macOS client compiles `applets.scala`, `display.scala`,
`paint.scala`, `netstatus.scala`, `input.scala` and `syscall.scala`
through symlinks under `wata-mac/src/main/scala`, so the bodies stay
one source across backends. An edit to those files is compiled by BOTH
apps — a new device-only reference in them needs a stub in wata-mac's
`stubs.scala` (its build fails loudly until it has one); see
[wata-mac.md](wata-mac.md).

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
(`ui.scala`): **input poll, present, LEDs, button/panel backlight,
unblank, and the frame pace**. Time is not part of it — the loop already takes a
`Clock` capability from `wataclient`, and `UiDevice` owns only the one
sleep. It is deliberately NOT `Shareable`: a `UiDevice` never crosses a
goroutine boundary, which is what lets the real implementation hold the
mmap'd framebuffer slice as a plain field.

Four implementations:

| impl | file | edges |
|---|---|---|
| `FbUiDevice` | `ui.scala` | the real thing: `Evdev.poll` over `/dev/input/event{0,1,2}`, `FbTest.present` into the mmap'd `/dev/fb0`, `Led.*` over sysfs. |
| `SimDevice` | `sim.scala` | a terminal: ANSI truecolor half-blocks out, raw stdin in, LEDs as colored cells in a status row. |
| `GioDevice` | `gio.scala` | a window: the frame blitted as an integer-scaled texture, touch buttons and desktop keys in, LEDs as two dots in the chrome — see below. |
| `ScriptDevice` | `uiscript.scala` | deterministic: input from a script's injection cell, no display (the driver encodes the pixel buffer itself at a checkpoint). |

One sgola shape to know: `pollInput` returns a `KeyBatch`, a
non-generic record wrapping `List[KeyEvent]`, because the emitter
stamps a generic result type at the IMPL (`List__KeyEvent`) but leaves
the interface declaration a bare, undefined `List` — a `go build`
failure, not a compiler diagnostic. Any trait method here that wants a
collection result needs the same boxing.

Two device edges are NOT behind the seam and stay best-effort no-ops
off-device: `KidSettingsLogic.setBl` writes the backlight sysfs node
directly from the kid settings applet, and the `Led.*` sysfs writes never
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
mostly ASCII plus a handful of custom icon glyphs at 0x80-0x90
(battery, checkmark, wifi, signal bars, the connectivity element's
cellular mast at `Font.ICON_CELL` = 0x8C, the favorite star at
`Font.ICON_STAR` = 0x8D, and the outbox/playback marks
`Font.ICON_UNSENT` = 0x8E, `Font.ICON_UNDELIV` = 0x8F,
`Font.ICON_PLAY` = 0x90) and a couple of block-element glyphs. There is
no vector/TrueType font path; the font table comment says it was
generated by a `scratchpad/genfont.py` script that is not present in
this module, so 0x8C-0x90 were drawn by hand in the same 5x8 style and
a future regeneration has to carry all five forward. `Font.drawText`/`drawTextCentered` lay text out on a
26-column x 15-row character grid (`display.scala:96-97`) below a
1-pixel status line. `drawText` goes through the string's BYTES, i.e.
ASCII: a custom glyph is drawn with `Font.drawChar` and its code, never
by putting a >0x7F character in a string (which would UTF-8 encode into
two bytes and two wrong glyphs).

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
results) into LED and status updates, compute the frame's connectivity
(`NetStatus.poll`, below), build a `FrameCtx` bundling that frame's
snapshot/connection/connectivity/queues, poll input, route it into
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
  wither-style state records rather than mutation. There are four
  applets today: `WataApplet`/`WataLogic` (contacts + conversation
  view, PTT recording, playback — `applets.scala:38-479`),
  `KidSettingsApplet`/`KidSettingsLogic` (the settings a kid sees —
  four rows: notify, brightness, data, battery; plans 0053/0054, see
  "The settings split" below), `SnakeApplet`/`SnakeLogic` (the snake
  game, `snake.scala` — ported from the Zig client's
  `applets/snake.zig`; see the parity table) and
  `SettingsApplet`/`SettingsLogic` (the
  DEVELOPER settings, slot `Shell.DEV` — OUTSIDE the dot rotation,
  reachable only through the kid panel's hidden development row:
  audio echo test, screen timeout, disconnect, info, plus
  everything absorbed from system-menu — the IP and cellular info
  rows, the net test and the
  power off / reboot-to-BL / reboot-to-EDL actions; see "The settings
  device rows". Brightness, notify and the radio toggles retired to
  the kid panel — plan 0054's de-dupe, one door per preference).
  There is deliberately no display-name row: a person's
  name is their account's, set by whoever administers the server (the
  admin interface, plan 0021), not picked from presets on a handset —
  so the developer menu holds ten items (eleven with Enroll) and every
  row is about this device. That menu outgrew the grid, so it
  renders as a scrolling window of six with `^`/`v` cues in the
  last column; the window start is derived from the selection (no
  scroll state), which keeps the frames the goldens pin
  deterministic.
- Input routing has three special cases outside the generic per-applet
  dispatch: the PTT button is the talk key everywhere (plan 0052,
  `Shell.pttGlobal`) — inside the wata applet it records; from any
  other applet the press CHIMES and switches to the wata screen
  without recording, so the second press is the one that talks, with
  the screen saying to whom (the release lands on wata as a no-op,
  `pttHeld` being false); the two "dot" buttons cycle the
  active applet over the first `Shell.ROTATION` (3) slots — the DEV
  applet sits past them, and a dot pressed inside it leaves through
  the settings slot it is the hidden room of (`rotBase`); OK on the
  kid panel's development row is intercepted by the shell as the door
  to DEV (`isDevDoor`); and red pressed in the snake applet returns
  to the wata applet, in the DEV applet back to the kid settings
  (`isDevBack`, which also drops DEV's confirm latch — except while
  the enrolment QR is open, whose only way out is Back, so the key
  routes into the applet then) — the red-goes-back convention; neither
  applet ever sees the key (`Shell.handleInput`). The contacts footer
  carries the `PTT talk` hint.
- The shell owns the `AudioEvt` mailbox's SINGLE per-frame drain
  (`Shell.drainAudio` -> `routeAudio`): echo events go to the developer
  settings applet (the echo test's home), recording/playback events to
  the wata applet, whichever
  applet is active. This is load-bearing, not tidiness — when each
  applet ran its own drain on the shared channel, each discarded the
  other's events, and an `AeRecordingDone` landing between the two
  drains inside one `Shell.update` was silently eaten: a recording
  dropped on the floor, at a scheduler-dependent ~1% per send that
  grew under machine load (docs/plans/0009-audio-event-routing.md).
  Every applet's `update` is still called every frame even when
  inactive, for its timers and cursor clamping — except the snake,
  which `Shell.tickOne` ticks only while active: the Zig shell ticks
  the active applet alone, so switching away from its snake implicitly
  pauses the game, and an always-ticking port would run it into a wall
  unwatched. The snake takes no part in the audio routing the
  always-tick rule exists for.

**Every screen an applet shows is a `wataui` BODY** (plan 0024): an
applet's `render` reads whatever is ambient, hands it to a pure
`(state, ...) => View`, and makes ONE `FbPaint.draw` call. `WataLogic`
has one body (`body` -> the enrolment screen, the boot screen, the
connection line, the contact list or the conversation, plus the status
flash and the recording bar as children over it) and `SettingsLogic` one
(`body` -> the menu or the enrolment screen). Nothing else writes into
the pixel buffer from an applet. The exception is deliberate: the snake
keeps its own painter, because it is a game surface rather than a wata
screen and no second backend will ever render it.

**The recording bar carries a live capture meter** (plan 0042). The red
band's elapsed time animates on the CLOCK (`tickTimers`), so it counts up
identically over a live microphone and a dead one — which is exactly how a
zeroed `DEC1 MUX` cost a real message (the route-watchdog section below
tells that story). The meter is the audio-driven counterpart: the record
loop computes the peak absolute sample of each 40ms period right between
the read and the encode (`AudioThread.periodLevel`), scales it to 0..32,
and posts `AeCaptureLevel` on the existing event mailbox once per period
(25 Hz, `trySend` — a dropped tick is harmless). The wata applet keeps it
as `WataState.captureLevel` (reset to 0 on PTT press) and draws one bright
green rect keyed `"lvl"` inside the bar, width `level * (W-8) / 32`, min 1
— so a dead mic is a visible flat sliver while the clock counts happily,
and a bar that moves when the kid speaks is proof sound is arriving. It is
also an implicit liveness signal: a record loop that stops reading stops
ticking. Both clients share the bar (the shared `applets.scala` /
`audiothread.scala`); scripted runs pin it with the `caplevel` uiscript
directive (the `rec-meter` scenario), and mac-smoke asserts the exact rect
the fake mic's constant-amplitude tone produces (level 15).

### The settings split: the kid panel and the developer door

The settings slot in the dot rotation holds the KID panel (plan 0053,
`KidSettingsLogic`): exactly four rows — **Notify** (play / chime /
quiet — OK and both arrows cycle; `play` is the walkie-talkie mode,
auto-playing arrivals through plan 0041's existing path — plan 0055
exposed it), **Bright** (the backlight steps, `<`/`>`, applied
live — `KidSettingsLogic.setBl` is the one settings row writing the
backlight now), **Data** (the tri-state below), **Battery** (read-only
— the percentage from the same cached `DiagSnap`, `n/a` off-device;
plan 0054) — with the bottom two grid rows
(the `DETAIL_ROW` convention) always carrying a help/status line for
the selected row. Scrolling DOWN past the last row lands on a hidden
`development` row, drawn only while selected; OK on it opens the
developer applet (`Shell.DEV` — the entire panel described in "The
settings device rows", retitled `DEV SETTINGS`), and red returns.

**The data row is a tri-state of the pipe, not two toggles**: `off`
(wifi off, data off), `wifi` (wifi on, data off), `cell` (data on,
wifi off). Left/right cycle the PENDING target on screen — shown
yellow with a `>` prefix, so asked-for is never dressed as done — OK
applies it, and up/down moving the selection clears an unconfirmed
pick (plan 0055: pick, then confirm; no timer). What the row SHOWS is
derived from the same cached `DiagSnap` reads the developer rows use
(the wifi state and the ppp0 link), refreshed on the same
`DIAG_REFRESH` cadence. The apply issues at most one call per radio
and NEVER retries on its own: repeated data calls per boot work
(hardware-retested 2026-08-16), but an immediate redial after a hangup
can fail while the modem settles (~5s) — so a run that reported
something shows it on the help rows in red, KEEPS the target pending,
and OK again is the deliberate retry (off-device: the guarded `not on
device`, which is what lets the sim walk the report path too).

**Both panels edit the same persisted `FbPrefs`** (`FbConfig.savePrefs`),
each holding brightness/timeout mirrors in its own state record;
`Shell.syncPrefs` refreshes the INACTIVE panel's mirror from the
active one's every frame (only the active applet receives input), so
the two never disagree and `Ui` reads brightness/timeout from the DEV
state alone. The kid state carries `timeoutIdx` only as that mirror —
the screen-timeout row itself is developer-only — and the dev state
symmetrically keeps a `brightness` mirror with no row of
its own (plan 0054): each panel's `persisted` saves the WHOLE `FbPrefs`
record, so the mirror is what keeps a timeout save from clobbering the
kid's brightness and vice versa. The notify mode needs no mirror since
plan 0055 — the kid row is its only editor and `FbConfig`'s cell is the
shared authority. The kid panel is
pinned by the `kid-settings` scenario (`alice-kid-settings.txt`): the
four rows and help text, the notify tri-state cycle, the target
picking (the `kidtarget` probe proves nothing applies before OK, and
that up/down clears a pick), the OK-apply with its red report, the
hidden row, and the door both ways (`applet`/`kidrow` probes).

### The settings device rows

The DEVELOPER settings applet — the panel behind the kid panel's
development row — is the whole of what system-menu offered a parent
or a kid (plan 0003's retirement checklist): wata is the only
framebuffer occupant after the tty1 flip, so anything system-menu did
that still matters had to move here. `diag.scala` is that half — every
source and command line is system-menu's own, so the two never disagree
about what "Data: off" means:

| row | what it shows / does | source, as system-menu reads it |
|---|---|---|
| Device Info | battery %, uptime, free memory | `Led.readBatteryPercent` (the battery sysfs node), `/proc/uptime` first field, `/proc/meminfo`'s `MemAvailable` — both parsed in `Diag`, not shelled out to awk |
| IP | wlan0's IPv4 address | `net.InterfaceByName("wlan0")` (system-menu's `ip -4 addr show wlan0`) |
| Cell data | ppp0 link state + signal strength, e.g. `up -85dBm` | the ppp0 sysfs node, plus `qmicli -p -d msmipc://0 --nas-get-signal-strength` parsed the way `modem_info` parses it (`-128` = no measurement, shown `--`). The ppp0 ADDRESS has no room next to the signal, so it moves to the row's detail line |
| Net test | OK runs four probes, verdicts in the detail block | `ping -c2 -W3` against the auto-detected default gateway (`ip route show dev wlan0`, then ppp0), `1.1.1.1` and `8.8.8.8`, plus the `nslookup google.com` DNS probe judged by system-menu's own test (an `Address` line, no `NXDOMAIN`) |
| Power off / Reboot to BL / Reboot to EDL | OK arms, OK again runs | `poweroff`, `/usr/local/bin/reboot-bootloader`, `/usr/local/bin/reboot-edl` |

The independent Wifi / Data link toggle rows retired to the kid panel's
Data tri-state (plan 0054) — same commands (`rc-service wifi start` /
`stop`, `pppd call cellular &` / `killall pppd`, via the same `Diag`
calls), one control instead of two; the combination the tri-state
cannot express (both radios up at once) was never an on-device need.
| Enroll | OK opens the enrolment QR (Back closes) | nothing external — `enrol.scala`; see "Device identity and enrolment" |

Enroll is the one CONDITIONAL row: it exists only when this handset is
configured to speak iroh, i.e. when it has an identity that needs
admitting. That is also why the item constants in `SettingsLogic` are
stable **ids** rather than menu positions — `itemAt` maps a position to
an id, so inserting Enroll after Network shifts no id and invalidates
no golden of a plain-TCP device.

**Every one of those reads is CACHED, together, on one countdown.** The
screen is a `wataui` body (plan 0024), and a body reads its arguments and
nothing else — so nothing on the render path may touch a sysfs node, the
modem, the interface table or the environment. `SettingsLogic.refreshDiag`
reads all eight (`readDiag` -> `DiagSnap`: the wlan0 address, the ppp0 link
and signal, the ppp0 address, the wifi state, the battery percentage,
uptime, free memory, and whether this handset has an identity to enroll)
every `DIAG_REFRESH` frames — ~5s, system-menu's own refresh cadence —
and the applet's state carries the answers. One record because they share
one cadence; reading half of them per frame and half every five seconds
would be two policies for one idea. `enrol` is the one field seeded at
construction, because the MENU SHAPE depends on it and an input event can
reach the menu before the first refresh does.

Three rules hold this together:

- **`Diag.onDevice()` gates every read and every command** (it probes
  the lcd-bl sysfs node, hardware no dev host has). Off-device the info
  rows answer `n/a`, the net test runs NOTHING and says `n/a` /
  `not on device`, and a toggle arms and then reports `not on device`
  instead of pretending. That is what makes the whole menu walkable in
  the sim and byte-reproducible in the goldens — including on a Linux
  CI host, which does have a `/proc/uptime` of its own.
- **The radio calls are deliberate and never auto-retried.** The
  stray-keypress guard is the kid panel's pick-then-confirm gesture
  (`isPowerRow` keeps the two-OK confirm for the power actions alone).
  Repeated data calls per boot work (hardware-retested 2026-08-16 —
  the once-per-boot pin died with the kernel bugs behind it), but an
  immediate redial after a hangup can fail while the modem settles
  (~5s): a failed apply is reported on the help rows (`actionMsg`,
  red) with the target kept pending, and OK again is the deliberate
  retry. `pppd` is backgrounded, so the outcome arrives through the
  ~5s ppp0 refresh, not through the exec's exit status.
- **The net test runs OFF the frame loop.** OK starts the four probes on
  a goroutine (`Diag.startNetTest`) and returns; the row reads `run..`
  until `SettingsLogic.collectNetTest` picks the verdicts up from
  `Diag.takeNetTest` on a later frame's update. The probes take seconds
  and a frame is 33ms — the frame goroutine never blocks, which is the
  rule the whole UI is held to. A second OK while a test runs does
  nothing; one test is one test.

Commands run as `sh -c "<system-menu's line>"` through the `go.exec`
facade, which is why the lines keep their redirections verbatim; the
two shapes bound are `Run()` (exit status: the toggles, the pings) and
`Output()` (stdout: the gateway, the signal strength).

### The connectivity element

The chrome that tells a kid why the radio went quiet (plan 0013,
milestone 4) is ONE computed record per frame, `NetState`
(`netstatus.scala`), from which BOTH the wata applet's header indicator
and the 1px status line are drawn — two indicators deriving
independently would eventually make disagreeing claims about the same
connection. `Ui.frameStep` calls `NetStatus.poll` exactly once a frame
(it advances the interface-refresh countdown and the blink phase) and
puts the result in the frame's `FrameCtx`.

Two inputs:

- **the pipe** — which interface carries traffic, from the same sources
  the settings applet's diagnostics rows use (`Diag.wlanIp` for wlan0,
  `Diag.cellData` for ppp0) on the same ~5s cadence
  (`NetStatus.REFRESH_FRAMES`, its own countdown; an interface lookup
  per frame would be needless). wlan0 with an address is `PipeWifi`,
  else ppp0 up is `PipeCell`, else `PipeNone`. Off-device both sources
  answer `n/a` honestly, which is its own pipe, `PipeUnknown`.
- **the health** — `wataclient`'s `ConnectionState`, the only honest
  source: an interface holding an IP whose sync is erroring is
  reconnecting, not "on wifi". `Connected`/`Syncing` = `NetLive`,
  `Connecting`/`ConnError` = `NetReconnecting`, `Disconnected` =
  `NetDown`.

What it draws, right-aligned in the header slot the old
`ok`/`..`/`ERR`/`off` indicator held (it *replaces* that indicator):

| pipe | live | reconnecting | down |
|---|---|---|---|
| wifi | wifi glyph, green | glyph yellow + `..` alternating | glyph red |
| cellular | mast glyph (0x8C), green | glyph yellow + `..` | glyph red |
| unknown (off-device) | `NET` green | `NET` yellow + `..` | `NET` red |
| none (device, no address) | `OFF` red | `OFF` red | `OFF` red |

The `..` alternate on `NetStatus.BLINK_FRAMES` (~0.5s) — on for the
first phase of a health state, off for the next. The phase counter
RESETS when the health changes, which is what makes a scripted
reconnecting frame reproducible: the goldens' checkpoints are otherwise
taken at a frame count the live network decides.

The status line derives from the same record (`ShellStatus.fromNet`):
no pipe at all is red whatever the sync loop last managed to say, and
otherwise the connection keeps the colors it always had.

Two uitest-only overrides make the whole table pinnable off-device
without faking interfaces underneath `Diag` (which would make the
goldens depend on the host's network): the `conn` directive forces the
connection state the frames report — necessary because the sync loop
republishes `Syncing` on every snapshot, so writing the live cell would
not survive a frame — and `netpipe` forces the pipe. Both are inert in
every real run (`Ui.resetCells` clears them per session).

### Outbox marks and the playback mark

Two row marks say what the client is doing with the user's own audio
(plan 0022 milestone B; the queue itself is `wataclient`'s —
[wataclient.md](wataclient.md)).

- **The contact row's outbox mark**, last column, right-aligned like the
  favorite star so nothing reflows: `ICON_UNSENT` in yellow while that
  conversation has a send still queued, `ICON_UNDELIV` in red once one
  was refused for good. The louder one wins when both apply, and the
  unplayed badge shifts two columns left to make room. The undelivered
  mark stays until the user OPENS that conversation, which is where they
  find out — `WataLogic.enterConversation` sends `ActAckOutbox` for both
  spellings of the conversation's key (room id and contact id, since a
  send queued before the DM room existed is filed under the contact).
- **The message row is one fixed grid** (plan 0049,
  `WataLogic.msgRowView`): marks in columns 0-1, the age at column 2
  (3 wide — `ageStr`: "now" under a minute, then "59m"/"23h"/"99d",
  future timestamps clamped to "now" for the 1970-boot handset and the
  scripted harness alike; the non-"now" arms are pinned by the
  `agecheck` selfcheck in fb-smoke), the sender at column 6 — "me" on
  an own row, else the display name clipped to the room left of the
  duration — and the duration right-aligned ending at column 24, with
  column 25 the favorite star. Both mark columns are reserved on every
  row, so own and received text share the grid and nothing ever
  reflows. The marks read the SAME on every row (plan 0051): check one
  always — the message is delivered (in the timeline; for an own row
  that says the server has it) — and a second adjacent `ICON_CHECK`
  when it has been HEARD by its audience (`WataLogic.heardMark`):
  `VoiceMessage.playedByPeer` for an own row, `isPlayed` for a
  received one. Two adjacent check glyphs rather than a new doubled
  glyph is the Zig reference's documented convention
  (`src/fbclient/src/font.zig` in git history at `27a2f75`: "draw two
  0x80 glyphs adjacent"). The `uitest` probe `peer`
  counts peer-played messages in the open conversation; the
  `dm-roundtrip` scenario waits on it and the `dm-alice-reply` golden
  shows the double check.
- **The message row's play mark**, column 0: `ICON_PLAY` in place of the
  played check while `WataState.playing` holds, i.e. from the instant OK
  is RELEASED — before any byte has moved. A slow fetch is exactly when
  the feedback matters. A hung download resolves through the request
  deadline into `EvPlaybackError`, which clears `playing` and flashes;
  the flash names the cause, `PLAY FAILED` for a fetch that failed and
  `NO AUDIO` when the bytes arrived but there is nothing to play them
  through (no audio thread, or the audio thread reported failure). A
  RECORDING that fails flashes `MIC FAILED` (`WataState.micError`) —
  the microphone's fault named as such; it used to ride the send-error
  flag and read `SEND FAILED`, blaming the network for a dead or denied
  mic (plan 0045 slice 4).

The mark state reaches the frame the way everything else does: the
runtime pushes `EvOutbox(unsent, undelivered)` on every queue change,
`Ui.drainUiEvents` parks it in a cell, and `FrameCtx` carries it. The
render path never reads the outbox — it could not, since the queue lives
behind the action loop. The `uitest` probes `unsent`/`undeliv` count the
marked conversations, which is what the `outbox-restart` scenario waits
on.

### Request deadlines

`FbCaps.httpDo` builds the `HttpDo` capability, and every client it can
build carries a **per-request deadline** — 30s by default,
`WATA_HTTP_TIMEOUT_MS` overriding (the knob the hung-server test shrinks).
Unbounded was a wedge: one half-open connection — a server that accepted
and then went away, the ordinary wifi-roam or NAT-drop case — and the
sync round, or the action the UI is waiting on, never returns, with the
screen frozen on its last good frame. A hung request must instead become
a failed round, which `wataclient`'s backoff already knows how to handle
([wataclient.md](wataclient.md), "The connect lifecycle").

- **TCP**: `go.httpc.newClient(ms)` over `go-pkgs/httpc` — the bound
  net/http facade exposes the `Client` type but not its `Timeout` field,
  and `DefaultClient` has none, so the field costs a three-line Go
  package (the `@go.bind` pattern `go.irohnet`/`go.audio` already use).
- **iroh**: `go-pkgs/irohnet`'s client conns carry the same bound as a
  post-dial read/write deadline, re-armed per operation (`Conn.armOp`) —
  the dial already had 30s, the stream after it had nothing. Server-side
  accepted conns keep no deadline: a server's read between requests is
  legitimately idle. An idle keep-alive conn's background read hits the
  deadline and retires from the transport pool, which is cheaper than the
  wedge it prevents.
- **iroh init failure is loud**: a client that cannot be built at all
  latches `FbCaps.transportUnavailable`, which the boot screen names
  outright. It used to downgrade silently to the plain client pointed at
  the placeholder iroh host, i.e. a client that could only ever fail,
  with a stdout line nobody on a handset can read.

### The boot presentation

The device boots into wata before the network and the modem are up, so
the first seconds of every power-on are a state the client cannot
distinguish from a failure — and the applet used to render exactly that
failure, `renderConnecting`'s `Disconnected` / `Connection error` line,
as the first thing a kid saw each morning. So the wata applet has a
BOOT state, held by one session-scoped latch: `NetStatus.everLive` is
false from session start and set by `NetStatus.poll` on the first frame
whose health is `NetLive` (cleared with the other session cells in
`NetStatus.reset`, which `Ui.resetCells` calls). Health alone cannot
separate "has not connected yet" from "connected and dropped" — both
are `NetDown`/`NetReconnecting` — and the latch is the whole of that
distinction.

While the latch is unset, `WataLogic.bodyContacts` yields
`bodyBoot` instead of the contact list or the connection line: the
`WATA` title, the ordinary connectivity element (the header is
unchanged — the same `NetState` the rest of the frame draws from), a
centered headline in the conversation area, an optional second line
saying what to do about it, and a footer naming the two live keys.

This was the first screen built as a `wataui` BODY (plan 0024):
`WataLogic.bodyBoot` is a pure function of
`(NetState, ConnectionState, quitArmed, transportUnavailable, provisioning,
clockOk)` to a view tree. The app-edge reads —
`FbCaps.transportUnavailable()`, `NetStatus.clockOk()` — are hoisted
to `WataLogic.render`, the call site that paints the whole applet, because
a body reads its arguments and nothing else ([wataui.md](wataui.md)).
Centering is the body's arithmetic (`FbPaint.centerCol`), so the
interpreter only ever sees a positioned `VText`. The connectivity
element is `WataLogic.netView`, one view definition every screen that
shows it embeds.

The copy is `WataLogic.bootMsg`/`bootSubMsg`, in priority order:

| condition | headline | second line |
|---|---|---|
| `FbCaps.transportUnavailable()` | `transport unavailable` | `check config` |
| connection is `ConnAuthRejected` | `account rejected` | `check server` |
| `stillBooting` (below) | `starting up...` | — |
| connection is `ConnError` | `can't reach server` | `retrying...` |
| provisioning | `setting up...` | `handset approved` |
| an interface, and health not down | `waiting for network` | — |
| otherwise | `starting up...` | — |

The calm states are the boot-before-the-network case: naming it an
error there would teach a kid that the radio is broken every morning.
The failure states are the opposite mistake, and the one the field
actually hit — the error copy used to be gated behind `everLive`, so a
client that never got its first connection could only ever show the calm
line, and a device sat for hours saying "waiting for network" under a
live wifi glyph. Error copy renders whenever the state says error,
latched or not (plan 0022). A rejected login is separated from a
transport failure because the two need different actions from whoever is
holding the handset.

`WataLogic.stillBooting` is where those two pressures meet (plan 0035):
a failure outranks the calm copy only once the device could plausibly
have succeeded. It could not while

- the pipe is `PipeNone` — the DEVICE's honest "interfaces exist, none
  holds an address". `PipeUnknown`, the host answer every off-device
  build and golden sees, is deliberately excluded: on a Mac pointed at a
  dead server the failure is the whole truth.
- `NetStatus.clockOk()` is false. The handset has no battery-backed RTC,
  so it boots at Jan 1 1970, and at 1970 every TLS handshake fails
  certificate validation — which takes out iroh's relay connections and
  its pkarr/DNS address discovery outright. A dial that fails in that
  window says nothing about the server. The check is a latch against a
  2025-01-01 floor: a clock only ever gets set, and the flag is a
  property of the machine, not of a client session (`NetStatus.reset`
  leaves it alone). Making the clock actually arrive is the rootfs's job
  — bq268-alpine `docs/planning/clock-at-boot.md`.

Two more things ride the frame step for the same reason (`Ui.frameStep`,
and its counterpart in wata-mac's pump):

- **The network arriving retries immediately.** `NetStatus.poll` marks
  the edge from no interface to an interface; the frame loop takes it
  (`takePipeArrival`) and pokes `Runtime.retryNow`. Without it a client
  whose backoff climbed to its 60s ceiling while it had no network sits
  out that ceiling with wifi already up.
- **One log line per connectivity change.** `NetStatus.logTransition`
  prints `net: +47s pipe=wifi conn=connecting clock=UNSET` on each change
  of the (pipe, connection, clock) tuple — a boot's whole story in four
  or five lines, against a transport that logs a dial failure once per
  distinct reason and can therefore look silent for an hour. The stamps
  re-zero when the clock steps from 1970, or every later line would carry
  a 56-year delta. The iroh dialer repeats a stuck reason every 60s with
  the count it swallowed (`go-pkgs/irohnet`, `logDialError`).

BOTH KEYS ARE LIVE on this screen, which is the other half of the same
fix — the screen a stuck user presses things on used to have exactly one
live key, and it quit:

- **OK = retry now** (`WataLogic.retryOnOk` -> `Runtime.retryNow`): pokes
  the client's backoff sleep so the next attempt happens immediately
  instead of at the end of a 60s ceiling.
- **Back = the two-step exit** (below); while it is armed the footer
  becomes `BACK again to exit`, since that is the only thing the next
  press does.

The lines are STATIC: the header's `..` is already the one moving thing
on the screen while the client is reconnecting, and a second animation
under it would be noise rather than information (were it to animate it
would ride `NetState.blink`, whose phase resets on a health change — the
discipline that keeps a scripted frame reproducible).

Once the latch is set it stays set for the session, so a later drop
shows the ordinary presentation — the contact list the snapshot still
holds, under a yellow header — and never the boot screen again. The
`early-boot` uitest scenario (`alice-boot.txt`) pins all four frames,
the last of them being precisely that non-return; `boot-retry` and
`auth-rejected` pin the failure copy, and `boot-retry` additionally
proves the recovery — its phase starts with NO server, the harness boots
one four seconds in, and the SAME process walks into an ordinary
session.

### Device identity and enrolment

`enrol.scala` — the device half of plan 0014. On a handset configured to speak
iroh (`WATA_IROH_CONFIG` names a file), the transport's allowlist decides
whether this device exists at all, and a brand-new device is not on it.

**The identity is minted on the device.** A handset is deployed with an iroh
config that names the family's server and carries **no secret**:

```json
{
  "peer":      "<the family server's node id>",
  "relay":     "n0",
  "peerAddrs": ["192.168.1.4:52011"],
  "adminUrl":  "http://192.168.1.4:8008"   // optional override
}
```

`Enrol.nodeId` calls `irohnet.EnsureKey` on first use: an ed25519 key is
generated in the process, written into that same file (temp + rename, `0600`,
every other field preserved), and only the **public node id** is returned. The
call is idempotent, so re-deploying never re-mints an identity that is already
enrolled, and it is tried exactly once per session — a config that cannot be
written will not become writable between two frames, and retrying inside the
render path would turn a broken deployment into a stuttering one. Nothing
secret ever crosses the gap; enrolment is the approval of a public id.

The base URL the enrolment QR (and the courtesy announce) points at defaults
to **`http://wata.local:8008`** — the Bonjour/mDNS name the server's install
publishes (`tools/server-service.py`; owner ruling on plan 0014). A concrete
LAN address baked at deploy time goes stale with the server's DHCP lease,
while the mDNS name follows the machine, and it is the *parent's phone* that
must resolve it — phones resolve `.local` natively. The iroh config's
`adminUrl` overrides the default (an unusual deployment), and `WATA_ADMIN_URL`
overrides both (how a harness pins the goldened QR). One caveat: the device's
own Go resolver may not resolve `.local` (no mDNS client on the handset), so
under the default the device-side announce can silently fail — which costs
only the typed-code fallback; the admin page announces on the device's behalf
when the parent scans the QR.

**The QR screen** (`Enrol.snap` + `Enrol.body`, a `wataui` body — plan 0024)
encodes `<adminUrl>/admin#enroll/<nodeId>/<nonce>` — about 106 bytes, which
`go-pkgs/qr` — a thin adapter over `rsc.io/qr`, an ordinary fetched Go
dependency, at level L — turns into a 37-module code. `snap` reads (the
identity, the URL, the encoder); `body` draws, scaling the module grid into the
one `VImage` wata has. It is centred at the largest whole pixels-per-module
that fits between the header and the code line: **2** on this panel, an 82x82 block, with
a two-module quiet zone (the spec asks for four; two is what 160x128 can
afford, and the extra pixel per module is what a camera actually needs). The
block is white with black modules — a QR on a black background does not scan.
Under it sit the typed code and one line of instruction.

The nonce is minted once per session from the millisecond clock, four
characters of an uppercase base-32 alphabet the server's `validNonce` accepts.
It is a correlator, not a credential.

**The typed code** is `<nonce>-<first 8 hex of the node id>` — the fallback for
a screen too dim to scan. It **selects** a pending row on the admin page and
cannot create one; the argument for why, and the resulting limitation (the
typed path needs a device that could announce itself, i.e. one on the family
network), is in `docs/design/wata-server.md` under Device enrolment.

**Two ways in:**

- **the boot state.** `WataLogic.bodyContacts` draws `Enrol.body`
  instead of the boot screen when `Enrol.required()` — iroh is configured *and*
  the transport has refused this node id outright. That refusal is
  `irohnet.LastRefusal()` containing `not allowlisted`, the loud reason the
  Rust layer formats and `Dialer.logDialError` records; it is a transport-level
  verdict, so it never reaches the portable core (the `HttpDo` capability folds
  every transport error into `HttpResponse(0, "")`) and it is read at the app
  edge exactly like `FbCaps.transportUnavailable()`. It is not an extra
  `ConnectionState`: `wataclient` cannot produce this verdict, so a case there
  would have to be synthesised at the app edge anyway. Being refused is not a
  network problem, so the calm "waiting for network" line gives way to the QR.

  **A refusal is loud but never terminal.** The transport keeps redialing on
  the sync loop's ordinary cadence, and the first dial that gets through clears
  `LastRefusal()` — so an approval a parent makes while the QR is on screen
  takes that screen down by itself, with nothing restarted. That is the whole
  promise of the enrolment flow, and it is where the first hardware enrolment
  broke: a refused client answers dials from its cached dead connection for a
  cooldown (`REFUSAL_COOLDOWN`, `go-pkgs/irohnet/rust/src/lib.rs`) before it
  attempts another handshake, and while every one of those fast local failures
  re-stamped the cooldown, a device that retried faster than it — the sync loop
  plus the boot screen's OK key — slid the window forward forever and never
  attempted one. Trying harder was what kept it out. Only a real handshake
  stamps the cooldown now; the Go tests pin it
  (`TestRefusedClientRedialsAfterAllow`, which dials faster than the cooldown
  throughout), and `just tunnel-smoke`'s enrolment leg approves a client
  process that is left RUNNING and refused.
  And a failure run that AGES rebuilds the transport's endpoint (plan 0030):
  a handset whose dials have failed for five straight minutes with no bytes
  ever arriving swaps in a fresh endpoint — new sockets, discovery, relay
  state — before its next handshake, the in-process form of the app restart
  that healed the field's long-refused handset after a network move. The
  aged-refusal gate (`go-pkgs/irohnet/aged_refusal_test.go`, inside
  tunnel-smoke) holds a client refused for a real minute and asserts both the
  rebuild and the prompt admission after the approval.
  **After the approval: the provisioning arc** (plan 0027). A handset
  enrolled this way has no password — its session comes from
  `POST /_wata/v1/device-login`, which `wataclient` calls whenever the config
  carries no password (`Runtime.freshLogin`): the iroh connection's proven
  node id is exchanged for a token belonging to the account bound at approve,
  and the session is saved through the ordinary `persistSession` path, so the
  next boot resumes on the stored token like any other. The window between
  the QR coming down and the first live frame reads as
  `Enrol.provisioning()` — this session WAS refused (`everRefused`, latched
  only by the real transport verdict, never a scripted forced one) and no
  longer is — and the boot screen says "setting up... / handset approved"
  there instead of "waiting for network", because the parent is watching that
  screen right after the approve click. The whole arc — refused with no
  credentials, approved with an inline-created account, device-logged-in,
  syncing as that account — is the `refused-then-provisioned` integ scenario,
  driven by `just tunnel-smoke`.
- **Dev settings -> Enroll** (behind the kid panel's development row, plan
  0053), offered whenever iroh is configured (`SettingsLogic`'s
  `ENROLL` row, `enrolOpen` in `SettingsState`). Back closes it; nothing else
  does, because a QR a stray keypress dismisses is a QR a parent has to go find
  again mid-scan — which is also why the shell's red-returns-to-kid-settings
  arm stands down while the QR is open.

**The announce is best-effort and rides the plain-TCP client.** Showing the
screen posts `{nodeId, nonce}` to `<adminUrl>/_wata/v1/enroll` once per session
(`FbCaps.plainHttp`, a 1.5s bound) — on a goroutine of its own, because the
frame that decides the screen appears is the frame that starts it and a bounded
1.5s is still 45 dropped frames. The once-per-session latch is set by the UI
goroutine before the spawn, so the "once" is decided by the cell's owner. Over
TCP, because announcing over the iroh
transport that just refused this device is the one thing that cannot work. It
is silent on failure: with plan 0014's page-side-announce ruling the parent's
phone posts the announce from the admin page anyway, so a failed announce costs
only the typed-code fallback. Announcing rides the screen appearing rather than
a keypress because nobody presses anything on a handset that has never
connected.

Goldens: the `enroll` uitest scenario (`alice-enroll.txt`) pins the
not-allowlisted boot frame, the settings row, the QR opened from it, and the
close. Both minted halves are pinned from the script (`enrolid`), and the
refusal is forced (`enrolstate`) — a hermetic run cannot provoke a real iroh
refusal. The admin URL is the harness's fixed `WATA_ADMIN_URL`, because the QR
is a function of the exact bytes of that string.

### The command poller

`cmdpoller.scala` (plan 0020). One goroutine long-polls the server's
device-command mailbox (`GET /_wata/v1/cmd/poll?wait=20`, wata-server.md),
dispatches each command, and reports each result back
(`POST /_wata/v1/cmd/report`). It authenticates with the session token
(`Runtime.lastAuth`), so it starts working once a session is up and rides
whatever transport the client is configured for. There is no device UI:
provisioning shows nothing on the handset beyond the connectivity element
reacting to the network change.

`Ui.loopWithDevice` starts it after `Runtime.start` and stops it at the
quit edge; sim and uitest deliberately do not — they stay deterministic,
and the integ scenario `wifi-cmd` runs the real loop instead. Lifecycle is
an epoch cell: `stop` bumps it and the loop exits after its in-flight poll
returns (bounded by the 20 s wait, under the http capability's 30 s bound).
A poll error backs off 3 s; an empty session waits 1 s for login.

**The three wifi ops** (`WifiCmd`, plans 0020/0031) shell out the way
`Diag` does, behind a host-fakeable seam, and every report tells the
truth about what the radio did:

- `wifi_scan` — `<cli> scan`, then `<cli> scan_results` polled until it
  SETTLES, where `<cli>` is `$WATA_WIFI_CLI`, else `wpa_cli -i wlan0` on
  the device, else the op reports `{ok:false, detail:"not on device"}`.
  The scan is asynchronous and an instant read answers the *previous*
  cached sweep (a visible network went missing that way), so the poller
  reads a baseline before triggering, then polls (500 ms) until the
  output moves off it or `$WATA_WIFI_SETTLE_MS` (default 4000) passes —
  a sweep identical to the cache costs the full window, which is
  indistinguishable without event listening. The tab-separated table is
  parsed to `[{ssid, signal, secured}]`: duplicate ssids collapse to the
  strongest row (one answer per band), hidden (empty-ssid) rows drop, and
  `secured` reads off the flags column (WPA/WEP/RSN).
- `wifi_join` — runs the alpine-provided helper (`$WATA_WIFI_JOIN`, else
  `/usr/local/bin/wifi-join`; the spec handoff is bq268-alpine
  `docs/planning/wifi-join-helper.md`) with the ssid as the ONE argv
  argument and the PSK on the helper's STDIN — argv and the environment
  are world-readable in /proc, stdin is not (`exec.Cmd.Stdin` via the
  `go.exec` facade). A helper that is not there reports
  `{ok:false, detail:"wifi-join helper missing"}` honestly. The helper's
  exit 0 means CONFIG APPLIED, not joined: **the verdict is the
  association outcome** (owner-ruled — a mistyped PSK once answered
  "join ok" while the radio silently roamed to a fallback). The poller
  probes `<cli> status` (per-line `key=value` match; a substring match
  on `ssid=` would hit `bssid=`) until the target ssid shows
  `wpa_state=COMPLETED` or `$WATA_WIFI_ASSOC_MS` (default 20000) passes,
  then reports `joined <ssid>` / `auth failed for <ssid>, still on
  <actual>` — ok is impossible without association. A bad join never
  destroys a working credential: the live conf
  (`/etc/wpa_supplicant/wpa_supplicant.conf`) is copied aside as opaque
  bytes before the helper runs, restored + `reconfigure`d on a failed
  association, deleted on success (no conf file — every dev host — makes
  each leg a silent no-op).
- `wifi_off {minutes}` — the cellular-fallback test switch: `<cli>
  disable_network all` (runtime only — nothing calls `save_config`, so
  persistent config is untouched and a reboot restores wifi), then an
  in-process auto-restore timer (`enable_network all` + `reassociate`
  after the window; default 10 min, clamped to 120;
  `$WATA_WIFI_RESTORE_MS` shrinks it for the harness). A second
  `wifi_off` re-arms via an epoch cell, never stacking restores. The
  report goes out after the radio drops — it arriving over whatever
  transport survives IS the test.

The integ scenario `wifi-cmd` is the seam's oracle: the real poller loop
against the fake cli/helper (`tools/integ-wifi-cli.py`,
`tools/integ-wifi-join.py`, plus the harness knobs
`WATA_WIFI_SETTLE_MS=0` / short `ASSOC_MS` / 300 ms `RESTORE_MS` and the
fake association state file `$WATA_WIFI_STATE`), asserting the parked
long-poll wakes, the parsed scan report (dedupe, hidden-row drop,
secured flags), BOTH join verdicts (the fake associates only `HomeNet`,
so a join to any other ssid drives the auth-failed arc), the capture
file's proof that the PSK traveled by stdin and never argv, the wifi_off
report plus the auto-restore firing (`enable_network` in the fake cli's
`$WATA_WIFI_CLI_LOG`) — and that the device's own non-admin token cannot
queue commands. On-device passes still owed: the join verdict against a
real mistyped PSK, and `wifi_off` proving the report rides cellular
(WATA-TODO.md).

### The exit is a menu

`back` on the contacts view is the only exit edge (`Ui.isQuitEdge`), and
it takes two presses within `Ui.QUIT_ARM_S` (2s): the first arms the
confirmation and the applet says so (on the boot screen and on the
contact list footer, via `FrameCtx.quitArmed`), an unconfirmed arm ages
out in `Ui.tickQuitArm`, and the second **opens the exit menu**. The
ordinary in-session `back` (conversation -> contacts, settings -> wata)
is untouched.

The menu (`exitmenu.scala`, plan 0040) offers five actions: restart the
app, reboot, power off, reboot to fastboot, reboot to EDL. It is modal —
while it is open it takes every key and the shell sees none, so nothing
moves under a person who is choosing how to leave — and it is the only
thing that ends the frame loop, through `Restart app` alone (inittab
respawns the app, which *is* the restart). The shell still updates behind
it, since the sync loop keeps running and its state should not be stale
when the menu closes; only the drawing is replaced.

**What takes two confirmations, and why.** Not the most destructive
actions — a reboot and a power-off are both fine, the handset comes back
by pressing a button. The split is **who can undo it**. Fastboot and EDL
end with a device that shows nothing, responds to nothing, and stays that
way until somebody with a cable and a host machine intervenes, so those
two need a second OK. `ExitMenuState.confirm` is a counter rather than
Settings' `armed` Boolean because a second step needs a third value; any
key other than OK returns it to 0, and it ages out (`ExitMenu.ARM_S`) so
an abandoned menu never sits one keypress away from EDL. The two rows are
drawn red even unselected: the menu should look different where it is
different, before anyone presses anything.

The actions themselves are `Diag`'s, shared with the Settings rows that
keep them — two surfaces over the same actions is the intent, since
somebody already in Settings should not have to leave to power off.
`Diag.reboot` is the one this added.

The oracle is the `exit-menu` scenario in `just fb-ui-tests`, and it
exists for the case a handset cannot check: one OK on `Reboot to EDL`
raises the counter to 1 and runs **nothing**, which the script asserts
through the `exitopen`/`exitrow`/`exitconfirm` probes, with a golden per
checkpoint. It also pins the negative — that any other key disarms —
because a menu that silently stayed armed while the person thought they
had left is the worst failure this screen has.

## Input

`Evdev` (`input.scala`) opens exactly `/dev/input/event0`,
`/dev/input/event1`, `/dev/input/event2` non-blocking
(`Evdev.open`, `input.scala:92`), reads raw 16-byte
`struct input_event`s (32-bit-ARM layout: 4+4+2+2+4 bytes,
`input.scala:34`), and maps kernel key codes to an app-level `Key`
sum type (`input.scala:11-22`): d-pad, enter/back, PTT (F1), the
headset PTT line (F2 — the BQ268 case has no such key; the code is
kept for the sim/script vocabulary), and two "dot" buttons (F3/F10)
used to switch applets. `KeyState` distinguishes
pressed/released/repeat. On the case, the front matrix keys are the
green (answer) key = enter, the red (hang-up) key = back, up/down,
and P1/P2 = left/right; on-screen hints name keys by those labels.

**Hold gestures** live in the wata applet, not the input layer, and all
three follow one shape: the press only records `<key>Held` + a zero hold
time, `WataLogic.tickTimers` accumulates `dt` into it every frame, and
the ACTION fires either from the timer (crossing the threshold — which
also drops the held flag, so the eventual release does nothing) or from
the release (below the threshold). PTT is the original — press starts
recording, release sends. Red in a conversation is tap = back, hold past
`BACK_HOLD_DELETE` (0.8s) = redact. OK in a conversation is tap = play,
hold past `OK_HOLD_FAVORITE` (0.8s) = toggle the favorite; because OK now
has a hold, its play fires on the RELEASE rather than the press, and it
is routed before the press-only dispatch (so `conversationInput` has no
`KEnter` arm at all).

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

**The startup chirp** (`chirp.scala`, `wata-fb/assets/chirp.ogg`) is the
short walkie-talkie bleep the handset plays once its audio route exists, so
a user holding a device with a ~40s boot can tell "ready" from "still
booting" from "broken". `AudioThread.mainLoop` plays it right after
`setupMixer()` and before the first command — the routes must exist for it
to be audible, and that thread owns the pcm device, so playing it anywhere
else would race the mixer setup. The `chirp` flag on `mainLoop` is what
selects it: the app passes true, the diagnostic drivers (`selftest`,
`devcli`) pass false, since they are judged by the sounds they make on
purpose. A PTT or roger beep is a further `Chirp.play()` call site from
that thread, not a redesign.

The asset is a **file beside the binary** (`/opt/wata/chirp.ogg`, or
wherever `WATA_CHIRP` points — the run-mode deploy lands it in `/dev/shm`),
not bytes compiled into the source, so ~3KB of audio stays reviewable data
instead of a generated array literal. `tools/fb-deploy.sh` ships it in both
run and install modes. The price is that a deploy can skew: a missing or
unreadable asset prints one line and the app runs on. `Chirp.play()` is
otherwise nothing new — `Ogg.readFrames` → `Decoder.decodeFrame` →
`playMessage`, the same path a voice message takes.

Format is the device's own — Ogg/Opus, mono, 48kHz — built from the owner's
source recording by `tools/make-chirp.py` (`just make-chirp`), which records
the trim and the encoder flags as code. Two of those flags are load-bearing:

- **`-page_duration 20000`** puts one 20ms Opus packet in each Ogg page.
  `Ogg.readFrames` treats a page's whole payload as a single frame, so an
  ffmpeg-default file — which packs many packets per page — reads as one
  2000-byte "frame" claiming 20ms and would play as a fraction of itself.
  The reader limitation is real and tracked as `OGG-MULTI-PACKET-PAGE`; the
  flag keeps the asset inside what our own reader understands. `wata-fb
  oggforeign` over the committed asset is pinned in the fb smoke
  (`tools/fb-chirp.expected.txt`), so a re-encode that loses the flag fails
  a gate rather than a boot.
- **`-fflags +bitexact`** makes the output byte-stable. Without it the Ogg
  muxer picks a random stream serial per run and stamps a vendor string into
  the comment header, so two encodes of identical audio differ in the serial
  and every page CRC, and "did the asset change" stops being answerable.

**Judging it on the device** is `tools/chirp-check.py` (`just chirp-check`):
it restarts the app while recording the room on this Mac and compares the
chirp's band (700-2600Hz, where the asset's energy is) against the same band
in a baseline taken moments earlier and against a neighbouring band in the
same recording — the shape bq268-alpine's `speaker-check` established, since
in-recording ratios are what keep a noisy room from deciding the answer. Two
differences from that one: it cannot play its own sound (wata holds `hw:0,0`,
and the point is to hear what the *app* plays), and the chirp is ~0.6s inside
a multi-second recording, so both recordings are scanned in 0.5s windows and
compared at their loudest one rather than by whole-recording RMS.
`--no-restart` is the negative control, `--cold-boot` reboots instead.
Measured on the handset: the chirp reads 8x its neighbouring band and 5-8x
the baseline; the control reads 0.3x the baseline.

On a cold boot the chirp can be inaudible while everything reports success:
the codec resets `RX2 MIX1 INP1` to zero as the Q6 comes up, and `SetupMixer`
runs once. The route watchdog (below) puts it back at the next stream open,
and the chirp still makes the underlying condition *audible*, which is the
point: a missing hello is the cheapest signal that something is wrong with
the route, available before anyone tries to send anything. There is no software volume anywhere in the client (`PlayVol` is a
fixed 8192), so the hardware knob's off position silences the chirp by
construction, exactly as it silences a message.

**The route watchdog.** `SetupMixer` applies the routes once, and the codec
does not keep them: when the Q6 comes up or restarts it resets `RX2 MIX1
INP1` and `DEC1 MUX` to `ZERO` (index 0 in both; the wanted values are `RX1`
at 3 and `ADC1` at 1). Neither failure announces itself. A zeroed playback
mux is a dead speaker with every other control still reading correct; a
zeroed capture mux is a microphone that records four seconds of digital
silence which encodes, sends, arrives and plays as nothing, which looks
exactly like a kid who did not speak — it cost a real message on 2026-08-07.

`RouteCtl` (`go-pkgs/audio/audio_linux.go`) is a pre-opened handle in the
shape `VolCtl` already established, and for the same reason: `mixer_open`
enumerates ~700 controls on this codec and cannot sit in front of a stream.
It resolves each mux's target enum *index* once at open, so the hot-path
check is one ioctl per mux and no allocation, and `OpenPlaybackTuned` and
`OpenCapture` call it before `pcm_open`. Opening fails if either control or
either value is missing rather than watching one of the two — a half
watchdog reporting "routes fine" is worse than none. A correction prints,
because it is the only visible trace that the Q6 restarted.

This complements rather than replaces bq268-alpine's `audio-mixer` service,
which applies-verifies-and-watches both directions for two minutes after
boot: that covers the boot race, this covers a Q6 restart *mid-session*,
after the watcher has stopped, which would otherwise silence the app until
someone rebooted the handset.

Verified on the handset by forcing both muxes to `ZERO` and running
`wata-fb --selftest echo`: the capture open put `DEC1 MUX` back and the
playback open put `RX2 MIX1 INP1` back, each logging before its stage, and
the run finished `SELFTEST PASS` with both controls reading their wanted
values — recovered with no restart. (Note for anyone repeating it: an
`amixer cset` on `DEC1 MUX` does not take while a capture stream is open,
so break the muxes with no stream running, or expect only the playback
correction to fire.)

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

## Arrival notifications

A message landing while nobody is looking at the handset announces itself
beyond the unplayed-count digit (plan 0041). **The model is `wataclient`'s,
not this client's** (`notify.scala`: `NotifyMode`, `Arrival`,
`Notify.step`) — the mac consumes the same one
(`docs/design/wata-mac.md`, "Arrival notifications"), so both clients
answer "which arrival is worth announcing" identically and only the
presentation differs. The frame loop steps it once per frame off the
snapshot the frame already read (`Ui.notifyFrame`, marks carried in
`notifyC` beside the other pump cells); the edge is a conversation's
unplayed count RISING with a NEW newest unplayed message, priming is
once per session.

- **Chime is the device default** (plan 0047: `notify_mode` absent =
  `chime`, and a stored `play` — the pre-0047 default nobody chose
  knowingly — loads as chime; the mac defaults to `quiet` — a desktop
  is not a walkie-talkie). An arrival under chime mode sends `AcChime`
  through the audio thread's mailbox — the thread owns the pcm device
  and does one thing at a time, so the chime (the startup chirp asset,
  `Chirp.play()`, one audio path) never cuts into a recording or a
  playback — and then sets the same banner quiet mode sets. The
  kid settings panel's Notify row cycles play/chime/quiet.
- **Play-now (auto-play) is the walkie-talkie mode**, the kid Notify
  row's third value since plan 0055 (plan 0047 had parked it behind
  future focus-modes work). An arrival under it sends the same
  `ActPlay` the applet's OK press sends and marks the applet playing
  through the `Shell.notifyWataPlaying` shim, so the existing
  `AePlaybackDone` arm sends the read receipt — an auto-played message
  really becomes played. The `canAutoPlay` gate is the mac's
  (`!playing && !pttHeld`); an arrival that loses it falls through to
  the quiet channels rather than queueing.
- **The volume knob needs no software.** `PlayVol` is fixed; the pot is
  analog and pre-PA, so its off position silences the chime exactly as
  it silences the chirp and a playback — the knob is the mute switch,
  which is what "chime only when the volume is up" means on this
  hardware. A chimed message stays unplayed either way; only actually
  playing it receipts it.
- **Quiet mode announces on three channels, all derived from the one
  number** (`Notify.totalUnplayed` + the per-conversation counts the
  contact list already badges — no second state threads through the sync
  engine): the green LED **blinks** ~1 Hz while anything is unplayed (the
  screen-off channel — see the LED arbiter below); a **banner**
  (`Ui.bannerView`, two rows at the top of the panel showing
  `Notify.title`/`Notify.body`) stays up `BANNER_MS` (~4s), drawn only
  while the screen is on, never over the modal exit menu, and not when
  the announced conversation is already open on a lit screen; and the
  contact row of any conversation with `unplayedCount > 0` carries a
  **yellow underline** (`contactRowView`'s `"unp"` child) — persistent
  until played, because it renders the count, not the edge. An arrival
  never wakes the screen: a handset in a dark bedroom staying dark is a
  feature.
- **Priming latches on sync-caught-up, and an arrival is a NEW newest
  message** (plan 0043, `notify.scala`). The session primes on the first
  snapshot with `caughtUp` true — the first fully processed `/sync`
  round, conversations or not — so a first sync the engine delivers
  across several snapshots primes on the complete picture and none of
  its pieces read as arrivals; a fresh account primes on an empty
  picture, so the first thing anyone ever says still announces. And
  announcing takes the count rising AND the newest unplayed message
  changing, so the backfill walk appending older history (which raises
  the count with no live event behind it) moves the badge and stays
  silent. Either guard alone leaves a hole — backfill runs long after
  the first round, and a split first sync's tail IS a new newest. The
  fb-ui receiving phases that force `notifymode quiet` before badge
  checkpoints keep doing so: they pin the real invariant that badge
  state does not depend on notify mode, and cost nothing.
- **Every arrival prints one decision line** to the app log:
  `notify: play|chime|quiet|suppressed "<title>" "<body>" unplayed=<n>` —
  the assertable half of the presentation (`suppressed` = the person was
  already looking at that conversation).
- **The mode is device config**, a `notify_mode` key in the config store
  with its own cell and `loadNotifyMode`/`notifyMode`/`saveNotifyMode`
  (`config.scala`) — deliberately NOT an `FbPrefs` field, since the
  shared settings applets construct that record positionally. The kid
  panel's first row cycles play/chime/quiet on OK or left/right (the
  developer menu's Notify row retired there — plan 0054 — and the
  mode has no dev-state mirror at all since plan 0055: the kid row is
  its only editor) and persists
  through `KidSettingsLogic.persisted`, the same
  seam as brightness/timeout. The applets are shared, so the mac's settings
  body grows the row too; its chrome commands `notify:play`/`notify:quiet`
  use the same `Notify.MODE_*` constants, so the spellings cannot drift.
- **The gate**: the `arrival-notify` scenario in `fb-ui-tests.py` — the
  `sendas` script directive lands a mid-session out-of-band arrival, the
  `unplayed`/`notifyled`/`notifyred`/`notifybanner` probes read the
  computed decisions, `notify-banner.png`/`notify-highlight.png` pin the
  pixels, the harness's `logs` assertion pins the decision lines, and
  play mode is proven by the `played` probe rising (the receipt went
  through the ordinary playback-done arm). The on-handset pass (LED
  visible, speaker plays, knob-off leg) rides the next `fb-deploy` —
  tracked in WATA-TODO.md.

## LEDs and peripherals

`Led` (`led.scala`) drives backlight, red LED, green LED, and button
backlight via sysfs writes (`/sys/class/leds/.../brightness`) through
`go.syscall` (`open`/`write`/`close`, `syscall.scala`). Every call is
best-effort: failures are silently swallowed (`led.scala:31`), which
means there is no way to detect from this code whether an LED write
actually succeeded — deliberate, since the dev host has no such sysfs
tree at all and the code needs to run unmodified there.

The LEDs are decided by ONE pure per-frame arbiter (`Ui.ledArbiter`,
plan 0041 — `onConn` used to write them directly on connection changes,
which left no room for a second meaning on the green):
`(connState, unplayed, nowMs) -> LedState(green, red)`. Red steady =
connection bad (error/auth-rejected/disconnected), as before. Green
carries two meanings ordered by urgency: BLINKING ~1 Hz (frame-clock
driven — the sysfs layer has no blink primitive) = unplayed messages
waiting, which is the screen-off announcement channel; steady = live and
idle. `Ui.applyLeds` writes through the `UiDevice` seam only on a change,
so a blink costs two sysfs writes a second, not sixty; the policy half
(`Ui.ledGreen`: 0 off / 1 steady / 2 blinking) is what the scripted
driver's `notifyled` probe reads. The backlight
brightness is user-configurable through the settings applet
(`SettingsLogic.brightnessUp/Down`, `applets.scala:664-676`,
30-second-to-never idle screen-off timeout,
`SettingsLogic.timeoutSecs`, `applets.scala:564`) and the idle timer
in `Ui.tickIdle` (`ui.scala:180`) turns the backlight and button
backlight off after that timeout, restoring them on the next input
(`Ui.wake`, `ui.scala:172`).

**Kernel framebuffer blanking is a separate axis from the backlight**,
and it is why an app that only restores the backlight can still face a
white screen: the kernel's console-blank timer (or an explicit
`FBIOBLANK`) puts the fbdev into a blanked state in which the ST7735S
panel displays WHITE with the backlight on and writes into the mmap'd
`/dev/fb0` never reach the glass — the app's frames are present in fb
memory but invisible until some VT event unblanks (historically: the
first keypress after boot). `Led.unblankFb` writes `0`
(`FB_BLANK_UNBLANK`) to `/sys/class/graphics/fb0/blank` — the
proven-on-device node; the `FBIOBLANK` ioctl reaches the same handler —
through the same probed-once `writeSysfs` discipline as the LED nodes,
so it is a silent no-op on hosts. `UiDevice.unblank()` exposes it
behind the seam (host backends no-op), and it runs at the two edges
where a blanked panel would otherwise swallow frames: device init in
`Ui.loopWithDevice`, right after `/dev/fb0` is opened, and every
screensaver wake (`Ui.wake`). The boot-side half — `consoleblank=0` on
the kernel cmdline so the console-blank timer never arms between
respawns — is bq268-alpine's (its
`docs/planning/consoleblank-cmdline.md`).

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
- **The outbox directory** sits beside it: `<config dir>/outbox/`,
  `$WATA_FB_OUTBOX` overriding. So one `$WATA_FB_CONFIG` names one
  device's whole persistent state, and two scripted runs cannot see each
  other's queue. `FbConfig.outbox()` creates the directory and PROBES it
  with a throwaway file; a directory that will not take a write yields a
  store reporting `persistent() == false`, which keeps the queue alive
  for the session and prints one line rather than pretending. Slots are
  `eN.msg`, written 0600, and freed by truncate-then-unlink — an unlink
  that fails must still not leave a delivered message readable next
  boot.
- **Contents**: `{homeserver, username, access_token, user_id,
  device_id}` — the Zig client's file shape — plus two fields the
  settings applet owns: `brightness` and `screen_timeout_idx`.
  Those are the device's preferences, not the account's (a display
  name IS the account's, and lives on the server):
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
  never overwritten with nothing. The `username` written is the run's
  configured one, except under device-login (plan 0027), which is
  configured with no username at all: `FbConfig.sessionUser` then
  derives the localpart from the `user_id` the server answered, so the
  stored session and every username-keyed read (`storedFor`'s resume
  match, any UI naming the account) agree instead of carrying `""`.
  The preferences at
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
applet bodies (`WataLogic.bodyContacts`'s `contactRowsView`,
`bodyConversation`'s `msgRowsView`) build their views from it directly —
there is no separate "apply message" step in this module.

The message rows render the snapshot's list as it comes, and that list is
**newest first** (see `docs/design/wataclient.md`), so row 0 — the top row,
and the row `enterConv` puts the cursor on — is the message that just
arrived — so opening a conversation puts the cursor on the message
somebody just sent, and OK plays it.

**The message cursor is an index anchored by event id.** `msgSelected`
stays the index the renderer and the scroll window read, but every
arrival inserts a row at index 0 and shifts the rest down, so an index
alone would slide the selection one message older per arrival — onto a
row the user never chose, which PTT, favorite and delete would then act
on. `WataState.msgAnchorId` carries the selected message's event id, and
`clampMessages` re-locates it in each frame's snapshot, moving the index
to wherever that message now sits. The anchor is `""` while the cursor is
on row 0 where `enterConv` left it: that cursor is not holding a message,
it is holding "the newest" — the walkie-talkie default, where the top row
is the message to play next — so it keeps tracking each arrival. An
explicit up/down sets the anchor to the row it lands on (and clears it
again on returning to row 0); an anchor whose message vanished (redacted)
falls back to the nearest surviving index, clamped, and re-anchors there.
The `cursor-anchor` uiscript scenario pins all three rules through the
`msgsel` probe.

Playing a received clip is a full round-trip: `ActPlay(mxcUrl)` goes to
`wataclient`, which downloads and hands PCM/Ogg bytes back through the
`AudioEvt` channel, and `WataLogic.onAudioEvent`
(`applets.scala:241`) reacts to `AePlaybackDone`/`AePlaybackError`.
**`AePlaybackDone` is also the only place this client posts a read
receipt** — the rule is `wataclient`'s and the reasoning lives there, but
its mechanics are here: `playSelected` records the playing message in
`WataState.playingRoom`/`playingId`, because by the time the audio ends
the cursor may have moved and `AePlaybackDone` carries no identity of its
own. Both fields clear when playback stops, so a stale target cannot
receipt the wrong message on the next clip. `AePlaybackError` posts
nothing.

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
| `integ <scenario> <baseUrl>` | `integ.scala` | the live scenarios (login, two-user sync, voice send/receive, read receipts, ordering, redaction, download-byte-equality, the server-minted family room, groups, the no-leave rule, session resume, canonical DMs, backfill, offline retry, auth rejection, an admin rename reaching a syncing client, and the outbox surviving an outage plus a restart) run against a live `wata-server`, each driven through `wataclient`'s real `Runtime`/action queue, printing `INTEG PASS/FAIL <scenario>`. |
| `--selftest [echo\|play\|all]` | `selftest.scala` | on-device audio-thread selftest described above. |
| `login\|voicesend\|voiceplay\|audiosoak ...` | `devcli.scala` | scripted, non-interactive actions against a live server: provision/login a user, record-and-send a clip, sync-and-play the newest clip, or run a long record/send/sync/download/play soak loop (intended to run under `GODEBUG=gctrace=1` to watch GC pressure — `devcli.scala:105`). |
| `sim [base] [user] [pass] [--once]` | `sim.scala` | the host simulator: the real frame loop drawn into a terminal — see below. |
| `gio [base] [user] [pass] [--scale N] [--frames N]` | `gio.scala` | the window shell: the real frame loop blitted into a Gio window — see below. Needs a `-tags gioshell` build (`just phone-blit`). |
| `uitest <script> <base> <user> <pass> <outdir>` (`-` in a credential slot = resume from the store) | `uiscript.scala` | one scripted, deterministic UI session with PNG checkpoints — see below. |
| `ui [base] [user] [pass]` | `ui.scala` | the actual product: the full on-device client. |

`integ` (the live scenarios, `wataclient`'s `Runtime` directly) and
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
log already classifies its cause. (The retired jest conformance runner,
`wata-tests.sh` — in git history at `27a2f75` — was the one harness
pinned to a fixed :8008, because the read-only TS suites hardcoded that
URL per file.)

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

Twenty-three scripted scenarios, each a fresh server and a sequence of
one-user phases:

| scenario | what it pins |
|---|---|
| `voice-alice-to-bob` | the send path end to end: the server-minted family room is there from boot (plan 0018 — no bootstrap phase exists anywhere in this suite), alice holds PTT and sends; bob runs, opens the conversation and renders the message row. Goldens both contact lists, the post-send frame and the settings menu. |
| `conversation-actions` | the conversation view's own inputs: alice sends thirteen clips (one more than the twelve rows that fit), scrolls the selection to the bottom, redacts one by holding red past `BACK_HOLD_DELETE`, and favorites another by holding OK past `OK_HOLD_FAVORITE`; bob then receives the twelve and plays one. Goldens the full window, the scrolled window, the post-redaction list, the starred row, and the played marks. Bob's goldens carry alice's star too, which is what pins the marker travelling as ordinary room state. |
| `cursor-anchor` | the message cursor's event-id anchoring, via the `msgsel` probe: an idle cursor on row 0 stays on 0 through an arrival (tracking newest), a cursor moved one row down keeps the SAME message as an arrival shifts its index from 1 to 2, and redacting the anchored message falls back to the nearest surviving row. Goldens the held highlight two rows down. |
| `group-list` | plan 0018's list rendering: the `group` directive mints "kids" through `POST /_wata/v1/group` (server-stamped, both members joined server-side), and the goldens pin the roster `[Family, kids, Bob]` and the opened group view titled by the stamp's name. |
| `dm-roundtrip` | the canonical-DM flow (plan 0007) rendered: alice selects bob's ROOMLESS roster row, the first PTT send resolves the room through `POST /_wata/v1/dm`, bob receives with an unplayed badge, receipts, plays, replies, and alice's second session pins the reply and the badge clearing. Goldens the roster before/after, both conversation views, and the badge lifecycle. |
| `family-three` | a third account (per-scenario `$WATA_USERS`), all three boot-joined by the server: all three send into the family room. Goldens charlie's roster (the family plus TWO DM-able contacts) and the conversation with three-way sender attribution and interleaved ordering. |
| `badges-across-restart` | unplayed counts across a restart: bob sees family=1 / DM=2, resumes with no credentials, and the badge frame is byte-identical; playing out the DM clears only its own badge. |
| `send-play-failed` | the failure flashes, against a server failing on demand (`WATA_TEST_HOOKS=1` + the `failnext` directive): an armed upload 500 draws `SEND FAILED` over a row that now carries the unsent mark, an armed download 500 draws `PLAY FAILED`, and the retry after each succeeds — the self-disarming counter is the disarm, and the send's retry is the OUTBOX's, not a second press. |
| `outbox-restart` | a message survives an outage and a restart (plan 0022): with every upload answering 500, the send is queued and the row marked; a SECOND PROCESS resumes from the config store, finds the queue on disk beside it, and — once the hook is disarmed — delivers it on the next sync round, clearing the mark. Goldens the marked row, the cleared row with its badge, and the message in the conversation. |
| `playing-hung` | the playback mark and the hung download: a SIGSTOPped server (`stop_server_after` + `http_timeout_ms: 1500`) means the fetch never answers, so the mark drawn on the OK release stays until the deadline turns it into `PLAY FAILED` and clears it. |
| `early-boot` | the applet's boot presentation and its session latch: the earliest cold-boot frame (`starting up...`, no interface), a dial that FAILED while there is still no interface (`starting up...` again — plan 0035's calm-outranks-failure rule, and the frame the field sequence got wrong), the frame after an interface appears and the client starts trying (`waiting for network`), the ordinary contact list once the link has been live once, and — after a scripted health drop — that the boot screen does NOT come back. Forced with `conn`/`netpipe` from the first frame the script steps, so no frame is ever polled live before the boot frames are taken. |
| `conn-status` | the header's connectivity element and the status line it shares its computed state with: connected (`NET` off-device), reconnecting on both phases of the `..` alternation, disconnected, and — through the `netpipe` override — the device-only wifi and cellular glyphs and the `OFF` state, whose red status line the client's own belief that it is syncing does not override. |
| `settings-walk` | every settings item and its detail block: the echo test, brightness down two steps, the screen-timeout picker, network, device info (battery/uptime/memory), and the device rows absorbed from system-menu — the IP and cellular info rows, the net test and the wifi/data toggles (all an honest `n/a` on the host, the toggles reporting `not on device` after their armed OK), the confirm arming on a power row, the guarded no-op on the second OK, and a move-away cancelling an armed action. Nineteen checkpoints in one phase rather than a second scenario: every frame's scroll window and detail block depends on where the walk is, and a fresh server would only re-derive that. A second phase with no credentials goldens the same menu with the changed preferences restored from the store. |
| `session-resume` | the config store: one phase logs in with arguments, the next starts with `-` in every credential slot and has to come up on the stored token. The phase running at all is as much the assertion as its frames. |
| `boot-retry` | the connect lifecycle off the happy path (plan 0022): the phase starts with NO server (the scenario's `late_server` key boots one four seconds in), so the client faces a failed first login. Goldens the `can't reach server / retrying...` boot copy with its key footer, the armed two-step quit, and — with no restart, the same process — the ordinary contact list once the server appears. Also asserts the loop is still attempting (`logins`) and that the quit arm ages out. |
| `auth-rejected` | the scenario's `password` key hands the phase the wrong one: the boot screen must say `account rejected / check server`, not `waiting for network`, and the loop must still be alive behind it (OK pokes it). Needs no `conn` forcing — a rejected login reads as DOWN, and a down header draws no `..`. |
| `hung-server` | the server that ACCEPTS and never answers: the harness SIGSTOPs it six seconds in (`stop_server_after`) with `http_timeout_ms: 1500`, so the per-request deadline fires inside the test. A hung round becomes a `connerr` and a hung upload becomes `SEND FAILED` — and the script REACHING ITS END is the assertion that the frame loop never blocked on the client. |
| `disconnect-quit` | Settings -> Network OFF and then the quit edge, i.e. `Runtime.stopClient` twice. The frames are incidental; the run printing `UITEST PASS` is the assertion, since the second close used to panic. |
| `snake` | the snake applet end to end, on exact frame counts (the game is pure virtual-clock work once open, and the food PRNG is fixed-seed, so `idle N` lands on exact game states — `tools/snake-frames.py` is the mirror that designs the counts): the fresh board, the first eat (score, growth, the next food), the turn-and-wall game over with its overlay, the OK restart continuing the PRNG sequence, and red leaving back to the wata applet. |

A few things the scripts need that are worth knowing. `waitmax` is the
mirror of `wait` — advance until a probe drops to a bound — because a
redaction shrinks a count, which an ordinary `wait` (a `>=` test)
already satisfies. `idle` runs frames with the real per-frame pause
switched off: a timer expiring needs simulated time, not network
progress, which is what makes the screensaver's half-minute of blanking
cost the suite nothing. A phase whose credentials are `-` cannot
also run the out-of-band `group` directive, since that logs in
directly and a resumed run has no password: mint in one phase, resume
in a later one. (There is no family bootstrap at all: the server mints
the family room at boot with every account joined, plan 0018.) `failnext <n>` arms the server's
`WATA_TEST_HOOKS=1` fail-on-demand counter (wata-server's testhooks.scala;
the harness starts a scenario's server with the env var only when the
scenario opts in, and probes the hook route on EVERY server so the
production 404 is asserted each run), and the `sendfail`/`playfail`
probes over the session tallies are what a script waits on after
provoking a failure; `conntag`, `logins`, `connerr`, `quitarm` and
`frames` are the connect-lifecycle probes (the connection the frames
report, the client's login attempts, its error transitions, the armed
quit, and the frame counter). `conn <state>` and `netpipe <pipe>` force the connectivity
element's two inputs (see "The connectivity element"), which is the only
way to reach a bad-connection or device-interface frame from a host with
a healthy loopback server. And a scenario's `users` key writes a
`$WATA_USERS` accounts file, which is how a phase gets a third login.

### The window shell (Gio)

**`just phone-blit`** — the same frame loop again, this time in a
desktop window, and the road to a phone client (plan 0023 milestone 2:
"the phone is a bigger BQ268"). The window blits the 160x128 RGB565
buffer as an integer-scaled, nearest-neighbour texture with a row of
touch buttons under it — UP, DOWN, OK, BACK and a wide PTT — so the
entire existing UI, goldens and all, runs on a touchscreen with no new
UI code. Android and iOS fall out of the same Gio build (packaging is
not done yet).

Everything Gio lives in the plain-Go module **`go-pkgs/gioshell`**,
behind a primitive-typed facade (`go.gioshell`, `gioshell.scala`) in
exactly the shape `go.audio` uses: bytes, ints and bools cross, nothing
else. `GioDevice` (`gio.scala`) is then a nine-line `UiDevice`.

**Two event loops, and which goroutine each owns.** Gio's `app.Main`
must run on the process's main goroutine (macOS runs its window server
there) and never returns, so `gio` is the one front end whose frame
loop is NOT the main goroutine: `Gio.loop` forks the frame loop and
then sits in `app.Main` forever. The device object stays inside that
fork — `Gio.drive` builds it there — which is what keeps "a `UiDevice`
never crosses a goroutine boundary" true, and is also what the fork's
crossable-capture check demands. Only frame bytes and packed key ints
cross, through the shell's own mutex and atomics. `present` copies the
frame (the caller's buffer is reused by the very next frame) and wakes
the window with `Invalidate`; `pollInput` drains a queue the window
loop fills from pointer and key events; `frameSleep` paces as the sim
does; the backlight calls are no-ops.

This relies on the emitted `func main` and the body of `sgo.supervised`
both running on the Go main goroutine. They do, and nothing about that
is accidental — but it is a guarantee this backend now depends on
(sgola ticket `MAIN-GOROUTINE-GUARANTEE`).

Teardown crosses the same seam: the frame loop's quit edge (Back twice
on contacts) ends in `gioshell.Done`, which exits the process because
`app.Main` will not give the main goroutine back; closing the window
sets the quit flag, waits for that teardown, and exits anyway.

**Input.** A press inside a button zone sends the key down and the
matching release sends it up, so PTT is a real press-and-hold — the
same `KeyState` pair evdev delivers, with none of the terminal sim's
repeat-gap inference. Desktop keys mirror `fb-sim`'s mapping: arrows =
d-pad, Enter = OK, Esc/Backspace = back, Space = PTT, `z`/`x` =
prev/next applet, `f` = F2.

**The blit may not touch frame content**, and that is asserted rather
than assumed. `Expand` (RGB565 → RGBA by bit replication, the same
widening the PNG golden encoder uses) and `ScaleNearest` (each source
pixel becomes an s×s block, no interpolation) are pure functions with
plain-Go tests that `just fb-smoke` runs. On top of that, a
GPU-backed test renders the real view through Gio's headless surface
and reads the pixels back, so what the window actually draws is checked
against the source frame, not just the arithmetic. `just phone-blit`
runs that one (it needs a GPU context, though no display).

**Audio does not work here.** The shell reuses `SimAudio`, the same
no-op stand-in the terminal sim uses — there is no codec and no ALSA on
a desktop — so a voice message records instantly and plays silently.
The button row says so on screen.

Gio is opt-in at the Go build tag `gioshell`: an ordinary `sgo build`
(and therefore the armv7 device cross-build and the linux/amd64 smoke)
compiles the package's window-free stub and grows no window toolkit,
the same arrangement `go-pkgs/irohnet` uses for its real transport.
`tools/phone-blit.py` does the tagged rebuild, boots a scratch
`wata-server` unless given a `--base`, and opens the window;
`--frames N` quits after N presented frames and asserts the window
drew, which is as far as an unattended run can go — clicking is the
owner's job. Gio needs a GUI session with an *active* display: a
sleeping or locked screen is enough to stop the window appearing, and
the shell says so on stderr after five frameless seconds instead of
sitting there mute.

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
| delete a message (hold-red redact) | `conversation-actions` |
| favorite a message (hold-OK), star rendered on both sides | `conversation-actions` |
| long conversation: scroll window, selection clamp | `conversation-actions` |
| selection anchored across an arrival (same message, not same index) | `cursor-anchor` |
| sender attribution, >2 participants, interleaved ordering | `family-three` |
| send failure feedback (`SEND FAILED`) and recovery | `send-play-failed` |
| play failure feedback (`PLAY FAILED`) and recovery | `send-play-failed` |
| every settings item, preference persistence | `settings-walk` |
| screensaver blank + wake swallow | `settings-walk` |
| snake: open, steer, eat, game over, restart, leave | `snake` |
| connectivity is legible: which pipe, reconnecting, no interface | `conn-status` |
| mid-phase network drop / degraded boot | PARTLY — `conn-status` pins what the UI DRAWS for every bad state (the states are forced); an actual drop mid-phase still needs a proxy or a server pause, and rides the roaming flip test of plan 0013 |

## Parity with the Zig fbclient

The Zig fbclient (`src/fbclient/` in git history — last present at
commit `27a2f75`) is the behavioral spec for this module: it was
feature-complete against the TUI before the Sgola port started. This
table is the feature-by-feature comparison, derived by reading
`src/fbclient/src/applets/wata.zig`, `applets/settings.zig`,
`main.zig`, `shell.zig` and `config.zig` (all at that commit) against
`applets.scala`, `shell.scala` and `ui.scala`.

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
| open conversation (NO receipt: a receipt means heard, and only playback posts one) | receipts | no receipt | wata-fb changed the rule — see `docs/design/wataclient.md` |
| message rows: duration `m:ss`, sender, played check-mark, gray-when-played | yes | yes | same |
| OK tap = download-and-play, receipt when the audio ENDS | receipt on play start | receipt on `AePlaybackDone` | same gesture; wata-fb acts on the RELEASE, because OK now also has a hold, and a failed playback receipts nothing |
| hold OK past `OK_HOLD_FAVORITE` = toggle the message's favorite | no | yes | wata-fb only (plan 0019): the star keeps the clip past media retention |
| a favorited row's star, right-aligned | no | yes | wata-fb only; `Font.ICON_STAR` (0x8D), drawn from the server's `net.wata.favorite` state |
| hold red (or F2 in sim/scripts) = redact the selected message | F2 only | yes | wata-fb adds the red hold; a red tap still navigates back |
| message scrolling past the visible window | yes | yes | same |
| a selection left past the end of a shrunk list | not reconciled | reconciled every frame | wata-fb only, see below |
| the rows above under test | n/a | yes | the `conversation-actions` scenario |
| **Settings** ||||
| echo test driven over the audio command mailbox | yes | yes | same |
| brightness ±5, clamped 0..40, sysfs write-through | yes | yes | same |
| screen-timeout picker 30s/1m/2m/5m/Never | yes | yes | same |
| display-name preset picker, OK sets it over Matrix | yes | no | dropped (plan 0021): display names are account state an admin sets on the server, and the fan-out puts the new name on a syncing handset with no restart |
| network disconnect (stop sync + actions, restart to reconnect) | yes | yes | same |
| brightness / screen-timeout survive a restart | no | yes | wata-fb only — the same config store the session lives in |
| battery percent in the Info detail | yes | yes | same, `Led.readBatteryPercent`; absent hardware reads -1 and the line is left out |
| battery / uptime / free memory in the Info detail | battery only | yes | uptime and `MemAvailable` read straight out of `/proc` (system-menu shells out to awk for the same number); `n/a` off-device |
| wlan0 IP + cellular-data info rows (link + signal dBm) | no (system-menu) | yes | absorbed from system-menu (plan 0003 phase 5): `Diag.wlanIp`/`cellData` mirror its sources — `ip -4 addr show <iface>`, the ppp0 sysfs node, and `qmicli --nas-get-signal-strength` — re-read every ~5s; off-device both rows answer `n/a` |
| net test: ping gateway / 1.1.1.1 / 8.8.8.8 + DNS probe | no (system-menu) | yes | same four probes, same command lines, verdicts in the row's detail block; synchronous (a frozen frame loop for a few seconds, as in system-menu); off-device it runs nothing and says `n/a` |
| wifi ON/OFF, cellular data start/stop | no (system-menu) | yes | `rc-service wifi start`/`stop` and `pppd call cellular &`/`killall pppd`; both take the power rows' two-OK confirm and NEVER retry — the modem accepts one data call per boot, so a failure is reported on the row instead |
| power off / reboot to BL / reboot to EDL | no (system-menu) | yes | same commands system-menu runs (`poweroff`, `/usr/local/bin/reboot-bootloader`, `/usr/local/bin/reboot-edl`) via `go.exec`; OK arms (the detail rows become a red confirm prompt), a second OK runs, any other key cancels. `Diag.runOnDevice` gates on the lcd-bl sysfs node so off the hardware the run is a logged no-op; on-device verification is the boot-into-wata phase's checkpoint |
| detail area under the menu | rows 16..19 of 19 | rows 13..14 of 15 | same idea, sized to the landscape grid |
| every settings item under test | n/a | yes | the `settings-walk` scenario |
| **Chrome / feedback** ||||
| 1px status line colored by connection | yes | yes | same colors; wata-fb derives it from the connectivity element's computed state, so `OFF` (no interface with an address) reddens the line too |
| header + connection indicator (`ok`/`..`/`ERR`/`off`) | yes | replaced | wata-fb draws the connectivity element in that slot instead: pipe glyph (wifi / cellular) or `NET` off-device or `OFF`, plus `..` while reconnecting — the Zig indicator reported only the client's health, which is half the answer to "why did the radio go quiet" |
| connectivity: pipe + health, under test | no | yes | the `conn-status` scenario |
| pre-sync placeholder (`Connecting…`/`Syncing…`/…) | yes | yes | same |
| PTT overlay: red bar + hold timer | yes | yes | same, with a `REC` prefix |
| `SENT` / `SEND FAILED` / `PLAY FAILED` flash | yes | yes | same, plus `MIC FAILED` for a recording failure (the Zig client folded it into `SEND FAILED`) |
| screensaver blank + wake-swallows-the-keypress | yes | yes | same, and covered by `settings-walk` |
| **Snake applet** ||||
| board: full-width grid of 6x8 cells, bottom row = score | 21x18 | 26x14 | same cells, sized to the landscape grid |
| 3-cell snake at board center heading right | yes | yes | same |
| arrows steer, buffered one tick, reversal ignored | yes | yes | same guard (against the applied direction) |
| tick 150ms/step, -5ms per food, 60ms floor | yes | yes | same |
| food +10, grow by one, red cell; green head, dark-green body, 1px cell gap | yes | yes | same |
| wall + self collision end the game | yes | yes | same |
| zero-padded score, bottom row | `SCORE:%04d` | same | same |
| GAME OVER overlay, OK restarts | yes | yes | same, hint says `OK` (the green key's label), not `ENTER` |
| food PRNG | wall-clock-seeded | fixed-seed minstd LCG threaded through the state | deliberate: deterministic under the uitest virtual clock; a restart continues the sequence rather than reseeding |
| implicit pause when switched away | shell ticks active applet only | `Shell.tickOne` skips the inactive snake | same behavior by special case (this shell otherwise ticks every applet) |
| leave the game with red | no key (dots only) | red returns to the wata applet | wata-fb adds the shell's red-goes-back convention |
| the rows above under test | n/a | yes | the `snake` scenario |
| **Not ported** ||||
| clock / charmap applets | yes | no | toys, out of scope |
| FreeType text rendering | optional | no | bitmap font only |

Three things worth stating outright:

- **`device_id` is stored but never populated.** `wataclient`'s
  `loginOrResume` publishes `AuthCreds(accessToken, userId)` and drops
  the login response's device id, so this module writes `""` there.
  Nothing reads it back — `Sessions.isValid` wants a homeserver and a
  token — and the field is kept only because the Zig client's
  `config.json` has it.

- **The settings detail area is sized to the grid it draws on.** The
  menu shows six items at two-row spacing (grid rows 2..12; a scrolling
  window over the eleven), which leaves
  rows 13 and 14 — the last two of the 15-row landscape grid — for the
  selected item's detail text, so two lines is what every item gets.
  The layout number this replaced, `2 + N_ITEMS * 2 + 1` = 15, was
  inherited from the portrait grid where 16 is a real row: it put the
  first detail line one row past the bottom and the second one off the
  panel entirely. (The Zig client has the same arithmetic and loses
  its own fourth echo-test line to it.)
- **The cursors are reconciled with the snapshot every frame.** Both
  lists change under the selection with no input at all — an arrival
  puts a message row on top, a redaction drops one, a peer leaving
  drops a conversation — and a selection left past the end highlights
  nothing and plays nothing. `WataLogic.clampSelection`, called from
  `update`, re-locates the message cursor's event-id anchor (see the
  conversation view section), pulls both cursors back inside their
  lists, and drags the scroll windows after them. The Zig client does
  not do this and has the same dead cursor after its own `F2`.
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
deletes it. `BQ268_HOST` and
`FB_CC` are overridable via environment variables; the device's SSH
host alias is expected to be `bq268` in the operator's `~/.ssh/config`.

`tools/fb-deploy.sh install` (`just fb-deploy install`) is the durable
path, and the only mode that is not transient: it builds the iroh binary
(an installed handset's transport is iroh, so `install` implies it),
lands it beside the running one, rotates the old one to
`/opt/wata/wata-fb.prev`, and kills the running app — tty1 respawns
`/opt/wata/start.sh`, so the new binary is up within a second and the
previous one is one `mv` away. It never touches `/etc/wata/iroh.json`:
that file carries the handset's minted identity, and an enrolled device
must not be re-identified by a deploy. It ships `chirp.ogg` alongside the
binary in both modes.

A deploy is only as trustworthy as the binary the build stage hands it,
and `sgo`'s go-build stage is a cache: it skips when its declared inputs
are unchanged. Those inputs include each `godep`'s sources — the
`go-pkgs/` trees — so editing `go-pkgs/audio` re-runs the stage. Its
SKIP line names what it hashed, with counts:

```
sgo: go build  SKIP (unchanged: 54 emitted .go, go.mod, 5 godep trees/27 files)
```

Read that line rather than trusting the word SKIP: a stage that under-
declares its inputs prints exactly the same cheerful SKIP as a correct
one, which is how a deploy can carry code nobody wrote. `RUN` means the
stage noticed a change; whether the *output* actually changed is a
separate question, answered by `go tool buildid <binary>` — a comment-only
edit legitimately produces `RUN` with an unmoved BuildID, because Go's own
cache sees identical compiled output. After a change to Go-side code,
check the BuildID moved before believing a device test.

The kill is spelled `pkill -f 'wata-fb[ ]ui'`. Without the bracket the
pattern matches the ssh session's own command line, which carries that text:
the install kills its own remote shell, the deploy exits 255, and everything
it was supposed to do has already happened — a failure that looks like a
network problem and is not one.

`just fb-shot` (`tools/fb-shot.py`) reads `/dev/fb0` over ssh and writes
the panel as a PNG — the live screen, without taking the panel over,
which is how a boot sequence is checked from the host. It shows the last
PAINTED frame: the screensaver stops rendering rather than clearing, so
a shot taken while the screen is off is the frame from before it went
off, not a stale bug.

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

The embedded iroh transport cross-builds for the device too
(`go-pkgs/irohnet`): `go-pkgs/irohnet/mklib.py arm` stages the Rust
staticlib for `armv7-unknown-linux-musleabihf` (rustup supplies the
target std; `zig cc` is the cross C compiler for ring's C sources,
behind a generated wrapper that strips cc-rs's clang-style `--target=`
flags), and the iroh-enabled device binary is a manual
`go build -tags iroh` in the sgo emit dir under the same
`GOOS=linux GOARCH=arm GOARM=7 CC="zig cc -target arm-linux-musleabihf"`
environment, with `-ldflags "-linkmode=external -extldflags=-static"`
(the `iroh` tag never rides `sgo build` itself — the driver has no tag
passthrough). The cgo directive adds `-lunwind` on linux/arm: Rust
std's panic machinery references `_Unwind_*`, which zig satisfies with
its bundled LLVM libunwind, statically. `just iroh-lan-smoke`
(`tools/iroh-lan-smoke.py`) is the repeatable proof: it cross-builds
that binary, boots wata-server over iroh on the host, deploys to
`/dev/shm` under fb-deploy's conventions (nothing installed, artifacts
removed), and runs the `login-syncing` integ scenario from the device
over the LAN — direct addresses, no relay. It needs the hardware, so
it is not part of `just ci`.

### Deploying an iroh-mode device config, and the flip

`tools/fb-deploy.sh` provisions the device's iroh config when
`BQ268_IROH_PEER` is set:

```
BQ268_IROH_PEER=<the server's node id> tools/fb-deploy.sh
```

It writes `/etc/wata/iroh.json` (`0600`) with `peer` and `relay`
(`BQ268_IROH_RELAY`, default `n0`) — **never a secretKey**, which the handset
mints itself on first boot — leaves an existing file alone so a re-deploy
cannot re-mint an enrolled identity, and runs the transient `/dev/shm` binary
with `WATA_IROH_CONFIG` pointing at it. The enrolment QR then points at the
default `http://wata.local:8008`; setting `WATA_ADMIN_URL` bakes an explicit
`adminUrl` into the config and exports it for the run instead.

That is a RUN, not an install. **Making iroh the handset's permanent transport
is a deliberate on-hardware step**, done once per device, and these are the
exact commands:

1. `wata-server` must be serving over iroh and reachable: start it with
   `WATA_IROH_CONFIG` and `WATA_LISTEN` (the dual listener — no browser can
   dial iroh, and `/admin` has to be loadable), and take its node id from the
   `irohnet: node <id>` line it prints.
2. Provision the device config once, exactly as fb-deploy does:
   `ssh root@bq268 'mkdir -p /etc/wata && cat > /etc/wata/iroh.json'` with
   `{"peer":"<server node id>","relay":"n0"}`,
   then `chmod 600 /etc/wata/iroh.json`. (Add an `"adminUrl"` field only when
   the QR must point somewhere other than `http://wata.local:8008`.)
3. Make the durable launcher pass it: `/opt/wata/start.sh` becomes
   `exec env WATA_IROH_CONFIG=/etc/wata/iroh.json /opt/wata/wata-fb ui`. The
   file lives in the `bq268-alpine` rootfs overlay, so a change that should
   survive a reflash belongs there, not only on the running device.
4. Reboot the handset. It mints its key, is refused (`401 not allowlisted`),
   and shows the enrolment QR instead of "waiting for network".
5. Scan it with a phone, sign in to `/admin`, approve the row. The device is
   admitted with no server restart.

Step 3 is the flip, and it is the only irreversible-feeling one: TCP-LAN
remains the fallback transport and every harness's default, so undoing it is
deleting `WATA_IROH_CONFIG` from `start.sh`.

## File-by-file map

| file | lines | what it does |
|---|---|---|
| `main.scala` | 93 | Top-level subcommand dispatcher; also has the pre-device "skeleton" smoke check that exercises the cgo path with a synthesized tone. |
| `syscall.scala` | 52 | `go.syscall` facade: thin binds for `Open/Close/Read/Write/Mmap/Munmap/Mkdir` plus the flag/prot/map constants, used by every device-layer file that touches `/dev/fb0`, `/dev/input/*`, or sysfs. |
| `config.scala` | 266 | The session and preferences store: `$WATA_FB_CONFIG` / `/etc/wata/config.json` read and write over `go.sys`/`go.syscall`, `FbConfig.resolve` (the arguments-override-the-store rule every UI entry point builds its `ClientConfig` with), and `FbOutbox`, the slot-file `OutboxStore` under `<config dir>/outbox/`. |
| `caps.scala` | 130 | App-edge implementations of `wataclient`'s `Clock` and `HttpDo` capability traits, over `go.time` and Go's `net/http`; `WATA_IROH_CONFIG=<json>` swaps the underlying client for the embedded iroh transport (plan 0013), nothing above the capability line changing. Every request carries a deadline (see "Request deadlines"). |
| `httpc.scala` | 20 | The `go.httpc` facade over `go-pkgs/httpc` — an `*http.Client` with a `Timeout`, the one net/http field the bound facade does not carry. |
| `irohnet.scala` | 35 | Sgola-side `@go.bind` facade over `go-pkgs/irohnet`: `newHTTPClient(config)`, an `*http.Client` whose connections are iroh streams (real with `-tags iroh` on darwin and linux/arm; loud-error stub elsewhere); `ensureKey(config)`, the device-minted identity; `lastRefusal()`, the dial-refusal reason the enrolment screen reads. |
| `qr.scala` | 18 | The `go.qr` facade over `go-pkgs/qr`, the thin adapter over the fetched `rsc.io/qr`: text in, the QR module grid out as one byte per module. |
| `enrol.scala` | 392 | Device identity and enrolment: the minted node key, the session nonce, the admin URL, the QR screen's snapshot and body, the typed code, and the best-effort plain-TCP announce. |
| `audio.scala` | 88 | Sgola-side `@go.bind` facade over the `go-pkgs/audio` Go package: constants, `Encoder`/`Decoder`/`Capture` opaque handles, `setupMixer`, `playMessage`, `tone`, `stateName`. |
| `display.scala` | 409 | RGB565 draw primitives (`Draw`), color constants (`Color`), the 5x8 bitmap font and glyph table (`Font`), fixed 160x128 geometry (`Display`). |
| `png.scala` | 127 | Minimal deterministic PNG encoder (CRC-32, Adler-32, one stored DEFLATE block) used only for the host-side golden-frame dump. |
| `input.scala` | 159 | `/dev/input/event{0,1,2}` reader: raw `input_event` decoding, kernel-keycode → `Key`/`KeyState` mapping, and `KeyBatch` (the non-generic box the `UiDevice` input edge needs). |
| `led.scala` | 63 | Backlight/LED control and the battery-capacity read, via sysfs; best-effort, errors swallowed, and a missing battery node reads -1 rather than failing. |
| `oggforeign.scala` | 22 | `wata-fb oggforeign` driver: reads a fixture file and prints `wataclient`'s foreign-Ogg oracle report. |
| `syncfixdriver.scala` | 28 | `wata-fb syncfix` driver: reads captured `/sync` fixture files and feeds them to `wataclient`'s sync-fixture oracle. |
| `audiothread.scala` | 349 | The background audio goroutine: record/playback/echo-test sessions over the `AudioCmd`/`AudioEvt` mailbox protocol, layered close-and-rethrow resource tiers around the cgo capture/encoder/decoder handles. |
| `chirp.scala` | 57 | The startup bleep: loads `/opt/wata/chirp.ogg` (or `$WATA_CHIRP`), decodes and plays it through the same reader/decoder/`playMessage` path a voice message takes, best-effort throughout. `Chirp.play()` is the whole surface, so a PTT or roger beep is a call site. |
| `fbtest.scala` | 102 | `fbdump` (host PNG golden) and `fbsmoke` (on-device fb/LED/evdev smoke test) drivers; also `FbTest.present`, the byte-copy blit used by the real UI loop too. |
| `selftest.scala` | 112 | `--selftest` driver: spawns the production audio thread and drives it through its real command mailbox for an echo test and a tone-playback test. |
| `shell.scala` | 214 | `ShellState`, the active-applet index, status-line coloring, and input routing/dispatch between applets (PTT-always-to-wata, dot-buttons switch applets, red-in-snake goes back to wata, everything else goes to the active applet; the snake is also the one applet ticked only while active). |
| `snake.scala` | 275 | The snake applet, ported from the Zig client's `applets/snake.zig`: packed-cell body, deterministic minstd food PRNG, tick/step game logic, and rendering; frame counts for its uitest scenario are designed with `tools/snake-frames.py`, an exact Python mirror. |
| `applets.scala` | 1579 | The `wata` and `settings` applets: their state records, wither-style update functions, input handling, and their `wataui` bodies (both applets are pure view functions painted by `FbPaint`); also the `Applet` interface and the shared `FrameCtx` per-frame context record. |
| `netstatus.scala` | 176 | The connectivity element's computed state (`NetState` = pipe + health + blink phase): the cached ~5s interface read, the `ConnectionState` mapping, the reconnecting animation's phase, and what the header draws for each combination — read by both the header indicator and the 1px status line. |
| `diag.scala` | 371 | The settings applet's device rows (`Diag`): the wlan0/ppp0/signal/uptime/memory reads, the ping+DNS net test and the goroutine that runs it off the frame loop, the wifi and cellular-data toggles, and the poweroff / reboot-bootloader / reboot-edl commands, all mirroring system-menu's sources and command lines; `onDevice()` (the lcd-bl sysfs probe) gates every read and every command. |
| `netexec.scala` | 73 | The `go.exec` facade over `os/exec` (`Command` at one, two and three arities, `Run`, `Output`, and the `Stdin` field as a pre-run setter) and the `go.netif` facade over `net` — what `Diag` and `WifiCmd` run their command lines through. |
| `cmdpoller.scala` | 260 | The command poller (`CmdPoller`, plan 0020): the goroutine long-polling the server's device-command mailbox and reporting results, plus `WifiCmd` — the `wifi_scan`/`wifi_join` device mechanics behind the `$WATA_WIFI_CLI`/`$WATA_WIFI_JOIN` host-fakeable seam. |
| `devcli.scala` | 288 | Non-interactive scripted actions against a live server: `login`, `voicesend`, `voiceplay`, `audiosoak`, each printing a greppable `PASS`/`FAIL` line. |
| `integ.scala` | 831 | Live-server integration scenarios exercising cross-user sync, voice send/receive, receipts, ordering, redaction, byte-exact download, the server-minted family room, groups (get-or-extend + server-side joins), the family no-leave rule, session resume, canonical DMs, backfill, offline retry, auth rejection, an admin rename landing on a syncing client, the outbox's queue/persist/drop/deliver cycle, and the command poller run end to end against the fake wifi seam. |
| `ui.scala` | 442 | The `UiDevice` seam and its real `FbUiDevice` impl, plus the product entry point: opens the framebuffer, wires the sync/action/audio threads together via `sgo.supervised`, and runs `frameStep` at ~30fps. |
| `gio.scala` | 129 | The window front end: `GioDevice` (present/LEDs/keys over the `go.gioshell` facade) and `Gio` (the forked frame loop, the packed-key decoding, the `--scale`/`--frames` flags). |
| `gioshell.scala` | 63 | The `go.gioshell` facade for `go-pkgs/gioshell`. |
| `sim.scala` | 352 | The interactive host front end: `SimAudio` (the mailbox-protocol audio stand-in), `SimTerm` (RGB565 → ANSI truecolor half-blocks), `SimDevice` (raw-stdin keys, inferred PTT release). |
| `uiscript.scala` | 673 | The deterministic scripted driver: virtual frame clock, script lexer and directives, live probes, PNG checkpoint dumps, and the out-of-band group mint. |

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
