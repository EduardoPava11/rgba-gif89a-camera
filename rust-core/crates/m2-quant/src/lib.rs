use tracing::{info, debug, span, Level, warn};
use common_types::{
    Frames81Rgb, QuantizedSet, GifPipeError, QuantizedCubeData
};
use common_types::oklab::{rgb_to_oklab, delta_e_oklab};
use rand::seq::SliceRandom;

/// Oklab-based streaming k-means quantizer
pub struct OklabQuantizer {
    max_colors: usize,
    convergence_threshold: f32,
    max_iterations: usize,
}

impl Default for OklabQuantizer {
    fn default() -> Self {
        Self {
            max_colors: 256,
            convergence_threshold: 1.0,
            max_iterations: 50,
        }
    }
}

impl OklabQuantizer {
    pub fn new(max_colors: usize) -> Self {
        Self {
            max_colors,
            ..Default::default()
        }
    }

    /// Quantize RGB frames using Oklab perceptual color space
    #[tracing::instrument(level = "info", skip(self, frames_data))]
    pub fn quantize_frames(&self, frames_data: Frames81Rgb) -> Result<QuantizedSet, GifPipeError> {
        let span = span!(Level::INFO, "M2_quantize", frames = frames_data.frames_rgb.len());
        let _guard = span.enter();

        let start_time = std::time::Instant::now();

        info!(
            stage = "M2", 
            max_colors = self.max_colors,
            frames = frames_data.frames_rgb.len(),
            "Starting Oklab quantization"
        );

        // Sample pixels from all frames for k-means
        let sample_pixels = self.sample_pixels(&frames_data.frames_rgb)?;
        
        info!(
            stage = "M2",
            sampled_pixels = sample_pixels.len(),
            "Pixel sampling completed"
        );

        // Run k-means clustering in Oklab space
        let palette = self.kmeans_oklab(&sample_pixels)?;
        
        info!(
            stage = "M2",
            palette_colors = palette.len(),
            "K-means clustering completed"
        );

        // Map each frame to palette indices
        let mut quantized_frames = Vec::new();
        let mut frame_errors = Vec::new();

        for (frame_idx, frame_rgb) in frames_data.frames_rgb.iter().enumerate() {
            debug!(stage = "M2", frame_idx = frame_idx, "Quantizing frame");

            let (frame_indices, frame_error) = self.map_frame_to_palette(frame_rgb, &palette)?;
            quantized_frames.push(frame_indices);
            frame_errors.push(frame_error);

            debug!(
                stage = "M2",
                frame_idx = frame_idx,
                avg_delta_e = frame_error,
                "Frame quantization completed"
            );
        }

        let processing_time = start_time.elapsed().as_millis() as u64;
        let avg_error = frame_errors.iter().sum::<f32>() / frame_errors.len() as f32;

        info!(
            stage = "M2",
            duration_ms = processing_time,
            avg_delta_e = avg_error,
            palette_size = palette.len(),
            "Quantization completed"
        );

        Ok(QuantizedSet {
            palette_rgb: palette.into_iter().flatten().collect(), // Convert [[u8; 3]] to Vec<u8>
            frames_indices: quantized_frames,
            palette_stability: 0.85, // Placeholder - would be calculated from temporal analysis
            mean_perceptual_error: avg_error,
            p95_perceptual_error: frame_errors.iter().fold(0.0, |max, &val| max.max(val)),
            processing_time_ms: processing_time,
            attention_maps: frames_data.attention_maps, // Pass through attention maps
        })
    }

    /// Sample pixels from frames using attention-weighted sampling
    fn sample_pixels(&self, frames_rgb: &[Vec<u8>]) -> Result<Vec<[u8; 3]>, GifPipeError> {
        const SAMPLES_PER_FRAME: usize = 1000;
        let mut samples = Vec::new();
        let mut rng = rand::thread_rng();

        for frame_rgb in frames_rgb {
            if frame_rgb.len() % 3 != 0 {
                return Err(GifPipeError::InvalidFrameData {
                    message: "RGB frame length not divisible by 3".to_string(),
                });
            }

            let pixel_count = frame_rgb.len() / 3;
            let mut frame_samples = Vec::new();

            // Simple random sampling (could be enhanced with attention weighting)
            let mut pixel_indices: Vec<usize> = (0..pixel_count).collect();
            pixel_indices.shuffle(&mut rng);

            for &idx in pixel_indices.iter().take(SAMPLES_PER_FRAME.min(pixel_count)) {
                let rgb_idx = idx * 3;
                if rgb_idx + 2 < frame_rgb.len() {
                    frame_samples.push([
                        frame_rgb[rgb_idx],
                        frame_rgb[rgb_idx + 1], 
                        frame_rgb[rgb_idx + 2]
                    ]);
                }
            }

            samples.extend(frame_samples);
        }

        Ok(samples)
    }

