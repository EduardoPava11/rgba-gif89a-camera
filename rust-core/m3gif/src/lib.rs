use color_quant::NeuQuant;
use gif::{Encoder, Frame, Repeat};
use std::borrow::Cow;
use std::fs::File;
use std::io::Write;
use std::sync::atomic::{AtomicUsize, Ordering};
use thiserror::Error;

// Add the new module
mod m2m3_bridge;

// Re-export the new types and functions for UniFFI
pub use m2m3_bridge::{
    QuantizedCubeData,
    GifInfo,
    GifValidation,
    m2_quantize_for_cube,
    m3_write_gif_from_cube,
    validate_gif_bytes,
};

/// GIF creation errors
#[derive(Debug, Error)]
pub enum GifError {
    #[error("Invalid dimensions: {0}")]
    InvalidDimensions(String),
    
    #[error("Invalid frame count: expected 81, got {0}")]
    InvalidFrameCount(usize),
    
    #[error("Quantization failed: {0}")]
    QuantizationError(String),
    
    #[error("Encoding failed: {0}")]
    EncodingError(String),
    
    #[error("IO error: {0}")]
    IoError(String),
}

/// Statistics about the created GIF
#[derive(Debug, Clone)]
pub struct GifStats {
    pub frames: u16,
    pub size_bytes: u64,
    pub palettes: Vec<u16>,
    pub compression_ratio: f32,
}

/// Quantization method for color reduction
#[derive(Debug, Clone, Copy)]
pub enum QuantizationMethod {
    NeuQuant { colors: u16, sample_fac: u8 },
    MedianCut { colors: u16 },
}

impl Default for QuantizationMethod {
    fn default() -> Self {
        // NeuQuant with high quality settings
        QuantizationMethod::NeuQuant { 
            colors: 256, 
            sample_fac: 10  // Lower = higher quality (1-30 range)
        }
    }
}

/// Quantize RGBA data to indexed color with palette
/// Returns (RGB palette, indices)
pub fn quantize_rgba_to_lct(
    rgba: &[u8],
    width: u16,
    height: u16,
    method: QuantizationMethod,
) -> Result<(Vec<u8>, Vec<u8>), GifError> {
    let pixel_count = (width as usize) * (height as usize);
    
    if rgba.len() != pixel_count * 4 {
        return Err(GifError::InvalidDimensions(
            format!("Expected {} bytes, got {}", pixel_count * 4, rgba.len())
        ));
    }
    
    match method {
        QuantizationMethod::NeuQuant { colors, sample_fac } => {
            // NeuQuant expects RGBA data (4 bytes per pixel) 
            // We already have RGBA, so use it directly
            
            // Run NeuQuant quantization
            let nq = NeuQuant::new(sample_fac as i32, colors as usize, rgba);
            
            // Get the palette (RGB format)
            let palette = nq.color_map_rgb();
            
            // Map pixels to palette indices with Floyd-Steinberg dithering
            let mut indices = Vec::with_capacity(pixel_count);
            let mut error_r = vec![0i32; pixel_count];
            let mut error_g = vec![0i32; pixel_count];
            let mut error_b = vec![0i32; pixel_count];
            
            for y in 0..height as usize {
                for x in 0..width as usize {
                    let i = y * width as usize + x;
                    let idx = i * 4;  // RGBA data, 4 bytes per pixel
                    
                    // Apply accumulated error
                    let r = (rgba[idx] as i32 + error_r[i]).clamp(0, 255) as u8;
                    let g = (rgba[idx + 1] as i32 + error_g[i]).clamp(0, 255) as u8;
                    let b = (rgba[idx + 2] as i32 + error_b[i]).clamp(0, 255) as u8;
                    
                    // Find nearest palette color
                    // NeuQuant's index_of expects RGBA (4 bytes)
                    let index = nq.index_of(&[r, g, b, 255]) as u8;
                    indices.push(index);
                    
                    // Calculate quantization error
                    let palette_idx = index as usize * 3;
                    let err_r = r as i32 - palette[palette_idx] as i32;
                    let err_g = g as i32 - palette[palette_idx + 1] as i32;
                    let err_b = b as i32 - palette[palette_idx + 2] as i32;
                    
                    // Distribute error using Floyd-Steinberg coefficients
                    // Right: 7/16
                    if x + 1 < width as usize {
                        let idx_right = i + 1;
                        error_r[idx_right] += (err_r * 7) / 16;
                        error_g[idx_right] += (err_g * 7) / 16;
                        error_b[idx_right] += (err_b * 7) / 16;
                    }
                    
                    // Below-left: 3/16
                    if y + 1 < height as usize && x > 0 {
                        let idx_bl = i + width as usize - 1;
                        error_r[idx_bl] += (err_r * 3) / 16;
                        error_g[idx_bl] += (err_g * 3) / 16;
                        error_b[idx_bl] += (err_b * 3) / 16;
                    }
                    
                    // Below: 5/16
                    if y + 1 < height as usize {
                        let idx_below = i + width as usize;
                        error_r[idx_below] += (err_r * 5) / 16;
                        error_g[idx_below] += (err_g * 5) / 16;
                        error_b[idx_below] += (err_b * 5) / 16;
                    }
                    
                    // Below-right: 1/16
                    if y + 1 < height as usize && x + 1 < width as usize {
                        let idx_br = i + width as usize + 1;
                        error_r[idx_br] += err_r / 16;
                        error_g[idx_br] += err_g / 16;
                        error_b[idx_br] += err_b / 16;
                    }
                }
            }
            
            Ok((palette, indices))
        }
        
        QuantizationMethod::MedianCut { colors } => {
            // Fallback median-cut implementation
            // For now, use simple frequency-based selection
            median_cut_quantize(rgba, width, height, colors)
        }
    }
}

