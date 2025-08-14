#!/bin/bash
# Validate M2 logs

set -e

echo "=== M2 Validation (Downsize with Go 9×9 NN) ==="
echo ""

# Check if log file exists
if [ ! -f "m2.log" ]; then
    echo "❌ Error: m2.log not found. Run grab_logs.sh first."
    exit 1
fi

# Check if M2 has any logs
if [ ! -s "m2.log" ]; then
    echo "⚠️ Warning: m2.log is empty. M2 may not be implemented yet."
    exit 0
fi

# Extract session ID from downsize_start
SESSION_ID=$(grep '"event":"downsize_start"' m2.log | head -1 | sed 's/.*"session_id":"\([^"]*\)".*/\1/' 2>/dev/null || echo "")

if [ -z "$SESSION_ID" ]; then
    echo "⚠️ No downsize_start event found (M2 not implemented)"
    exit 0
fi

echo "Session ID: $SESSION_ID"
echo ""

# Count downsized frames
FRAME_COUNT=$(grep '"event":"frame_downsized"' m2.log | grep "\"session_id\":\"$SESSION_ID\"" | wc -l)
echo "Frames downsized: $FRAME_COUNT"

# Validate frame count
if [ "$FRAME_COUNT" -eq 81 ]; then
    echo "✅ Frame count: PASS (81 frames)"
else
    echo "❌ Frame count: FAIL (expected 81, got $FRAME_COUNT)"
fi

# Check output sizes (should be 81*81*4 = 26244 bytes for uncompressed)
echo ""
echo "Checking downsized sizes..."
EXPECTED_SIZE=$((81 * 81 * 4))
SIZE_CHECK=0

while IFS= read -r size; do
    if [ "$size" -gt "$EXPECTED_SIZE" ]; then
        echo "⚠️ Unexpected output size: $size bytes (expected ≤ $EXPECTED_SIZE)"
        ((SIZE_CHECK++))
    fi
done < <(grep '"event":"frame_downsized"' m2.log | grep -o '"bytes_out":[0-9]*' | cut -d: -f2)

if [ "$SIZE_CHECK" -eq 0 ]; then
    echo "✅ Output sizes: All within expected range"
fi

# Calculate NN timing statistics
echo ""
echo "Neural Network Performance:"

# Average NN inference time
AVG_MS=$(grep '"event":"frame_downsized"' m2.log | grep -o '"dt_ms":[0-9.]*' | cut -d: -f2 | awk '{sum+=$1; count++} END {if(count>0) printf "%.2f", sum/count; else print "N/A"}')
echo "  Average NN inference time: ${AVG_MS}ms"

# Calculate percentiles
TIMES=($(grep '"event":"frame_downsized"' m2.log | grep -o '"dt_ms":[0-9.]*' | cut -d: -f2 | sort -n))
if [ ${#TIMES[@]} -gt 0 ]; then
    P50_IDX=$((${#TIMES[@]} * 50 / 100))
    P95_IDX=$((${#TIMES[@]} * 95 / 100))
    P99_IDX=$((${#TIMES[@]} * 99 / 100))
    
    echo "  P50 (median): ${TIMES[$P50_IDX]}ms"
    echo "  P95: ${TIMES[$P95_IDX]}ms"
    echo "  P99: ${TIMES[$P99_IDX]}ms"
fi

# Total downsize time
TOTAL_MS=$(grep '"event":"downsize_done"' m2.log | grep -o '"dt_ms_cum":[0-9.]*' | cut -d: -f2 | head -1)
if [ -n "$TOTAL_MS" ]; then
    echo "  Total downsize time: ${TOTAL_MS}ms"
    THROUGHPUT=$(echo "scale=2; $FRAME_COUNT * 1000 / $TOTAL_MS" | bc 2>/dev/null || echo "N/A")
    echo "  Throughput: $THROUGHPUT frames/sec"
fi

# Check summary stats from downsize_done
echo ""
echo "Summary from downsize_done event:"
SUMMARY=$(grep '"event":"downsize_done"' m2.log | head -1)
if [ -n "$SUMMARY" ]; then
    OK_FRAMES=$(echo "$SUMMARY" | grep -o '"ok_frames":[0-9]*' | cut -d: -f2)
    ERR_FRAMES=$(echo "$SUMMARY" | grep -o '"err_frames":[0-9]*' | cut -d: -f2)
    AVG_MS_SUMMARY=$(echo "$SUMMARY" | grep -o '"avg_ms":[0-9.]*' | cut -d: -f2)
    P95_MS_SUMMARY=$(echo "$SUMMARY" | grep -o '"p95_ms":[0-9.]*' | cut -d: -f2)
    
    echo "  OK frames: ${OK_FRAMES:-N/A}"
    echo "  Error frames: ${ERR_FRAMES:-0}"
    echo "  Average (from summary): ${AVG_MS_SUMMARY:-N/A}ms"
    echo "  P95 (from summary): ${P95_MS_SUMMARY:-N/A}ms"
fi

# Check for errors
echo ""
ERROR_COUNT=$(grep '"ok":false' m2.log | wc -l)
if [ "$ERROR_COUNT" -eq 0 ]; then
    echo "✅ No errors detected"
else
    echo "❌ Errors found: $ERROR_COUNT"
    # Check for specific error types
    NN_MISSING=$(grep '"err_code":"E_NN_MISSING"' m2.log | wc -l)
    NN_INFER=$(grep '"err_code":"E_NN_INFER"' m2.log | wc -l)
    
    [ "$NN_MISSING" -gt 0 ] && echo "  - Neural network model missing: $NN_MISSING"
    [ "$NN_INFER" -gt 0 ] && echo "  - Neural network inference errors: $NN_INFER"
fi

# Performance check
echo ""
echo "Performance Check:"
if [ -n "$AVG_MS" ] && [ "$AVG_MS" != "N/A" ]; then
    if (( $(echo "$AVG_MS < 40" | bc -l) )); then
        echo "✅ Performance target met (<40ms per frame)"
    else
        echo "⚠️ Performance below target (avg ${AVG_MS}ms, target <40ms)"
    fi
fi

# Summary
echo ""
echo "=== M2 Summary ==="
if [ "$FRAME_COUNT" -eq 81 ] && [ "$ERROR_COUNT" -eq 0 ]; then
    echo "✅ M2 VALIDATION: PASS"
    exit 0
else
    echo "❌ M2 VALIDATION: FAIL"
    exit 1
fi