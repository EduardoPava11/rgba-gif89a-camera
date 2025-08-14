#!/bin/bash
# run_device_smoke.sh - On-device smoke test for Milestone 1
# Tests the complete RGBA→GIF89a pipeline on a connected Android device

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
PACKAGE_NAME="com.rgbagif.camera"
ACTIVITY_NAME=".MainActivity"
OUTPUT_DIR="/sdcard/DCIM/RGBAGif89a"
LOGCAT_TAG="RGBAGif89a"

echo -e "${BLUE}═══════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}    RGBA→GIF89a Camera - Milestone 1 Device Test${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════════${NC}"
echo ""

# Check for connected device
echo -e "${YELLOW}▶ Checking for connected device...${NC}"
if ! adb devices | grep -q "device$"; then
    echo -e "${RED}✗ No device connected. Please connect an Android device and enable USB debugging.${NC}"
    exit 1
fi

DEVICE_ID=$(adb devices | grep "device$" | head -n1 | awk '{print $1}')
DEVICE_MODEL=$(adb -s "$DEVICE_ID" shell getprop ro.product.model | tr -d '\r\n')
ANDROID_VERSION=$(adb -s "$DEVICE_ID" shell getprop ro.build.version.release | tr -d '\r\n')

echo -e "${GREEN}✓ Found device: $DEVICE_MODEL (Android $ANDROID_VERSION)${NC}"
echo ""

# Build the app
echo -e "${YELLOW}▶ Building app...${NC}"
./gradlew :app:assembleDebug
if [ $? -ne 0 ]; then
    echo -e "${RED}✗ Build failed${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Build successful${NC}"
echo ""

# Install the app
echo -e "${YELLOW}▶ Installing app...${NC}"
adb -s "$DEVICE_ID" install -r app/build/outputs/apk/debug/app-debug.apk
if [ $? -ne 0 ]; then
    echo -e "${RED}✗ Installation failed${NC}"
    exit 1
fi
echo -e "${GREEN}✓ App installed${NC}"
echo ""

# Grant camera permission
echo -e "${YELLOW}▶ Granting camera permission...${NC}"
adb -s "$DEVICE_ID" shell pm grant "$PACKAGE_NAME" android.permission.CAMERA
adb -s "$DEVICE_ID" shell pm grant "$PACKAGE_NAME" android.permission.WRITE_EXTERNAL_STORAGE || true
echo -e "${GREEN}✓ Permissions granted${NC}"
echo ""

# Clear old test data
echo -e "${YELLOW}▶ Clearing old test data...${NC}"
adb -s "$DEVICE_ID" shell rm -rf "$OUTPUT_DIR" 2>/dev/null || true
echo -e "${GREEN}✓ Old data cleared${NC}"
echo ""

# Start logcat monitoring
echo -e "${YELLOW}▶ Starting logcat monitor...${NC}"
adb -s "$DEVICE_ID" logcat -c
adb -s "$DEVICE_ID" logcat "$LOGCAT_TAG:*" "*:S" > logcat_output.txt &
LOGCAT_PID=$!
echo -e "${GREEN}✓ Logcat monitoring started (PID: $LOGCAT_PID)${NC}"
echo ""

# Launch the app
echo -e "${YELLOW}▶ Launching app...${NC}"
adb -s "$DEVICE_ID" shell am start -n "$PACKAGE_NAME/$ACTIVITY_NAME"
if [ $? -ne 0 ]; then
    echo -e "${RED}✗ Failed to launch app${NC}"
    kill $LOGCAT_PID 2>/dev/null
    exit 1
fi
echo -e "${GREEN}✓ App launched${NC}"
echo ""

# Wait for app to initialize
echo -e "${YELLOW}▶ Waiting for app initialization...${NC}"
sleep 3

# Check if Rust pipeline initialized
if grep -q "Pipeline initialized with Go 9×9 model" logcat_output.txt; then
    echo -e "${GREEN}✓ Rust pipeline initialized${NC}"
else
    echo -e "${YELLOW}⚠ Pipeline initialization not confirmed in logs${NC}"
fi
echo ""

