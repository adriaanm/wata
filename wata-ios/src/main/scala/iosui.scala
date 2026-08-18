package go

/** `go.iosui` — the Go glue under the Sgola interpreter
 *  (go-pkgs/iosui, whose headers state why each function cannot be a facade
 *  binding on the generated uikit surface): the main-queue seam and the pool
 *  brackets (the dispatch machinery sgola-ffi.md keeps Go), cross-class
 *  casts, the raw-RGBA bitmap crossing the bindings refuse, the offscreen
 *  render probe (iOS UIColor cannot answer for its own components), and the
 *  ObjC-runtime reads the tests assert with. */
@go.bind("github.com/adriaanm/wata/go-pkgs/iosui")
object iosui:

  // ---- pools + the windowed main-queue seam ---------------------------------
  /** open an autorelease pool (bracket every direct UIKit excursion). */
  @go.name("PoolPush") def poolPush(): go.Uintptr = ???
  @go.name("PoolPop") def poolPop(p: go.Uintptr): Unit = ???
  /** enqueue a `go.callback` trampoline on the MAIN queue, pool-wrapped —
   *  the windowed frame hop. */
  @go.name("OnMain") def onMain(fn: go.Uintptr): Unit = ???

  // ---- cross-class casts ----------------------------------------------------
  @go.name("AllocLabelAsView") def allocLabelAsView(): uikit.UIView = ???
  @go.name("AllocImageViewAsView") def allocImageViewAsView(): uikit.UIView = ???
  /** adopt the interpreter's UIView as its concrete class — the cast is
   *  Go's; everything driven THROUGH the facet is the facade's own binding. */
  @go.name("AsLabel") def asLabel(v: uikit.UIView): uikit.UILabel = ???
  @go.name("AsImageView") def asImageView(v: uikit.UIView): uikit.UIImageView = ???

  // ---- the raw-pointer crossing the bindings refuse -------------------------
  /** a UIImage over a CGImage holding a COPY of rgba (w*h*4, meshed RGBA,
   *  opaque, row 0 = top). */
  @go.name("ImageFromRGBA") def imageFromRGBA(rgba: go.Bytes, w: scala.Int, h: scala.Int): uikit.UIImage = ???

  // ---- the offscreen render probe -------------------------------------------
  /** render the view's layer tree offscreen (one pixel per point, row 0 =
   *  the view's TOP row — the hello's band probes pinned the orientation)
   *  and read pixel (x, y) back packed 0xRRGGBB; -1 out of bounds. Needs no
   *  window and no screen. */
  @go.name("RenderPixel") def renderPixel(v: uikit.UIView, x: scala.Int, y: scala.Int): scala.Int = ???

  // ---- ObjC-runtime reads (the interp tests' assertion surface) -------------
  @go.name("ViewClassName") def viewClassName(v: uikit.UIView): String = ???
  /** the SAME native object? (the zero-field facade's `==` cannot say) */
  @go.name("SameView") def sameView(a: uikit.UIView, b: uikit.UIView): Boolean = ???
  @go.name("SubviewCount") def subviewCount(v: uikit.UIView): scala.Int = ???
  @go.name("SubviewAt") def subviewAt(v: uikit.UIView, i: scala.Int): uikit.UIView = ???

/** `go.iosshell` — the UIKit shell (go-pkgs/iosshell): UIApplicationMain
 *  ownership, the synthesized delegate, the window and the root container.
 *
 *  THREADING (the shell's structural inversion vs macshell): UIKit builds
 *  the UI inside its own launch callback, and UIApplicationMain never
 *  returns — so the Sgola main goes `start()` → `runApp(ready)`, and
 *  everything after (stage creation, adoptRoot, forking the pump) happens
 *  inside `ready`, a `go.callback` trampoline the shell invokes on the main
 *  thread once the window is key and visible. */
