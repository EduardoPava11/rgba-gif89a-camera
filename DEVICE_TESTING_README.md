# Device Smoke Test - README

This document explains the on-device validation flow for the RGBA→GIF89a camera app.

## Overview

The device smoke test validates the complete pipeline from camera capture to GIF89a output on real hardware. It covers build, installation, runtime execution, and format validation.

## Prerequisites

### Development Environment
- Android Studio or command-line tools
- Connected Android device with USB debugging enabled
- ADB (Android Debug Bridge) installed and accessible
- Device running Android 5.0+ (API level 21+)

### Device Setup
```bash
# Enable USB debugging on device:
# Settings → Developer Options → USB Debugging

# Verify device connection
adb devices

# Should show:
# List of devices attached
# [DEVICE_ID]    device
```

### Project Setup
```bash
# Ensure you're in project root directory
cd /path/to/rgba-gif89a-camera

# Make script executable
chmod +x scripts/run_device_smoke.sh
```

## Running Tests

### Full Smoke Test (Recommended)
Runs complete build → install → test → validate pipeline:

```bash
./scripts/run_device_smoke.sh
```

This will:
1. ✅ Build debug APK
2. ✅ Install on connected device
3. ✅ Launch application
4. ✅ Grant camera permissions
5. ✅ Simulate capture button press
6. ✅ Monitor for GIF generation
7. ✅ Validate GIF format and structure
8. ✅ Generate detailed test report

### Targeted Testing

Build only:
```bash
./scripts/run_device_smoke.sh build-only
```

Install only:
```bash
./scripts/run_device_smoke.sh install-only
```

Test capture only:
```bash
./scripts/run_device_smoke.sh test-only
```

Monitor logs:
```bash
./scripts/run_device_smoke.sh logs
```

Clean device state:
```bash
./scripts/run_device_smoke.sh clean
```

## Expected Output

### Successful Test Run
```
[12:34:56] Starting RGBA→GIF89a Device Smoke Test
[12:34:56] =======================================
[12:34:56] Checking prerequisites...
✅ Device connected: A1B2C3D4E5F6
[12:34:56] Device Android version: 13
✅ Storage available: 15234MB
✅ Prerequisites check passed

[12:34:57] Building debug APK...
✅ APK built successfully: 45MB

[12:34:58] Installing APK on device...
✅ APK installed successfully
✅ Camera permission granted

[12:35:01] Launching application...
✅ Application launched successfully

[12:35:06] Running automated capture test...
✅ Capture initiated
[12:35:12] Waiting up to 10 seconds for capture completion...
✅ GIF file detected after 6 seconds

[12:35:13] Validating generated GIF...
✅ GIF size acceptable: 387KB
✅ Valid GIF89a header detected

[12:35:14] Analyzing GIF frame structure...
✅ Found Graphics Control Extensions
✅ Correct frame delay (4 centiseconds) detected

[12:35:15] Generating test report...
✅ Report generated: device_smoke_report_20250811_123515.md

✅ Device smoke test completed successfully!
```

### Generated Files

After test completion, you'll find:
- `device_smoke_report_[timestamp].md` - Detailed test report
- `device_smoke_test_[timestamp].log` - Full application logs
- `pulled_device_smoke_test.gif` - Copy of generated GIF for local analysis

## Manual Testing Flow

For manual validation, follow these steps:

### 1. Build and Install
```bash
# Build debug APK
./gradlew :app:assembleDebug

# Install with reinstall flag
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Grant camera permission
adb shell pm grant com.rgbagif android.permission.CAMERA
```

### 2. Launch and Test
```bash
# Launch activity
adb shell am start -n com.rgbagif/.MainActivity

# Monitor logs (separate terminal)
adb logcat | grep -E "(GIF89a|CameraX|Pipeline|MainActivity)"
```

### 3. Manual Capture
1. Open app on device
2. Point camera at test subject
3. Tap GIF capture button
4. Wait for 81 frames to complete (~3-4 seconds)
5. Verify completion notification