# Simulate capture (requires manual interaction or UI Automator)
echo -e "${BLUE}═══════════════════════════════════════════════════════${NC}"
echo -e "${YELLOW}MANUAL TEST REQUIRED:${NC}"
echo ""
echo "  1. Tap the ${YELLOW}CAPTURE button${NC} (large orange square)"
echo "  2. Watch the ${GREEN}9×9 grid${NC} fill with green squares"
echo "  3. Wait for ${BLUE}81 frames${NC} to be captured"
echo "  4. Observe the ${GREEN}celebration animation${NC}"
echo ""
echo -e "${YELLOW}Press ENTER when capture is complete...${NC}"
read -r

# Check for output files
echo ""
echo -e "${YELLOW}▶ Checking for output files...${NC}"

# Check neural GIF
NEURAL_GIF=$(adb -s "$DEVICE_ID" shell "ls $OUTPUT_DIR/neural/*.gif 2>/dev/null | head -n1" | tr -d '\r\n')
if [ -n "$NEURAL_GIF" ]; then
    echo -e "${GREEN}✓ Neural GIF created: $NEURAL_GIF${NC}"
    
    # Get file size
    SIZE=$(adb -s "$DEVICE_ID" shell "stat -c %s '$NEURAL_GIF'" | tr -d '\r\n')
    echo "  Size: $((SIZE / 1024)) KB"
    
    # Pull file for analysis
    adb -s "$DEVICE_ID" pull "$NEURAL_GIF" test_neural.gif 2>/dev/null
else
    echo -e "${RED}✗ Neural GIF not found${NC}"
fi

# Check debug GIF
DEBUG_GIF=$(adb -s "$DEVICE_ID" shell "ls $OUTPUT_DIR/debug/*.gif 2>/dev/null | head -n1" | tr -d '\r\n')
if [ -n "$DEBUG_GIF" ]; then
    echo -e "${GREEN}✓ Debug GIF created: $DEBUG_GIF${NC}"
    
    # Get file size
    SIZE=$(adb -s "$DEVICE_ID" shell "stat -c %s '$DEBUG_GIF'" | tr -d '\r\n')
    echo "  Size: $((SIZE / 1024)) KB"
    
    # Pull file for analysis
    adb -s "$DEVICE_ID" pull "$DEBUG_GIF" test_debug.gif 2>/dev/null
else
    echo -e "${YELLOW}⚠ Debug GIF not found (optional)${NC}"
fi
echo ""

# Analyze logs for performance metrics
echo -e "${YELLOW}▶ Analyzing performance metrics...${NC}"

# Kill logcat
kill $LOGCAT_PID 2>/dev/null

# Extract metrics from logs
if [ -f logcat_output.txt ]; then
    # Frame processing times
    AVG_TIME=$(grep "Frame .* processed in" logcat_output.txt | \
               sed -n 's/.*processed in \([0-9]*\)ms.*/\1/p' | \
               awk '{sum+=$1; count++} END {if(count>0) print sum/count; else print 0}')
    
    if [ "${AVG_TIME%.*}" != "0" ]; then
        echo -e "  Average processing time: ${YELLOW}${AVG_TIME}ms${NC}"
        
        if (( $(echo "$AVG_TIME < 30" | bc -l) )); then
            echo -e "  ${GREEN}✓ Meets <30ms target${NC}"
        elif (( $(echo "$AVG_TIME < 50" | bc -l) )); then
            echo -e "  ${YELLOW}⚠ Slightly above target (30-50ms)${NC}"
        else
            echo -e "  ${RED}✗ Exceeds 50ms target${NC}"
        fi
    fi
    
    # Palette sizes
    AVG_PALETTE=$(grep "palette=" logcat_output.txt | \
                  sed -n 's/.*palette=\([0-9]*\).*/\1/p' | \
                  awk '{sum+=$1; count++} END {if(count>0) print sum/count; else print 0}')
    
    if [ "${AVG_PALETTE%.*}" != "0" ]; then
        echo -e "  Average palette size: ${YELLOW}${AVG_PALETTE}/256 colors${NC}"
    fi
    
    # Error count
    ERROR_COUNT=$(grep -c "ERROR\|Exception" logcat_output.txt || true)
    if [ "$ERROR_COUNT" -eq 0 ]; then
        echo -e "  ${GREEN}✓ No errors detected${NC}"
    else
        echo -e "  ${RED}✗ $ERROR_COUNT errors found${NC}"
    fi
