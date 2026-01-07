# Claude Context

Project-specific context for AI assistants working on this codebase.

## Project Decisions

### Why Matrix over Signal/custom backend?
- Signal requires running their server infrastructure or reverse-engineering their protocol
- Matrix is an open standard with free hosted servers (matrix.org)
- Voice messages sent as standard `m.audio` events are interoperable with Element
- E2E encryption available via Olm/Megolm (deferred to v2)

### Why React Native (bare) over Expo?
- Need native Kotlin module for hardware PTT button capture (`KeyEvent.KEYCODE_PTT`)
- Expo's managed workflow doesn't support custom native modules without ejecting
- Bare workflow provides full control over Android configuration

### Why not real-time streaming?
- Store-and-forward voice messages are simpler and more reliable
- Target devices (Zello handhelds) may have poor connectivity
- Matrix handles message queuing and delivery

## Technical Notes

### Matrix SDK
- `matrix-js-sdk` uses ESM modules - Jest requires `NODE_OPTIONS='--experimental-vm-modules'`
- Login requires `identifier` format for Conduit: `{type: 'm.id.user', user: 'username'}`
- The SDK exports a singleton `MatrixClient` - create via `createClient()`

### Audio
- `react-native-audio-recorder-player` exports a singleton instance, not a class
- Use AAC encoding (`.m4a`) for broad compatibility and small file sizes
- Audio files uploaded to Matrix via `uploadContent()`, then sent as `m.audio` message

### Testing
- Integration tests use Conduit (Rust Matrix server) - lighter than Synapse/Dendrite
- Test users: `alice` and `bob` with password `testpass123`
- Mocks for RN modules in `test/integration/__mocks__/`

## Remaining Work

### Hardware PTT Button (Phase 4)
Create native Kotlin module:
- Location: `android/app/src/main/java/com/wata/PttButtonModule.kt`
- Capture `KeyEvent.KEYCODE_PTT` (code 79) or device-specific codes
- Bridge to JS via `NativeEventEmitter`
- Hook: `usePttButton.ts`

### Push Notifications (Phase 6)
- Set up Firebase project and add `google-services.json`
- Install `@react-native-firebase/app` and `@react-native-firebase/messaging`
- Configure Matrix push rules via `client.setPushRuleEnabled()`

## Commands

```bash
npm start                    # Metro bundler
npm run android              # Build and run on device/emulator
npm run test:integration     # Run against local Matrix server
./gradlew assembleDebug      # Build APK (from android/)
```

## Build Output

Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`
