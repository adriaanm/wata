# WATA-TODO — known debt that moves out with the Wata code

*Created 2026-07-12 (designer session, during the graduation-brief
sweep-3 audit). Wata continues in its own repo (on Sgola); this doc
rides along with the code when it moves. Scope: Wata-app debt only —
anything sgola-direct lives in GRADUATION-BRIEF.md / the sgola
registry instead. Sources: M7/M8 brief scoreboards + ROADMAP exit
blocks + git log; each entry cites where it was recorded.*

## wata-server spec conformance

- **Unexercised surface** (M7 exit "Open/deferred", dispositions
  stand unchallenged but untested): `/publicRooms`, `createRoom` extras
  (initial_state/creation_content/PL-override).
- **The `/sync` allocation tax** (M7 exit: "the strongest perf
  lever") — profiled, recorded, unscheduled.
- **The actor-store refactor** — recorded M7 verdict: no evidence
  demands it; keep as a non-goal unless load says otherwise.
- Behavior pin worth remembering: wata-server does NOT auto-create
  users. Accounts come from `$WATA_USERS`; with it unset the built-in
  alice/bob pair applies, which is what all harnesses log in as.

## client / device

- **tinyalsa vendored patch — upstream call never made** (M8 ch.6:
  `grep "SGOLA PATCH" vendor/tinyalsa/src/pcm.c`; pull-mode
  sync_ptr on non-MMAP handles, kernel-semantics-correct, fixes the
  long-standing replay/stutter bug that also afflicted the Zig
  client). Decide: upstream it, or pin vendored-forever with the
  reason written down.
- **FFmpeg EOS-flag nuance on our Ogg/Opus output** (DOGFOOD M8
  ch.6b) — one look, as promised there: third-party tools reading
  our streams is ecosystem courtesy. (The writer itself is
  ci-oracled byte-exact; this is about the EOS flag convention.)
- **dot2 input bus undiscovered** (M8 ch.7): input handling opens
  /dev/input/event{0,1,2} only — a gap SHARED with the original Zig
  client (mirrored faithfully, out of port scope then). If dot2
  matters, discover its bus properly.
- **/dev/shm Zig-binary backup is transient** (M8 pickup note) —
  gone on reboot; the Zig client at /opt/wata was replaced in place
  by the Sgola binary. If a rollback artifact is wanted, store it
  somewhere durable.

## sgola-side items that Wata is waiting on (tracked THERE, not here)

- `go.Slice[T]` sub-slicing (blocked the Opus decoder consumer) —
  GRADUATION-BRIEF ch.D.
- cgo cross-targets beyond linux/arm (a second device target) —
  GRADUATION-BRIEF consumer-driven ledger.
- ADT-valued `Atomic` cells / RWMutex reader-parallel cell — same
  ledger, first-consumer-triggered (Wata is the likely consumer).
- Emitter gap (from the `[FB-SIM]` build): a trait's abstract method with
  a generic result type (`def pollInput(): List[KeyEvent]`) emits a bare,
  undefined `List` in the Go interface declaration — fails only at
  `go build`, violating the loud-wall doctrine. Workaround in-tree: box
  in a non-generic record (`KeyBatch`). Triaged upstream behind the
  TEMPLATE-TYPE-VOCAB session.
- Emitter trap (same build): the emitted `catch` arm names its Go error
  variable `err`, so a user `var err` in the enclosing scope collides
  (`cannot use … as error value`). Workaround: don't name things `err`
  near a `try`.
- Instantiation swap (from the `[CANONICAL-DM]` build): a `Mutex.withLock`
  lambda whose result is a DIFFERENT instantiation of a generic than the
  one it reads (`Option[DmPair]` in, `Option[String]` out) emits the two
  `Option` interfaces bound to each other's symbols — two opposite
  "does not implement" errors on one emitted line, at `go build`, not as a
  sgola diagnostic. Workaround in-tree: the block returns the Option it
  looked up and the mapping happens outside it. Filed:
  `WATA-WITHLOCK-OPTION-INSTANTIATION`.
- Instantiation gap (same build): a generic that appears ONLY in
  signatures and is never constructed (`List[StateSeed]`) is referenced by
  the emitted Go but never defined — `undefined: List__StateSeed` at
  `go build`, again with compile and link reporting success. Self-resolves
  once something constructs the type, but stays permanently reachable for
  a library that leaves construction to its consumers. Filed:
  `WATA-SIGNATURE-ONLY-GENERIC-NOT-INSTANTIATED`.
