# Device Automation Setup

Complete automated testing workflow for rapid iteration with physical Android device.

## Setup Complete

Wireless ADB connection established to: `192.168.178.49:5555` (RG353P)

## Automated Workflow

### Complete Setup (First Time)

```bash
# 1. Connect device via USB
adb devices

# 2. Enable wireless ADB
adb tcpip 5555
adb connect <device-ip>:5555

# 3. Setup port forwarding (for Metro and Conduit)
adb reverse tcp:8081 tcp:8081  # Metro bundler
pnpm dev:forward             # Conduit Matrix server (port 8008)

# 4. Start Matrix server (optional, for full functionality)
pnpm dev:server

# 5. Start Metro bundler
npm start  # Keep this running

# 6. Deploy app
pnpm dev:deploy
```

### Quick Iteration Loop (Recommended)

For fastest development with hot reload:

```bash
# Terminal 1: Start Metro bundler (keep running)
npm start

# Terminal 2: Deploy once
pnpm dev:deploy

# Now edit code and see changes with hot reload!
# Metro will automatically push updates to the device
```

**Important**: Ensure port forwarding is set up:
- `adb reverse tcp:8081 tcp:8081` (for Metro bundler)
- `pnpm dev:forward` (for Matrix server on port 8008)

### Full Test Cycle (Agent-Driven)

Complete build, deploy, and verification cycle:

```bash
# Ensure Metro is running first!
pnpm dev:test-cycle
```

This will:
1. Check device connection
2. Build fresh APK
3. Deploy to device
4. Launch app
5. Health check (verify no crashes)
6. Capture screenshot

### Individual Commands

```bash
# Build and deploy
pnpm dev:deploy              # Full build + install
pnpm dev:deploy:quick        # Install existing APK (no rebuild)

# Monitoring and debugging
pnpm dev:logs                # Live log monitoring (filtered)
pnpm dev:check               # Health check (crashes, errors, status)
pnpm dev:screenshot          # Capture screenshot

# Input simulation
pnpm dev:input ptt           # Simulate PTT button
pnpm dev:input up            # D-pad up
pnpm dev:input down          # D-pad down
pnpm dev:input select        # Select/confirm
pnpm dev:input menu          # Menu button
pnpm dev:input back          # Back button

# Infrastructure
pnpm dev:forward             # Setup ADB port forwarding (for localhost:8008)
pnpm dev:server              # Start local Conduit Matrix server
```

## Agent Automation Capabilities

The agent can now:

1. **Build & Deploy**: Automatically compile and install new versions
2. **Monitor Logs**: Watch for errors, crashes, and app output
3. **Verify Behavior**: Check app health, detect crashes
4. **Capture State**: Take screenshots to verify UI
5. **Simulate Input**: Send button presses (PTT, D-pad, etc.)
6. **Iterate Quickly**: Deploy changes and verify in seconds

## Typical Agent Workflow

```bash
# 1. Start Metro (keep running in background)
npm start &

# 2. Make code changes
# (agent edits files)

# 3. Deploy and verify
pnpm dev:test-cycle

# 4. Monitor logs if needed
pnpm dev:logs

# 5. Take screenshot to verify UI
pnpm dev:screenshot

# 6. Test button interactions
pnpm dev:input ptt
pnpm dev:screenshot  # Verify PTT recording UI
```

## Wireless ADB Details

- **Device IP**: 192.168.178.49:5555
- **Connection**: Wireless (USB disconnected)
- **Reconnect**: If connection drops, run `adb connect 192.168.178.49:5555`
- **Check status**: `adb devices`

## Metro Bundler

The app requires Metro to be running for development builds:
- Metro serves the JavaScript bundle
- Enables hot reload for fast iteration
- Required for `pnpm dev:deploy` and `pnpm dev:test-cycle`

For production builds without Metro dependency:
```bash
cd android
./gradlew assembleRelease  # Creates standalone APK
```

## Troubleshooting

### Black screen on launch
- Ensure Metro bundler is running: `npm start`
- Check logs: `pnpm dev:logs`

### "No device connected"
- Check connection: `adb devices`
- Reconnect: `adb connect 192.168.178.49:5555`

### App crashes
- Check logs: `pnpm dev:check`
- View full logs: `pnpm dev:logs`

### Multiple devices
- Scripts auto-select first available device
- To use specific device, edit scripts to add `-s <device-id>`

## Next Steps

The automation is ready for rapid iteration. The agent can now:
- Deploy code changes automatically
- Monitor for issues
- Verify behavior with screenshots
- Simulate user interactions
- Detect and report errors

Start Metro and begin coding!
