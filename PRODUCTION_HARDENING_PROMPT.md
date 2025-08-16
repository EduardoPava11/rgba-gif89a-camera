# Production Hardening Prompt - RGBA GIF89a Camera (81×81×81 Cube Vision)

## Role & Mission
You are a senior cross-platform engineer specializing in Rust + Kotlin + UniFFI. Your mission is to harden the RGBA GIF89a camera app for production by implementing structured logging, stabilizing the UniFFI surface, adding test infrastructure, and ensuring reliable Android runtime behavior. The app's core vision is **"form follows function"** - creating an 81-frame, 81×81 pixel GIF89a that visualizes as a rotating cube at 24fps.

## Current State Analysis
Based on codebase inspection:
- ✅ **Modular crate structure** exists (m1-neural, m2-quant, m3-gif, common-types, ffi)
- ✅ **CubeVisualizationScreen.kt** already implements cube preview with Compose
- ⚠️ **Partial tracing** in crates/ffi/src/lib.rs but still uses android_logger elsewhere
- ❌ **No WorkManager/ForegroundService** implementations found
- ❌ **No golden test fixtures** in fixtures/golden/
- ❌ **Missing E_M1_* error codes** in error taxonomy

## 0. Preconditions & Setup

```bash
# Verify you're in the project root
test -f ARCHITECTURE.md && test -d rust-core && test -d app || exit 1

# Create feature branch
git checkout -b feat/prod-hardening-81cube

# Check current state
echo "=== Current Implementation Gaps ==="
grep -r "android_logger" rust-core/ | wc -l  # Should be > 0 (needs replacement)
ls fixtures/golden/ 2>/dev/null || echo "No golden fixtures"
find app/src/main -name "*Worker.kt" -o -name "*Service.kt" | wc -l  # Should be 0
```

## 1. Complete Tracing Migration to Structured Logging

### Goal
Replace all `android_logger` and `log::*` calls with structured `tracing` + `tracing-logcat` emitting JSON to Android logcat with session correlation.

### Implementation

#### 1.1 Update Dependencies
```toml
# rust-core/crates/ffi/Cargo.toml
[dependencies]
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["fmt", "json", "env-filter"] }
tracing-logcat = "0.2"  # Latest version with JSON support
uuid = { version = "1.0", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
serde_json = "1.0"

# Remove android_logger from all Cargo.toml files
```

#### 1.2 Replace Logging Initialization
```rust
// rust-core/crates/ffi/src/lib.rs
use tracing_logcat::LogcatMakeWriter;
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt, EnvFilter};
use std::sync::Once;

static INIT: Once = Once::new();

#[uniffi::export]
pub fn init_android_tracing() -> String {
    let session_id = uuid::Uuid::new_v4();
    
    INIT.call_once(|| {
        // Hierarchical logging: INFO for summaries, DEBUG for per-frame
        let filter = EnvFilter::try_from_default_env()
            .unwrap_or_else(|_| EnvFilter::new("info,gifpipe=debug"));
        
        #[cfg(target_os = "android")]
        {
            let logcat = LogcatMakeWriter::new("GifPipe81")
                .with_max_level(tracing::Level::TRACE)
                .build();
            
            tracing_subscriber::registry()
                .with(tracing_subscriber::fmt::layer()
                    .with_writer(logcat)
                    .json()  // Structured JSON
                    .flatten_event(true)  // Flat structure for analytics
                    .with_current_span(true)
                    .with_span_list(true))
                .with(filter)
                .init();
        }
        
        // Panic hook for E_PANIC error code
        std::panic::set_hook(Box::new(|info| {
            tracing::error!(
                code = "E_PANIC",
                panic_info = ?info,
                location = info.location().map(|l| format!("{}:{}:{}", l.file(), l.line(), l.column())),
                "Rust panic occurred"
            );
        }));
    });
    
    tracing::info!(
        session_id = %session_id,
        version = env!("CARGO_PKG_VERSION"),
        event = "session_start",
        "GIF pipeline session initialized"
    );
    
    session_id.to_string()
}
```

