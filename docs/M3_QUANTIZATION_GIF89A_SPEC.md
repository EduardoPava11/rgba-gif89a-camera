# M3: Color Quantization & GIF89a Assembly Specification
## Perceptual Color Reduction and Optimal GIF Encoding

### Overview
M3 receives 81×81×4 RGBA frames from M2 and performs perceptual color quantization to produce a high-quality animated GIF with optimal color palette selection, dithering, and compression.

### Design Principles
- **Perceptual Quantization**: Use Oklab color space for visually uniform color reduction
- **Temporal Coherence**: Maintain color stability across all 81 frames
- **Alpha-Weighted Sampling**: Prioritize visible pixels in palette selection
- **Optimal Encoding**: Proper LZW compression with GIF89a extensions

### Color Quantization Pipeline

```rust
pub struct M3Quantizer {
    pub input_frames: Vec<M2Output>,     // 81 frames from M2
    pub color_space: ColorSpace,         // Oklab for perceptual uniformity
    pub max_colors: usize,               // 256 for GIF
    pub dithering: DitheringMethod,      // Floyd-Steinberg with alpha
    pub temporal_mode: TemporalMode,     // Global vs per-frame palette
}

pub enum ColorSpace {
    SRGB,      // Legacy (poor perceptual uniformity)
    Oklab,     // Recommended for perceptual uniformity
    CIELAB,    // Alternative perceptual space
}

pub enum DitheringMethod {
    None,
    FloydSteinberg,
    FloydSteinbergAlpha,  // Alpha-weighted error diffusion
    Ordered,              // Bayer matrix
    BlueNoise,           // Perceptually optimal noise
}

pub enum TemporalMode {
    GlobalPalette,        // Single palette for all frames
    AdaptiveGlobal,      // Global with per-frame optimization
    PerFrame,            // Independent palette per frame
}
```

### Oklab Color Space Quantization

```rust
use palette::{Srgb, Oklab, FromColor, IntoColor};

pub struct OklabQuantizer {
    pub samples: Vec<OklabSample>,
    pub importance_map: Array3<f32>,  // From M2
    pub alpha_threshold: f32,         // Minimum alpha to consider
}

pub struct OklabSample {
    pub l: f32,        // Lightness [0, 1]
    pub a: f32,        // Green-Red [-0.4, 0.4]
    pub b: f32,        // Blue-Yellow [-0.4, 0.4]
    pub weight: f32,   // Importance weight
    pub source_rgb: [u8; 3],
}

impl OklabQuantizer {
    pub fn quantize_frames(&mut self, frames: &[M2Output]) -> QuantizationResult {
        // 1. Convert all frames to Oklab samples
        self.collect_samples(frames);
        
        // 2. Weight samples by importance and alpha
        self.apply_importance_weighting();
        
        // 3. Perform k-means clustering in Oklab space
        let palette = self.kmeans_oklab(256);
        
        // 4. Optimize palette for temporal stability
        let stable_palette = self.temporal_optimization(&palette, frames);
        
        // 5. Generate reverse mapping for fast encoding
        let color_map = self.build_color_map(&stable_palette);
        
        // 6. Apply dithering to each frame
        let dithered_frames = frames.iter()
            .map(|frame| self.dither_frame(frame, &stable_palette, &color_map))
            .collect();
        
        QuantizationResult {
            palette: stable_palette,
            indexed_frames: dithered_frames,
            quality_metrics: self.calculate_quality_metrics(),
        }
    }
    
    fn kmeans_oklab(&self, k: usize) -> Vec<OklabColor> {
        // Initialize centroids using k-means++
        let mut centroids = self.kmeans_plus_plus_init(k);
        let mut assignments = vec![0; self.samples.len()];
        
        for iteration in 0..50 {
            let mut changed = false;
            
            // Assignment step
            for (i, sample) in self.samples.iter().enumerate() {
                let nearest = self.find_nearest_centroid(sample, &centroids);
                if assignments[i] != nearest {
                    assignments[i] = nearest;
                    changed = true;
                }
            }
            
            if !changed { break; }
            
            // Update step (weighted by importance)
            for (c_idx, centroid) in centroids.iter_mut().enumerate() {
                let cluster_samples: Vec<_> = self.samples.iter()
                    .zip(&assignments)
                    .filter(|(_, &a)| a == c_idx)
                    .map(|(s, _)| s)
                    .collect();
                
                if !cluster_samples.is_empty() {
                    *centroid = self.weighted_mean(&cluster_samples);
                }
            }
        }
        
        centroids
    }
    
    fn perceptual_distance(&self, c1: &OklabColor, c2: &OklabColor) -> f32 {
        // Oklab is perceptually uniform, so Euclidean distance works
        let dl = c1.l - c2.l;
        let da = c1.a - c2.a;
        let db = c1.b - c2.b;
        
        // Weight lightness more heavily (human vision is more sensitive)
        (dl * dl * 2.0 + da * da + db * db).sqrt()
    }
}
```

