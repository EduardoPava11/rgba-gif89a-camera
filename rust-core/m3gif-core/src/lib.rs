use anyhow::Result;
use log::info;

pub mod cbor_v2;
pub mod downscale;
pub mod quantize;
pub mod gif_encode;

pub use cbor_v2::*;
pub use downscale::*;
pub use quantize::*;
pub use gif_encode::*;

// Re-export common types
pub type GifCoreResult<T> = Result<T, anyhow::Error>;

#[derive(Debug, Clone)]
pub struct ColorMetrics {
    pub avg_rgb: (f32, f32, f32),
    pub nonzero_ratio: f32,
    pub dominant_colors: Vec<[u8; 3]>,
    pub color_variance: f32,
}

impl ColorMetrics {
    pub fn calculate(rgba_data: &[u8]) -> Self {
        let pixel_count = rgba_data.len() / 4;
        if pixel_count == 0 {
            return Self::default();
        }
        
        let mut r_sum = 0u64;
        let mut g_sum = 0u64; 
        let mut b_sum = 0u64;
        let mut nonzero_count = 0;
        
        for chunk in rgba_data.chunks_exact(4) {
            r_sum += chunk[0] as u64;
            g_sum += chunk[1] as u64;
            b_sum += chunk[2] as u64;
            if chunk[0] != 0 || chunk[1] != 0 || chunk[2] != 0 {
                nonzero_count += 1;
            }
        }
        
        Self {
            avg_rgb: (
                r_sum as f32 / pixel_count as f32,
                g_sum as f32 / pixel_count as f32, 
                b_sum as f32 / pixel_count as f32,
            ),
            nonzero_ratio: nonzero_count as f32 / pixel_count as f32,
            dominant_colors: Vec::new(), // Could implement k-means here
            color_variance: 0.0, // Could implement variance calculation
        }
    }
}

impl Default for ColorMetrics {
    fn default() -> Self {
        Self {
            avg_rgb: (0.0, 0.0, 0.0),
            nonzero_ratio: 0.0,
            dominant_colors: Vec::new(),
            color_variance: 0.0,
        }
    }
}
