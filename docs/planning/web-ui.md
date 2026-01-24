# Web UI Planning Document

## Overview

The web UI extends the WATA platform with a browser-based interface. Its primary role is **administration and onboarding** (family management, device setup, configuration) while maintaining full walkie-talkie functionality.

**Key principles:**
- Contacts are the primary interaction surface (big PTT targets)
- Keyboard-first on desktop (space bar to talk, like TUI)
- Touch-first on mobile (tap and hold contacts)
- Admin features available on all screen sizes

---

## Technology Stack

### Core Framework
- **React + Vite** - Fast development server, native ESM, excellent TypeScript support
- **CSS Modules + CSS Variables** - Scoped styles, design tokens for theming
- **React Context + hooks** - State management (reuse existing patterns from `src/shared/`)

### Audio Architecture
- **AudioWorklet** - Recording in separate thread (no main thread blocking)
- **libopus WebAssembly** - Consistent Opus encoding across all browsers
- **Web Audio API** - Low-latency playback

### Shared Code
Everything in `src/shared/` is reused directly:
- `MatrixService` - Core Matrix client logic
- `useMatrixSync`, `useRooms`, `useVoiceMessages` - React hooks
- Types, config, utilities

Platform-specific adapters:
- `WebAudioService` - AudioWorklet-based recording/playback
- `WebCredentialStorage` - localStorage credential storage
- `OpusEncoder` - WebAssembly Opus encoding

---

## UI Layout

### Design Philosophy

**Contacts as PTT Targets**

Each contact is a large, touchable area. Touch and hold to record a voice message. This mirrors the physical walkie-talkie experience where you select a channel and push the button.

```
┌─────────────────────────────────────────────────────────┐
│  WATA                                    [≡ Admin]     │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  ┌─────────────────────────────────────────────────┐   │
│  │                                                   │   │
│  │              Mom                                  │   │  ← Big touch target
│  │             ● 2 new                               │   │
│  │                                                   │   │
│  └─────────────────────────────────────────────────┘   │
│                                                          │
│  ┌─────────────────────────────────────────────────┐   │
│  │                                                   │   │
│  │              Dad                                  │   │
│  │                                                   │   │
│  └─────────────────────────────────────────────────┘   │
│                                                          │
│  ┌─────────────────────────────────────────────────┐   │
│  │              Family                               │   │
│  │             ⚠ Send error                          │   │
│  │                                                   │   │
│  └─────────────────────────────────────────────────┘   │
│                                                          │
├─────────────────────────────────────────────────────────┤
│  ↑↓ Select  Space Talk  Enter History  ≡ Admin         │  ← Desktop hints
└─────────────────────────────────────────────────────────┘
```

### Recording State

When recording, show visual feedback:
1. Ripple/wave animation from touch point
2. Contact highlight expands
3. Other contacts dim (reduce opacity)
4. Recording indicator appears

