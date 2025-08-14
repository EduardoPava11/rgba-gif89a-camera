# Two-GIF Output Strategy

## Overview

The app produces two distinct GIF outputs for different purposes:

1. **Global Palette GIF** (Production deliverable)
2. **Per-Frame Palette GIF** (Diagnostic tool)

## 1. Global Palette GIF

### Purpose
- **Primary output** for end users
- Optimized for file size and temporal stability
- Single 256-color palette shared across all 81 frames
- Best visual quality with minimal flicker

### Generation Process

```rust
// Post-processing after all 81 frames captured
pub fn build_global_palette_gif(&self) -> Result<Vec<u8>, GifPipeError> {
    // Step 1: Collect all RGB data with alpha weighting
    let mut weighted_pixels = Vec::new();
    for (frame_idx, frame) in self.frames.iter().enumerate() {
        for i in 0..81*81 {
            let weight = frame.alpha_81x81[i] as f32 / 255.0;
            let weight = weight.powf(0.7); // γ=0.7 for importance
            
            if weight > 0.1 {
                weighted_pixels.push((
                    frame.rgb_81x81[i*3..i*3+3],
                    weight * temporal_weight(frame_idx)
                ));
            }
        }
    }
    
    // Step 2: Build global palette via Oklab k-means
    let global_palette = oklab_kmeans(&weighted_pixels, 256)?;
    
    // Step 3: Remap all frames to global palette
    let mut gif_encoder = GifEncoder::new();
    gif_encoder.set_global_palette(&global_palette);
    
    for frame in &self.frames {
        let indices = remap_to_palette(&frame.rgb_81x81, &global_palette);
        gif_encoder.add_frame(indices, delay=4)?; // 25fps
    }
    
    gif_encoder.finalize()
}

fn temporal_weight(frame_idx: usize) -> f32 {
    // Slightly favor middle frames for palette
    let center = 40.5;
    let distance = (frame_idx as f32 - center).abs();
    1.0 - (distance / 40.5) * 0.2 // 80-100% weight range
}
```

### File Structure
```
capture_global.gif
├── Header: "GIF89a"
├── Logical Screen Descriptor
├── Global Color Table (256 colors)
├── Application Extension (NETSCAPE2.0, loop forever)
└── 81 × Image Data
    ├── Graphics Control Extension (delay=4)
    └── Image Descriptor + LZW compressed indices
```

### Quality Characteristics
- **Palette stability**: 100% (no changes between frames)
- **Temporal flicker**: Minimal
- **Color accuracy**: Good average, may lose detail in individual frames
- **File size**: Smallest possible
- **Use case**: Final deliverable, social media sharing

## 2. Per-Frame Palette GIF

### Purpose
- **Diagnostic tool** for quality analysis
- Each frame gets its own optimized 256-color palette
- Maximum color fidelity per frame
- Reveals palette evolution and drift

### Generation Process

```rust
// Real-time during capture
pub fn build_per_frame_palette_gif(&self) -> Result<Vec<u8>, GifPipeError> {
    let mut gif_encoder = GifEncoder::new();
    gif_encoder.set_global_palette(None); // No global palette
    
    for (idx, frame) in self.frames.iter().enumerate() {
        // Build frame-specific palette
        let frame_palette = self.quantizer.build_palette(
            &frame.rgb_81x81,
            &frame.alpha_81x81,
            256
        )?;
        
        // Quantize with frame palette
        let indices = self.quantizer.quantize_with_palette(
            &frame.rgb_81x81,
            &frame_palette
        );
        
        // Add frame with local color table
        gif_encoder.add_frame_with_palette(
            indices,
            frame_palette,
            delay=4
        )?;
        
        log::debug!("Frame {}: {} unique colors", idx, count_unique(&indices));
    }
    
    gif_encoder.finalize()
}
```

### File Structure
```
capture_perframe.gif
├── Header: "GIF89a"
├── Logical Screen Descriptor
├── Application Extension (NETSCAPE2.0, loop forever)
└── 81 × Frame Block
    ├── Graphics Control Extension (delay=4)
    ├── Image Descriptor
    ├── Local Color Table (256 colors, unique per frame)
    └── Image Data (LZW compressed indices)
```

### Quality Characteristics
- **Palette stability**: Variable (changes each frame)
- **Temporal flicker**: Higher (palette shimmer)
- **Color accuracy**: Maximum per frame
- **File size**: Larger (81 palettes vs 1)
- **Use case**: Quality inspection, debugging quantization

## Comparison Metrics

| Aspect | Global Palette | Per-Frame Palette |
|--------|---------------|-------------------|
| File size | ~300-400 KB | ~600-800 KB |
| Palette count | 1 × 256 colors | 81 × 256 colors |
| Color accuracy | 85-90% | 95-98% |
| Temporal stability | Excellent | Poor |
| Encoding speed | Slower (2-pass) | Faster (1-pass) |
| Memory usage | Higher (buffer all) | Lower (streaming) |

## Implementation Details

### Kotlin Side

