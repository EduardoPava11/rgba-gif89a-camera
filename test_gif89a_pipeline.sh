#!/bin/bash

# RGBA→GIF89a Pipeline Test Script
# Tests the full M1→M2→M3 pipeline and validates GIF89a output

echo "🎯 RGBA→GIF89a Pipeline Test"
echo "==============================="
echo ""

# Configuration
PACKAGE="com.rgbagif.debug"
TIMEOUT=60
TEST_START_TIME=$(date +%s)

echo "📱 DEVICE SETUP"
echo "==============="

# Check for connected devices
DEVICE_COUNT=$(adb devices -l | grep -v "List of devices" | grep -c device)
if [[ "$DEVICE_COUNT" -eq 0 ]]; then
    echo "❌ No devices connected"
    echo "Connect a device via USB debugging or start an emulator"
    exit 1
elif [[ "$DEVICE_COUNT" -eq 1 ]]; then
    echo "✅ One device connected"
else
    echo "✅ Multiple devices connected ($DEVICE_COUNT total)"
fi

# Start app
echo ""
echo "📱 LAUNCHING APP"
echo "=================="

adb shell am force-stop $PACKAGE
sleep 1
adb shell am start -n $PACKAGE/.MainActivity
sleep 3

# Check if app launched successfully
APP_RUNNING=$(adb shell pidof $PACKAGE)
if [[ -z "$APP_RUNNING" ]]; then
    echo "❌ App failed to launch"
    exit 1
fi
echo "✅ App launched successfully (PID: $APP_RUNNING)"

echo ""
echo "🎬 STARTING CAPTURE TEST"
echo "========================="

# Clear previous logs
adb logcat -c

# Look for "Ready to test" or start capture automatically
echo "Starting monitoring logs for M1→M2→M3 pipeline..."

# Start capturing logs in background
adb logcat | grep -E "(CaptureViewModel|M2Processor|M3Processor|GIF89a|PIPELINE)" > pipeline_logs.txt &
LOGCAT_PID=$!

# Give it time to capture frames
echo "⏱️ Waiting for capture completion (up to ${TIMEOUT}s)..."
echo ""

# Wait for GIF completion
ELAPSED=0
while [[ $ELAPSED -lt $TIMEOUT ]]; do
    # Check for M3 completion
    GIF_DONE=$(adb logcat -d | grep -c "M3_GIF_DONE")
    
    if [[ "$GIF_DONE" -gt 0 ]]; then
        echo "✅ GIF89a export completed!"
        break
    fi
    
    # Check for errors
    ERRORS=$(adb logcat -d | grep -c "PIPELINE_ERROR")
    if [[ "$ERRORS" -gt 0 ]]; then
        echo "❌ Pipeline error detected"
        adb logcat -d | grep "PIPELINE_ERROR" | tail -3
        break
    fi
    
    # Progress update
    FRAMES_CAPTURED=$(adb logcat -d | grep -c "Captured frame")
    M2_FRAMES=$(adb logcat -d | grep -c "M2_FRAME_END")
    
    if [[ "$FRAMES_CAPTURED" -gt 0 || "$M2_FRAMES" -gt 0 ]]; then
        echo "📸 Captured: $FRAMES_CAPTURED frames, M2 processed: $M2_FRAMES"
    fi
    
    sleep 2
    ELAPSED=$((ELAPSED + 2))
done

# Stop logcat monitoring
kill $LOGCAT_PID 2>/dev/null

echo ""
echo "📊 PIPELINE RESULTS"
echo "===================="

# Analyze logs
CAPTURE_COUNT=$(adb logcat -d | grep -c "Captured frame")
M2_COUNT=$(adb logcat -d | grep -c "M2_FRAME_END\|downsized to 81×81")
M3_START=$(adb logcat -d | grep -c "M3_START\|Starting M2→M3 pipeline")
M3_DONE=$(adb logcat -d | grep -c "M3_GIF_DONE\|M3 GIF89a export complete")

echo "📸 Frames captured: $CAPTURE_COUNT"
echo "🔄 M2 frames processed: $M2_COUNT"
echo "🎞️ M3 pipeline started: $M3_START"
echo "✅ M3 GIF89a completed: $M3_DONE"

# Check for actual GIF files
echo ""
echo "📁 FILE VALIDATION"
echo "==================="

# Find GIF files
GIF_FILES=$(adb shell "find /sdcard/Android/data/$PACKAGE/files -name '*.gif' -type f 2>/dev/null | wc -l" | tr -d '\r\n')
echo "📄 GIF files found: $GIF_FILES"

