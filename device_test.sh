#!/bin/bash

# Device Verification Script for UniFFI Integration
# Tests the RGBA→Neural→Quantization→GIF89a pipeline end-to-end

DEVICE="R5CX62YBM4H"
PACKAGE="com.rgbagif"
OUTPUT_DIR="/Users/daniel/rgba-gif89a-camera/device_test_output"

echo "🔍 UniFFI Integration Verification"
echo "=================================="
echo "Device: $DEVICE"
echo "Package: $PACKAGE"
echo ""

# Create output directory
mkdir -p "$OUTPUT_DIR"

echo "📱 Starting app and monitoring logs..."

# Start the app
adb -s $DEVICE shell am start -n "$PACKAGE/.MainActivity"

# Monitor logs for UniFFI and native library loading
echo "🔧 Monitoring native library loading..."
timeout 10s adb -s $DEVICE logcat -c
timeout 10s adb -s $DEVICE logcat "*:I" | grep -E "(System.loadLibrary|m3gif|UniFFI|panic|FATAL)" &

# Wait for app to initialize
sleep 3

echo "📸 Testing M1→M2→M3 pipeline..."

# Pull any generated GIF files
echo "📁 Checking for generated GIF files..."
adb -s $DEVICE shell "find /sdcard -name '*.gif' -type f 2>/dev/null" | while read file; do
    if [ ! -z "$file" ]; then
        filename=$(basename "$file")
        echo "Found GIF: $filename"
        adb -s $DEVICE pull "$file" "$OUTPUT_DIR/$filename"
    fi
done

# Check app logs for our panic-safe FFI functions
echo "🛡️ Checking panic-safe FFI function calls..."
adb -s $DEVICE logcat -d | grep -E "(m3_create_gif89a_rgba|m2_downsize_rgba|m3_save_gif_to_file)" > "$OUTPUT_DIR/ffi_calls.log"

# Check for any crashes or native errors
echo "⚠️ Checking for crashes..."
adb -s $DEVICE logcat -d | grep -E "(FATAL|AndroidRuntime|SIGSEGV|SIGABRT)" > "$OUTPUT_DIR/crashes.log"

# Check UniFFI binding generation
echo "🔗 Checking UniFFI bindings..."
adb -s $DEVICE logcat -d | grep -i "uniffi" > "$OUTPUT_DIR/uniffi.log"

echo ""
echo "✅ Verification complete! Results in: $OUTPUT_DIR"
echo ""

# Summary
echo "📊 Summary:"
if [ -s "$OUTPUT_DIR/crashes.log" ]; then
    echo "❌ Crashes detected - check crashes.log"
else
    echo "✅ No crashes detected"
fi

if [ -s "$OUTPUT_DIR/ffi_calls.log" ]; then
    echo "✅ FFI function calls detected"
else
    echo "⚠️ No FFI function calls detected yet"
fi

gif_count=$(find "$OUTPUT_DIR" -name "*.gif" | wc -l)
echo "📸 GIF files found: $gif_count"

echo ""
echo "🔍 To manually test:"
echo "1. Open the app on device $DEVICE"
echo "2. Go through M1 capture → M2 processing → M3 export"
echo "3. Check that GIF files are generated successfully"
echo "4. Verify no crashes during the pipeline"

wait
