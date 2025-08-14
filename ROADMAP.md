# RGBA→GIF89a Camera App Roadmap

## Technical Specifications (Locked)
- **Input**: CameraX RGBA8888 at 729×729, 24fps capture
- **Output**: GIF89a at 81×81, 256 colors, delay=4 (≈25fps)
- **Processing**: 9×9 Go head neural downsizer + alpha-aware quantization
- **CBOR Schema**: v1 with stride handling and alpha verification
- **Quantization**: Oklab k-means with Floyd-Steinberg dithering
- **Dual Output**: Global palette (production) + per-frame (diagnostic)

## Phase 0 — Ground Rules & Scaffolding (Day 1–2)

### Goals
- Create workspace structure: `android/`, `rust-core/`, `uniffi/`
- Wire cargo-ndk and Android NDK toolchains
- Establish UniFFI bridge with stub methods

### Platform Facts
- **GIF89a**: Indexed color (≤256), frame animation, LZW compression
- **Android**: ARGB_8888/RGBA_8888 = 4 bytes/pixel (watch memory!)
- **UniFFI**: Safe Kotlin bindings over Rust
- **Target**: 729×729 → 81×81 downsample

### Deliverables
✅ Repo layout with Gradle + cargo-ndk scripts  
✅ Compile "hello .so" library  
✅ Kotlin calls `Pipeline.new()` successfully  
✅ UDL checked in, Kotlin bindings generated

---

## Phase 1 — Burn Downsizer with 9×9 Go Head (Week 1)

### Model Contract
```rust
forward(
    rgba: [1,4,H,W],
    a_prev: [1,1,81,81],
    err_prev: [1,1,81,81],
    usage_prev: [1,1,81,81]
) -> (rgb81: [1,3,81,81], a81: [1,1,81,81])
```

### Components
- **9×9 Policy Head**: Select 1 of K kernels per macrocell (argmax inference)
- **Alpha Head**: Sigmoid map (0..1) for quantization guidance
- **UniFFI Surface**:
  - `Pipeline.new(SessionConfig{81x81, expect=81})`
  - `push_frame(ByteArray rgba, FrameInfo)`
  - `latest_feedback()` returns A/error/usage maps

### Acceptance Test
Feed static image → verify stable RGB81/A81 output deterministically

### Deliverables
✅ Burn model with weights loader  
✅ Rust wrapper implementation  
✅ Kotlin preview overlay for A heatmap  
✅ Unit tests for shapes & determinism

---

## Phase 2 — Alpha-Aware Palette + Indexer (Week 2)

### Palette Management
- **Memory**: Start empty, warm-start subsequent frames
- **Growth**: Cap new colors per frame (e.g., 16)
- **Proposal**: Weight sampling by A^γ (γ ≈ 0.7)
- **Fusion**: k-means or libimagequant to ≤256 colors

### Indexing & Dithering
- Nearest palette match
- Floyd-Steinberg with strength scaled by A
- Reduce sparkle in low-A zones

### Compression Metrics
- Track run-length smoothness
- Monitor index-delta entropy

### Deliverables
✅ Rust quantizer producing indices & palette  
✅ GIF frame encoder via gif crate  
✅ Feedback maps (E_t, U_t) for next frame

---

## Phase 3 — End-to-End 81-Frame Capture (Week 3)

### Pipeline Flow
1. **CameraX**: ARGB_8888 → ByteArray (bounded channel)
2. **Downsizer**: Burn NN → (RGB_t, A_t)
3. **Quantizer**: A-aware → indices, palette, GIF frame
4. **Feedback**: Update (A_t, E_t, U_t, P_t)
5. **Output**: Animated GIF89a via gif crate

### Memory Management
- Bounded channel to avoid OOM
- Store only 81×81 intermediates
- Target: <100MB peak usage

### Deliverables
✅ On-device demo capturing 81 frames  
✅ Valid `capture.gif` output  
✅ Playback in Android Gallery

---