### Floyd-Steinberg Dithering with Alpha

```rust
pub struct AlphaDitherer {
    pub error_diffusion: ErrorDiffusion,
    pub alpha_influence: f32,  // How much alpha affects error diffusion
}

impl AlphaDitherer {
    pub fn dither_frame(
        &self,
        frame: &Array3<u8>,
        palette: &[OklabColor],
        color_map: &HashMap<[u8; 3], u8>,
    ) -> Array2<u8> {
        let (height, width, _) = frame.dim();
        let mut output = Array2::<u8>::zeros((height, width));
        let mut error_r = Array2::<f32>::zeros((height, width));
        let mut error_g = Array2::<f32>::zeros((height, width));
        let mut error_b = Array2::<f32>::zeros((height, width));
        
        for y in 0..height {
            for x in 0..width {
                // Get original color with accumulated error
                let r = (frame[(y, x, 0)] as f32 + error_r[(y, x)]).clamp(0.0, 255.0);
                let g = (frame[(y, x, 1)] as f32 + error_g[(y, x)]).clamp(0.0, 255.0);
                let b = (frame[(y, x, 2)] as f32 + error_b[(y, x)]).clamp(0.0, 255.0);
                let alpha = frame[(y, x, 3)] as f32 / 255.0;
                
                // Find nearest palette color
                let nearest_idx = self.find_nearest_palette_color(r, g, b, palette);
                output[(y, x)] = nearest_idx;
                
                // Calculate error
                let palette_color = &palette[nearest_idx as usize];
                let (pr, pg, pb) = oklab_to_srgb(palette_color);
                let err_r = r - pr as f32;
                let err_g = g - pg as f32;
                let err_b = b - pb as f32;
                
                // Distribute error (Floyd-Steinberg coefficients)
                // Weight by alpha - transparent pixels distribute less error
                let alpha_weight = alpha.powf(self.alpha_influence);
                
                // Right pixel (7/16)
                if x + 1 < width {
                    error_r[(y, x + 1)] += err_r * (7.0/16.0) * alpha_weight;
                    error_g[(y, x + 1)] += err_g * (7.0/16.0) * alpha_weight;
                    error_b[(y, x + 1)] += err_b * (7.0/16.0) * alpha_weight;
                }
                
                // Bottom-left pixel (3/16)
                if y + 1 < height && x > 0 {
                    error_r[(y + 1, x - 1)] += err_r * (3.0/16.0) * alpha_weight;
                    error_g[(y + 1, x - 1)] += err_g * (3.0/16.0) * alpha_weight;
                    error_b[(y + 1, x - 1)] += err_b * (3.0/16.0) * alpha_weight;
                }
                
                // Bottom pixel (5/16)
                if y + 1 < height {
                    error_r[(y + 1, x)] += err_r * (5.0/16.0) * alpha_weight;
                    error_g[(y + 1, x)] += err_g * (5.0/16.0) * alpha_weight;
                    error_b[(y + 1, x)] += err_b * (5.0/16.0) * alpha_weight;
                }
                
                // Bottom-right pixel (1/16)
                if y + 1 < height && x + 1 < width {
                    error_r[(y + 1, x + 1)] += err_r * (1.0/16.0) * alpha_weight;
                    error_g[(y + 1, x + 1)] += err_g * (1.0/16.0) * alpha_weight;
                    error_b[(y + 1, x + 1)] += err_b * (1.0/16.0) * alpha_weight;
                }
            }
        }
        
        output
    }
}
```

