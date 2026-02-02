# Android Native Kotlin Port

## Overview

Port the Android app from React Native to native Kotlin. This eliminates Metro/Babel complexity, improves Android 8 compatibility, and gives direct hardware access.

**Key decision:** WataClient will be maintained in both Kotlin and TypeScript. The TUI and Web frontends continue to use the TypeScript version.

## Code to Port

| Component | TS Lines | Kotlin Estimate | Notes |
|-----------|----------|-----------------|-------|
| WataClient | ~3,190 | ~2,500 | Matrix protocol, sync, domain logic |
| Ogg container | ~640 | ~400 | Pure byte manipulation |
| Audio service | ~260 | ~200 | Use android-opus-codec for Opus |
| UI screens | ~600 | ~800 | Compose is more verbose |
| **Total** | ~4,690 | ~3,900 | |

## Project Structure

```
wata/
├── src/
│   ├── android/              # NEW: Native Kotlin app
│   │   ├── app/
│   │   │   └── src/main/
│   │   │       ├── java/com/wata/
│   │   │       │   ├── WataApplication.kt
│   │   │       │   ├── MainActivity.kt
│   │   │       │   ├── client/           # WataClient port
│   │   │       │   │   ├── WataClient.kt
│   │   │       │   │   ├── MatrixApi.kt
│   │   │       │   │   ├── SyncEngine.kt
│   │   │       │   │   ├── DmRoomService.kt
│   │   │       │   │   └── Types.kt
│   │   │       │   ├── audio/            # Audio pipeline
│   │   │       │   │   ├── OggMuxer.kt
│   │   │       │   │   ├── OggDemuxer.kt
│   │   │       │   │   └── AudioService.kt
│   │   │       │   └── ui/               # Compose UI
│   │   │       │       ├── theme/
│   │   │       │       ├── ContactListScreen.kt
│   │   │       │       └── ChatScreen.kt
│   │   │       └── res/
│   │   ├── build.gradle.kts
│   │   └── settings.gradle.kts
│   ├── shared/               # Keep for TUI/Web
│   ├── tui/
│   └── web/
└── android/                  # OLD: RN android folder (remove after port)
```

---

## Phase 0: Project Setup

**Goal:** Kotlin project that builds and runs on device with OkHttp + Compose.

### Tasks

- [x] **0.1** Create `src/android/` directory structure
  - New Gradle project with Kotlin DSL
  - `app/` module
  - minSdk 26 (Android 8)

- [x] **0.2** Configure dependencies
  ```kotlin
  // build.gradle.kts
  dependencies {
      // Networking
      implementation("com.squareup.okhttp3:okhttp:4.12.0")
      implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
      implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

      // UI
      implementation("androidx.compose.ui:ui:1.6.0")
      implementation("androidx.compose.material3:material3:1.2.0")
      implementation("androidx.navigation:navigation-compose:2.7.6")

      // Audio
      implementation(files("libs/opus.aar"))  // android-opus-codec

      // Testing
      testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
      testImplementation("io.mockk:mockk:1.13.9")
      testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
  }
  ```

- [x] **0.3** Create minimal MainActivity with Compose
  - "Hello Wata" text
  - Verify builds and runs on device

- [x] **0.4** Set up test infrastructure
  - JUnit 5 + MockK
  - MockWebServer for HTTP tests
  - Verify tests run with `./gradlew test`

**Exit criteria:** `./gradlew assembleDebug` produces APK, tests pass.

---

## Phase 1: WataClient Port

**Goal:** Kotlin WataClient that can login, sync, and send/receive messages. Tested against local Conduit.

### 1.1 Types & Domain Models

- [ ] **1.1.1** Port `types.ts` → `Types.kt`
  - `User`, `Contact`, `Family`, `Conversation`, `VoiceMessage`
  - `ConnectionState` sealed class
  - Event handler typealias definitions

- [ ] **1.1.2** Add kotlinx.serialization annotations
  - `@Serializable` for JSON parsing
  - Custom serializers for Date, nullable fields

