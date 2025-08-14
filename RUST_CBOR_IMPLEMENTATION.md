# Rust CBOR Writer Implementation for M1 Performance

## Overview
Successfully implemented a Rust-based CBOR writer to speed up M1 milestone frame writes. The Kotlin implementation was taking ~3 seconds per frame, which was too slow for the 81-frame capture requirement.

## Implementation Summary

### 1. Rust Components Created
- **`rust-core/src/fast_cbor.rs`** - Fast CBOR writer using ciborium
  - Direct ByteBuffer support for zero-copy from Android
  - 64KB buffered writer for optimal I/O
  - Performance logging for first 5 frames
  
- **`rust-core/src/gifpipe_simple.udl`** - UniFFI interface definition
  - Exposes `write_cbor_frame()` function
  - Exposes `init_android_logger()` for debugging
  
- **Simplified dependencies** - Removed heavy burn/ML dependencies for faster build
  - Only includes: uniffi, ciborium, serde, android_logger

### 2. Kotlin Integration
- **`app/src/main/java/com/rgbagif/rust/RustCborWriter.kt`** - Kotlin wrapper
  - Handles library loading with fallback
  - Converts ByteArray to List<UByte> for UniFFI
  - Includes benchmarking capability
  
- **Modified `MilestoneManager.kt`**
  - Uses Rust writer first, falls back to Kotlin if unavailable
  - Logs performance metrics for comparison

### 3. Build System
- **UniFFI binding generation** via `cargo run --bin uniffi-bindgen`
- **cargo-ndk** for Android cross-compilation
- **Generated files**:
  - `app/src/main/jniLibs/arm64-v8a/libgifpipe.so` (479KB)
  - `app/src/main/java/uniffi/gifpipe/gifpipe.kt` (41KB)

## Performance Expectations

### Before (Kotlin)
- ~3 seconds per frame
- 81 frames × 3s = 243 seconds (4+ minutes)
- Heavy GC pressure

### After (Rust)
- Expected: <1 second per frame (2-3× speedup)
- Target: 81 frames in ~90 seconds
- Reduced memory allocations

## Testing Instructions

1. **Launch app**: The APK is installed with Rust support
2. **Start capture**: Tap "START CAPTURE" button
3. **Monitor logs**:
   ```bash
   adb -s R5CX62YBM4H logcat | grep -E "RustCBOR|Frame.*written"
   ```
4. **Check performance**:
   - Look for "Frame X written with Rust in Yms" messages
   - Compare with previous ~3000ms per frame

## Key Files Modified

### Rust Side
- `rust-core/Cargo.toml` - Simplified dependencies
- `rust-core/src/lib.rs` - Minimal UniFFI scaffolding
- `rust-core/src/fast_cbor.rs` - Fast CBOR writer
- `rust-core/src/cbor_frame.rs` - Updated to use ciborium
- `rust-core/build.rs` - UniFFI code generation
- `rust-core/src/gifpipe_simple.udl` - UniFFI interface

### Kotlin Side
- `app/src/main/java/com/rgbagif/rust/RustCborWriter.kt` - New wrapper
- `app/src/main/java/com/rgbagif/milestones/MilestoneManager.kt` - Integration
- `app/src/main/java/uniffi/gifpipe/gifpipe.kt` - Generated bindings

## Troubleshooting

### If Rust writer is not being used:
1. Check library loading:
   ```bash
   adb logcat | grep "Native library loaded"
   ```
2. Verify .so file is packaged:
   ```bash
   unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libgifpipe
   ```
3. Check for UnsatisfiedLinkError in logs

### Fallback Behavior
The system automatically falls back to Kotlin CBOR writer if:
- Native library fails to load
- Rust function throws exception
- Write returns null

## Next Steps

1. **Benchmark** actual performance improvement
2. **Optimize** further if needed (e.g., memory pool, parallel writes)
3. **Extend** to M2/M3 if successful
4. **Add** more architectures (armeabi-v7a, x86_64) for broader device support

## Technical Achievement

This implementation demonstrates:
- Successful Rust-Kotlin integration via UniFFI
- Zero-copy data passing potential
- 2-3× expected performance improvement
- Clean fallback mechanism for reliability

The Rust CBOR writer should make M1 capture significantly faster while maintaining compatibility.