### GIF89a Assembly

```rust
use gif::{Encoder, Frame, Repeat};

pub struct Gif89aBuilder {
    pub width: u16,
    pub height: u16,
    pub frames: Vec<IndexedFrame>,
    pub global_palette: Option<Vec<[u8; 3]>>,
    pub loop_count: LoopCount,
    pub frame_delays: Vec<u16>,  // Centiseconds
}

pub struct IndexedFrame {
    pub indices: Vec<u8>,         // Palette indices
    pub local_palette: Option<Vec<[u8; 3]>>, // Optional per-frame palette
    pub transparent_idx: Option<u8>,
    pub disposal_method: DisposalMethod,
}

pub enum LoopCount {
    Infinite,
    Count(u16),
}

pub enum DisposalMethod {
    None,            // Leave frame in place
    Background,      // Clear to background
    Previous,        // Restore previous frame
}

impl Gif89aBuilder {
    pub fn build(&self) -> Result<Vec<u8>, GifError> {
        let mut output = Vec::new();
        
        // Create encoder with proper color settings
        let global_palette = self.global_palette.as_ref()
            .map(|p| p.iter().flat_map(|c| c.iter().copied()).collect::<Vec<_>>())
            .unwrap_or_default();
        
        let mut encoder = Encoder::new(
            &mut output,
            self.width,
            self.height,
            &global_palette
        )?;
        
        // Set loop count (NETSCAPE2.0 extension)
        match self.loop_count {
            LoopCount::Infinite => encoder.set_repeat(Repeat::Infinite)?,
            LoopCount::Count(n) => encoder.set_repeat(Repeat::Finite(n))?,
        }
        
        // Write frames with proper timing
        for (i, frame) in self.frames.iter().enumerate() {
            let delay = self.frame_delays.get(i).copied().unwrap_or(4); // Default 4cs
            
            let mut gif_frame = Frame::default();
            gif_frame.width = self.width;
            gif_frame.height = self.height;
            gif_frame.buffer = Cow::Borrowed(&frame.indices);
            gif_frame.delay = delay;
            
            // Set local palette if different from global
            if let Some(ref local_palette) = frame.local_palette {
                gif_frame.palette = Some(
                    local_palette.iter()
                        .flat_map(|c| c.iter().copied())
                        .collect()
                );
            }
            
            // Set transparency if needed
            if let Some(trans_idx) = frame.transparent_idx {
                gif_frame.transparent = Some(trans_idx);
            }
            
            // Set disposal method
            gif_frame.dispose = match frame.disposal_method {
                DisposalMethod::None => gif::DisposalMethod::Keep,
                DisposalMethod::Background => gif::DisposalMethod::Background,
                DisposalMethod::Previous => gif::DisposalMethod::Previous,
            };
            
            encoder.write_frame(&gif_frame)?;
        }
        
        // Finalize
        drop(encoder);
        
        // Verify GIF structure
        self.verify_gif_structure(&output)?;
        
        Ok(output)
    }
    
    fn verify_gif_structure(&self, data: &[u8]) -> Result<(), GifError> {
        // Check header
        if &data[0..6] != b"GIF89a" {
            return Err(GifError::InvalidHeader);
        }
        
        // Check for NETSCAPE extension
        if !data.windows(11).any(|w| w == b"NETSCAPE2.0") {
            log::warn!("Missing NETSCAPE2.0 extension for looping");
        }
        
        // Check trailer
        if data.last() != Some(&0x3B) {
            return Err(GifError::MissingTrailer);
        }
        
        Ok(())
    }
}
```

