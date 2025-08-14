/// Oklab-based quantizer with alpha-aware sampling and Floyd-Steinberg dithering
use palette::{Srgb, Lab, Oklab, FromColor, IntoColor};
use anyhow::{Result, anyhow};

/// Oklab color space quantizer
pub struct OklabQuantizer {
    max_colors: usize,
    new_colors_per_frame: usize,
    dither_strength: f32,
}

impl OklabQuantizer {
    pub fn new() -> Self {
        Self {
            max_colors: 256,
            new_colors_per_frame: 16,
            dither_strength: 0.35,
        }
    }
    
    /// Build initial palette with alpha-weighted sampling
    pub fn build_initial_palette(
        &self,
        rgb_data: &[u8],
        alpha_map: &[f32],
        size: usize,
    ) -> Result<Vec<[u8; 3]>> {
        // Convert to Oklab and weight by alpha
        let mut weighted_colors = Vec::new();
        
        for i in 0..size * size {
            let alpha = alpha_map[i];
            let weight = alpha.powf(0.7); // γ = 0.7 for alpha weighting
            
            if weight > 0.1 {
                let r = rgb_data[i * 3] as f32 / 255.0;
                let g = rgb_data[i * 3 + 1] as f32 / 255.0;
                let b = rgb_data[i * 3 + 2] as f32 / 255.0;
                
                // Convert to Oklab
                let srgb = Srgb::new(r, g, b);
                let oklab: Oklab = Oklab::from_color(srgb);
                
                weighted_colors.push((oklab, weight));
            }
        }
        
        // K-means in Oklab space
        let palette_oklab = self.kmeans_oklab(&weighted_colors, self.max_colors)?;
        
        // Convert back to RGB
        let palette: Vec<[u8; 3]> = palette_oklab
            .iter()
            .map(|&oklab| {
                let srgb: Srgb = Srgb::from_color(oklab);
                [
                    (srgb.red * 255.0) as u8,
                    (srgb.green * 255.0) as u8,
                    (srgb.blue * 255.0) as u8,
                ]
            })
            .collect();
        
        Ok(palette)
    }
    
    /// Update palette with warm-start and new color admission
    pub fn update_palette(
        &self,
        rgb_data: &[u8],
        alpha_map: &[f32],
        previous_palette: &[[u8; 3]],
        size: usize,
    ) -> Result<Vec<[u8; 3]>> {
        // Keep most-used colors from previous palette (simplified for now)
        let keep_count = self.max_colors - self.new_colors_per_frame;
        let mut new_palette: Vec<[u8; 3]> = previous_palette
            .iter()
            .take(keep_count.min(previous_palette.len()))
            .copied()
            .collect();
        
        // Sample new colors with alpha weighting
        let mut new_candidates = Vec::new();
        
        for i in 0..size * size {
            let alpha = alpha_map[i];
            if alpha > 0.5 {
                let r = rgb_data[i * 3] as f32 / 255.0;
                let g = rgb_data[i * 3 + 1] as f32 / 255.0;
                let b = rgb_data[i * 3 + 2] as f32 / 255.0;
                
                let srgb = Srgb::new(r, g, b);
                let oklab: Oklab = Oklab::from_color(srgb);
                
                // Check if color is novel
                if self.is_novel_oklab(oklab, &new_palette) {
                    new_candidates.push((oklab, alpha));
                }
            }
        }
        
        // Add new colors via k-means
        if !new_candidates.is_empty() {
            let new_colors_oklab = self.kmeans_oklab(
                &new_candidates,
                self.new_colors_per_frame.min(new_candidates.len()),
            )?;
            
            for oklab in new_colors_oklab {
                let srgb: Srgb = Srgb::from_color(oklab);
                new_palette.push([
                    (srgb.red * 255.0) as u8,
                    (srgb.green * 255.0) as u8,
                    (srgb.blue * 255.0) as u8,
                ]);
            }
        }
        
        Ok(new_palette)
    }
    
