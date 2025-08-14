#!/bin/bash

# RGBA GIF89a Camera - Pipeline Verification Script
# Verifies M1→M2→M3 pipeline with canonical logging

set -e

echo "========================================"
echo "RGBA GIF89a Pipeline Verification"
echo "========================================"
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if device is connected
if ! adb devices | grep -q device; then
    echo -e "${RED}❌ No Android device connected${NC}"
    echo "Please connect a device and enable USB debugging"
    exit 1
fi

DEVICE=$(adb devices | grep device | head -1 | awk '{print $1}')
echo -e "${GREEN}✅ Device connected: $DEVICE${NC}"
echo ""

# Function to check for canonical log event
check_log_event() {
    local event=$1
    local timeout=${2:-10}
    local found=false
    
    echo -n "Checking for $event... "
    
    for i in $(seq 1 $timeout); do
        if adb logcat -d | grep -q "$event"; then
            found=true
            break
        fi
        sleep 1
    done
    
    if $found; then
        echo -e "${GREEN}✅ Found${NC}"
        return 0
    else
        echo -e "${RED}❌ Not found${NC}"
        return 1
    fi
}

# Function to extract log metrics
extract_metric() {
    local pattern=$1
    adb logcat -d | grep "$pattern" | tail -1 | sed -E "s/.*$pattern.*/\1/"
}

echo "1. CLEARING LOGS"
echo "----------------"
adb logcat -c
echo "Logs cleared"
echo ""

echo "2. LAUNCHING APP"
echo "----------------"
PACKAGE="com.rgbagif"
ACTIVITY="$PACKAGE/.MainActivity"

# Force stop if running
adb shell am force-stop $PACKAGE

# Launch app
adb shell am start -n $ACTIVITY
sleep 3

if adb shell ps | grep -q $PACKAGE; then
    echo -e "${GREEN}✅ App launched successfully${NC}"
else
    echo -e "${RED}❌ Failed to launch app${NC}"
    exit 1
fi
echo ""

echo "3. CHECKING CANONICAL LOG EVENTS"
echo "---------------------------------"

# M1 Events
echo "M1 (RGBA Capture):"
check_log_event "CAMERA_INIT.*format.*RGBA.*resolution.*729"
check_log_event "M1_START.*sessionId"
check_log_event "M1_FRAME_SAVED.*idx.*width.*729.*height.*729"
check_log_event "M1_DONE.*totalFrames"

echo ""

# M2 Events
echo "M2 (Neural Downsize):"
check_log_event "M2_START.*sessionId"
check_log_event "M2_FRAME_DONE.*inW.*729.*outW.*81"
check_log_event "M2_MOSAIC_DONE.*grid.*9x9"
check_log_event "M2_DONE.*totalFrames"

echo ""

# M3 Events
echo "M3 (GIF Export):"
check_log_event "M3_START.*sessionId"
check_log_event "M3_GIF_DONE.*frames.*fps.*loop"

echo ""

# Rust Events
echo "Rust Module Events:"
check_log_event "M1_RUST_INIT.*version"
check_log_event "M1_RUST_WRITE_CBOR.*idx.*bytes"
check_log_event "M2_RUST_DOWNSIZE_BEGIN"
check_log_event "M2_RUST_DOWNSIZE_END.*elapsedMs"

echo ""

echo "4. PERFORMANCE METRICS"
echo "----------------------"

# Extract timing metrics
M1_TIME=$(adb logcat -d | grep "M1_DONE" | tail -1 | grep -oE "elapsedMs: [0-9]+" | grep -oE "[0-9]+")
M2_TIME=$(adb logcat -d | grep "M2_DONE" | tail -1 | grep -oE "elapsedMs: [0-9]+" | grep -oE "[0-9]+")
M3_TIME=$(adb logcat -d | grep "M3_GIF_DONE" | tail -1 | grep -oE "elapsedMs: [0-9]+" | grep -oE "[0-9]+")

if [ ! -z "$M1_TIME" ]; then
    echo "M1 Capture Time: ${M1_TIME}ms"
fi
if [ ! -z "$M2_TIME" ]; then
    echo "M2 Processing Time: ${M2_TIME}ms"
fi
if [ ! -z "$M3_TIME" ]; then
    echo "M3 Export Time: ${M3_TIME}ms"
fi

echo ""

echo "5. ERROR CHECK"
echo "--------------"

ERROR_COUNT=$(adb logcat -d | grep -c "PIPELINE_ERROR" || true)
if [ "$ERROR_COUNT" -eq 0 ]; then
    echo -e "${GREEN}✅ No pipeline errors detected${NC}"
else
    echo -e "${YELLOW}⚠️  Found $ERROR_COUNT pipeline errors:${NC}"
    adb logcat -d | grep "PIPELINE_ERROR" | head -3
fi

echo ""

echo "6. MEMORY CHECK"
echo "---------------"

MEMORY_LOG=$(adb logcat -d | grep "MEMORY_SNAPSHOT" | tail -1)
if [ ! -z "$MEMORY_LOG" ]; then
    echo "Latest memory snapshot:"
    echo "$MEMORY_LOG" | grep -oE "availableMb: [0-9]+.*usedMb: [0-9]+"
else
    echo "No memory snapshots found"
fi

echo ""

echo "========================================"
echo "VERIFICATION SUMMARY"
echo "========================================"

# Count successful checks
TOTAL_CHECKS=13
PASSED_CHECKS=$(grep -c "✅" /tmp/verify_output 2>/dev/null || echo 0)

if [ "$ERROR_COUNT" -eq 0 ]; then
    echo -e "${GREEN}✅ Pipeline verification PASSED${NC}"
    echo "All canonical log events detected"
    echo "No errors encountered"
else
    echo -e "${YELLOW}⚠️  Pipeline verification PARTIAL${NC}"
    echo "Some events may be missing or errors detected"
fi

echo ""
echo "To view full logs, run:"
echo "  adb logcat | grep -E 'M1_|M2_|M3_|CAMERA_|PIPELINE_|MEMORY_'"
echo ""
echo "To filter by milestone:"
echo "  adb logcat | grep M1_    # M1 events only"
echo "  adb logcat | grep M2_    # M2 events only"
echo "  adb logcat | grep M3_    # M3 events only"