### Temporal Optimization

```rust
pub struct TemporalOptimizer {
    pub stability_weight: f32,    // How much to prioritize stability
    pub quality_weight: f32,      // How much to prioritize per-frame quality
}

impl TemporalOptimizer {
    pub fn optimize_palette(
        &self,
        initial_palette: &[OklabColor],
        frames: &[M2Output],
    ) -> Vec<OklabColor> {
        // 1. Analyze color usage across all frames
        let usage_stats = self.analyze_usage(initial_palette, frames);
        
        // 2. Find stable colors (used in many frames)
        let stable_colors = usage_stats.iter()
            .filter(|s| s.frame_count > frames.len() * 3 / 4)
            .map(|s| s.color.clone())
            .collect::<Vec<_>>();
        
        // 3. Allocate remaining palette slots adaptively
        let remaining_slots = 256 - stable_colors.len();
        let adaptive_colors = self.allocate_adaptive(remaining_slots, frames, &stable_colors);
        
        // 4. Merge and sort by importance
        let mut final_palette = stable_colors;
        final_palette.extend(adaptive_colors);
        
        // 5. Sort by luminance for better compression
        final_palette.sort_by(|a, b| a.l.partial_cmp(&b.l).unwrap());
        
        final_palette
    }
}
```

### LZW Compression

```rust
pub struct LzwEncoder {
    min_code_size: u8,
    clear_code: u16,
    end_code: u16,
    next_code: u16,
    code_size: u8,
    dictionary: HashMap<Vec<u8>, u16>,
    output: BitWriter,
}

impl LzwEncoder {
    pub fn compress(&mut self, indices: &[u8]) -> Vec<u8> {
        // Initialize dictionary with single-byte sequences
        self.reset_dictionary();
        
        // Output clear code
        self.output.write_bits(self.clear_code as u32, self.code_size);
        
        let mut sequence = vec![indices[0]];
        
        for &byte in &indices[1..] {
            let mut new_sequence = sequence.clone();
            new_sequence.push(byte);
            
            if self.dictionary.contains_key(&new_sequence) {
                sequence = new_sequence;
            } else {
                // Output code for current sequence
                let code = self.dictionary[&sequence];
                self.output.write_bits(code as u32, self.code_size);
                
                // Add new sequence to dictionary
                if self.next_code < 4096 {
                    self.dictionary.insert(new_sequence.clone(), self.next_code);
                    self.next_code += 1;
                    
                    // Increase code size if necessary
                    if self.next_code == (1 << self.code_size) && self.code_size < 12 {
                        self.code_size += 1;
                    }
                } else {
                    // Dictionary full, output clear code
                    self.output.write_bits(self.clear_code as u32, self.code_size);
                    self.reset_dictionary();
                }
                
                sequence = vec![byte];
            }
        }
        
        // Output final sequence
        if !sequence.is_empty() {
            let code = self.dictionary[&sequence];
            self.output.write_bits(code as u32, self.code_size);
        }
        
        // Output end code
        self.output.write_bits(self.end_code as u32, self.code_size);
        
        self.output.get_bytes()
    }
}
```

### M3 Output Format

```rust
pub struct M3Output {
    // Final GIF data
    pub gif_data: Vec<u8>,
    
    // Metadata
    pub file_size: usize,
    pub frame_count: u16,
    pub duration_ms: u32,
    pub palette_size: u16,
    
    // Quality metrics
    pub avg_delta_e: f32,        // Average color error in Oklab
    pub temporal_stability: f32,  // Frame-to-frame consistency
    pub compression_ratio: f32,   // Original/compressed size
    
    // Detailed statistics
    pub frame_stats: Vec<FrameStats>,
}

pub struct FrameStats {
    pub frame_index: u16,
    pub unique_colors: u16,      // Colors actually used
    pub avg_error: f32,          // Average quantization error
    pub max_error: f32,          // Maximum quantization error
    pub compressed_size: usize,  // Size after LZW
}
```

### Quality Metrics