/// Simple median-cut quantization (fallback)
fn median_cut_quantize(
    rgba: &[u8],
    width: u16,
    height: u16,
    max_colors: u16,
) -> Result<(Vec<u8>, Vec<u8>), GifError> {
    let pixel_count = (width as usize) * (height as usize);
    
    // Collect unique colors with frequency
    let mut color_freq = std::collections::HashMap::new();
    for i in 0..pixel_count {
        let idx = i * 4;
        let rgb = (rgba[idx], rgba[idx + 1], rgba[idx + 2]);
        *color_freq.entry(rgb).or_insert(0) += 1;
    }
    
    // Sort by frequency and take top colors
    let mut sorted_colors: Vec<_> = color_freq.into_iter().collect();
    sorted_colors.sort_by_key(|&(_, freq)| std::cmp::Reverse(freq));
    sorted_colors.truncate(max_colors as usize);
    
    // Build palette
    let mut palette = Vec::with_capacity(max_colors as usize * 3);
    let mut color_to_index = std::collections::HashMap::new();
    
    for (i, ((r, g, b), _)) in sorted_colors.iter().enumerate() {
        palette.push(*r);
        palette.push(*g);
        palette.push(*b);
        color_to_index.insert((*r, *g, *b), i as u8);
    }
    
    // Pad palette to requested size
    while palette.len() < (max_colors as usize * 3) {
        palette.extend_from_slice(&[0, 0, 0]);
    }
    
    // Map pixels to nearest palette color
    let mut indices = Vec::with_capacity(pixel_count);
    for i in 0..pixel_count {
        let idx = i * 4;
        let rgb = (rgba[idx], rgba[idx + 1], rgba[idx + 2]);
        
        let index = if let Some(&idx) = color_to_index.get(&rgb) {
            idx
        } else {
            // Find nearest color
            find_nearest_palette_index(rgb, &sorted_colors)
        };
        indices.push(index);
    }
    
    Ok((palette, indices))
}

