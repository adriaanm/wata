# 0015 — wata-server as a macOS service

Status: done

## Problem

`[SRV-PACKAGE]` wata-server runs only as a foreground process out of a dev
checkout (`just server`). The family homeserver has to run like an appliance
on the host mac: come up at boot, keep running after logout, restart when it
crashes, keep its data across upgrades, and not fill the disk with logs. Today
none of that exists; a reboot silently takes the family network down.

## Decision

Package it as a **launchd LaunchDaemon** — the only launchd flavor that
survives both reboot and logout — driven by one Python tool with an
install/uninstall/status/package surface, and a filesystem layout that
separates versioned code from stable data:

```
/usr/local/wata/
  releases/<version>/wata-server     # one dir per installed build
  current -> releases/<version>      # the running release (rollback = re-point)
  bin/wata-server-run                # stable wrapper the daemon execs
  etc/wata.env                       # config as env lines (sourced by the wrapper)
  etc/users.json                     # the provisioned accounts (WATA_USERS)
  data/                              # WATA_DATA: journal.jsonl, media/, format marker
  log/wata-server.log                # daemon stdout+stderr
/Library/LaunchDaemons/net.wata.server.plist
/etc/newsyslog.d/wata-server.conf   # size-based rotation of the daemon log
```

Why each piece:

- **Wrapper, not a fat plist.** launchd cannot source an env file, and baking
  env into the plist means every config change is a sudo plist regen +
  bootout/bootstrap. The plist stays a constant: it execs
  `bin/wata-server-run`, which sources `etc/wata.env` and execs
  `current/wata-server`. Config edits are a file edit + `just server-restart`.
- **`<version>` = `<yyyymmdd>-<shortsha>`** from the wata checkout at package
  time (`-dirty` suffixed when the tree is). Old releases are kept; uninstall
  and a `prune` are the only things that delete them.
- **Data is unversioned but format-marked.** `data/` survives every upgrade
  untouched (the journal is the database). A `data/FORMAT` file (currently
  `1`) is written on first install so a future incompatible journal/media
  layout change has something to gate a migration on. The daemon runs with
  `WATA_LOG=data/journal.jsonl`, `WATA_DATA=data`.
- **Restart on failure**: `KeepAlive` with `SuccessfulExit=false` plus
  `ThrottleInterval` 10 — a crash loop retries forever but slowly; a clean
  `launchctl bootout` stays down.
- **Rotation is newsyslog's job** (native macOS, size-based, no extra
  daemon): rotate at 5 MB, keep 5, compress. The journal is NOT a log and is
  never rotated.
- **Runs as the installing user** (plist `UserName`), not root: the server
  needs no privileged port (8008, or no port at all in iroh mode) and the
  data dir stays owned by the human who administers it.
- **Default transport is TCP :8008**; iroh mode is one line in `wata.env`
  (`WATA_IROH_CONFIG=/usr/local/wata/etc/iroh.json`). The packaged binary is
  built `-tags iroh` when `--iroh` is passed to `package` (requires the
  irohnet staticlib, same path `tools/iroh-lan-smoke.py` uses); the default
  package is the plain build.

## What changes

- `tools/server-service.py` — all the logic, subcommands:
  - `package [--iroh]` — `sgo build` + `go build` the server, stage a release
    dir under `.service-stage/` (no sudo, no system paths).
  - `install` — copy the staged release into the layout above, write the
    wrapper/env/users/plist/newsyslog files if absent (never overwrite an
    existing `etc/`), point `current`, `launchctl bootout` (if loaded) +
    `bootstrap`. Requires sudo; refuses politely without it.
  - `uninstall` — bootout, remove the plist + newsyslog conf; `--purge` also
    removes `/usr/local/wata` (data included) after an explicit confirm.
  - `status` — layout present? plist loaded? process alive? port/iroh
    announce answering? journal size, release list.
  - `selftest` — the no-sudo gate: package, install into `--root <tmp>`
    (every absolute path re-rooted), `plutil -lint` the plist, run the
    wrapper in the foreground against the staged layout, poll `/versions`,
    assert the journal appears, kill it. This is what CI-adjacent runs use.
- `justfile` — thin recipes: `server-package`, `server-install`,
  `server-uninstall`, `server-status`, `server-restart`, `server-selftest`.
- `docs/design/wata-server.md` — a "Running as a service" section tagged
  `[SRV-PACKAGE]` describing the layout and lifecycle (folded in when done).

## Verification

`tools/server-service.py selftest` green (runs unprivileged, exercises
package → install → boot → serve → journal). The real `sudo just
server-install` on this mac is a human step; `just server-status` after it is
the acceptance check.

Acceptance recorded 2026-08-05: installed and serving on this mac —
`/versions` answers on `:8008`, persistence ON with the journal appearing in
`data/` on first login, log writing under `log/`. The first install caught
what the no-sudo selftest cannot: a root install left the layout root-owned
while the daemon runs as the installing user, so launchd could not open the
log and the job sat loaded-but-dead. `install` now chowns the prefix to
`SUDO_USER`; the plist and newsyslog conf stay system files.

## Out of scope

- Linux packaging (the always-on box / Raspberry Pi target) — same tool grows
  a systemd backend later; the layout is deliberately portable.
- Journal compaction/backup (plan 0012 debt), release pruning policy beyond a
  manual `prune`.
- Any change to server code: this is packaging only.
