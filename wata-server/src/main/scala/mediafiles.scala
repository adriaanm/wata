import language.experimental.saferExceptions
import sgo.{Mutex, mutex}

/** The file-backed media blob store: one file per blob at
 *  `<dataDir>/media/<mediaId>` (mediaId is server-minted base64url, so it is
 *  path-safe). The in-memory store and the journal carry METADATA ONLY; the
 *  bytes live here. See docs/plans/0012-media-bounds.md.
 *
 *  Enablement follows persistence: the data dir is `$WATA_DATA` when set,
 *  otherwise the directory containing the `$WATA_LOG` journal file. With
 *  neither env var (the stateless test runs) there is no dir, `enabled` is
 *  false, and blobs stay in memory exactly as before — nothing is written.
 *
 *  Writes happen BEFORE the corresponding journal op (store.scala
 *  `storeMedia`), so a crash between the two leaves an orphan FILE, never a
 *  journal ref to a missing blob. `write` truncates, so the boot migration
 *  (persist.scala `applyMedia`) re-running over an uncompacted journal is
 *  idempotent. */
class MediaFilesState:
  var dir: String = ""

object MediaFiles:
  private val cell: Mutex[MediaFilesState] = mutex(new MediaFilesState())

  /** resolve the media dir from the env (write-once, before serving) and
   *  create it. Called from `Server.serve` BEFORE `Journal.boot`, so the
   *  journal replay's migration path can already write blobs out. */
  def boot(): Unit =
    val d = resolveDataDir(go.sys.getenv("WATA_DATA"), go.sys.getenv("WATA_LOG"))
    if d == "" then ()
    else bootWith(d + "/media")

  def bootWith(mediaDir: String): Unit =
    go.osfile.mkdirAll(mediaDir, 493)
    cell.withLock(st => st.dir = mediaDir)
    println("Wata media dir " + mediaDir)

  /** `$WATA_DATA`, else the journal file's parent dir, else "" (disabled). */
  def resolveDataDir(dataEnv: String, logEnv: String): String =
    if dataEnv != "" then dataEnv
    else if logEnv != "" then parentDir(logEnv)
    else ""

  /** the path up to the last '/', or "." for a bare filename. */
  def parentDir(p: String): String =
    val i = lastSlash(p, 0, -1)
    if i < 0 then "." else if i == 0 then "/" else p.substring(0, i)

  def lastSlash(s: String, i: scala.Int, best: scala.Int): scala.Int =
    if i >= s.length then best
    else if s.charAt(i) == '/' then lastSlash(s, i + 1, i)
    else lastSlash(s, i + 1, best)

  /** file-backed mode on? Read at every media mutation site. */
  def enabled: Boolean = cell.withLock(st => st.dir != "")

  def blobPath(mediaId: String): String = cell.withLock(st => st.dir) + "/" + mediaId

  /** write one blob (create-or-truncate; error dropped — best-effort, the
   *  journal's own durability posture). `data` is a byte-preserving String. */
  def write(mediaId: String, data: String): Unit =
    go.osfile.writeFile(blobPath(mediaId), go.bytes(data), 420)

  /** read one blob back as a byte-preserving String; `None` when missing. */
  def load(mediaId: String): Option[String] =
    var out: Option[String] = None
    try
      val raw = go.sys.readFile(blobPath(mediaId))
      out = Some(go.string(raw))
      ()
    catch case e: sgo.GoError => ()
    out

  /** does the blob file exist? (an open/close probe — replay uses this instead
   *  of decoding any payload). */
  def exists(mediaId: String): Boolean =
    var ok = false
    try
      val f = go.os.open(blobPath(mediaId))
      f.close()
      ok = true
      ()
    catch case e: sgo.GoError => ()
    ok

  /** delete one blob (redaction / retention reclaim; idempotent — a missing
   *  file is fine on a replayed redaction). */
  def delete(mediaId: String): Unit =
    go.osfile.remove(blobPath(mediaId))
