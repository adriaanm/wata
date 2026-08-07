# 0037 — the mac client becomes an app a parent can use

Status: proposed

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

### 1. `Wata.app` — the bundle

`tools/mac-app.py` (Python, per the >10-lines rule) assembles the bundle
from the `sgo`-built binary: `Info.plist` (bundle id
`com.adriaanm.wata`, `NSMicrophoneUsageDescription`,
`LSMinimumSystemVersion`), the icon, ad-hoc signing. `just mac-app`
builds it; `just mac` keeps working unbundled for development.

The app must run with **no environment at all** — which plan 0036
already delivered, provided something has logged in once. Slice 2 is
what makes that first login possible.

### 2. The login sheet

A native modal: homeserver, username, password, and a "remember me"
checkbox (default on). Shown when the stores hold no usable identity, and
again when the connection reports `ConnAuthRejected` — the case plan 0036
had to store the password for, because the window had nowhere to ask.
With a real sheet, storing the password becomes a **choice** rather than
a requirement; the checkbox is that choice, and unchecking it keeps the
token only.

`Account ▸ Sign Out` forgets both Keychain items and returns to the
sheet, which is also the "switch account" path.

### 3. The menu bar and Preferences

The standard three menus a mac user expects to find (App/Edit/Window),
`About`, `Preferences ⌘,`, `Quit ⌘Q` — today ⌘Q does nothing and the
two-step Back quit is a handset idiom nobody will guess. Preferences
holds the account, the notification mode (slice 4), and the homeserver.

### 4. Notifications

On a message arriving while the app is not frontmost:

- a `UNUserNotificationCenter` banner naming the sender,
- the Dock tile badged with the unplayed count,
- optionally a sound.

Plus the **walkie-talkie toggle** `MSG-NOTIFICATION-DESIGN` asks for:
play the message immediately, or notify quietly. This slice implements
the mac half of that ticket and settles the model; the handset half
(LED, speaker, screen state) stays in the ticket, and the two should
share the mode enum in `wataclient` rather than each inventing one.

The arrival edge already exists and is already logged — `have:` lines
(the unplayed count moving) are exactly the signal to notify on.

### 5. The Devices window — the admin surface

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

## The binding work

All of this is AppKit that `tools/bindgen` does not generate yet: a new
`macui` target in `bindgen.json` for `NSAlert`, `NSMenu`, `NSMenuItem`,
`NSSecureTextField`, `NSButton`, `NSDockTile`, and
`UNUserNotificationCenter`/`UNMutableNotificationContent`.

**The known risk**: notification and sheet completion handlers are
delegate callbacks, and `BINDGEN-TYPED-STRUCTS` records that struct and
`CGFloat` returns from a callback are currently refused. If a handler
needed here is one of those, that ticket becomes a prerequisite rather
than a nice-to-have — worth checking at the top of slice 2, not at the
bottom of slice 4.

## Verification

- Each slice extends `tools/mac-creds-smoke.py` or gets a sibling; the
  chrome is drivable headlessly the same way the stage is (`tree` already
  walks live NSViews, so a sheet's fields are assertable).
- **`just mac-smoke` and `just ci` must stay green throughout.** The
  stage is untouched by design, so a golden that moves means a slice
  reached into the wrong surface — that is the invariant this plan is
  built to keep, and it is worth treating a broken golden here as a
  design error rather than a test to re-bless.
- The bundle gets its own check: launch `Wata.app` with an empty
  environment and reach the contact list.

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
