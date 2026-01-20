# Wata

A minimalist push-to-talk voice messaging app for Android (and macOS Terminal), built on the Matrix protocol. "Wata" is short for walkie-talkie, in case it wasn't obvious (zie ook: "watte"!?).

## Disclaimer
Greetings! First, some words of discouragement for those actually reading the README.

I'm building this for my personal use, as well as to learn some new skills by doing some new stuff. I'm sharing the source because why not; I didn't even write it myself.

I make no claims regarding its quality or utility, nor do I make any commitments regarding fixing bugs or accepting changes.

Have fun with it!

## Frontends

- **Android** (`src/`) - React Native app for PTT handhelds
- **TUI** (`tui/`) - Terminal UI for macOS (see `tui/README.md`)

## Goals

Wata is a walkie-talkie style app designed for:

- **Simplicity** - Large buttons, minimal UI, kid-friendly
- **Hardware PTT** - Works with cheap Zello Android handhelds (Android 8+)
- **No backend** - Uses existing Matrix infrastructure (matrix.org)
- **Interoperability** - Voice messages work with Element and other Matrix clients

## Architecture

```
┌────────────────────────┐  ┌────────────────────────┐
│   Android Frontend     │  │    TUI Frontend        │
│   (React Native)       │  │    (Ink/Terminal)      │
│                        │  │                        │
│ - ContactListScreen    │  │ - ContactListView      │
│ - ChatScreen           │  │ - ChatView             │
│ - RN Audio/Keychain    │  │ - macOS Audio/Keychain │
└───────────┬────────────┘  └───────────┬────────────┘
            │                           │
            └───────────┬───────────────┘
                        ▼
            ┌───────────────────────┐
            │   SHARED BACKEND      │
            │   (src/)              │
            │                       │
            │ - MatrixService       │
            │ - Matrix hooks        │
            │ - Types/Interfaces    │
            └───────────┬───────────┘
                        │
                        ▼
            ┌───────────────────────┐
            │  Matrix Homeserver    │
            │  (matrix.org)         │
            └───────────────────────┘
```

**Both frontends share the same Matrix backend code** (`src/services/MatrixService.ts`, hooks, types), ensuring identical protocol behavior. Only UI and platform-specific services (audio, credentials) differ.

### Key Files

```
src/
├── App.tsx                    # Navigation setup
├── config/
│   └── matrix.ts              # Matrix credentials (configure here)
├── services/
│   ├── MatrixService.ts       # Matrix SDK wrapper
│   └── AudioService.ts        # Recording/playback
├── screens/
│   ├── ContactListScreen.tsx  # List of DM rooms
│   └── ChatScreen.tsx         # Voice message thread + PTT
└── hooks/
    ├── useMatrix.ts           # Matrix client state
    └── useAudioRecorder.ts    # Recording state
```

## Tech Choices

| Choice | Rationale |
|--------|-----------|
| React Native (bare) | Cross-platform potential, large ecosystem |
| TypeScript | Type safety, better tooling |
| matrix-js-sdk | Official SDK, well-maintained |
| matrix.org | Free, federated, no backend to maintain |
| react-native-audio-recorder-player | Mature, supports AAC encoding |
| react-native-keychain | Secure credential storage |

## Getting Started

### Prerequisites

- Node.js 22+
- Android SDK (API 26+)
- Java 17+ (tested with Java 23)
- Docker (for local Matrix server)

### Quick Start

```bash
# Install dependencies
pnpm install

# Start local Matrix server (one-time setup)
pnpm dev:server

# For physical devices: Set up port forwarding (after connecting device)
pnpm dev:forward

# Start Metro bundler (for hot reload)
pnpm start

# In another terminal: Build and run on device
pnpm android
```

### Device Testing

The app auto-logs into your local Matrix server using `http://localhost:8008`.

**For Android Emulator:**
- Works out of the box
- Just run `pnpm dev:server` and `pnpm android`

**For Physical Device:**
- Connect via USB or wireless ADB
- Run `pnpm dev:forward` to set up ADB port forwarding
- No IP configuration needed!

The app will auto-login as `alice` (password: `testpass123`). Test messaging by:
- Using `bob` on another device (also using `testpass123`)
- Using Element web client at http://localhost:8008

### Running Tests

Integration tests run against a local Matrix server (Conduit):

```bash
# Start the server (already done if you ran dev:server)
pnpm test:integration:setup

# Run integration tests
pnpm test:integration

# Stop the server
cd test/docker && docker compose down
```

## Status

**Current phase: Core implementation complete**

- [x] Project setup (React Native + TypeScript)
- [x] Matrix integration (login, rooms, messaging)
- [x] Audio recording and playback
- [x] Basic UI screens (optimized for D-pad navigation)
- [x] Auto-login (no keyboard required)
- [x] Integration test infrastructure
- [ ] Hardware PTT button (native Kotlin module)
- [ ] Push notifications (FCM)
- [ ] Device testing on Zello handhelds

## Contributing

The app targets cheap Android PTT handhelds running Android 8. When contributing:

- Keep the UI simple - large touch targets, minimal text
- Voice messages use standard Matrix `m.audio` events
- Test with `pnpm test:integration` before submitting PRs
