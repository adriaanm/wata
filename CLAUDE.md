# Claude Context

## Project Status
We are currently developing a prototype. A simple android app (primarily) and a TUI for Mac. v1 will target hobbyist usage (may require custom builds, but no compromises on security and core walkie-talkie functionality). v2 should be ready for deployment to app stores.

## Documentation

| Document | Description |
|----------|-------------|
| [docs/quickstart.md](docs/quickstart.md) | Getting started guide |
| [docs/device-automation.md](docs/device-automation.md) | Physical device testing workflow |
| [docs/tui-architecture.md](docs/tui-architecture.md) | TUI frontend design |
| [docs/matrix-servers.md](docs/matrix-servers.md) | Matrix server comparison |
| [docs/testing.md](docs/testing.md) | Test strategy and infrastructure |
| [docs/roadmap.md](docs/roadmap.md) | Future work and TODOs |

## Project Decisions

### Why Matrix over Signal/custom backend?
- Signal requires running their server infrastructure or reverse-engineering their protocol
- Matrix is an open standard with free hosted servers (matrix.org)
- Voice messages sent as standard `m.audio` events are interoperable with Element
- E2E encryption available via Olm/Megolm (deferred to v1, see `docs/roadmap.md`)

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
- It should be kid-friendly, it should feel like a walkie-talkie (and nothing more).

## Technical Notes

### Authentication
- No login screen (device has no keyboard)
- Credentials hardcoded in `src/config/matrix.ts` for build-time configuration
- App auto-logs in on startup using configured credentials
- Modify `src/config/matrix.ts` before building to change:
  - Homeserver URL (default: `http://localhost:8008` for local Conduit)
  - Username (default: `alice`)
  - Password (default: `testpass123`)
- Future: Replace with QR code provisioning or companion config app

### Matrix SDK
- Requires Node.js 22+ (uses `Promise.withResolvers`)
- `matrix-js-sdk` uses ESM modules - Jest requires `NODE_OPTIONS='--experimental-vm-modules'`
- Login requires `identifier` format for Conduit: `{type: 'm.id.user', user: 'username'}`
- The SDK exports a singleton `MatrixClient` - create via `createClient()`
- Requires crypto polyfills for React Native:
  - `react-native-get-random-values` for `crypto.getRandomValues()`
  - `buffer` for Node.js Buffer API
  - Both imported in `index.js` before app initialization

**Conduit URL Normalization:**
- Conduit requires trailing slash on `/_matrix/client/v3/pushrules/` but the SDK omits it
- We normalize URLs in `src/shared/lib/fixed-fetch-api.ts` to add the trailing slash
- Push rules work correctly - Conduit returns proper rules and the SDK syncs successfully

### Audio
- `react-native-audio-recorder-player` exports a singleton instance, not a class
- Use AAC encoding (`.m4a`) for broad compatibility and small file sizes
- Audio files uploaded to Matrix via `uploadContent()`, then sent as `m.audio` message

### Testing
- Integration tests use Conduit (Rust Matrix server) - lighter than Synapse/Dendrite
- Test users: `alice` and `bob` with password `testpass123`
- Mocks for RN modules in `test/integration/__mocks__/`

### Logging (TUI)
**CRITICAL**: Never use `console.log/warn/error` in TUI code — it corrupts the Ink UI.

```typescript
// ❌ DON'T
console.log('Something happened');

// ✅ DO - See docs/coding-rules.md for full guide
import { LogService } from './services/LogService.js';
LogService.getInstance().addEntry('log', 'Something happened');
```

See [docs/coding-rules.md](docs/coding-rules.md) for complete logging guidelines.

## Remaining Work

### Hardware PTT Button (Phase 4)
Create native Kotlin module:
- Location: `android/app/src/main/java/com/wata/PttButtonModule.kt`
- Capture `KeyEvent.KEYCODE_PTT` (code 79) or device-specific codes
- Bridge to JS via `NativeEventEmitter`
- Hook: `usePttButton.ts`

### Push Notifications (v1)
**Deferred to v1** - not needed for active PTT use.

Why it's not urgent:
- Messages arrive via real-time sync while app is open
- Target PTT devices run the app continuously in foreground
- Push is only needed for background/sleep scenarios

See `docs/roadmap.md` for implementation notes when ready.

## Development Workflow

### Fast Iteration with Metro Bundler

For rapid development with hot reloading:

```bash
# Terminal 1: Start Conduit server (one-time setup)
npm run dev:server

# Terminal 2: Set up port forwarding (for physical devices, run after connecting)
npm run dev:forward

# Terminal 3: Start Metro bundler
npm start

# Terminal 4: Deploy to device/emulator (one-time per session)
npm run android

# Now edit code and see changes instantly via hot reload!
```

### Device Testing with Local Conduit

The app is configured to use `http://localhost:8008` by default, which works for both emulators and physical devices.

**Android Emulator:**
- Works out of the box (emulator has built-in localhost forwarding)
- Just run `npm run dev:server` and `npm run android`

**Physical Device:**
- Requires ADB reverse proxy for localhost forwarding
- Connect device via USB or wireless ADB
- Run `npm run dev:forward` to set up port forwarding
- The forwarding persists until device disconnects

**No IP lookup required!** The ADB reverse proxy makes `localhost:8008` on the device map to `localhost:8008` on your host machine.

**Fallback (if ADB reverse fails):**
If you can't use ADB reverse proxy, use manual IP configuration:
```bash
# 1. Find your host machine's IP
npm run dev:ip

# 2. Update src/config/matrix.ts with the IP shown
homeserverUrl: 'http://192.168.x.x:8008'
```

### Commands Reference

```bash
# Development
npm start                    # Metro bundler (for hot reload)
npm run android              # Build and run on device/emulator
npm run dev:server           # Start Conduit Matrix server (Docker)
npm run dev:forward          # Set up ADB port forwarding (physical devices)
npm run dev:ip               # Show local IP (fallback if adb reverse fails)

# Testing
npm run test:integration     # Run against local Matrix server
npm run test:integration:setup  # Alias for dev:server

# Code quality
npm run check                # Run all checks (typecheck + lint + format)
npm run lint                 # ESLint
npm run lint:fix             # ESLint with auto-fix
npm run format               # Prettier format
npm run format:check         # Prettier check (CI)
npm run typecheck            # TypeScript type checking

# Production build
cd android && ./gradlew assembleDebug  # Build APK
```

Before committing, run `npm run check` to verify code quality.

## Build Output

Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`
