# wata-tui — the terminal client and admin interface

`wata-tui` is the second client: a host-side Sgola app over `wataclient` +
`json` with no device layer. It logs in as a real family user, shows what
that user's client knows, sends and plays voice messages, and can put an
authenticated request straight at the server. It exists so development and
administration do not need a BQ268 (or the simulator) in the loop.

It is a **line-oriented command REPL**, not a full-screen UI: one command
per stdin line, plain deterministic lines out. That is what makes it both
hand-usable and scriptable — `tools/tui-smoke.py` drives it by piping a
command script and asserting on the printed lines. The reasoning for that
shape, and what of the retired TypeScript tui is deliberately not ported,
is in [../plans/0016-tui-port.md](../plans/0016-tui-port.md).

## Running it

```
WATA_TUI_USER=alice WATA_TUI_PASS=testpass123 just tui
wata-tui <homeserver> <user> <password>
```

Login is from `WATA_TUI_HS` (default `http://127.0.0.1:8008`),
`WATA_TUI_USER`, `WATA_TUI_PASS`, with positional arguments overriding.
There is no stored session file — an admin tool logs in each run.
`$WATA_IROH_CONFIG` swaps the transport for embedded iroh exactly as it
does for `wata-fb` (plan 0013), so the tui is also a second iroh client.

Startup prints one line: `ready <userId>`, or `login failed`.

## Commands

| command | does |
|---|---|
| `snap` | the current `StateSnapshot`: `conn`, `self`, one `contact` line each, one `conv` line each with message and unplayed counts |
| `msgs <conv#>` | that conversation's voice messages: sender, duration, `origin_server_ts`, played flag, event id, mxc url |
| `send <conv#\|user> <file.ogg>` | upload the file and send it as `m.audio`; a non-numeric target is a user id whose DM room the server resolves |
| `play <conv#> <msg#>` | download the Ogg, write it under `$WATA_TUI_TMPDIR` (default `/tmp`), hand it to the player, then send the read receipt |
| `mark <conv#> <msg#>` | the read receipt only |
| `fav <conv#> <msg#>` | TOGGLE the server's `net.wata.favorite` marker on that message (plan 0019), so the media retention sweep spares it; prints `fav <eventId> <true\|false>` — the state the toggle left behind — or `fav failed: <status> <body>` |
| `wifi <conv#\|user>` | the wifi panel (plan 0020): queue a `wifi_scan` for that user's handset through the server's command mailbox, wait for its report, print the networks numbered (`net <n> <ssid> signal=<dBm> secured=<bool>`) |
| `join <net#>` | prompt `psk?` and read the PSK as the NEXT stdin line (empty for an open network — a prompt, not an argument, so the secret never sits in a shell history line), queue `wifi_join` for the last `wifi` target, wait for the device's verdict: `wifi join ok <detail>` / `wifi join failed: <detail>` |
| `raw <METHOD> <path> [json]` | an authenticated request straight at the server; prints `raw <status> <length>` then the body |
| `wait <ms>` | poll snapshots for that long, printing `change convs=<n> unplayed=<n>` whenever the summary moves, then `waited <ms>` |
| `quit` | wind the client down and exit (`bye`) |

Every rejection is prefixed `?`. Numbered references index the **last
`snap`**: `snap` stashes the conversation list it printed, and
`msgs`/`play`/`mark`/`fav` index into that list and into a conversation's own
message order — which is exactly the order `msgs` prints. The wifi pair has
its own numbering base the same way: `wifi` stashes its target and the
network list it printed, and `join` indexes that. The wait between queue and
report is skew-free — each report carries a server-stamped `seq`
(wata-server.md, "The device-command mailbox"), and the tui polls until the
seq moves past what it read before queueing, so a stale report from an
earlier scan can never satisfy a new one. So a script's
numbers are stable against whatever arrives mid-run, and `play` does not
depend on `msgs` having been run first. Ids are printed alongside for
`raw` use. Mic capture is out of scope: `send` takes an Ogg file.

## How it is built