```
┌─────────────────────────────────────────────────────────┐
│  ● REC 0:05  Release to send → Mom                      │  ← Recording banner
├─────────────────────────────────────────────────────────┤
│                                                          │
│  ┌─────────────────────────────────────────────────┐   │
│  │  ══════════════════════════════════════════════    │   │  ← Recording ripple
│  │                                                   │   │
│  │              Mom                                  │   │  ← Active contact
│  │             ● 2 new                               │   │     (bright, expanded)
│  │                                                   │   │
│  └─────────────────────────────────────────────────┘   │
│                                                          │
│  ┌─────────────────────────────────────────────────┐   │
│  │                                                   │   │
│  │              Dad          [dimmed: 40% opacity]   │   │  ← Dimmed contacts
│  │                                                   │   │
│  └─────────────────────────────────────────────────┘   │
│                                                          │
│  ┌─────────────────────────────────────────────────┐   │
│  │              Family        [dimmed: 40% opacity]  │   │
│  │                                                   │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

### Desktop Interaction

**Primary: Keyboard navigation (TUI-style)**

- `↑`/`↓` or `j`/`k` - Select contact
- `Space` (hold) - Record and send to selected contact
- `Enter` - View message history
- `Esc` - Back to main view

**Secondary: Mouse/trackpad**

Each contact has a PTT button for users who prefer clicking:

```
┌─────────────────────────────────────────────────────────┐
│  WATA                                    [≡ Admin]     │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  ┌─────────────────────────────────────────────────┐   │
│  │  Mom                                    [ 🎤 ]   │   │  ← PTT button on hover
│  │  ● 2 new                                         │   │
│  └─────────────────────────────────────────────────┘   │
│                                                          │
│  ┌─────────────────────────────────────────────────┐   │
│  │  Dad                                    [ 🎤 ]   │   │
│  └─────────────────────────────────────────────────┘   │
│                                                          │
│  ┌─────────────────────────────────────────────────┐   │
│  │  Family                                 [ 🎤 ]   │   │
│  │  ⚠ Send error                                     │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

The PTT button:
- Appears on hover (desktop) or is always visible (mobile)
- Positioned at the right edge of each contact card
- Same touch-and-hold behavior as the entire card
- Visual feedback matches full-card recording

### Responsive Behavior

**Mobile (< 768px):**
- Full-width contact cards
- PTT button always visible (right-aligned)
- Bottom sheet for admin menu
- Touch hints only (no keyboard hints in footer)

**Desktop (≥ 768px):**
- Centered layout with max-width (600px)
- PTT button on hover only
- Keyboard hints in footer
- Side drawer or modal for admin menu

### Selection State

Keyboard navigation shows which contact is selected:

```
┌─────────────────────────────────────────────────────────┐
│  WATA                                    [≡ Admin]     │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  ┌─────────────────────────────────────────────────┐   │
│  │  Mom                                            │   │  ← Unselected
│  │  ● 2 new                                         │   │
│  └─────────────────────────────────────────────────┘   │
│                                                          │
│  ┌─────────────────────────────────────────────────┐   │
│  │  ▶ Dad                                           │   │  ← Selected (cursor)
│  │                                                  │   │
│  └─────────────────────────────────────────────────┘   │
│      ↑ Selection indicator (left border or icon)       │
│                                                          │
│  ┌─────────────────────────────────────────────────┐   │
│  │  Family                                          │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

Selection indicators:
- Left border accent color
- Small arrow/caret icon
- Slightly elevated (shadow)
- Keyboard hint shows target name

---

## Component Structure

```
web/src/
├── components/
│   ├── MainView.tsx              # Contact list + PTT ✅
│   ├── ContactCard.tsx           # Individual contact (PTT target) ✅
│   ├── RecordingIndicator.tsx    # Recording state banner ✅
│   ├── LoginView.tsx             # Authentication form ✅
│   ├── LoadingView.tsx           # Loading/auth state ✅
│   ├── AudioCodeTestHarness.tsx  # AudioCode testing UI ✅
│   ├── HistoryView.tsx           # Message history screen (Phase 4)
│   ├── MessageItem.tsx           # Voice message row (Phase 4)
│   └── admin/
│       ├── AdminDrawer.tsx       # Side drawer (desktop) (Phase 5)
│       ├── AdminSheet.tsx        # Bottom sheet (mobile) (Phase 5)
│       ├── FamilyManager.tsx     # Member list/removal (Phase 5)
│       ├── InviteFlow.tsx        # QR code / link invite (Phase 5)
│       ├── DeviceManager.tsx     # Device list/revocation (Phase 5)
│       ├── SettingsPanel.tsx     # User config (Phase 5)
│       └── LogsPanel.tsx         # Diagnostics (Phase 5)
│
├── hooks/
│   ├── usePtt.ts                 # PTT orchestration ✅
│   ├── useAudioRecorder.ts       # Recording state wrapper ✅
│   ├── useAudioPlayer.ts         # Playback with progress/seek/volume ✅
│   ├── useContactSelection.ts    # Keyboard navigation ✅
│   ├── useMatrix.ts              # Matrix hooks (useAuth, useMatrixSync, useRooms) ✅
│   └── useContacts.ts            # Build contacts from Matrix ✅
│
├── services/
│   ├── matrixService.ts          # Web MatrixService singleton ✅
│   ├── WebAudioService.ts        # MediaRecorder + HTML Audio ✅
│   ├── OnboardingAudioService.ts # AudioCode wrapper ✅
│   ├── WebCredentialStorage.ts   # localStorage adapter ✅
│   └── LogService.ts             # Web logging ✅
│
├── styles/
│   ├── variables.css             # Design tokens ✅
│   ├── contact-card.css          # Contact card styles ✅
│   └── animations.css            # Ripple, dimming transitions ✅
│
└── data/
    └── mockData.ts               # Mock contacts for testing ✅
