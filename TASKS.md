# Tasks

## Active

- [ ] Voice message playback stutters on BQ268 — audio_thread.zig writes period-at-a-time which causes underruns. Echo test is fixed (single large pcm_writei). Need same approach for streaming Ogg playback (decode all frames first, then write).
- [ ] Matrix integration for fbclient — connect to homeserver, send/receive voice messages.

## Backlog

- [ ] Group chat support — currently DM-only. Needed for family use case (parents + kids).
- [ ] Push notifications — requires switching from Conduit to Synapse.
- [ ] Disappearing messages — auto-delete after 24hrs once listened to.
- [ ] Offline message queue — store outgoing when disconnected, send on reconnect.
- [ ] App store build — remove hardcoded credentials, add provisioning flow.
- [ ] Invite security — only accept invites from family room members.
