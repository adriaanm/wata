import language.experimental.saferExceptions

/** The watch's `HttpDo` over **NSURLSession** — the socket wall's answer
 *  (`WATCH-URLSESSION-HTTP`, plan 0075, step 1 of plan 0074). watchOS denies
 *  BSD sockets to third-party apps (every dial EHOSTUNREACH, proven on the
 *  Series 10, 2026-08-23), so Go's net stack can never complete a request
 *  from the wrist; third-party networking is NSURLSession or nothing. This
 *  is the same capability seam, transport swapped underneath: nothing above
 *  `caps.httpDo()` changes, and a status-0 response still means "transport
 *  failed" to the portable core.
 *
 *  EVERYTHING here is Sgola — no Go of ours sits under it. Both FFI legs it
 *  needs are closed on the current pin: calls out through `go.purego` +
 *  `go.cstring` brackets (objc-spike), and the delegate methods IN as ObjC
 *  methods whose IMPs are `go.callback` trampolines (callback-spike). One
 *  serial session and one delegate exist for the process, built once at
 *  module init; each `send` resumes a task on them.
 *
 *  Three dialect constraints shaped the details, recorded because they are
 *  the interesting part:
 *
 *  - **Sgola cannot dereference, in either direction.** Raw bytes cross by
 *    file instead: an outgoing body goes to a sandbox temp file with
 *    `syscall.write` (FbConfig's proven pattern) and enters ObjC as
 *    `[NSData dataWithContentsOfFile:]`; the accumulated reply leaves via
 *    `[NSMutableData writeToFile:atomically:]` and comes back through
 *    `go.sys.readFile`. A failing request's `localizedDescription` rides
 *    the same route so the cause line names something real (`http: …
 *    failed: <cause>` at this seam — status 0 is all the core ever sees).
 *    Temp files are unlinked after every request.
 *  - **No integers across msgSend** (UINTPTR-INT-ARGS). `go.Uintptr` has no literal and no
 *    conversion, so a non-zero integer argument is unspellable — but zero
 *    is spelled by OMISSION (`SyscallN` zero-fills untouched registers),
 *    which covers every integer argument this code needs:
 *    `delegateQueue:nil`, `atomically:NO`. NSURLSession's own timeout
 *    (`setTimeoutInterval:`, a double) is therefore unreachable; the
 *    deadline is enforced HERE, `sgo.select2` between the done channel and
 *    `go.time.After`. Scalar RESULTS arrive as opaque words too, so booleans
 *    and the status code round-trip through Uintptr's concat render
 *    ("0"/"1", decimal) — contained in `asBool`/`asInt`.
 *  - **Autorelease discipline.** Delegate callbacks arrive on the session's
 *    own thread, where no pool exists; both callback bodies and the
 *    send-side construction run inside `go.iosui.poolPush/Pop` brackets.
 *
 *  Threading: the two IMPs share state only through synchronizers (`errC`,
 *  `doneCh`) because they run on a foreign thread — exactly the CONC-8
 *  predicate the registration site checks. `send` itself stays synchronous,
 *  like every other HttpDo; the client core drives it from one goroutine,
 *  so the per-request temp files are not contended. */

/** the caps-seam impl; `timeoutMs` is the per-request deadline (30s default,
 *  `WATA_HTTP_TIMEOUT_MS` override — clears the server's ~25s long-poll). */
class WristHttp(timeoutMs: Long) extends HttpDo:
  def send(req: HttpRequest): HttpResponse = NsHttp.send(req, timeoutMs)

