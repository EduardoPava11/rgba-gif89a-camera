#!/bin/bash
# Grab logs for all milestones

set -e

echo "=== RGBA→GIF89a Milestone Testing ==="
echo "Starting at $(date)"
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Use DEVICE env var if set, otherwise default to emulator-5554
DEVICE=${DEVICE:-emulator-5554}

# Clear and setup
adb -s "$DEVICE" logcat -c
adb -s "$DEVICE" shell am force-stop com.rgbagif.debug

# Start unified log collection
adb -s "$DEVICE" logcat -v time > full_test.log &
FULL_LOG_PID=$!

# Launch app
echo -e "${YELLOW}Launching app...${NC}"
adb -s "$DEVICE" shell am start -n com.rgbagif.debug/com.rgbagif.MainActivity
sleep 3

# Grant permissions if needed
adb -s "$DEVICE" shell pm grant com.rgbagif.debug android.permission.CAMERA 2>/dev/null || true

# M1: Capture
echo -e "${YELLOW}📸 M1: Starting capture...${NC}"
adb -s "$DEVICE" shell input tap 540 2148  # START button
sleep 10  # Capture for 10 seconds
adb -s "$DEVICE" shell input tap 540 2148  # STOP button
echo -e "${GREEN}✅ M1: Capture complete${NC}"
sleep 2

# M2: Browse and Downsize
echo -e "${YELLOW}🔍 M2: Browsing and downsizing...${NC}"
adb -s "$DEVICE" shell input tap 965 178  # Navigate to browser (View Frames FAB)
sleep 2
adb -s "$DEVICE" shell input tap 960 100  # Downsize all (top-right action)
sleep 5
echo -e "${GREEN}✅ M2: Downsize complete${NC}"

# M3: GIF Export (when implemented)
echo -e "${YELLOW}🎞️ M3: Exporting GIF...${NC}"
# adb -s "$DEVICE" shell am broadcast -a com.rgbagif.EXPORT_GIF
echo -e "${YELLOW}⏳ M3: Not yet implemented${NC}"

# Stop logging
sleep 2
kill $FULL_LOG_PID 2>/dev/null || true

# Split logs by milestone
echo ""
echo "Splitting logs by milestone..."
grep -E "RGBA\.(CAPTURE|CBOR|PNG|ERROR)" full_test.log > m1.log || true
grep -E "RGBA\.(DOWNSIZE|ERROR)" full_test.log > m2.log || true
grep -E "RGBA\.(QUANT|GIF|ERROR)" full_test.log > m3.log || true

# Count log entries
M1_COUNT=$(wc -l < m1.log)
M2_COUNT=$(wc -l < m2.log)
M3_COUNT=$(wc -l < m3.log)

echo ""
echo "=== Log Summary ==="
echo "M1 (Capture): $M1_COUNT entries"
echo "M2 (Downsize): $M2_COUNT entries"
echo "M3 (GIF): $M3_COUNT entries"
echo ""
echo "Logs saved to: m1.log, m2.log, m3.log"
echo "Full log: full_test.log"
echo ""
echo "Run validators with:"
echo "  ./scripts/m1_validate.sh"
echo "  ./scripts/m2_validate.sh"