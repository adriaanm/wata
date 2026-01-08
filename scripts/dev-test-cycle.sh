#!/bin/bash
# Automated test cycle: build, deploy, verify
# This script is designed for agent-driven iteration

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}╔════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║   Wata Automated Test Cycle           ║${NC}"
echo -e "${BLUE}╔════════════════════════════════════════╗${NC}"
echo ""

# Step 1: Check device connection
echo -e "${BLUE}[1/6] Checking device connection...${NC}"
if ! adb devices | grep -q "device$"; then
    echo -e "${RED}✗ No device connected${NC}"
    echo "Please connect device and try again"
    exit 1
fi
DEVICE=$(adb devices | grep "device$" | head -1 | awk '{print $1}')
echo -e "${GREEN}✓ Connected to: ${DEVICE}${NC}"
echo ""

# Step 2: Build APK
echo -e "${BLUE}[2/6] Building APK...${NC}"
cd android
if ./gradlew assembleDebug; then
    echo -e "${GREEN}✓ Build successful${NC}"
else
    echo -e "${RED}✗ Build failed${NC}"
    exit 1
fi
cd ..
echo ""

# Step 3: Deploy to device
echo -e "${BLUE}[3/6] Deploying to device...${NC}"
APK_PATH="android/app/build/outputs/apk/debug/app-debug.apk"
if adb -s "$DEVICE" install -r "$APK_PATH"; then
    echo -e "${GREEN}✓ APK installed${NC}"
else
    echo -e "${RED}✗ Installation failed${NC}"
    exit 1
fi
echo ""

# Step 4: Launch app
echo -e "${BLUE}[4/6] Launching app...${NC}"
adb -s "$DEVICE" shell am start -n com.wata/.MainActivity
sleep 3  # Give app time to start
echo -e "${GREEN}✓ App launched${NC}"
echo ""

# Step 5: Health check
echo -e "${BLUE}[5/6] Running health check...${NC}"

# Check if app is running
if adb shell pidof com.wata > /dev/null; then
    PID=$(adb shell pidof com.wata)
    echo -e "${GREEN}✓ App is running (PID: ${PID})${NC}"
else
    echo -e "${RED}✗ App crashed or not running${NC}"
    echo "Checking logs for errors..."
    adb logcat -d -t 50 AndroidRuntime:E ReactNativeJS:E *:S
    exit 1
fi

# Check for crashes
CRASHES=$(adb logcat -d -t 50 AndroidRuntime:E *:S 2>/dev/null | grep -i "fatal\|exception" || true)
if [ -n "$CRASHES" ]; then
    echo -e "${RED}✗ Found crashes in logs:${NC}"
    echo "$CRASHES"
    exit 1
else
    echo -e "${GREEN}✓ No crashes detected${NC}"
fi

# Check for React Native errors
RN_ERRORS=$(adb logcat -d -t 50 ReactNativeJS:E *:S 2>/dev/null || true)
if [ -n "$RN_ERRORS" ]; then
    echo -e "${RED}✗ Found React Native errors:${NC}"
    echo "$RN_ERRORS"
    exit 1
else
    echo -e "${GREEN}✓ No React Native errors${NC}"
fi

echo ""

# Step 6: Take screenshot
echo -e "${BLUE}[6/6] Capturing screenshot...${NC}"
mkdir -p screenshots
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
SCREENSHOT="screenshots/test_${TIMESTAMP}.png"
if adb exec-out screencap -p > "$SCREENSHOT"; then
    echo -e "${GREEN}✓ Screenshot saved: ${SCREENSHOT}${NC}"
else
    echo -e "${YELLOW}! Screenshot capture failed${NC}"
fi
echo ""

# Success summary
echo -e "${GREEN}╔════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║   ✓ TEST CYCLE COMPLETED SUCCESSFULLY  ║${NC}"
echo -e "${GREEN}╚════════════════════════════════════════╝${NC}"
echo ""
echo "App is running on device. Next steps:"
echo "  - Monitor logs: npm run dev:logs"
echo "  - Take screenshot: npm run dev:screenshot"
echo "  - Simulate input: npm run dev:input <key>"
echo "  - Health check: npm run dev:check"
echo ""