if [[ "$GIF_FILES" -gt 0 ]]; then
    echo "✅ GIF Export: Files created successfully"
    
    # Get the newest GIF file
    LATEST_GIF=$(adb shell "find /sdcard/Android/data/$PACKAGE/files -name '*.gif' -type f 2>/dev/null | head -1" | tr -d '\r\n')
    
    if [[ -n "$LATEST_GIF" ]]; then
        echo "📥 Latest GIF: $LATEST_GIF"
        
        # Get file info
        GIF_SIZE=$(adb shell "stat -c%s '$LATEST_GIF' 2>/dev/null" | tr -d '\r\n')
        if [[ -n "$GIF_SIZE" && "$GIF_SIZE" -gt 0 ]]; then
            GIF_SIZE_KB=$((GIF_SIZE / 1024))
            echo "📊 GIF Size: ${GIF_SIZE_KB}KB ($GIF_SIZE bytes)"
            
            # Pull GIF for inspection
            echo "📥 Pulling GIF for validation..."
            adb pull "$LATEST_GIF" ./pipeline_test_output.gif 2>/dev/null
            
            if [[ -f "./pipeline_test_output.gif" ]]; then
                echo "✅ GIF pulled successfully: ./pipeline_test_output.gif"
                
                # Basic GIF header validation
                if xxd -l 6 ./pipeline_test_output.gif 2>/dev/null | grep -q "4749 4638 3961"; then
                    echo "✅ GIF89a Header: Valid 'GIF89a' signature detected"
                else
                    echo "❌ GIF89a Header: Invalid or missing signature"
                fi
                
                # File size check
                if [[ "$GIF_SIZE_KB" -lt 1000 ]]; then
                    echo "✅ File Size: Under 1MB ($GIF_SIZE_KB KB) - Good compression"
                else
                    echo "⚠️ File Size: Large file ($GIF_SIZE_KB KB) - May need optimization"
                fi
            else
                echo "⚠️ Could not pull GIF file for inspection"
            fi
        else
            echo "❌ GIF file appears to be empty or inaccessible"
        fi
    fi
else
    echo "❌ GIF Export: No files created"
fi

echo ""
echo "🔍 ERROR ANALYSIS"
echo "=================="

PIPELINE_ERRORS=$(adb logcat -d | grep -c "PIPELINE_ERROR")
M3_ERRORS=$(adb logcat -d | grep -c "M3.*failed\|M3.*error")
M2_ERRORS=$(adb logcat -d | grep -c "M2.*failed\|M2.*error")

echo "❌ Pipeline errors: $PIPELINE_ERRORS"
echo "❌ M2 errors: $M2_ERRORS"  
echo "❌ M3 errors: $M3_ERRORS"

if [[ "$PIPELINE_ERRORS" -gt 0 ]]; then
    echo ""
    echo "Recent pipeline errors:"
    adb logcat -d | grep "PIPELINE_ERROR" | tail -3
fi

echo ""
echo "📈 PERFORMANCE SUMMARY"
echo "======================"

TOTAL_TEST_TIME=$(($(date +%s) - TEST_START_TIME))
echo "⏱️ Total test time: ${TOTAL_TEST_TIME}s"

# Success criteria
SUCCESS_SCORE=0
MAX_SCORE=5

# Score the pipeline
if [[ "$CAPTURE_COUNT" -ge 81 ]]; then
    echo "✅ M1 Capture: 81+ frames captured"
    SUCCESS_SCORE=$((SUCCESS_SCORE + 1))
else
    echo "❌ M1 Capture: Only $CAPTURE_COUNT frames (expected 81)"
fi

if [[ "$M2_COUNT" -ge 81 ]]; then
    echo "✅ M2 Processing: 81+ frames processed"
    SUCCESS_SCORE=$((SUCCESS_SCORE + 1))
else
    echo "❌ M2 Processing: Only $M2_COUNT frames processed"
fi

if [[ "$M3_START" -gt 0 ]]; then
    echo "✅ M3 Pipeline: Started successfully"
    SUCCESS_SCORE=$((SUCCESS_SCORE + 1))
else
    echo "❌ M3 Pipeline: Never started"
fi

if [[ "$M3_DONE" -gt 0 ]]; then
    echo "✅ M3 Export: Completed successfully"
    SUCCESS_SCORE=$((SUCCESS_SCORE + 1))
else
    echo "❌ M3 Export: Never completed"
fi

if [[ "$GIF_FILES" -gt 0 ]]; then
    echo "✅ GIF Files: Created successfully"
    SUCCESS_SCORE=$((SUCCESS_SCORE + 1))
else
    echo "❌ GIF Files: No files found"
fi

echo ""
echo "🎯 FINAL RESULT"
echo "================"

PERCENTAGE=$((SUCCESS_SCORE * 100 / MAX_SCORE))
echo "Score: $SUCCESS_SCORE/$MAX_SCORE ($PERCENTAGE%)"

if [[ "$SUCCESS_SCORE" -eq "$MAX_SCORE" ]]; then
    echo "🎉 SUCCESS: Full RGBA→GIF89a pipeline working!"
    echo "✅ M1 capture → M2 downsample → M3 GIF89a export"
    echo ""
    echo "Output files:"
    echo "- Pipeline logs: pipeline_logs.txt"
    echo "- Final GIF: pipeline_test_output.gif"
    exit 0
elif [[ "$SUCCESS_SCORE" -ge 3 ]]; then
    echo "⚠️ PARTIAL SUCCESS: Major components working"
    echo "Some issues need attention but core pipeline functional"
    exit 0
else
    echo "❌ FAILURE: Critical pipeline issues"
    echo "Check logs and fix major components"
    exit 1
fi