fn find_nearest_palette_index(
    rgb: (u8, u8, u8),
    palette: &[((u8, u8, u8), usize)],
) -> u8 {
    let (r1, g1, b1) = rgb;
    let mut min_dist = u32::MAX;
    let mut best_idx = 0u8;
    
    for (i, ((r2, g2, b2), _)) in palette.iter().enumerate() {
        let dr = (r1 as i32 - *r2 as i32).abs() as u32;
        let dg = (g1 as i32 - *g2 as i32).abs() as u32;
        let db = (b1 as i32 - *b2 as i32).abs() as u32;
        let dist = dr * dr + dg * dg + db * db;
        
        if dist < min_dist {
            min_dist = dist;
            best_idx = i as u8;
        }
    }
    
    best_idx
}

/// Create a GIF89a from RGBA frames
/// Implements full spec: Header, LSD, NETSCAPE2.0, per-frame GCE+LCT+LZW
pub fn encode_gif89a_rgba(
    frames: &[Vec<u8>],
    width: u16,
    height: u16,
    delay_cs: u16,
    loop_forever: bool,
    method: QuantizationMethod,
) -> Result<Vec<u8>, GifError> {
    // Validate frame count (must have at least 1 frame, 81 is optimal)
    if frames.is_empty() {
        return Err(GifError::InvalidFrameCount(0));
    }
    
    if frames.len() != 81 {
        log::warn!("Expected 81 frames for optimal GIF, got {}", frames.len());
    }
    
    // Validate dimensions (81x81 is expected)
    if width != 81 || height != 81 {
        log::warn!("Expected 81x81 dimensions, got {}x{}", width, height);
    }
    
    let mut output = Vec::new();
    let mut encoder = Encoder::new(&mut output, width, height, &[])
        .map_err(|e| GifError::EncodingError(e.to_string()))?;
    
    // Set infinite loop if requested (NETSCAPE2.0 extension)
    if loop_forever {
        encoder.set_repeat(Repeat::Infinite)
            .map_err(|e| GifError::EncodingError(e.to_string()))?;
    }
    
    let mut palettes = Vec::new();
    
    // Process each frame
    for (idx, rgba_frame) in frames.iter().enumerate() {
        // Quantize frame to indexed color
        let (palette, indices) = quantize_rgba_to_lct(
            rgba_frame,
            width,
            height,
            method,
        )?;
        
        let palette_size = palette.len() / 3;
        palettes.push(palette_size as u16);
        
        // Log per-frame processing
        log::debug!("M3_GCE idx={} delayCs={} dispose=1 trans=false", idx, delay_cs);
        log::debug!("M3_ID idx={} lct={}", idx, palette_size);
        
        // Calculate minimum code size for LZW
        let min_code_size = calculate_min_code_size(palette_size);
        log::debug!("M3_LZW idx={} minCodeSize={}", idx, min_code_size);
        
        // Create frame with proper dimensions and data
        let mut frame = Frame::default();
        frame.width = width;
        frame.height = height;
        frame.buffer = Cow::Borrowed(&indices);
        frame.palette = Some(palette.clone());
        
        // Set frame delay (in centiseconds)
        frame.delay = delay_cs;
        
        // Write frame with proper LZW compression
        encoder.write_frame(&frame)
            .map_err(|e| GifError::EncodingError(format!("Frame {}: {}", idx, e)))?;
    }
    
    // Finish encoding
    drop(encoder);
    
    // Verify GIF structure (sanity check)
    verify_gif_structure(&output, frames.len())?;
    
    let stats = GifStats {
        frames: frames.len() as u16,
        size_bytes: output.len() as u64,
        palettes,
        compression_ratio: calculate_compression_ratio(frames, &output),
    };
    
    log::info!(
        "M3_GIF_DONE frames={} sizeBytes={} compression_ratio={:.2}",
        stats.frames,
        stats.size_bytes,
        stats.compression_ratio
    );
    
    Ok(output)
}

/// Calculate minimum code size for LZW based on palette size
fn calculate_min_code_size(palette_size: usize) -> u8 {
    if palette_size <= 2 {
        2  // Minimum is 2
    } else if palette_size <= 4 {
        2
    } else if palette_size <= 8 {
        3
    } else if palette_size <= 16 {
        4
    } else if palette_size <= 32 {
        5
    } else if palette_size <= 64 {
        6
    } else if palette_size <= 128 {
        7
    } else {
        8  // Max for 256 colors
    }
}

