# 0036 — the mac client remembers who you are

Status: proposed

## The problem

wata-mac is still shaped like a test harness. It takes its homeserver,
user and **password** from `WATA_MAC_HS` / `WATA_MAC_USER` /
`WATA_MAC_PASS` (or argv), logs in from scratch on every launch, and
persists nothing at all:

- **The password is in the environment.** Which means it is in a shell
  history, a `just` invocation, or a process listing. There is no way to
  start the app without putting it there.
- **Every launch is a fresh password login.** The access token the
  client is handed is thrown away at exit, so the credential that gets
  reused is the durable one rather than the disposable one — backwards.
- **The outbox is memory-only.** `MemOutbox`, because the mac stubs
  `FbConfig` out entirely. outbox.scala opens by saying a recording is
  the one thing this client produces that cannot be recreated — and on
  the mac, quitting the app throws away every queued one. A parent who
  records a message with the server unreachable and closes the window
  has silently lost it.

The handset has had all three solved since it had a config file
(`/etc/wata/config.json`, `outbox/eN.msg`). The mac never got the
equivalent because it started life as mac-smoke's subject.

## The decision

**Secrets go in the Keychain; everything else goes in a normal config
file; the outbox goes on disk.** Three stores, split by what each thing
actually is.

### 1. The Keychain holds the token AND the password

A generic-password item per identity, service `wata`, account
`<user>@<homeserver>` — so two accounts, or one account against a
staging and a live server, do not collide.

Two items, not one, because they have different jobs:

| item | written | read |
|------|---------|------|
| `token` | after any successful login | first, on every launch |
| `password` | on first login, when the user supplied one | only after the token is rejected |

Storing the password as well as the token is a deliberate call, and the
windowed app forces it: **there is nowhere to type a password.** The
window is the fb bodies — a keyboard-driven contact list with no text
entry anywhere in it. A token that has expired or been invalidated
server-side would leave the windowed app permanently on its boot screen
with no way for the user to fix it. So the recovery path has to be
unattended, which means the password has to be recoverable, which means
the Keychain. `WATA_MAC_NO_SAVE_PASSWORD=1` opts out for anyone who
would rather re-run with `WATA_MAC_PASS` after an expiry.

The login sequence becomes: Keychain token -> resume; on 401, Keychain
password -> login and store the new token; on 401 again, or nothing
stored, fall back to the environment exactly as today, and store what
works. So the first run is unchanged (`WATA_MAC_USER`/`PASS` still
work) and every run after it needs no environment at all.

### 2. A real config store, not a stub

`~/Library/Application Support/wata/config.json` (override:
`WATA_MAC_CONFIG`), 0600, holding the non-secret half — homeserver,
user, user id, device id — as the mac's `FbConfig`, replacing the stub.
**No token in this file**; that is the whole point of the split.

### 3. A real outbox store

`~/Library/Application Support/wata/outbox/eN.msg`, the same numbered
slots wata-fb uses, so `Outbox.persistent()` is finally true on the mac
and a queued recording survives the window closing.

### The binding: hand-written purego, not bindgen

`Security.framework`'s `SecItem*` are plain C functions taking
`CFDictionary`, and the keys (`kSecClass`, `kSecAttrService`, …) are
`CFStringRef` globals reached by `dlsym`. That is not the ObjC surface
`tools/bindgen` generates and allowlists — it is the same shape as
`nativeui/dispatch.go`, which already reaches `_dispatch_main_q` by
dlsym and calls `dispatch_async_f` through purego. So: a new
hand-written `go-pkgs/mackeychain`, four exported functions (`Set`,
`Get`, `Delete`, `Available`), CoreFoundation objects created and
released inside each call so none of them outlive it.

## What changes

| file | change |
|------|--------|
| `go-pkgs/mackeychain/` | new: purego bindings to SecItemAdd/CopyMatching/Update/Delete + the CF glue; `keychain_test.go` |
| `wata-mac/src/main/scala/keychain.scala` | new: the facade over it, and the token/password login sequence |
| `wata-mac/src/main/scala/config.scala` | new: the real `FbConfig` (replacing the stub) and the file-backed `OutboxStore` |
| `wata-mac/src/main/scala/stubs.scala` | the `FbConfig` stub comes out |
| `wata-mac/src/main/scala/main.scala` | login from the stores, environment as the fallback |
| `tools/mac-smoke.py` | unchanged in what it asserts; it keeps passing env credentials, which stays the first-run path |
| `docs/design/wata-mac.md` | the three stores, and the codesigning gotcha below |

## Verification

- `go test ./...` in `go-pkgs/mackeychain`: round-trips an item under a
  test-only service name and deletes it. Prompt-free, because the item
  is created and read by the same binary in one run.
- A new `just mac-creds-smoke`: a headless run with `WATA_MAC_PASS` set
  writes the token; a SECOND headless run with **no** credentials in the
  environment reaches `ready @alice:localhost` from the Keychain alone;
  a third with a deliberately corrupted token still gets there through
  the stored password. Standalone like mac-smoke, macOS only, not in
  `ci` (it touches the developer's login keychain).
- `just mac-smoke` and `just ci` stay green — the env path is untouched.

## The gotcha this will hit

Keychain ACLs are keyed to the **binary's code signature**. An unsigned
or ad-hoc-signed binary — which is what `just mac-build` produces — gets
a fresh identity on every rebuild, so macOS re-prompts ("wata-mac wants
to use your confidential information") after each build, and "Always
Allow" does not stick across them. This is a development-time annoyance,
not a product one: a signed, bundled app has a stable identity and
prompts once. Signing and bundling are **out of scope** here and belong
with whatever ships the app; this plan notes the cost so the next
session does not go hunting for a bug.

## Out of scope

- Signing, notarizing, or bundling the app (`.app`, Info.plist, a real
  bundle id). The gotcha above is a symptom of not having them; solving
  it is its own piece of work.
- A password/login UI in the window. The Keychain exists here precisely
  so one is not needed; if the app ever grows text entry, the recovery
  path can stop depending on a stored password.
- iCloud Keychain sync (`kSecAttrSynchronizable`), Touch ID gating
  (`kSecAccessControl`), and per-item access groups.
- The handset. Its config file is already the right shape for a device
  with no user accounts and no keychain.