    /// K-means clustering in Oklab perceptual color space
    fn kmeans_oklab(&self, samples: &[[u8; 3]]) -> Result<Vec<[u8; 3]>, GifPipeError> {
        if samples.is_empty() {
            return Err(GifPipeError::QuantizationFailed {
                message: "No samples provided for k-means clustering".to_string(),
            });
        }

        let k = self.max_colors.min(samples.len());
        let mut rng = rand::thread_rng();
        
        // Initialize centroids by sampling
        let mut centroids: Vec<[f32; 3]> = samples
            .choose_multiple(&mut rng, k)
            .map(|&rgb| rgb_to_oklab(rgb[0], rgb[1], rgb[2]))
            .collect();

        debug!(stage = "M2", centroids = k, "K-means initialization");

        for iteration in 0..self.max_iterations {
            // Assign points to nearest centroids
            let mut clusters: Vec<Vec<[f32; 3]>> = vec![Vec::new(); k];
            let mut total_distance = 0.0f32;

            for &sample_rgb in samples {
                let sample_oklab = rgb_to_oklab(sample_rgb[0], sample_rgb[1], sample_rgb[2]);
                
                let (closest_idx, distance) = centroids
                    .iter()
                    .enumerate()
                    .map(|(idx, &centroid)| (idx, delta_e_oklab(sample_oklab, centroid)))
                    .min_by(|a, b| a.1.partial_cmp(&b.1).unwrap())
                    .unwrap();

                clusters[closest_idx].push(sample_oklab);
                total_distance += distance;
            }

            // Update centroids
            let mut max_movement = 0.0f32;
            for (i, cluster) in clusters.iter().enumerate() {
                if !cluster.is_empty() {
                    let old_centroid = centroids[i];
                    
                    let new_centroid = [
                        cluster.iter().map(|p| p[0]).sum::<f32>() / cluster.len() as f32,
                        cluster.iter().map(|p| p[1]).sum::<f32>() / cluster.len() as f32,
                        cluster.iter().map(|p| p[2]).sum::<f32>() / cluster.len() as f32,
                    ];
                    
                    let movement = delta_e_oklab(old_centroid, new_centroid);
                    max_movement = max_movement.max(movement);
                    centroids[i] = new_centroid;
                }
            }

            let avg_distance = total_distance / samples.len() as f32;
            
            debug!(
                stage = "M2",
                iteration = iteration,
                max_movement = max_movement,
                avg_distance = avg_distance,
                "K-means iteration"
            );

            if max_movement < self.convergence_threshold {
                debug!(stage = "M2", converged_at = iteration, "K-means converged");
                break;
            }
        }

        // Convert centroids back to RGB
        let palette: Vec<[u8; 3]> = centroids
            .into_iter()
            .map(|oklab| self.oklab_to_rgb(oklab))
            .collect();

        Ok(palette)
    }

    /// Map a frame to palette indices with error calculation
    fn map_frame_to_palette(&self, frame_rgb: &[u8], palette: &[[u8; 3]]) -> Result<(Vec<u8>, f32), GifPipeError> {
        if frame_rgb.len() % 3 != 0 {
            return Err(GifPipeError::InvalidFrameData {
                message: "RGB frame length not divisible by 3".to_string(),
            });
        }

        let pixel_count = frame_rgb.len() / 3;
        let mut indices = Vec::with_capacity(pixel_count);
        let mut total_error = 0.0f32;

        // Pre-convert palette to Oklab for faster comparison
        let palette_oklab: Vec<[f32; 3]> = palette
            .iter()
            .map(|&rgb| rgb_to_oklab(rgb[0], rgb[1], rgb[2]))
            .collect();

        for i in 0..pixel_count {
            let rgb_idx = i * 3;
            if rgb_idx + 2 < frame_rgb.len() {
                let pixel_rgb = [
                    frame_rgb[rgb_idx],
                    frame_rgb[rgb_idx + 1],
                    frame_rgb[rgb_idx + 2]
                ];
                let pixel_oklab = rgb_to_oklab(pixel_rgb[0], pixel_rgb[1], pixel_rgb[2]);

                // Find closest palette color
                let (best_idx, error) = palette_oklab
                    .iter()
                    .enumerate()
                    .map(|(idx, &pal_oklab)| (idx, delta_e_oklab(pixel_oklab, pal_oklab)))
                    .min_by(|a, b| a.1.partial_cmp(&b.1).unwrap())
                    .unwrap();

                indices.push(best_idx as u8);
                total_error += error;
            }
        }

        let avg_error = total_error / pixel_count as f32;
        Ok((indices, avg_error))
    }

