# Android Development Guide

This guide covers development of the native Kotlin Android app for Wata.

## Overview

The Android app is a native Kotlin implementation targeting PTT handheld devices. It uses Jetpack Compose for UI and OkHttp for Matrix protocol communication. The WataClient is maintained separately in both Kotlin (for Android) and TypeScript (for TUI/Web).

## Project Structure

```
src/android/
├── app/
│   └── src/main/
│       ├── java/com/wata/
│       │   ├── WataApplication.kt          # Application class
│       │   ├── MainActivity.kt             # Entry point, PTT key handling
│       │   ├── client/                     # Matrix protocol client
│       │   │   ├── WataClient.kt           # Main client facade
│       │   │   ├── MatrixApi.kt            # HTTP API layer (OkHttp)
│       │   │   ├── SyncEngine.kt           # Long-polling sync loop
│       │   │   ├── DmRoomService.kt        # Direct message room management
│       │   │   ├── FamilyRoomService.kt    # Family room management
│       │   │   └── Types.kt                # Domain models
│       │   ├── audio/                      # Audio recording/playback
│       │   │   ├── AudioService.kt         # Audio orchestration
│       │   │   ├── OpusCodec.kt            # Opus encoding/decoding
│       │   │   ├── OggMuxer.kt             # Ogg container creation
│       │   │   └── OggDemuxer.kt           # Ogg container parsing
│       │   └── ui/                         # Compose UI
│       │       ├── theme/                  # Colors, typography
│       │       ├── ContactListScreen.kt    # Main contact list
│       │       ├── ChatScreen.kt           # DM chat view
│       │       └── WataViewModel.kt        # UI state management
│       └── res/                            # Android resources
├── build.gradle.kts                        # App-level Gradle config
└── settings.gradle.kts                     # Gradle settings
```

## Key Dependencies

| Library | Purpose | Version |
|---------|---------|---------|
| OkHttp | HTTP client | 4.12.0 |
| kotlinx.serialization | JSON parsing | 1.6.3 |
| kotlinx.coroutines | Async/concurrency | 1.8.0 |
| Jetpack Compose | UI framework | 1.6.0 |
| Navigation Compose | Screen navigation | 2.7.6 |
| android-opus-codec | Opus encoding | AAR |

## Build System

The Android app uses Gradle with Kotlin DSL. Build from the project root:

```bash
# Build and install on connected device/emulator
pnpm android

# Or directly with Gradle
cd src/android
./gradlew assembleDebug          # Build APK only
./gradlew installDebug           # Install on connected device
./gradlew assembleDebug --info   # Verbose build for debugging
```

**APK Output:** `src/android/app/build/outputs/apk/debug/app-debug.apk`

## Authentication

The app uses hardcoded credentials for prototype purposes. Edit `MatrixConfig.kt` to change:

```kotlin
// src/android/app/src/main/java/com/wata/client/MatrixConfig.kt
object MatrixConfig {
    const val HOMESERVER_URL = "http://10.0.2.2:8008"  // Emulator localhost
    const val USERNAME = "alice"
    const val PASSWORD = "testpass123"
}
```

**For physical devices:** Use ADB reverse proxy or update the URL to your host machine's IP (run `pnpm dev:ip`).

## Architecture

### WataClient

The main client facade (`WataClient.kt`) provides:
- `login()` - Authenticate with Matrix
- `connect()` - Start sync loop
- `disconnect()` - Stop sync
- `getContacts()` - List of users for DM
- `getConversation(roomId)` - Get messages for a room
- `sendVoiceMessage(roomId, audioData)` - Upload and send audio
- `markMessagePlayed(eventId, roomId)` - Send read receipt

**State is held in-memory.** Rooms and contacts are refreshed on each sync.

### MatrixApi

OkHttp-based HTTP client for Matrix endpoints:
- `login()` - `POST /_matrix/client/v3/login`
- `sync()` - `GET /_matrix/client/v3/sync`
- `sendMessage()` - `PUT /_matrix/client/v3/rooms/{roomId}/send/{eventType}/{txnId}`
- `uploadContent()` - `POST /_matrix/client/v3/upload`

All responses are parsed as `kotlinx.serialization` JSON objects.

### SyncEngine

Long-polling sync loop running in a background thread:
1. Calls `sync(sinceToken)` with 30s timeout
2. Parses room events (messages, memberships)
3. Emits events to listeners
4. Repeats with new `sinceToken`

