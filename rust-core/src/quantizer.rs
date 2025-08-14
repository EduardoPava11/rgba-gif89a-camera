use anyhow::{Result, anyhow};
use palette::{Srgb, Lab, FromColor, IntoColor};
use std::collections::HashMap;

/// Alpha-aware quantizer with palette memory
pub struct AlphaAwareQuantizer {
    use_global_palette: bool,
    allow_palette_growth: bool,
    current_palette: Vec<[u8; 3]>,
    palette_usage: Vec<u32>,
    max_colors: usize,
    new_colors_per_frame: usize,
}

impl AlphaAwareQuantizer {
    pub fn new(use_global_palette: bool, allow_palette_growth: bool) -> Self {
        Self {
            use_global_palette,
            allow_palette_growth,
            current_palette: Vec::new(),
            palette_usage: Vec::new(),
            max_colors: 256,
            new_colors_per_frame: 16,
        }
    }
    
    /// Quantize frame with alpha awareness
    pub fn quantize_frame(
        &mut self,
        rgb_data: &[u8],
        alpha_data: &[u8],
        size: u32,
    ) -> Result<(Vec<u8>, Vec<[u8; 3]>, Vec<u8>, Vec<u8>)> {
        let pixel_count = (size * size) as usize;
        
        // Validate input
        if rgb_data.len() != pixel_count * 3 {
            return Err(anyhow!("Invalid RGB data size"));
        }
        if alpha_data.len() != pixel_count {
            return Err(anyhow!("Invalid alpha data size"));
        }
        
        // Build or update palette
        let palette = if self.current_palette.is_empty() {
            self.build_initial_palette(rgb_data, alpha_data)?
        } else if self.allow_palette_growth {
            self.update_palette(rgb_data, alpha_data)?
        } else {
            self.current_palette.clone()
        };
        
        // Map pixels to palette indices
        let (indexed, error_map) = self.map_to_palette(rgb_data, alpha_data, &palette);
        
        // Calculate usage statistics
        let usage_map = self.calculate_usage(&indexed, pixel_count);
        
        // Update state
        self.current_palette = palette.clone();
        
        Ok((indexed, palette, error_map, usage_map))
    }
    
    /// Build initial palette with alpha-weighted sampling
    fn build_initial_palette(
        &mut self,
        rgb_data: &[u8],
        alpha_data: &[u8],
    ) -> Result<Vec<[u8; 3]>> {
        let mut weighted_colors = Vec::new();
        let pixel_count = alpha_data.len();
        
        // Sample colors weighted by alpha
        for i in 0..pixel_count {
            let alpha = alpha_data[i] as f32 / 255.0;
            let weight = alpha.powf(0.7); // Gamma for alpha weighting
            
            if weight > 0.1 {
                let r = rgb_data[i * 3];
                let g = rgb_data[i * 3 + 1];
                let b = rgb_data[i * 3 + 2];
                weighted_colors.push(([r, g, b], weight));
            }
        }
        
        // Build palette using weighted k-means
        let palette = self.weighted_kmeans(&weighted_colors, self.max_colors.min(256))?;
        
        // Initialize usage tracking
        self.palette_usage = vec![0; palette.len()];
        
        Ok(palette)
    }
    
    /// Update palette with new colors
    fn update_palette(
        &mut self,
        rgb_data: &[u8],
        alpha_data: &[u8],
    ) -> Result<Vec<[u8; 3]>> {
        // Keep most-used colors from previous palette
        let mut palette_with_usage: Vec<_> = self.current_palette.iter()
            .zip(self.palette_usage.iter())
            .map(|(color, &usage)| (*color, usage))
            .collect();
        
        // Sort by usage
        palette_with_usage.sort_by_key(|&(_, usage)| std::cmp::Reverse(usage));
        
        // Keep top colors
        let keep_count = self.max_colors - self.new_colors_per_frame;
        let mut new_palette: Vec<[u8; 3]> = palette_with_usage
            .iter()
            .take(keep_count)
            .map(|(color, _)| *color)
            .collect();
        
        // Add new colors from current frame
        let mut new_color_candidates = Vec::new();
        let pixel_count = alpha_data.len();
        
        for i in 0..pixel_count {
            let alpha = alpha_data[i] as f32 / 255.0;
            if alpha > 0.5 {
                let r = rgb_data[i * 3];
                let g = rgb_data[i * 3 + 1];
                let b = rgb_data[i * 3 + 2];
                
                // Check if color is far from existing palette
                if self.is_novel_color([r, g, b], &new_palette) {
                    new_color_candidates.push(([r, g, b], alpha));
                }
            }
        }
        
        // Add most important new colors
        if !new_color_candidates.is_empty() {
            let new_colors = self.weighted_kmeans(
                &new_color_candidates,
                self.new_colors_per_frame.min(new_color_candidates.len()),
            )?;
            
            new_palette.extend(new_colors);
        }
        
        // Reset usage for new palette
        self.palette_usage = vec![0; new_palette.len()];
        
        Ok(new_palette)
    }
    
    /// Check if color is novel compared to palette
    fn is_novel_color(&self, color: [u8; 3], palette: &[[u8; 3]]) -> bool {
        let threshold = 30.0; // Lab color distance threshold
        
        let lab_color = Lab::from_color(
            Srgb::new(
                color[0] as f32 / 255.0,
                color[1] as f32 / 255.0,
                color[2] as f32 / 255.0,
            )
        );
        
        for palette_color in palette {
            let lab_palette = Lab::from_color(
                Srgb::new(
                    palette_color[0] as f32 / 255.0,
                    palette_color[1] as f32 / 255.0,
                    palette_color[2] as f32 / 255.0,
                )
            );
            
            let distance = color_distance_lab(&lab_color, &lab_palette);
            if distance < threshold {
                return false;
            }
        }
        
        true
    }
    
