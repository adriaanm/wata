# TUI Frontend Architecture

A terminal-based frontend for macOS that mirrors the Android PTT voice messaging app.

## Overview

The TUI frontend provides a keyboard-driven interface for Matrix voice messaging, designed to closely replicate the mobile app's UX in a terminal environment. **Both frontends share the same backend code** for Matrix operations, ensuring identical protocol behavior.

## Shared Backend Principle

**The Android app is the reference implementation.** The TUI frontend must use the same backend services and types to guarantee consistent Matrix behavior across platforms.

### What is Shared (in `src/`)

| Module | Purpose | Notes |
|--------|---------|-------|
| `services/MatrixService.ts` | Matrix client, auth, sync, messaging | Core backend - must be identical |
| `hooks/useMatrixSync.ts` | Sync state subscription | Shared logic |
| `hooks/useRooms.ts` | Room list subscription | Shared logic |
| `hooks/useVoiceMessages.ts` | Message list subscription | Shared logic |
| `types/*.ts` | `MatrixRoom`, `VoiceMessage`, etc. | Shared interfaces |
| `config/matrix.ts` | Homeserver URL, credentials | Shared config |
| `lib/fixed-fetch-api.ts` | Conduit workarounds | Shared workaround |

### What is Platform-Specific

| Android (`src/`) | TUI (`tui/src/`) |
|------------------|------------------|
| `services/AudioService.ts` (RN audio) | `services/TuiAudioService.ts` (Node.js audio) |
| `screens/*.tsx` (React Native) | `views/*.tsx` (Ink) |
| `components/*.tsx` (RN components) | `components/*.tsx` (Ink components) |
| `hooks/useAudioRecorder.ts` | `hooks/useAudioRecorder.ts` (reimplemented) |
| `hooks/useAudioPlayer.ts` | `hooks/useAudioPlayer.ts` (reimplemented) |

### Abstraction Strategy

Platform-specific code (credential storage, audio) uses **adapter interfaces**:

```typescript
// src/services/CredentialStorage.ts (interface)
export interface CredentialStorage {
  store(username: string, password: string): Promise<void>;
  retrieve(): Promise<{username: string; password: string} | null>;
  clear(): Promise<void>;
}

// Android: src/services/RNCredentialStorage.ts
// Uses @react-native-keychain

// TUI: tui/src/services/KeytarCredentialStorage.ts
// Uses keytar (macOS Keychain)
```

MatrixService accepts these adapters via dependency injection, keeping the core logic identical.

## Technology Stack

