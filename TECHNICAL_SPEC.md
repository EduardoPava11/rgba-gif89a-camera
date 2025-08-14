# Technical Specification - RGBA→GIF89a Camera App

## Locked Decisions & Specifications

### 1. Input Format
- **Source**: CameraX ImageAnalysis with `OUTPUT_IMAGE_FORMAT_RGBA_8888`
- **Layout**: Single plane (plane 0) containing RGBA pixels
- **Bit depth**: 8 bits per channel (32 bits total)
- **Alpha**: 0-255 range (though camera typically outputs 255/opaque)

### 2. Processing Geometry
- **Input**: 729×729 RGBA8888
- **Output**: 81×81 indexed color
- **Frames**: 81 frames total
- **Capture rate**: 24 fps
- **GIF timing**: delay=4 (4/100 sec per frame ≈ 25fps, closest to 24fps)
- **Loop**: Infinite (loop=forever)

### 3. CBOR Schema (Minimal, Future-proof)
```json
{
  "v": 1,                    // Schema version
  "ts": uint,                // Timestamp milliseconds
  "w": 729,                  // Width
  "h": 729,                  // Height
  "fmt": "RGBA8888",         // Format assertion (4 bytes/pixel)
  "stride": uint,            // Row stride in bytes (often w*4)
  "premul": false,           // Alpha premultiplied (should be false)
  "colorspace": "sRGB",      // Color space for transforms
  "rgba": bstr               // Raw bytes (tightly or stride-packed)
}
```

### 4. Alpha Channel Semantics
- **Input Alpha**: Expected to be 255 (opaque) from camera
- **Runtime Verification**: Scan frames to verify A=255 assumption
- **Learned Alpha**: 81×81 float map (0.0-1.0) from neural network
- **Usage**: Guides quantization but NOT written to GIF
- **Export**: Debug overlays only (heatmaps)

### 5. GIF Output Policy
#### Primary Output (Global Palette)
- Single 256-color palette for all 81 frames
- Optimized for size and temporal stability
- Production deliverable

#### Diagnostic Output (Per-Frame Palettes)
- Individual palette per frame
- For shimmer/quality analysis
- Development tool only

### 6. Quantization Strategy
- **Algorithm**: K-means in Oklab color space
- **Weighting**: Alpha-weighted sampling (weight ∝ A^0.7)
- **Warm-start**: From previous palette (cap 16 new colors/frame)
- **A/B Testing**: Toggle with libimagequant for comparison

### 7. Dithering
- **Method**: Floyd-Steinberg error diffusion
- **Strength**: Scaled by alpha (strength = base_strength * A)
- **Low-A zones**: Minimal dithering to reduce sparkle

### 8. Processing Queue
- **Kotlin→Rust**: Bounded queue size=2
- **Real-time**: A-guided feedback loop
- **Memory**: Process CBOR on device

### 9. Quality Metrics
- **Error**: ΔE in Oklab space (mean/max per frame)
- **Palette drift**: Track color changes frame-to-frame
- **Temporal flicker**: Pixel change percentage
- **Target SSIM**: >0.92 at 81×81

## Frame-by-Frame Processing Flow

### Frame 1 (Bootstrap)
```
1. Capture: 729×729 RGBA from CameraX
2. Downsample: NN forward with zero feedback → RGB₁(81×81), A₁(81×81)
3. Quantize:
   - Build P₁ from RGB₁ (A-weighted sampling)
   - K-means in Oklab → 256 colors
   - Index pixels + Floyd-Steinberg (strength ∝ A₁)
   - Compute ΔE map and usage statistics
4. Output:
   - Buffer for global palette GIF
   - Write to per-frame palette GIF
5. Save state: (P₁, A₁, E₁, U₁)
```

### Frames 2-81
```
1. Capture: 729×729 RGBA from CameraX
2. Downsample: NN with feedback (A_{t-1}, E_{t-1}, U_{t-1}) → RGB_t, A_t
3. Quantize:
   - Warm-start from P_{t-1}
   - Add ≤16 new colors via A-weighted sampling
   - Quick k-means refinement in Oklab
   - Index + dither with A-scaling
   - Update ΔE and usage maps
4. Output:
   - Buffer for global palette GIF
   - Write to per-frame palette GIF
5. Update state: (P_t, A_t, E_t, U_t)
```

