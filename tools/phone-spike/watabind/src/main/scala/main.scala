import language.experimental.saferExceptions

/** An entry point exists only because `sgo` builds LIBRARIES that carry the
 *  sgola runtime as apps (see sgo.build): this module's product is the
 *  emitted Go package, not a binary. Running it is still useful — it drives
 *  the same `Bind.probe` the bound framework calls, so the Swift shell's
 *  output can be diffed against a pure-Go run of identical code.
 *
 *    watabind <homeserver> <user> <password>
 */
object Main:
  def main(args: Array[String]): Unit =
    if args.length < 3 then println(Bind.hello())
    else println(Bind.probe(args(0), args(1), args(2), 30000L))
