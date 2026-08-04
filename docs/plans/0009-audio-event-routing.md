# 0009 — one audio-event drain, routed by the shell

Status: done

## The problem

`AudioEvt` is one capacity-16 channel from the audio thread to the UI, but
it had TWO competing consumers: `WataLogic.update` and `SettingsLogic.update`
each ran their own `tryReceive` drain every frame (every applet ticks every
frame), and each DISCARDED the other's events — wata dropped the echo
events, settings dropped `AeRecordingDone`/playback events. Which drain got
an event depended on when the audio goroutine's send landed inside the
frame: an `AeRecordingDone` arriving in the window between wata's drain
(applet 0) and settings' drain (applet 1) was eaten by settings and the
recording silently vanished — no send action, no `EvSendFailed`, nothing.

The window is a few microseconds of `Shell.update` on a quiet box, so the
loss rate was ~1% per send; scheduler preemption between the two drains
stretches it, which is why the `fb-ui-tests` conversation scenario failed
"under load, a different message index each time" (`wait msgs >= N` expiring
with `sendok = N-1, sendfail = 0`, connection healthy). On the device this
is a walkie-talkie that occasionally drops a recording on the floor.

## The decision

One channel, one drain, one dispatcher. `Shell.update` drains `audioEvts`
exactly once per frame — before ticking the applets — and routes each event
by type: the four `AeEcho*` events to `SettingsLogic.onEcho`, everything
else to `WataLogic.onAudioEvent`. The applets' `update` methods keep their
timers/clamping but no longer touch the channel, so there is no window and
no discard: every event reaches its owner regardless of arrival time.

Routing in the shell rather than splitting the channel keeps the audio
thread's mailbox protocol (`audiocmd.scala`) unchanged — the producer side
and the capacity-16/`trySend` delivery semantics stay as documented.

## What changes

- `wata-fb/src/main/scala/shell.scala` — `Shell.update` gains the
  drain-and-route step (`drainAudio`/`routeAudio`/`isEchoEvt`).
- `wata-fb/src/main/scala/applets.scala` — `WataLogic.update` loses
  `drainAudioEvents`; `SettingsLogic.update` loses its drain loop;
  `onAudioEvent`/`onEcho` stay as the per-event transitions the shell calls.

## Verification

- The repro that found it: loop the `alice-convo` uitest phase (13 sends per
  iteration) against a fresh journaled server; before the fix 3 losses in 20
  iterations, after the fix 0 in 150+.
- `just fb-ui-tests` and `just integ` 5x consecutively, at least once under
  heavy load (CPU burners + cold-cache `go build -a std` loop); `just ci`.
- The settings-echo golden byte-checks the echo detail after the change —
  if the old golden encoded the racy outcome it is regenerated with
  `--update` and eyeballed.

## Out of scope

- Splitting the audio mailbox into per-consumer channels (protocol change
  with no added safety once the drain is single).
- Any retry/resend of lost recordings — after this fix nothing is lost to
  drop windows; `trySend`-on-full drops remain the documented delivery
  semantics.