**Reference:** `src/shared/lib/wata-client/types.ts` (~190 lines)

### 1.2 Matrix API Layer

- [ ] **1.2.1** Port `matrix-api.ts` → `MatrixApi.kt`
  - OkHttp client setup
  - Login endpoint (`/_matrix/client/v3/login`)
  - Sync endpoint (`/_matrix/client/v3/sync`)
  - Send message endpoint
  - Upload media endpoint
  - Download media endpoint

- [ ] **1.2.2** Write integration tests against Conduit
  - Test login with alice/testpass123
  - Test sync returns rooms
  - Test send/receive message round-trip

**Reference:** `src/shared/lib/wata-client/matrix-api.ts` (~766 lines)

### 1.3 Sync Engine

- [ ] **1.3.1** Port `sync-engine.ts` → `SyncEngine.kt`
  - Long-polling sync loop (coroutine)
  - Room state parsing
  - Timeline event parsing
  - Since token management

- [ ] **1.3.2** Handle Matrix event types
  - `m.room.message` (voice messages)
  - `m.room.member` (membership)
  - `m.room.name` (room names)
  - `m.receipt` (read receipts for "played" status)

- [ ] **1.3.3** Write sync tests
  - Mock sync responses
  - Verify events parsed correctly
  - Verify since token updates

**Reference:** `src/shared/lib/wata-client/sync-engine.ts` (~606 lines)

### 1.4 DM Room Service

- [ ] **1.4.1** Port `dm-room-service.ts` → `DmRoomService.kt`
  - Find existing DM with user
  - Create new DM room
  - DM room caching

**Reference:** `src/shared/lib/wata-client/dm-room-service.ts` (~200 lines)

### 1.5 WataClient Facade

- [ ] **1.5.1** Port `wata-client.ts` → `WataClient.kt`
  - Public API: `login()`, `connect()`, `disconnect()`
  - `getContacts()`, `getConversation()`
  - `sendVoiceMessage()`, `markMessagePlayed()`
  - Event emission (Kotlin Flow or callbacks)

- [ ] **1.5.2** Integration test: full flow
  - Login → Sync → Get contacts → Send message → Receive message
  - Run against local Conduit

**Reference:** `src/shared/lib/wata-client/wata-client.ts` (~1,059 lines)

**Phase 1 exit criteria:** Kotlin WataClient passes same integration tests as TypeScript version.

---

## Phase 1b: Testing & Code Quality

**Goal:** Ensure the Kotlin port is functionally equivalent to TypeScript, with robust tests and proper async behavior.

### 1b.0 Bug Fixes & Code Issues

Issues identified during code review (TS vs Kotlin comparison):

- [x] **1b.0.1** Fix `is_direct` flag parsing (`DmRoomService.kt:420-433, 438-458`)
  - Bug: Checked for string `"true"` instead of boolean `true`
  - Fix: Use `jsonPrimitive.booleanOrNull` instead of `jsonPrimitive.content`
  - Impact: DM rooms were never detected as direct messages
  - **FIXED** in commit 7521a50

- [ ] **1b.0.2** Fix `getOrCreateDMRoom()` to actually create rooms (`WataClient.kt:738-756`)
  - Currently throws: `"DM room creation not yet implemented"`
  - Should call `dmRoomService.ensureDMRoom()` (which exists but is unused)
  - Tests work around this by using `createDMRoom()` directly

- [ ] **1b.0.3** Implement `updateMDirectForRoom()` (`DmRoomService.kt:325-350`)
  - Currently doesn't persist `m.direct` account data to server
  - Need to serialize room list back to JsonObject and call `api.setAccountData()`
  - Without this, other clients won't see DM room associations

- [ ] **1b.0.4** Fix auto-join for invited rooms (`WataClient.kt:1012-1024`)
  - TS version auto-joins invites in `handleMembershipChanged()`
  - Kotlin version has the code but may not trigger correctly
  - Tests explicitly call `joinRoom()` as workaround

