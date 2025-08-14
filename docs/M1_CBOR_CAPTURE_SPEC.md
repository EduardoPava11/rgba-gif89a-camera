# M1: CBOR Frame Capture Specification
## High-Fidelity 8-bit RGBA Capture from CameraX

### Overview
M1 is responsible for capturing raw RGBA frames from CameraX with maximum fidelity, preserving all 8-bit color information while maintaining consistent color space and preparing data for neural network processing.

### Design Principles
- **Fidelity First**: Preserve every bit of color information from the camera sensor
- **Color Space Consistency**: Maintain sRGB throughout with proper metadata
- **Single Frame Processing**: Optimize for quality over speed (one frame at a time)
- **Neural Network Ready**: Format optimized for direct tensor conversion

### CBOR Frame Format v2.0

```rust
pub struct CborFrameV2 {
    // Header (16 bytes when encoded)
    pub version: u16,           // 0x0200 (v2.0)
    pub frame_index: u16,       // 0-80 for 81 frames
    pub timestamp_ms: u64,      // Capture timestamp
    pub checksum: u32,          // CRC32 of rgba_data
    
    // Dimensions (12 bytes)
    pub width: u16,             // 729 pixels
    pub height: u16,            // 729 pixels
    pub stride: u32,            // Row stride in bytes (usually width * 4)
    pub pixel_format: u32,      // 0x01 = RGBA8888
    
    // Color Space (variable, ~32 bytes)
    pub color_space: ColorSpace,
    
    // Data (2,125,764 bytes for 729×729×4)
    pub rgba_data: Vec<u8>,     // Tightly packed RGBA bytes
    
    // Metadata (variable, ~256 bytes)
    pub metadata: FrameMetadata,
}

pub struct ColorSpace {
    pub space: String,          // "sRGB" (future: "Display-P3", "Rec.2020")
    pub gamma: f32,             // 2.2 for sRGB
    pub white_point: [f32; 2],  // [0.3127, 0.3290] for D65
    pub primaries: [[f32; 2]; 3], // RGB primaries xy coordinates
    pub transfer_function: String, // "sRGB" or "linear"
}

pub struct FrameMetadata {
    pub exposure_time_ns: u64,  // Sensor exposure time
    pub iso_sensitivity: u32,   // ISO value
    pub focal_length_mm: f32,   // Lens focal length
    pub aperture_f_stop: f32,   // f-number
    pub color_temperature: u32,  // Kelvin
    pub tint_correction: i16,    // Green-magenta tint
    pub sensor_timestamp: u64,   // Hardware timestamp
    pub rotation_degrees: u16,   // 0, 90, 180, 270
    pub is_mirrored: bool,       // Front camera mirror
}
```

### CameraX Configuration

```kotlin
// Optimal CameraX setup for color fidelity
class M1CameraConfig {
    val imageAnalysisConfig = ImageAnalysis.Builder()
        .setTargetResolution(Size(729, 729))
        .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .setImageQueueDepth(2)  // Minimal buffering
        .setBackpressureStrategy(STRATEGY_BLOCK_PRODUCER)
        .build()
    
    // Color control extensions
    val colorCorrection = Camera2Interop.Extender(preview)
        .setCaptureRequestOption(
            CaptureRequest.COLOR_CORRECTION_MODE,
            CameraMetadata.COLOR_CORRECTION_MODE_HIGH_QUALITY
        )
        .setCaptureRequestOption(
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
            CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
        )
}
```

### Frame Capture Pipeline

```rust
// Rust implementation for zero-copy capture
pub fn capture_frame_v2(
    image_proxy: &ImageProxy,
    frame_index: u16,
) -> Result<CborFrameV2, CaptureError> {
    // 1. Validate image format
    assert_eq!(image_proxy.format(), ImageFormat::RGBA_8888);
    assert_eq!(image_proxy.width(), 729);
    assert_eq!(image_proxy.height(), 729);
    
    // 2. Extract plane data (zero-copy when possible)
    let plane = image_proxy.planes()[0];
    let stride = plane.row_stride();
    let pixel_stride = plane.pixel_stride(); // Should be 4 for RGBA
    
    // 3. Handle stride padding if necessary
    let rgba_data = if stride == 729 * 4 {
        // No padding, direct copy
        plane.buffer().to_vec()
    } else {
        // Remove row padding
        let mut tight_data = Vec::with_capacity(729 * 729 * 4);
        for y in 0..729 {
            let row_start = y * stride;
            let row_end = row_start + (729 * 4);
            tight_data.extend_from_slice(&plane.buffer()[row_start..row_end]);
        }
        tight_data
    };
    
    // 4. Calculate checksum for integrity
    let checksum = crc32fast::hash(&rgba_data);
    
    // 5. Extract camera metadata
    let metadata = extract_camera_metadata(image_proxy)?;
    
    // 6. Build CBOR frame
    Ok(CborFrameV2 {
        version: 0x0200,
        frame_index,
        timestamp_ms: image_proxy.timestamp() / 1_000_000,
        checksum,
        width: 729,
        height: 729,
        stride: 729 * 4,
        pixel_format: 0x01, // RGBA8888
        color_space: ColorSpace::srgb_default(),
        rgba_data,
        metadata,
    })
}
```