/// High-quality downscale from 729×729 to 81×81 using Lanczos3 filter (PANIC-SAFE)
pub fn m2_downsize_rgba_729_to_81(rgba_729: Vec<u8>) -> Result<Vec<u8>, GifError> {
    // Log the downscaling method being used
    log::info!("M2_DOWNSCALE_START method=Lanczos3 input=729x729 output=81x81");
    
    std::panic::catch_unwind(|| inner_downsize_rgba_729_to_81(rgba_729))
        .map_err(|_| GifError::EncodingError("Internal panic during downsize".to_string()))?
}

/// Internal downsize implementation (can panic, but caught by wrapper)
fn inner_downsize_rgba_729_to_81(rgba_729: Vec<u8>) -> Result<Vec<u8>, GifError> {
    const INPUT_SIZE: u32 = 729;
    const OUTPUT_SIZE: u32 = 81;
    
    if rgba_729.len() != (INPUT_SIZE * INPUT_SIZE * 4) as usize {
        return Err(GifError::InvalidDimensions(
            format!("Expected {} bytes, got {}", INPUT_SIZE * INPUT_SIZE * 4, rgba_729.len())
        ));
    }
    
    // Use image crate for high-quality resizing with Lanczos3
    use image::{ImageBuffer, Rgba, imageops::FilterType};
    
    // Create image from RGBA bytes
    let img = ImageBuffer::<Rgba<u8>, Vec<u8>>::from_raw(
        INPUT_SIZE, 
        INPUT_SIZE, 
        rgba_729
    ).ok_or_else(|| GifError::EncodingError("Failed to create image buffer".to_string()))?;
    
    // Resize using Lanczos3 filter
    let resized = image::imageops::resize(
        &img,
        OUTPUT_SIZE,
        OUTPUT_SIZE,
        FilterType::Lanczos3
    );
    
    // Convert back to raw RGBA bytes
    let output = resized.into_raw();
    
    log::info!("M2_DOWNSCALE_DONE method=Lanczos3 output_size={}", output.len());
    
    // Log statistics for first few frames (thread-safe)
    static FRAME_COUNT: AtomicUsize = AtomicUsize::new(0);
    let frame_num = FRAME_COUNT.fetch_add(1, Ordering::Relaxed);
    
    if frame_num < 3 {
        let mut sum_r = 0u64;
        let mut sum_g = 0u64;
        let mut sum_b = 0u64;
        let mut non_zero = 0usize;
        
        let pixel_count = (OUTPUT_SIZE * OUTPUT_SIZE) as usize;
        for i in 0..pixel_count {
            let idx = i * 4;
            sum_r += output[idx] as u64;
            sum_g += output[idx + 1] as u64;
            sum_b += output[idx + 2] as u64;
            if output[idx] != 0 || output[idx + 1] != 0 || output[idx + 2] != 0 {
                non_zero += 1;
            }
        }
        
        log::info!(
            "M2_STATS frame={} avgRGB=({},{},{}) nonZero={}",
            frame_num,
            sum_r / pixel_count as u64,
            sum_g / pixel_count as u64,
            sum_b / pixel_count as u64,
            non_zero
        );
    }
    
    Ok(output)
}

fn calculate_compression_ratio(frames: &[Vec<u8>], compressed: &[u8]) -> f32 {
    let uncompressed_size: usize = frames.iter().map(|f| f.len()).sum();
    uncompressed_size as f32 / compressed.len() as f32
}