@go.bind("github.com/adriaanm/wata/go-pkgs/iosshell")
object iosshell:
  /** load UIKit, synthesize the delegate — no UI yet. Main goroutine, first. */
  @go.name("Start") def start(): Unit = ???
  /** UIApplicationMain — never returns; `ready` runs once the window is up. */
  @go.name("RunApp") def runApp(ready: go.Uintptr): Unit = ???
  /** the root container's bounds (points) — valid once `ready` has run. */
  @go.name("ContainerBounds") def containerBounds(): uikit.CGRect = ???
  /** splice the stage's root into the window's container (main thread). */
  @go.name("AdoptRoot") def adoptRoot(v: uikit.UIView): Unit = ???

  // ---- the touch keypad (keypad.go — the minimal viable input mapping) ------
  /** lay the key buttons into the container (main thread, inside `ready`). */
  @go.name("AddKeypad") def addKeypad(): Unit = ???
  /** one pending key event as `code*4 + phase` (IosKeys codes, phase 0
   *  release / 1 press), or -1 — never blocks. */
  @go.name("NextKey") def nextKey(): scala.Int = ???
  /** inject a key edge into the same queue the buttons feed. */
  @go.name("PushKey") def pushKey(code: scala.Int, phase: scala.Int): Unit = ???

  // ---- the wata:// scheme (plan 0062) ---------------------------------------
  /** the oldest URL handed to the app (Info.plist registers `wata:`), or ""
   *  — never blocks; the pump polls once per frame, the setup wait faster. */
  @go.name("TakeURL") def takeURL(): String = ???
  /** open a URL in its owning app (Safari for http) — any thread, the shell
   *  hops to the main queue itself; fire-and-forget. */
  @go.name("OpenURL") def openURL(u: String): Unit = ???

  // ---- APNs (plan 0065 tier 2, push.go) -------------------------------------
  /** ask for notification permission, become the notification centre's
   *  delegate, and register for remote notifications. Any thread; the UIKit
   *  work hops to the main queue itself. Called from the ready hop, so the
   *  interptest mode raises no permission prompt. */
  @go.name("RegisterForPush") def registerForPush(): Unit = ???
  /** the oldest device token iOS has issued (hex), or "" — never blocks. iOS
   *  RE-issues, so every value has to be posted to the server; a stale token
   *  is a phone that never rings. */
  @go.name("TakeDeviceToken") def takeDeviceToken(): String = ???
  /** the oldest registration failure or authorization answer, or "" — what
   *  the pump prints so a silently unregistered phone says so in the log. */
  @go.name("TakePushNote") def takePushNote(): String = ???
  /** the oldest push the app observed, described for printing
   *  (`present room=… event=…`), or "". `pushRoom`/`pushTapped` answer for
   *  the one this call just handed out. */
  @go.name("TakePush") def takePush(): String = ???
  /** the room id of the push `takePush` last returned. */
  @go.name("PushRoom") def pushRoom(): String = ???
  /** was the push `takePush` last returned a TAP? (open its conversation). */
  @go.name("PushTapped") def pushTapped(): Boolean = ???

  // ---- PushToTalk (plan 0065 tier 3, ptt.go) ---------------------------------
  /** load PushToTalk and ask the system for the channel manager the framework
   *  requires at LAUNCH (without one it tears down the app's channel and its
   *  ability to receive pushes). Called from the ready hop, once; inert when
   *  the framework is missing (every simulator) or the build carries no
   *  entitlement — the reason is queued as an event. */
  @go.name("PTTStart") def pttStart(): Unit = ???
  /** join the one channel: `name` is what the system UI shows, `key` is what
   *  its UUID is derived from (the family room id). "" when the request went
   *  out, else why it did not — a background join is refused by the framework
   *  and by the product (no server conscripts a phone into a channel). */
  @go.name("PTTJoin") def pttJoin(name: String, key: String): String = ???
  /** leave the channel (the ephemeral push token dies with it). */
  @go.name("PTTLeave") def pttLeave(): Unit = ???
  /** request the framework to begin/stop transmitting — the on-screen PTT
   *  button's path while a channel is joined, so it and the system talk
   *  button take the same one. */
  @go.name("PTTTransmit") def pttTransmit(on: Boolean): Unit = ???
  /** is a channel joined right now? */
  @go.name("PTTJoined") def pttJoined(): Boolean = ???
  /** the oldest thing the framework told the app, or "" — printed as `ptt: …`;
   *  `talk on <system|app>`, `talk off` and `left` are also decisions. */
  @go.name("TakePTTEvent") def takePttEvent(): String = ???
  /** the oldest EPHEMERAL push token (hex), or "". One per join, dead after
   *  the leave — it goes to `/_wata/v1/push/channel/join`, never to tier 2's
   *  registration. */
  @go.name("TakePTTToken") def takePttToken(): String = ???
  /** the oldest message an incoming `pushtotalk` push named, described for
   *  printing (`play #n ep=e room=… event=…`), or "". `pttPlayRoom`/
   *  `pttPlayEvent`/`pttPlayAgeMs`/`pttPlayEpisode` answer for the one this
   *  call just handed out. */
  @go.name("TakePTTPlay") def takePttPlay(): String = ???
  /** are there pushes the pump has not drained yet? Asked before an episode is
   *  ended: a push that landed after this frame's drain belongs to the episode
   *  about to be closed, and closing it would strand that message with no
   *  speaker and no session. */
  @go.name("PTTPlayPending") def pttPlayPending(): Boolean = ???
  /** the room id of the push `takePttPlay` last returned. */
  @go.name("PTTPlayRoom") def pttPlayRoom(): String = ???
  /** the event id of the push `takePttPlay` last returned. */
  @go.name("PTTPlayEvent") def pttPlayEvent(): String = ???
  /** how long ago that push ARRIVED, in ms — a suspended app runs no frames,
   *  so the pump can drain a push long after the framework delivered it. */
  @go.name("PTTPlayAgeMs") def pttPlayAgeMs(): scala.Int = ???
  /** the episode of that push — the raised speaker it belongs to, shared with
   *  every other push of the same burst. It goes back into
   *  `pttSpeakerStopped`, so an episode can only ever end itself. */
  @go.name("PTTPlayEpisode") def pttPlayEpisode(): scala.Int = ???
  /** has the audio session THIS EPISODE asked for been handed over —
   *  activated, and activated after the episode opened? "Some session is
   *  active" is not enough (a message would play on the previous episode's
   *  session and die when that one is torn down), and per-PUSH is too strict
   *  (a speaker that is already up produces no new activation, so every
   *  message after a burst's first would wait for one that never comes). */
  @go.name("PTTPlayReady") def pttPlayReady(): Boolean = ???
  /** end a receive episode: clear the channel's active remote participant, so
   *  the system takes the speaker off screen and hands the audio session
   *  back. An episode nobody ends never ends — and one that is no longer
   *  current ends nothing, which is what `episode` is for. */
  @go.name("PTTSpeakerStopped") def pttSpeakerStopped(episode: scala.Int): Unit = ???

  // ---- the persistent app log (plan 0064) -----------------------------------
  /** tee this process's stdout+stderr into `path` (truncated at open, growth
   *  capped) — the original console keeps every line, so tethered launches
   *  and the harnesses are unchanged. Call FIRST in main, before any output.
   *  "" on success, else the error text. */
  @go.name("TeeLog") def teeLog(path: String): String = ???
