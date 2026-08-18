# 0064 — wata-ios: a persistent on-device log, and audio errors that name their cause

Status: accepted

## The problem

On a physical iPhone the app's output (Scala `println` → Go stdout,
Go `log.Printf` → stderr) is visible only through a tethered
`devicectl device process launch --console` — phone unlocked, cabled,
launched from the Mac. A normal icon-tap launch logs into the void, so
a field failure leaves nothing to read afterwards.

The first such failure made the second problem concrete: an
`AeRecordingError` at startup whose cause was swallowed twice over —
the shared audio thread's non-throws boundaries
(`wata-fb/src/main/scala/audiothread.scala`: `doRecord`, `doPlay`,
`playPcm`, `echoCapture`, `echoPlay`) catch `sgo.GoError` and discard
the error text, and wata-ios's pump then prints a STALE note claiming
audio is "stubbed off (plan 0044)" — plan 0063 made it real.

## The decision

1. **Tee stdout+stderr into the sandbox, at the top of main.** A Go
   function in `go-pkgs/iosshell` (`TeeLog(path)`) — raw fd work is
   not expressible in the dialect, and iosshell is the platform-glue
   home — replaces fds 1 and 2 with pipe write-ends (`dup2`) and runs
   one copy goroutine per stream writing every chunk to BOTH the
   original fd and the log file. A tee, not a redirect: tethered
   `--console` launches and the simulator harnesses keep their output
   unchanged. The file is `$HOME/Documents/wata.log` (Documents exists
   in every iOS sandbox and is trivially addressable by `devicectl
   copy from`), truncated at open, growth capped at 4 MiB per run
   (past the cap the console copy continues, the file copy stops) —
   a debug surface, not a log system. Exposed as
   `go.iosshell.teeLog(path): String` ("" or the error text), called
   first in `Main.main` before any output; a failure is printed and
   ignored. wata-mac is left alone: its launches (Terminal, the smoke
   harnesses) already have a console, and lifting TeeLog into shared
   glue can happen when the mac wants a file log.

2. **A pull recipe.** `just ios-log` → `tools/ios-log.py`: fetch the
   log with `xcrun devicectl device copy from --domain-type
   appDataContainer --domain-identifier <bundle-id> --source
   Documents/wata.log --destination <tmp>` and print it (or `--out`
   to keep it). Device: `--device` / `$WATA_DEVICE`, else the single
   attached iPhone (reusing ios-device.py's `pick_device`). Bundle
   id: `--bundle-id` / `$WATA_BUNDLE_ID`, default `net.wa-ta.ios`
   (ios-device.py's convention). No device name is hardcoded, per
   the environment-specifics rule.

3. **Audio errors name their cause.** Every audiothread.scala catch
   that turns a `sgo.GoError` into an error event first prints
   `audio: <surface> failed: <e.getMessage>` — the catch is the only
   place the text still exists. The file is shared (wata-mac and
   wata-ios symlink it), so all three clients gain the line; that is
   the point. And wata-ios's `noteAudioStub` (plus its told-once
   cell) is deleted — the audio thread's own line supersedes it — and
   main.scala's remaining "stub audio" comments are corrected.

## Verification

`just ci` green; `just ios-smoke` + `just ios-enroll-smoke` if the
simulator environment allows (the tee runs in both — the harnesses'
expected lines double as the tee's no-regression check; the log file
itself is asserted by eye on the next device install). The on-phone
pull (`just ios-log` after an icon-tap launch) is the owner's next
`just ios-device` session — the phone is not attached.

## Out of scope

Log rotation beyond truncate+cap; os_log/unified-logging integration;
a wata-mac file log; wata-fb (its `/tmp/wata.log` redirect is the
rootfs's `start.sh`).
