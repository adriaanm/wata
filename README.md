# Wata

A minimalist push-to-talk voice messaging app for Android (and macOS Terminal), built on the Matrix protocol. "Wata" is short for walkie-talkie, in case it wasn't obvious (zie ook: "watte"!?).

## Disclaimer
Greetings! First, some words of discouragement for those actually reading the README.

I'm building this for my personal use, as well as to learn some new skills by doing some new stuff. I'm sharing the source because why not; I didn't even write it myself.

I make no claims regarding its quality or utility, nor do I make any commitments regarding fixing bugs or accepting changes.

Have fun with it!

## Frontends

- **Android** (`src/android/`) - Native Kotlin app for PTT handhelds
- **TUI** (`src/tui/`) - Terminal UI for macOS (see `src/tui/README.md`)
- **Web** (`src/web/`) - Web app for companion interface

## Goals

Wata is a walkie-talkie style app designed for:

- **Simplicity** - Large buttons, minimal UI, kid-friendly
- **Hardware PTT** - Works with cheap Zello Android handhelds (Android 8+)
- **No backend** - Uses existing Matrix infrastructure (matrix.org)
- **Interoperability** - Voice messages work with Element and other Matrix clients

## Architecture

```
┌────────────────────────┐  ┌────────────────────────┐  ┌──────────────────────┐
│   Android Frontend     │  │    TUI Frontend        │  │   Web Frontend       │
│   (Native Kotlin)      │  │    (Ink/Terminal)      │  │   (Vite/React)       │
│                        │  │                        │  │                      │
│ - ContactListScreen    │  │ - ContactListView      │  │ - ContactListView    │
│ - ChatScreen           │  │ - ChatView             │  │ - ChatView           │
│ - Opus Audio/Keystore  │  │ - FFmpeg Audio/Keychain│  │ - Web Audio API      │
└───────────┬────────────┘  └───────────┬────────────┘  └──────────┬───────────┘
            │                           │                         │
            └───────────────────────────┴─────────────────────────┘
                                     │
                                     ▼
                        ┌───────────────────────┐
                        │   SHARED BACKEND      │
                        │   (src/shared/)      │
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

**TUI and Web share the same Matrix backend code** (`src/shared/`), ensuring identical protocol behavior. Android has its own native Kotlin WataClient for direct hardware access.

## Tech Choices

| Choice | Rationale |
|--------|-----------|
| Native Kotlin | Direct hardware access, better Android 8 compatibility |
| TypeScript (TUI/Web) | Type safety, better tooling |
| matrix-js-sdk (TS) | Official SDK, well-maintained |
| OkHttp (Android) | Efficient HTTP client |
| matrix.org | Free, federated, no backend to maintain |
| Ogg Opus | Voice-optimized codec, small file sizes |

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

# Build and run on Android device/emulator
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
cd test/docker && docker-compose down
```

## Status

**Current phase: Native Kotlin implementation complete**

- [x] Project setup (Native Kotlin + Gradle)
- [x] Matrix integration (login, rooms, messaging)
- [x] Audio recording and playback (Ogg Opus)
- [x] Basic UI screens (Jetpack Compose, D-pad navigation)
- [x] Auto-login (no keyboard required)
- [x] Integration test infrastructure
- [x] Hardware PTT button capture
- [ ] Push notifications (FCM)
- [ ] Device testing on Zello handhelds

## Contributing

The app targets cheap Android PTT handhelds running Android 8. When contributing:

- Keep the UI simple - large touch targets, minimal text
- Voice messages use standard Matrix `m.audio` events
- Test with `pnpm test:integration` before submitting PRs