### Color Fidelity Optimizations

#### 1. Disable Auto White Balance During Capture
```kotlin
// Lock white balance after initial convergence
private fun lockWhiteBalance() {
    camera2Interop.setCaptureRequestOption(
        CaptureRequest.CONTROL_AWB_MODE,
        CameraMetadata.CONTROL_AWB_MODE_OFF
    )
    camera2Interop.setCaptureRequestOption(
        CaptureRequest.COLOR_CORRECTION_GAINS,
        currentColorGains  // Locked gains
    )
}
```

#### 2. Raw Sensor Access (Future Enhancement)
```kotlin
// For maximum fidelity, capture RAW and convert to RGBA
val rawImageReader = ImageReader.newInstance(
    729, 729,
    ImageFormat.RAW_SENSOR,
    1
)
// Then demosaic and color-correct in Rust
```

#### 3. HDR Frame Stacking (Future)
```rust
// Capture 3 exposures per logical frame
pub struct HdrFrame {
    pub underexposed: CborFrameV2,   // -2 EV
    pub normal: CborFrameV2,          // 0 EV
    pub overexposed: CborFrameV2,    // +2 EV
}

// Merge in linear space before neural processing
pub fn merge_hdr(frames: &HdrFrame) -> CborFrameV2 {
    // Implement Debevec tone mapping
}
```

### Quality Validation

```rust
// Validate captured frame quality
pub fn validate_frame_quality(frame: &CborFrameV2) -> QualityReport {
    QualityReport {
        // Check for clipping
        clipped_pixels: count_clipped_pixels(&frame.rgba_data),
        
        // Verify color distribution
        histogram: calculate_histogram(&frame.rgba_data),
        
        // Detect banding artifacts
        banding_score: detect_banding(&frame.rgba_data),
        
        // Calculate color statistics
        avg_rgb: calculate_average_rgb(&frame.rgba_data),
        color_variance: calculate_color_variance(&frame.rgba_data),
        
        // Verify no dead pixels
        dead_pixels: find_dead_pixels(&frame.rgba_data),
    }
}
```

### CBOR Encoding

```rust
use ciborium::{cbor, Value};

impl CborFrameV2 {
    pub fn to_cbor(&self) -> Result<Vec<u8>, cbor::Error> {
        // Use CBOR indefinite-length byte string for rgba_data
        let mut encoder = Encoder::new(Vec::new());
        
        // Encode header as fixed map
        encoder.encode(cbor!({
            "v" => self.version,
            "idx" => self.frame_index,
            "ts" => self.timestamp_ms,
            "crc" => self.checksum,
            "w" => self.width,
            "h" => self.height,
            "stride" => self.stride,
            "fmt" => self.pixel_format,
            "cs" => self.color_space.to_cbor(),
            "meta" => self.metadata.to_cbor(),
            // Use byte string for efficient binary encoding
            "rgba" => Value::Bytes(self.rgba_data.clone()),
        }))?;
        
        Ok(encoder.into_writer())
    }
}
```

### Performance Considerations

1. **Memory Pool**: Pre-allocate 3 frame buffers (capture, process, encode)
2. **Zero-Copy**: Use ByteBuffer.allocateDirect() for JNI transfers
3. **Parallel Validation**: Run quality checks on separate thread
4. **Compression**: Optional zstd compression for storage (lossless)

### Integration Points

```rust
// UniFFI interface for Kotlin integration
#[uniffi::export]
pub fn m1_capture_frame(
    plane_buffer: Vec<u8>,
    stride: u32,
    frame_index: u16,
    timestamp_ms: u64,
) -> Result<Vec<u8>, M1Error> {
    let frame = build_cbor_frame_v2(
        plane_buffer,
        stride,
        frame_index,
        timestamp_ms
    )?;
    frame.to_cbor()
}
```

### Success Metrics

- **Color Accuracy**: ΔE < 1.0 in sRGB space
- **Bit Depth**: Full 8-bit per channel preserved
- **Dynamic Range**: > 7 stops captured
- **Frame Consistency**: < 0.5% variance between frames
- **Processing Time**: < 50ms per frame (not critical)

### Future Enhancements

1. **10-bit Capture**: When Android supports 10-bit camera pipeline
2. **Wide Color Gamut**: Display-P3 or Rec.2020 capture
3. **RAW Processing**: Direct sensor data with custom ISP
4. **Multi-Frame Noise Reduction**: Temporal denoising
5. **Lens Correction**: Vignetting and distortion compensation