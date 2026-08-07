# 0037 — the mac client becomes an app a parent can use

Status: done

## The problem

wata-mac works and is not usable. To run it today you set environment
variables, and to do anything administrative — point a handset at a new
wifi network, approve a device someone just unboxed — you run wata-tui
and type commands. A message arriving while the window is behind another
one is invisible: no sound, no badge, nothing. Every one of those is a
thing the person this product is for (a parent, not this repo's author)
cannot be asked to do.

Three asks, one shape: **the app has to carry its own state and speak for
itself**, rather than being driven from a shell.

This also decides the iOS client. wata-mac is the model for it, so
whatever layering answers this question gets answered once.

## The decision

**Two surfaces, kept apart.**

1. **The stage stays exactly as it is** — 160×128, wata-fb's own bodies,
   the 10-key vocabulary, the wataui purity rule, the fb goldens as its
   oracle. Not one element is added to the view algebra.
2. **Everything a host user needs becomes NATIVE CHROME around it** —
   a login sheet, a menu bar, a Preferences window, an admin window,
   notifications, a Dock badge.

The temptation is to grow the view algebra a text field and let the
login screen be another applet. That is the wrong call twice over. The
algebra is the **handset's** contract: whatever is added appears on a
device with no keyboard, where it cannot be used, and every fb golden
becomes a negotiation between two products. And a password field drawn
as 6×8 pixel glyphs on a 160×128 stage is worse than a native sheet on
every axis — no paste, no password manager, no accessibility, no
secure-entry field.

So the split is not a compromise; it is the layering. **The shared
bodies are the product; the chrome is the platform.** iOS reuses the
first and rewrites the second, which is precisely the thing the mac app
exists to prove.

### Why the bundle comes first

Everything here is gated on `Wata.app` existing:

| gated on a bundle | why |
|---|---|
| notifications | `UNUserNotificationCenter` requires a bundle identifier; an unbundled binary cannot post one at all |
| the Keychain not re-prompting | ACLs key to the code signature; a stable signed bundle prompts once instead of after every rebuild (plan 0036's gotcha) |
| the microphone | a bundle needs `NSMicrophoneUsageDescription` or PTT crashes; unbundled, the TCC grant is attributed to the terminal, not to Wata |
| launching at all | a non-technical user double-clicks an icon; they do not run `just mac` |
| the app's own name | menu bar, Dock, and the notification's title all read "Wata" only from `CFBundleName` |

Prior art to follow: `~/g/utv`'s `scripts/bundle-app.sh` — assemble
`Contents/{MacOS,Resources}` by hand, generate `Info.plist`, build the
icon from an `.iconset` with `iconutil` (no Xcode, no `actool`), and
ad-hoc `codesign --force --sign -` with an entitlements file. No Xcode
project, which matches this repo's CLI-only rule and the ios-spike's
architecture (A) finding.

## The slices

Five, in dependency order. Each lands on its own with its own
verification; none is big enough to need its own plan.

### 1. `Wata.app` — the bundle — DONE

`tools/mac-app.py` (Python, per the >10-lines rule) assembles the bundle
from the `sgo`-built binary: `Info.plist` (bundle id
`com.adriaanm.wata`, `NSMicrophoneUsageDescription`,
`LSMinimumSystemVersion`), the icon, ad-hoc signing. `just mac-app`
builds it; `just mac` keeps working unbundled for development.

The app must run with **no environment at all** — which plan 0036
already delivered, provided something has logged in once. Slice 2 is
what makes that first login possible.

Landed. `tools/mac-icon.py` builds the iconset from `wata-icon-src.png`
(pixel-art handset, screen lit with the app's name) — cropped to the
device, scaled with `sips`, composited onto a rounded plate. The trick
that makes it an icon rather than a black square: macOS does not round
app icons the way iOS does, so every source pixel darker than an ink
floor becomes transparent and a near-black plate shows through, which
also swallows the dark pixels inside the device.

`mac-creds-smoke` grew the bundle check — Info.plist keys, a signature
that verifies, and the executable starting from an environment holding
only `HOME` and `PATH`.

One limit worth stating: the ad-hoc signature (`--sign -`) is stable for
a given binary but CHANGES when the binary changes, so a rebuild still
re-prompts the Keychain. Only a Developer ID identity fixes that, and it
stays out of scope.

### 2. The login sheet — DONE

A native modal: homeserver, username, password, and a "remember me"
checkbox (default on). Shown when the stores hold no usable identity, and
again when the connection reports `ConnAuthRejected` — the case plan 0036
had to store the password for, because the window had nowhere to ask.
With a real sheet, storing the password becomes a **choice** rather than
a requirement; the checkbox is that choice, and unchecking it keeps the
token only.

`Account ▸ Sign Out` forgets both Keychain items and returns to the
sheet, which is also the "switch account" path. (Deferred to slice 3 —
it is a menu item, and there is no menu bar yet.)

Landed, and **the named binding risk did not bite**: `NSAlert.runModal`
is a synchronous call returning an `NSInteger`, so the sheet needs no
block, no delegate and no completion handler. The refusal on
struct/`CGFloat` callback returns is still real, but it lives in slice
4's `UNUserNotificationCenter` authorization, not here.

NSAlert, NSButton and NSSecureTextField are not in the bindgen
allowlist, and are reached with raw `objc.Send` — the way
`nativeui/keyview.go` already synthesizes its key view. Eight selectors
in one file did not justify an SDK regeneration; the menu bar and
notifications should tip that decision the other way.

Two structural consequences worth knowing:

- **The session grew an outer loop.** A modal must run on the main
  thread, and the main queue only drains under `runApp`, so the sheet
  runs on the PUMP goroutine — which means the client is now built there
  too, after the credentials are known, rather than on the main thread
  before the pump starts. The loop is: get credentials, run the session,
  and if what ended it was `ConnAuthRejected`, forget the secrets and ask
  again. That last arc is the one the sheet exists for.
- **Headless has no sheet.** There is no runloop to put a modal on, and a
  harness that hung waiting for a click would be worse than one that says
  what it needs — so headless keeps the stdin prompt and the "set
  WATA_MAC_USER" message. `mac-creds-smoke` asserts both answers: bare
  headless asks and exits, bare windowed comes up and STAYS up.

Verification note: this machine has no screen-recording grant, so the
sheet could not be screenshotted. Instead the sheet is split into a
builder and a reader with the modal in between, and `login_test.go`
asserts what a look would have checked — that the password field is
really an `NSSecureTextField` (one that was not would echo the password
to the screen), that all seven controls are in the accessory view, that
the prefills land, that the cursor starts at the first empty field, and
that the answer trims the server but NOT the password.

### 3. The menu bar and Preferences — DONE

The standard three menus a mac user expects to find (App/Edit/Window),
`About`, `Preferences ⌘,`, `Quit ⌘Q` — today ⌘Q does nothing and the
two-step Back quit is a handset idiom nobody will guess. Preferences
holds the account, the notification mode (slice 4), and the homeserver.

Landed as `macshell/menu.go` + `prefs.go`, and it carries slice 2's
deferred `Account ▸ Sign Out`. Three things worth keeping:

- **The Edit menu is the point, not an afterthought.** Without it there
  is no ⌘V, so the login sheet cannot be pasted into and every password
  manager is useless — which undoes most of what slice 2 was for. Its
  items target **nil** so the action walks the responder chain to the
  focused field; macshell never learns the sheet's fields exist.
- **Our two items cannot work that way.** Settings and Sign Out belong to
  the Sgola session (the stores, the client lifecycle), so they push onto
  a command queue the pump polls once a frame — a menu click must not
  block the main thread waiting for the pump. `windowedSession` now has
  three endings: `quit`, `rejected`, `signout`, the last two identical
  except for who decided.
- **Settings shows the account rather than editing it.** The token is
  scoped to the (server, name) pair and the Keychain items are keyed by
  it, so an editable field would pretend a text edit could do what only a
  re-login can. `Sign Out…` returns to the sheet, prefilled — which is
  the switch-account path too.

The notification mode stays out until slice 4, where the setting will
exist at all. The homeserver is shown, not editable, for the reason
above.

Verification note, same shape as slice 2's: AppKit throws on
`setMainMenu:` off the main thread and refuses to instantiate an
`NSWindow` there at all, so both are split builder-from-installer and
`menu_test.go` asserts the built structure from a test goroutine. What is
NOT covered by a test is the sign-out ARC end to end — the headless smoke
has no menus, and the windowed one cannot be clicked. Its second half
(forget the secrets, return to the sheet) is the same code
`mac-creds-smoke` case 3 already drives through the rejected-token path;
only the trigger differs.

### 4. Notifications — DONE

On a message arriving while the app is not frontmost:

- a `UNUserNotificationCenter` banner naming the sender,
- the Dock tile badged with the unplayed count,
- plus the **walkie-talkie toggle** `MSG-NOTIFICATION-DESIGN` asks for:
  play the message immediately, or notify quietly.

Landed, and the model is settled where the handset half can reuse it:
`wataclient/src/main/scala/notify.scala` (`NotifyMode`, `Arrival`,
`Notify.step`), with a pinned byte oracle in `just ci`
(`wata-fb notifytest`) and `just mac-notify-smoke` for the mac end to end.
Four things worth keeping:

- **The named binding risk did not bite.** `tools/bindgen` generates
  `UNUserNotificationCenter`, `UNMutableNotificationContent`,
  `UNNotificationRequest` and `NSDockTile` with no refusal on anything
  needed. The two calls that take handlers take **outgoing** blocks, which
  the emitter has always mapped; `BINDGEN-TYPED-STRUCTS` gates struct and
  `CGFloat` *returns from a callback*, and neither handler returns anything.
  "The handler is a block" and "the handler is a refused shape" read alike
  and are different questions — the refused axis is the return.
- **The arrival edge is the unplayed count rising**, off the snapshot the
  frame already reads. No second channel through the sync engine, so the
  banner, the badge and the contact list's own badge cannot disagree.
- **Priming is once per session, not once per conversation.** The obvious
  rule — never announce a conversation you are seeing for the first time —
  silences exactly the arrival most worth having, because a DM ROOM IS
  CREATED BY ITS FIRST MESSAGE. Caught by the smoke, not by review.
- **Auto-play is the OK path, not a second one**: the same `ActPlay` plus
  `WataLogic.withPlaying`, so the existing `AePlaybackDone` arm sends the
  read receipt and an auto-played message really becomes played. The smoke
  proves that through the NEXT arrival's badge reading 1 rather than 2.

The one gap this slice hit is upstream, not here: a tuple component of
interface type erases to Go's `any` and loses its type assertion on
assignment (`TUPLE-REF-COMPONENT-ASSIGN`, filed; workaround is a named case
class, which reads better anyway).

### 5. The Devices window — the admin surface — DONE

What `wata-tui` can do and the app cannot, as a window:

| tui | window |
|---|---|
| `wifi <user>` | pick a handset, see its scan results as a list |
| `join <net#>` + PSK prompt | select a network, type the PSK in a secure field |
| `wifi off <user> [min]` | a "test cellular fallback" action |
| the `/admin` enrolment API | pending devices, with Approve/Deny |

Every one of these is a request through the server's command mailbox
(plan 0020/0031) or the admin listener (plan 0027) — the transport work
is done, and this slice is presentation over it.

Landed as `macshell/devices.go` + `wata-mac`'s `devices.scala`, on ⌘D,
gated by `just mac-devices-smoke` and `devices_test.go`. Four things
worth keeping:

- **The chrome does no I/O and the pump does not wait.** A button reads
  its controls and pushes a command; the work runs on a forked goroutine
  beside the audio thread, because a scan takes up to a minute and a pump
  that waited would freeze the stage. The relay is `trySend`, so a busy
  worker cannot stall a frame.
- **The password never becomes a string anything might print.** It stays
  in the `NSSecureTextField`; the session takes it with `TakePSK`, which
  reads and clears in one call, and it reaches the request body only. The
  log line says `psk=<n> chars`. The smoke's last check is a grep of every
  printed line and the whole server log for the password it typed.
- **The convenience factories are a trap.** `+[NSButton buttonWithTitle:
  target:action:]` generates cleanly and blocks forever off the main
  thread — the factories go through the appearance machinery, which waits
  on the main runloop. A generated signature says nothing about which
  thread may call it. `-alloc`/`-initWithFrame:` is fine.
- **`NSSecureTextField` is unbindable and does not need binding**: it
  declares no members of its own, so the generator refuses the class
  outright and every message it answers already belongs to `NSTextField`
  or `NSControl`.

## The binding work

How it settled, once each slice actually needed the calls:

- **The AppKit chrome is mostly raw `objc.Send`.** `NSAlert`, `NSMenu`
  and `NSMenuItem` are a handful of selectors in three files, all taking
  objects or nothing, and generating a target for them would have been
  more machinery than the machinery it replaced. Slice 5 moved `NSButton`
  and `NSPopUpButton` onto the allowlist, because the Devices window reads
  its lists back rather than only writing them. `NSSecureTextField` stays
  raw of necessity: it declares no members of its own, so there is nothing
  to generate.
- **The notification surface IS generated**, because it is a real API with
  enums and a completion-handler contract rather than a few setters:
  `usernotifications` is its own bindgen target and `NSDockTile`/`NSBundle`
  joined `appkit`.
- **The known risk did not materialize.** `BINDGEN-TYPED-STRUCTS` refuses a
  struct or `CGFloat` RETURN from a callback; the handlers here are outgoing
  blocks returning void, which the emitter has always mapped. Nothing in
  this plan was blocked on that ticket.

## Verification

- Each slice extends `tools/mac-creds-smoke.py` or gets a sibling
  (slice 5's is `tools/mac-devices-smoke.py`); the
  chrome is drivable headlessly the same way the stage is (`tree` already
  walks live NSViews, so a sheet's fields are assertable).
- **`just mac-smoke` and `just ci` must stay green throughout.** The
  stage is untouched by design, so a golden that moves means a slice
  reached into the wrong surface — that is the invariant this plan is
  built to keep, and it is worth treating a broken golden here as a
  design error rather than a test to re-bless.
- The bundle gets its own check: launch `Wata.app` with an empty
  environment and reach the contact list.
- Slice 4's oracle is the DECISION, not the pixels: the pump prints
  `notify: play|banner|suppressed "<title>" "<body>" badge=<n>` per
  arrival, and `just mac-notify-smoke` asserts those lines. Whether macOS
  drew a banner is macOS's business; whether this client would have asked
  for one, naming whom, at what count, is entirely ours. The shared edge
  logic is pure, so it also gets a byte oracle inside `just ci`
  (`wata-fb notifytest`).

## Out of scope

- Developer ID signing, notarization, and distribution (a `.dmg`, an
  update mechanism). Ad-hoc signing is enough to run locally and to stop
  the Keychain re-prompting; shipping to other people's Macs is its own
  piece of work.
- Any change to the view algebra, the fb bodies, or the handset.
- iOS itself. This plan settles the layering it will copy; the UIKit
  chrome is `IOS-CLIENT-ASSEMBLY`.
- A settings surface beyond the essentials above — this is a family
  walkie-talkie, not a client with a preferences tree.