```kotlin
// MainActivity.kt
class MainActivity : ComponentActivity() {
    private val pipeline = Pipeline(SessionConfig(
        useGlobalPalette = true,  // Can toggle
        allowPaletteGrowth = true,
        maxFrames = 81,
        outputSize = 81
    ))
    
    fun onCaptureComplete() {
        // Generate both outputs
        coroutineScope.launch {
            // Global palette (primary)
            val globalGif = pipeline.finalizeToPath(
                "$externalDir/capture_${timestamp}_global.gif"
            )
            
            // Per-frame palette (diagnostic)
            if (BuildConfig.DEBUG) {
                val perFrameGif = pipeline.finalizePerFrameGif(
                    "$externalDir/capture_${timestamp}_perframe.gif"
                )
                
                // Show comparison in UI
                showGifComparison(globalGif, perFrameGif)
            }
        }
    }
}
```

### Rust Side

```rust
// pipeline.rs
impl Pipeline {
    /// Generate global palette GIF (production)
    pub fn finalize_to_path(&self, path: &str) -> Result<(), GifPipeError> {
        // Two-pass: collect stats, then encode
        let global_palette = self.build_global_palette()?;
        self.encode_with_palette(path, &global_palette)
    }
    
    /// Generate per-frame palette GIF (diagnostic)
    pub fn finalize_per_frame_gif(&self, path: &str) -> Result<(), GifPipeError> {
        // One-pass: encode with local palettes
        self.encode_with_local_palettes(path)
    }
    
    /// A/B comparison mode
    pub fn generate_comparison_strip(&self, path: &str) -> Result<(), GifPipeError> {
        // Side-by-side or alternating frames
        // Useful for quality assessment
        todo!("Implement comparison visualization")
    }
}
```

## Quality Assessment Workflow

1. **Capture**: Record 81 frames at 24fps
2. **Process**: Generate both GIF variants
3. **Compare**: 
   - Open both in image viewer
   - Check for palette shimmer in per-frame version
   - Verify global palette captures essence
4. **Metrics**:
   - ΔE color difference per frame
   - Palette utilization histogram
   - Temporal stability score
5. **Decision**: 
   - Ship global palette version
   - Use per-frame for debugging if quality issues

## Storage Paths

```
/sdcard/Android/data/com.rgbagif/files/
├── captures/
│   ├── 2024_01_15_14_23_45_global.gif    # Production
│   ├── 2024_01_15_14_23_45_perframe.gif  # Debug only
│   └── 2024_01_15_14_23_45_metrics.json  # Quality stats
└── debug/
    ├── palettes/                          # PNG swatches
    ├── alpha_maps/                        # Heatmap PNGs
    └── error_maps/                        # ΔE visualizations
```

## User Interface

```kotlin
// GifOutputSettings.kt
@Composable
fun GifOutputSettings(
    viewModel: MainViewModel
) {
    Column {
        Text("GIF Output Mode")
        
        Row {
            RadioButton(
                selected = viewModel.outputMode == OutputMode.GLOBAL,
                onClick = { viewModel.outputMode = OutputMode.GLOBAL }
            )
            Text("Global Palette (Recommended)")
        }
        
        Row {
            RadioButton(
                selected = viewModel.outputMode == OutputMode.PER_FRAME,
                onClick = { viewModel.outputMode = OutputMode.PER_FRAME }
            )
            Text("Per-Frame Palette (Diagnostic)")
        }
        
        if (viewModel.outputMode == OutputMode.PER_FRAME) {
            Card(
                backgroundColor = Color.Yellow.copy(alpha = 0.2f)
            ) {
                Text(
                    "⚠️ Per-frame mode is for quality debugging only. " +
                    "File size will be 2× larger with visible shimmer."
                )
            }
        }
        
        // Debug options
        if (BuildConfig.DEBUG) {
            SwitchWithLabel(
                label = "Export both variants",
                checked = viewModel.exportBoth,
                onCheckedChange = { viewModel.exportBoth = it }
            )
            
            SwitchWithLabel(
                label = "Generate comparison strip",
                checked = viewModel.generateComparison,
                onCheckedChange = { viewModel.generateComparison = it }
            )
        }
    }
}
```

## Acceptance Criteria

### Global Palette GIF
- [ ] Single 256-color palette for all frames
- [ ] File size <500KB for 81 frames
- [ ] No visible palette shimmer
- [ ] ΔE mean <10 in Oklab space
- [ ] Plays correctly in all viewers

### Per-Frame Palette GIF
- [ ] Unique palette per frame (up to 256 colors each)
- [ ] Maximum color fidelity
- [ ] Diagnostic metrics logged
- [ ] Clear "DEBUG" watermark or filename
- [ ] Not shipped in production builds

### Comparison Tools
- [ ] Side-by-side viewer in debug mode
- [ ] Palette drift visualization
- [ ] Frame-by-frame ΔE chart
- [ ] Export metrics as JSON
- [ ] A/B test with libimagequant

## Future Enhancements

1. **Adaptive Global Palette**: Build from frames 20-60 (most stable)
2. **Hybrid Mode**: Global base + small per-frame corrections
3. **Palette Clustering**: Group similar frames, share palettes
4. **Progressive Palette**: Start with 128 colors, grow to 256
5. **ML Palette Prediction**: Use neural network to predict optimal palette