object NsHttp:

  // ---- the ObjC runtime, resolved once at module init ----------------------
  // Declaration order IS initialization order here: symbols before classes,
  // classes before the delegate, everything before the session. libobjc is
  // present on every watchOS; failure this early is not survivable or even
  // nameable above boot, so say what died and stop (os.Exit never returns;
  // the trailing ??? only satisfies the typer).

  private val RTLD_LAZY: scala.Int = 0x1
  private val RTLD_GLOBAL: scala.Int = 0x8

  private def die(what: String): Unit =
    println("nshttp: objc runtime unavailable: " + what)
    go.os.exit(go.Int.of(4))

  private val libobjc: go.Uintptr =
    try go.purego.dlopen("/usr/lib/libobjc.A.dylib", RTLD_GLOBAL | RTLD_LAZY)
    catch case e: sgo.GoError => die(e.message); ???

  private def sym(name: String): go.Uintptr =
    try go.purego.dlsym(libobjc, name)
    catch case e: sgo.GoError => die(name + ": " + e.message); ???

  private val pMsgSend = sym("objc_msgSend")
  private val pGetClass = sym("objc_getClass")
  private val pRegName = sym("sel_registerName")
  private val pAllocPair = sym("objc_allocateClassPair")
  private val pAddMethod = sym("class_addMethod")
  private val pRegPair = sym("objc_registerClassPair")

  /** msgSend at fixed arities — `_1` discards r2/errno, both meaningless for
   *  objc_msgSend (the objc-spike discard). */
  private def msg1(t: go.Uintptr, sel: go.Uintptr): go.Uintptr =
    go.purego.syscallN(pMsgSend, t, sel)._1
  private def msg2(t: go.Uintptr, sel: go.Uintptr, a: go.Uintptr): go.Uintptr =
    go.purego.syscallN(pMsgSend, t, sel, a)._1
  private def msg3(t: go.Uintptr, sel: go.Uintptr, a: go.Uintptr, b: go.Uintptr): go.Uintptr =
    go.purego.syscallN(pMsgSend, t, sel, a, b)._1

  /** scalar results ride Uintptr's concat render — the honest round-trip.
   *  UINTPTR-INT-ARGS: delete both helpers when a Uintptr/Int conversion lands. */
  private def asBool(w: go.Uintptr): Boolean = ("" + w) == "1"
  private def asInt(w: go.Uintptr): scala.Int =
    val s = "" + w
    var out = 0
    var i = 0
    var ok = s.length > 0
    while i < s.length do
      val ch = s.substring(i, i + 1)
      if ch == "0" then out = out * 10
      else if ch == "1" then out = out * 10 + 1
      else if ch == "2" then out = out * 10 + 2
      else if ch == "3" then out = out * 10 + 3
      else if ch == "4" then out = out * 10 + 4
      else if ch == "5" then out = out * 10 + 5
      else if ch == "6" then out = out * 10 + 6
      else if ch == "7" then out = out * 10 + 7
      else if ch == "8" then out = out * 10 + 8
      else if ch == "9" then out = out * 10 + 9
      else ok = false
      i = i + 1
    if ok then out else 0

  private def cls(name: String): go.Uintptr =
    val (c, _, _) = go.cstring(name) { p => go.purego.syscallN(pGetClass, p) }
    c

  private def mkSel(name: String): go.Uintptr =
    val (s, _, _) = go.cstring(name) { p => go.purego.syscallN(pRegName, p) }
    s

  // class objects, resolved once
  private val clsNSString = cls("NSString")
  private val clsNSData = cls("NSData")
  private val clsMutData = cls("NSMutableData")
  private val clsNSURL = cls("NSURL")
  private val clsMutReq = cls("NSMutableURLRequest")
  private val clsSession = cls("NSURLSession")
  private val clsSessionCfg = cls("NSURLSessionConfiguration")

  private val selAlloc = mkSel("alloc")
  private val selInit = mkSel("init")
  private val selStringWithUTF8 = mkSel("stringWithUTF8String:")
  private val selData = mkSel("data")
  private val selDataWithContentsOfFile = mkSel("dataWithContentsOfFile:")
  private val selAppendData = mkSel("appendData:")
  private val selSetData = mkSel("setData:")
  private val selWriteToFile = mkSel("writeToFile:atomically:") // atomically omitted = NO
  private val selURLWithString = mkSel("URLWithString:")
  private val selRequestWithURL = mkSel("requestWithURL:")
  private val selSetHTTPMethod = mkSel("setHTTPMethod:")
  private val selSetValueForHeader = mkSel("setValue:forHTTPHeaderField:")
  private val selSetHTTPBody = mkSel("setHTTPBody:")
  private val selDataTask = mkSel("dataTaskWithRequest:")
  private val selResume = mkSel("resume")
  private val selCancel = mkSel("cancel")
  private val selResponse = mkSel("response")
  private val selRespondsTo = mkSel("respondsToSelector:")
  private val selStatusCode = mkSel("statusCode")
  private val selLocalizedDesc = mkSel("localizedDescription")
  private val selDefaultCfg = mkSel("defaultSessionConfiguration")
  private val selSessionWith = mkSel("sessionWithConfiguration:delegate:delegateQueue:")

  /** an NSString over a Scala string — the spike's stringWithUTF8String leg.
   *  The handle survives past the bracket (the STRING COPY does not, which
   *  is why every crossing here copies: UTF8String pointers stay inside one
   *  bracket's extent, and none of these selectors retain the C pointer). */
  private def nsstr(s: String): go.Uintptr =
    val (h, _, _) = go.cstring(s) { p => go.purego.syscallN(pMsgSend, clsNSString, selStringWithUTF8, p) }
    h

  // ---- the shared state the delegate IMPs reach ----------------------------

  /** the reply accumulator, owned (+alloc) and process-long: didReceiveData
   *  appends into it, send reads and resets it between tasks. */
  private val acc: go.Uintptr =
    msg1(msg1(clsMutData, selAlloc), selInit)

  /** the completion token IS the error handle (nil renders "0"): one
   *  buffered(1) channel, so the delegate thread can NEVER block — a
   *  timed-out send just leaves the token for the next request's drain. */
  private val doneCh: sgo.Chan[go.Uintptr] = sgo.makeChan[go.Uintptr](1)

  /** which arm fired: select2's arms cannot mutate an outer var (SELECT2-ARM-VAR-MUT:
   *  the emitter has no BooleanRef), so the delegate sets this BEFORE the token lands
   *  and send() reads it after the select. */
  private val doneC: sgo.Atomic[Boolean] = sgo.atomic(false)

  private def drainDone(): Unit =
    var going = true
    while going do
      doneCh.tryReceive() match
        case Some(_) => ()
        case None    => going = false

  // ---- the two delegate methods, bodies in Sgola ---------------------------
  // Registered ONCE at module init (the trampoline-cap rule); the literals
  // capture nothing — they reach state through this object's cells, the mac
  // interp trampoline's discipline.

  private val impData: go.Uintptr = go.callback(
    (self: go.Uintptr, cmd: go.Uintptr, sess: go.Uintptr, task: go.Uintptr, data: go.Uintptr) =>
      onData(data))

  private val impComplete: go.Uintptr = go.callback(
    (self: go.Uintptr, cmd: go.Uintptr, sess: go.Uintptr, task: go.Uintptr, err: go.Uintptr) =>
      onComplete(err))

  private def onData(data: go.Uintptr): scala.Int =
    val pool = go.iosui.poolPush()
    msg2(acc, selAppendData, data)
    go.iosui.poolPop(pool)
    0

  private def onComplete(err: go.Uintptr): scala.Int =
    val pool = go.iosui.poolPush()
    doneC.set(true)
    doneCh.send(err)
    go.iosui.poolPop(pool)
    0

  // ---- the delegate object and the session ---------------------------------

  private val deleg: go.Uintptr = mkDeleg()

  private def addM(c: go.Uintptr, name: String, imp: go.Uintptr, types: String): Unit =
    val s = mkSel(name)
    val (_, _, _) = go.cstring(types) { t => go.purego.syscallN(pAddMethod, c, s, imp, t) }
    ()

  private def mkDeleg(): go.Uintptr =
    val (base, _, _) = go.cstring("NSObject") { p => go.purego.syscallN(pGetClass, p) }
    val (c, _, _) = go.cstring("WataHttpDelegate") { p =>
      // extraBytes omitted: SyscallN zero-fills, the only spelling of zero
      go.purego.syscallN(pAllocPair, base, p)
    }
    // v@: = void return, id self, SEL _cmd; one @ per object argument after
    addM(c, "URLSession:dataTask:didReceiveData:", impData, "v@:@@@")
    addM(c, "URLSession:task:didCompleteWithError:", impComplete, "v@:@@")
    val (_, _, _) = go.purego.syscallN(pRegPair, c)
    msg1(msg1(c, selAlloc), selInit)

  private val session: go.Uintptr = mkSession()

  private def mkSession(): go.Uintptr =
    val pool = go.iosui.poolPush()
    val cfg = msg1(clsSessionCfg, selDefaultCfg) // factory answer, autoreleased
    // the queue argument omitted = nil: the session makes its own serial one
    val s = msg3(clsSession, selSessionWith, cfg, deleg)
    go.iosui.poolPop(pool)
    s

  // ---- the request itself --------------------------------------------------

  /** the sandbox temp files (beside the config store, which exists by the
   *  time any request runs — and is mkdir'd here regardless). */
  private def tmpDir(): String =
    val d = FbConfig.stateDir()
    if d == "" then "/tmp" else d

  private def writeTmp(path: String, text: String): Unit =
    try
      val fd = go.syscall.open(path,
        go.syscall.O_WRONLY | go.syscall.O_CREAT | go.syscall.O_TRUNC, 384)
      go.syscall.write(fd, go.bytes(text))
      go.syscall.close(fd)
    catch case e: sgo.GoError => ()

  private def readTmp(path: String): String =
    var out = ""
    try out = go.string(go.sys.readFile(path))
    catch case e: sgo.GoError => ()
    out

  private def rmTmp(path: String): Unit = go.syscall.unlink(path)

  /** the deadline contract caps.timeoutMs() documents, enforced by select. */
  def send(req: HttpRequest, timeoutMs: Long): HttpResponse =
    FbConfig.mkdirAll(tmpDir())
    val pool = go.iosui.poolPush()
    val out = doSend(req, timeoutMs)
    go.iosui.poolPop(pool)
    out

  private def doSend(req: HttpRequest, timeoutMs: Long): HttpResponse =
    val reqTmp = tmpDir() + "/wata-http-req.tmp"
    val resTmp = tmpDir() + "/wata-http-res.tmp"
    val errTmp = tmpDir() + "/wata-http-err.tmp"

    // reset the shared state BEFORE anything can resume
    doneC.set(false)
    drainDone()
    val empty = msg1(clsNSData, selData) // factory answer, autoreleased
    msg2(acc, selSetData, empty)

    if req.url == "" then return HttpResponse(0, "")
    val url = nsstr(req.url)
    val uo = msg2(clsNSURL, selURLWithString, url)
    if ("" + uo) == "0" then
      println("http: " + req.method + " " + req.url + " failed: bad URL")
      return HttpResponse(0, "")

    val r = msg2(clsMutReq, selRequestWithURL, uo)
    msg2(r, selSetHTTPMethod, nsstr(req.method))

    var cur = req.headers
    var going = true
    while going do
      cur match
        case hd :: tl =>
          msg3(r, selSetValueForHeader, nsstr(hd._2), nsstr(hd._1))
          cur = tl
        case Nil => going = false

    if req.body != "" then
      writeTmp(reqTmp, req.body)
      val fb = nsstr(reqTmp)
      val body = msg2(clsNSData, selDataWithContentsOfFile, fb)
      msg2(r, selSetHTTPBody, body)
      rmTmp(reqTmp) // NSData copied the contents

    val task = msg2(session, selDataTask, r)
    msg1(task, selResume)

    sgo.select2(doneCh, go.time.After(go.time.milliseconds(timeoutMs.toInt)))(
      (_err: go.Uintptr) => (), (_t: go.time.Time) => ())

    if !doneC.get() then
      msg1(task, selCancel)
      println("http: " + req.method + " " + req.url + " failed: timed out after " + timeoutMs + " ms")
      return HttpResponse(0, "")

    // the completion token IS the error handle; nil renders "0"
    val err = doneCh.recv()
    if ("" + err) != "0" then
      // name the cause while we can: description -> temp file -> Sgola.
      // writeToFile:atomically:'s flag is zero, spelled by OMISSION (msg2's
      // trailing registers are zero-filled) — the one integer argument the
      // selector takes, and it is the zero.
      val desc = msg1(err, selLocalizedDesc)
      msg2(desc, selWriteToFile, nsstr(errTmp))
      println("http: " + req.method + " " + req.url + " failed: " + readTmp(errTmp))
      rmTmp(errTmp)
      HttpResponse(0, "")
    else
      var st = 0
      val resp = msg1(task, selResponse)
      if ("" + resp) != "0" then
        if asBool(msg2(resp, selRespondsTo, selStatusCode)) then
          st = asInt(msg1(resp, selStatusCode))
      var bodyS = ""
      if asBool(msg2(acc, selWriteToFile, nsstr(resTmp))) then
        bodyS = readTmp(resTmp)
      rmTmp(resTmp)
      HttpResponse(st, bodyS)
