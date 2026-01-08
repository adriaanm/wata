#!/bin/bash

# Setup ADB reverse proxy for Matrix server
# This allows the device to access localhost:8008 on the dev machine

set -e

echo "🔌 Setting up ADB port forwarding..."
echo ""

# Check if adb is available
if ! command -v adb &> /dev/null; then
    echo "❌ Error: adb not found in PATH"
    echo "   Make sure Android SDK is installed and adb is in your PATH"
    exit 1
fi

# Check if any device is connected
DEVICES=$(adb devices | grep -v "List of devices" | grep -E "device$|emulator" | wc -l)

if [ "$DEVICES" -eq 0 ]; then
    echo "❌ No Android devices found"
    echo ""
    echo "   Connect a device via USB or start an emulator, then run:"
    echo "   npm run dev:forward"
    exit 1
fi

if [ "$DEVICES" -gt 1 ]; then
    echo "⚠️  Multiple devices detected:"
    adb devices
    echo ""
    echo "   Please specify which device to use:"
    echo "   adb -s DEVICE_ID reverse tcp:8008 tcp:8008"
    exit 1
fi

# Set up reverse proxy
echo "📱 Device found, setting up port forwarding..."
adb reverse tcp:8008 tcp:8008

if [ $? -eq 0 ]; then
    echo "✅ Port forwarding active!"
    echo ""
    echo "   Device localhost:8008 → Host localhost:8008"
    echo ""
    echo "   Your app can now connect to the local Matrix server using:"
    echo "   http://localhost:8008"
    echo ""
    echo "💡 This persists until device disconnects or restarts"
else
    echo "❌ Failed to set up port forwarding"
    exit 1
fi