/// Verify GIF structure for sanity (catch "black GIF" issues early)
fn verify_gif_structure(gif_data: &[u8], expected_frames: usize) -> Result<(), GifError> {
    // Check minimum size
    if gif_data.len() < 100 {
        return Err(GifError::EncodingError(
            format!("GIF too small: {} bytes", gif_data.len())
        ));
    }
    
    // Check header
    if &gif_data[0..6] != b"GIF89a" {
        return Err(GifError::EncodingError(
            "Invalid GIF header: expected GIF89a".to_string()
        ));
    }
    
    // Check for NETSCAPE2.0 extension (for looping)
    let netscape_pattern = b"NETSCAPE2.0";
    let has_netscape = gif_data.windows(11)
        .any(|window| window == netscape_pattern);
    
    if !has_netscape {
        log::warn!("GIF missing NETSCAPE2.0 extension (no infinite loop)");
    }
    
    // Count image separators (0x2C) - rough frame count
    let image_separators = gif_data.iter()
        .filter(|&&b| b == 0x2C)
        .count();
    
    if image_separators < expected_frames {
        log::warn!(
            "GIF may be incomplete: found {} image separators, expected {} frames",
            image_separators,
            expected_frames
        );
    }
    
    // Check for trailer
    if gif_data[gif_data.len() - 1] != 0x3B {
        return Err(GifError::EncodingError(
            "Invalid GIF trailer: expected 0x3B".to_string()
        ));
    }
    
    log::debug!(
        "GIF verification passed: {} bytes, {} image separators",
        gif_data.len(),
        image_separators
    );
    
    Ok(())
}

/// Main entry point for UniFFI - creates GIF89a with NeuQuant quantization (PANIC-SAFE)
pub fn m3_create_gif89a_rgba(
    frames_rgba: Vec<Vec<u8>>,
    width: u16,
    height: u16,
    delay_cs: u16,
    loop_forever: bool,
) -> Result<GifStats, GifError> {
    std::panic::catch_unwind(|| inner_create_gif89a_rgba(frames_rgba, width, height, delay_cs, loop_forever))
        .map_err(|_| GifError::EncodingError("Internal panic during GIF creation".to_string()))?
}

/// Internal implementation (can panic, but caught by wrapper)
fn inner_create_gif89a_rgba(
    frames_rgba: Vec<Vec<u8>>,
    width: u16,
    height: u16,
    delay_cs: u16,
    loop_forever: bool,
) -> Result<GifStats, GifError> {
    // Initialize Android logger if not already done
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug)
            .with_tag("M3GIF"),
    );
    
    log::info!("M3_START frames={} quant=NeuQuant samplefac=10", frames_rgba.len());
    
    // Use high-quality NeuQuant settings
    let method = QuantizationMethod::NeuQuant {
        colors: 256,
        sample_fac: 10,  // 10 = high quality (1=best, 30=fastest)
    };
    
    // Create GIF
    let gif_data = encode_gif89a_rgba(
        &frames_rgba,
        width,
        height,
        delay_cs,
        loop_forever,
        method,
    )?;
    
    // Calculate stats
    let stats = GifStats {
        frames: frames_rgba.len() as u16,
        size_bytes: gif_data.len() as u64,
        palettes: vec![256; frames_rgba.len()],  // NeuQuant always uses full palette
        compression_ratio: calculate_compression_ratio(&frames_rgba, &gif_data),
    };
    
    Ok(stats)
}

/// Export GIF data to file (for testing) (PANIC-SAFE)
pub fn m3_save_gif_to_file(
    frames_rgba: Vec<Vec<u8>>,
    width: u16,
    height: u16,
    delay_cs: u16,
    output_path: String,
) -> Result<GifStats, GifError> {
    // Try to catch and log the panic details
    std::panic::catch_unwind(|| inner_save_gif_to_file(frames_rgba, width, height, delay_cs, output_path))
        .map_err(|panic_info| {
            let msg = if let Some(s) = panic_info.downcast_ref::<String>() {
                format!("Panic: {}", s)
            } else if let Some(s) = panic_info.downcast_ref::<&str>() {
                format!("Panic: {}", s)
            } else {
                "Unknown panic occurred".to_string()
            };
            log::error!("M3GIF PANIC: {}", msg);
            GifError::IoError(format!("Internal panic during file save: {}", msg))
        })?
}