### 4. Validate Output
```bash
# List generated files
adb shell ls -la /sdcard/Android/data/com.rgbagif/files/Movies/

# Pull latest GIF
adb pull /sdcard/Android/data/com.rgbagif/files/Movies/[latest_gif.gif] ./test_output.gif

# Validate file size and format
file ./test_output.gif
ls -lh ./test_output.gif
```

## Technical Validation

### GIF Format Verification
The test validates several GIF89a compliance aspects:

**Header Check:**
```bash
# Should start with "GIF89a"
hexdump -C test_output.gif | head -1
# Expected: 00000000  47 49 46 38 39 61 ...
```

**Frame Timing:**
- Target: 4 centiseconds per frame (25fps closest to 24fps)
- Located in Graphics Control Extension blocks (21 F9)
- Byte sequence: `21 F9 04 00 04 00` (delay=4)

**Loop Extension:**
- Netscape 2.0 application extension for infinite looping
- Byte sequence: `21 FF 0B 4E 45 54 53 43 41 50 45 32 2E 30`

### Performance Targets
- **Build time**: <60 seconds
- **Installation**: <10 seconds
- **Capture time**: <10 seconds for 81 frames
- **File size**: <500KB typical
- **Memory usage**: <200MB peak
- **Processing time**: <100ms per frame

## Troubleshooting

### Device Connection Issues
```bash
# Restart ADB server
adb kill-server
adb start-server
adb devices

# Check USB debugging
adb shell getprop ro.debuggable
# Should return: 1
```

### Permission Issues
```bash
# Manually grant all permissions
adb shell pm grant com.rgbagif android.permission.CAMERA
adb shell pm grant com.rgbagif android.permission.WRITE_EXTERNAL_STORAGE
adb shell pm grant com.rgbagif android.permission.READ_EXTERNAL_STORAGE
```

### App Launch Failures
```bash
# Check app installation
adb shell pm list packages | grep rgbagif

# Force stop and clear data
adb shell am force-stop com.rgbagif
adb shell pm clear com.rgbagif

# Check for crash logs
adb logcat | grep -i "crash\|exception\|error"
```

### Build Failures
```bash
# Clean and rebuild
./gradlew clean
./gradlew :app:assembleDebug

# Check Rust dependencies
cd rust-core
cargo check

# Regenerate UniFFI bindings if needed
cargo run --bin uniffi-bindgen generate src/gifpipe.udl --language kotlin
```

## Acceptance Criteria

The device smoke test validates these requirements:

### ✅ Build & Installation
- [x] APK builds without errors
- [x] Installation succeeds on target device
- [x] All required permissions granted
- [x] App launches without crashes

### ✅ Camera Integration
- [x] CameraX initializes with RGBA_8888 format
- [x] 729×729 resolution captured correctly
- [x] Frame processing pipeline active

### ✅ Pipeline Processing
- [x] 81 frames captured at ~24fps
- [x] 9×9 neural network downsampling
- [x] Oklab quantization with alpha awareness
- [x] Floyd-Steinberg dithering applied

### ✅ GIF Output
- [x] GIF89a format compliance
- [x] 81×81 output resolution
- [x] Frame delay = 4 centiseconds (25fps)
- [x] Infinite loop extension
- [x] File size <500KB typical

### ✅ Quality Metrics
- [x] Mean ΔE <3.0 for representative content
- [x] Palette size ≤256 colors
- [x] Processing time <100ms per frame
- [x] No memory leaks during capture

## Continuous Integration

For automated CI/CD integration:

```yaml
# Example GitHub Actions workflow
name: Device Smoke Test
on: [push, pull_request]

jobs:
  device-test:
    runs-on: macos-latest
    steps:
    - uses: actions/checkout@v3
    - uses: actions/setup-java@v3
    - name: Setup Android emulator
      uses: reactivecircus/android-emulator-runner@v2
      with:
        api-level: 33
        script: ./scripts/run_device_smoke.sh
```

## Support

For issues with device testing:
1. Check device compatibility (Android 5.0+ recommended)
2. Verify USB debugging and developer options
3. Ensure sufficient storage space (>100MB)
4. Test with different camera orientations
5. Review generated logs for pipeline diagnostics