    /// Map pixels to palette with alpha-weighted dithering
    fn map_to_palette(
        &mut self,
        rgb_data: &[u8],
        alpha_data: &[u8],
        palette: &[[u8; 3]],
    ) -> (Vec<u8>, Vec<u8>) {
        let pixel_count = alpha_data.len();
        let mut indexed = Vec::with_capacity(pixel_count);
        let mut error_map = Vec::with_capacity(pixel_count);
        
        // Convert palette to Lab for better color matching
        let palette_lab: Vec<Lab> = palette.iter()
            .map(|&[r, g, b]| {
                Lab::from_color(Srgb::new(
                    r as f32 / 255.0,
                    g as f32 / 255.0,
                    b as f32 / 255.0,
                ))
            })
            .collect();
        
        for i in 0..pixel_count {
            let r = rgb_data[i * 3];
            let g = rgb_data[i * 3 + 1];
            let b = rgb_data[i * 3 + 2];
            let alpha = alpha_data[i] as f32 / 255.0;
            
            // Convert to Lab
            let pixel_lab = Lab::from_color(
                Srgb::new(
                    r as f32 / 255.0,
                    g as f32 / 255.0,
                    b as f32 / 255.0,
                )
            );
            
            // Find nearest palette color
            let (best_idx, distance) = palette_lab.iter()
                .enumerate()
                .map(|(idx, &palette_color)| {
                    let dist = color_distance_lab(&pixel_lab, &palette_color);
                    // Weight distance by alpha (high alpha = more important to match well)
                    let weighted_dist = dist * (2.0 - alpha);
                    (idx, weighted_dist)
                })
                .min_by(|a, b| a.1.partial_cmp(&b.1).unwrap())
                .unwrap();
            
            indexed.push(best_idx as u8);
            
            // Update usage
            if best_idx < self.palette_usage.len() {
                self.palette_usage[best_idx] += 1;
            }
            
            // Calculate quantization error (0-255 scale)
            let error = (distance.min(100.0) * 2.55) as u8;
            error_map.push(error);
        }
        
        (indexed, error_map)
    }
    
    /// Calculate usage heatmap
    fn calculate_usage(&self, indexed: &[u8], pixel_count: usize) -> Vec<u8> {
        let mut usage_map = vec![0u8; pixel_count];
        
        // Find max usage for normalization
        let max_usage = *self.palette_usage.iter().max().unwrap_or(&1) as f32;
        
        for i in 0..pixel_count {
            let palette_idx = indexed[i] as usize;
            if palette_idx < self.palette_usage.len() {
                let usage = self.palette_usage[palette_idx] as f32;
                usage_map[i] = ((usage / max_usage) * 255.0) as u8;
            }
        }
        
        usage_map
    }
    
    /// Weighted k-means clustering for palette generation
    fn weighted_kmeans(
        &self,
        weighted_colors: &[([u8; 3], f32)],
        k: usize,
    ) -> Result<Vec<[u8; 3]>> {
        if weighted_colors.is_empty() {
            return Ok(vec![]);
        }
        
        let k = k.min(weighted_colors.len());
        
        // Initialize centers with k++ algorithm
        let mut centers = vec![];
        centers.push(weighted_colors[0].0);
        
        for _ in 1..k {
            let mut distances = vec![];
            for &(color, weight) in weighted_colors {
                let min_dist = centers.iter()
                    .map(|&center| color_distance_rgb(&color, &center))
                    .min_by(|a, b| a.partial_cmp(b).unwrap())
                    .unwrap();
                distances.push(min_dist * weight);
            }
            
            // Choose next center with probability proportional to weighted distance
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
            let mut clusters: Vec<Vec<([u8; 3], f32)>> = vec![vec![]; k];
            
            for &(color, weight) in weighted_colors {
                let nearest = centers.iter()
                    .enumerate()
                    .min_by_key(|(_, &center)| {
                        (color_distance_rgb(&color, &center) * 1000.0) as u32
                    })
                    .unwrap()
                    .0;
                clusters[nearest].push((color, weight));
            }
            
            // Update centers
            for (i, cluster) in clusters.iter().enumerate() {
                if !cluster.is_empty() {
                    let total_weight: f32 = cluster.iter().map(|&(_, w)| w).sum();
                    let mut r_sum = 0.0;
                    let mut g_sum = 0.0;
                    let mut b_sum = 0.0;
                    
                    for &([r, g, b], weight) in cluster {
                        r_sum += r as f32 * weight;
                        g_sum += g as f32 * weight;
                        b_sum += b as f32 * weight;
                    }
                    
                    centers[i] = [
                        (r_sum / total_weight) as u8,
                        (g_sum / total_weight) as u8,
                        (b_sum / total_weight) as u8,
                    ];
                }
            }
        }
        
        Ok(centers)
    }
}

/// Calculate color distance in Lab space
fn color_distance_lab(a: &Lab, b: &Lab) -> f32 {
    let dl = a.l - b.l;
    let da = a.a - b.a;
    let db = a.b - b.b;
    (dl * dl + da * da + db * db).sqrt()
}

/// Calculate color distance in RGB space
fn color_distance_rgb(a: &[u8; 3], b: &[u8; 3]) -> f32 {
    let dr = a[0] as f32 - b[0] as f32;
    let dg = a[1] as f32 - b[1] as f32;
    let db = a[2] as f32 - b[2] as f32;
    (dr * dr + dg * dg + db * db).sqrt()
}

// Stub for random number generation (replace with proper RNG)
mod rand {
    pub fn random<T>() -> T
    where
        T: Default,
    {
        T::default()
    }
}