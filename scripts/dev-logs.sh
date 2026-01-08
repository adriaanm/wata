#!/bin/bash
# Monitor app logs in real-time with filtering

# Colors
RED='\033[0;31m'
YELLOW='\033[1;33m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${GREEN}=== Monitoring Wata App Logs ===${NC}"
echo "Press Ctrl+C to stop"
echo ""

# Check if device is connected
if ! adb devices | grep -q "device$"; then
    echo -e "${RED}Error: No device connected${NC}"
    exit 1
fi

# Clear logcat buffer
adb logcat -c

# Monitor logs with filters for React Native and app-specific tags
# Highlight errors and warnings
adb logcat \
    ReactNativeJS:V \
    ReactNative:V \
    AndroidRuntime:E \
    System.err:W \
    chromium:I \
    *:S | \
    while IFS= read -r line; do
        if echo "$line" | grep -q -i "error\|exception\|fatal"; then
            echo -e "${RED}${line}${NC}"
        elif echo "$line" | grep -q -i "warn"; then
            echo -e "${YELLOW}${line}${NC}"
        elif echo "$line" | grep -q -i "matrix\|wata"; then
            echo -e "${BLUE}${line}${NC}"
        else
            echo "$line"
        fi
    done