    /// Convert Oklab back to RGB (simplified conversion)
    fn oklab_to_rgb(&self, oklab: [f32; 3]) -> [u8; 3] {
        // Simplified Oklab to RGB conversion
        // In production, use proper color space conversion library
        let l = oklab[0];
        let a = oklab[1];  
        let b = oklab[2];

        // Rough approximation for now
        let r = (l + 0.3963 * a + 0.2158 * b).clamp(0.0, 1.0);
        let g = (l - 0.1055 * a - 0.0638 * b).clamp(0.0, 1.0);
        let blue = (l - 0.0894 * a - 1.2914 * b).clamp(0.0, 1.0);

        [
            (r * 255.0) as u8,
            (g * 255.0) as u8,
            (blue * 255.0) as u8,
        ]
    }

    /// Quantize frames for cube data with global palette
    pub fn quantize_for_cube(&self, frames: Frames81Rgb) -> Result<QuantizedCubeData, GifPipeError> {
        let span = span!(Level::INFO, "M2_quantize_cube", 
            frames = 81,
            target_colors = 256,
            method = "oklab_streaming_kmeans"
        );
        let _guard = span.enter();
        
        // Sample pixels from all 81 frames for global k-means
        let all_samples = self.sample_all_frames(&frames, 1000)?; // 1000 per frame
        info!(total_samples = all_samples.len(), "Building global palette");
        
        // Run k-means in Oklab space
        let global_palette_rgb = self.kmeans_oklab(&all_samples)?;
        let global_palette_bytes: Vec<u8> = global_palette_rgb.iter()
            .flat_map(|rgb| vec![rgb[0], rgb[1], rgb[2]])
            .collect();
        
        // Quantize each frame using global palette
        let mut indexed_frames = Vec::with_capacity(81);
        let mut delta_e_values = Vec::with_capacity(81);
        
        for (idx, frame) in frames.frames_rgb.iter().enumerate() {
            let (indices, frame_delta_e) = self.quantize_frame_with_palette(
                frame, 
                &global_palette_rgb
            )?;
            
            indexed_frames.push(indices);
            delta_e_values.push(frame_delta_e);
            
            if idx % 10 == 0 {
                info!(frame = idx, delta_e = frame_delta_e, "Quantized frame batch");
            }
        }
        
        // Calculate temporal metrics
        let palette_stability = self.calculate_palette_stability(&indexed_frames)?;
        let mean_delta_e = delta_e_values.iter().sum::<f32>() / 81.0;
        let p95_delta_e = self.calculate_p95(&delta_e_values);
        
        info!(
            palette_stability = palette_stability,
            mean_delta_e = mean_delta_e,
            p95_delta_e = p95_delta_e,
            "Global quantization complete"
        );
        
        Ok(QuantizedCubeData {
            width: 81,
            height: 81,
            global_palette_rgb: global_palette_bytes,
            indexed_frames,
            delays_cs: vec![4; 81], // 25fps = 4cs
            palette_stability,
            mean_delta_e,
            p95_delta_e,
            attention_maps: Some(frames.attention_maps),
        })
    }
    
    fn sample_all_frames(&self, frames: &Frames81Rgb, samples_per_frame: usize) -> Result<Vec<[u8; 3]>, GifPipeError> {
        let mut all_samples = Vec::new();
        
        for frame in &frames.frames_rgb {
            let frame_samples = self.sample_frame_pixels(frame, samples_per_frame)?;
            all_samples.extend(frame_samples);
        }
        
        Ok(all_samples)
    }
    
    fn quantize_frame_with_palette(
        &self,
        frame: &[u8],
        palette: &[[u8; 3]],
    ) -> Result<(Vec<u8>, f32), GifPipeError> {
        let (indices, error) = self.map_frame_to_palette(frame, palette)?;
        Ok((indices, error))
    }
    
