# M1 Implementation Status - CborFrameV2

## ✅ Completed

### Rust Core (rust-core/)
1. **cbor_frame_v2.rs** - Enhanced CBOR format with:
   - CRC32 integrity checking via `crc32fast`
   - ColorSpace tracking (sRGB, Display-P3, Rec.2020)
   - FrameMetadata structure with camera parameters
   - Row stride handling for tight packing
   - Quality validation with clipping detection
   - Frame statistics calculation

2. **fast_cbor_v2.rs** - Fast writer implementation:
   - Zero-copy optimizations where possible
   - Metadata extraction from CaptureResult
   - Quality reporting for first 3 frames
   - File integrity verification
   - UniFFI exports for Android integration

3. **gifpipe.udl** - Updated UniFFI interface:
   - `write_cbor_frame_v2()` with full metadata
   - `write_cbor_frame_v2_simple()` for simplified usage
   - `verify_cbor_v2_file()` for integrity checking

### Android/Kotlin (app/)
1. **M1ProcessorV2.kt** - Enhanced frame processor:
   - ImageProxy to CBOR V2 conversion
   - Metadata extraction from CaptureResult
   - Row stride removal for tight packing
   - Quality metrics logging
   - Frame indexing and session tracking

2. **CameraXManager.kt** - Already configured for:
   - RGBA_8888 output format
   - 729×729 capture resolution
   - Single plane processing
   - Row stride handling

## 🚧 Next Steps

### Integration
1. Update MainViewModel to use M1ProcessorV2
2. Wire up CborFrameV2 capture in the capture flow
3. Test on device with real camera data

### Testing
1. Run `./test_cbor_v2.sh` to build and verify
2. Deploy to device and capture frames
3. Verify CBOR V2 files with integrity checks
4. Validate quality metrics

## Key Improvements Over V1

| Feature | V1 (CborFrame) | V2 (CborFrameV2) |
|---------|----------------|------------------|
| Integrity | None | CRC32 checksum |
| Metadata | Basic timestamp | Full camera metadata |
| Color Space | Fixed sRGB | Configurable with primaries |
| Quality | No validation | Clipping & dynamic range checks |
| Stride | Basic handling | Optimized tight packing |
| Frame Index | Not tracked | Sequential indexing |

## CBOR V2 Schema

```rust
pub struct CborFrameV2 {
    pub version: u16,           // 0x0200
    pub frame_index: u16,       // 0-80
    pub timestamp_ms: u64,
    pub checksum: u32,          // CRC32
    pub width: u16,             // 729
    pub height: u16,            // 729
    pub stride: u32,            // Tight: 729*4
    pub pixel_format: u32,      // 0x01 = RGBA8888
    pub color_space: ColorSpace,
    pub metadata: FrameMetadata,
    pub rgba_data: Vec<u8>,     // 2,125,764 bytes
}
```

## Quality Metrics

The implementation tracks:
- **Clipped pixels**: Pixels at 0 or 255 (per channel)
- **Alpha usage**: Non-opaque pixels (A ≠ 255)
- **Dynamic range**: Histogram spread
- **Color balance**: RGB channel ratios
- **Integrity**: CRC32 verification

## Performance

Expected performance on modern Android devices:
- Frame capture: ~30-50ms
- CBOR encoding: ~10-20ms
- File write: ~5-10ms
- Total per frame: ~50-80ms

This allows capturing 32 frames in ~2-3 seconds.

## Files Modified

```
rust-core/
├── src/
│   ├── cbor_frame_v2.rs     [NEW]
│   ├── fast_cbor_v2.rs      [NEW]
│   ├── lib.rs                [MODIFIED]
│   └── gifpipe.udl           [MODIFIED]
├── Cargo.toml                [MODIFIED]

app/src/main/java/
├── com/rgbagif/
│   └── processing/
│       └── M1ProcessorV2.kt  [NEW]
└── uniffi/gifpipe/
    └── gifpipe.kt            [REGENERATED]
```

## Commands

```bash
# Build Rust library
cd rust-core && cargo build --release

# Generate UniFFI bindings
cargo run --bin uniffi-bindgen -- generate \
  --language kotlin \
  --out-dir ../app/src/main/java \
  src/gifpipe.udl

# Build for Android (requires cargo-ndk)
cargo ndk -t aarch64-linux-android \
  -o ../app/src/main/jniLibs build --release

# Test on device
./gradlew installDebug
adb shell am start -n com.rgbagif/.MainActivity
```