/// Internal save implementation (can panic, but caught by wrapper)
fn inner_save_gif_to_file(
    frames_rgba: Vec<Vec<u8>>,
    width: u16,
    height: u16,
    delay_cs: u16,
    output_path: String,
) -> Result<GifStats, GifError> {
    // Initialize Android logger if not already done
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug)
            .with_tag("M3GIF"),
    );
    
    log::info!("M3GIF: Saving GIF to: {}", output_path);
    log::info!("M3GIF: Frames: {}, dimensions: {}x{}", frames_rgba.len(), width, height);
    
    // Validate input
    if frames_rgba.is_empty() {
        return Err(GifError::InvalidFrameCount(0));
    }
    
    // Debug log frame sizes
    for (i, frame) in frames_rgba.iter().enumerate() {
        if i < 3 {
            log::debug!("M3GIF: Frame {} size: {} bytes (expected {})", 
                i, frame.len(), (width as usize) * (height as usize) * 4);
        }
        if frame.len() != (width as usize) * (height as usize) * 4 {
            return Err(GifError::InvalidDimensions(
                format!("Frame {} has {} bytes, expected {}", 
                    i, frame.len(), (width as usize) * (height as usize) * 4)
            ));
        }
    }
    
    // Create parent directory if it doesn't exist
    if let Some(parent) = std::path::Path::new(&output_path).parent() {
        log::debug!("M3GIF: Creating directory: {:?}", parent);
        std::fs::create_dir_all(parent)
            .map_err(|e| GifError::IoError(format!("Failed to create directory: {}", e)))?;
    }
    
    let method = QuantizationMethod::NeuQuant {
        colors: 256,
        sample_fac: 10,
    };
    
    log::debug!("M3GIF: Starting GIF encoding with NeuQuant");
    
    // Use the proper encode_gif89a_rgba function
    let output = encode_gif89a_rgba(
        &frames_rgba,
        width,
        height,
        delay_cs,
        true, // loop_forever
        method,
    )?;
    
    // Write the encoded GIF data to file
    let mut file = File::create(&output_path)
        .map_err(|e| GifError::IoError(format!("Failed to create file {}: {}", output_path, e)))?;
    
    file.write_all(&output)
        .map_err(|e| GifError::IoError(e.to_string()))?;
    
    // Calculate compression ratio
    let raw_size = frames_rgba.len() * width as usize * height as usize * 4;
    let compressed_size = output.len();
    let compression_ratio = if compressed_size > 0 {
        raw_size as f32 / compressed_size as f32
    } else {
        1.0
    };
    
    let stats = GifStats {
        frames: frames_rgba.len() as u16,
        size_bytes: output.len() as u64,
        palettes: vec![256], // NeuQuant uses 256 colors
        compression_ratio,
    };
    
    log::info!("GIF saved: {} bytes", stats.size_bytes);
    
    Ok(stats)
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_min_code_size() {
        assert_eq!(calculate_min_code_size(2), 2);
        assert_eq!(calculate_min_code_size(4), 2);
        assert_eq!(calculate_min_code_size(8), 3);
        assert_eq!(calculate_min_code_size(16), 4);
        assert_eq!(calculate_min_code_size(32), 5);
        assert_eq!(calculate_min_code_size(64), 6);
        assert_eq!(calculate_min_code_size(128), 7);
        assert_eq!(calculate_min_code_size(256), 8);
    }
    
    #[test]
    fn test_quantization() {
        // Create test frame (2x2 RGBA)
        let rgba = vec![
            255, 0, 0, 255,    // Red
            0, 255, 0, 255,    // Green
            0, 0, 255, 255,    // Blue
            255, 255, 0, 255,  // Yellow
        ];
        
        let (palette, indices) = quantize_rgba_to_lct(
            &rgba,
            2,
            2,
            QuantizationMethod::MedianCut { colors: 4 },
        ).unwrap();
        
        // Should have 4 colors in palette
        assert_eq!(palette.len(), 12); // 4 colors * 3 bytes
        assert_eq!(indices.len(), 4);  // 4 pixels
    }
    
    #[test]
    fn test_nn_downsizes_729_to_81() {
        // Initialize logger for test
        let _ = env_logger::builder()
            .filter_level(log::LevelFilter::Info)
            .try_init();
        
        // Create 729×729 RGBA test frame (all red for simplicity)
        let input = vec![255u8, 0, 0, 255].repeat(729 * 729);
        assert_eq!(input.len(), 729 * 729 * 4);
        
        // Call the downscaler
        let output = m2_downsize_rgba_729_to_81(input).expect("Downscale should succeed");
        
        // Verify output dimensions
        assert_eq!(output.len(), 81 * 81 * 4, "Output should be 81×81 RGBA");
        
        // Verify the first pixel is red (allowing for some interpolation variance)
        assert!(output[0] > 250, "Red channel should be preserved");
        assert!(output[1] < 5, "Green should be near 0");
        assert!(output[2] < 5, "Blue should be near 0");
        assert_eq!(output[3], 255, "Alpha should be 255");
        
        println!("✅ Neural downsizer test passed: 729×729 → 81×81");
    }
}