#### 1.3 Add Session Correlation to Pipeline
```rust
// rust-core/crates/ffi/src/lib.rs

#[uniffi::export]
pub fn strategy_b_neural_pipeline(
    rgba_frames_729: Vec<Vec<u8>>,
    fps: u8,
    loop_forever: bool,
    output_gif_path: String,
    session_id: String,  // Pass from Kotlin
) -> Result<QuantResult, GifPipeError> {
    let _root = tracing::info_span!(
        "gif_capture",
        session_id = %session_id,
        frames = rgba_frames_729.len(),
        input_res = "729x729",
        output_res = "81x81",
        fps = fps,
        loop_mode = loop_forever
    ).entered();
    
    // Log every 10 frames to avoid spam
    for (idx, frame) in rgba_frames_729.iter().enumerate() {
        if idx % 10 == 0 {
            tracing::info!(
                frame_idx = idx,
                frame_size = frame.len(),
                progress = format!("{}/{}", idx, rgba_frames_729.len()),
                "Processing frame batch"
            );
        }
        
        // Per-frame details at DEBUG level
        tracing::debug!(
            frame_idx = idx,
            frame_bytes = frame.len(),
            "Processing individual frame"
        );
    }
    
    // M1: Neural downsampling
    let downsized = {
        let _m1_span = tracing::info_span!("m1_neural", session_id = %session_id).entered();
        let start = std::time::Instant::now();
        
        let result = downsample_neural_729_to_81(&rgba_frames_729)?;
        
        tracing::info!(
            duration_ms = start.elapsed().as_millis() as u64,
            output_frames = result.len(),
            attention_mean = calculate_attention_mean(&result),
            "M1 neural downsampling complete"
        );
        
        result
    };
    
    // Similar for M2 and M3...
}
```

### Acceptance Criteria
```bash
# Build and install
cd rust-core && cargo build --release -p gifpipe-ffi
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk

# Verify JSON logs
adb logcat -c
# Run app, capture frames
adb logcat -s GifPipe81:* | grep session_id | head -5
# Should see JSON with session_id, timestamps, spans
```

## 2. Complete Error Taxonomy & UniFFI Stabilization

### Goal
Add missing error codes, stabilize the production API, and properly gate debug functions.

### Implementation

#### 2.1 Complete Error Codes
```rust
// rust-core/crates/common-types/src/lib.rs

#[derive(uniffi::Error, thiserror::Error, Debug)]
pub enum GifPipeError {
    #[error("E_M1_INPUT: Invalid RGBA frame at index {index}: {message}")]
    InvalidFrameData { index: u32, message: String },
    
    #[error("E_M1_NEURAL: Neural downsampling failed at frame {frame}: {message}")]
    NeuralProcessingFailed { frame: u32, message: String },
    
    #[error("E_M2_PALETTE: Quantization failed after {iterations} iterations: {message}")]
    QuantizationFailed { iterations: u32, message: String },
    
    #[error("E_M3_GIFWRITE: GIF creation failed: {message}")]
    GifCreationFailed { message: String },
    
    #[error("E_VALIDATION: {message}")]
    ValidationFailed { message: String },
    
    #[error("E_PANIC: Rust panic: {message}")]
    Panic { message: String },
}

impl GifPipeError {
    pub fn code(&self) -> &'static str {
        match self {
            Self::InvalidFrameData { .. } => "E_M1_INPUT",
            Self::NeuralProcessingFailed { .. } => "E_M1_NEURAL",
            Self::QuantizationFailed { .. } => "E_M2_PALETTE",
            Self::GifCreationFailed { .. } => "E_M3_GIFWRITE",
            Self::ValidationFailed { .. } => "E_VALIDATION",
            Self::Panic { .. } => "E_PANIC",
        }
    }
}
```

