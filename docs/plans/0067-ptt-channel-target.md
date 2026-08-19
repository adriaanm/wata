# 0067 — where a system-started transmission goes, and saying so

Status: proposed

`[PTT-CHANNEL-TARGET]`

Pressing the talk button on the Dynamic Island or the lock screen
records and sends without any wata screen in front of the user. Today
that always goes to the family conversation, and nothing on screen says
so. The owner's ask (2026-08-19): make the destination a choice, and
show it.

## The problem

iOS gives a PTT app ONE channel system-wide, whose UI is a name, an
image and a talk button. There is no recipient picker and no
participant list to choose from, and joining a different channel needs
the app foregrounded with explicit user interaction — so the target
cannot be chosen at press time. It has to be decided while the app is
open, and the only place it can be SEEN is the channel descriptor's
name, which `setChannelDescriptor` may update while joined.

So the product question is not "how does the user pick at press time"
but "what does the press mean, and does the user know before pressing".

## The decision

Two modes, and the default is the one that needs no thought:

1. **Follow the most recent interaction** (default). An interaction is a
   voice message sent or received IN THE APP; the target is the
   conversation holding the newest one, and the family when there is
   none. This is what a walkie-talkie does — you answer whoever just
   talked to you — and it means the common case (reply to the person
   who just called) needs no setting at all.
2. **Fixed.** A conversation the user picks; every system press goes
   there regardless of traffic.

**The descriptor names the target, always.** Whichever mode is active,
the channel's name is the target conversation's name, so the pill and
the lock screen say where the audio will go before the user presses.
That is the whole reason the modes are safe: mode 1 changes the target
behind the user's back, and the system UI is what makes that visible.

**A target change is not a re-join.** `setChannelDescriptor` mutates
the joined channel — same UUID (derived from the family room id), same
ephemeral push token, no leave. The UUID stays family-derived because
it identifies the DEVICE's one channel, not the target.

## What changes

- `wata-ios/src/main/scala/ptt.scala` — `target(ctx)`: the fixed
  conversation when one is configured and still exists, else the
  conversation with the newest message, else the family.
  `descriptorStep`: when the target's name changes while joined, push
  it through the shell (one call per change, like the service status).
  `talkOn(fromSystem = true)` opens the target rather than the family.
- `go-pkgs/iosshell/ptt.go` — `PTTDescriptor(name)`:
  `setChannelDescriptor` on the joined channel, and the remembered name
  the restoration delegate hands back, so a relaunch restores the
  channel with the name the user last saw rather than a stale one.
- `wata-ios/src/main/scala/config.scala` — `ptt_target`: "" for mode 1,
  a conversation key for mode 2. One more key in the same store as
  `notify_mode`, read at startup, written when the mode changes.
- `wata-ios/src/main/scala/iosui.scala` — the facade line.

**The picker is deliberately NOT in this plan.** wata-ios has no
settings surface at all — the touch keypad is ▲▼◀▶ BACK OK PTT, with no
applet-switch key, so the shared settings applet is unreachable there —
and inventing one is `ADULT-UX-NONHAPPY`'s work, not a side effect of
this. Mode 2 is therefore reachable by config only until that lands,
and the config key is the seam the picker will write. Mode 1, the
default and the interesting half, is fully live.

## How it is verified

- The target rule is pure and lives with the pump's other decisions, so
  `ios-smoke` exercises it in the simulator (unjoined: the rule still
  runs, only the descriptor call is inert), and the smoke's inbound leg
  gives it a real "most recent interaction" to follow — bob's message
  arrives, and the target must move to bob's conversation.
- Device: with the app joined, the pill names the family; after a
  message from one kid, it names that kid; a press then lands in that
  conversation. With `ptt_target` set, the name never moves.

## Out of scope

- The picker UI (above), and any settings surface on iOS.
- Naming the SPEAKER for a replayed message: `pttBeginPlaybackEpisode`
  uses the channel name because it is called from the audio thread,
  which knows a PCM buffer and nothing else. Plumbing the sender down
  is a separate change and does not block this one.
- Per-conversation PTT channels. iOS allows exactly one.
