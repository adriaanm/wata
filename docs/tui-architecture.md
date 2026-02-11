# TUI Frontend Architecture

A terminal-based frontend for macOS that mirrors the Android PTT voice messaging app.

## Overview

The TUI frontend provides a keyboard-driven interface for Matrix voice messaging, designed to closely replicate the mobile app's UX in a terminal environment. **Both frontends share the same backend code** for Matrix operations, ensuring identical protocol behavior.

## Shared Backend Principle

**The Android app is the reference implementation.** The TUI frontend must use the same backend services and types to guarantee consistent Matrix behavior across platforms.

### What is Shared (in `src/`)

| Module | Purpose | Notes |
|--------|---------|-------|
| `services/WataService.ts` | Matrix client, auth, sync, messaging | Core backend - must be identical |
| `hooks/useMatrixSync.ts` | Sync state subscription | Shared logic |
| `hooks/useRooms.ts` | Room list subscription | Shared logic |
| `hooks/useVoiceMessages.ts` | Message list subscription | Shared logic |
| `types/*.ts` | `MatrixRoom`, `VoiceMessage`, etc. | Shared interfaces |
| `config/matrix.ts` | Homeserver URL, credentials | Shared config |

### What is Platform-Specific

| Android (`src/android/`) | TUI/Web (`src/tui/`, `src/web/`) |
|--------------------------|---------------------------------|
| `audio/` (native Kotlin Opus) | `services/PvRecorderAudioService.ts` (FFmpeg) |
| `ui/` (Jetpack Compose) | `views/*.tsx` (Ink) or components (React) |
| N/A | `hooks/useAudioRecorder.ts` (keyboard-based) |
| N/A | `hooks/useAudioPlayer.ts` |

### Abstraction Strategy

Platform-specific code (credential storage, audio) uses **adapter interfaces**:

```typescript
// src/services/CredentialStorage.ts (interface)
export interface CredentialStorage {
  store(username: string, password: string): Promise<void>;
  retrieve(): Promise<{username: string; password: string} | null>;
  clear(): Promise<void>;
}

// Android: native Android Keystore (via EncryptedSharedPreferences)
// TUI: tui/src/services/KeytarCredentialStorage.ts (uses keytar / macOS Keychain)
// Web: localStorage or sessionStorage
```

WataService accepts these adapters via dependency injection, keeping the core logic identical.

## Technology Stack