#### 2.2 Implement GIF Validation
```rust
// rust-core/crates/ffi/src/lib.rs

#[uniffi::export]
pub fn validate_gif_bytes(gif_data: Vec<u8>) -> Result<GifValidation, GifPipeError> {
    let validation = GifValidation {
        has_gif89a_header: gif_data.starts_with(b"GIF89a"),
        has_netscape_loop: find_netscape_extension(&gif_data),
        frame_count: count_frame_separators(&gif_data),
        has_trailer: gif_data.ends_with(&[0x3B]),
        size_bytes: gif_data.len() as u64,
    };
    
    if !validation.has_gif89a_header {
        return Err(GifPipeError::ValidationFailed {
            message: "Missing GIF89a header".to_string()
        });
    }
    
    if validation.frame_count != 81 {
        tracing::warn!(
            expected = 81,
            actual = validation.frame_count,
            "Frame count mismatch for 81-cube"
        );
    }
    
    Ok(validation)
}

fn find_netscape_extension(data: &[u8]) -> bool {
    // Look for NETSCAPE2.0 application extension
    let netscape = b"NETSCAPE2.0";
    data.windows(netscape.len()).any(|w| w == netscape)
}

fn count_frame_separators(data: &[u8]) -> u32 {
    data.iter().filter(|&&b| b == 0x2C).count() as u32
}
```

#### 2.3 Feature-Gate Debug APIs
```rust
// rust-core/crates/ffi/Cargo.toml
[features]
default = []
debug-api = []

// rust-core/crates/ffi/src/lib.rs
#[cfg(feature = "debug-api")]
#[uniffi::export]
pub fn debug_m1_downsample(
    frame_729: Vec<u8>
) -> Result<Vec<u8>, GifPipeError> {
    tracing::debug!("Debug M1 downsample called");
    // Implementation...
}

#[cfg(feature = "debug-api")]
#[uniffi::export]
pub fn debug_m2_palette(
    frames_81: Vec<Vec<u8>>
) -> Result<Vec<u8>, GifPipeError> {
    tracing::debug!("Debug M2 palette extraction called");
    // Implementation...
}
```

## 3. Golden Tests & Property Invariants

### Goal
Add regression tests with known-good fixtures and property-based invariant testing.

### Implementation

#### 3.1 Set Up Git LFS and Fixtures
```bash
# Initialize Git LFS
git lfs install
git lfs track "fixtures/golden/*.gif"
git lfs track "fixtures/golden/*.cbor"

# Create fixture directories
mkdir -p fixtures/golden fixtures/test_data

# Create test data generator
cat > rust-core/gifpipe-cli/src/bin/generate_fixtures.rs << 'EOF'
use std::fs;
use std::path::Path;

fn main() {
    // Generate 81 test frames at 729x729
    let mut frames = Vec::new();
    for i in 0..81 {
        let mut frame = vec![0u8; 729 * 729 * 4];
        // Create gradient pattern
        for y in 0..729 {
            for x in 0..729 {
                let idx = (y * 729 + x) * 4;
                frame[idx] = ((x + i * 3) % 256) as u8;     // R
                frame[idx + 1] = ((y + i * 2) % 256) as u8; // G
                frame[idx + 2] = ((i * 5) % 256) as u8;     // B
                frame[idx + 3] = 255;                        // A
            }
        }
        frames.push(frame);
    }
    
    // Save as CBOR
    let cbor_data = serde_cbor::to_vec(&frames).unwrap();
    fs::write("fixtures/golden/input_81_frames.cbor", cbor_data).unwrap();
    
    println!("Generated test fixtures");
}
EOF
```

