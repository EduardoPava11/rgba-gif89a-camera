# RGBA GIF89a Camera - Pipeline Verification

## Overview

This document describes the canonical logging events and verification process for the M1→M2→M3 pipeline.

## Architecture

```
M1 (RGBA Capture) → M2 (Neural Downsize) → M3 (GIF Export)
     729×729              81×81                GIF89a
   RGBA_8888 only       9×9 blocks            81 frames
```

## Canonical Log Events

All log events follow a structured single-line format for easy parsing and verification.

### M1: RGBA Capture Events

```
CAMERA_INIT { format: "RGBA_8888", resolution: "729x729" }
M1_START { sessionId: "abc123" }
M1_FRAME_SAVED { idx: 0, width: 729, height: 729, bytes: 2125764, path: "/path/to/frame_000.cbor" }
M1_DONE { totalFrames: 81, elapsedMs: 12345 }
```

### M2: Neural Downsize Events

```
M2_START { sessionId: "abc123" }
M2_FRAME_DONE { idx: 0, inW: 729, inH: 729, outW: 81, outH: 81, elapsedMs: 45, path: "/path/to/frame_000.png" }
M2_MOSAIC_DONE { path: "/path/to/mosaic.png", grid: "9x9" }
M2_DONE { totalFrames: 81, elapsedMs: 3650 }
```

### M3: GIF Export Events

```
M3_START { sessionId: "abc123" }
M3_GIF_DONE { frames: 81, fps: 10, sizeBytes: 524288, loop: true, path: "/path/to/output.gif" }
```

### Rust Module Events

```
M1_RUST_INIT { version: "1.0.0-jni" }
M1_RUST_WRITE_CBOR { idx: 0, bytes: 2125764, outPath: "/path/to/frame.cbor" }
M2_RUST_DOWNSIZE_BEGIN { idx: 0 }
M2_RUST_DOWNSIZE_END { idx: 0, elapsedMs: 42 }
```

### Error Events

```
PIPELINE_ERROR { stage: "M1", message: "Frame capture failed", stack: "..." }
FRAME_DROPPED { reason: "Buffer full" }
```

### Performance Events

```
MEMORY_SNAPSHOT { availableMb: 512, totalMb: 2048, usedMb: 1536 }
```

## Verification Process

### 1. Automated Verification

Run the verification script:

```bash
./verify_pipeline.sh
```

This script will:
- Launch the app
- Monitor for canonical log events
- Extract performance metrics
- Check for errors
- Generate a summary report

### 2. Manual Verification via Logcat

#### View all pipeline events:
```bash
adb logcat | grep -E 'M1_|M2_|M3_|CAMERA_|PIPELINE_|MEMORY_'
```

#### Filter by milestone:
```bash
adb logcat | grep M1_    # M1 events only
adb logcat | grep M2_    # M2 events only  
adb logcat | grep M3_    # M3 events only
```

#### Monitor in real-time with formatting:
```bash
adb logcat -v time | grep -E --color=always 'M[123]_|PIPELINE_ERROR'
```

#### Save logs to file:
```bash
adb logcat -d > pipeline_logs.txt
grep -E 'M1_|M2_|M3_' pipeline_logs.txt > canonical_events.txt
```

### 3. Performance Analysis

#### Extract timing metrics:
```bash
# M1 frame times
adb logcat -d | grep M1_FRAME_SAVED | awk '{print $NF}' | grep -oE '[0-9]+' | awk '{sum+=$1; count++} END {print "Avg:", sum/count, "ms"}'

# M2 processing times
adb logcat -d | grep M2_FRAME_DONE | grep -oE 'elapsedMs: [0-9]+' | grep -oE '[0-9]+' | awk '{sum+=$1; count++} END {print "Avg:", sum/count, "ms"}'
```

#### Memory usage over time:
```bash
adb logcat -d | grep MEMORY_SNAPSHOT | tail -10
```

## Expected Values

### Timing Targets
- **M1 Capture**: < 20ms per frame
- **M2 Downsize**: < 50ms per frame (baseline), < 100ms (neural)
- **M3 Export**: < 5 seconds total

### Data Sizes
- **CBOR Frame**: ~2.1 MB (729×729×4 RGBA)
- **PNG Frame**: ~26 KB (81×81 compressed)
- **Final GIF**: < 2 MB (81 frames)

### Quality Metrics (M2)
- **SSIM**: > 0.85
- **PSNR**: > 28 dB
- **Edge Preservation**: > 0.90

## Troubleshooting

### No log events appearing

1. Check Timber initialization:
```bash
adb logcat | grep "Timber"
```

2. Verify app has WRITE_EXTERNAL_STORAGE permission:
```bash
adb shell pm list permissions -g | grep rgbagif
```

### M1 frames not saving

Check CBOR write errors:
```bash
adb logcat | grep -E "M1_RUST_ERROR|DirectByteBuffer"
```

### M2 processing slow

Check if neural network loaded:
```bash
adb logcat | grep "M2.*neural"
```

### Memory issues

Monitor memory pressure:
```bash
adb shell dumpsys meminfo com.rgbagif
```

## Build Configuration

Ensure these are set in your build:

### app/build.gradle.kts
```kotlin
dependencies {
    implementation("com.jakewharton.timber:timber:5.0.1")
}
```

### Rust Cargo.toml
```toml
[dependencies]
android_logger = "0.13"
log = "0.4"
```

### AndroidManifest.xml
```xml
<application
    android:name=".RgbaGifApp"
    ...>
```

## Success Criteria

✅ All canonical log events appear in order
✅ No PIPELINE_ERROR events
✅ Processing completes within timing targets
✅ Output GIF is created successfully
✅ Memory usage remains stable

## Next Steps

After verification passes:
1. Run performance profiling with Android Studio
2. Analyze frame timing distribution
3. Test with different lighting conditions
4. Validate GIF quality metrics