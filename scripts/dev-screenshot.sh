#!/bin/bash
# Capture screenshot from connected device

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

# Check if device is connected
if ! adb devices | grep -q "device$"; then
    echo -e "${RED}Error: No device connected${NC}"
    exit 1
fi

# Create screenshots directory if it doesn't exist
mkdir -p screenshots

# Generate filename with timestamp
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
FILENAME="screenshots/screenshot_${TIMESTAMP}.png"

echo -e "${GREEN}Capturing screenshot...${NC}"
adb exec-out screencap -p > "$FILENAME"

if [ -f "$FILENAME" ]; then
    echo -e "${GREEN}Screenshot saved: ${FILENAME}${NC}"

    # Try to open the screenshot (macOS)
    if command -v open &> /dev/null; then
        open "$FILENAME"
    fi
else
    echo -e "${RED}Failed to capture screenshot${NC}"
    exit 1
fi