| Layer | Technology | Rationale |
|-------|------------|-----------|
| Runtime | Node.js 22+ | Matches mobile requirements, shared dependencies |
| TUI Framework | [Ink](https://github.com/vadimdemedes/ink) | React-based (familiar patterns), composable, TypeScript |
| Audio Recording | [node-record-lpcm16](https://www.npmjs.com/package/node-record-lpcm16) + FFmpeg | Cross-platform, AAC encoding via FFmpeg |
| Audio Playback | [play-sound](https://www.npmjs.com/package/play-sound) or `afplay` | macOS native player, simple API |
| Matrix Client | `WataClient` | Custom client library (lighter) |
| Keypress | Ink built-in `useInput()` | Handles raw keyboard input |
| Storage | `keytar` | macOS Keychain for credentials (like RN Keychain) |

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                        TUI Application                          │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              Ink Components (tui/src/)                   │   │
│  │  ┌─────────────────────────────────────────────────────┐ │   │
│  │  │                    MainView                          │ │   │
│  │  │  - Family member list (from family room membership) │ │   │
│  │  │  - PTT recording (Space to talk)                    │ │   │
│  │  │  - Status indicators (●/⚠/none)                     │ │   │
│  │  └───────────────────────┬─────────────────────────────┘ │   │
│  │                          │ Enter (if unread)             │   │
│  │                          ▼                               │   │
│  │  ┌─────────────────────────────────────────────────────┐ │   │
│  │  │                   HistoryView                        │ │   │
│  │  │  - Messages from contact (most recent first)        │ │   │
│  │  │  - Playback (Enter to play)                         │ │   │
│  │  └─────────────────────────────────────────────────────┘ │   │
│  └─────────────────────────────────────────────────────────┘   │
│                              │                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │          Platform-Specific Hooks (tui/src/hooks/)        │   │
│  │  useAudioRecorder (TUI)    useAudioPlayer (TUI)         │   │
│  │  useNavigation             useInput                      │   │
│  └─────────────────────────────────────────────────────────┘   │
│                              │                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │          Platform-Specific Services (tui/src/)           │   │
│  │  ┌────────────────────────────────────────────────────┐ │   │
│  │  │  TuiAudioService         KeytarCredentialStorage   │ │   │
│  │  │  - node-record-lpcm16    - keytar (macOS Keychain) │ │   │
│  │  │  - FFmpeg encoding                                  │ │   │
│  │  │  - afplay playback                                  │ │   │
│  │  └────────────────────────────────────────────────────┘ │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                               │
                               │ imports
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│              SHARED BACKEND (src/ - Android is primary)         │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                    Shared Hooks                          │   │
│  │  useMatrixSync    useRooms    useVoiceMessages          │   │
│  └─────────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                    Shared Services                       │   │
│  │  ┌──────────────────────────────────────────────────┐   │   │
│  │  │  WataService (core backend)                    │   │   │
│  │  │  - WataClient                                     │   │   │
│  │  │  - Login, sync, room management                   │   │   │
│  │  │  - Voice message send/receive                     │   │   │
│  │  │  - Accepts CredentialStorage adapter              │   │   │
│  │  └──────────────────────────────────────────────────┘   │   │
│  └─────────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                    Shared Types                          │   │
│  │  MatrixRoom    VoiceMessage    CredentialStorage (if)   │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

## Directory Structure

```
wata/
├── src/                          # SHARED BACKEND (Android primary)
│   ├── services/
│   │   ├── WataService.ts        # Core Matrix logic (shared)
│   │   ├── AudioService.ts       # Android audio (platform-specific)
│   │   └── CredentialStorage.ts  # Interface for credential adapters
│   │
│   ├── hooks/
│   │   ├── useMatrixSync.ts      # Shared - sync state
│   │   ├── useRooms.ts           # Shared - room list
│   │   └── useVoiceMessages.ts   # Shared - message list
│   │
│   ├── types/
│   │   ├── MatrixRoom.ts         # Shared interface
│   │   └── VoiceMessage.ts       # Shared interface
│   │
│   ├── config/
│   │   └── matrix.ts             # Shared config
│   │
│   ├── lib/
│   │   └── wata-client/          # WataClient library
│   │
│   ├── screens/                  # Android-only (React Native)
│   └── components/               # Android-only (React Native)
│
├── tui/                          # TUI FRONTEND
│   ├── package.json              # TUI-specific dependencies
│   ├── tsconfig.json             # TypeScript config (paths to ../src)
│   │
│   └── src/
│       ├── index.tsx             # Entry point
│       ├── App.tsx               # Root component, navigation state
│       │
│       ├── views/
│       │   ├── MainView.tsx          # Family list + PTT (primary)
│       │   ├── HistoryView.tsx       # Message playback
│       │   └── LoadingView.tsx       # Startup/sync screen
│       │
│       ├── components/
│       │   ├── FocusableItem.tsx     # Selectable list item
│       │   ├── MessageItem.tsx       # Voice message row
│       │   ├── RecordingStatus.tsx   # PTT recording indicator
│       │   ├── StatusBar.tsx         # Bottom status bar
│       │   └── Header.tsx            # Top title bar
│       │
│       ├── hooks/
│       │   ├── useNavigation.ts      # TUI-specific navigation
│       │   ├── useAudioRecorder.ts   # TUI-specific recording
│       │   └── useAudioPlayer.ts     # TUI-specific playback
│       │
│       ├── services/
│       │   ├── TuiAudioService.ts        # macOS-native audio
│       │   └── KeytarCredentialStorage.ts # keytar adapter
│       │
│       └── theme.ts                  # Terminal colors & styles
│
└── test/
    └── integration/              # Shared integration tests
```

**Key point:** The TUI imports shared code directly from `../src/`. TypeScript path aliases make this clean:

```json
// tui/tsconfig.json
{
  "compilerOptions": {
    "paths": {
      "@shared/*": ["../src/*"]
    }
  }
}
```

```typescript
// tui/src/App.tsx
import { WataService } from '@shared/services/WataService';
import { useRooms } from '@shared/hooks/useRooms';
import type { MatrixRoom } from '@shared/types/MatrixRoom';
```

## Screen Design

The UI is optimized for the core use case: **sending ephemeral voice messages to family members**.

### Design Principles

1. **Minimal friction**: Talk to someone in one action (select + space)
2. **Main screen is everything**: No navigation to send a message
3. **Status at a glance**: Unread, error, or all-good per contact
4. **History is secondary**: Only accessed when there's something unread

### Views

| View | Purpose | Entry |
|------|---------|-------|
| **MainView** | Family list + PTT | Default screen |
| **HistoryView** | Message playback | Enter on contact with unread |

### MainView (Primary Interface)

The main screen shows all family members. PTT happens directly here.

```
┌─────────────────────────────────────────┐
│  WATA                                   │
├─────────────────────────────────────────┤
│                                         │
│  ▶ Mom                            ●     │  ← ● = unread message
│                                         │
│    Dad                                  │  ← no indicator = all good
│                                         │
│    Sister                         ⚠     │  ← ⚠ = delivery error
│                                         │
│  ─────────────────────────────────────  │
│    Family                         ●     │  ← broadcast channel
│                                         │
├─────────────────────────────────────────┤
│  ↑↓ Navigate  Space Talk  Enter History │
└─────────────────────────────────────────┘
```

**Recording state** (when holding space):

```
┌─────────────────────────────────────────┐
│  WATA                                   │
├─────────────────────────────────────────┤
│                                         │
│  ▶ Mom                            ●     │
│    ┌─────────────────────────────┐      │
│    │ ● REC 0:05  Release to send │      │  ← inline recording indicator
│    └─────────────────────────────┘      │
│    Dad                                  │
│                                         │
│    Sister                         ⚠     │
│                                         │
│  ─────────────────────────────────────  │
│    Family                         ●     │
│                                         │
├─────────────────────────────────────────┤
│  Recording...                           │
└─────────────────────────────────────────┘
```

### HistoryView (Message Playback)

Accessed by pressing Enter on a contact with unread messages. Shows messages from that contact, most recent on top.

```
┌─────────────────────────────────────────┐
│  ← Mom                                  │
├─────────────────────────────────────────┤
│                                         │
│  ▶ 0:12                      10:33 AM   │  ← selected, Enter to play
│                                         │
│    0:08                      10:31 AM   │
│                                         │
│    0:15                      10:28 AM   │
│                                         │
│    0:10                      Yesterday  │
│                                         │
├─────────────────────────────────────────┤
│  ↑↓ Navigate  Enter Play  Esc Back      │
└─────────────────────────────────────────┘
```

**Notes:**
- Messages from this contact only (not your replies)
- Most recent on top (reverse chronological)
- Duration + timestamp, no sender name needed (it's all from them)
- Playing a message marks it as read
- After playing the last unread, the ● indicator clears on MainView

### Key Bindings

| Context | Key | Action |
|---------|-----|--------|
| Global | `q` / `Ctrl+C` | Quit application |
| MainView | `↑` / `k` | Move selection up |
| MainView | `↓` / `j` | Move selection down |
| MainView | `Space` (hold) | Record and send to selected contact |
| MainView | `Enter` | Open history (if unread messages) |
| HistoryView | `↑` / `k` | Select previous message |
| HistoryView | `↓` / `j` | Select next message |
| HistoryView | `Enter` | Play selected message |
| HistoryView | `Esc` | Return to main |

### Status Indicators

| Indicator | Meaning | Display |
|-----------|---------|---------|
| `●` | Unread message(s) | Right-aligned, accent color |
| `⚠` | Delivery error | Right-aligned, error color |
| (none) | All good | No indicator |

**Priority**: Error takes precedence over unread (show `⚠` not `●`)

## Implementation Strategy

### Phase 1: Project Setup & Backend Abstraction

1. Initialize `tui/` directory with package.json
2. Set up TypeScript with path aliases to `../src/`
3. **Refactor Android's WataService** to accept a `CredentialStorage` adapter (dependency injection)
4. Create `KeytarCredentialStorage.ts` implementing the adapter interface
5. Verify shared imports work: `WataService`, types, config
6. Create basic App shell with navigation state
7. Implement LoadingView for startup

**Deliverable:** TUI app that logs in using shared WataService

### Phase 2: Main View (Family List + PTT)

1. Query family room membership for contact list (see `docs/family-model.md`)
2. Build MainView with family member list
3. Implement status indicators (unread ●, error ⚠)
4. Wire up Space key for PTT recording directly from main view
5. Implement inline recording indicator

**Deliverable:** Main view with family list and PTT

### Phase 3: Audio Recording & Sending

1. Implement TuiAudioService recording methods
2. Use PvRecorder + FFmpeg → Opus pipeline
3. Upload and send to selected contact's DM (create on-demand)
4. Track delivery status for error indicator

**Deliverable:** Can record and send voice messages from main view

### Phase 4: History View (Playback)

1. Build HistoryView showing messages from selected contact
2. Filter to show only their messages (not your replies)
3. Most recent on top (reverse chronological)
4. Implement playback with `afplay`
5. Mark messages as read on playback

**Deliverable:** Can view and play message history

### Phase 5: Unread Tracking

1. Track read receipts or local read state
2. Update unread indicator when messages arrive
3. Clear indicator when messages are played
4. Persist read state across restarts

**Deliverable:** Accurate unread indicators

### Phase 6: Polish & Testing

1. Error handling (delivery failures, network issues)
2. Graceful shutdown
3. Integration tests against Conduit
4. Documentation

## Service Implementations

### TuiAudioService

```typescript
// Pseudocode for macOS audio handling

import { spawn } from 'child_process';
import * as fs from 'fs';

class TuiAudioService {
  private recordProcess: ChildProcess | null = null;
  private playProcess: ChildProcess | null = null;

  // Recording: sox/rec → raw PCM → FFmpeg → AAC
  async startRecording(): Promise<void> {
    const tempPath = `/tmp/wata-recording-${Date.now()}.m4a`;
    this.recordProcess = spawn('rec', [
      '-q',                    // quiet
      '-r', '44100',           // sample rate
      '-c', '1',               // mono
      '-t', 'raw',             // raw PCM
      '-',                     // stdout
    ]);

    const ffmpeg = spawn('ffmpeg', [
      '-f', 's16le',           // input format
      '-ar', '44100',          // sample rate
      '-ac', '1',              // mono
      '-i', 'pipe:0',          // stdin
      '-c:a', 'aac',           // AAC codec
      '-b:a', '64k',           // bitrate
      tempPath
    ]);

    this.recordProcess.stdout.pipe(ffmpeg.stdin);
  }

  // Playback: afplay (macOS built-in)
  async startPlayback(filePath: string): Promise<void> {
    this.playProcess = spawn('afplay', [filePath]);
  }

  async stopPlayback(): Promise<void> {
    this.playProcess?.kill();
  }
}
```

### MatrixService Refactoring

To enable sharing, the `WataService.ts` must be refactored to remove platform-specific dependencies:

**Before (direct dependency):**
```typescript
// src/services/MatrixService.ts
import * as Keychain from 'react-native-keychain'; // Or other platform-specific lib

class WataService {
  async storeCredentials(user: string, pass: string) {
    await Keychain.setGenericPassword(user, pass);  // Platform-specific!
  }
}
```

**After (adapter injection):**
```typescript
// src/services/CredentialStorage.ts (interface)
export interface CredentialStorage {
  store(username: string, password: string): Promise<void>;
  retrieve(): Promise<{username: string; password: string} | null>;
  clear(): Promise<void>;
}

// src/services/MatrixService.ts (shared)
import type { CredentialStorage } from './CredentialStorage';

class WataService {
  constructor(private credentials: CredentialStorage) {}

  async storeCredentials(user: string, pass: string) {
    await this.credentials.store(user, pass);  // Platform-agnostic
  }
}

// Platform-specific implementations:
// - TUI: src/tui/services/KeytarCredentialStorage.ts (uses keytar / macOS Keychain)
// - Web: src/web/services/LocalStorageCredentialStorage.ts
// - Android: native Android Keystore (separate WataClient.kt implementation)
```

**Other platform abstractions:**

| Dependency | Abstraction |
|------------|-------------|
| keytar (TUI) | `CredentialStorage` interface |
| localStorage (Web) | `CredentialStorage` interface |
| Android Keystore | Native Kotlin (separate implementation) |
| `fetch` | Works in all platforms (Node 18+ has native fetch) |

The goal: **MatrixService.ts has zero platform-specific imports** and can be imported directly by TUI and Web frontends.

## Theme Mapping

Terminal colors mapped from mobile theme:

```typescript
// tui/src/theme.ts
export const colors = {
  background: 'black',        // #000000
  backgroundLight: 'gray',    // #1a1a1a
  text: 'white',              // #FFFFFF
  textMuted: 'gray',          // #AAAAAA
  accent: 'cyan',             // #00AAFF
  recording: 'red',           // #FF3333
  playing: 'green',           // #33FF33
  focus: 'yellow',            // #FFAA00 (selection highlight)
};
```

## PTT Key Detection

Ink's `useInput` hook supports keydown events. For hold-to-record:

```typescript
import { useInput, useApp } from 'ink';
import { useState, useRef } from 'react';

function usePtt(onStart: () => void, onStop: () => void) {
  const [isPressed, setIsPressed] = useState(false);
  const pressedRef = useRef(false);

  useInput((input, key) => {
    // Space key for PTT
    if (input === ' ' && !pressedRef.current) {
      pressedRef.current = true;
      setIsPressed(true);
      onStart();
    }
  }, { isActive: true });

  // Key release detection requires stdin in raw mode
  // Ink handles this, but release is detected on next input
  // Alternative: use process.stdin directly for keyup events
}
```

**Note:** True keyup detection in Node.js terminals is limited. Alternatives:
1. Toggle mode: Press once to start, press again to stop
2. Timer-based: Record for N seconds on single press
3. Raw stdin: Custom key event handling with escape sequences

Recommended: Start with **toggle mode** for simplicity, iterate based on UX feedback.

## Development Commands

```bash
# From project root
cd tui

# Development
pnpm tui:dev              # Start with hot reload (ink --watch)
pnpm tui                  # Run TUI

# With local Conduit
pnpm dev:server           # Start Conduit (shared with mobile)
pnpm tui                  # Run TUI

# Testing
pnpm test                 # Unit tests
pnpm test:integration     # Against local Conduit
```

## Shared Code Strategy

**The backend code lives in `src/` and is owned by the Android app.** The TUI imports it directly via TypeScript path aliases.

### Rules of Engagement

1. **Android is the source of truth** - All Matrix-related logic changes happen in `src/`
2. **TUI never forks backend code** - If something doesn't work for TUI, refactor the Android code to be platform-agnostic
3. **Platform abstractions go in `src/`** - Interfaces like `CredentialStorage` are defined alongside the services that use them
4. **TUI provides adapters** - Platform-specific implementations (e.g., `KeytarCredentialStorage`) live in `tui/src/services/`

### Import Flow

```
tui/src/App.tsx
    │
    ├── imports from @shared/ (../src/)
    │   ├── MatrixService.ts      ✓ shared
    │   ├── useRooms.ts           ✓ shared
    │   ├── useVoiceMessages.ts   ✓ shared
    │   └── types/*               ✓ shared
    │
    └── imports from local (tui/src/)
        ├── TuiAudioService.ts    ✗ platform-specific
        ├── KeytarCredentialStorage.ts  ✗ platform-specific
        └── views/*               ✗ UI layer
```

### Why Not a Monorepo?

A formal monorepo (pnpm workspaces, Lerna, Nx) adds complexity:
- React Native's Metro bundler has monorepo quirks
- Shared packages need publishing/linking
- Overkill for two frontends

Instead, we use **direct imports with path aliases**:
- Simpler tooling
- No build step for shared code
- Changes to `src/` immediately available to TUI

## Testing Strategy

| Test Type | Framework | Focus |
|-----------|-----------|-------|
| Unit | Jest | Services, hooks, utilities |
| Component | Ink testing utilities | View rendering |
| Integration | Jest + Conduit | Matrix operations |
| E2E | Manual | Full user flows |

Integration tests can share `test/integration/` setup from mobile app.

## Open Questions

1. **PTT UX**: Toggle vs hold-to-record? Terminal limitations may require toggle.
2. **Audio Dependencies**: Require FFmpeg/SoX pre-installed, or bundle binaries?
3. **Cross-platform**: Focus macOS first, extend to Linux later?

## Success Criteria

- [ ] Login and sync with Matrix homeserver
- [ ] Display family members from family room membership
- [ ] PTT recording from main view (Space to talk)
- [ ] Send voice messages to DM rooms (created on-demand)
- [ ] Status indicators (unread ●, error ⚠)
- [ ] History view for message playback
- [ ] Play voice messages via macOS audio
- [ ] Unread tracking (clear after playback)
- [ ] Family broadcast (send to family room)
- [ ] Integration tests passing against Conduit
- [ ] **Shared backend**: TUI imports `WataService`, hooks, and types from `src/` with zero duplication
