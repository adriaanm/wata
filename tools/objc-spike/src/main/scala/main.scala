import language.experimental.saferExceptions

/** Plan 0038, the call-out leg: send an ObjC message from Sgola with no Go
 *  code of ours in between. This runs its oracle: `objc-spike: length = 5`.
 *
 *  The oracle is arithmetic and unforgiving. `[[NSString
 *  stringWithUTF8String:"hello"] length]` is 5; a mis-marshalled argument
 *  gives a crash or a garbage number, never a near miss. The message send
 *  itself goes through `objc_msgSend`, reached by `dlsym` out of
 *  libobjc — which is exactly how purego's own objc package does it,
 *  minus the Go.
 *
 *  C-string addresses use the bracket, `go.cstring(s) { p => body }`:
 *  `p` addresses a NUL-terminated COPY of `s`, valid for exactly the
 *  bracket's extent (the emitted Go KeepAlives the copy until `body`
 *  returns). That shape exists because a free uintptr keeps nothing
 *  alive across statements — so the foreign call happens INSIDE the
 *  bracket and the bracket returns the call's result. Semantics: sgola
 *  spec ch.15 (`docs/spec/15-the-go-boundary_sgola.md`).
 */
object Main:

  val RTLD_LAZY: scala.Int = 0x1
  val RTLD_GLOBAL: scala.Int = 0x8

  def main(args: Array[String]): Unit =
    try
      val libobjc = go.purego.dlopen("/usr/lib/libobjc.A.dylib", RTLD_GLOBAL | RTLD_LAZY)
      val msgSend = go.purego.dlsym(libobjc, "objc_msgSend")
      val getClass = go.purego.dlsym(libobjc, "objc_getClass")
      val regName = go.purego.dlsym(libobjc, "sel_registerName")

      // NSString.stringWithUTF8String_("hello").length
      //
      // `objc_msgSend` and friends return in the first register only; the
      // other two results of SyscallN are the ABI's second return register
      // and errno, and neither means anything here. Discarding them is the
      // tuple pattern, which emits Go's own `r1, _, _ := …`.
      val (cls, _, _) = go.cstring("NSString") { p => go.purego.syscallN(getClass, p) }
      val (selStr, _, _) = go.cstring("stringWithUTF8String:") { p => go.purego.syscallN(regName, p) }
      val (selLen, _, _) = go.cstring("length") { p => go.purego.syscallN(regName, p) }
      val (str, _, _) = go.cstring("hello") { p => go.purego.syscallN(msgSend, cls, selStr, p) }
      val (len, _, _) = go.purego.syscallN(msgSend, str, selLen)

      println("objc-spike: length = " + len)
    catch case e: sgo.GoError => println("objc-spike: FAILED " + e.getMessage)
