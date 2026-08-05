package go

import language.experimental.saferExceptions

/** `go.gioshell` — the APP-OWNED facade for `go-pkgs/gioshell`, the Gio window
 *  that hosts the real frame loop on a desktop (and, with gogio, on a phone):
 *  plan 0023 milestone 2, "the phone is a bigger BQ268". Gio owns the window,
 *  the texture upload and the touch zones; this repo's UI code is untouched,
 *  because the window is reached through one more `UiDevice` backend
 *  (`GioDevice`, gio.scala).
 *
 *  The whole surface is primitive-typed — bytes, ints, bools — the same
 *  discipline `go.audio` keeps. Nothing Sgola-shaped crosses into Go: a key
 *  event arrives as one packed int and `GioDevice` rebuilds the `KeyEvent`.
 *
 *  THREADING. `eventLoop` (Gio's `app.Main`) must own the process's main
 *  goroutine — macOS runs its window server on it — and it does not return.
 *  So the wata frame loop runs on a forked goroutine over `GioDevice` while
 *  the main goroutine sits in `eventLoop`; `present` copies the frame and
 *  wakes the window, `nextKey` drains the queue the window fills. The device
 *  object itself never crosses: `drive` builds it inside the fork.
 *
 *  Gio is compiled in only with the `gioshell` Go build tag (`just
 *  phone-blit`); every other build — the armv7 device cross-build, the
 *  linux/amd64 smoke, plain `sgo build` — gets the package's loud-error stub,
 *  so `start` is the one call that throws. */
@go.bind("github.com/adriaanm/wata/go-pkgs/gioshell")
object gioshell:
  /** `gioshell.Start(w, h, scale, maxFrames)` — bring the window up and
   *  return. `scale` is a forced integer magnification (0 = fit the window),
   *  `maxFrames` > 0 quits after that many presented frames (the unattended
   *  sanity run). Errors on a build without the `gioshell` tag. */
  @go.name("Start") def start(w: scala.Int, h: scala.Int, scale: scala.Int,
                              maxFrames: scala.Int): Unit throws sgo.GoError = ???

  /** `gioshell.Main()` — Gio's event loop. Call it from the main goroutine,
   *  last; it does not return. */
  @go.name("Main") def eventLoop(): Unit = ???

  /** `gioshell.Present(px)` — copy the RGB565 frame and wake the window. */
  @go.name("Present") def present(px: go.Bytes): Unit = ???

  /** `gioshell.Leds(green, red)` — the two connection LEDs in the chrome. */
  @go.name("Leds") def leds(green: Boolean, red: Boolean): Unit = ???

  /** `gioshell.NextKey()` — one pending key event as `code*2 + pressed`, or
   *  -1 when the queue is empty (never blocks, exactly like `Evdev.poll`). */
  @go.name("NextKey") def nextKey(): scala.Int = ???

  /** `gioshell.Quit()` — the window is gone, or the frame budget is spent. */
  @go.name("Quit") def quit(): Boolean = ???

  /** `gioshell.Frames()` — frames presented to the window this session. */
  @go.name("Frames") def framesPresented(): scala.Int = ???

  /** `gioshell.Painted()` — frames the WINDOW drew. Presented counts what this
   *  loop handed over; painted counts what Gio put on the screen, which is
   *  what an unattended run needs to tell a live window from a live loop. */
  @go.name("Painted") def framesPainted(): scala.Int = ???

  /** `gioshell.Done()` — the frame loop has torn down; ends the process,
   *  since `eventLoop` never gives the main goroutine back. */
  @go.name("Done") def done(): Unit = ???