- [x] **1b.0.5** Add `room_id` to timeline events for `getPlayedByForEvent()` (`WataClient.kt:831-840`)
  - TS passes `room` parameter to `eventToVoiceMessage()` and `getPlayedByForEvent()`
  - Kotlin tries to look up room from `event.room_id` which may be null
  - Causes receipts to not be found for voice messages
  - **FIXED** in commit 59a62b8 - modified functions to accept optional `room` parameter

#### Test Infrastructure Gaps

| Aspect | TypeScript | Kotlin | Issue |
|--------|------------|--------|-------|
| Room creation | `getOrCreateDmRoom()` | `createDMRoom()` directly | Bypasses DmRoomService |
| Room joining | Auto-join in event handler | Explicit `joinRoom()` | May miss invite events |
| Waiting | Event listeners + polling | Polling only | Slower, less reliable |
| Room param | Passed to event handlers | Looked up from event | `room_id` may be null |

### 1b.1 Test Environment Setup

**Use tmux for test monitoring** (tests may hang on sync timeouts):

```bash
# Create tmux session for test monitoring
tmux new-session -d -s wata-tests

# Split into panes: Conduit server | Gradle tests | Log tail
tmux split-window -h -t wata-tests
tmux split-window -v -t wata-tests:0.1

# Pane 0: Conduit server
tmux send-keys -t wata-tests:0.0 'cd /Users/adriaan/g/wata && pnpm dev:server' Enter

# Pane 1: Run tests (use Gradle daemon for performance)
tmux send-keys -t wata-tests:0.1 'cd /Users/adriaan/g/wata/src/android && ./gradlew test --daemon' Enter

# Pane 2: Monitor for hangs
tmux send-keys -t wata-tests:0.2 'watch -n 5 "ps aux | grep -E \"(java|conduit)\" | grep -v grep"' Enter

# Attach to session
tmux attach -t wata-tests
```

**Gradle daemon tips:**
- First run: `./gradlew --daemon` starts the daemon
- Subsequent runs are faster (JVM stays warm)
- Stop daemon: `./gradlew --stop`
- Check status: `./gradlew --status`

### 1b.2 Async Architecture (Optional - defer to Phase 3)

These changes improve Android compatibility but aren't blocking for Phase 2:

- [ ] **1b.2.1** Convert `SyncEngine` to use Kotlin coroutines
  - Replace `Thread` with `CoroutineScope` + `launch`
  - Replace `Thread.sleep()` with `delay()`
  - Use `withContext(Dispatchers.IO)` for network calls
  - Risk without: potential ANRs if sync blocks main thread

- [ ] **1b.2.2** Make `WataClient` methods suspend functions
  - `suspend fun login()`, `suspend fun connect()`
  - Keep synchronous variants for simple lookups
  - Allows proper cancellation and timeout handling

### 1b.3 Align Tests with TypeScript Patterns

- [ ] **1b.3.1** Create `TestOrchestrator.kt` equivalent
  - Manage multiple test clients
  - `createRoom(alice, bob)` using DmRoomService flow
  - `waitForRoom()` with event listeners + polling

- [ ] **1b.3.2** Port missing test scenarios from TypeScript
  - `voice-message-flow.test.ts` → bidirectional messaging
  - `read-receipts.test.ts` → mark as played flow
  - `edge-cases.test.ts` → error handling

- [ ] **1b.3.3** Add timeout guards
  - Wrap sync waits with explicit timeouts
  - Log state on timeout for debugging
  - Use `@Test(timeout = 60000)` annotations

### 1b.4 Verification Checklist

Run these commands to verify the port:

```bash
# Start Conduit (in tmux pane or separate terminal)
pnpm dev:server

# Run Kotlin tests
cd src/android
./gradlew test --daemon --info 2>&1 | tee test-output.log

# Check for failures
grep -E "(FAILED|PASSED|SKIPPED)" test-output.log

# Run TypeScript tests for comparison
cd ../..
pnpm test:integration
```

