#!/bin/bash
# Simulate hardware button presses on the device

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

if [ -z "$1" ]; then
    echo -e "${YELLOW}Usage: $0 <key>${NC}"
    echo ""
    echo "Common keys for PTT device:"
    echo "  ptt       - PTT button (KEYCODE_PTT, code 79)"
    echo "  up        - D-pad Up"
    echo "  down      - D-pad Down"
    echo "  select    - D-pad Center/Select"
    echo "  menu      - Menu button"
    echo "  back      - Back/Exit button"
    echo "  p1        - P1 button (Center)"
    echo "  p2        - P2 button (device-specific)"
    echo ""
    echo "Examples:"
    echo "  $0 ptt         # Press PTT button"
    echo "  $0 up          # Press Up"
    echo "  $0 menu        # Press Menu"
    exit 1
fi

# Check if device is connected
if ! adb devices | grep -q "device$"; then
    echo -e "${RED}Error: No device connected${NC}"
    exit 1
fi

# Map friendly names to Android keycodes
case "$1" in
    ptt)
        KEYCODE="79"  # KEYCODE_PTT
        NAME="PTT"
        ;;
    up)
        KEYCODE="KEYCODE_DPAD_UP"
        NAME="D-pad Up"
        ;;
    down)
        KEYCODE="KEYCODE_DPAD_DOWN"
        NAME="D-pad Down"
        ;;
    select|center|p1)
        KEYCODE="KEYCODE_DPAD_CENTER"
        NAME="Select/Center"
        ;;
    menu)
        KEYCODE="KEYCODE_MENU"
        NAME="Menu"
        ;;
    back|exit)
        KEYCODE="KEYCODE_BACK"
        NAME="Back"
        ;;
    p2)
        KEYCODE="85"  # Device-specific, may need adjustment
        NAME="P2"
        ;;
    *)
        # Allow passing raw keycode numbers or keycode names
        KEYCODE="$1"
        NAME="$1"
        ;;
esac

echo -e "${GREEN}Sending key: ${NAME} (${KEYCODE})${NC}"
adb shell input keyevent "$KEYCODE"

if [ $? -eq 0 ]; then
    echo -e "${GREEN}Key event sent successfully${NC}"
else
    echo -e "${RED}Failed to send key event${NC}"
    exit 1
fi
