#!/bin/bash
# End-to-end test script for 81-frame GIF capture

set -e

echo "🎬 E2E Test: 81-Frame GIF Capture"
echo "=================================="

# Check device connection
if ! adb devices | grep -q "device$"; then
    echo "❌ No Android device connected"
    echo "   Please connect a device or start an emulator"
    exit 1
fi

DEVICE=$(adb devices | grep device$ | head -1 | awk '{print $1}')
echo "✅ Testing on device: $DEVICE"

# Build and install
echo ""
echo "Building app..."
cd rust-core
cargo ndk -t arm64-v8a build --release
cd ..
./gradlew assembleDebug

echo ""
echo "Installing app..."
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Clear previous captures
echo ""
echo "Clearing previous captures..."
adb shell rm -rf /sdcard/Android/data/com.rgbagif/files/*.gif

# Launch app
echo ""
echo "Launching app..."
adb shell am start -n com.rgbagif/.MainActivity

# Wait for app to start
sleep 3

# Take screenshot of initial state
adb shell screencap -p /sdcard/test_initial.png
adb pull /sdcard/test_initial.png test_results/initial.png 2>/dev/null || true

# Start capture
echo ""
echo "Starting 81-frame capture..."
# Tap capture button (center of screen, bottom third)
adb shell input tap 360 1400

# Monitor capture progress
START_TIME=$(date +%s)
FRAME_COUNT=0

echo "Capturing frames..."
for i in {1..20}; do
    sleep 2
    
    # Check if capture is complete (look for GIF file)
    GIF_COUNT=$(adb shell "ls /sdcard/Android/data/com.rgbagif/files/*.gif 2>/dev/null | wc -l" | tr -d '\r\n' | tr -d ' ')
    
    if [ "$GIF_COUNT" -gt "0" ]; then
        echo ""
        echo "✅ Capture complete!"
        break
    fi
    
    ELAPSED=$(($(date +%s) - START_TIME))
    echo -n "  ${ELAPSED}s elapsed..."
done

# Pull the GIF
echo ""
echo "Retrieving GIF..."
mkdir -p test_results
LATEST_GIF=$(adb shell "ls -t /sdcard/Android/data/com.rgbagif/files/*.gif 2>/dev/null | head -1" | tr -d '\r\n')

if [ ! -z "$LATEST_GIF" ]; then
    adb pull "$LATEST_GIF" test_results/capture.gif
    echo "✅ GIF saved to test_results/capture.gif"
    
    # Verify GIF
    echo ""
    echo "Verifying GIF..."
    if command -v file &> /dev/null; then
        file test_results/capture.gif
    fi
    
    if command -v identify &> /dev/null; then
        identify -format "  Dimensions: %wx%h\n  Frames: %n\n" test_results/capture.gif[0]
    fi
    
    # Check file size
    SIZE=$(stat -f%z test_results/capture.gif 2>/dev/null || stat -c%s test_results/capture.gif 2>/dev/null)
    SIZE_KB=$((SIZE / 1024))
    echo "  Size: ${SIZE_KB}KB"
    
    if [ $SIZE_KB -lt 500 ]; then
        echo "  ✅ Size under 500KB target"
    else
        echo "  ⚠️  Size exceeds 500KB target"
    fi
    
    # Test playback
    echo ""
    echo "Opening GIF in gallery..."
    adb shell am start -a android.intent.action.VIEW \
        -d "file://$LATEST_GIF" \
        -t "image/gif"
    
else
    echo "❌ No GIF file found"
    echo "   Checking logs..."
    adb logcat -d | grep -E "GifPipe|Pipeline|Error" | tail -20
    exit 1
fi

echo ""
echo "=================================="
echo "✅ E2E Test Complete!"
echo ""
echo "Results in test_results/:"
echo "  - capture.gif: The generated GIF"
echo "  - initial.png: Screenshot before capture"
echo ""
echo "Quality metrics:"
echo "  - Expected: 81 frames at 81×81"
echo "  - Palette: ≤256 colors"
echo "  - Size: <500KB"