**Exit criteria for Phase 1b:**
1. All integration tests pass (`EndToEndFlowTest`, `MatrixApiTest`, `SyncEngineTest`)
2. Tests don't hang (complete within 2 minutes each)
3. `is_direct` rooms are properly detected
4. No `Thread.sleep()` on test assertions (use `waitForCondition()`)

---

## Phase 2: Audio Pipeline

**Goal:** Record PCM, encode to Ogg Opus, decode Ogg Opus for playback.

### 2.1 Opus Integration

- [ ] **2.1.1** Add android-opus-codec library
  - Download AAR from https://github.com/theeasiestway/android-opus-codec
  - Place in `libs/opus.aar`
  - Verify encoder/decoder work

- [ ] **2.1.2** Create `OpusCodec.kt` wrapper
  - `encode(pcm: ShortArray, frameSize: Int): ByteArray`
  - `decode(opus: ByteArray): ShortArray`
  - Match 16kHz mono, 960 frame size settings from TS

### 2.2 Ogg Container

- [ ] **2.2.1** Port `ogg.ts` → `OggMuxer.kt`
  - CRC32 table (Ogg polynomial)
  - `createOggPage()` function
  - `createOpusHead()` / `createOpusTags()`
  - `muxPackets()` for complete file

- [ ] **2.2.2** Port `ogg.ts` → `OggDemuxer.kt`
  - Parse Ogg pages
  - Extract Opus packets
  - Skip OpusHead/OpusTags headers

- [ ] **2.2.3** Round-trip test
  - Encode PCM → Ogg Opus → Decode → Compare PCM
  - Verify output playable in VLC

**Reference:** `src/shared/lib/ogg.ts` (~640 lines)

### 2.3 Audio Service

- [ ] **2.3.1** Create `AudioService.kt`
  - `startRecording()` - AudioRecord at 16kHz mono
  - `stopRecording()` - Returns Ogg Opus ByteArray
  - `startPlayback(url: String)` - MediaPlayer or AudioTrack
  - `stopPlayback()`

- [ ] **2.3.2** Integrate with WataClient
  - `sendVoiceMessage()` uploads Ogg Opus
  - Message playback downloads and plays

**Reference:** `src/rn/services/AudioService.ts` (~260 lines)

**Phase 2 exit criteria:** Can record voice, upload to Matrix, download and play back.

---

## Phase 3: UI Screens

**Goal:** Compose UI matching current RN functionality.

### 3.1 Theme & Components

