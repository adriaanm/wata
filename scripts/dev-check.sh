#!/bin/bash
# Check for app errors, crashes, and health status

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}=== Wata App Health Check ===${NC}"
echo ""

# Check if device is connected
if ! adb devices | grep -q "device$"; then
    echo -e "${RED}Error: No device connected${NC}"
    exit 1
fi

DEVICE=$(adb devices | grep "device$" | head -1 | awk '{print $1}')
echo -e "${GREEN}Device: ${DEVICE}${NC}"

# Check if app is installed
if adb shell pm list packages | grep -q "com.wata"; then
    echo -e "${GREEN}✓ App is installed${NC}"
else
    echo -e "${RED}✗ App is not installed${NC}"
    exit 1
fi

# Check if app is running
if adb shell pidof com.wata > /dev/null; then
    PID=$(adb shell pidof com.wata)
    echo -e "${GREEN}✓ App is running (PID: ${PID})${NC}"
else
    echo -e "${YELLOW}✗ App is not running${NC}"
fi

# Check for recent crashes (last 100 lines)
echo ""
echo -e "${BLUE}Checking for crashes...${NC}"
CRASHES=$(adb logcat -d -t 100 AndroidRuntime:E *:S 2>/dev/null | grep -i "fatal\|exception")
if [ -n "$CRASHES" ]; then
    echo -e "${RED}✗ Found recent crashes:${NC}"
    echo "$CRASHES" | tail -20
else
    echo -e "${GREEN}✓ No recent crashes detected${NC}"
fi

# Check for React Native errors
echo ""
echo -e "${BLUE}Checking for React Native errors...${NC}"
RN_ERRORS=$(adb logcat -d -t 100 ReactNativeJS:E *:S 2>/dev/null)
if [ -n "$RN_ERRORS" ]; then
    echo -e "${RED}✗ Found React Native errors:${NC}"
    echo "$RN_ERRORS" | tail -20
else
    echo -e "${GREEN}✓ No React Native errors${NC}"
fi

# Check for warnings
echo ""
echo -e "${BLUE}Checking for warnings...${NC}"
WARNINGS=$(adb logcat -d -t 100 ReactNativeJS:W *:S 2>/dev/null)
if [ -n "$WARNINGS" ]; then
    echo -e "${YELLOW}! Found warnings:${NC}"
    echo "$WARNINGS" | tail -10
else
    echo -e "${GREEN}✓ No warnings${NC}"
fi

# Check current activity
echo ""
echo -e "${BLUE}Current activity:${NC}"
CURRENT_ACTIVITY=$(adb shell dumpsys activity | grep "mResumedActivity" | head -1)
if echo "$CURRENT_ACTIVITY" | grep -q "com.wata"; then
    echo -e "${GREEN}✓ Wata app is in foreground${NC}"
    echo "$CURRENT_ACTIVITY"
else
    echo -e "${YELLOW}! Wata app is not in foreground${NC}"
    echo "$CURRENT_ACTIVITY"
fi

echo ""
echo -e "${BLUE}=== Health Check Complete ===${NC}"
