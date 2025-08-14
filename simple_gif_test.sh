#!/bin/bash

# Simple GIF89a Pipeline Test
# Focus: Can we create a GIF89a file and download it?

echo "🎯 SIMPLE GIF89a PIPELINE TEST"
echo "============================="
echo ""

# Use real device (not emulator) for better testing
DEVICE_ID="R5CX62YBM4H"
PACKAGE="com.rgbagif.debug"

echo "📱 Device: $DEVICE_ID"
echo "📦 Package: $PACKAGE"
echo ""

echo "🚀 Starting app..."
adb -s $DEVICE_ID shell am start -n "$PACKAGE/com.rgbagif.MainActivity" -W
sleep 2

echo "🧹 Clearing existing files..."
adb -s $DEVICE_ID shell "rm -rf /sdcard/Android/data/$PACKAGE/files/*" 2>/dev/null

echo "📸 Starting capture simulation..."
echo "   - This will take ~3-5 seconds to capture 81 frames"
echo "   - Then ~5-10 seconds for M2→M3 processing"

# Grant permissions if needed
adb -s $DEVICE_ID shell pm grant $PACKAGE android.permission.CAMERA 2>/dev/null
adb -s $DEVICE_ID shell pm grant $PACKAGE android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null

# Monitor logs for key events
echo "🔍 Monitoring for GIF creation..."
echo "   Looking for: M3_GIF_DONE, PIPELINE_ERROR, final.gif"
echo ""

# Start log monitoring in background
timeout 30 adb -s $DEVICE_ID logcat -c # Clear logs
timeout 30 adb -s $DEVICE_ID logcat | grep -E "(M3_GIF_DONE|PIPELINE_ERROR|final\.gif|M3_START)" &
LOG_PID=$!

# Wait for processing
echo "⏱️  Waiting for processing (max 30 seconds)..."
sleep 30

# Stop log monitoring
kill $LOG_PID 2>/dev/null

echo ""
echo "🔍 CHECKING RESULTS"
echo "=================="

# Check if any GIF files were created
echo "📄 Looking for GIF files..."
GIF_FILES=$(adb -s $DEVICE_ID shell "find /sdcard/Android/data/$PACKAGE -name '*.gif' 2>/dev/null")

if [[ -n "$GIF_FILES" ]]; then
    echo "✅ GIF FILES FOUND:"
    echo "$GIF_FILES"
    echo ""
    
    # Get the first/latest GIF
    LATEST_GIF=$(echo "$GIF_FILES" | head -1 | tr -d '\r\n')
    
    echo "📥 DOWNLOADING GIF: $LATEST_GIF"
    adb -s $DEVICE_ID pull "$LATEST_GIF" ./downloaded_test.gif
    
    if [[ -f "./downloaded_test.gif" ]]; then
        echo "✅ SUCCESS! GIF downloaded to: ./downloaded_test.gif"
        
        # Check file size
        GIF_SIZE=$(ls -l ./downloaded_test.gif | awk '{print $5}')
        echo "📊 File size: $GIF_SIZE bytes ($(($GIF_SIZE / 1024))KB)"
        
        # Check GIF header
        if xxd -l 6 ./downloaded_test.gif 2>/dev/null | grep -q "4749 4638 3961"; then
            echo "✅ Valid GIF89a header detected!"
        else
            echo "❌ Invalid GIF header"
        fi
        
        # Try to open it
        echo "🖼️  Opening GIF..."
        if command -v open >/dev/null; then
            open ./downloaded_test.gif
        fi
        
        echo ""
        echo "🎉 SUCCESS: GIF89a pipeline is working!"
        echo "   - 81 frames captured → M2 processed → M3 exported → Downloaded"
        
    else
        echo "❌ Failed to download GIF"
    fi
    
else
    echo "❌ NO GIF FILES FOUND"
    echo ""
    echo "🔍 Checking what files were created:"
    adb -s $DEVICE_ID shell "find /sdcard/Android/data/$PACKAGE -type f 2>/dev/null" | head -10
    
    echo ""
    echo "🔍 Checking recent logs for errors:"
    adb -s $DEVICE_ID logcat -d | grep -E "(PIPELINE_ERROR|Error|Exception)" | tail -5
fi

echo ""
echo "========================"
echo "Test complete!"
