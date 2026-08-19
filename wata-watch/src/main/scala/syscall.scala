package go

import language.experimental.saferExceptions

/** `go.syscall` — the APP-OWNED device-layer facade for the raw Linux syscalls
 *  the framebuffer / evdev / LED-sysfs code needs, bound entirely via
 *  `@go.bind("syscall")` (the import path rides the tree; the emitter never
 *  learns the name "syscall" itself). Device-layer code is app-side and MAY
 *  use facades directly (the portability tripwire only guards the wataclient
 *  module). `syscall.*` exists on BOTH darwin and linux, so this compiles on
 *  the host even though the device paths only *run* on the target hardware
 *  (the host uses the PNG backend instead of a real framebuffer).
 *
 *  Bound surface: syscall.{Open,Close,Read,Write,Mmap,Munmap} + the O_* /
 *  PROT_* / MAP_* constants. `Close`/`write`/`Munmap` declare `Unit` and drop
 *  their `error`/`n` returns (best-effort); `Read`/`Mmap`/`Open` — and
 *  `writeChecked`, a second binding of the same `syscall.Write` — ride the
 *  `(T, error)` throws val-bind lowering. `perm` is always passed as a LITERAL
 *  at call sites (`0` for the read/write-existing paths, `420` = 0644 for the
 *  one create path, the uitest PNG checkpoint dump) so it lands as an untyped
 *  constant into Go's `uint32`. */
@go.bind("syscall")
object syscall:
  // --- open flags (Go untyped constants) ----------------------------------
  def O_RDONLY: scala.Int  = ???
  def O_WRONLY: scala.Int  = ???
  def O_RDWR: scala.Int    = ???
  def O_NONBLOCK: scala.Int = ???
  def O_CREAT: scala.Int    = ???
  def O_TRUNC: scala.Int    = ???
  // --- mmap prot / flags ---------------------------------------------------
  def PROT_READ: scala.Int  = ???
  def PROT_WRITE: scala.Int = ???
  def MAP_SHARED: scala.Int = ???

  /** `syscall.Open(path, mode, perm)` — `(fd int, err error)`. */
  @go.name("Open") def open(path: String, mode: scala.Int, perm: scala.Int): scala.Int throws sgo.GoError = ???
  /** `syscall.Close(fd)` — error dropped. */
  @go.name("Close") def close(fd: scala.Int): Unit = ???
  /** `syscall.Read(fd, p)` — `(n int, err error)`; EAGAIN on a NONBLOCK fd
   *  surfaces as a `GoError` (the caller's `catch` = Zig's `catch break`). */
  @go.name("Read") def read(fd: scala.Int, p: go.Bytes): scala.Int throws sgo.GoError = ???
  /** `syscall.Write(fd, p)` — `n`/`error` dropped (best-effort; the
   *  stdout/PNG dump sinks). */
  @go.name("Write") def write(fd: scala.Int, p: go.Bytes): Unit = ???
  /** `syscall.Write(fd, p)` — `(n int, err error)` surfaced, for callers that
   *  need to distinguish a real write failure (the LED sysfs nodes). */
  @go.name("Write") def writeChecked(fd: scala.Int, p: go.Bytes): scala.Int throws sgo.GoError = ???
  /** `syscall.Mmap(fd, offset, length, prot, flags)` — `([]byte, error)`. */
  @go.name("Mmap") def mmap(fd: scala.Int, offset: scala.Long, length: scala.Int, prot: scala.Int, flags: scala.Int): go.Bytes throws sgo.GoError = ???
  /** `syscall.Munmap(b)` — error dropped. */
  @go.name("Munmap") def munmap(b: go.Bytes): Unit = ???
  /** `syscall.Mkdir(path, mode)` — error dropped (best-effort: "already
   *  exists" is the common and desired outcome at the one call site, the
   *  config store's parent directory). */
  @go.name("Mkdir") def mkdir(path: String, mode: scala.Int): Unit = ???
  /** `syscall.Unlink(path)` — error dropped (best-effort: the one call site is
   *  the outbox freeing a delivered message's slot, which TRUNCATES the file
   *  before unlinking it, so a failed unlink still leaves the slot empty
   *  rather than redelivering the message next boot). */
  @go.name("Unlink") def unlink(path: String): Unit = ???
