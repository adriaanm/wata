import language.experimental.saferExceptions

/** the txn-seed oracle (plan 0048): transaction ids are deduped server-side
 *  per DEVICE across restarts, so a client construction must seed the txn
 *  counter from the clock and only ever move it forward. Observed through
 *  the one seam that answers a txn id without a network: `Runtime.dropped`
 *  on a voice send emits `EvSendFailed` carrying the next id. Byte-diffed
 *  by tools/wataclient-tests.sh, check 8. */

/** a clock the test moves by hand; the cell lives at module level (the house
 *  synchronizer shape — an `sgo.Atomic` class field has no Go mapping). */
object TxnClock:
  val msC: sgo.Atomic[Long] = sgo.atomic(5000000000L)

final class StepClock() extends Clock:
  def nowUnixMillis(): Long = TxnClock.msC.get()
  def sleepMs(ms: Long): Unit = ()

object TxnTest:

  def run(): Unit =
    val clock = StepClock()
    val cfg = ClientConfig("http://parked", "u", "p", 1000, Session("", "", "", "", ""))
    var c = Runtime.make(cfg, NullHttp(), clock)
    println("txn after construction at t=5000000s: " + nextTxn(c))
    // a second client in the same process, same second: the seed may not
    // step the counter back onto the id the line above used.
    c = Runtime.make(cfg, NullHttp(), clock)
    println("txn after same-second reconstruction: " + nextTxn(c))
    // the clock moved on: a later construction re-seeds forward.
    TxnClock.msC.set(9000000000L)
    c = Runtime.make(cfg, NullHttp(), clock)
    println("txn after clock advance to t=9000000s: " + nextTxn(c))

  /** the next txn id, read without a network: a dropped voice send emits
   *  `EvSendFailed(txnCounterC.add(1))`. */
  def nextTxn(c: MatrixClient): Int =
    Runtime.dropped(c, ActSendVoice("", "", Bytes.fromRawString(""), 0L))
    drainTxn(c)

  def drainTxn(c: MatrixClient): Int =
    var out = -1
    var going = true
    while going do
      Runtime.pollEvent(c) match
        case s: Some[UiEvent] => s.value match
          case f: EvSendFailed => out = f.txnId
          case _ => ()
        case None => going = false
    out
