package go

import language.experimental.saferExceptions

/** `go.memprobe` — the spike's one facade: the live heap after a forced GC.
 *  This is the measurement DIFF-RETAINS-REPRO is judged by; RSS is a
 *  scavenger-driven sawtooth and is exactly what made the wata-mac leak read
 *  as "steady" for a whole session. */
@go.bind("github.com/adriaanm/wata/go-pkgs/memprobe")
object memprobe:
  /** `memprobe.LiveHeap()` — runtime.GC(), then ReadMemStats().HeapAlloc. */
  @go.name("LiveHeap") def liveHeap(): Long = ???