fi
echo ""

# Validate GIF files if they exist
if [ -f test_neural.gif ]; then
    echo -e "${YELLOW}▶ Validating GIF structure...${NC}"
    
    # Use ImageMagick if available
    if command -v identify &> /dev/null; then
        # Get GIF info
        FRAMES=$(identify test_neural.gif | wc -l)
        DIMENSIONS=$(identify test_neural.gif[0] | awk '{print $3}')
        
        echo "  Neural GIF: ${YELLOW}$FRAMES frames${NC} at ${YELLOW}$DIMENSIONS${NC}"
        
        if [ "$FRAMES" -eq 81 ] && [ "$DIMENSIONS" = "81x81" ]; then
            echo -e "  ${GREEN}✓ Correct frame count and dimensions${NC}"
        else
            echo -e "  ${RED}✗ Unexpected frame count or dimensions${NC}"
        fi
    else
        echo -e "  ${YELLOW}⚠ ImageMagick not installed, skipping GIF validation${NC}"
    fi
fi
echo ""

# Test overlay features
echo -e "${BLUE}═══════════════════════════════════════════════════════${NC}"
echo -e "${YELLOW}OVERLAY FEATURE TEST:${NC}"
echo ""
echo "  1. Tap the ${YELLOW}Alpha (A)${NC} button to toggle alpha overlay"
echo "  2. Tap the ${YELLOW}Delta E (ΔE)${NC} button to toggle color difference"
echo "  3. Tap the ${YELLOW}Info (i)${NC} button to view pipeline documentation"
echo "  4. Tap the ${YELLOW}Status${NC} button to view detailed metrics"
echo ""
echo -e "${YELLOW}Press ENTER when testing is complete...${NC}"
read -r

# Final summary
echo ""
echo -e "${BLUE}═══════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}                    TEST SUMMARY${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════════${NC}"
echo ""

TEST_PASSED=true

# Check critical requirements
echo "Critical Requirements:"
if [ -n "$NEURAL_GIF" ]; then
    echo -e "  ${GREEN}✓${NC} Neural GIF generated"
else
    echo -e "  ${RED}✗${NC} Neural GIF not generated"
    TEST_PASSED=false
fi

if [ "${AVG_TIME%.*}" != "0" ] && (( $(echo "$AVG_TIME < 50" | bc -l) )); then
    echo -e "  ${GREEN}✓${NC} Processing time acceptable (<50ms)"
else
    echo -e "  ${RED}✗${NC} Processing time too slow or not measured"
    TEST_PASSED=false
fi

if [ "$ERROR_COUNT" -eq 0 ]; then
    echo -e "  ${GREEN}✓${NC} No errors in pipeline"
else
    echo -e "  ${RED}✗${NC} Errors detected in pipeline"
    TEST_PASSED=false
fi

echo ""
echo "Optional Features:"
[ -n "$DEBUG_GIF" ] && echo -e "  ${GREEN}✓${NC} Debug GIF generated" || echo -e "  ${YELLOW}○${NC} Debug GIF not generated"
echo ""

if [ "$TEST_PASSED" = true ]; then
    echo -e "${GREEN}════════════════════════════════════════════════════════${NC}"
    echo -e "${GREEN}           ✓ MILESTONE 1 TEST PASSED${NC}"
    echo -e "${GREEN}════════════════════════════════════════════════════════${NC}"
else
    echo -e "${RED}════════════════════════════════════════════════════════${NC}"
    echo -e "${RED}           ✗ MILESTONE 1 TEST FAILED${NC}"
    echo -e "${RED}════════════════════════════════════════════════════════${NC}"
fi

echo ""
echo "Test artifacts saved:"
echo "  • logcat_output.txt - Complete logcat output"
[ -f test_neural.gif ] && echo "  • test_neural.gif - Neural network output"
[ -f test_debug.gif ] && echo "  • test_debug.gif - Debug visualization"
echo ""

exit $([ "$TEST_PASSED" = true ] && echo 0 || echo 1)