    /// Apply Floyd-Steinberg dithering with alpha scaling
    pub fn apply_dithering(
        &self,
        rgb_data: &[u8],
        indices: &[u8],
        palette: &[[u8; 3]],
        alpha_map: &[f32],
        size: usize,
    ) -> Vec<u8> {
        let mut output = indices.to_vec();
        let mut error_r = vec![0.0f32; size * size];
        let mut error_g = vec![0.0f32; size * size];
        let mut error_b = vec![0.0f32; size * size];
        
        for y in 0..size {
            for x in 0..size {
                let idx = y * size + x;
                let alpha = alpha_map[idx];
                
                // Scale dithering strength by alpha
                let strength = self.dither_strength * alpha;
                
                if strength < 0.01 {
                    continue; // Skip dithering in low-alpha areas
                }
                
                // Current pixel with accumulated error
                let r = (rgb_data[idx * 3] as f32 + error_r[idx]).clamp(0.0, 255.0);
                let g = (rgb_data[idx * 3 + 1] as f32 + error_g[idx]).clamp(0.0, 255.0);
                let b = (rgb_data[idx * 3 + 2] as f32 + error_b[idx]).clamp(0.0, 255.0);
                
                // Find nearest palette color
                let nearest_idx = self.find_nearest_oklab(r, g, b, palette);
                output[idx] = nearest_idx;
                
                // Calculate error
                let palette_color = palette[nearest_idx as usize];
                let err_r = r - palette_color[0] as f32;
                let err_g = g - palette_color[1] as f32;
                let err_b = b - palette_color[2] as f32;
                
                // Distribute error (Floyd-Steinberg coefficients)
                // Scale by strength (which includes alpha)
                if x + 1 < size {
                    let idx_right = y * size + x + 1;
                    error_r[idx_right] += err_r * 7.0/16.0 * strength;
                    error_g[idx_right] += err_g * 7.0/16.0 * strength;
                    error_b[idx_right] += err_b * 7.0/16.0 * strength;
                }
                
                if y + 1 < size {
                    if x > 0 {
                        let idx_bl = (y + 1) * size + x - 1;
                        error_r[idx_bl] += err_r * 3.0/16.0 * strength;
                        error_g[idx_bl] += err_g * 3.0/16.0 * strength;
                        error_b[idx_bl] += err_b * 3.0/16.0 * strength;
                    }
                    
                    let idx_below = (y + 1) * size + x;
                    error_r[idx_below] += err_r * 5.0/16.0 * strength;
                    error_g[idx_below] += err_g * 5.0/16.0 * strength;
                    error_b[idx_below] += err_b * 5.0/16.0 * strength;
                    
                    if x + 1 < size {
                        let idx_br = (y + 1) * size + x + 1;
                        error_r[idx_br] += err_r * 1.0/16.0 * strength;
                        error_g[idx_br] += err_g * 1.0/16.0 * strength;
                        error_b[idx_br] += err_b * 1.0/16.0 * strength;
                    }
                }
            }
        }
        
        output
    }
    
    /// Calculate ΔE in Oklab space
    pub fn calculate_delta_e(
        &self,
        rgb_data: &[u8],
        indices: &[u8],
        palette: &[[u8; 3]],
        size: usize,
    ) -> (f32, f32, Vec<u8>) {
        let mut total_error = 0.0f32;
        let mut max_error = 0.0f32;
        let mut error_map = Vec::with_capacity(size * size);
        
        for i in 0..size * size {
            let original_r = rgb_data[i * 3] as f32 / 255.0;
            let original_g = rgb_data[i * 3 + 1] as f32 / 255.0;
            let original_b = rgb_data[i * 3 + 2] as f32 / 255.0;
            
            let palette_color = palette[indices[i] as usize];
            let quant_r = palette_color[0] as f32 / 255.0;
            let quant_g = palette_color[1] as f32 / 255.0;
            let quant_b = palette_color[2] as f32 / 255.0;
            
            // Convert to Oklab
            let original_oklab: Oklab = Oklab::from_color(Srgb::new(original_r, original_g, original_b));
            let quant_oklab: Oklab = Oklab::from_color(Srgb::new(quant_r, quant_g, quant_b));
            
            // Calculate ΔE (Euclidean distance in Oklab)
            let delta_l = original_oklab.l - quant_oklab.l;
            let delta_a = original_oklab.a - quant_oklab.a;
            let delta_b = original_oklab.b - quant_oklab.b;
            let delta_e = (delta_l * delta_l + delta_a * delta_a + delta_b * delta_b).sqrt();
            
            total_error += delta_e;
            max_error = max_error.max(delta_e);
            
            // Store as u8 (scale to 0-255 range, cap at reasonable max)
            let error_u8 = ((delta_e * 100.0).min(255.0)) as u8;
            error_map.push(error_u8);
        }
        
        let mean_error = total_error / (size * size) as f32;
        
        (mean_error, max_error, error_map)
    }
    
