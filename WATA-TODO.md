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

- **Framebuffer unblank fix needs a device pass** (FB-FIRST-FRAME-WHITE,
  2026-08-06): `Ui.loopWithDevice`/`Ui.wake` now write `0` to
  `/sys/class/graphics/fb0/blank` (`Led.unblankFb`) so the first frame
  and every screensaver wake land on an unblanked panel. Rides the next
  `fb-deploy`; verify no white screen at boot and after idle wake. The
  cmdline half (`consoleblank=0`) is handed to bq268-alpine
  (`docs/planning/consoleblank-cmdline.md` there). 2026-08-07: installed
  and cold-booted twice (`just fb-deploy install`) — the boot half is
  good, the panel painted the boot screen from the first frame with no
  white. The idle-wake half still needs a physical key press; note that
  `just fb-shot` shows the last PAINTED frame, so a shot taken while the
  screensaver is off is not evidence either way.
- **The startup chirp's COLD-BOOT leg is unverified** (plan 0039,
  2026-08-08): installed and heard on the handset — `just chirp-check`
  reads the bleep at 8x its neighbouring band and 5-8x the baseline after
  an app restart, with the negative control at 0.3x — but the run that
  reboots (`just chirp-check --cold-boot`) has not produced a verdict:
  the device came up in aboot's fastboot instead of Linux and needed a
  manual `fastboot continue`, so those 90 seconds recorded a device that
  was not booting. Rerun it with someone near the handset. That leg is
  the interesting one: the codec resets `RX2 MIX1 INP1` as the Q6 comes
  up, so a chirp played right after `SetupMixer` can be silent while
  everything reports success (AUDIO-ROUTE-REAPPLY).
- **Wifi truthfulness needs its device passes** (plan 0031, 2026-08-06):
  the settle/verdict/off changes are integ-proven on the fake seam only.
  On hardware, after the next `fb-deploy`: a `wifi`+`join` with a real
  mistyped PSK must answer the auth-failed verdict and leave the handset
  on its previous network (the conf rollback), and `wifi off` must
  report over cellular and auto-restore.
- **Aged-refusal endpoint rebuild not yet on the device** (plan 0030,
  2026-08-06): the fix lives in the irohnet Rust staticlib; only the
  host lib (`clib/darwin`) was rebuilt. The armv7 lib rides the next
  `fb-deploy` (`mklib.py arm`) — until then a handset that wedges its
  endpoint after a network move still needs the app restart. Field
  re-verification of the healed arc happens then.
- **Device-command mailbox: the node-id credential's positive leg is
  ungated.** cmd-smoke pins the negatives (forged header + no token →
  401; unbound node → 403) and the bearer-token path end to end, but no
  gate exercises a real iroh peer polling the mailbox by its
  handshake-proven `X-Wata-Node-Id` alone — that needs a live iroh
  listener, so it belongs in tunnel-smoke when the mailbox first
  matters over iroh (the device poller today runs inside a logged-in
  session, so the token path is the one in production use).
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

