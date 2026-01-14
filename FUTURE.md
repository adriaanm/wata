# Current status
Prototype

# Future Work 

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
2. Remove push rules interception from `src/lib/fixed-fetch-api.ts`

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
- Enable Olm/Megolm crypto in matrix-js-sdk
- Handle key backup and device verification
- Consider UX for verification on keypad-only devices

# Never
This app is not intended as a replacement for Signal, Whatsapp,... It should be thought of as a long-range walkie talkie with a few other improvements (mostly about handling temporary lack of connectivity, and ability to form groups). No text, no images,... Minimalist, kid-friendly interface.