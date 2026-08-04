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

- Backend crash (filed 2026-08-05, `wata-thicket-recursive-unit-walk`;
  acked same day: sgola queue top): a `Unit`-returning self-recursive
  list walk crashes `sgolaBackend` ("unsupported expression (Thicket)");
  file-context-dependent. Workaround everywhere: `while` over a `var
  cur`. FIXED upstream at `2083ef6` (2026-08-05): the Unit statement
  path lacked the tailrec recognizer; any reachable Unit-returning
  self-recursive method triggered it (the file-context sensitivity was
  dead-code elimination masking the isolated repro). Tail recursion
  emits bounded Go loops, so recursive walks are safe once the repin
  (SGOLA-REPIN in TODO.jsonl) lands.
- Facade gap (filed 2026-08-05; ruled A upstream, ticketed
  VARIADIC-FACADE-BIND — terminal state is a Scala-varargs bind with
  call-site spread): until then `exec.Command` stays five explicit
  arities in `wata-tui/src/main/scala/facades.scala`.
- Try-shape sensitivity (filed 2026-08-05; ruled A upstream, ticketed
  TRY-STMT-NESTED-THROWING-SHAPES — all three statement shapes should
  lower via the 9d4f544 hoist machinery): keep val-bound shapes until it
  lands.
- FYI from upstream 2026-08-05: F104 (`29cf82e`) fixed
  `sgo.makeChan/makeSlice/makeMap` in ctor/function-argument position;
  we never dropped the bind-to-val convention, so nothing to unwind —
  the next repin simply makes the convention optional there.

- `go.Slice[T]` sub-slicing (blocked the Opus decoder consumer) —
  GRADUATION-BRIEF ch.D.
- cgo cross-targets beyond linux/arm (a second device target) —
  GRADUATION-BRIEF consumer-driven ledger.
- ADT-valued `Atomic` cells / RWMutex reader-parallel cell — same
  ledger, first-consumer-triggered (Wata is the likely consumer).
- Emitter trap (residual of the fixed THROWS-CALL-IN-ARG-POSITION; loud;
  upstream ticket UNIT-THROWS-DEF): a USER-WRITTEN `def f(): Unit throws E`
  definition (not a facade — facade lone-`error` shapes like `exec.Cmd.Run`
  lower fine as of pin `9d4f544`) is a loud wall — give such a def a
  non-Unit result (or keep it a facade) until the fix lands.
- Emitter trap (upstream-known, filed FACADE-TYPE-ONLY-IMPORT-MISSING): a
  bound facade type referenced ONLY as a type, with no call into its
  package anywhere in the same unit, can miss its Go import — the symptom
  is `undefined: <pkg>` at `go build`. Stopgap: any call into the package.
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
- Emitter trap (residual): locals in *generic/template* bodies bypass the
  emitter's rename funnel, so reserved-name protection (`ok`, `err`
  suffixing) does not apply there — avoid Go-reserved-looking local names
  in generic code until TEMPLATE-LOCALS-NO-RENAME-FUNNEL lands.
  (Ordinary, non-generic bodies are safe: `ok` is reserved and locals are
  auto-renamed as of pin `a95b8b2`.)
