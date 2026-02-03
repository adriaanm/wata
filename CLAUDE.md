# Claude Context

## Working with Claude

**Commit Policy:** Commit coherent changes as soon as they are complete. Don't batch unrelated changes or wait to commit until the end of a session.
**Background Processes** Prefer tmux (remember we are using zsh).
**Planning** Planning docs go in docs/planning. Once plan is complete, distil learnings, architecture, guidelines and move to docs/ as a guide.

## Project Status
We are currently developing a prototype with three frontend targets:
- **Android** - Primary target for PTT handhelds using Kotlin (src/android)
- **TUI** (Terminal UI) - macOS/Linux CLI interface
- **Web app** - Browser-based companion interface

v1 will target hobbyist usage (may require custom builds, but no compromises on security and core walkie-talkie functionality). v2 should be ready for deployment to app stores.

## Documentation

| Document | Description |
|----------|-------------|
| [docs/quickstart.md](docs/quickstart.md) | Getting started guide |
| [docs/android-development.md](docs/android-development.md) | Native Android Kotlin development guide |
| [docs/device-automation.md](docs/device-automation.md) | Physical device testing workflow |
| [docs/tui-architecture.md](docs/tui-architecture.md) | TUI frontend design |
| [docs/family-model.md](docs/family-model.md) | Family room architecture and Matrix mapping |
| [docs/matrix-servers.md](docs/matrix-servers.md) | Matrix server comparison |
| [docs/testing.md](docs/testing.md) | Test strategy and infrastructure |
| [docs/voice.md](docs/voice.md) | Audio recording/encoding/playback architecture |
| [docs/roadmap.md](docs/roadmap.md) | Future work and TODOs |

## Project Structure

This is a mixed repo. For android, we use Gradle, sources in src/android.
Everything TypeScript is managed by pnpm.

### Workspaces

| Workspace | Package Name | Description |
|-----------|--------------|-------------|
| `src/shared` | `@wata/shared` | Matrix SDK integration, shared hooks, utilities |
| `src/tui` | `@wata/tui` | Terminal UI (Ink) |
| `src/web` | `@wata/web` | Web app (Vite) |

### Build Tools

| Platform | Bundler/Build tool | Compiler |
|----------|---------|------------|
| Android | Gradle | Kotlin compiler |
| TUI | None (tsx) | TypeScript via tsx |
| Web | Vite | esbuild |

### Path Aliases

All workspaces use `@shared/*` to import from the shared package:
```typescript
import { MatrixService } from '@shared/services/MatrixService';
```

## Project Decisions

### Why Matrix over Signal/custom backend?
- Matrix is an open standard with free hosted servers (matrix.org)
- Voice messages sent as standard `m.audio` events are interoperable with Element
- E2E encryption available via Olm/Megolm (deferred to v1, see `docs/roadmap.md`)

### Why native Kotlin over React Native?
- Direct hardware access for PTT button capture (`KeyEvent.KEYCODE_PTT`)
- Better Android 8 compatibility without Metro/Babel complexity
- Smaller APK size and faster startup
- No JavaScript bridge overhead for Matrix protocol handling

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
- Credentials hardcoded in `src/shared/config/matrix.ts` for build-time configuration
- App auto-logs in on startup using configured credentials
- Modify `src/shared/config/matrix.ts` before building to change:
  - Homeserver URL (default: `http://localhost:8008` for local Conduit)
  - Username (default: `alice`)
  - Password (default: `testpass123`)
- Future: Replace with QR code provisioning or companion config app

### Matrix SDK
- Requires Node.js 22+ (uses `Promise.withResolvers`)
- `matrix-js-sdk` uses ESM modules - Jest requires `NODE_OPTIONS='--experimental-vm-modules'`
- Login requires `identifier` format for Conduit: `{type: 'm.id.user', user: 'username'}`
- The SDK exports a singleton `MatrixClient` - create via `createClient()`

**Conduit URL Normalization:**
- Conduit requires trailing slash on `/_matrix/client/v3/pushrules/` but the SDK omits it
- We normalize URLs in `src/shared/lib/fixed-fetch-api.ts` to add the trailing slash
- Push rules work correctly - Conduit returns proper rules and the SDK syncs successfully

### Audio
- Android uses native `AudioRecord`/`AudioTrack` with Opus encoding
- TUI uses PvRecorder with FFmpeg encoding to Ogg Opus
- Audio files uploaded to Matrix via `uploadContent()`, then sent as `m.audio` message
- See [docs/voice.md](docs/voice.md) for architecture details

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

### Push Notifications (v1)
**Deferred to v1** - not needed for active PTT use.

Why it's not urgent:
- Messages arrive via real-time sync while app is open
- Target PTT devices run the app continuously in foreground
- Push is only needed for background/sleep scenarios

See `docs/roadmap.md` for implementation notes when ready.

## Development Workflow

### Device Testing with Local Conduit

The app is configured to use `http://localhost:8008` by default, which works for both emulators and physical devices.

**Android Emulator:**
- Works out of the box (emulator has built-in localhost forwarding)
- Just run `pnpm dev:server` and `pnpm android`

**Physical Device:**
- Requires ADB reverse proxy for localhost forwarding
- Connect device via USB or wireless ADB
- Run `pnpm dev:forward` to set up port forwarding
- The forwarding persists until device disconnects

**No IP lookup required!** The ADB reverse proxy makes `localhost:8008` on the device map to `localhost:8008` on your host machine.

**Fallback (if ADB reverse fails):**
If you can't use ADB reverse proxy, use manual IP configuration:
```bash
# 1. Find your host machine's IP
pnpm dev:ip

# 2. Update src/shared/config/matrix.ts with the IP shown
homeserverUrl: 'http://192.168.x.x:8008'
```

### Commands Reference

```bash
# Android (native Kotlin)
pnpm android                  # Build and run on device/emulator via Gradle
cd src/android && ./gradlew assembleDebug    # Build APK only

# TUI (Terminal UI)
pnpm tui                      # Run TUI
pnpm tui:dev                  # Run TUI with watch mode

# Web
pnpm web                      # Start Vite dev server (port 3000)
pnpm web:build                # Production build
pnpm web:preview              # Preview production build

# Development helpers
pnpm dev:server               # Start Conduit Matrix server (Docker)
pnpm dev:forward              # Set up ADB port forwarding (physical devices)
pnpm dev:ip                   # Show local IP (fallback if adb reverse fails)

# Testing
pnpm test:integration         # Run against local Matrix server
pnpm test:integration:setup   # Alias for dev:server

# Code quality
pnpm check                    # Run all checks (typecheck + lint + format)
pnpm lint                     # ESLint
pnpm lint:fix                 # ESLint with auto-fix
pnpm format                   # Prettier format
pnpm format:check             # Prettier check (CI)
pnpm typecheck                # TypeScript type checking (all workspaces)

# Production build
cd android && ./gradlew assembleDebug  # Build APK
```

Before committing, run `pnpm check` to verify code quality.

## Build Output

| Platform | Output |
|----------|--------|
| Android | `android/app/build/outputs/apk/debug/app-debug.apk` |
| Web | `src/web/dist/` |
| TUI | Runs directly via Node.js |
