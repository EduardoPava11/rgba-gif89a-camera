# PR: Fix Weight Format & Align Milestone 1 Specifications

## Summary
This PR fixes the weight format mismatch and ensures all components are aligned for Milestone 1 (per-frame 256-color GIF89a).

## Changes

### 1. Weight Format Standardization (.mpk)
- **Before**: Used `BinFileRecorder` with `.bin` format
- **After**: Uses `DefaultFileRecorder` with `.mpk` (MessagePack) format
- **Files changed**:
  - `rust-core/src/go_network.rs`: Updated `load_go9x9_model()` to use DefaultFileRecorder
  - `scripts/bootstrap.sh`: Updated to copy `.mpk` files instead of `.bin`

### 2. Camera Resolution Alignment  
- **Before**: 1536×1536 (optimized for 64×64 thumbnails)
- **After**: 729×729 (correct for 9×9 Go head → 81×81 output)
- **Files changed**:
  - `app/src/main/java/com/rgbagif/camera/CameraXManager.kt`: Updated target size and crop

### 3. Verified Components
- ✅ **CameraX stride handling**: Properly handles `rowStride` at line 178 of CameraXManager.kt
- ✅ **Per-frame palette only**: Pipeline uses individual palette per frame (no global palette attempt)
- ✅ **RGBA_8888 format**: CameraX configured for direct RGBA output
- ✅ **GIF timing**: delay=4 centiseconds (≈25fps), loop=∞

## Technical Verification

### Weight Loading (Rust)
```rust
// NEW: DefaultFileRecorder with .mpk format
let recorder = DefaultFileRecorder::<FullPrecisionSettings>::new();
match recorder.load(temp_path.clone(), device) {
    Ok(record) => {
        model.load_record(record);
        log::info!("Loaded pre-trained Go 9×9 weights from .mpk");
    }
    Err(e) => {
        log::warn!("Could not load weights: {}, using random init", e);
    }
}
```

### Stride Handling (Kotlin)
```kotlin
// Properly handles row stride for RGBA_8888
val rowStride = plane.rowStride // May be > width*4
for (row in 0 until height) {
    var srcOffset = row * rowStride
    for (col in 0 until width) {
        // Read RGBA with proper stride offset
        val r = rgba[srcOffset].toInt() and 0xFF
        // ...
        srcOffset += pixelStride
    }
}
```

### Per-Frame Palette (Rust)
```rust
// Each frame gets its own palette (Milestone 1)
let mut gif_frame = GifFrame::from_indexed_pixels(
    size,
    size,
    &frame.indexed,
    Some(&palette_flat),  // Per-frame palette
);
```

## File Summary

| File | Changes |
|------|---------|
| `rust-core/src/go_network.rs` | Switch to DefaultFileRecorder, .mpk format |
| `scripts/bootstrap.sh` | Copy .mpk files, create placeholder if missing |
| `app/.../CameraXManager.kt` | Update to 729×729 resolution |
| `rust-core/src/pipeline.rs` | Verified per-frame palette (no changes needed) |

## Testing Checklist

- [ ] Run `./scripts/bootstrap.sh` - should copy .mpk weights
- [ ] Build Rust: `cd rust-core && cargo ndk build --release`
- [ ] Build Android: `./gradlew assembleDebug`
- [ ] Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- [ ] Capture 81 frames at 729×729
- [ ] Verify output GIF:
  - [ ] 81×81 resolution
  - [ ] Per-frame palettes (≤256 colors each)
  - [ ] delay=4 (inspect with `gifsicle --info`)
  - [ ] Loops forever
  - [ ] ΔE logged per frame

## Migration Notes

### For existing setups:
1. Export weights from training repo:
   ```bash
   cd ~/neural-camera-app/training
   cargo run --bin export_weights -- --output go9x9_default_full.mpk
   ```

2. Copy to app:
   ```bash
   cp go9x9_default_full.mpk ~/rgba-gif89a-camera/rust-core/assets/
   ```

3. Re-run bootstrap:
   ```bash
   cd ~/rgba-gif89a-camera
   ./scripts/bootstrap.sh
   ```

## Risks Mitigated

1. **Weight format mismatch**: Now uses consistent DefaultFileRecorder format
2. **Asset path issues**: Bootstrap script handles multiple possible locations
3. **Row stride bugs**: CameraX properly handles stride ≠ width×4
4. **Wrong resolution**: Fixed to 729×729 for Go head architecture
5. **Global palette attempt**: Verified per-frame palette only

## Next Steps

After this PR:
1. Complete Milestone 1 testing (per-frame GIF)
2. Implement global palette optimization (Milestone 2)
3. Add A/B testing with libimagequant
4. Profile performance on device