### Post-Processing (After Frame 81)
```
1. Build global palette:
   - A-weighted histogram of all 81 frames
   - K-means in Oklab → final 256 colors
2. Remap all frames to global palette
3. Write final GIF with:
   - Header: "GIF89a"
   - Global Color Table (256 colors)
   - Graphics Control Extension (delay=4, loop=forever)
   - 81 image frames
```

## Kernel Selection Menu (K=6)

| Kernel | Type | Pros | Cons | Use Case |
|--------|------|------|------|----------|
| 1 | Box 3×3 | Fast, strong smoothing, good compression | Can blur edges | Flat regions |
| 2 | Gaussian 3×3 | Gentle blur, fewer halos | Slightly slower | Default choice |
| 3 | Box 5×5 | Stronger smoothing, better compression | More blur | Large flat areas |
| 4 | Gaussian 5×5 | Preserves structure better than Box 5×5 | Slower | Gradients |
| 5 | Edge-preserving | Maintains contours, good for text/lines | Most expensive | High-detail areas |
| 6 | Lanczos | High-quality, sharp results | Can cause ringing | Fine details |

Selection: 9×9 Go head chooses per macrocell based on learned policy.

## Weight Format Strategy

### Training Side
```rust
// export_weights.rs in training repo
use burn::record::{DefaultFileRecorder, FullPrecisionSettings};

let recorder = DefaultFileRecorder::<FullPrecisionSettings>::new();
recorder.save("go9x9_default_full.mpk", &model)?;
```

### App Side
```rust
// Load in app
let recorder = DefaultFileRecorder::<FullPrecisionSettings>::new();
let record = recorder.load("assets/go9x9_default_full.mpk", &device)?;
model.load_record(record);
```

**Format**: Burn DefaultFileRecorder with FullPrecisionSettings (.mpk)
- Carries metadata for resilience
- Named MessagePack format
- Future option: HalfPrecision for size reduction

## Android Implementation Details

### CameraX Configuration
```kotlin
val imageAnalysis = ImageAnalysis.Builder()
    .setTargetResolution(Size(729, 729))
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .build()

// Handle row stride when copying
val buffer = imageProxy.planes[0].buffer
val rowStride = imageProxy.planes[0].rowStride
if (rowStride == width * 4) {
    // Direct copy
    buffer.get(rgbaArray)
} else {
    // Handle padding
    for (row in 0 until height) {
        buffer.position(row * rowStride)
        buffer.get(rgbaArray, row * width * 4, width * 4)
    }
}
```

### CBOR Handling (Kotlin)
```kotlin
// Using kotlinx.serialization for CBOR
@Serializable
data class CborFrame(
    val v: Int = 1,
    val ts: Long,
    val w: Int,
    val h: Int,
    val fmt: String,
    val stride: Int,
    val premul: Boolean,
    val colorspace: String,
    @ByteString val rgba: ByteArray
)

// Only for UI/PNG export, not pipeline processing
fun exportToPng(frame: CborFrame) { /* Debug only */ }
```

## Acceptance Criteria

### Milestone 1 Deliverables
- [x] CBOR schema implementation with stride handling
- [x] Alpha verification probe (log % pixels with A≠255)
- [x] Oklab k-means quantizer with ΔE logging
- [x] Floyd-Steinberg with A-scaled strength
- [x] Two GIF outputs (global + per-frame palettes)
- [x] Burn weight loading (.mpk format)
- [x] Frame timing (delay=4, loop=forever)

### Quality Gates
- ΔE mean < 10 in Oklab space
- Palette stability > 80% between frames
- Memory usage < 100MB peak
- Processing < 50ms per frame
- GIF size < 500KB for 81 frames

## References
- [CameraX RGBA_8888](https://developer.android.com/reference/androidx/camera/core/ImageAnalysis#OUTPUT_IMAGE_FORMAT_RGBA_8888)
- [GIF89a Specification](https://www.w3.org/Graphics/GIF/spec-gif89a.txt)
- [Oklab Color Space](https://bottosson.github.io/posts/oklab/)
- [Floyd-Steinberg Dithering](https://en.wikipedia.org/wiki/Floyd%E2%80%93Steinberg_dithering)
- [Burn Records](https://burn.dev/book/building-blocks/record.html)