```

---

## Progress & Implementation Status

### Phase 1: Foundation ✅ COMPLETE

**Completed:**
- ✅ Set up Vite + React project
- ✅ TypeScript configuration
- ✅ CSS design tokens (`styles/variables.css`)
- ✅ WebCredentialStorage adapter (`services/WebCredentialStorage.ts`)
- ✅ Project structure with proper directories

**Completed in Phase 2.5:**
- ✅ TypeScript path aliases to `src/shared/` - Proper Vite configuration
- ✅ Real Matrix connection - Full integration with shared MatrixService
- ✅ Buffer polyfill - Added for Matrix SDK compatibility
- ✅ LoginView component - Authentication form
- ✅ Session persistence - Auto-login via stored credentials

### Phase 2: Core UI ✅ COMPLETE

**Completed:**
- ✅ ContactCard component with PTT interactions (`components/ContactCard.tsx`)
  - Touch and hold recording
  - Visual feedback (ripple animation, dimming)
  - Desktop PTT button (on hover)
- ✅ MainView component with keyboard navigation (`components/MainView.tsx`)
  - Arrow keys / j/k for selection
  - Space bar for PTT (global)
  - Visual selection indicator
- ✅ RecordingIndicator component (`components/RecordingIndicator.tsx`)
  - Recording banner with duration
  - Target contact display
- ✅ LoadingView component (`components/LoadingView.tsx`)
  - Loading spinner
  - Auth state handling (stub)
- ✅ CSS animations (`styles/animations.css`)
  - Ripple effect
  - Recording pulse
  - Contact dimming
  - Status indicators
- ✅ Mock data (`data/mockData.ts`)
  - Sample contacts with different states (unread, error)
- ✅ Hooks for interaction
  - `usePtt` - PTT logic with keyboard/touch support
  - `useContactSelection` - Keyboard navigation state

**Deferred:**
- Audio recording (uses MediaRecorder stub)

### Phase 2.5: Auth & Real Matrix Integration ✅ COMPLETE

**Completed:**
- ✅ LoginView component (`components/LoginView.tsx`)
  - Username/password form
  - Loading state with spinner
  - Error display
  - Auto-focus on username field
- ✅ App.tsx authentication flow
  - Conditional rendering (loading → login → main)
  - Real contacts from `useContacts` hook
  - Session restoration
- ✅ Shared module resolution fix
  - Removed `@tui/services/LogService` import from shared code
  - Added `Logger` interface with `setLogger()` function
  - Web and TUI wire up their own LogService implementations
- ✅ WebCredentialStorage fully functional
  - Session persistence via localStorage
  - Auto-login on revisit

**Implementation Details:**
The shared code (`MatrixService.ts`, `matrix-auth.ts`) no longer imports platform-specific LogService. Instead, it provides a `Logger` interface and `setLogger()` function. Each platform (web, TUI) injects its own logger implementation.

**Files Modified:**
- `src/shared/services/MatrixService.ts` - Removed LogService import, added Logger interface
- `src/shared/lib/matrix-auth.ts` - Removed LogService import, added Logger interface
- `src/web/src/App.tsx` - Wired up auth flow and real contacts
- `src/web/src/components/LoginView.tsx` - New login form component
- `src/web/src/services/matrixService.ts` - Wire up web's LogService
- `src/tui/App.tsx` - Wire up TUI's LogService

### Phase 3: Audio Pipeline ✅ COMPLETE

**Completed:**
- ✅ WebAudioService with MediaRecorder API (`services/WebAudioService.ts`)
  - 16kHz mono recording with echo cancellation
  - Browser-native Opus encoding (webm/opus or fallback)
  - Voice-optimized audio constraints
- ✅ useAudioRecorder hook - React wrapper for recording
- ✅ useAudioPlayer hook - Full playback with progress/seek/volume
- ✅ usePtt hook - Complete PTT flow with Matrix integration
- ✅ Matrix voice message sending via `matrixService.sendVoiceMessage()`

**Implementation Note:**
We chose the simpler MediaRecorder approach over AudioWorklet + WASM Opus. Benefits:
- No extra dependencies (no libopus.wasm)
- Browser handles encoding natively
- Works across Chrome/Firefox/Safari with graceful fallbacks
- Easier to maintain

The AudioWorklet approach is documented but not needed for v1.

### Phase 4: History & Feedback (NEXT - In Progress)

**Goal:** Allow users to view and play back voice message history for each contact.

**Components to Build:**

1. **HistoryView.tsx** - Message history screen
   - Header with contact name and back button
   - Scrollable list of voice messages
   - Auto-scroll to newest on open
   - Keyboard navigation (Enter from MainView opens, Esc goes back)

2. **MessageItem.tsx** - Individual voice message row
   - Sender avatar/name (for group)
   - Timestamp
   - Duration display
   - Play/pause button
   - Progress bar with seek
   - Visual distinction for sent vs received
   - "New" badge for unread messages

3. **useVoiceMessages hook** - Fetch messages from Matrix
   - Already exists in shared: `src/shared/hooks/useVoiceMessages.ts`
   - Need to integrate with web audio player
   - Handle MXC URL → HTTP URL conversion

4. **Unread tracking** - Real unread counts
   - Update `useContacts` to track actual unread counts
   - Mark messages as read when HistoryView opens
   - Badge on ContactCard for unread count

**Implementation Plan:**

```
Step 1: Basic HistoryView shell
- Create HistoryView component with navigation
- Add route state to MainView (main vs history)
- Wire up Enter key and back button