#### 3.2 Golden Tests
```rust
// rust-core/crates/ffi/tests/golden_tests.rs

#[test]
fn test_golden_gif_output() {
    let input_frames = load_cbor_fixture("fixtures/golden/input_81_frames.cbor");
    let expected_gif = std::fs::read("fixtures/golden/expected_output.gif").unwrap();
    let expected_metrics: Metrics = serde_json::from_str(
        &std::fs::read_to_string("fixtures/golden/metrics.json").unwrap()
    ).unwrap();
    
    // Process through pipeline
    let result = strategy_b_neural_pipeline(
        input_frames,
        24,  // fps
        true, // loop
        "test_output.gif".to_string(),
        "test-session".to_string()
    ).unwrap();
    
    // Validate structure
    let gif_data = std::fs::read("test_output.gif").unwrap();
    assert!(gif_data.starts_with(b"GIF89a"), "Missing GIF89a header");
    assert!(gif_data.ends_with(&[0x3B]), "Missing trailer");
    
    // Validate metrics within tolerance
    assert!((result.mean_perceptual_error - expected_metrics.mean_delta_e).abs() < 0.5);
    assert!((result.p95_perceptual_error - expected_metrics.p95_delta_e).abs() < 1.0);
    assert_eq!(result.frame_count, 81, "Must have exactly 81 frames for cube");
}
```

#### 3.3 Property Invariants
```rust
// rust-core/crates/ffi/Cargo.toml
[dev-dependencies]
proptest = "1.0"

// rust-core/crates/ffi/tests/invariants.rs
use proptest::prelude::*;

proptest! {
    #[test]
    fn palette_size_invariant(
        frames in prop::collection::vec(frame_generator(), 81..=81)
    ) {
        let result = process_frames(frames);
        prop_assert!(result.palette_rgb.len() <= 256 * 3, "Palette exceeds 256 colors");
    }
    
    #[test]
    fn frame_dimensions_invariant(
        frames in valid_frames_729()
    ) {
        let result = downsample_neural_729_to_81(frames);
        for frame in result {
            prop_assert_eq!(frame.len(), 81 * 81 * 4, "Frame must be exactly 81x81");
        }
    }
    
    #[test]
    fn gif_structure_invariant(
        frames in prop::collection::vec(frame_generator(), 81..=81)
    ) {
        let gif = encode_gif(frames);
        prop_assert!(gif.starts_with(b"GIF89a"));
        prop_assert!(gif.ends_with(&[0x3B]));
        prop_assert_eq!(count_frames(&gif), 81);
    }
    
    #[test]
    fn error_improvement_invariant(
        frames in valid_frames_81()
    ) {
        let palette1 = build_palette(&frames, 10);   // 10 iterations
        let palette2 = build_palette(&frames, 100);  // 100 iterations
        prop_assert!(palette2.mean_error <= palette1.mean_error, "Error must improve");
    }
}
```

## 4. Android Runtime Reliability

### Goal
Implement ForegroundService for capture and WorkManager for export to ensure 81-frame reliability.

### Implementation

#### 4.1 Foreground Service for Capture
```kotlin
// app/src/main/java/com/rgbagif/capture/GifCaptureService.kt
package com.rgbagif.capture

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.UUID

class GifCaptureService : Service() {
    private val sessionId = UUID.randomUUID().toString()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val frames = mutableListOf<ByteArray>()
    
    companion object {
        const val NOTIFICATION_ID = 81
        const val CHANNEL_ID = "gif_capture_81"
        const val TARGET_FRAMES = 81
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification(0))
        
        scope.launch {
            captureFrames()
        }
        
        return START_NOT_STICKY
    }
    
    private suspend fun captureFrames() = withContext(Dispatchers.IO) {
        // Initialize Rust logging with session
        val rustSessionId = initAndroidTracing()
        
        // Capture exactly 81 frames at 24fps
        repeat(TARGET_FRAMES) { frameIdx ->
            val notification = createNotification(frameIdx + 1)
            notificationManager.notify(NOTIFICATION_ID, notification)
            
            // Capture frame from CameraX
            val rgbaFrame = captureRgbaFrame729()
            frames.add(rgbaFrame)
            
            // Log progress every 10 frames
            if (frameIdx % 10 == 0) {
                android.util.Log.i("GifCapture", 
                    """{"session_id":"$sessionId","frame":$frameIdx,"total":$TARGET_FRAMES}""")
            }
            
            delay(1000L / 24) // 24fps timing
        }
        
        // Trigger export via WorkManager
        enqueueExportWork(frames, sessionId)
        stopSelf()
    }
    
    private fun createNotification(progress: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Capturing GIF Cube")
            .setContentText("Frame $progress/$TARGET_FRAMES")
            .setProgress(TARGET_FRAMES, progress, false)
            .setSmallIcon(R.drawable.ic_cube)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GIF Cube Capture",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Capturing 81 frames for GIF cube"
        }
        notificationManager.createNotificationChannel(channel)
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}
```

