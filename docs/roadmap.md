# Current status
Prototype

# Future Work

## TUI Audio ✓

Complete. See [docs/voice.md](voice.md) for architecture details.

**Implemented:**
- Recording via PvRecorder (16kHz PCM) with FFmpeg encoding to Ogg Opus
- Playback with format detection (Ogg Opus or M4A) and FFmpeg decoding
- PTT hold-to-record behavior (detects key release via gap in key repeat events)
- Error handling with status bar display and graceful cleanup on exit

## Android Audio Review

The Android app uses native Kotlin with Opus encoding (Ogg Opus format). This is consistent with the TUI implementation.

**Current Implementation:**
- Native `AudioRecord`/`AudioTrack` for recording/playback
- `android-opus-codec` library for Opus encoding/decoding
- Custom Ogg muxer/demuxer for container format
- 16kHz mono, Opus VOIP mode at ~24kbps
- See [docs/voice.md](voice.md) for architecture details

## Requirements for v1

## Fast delivery, even if app is in background

This requires Push Notifications.

**Why deferred:** For the prototype, we assume the app runs continuously. Messages arrive via real-time Matrix sync while the app is open. Push is only needed for background/sleep scenarios.

**When to implement:** If users need notifications when:
- App is backgrounded
- Device is in sleep mode
- Using on regular smartphones (not dedicated PTT devices)

**Prerequisites:**
1. Switch from Conduit to Synapse (Conduit doesn't support push rules)

## Delivery for intermittently connected devices
Basic off-line synch for incoming messages. Outgoing messages only send when device is connected. Otherwise, sending is disabled.

## Disappearing messages
Once listened to, messages are deleted automatically within 24hrs. This is not configurable.

## Group Chat

The prototype focuses on 1:1 voice messaging (DMs). Even for hobbyist usage in v1, groups will be needed, as this is primarily intended for parents to communicate with their kids. Groups can be assumed to be small, fixed at provisioning / admin app.

**Implementation:**
- Support non-DM rooms
- Room list UI changes
- Consider "channels" vs "groups" UX
- Group chat and DM should be distinct to avoid confusion.


# Requirements for v2

## App store ready
Build does not contain any credentials. Provisioning happens through some tbd mechanism (admin part of app, the TUI, some web app,...)

## Invite Security

Currently, all room invites are auto-accepted (trusted family environment). For public deployment or multi-tenant servers, add validation:

**Safety improvements:**
- Only accept invites from users who share a family room with us
- Reject DM invites from unknown users
- Add admin settings to configure invite acceptance policy
- Consider adding blocklist/allowlist for specific users

**Implementation:**
- Check inviter's membership in family rooms before accepting DM invites
- Add user preference for "Auto-accept invites from family members only"
- Log rejected invites for security auditing

## Message History / Offline Support
Full offline support for incoming and outgoing messages. Retention is configurable. Clear errors when outgoing messages fail to send after some short retries.

**Implementation:**
- Local message storage (SQLite or AsyncStorage)
- Sync gap handling
- Offline queue for outgoing messages

# Backlog
## E2E Encryption

**Why deferred:** Adds complexity. Voice messages work without encryption for the prototype.

**When to implement:** If messages need to be private from server operators.

**Implementation:**
- Enable Olm/Megolm crypto in WataClient
- Handle key backup and device verification
- Consider UX for verification on keypad-only devices

# Non-goals

**Element client interop:** We use Matrix for the protocol and server infrastructure, not for interoperability with Element or other Matrix clients. If Element can receive Wata messages, that's a nice side effect, but not a design goal. Wata-to-Wata communication is what matters.

**General messaging app:** This app is not intended as a replacement for Signal, Whatsapp, etc. It should be thought of as a long-range walkie talkie with a few improvements (handling temporary lack of connectivity, ability to form groups). No text, no images. Minimalist, kid-friendly interface.