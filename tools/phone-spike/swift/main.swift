// The functional smoke for plan 0023 M1: a Swift process that calls into the
// gomobile-bound, sgola-emitted wataclient core and prints what the client saw.
//
//   watashell <homeserver> <user> <password> [timeoutMillis]
//
// Nothing here knows about Matrix, sync, or the domain model — that is the
// point. The whole client is behind two C-shaped functions.

import Foundation

let args = CommandLine.arguments
guard args.count >= 4 else {
    FileHandle.standardError.write("usage: watashell <homeserver> <user> <password> [timeoutMillis]\n".data(using: .utf8)!)
    exit(2)
}
let timeoutMillis = args.count >= 5 ? Int(args[4]) ?? 30000 : 30000

print("hello " + WatamobileHello())

let report = WatamobileProbe(args[1], args[2], args[3], timeoutMillis)
print(report, terminator: "")

// A report that never reached a snapshot starts with `error `; make that the
// process's answer so a shell script does not have to grep for it.
exit(report.hasPrefix("error ") ? 1 : 0)