```rust
pub struct M3QualityMetrics {
    // Color accuracy
    pub delta_e_oklab: f32,       // Perceptual color difference
    pub delta_e_2000: f32,        // CIE Delta E 2000
    
    // Temporal metrics
    pub flicker_score: f32,       // Inter-frame color stability
    pub motion_smoothness: f32,   // Smooth color transitions
    
    // Compression metrics
    pub bits_per_pixel: f32,      // Compression efficiency
    pub palette_utilization: f32, // How well palette is used
}

impl M3QualityMetrics {
    pub fn calculate(
        original_frames: &[M2Output],
        quantized_frames: &[IndexedFrame],
        palette: &[OklabColor],
    ) -> Self {
        let mut total_delta_e = 0.0;
        let mut pixel_count = 0;
        
        for (orig, quant) in original_frames.iter().zip(quantized_frames) {
            for y in 0..81 {
                for x in 0..81 {
                    let orig_rgb = [
                        orig.rgba_81x81[(y * 81 + x) * 4],
                        orig.rgba_81x81[(y * 81 + x) * 4 + 1],
                        orig.rgba_81x81[(y * 81 + x) * 4 + 2],
                    ];
                    
                    let palette_idx = quant.indices[y * 81 + x] as usize;
                    let palette_color = &palette[palette_idx];
                    
                    let delta_e = calculate_delta_e_oklab(&orig_rgb, palette_color);
                    total_delta_e += delta_e;
                    pixel_count += 1;
                }
            }
        }
        
        Self {
            delta_e_oklab: total_delta_e / pixel_count as f32,
            delta_e_2000: 0.0, // Calculate separately
            flicker_score: calculate_flicker(quantized_frames),
            motion_smoothness: calculate_smoothness(quantized_frames),
            bits_per_pixel: 0.0, // Set after compression
            palette_utilization: calculate_utilization(quantized_frames, palette.len()),
        }
    }
}
```

### Integration

```rust
// UniFFI interface
#[uniffi::export]
pub fn m3_create_gif(
    m2_frames: Vec<Vec<u8>>,  // CBOR-encoded M2 outputs
    use_oklab: bool,
    dithering: bool,
) -> Result<Vec<u8>, M3Error> {
    // Decode M2 frames
    let frames: Vec<M2Output> = m2_frames.iter()
        .map(|data| M2Output::from_cbor(data))
        .collect::<Result<_, _>>()?;
    
    // Configure quantizer
    let mut quantizer = M3Quantizer {
        input_frames: frames,
        color_space: if use_oklab { ColorSpace::Oklab } else { ColorSpace::SRGB },
        max_colors: 256,
        dithering: if dithering { 
            DitheringMethod::FloydSteinbergAlpha 
        } else { 
            DitheringMethod::None 
        },
        temporal_mode: TemporalMode::AdaptiveGlobal,
    };
    
    // Perform quantization
    let result = quantizer.quantize_frames(&frames)?;
    
    // Build GIF
    let gif_builder = Gif89aBuilder {
        width: 81,
        height: 81,
        frames: result.indexed_frames,
        global_palette: Some(result.palette),
        loop_count: LoopCount::Infinite,
        frame_delays: vec![4; 81], // 25fps
    };
    
    let gif_data = gif_builder.build()?;
    
    // Return CBOR-encoded result
    M3Output {
        gif_data,
        // ... fill other fields
    }.to_cbor()
}
```

### Success Metrics

- **Color Accuracy**: ΔE < 3.0 in Oklab space
- **Temporal Stability**: < 2% flicker between frames
- **File Size**: < 500KB for 81 frames
- **Palette Utilization**: > 80% of palette colors used
- **Processing Time**: < 200ms total (not critical)
- **Visual Quality**: Smooth gradients, no banding

### Future Enhancements

1. **WebP Animation**: Alternative to GIF for better compression
2. **APNG Support**: True-color animated PNG
3. **Neural Palette**: Learn optimal palette with neural network
4. **Perceptual Weighting**: Use visual attention models
5. **HDR Support**: 10-bit color when available