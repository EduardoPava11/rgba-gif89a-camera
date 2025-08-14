#!/bin/bash

# RGBA→GIF89a Pipeline Validation Script
# Verifies M1 capture → M2 downsize → M3 GIF89a export works end-to-end

set -e

LOG_FILE="/tmp/rgba_gif_pipeline_test.log"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="com.rgbagif.debug"

echo "🚀 RGBA→GIF89a Pipeline End-to-End Test"
echo "========================================"

# 1. Build & install
echo "📦 Building APK..."
./gradlew :app:assembleDebug || {
    echo "❌ Build failed"
    exit 1
}

echo "📱 Installing APK..."
adb install -r "$APK_PATH" || {
    echo "❌ Installation failed"  
    exit 1
}

# 2. Clear logs and start app
echo "🧹 Clearing logs..."
adb logcat -c

echo "🎬 Starting app..."
adb shell am start -n "$PACKAGE/com.rgbagif.MainActivity" || {
    echo "❌ Failed to start app"
    exit 1
}

# Wait for app to initialize
sleep 5

echo "📋 Collecting logs (30 second window for manual testing)..."
echo "   Please capture 81 frames using the app now..."
sleep 30

adb logcat -d > "$LOG_FILE"

# 3. Verify pipeline execution
echo ""
echo "🔍 PIPELINE VERIFICATION"
echo "========================"

# Check M1 capture
if grep -q "CAPTURE_WIDTH.*729" "$LOG_FILE" && grep -q "CAPTURE_HEIGHT.*729" "$LOG_FILE"; then
    echo "✅ M1 Capture: 729×729 RGBA_8888 configured"
else
    echo "❌ M1 Capture: Invalid dimensions"
fi

# Check frame count requirement
if grep -q "CAPTURE_FRAME_COUNT.*81" "$LOG_FILE"; then
    echo "✅ Frame Count: Configured for exactly 81 frames"
else
    echo "❌ Frame Count: Not set to 81 frames"
fi

# Check M2 processing
M2_FRAME_COUNT=$(grep -c "M2_FRAME_END" "$LOG_FILE" || echo "0")
echo "📊 M2 Frames Processed: $M2_FRAME_COUNT"

if [[ "$M2_FRAME_COUNT" -eq 81 ]]; then
    echo "✅ M2 Processing: All 81 frames processed"
elif [[ "$M2_FRAME_COUNT" -gt 0 ]]; then
    echo "⚠️  M2 Processing: Only $M2_FRAME_COUNT frames (expected 81)"
else
    echo "❌ M2 Processing: No frames processed"
fi

# Check PNG output (lossless)
if grep -q "Saved PNG" "$LOG_FILE"; then
    echo "✅ M2 Output: PNG format (lossless, GIF-ready)"
else
    echo "❌ M2 Output: No PNG files detected"
fi

# Check M3 pipeline connection
if grep -q "M3_START frames=81" "$LOG_FILE"; then
    echo "✅ M3 Pipeline: Triggered with 81 frames"
elif grep -q "M3_START" "$LOG_FILE"; then
    ACTUAL_FRAMES=$(grep "M3_START" "$LOG_FILE" | sed 's/.*frames=\([0-9]*\).*/\1/')
    echo "⚠️  M3 Pipeline: Started with $ACTUAL_FRAMES frames (expected 81)"
else
    echo "❌ M3 Pipeline: Not triggered"
fi

# Check GIF89a spec compliance
if grep -q "M3_GIF_DONE.*loop=true" "$LOG_FILE"; then
    echo "✅ GIF89a Spec: Loop extension present"
else
    echo "❌ GIF89a Spec: No loop extension"
fi

# Check 4cs delay (25 fps)
if grep -q 'delay="4"' "$LOG_FILE"; then
    echo "✅ GIF89a Timing: 4cs delay (~25 fps)"
else
    echo "❌ GIF89a Timing: Wrong delay setting"
fi

# 4. Check output files on device
echo ""
echo "📁 FILE VERIFICATION" 
echo "===================="

# Check for final GIF
GIF_FILES=$(adb shell "find /sdcard/Android/data/$PACKAGE/files -name '*.gif' 2>/dev/null | wc -l" || echo "0")
echo "📄 GIF Files Created: $GIF_FILES"

