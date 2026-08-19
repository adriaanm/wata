# 0066 — while a PTT channel is joined, every sound is an episode

Status: proposed

`[IOS-JOINED-AUDIO]`

Plan 0065 tier 3 joined the family channel on the phone and proved
transmit and receive on hardware. It also, unnoticed, took the app's
audio session away from it — and the app still behaves as though it
owns one. The result is that wata-ios has no working audio at all
except during a PushToTalk episode: opening the app and playing a
message is silence.

## The problem

Three device logs (2026-08-19) say the same thing from three angles.

At launch, joined by channel restoration, the engine cannot start:

```
macaudio: a PushToTalk channel is joined — the framework owns audio session activation
macaudio: joined — category set, setActive left to the framework
macaudio: SetupMixer failed, audio is unavailable:
  AVAudioEngine start: … error 1701737535        <- 'ent?'
```

After an episode ends, it cannot start either — same wall, different
code:

```
macaudio: PushToTalk released the audio session
macaudio: joined — category set, setActive left to the framework
macaudio: engine restart after a session change: … error 2003329396   <- 'what'
```

And during an episode everything works, because the framework
activated the session:

```
ptt: transmit begin (system)
macaudio: PushToTalk owns the audio session
macaudio: the engine was built on a retry — audio is available again
macaudio: session … rate=48000 outCh=2 inCh=1 engineInRate=48000
ptt: talk on system
```

`'ent?'` is `AVAudioSessionErrorCodeMissingEntitlement`; `'what'` is
`kAudioUnitErr_CannotDoInCurrentContext`. Owner-confirmed symptom:
with the app open and the channel joined, tapping a message plays
nothing.

**`session_ios.go` rests on an assumption that is false.** It states
that while joined the app may still set a category, and that the
engine's own start activates the session on the app's behalf "which is
AVFAudio's call and not ours". Both halves are refused on hardware.
The mistake is understandable — self-activation *did* work before the
join existed, which read as evidence that it was allowed.

## What Apple documents

[Creating a Push to Talk app][doc] and Apple DTS in [forum thread
804205][dts], which is the same failure from the other end (background
transmit noise) and is answered with the rule:

- **"The activation of your audio session MUST be triggered by the
  PushToTalk system, NOT your app."** And why our earlier build looked
  fine: *"`setActive` will work in the foreground (because the
  foreground app always has control over the audio system) but will
  NOT work correctly in the background."* Self-activation is not a
  thing that works and occasionally fails; it is a thing that fails
  wherever it matters.
- **Do not use `AVAudioRecorder`/`AVAudioPlayer`** — they activate
  sessions themselves. `AVAudioEngine` is the sanctioned API. We are
  already there.
- **To play audio while joined, ask the framework for an episode**:
  `setActiveRemoteParticipant(participant, channelUUID:)`, start
  playback in `channelManager(_:didActivate:)`, and
  `setActiveRemoteParticipant(nil, …)` when finished. There is no
  other sanctioned way for a joined app to make a sound.
- **Half-duplex ordering**: transmitting and want to play? Call
  `stopTransmitting()`, set the participant in `didDeactivate`, play
  in the *next* `didActivate`.
- **`didActivate` does not fire when the session is already active.**
  The receive rewrite learned this on hardware (an episode is one
  raised speaker, not one push); this is its documented cause.
- Two APIs we ignore and should not: `setServiceStatus` (the system
  UI's own view of whether our service is reachable — the honest home
  for what the app's blinking `NET` means) and `setTransmissionMode`
  (half-duplex is both the default and what a walkie-talkie wants,
  and saying so in code beats inheriting it).

[doc]: https://developer.apple.com/documentation/pushtotalk/creating-a-push-to-talk-app
[dts]: https://developer.apple.com/forums/thread/804205

## The decision

**While a channel is joined, the app never touches the audio session,
and every sound it makes is an episode.**

Three rules, replacing `session_ios.go`'s three-way `sessionRule`:

1. **Joined ⇒ hands off.** No `setCategory:`, no `setActive:`, not
   even outside an episode. The category exists to put our own
   playback on the speaker; a framework session is already configured
   for speaker use, so the reason to set it is gone with the ownership.
   Not-joined keeps today's behaviour exactly (category + setActive +
   engine start), which is what the mac and the simulator run.