- [ ] **3.1.1** Port theme constants
  - Colors (bg, primary, text, etc.)
  - Typography (large, readable fonts for 1.77" screen)

- [ ] **3.1.2** Create `FocusableSurface` composable
  - D-pad navigation support
  - Focus indication (border/highlight)

### 3.2 Contact List Screen

- [ ] **3.2.1** Port `ContactListScreen.tsx` → `ContactListScreen.kt`
  - Header with user info
  - Scrollable contact list
  - Unread message badges
  - D-pad navigation between contacts

- [ ] **3.2.2** Wire to WataClient
  - `client.getContacts()` for list
  - `client.on("contactsUpdated")` for updates
  - Navigate to Chat on select

**Reference:** `src/rn/screens/ContactListScreen.tsx` (~147 lines)

### 3.3 Chat Screen

- [ ] **3.3.1** Port `ChatScreen.tsx` → `ChatScreen.kt`
  - Header with contact name and back button
  - Message list (scrollable)
  - Message bubbles (sent vs received)
  - PTT recording indicator

- [ ] **3.3.2** PTT recording flow
  - KeyEvent capture for KEYCODE_PTT
  - Recording animation/feedback
  - Send on release

- [ ] **3.3.3** Message playback
  - Tap to play
  - Progress indicator
  - Mark as played

**Reference:** `src/rn/screens/ChatScreen.tsx` (~291 lines)

### 3.4 Navigation

- [ ] **3.4.1** Set up Navigation Compose
  - ContactList → Chat navigation
  - Back button handling
  - Hardware back key (KEYCODE_BACK)

**Phase 3 exit criteria:** App is functionally equivalent to RN version.

---

## Phase 4: Integration & Cleanup

**Goal:** Remove RN, update build scripts, verify on device.

### 4.1 Final Integration

- [ ] **4.1.1** End-to-end test on physical device
  - ABBREE or similar PTT handheld
  - Test PTT button recording
  - Test D-pad navigation
  - Test message send/receive with real Matrix server

- [ ] **4.1.2** Performance verification
  - App startup time
  - Recording latency
  - APK size comparison

### 4.2 Cleanup

- [ ] **4.2.1** Remove old RN Android code
  - Delete `android/` folder (old RN)
  - Move `src/android/` → `android/`
  - Or keep `src/android/` and update root `package.json`

- [ ] **4.2.2** Update documentation
  - `CLAUDE.md` - Remove RN references
  - `docs/quickstart.md` - Update build instructions
  - `docs/voice.md` - Document Kotlin audio pipeline

- [ ] **4.2.3** Update CI/scripts
  - `pnpm android` → Gradle build
  - Remove Metro-related scripts

**Phase 4 exit criteria:** Clean native Kotlin app, no RN remnants.

---

## Testing Strategy

### Unit Tests
- `Types.kt` - Serialization round-trips
- `MatrixApi.kt` - Request/response parsing (MockWebServer)
- `SyncEngine.kt` - Event parsing, state updates
- `OggMuxer.kt` / `OggDemuxer.kt` - Round-trip encoding

### Integration Tests
- WataClient against local Conduit
- Full message flow: login → sync → send → receive

### Device Tests
- Manual testing on ABBREE handheld
- PTT button capture
- Audio quality verification

---

## Dependencies

| Library | Purpose | Version |
|---------|---------|---------|
| OkHttp | HTTP client | 4.12.0 |
| kotlinx.serialization | JSON parsing | 1.6.3 |
| kotlinx.coroutines | Async/concurrency | 1.8.0 |
| Jetpack Compose | UI framework | 1.6.0 |
| Navigation Compose | Screen navigation | 2.7.6 |
| android-opus-codec | Opus encoding | latest |
| MockK | Test mocking | 1.13.9 |
| MockWebServer | HTTP test server | 4.12.0 |

---

## Risk Mitigation

| Risk | Mitigation |
|------|------------|
| Matrix protocol edge cases | Port integration tests from TS, run against Conduit |
| Opus library compatibility | Test on Android 8 emulator early |
| D-pad navigation complexity | Use `FocusManager` API, test on device early |
| WataClient divergence | Keep TypeScript as reference, document differences |

---

## Subagent Dispatch Guide

Each phase section is designed for independent work:

- **Phase 0**: Self-contained project setup, no dependencies ✅ COMPLETE
- **Phase 1.1-1.2**: Can start after Phase 0, types + API are independent
- **Phase 1.3-1.5**: Requires 1.1-1.2 complete
- **Phase 1b**: Requires Phase 1 complete; validates correctness before moving on
- **Phase 2**: Can parallel with Phase 1 (only needs types)
- **Phase 3**: Requires Phase 1 + Phase 1b + Phase 2 complete
- **Phase 4**: Final integration, requires all above

For each task, provide the subagent:
1. The specific checkbox item
2. Reference TypeScript file path
3. Target Kotlin file path
4. Test requirements

### Running Tests (Important)

**Use tmux to manage background processes** - tests may hang on sync timeouts:

```bash
# Quick test run with Gradle daemon (faster subsequent runs)
cd src/android && ./gradlew test --daemon

# If tests hang, check running Java processes
ps aux | grep java

# Kill hung Gradle/test processes if needed
./gradlew --stop
pkill -f "GradleDaemon"
```

**Always ensure Conduit is running** before integration tests:
```bash
pnpm dev:server  # In separate terminal/tmux pane
```