Step 2: MessageItem with playback
- Create MessageItem component
- Integrate useAudioPlayer for playback
- Display timestamp, duration, play/pause

Step 3: Fetch real messages
- Wire useVoiceMessages to HistoryView
- Convert MXC URLs for playback
- Handle loading/error states

Step 4: Unread tracking
- Add real unread count to useContacts
- Mark as read on history view open
- Show unread badges on ContactCard
```

### Phase 5: Admin Interface (TODO)

**Goal:** Web-based family management and device setup.

**Components to Build:**

1. **Navigation Structure**
   - AdminDrawer.tsx - Slide-in side panel (desktop)
   - AdminSheet.tsx - Bottom sheet (mobile)
   - Menu items: Family, Devices, Settings, Logs

2. **FamilyManager.tsx** - Family room administration
   - List current family members
   - Remove member (kick from room)
   - View member status (online/offline)

3. **InviteFlow.tsx** - Add new family members
   - Generate invite link or QR code
   - Display AudioCode for device onboarding
   - Show pending invites

4. **DeviceManager.tsx** - Registered devices
   - List devices per family member
   - Revoke device access
   - View device metadata

5. **SettingsPanel.tsx** - Configuration
   - User profile (display name, avatar)
   - Audio settings (quality, beep on/off)
   - Homeserver URL (advanced)
   - Export/import config

6. **LogsPanel.tsx** - Diagnostics
   - Matrix sync status
   - Recent errors
   - Connection quality

---

## Implementation Phases (Updated)

**Phase 1: Foundation** ✅ COMPLETE
- ✅ Set up Vite + React project
- ✅ TypeScript configuration
- ✅ WebCredentialStorage adapter

**Phase 2: Core UI** ✅ COMPLETE
- ✅ ContactCard component (touch targets)
- ✅ Selection state (keyboard navigation)
- ✅ Touch and hold recording (UI)
- ✅ Desktop PTT buttons
- ✅ Recording animations (ripple, dimming)
- ✅ Recording indicator banner

**Phase 2.5: Auth & Matrix Integration** ✅ COMPLETE
- ✅ LoginView component
- ✅ Real Matrix connection with shared hooks
- ✅ Session persistence
- ✅ Logger injection pattern for shared code

**Phase 3: Audio Pipeline** ✅ COMPLETE
- ✅ WebAudioService with MediaRecorder
- ✅ useAudioRecorder / useAudioPlayer hooks
- ✅ usePtt hook with Matrix integration
- ✅ Voice message send to Matrix

**Phase 4: History & Feedback** (NEXT)
- HistoryView component
- MessageItem component
- Message playback with progress/seek
- Real unread counts from Matrix
- Mark messages as read

**Phase 5: Admin Interface**
- AdminDrawer/AdminSheet navigation
- FamilyManager (members, removal)
- InviteFlow (links, QR, AudioCode)
- DeviceManager
- SettingsPanel
- LogsPanel

---

## Next Steps: Phase 4 Implementation Details

### Step 4.1: View Navigation State

Add view switching between contacts list and message history.

**Files to modify:**
- `src/web/src/App.tsx` - Add view state management
- `src/web/src/types.ts` - Add view state types

**Implementation:**
```typescript
// types.ts
type ViewState =
  | { view: 'main' }
  | { view: 'history'; contactId: string };