| Layer | Technology | Rationale |
|-------|------------|-----------|
| Runtime | Node.js 22+ | Matches mobile requirements, shared dependencies |
| TUI Framework | [Ink](https://github.com/vadimdemedes/ink) | React-based (familiar patterns), composable, TypeScript |
| Audio Recording | [node-record-lpcm16](https://www.npmjs.com/package/node-record-lpcm16) + FFmpeg | Cross-platform, AAC encoding via FFmpeg |
| Audio Playback | [play-sound](https://www.npmjs.com/package/play-sound) or `afplay` | macOS native player, simple API |
| Matrix Client | `matrix-js-sdk` | Same SDK as mobile app |
| Keypress | Ink built-in `useInput()` | Handles raw keyboard input |
| Storage | `keytar` | macOS Keychain for credentials (like RN Keychain) |

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                        TUI Application                          │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              Ink Components (tui/src/)                   │   │
│  │  ┌─────────────────┐    ┌─────────────────────────────┐ │   │
│  │  │ ContactListView │───▶│       ChatView              │ │   │
│  │  │ - Room list     │    │ - Message list              │ │   │
│  │  │ - Focus state   │    │ - Recording status          │ │   │
│  │  │ - Selection     │    │ - PTT indicator             │ │   │
│  │  └─────────────────┘    └─────────────────────────────┘ │   │
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
│  │  │  MatrixService (core backend)                     │   │   │
│  │  │  - matrix-js-sdk                                  │   │   │
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
│   │   ├── MatrixService.ts      # Core Matrix logic (shared)
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
│   │   └── fixed-fetch-api.ts    # Shared Conduit workaround
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
│       │   ├── ContactListView.tsx   # Room list screen
│       │   ├── ChatView.tsx          # Voice message screen
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
import { MatrixService } from '@shared/services/MatrixService';
import { useRooms } from '@shared/hooks/useRooms';
import type { MatrixRoom } from '@shared/types/MatrixRoom';
```

## Screen Mapping

### Mobile → TUI Equivalence

| Mobile Screen | TUI View | Key Bindings |
|---------------|----------|--------------|
| ContactListScreen | ContactListView | `↑/↓` navigate, `Enter` select, `q` quit |
| ChatScreen | ChatView | `Space` PTT (hold), `↑/↓` navigate, `Enter` play, `Esc` back |

### ContactListView Layout

```
┌─────────────────────────────────────────┐
│  WATA - Contacts                   [q]  │
├─────────────────────────────────────────┤
│  ▶ Alice                                │
│    "Voice message"          2 min ago   │
│  ─────────────────────────────────────  │
│    Bob                                  │
│    "Voice message"          1 hour ago  │
│  ─────────────────────────────────────  │
│    Charlie                              │
│    "Voice message"          Yesterday   │
├─────────────────────────────────────────┤
│  ↑↓ Navigate  Enter Select  q Quit      │
└─────────────────────────────────────────┘
```

### ChatView Layout

```
┌─────────────────────────────────────────┐
│  ← Alice                                │
├─────────────────────────────────────────┤
│  ● REC 0:05                             │  ← Recording indicator
├─────────────────────────────────────────┤
│  Alice        [▶]  0:12      10:30 AM   │
│  You               0:08      10:31 AM   │
│  Alice        [▶]  0:15      10:32 AM   │
│▶ You               0:10      10:33 AM   │  ← Selected message
├─────────────────────────────────────────┤
│  Space PTT  Enter Play  Esc Back        │
└─────────────────────────────────────────┘
```

## Key Bindings

| Context | Key | Action |
|---------|-----|--------|
| Global | `q` / `Ctrl+C` | Quit application |
| Global | `Ctrl+L` | Refresh/redraw |
| ContactList | `↑` / `k` | Move selection up |
| ContactList | `↓` / `j` | Move selection down |
| ContactList | `Enter` | Open selected contact |
| Chat | `↑` / `k` | Select previous message |
| Chat | `↓` / `j` | Select next message |
| Chat | `Enter` | Play/pause selected message |
| Chat | `Space` (hold) | Push-to-talk record |
| Chat | `Esc` / `Backspace` | Return to contacts |

## Implementation Strategy

### Phase 1: Project Setup & Backend Abstraction

1. Initialize `tui/` directory with package.json
2. Set up TypeScript with path aliases to `../src/`
3. **Refactor Android's MatrixService** to accept a `CredentialStorage` adapter (dependency injection)
4. Create `KeytarCredentialStorage.ts` implementing the adapter interface
5. Verify shared imports work: `MatrixService`, types, config
6. Create basic App shell with navigation state
7. Implement LoadingView for startup

**Deliverable:** TUI app that logs in using shared MatrixService

### Phase 2: Contact List Screen

1. Import shared `useMatrixSync` and `useRooms` hooks (no porting needed)
2. Build ContactListView with FocusableItem components
3. Implement keyboard navigation (useInput)
4. Style with terminal colors matching mobile theme

**Deliverable:** Functional contact list using shared hooks

### Phase 3: Chat View (Display Only)

1. Import shared `useVoiceMessages` hook (no porting needed)
2. Build ChatView with MessageItem components
3. Implement message list navigation
4. Add Header and StatusBar components

**Deliverable:** Chat screen showing voice messages via shared backend

### Phase 4: Audio Playback

1. Implement TuiAudioService playback methods
2. Use `afplay` (macOS) for audio output
3. Wire up Enter key to play selected message
4. Show playback state in UI

**Deliverable:** Can play voice messages

### Phase 5: Audio Recording & PTT

1. Implement TuiAudioService recording methods
2. Use `node-record-lpcm16` → FFmpeg pipeline
3. Wire up Space key for PTT (keydown/keyup detection)
4. Implement RecordingStatus component with timer
5. Upload and send via MatrixService

**Deliverable:** Full PTT voice messaging

### Phase 6: Polish & Testing

1. Error handling and edge cases
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

### MatrixService Refactoring (Android)

To enable sharing, the Android `MatrixService.ts` must be refactored to remove platform-specific dependencies:

**Before (direct dependency):**
```typescript
// src/services/MatrixService.ts (Android)
import * as Keychain from 'react-native-keychain';

class MatrixService {
  async storeCredentials(user: string, pass: string) {
    await Keychain.setGenericPassword(user, pass);  // RN-specific!
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

class MatrixService {
  constructor(private credentials: CredentialStorage) {}

  async storeCredentials(user: string, pass: string) {
    await this.credentials.store(user, pass);  // Platform-agnostic
  }
}

// src/services/RNCredentialStorage.ts (Android adapter)
import * as Keychain from 'react-native-keychain';
export class RNCredentialStorage implements CredentialStorage { ... }

// tui/src/services/KeytarCredentialStorage.ts (TUI adapter)
import keytar from 'keytar';
export class KeytarCredentialStorage implements CredentialStorage { ... }
```

**Other platform abstractions needed:**

| Dependency | Abstraction |
|------------|-------------|
| `react-native-keychain` | `CredentialStorage` interface |
| `react-native-fs` | Node.js `fs` (conditional import or abstraction) |
| `fetch` | Works in both (Node 18+ has native fetch) |

The goal: **MatrixService.ts has zero React Native imports** and can be imported directly by the TUI.

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
npm run dev              # Start with hot reload (ink --watch)
npm run build            # Compile TypeScript

# With local Conduit
npm run dev:server       # Start Conduit (shared with mobile)
npm run start            # Run compiled TUI

# Testing
npm test                 # Unit tests
npm run test:integration # Against local Conduit
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

A formal monorepo (npm workspaces, Lerna, Nx) adds complexity:
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
- [ ] Display contact list with keyboard navigation
- [ ] Display voice messages in chat view
- [ ] Play voice messages via macOS audio
- [ ] Record and send voice messages via PTT
- [ ] Visual parity with mobile app (within terminal constraints)
- [ ] Integration tests passing against Conduit
- [ ] **Shared backend**: TUI imports `MatrixService`, hooks, and types from `src/` with zero duplication