| file | what it is |
|---|---|
| `src/main/scala/main.scala` | login from env/args, `Runtime.make` + `start` in one `supervised` scope, the REPL as the main goroutine |
| `src/main/scala/repl.scala` | the stdin line loop, command parse, listing state, and the file read/write |
| `src/main/scala/player.scala` | `$WATA_TUI_PLAYER` resolution and the process run |
| `src/main/scala/text.scala` | `Str`: tokenizing, indexing, decimal parse — there is no `String.split` here |
| `src/main/scala/caps.scala` | the `HttpDo`/`Clock` impls, including the `WATA_IROH_CONFIG` client swap |
| `src/main/scala/facades.scala` | the app-owned `@go.bind` facades: `go.osx` (stdin), `go.bufio` (line scanner), `go.exec` (player), `go.syscall` (file write) |
| `src/main/scala/irohnet.scala` | the `go.irohnet` facade, same as wata-fb's |

The client is **headless** (`Runtime.make`, not `makeWithAudio`): playback
is an external process, so there is no audio thread and no `AudioCmd`
consumer. `play` therefore does not use `ActPlay` — a headless client
routes that to an audio thread that does not exist — and calls
`MatrixHttp.downloadMedia` directly through an `Hs` built on
`Runtime.lastAuth`. That same `Hs` is what `raw` rides, which is why `raw`
gets the bearer token and the 429 retry for free.

Three facade details worth knowing before editing `facades.scala`:

- The core facade already owns `go.os`, and a second `object os` in
  `package go` would collide, so the binding that reaches `os.Stdin` is
  `go.osx` — the same reason wata-fb's `net` binding is `go.netif`.
- Go's `exec.Command(name, arg ...string)` is variadic and this dialect
  has no slice-spread lowering, so the arity is bound explicitly:
  `command0` … `command4`, all `@go.name("Command")`, picked by argument
  count in `Player.cmdFor`.
- A **`Unit`-returning self-recursive list walk crashes the compiler
  backend** here (`sgolaBackend`: "unsupported expression (Thicket)" over
  an `EmptyTree`), in the shape
  `def walk(xs: List[T], i: Int): Unit = xs match { case h :: t => println(…); walk(t, i + 1)  case Nil => () }`.
  It does not reproduce from that def alone — it needs the surrounding
  file — so every list walk here is a `while` loop over a `var cur`,
  which is the idiom the rest of the repo already uses.

## The gate

`tools/tui-smoke.py` (`just tui-smoke`) boots one fresh `wata-server` on a
random port — pid-matched through `lsof`, the harness-isolation pattern
described in [wata-fb.md](wata-fb.md) — and runs three tui sessions against
it in sequence, since `wataclient`'s runtime is one client per process:

1. **bob** sends `go-pkgs/audio/testdata/tui-foreign.ogg` to
   `@alice:localhost` and quits.
2. **alice** runs `wait / snap / msgs 2 / play 2 1 / wait / snap / raw GET
   whoami / quit`.
3. **alice again**, for the wifi panel: while a harness thread plays bob's
   handset over the command mailbox (long-polls `/cmd/poll` with bob's
   token, answers the scan with canned networks and the join with a
   verdict), the session runs `join`-before-scan (refused), `wifi
   @bob:localhost`, a bad `join 9`, and `join 1` with the PSK on the next
   line. Asserted: the numbered network lines match the canned report
   verbatim, the verdict line, and — on the device side — that the join
   command arrived with the ssid and the exact PSK.

It asserts on the printed lines: the first `snap` shows the server-minted
family room at index 1 and bob's DM at index 2 with `msgs=1 unplayed=1`, `msgs` lists one unplayed message from bob with an
mxc url, `play` reports the fixture's exact byte count, and the second
`snap` shows `unplayed=0` — the receipt `play` sent, round-tripped through
the server. `$WATA_TUI_PLAYER` points at a stub that records its argv and
copies the file it was handed, so the harness also byte-compares the
played Ogg against the one bob sent, end to end through the media repo.

It takes ~10 seconds (two `wait`s dominate), so it is a standalone recipe
rather than part of `just ci`.
