#!/bin/bash
# Automated build and deployment script for physical device testing

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}=== Wata Device Deployment ===${NC}"

# Check if device is connected
if ! adb devices | grep -q "device$"; then
    echo -e "${RED}Error: No device connected${NC}"
    echo "Run 'adb devices' to check connection"
    exit 1
fi

DEVICE=$(adb devices | grep "device$" | head -1 | awk '{print $1}')
echo -e "${GREEN}Connected to device: ${DEVICE}${NC}"

# Option to build fresh APK or use existing
BUILD_APK=${1:-"yes"}

if [ "$BUILD_APK" == "yes" ]; then
    echo -e "${YELLOW}Building APK...${NC}"
    cd android
    ./gradlew assembleDebug
    cd ..
    echo -e "${GREEN}Build complete${NC}"
fi

APK_PATH="android/app/build/outputs/apk/debug/app-debug.apk"

if [ ! -f "$APK_PATH" ]; then
    echo -e "${RED}Error: APK not found at $APK_PATH${NC}"
    exit 1
fi

echo -e "${YELLOW}Installing APK...${NC}"
adb -s "$DEVICE" install -r "$APK_PATH"

echo -e "${GREEN}Launching app...${NC}"
adb -s "$DEVICE" shell am start -n com.wata/.MainActivity

echo -e "${GREEN}=== Deployment Complete ===${NC}"
echo ""
echo "Next steps:"
echo "  - Run './scripts/dev-logs.sh' to monitor logs"
echo "  - Run './scripts/dev-screenshot.sh' to capture screen"
echo "  - Run './scripts/dev-input.sh <keycode>' to simulate buttons"
