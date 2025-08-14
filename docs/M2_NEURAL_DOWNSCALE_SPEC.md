# M2: Neural Downscale Specification
## Intelligent 729×729 → 81×81 Reduction with Learned Kernels

### Overview
M2 performs intelligent downscaling from 729×729 to 81×81 pixels using neural network-guided kernel selection and alpha channel learning. This is not just resize - it's intelligent feature preservation optimized for Go game visualization.

### Design Principles
- **Context-Aware Downscaling**: Different regions use different kernels
- **Alpha Channel Learning**: Progressive refinement of transparency
- **Color Preservation**: Maintain vibrant colors through intelligent sampling
- **Go-Specific Optimization**: Preserve stone edges and board lines

### Neural Network Architecture

```rust
pub struct NeuralDownscaler {
    // Input: 729×729×4 RGBA
    // Output: 81×81×4 RGBA + kernel maps
    
    pub input_size: (u32, u32),      // (729, 729)
    pub output_size: (u32, u32),     // (81, 81)
    pub scale_factor: u32,            // 9 (729/81)
    
    // Learned components
    pub kernel_predictor: KernelNet,  // Predicts best kernel per region
    pub alpha_refiner: AlphaNet,      // Learns alpha channel
    pub color_encoder: ColorNet,      // Preserves color relationships
}

pub struct KernelNet {
    // CNN that predicts optimal kernel for each 9×9 region
    pub conv1: Conv2d,  // 4 → 32 channels, 3×3
    pub conv2: Conv2d,  // 32 → 64 channels, 3×3
    pub conv3: Conv2d,  // 64 → 6 channels (kernel types)
}

pub enum DownscaleKernel {
    Box,           // Simple average (smooth regions)
    Gaussian,      // Gaussian blur (soft features)
    Lanczos3,      // Sharp edges (stone boundaries)
    Mitchell,      // Balanced (general purpose)
    EdgePreserve,  // Custom edge-preserving
    Adaptive,      // Learned weights
}
```

### M2 Processing Pipeline

```rust
pub struct M2FrameData {
    // Input from M1
    pub source_cbor: CborFrameV2,     // 729×729×4 RGBA
    
    // Neural processing results
    pub kernel_map: Array2<u8>,       // 81×81 kernel selections
    pub alpha_map: Array2<f32>,       // 81×81 learned alpha
    pub importance_map: Array2<f32>,  // 81×81 visual importance
    
    // Output
    pub downscaled_rgba: Vec<u8>,     // 81×81×4 final RGBA
    pub color_palette_hint: Vec<[u8; 3]>, // Suggested palette
    
    // Metadata
    pub processing_time_ms: u32,
    pub kernel_stats: KernelStatistics,
    pub color_metrics: ColorMetrics,
}

pub struct KernelStatistics {
    pub kernel_usage: [u32; 6],       // Usage count per kernel type
    pub edge_pixels: u32,             // Pixels using edge-preserve
    pub smooth_pixels: u32,           // Pixels using box/gaussian
}

pub struct ColorMetrics {
    pub dominant_colors: Vec<[u8; 3]>, // Top 16 colors
    pub color_variance: f32,          // Color distribution spread
    pub saturation_avg: f32,          // Average saturation
    pub luminance_histogram: [u32; 256], // Brightness distribution
}
```

### Intelligent Downscaling Algorithm