    /// K-means clustering in Oklab space
    fn kmeans_oklab(
        &self,
        weighted_colors: &[(Oklab, f32)],
        k: usize,
    ) -> Result<Vec<Oklab>> {
        if weighted_colors.is_empty() {
            return Ok(vec![]);
        }
        
        let k = k.min(weighted_colors.len());
        
        // Initialize centers with k++ algorithm
        let mut centers = vec![weighted_colors[0].0];
        
        for _ in 1..k {
            let mut distances = Vec::new();
            for &(color, weight) in weighted_colors {
                let min_dist = centers
                    .iter()
                    .map(|&center| self.oklab_distance(color, center))
                    .min_by(|a, b| a.partial_cmp(b).unwrap())
                    .unwrap();
                distances.push(min_dist * weight);
            }
            
            // Choose next center weighted by distance
            let total: f32 = distances.iter().sum();
            let mut cumulative = 0.0;
            let target = rand::random::<f32>() * total;
            
            for (i, &dist) in distances.iter().enumerate() {
                cumulative += dist;
                if cumulative >= target {
                    centers.push(weighted_colors[i].0);
                    break;
                }
            }
        }
        
        // Iterate k-means
        for _ in 0..10 {
            // Assign points to clusters
            let mut clusters: Vec<Vec<(Oklab, f32)>> = vec![vec![]; k];
            
            for &(color, weight) in weighted_colors {
                let nearest = centers
                    .iter()
                    .enumerate()
                    .min_by_key(|(_, &center)| {
                        (self.oklab_distance(color, center) * 1000.0) as u32
                    })
                    .unwrap()
                    .0;
                clusters[nearest].push((color, weight));
            }
            
            // Update centers
            for (i, cluster) in clusters.iter().enumerate() {
                if !cluster.is_empty() {
                    let total_weight: f32 = cluster.iter().map(|&(_, w)| w).sum();
                    let mut l_sum = 0.0;
                    let mut a_sum = 0.0;
                    let mut b_sum = 0.0;
                    
                    for &(color, weight) in cluster {
                        l_sum += color.l * weight;
                        a_sum += color.a * weight;
                        b_sum += color.b * weight;
                    }
                    
                    centers[i] = Oklab {
                        l: l_sum / total_weight,
                        a: a_sum / total_weight,
                        b: b_sum / total_weight,
                    };
                }
            }
        }
        
        Ok(centers)
    }
    
    /// Calculate distance in Oklab space
    fn oklab_distance(&self, a: Oklab, b: Oklab) -> f32 {
        let dl = a.l - b.l;
        let da = a.a - b.a;
        let db = a.b - b.b;
        (dl * dl + da * da + db * db).sqrt()
    }
    
    /// Check if color is novel compared to palette
    fn is_novel_oklab(&self, color: Oklab, palette: &[[u8; 3]]) -> bool {
        let threshold = 0.05; // Oklab distance threshold
        
        for &[r, g, b] in palette {
            let palette_oklab: Oklab = Oklab::from_color(
                Srgb::new(r as f32 / 255.0, g as f32 / 255.0, b as f32 / 255.0)
            );
            
            if self.oklab_distance(color, palette_oklab) < threshold {
                return false;
            }
        }
        
        true
    }
    
    /// Find nearest palette color in Oklab space
    fn find_nearest_oklab(&self, r: f32, g: f32, b: f32, palette: &[[u8; 3]]) -> u8 {
        let target = Oklab::from_color(Srgb::new(r / 255.0, g / 255.0, b / 255.0));
        
        palette
            .iter()
            .enumerate()
            .min_by_key(|(_, &[pr, pg, pb])| {
                let palette_oklab = Oklab::from_color(
                    Srgb::new(pr as f32 / 255.0, pg as f32 / 255.0, pb as f32 / 255.0)
                );
                (self.oklab_distance(target, palette_oklab) * 10000.0) as u32
            })
            .unwrap()
            .0 as u8
    }
}

// Stub for random (replace with proper RNG)
mod rand {
    pub fn random<T>() -> T
    where
        T: Default,
    {
        T::default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_oklab_quantizer() {
        let quantizer = OklabQuantizer::new();
        
        // Create test data
        let size = 81;
        let rgb_data = vec![128u8; size * size * 3];
        let alpha_map = vec![1.0f32; size * size];
        
        // Build initial palette
        let palette = quantizer
            .build_initial_palette(&rgb_data, &alpha_map, size)
            .unwrap();
        
        assert!(!palette.is_empty());
        assert!(palette.len() <= 256);
    }
    
    #[test]
    fn test_delta_e_calculation() {
        let quantizer = OklabQuantizer::new();
        
        let size = 2;
        let rgb_data = vec![255, 0, 0, 0, 255, 0, 0, 0, 255, 255, 255, 255];
        let indices = vec![0, 1, 2, 3];
        let palette = vec![
            [255, 0, 0],
            [0, 255, 0],
            [0, 0, 255],
            [255, 255, 255],
        ];
        
        let (mean_error, max_error, error_map) = 
            quantizer.calculate_delta_e(&rgb_data, &indices, &palette, size);
        
        assert_eq!(mean_error, 0.0); // Perfect match
        assert_eq!(max_error, 0.0);
        assert_eq!(error_map, vec![0, 0, 0, 0]);
    }
}