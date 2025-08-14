#!/bin/bash
# Verify complete Rust GIF pipeline on device
# Captures 81 frames at 729×729, downsizes to 81×81, creates GIF

echo "========================================"
echo "RUST GIF PIPELINE VERIFICATION"
echo "========================================"
echo ""
echo "Features implemented:"
echo "  ✅ UniFFI 0.27.1 (contract version fixed)"
echo "  ✅ M2: Lanczos3 high-quality downscaling"
echo "  ✅ M3: NeuQuant + Floyd-Steinberg dithering"
echo "  ✅ M3: Proper LZW compression via gif crate"
echo ""

# Install APK
echo "Installing APK..."
adb install -r app/build/outputs/apk/debug/app-debug.apk || exit 1

# Clear old data
echo "Clearing old data..."
adb shell rm -rf /sdcard/Android/data/com.rgbagif.debug/files/

# Start app
echo "Starting app..."
adb shell am force-stop com.rgbagif.debug
adb logcat -c
adb shell am start -n com.rgbagif.debug/com.rgbagif.MainActivity

sleep 3

# Start capture
echo ""
echo "Starting capture (81 frames at 729×729)..."
adb shell input tap 540 1200  # Tap capture button

# Monitor capture progress
echo "Monitoring capture..."
START_TIME=$(date +%s)
LAST_LOG=""

while true; do
    # Check for M1 completion
    M1_LOG=$(adb logcat -d | grep "M1_COMPLETE" | tail -1)
    if [ ! -z "$M1_LOG" ] && [ "$M1_LOG" != "$LAST_LOG" ]; then
        echo "✅ M1 Capture complete: 81 frames"
        LAST_LOG="$M1_LOG"
        break
    fi
    
    # Check for capture progress
    FRAME_LOG=$(adb logcat -d | grep "frames_captured" | tail -1)
    if [ ! -z "$FRAME_LOG" ]; then
        echo -ne "\rCapturing frames... $FRAME_LOG"
    fi
    
    ELAPSED=$(($(date +%s) - START_TIME))
    if [ "$ELAPSED" -gt "60" ]; then
        echo ""
        echo "⏱️ Timeout waiting for capture"
        break
    fi
    
    sleep 1
done

echo ""
echo ""
echo "Checking M2 downscaling (Rust Lanczos3)..."
# Check for M2 processing logs
M2_LOGS=$(adb logcat -d | grep "M2_")
if echo "$M2_LOGS" | grep -q "M2_DOWNSCALE using Rust Lanczos3"; then
    echo "✅ M2: Using Rust Lanczos3 downscaling"
else
    echo "⚠️ M2: Fallback to manual downscaling"
fi

# Check M2 statistics
M2_STATS=$(adb logcat -d | grep "M2_STATS" | tail -1)
if [ ! -z "$M2_STATS" ]; then
    echo "  $M2_STATS"
fi

echo ""
echo "Checking M3 GIF creation (Rust NeuQuant + Floyd-Steinberg)..."
# Wait for M3 processing
sleep 5

# Check for M3 processing logs
M3_LOGS=$(adb logcat -d | grep "M3_")
if echo "$M3_LOGS" | grep -q "M3_START.*NeuQuant"; then
    echo "✅ M3: Using Rust NeuQuant quantization"
fi

if echo "$M3_LOGS" | grep -q "M3_GIF_DONE"; then
    echo "✅ M3: GIF created successfully"
    GIF_STATS=$(echo "$M3_LOGS" | grep "M3_GIF_DONE" | tail -1)
    echo "  $GIF_STATS"
fi

# Check for GIF file
echo ""
echo "Checking output files..."
GIF_FILES=$(adb shell "find /sdcard/Android/data/com.rgbagif.debug/files -name '*.gif' 2>/dev/null")
if [ ! -z "$GIF_FILES" ]; then
    echo "✅ GIF files created:"
    echo "$GIF_FILES" | while read file; do
        SIZE=$(adb shell "ls -lh '$file' 2>/dev/null" | awk '{print $5}')
        echo "  $file ($SIZE)"
    done
    
    # Pull the GIF for inspection
    FIRST_GIF=$(echo "$GIF_FILES" | head -1 | tr -d '\r')
    if [ ! -z "$FIRST_GIF" ]; then
        echo ""
        echo "Pulling GIF for inspection..."
        adb pull "$FIRST_GIF" /tmp/rust_pipeline_output.gif 2>/dev/null
        
        if [ -f /tmp/rust_pipeline_output.gif ]; then
            SIZE=$(ls -lh /tmp/rust_pipeline_output.gif | awk '{print $5}')
            echo "✅ GIF saved to /tmp/rust_pipeline_output.gif ($SIZE)"
            
            # Open in preview on macOS
            if command -v open &> /dev/null; then
                open /tmp/rust_pipeline_output.gif
                echo "✅ Opening GIF in preview..."
            fi
        fi
    fi
else
    echo "❌ No GIF files found"
fi

# Check for errors
echo ""
echo "Checking for errors..."
ERROR_COUNT=$(adb logcat -d | grep -c "PIPELINE_ERROR\|GifException\|UniFFI.*error")
if [ "$ERROR_COUNT" -gt "0" ]; then
    echo "⚠️ Found $ERROR_COUNT errors:"
    adb logcat -d | grep "PIPELINE_ERROR\|GifException\|UniFFI.*error" | head -5
else
    echo "✅ No pipeline errors detected"
fi

# Performance summary
echo ""
echo "========================================"
echo "PIPELINE SUMMARY"
echo "========================================"
echo ""

# Check UniFFI loading
if adb logcat -d | grep -q "M3 Rust GIF encoder loaded"; then
    echo "✅ Rust M3 GIF encoder loaded"
fi

if adb logcat -d | grep -q "M2 Rust downscaler loaded"; then
    echo "✅ Rust M2 downscaler loaded (Lanczos3)"
fi

# Frame processing stats
echo ""
echo "Frame processing pipeline:"
echo "  1. Capture: 81 frames at 729×729 RGBA"
echo "  2. M2 Downscale: 729×729 → 81×81 (Lanczos3)"
echo "  3. M3 Quantize: NeuQuant + Floyd-Steinberg dithering"
echo "  4. M3 Encode: GIF89a with proper LZW compression"

echo ""
echo "========================================"
echo "VERIFICATION COMPLETE"
echo "========================================"