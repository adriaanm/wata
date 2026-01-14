# Future Work (v2+)

Features deferred to future versions. These are not blockers for v1.

## Push Notifications

**Why deferred:** Target PTT devices run the app continuously. Messages arrive via real-time Matrix sync while the app is open. Push is only needed for background/sleep scenarios.

**When to implement:** If users need notifications when:
- App is backgrounded
- Device is in sleep mode
- Using on regular smartphones (not dedicated PTT devices)

**Prerequisites:**
1. Switch from Conduit to Synapse (Conduit doesn't support push rules)
2. Remove push rules interception from `src/lib/fixed-fetch-api.ts`

**Implementation:**
- Set up Firebase project and add `google-services.json`
- Install `@react-native-firebase/app` and `@react-native-firebase/messaging`
- Configure Matrix push rules via `client.setPushRuleEnabled()`
- Set up Matrix push gateway (sygnal or similar)

## E2E Encryption

**Why deferred:** Adds complexity. Voice messages work without encryption for v1.

**When to implement:** If messages need to be private from server operators.

**Implementation:**
- Enable Olm/Megolm crypto in matrix-js-sdk
- Handle key backup and device verification
- Consider UX for verification on keypad-only devices

## Group Chat

**Why deferred:** v1 focuses on 1:1 voice messaging (DMs).

**When to implement:** If users need walkie-talkie style group channels.

**Implementation:**
- Support non-DM rooms
- Room list UI changes
- Consider "channels" vs "groups" UX

## Message History / Offline Support

**Why deferred:** v1 assumes always-connected devices.

**When to implement:** If devices have intermittent connectivity.

**Implementation:**
- Local message storage (SQLite or AsyncStorage)
- Sync gap handling
- Offline queue for outgoing messages
