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
│   ├── PttButton.tsx             # Desktop PTT button (inline in ContactCard)
│   ├── RecordingIndicator.tsx    # Recording state banner ✅
│   ├── LoginView.tsx             # Authentication form ✅
│   ├── LoadingView.tsx           # Loading/auth state ✅
│   ├── HistoryView.tsx           # Message playback (TODO)
│   ├── MessageItem.tsx           # Voice message row (TODO)
│   └── admin/
│       ├── AdminDrawer.tsx       # Side drawer (desktop) (TODO)
│       ├── AdminSheet.tsx        # Bottom sheet (mobile) (TODO)
│       ├── FamilyManager.tsx     # Add/remove members (TODO)
│       ├── InviteFlow.tsx        # QR code / link invite (TODO)
│       └── SettingsPanel.tsx     # Config, profile, logs (TODO)
│
├── hooks/
│   ├── usePtt.ts                 # PTT logic (keyboard + touch) ✅
│   ├── useAudioRecorder.ts       # WebAudio API integration (stub)
│   ├── useAudioPlayer.ts         # Web Audio API playback (TODO)
│   ├── useContactSelection.ts    # Keyboard selection state ✅
│   ├── useContactStatus.ts       # Unread/error tracking (TODO)
│   ├── useMatrix.ts              # Matrix integration hooks ✅ (useAuth, useMatrixSync, useRooms)
│   └── useContacts.ts            # Build contacts from Matrix data ✅
│
├── services/
│   ├── matrixService.ts          # Web-specific MatrixService singleton ✅
│   ├── WebAudioService.ts        # AudioWorklet recording ✅ (stub)
│   ├── OpusEncoder.ts            # WebAssembly Opus (TODO - Phase 3)
│   ├── WebCredentialStorage.ts   # localStorage adapter ✅
│   └── LogService.ts             # Web logging adapter ✅
│
├── styles/
│   ├── variables.css             # Design tokens ✅
│   ├── contact-card.css          # Contact card styles ✅
│   └── animations.css            # Ripple, dimming transitions ✅
│
├── worklets/                     # AudioWorklet processors (TODO - Phase 3)
│
└── data/
    └── mockData.ts               # Mock contacts for UI development ✅
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

### Phase 3: Audio Pipeline (TODO)

**Remaining:**
- AudioWorklet processor (`worklets/ptt-processor.ts`)
- Opus WebAssembly encoding
- Ogg container muxing
- Web Audio API playback
- Real Microphone access via MediaRecorder

### Phase 4: History & Feedback (TODO)

**Remaining:**
- HistoryView component for message playback
- MessageItem component
- Real unread/error indicators from Matrix
- Message playback functionality

### Phase 5: Admin Interface (TODO)

**Remaining:**
- Admin drawer/sheet navigation
- Family management UI
- Member invite flow
- Settings panel
- Device onboarding

---

## Implementation Phases (Updated)

**Phase 1: Foundation** ✅ COMPLETE
- ✅ Set up Vite + React project
- ✅ TypeScript configuration
- ✅ WebCredentialStorage adapter
- ⏸️ Basic Matrix connection (deferred - using mock)

**Phase 2: Core UI** ✅ COMPLETE
- ✅ ContactCard component (touch targets)
- ✅ Selection state (keyboard navigation)
- ✅ Touch and hold recording (UI only)
- ✅ Desktop PTT buttons
- ✅ Recording animations (ripple, dimming)
- ✅ Recording indicator banner

**Phase 3: Audio Pipeline** (NEXT)
- AudioWorklet processor
- Opus WebAssembly integration
- Ogg container muxing
- Web Audio API playback
- Matrix voice message sending

**Phase 4: History & Feedback**
- HistoryView component
- Message playback
- Real unread/error indicators from Matrix
- Read receipts

**Phase 5: Admin Interface**
- Admin drawer/sheet
- Family management
- Device onboarding
- Settings panel

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

### Audio Recording

Current `WebAudioService` uses MediaRecorder stub. Phase 3 will implement:
- AudioWorklet for separate-thread recording
- Opus WASM encoding for consistent cross-browser output
- Ogg container muxing

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

The current implementation (Phases 1-2.5) has real Matrix integration:

```bash
cd src/web
pnpm install
pnpm web
```

Navigate to http://localhost:3000

**Authentication:**
- First visit: Login form appears (enter Matrix username/password)
- Subsequent visits: Auto-logs in via stored credentials
- Requires a running Matrix server (e.g., local Conduit)

**Try it out:**
- **Desktop:** Use arrow keys to select contacts, hold Space to record
- **Desktop:** Hover over contacts to see PTT button, click and hold to record
- **Mobile:** Tap and hold contact cards to record
- Visual feedback shows recording state with ripple animation and dimmed contacts

**Note:** Voice message recording currently uses a stub (Phase 3 will implement real audio).

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
