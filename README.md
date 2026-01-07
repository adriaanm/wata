# Wata

A minimalist push-to-talk voice messaging app for Android, built on the Matrix protocol.

## Goals

Wata is a walkie-talkie style app designed for:

- **Simplicity** - Large buttons, minimal UI, kid-friendly
- **Hardware PTT** - Works with cheap Zello Android handhelds (Android 8+)
- **No backend** - Uses existing Matrix infrastructure (matrix.org)
- **Interoperability** - Voice messages work with Element and other Matrix clients

## Architecture

```
┌──────────────────────────────────────────┐
│              Wata App                    │
├──────────────────────────────────────────┤
│  UI Layer (React Native + TypeScript)   │
│  - ContactListScreen                    │
│  - ChatScreen (voice messages only)     │
│  - PTTButton component                  │
├──────────────────────────────────────────┤
│  Services                               │
│  - MatrixService (auth, sync, messages) │
│  - AudioService (record/playback)       │
├──────────────────────────────────────────┤
│  Native Module (Kotlin) [planned]       │
│  - Hardware PTT button capture          │
└──────────────────────────────────────────┘
           │
           ▼
┌──────────────────────────────────────────┐
│       matrix.org homeserver             │
└──────────────────────────────────────────┘
```

### Key Files

```
src/
├── App.tsx                    # Navigation setup
├── services/
│   ├── MatrixService.ts       # Matrix SDK wrapper
│   └── AudioService.ts        # Recording/playback
├── screens/
│   ├── LoginScreen.tsx        # Username/password login
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

- Node.js 20+
- Android SDK (API 26+)
- Java 17+ (tested with Java 23)

### Setup

```bash
# Install dependencies
npm install

# Start Metro bundler
npm start

# Build and run on Android
npm run android
```

### Running Tests

Integration tests run against a local Matrix server (Conduit):

```bash
# Start the local Matrix server (requires Docker)
cd test/docker && docker compose up -d

# Create test users (first time only)
./setup.sh

# Run integration tests
npm run test:integration

# Stop the server
docker compose down
```

## Status

**Current phase: Core implementation complete**

- [x] Project setup (React Native + TypeScript)
- [x] Matrix integration (login, rooms, messaging)
- [x] Audio recording and playback
- [x] Basic UI screens
- [x] Integration test infrastructure
- [ ] Hardware PTT button (native Kotlin module)
- [ ] Push notifications (FCM)
- [ ] Device testing on Zello handhelds

## Contributing

The app targets cheap Android PTT handhelds running Android 8. When contributing:

- Keep the UI simple - large touch targets, minimal text
- Voice messages use standard Matrix `m.audio` events
- Test with `npm run test:integration` before submitting PRs
