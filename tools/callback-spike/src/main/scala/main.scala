import language.experimental.saferExceptions

/** Plan 0038, the call-in leg — ANSWERED. An ObjC method whose BODY is
 *  Sgola: the runtime dispatches `wataProbe` to an IMP whose address came
 *  from `go.callback`, and the value the Sgola function returns arrives
 *  back through `objc_msgSend`. Compiles and runs green on the pinned
 *  toolchain (sgola `cb15191`, where `go.callback` landed); ci asserts the
 *  oracle via `just callback-spike`.
 *
 *  The landed v1 contract this exercises:
 *    - `go.callback(literal): go.Uintptr` — a REGISTRATION returning a FREE
 *      address value (a purego trampoline has process lifetime, so unlike
 *      `go.cstring` no bracket is needed);
 *    - the argument must be a function LITERAL with ASCRIBED param types in
 *      v1 — they read as the declared foreign signature. A named def (the
 *      pre-landing spelling here) does not compile; if that chafes in the
 *      real ports (dispatch/keyview reuse one body across selectors), it is
 *      a fileable-against edge of the v1 ruling, not a bug;
 *    - params `go.Uintptr | Int`, result `go.Uintptr | Int | Unit`,
 *      arity <= 15 — the trampoline marshals ordinary values, so a constant
 *      result is simply an `Int` (sgola `a48248e`);
 *    - registration at module/startup scope only (the ~2000 trampoline cap
 *      fails loudly if minted per-frame) — hence `cbAddr` is a module val;
 *    - the lambda's captures face the CONC-8 fork predicate at the
 *      registration site — this one captures NOTHING, trivially satisfied.
 *
 *  The oracle is a constant and unforgiving: the IMP returns `Int` 42, so
 *  `objc_msgSend(inst, wataProbe)` must yield exactly 42. A mis-registered
 *  IMP — wrong trampoline ABI, wrong argument order, a callback table off
 *  by one — gives a crash or garbage, never the one distinctive value.
 *
 *  Every C-string crossing sits in a `go.cstring` bracket returning the
 *  syscall's results (the lints: the bracket's result must not be
 *  `go.Uintptr`; `p` must not escape the bracket).
 */
object Main:

  val RTLD_LAZY: scala.Int = 0x1
  val RTLD_GLOBAL: scala.Int = 0x8

  /** Module-scope registration, per the contract: the trampoline is minted
   *  once, at startup, never per-frame. v1 requires a function literal with
   *  ascribed params (see header); the body is the whole ObjC method. */
  val cbAddr: go.Uintptr = go.callback((self: go.Uintptr, cmd: go.Uintptr) => 42)

  def main(args: Array[String]): Unit =
    try
      val libobjc = go.purego.dlopen("/usr/lib/libobjc.A.dylib", RTLD_GLOBAL | RTLD_LAZY)
      val msgSend = go.purego.dlsym(libobjc, "objc_msgSend")
      val getClass = go.purego.dlsym(libobjc, "objc_getClass")
      val regName = go.purego.dlsym(libobjc, "sel_registerName")
      val allocPair = go.purego.dlsym(libobjc, "objc_allocateClassPair")
      val addMethod = go.purego.dlsym(libobjc, "class_addMethod")
      val regPair = go.purego.dlsym(libobjc, "objc_registerClassPair")

      // Synthesize: WataProbe : NSObject, one method `wataProbe` whose IMP
      // is the Sgola callback above. NSObject lives in libobjc itself, so
      // no other library is needed.
      //
      // objc_allocateClassPair's third arg (extraBytes) is 0 by OMISSION:
      // SyscallN zero-fills the registers it is not given, which is the
      // only spelling of zero an opaque go.Uintptr admits.
      val (nsObject, _, _) = go.cstring("NSObject") { p => go.purego.syscallN(getClass, p) }
      val (cls, _, _) = go.cstring("WataProbe") { p => go.purego.syscallN(allocPair, nsObject, p) }
      val (selProbe, _, _) = go.cstring("wataProbe") { p => go.purego.syscallN(regName, p) }
      // class_addMethod(cls, sel, imp, types) — types "q@:" (long-long
      // return, receiver, selector): the callback answers an integer, and
      // the frameworks read this string, so it says so. BOOL result in r1:
      // nonzero on success.
      val (added, _, _) = go.cstring("q@:") { p => go.purego.syscallN(addMethod, cls, selProbe, cbAddr, p) }
      val (_, _, _) = go.purego.syscallN(regPair, cls)

      // alloc/init an instance and send it the synthesized selector. The
      // dispatch crosses INTO Sgola here: objc_msgSend jumps to the
      // trampoline, the trampoline calls the registered literal.
      val (selAlloc, _, _) = go.cstring("alloc") { p => go.purego.syscallN(regName, p) }
      val (selInit, _, _) = go.cstring("init") { p => go.purego.syscallN(regName, p) }
      val (allocd, _, _) = go.purego.syscallN(msgSend, cls, selAlloc)
      val (inst, _, _) = go.purego.syscallN(msgSend, allocd, selInit)
      val (probe, _, _) = go.purego.syscallN(msgSend, inst, selProbe)

      println("callback-spike: added = " + added)
      println("callback-spike: probe = " + probe)
      // go.Uintptr is opaque (no ==); its render surface is concat, which
      // objc-spike already exercises. The constant makes the comparison
      // textual and exact. No os.Exit facade in the dialect, so like
      // objc-spike the process exit is 0 either way — the ci recipe's grep
      // for the exact PASS line is the assertion.
      if ("" + probe) == "42" then println("callback-spike: PASS")
      else println("callback-spike: FAIL")
    catch case e: sgo.GoError => println("callback-spike: FAILED " + e.getMessage)