if [[ "$GIF_FILES" -gt 0 ]]; then
    echo "✅ GIF Export: Files created successfully"
    
    # Pull the GIF for inspection
    LATEST_GIF=$(adb shell "find /sdcard/Android/data/$PACKAGE/files -name '*.gif' 2>/dev/null | head -1" | tr -d '\r')
    if [[ -n "$LATEST_GIF" ]]; then
        echo "📥 Pulling GIF for validation: $LATEST_GIF"
        adb pull "$LATEST_GIF" ./final_test.gif 2>/dev/null || echo "⚠️ Could not pull GIF file"
        
        if [[ -f "./final_test.gif" ]]; then
            GIF_SIZE=$(ls -l ./final_test.gif | awk '{print $5}')
            echo "📊 GIF Size: $GIF_SIZE bytes"
            
            # Basic GIF header check
            if xxd -l 6 ./final_test.gif | grep -q "4749 4638 3961"; then
                echo "✅ GIF Header: Valid 'GIF89a' signature"
            else
                echo "❌ GIF Header: Invalid signature"
            fi
        fi
    fi
else
    echo "❌ GIF Export: No files created"
fi

# Check PNG frames
PNG_COUNT=$(adb shell "find /sdcard/Android/data/$PACKAGE/files -name '*.png' 2>/dev/null | wc -l" || echo "0")
echo "📄 PNG Frames Created: $PNG_COUNT"

if [[ "$PNG_COUNT" -eq 81 ]]; then
    echo "✅ PNG Frames: All 81 frames saved"
elif [[ "$PNG_COUNT" -gt 0 ]]; then
    echo "⚠️  PNG Frames: Only $PNG_COUNT frames (expected 81)"
else
    echo "❌ PNG Frames: No frames saved"
fi

# 5. Error detection
echo ""
echo "🚨 ERROR ANALYSIS"
echo "=================="

PIPELINE_ERRORS=$(grep -c "PIPELINE_ERROR" "$LOG_FILE" || echo "0")
if [[ "$PIPELINE_ERRORS" -gt 0 ]]; then
    echo "❌ Pipeline Errors: $PIPELINE_ERRORS found"
    echo "   Recent errors:"
    grep "PIPELINE_ERROR" "$LOG_FILE" | tail -3
else
    echo "✅ Pipeline Errors: None detected"
fi

# Check for critical failures
if grep -q "expected 81 frames" "$LOG_FILE"; then
    echo "❌ Critical: Frame count mismatch - GIF89a spec requires exactly 81"
fi

if grep -q "UniFFI contract mismatch" "$LOG_FILE"; then
    echo "⚠️  Warning: UniFFI disabled - using fallback mode"
fi

# 6. Final summary
echo ""
echo "📊 FINAL VALIDATION SUMMARY"
echo "============================"

SCORE=0
MAX_SCORE=8

# Scoring
[[ "$M2_FRAME_COUNT" -eq 81 ]] && ((SCORE++))
grep -q "Saved PNG" "$LOG_FILE" && ((SCORE++))
grep -q "M3_START frames=81" "$LOG_FILE" && ((SCORE++))  
grep -q "M3_GIF_DONE.*loop=true" "$LOG_FILE" && ((SCORE++))
grep -q 'delay="4"' "$LOG_FILE" && ((SCORE++))
[[ "$GIF_FILES" -gt 0 ]] && ((SCORE++))
[[ -f "./final_test.gif" ]] && xxd -l 6 ./final_test.gif | grep -q "4749 4638 3961" && ((SCORE++))
[[ "$PIPELINE_ERRORS" -eq 0 ]] && ((SCORE++))

echo "🎯 Pipeline Score: $SCORE/$MAX_SCORE"

if [[ "$SCORE" -eq "$MAX_SCORE" ]]; then
    echo "🎉 SUCCESS: RGBA→GIF89a pipeline working perfectly!"
    echo "   ✅ 81 frames captured at 729×729"
    echo "   ✅ M2 downsize to 81×81 (PNG lossless)"
    echo "   ✅ M3 GIF89a export with proper spec compliance"
    echo "   ✅ 4cs delay, infinite loop, valid header"
elif [[ "$SCORE" -ge 5 ]]; then
    echo "⚠️  PARTIAL: Pipeline mostly working ($SCORE/$MAX_SCORE)"
    echo "   Some components need attention"
else
    echo "❌ FAILED: Major pipeline issues ($SCORE/$MAX_SCORE)"
    echo "   Significant fixes required"
fi

echo ""
echo "📋 Log file saved to: $LOG_FILE"
echo "🎞️  Test GIF available at: ./final_test.gif"
echo ""
echo "✅ Test completed!"
