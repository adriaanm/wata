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
- Facade-subclass restriction (from the `[SRV-BODY-LIMIT]` build): a
  self-call of a nullary instance `def` on a class extending a `go.*`
  facade trait crashes the plugin (`unsupported expression (Apply)`, as
  an unhandled exception) — the identical shape on a plain class
  compiles clean, so it is the facade-callback lowering path, not
  anything inherent. Workaround: keep such members on an object.
  Filed: `WATA-FACADE-SUBCLASS-SELF-CALL`.
- Emitter trap (residual of the fixed CATCH-ERR-SHADOW): a *parameter*
  literally named `err` still bypasses the emitter's rename machinery —
  avoid that spelling for params until PARAM-ERR-COLLISION lands. Local
  vals/vars named `err` are safe (auto-suffixed since toolchain
  `1d49ec4`).
- Emitter trap (upstream-known, not yet hit here):
  `List(...)` varargs with a sealed-family element type
  (`List(KeyDown(...), KeyUp(...))`) fails at `go build` (concrete slice
  vs collapsed `[]any` template param) — build such lists via cons until
  LIST-VARARGS-FAMILY-ELEM-COLLAPSE lands.
- Emitter trap (from the `[FB-PNG-BLOCK]` build): an Int-LED string
  concatenation (`blocks + " blocks"`, i.e. `any2stringadd` with the Int
  on the left) compiles and links clean but emits `int + string` Go —
  `invalid operation: mismatched types int and untyped string` at
  `go build`. A String-led concat (`"" + blocks + " blocks"`) is fine,
  which is the tree's universal style anyway. Workaround: lead every
  mixed concat with a String. Filed: `WATA-INT-LED-STRING-CONCAT`.