```rust
impl NeuralDownscaler {
    pub fn process_frame(&mut self, input: &CborFrameV2) -> Result<M2FrameData, M2Error> {
        // 1. Prepare input tensor (729×729×4)
        let input_tensor = self.prepare_tensor(&input.rgba_data)?;
        
        // 2. Predict kernel map (which kernel to use for each output pixel)
        let kernel_map = self.kernel_predictor.forward(&input_tensor)?;
        
        // 3. Extract features for alpha learning
        let features = self.extract_features(&input_tensor)?;
        
        // 4. Learn alpha channel (progressive refinement)
        let alpha_map = self.alpha_refiner.refine_alpha(&features, &input_tensor)?;
        
        // 5. Perform adaptive downscaling
        let mut output = Array3::<u8>::zeros((81, 81, 4));
        
        for y in 0..81 {
            for x in 0..81 {
                let kernel_type = kernel_map[(y, x)];
                let source_region = self.get_source_region(x, y); // 9×9 region
                
                // Apply selected kernel
                let pixel = match kernel_type {
                    0 => self.apply_box_filter(&input_tensor, &source_region),
                    1 => self.apply_gaussian(&input_tensor, &source_region),
                    2 => self.apply_lanczos3(&input_tensor, &source_region),
                    3 => self.apply_mitchell(&input_tensor, &source_region),
                    4 => self.apply_edge_preserve(&input_tensor, &source_region),
                    5 => self.apply_learned_kernel(&input_tensor, &source_region, x, y),
                    _ => unreachable!(),
                };
                
                // Apply learned alpha
                output[(y, x, 0)] = pixel[0]; // R
                output[(y, x, 1)] = pixel[1]; // G
                output[(y, x, 2)] = pixel[2]; // B
                output[(y, x, 3)] = (pixel[3] as f32 * alpha_map[(y, x)]) as u8;
            }
        }
        
        // 6. Color analysis for M3 hint
        let color_palette_hint = self.analyze_colors(&output)?;
        
        // 7. Build result
        Ok(M2FrameData {
            source_cbor: input.clone(),
            kernel_map,
            alpha_map,
            importance_map: self.calculate_importance(&output)?,
            downscaled_rgba: output.into_raw_vec(),
            color_palette_hint,
            processing_time_ms: 0, // Set by caller
            kernel_stats: self.calculate_kernel_stats(&kernel_map),
            color_metrics: self.calculate_color_metrics(&output),
        })
    }
}
```

### Kernel Implementations

```rust
// Box Filter - Simple averaging
fn apply_box_filter(&self, input: &Array3<u8>, region: &Region) -> [u8; 4] {
    let mut sum = [0u32; 4];
    for y in region.y_start..region.y_end {
        for x in region.x_start..region.x_end {
            for c in 0..4 {
                sum[c] += input[(y, x, c)] as u32;
            }
        }
    }
    let count = ((region.y_end - region.y_start) * (region.x_end - region.x_start)) as u32;
    [
        (sum[0] / count) as u8,
        (sum[1] / count) as u8,
        (sum[2] / count) as u8,
        (sum[3] / count) as u8,
    ]
}

// Lanczos3 - High-quality sharp downscaling
fn apply_lanczos3(&self, input: &Array3<u8>, region: &Region) -> [u8; 4] {
    let a = 3.0; // Lanczos parameter
    let mut weighted_sum = [0.0f32; 4];
    let mut weight_sum = 0.0f32;
    
    let center_x = (region.x_start + region.x_end) as f32 / 2.0;
    let center_y = (region.y_start + region.y_end) as f32 / 2.0;
    
    for y in (region.y_start - 3)..(region.y_end + 3) {
        for x in (region.x_start - 3)..(region.x_end + 3) {
            if x < 0 || x >= 729 || y < 0 || y >= 729 { continue; }
            
            let dx = (x as f32 - center_x) / 4.5; // Scale to -1..1
            let dy = (y as f32 - center_y) / 4.5;
            let distance = (dx * dx + dy * dy).sqrt();
            
            if distance < a {
                let weight = lanczos_kernel(distance, a);
                weight_sum += weight;
                
                for c in 0..4 {
                    weighted_sum[c] += input[(y as usize, x as usize, c)] as f32 * weight;
                }
            }
        }
    }
    
    [
        (weighted_sum[0] / weight_sum).clamp(0.0, 255.0) as u8,
        (weighted_sum[1] / weight_sum).clamp(0.0, 255.0) as u8,
        (weighted_sum[2] / weight_sum).clamp(0.0, 255.0) as u8,
        (weighted_sum[3] / weight_sum).clamp(0.0, 255.0) as u8,
    ]
}

// Edge-Preserving Filter (Bilateral-like)
fn apply_edge_preserve(&self, input: &Array3<u8>, region: &Region) -> [u8; 4] {
    let center_x = (region.x_start + region.x_end) / 2;
    let center_y = (region.y_start + region.y_end) / 2;
    let center_color = [
        input[(center_y, center_x, 0)] as f32,
        input[(center_y, center_x, 1)] as f32,
        input[(center_y, center_x, 2)] as f32,
    ];
    
    let mut weighted_sum = [0.0f32; 4];
    let mut weight_sum = 0.0f32;
    
    for y in region.y_start..region.y_end {
        for x in region.x_start..region.x_end {
            let color = [
                input[(y, x, 0)] as f32,
                input[(y, x, 1)] as f32,
                input[(y, x, 2)] as f32,
            ];
            
            // Spatial weight (Gaussian)
            let dx = (x - center_x) as f32;
            let dy = (y - center_y) as f32;
            let spatial_weight = (-0.5 * (dx * dx + dy * dy) / 9.0).exp();
            
            // Color weight (similarity)
            let color_dist = ((color[0] - center_color[0]).powi(2) +
                             (color[1] - center_color[1]).powi(2) +
                             (color[2] - center_color[2]).powi(2)).sqrt();
            let color_weight = (-0.5 * color_dist / 50.0).exp();
            
            let weight = spatial_weight * color_weight;
            weight_sum += weight;
            
            for c in 0..4 {
                weighted_sum[c] += input[(y, x, c)] as f32 * weight;
            }
        }
    }
    
    [
        (weighted_sum[0] / weight_sum).clamp(0.0, 255.0) as u8,
        (weighted_sum[1] / weight_sum).clamp(0.0, 255.0) as u8,
        (weighted_sum[2] / weight_sum).clamp(0.0, 255.0) as u8,
        (weighted_sum[3] / weight_sum).clamp(0.0, 255.0) as u8,
    ]
}
```

