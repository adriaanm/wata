/** M8 chunk 3 — the portable-conformance SEED runner (decision 11c). Compiled
 *  WITHOUT the sgola plugin, to real JVM bytecode: runs the byte oracle
 *  (`OggOracle.report`) on plain scalac and checks a handful of GOLDEN lines
 *  (CRC-32 vectors from an independent poly-0x04C11DB7/init-0 reference; the Ogg
 *  round-trip; the computed-Byte op surface). The SAME `report()` runs on the
 *  sgola backend; the two outputs must be byte-identical. Exit non-zero on any
 *  golden mismatch so `wataclient-tests.sh` can gate on it.
 *
 *  NB core's `list.scala`/`prelude.scala` SHADOW `scala.List`/`None` in scope
 *  here, so this runner avoids `List(...)` varargs / `None` and uses `Array` +
 *  a `while` loop. */
object Conformance:
  def main(args: Array[String]): Unit =
    val out = OggOracle.report()
    System.out.print(out)

    // GOLDEN lines (independently computed; see the report's byte-tuple rendering).
    val golden: Array[String] = Array(
      "crc empty 0.0.0.0",             // CRC-32 of "" is 0
      "crc 123456789 127.137.161.137", // 0x89A1897F little-endian
      "crc hello 66.134.150.211",      // 0xD3968642 little-endian
      "crc abc-ne-def true",
      "crc incremental true",
      "opushead size 19",
      "opushead magic true",
      "opustags size 20",
      "opustags vendor true",
      "stream bos true",
      "stream crc-valid true",
      "roundtrip frames 5",
      "roundtrip exact true",
      "roundtrip bigframe 600",
      "reader empty 0",
      "byte narrow 44",
      "byte widen -56",
      "byte cmp true",
      "rawstr len 6",       // M8 chunk 4: the raw-byte String bridge
      "rawstr roundtrip true",
      "freeze size 3",         // M9 chunk 1: freeze / toBytes / IArray
      "freeze stable true",    // freeze-then-append-more (both windows)
      "freeze builder 1004",
      "freeze2 true",
      "toBytes copy true",
      "iarray sum 60",
      "iarray tab 5"
    )
    var failed = 0
    var i = 0
    while i < golden.length do
      val line = golden(i)
      if !out.contains(line + "\n") then
        System.err.println("CONFORMANCE FAIL: missing golden line: " + line)
        failed += 1
      i += 1
    if failed == 0 then
      System.err.println("conformance: " + golden.length.toString + " golden lines OK")
    else
      System.err.println("conformance: " + failed.toString + " golden mismatches")
      System.exit(1)
