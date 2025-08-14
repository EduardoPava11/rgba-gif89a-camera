#!/bin/bash
# Complete M1→M2→M3 Pipeline Test
# Tests RGBA capture → Neural downsize → GIF89a export

set -e

echo "============================================"
echo "RGBA→GIF89a COMPLETE PIPELINE TEST"
echo "============================================"
echo ""

# Check for device
DEVICE=$(adb devices | grep -E "device$|emulator" | head -1 | awk '{print $1}')
if [ -z "$DEVICE" ]; then
    echo "❌ No Android device found"
    exit 1
fi

echo "✅ Device found: $DEVICE"
echo ""

# Build and install app
echo "1. Building app with all processors..."
cd /Users/daniel/rgba-gif89a-camera
./gradlew assembleDebug || {
    echo "❌ Build failed"
    exit 1
}

echo ""
echo "2. Installing app..."
adb -s "$DEVICE" install -r app/build/outputs/apk/debug/app-debug.apk || {
    echo "❌ Installation failed"
    exit 1
}

# Clear logs and start monitoring
adb -s "$DEVICE" logcat -c

echo ""
echo "3. Starting app and monitoring pipeline..."
adb -s "$DEVICE" shell am start -n com.rgbagif/.MainActivity

# Monitor canonical logs
echo ""
echo "4. Monitoring canonical logs..."
echo "   Waiting for M1→M2→M3 pipeline events..."
echo ""

# Start log monitoring in background
LOG_FILE="/tmp/pipeline_test_$(date +%Y%m%d_%H%M%S).log"
adb -s "$DEVICE" logcat | tee "$LOG_FILE" | grep -E "APP_START|M[123]_|JNI_|CAMERA_INIT|GIF_DONE" &
LOG_PID=$!

# Function to check milestone completion
check_milestone() {
    local milestone=$1
    local timeout=$2
    local start_time=$(date +%s)
    
    echo "   Waiting for $milestone completion (timeout: ${timeout}s)..."
    
    while true; do
        if grep -q "${milestone}_DONE\|${milestone}_COMPLETE" "$LOG_FILE" 2>/dev/null; then
            echo "   ✅ $milestone complete!"
            return 0
        fi
        
        local elapsed=$(($(date +%s) - start_time))
        if [ $elapsed -gt $timeout ]; then
            echo "   ⏱️ $milestone timeout after ${timeout}s"
            return 1
        fi
        
        sleep 1
    done
}

# Wait for app initialization
sleep 5

echo ""
echo "5. Triggering capture (you may need to tap the capture button)..."
echo "   Please tap the capture button in the app to start M1"
echo ""

# Check M1 completion
if check_milestone "M1" 60; then
    echo "   M1 frames captured:"
    grep -c "M1_FRAME_SAVED" "$LOG_FILE" 2>/dev/null || echo "0"
fi

# Check M2 completion
echo ""
echo "6. Checking M2 Neural Downsize..."
if check_milestone "M2" 30; then
    # Check for JPEG conversion
    if grep -q "Saved JPEG" "$LOG_FILE"; then
        echo "   ✅ JPEGs generated successfully!"
    else
        echo "   ⚠️ No JPEG files detected"
    fi
fi

# Check M3 completion
echo ""
echo "7. Checking M3 GIF Export..."
if check_milestone "M3" 30; then
    # Check for GIF file
    if grep -q "GIF_DONE\|GIF export complete" "$LOG_FILE"; then
        echo "   ✅ GIF exported successfully!"
        
        # Extract GIF details
        grep "GIF_DONE\|gif_size_bytes" "$LOG_FILE" | tail -1
    else
        echo "   ⚠️ No GIF export detected"
    fi
fi

# Check for errors
echo ""
echo "8. Checking for errors..."
ERROR_COUNT=$(grep -c "JNI_FAIL\|M[123]_.*fail\|ERROR\|FATAL" "$LOG_FILE" 2>/dev/null || echo "0")
if [ "$ERROR_COUNT" -gt "0" ]; then
    echo "   ⚠️ Found $ERROR_COUNT errors:"
    grep "JNI_FAIL\|M[123]_.*fail" "$LOG_FILE" | head -5
else
    echo "   ✅ No critical errors detected"
fi

# Summary
echo ""
echo "============================================"
echo "PIPELINE TEST SUMMARY"
echo "============================================"

# Count milestone completions
M1_DONE=$(grep -c "M1_DONE\|M1_COMPLETE" "$LOG_FILE" 2>/dev/null || echo "0")
M2_DONE=$(grep -c "M2_DONE\|M2_COMPLETE" "$LOG_FILE" 2>/dev/null || echo "0")
M3_DONE=$(grep -c "M3_DONE\|M3_COMPLETE\|GIF_DONE" "$LOG_FILE" 2>/dev/null || echo "0")

echo "M1 CBOR Capture:     $([ "$M1_DONE" -gt "0" ] && echo "✅ PASS" || echo "❌ FAIL")"
echo "M2 Neural Downsize:  $([ "$M2_DONE" -gt "0" ] && echo "✅ PASS" || echo "❌ FAIL")"
echo "M3 GIF Export:       $([ "$M3_DONE" -gt "0" ] && echo "✅ PASS" || echo "❌ FAIL")"

# Check output format
if grep -q "Saved JPEG" "$LOG_FILE"; then
    echo "Output Format:       ✅ JPEG (as requested)"
else
    echo "Output Format:       ⚠️ PNG or missing"
fi

echo ""
echo "Log file saved to: $LOG_FILE"

# Kill log monitoring
kill $LOG_PID 2>/dev/null

# Pull output files if successful
if [ "$M3_DONE" -gt "0" ]; then
    echo ""
    echo "Pulling output files..."
    OUTPUT_DIR="/tmp/rgba_gif_output_$(date +%Y%m%d_%H%M%S)"
    mkdir -p "$OUTPUT_DIR"
    
    # Try to pull GIF and diagnostic files
    adb -s "$DEVICE" pull /sdcard/Android/data/com.rgbagif/files/ "$OUTPUT_DIR" 2>/dev/null || true
    
    if [ -f "$OUTPUT_DIR/*/output.gif" ]; then
        echo "✅ GIF file pulled to: $OUTPUT_DIR"
    fi
fi

echo ""
echo "Test complete!"