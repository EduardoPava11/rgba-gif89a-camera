# M2: Neural Downsize Module (729×729 → 81×81)

## Overview
M2 implements the neural downsize step of the RGBA→GIF89a camera pipeline, converting captured 729×729 frames to 81×81 frames using a fixed 9×9 policy/value network.

## Architecture

### Rust Core (`rust-core/m2down/`)
- **Algorithm**: 9×9 block averaging (baseline implementation)
- **Input**: 729×729 RGBA8888 byte array
- **Output**: 81×81 RGBA8888 byte array
- **Processing**: CPU-only, quality prioritized over speed
- **Binding**: UniFFI for Rust→Kotlin interop

### Key Components

#### `m2_downsize_9x9_cpu()`
Main processing function that validates dimensions and performs downsampling:
- Validates input is exactly 729×729
- Each output pixel averages a 9×9 input block
- Returns 81×81 RGBA data

#### Kotlin Integration
- `M2Processor.kt`: Handles frame processing and PNG export
- `MosaicBuilder.kt`: Creates 9×9 diagnostic grid (729×729 total)
- `M2Card.kt`: UI component showing real-time progress

## File Structure

```
session_dir/
├── downsized/           # 81 PNG files (81×81 each)
│   ├── frame_000.png
│   ├── frame_001.png
│   └── ...frame_080.png
└── mosaic.png          # 9×9 grid visualization (729×729)
```

## Performance Metrics

### Expected Timings (per frame)
- Baseline (9×9 averaging): ~5-10ms
- Neural network (future): ~20-30ms
- PNG export: ~2-5ms

### Memory Usage
- Input buffer: 2.1MB (729×729×4)
- Output buffer: 26KB (81×81×4)
- Peak usage: ~3MB per frame

## Testing

### Unit Tests
```bash
cd rust-core/m2down
cargo test
```

### Integration Test
1. Capture 81 frames via M1
2. Process with M2
3. Verify 81 PNG files created
4. Check mosaic.png generated
5. Review timing logs

## Future Enhancements

### Neural Network Path
- Tiny CNN with learned weights
- Fixed 9×9 receptive field
- Stored weights in Rust binary
- Switchable via feature flag

### Quality Metrics
- PSNR comparison vs simple averaging
- Perceptual hash for content preservation
- Edge preservation metrics

## API Reference

### Rust
```rust
pub fn m2_downsize_9x9_cpu(
    rgba_729: Vec<u8>,  // 729×729×4 bytes
    width: u32,         // Must be 729
    height: u32,        // Must be 729
) -> Result<Vec<u8>, M2Error>  // Returns 81×81×4 bytes
```

### Kotlin
```kotlin
fun m2Downsize9x9Cpu(
    rgba729: List<UByte>,
    width: UInt,
    height: UInt
): List<UByte>
```

## Error Handling

### M2Error Types
- `InvalidInputDimensions`: Input not 729×729
- `InvalidDataSize`: Buffer size mismatch

## Logs

### LogCat Tags
- `M2Processor`: Frame processing events
- `MosaicBuilder`: Mosaic generation
- `M2_START`: Session begins
- `M2_FRAME_DONE`: Per-frame completion with timing
- `M2_DONE`: Session complete with total time

## North Star Compliance
✅ CPU-only processing
✅ Fixed 9×9 downsampling
✅ 729×729 → 81×81 exact
✅ PNG output (lossless)
✅ Per-frame timing logs
✅ Diagnostic mosaic generation