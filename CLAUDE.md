# Claude Context

## Workflows

### Reproducibility

All repeated commands go in the `justfile`. Run `just` to list recipes. If you find yourself running a multi-step sequence more than once, capture it as a recipe.

### Task Tracking

`TASKS.md` is the single source of truth for open work. Check it at the start of each session. Mark items `[x]` when done, add new items as discovered. Keep it concise — one line per task, checkbox format. Edit the markdown directly (no tooling needed).

### Commit Granularly

Each logical change gets its own commit — don't batch unrelated work. A "logical change" is one thing you could describe in a single sentence. If you've touched the Zig client, the justfile, and docs in the same session, that's probably 2–3 commits.

### Keep Docs Updated

Before committing any non-trivial code change, do a quick check if docs need to be updated. Planning docs go in `docs/planning`. When complete, distill to `docs/` as a guide.

### Background Processes

Prefer tmux (zsh).

---

## Documentation Guide

| Doc | When to Read |
|-----|--------------|
| [TASKS.md](TASKS.md) | Start of every session — open work items |
| [quickstart](docs/quickstart.md) | First time setup, daily workflow |
| [android-development](docs/android-development.md) | Working on native Kotlin Android app |
| [dm-room-service](docs/dm-room-service.md) | DM room management, m.direct handling |
| [voice](docs/voice.md) | Audio recording/encoding architecture |
| [family-model](docs/family-model.md) | Room architecture, Matrix concepts |
| [tui-architecture](docs/tui-architecture.md) | Terminal UI frontend design |
| [testing](docs/testing.md) | Test strategy, running tests |
| [roadmap](docs/roadmap.md) | Future work, v1/v2 requirements |
| [device-automation](docs/device-automation.md) | Physical device testing workflow |
| [matrix-servers](docs/matrix-servers.md) | Matrix server comparison |
| [coding-rules](docs/coding-rules.md) | TUI logging guidelines |
| [framebuffer-client](docs/planning/framebuffer-client.md) | Zig framebuffer client design |
| [freetype-font-rendering](docs/planning/freetype-font-rendering.md) | FreeType font rendering for wata applet |
| [concurrency-redesign](docs/planning/concurrency-redesign.md) | Mailbox-based actor model for fbclient threads |

---

## Project Overview

**Walkie-talkie voice messaging app** on Matrix protocol.

**Frontends:** Android (native Kotlin), TUI (Ink/terminal), Web (Vite), Framebuffer (Zig).
**Target:** BQ268 handhelds (128×160 SPI display, D-pad, PTT button).

**Stack:**
- Android: Kotlin + Jetpack Compose + OkHttp
- TUI/Web: TypeScript + matrix-js-sdk
- Framebuffer: Zig 0.16-dev + SDL2 (dev) / fbdev (device) + FreeType (font rendering)
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

# Framebuffer client (Zig)
pnpm fb                       # Build + run with SDL2 (dev)
pnpm fb:build                 # Build only
pnpm fb:device                # Cross-compile for ARM device
just fb-build                 # Cross-compile for ARM (justfile)
just fb-deploy                # Build + deploy + restart on BQ268
just fb-test                  # Unit tests
just fb-test-integration      # E2E tests against Conduit (auto-starts via test/docker)

# Dev helpers
pnpm dev:server               # Start wata-server in foreground (TS, in-memory)
pnpm dev:server:conduit       # Start Conduit via docker (fallback)
just wata-up / wata-down      # Control permanent wata-server systemd user service
just wata-logs                # Tail wata-server journald logs
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
├── fbclient/         # Zig framebuffer client (SDL2 dev / fbdev device)
├── shared/           # TS code shared by TUI/Web
├── tui/              # Terminal UI (Ink)
└── web/              # Web app (Vite)
```

**Path aliases:** `@shared/*` imports from `src/shared/`.

**Workspaces:** `@wata/shared`, `@wata/tui`, `@wata/web`.

---

## Quick Reference

| Topic | Location |
|-------|----------|
| PTT button codes | `docs/android-development.md` (Hardware Keys) |
| DM room lookup/creation | `docs/dm-room-service.md` |
| Opus audio format | `docs/voice.md` |
| Matrix room types | `docs/family-model.md` |
| TUI component patterns | `docs/tui-architecture.md` |
| WataClient specs | `specs/` |
