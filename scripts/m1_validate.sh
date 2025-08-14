#!/bin/bash
# Validate M1 logs

set -e

echo "=== M1 Validation (Capture → CBOR → PNG) ==="
echo ""

# Check if log file exists
if [ ! -f "m1.log" ]; then
    echo "❌ Error: m1.log not found. Run grab_logs.sh first."
    exit 1
fi

# Extract session ID
SESSION_ID=$(grep '"event":"capture_start"' m1.log | head -1 | sed 's/.*"session_id":"\([^"]*\)".*/\1/' 2>/dev/null || echo "")

if [ -z "$SESSION_ID" ]; then
    echo "❌ No capture_start event found"
    exit 1
fi

echo "Session ID: $SESSION_ID"
echo ""

# Count frames captured
FRAME_COUNT=$(grep '"event":"frame_saved"' m1.log | grep "\"session_id\":\"$SESSION_ID\"" | wc -l)
echo "Frames captured: $FRAME_COUNT"

# Validate frame count
if [ "$FRAME_COUNT" -eq 81 ]; then
    echo "✅ Frame count: PASS (81 frames)"
else
    echo "❌ Frame count: FAIL (expected 81, got $FRAME_COUNT)"
fi

# Check CBOR sizes (should be 729*729*4 = 2125764 bytes)
echo ""
echo "Checking CBOR sizes..."
EXPECTED_SIZE=$((729 * 729 * 4))
INVALID_SIZES=0

while IFS= read -r size; do
    if [ "$size" -ne "$EXPECTED_SIZE" ]; then
        echo "⚠️ Unexpected CBOR size: $size bytes (expected $EXPECTED_SIZE)"
        ((INVALID_SIZES++))
    fi
done < <(grep '"event":"frame_saved"' m1.log | grep -o '"bytes_out":[0-9]*' | cut -d: -f2)

if [ "$INVALID_SIZES" -eq 0 ]; then
    echo "✅ CBOR sizes: All correct ($EXPECTED_SIZE bytes)"
else
    echo "⚠️ CBOR sizes: $INVALID_SIZES frames with incorrect size"
fi

# Calculate timing statistics
echo ""
echo "Timing Analysis:"

# Average frame save time
AVG_MS=$(grep '"event":"frame_saved"' m1.log | grep -o '"dt_ms":[0-9.]*' | cut -d: -f2 | awk '{sum+=$1; count++} END {if(count>0) printf "%.2f", sum/count; else print "N/A"}')
echo "  Average frame save time: ${AVG_MS}ms"

# Min/Max times
MIN_MS=$(grep '"event":"frame_saved"' m1.log | grep -o '"dt_ms":[0-9.]*' | cut -d: -f2 | sort -n | head -1)
MAX_MS=$(grep '"event":"frame_saved"' m1.log | grep -o '"dt_ms":[0-9.]*' | cut -d: -f2 | sort -n | tail -1)
echo "  Min frame save time: ${MIN_MS}ms"
echo "  Max frame save time: ${MAX_MS}ms"

# Total capture time
TOTAL_MS=$(grep '"event":"capture_done"' m1.log | grep -o '"dt_ms_cum":[0-9.]*' | cut -d: -f2 | head -1)
if [ -n "$TOTAL_MS" ]; then
    echo "  Total capture time: ${TOTAL_MS}ms"
    FPS=$(echo "scale=2; $FRAME_COUNT * 1000 / $TOTAL_MS" | bc 2>/dev/null || echo "N/A")
    echo "  Effective FPS: $FPS"
fi

# Check for errors
echo ""
ERROR_COUNT=$(grep '"ok":false' m1.log | wc -l)
if [ "$ERROR_COUNT" -eq 0 ]; then
    echo "✅ No errors detected"
else
    echo "❌ Errors found: $ERROR_COUNT"
    echo "Error details:"
    grep '"ok":false' m1.log | head -3
fi

# Summary
echo ""
echo "=== M1 Summary ==="
if [ "$FRAME_COUNT" -eq 81 ] && [ "$INVALID_SIZES" -eq 0 ] && [ "$ERROR_COUNT" -eq 0 ]; then
    echo "✅ M1 VALIDATION: PASS"
    exit 0
else
    echo "❌ M1 VALIDATION: FAIL"
    exit 1
fi