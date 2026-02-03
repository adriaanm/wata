# Quick Start Guide

This is a streamlined guide to get you developing on Wata as quickly as possible.

## First-Time Setup

```bash
# 1. Install dependencies
pnpm install

# 2. Start local Matrix server
pnpm dev:server
```

That's it! The server is now running and ready.

## Daily Development Workflow

### Android (Kotlin)

The Android app is built with native Kotlin using Gradle. No Metro bundler or hot reload—build and deploy directly.

```bash
# Build and install on device/emulator
pnpm android

# Or build APK only
cd src/android && ./gradlew assembleDebug

# Install APK on connected device
cd src/android && ./gradlew installDebug
```

For iterative development:
- Edit code in `src/android/`
- Rebuild and install with `pnpm android`
- For faster builds, use Gradle's build cache: subsequent builds are faster

### TUI (Terminal UI)

```bash
# Run TUI
pnpm tui

# Run with watch mode
pnpm tui:dev
```

### Physical Device Testing

```bash
# Connect device, then set up port forwarding
pnpm dev:forward

# Build and deploy
pnpm android
```

**Note:** Run `pnpm dev:forward` again if you disconnect/reconnect the device.

## Testing Between Devices

The app auto-logs in as `alice`. To test messaging:

**Option 1: Second device**
- Edit `src/android/app/src/main/java/com/wata/client/MatrixConfig.kt`
- Change username to `"bob"` and rebuild
- Build and run on second device

**Option 2: Element web client**
- Open http://localhost:8008 in browser
- Register/login as `bob` / `testpass123`
- Create DM with `@alice:localhost`

## Troubleshooting

**"Connection failed" on physical device:**
```bash
# Check device is connected
adb devices

# Re-run port forwarding
pnpm dev:forward
```

**Port forwarding doesn't work:**
```bash
# Fallback to manual IP
pnpm dev:ip

# Update src/android/app/src/main/java/com/wata/client/MatrixConfig.kt with the IP shown
```

**Server not responding:**
```bash
# Check server status
cd test/docker && docker-compose ps

# Restart server
docker-compose restart
```

## That's It!

The workflow is:
1. Server running? ✓
2. Port forwarding (for device)? ✓
3. Build and deploy with `pnpm android`