### Alpha Channel Learning

```rust
pub struct AlphaNet {
    // Learn which pixels are important for the final image
    pub attention_conv: Conv2d,  // Attention mechanism
    pub refinement_conv: Conv2d, // Alpha refinement
    pub memory: Vec<Array2<f32>>, // Temporal memory (81 frames)
}

impl AlphaNet {
    pub fn refine_alpha(
        &mut self,
        features: &Array3<f32>,
        input: &Array4<u8>,
    ) -> Result<Array2<f32>, M2Error> {
        // 1. Calculate base alpha from input
        let base_alpha = self.extract_base_alpha(input)?;
        
        // 2. Apply attention to find important regions
        let attention = self.attention_conv.forward(features)?;
        
        // 3. Refine alpha based on temporal consistency
        let refined = if !self.memory.is_empty() {
            let temporal_alpha = self.temporal_smoothing(&base_alpha)?;
            self.refinement_conv.forward(&[base_alpha, attention, temporal_alpha].concat())?
        } else {
            self.refinement_conv.forward(&[base_alpha, attention].concat())?
        };
        
        // 4. Update memory
        self.memory.push(refined.clone());
        if self.memory.len() > 81 {
            self.memory.remove(0);
        }
        
        Ok(refined)
    }
}
```

### Color Preservation Strategy

```rust
pub struct ColorNet {
    // Preserve color relationships during downscaling
    pub encoder: ColorEncoder,     // Encode color relationships
    pub decoder: ColorDecoder,     // Decode to palette hints
}

impl ColorNet {
    pub fn analyze_colors(&self, downscaled: &Array3<u8>) -> Vec<[u8; 3]> {
        // 1. Convert to Oklab for perceptual analysis
        let oklab = self.to_oklab(downscaled);
        
        // 2. Find perceptually distinct colors
        let clusters = self.cluster_colors(&oklab, 256);
        
        // 3. Weight by visual importance
        let weighted = self.weight_by_importance(&clusters);
        
        // 4. Convert back to sRGB
        weighted.iter()
            .map(|c| self.oklab_to_srgb(c))
            .collect()
    }
}
```

