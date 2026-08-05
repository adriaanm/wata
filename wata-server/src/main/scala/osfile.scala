package go

import language.experimental.saferExceptions

/** `go.fsx` — `io/fs`, curated to the one thing `os.Stat` is called for: a
 *  file's size. Go's `os.Stat` returns the INTERFACE `fs.FileInfo`, so this is
 *  a trait (the core's `go.io.Reader` is the precedent); nothing implements
 *  it here, it is only ever the opaque type a `Stat` result carries. */
@go.bind("io/fs")
object fsx:
  trait FileInfo:
    @go.name("Size") def size(): scala.Long

/** `go.osfile` — an APP-OWNED facade for the stdlib `os` file operations
 *  the media blob store needs (the core facade covers only open/read/append).
 *  Bound via `@go.bind("os")`; the import path rides the tree.
 *
 *  `perm` is always passed as a LITERAL at call sites (`420` = 0644 for blob
 *  files, `493` = 0755 for the media dir) so it lands as an untyped constant
 *  into Go's `fs.FileMode` — the same convention as wata-fb's `go.syscall`
 *  facade. `writeFile`/`mkdirAll`/`remove` declare `Unit` and drop their
 *  `error` returns (best-effort durability, the journal's own posture; the
 *  caller that needs to distinguish a missing blob probes with `go.os.open`). */
@go.bind("os")
object osfile:
  /** `os.WriteFile(name, data, perm)` — create-or-TRUNCATE, so a re-write of
   *  the same blob (the boot migration re-running over an uncompacted journal)
   *  is idempotent. Error dropped. */
  @go.name("WriteFile") def writeFile(name: String, data: go.Bytes, perm: scala.Int): Unit = ???
  /** `os.MkdirAll(path, perm)` — "already exists" is the common and desired
   *  outcome at the one call site (the media dir at boot). Error dropped. */
  @go.name("MkdirAll") def mkdirAll(path: String, perm: scala.Int): Unit = ???
  /** `os.Remove(name)` — blob reclaim on redaction; a missing file is fine
   *  (the reclaim is idempotent across replays). Error dropped. */
  @go.name("Remove") def remove(name: String): Unit = ???
  /** `os.Rename(old, new)` — the second half of the accounts file's atomic
   *  write (temp file in the same dir, then rename over the target;
   *  config.scala `writeFile`). Error dropped: a failed rename leaves the
   *  previous file intact, which is the safe outcome. */
  @go.name("Rename") def rename(oldpath: String, newpath: String): Unit = ???
  /** `os.Stat(name)` — used only for SIZES the admin status panel reports
   *  (the journal file, each media blob). `throws`, so a missing file is a
   *  caught, reported-as-zero outcome rather than a dropped error. */
  @go.name("Stat") def stat(name: String): go.fsx.FileInfo throws sgo.GoError = ???
