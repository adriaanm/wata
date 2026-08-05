# Phone spike report — sgola-emitted Go through `gomobile bind`

Plan [0023](../../docs/plans/0023-sgola-everywhere.md) milestone 1, run
2026-08-05 against sgola pin `f0bce9e`, Go 1.26.5 (host) / 1.26.3 (pinned
emitter), Xcode 26.2, `golang.org/x/mobile@v0.0.0-20260803200217-62cee1672c8e`.

## The answer

**Yes.** `gomobile bind` compiles sgola-emitted Go for `ios`,
`iossimulator` and `macos` with no compiler-side accommodation, no build tags,
no source edits to `wataclient`, and no failed attempt: both binds succeeded on
the first run. A Swift process linked against the macOS framework logs in to a
`wata-server` over TCP, runs the sync loop, and prints a rendered
`StateSnapshot`:

```
hello watabind sgola-emitted go, wataclient linked
self @alice:localhost Alice
contacts 1
contact @bob:localhost
conversations 2
conv family with=- messages=0 unplayed=0
conv dm with=@bob:localhost messages=0 unplayed=0
family Family
```

That is the whole client core — sync engine, domain model, transport, the
supervised-scope concurrency — running as an AOT library inside a Swift host.

The one thing NOT proven: execution on iOS itself. There is no iOS simulator
runtime installed on this machine (see "The simulator leg" below). The iOS
slices are built, linked, and packaged; nothing has run them.

## What it took

1. A Sgola module (`watabind/`) that links `wataclient` and supplies the
   `HttpDo`/`Clock` capability impls — a strip of `wata-tui`'s `caps.scala`
   with the iroh branch removed. Plus one object, `Bind`, whose entire surface
   is `hello(): String` and `probe(hs, user, pass, timeoutMs): String`.
2. `aslib.py`: a 2-rule rewrite of the emitted tree (`package main` ->
   `package watacore`, `func main` -> `func RunCLI`). See friction #1.
3. A symlink so `sgo` can find `wataclient`. See friction #2.
4. A 30-line hand-written Go package (`watamobile/`) that gobind binds.
5. A bridging header, because gomobile names the macOS framework
   `Watamobile-Macos`. See friction #5.

Total new Sgola: ~190 lines. Total new Go: 30 lines. No product-tree change.

## Friction — sgola-attributable (tickets filed)

**1. `NO-LIB-EMIT-FOR-RUNTIME-LIBS` — no emission shape is an importable Go
library that carries the runtime.** `mode app` emits a whole-program
`package main` module (correct contents, un-importable); `mode library` is the
`@goexport` publish shape, which emits a runtime-free facade package and so
cannot carry a core-dependent in-link library — and `wataclient` is
core-dependent to its bones (`List`, `Option`, sealed ADTs, `sgo.Atomic`,
`sgo.supervised`). Neither shape is "the whole program, as a package". Every
non-Go host — gomobile, a C shared library, a Go program embedding the client
— hits this. **Workaround:** `aslib.py` rewrites the emitted package clause.
It works, and it is two `str.replace` calls, which is exactly why the emitter
should just do it: an `emitpackage <name>` marker (app-mode emission under a
chosen package name, `func main` suppressed or renamed) would delete this file.

**2. `INLINK-DEP-SEARCH-PARENT-ONLY` — in-link dep resolution cannot see a
library that is not a sibling.** `inLinkRoots` searches the declaring module's
parent dir, then the toolchain home (+`scenarios/`, +`.sgo/sil`). A module
nested deeper in the same repo — `tools/phone-spike/watabind`, wanting the
repo-root `wataclient` — finds nothing, and the error names only the roots it
tried. **Workaround:** `ln -s ../../wataclient tools/phone-spike/wataclient`.
Committing a symlink to make a name resolve is a smell; walking UP from the
declaring module to the repo root (or letting `sgo.deps` carry a relative
path) would fix it.

Both are mirrored in `WATA-TODO.md`'s waiting-on-sgola list.

## Friction — gomobile-inherent (no ticket; the report is the record)

**3. gobind's type surface is tiny** — signed ints, floats, bool, string,
`[]byte`, and interfaces/structs built from those. Nothing in the emitted core
qualifies: `MatrixClient` holds channels, `StateSnapshot` holds cons cells,
every ADT case is a pointer-shaped struct behind a marker interface. This is
not a sgola problem — a hand-written Go project has the same wall — but it is
a *design* constraint for M3/M4: the bind surface will always be a hand-written
shim, so it should be small and stringly, or the domain should cross as
serialized bytes. Notably, gobind does not choke on the un-bindable types; it
only looks at the named package's exported signatures, so binding the shim
never touched the emitted package's API.

**4. The bound call was blocking and thread-agnostic — ANSWERED by plan
0025.** `Runtime.start` forks into an `sgo.supervised` scope whose join is the
scope exit, so the first cut of this spike kept the whole scope inside one
call. Go's runtime creates its own threads under gomobile and the call arrived
on whatever thread Swift used, with no main-thread requirement and no
init-order surprise — but that shape hands the host no lifetime. wataclient
now has `ClientHandle` (`wataclient/src/main/scala/handle.scala`): `start`
returns a `Handle` whose supervised scope lives on a goroutine it owns, and
the outside gets `sendAction` / `snapshot` / `connection` / `events` / `stop`
+ join. The shim was rewired onto it and that rewire IS the sufficiency
proof — `watamobile` now exposes `Start`/`Watch(EventSink)`/`Live`/`HasSelf`/
`Report`/`Stop`, a goroutine drains the handle's bounded dirty-flag channel
into the sink, and `Probe` is written the way a Swift view controller would be
(start, observe, read, stop) rather than as one blocking call. Nothing parks a
thread and nothing sleeps. The printed report is unchanged, which is the
point: the same client, driven from outside.

