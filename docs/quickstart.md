# Quick Start Guide

This is a streamlined guide to get you developing on Wata as quickly as possible.

## First-Time Setup

```bash
# 1. Install dependencies
npm install

# 2. Start local Matrix server
npm run dev:server
```

That's it! The server is now running and ready.

## Daily Development Workflow

### Emulator (Simplest)

```bash
# Terminal 1: Start Metro
npm start

# Terminal 2: Run on emulator
npm run android
```

Hot reload is active. Edit code and see changes instantly!

### Physical Device

```bash
# Terminal 1: Start Metro
npm start

# Terminal 2: Connect device, then set up port forwarding
npm run dev:forward

# Terminal 3: Run on device
npm run android
```

**Note:** Run `npm run dev:forward` again if you disconnect/reconnect the device.

## Testing Between Devices

The app auto-logs in as `alice`. To test messaging:

**Option 1: Second device**
- Edit `src/shared/config/matrix.ts` and change username to `'bob'`
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
npm run dev:forward
```

**Port forwarding doesn't work:**
```bash
# Fallback to manual IP
npm run dev:ip

# Update src/shared/config/matrix.ts with the IP shown
```

**Server not responding:**
```bash
# Check server status
cd test/docker && docker compose ps

# Restart server
docker compose restart
```

## That's It!

The workflow is:
1. Server running? ✓
2. Metro running? ✓
3. Port forwarding (for device)? ✓
4. Deploy once, then hot reload forever!
