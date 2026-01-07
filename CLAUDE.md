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

## Target Device

ABBREE Zello handheld (or similar Android PTT devices):
- **Screen**: 1.77" (tiny), non-touch
- **Navigation**: D-pad only (Up/Down arrows, Select, Menu, Exit)
- **Input**: No keyboard, hardware buttons only

### Physical Keys
Front keypad (2x3 grid):
```
[Menu]  [Up]   [Exit]
 [P1]  [Down]  [P2]
```

| Key | Android KeyEvent | App Function |
|-----|------------------|--------------|
| PTT (side) | `KEYCODE_PTT` (79) | Hold to record voice message |
| Up/Down | `KEYCODE_DPAD_UP/DOWN` | Navigate lists |
| Menu (green) | `KEYCODE_MENU` | Open context menu |
| Exit (red) | `KEYCODE_BACK` | Go back |
| P1 | `KEYCODE_DPAD_CENTER` or device-specific | Select/confirm |
| P2 | Device-specific | TBD |
| Side Key 1/2 | Device-specific | TBD |

Note: Exact key codes may vary by device - test on actual hardware.

### UI Constraints
- Large text and buttons (readable on 1.77" screen)
- High contrast colors
- D-pad focusable elements (no touch gestures)
- Simple, linear navigation (no complex menus)
- Visual feedback for PTT recording state

## Technical Notes

### Matrix SDK
- Requires Node.js 22+ (uses `Promise.withResolvers`)
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

# Code quality
npm run check                # Run all checks (typecheck + lint + format)
npm run lint                 # ESLint
npm run lint:fix             # ESLint with auto-fix
npm run format               # Prettier format
npm run format:check         # Prettier check (CI)
npm run typecheck            # TypeScript type checking
```

Before committing, run `npm run check` to verify code quality.

## Build Output

Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`
