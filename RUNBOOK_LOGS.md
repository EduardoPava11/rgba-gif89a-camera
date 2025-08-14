# RGBA→GIF89a Camera App - Logging Runbooks

## Prerequisites

```bash
# Ensure device is connected
adb devices

# Clear previous logs
adb logcat -c

# Set log buffer size for testing
adb logcat -G 16M
```

## M1: Capture → CBOR → PNG (729×729)

### Test Sequence

```bash
# 1. Launch app and grant permissions
adb shell am start -n com.rgbagif.debug/com.rgbagif.MainActivity
adb shell pm grant com.rgbagif.debug android.permission.CAMERA

# 2. Start log collection
adb logcat -v time | grep -E "RGBA\.(CAPTURE|CBOR|PNG|ERROR|PERF)" > m1_capture.log &
LOG_PID=$!

# 3. Trigger capture (tap START button)
adb shell input tap 540 2148

# 4. Wait for 81 frames (approximately 8-10 seconds at 10fps)
sleep 12

# 5. Stop capture (tap STOP)
adb shell input tap 540 2148

# 6. Collect logs
sleep 2
kill $LOG_PID

# 7. Parse and validate M1 logs
grep '"event":"capture_start"' m1_capture.log
grep '"event":"frame_saved"' m1_capture.log | wc -l  # Should be 81
grep '"event":"capture_done"' m1_capture.log
grep '"event":"png_generated"' m1_capture.log | wc -l  # Should be 81
```

### Expected Log Output (M1)

```json
{"ts_ms":1712345678901,"event":"capture_start","milestone":"M1","session_id":"20241211_143022_123","ok":true,"extra":{"target_frames":81,"size_px":"729x729"}}
{"ts_ms":1712345679012,"event":"frame_saved","milestone":"M1","session_id":"20241211_143022_123","frame_index":0,"size_px":"729x729","bytes_out":2125764,"dt_ms":23.4,"ok":true}
{"ts_ms":1712345687234,"event":"capture_done","milestone":"M1","session_id":"20241211_143022_123","dt_ms_cum":8333.0,"ok":true,"extra":{"frame_count":81}}
{"ts_ms":1712345687456,"event":"png_generated","milestone":"M1","session_id":"20241211_143022_123","frame_index":0,"bytes_in":2125764,"bytes_out":1892345,"dt_ms":18.2,"ok":true}
```

### Validation Script

```bash
#!/bin/bash
# m1_validate.sh

SESSION_ID=$(grep '"event":"capture_start"' m1_capture.log | head -1 | sed 's/.*"session_id":"\([^"]*\)".*/\1/')
echo "Session ID: $SESSION_ID"

FRAME_COUNT=$(grep '"event":"frame_saved"' m1_capture.log | grep "\"session_id\":\"$SESSION_ID\"" | wc -l)
echo "Frames captured: $FRAME_COUNT"

if [ "$FRAME_COUNT" -eq 81 ]; then
    echo "✅ M1 Capture: PASS (81 frames)"
else
    echo "❌ M1 Capture: FAIL (expected 81, got $FRAME_COUNT)"
fi

# Check CBOR sizes (should be 729*729*4 = 2125764 bytes)
CBOR_SIZES=$(grep '"event":"frame_saved"' m1_capture.log | grep -o '"bytes_out":[0-9]*' | cut -d: -f2)
for size in $CBOR_SIZES; do
    if [ "$size" -ne 2125764 ]; then
        echo "⚠️ Unexpected CBOR size: $size bytes"
    fi
done

# Check timing
AVG_MS=$(grep '"event":"frame_saved"' m1_capture.log | grep -o '"dt_ms":[0-9.]*' | cut -d: -f2 | awk '{sum+=$1} END {print sum/NR}')
echo "Average frame save time: ${AVG_MS}ms"
```

## M2: Browse → Downsize with Go 9×9 NN → PNG (81×81)

### Test Sequence

```bash
# 1. Navigate to Frame Browser
adb shell input tap 965 178  # Tap "View Frames" FAB

# 2. Start log collection for M2
adb logcat -c
adb logcat -v time | grep -E "RGBA\.(DOWNSIZE|PNG|ERROR|PERF)" > m2_downsize.log &
LOG_PID=$!

# 3. Trigger downsize (tap Downsize All button)
adb shell input tap 960 100  # Top-right action button

# 4. Wait for processing (81 frames × ~40ms = ~3.2s)
sleep 5

# 5. Stop log collection
kill $LOG_PID

# 6. Validate M2 logs
grep '"event":"downsize_start"' m2_downsize.log
grep '"event":"frame_downsized"' m2_downsize.log | wc -l  # Should be 81
grep '"event":"downsize_done"' m2_downsize.log
```

### Expected Log Output (M2)

```json
{"ts_ms":1712345700123,"event":"downsize_start","milestone":"M2","session_id":"20241211_143022_123","ok":true,"extra":{"frame_count":81,"target_size":"81x81"}}
{"ts_ms":1712345700156,"event":"frame_downsized","milestone":"M2","session_id":"20241211_143022_123","frame_index":0,"size_px":"81x81","bytes_in":2125764,"bytes_out":26244,"dt_ms":32.1,"ok":true}
{"ts_ms":1712345703234,"event":"downsize_done","milestone":"M2","session_id":"20241211_143022_123","dt_ms_cum":3111.0,"ok":true,"extra":{"ok_frames":81,"err_frames":0,"avg_ms":38.4,"p95_ms":45.2}}
```

## M3: Quantize → GIF89a Export

### Test Sequence

