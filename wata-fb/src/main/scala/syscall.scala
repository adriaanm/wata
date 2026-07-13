package go

import language.experimental.saferExceptions

/** `go.syscall` — the APP-OWNED device-layer facade (M8 chunk 5) for the raw
 *  Linux syscalls the framebuffer / evdev / LED-sysfs ports need, bound entirely
 *  via `@go.bind("syscall")` (the chunk-2 annotation mechanism; the plugin/core
 *  carry ZERO knowledge of it — the import path rides the tree). Device-layer
 *  code is app-side and MAY use facades (the portability tripwire only guards
 *  the wataclient module). `syscall.*` exists on BOTH darwin and linux, so this
 *  compiles on the host (`sgo ci`) even though the device paths only *run* on
 *  the BQ268 (the host uses the PNG backend, decision 4).
 *
 *  Enumerated binds (report): syscall.{Open,Close,Read,Write,Mmap,Munmap} +
 *  the O_* / PROT_* / MAP_* constants. `Close`/`Write`/`Munmap` declare `Unit`
 *  and drop their `error`/`n` returns (best-effort, the `os.File.Close` drop
 *  precedent); `Read`/`Mmap`/`Open` ride the `(T, error)` throws val-bind
 *  lowering. `perm` is always passed as the literal `0` at call sites (no
 *  O_CREAT is used) so it lands as an untyped constant into Go's `uint32`. */
@go.bind("syscall")
object syscall:
  // --- open flags (Go untyped constants) ----------------------------------
  def O_RDONLY: scala.Int  = ???
  def O_WRONLY: scala.Int  = ???
  def O_RDWR: scala.Int    = ???
  def O_NONBLOCK: scala.Int = ???
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
  /** `syscall.Write(fd, p)` — `n`/`error` dropped (best-effort). */
  @go.name("Write") def write(fd: scala.Int, p: go.Bytes): Unit = ???
  /** `syscall.Mmap(fd, offset, length, prot, flags)` — `([]byte, error)`. */
  @go.name("Mmap") def mmap(fd: scala.Int, offset: scala.Long, length: scala.Int, prot: scala.Int, flags: scala.Int): go.Bytes throws sgo.GoError = ???
  /** `syscall.Munmap(b)` — error dropped. */
  @go.name("Munmap") def munmap(b: go.Bytes): Unit = ???
