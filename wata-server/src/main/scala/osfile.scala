package go

/** `go.osfile` — an APP-OWNED facade for the three stdlib `os` file operations
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