## Phase 4 — Quality & Stability (Week 4)

### Temporal Stability
- Penalties for palette drift
- Limit new colors/frame
- Tune A-scaled dithering

### Performance
- Profile CPU time per frame
- Target: <50ms per frame
- Memory bounded to 100MB

### Quality Metrics
- SSIM > 0.92 at 81×81
- Palette stability > 80%
- File size < 500KB for 81 frames

### Deliverables
✅ Golden tests (deterministic output)  
✅ Flicker metric < 5%  
✅ Performance budget report  
✅ A/B test with libimagequant

---

## Phase 5 — Nice-to-Haves

### Features
- Global vs per-sequence palette toggle
- Debug exports (A maps, palette PNG, ΔE heatmaps)
- wgpu backend for GPU acceleration

### Polish
- Progress UI with frame counter
- GIF preview before save
- Share intent integration

---

## Claude Agent Runbook

### 1. Bootstrap
```bash
# Build Rust for Android
cd rust-core
cargo ndk -t arm64-v8a build --release

# Generate UniFFI bindings
cargo run --bin uniffi-bindgen generate src/gifpipe.udl --language kotlin

# Run Android skeleton
cd ../android
./gradlew assembleDebug
```

### 2. Wire Burn
```rust
// Implement downsizer model
impl Downsampler9x9 {
    pub fn forward(&self, rgba: Tensor, feedback: Feedback) -> (Tensor, Tensor)
}

// Load weights using Burn DefaultFileRecorder
use burn::record::{DefaultFileRecorder, FullPrecisionSettings};
let recorder = DefaultFileRecorder::<FullPrecisionSettings>::new();
let record = recorder.load("assets/go9x9_default_full.mpk", &device)?;
model.load_record(record);

// Add shape tests
assert_eq!(rgb_out.dims(), [1, 3, 81, 81]);
```

### 3. Implement Pipeline
```rust
// Bounded channel
let (tx, rx) = mpsc::sync_channel(3);

// Process frames
push_frame() -> downsizer() -> quantizer() -> feedback_update()
```

### 4. Palette & Indexer
```rust
// A-aware palette
let weights = alpha.powf(0.7);
let palette = weighted_kmeans(&colors, &weights, 256);

// Floyd-Steinberg
let error = apply_dithering(&indices, strength * alpha);
```

### 5. E2E Device Proof
```kotlin
// Capture 81 frames at 24fps
repeat(81) {
    val rgba = camera.captureFrame() // 729×729 RGBA8888
    val frame = CborFrame(
        v = 1,
        ts = System.currentTimeMillis(),
        w = 729, h = 729,
        fmt = "RGBA8888",
        stride = imageProxy.planes[0].rowStride,
        premul = false,
        colorspace = "sRGB",
        rgba = rgba
    )
    pipeline.pushFrame(frame.rgba, frameInfo)
}

// Finalize both GIF outputs
pipeline.finalizeToPath("capture_global.gif")  // Global palette
pipeline.finalizePerFrameGif("capture_perframe.gif")  // Per-frame palettes
```

### 6. Tune
- Cap palette drift: max 16 new colors/frame
- A-scaled dithering: `strength = 0.35 * alpha`
- Compare with libimagequant baseline

### 7. Ship
- Add CI tests for determinism
- Document memory constraints
- Performance benchmarks

## Success Metrics

| Metric | Target | Stretch |
|--------|--------|---------|
| Frame processing | <50ms | <30ms |
| Memory usage | <100MB | <75MB |
| GIF size (81 frames) | <500KB | <350KB |
| SSIM quality | >0.92 | >0.95 |
| Palette stability | >80% | >90% |
| Temporal flicker | <5% | <2% |

## Risk Mitigation

1. **OOM on capture**: Bounded channel, drop old frames
2. **Slow NN inference**: Fallback to simple downsample
3. **Poor quality**: A/B test with libimagequant
4. **Palette instability**: Increase warm-start weight
5. **Large GIF size**: Reduce palette per frame