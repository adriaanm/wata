# Wata TUI - Terminal Voice Messaging

A terminal-based frontend for Wata PTT voice messaging, built with [Ink](https://github.com/vadimdemedes/ink).

## Features

- **Matrix Integration**: Uses shared backend from Android app (zero code duplication)
- **Voice Messages**: Record and play voice messages via terminal
- **Keyboard Navigation**: Full D-pad style navigation (↑↓/jk, Enter, Esc)
- **macOS Native Audio**: Uses `afplay` for playback, `sox` + `ffmpeg` for recording

## Prerequisites

### Required

- **Node.js 22+** (for `Promise.withResolvers` and matrix-js-sdk compatibility)
- **npm** or **yarn**

### Audio Tools (macOS)

```bash
# Install Sox (for audio recording)
brew install sox

# Install FFmpeg (for AAC encoding)
brew install ffmpeg

# afplay is built-in to macOS (for playback)
```

## Installation

```bash
cd tui
npm install
```

## Configuration

The TUI uses the same configuration as the Android app. Edit `../src/config/matrix.ts` to set:

- Homeserver URL (default: `http://localhost:8008`)
- Username (default: `alice`)
- Password (default: `testpass123`)

## Development

```bash
# Start with hot reload
npm run dev

# Start local Matrix server (from project root)
npm run dev:server
```

## Usage

### Keyboard Controls

#### Contact List Screen

| Key | Action |
|-----|--------|
| `↑` / `k` | Move selection up |
| `↓` / `j` | Move selection down |
| `Enter` | Open selected contact |
| `q` / `Ctrl+C` | Quit application |

#### Chat Screen

| Key | Action |
|-----|--------|
| `↑` / `k` | Select previous message |
| `↓` / `j` | Select next message |
| `Enter` | Play/pause selected message |
| `Space` | Start/stop recording (toggle PTT) |
| `Esc` / `Backspace` | Return to contacts |

### PTT Recording

1. Press `Space` to start recording
2. Recording timer shows duration
3. Press `Space` again to stop and send
4. Message uploads automatically to Matrix

## Architecture

The TUI shares the Matrix backend with the Android app:

```
tui/src/
├── views/          # Terminal UI screens (Ink components)
├── components/     # Reusable UI components
├── hooks/          # React hooks for state management
├── services/       # Platform-specific services (audio, credentials)
└── [imports]       # Shared backend from ../src/
    ├── MatrixService.ts   # Core Matrix logic
    ├── hooks/             # Shared hooks
    └── types/             # Shared interfaces
```

### Shared Backend

- `@shared/services/MatrixService` - Matrix client, auth, messaging
- `@shared/hooks/useMatrix` - Sync, rooms, voice messages hooks
- `@shared/types/*` - MatrixRoom, VoiceMessage interfaces

### Platform-Specific

- `TuiAudioService` - macOS audio recording/playback (sox/ffmpeg/afplay)
- `KeytarCredentialStorage` - macOS Keychain for secure credentials
- Ink components for terminal rendering

## Building

```bash
npm run build
```

Output: `dist/index.js`

## Troubleshooting

### "rec: command not found"

Install Sox:

```bash
brew install sox
```

### "ffmpeg: command not found"

Install FFmpeg:

```bash
brew install ffmpeg
```

### Audio playback fails

Check that the Matrix server is reachable and audio URLs are valid HTTP(S) URLs.

### Credentials not storing

The TUI uses macOS Keychain via `keytar`. Ensure keychain access is enabled.

## Future Enhancements

- Hold-to-record PTT (requires raw stdin mode)
- Linux support (use `arecord` + `aplay` instead of macOS tools)
- Playback progress bar
- Message timestamps in chat view
- User avatars (Unicode/emoji representations)