#### 4.2 WorkManager for Export
```kotlin
// app/src/main/java/com/rgbagif/export/GifExportWorker.kt
package com.rgbagif.export

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class GifExportWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        const val KEY_SESSION_ID = "session_id"
        const val KEY_FRAMES_PATH = "frames_path"
        const val KEY_OUTPUT_PATH = "output_path"
        
        fun enqueue(
            context: Context,
            sessionId: String,
            framesPath: String,
            outputPath: String
        ): UUID {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            
            val data = workDataOf(
                KEY_SESSION_ID to sessionId,
                KEY_FRAMES_PATH to framesPath,
                KEY_OUTPUT_PATH to outputPath
            )
            
            val request = OneTimeWorkRequestBuilder<GifExportWorker>()
                .setConstraints(constraints)
                .setInputData(data)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    OneTimeWorkRequest.MIN_BACKOFF_MILLIS,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
                .addTag("gif_export_$sessionId")
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "export_$sessionId",
                    ExistingWorkPolicy.KEEP,
                    request
                )
            
            return request.id
        }
    }
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: return@withContext Result.failure()
        val framesPath = inputData.getString(KEY_FRAMES_PATH) ?: return@withContext Result.failure()
        val outputPath = inputData.getString(KEY_OUTPUT_PATH) ?: return@withContext Result.failure()
        
        return@withContext try {
            // Show progress notification
            setForeground(createForegroundInfo())
            
            // Load frames from disk
            val frames = loadFramesFromDisk(framesPath)
            
            // Call Rust pipeline
            val result = strategyBNeuralPipeline(
                rgbaFrames729 = frames,
                fps = 24,
                loopForever = true,
                outputGifPath = outputPath,
                sessionId = sessionId
            )
            
            // Log success metrics
            android.util.Log.i("GifExport", 
                """{"session_id":"$sessionId","status":"success","size":${result.gifSizeBytes}}""")
            
            Result.success(workDataOf(
                "gif_path" to outputPath,
                "size_bytes" to result.gifSizeBytes
            ))
            
        } catch (e: Exception) {
            android.util.Log.e("GifExport",
                """{"session_id":"$sessionId","status":"failed","error":"${e.message}"}""")
            
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
    
    private fun createForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, "gif_export")
            .setContentTitle("Creating GIF Cube")
            .setContentText("Processing 81 frames...")
            .setSmallIcon(R.drawable.ic_processing)
            .build()
        
        return ForegroundInfo(82, notification)
    }
}
```

## 5. Enhance Cube Visualization (WYSIWYG)

### Goal
Ensure the existing CubeVisualizationScreen uses the exact quantized palette from the GIF.

### Implementation

#### 5.1 Pass Quantized Data to Cube
```kotlin
// app/src/main/java/com/rgbagif/ui/CubeVisualizationScreen.kt

@Composable
fun CubeVisualizationScreen(
    quantizedData: QuantResult,  // From Rust pipeline
    gifFile: File?,
    onBack: () -> Unit
) {
    // Use actual palette and indexed frames
    val palette = remember { 
        quantizedData.paletteRgb.chunked(3).map { rgb ->
            Color(rgb[0].toInt(), rgb[1].toInt(), rgb[2].toInt())
        }
    }
    
    val indexedFrames = remember {
        quantizedData.framesIndices.map { indices ->
            ImageBitmap(81, 81).apply {
                // Convert indices to RGB using palette
                indices.forEachIndexed { idx, colorIndex ->
                    val color = palette[colorIndex.toInt()]
                    setPixel(idx % 81, idx / 81, color.toArgb())
                }
            }
        }
    }
    
    // Rest of cube rendering...
}
```