- **`EQUALS-LIST-EMIT-BROKEN-CONS`** (filed 2026-08-08) — `==` between
  two values of a case-class family carrying List fields (wataui's
  `View`) emits an `equalsList` helper whose type switch names the cons
  class unmangled (`case *:::` / `b.(*::)`), which is not Go; the go
  build stage fails with a bare syntax error naming neither the `==`
  site nor the cause. No shipping code hits it — wataui compares views
  via the hand-written `Views.eqView`, which exists for a semantic
  reason (a `Bytes`'s identity is not its contents), not as a
  workaround. **Workaround taken** in `tools/diff-spike/main.scala`
  (read a field via match instead of `==`); the comment names the key.

- **`TUPLE-REF-COMPONENT-ASSIGN`** — a tuple component whose type lowers
  to a Go interface (a sealed trait, a `List[T]`) is erased to `any` in
  the emitted tuple struct, and the type assertion that puts it back is
  inserted at ARGUMENT reads but not at a plain assignment, so the
  module does not compile (`cannot use r._2 (variable of interface type
  any) as List__Arrival value in assignment`). Value-shaped components
  are unaffected — `(String, Int)` and `(String, NotifyState)` are fine
  in the same build. Hit by `Notify.step`, which wanted to answer
  `(NotifyState, List[Arrival])`. **Workaround taken**: a named case
  class, `NotifyStep(marks, arrivals)` — clearer anyway, so it stays
  once the gap closes; the note lives in
  `wataclient/src/main/scala/notify.scala`.

- **`FACADE-VALUE-STRUCT`** — a facade class type is always a Go
  **pointer**, and cannot be constructed. Repro in `tools/interp-spike`
  (`just interp-spike`), diagnostics in its REPORT.md. It blocks
  `WIRE-DIES-INTERP-TO-SGOLA`: AppKit's geometry is C structs by value
  (`CGRect`, and the ObjC handles themselves are `struct{ objc.ID }`),
  so every crossing mismatches — `cannot use … (value of struct type
  appkit.CGRect) as *appkit.CGRect value`. Field access, `@go.name` and
  method binding are all already right; only the `*` and building a
  composite literal are missing. Constructing one today either emits the
  Sgola-side name (`undefined: appkit_CGRect`, from a `case class`) or
  crashes the plugin (`unsupported expression (Apply)` on `new`). No
  workaround taken — the alternative is a pointer-shaped Go shim in front
  of the generated bindings, which is the layer the port exists to delete.

- **`FACADE-DISCARD-EXTRA-RESULTS`** — the one gap plan 0038's call-out
  spike still stands on, with a live repro in `tools/objc-spike`
  (`just objc-spike`) and the diagnostics in its REPORT.md. It is the
  whole remaining distance from here to "an FFI layer written in Sgola":
  everything ELSE the spike needs already works — `(uintptr, error)`
  rides the `throws` lowering unchanged, and the variadic facade binding
  emits a bare Go variadic call.
  - **`FACADE-UINTPTR-TYPE` is FIXED** (upstream `b85a713`, pinned here
    at `e35b162`) and verified in the spike: all seven `[E008]` sites are
    gone, the compile stage passes, and `go.Uintptr` binds as parameter,
    result and variadic element. (The ticket cited `go.Int`'s opaque
    IOP-2 posture as precedent; that citation was WRONG — IOP-2 was
    revised 2026-07-12 and opaque `go.Int` is retired, `int` maps to
    `Int`. Do not reason from it again. The ruling rests opacity on the
    semantics instead: a uintptr is not a reference and does not keep its
    referent alive.)
  - Discarding extra results: `SyscallN(…) (r1, r2, err uintptr)` was
    unbindable because `throws`→`(T, error)` was the only multi-result
    shape. We asked for a DISCARD rule and argued against N-tuples on the
    grounds that they would force a representation decision. **That
    premise was stale and the ruling went the other way**: tuples already
    have a Go struct representation, so a method's Go results are simply
    `flatten(R) ++ (error if throws)` — one level, both directions,
    unambiguous because Go has no tuple type. `@go.discardResults` is
    DEAD. Discarding is Scala's own tuple-pattern binding, character for
    character Go's: `val (r1, _, _) = syscallN(…)`. "Want `r2` not `r1`"
    stops being a wall, and the composition worry about `throws` putting
    a discard in the middle dissolves — the tuple accounts for every
    result before the trailing `error`.
  - The spike is **spelled that way in the tree** as of 2026-08-08, so it
    is a standing pre-fix repro (filed back as
    `REPRO-FACADE-TUPLE-RESULTS-PREFIX`). Against the pin it shows both
    legs at once: `assignment mismatch: 1 variable but purego.SyscallN
    returns 3 values` (leg 1, the call still emits single-valued) and
    `undefined: Tuple3__R__R__R` — a facade result type does not reach
    whatever mints the `TupleN__…` struct, so even the materialized leg
    has nothing to build. When it lands, `just objc-spike` compiling IS
    leg 1's answer; repin, then send the verification ticket.
  - Filed 2026-08-07, with a third file alongside them:
    `VERIFY-VARIADIC-FACADE-BIND`, the other direction of the loop —
    `VARIADIC-FACADE-BIND` was minted from this inbox on 2026-08-05 and
    the spike is its first serious consumer outside its own scenario. It
    emitted a bare Go variadic call at three arities, first try; the wall
    that ticket was minted for never came up. Banked, no ticket — with a
    gap named back at us: the **Array-spread leg was not exercised**
    (every call site passes individual args), so `expr*` still rests on
    its own scenario alone.
  - Both gaps are Sonnet-tier and small, and they serialize behind each
    other (one agent in the sgola tree at a time). No date promised; the
    repin notice arrives in our `inbox/`, which now has a Monitor on it.


- ~~`WATA-EITHER-LIST-PAYLOAD`~~ FIXED (upstream `860e6d4`) and verified
  downstream: pin `860e6d4` full-gate green with `Group.resolveAll`
  returned to `Either[String, List[String]]` (the flat `ResolvedMembers`
  workaround deleted — the Either shape stands as the proof). The same
  shape inside a monomorphized generic method body has a separate upstream
  residue (`TEMPLATE-EITHER-PAYLOAD-ANY`, queued there); ground code is
  unaffected.

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

- The Gio window backend (plan 0023 M2) depends on the emitted `main` and
  the body of `sgo.supervised` running on the Go MAIN goroutine — Gio's
  `app.Main()` panics anywhere else and never returns. ANSWERED upstream
  same day (`6f16a53`): the guarantee is now a stated consumer-facing
  promise (sgola CONCURRENCY.md §4.1 + the go-boundary spec, citing our
  shape), with a runtime pin scenario in their ci; any future change is a
  designer ruling with a consumer heads-up. Nothing to unwind — this was
  a pin request, not a defect.
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
- A `go.Slice[T]` (`go.Bytes`) case-class FIELD crashes `sgolaBackend` with
  "no Go type mapping for `go.Slice`" plus an unhandled exception — a
  restriction reported as a crash, and with a message about function types
  that names neither the field nor its type (filed 2026-08-05,
  `GOSLICE-CASECLASS-FIELD-CRASH`; plan 0024). The same type is fine as a def
  parameter and as a def result. Worked around by converting at the facade
  edge: `Enrol.snap` turns `go.qr.matrix`'s `go.Bytes` into the portable core
  `Bytes` the enrolment snapshot carries. If the restriction is deliberate the
  workaround stays (it is better code — the body references no `go.*`); the
  crash is the part to fix either way.
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
  proof); walls of that class are POSITIONED now. ~~CROSS-MODULE-VAL-READ~~ FIXED and verified downstream: pin
  `f0bce9e` full-gate green with `Outbox.cap()` deleted and the
  direct cross-module `Outbox.CAP` reads restored in integ.scala
  (standing proof). ~~ATOMIC-STR-EQ~~ FIXED and verified downstream:
  pin `effbfcb` full-gate green with both typed-local binds inlined
  again (`Enrol.nonce`'s `.get() == ""` and jsonnav's tuple-accessor
  compare, kept as standing proofs). ~~GOMOD-TRANSITIVE-SUM~~ FIXED
  and verified downstream: pin `b38f999` full-gate green (incl. the
  armv7 cross-cgo leg) with the `GOFLAGS=-mod=mod` export deleted from
  sgo-env.sh and toolchain.py — the rsc.io/qr build now rides the
  propagated go.sum alone (standing proof).
  ~~INLINK-DEP-SEARCH-PARENT-ONLY~~ FIXED and verified downstream: pin
  `0d45d7f`, the committed symlink deleted and `watabind/sgo.deps`
  naming `wataclient ../../../wataclient` explicitly (standing proof;
  spike emit + full gate green). ~~CLASS-METHOD-LAMBDA-LIFT-MISMATCH~~
  FIXED and verified downstream: pin `3057fb8`, both repro shapes
  inlined again as standing proofs (the spawner-class shapes have since
  been deleted outright — see SGO-DETACHED-SPAWN — and `Handle.stopped`
  still carries the `selectValue` lambda); full ci + full phone-spike
  green. ~~SGO-DETACHED-SPAWN~~ FIXED and verified downstream: pin
  `e449105`, the `Spawner` trait and both app impls DELETED —
  `ClientHandle.startClient` owns its goroutine via `sgo.spawn` (standing
  proof), `start` lost its capability parameter; full ci + full
  phone-spike green through the gomobile-bound emission. ~~NO-LIB-EMIT-FOR-RUNTIME-LIBS~~ FIXED
  and verified downstream: pin `a5e3d27`, `emitpackage watacore` in the
  spike's sgo.build, aslib.py DELETED, gomobile binding the emitted
  `.sgo/watacore-pkg` dir directly — full spike (emit/bind/shell/smoke)
  plus the iOS-simulator leg plus full ci all green. The plan-0023
  friction ledger is EMPTY: every ticket the spike filed was fixed
  upstream and verified downstream same-day. ~~WATA-SKIP-FRESH-CHECKOUT~~ FIXED at
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
