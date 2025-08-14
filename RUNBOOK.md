# RGBA→GIF89a Camera App - RUNBOOK

## 🎯 Overview

3-Milestone workflow camera app that captures RGBA frames, applies neural network downsizing, and exports GIF89a files.

### Architecture
```
M1: Capture → CBOR → PNG (729×729, 81 frames)
M2: Downsize → Go 9×9 NN → PNG (81×81, 81 frames) 
M3: Quantize → 256 colors → GIF89a export
```

## 🚀 Quick Start

### Build & Install
```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Or use one command
./gradlew installDebug
```

### Launch App
```bash
# Start app via ADB
adb shell am start -n com.rgbagif.debug/com.rgbagif.MainActivity

# Grant camera permission if needed
adb shell pm grant com.rgbagif.debug android.permission.CAMERA
```

## 📱 UI Navigation

### Milestone Workflow Screen (Start Screen)
- Shows 3 milestone cards with progress indicators
- Each milestone shows timing after completion
- "Start" button on ready milestones
- "View Frames" button on completed milestones

### Milestone 1: CBOR Capture
1. Tap "Start" on M1 card
2. Camera captures 81 frames at 729×729
3. Saves as CBOR files in `/storage/emulated/0/Android/data/com.rgbagif.debug/files/sessions/<session_id>/cbor/`
4. Generates PNG files for viewing
5. Shows timing: capture duration in ms

### Milestone 2: Neural Downsize  
1. After M1 complete, tap "Start" on M2 card
2. Applies Go 9×9 neural network (placeholder for now)
3. Downsizes frames from 729×729 to 81×81
4. Saves downsized CBOR and PNG files
5. Shows timing: processing duration in ms

### Milestone 3: GIF Export
1. After M2 complete, tap "Start" on M3 card  
2. Quantizes frames to 256 colors
3. Generates GIF89a file
4. Output saved as `output.gif`
5. Shows timing: export duration in ms

## 🧪 Testing

### Automated Test Script
```bash
# Run all milestones and capture logs
./scripts/grab_logs.sh

# Output files:
# - full_test.log: Complete logcat output
# - m1.log: Milestone 1 logs (RGBA.CAPTURE, RGBA.CBOR, RGBA.PNG)
# - m2.log: Milestone 2 logs (RGBA.DOWNSIZE)
# - m3.log: Milestone 3 logs (RGBA.QUANT, RGBA.GIF)
```

### Manual Testing Steps

#### Test M1: Capture
```bash
# Clear logs
adb logcat -c

# Start app
adb shell am start -n com.rgbagif.debug/com.rgbagif.MainActivity

# Tap M1 Start button (coordinates for 1080x2400 screen)
adb shell input tap 900 600

# Wait for capture to complete (watch progress bar)
sleep 15

# Verify CBOR files created
adb shell ls /storage/emulated/0/Android/data/com.rgbagif.debug/files/sessions/*/cbor/ | wc -l
# Expected: 81 files

# Check logs
adb logcat -d | grep "RGBA.CAPTURE" | grep "capture_done"
```

#### Test M2: Downsize
```bash
# After M1 completes, tap M2 Start
adb shell input tap 900 900

# Wait for processing
sleep 10

# Verify downsized files
adb shell ls /storage/emulated/0/Android/data/com.rgbagif.debug/files/sessions/*/cbor_downsized/ | wc -l
# Expected: 81 files

# Check logs
adb logcat -d | grep "RGBA.DOWNSIZE" | grep "downsize_done"
```

#### Test M3: GIF Export
```bash
# After M2 completes, tap M3 Start
adb shell input tap 900 1200

# Wait for export
sleep 5

# Verify GIF created
adb shell ls /storage/emulated/0/Android/data/com.rgbagif.debug/files/sessions/*/output.gif
# Expected: File exists

# Check logs
adb logcat -d | grep "RGBA.GIF" | grep "gif_done"
```

### Expected Log Events

#### M1 Logs
```json
{"ts_ms":1234567890,"event":"session_start","milestone":"M1","session_id":"20240812_143022_456"}
{"ts_ms":1234567891,"event":"frame_captured","milestone":"M1","frame_index":0,"size_px":"729x729"}
{"ts_ms":1234567892,"event":"frame_captured","milestone":"M1","frame_index":1,"size_px":"729x729"}
...
{"ts_ms":1234567990,"event":"milestone_complete","milestone":"M1","dt_ms":8500,"frame_count":81}
```

#### M2 Logs
```json
{"ts_ms":1234568000,"event":"milestone_start","milestone":"M2","session_id":"20240812_143022_456"}
{"ts_ms":1234568001,"event":"frame_downsized","milestone":"M2","frame_index":0,"size_px":"81x81"}
...
{"ts_ms":1234568100,"event":"milestone_complete","milestone":"M2","dt_ms":3200}
```

