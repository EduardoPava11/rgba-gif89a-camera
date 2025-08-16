use anyhow::Result;
use color_quant::NeuQuant;
use log::info;

pub struct QuantizeOptions {
    pub sample_fac: i32,
}

impl Default for QuantizeOptions {
    fn default() -> Self {
        Self {
            sample_fac: 10,
        }
    }
}

pub fn quantize_rgba_frames(
    frames: &[Vec<u8>],
    _width: u32,
    _height: u32,
    options: Option<QuantizeOptions>,
) -> Result<Vec<(Vec<u8>, Vec<u8>)>> {
    let options = options.unwrap_or_default();
    
    // Collect all pixels for global quantization
    let mut all_pixels = Vec::new();
    for frame in frames {
        all_pixels.extend_from_slice(frame);
    }
    
    info!("Quantizing {} total RGBA pixels with sample_fac={}", all_pixels.len() / 4, options.sample_fac);
    
    // Create quantizer - NeuQuant expects RGBA pixels (4 bytes per pixel)
    let nq = NeuQuant::new(options.sample_fac, 256, &all_pixels);
    
    let mut result = Vec::new();
    
    for frame in frames {
        let mut indices = Vec::new();
        
        // Map each pixel to its quantized index
        for rgba_pixel in frame.chunks_exact(4) {
            let idx = nq.index_of(rgba_pixel);
            indices.push(idx as u8); // Convert usize to u8
        }
        
        // Get the color palette - convert RGBA to RGB for GIF
        let palette_rgba = nq.color_map_rgba();
        let mut palette_rgb = Vec::with_capacity(768); // 256 colors * 3 bytes
        for chunk in palette_rgba.chunks_exact(4) {
            palette_rgb.push(chunk[0]); // R
            palette_rgb.push(chunk[1]); // G
            palette_rgb.push(chunk[2]); // B
            // Skip alpha channel (chunk[3])
        }
        
        result.push((indices, palette_rgb));
    }
    
    Ok(result)
}
