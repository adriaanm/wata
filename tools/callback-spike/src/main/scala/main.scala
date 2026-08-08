import language.experimental.saferExceptions

/** Plan 0038, the call-in leg (OBJC-SPIKE-CALLBACK-LEG): an ObjC method whose
 *  BODY is Sgola. The runtime dispatches `wataProbe` to an IMP whose address
 *  came from `go.callback`, and the value the Sgola function returns arrives
 *  back through `objc_msgSend`.
 *
 *  This spike is EXPECTED NOT TO COMPILE on the current pin. `go.callback`
 *  is RULED (sgola `29536af`, 2026-08-08) but its implementation is queued
 *  upstream and has not landed; the spike is committed spelled against the
 *  ruled contract so the landing day is a compile-and-run, not a design
 *  session. The one wall must be the `go.callback` site — everything else
 *  here is the call-out vocabulary objc-spike already runs green.
 *
 *  The contract this is spelled against:
 *    - `go.callback(f): go.Uintptr` — a REGISTRATION returning a FREE
 *      address value (a purego trampoline has process lifetime, so unlike
 *      `go.cstring` no bracket is needed);
 *    - registration at module/startup scope only (the platform cap makes
 *      per-frame minting fail loudly) — hence `cbAddr` is a module-scope val;
 *    - `f` restricted to the address-sized vocabulary: `go.Uintptr` params
 *      and result, monomorphic;
 *    - `f`'s captures face the crossing predicate a fork's do — `onCall`
 *      captures NOTHING, so the predicate is trivially satisfied.
 *
 *  Why the callback returns `self` and not the briefed constant 42:
 *  `go.Uintptr` is OPAQUE — no literals, no Int/Long conversions (that
 *  opacity is the type's own safety argument, gocore.scala) — so a callback
 *  restricted to the address-sized vocabulary cannot RETURN a numeric
 *  constant at all today. Identity is the distinctive value the vocabulary
 *  can express, and the oracle stays arithmetic and unforgiving: the IMP
 *  returns its `self` argument, so `objc_msgSend(inst, wataProbe)` must
 *  yield exactly the receiver's address. A mis-registered IMP gives a crash
 *  or garbage — never the one address we just allocated. (Whether a landed
 *  `go.callback` wants a constant-result story is a question FOR the
 *  landing; this file is where it will surface.)
 *
 *  Every C-string crossing sits in a `go.cstring` bracket returning the
 *  syscall's results (the lints: the bracket's result must not be
 *  `go.Uintptr`; `p` must not escape the bracket).
 */
object Main:

  val RTLD_LAZY: scala.Int = 0x1
  val RTLD_GLOBAL: scala.Int = 0x8

  /** The ObjC method body. Address-sized signature per the ruling; pure —
   *  no captures, nothing mutable. Returns its receiver (see header). */
  def onCall(self: go.Uintptr, cmd: go.Uintptr): go.Uintptr = self

  /** Module-scope registration, per the contract: the trampoline is minted
   *  once, at startup, never per-frame. THIS is the wall on the current
   *  pin — `go.callback` has not landed. */
  val cbAddr: go.Uintptr = go.callback(onCall)

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
      // class_addMethod(cls, sel, imp, types) — types "L@:" (unsigned-long
      // return, receiver, selector). BOOL result in r1: nonzero on success.
      val (added, _, _) = go.cstring("L@:") { p => go.purego.syscallN(addMethod, cls, selProbe, cbAddr, p) }
      val (_, _, _) = go.purego.syscallN(regPair, cls)

      // alloc/init an instance and send it the synthesized selector. The
      // dispatch crosses INTO Sgola here: objc_msgSend jumps to the
      // trampoline, the trampoline calls onCall.
      val (selAlloc, _, _) = go.cstring("alloc") { p => go.purego.syscallN(regName, p) }
      val (selInit, _, _) = go.cstring("init") { p => go.purego.syscallN(regName, p) }
      val (allocd, _, _) = go.purego.syscallN(msgSend, cls, selAlloc)
      val (inst, _, _) = go.purego.syscallN(msgSend, allocd, selInit)
      val (probe, _, _) = go.purego.syscallN(msgSend, inst, selProbe)

      println("callback-spike: added = " + added)
      println("callback-spike: inst  = " + inst)
      println("callback-spike: probe = " + probe)
      // go.Uintptr is opaque (no ==); its render surface is concat, which
      // objc-spike already exercises. Same address, same rendering.
      if ("" + probe) == ("" + inst) then println("callback-spike: PASS")
      else println("callback-spike: FAIL")
    catch case e: sgo.GoError => println("callback-spike: FAILED " + e.getMessage)