// App.tsx - add view state
const [viewState, setViewState] = useState<ViewState>({ view: 'main' });
```

### Step 4.2: HistoryView Component

Create the message history screen.

**File:** `src/web/src/components/HistoryView.tsx`

**Props:**
```typescript
interface HistoryViewProps {
  contact: Contact;
  onBack: () => void;
}
```

**Features:**
- Header with back button and contact name
- Message list (scrollable, newest at bottom)
- Keyboard: Esc to go back
- Empty state when no messages

**Layout:**
```
┌─────────────────────────────────────────┐
│  ← Back        Mom                      │  Header
├─────────────────────────────────────────┤
│                                         │
│  [You] 10:30 AM                         │
│  ────────────── 0:05 ▶                  │
│                                         │
│  [Mom] 10:32 AM               ● NEW     │
│  ────────────── 0:12 ▶                  │
│                                         │
│  [You] 10:35 AM                         │
│  ────────────── 0:08 ▶                  │
│                                         │
└─────────────────────────────────────────┘
```

### Step 4.3: MessageItem Component

Individual message row with playback controls.

**File:** `src/web/src/components/MessageItem.tsx`

**Props:**
```typescript
interface MessageItemProps {
  message: VoiceMessage;
  isMine: boolean;
  isUnread: boolean;
  onPlay: () => void;
  isPlaying: boolean;
  progress: number; // 0-1
  onSeek: (progress: number) => void;
}
```

**Features:**
- Sender info (avatar if group, "You" for own messages)
- Timestamp display
- Duration display
- Play/pause button
- Progress bar (clickable to seek)
- "NEW" badge for unread
- Different styling for sent vs received

### Step 4.4: Voice Message Fetching

Wire up shared useVoiceMessages hook.

**Integration points:**
- `src/shared/hooks/useVoiceMessages.ts` already exists
- Need to convert MXC URLs to HTTP for web playback:
  ```typescript
  const httpUrl = matrixService.getClient()
    .mxcUrlToHttp(message.mxcUrl, undefined, undefined, undefined, true);
  ```

**State management:**
- Loading state while fetching
- Error handling for failed loads
- Auto-refresh on new messages (via Matrix sync)

### Step 4.5: Unread Count Tracking

Update useContacts to show real unread counts.

**Modifications to `src/web/src/hooks/useContacts.ts`:**
1. Subscribe to Matrix room timeline updates
2. Track unread count per room
3. Update badges when new messages arrive

**Matrix SDK approach:**
```typescript
// Get unread count from room
const room = matrixService.getClient().getRoom(roomId);
const unreadCount = room?.getUnreadNotificationCount() ?? 0;
```

### Step 4.6: Mark as Read

Mark messages as read when history view opens.

**Implementation:**
```typescript
// When HistoryView opens
useEffect(() => {
  const markAsRead = async () => {
    const client = matrixService.getClient();
    const room = client.getRoom(roomId);
    if (room) {
      const timeline = room.getLiveTimeline();
      const lastEvent = timeline.getEvents().at(-1);
      if (lastEvent) {
        await client.sendReadReceipt(lastEvent);
      }
    }
  };
  markAsRead();
}, [roomId]);
```

### Estimated Effort

| Step | Complexity | Dependencies |
|------|------------|--------------|
| 4.1 View Navigation | Low | None |
| 4.2 HistoryView | Medium | 4.1 |
| 4.3 MessageItem | Medium | useAudioPlayer |
| 4.4 Voice Message Fetching | Medium | shared hooks |
| 4.5 Unread Tracking | Medium | Matrix SDK |
| 4.6 Mark as Read | Low | 4.5 |

### Testing Checklist

- [ ] Navigate to history via Enter key on selected contact
- [ ] Navigate back via Esc or back button
- [ ] Play voice messages
- [ ] Progress bar updates during playback
- [ ] Seek by clicking progress bar
- [ ] Unread count shows on contact cards
- [ ] Unread badges disappear after viewing history
- [ ] New messages appear in history in real-time
- [ ] Empty state when no messages

---

## Known Issues & TODOs

### Path Resolution for Shared Code ✅ RESOLVED

**Previous Issue:** Vite could not resolve `@shared/*` imports properly, and shared code imported platform-specific LogService.

**Resolution (Phase 2.5):**
- ✅ Fixed module resolution by removing platform-specific imports from shared code
- ✅ Added `Logger` interface with `setLogger()` for dependency injection
- ✅ Web and TUI each wire up their own LogService implementations
- ✅ Shared code is now truly platform-agnostic

### WebCredentialStorage Security

**TODO:** Add encryption for stored credentials before production deployment.

Current implementation stores credentials in plain text localStorage. For production:
- Use Web Crypto API for encryption
- Store encryption key securely (consider secure context requirements)
- Implement proper session management

### Audio Recording ✅ RESOLVED

The WebAudioService now uses the native MediaRecorder API with browser Opus encoding. This works well across modern browsers and provides:
- 16kHz mono recording with voice optimization
- Native Opus encoding (Chrome/Firefox) or AAC fallback (Safari)
- Echo cancellation, noise suppression, auto-gain

The AudioWorklet + WASM Opus approach is documented but not necessary for v1.

---

## Development Commands

```bash
cd web
pnpm web                 # Start development server (http://localhost:3000)
pnpm web:build           # Build for production
pnpm web:preview         # Preview production build
```

---

## Running the Web UI

The current implementation (Phases 1-3) has full Matrix integration with voice messaging:

```bash
# Terminal 1: Start Conduit server
pnpm dev:server

# Terminal 2: Start web dev server
pnpm web
```

Navigate to http://localhost:3000

**Authentication:**
- First visit: Login form appears (enter Matrix username/password)
- Subsequent visits: Auto-logs in via stored credentials
- Requires a running Matrix server (e.g., local Conduit)

**Voice Messaging (Working!):**
- **Desktop:** Use arrow keys to select contacts, hold Space to record and send
- **Desktop:** Hover over contacts to see PTT button, click and hold to record
- **Mobile:** Tap and hold contact cards to record
- Visual feedback shows recording state with ripple animation and dimmed contacts
- Voice messages are sent to Matrix and can be received by other clients (Element, TUI)

**Current Limitations:**
- No message history view yet (Phase 4)
- No playback of received messages yet (Phase 4)
- Admin features not implemented (Phase 5)

---

## Interaction Patterns

### Touch and Hold (Mobile)

```typescript
// ContactCard.tsx
const handleTouchStart = (e: React.TouchEvent) => {
  e.preventDefault(); // Prevent mouse emulation
  const touch = e.touches[0];
  startRecording({
    x: touch.clientX,
    y: touch.clientY
  });
};

const handleTouchEnd = () => {
  stopRecording();
};

const handleTouchCancel = () => {
  cancelRecording();
};
```

**Visual feedback sequence:**
1. Touch start → Ripple animation begins at touch point
2. 100ms delay → Contact brightens, others dim (confirm recording)
3. Recording duration shown in banner
4. Touch end → Send message
5. Touch move outside card → Cancel (optional)

### Keyboard (Desktop)

```typescript
// MainView.tsx
useEffect(() => {
  const handleKeyDown = (e: KeyboardEvent) => {
    if (e.key === ' ' && !isRecording && !isSpaceHeld) {
      e.preventDefault();
      setIsSpaceHeld(true);
      startRecording();
    }
  };

  const handleKeyUp = (e: KeyboardEvent) => {
    if (e.key === ' ' && isSpaceHeld) {
      e.preventDefault();
      setIsSpaceHeld(false);
      stopRecording();
    }
  };

  window.addEventListener('keydown', handleKeyDown);
  window.addEventListener('keyup', handleKeyUp);
  return () => {
    window.removeEventListener('keydown', handleKeyDown);
    window.removeEventListener('keyup', handleKeyUp);
  };
}, [isRecording, isSpaceHeld, startRecording, stopRecording]);
```

**Behavior:**
- Space bar works globally (no need to focus contact list)
- Selection indicates target contact
- Visual feedback matches touch recording
- Release to send

### Mouse/Trackpad (Desktop)

```typescript
// PttButton.tsx (inline in ContactCard.tsx)
const handleMouseDown = (e: React.MouseEvent) => {
  e.preventDefault();
  startRecording();
};

const handleMouseUp = () => {
  stopRecording();
};

const handleMouseLeave = () => {
  if (isRecording) {
    cancelRecording();
  }
};
```

**PTT button behavior:**
- Hold to record (same as card touch)
- Release to send
- Cursor changes during recording
- Drag outside cancels

---

## Animations and Visual Feedback

### Recording Start Animation

```css
/* Ripple effect from touch point */
@keyframes ripple {
  0% {
    transform: scale(0);
    opacity: 0.6;
  }
  100% {
    transform: scale(4);
    opacity: 0;
  }
}