```bash
# 1. Start log collection for M3
adb logcat -c
adb logcat -v time | grep -E "RGBA\.(QUANT|GIF|ERROR)" > m3_gif.log &
LOG_PID=$!

# 2. Trigger quantization and GIF export
# (Implementation pending - button/menu to be added)
adb shell am broadcast -a com.rgbagif.EXPORT_GIF

# 3. Wait for processing
sleep 10

# 4. Stop log collection
kill $LOG_PID

# 5. Validate M3 logs
grep '"event":"quant_start"' m3_gif.log
grep '"event":"frame_quantized"' m3_gif.log | wc -l  # Should be 81
grep '"event":"gif_done"' m3_gif.log
```

### Expected Log Output (M3)

```json
{"ts_ms":1712345720456,"event":"quant_start","milestone":"M3","session_id":"20241211_143022_123","ok":true,"extra":{"frame_count":81,"target_palette":256}}
{"ts_ms":1712345720489,"event":"frame_quantized","milestone":"M3","session_id":"20241211_143022_123","frame_index":0,"dt_ms":28.3,"ok":true,"extra":{"palette":243}}
{"ts_ms":1712345724567,"event":"gif_done","milestone":"M3","session_id":"20241211_143022_123","bytes_out":1456789,"dt_ms":4111.0,"ok":true,"extra":{"frame_count":81,"delay_cs":4,"output_path":"/sdcard/Android/data/com.rgbagif.debug/files/output.gif"}}
```

## Perfetto Trace Collection

```bash
#!/bin/bash
# perfetto_trace.sh

# Start trace
adb shell perfetto \
  -c - --txt \
  -o /data/misc/perfetto-traces/rgba_gif.perfetto-trace <<EOF
buffers: {
    size_kb: 63488
    fill_policy: DISCARD
}
buffers: {
    size_kb: 2048
    fill_policy: DISCARD
}
data_sources: {
    config {
        name: "linux.process_stats"
    }
}
data_sources: {
    config {
        name: "android.surfaceflinger.frametimeline"
    }
}
data_sources: {
    config {
        name: "android.log"
        android_log_config {
            log_ids: MAIN
            log_ids: SYSTEM
            log_ids: CRASH
            filter_tags: "RGBA.*"
        }
    }
}
duration_ms: 30000
EOF

# Run app test sequence
./run_milestones.sh

# Pull trace
adb pull /data/misc/perfetto-traces/rgba_gif.perfetto-trace
echo "Open trace at: https://ui.perfetto.dev"
```

## Combined Test Script

```bash
#!/bin/bash
# scripts/grab_logs.sh

set -e

echo "=== RGBA→GIF89a Milestone Testing ==="
echo "Starting at $(date)"

# Clear and setup
adb logcat -c
adb shell am force-stop com.rgbagif.debug

# Start unified log collection
adb logcat -v time > full_test.log &
FULL_LOG_PID=$!

# Launch app
adb shell am start -n com.rgbagif.debug/com.rgbagif.MainActivity
sleep 3

# M1: Capture
echo "📸 M1: Starting capture..."
adb shell input tap 540 2148
sleep 10
adb shell input tap 540 2148
echo "✅ M1: Capture complete"

# M2: Browse and Downsize
echo "🔍 M2: Browsing and downsizing..."
adb shell input tap 965 178  # Navigate to browser
sleep 2
adb shell input tap 960 100  # Downsize all
sleep 5
echo "✅ M2: Downsize complete"

# M3: GIF Export (when implemented)
echo "🎞️ M3: Exporting GIF..."
# adb shell am broadcast -a com.rgbagif.EXPORT_GIF
echo "⏳ M3: Not yet implemented"

# Stop logging
kill $FULL_LOG_PID

# Split logs by milestone
grep -E "RGBA\.(CAPTURE|CBOR|PNG|ERROR)" full_test.log > m1.log
grep -E "RGBA\.(DOWNSIZE|ERROR)" full_test.log > m2.log
grep -E "RGBA\.(QUANT|GIF|ERROR)" full_test.log > m3.log

# Run validators
echo ""
echo "=== Validation Results ==="
./m1_validate.sh
./m2_validate.sh
# ./m3_validate.sh

echo ""
echo "Logs saved to: m1.log, m2.log, m3.log"
echo "Full log: full_test.log"
```

## Error Monitoring

```bash
# Monitor errors in real-time
adb logcat -v time | grep -E '"ok":false|"err_code"' --color=always

# Count errors by type
grep '"err_code"' full_test.log | grep -o '"err_code":"[^"]*"' | sort | uniq -c

# Extract error summaries
jq -r 'select(.ok == false) | "\(.event) - \(.err_code): \(.err_msg)"' < <(grep -E '"ok":false' full_test.log)
```

## CI Integration

```yaml
# .github/workflows/device_test.yml
name: Device Tests

on: [push, pull_request]

jobs:
  milestone-tests:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Setup Android SDK
        uses: android-actions/setup-android@v2
        
      - name: Start emulator
        run: |
          $ANDROID_HOME/emulator/emulator -avd test_device -no-audio -no-window &
          adb wait-for-device
          
      - name: Build and install
        run: |
          ./gradlew assembleDebug
          adb install -r app/build/outputs/apk/debug/app-debug.apk
          
      - name: Run milestone tests
        run: |
          chmod +x scripts/grab_logs.sh
          scripts/grab_logs.sh
          
      - name: Upload logs
        uses: actions/upload-artifact@v3
        with:
          name: test-logs
          path: |
            m1.log
            m2.log
            m3.log
            *.perfetto-trace
```