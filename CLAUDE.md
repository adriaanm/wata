# Claude Context

## Working with Claude

- **Commit Policy:** Commit coherent changes as soon as complete. Don't batch unrelated changes.
- **Background Processes:** Prefer tmux (zsh).
- **Planning:** Planning docs go in `docs/planning`. When complete, distill to `docs/` as a guide.

---

## Documentation Guide

| Doc | When to Read |
|-----|--------------|
| [quickstart](docs/quickstart.md) | First time setup, daily workflow |
| [android-development](docs/android-development.md) | Working on native Kotlin Android app |
| [voice](docs/voice.md) | Audio recording/encoding architecture |
| [family-model](docs/family-model.md) | Room architecture, Matrix concepts |
| [tui-architecture](docs/tui-architecture.md) | Terminal UI frontend design |
| [testing](docs/testing.md) | Test strategy, running tests |
| [roadmap](docs/roadmap.md) | Future work, v1/v2 requirements |
| [device-automation](docs/device-automation.md) | Physical device testing workflow |
| [matrix-servers](docs/matrix-servers.md) | Matrix server comparison |
| [coding-rules](docs/coding-rules.md) | TUI logging guidelines |

---

## Project Overview

**Walkie-talkie voice messaging app** on Matrix protocol.

**Frontends:** Android (native Kotlin), TUI (Ink/terminal), Web (Vite).
**Target:** ABBREE Zello handhelds (1.77" screen, D-pad, PTT button).

**Stack:**
- Android: Kotlin + Jetpack Compose + OkHttp
- TUI/Web: TypeScript + matrix-js-sdk
- Audio: Ogg Opus at 16kHz

---

## Essential Commands

```bash
# Android
pnpm android                  # Build + install on device
cd src/android && ./gradlew assembleDebug    # Build APK only

# TUI
pnpm tui                      # Run terminal UI
pnpm tui:dev                  # Watch mode

# Web
pnpm web                      # Dev server
pnpm web:build                # Production build

# Dev helpers
pnpm dev:server               # Start Conduit Matrix server
pnpm dev:forward              # ADB port forwarding (physical devices)
pnpm dev:ip                   # Show local IP (fallback)

# Testing
pnpm test:integration         # Run integration tests
pnpm check                    # typecheck + lint + format
```

---

## Critical Rules

### TUI Logging
**Never use `console.log/warn/error` in TUI** — corrupts Ink UI.
```typescript
// ✅ DO
import { LogService } from './services/LogService.js';
LogService.getInstance().addEntry('log', 'Something happened');

// ❌ DON'T
console.log('Something happened');
```

### Android Credentials
Edit `src/android/app/src/main/java/com/wata/client/MatrixConfig.kt` to change user/server.

### Physical Device Testing
- Run `pnpm dev:forward` after connecting device
- App uses `localhost:8008` (ADB reverse proxies to host)
- Fallback: `pnpm dev:ip` then update MatrixConfig.kt with IP

---

## Project Structure

```
src/
├── android/          # Native Kotlin (Gradle)
├── shared/           # TS code shared by TUI/Web
└── tui/              # Terminal UI (Ink)
└── web/              # Web app (Vite)
```

**Path aliases:** `@shared/*` imports from `src/shared/`.

**Workspaces:** `@wata/shared`, `@wata/tui`, `@wata/web`.

---

## Quick Reference

| Topic | Location |
|-------|----------|
| PTT button codes | `docs/android-development.md` (Hardware Keys) |
| Opus audio format | `docs/voice.md` |
| Matrix room types | `docs/family-model.md` |
| TUI component patterns | `docs/tui-architecture.md` |
| Conduit URL fix | `src/shared/lib/fixed-fetch-api.ts` |