.recording-ripple {
  position: absolute;
  border-radius: 50%;
  background: radial-gradient(circle, var(--color-recording) 0%, transparent 70%);
  animation: ripple 0.6s ease-out;
}
```

### Dimming Other Contacts

```css
/* Dimmed state for inactive contacts during recording */
.contact-card {
  transition: opacity 0.3s ease, filter 0.3s ease;
}

.contact-card--dimmed {
  opacity: 0.4;
  filter: grayscale(0.5);
  pointer-events: none; /* Prevent interaction during recording */
}

.contact-card--recording {
  opacity: 1;
  filter: none;
  box-shadow: 0 0 20px var(--color-recording-glow);
  transform: scale(1.02);
}
```

### Recording Banner

Fixed-position banner shows recording state:

```typescript
// RecordingIndicator.tsx
<div className="recording-banner">
  <span className="recording-dot">●</span>
  <span>REC</span>
  <span>{formatDuration(recordingDuration)}</span>
  <span>→ {selectedContact.name}</span>
  <span className="recording-hint">
    Release to send
  </span>
</div>
```

---

## Admin Interface

The web UI is the primary admin interface. Accessed via:

- Desktop: Hamburger menu (≡) in top-right → Side drawer
- Mobile: Hamburger menu (≡) in top-right → Bottom sheet

### Admin Features

**Family Management:**
- View family room members
- Invite new members (QR code or share link)
- Remove members
- Member permissions (future)

**Device Management:**
- View registered devices
- Generate device setup codes
- Revoke device access

**Settings:**
- Homeserver configuration
- User profile settings
- Audio quality settings
- Export/import configuration

**Logs & Diagnostics:**
- Matrix sync status
- Message delivery errors
- Connection quality metrics

---

## Accessibility

**Keyboard Navigation:**
- All features accessible via keyboard
- Visible focus indicators
- Shortcut hints in footer

**Screen Reader:**
- ARIA labels for contacts
- Live region for recording state
- Status announcements (errors, new messages)

**Touch:**
- Minimum 44x44px touch targets (WCAG 2.5.5)
- Clear visual feedback on touch
- No hover-dependent interactions on mobile

---

## Design Tokens

```css
/* styles/variables.css */
:root {
  /* Colors */
  --color-background: #0a0a0a;
  --color-surface: #1a1a1a;
  --color-surface-elevated: #252525;
  --color-text: #ffffff;
  --color-text-muted: #888888;
  --color-accent: #00aaff;
  --color-recording: #ff3333;
  --color-recording-glow: rgba(255, 51, 51, 0.3);
  --color-error: #ff9900;
  --color-success: #33ff33;

  /* Typography */
  --font-family: system-ui, -apple-system, sans-serif;
  --font-size-base: 16px;
  --font-size-lg: 20px;
  --font-size-xl: 24px;
  --font-size-2xl: 32px;

  /* Spacing */
  --spacing-xs: 4px;
  --spacing-sm: 8px;
  --spacing-md: 16px;
  --spacing-lg: 24px;
  --spacing-xl: 32px;

  /* Contact Card */
  --contact-card-height: 80px;
  --contact-card-padding: 16px;
  --contact-card-border-radius: 12px;
  --contact-card-gap: 12px;

  /* Transitions */
  --transition-fast: 150ms ease;
  --transition-normal: 300ms ease;
  --transition-slow: 500ms ease;

  /* Breakpoints */
  --breakpoint-mobile: 768px;
  --breakpoint-desktop: 1024px;
}
```

---

## Open Questions

1. **Cancel behavior:** Should dragging outside the contact card cancel recording, or should it require an explicit cancel button?

2. **Recording confirmation:** Should there be a "shake to cancel" or similar gesture, or is releasing outside sufficient?

3. **Multi-select:** Should admin interface support selecting multiple contacts for bulk operations?

4. **Audio feedback:** Should there be a beep/tone when recording starts and stops (like real walkie-talkies)?

5. **Message history persistence:** Should the web UI persist message history locally, or always fetch from Matrix?

---

## References

- **TUI Architecture:** `docs/tui-architecture.md` - Component patterns to reuse
- **Voice Architecture:** `docs/voice.md` - Audio encoding/decoding pipeline
- **Family Model:** `docs/family-model.md` - Family room and DM structure
- **Shared Hooks:** `src/shared/hooks/` - `useMatrixSync`, `useRooms`, `useVoiceMessages`