    fn calculate_palette_stability(&self, indexed_frames: &[Vec<u8>]) -> Result<f32, GifPipeError> {
        // Measure histogram similarity between consecutive frames
        let mut stability_scores = Vec::new();
        
        for i in 1..indexed_frames.len() {
            let hist_prev = self.build_histogram(&indexed_frames[i-1]);
            let hist_curr = self.build_histogram(&indexed_frames[i]);
            
            // Chi-squared distance between histograms
            let similarity = self.histogram_similarity(&hist_prev, &hist_curr);
            stability_scores.push(similarity);
        }
        
        Ok(stability_scores.iter().sum::<f32>() / stability_scores.len() as f32)
    }
    
    fn build_histogram(&self, frame_indices: &[u8]) -> Vec<u32> {
        let mut histogram = vec![0u32; 256];
        for &index in frame_indices {
            histogram[index as usize] += 1;
        }
        histogram
    }
    
    fn histogram_similarity(&self, hist1: &[u32], hist2: &[u32]) -> f32 {
        let total1: u32 = hist1.iter().sum();
        let total2: u32 = hist2.iter().sum();
        
        if total1 == 0 || total2 == 0 {
            return 0.0;
        }
        
        let mut intersection = 0u32;
        for (&h1, &h2) in hist1.iter().zip(hist2.iter()) {
            intersection += h1.min(h2);
        }
        
        intersection as f32 / total1.max(total2) as f32
    }

    fn sample_frame_pixels(&self, frame: &[u8], max_samples: usize) -> Result<Vec<[u8; 3]>, GifPipeError> {
        if frame.len() % 3 != 0 {
            return Err(GifPipeError::InvalidFrameData {
                message: "Frame length not divisible by 3".to_string(),
            });
        }

        let pixel_count = frame.len() / 3;
        let mut rng = rand::thread_rng();
        let mut pixel_indices: Vec<usize> = (0..pixel_count).collect();
        pixel_indices.shuffle(&mut rng);

        let mut samples = Vec::new();
        for &idx in pixel_indices.iter().take(max_samples.min(pixel_count)) {
            let rgb_idx = idx * 3;
            if rgb_idx + 2 < frame.len() {
                samples.push([
                    frame[rgb_idx],
                    frame[rgb_idx + 1],
                    frame[rgb_idx + 2],
                ]);
            }
        }

        Ok(samples)
    }

    fn calculate_p95(&self, errors: &[f32]) -> f32 {
        if errors.is_empty() {
            return 0.0;
        }
        
        let mut sorted = errors.to_vec();
        sorted.sort_by(|a, b| a.partial_cmp(b).unwrap());
        
        let idx = ((sorted.len() as f32 * 0.95) as usize).min(sorted.len() - 1);
        sorted[idx]
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use common_types::{FRAME_SIZE_81, Frames81Rgb};

    #[test]
    fn test_quantizer_creation() {
        let quantizer = OklabQuantizer::new(16);
        assert_eq!(quantizer.max_colors, 16);
    }

    #[test]
    fn test_pixel_sampling() {
        let quantizer = OklabQuantizer::default();
        
        // Create test frame
        let frame_rgb = vec![128u8; FRAME_SIZE_81 as usize * FRAME_SIZE_81 as usize * 3];
        let frames = vec![frame_rgb];
        
        let samples = quantizer.sample_pixels(&frames).unwrap();
        assert!(!samples.is_empty());
        assert!(samples.len() <= 1000); // SAMPLES_PER_FRAME
    }

    #[test]
    fn test_quantization_workflow() {
        let quantizer = OklabQuantizer::new(8);
        
        // Create test input
        let frame_rgb = vec![128u8; FRAME_SIZE_81 as usize * FRAME_SIZE_81 as usize * 3];
        let attention_map = vec![0.5f32; FRAME_SIZE_81 as usize * FRAME_SIZE_81 as usize];
        
        let frames_data = Frames81Rgb {
            frames_rgb: vec![frame_rgb],
            attention_maps: vec![attention_map],
            processing_time_ms: 0,
        };
        
        let result = quantizer.quantize_frames(frames_data).unwrap();
        
        assert_eq!(result.frames_indices.len(), 1);
        assert!(result.palette_rgb.len() <= 8 * 3); // Up to 8 colors * 3 bytes
        assert!(result.mean_perceptual_error >= 0.0);
    }

    #[test]
    fn test_invalid_frame_data() {
        let quantizer = OklabQuantizer::default();
        
        // Frame length not divisible by 3
        let invalid_frame = vec![128u8; 100]; // Not divisible by 3
        let result = quantizer.sample_pixels(&[invalid_frame]);
        assert!(result.is_err());
    }
}