// ==== RGB-ONLY FUNCTIONS ====

/// Downscale 729×729 RGB to 81×81 RGB (3 bytes per pixel)
pub fn m2_downsize_rgb_729_to_81(rgb_729: Vec<u8>) -> Result<Vec<u8>, GifError> {
    const INPUT_SIZE: usize = 729;
    const OUTPUT_SIZE: usize = 81;
    
    // Validate input is RGB (3 bytes per pixel)
    if rgb_729.len() != INPUT_SIZE * INPUT_SIZE * 3 {
        return Err(GifError::InvalidDimensions(
            format!("Expected {} RGB bytes, got {}", INPUT_SIZE * INPUT_SIZE * 3, rgb_729.len())
        ));
    }
    
    log::info!("M2_DOWNSCALE_RGB_START input=729x729x3 output=81x81x3");
    
    // Convert RGB to RGBA for processing (add alpha=255)
    let mut rgba_729 = Vec::with_capacity(INPUT_SIZE * INPUT_SIZE * 4);
    for chunk in rgb_729.chunks(3) {
        rgba_729.push(chunk[0]); // R
        rgba_729.push(chunk[1]); // G
        rgba_729.push(chunk[2]); // B
        rgba_729.push(255);       // A
    }
    
    // Use existing RGBA downscaler
    let rgba_81 = m2_downsize_rgba_729_to_81(rgba_729)?;
    
    // Strip alpha channel from result
    let mut rgb_81 = Vec::with_capacity(OUTPUT_SIZE * OUTPUT_SIZE * 3);
    for chunk in rgba_81.chunks(4) {
        rgb_81.push(chunk[0]); // R
        rgb_81.push(chunk[1]); // G
        rgb_81.push(chunk[2]); // B
        // Skip alpha
    }
    
    log::info!("M2_DOWNSCALE_RGB_DONE output_size={}", rgb_81.len());
    Ok(rgb_81)
}

/// Quantize RGB frames to create palette and indexed cube data
pub fn m2_quantize_for_cube_rgb(frames_81_rgb: Vec<Vec<u8>>) -> Result<QuantizedCubeData, GifError> {
    if frames_81_rgb.is_empty() {
        return Err(GifError::InvalidFrameCount(0));
    }
    
    // Convert RGB frames to RGBA
    let mut frames_81_rgba = Vec::with_capacity(frames_81_rgb.len());
    for rgb_frame in frames_81_rgb {
        if rgb_frame.len() != 81 * 81 * 3 {
            return Err(GifError::InvalidDimensions(
                format!("Expected {} RGB bytes per frame, got {}", 81 * 81 * 3, rgb_frame.len())
            ));
        }
        
        // Add alpha channel
        let mut rgba_frame = Vec::with_capacity(81 * 81 * 4);
        for chunk in rgb_frame.chunks(3) {
            rgba_frame.push(chunk[0]); // R
            rgba_frame.push(chunk[1]); // G
            rgba_frame.push(chunk[2]); // B
            rgba_frame.push(255);       // A
        }
        frames_81_rgba.push(rgba_frame);
    }
    
    // Use existing RGBA quantizer
    m2_quantize_for_cube(frames_81_rgba)
}

// UniFFI scaffolding - must be at the end
uniffi::include_scaffolding!("m3gif");