2. **Build the engine at launch; start it on the handover.** The graph
   (alloc, attach, connect, `prepareInput`) is session-independent and
   is built once, as now. `start` moves to `didActivateAudioSession`
   and `stop` to `didDeactivateAudioSession`. A joined app with no
   live episode has no running engine, and that is a normal state, not
   `audio is unavailable` — the launch log must stop claiming a defect
   where there is none.
3. **Playback while joined goes through an episode.** A play request
   raises the speaker (`setActiveRemoteParticipant` with the sender's
   name), waits for the handover, plays, and lowers the speaker. The
   push-woken path already has every piece of this; what changes is
   that an in-app play uses it too.

**Where rule 3 is implemented is the one real design choice**, and it
goes in macaudio, not in the pump. The alternative — intercept the
applet's play command in `wata-ios/ptt.scala` and route it through
`PlayQ` — puts a platform rule inside portable logic and leaves two
paths to playback that must stay in step. Instead `macaudio.PlayMessage`
requests the session it needs, through a hook `iosshell` registers at
startup (`macaudio.PTTSession = …`; iosshell already imports macaudio,
so the dependency cannot run the other way). The audio thread, the
applets and `wataclient` change not at all: they ask for a sound and
get one, on whatever session iOS says they may have.

The push path keeps raising its own speaker from the push result — it
must, because the answer to `incomingPushResult` *is* the raise — so
the hook finds a live episode and simply plays.

## What changes

- `go-pkgs/macaudio/session_ios.go` — `sessionRule` loses `ruleJoined`
  as a state that touches anything; the joined-but-idle case becomes
  "hands off". `startEngine` splits into build and start.
  `reclaimSession` stops setting a category and stops starting the
  engine while joined; on deactivate it stops the engine.
  The header's false paragraph is replaced with the rule and the two
  error codes that prove it.
- `go-pkgs/macaudio/engine.go` — `SetupMixer` builds the graph without
  requiring a start; a joined-and-idle launch reports the state rather
  than a failure. `PlayMessage`/`OpenCapture` ask the hook for a
  session before refusing.
- `go-pkgs/iosshell/ptt.go` — a speaker raise the app can initiate
  (`setActiveRemoteParticipant` with a named `PTParticipant`, opening
  an episode the existing `PTTSpeakerStopped` closes), registered as
  macaudio's hook; `setServiceStatus` mirrored from the session state
  the pump already prints; `setTransmissionMode` stated as half-duplex.
  Plus the diagnosis this investigation lacked: a transmission that
  ends before its handover arrives recorded nothing, and must say so
  rather than leaving a silent gap in the log.
- `wata-ios/src/main/scala/ptt.scala` — the service-status feed (one
  line per change, off `NetStatus`); no change to the play arc.
- `docs/design/wata-ios.md`, `docs/design/wata-mac.md` — the session
  rules, replacing the paragraph the hardware falsified.

## How it is verified

The framework does not exist in a simulator, so the split is the same
as tier 3's: what is gateable is gated, the rest is device-only and
says so.

- **Gateable here.** `just ios-smoke` and `just ios-enroll-smoke` run
  UNJOINED (no framework at all), so they pin rule 1's other half:
  the not-joined path still sets its category, activates, starts, and
  plays — i.e. the fix does not regress the simulator, the mac, or the
  handset. `go-pkgs/macaudio`'s own tests keep covering the codec and
  framing. The rule decision itself moves into a pure function with a
  table test that compiles everywhere, so "joined and idle touches
  nothing" is an assertion, not a comment.
- **Device legs**, in order: (1) open the app joined and tap a message
  — it plays, with the sender's name on the system UI, and the log
  shows the raise, the handover and the lowering; (2) the launch log
  no longer claims `audio is unavailable`; (3) a received burst still
  plays (plan 0065's arc, unregressed); (4) transmit from the Dynamic
  Island still records, and playback after it works — the reclaim that
  used to fail with `'what'`; (5) a short press that ends before the
  handover logs its own diagnosis.

## Out of scope

- Leaving the channel to regain the app's own session. A joined
  walkie-talkie is the product's normal state, not a mode to escape.
- The cold-launch race itself: a press that ends before iOS has
  launched the app cannot record, and no app-side change fixes it.
  Only its diagnosis is in scope.
- `ADULT-UX-NONHAPPY`'s presentation work, including what the system
  UI shows for a replayed message.
