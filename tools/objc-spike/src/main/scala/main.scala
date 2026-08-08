import language.experimental.saferExceptions

/** Plan 0038, the call-out leg: send an ObjC message from Sgola with no Go
 *  code of ours in between.
 *
 *  The oracle is arithmetic and unforgiving. `[[NSString
 *  stringWithUTF8String:"hello"] length]` is 5; a mis-marshalled argument
 *  gives a crash or a garbage number, never a near miss. The message send
 *  itself goes through `objc_msgSend`, reached by `dlsym` out of
 *  libobjc — which is exactly how purego's own objc package does it,
 *  minus the Go.
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
      val (cls, _, _) = go.purego.syscallN(getClass, cstr("NSString"))
      val (selStr, _, _) = go.purego.syscallN(regName, cstr("stringWithUTF8String:"))
      val (selLen, _, _) = go.purego.syscallN(regName, cstr("length"))
      val (str, _, _) = go.purego.syscallN(msgSend, cls, selStr, cstr("hello"))
      val (len, _, _) = go.purego.syscallN(msgSend, str, selLen)

      println("objc-spike: length = " + len)
    catch case e: sgo.GoError => println("objc-spike: FAILED " + e.getMessage)

  /** a NUL-terminated C string's address — the other thing an FFI layer
   *  cannot do without. */
  def cstr(s: String): go.Uintptr = ???