**Threading:** Uses a dedicated `Thread` (not coroutines). This may cause ANRs if sync blocks. Future improvement: migrate to coroutines.

### Audio Pipeline

**Recording:**
1. `AudioRecord` captures 16kHz PCM
2. `OpusCodec.encode()` converts to Opus frames (960 samples/frame)
3. `OggMuxer` wraps in Ogg container with OpusHead/OpusTags
4. Returns `ByteArray` for upload

**Playback:**
1. Download from MXC URL to temp file
2. `OggDemuxer` extracts Opus packets
3. `OpusCodec.decode()` converts to PCM
4. `AudioTrack` plays audio

**Format:** Ogg Opus, 16kHz mono, VOIP mode, ~24kbps

## UI (Jetpack Compose)

### Theme

High-contrast design for 1.77" screens:
- Large fonts: 24sp title, 20sp header, 18sp body
- D-pad focusable elements (orange border highlight)
- Minimal navigation (ContactList → Chat)

### Navigation

Navigation Compose with two routes:
- `"contactList"` - Main screen with user list
- `"chat/{userId}"` - Chat view with messages

Back button handling:
- Hardware back key: `NavigationCompose` handles automatically
- D-pad Exit/Back key: mapped to `KEYCODE_BACK`

### ViewModel

`WataViewModel` holds UI state as `StateFlow`:
- `connectionState` - Connecting/Connected/Disconnected/Error
- `contacts` - List of users for DM
- `currentConversation` - Messages for selected room
- `isRecording` - PTT recording state

## Hardware Keys

### PTT Button

Side PTT button (`KEYCODE_PTT`, code 79) is captured in `MainActivity`:

```kotlin
override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    if (keyCode == KeyEvent.KEYCODE_PTT) {
        viewModel.startRecording()
        return true
    }
    return super.onKeyDown(keyCode, event)
}

override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
    if (keyCode == KeyEvent.KEYCODE_PTT) {
        viewModel.stopRecordingAndSend()
        return true
    }
    return super.onKeyUp(keyCode, event)
}
```

**Note:** Key codes vary by device. Test on actual hardware and update as needed.

### D-Pad Navigation

| Key | KeyCode | Function |
|-----|---------|----------|
| Up/Down | `DPAD_UP/DOWN` | Navigate list items |
| P1 (Center) | `DPAD_CENTER` | Select/confirm |
| Menu | `MENU` | Open context menu (TODO) |
| Exit | `BACK` | Go back |

Focus management is handled by Compose's `focusable()` modifier.

## Testing

### Unit Tests

Run from `src/android/`:
```bash
./gradlew testDebugUnitTest
```

Tests use:
- JUnit 5 for assertions
- MockK for mocking
- MockWebServer for HTTP tests

### Integration Tests

Requires Conduit Matrix server running on `localhost:8008`:
```bash
# Terminal 1: Start Conduit
cd test/docker && docker-compose up

# Terminal 2: Run tests
cd src/android
./gradlew testDebugUnitTest
```

**Test users:** `alice` and `bob` with password `testpass123`

### Device Testing

```bash
# Connect device via USB or wireless ADB
adb devices

# Set up port forwarding for localhost:8008
pnpm dev:forward

# Build and install
pnpm android
```

## Development Workflow

1. Edit code in `src/android/`
2. Build and deploy: `pnpm android`
3. For faster iteration, use Gradle's build cache (subsequent builds are faster)

**No hot reload**—this is a native Kotlin app. Build times are typically 10-30 seconds.

## Common Issues

**Build fails with "Out of memory":**
```bash
# Increase Gradle heap size
export GRADLE_OPTS="-Xmx4g"
./gradlew assembleDebug
```

**Tests hang:**
- Ensure Conduit is running
- Check `adb reverse --list` for port forwarding
- Kill Gradle daemon: `./gradlew --stop`

**DM rooms not detected:**
- Check `is_direct` parsing in `DmRoomService.kt`
- Verify `m.direct` account data is synced

## Future Improvements

- Migrate `SyncEngine` to Kotlin coroutines (currently uses `Thread`)
- Add `suspend fun` variants to `WataClient` for proper async handling
- Implement proper cancellation and timeout handling
- Add offline message queue for intermittent connectivity
- Consider SQLite for local message persistence

## Related Documentation

- [Voice Architecture](voice.md) - Audio recording/encoding details
- [Quick Start](quickstart.md) - Getting started guide
- [Family Model](family-model.md) - Room architecture and Matrix mapping