## 6. CI/CD Integration

### Goal
Add GitHub Actions workflow for automated testing.

### Implementation

```yaml
# .github/workflows/ci.yml
name: Production Hardening CI

on:
  push:
    branches: [main, feat/prod-hardening-81cube]
  pull_request:

jobs:
  rust-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
        with:
          lfs: true
      
      - name: Install Rust
        uses: dtolnay/rust-toolchain@stable
      
      - name: Run tests
        run: |
          cd rust-core
          cargo test --all-features
          cargo test --features debug-api
      
      - name: Check structured logging
        run: |
          cargo build --release -p gifpipe-ffi
          # Verify session_id exists in exports
          cargo expand -p gifpipe-ffi | grep session_id || exit 1
  
  android-build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Setup Android SDK
        uses: android-actions/setup-android@v2
      
      - name: Build APK
        run: |
          ./gradlew assembleDebug
          
      - name: Verify Workers exist
        run: |
          find app/src -name "GifCaptureService.kt" | grep -q . || exit 1
          find app/src -name "GifExportWorker.kt" | grep -q . || exit 1
```

## Acceptance Criteria Summary

### ✅ When Complete, You Should Have:

1. **Structured Logging**
   - JSON logs in logcat with session_id correlation
   - Hierarchical levels (INFO summaries, DEBUG details)
   - Panic hook logging E_PANIC

2. **Stable UniFFI Surface**
   - Single production API: `strategy_b_neural_pipeline`
   - Debug APIs behind feature flag
   - Complete error codes with `.code()` accessor

3. **Test Infrastructure**
   - Golden fixtures in Git LFS
   - Property-based invariant tests
   - 81-frame validation

4. **Android Reliability**
   - ForegroundService for 81-frame capture
   - WorkManager for retryable export
   - Progress notifications

5. **WYSIWYG Cube**
   - Uses exact quantized palette
   - 81×81×81 visualization at 24fps
   - Matches exported GIF colors

## Single Command to Execute

```bash
# Copy this entire block to Claude or your agent:

echo "Starting production hardening for 81×81×81 GIF cube vision..."

# 1. Structured logging
cargo add tracing tracing-subscriber tracing-logcat uuid chrono serde_json --package ffi
# Implement init_android_tracing() with JSON output

# 2. Error taxonomy
# Add E_M1_INPUT, E_M1_NEURAL to GifPipeError enum
# Implement validate_gif_bytes() checking for 81 frames

# 3. Golden tests
git lfs install && git lfs track "fixtures/golden/*.{gif,cbor}"
mkdir -p fixtures/golden
# Generate test fixtures and add golden tests

# 4. Android services
# Create GifCaptureService.kt (ForegroundService)
# Create GifExportWorker.kt (CoroutineWorker)
# Update AndroidManifest.xml with service declarations

# 5. Verify cube uses quantized data
# Update CubeVisualizationScreen to use QuantResult palette

# 6. Run tests
cd rust-core && cargo test --all-features
./gradlew assembleDebug

echo "Production hardening complete for 81-cube vision!"
```

## Form Follows Function Philosophy

This implementation maintains the core vision:
- **81 frames** = Perfect cube (9×9×9 reduction from 729×729)
- **81×81 pixels** = Each face of the cube
- **24fps capture** → 25fps GIF (delay=4 centiseconds, closest match)
- **Global palette** = Temporal coherence across cube rotation
- **Oklab quantization** = Perceptually uniform color transitions

The production hardening ensures this elegant mathematical structure is reliably captured, processed, and visualized on Android devices.