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
- Conformance flake note (2026-08-05): stress-tests "concurrent sends
  from both users (50)" failed once under a heavily loaded machine
  (full ci + subagent gates in parallel; 158s run) and passed 84/84
  immediately after on a quiet box (41s). Load-sensitivity, not a
  regression — rerun alone before treating it as red.
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

- **Happy-path sweep, lower-tier findings** (2026-08-05 sweep; the
  wedge/data-loss tier is scoped in plan 0022): backoff and 429-retry
  sleeps are not stop-aware (quit during backoff freezes the last frame
  up to 60s, `runtime.scala`/`mhttp.scala`); config WRITE failure is a
  silent no-op (a device that can't persist its token re-logins every
  boot with no sign, `config.scala:123`); zero evdev devices is a
  legal silent boot — UI renders, nothing responds (`input.scala:99`);
  mid-session staleness is glyph-only (no way to tell "no new
  messages" from "offline an hour"); backfill error exits reuse the
  page-cap exit so a failed walk never requeues; auto-join failures
  re-POST every sync round with no backoff; failed favorite/delete
  looks like the button did nothing (not optimistic, error discarded);
  `setupMixer` failure is silent (no audio at all, no error,
  `go-pkgs/audio/audio_linux.go:260`).
- **Transaction ids restart at 1 with the session** (noticed 2026-08-05,
  plan 0022): `Runtime.txnCounterC` starts at 0 in every process, while a
  RESUMED session keeps the server-side device the previous run's txn ids
  were recorded against. The server deduplicates by (device, txn), so the
  first sends after a restart can be answered with an old event id instead
  of being stored. Long-standing (the Zig client has the same shape); the
  outbox's entries reuse their own persisted txn deliberately, which is
  correct for them and does not widen this. The fix is to persist the
  counter, or to seed it from something per-run.

## sgola-side items that Wata is waiting on (tracked THERE, not here)

- ~~Thicket backend crash~~ FIXED and verified downstream: pin `2083ef6`
  full-gate green with `Repl.printConvs` returned to the exact recursive
  shape that crashed (kept recursive as the standing proof). Recursion is
  safe again; the while/var walks elsewhere are style, not necessity.
- ~~Variadic facade gap~~ FIXED and verified downstream: pin `4cbea19`
  full-gate green with `exec.Command` collapsed to ONE varargs bind
  (`arg: String*`; spread via `Array[String]` + `xs*`, the one legal
  vehicle) — `command0..4` and the arity picker are gone from
  `wata-tui`. That closes every consumer ticket wata has filed to date.
- ~~Try-shape sensitivity~~ FIXED and verified downstream: pin `c81ed0f`
  full-gate green with `Repl.readFile` returned to the nested-assignment
  try shape (kept nested as the standing proof; tui-smoke pins the catch
  edge via an unreadable-file send). Upstream found the filing had teeth:
  two shapes that compiled elsewhere routed errors PAST the catch.
  Val-bound forms elsewhere are now taste, not necessity.
- FYI from upstream 2026-08-05: F104 (`29cf82e`) fixed
  `sgo.makeChan/makeSlice/makeMap` in ctor/function-argument position;
  we never dropped the bind-to-val convention, so nothing to unwind —
  the next repin simply makes the convention optional there.

- `HashMap.foldLeft` with `B` = a generic `List[T]` leaves `B`
  unspecialized in the linker and emits an `any` the call site cannot
  use (filed 2026-08-05, `WATA-FOLD-LIST-B`). Worked around in
  `wata-server` with one-field wrapper case classes
  (`model.scala` `IdList`/`MediaList`); drop them when it lands.
- An `if`-as-expression of PRIMITIVE type in argument position boxes:
  `atomic.set(if x > 0.0 then x else 0.0)` on an `Atomic[scala.Double]`
  reaches the backend as `java.lang.Double` and crashes `sgolaBackend`
  with "no Go type mapping" — naming the file, with no line or excerpt
  (filed 2026-08-05, `IF-EXPR-DOUBLE-BOXES`; plan 0022). Worked around
  by the house `var out = …; if … then out = …` idiom, which is why this
  had not been hit before; drop nothing when it lands, but the
  diagnostic half is the part that cost time.
- A cross-MODULE read of a module `val` emits `self.<Obj>.<VAL>`, which is
  not valid Go (`undefined: self`): `wata-fb` reading `Outbox.CAP` out of
  `wataclient`. Binding it to a local first makes no difference, so it is
  the read; a cross-module `def` call on the same object is fine, as are
  cross-unit val reads inside one module (filed 2026-08-05,
  `CROSS-MODULE-VAL-READ`; plan 0022). Worked around with
  `Outbox.cap()`; inline `Outbox.CAP` again when it lands.
- DATA-10 (`StringBuilder` as a parameter) prints the right restriction
  message and then crashes `sgolaBackend` with an unhandled exception
  (filed 2026-08-05, `WATA-DATA10-PLUGIN-CRASH`). Reporting path only —
  nothing to unwind here.

- ~~makeSlice-argpos~~ ~~DATA10 crash banner~~ ~~fold-B collapse~~
  FIXED and verified downstream: pin `ade6b1e` full-gate green with
  `mac.sum(go.makeSlice[Byte](0))` inline (standing proof) and the
  IdList/MediaList wrappers gone (folds go straight to `List[T]`).
  ~~WATA-FOLD-RETURN-POS~~ FIXED and verified downstream: pin
  `5663647` full-gate green with both store.scala folds inlined again
  (def-result and withLock-lambda-tail, kept as standing proofs).
  ~~IF-EXPR-DOUBLE-BOXES~~ FIXED and verified downstream: pin
  `4834bec`, tickQuitArm's direct if-expression restored (standing
  proof); walls of that class are POSITIONED now. **Every ticket
  wata has filed to date — compiler and driver — is landed and
  downstream-verified.** ~~WATA-SKIP-FRESH-CHECKOUT~~ FIXED at
  `94ce542`, verified by the original repro: a fresh worktree with the
  shared toolchain home rebuilds the liblink dep (RUN) and the
  cross-build succeeds; isolated-worktree deploy builds work directly
  now.
- FYI from upstream 2026-08-05 (`4cbea19..d30adec`): several
  template-body construction lifts landed, plus GEN-7 — a new registry
  row RESTRICTING four construction shapes inside generic bodies
  (facade-trait impls, @goexport classes, JDK exceptions as data,
  instance-inner open classes; spec 06-expressions has the fragment).
  Current wata code constructs all four at ground — unaffected today;
  relevant when writing new generic code.
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