#### M3 Logs
```json
{"ts_ms":1234568200,"event":"milestone_start","milestone":"M3","session_id":"20240812_143022_456"}
{"ts_ms":1234568201,"event":"frame_quantized","milestone":"M3","frame_index":0}
...
{"ts_ms":1234568300,"event":"gif_created","milestone":"M3","output_file":"output.gif","size_bytes":524288}
{"ts_ms":1234568301,"event":"milestone_complete","milestone":"M3","dt_ms":1100}
```

## 📂 Output Files

### Directory Structure
```
/storage/emulated/0/Android/data/com.rgbagif.debug/files/sessions/<session_id>/
├── cbor/                    # M1: Original CBOR frames (729×729)
│   ├── frame_000.cbor
│   ├── frame_001.cbor
│   └── ...
├── png_m1/                  # M1: PNG exports for viewing
│   ├── frame_000.png
│   └── ...
├── cbor_downsized/          # M2: Downsized CBOR frames (81×81)
│   ├── frame_000.cbor
│   └── ...
├── png_m2/                  # M2: Downsized PNG exports
│   ├── frame_000.png
│   └── ...
├── cbor_quantized/          # M3: Quantized CBOR frames
│   ├── frame_000.cbor
│   └── ...
└── output.gif               # M3: Final GIF89a export
```

### Pull Files for Analysis
```bash
# Get session ID from logs
SESSION_ID=$(adb logcat -d | grep "session_start" | tail -1 | grep -o '"session_id":"[^"]*"' | cut -d'"' -f4)

# Pull entire session directory
adb pull /storage/emulated/0/Android/data/com.rgbagif.debug/files/sessions/$SESSION_ID ./output/

# Pull just the GIF
adb pull /storage/emulated/0/Android/data/com.rgbagif.debug/files/sessions/$SESSION_ID/output.gif ./
```

## 🔧 Troubleshooting

### App Crashes on Start
```bash
# Check for missing permissions
adb shell pm list permissions -g | grep rgbagif

# Grant camera permission
adb shell pm grant com.rgbagif.debug android.permission.CAMERA

# Check for crash logs
adb logcat -d | grep "FATAL EXCEPTION"
```

### No Frames Captured
```bash
# Check camera is working
adb shell "pm list features | grep camera"

# Check for camera errors
adb logcat -d | grep -E "CameraX|Camera2" | grep -i error

# Verify storage permissions
adb shell ls -la /storage/emulated/0/Android/data/com.rgbagif.debug/
```

### Logs Not Appearing
```bash
# Check log level
adb shell getprop log.tag.RGBA.CAPTURE
# Should be empty or DEBUG/VERBOSE

# Set log level if needed
adb shell setprop log.tag.RGBA.CAPTURE DEBUG

# Clear and restart logcat
adb logcat -c
adb logcat | grep RGBA
```

## 🔄 CI/CD Integration

### GitHub Actions Workflow
```yaml
name: Build and Test

on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v3
    - uses: actions/setup-java@v3
      with:
        java-version: '17'
    - name: Build APK
      run: ./gradlew assembleDebug
    - name: Upload APK
      uses: actions/upload-artifact@v3
      with:
        name: app-debug
        path: app/build/outputs/apk/debug/app-debug.apk

  test:
    runs-on: macos-latest
    needs: build
    steps:
    - uses: actions/checkout@v3
    - uses: actions/download-artifact@v3
    - name: Start Emulator
      run: |
        $ANDROID_HOME/emulator/emulator -avd test -no-audio -no-window &
        adb wait-for-device
    - name: Install APK
      run: adb install app-debug/app-debug.apk
    - name: Run Tests
      run: ./scripts/grab_logs.sh
    - name: Check Results
      run: |
        M1_COUNT=$(wc -l < m1.log)
        if [ $M1_COUNT -lt 80 ]; then
          echo "M1 failed: only $M1_COUNT log entries"
          exit 1
        fi
```

## 📊 Performance Targets

| Milestone | Target Time | Frames | Resolution | Output |
|-----------|------------|--------|------------|--------|
| M1 | <10s | 81 | 729×729 | CBOR + PNG |
| M2 | <5s | 81 | 81×81 | CBOR + PNG |
| M3 | <2s | 81 | 81×81 | GIF89a |

## 🛠️ Development

### Add Timing to New Operations
```kotlin
// Use LogEvent.measure for automatic timing
val result = LogEvent.measure(
    event = "custom_operation",
    milestone = "M1",
    sessionId = sessionId,
    frameIndex = frameIdx
) {
    // Your operation here
    processFrame(bitmap)
}
```

### Add Custom Log Events
```kotlin
LogEvent.Entry(
    event = "custom_event",
    milestone = "M1",
    sessionId = sessionId,
    extra = mapOf(
        "custom_field" to value,
        "another_field" to 123
    )
).log()
```

## 📱 Device Requirements

- Android 7.0+ (API 24+)
- Camera permission
- ~100MB storage for session data
- CameraX support

## 🔗 Related Documentation

- [Architecture Overview](ARCHITECTURE.md)
- [API Documentation](API.md)  
- [Testing Guide](TESTING.md)
- [Darwin Pipeline Spec](DARWIN_PIPELINE_SPEC.md)