### Output Format

```rust
// M2 output format for M3 consumption
pub struct M2Output {
    // Core data
    pub rgba_81x81: Vec<u8>,          // 26,244 bytes (81×81×4)
    
    // Neural network outputs
    pub kernel_map: Vec<u8>,          // 6,561 bytes (81×81)
    pub alpha_map: Vec<f32>,          // 26,244 bytes (81×81×4 bytes)
    pub importance_map: Vec<f32>,     // 26,244 bytes
    
    // Color hints for M3
    pub suggested_palette: Vec<[u8; 3]>, // Up to 256 colors
    pub color_distribution: ColorDistribution,
    
    // Metadata
    pub frame_index: u16,
    pub processing_ms: u32,
    pub quality_score: f32,           // 0.0-1.0
}

impl M2Output {
    pub fn to_cbor(&self) -> Result<Vec<u8>, cbor::Error> {
        // Efficient CBOR encoding with compression
        let mut encoder = Encoder::new(Vec::new());
        
        encoder.encode(cbor!({
            "v" => 2,
            "idx" => self.frame_index,
            "rgba" => Value::Bytes(self.rgba_81x81.clone()),
            "kernels" => Value::Bytes(self.kernel_map.clone()),
            "alpha" => Value::Array(self.alpha_map.iter().map(|&v| Value::Float(v)).collect()),
            "importance" => Value::Array(self.importance_map.iter().map(|&v| Value::Float(v)).collect()),
            "palette" => self.suggested_palette.iter().map(|c| cbor!(c)).collect(),
            "time_ms" => self.processing_ms,
            "quality" => self.quality_score,
        }))?;
        
        Ok(encoder.into_writer())
    }
}
```

### Integration with M1 and M3

```rust
// UniFFI interface
#[uniffi::export]
pub fn m2_process_frame(
    m1_cbor_data: Vec<u8>,
    frame_index: u16,
    use_neural: bool,
) -> Result<Vec<u8>, M2Error> {
    // Decode M1 CBOR
    let m1_frame = CborFrameV2::from_cbor(&m1_cbor_data)?;
    
    // Process with neural downscaler
    let mut downscaler = get_or_create_downscaler()?;
    let m2_data = if use_neural {
        downscaler.process_frame(&m1_frame)?
    } else {
        // Fallback to Lanczos3 for all pixels
        fallback_lanczos3(&m1_frame)?
    };
    
    // Encode result
    m2_data.to_cbor()
}
```

### Quality Metrics

```rust
pub struct M2QualityMetrics {
    pub psnr: f32,                    // Peak Signal-to-Noise Ratio
    pub ssim: f32,                    // Structural Similarity Index
    pub delta_e: f32,                 // Color difference in Oklab
    pub edge_preservation: f32,       // Edge quality score
    pub temporal_consistency: f32,    // Frame-to-frame stability
}

impl M2QualityMetrics {
    pub fn calculate(original: &Array3<u8>, downscaled: &Array3<u8>) -> Self {
        // Implement quality metrics
        Self {
            psnr: calculate_psnr(original, downscaled),
            ssim: calculate_ssim(original, downscaled),
            delta_e: calculate_delta_e_oklab(original, downscaled),
            edge_preservation: calculate_edge_score(original, downscaled),
            temporal_consistency: 0.0, // Set by temporal analyzer
        }
    }
}
```

### Performance Optimizations

1. **SIMD Acceleration**: Use AVX2/NEON for kernel operations
2. **GPU Offload**: Metal/Vulkan for neural network inference
3. **Temporal Caching**: Reuse computations across frames
4. **Adaptive Quality**: Reduce quality for real-time preview

### Success Metrics

- **Color Accuracy**: ΔE < 2.0 in Oklab space
- **Edge Preservation**: > 0.9 correlation for edges
- **Processing Time**: < 100ms per frame (not critical)
- **Temporal Stability**: < 1% flicker between frames
- **Compression Ratio**: 81:1 spatial (729²/81² = 81)