A friction the rewire surfaced: **the app-mode link prunes the emitted package
to what `main` reaches**, so a bind-surface function no Sgola code calls is not
emitted at all (`Bind_events` vanished until `Bind.nextTopic` used it). Any
"emitted Go as a library" story needs an export marker; it is one more face of
`NO-LIB-EMIT-FOR-RUNTIME-LIBS` and is noted on that ticket.

**5. The macOS framework's name has a hyphen.** `gomobile bind -target=macos
-o Watamobile.xcframework` produces `Watamobile-Macos.framework`; a clang
module whose name contains a hyphen cannot be `import`ed from Swift, so the
shell reaches the API through `-import-objc-header` instead. gomobile's own
generated `Watamobile-Macos.h` also emits `#define __Watamobile-Macos_...`,
which every compile warns about (`ISO C99 requires whitespace after the macro
name`). Cosmetic, but it means the macOS leg cannot be a plain `import` — plan
accordingly if a macOS build ever ships.

**6. gomobile does not run under `tools/sgo-env.sh`,** so `GOWORK=off` and
`GOFLAGS=-mod=mod` have to be set for it explicitly (`spike.py`'s
`gomobile_env`). Same two knobs, different entry point.

## Numbers that change M2/M3 assumptions

- **Go version:** none needed. gomobile built with host go1.26.5 against a
  module declaring `go 1.26.3`; no pin conflict, no `GOTOOLCHAIN` gymnastics.
  The emitted Go is only *formatted* by the pinned Go — building it is
  version-agnostic within the usual Go compatibility window.
- **Size.** The `ios,iossimulator` xcframework is **46 MB** (device slice
  binary: 16 MB); the macOS one is **30 MB** (universal). That is the Go
  runtime + net/http + crypto/tls + the whole client, unstripped, for a
  ~11 kloc emitted Go tree. App Store thinning drops the simulator slice, so a
  device app carries roughly the 16 MB slice. Gio (M2) and generated Apple
  bindings (M3) add to this; a 16-20 MB floor is the starting point, not a
  surprise to discover later.
- **Build time.** A cold `gomobile bind` for iOS is a few minutes (it compiles
  the standard library per target); warm is well under a minute. Fine for a
  scripted stage, too slow for `just ci`.
- **cgo.** The spike is pure Go. `go-pkgs/audio` (opus + tinyalsa) is cgo and
  is *not* in this link; M3's audio path will be the first cgo-under-gomobile
  question and it is unanswered here.

## Gotchas worth folding into design docs

- `sgo build` rewrites the emission on every build, so any post-processing of
  the emitted tree (like `aslib.py`) is part of the build, not a one-off.
- `WATA_LISTEN` is read only on the iroh serve path; the plain TCP server takes
  its address from the positional argument. A harness that sets the env var and
  expects the port to move gets `:8008` and a confusing timeout.
- Contacts are built from family-room MEMBERSHIP: an *invited* user is not a
  contact. A fixture that only invites produces an empty contact list.

## The simulator leg — GREEN (custom device set sidesteps the TCC wall)

`spike.py --only sim` passes end to end: the shell runs INSIDE the iOS 26.3
simulator via `simctl spawn`, logs in, syncs, and prints the same report as
the macOS leg. The block below was real but is fully sidestepped: the device
set must simply not live under the symlinked `~/Library/Developer`. The sim
stage now runs every `simctl` call with `--set ~/.wata-simdevices` (override:
`WATA_SIM_DEVSET`) — a custom device set on the internal disk, which
CoreSimulatorService can write without any TCC grant. A smoke device costs
~0.1 GiB; the multi-GB runtime image was already on the internal disk either
way. No interactive grant, no daemon restart, nothing system-global.

How it originally failed, kept for the record:

- `xcodebuild -downloadPlatform iOS -exportPath /Volumes/MoorsExt/xcode-runtimes`
  succeeded. Note it BOTH exports the dmg (8.4 GB, on the external volume) and
  installs the runtime — no `simctl runtime add` needed. `iOS 26.3.1
  (23D8133)` is Ready. The install is not on the external volume: internal free
  space went 15 GiB -> 4.5 GiB.
- `swiftc -target arm64-apple-ios26.0-simulator` builds the same `main.swift`
  against the xcframework's `ios-arm64_x86_64-simulator` slice with no source
  change — only a second bridging header, because the iOS framework keeps the
  plain name `Watamobile`. So the simulator slice links, which is one more
  notch of proof than the bind alone.
- `simctl create` fails: this machine's `~/Library/Developer` is a symlink to
  `/Volumes/MoorsExt/Developer`, and `CoreSimulatorService` cannot write
  `.../CoreSimulator/Devices` there — `NSCocoaErrorDomain 513 / EPERM`, macOS
  TCC refusing a daemon access to a removable volume. That is a one-time
  interactive grant (Privacy & Security -> Files and Folders, or Full Disk
  Access, for `CoreSimulatorService`), not something a script can do, and
  moving device storage back to the internal disk is not an option at 4.5 GiB
  free.

With the sim leg green, the functional proof now exists on BOTH platforms:
the same Go compiled by the same toolchain through the same gobind-generated
ObjC surface, exercised natively on macOS and inside the iOS simulator.
