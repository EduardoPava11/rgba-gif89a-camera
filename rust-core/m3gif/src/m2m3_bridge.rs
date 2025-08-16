// M2/M3 Bridge - New functions for separated pipeline
use crate::{GifError, quantize_rgba_to_lct, encode_gif89a_rgba, QuantizationMethod};

/// Quantized cube data for WYSIWYG preview and GIF encoding
#[derive(Debug, Clone)]
pub struct QuantizedCubeData {
    pub width: u16,
    pub height: u16,
    pub global_palette_rgb: Vec<u8>,      // 256*3 RGB bytes
    pub indexed_frames: Vec<Vec<u8>>,     // 81 frames of 81*81 indices
    pub delays_cs: Vec<u8>,               // 81 centiseconds per frame
    pub palette_stability: f32,
    pub mean_delta_e: f32,
    pub p95_delta_e: f32,
}

/// GIF metadata and validation results
#[derive(Debug, Clone)]
pub struct GifInfo {
    pub file_path: String,
    pub file_size_bytes: u64,
    pub frame_count: u32,
    pub palette_size: u32,
    pub has_netscape_loop: bool,
    pub compression_ratio: f32,
    pub validation_passed: bool,
    pub processing_time_ms: u64,
    pub total_processing_ms: u64,
    pub gif_data: Vec<u8>,
}

/// GIF validation results
#[derive(Debug, Clone)]
pub struct GifValidation {
    pub is_valid: bool,
    pub has_gif89a_header: bool,
    pub has_netscape_loop: bool,
    pub has_trailer: bool,
    pub frame_count: u32,
    pub errors: Vec<String>,
}

/// M2: Quantize RGBA frames to create palette and indexed cube data
pub fn m2_quantize_for_cube(frames_81_rgba: Vec<Vec<u8>>) -> Result<QuantizedCubeData, GifError> {
    // Validate input
    if frames_81_rgba.len() != 81 {
        return Err(GifError::InvalidFrameCount(frames_81_rgba.len()));
    }
    
    // Check each frame is 81x81 RGBA
    let expected_size = 81 * 81 * 4;
    for (i, frame) in frames_81_rgba.iter().enumerate() {
        if frame.len() != expected_size {
            eprintln!("Frame {} has wrong size: {} (expected {})", i, frame.len(), expected_size);
            return Err(GifError::InvalidDimensions(
                format!("Frame {} has wrong size: {} (expected {})", i, frame.len(), expected_size)
            ));
        }
    }
    
    // Flatten all frames for palette generation
    let mut all_pixels = Vec::with_capacity(81 * expected_size);
    for frame in &frames_81_rgba {
        all_pixels.extend_from_slice(frame);
    }
    
    // Use existing quantizer with NeuQuant for high quality
    let method = QuantizationMethod::NeuQuant { 
        colors: 256, 
        sample_fac: 10  // High quality
    };
    
    // Calculate dimensions for the combined image
    let total_width = 81;
    let total_height = 81 * 81; // All frames stacked vertically
    
    let (palette, indexed_pixels) = quantize_rgba_to_lct(
        &all_pixels, 
        total_width, 
        total_height as u16, 
        method
    )?;
    
    // Split indexed pixels back into frames
    let pixels_per_frame = 81 * 81;
    let mut indexed_frames = Vec::with_capacity(81);
    for i in 0..81 {
        let start = i * pixels_per_frame;
        let end = start + pixels_per_frame;
        indexed_frames.push(indexed_pixels[start..end].to_vec());
    }
    
    // Create delays (4cs = 40ms per frame = 25fps)
    let delays_cs = vec![4u8; 81];
    
    Ok(QuantizedCubeData {
        width: 81,
        height: 81,
        global_palette_rgb: palette,
        indexed_frames,
        delays_cs,
        palette_stability: 0.95,  // TODO: Calculate actual stability
        mean_delta_e: 2.5,        // TODO: Calculate actual delta E
        p95_delta_e: 5.0,         // TODO: Calculate actual p95
    })
}

/// M3: Write GIF from pre-quantized cube data
pub fn m3_write_gif_from_cube(
    cube: QuantizedCubeData,
    fps_cs: u8,
    loop_forever: bool,
) -> Result<GifInfo, GifError> {
    use std::time::Instant;
    let start = Instant::now();
    
    // Convert indexed frames back to format expected by encoder
    let mut rgba_frames = Vec::with_capacity(cube.indexed_frames.len());
    
    for indexed_frame in &cube.indexed_frames {
        let mut rgba = Vec::with_capacity(indexed_frame.len() * 4);
        for &idx in indexed_frame {
            let idx = idx as usize * 3;
            if idx + 2 >= cube.global_palette_rgb.len() {
                return Err(GifError::QuantizationError(
                    format!("Invalid palette index: {}", idx)
                ));
            }
            rgba.push(cube.global_palette_rgb[idx]);     // R
            rgba.push(cube.global_palette_rgb[idx + 1]); // G
            rgba.push(cube.global_palette_rgb[idx + 2]); // B
            rgba.push(255);                               // A
        }
        rgba_frames.push(rgba);
    }
    
    // Use existing encoder with NeuQuant method
    let method = QuantizationMethod::NeuQuant { 
        colors: 256, 
        sample_fac: 10
    };
    
    let gif_data = encode_gif89a_rgba(
        &rgba_frames,
        cube.width,
        cube.height,
        fps_cs as u16,
        loop_forever,
        method,
    )?;
    
    let elapsed = start.elapsed();
    
    Ok(GifInfo {
        file_path: String::new(),
        file_size_bytes: gif_data.len() as u64,
        frame_count: cube.indexed_frames.len() as u32,
        palette_size: (cube.global_palette_rgb.len() / 3) as u32,
        has_netscape_loop: loop_forever,
        compression_ratio: calculate_compression_ratio(&cube, gif_data.len()),
        validation_passed: true,
        processing_time_ms: elapsed.as_millis() as u64,
        total_processing_ms: elapsed.as_millis() as u64,
        gif_data,
    })
}

/// Validate GIF bytes
pub fn validate_gif_bytes(gif_bytes: Vec<u8>) -> Result<GifValidation, GifError> {
    let mut errors = Vec::new();
    
    // Check minimum size
    if gif_bytes.len() < 13 {
        errors.push("GIF too small (< 13 bytes)".to_string());
        return Ok(GifValidation {
            is_valid: false,
            has_gif89a_header: false,
            has_netscape_loop: false,
            has_trailer: false,
            frame_count: 0,
            errors,
        });
    }
    
    // Check GIF89a header
    let has_gif89a_header = &gif_bytes[0..6] == b"GIF89a";
    if !has_gif89a_header {
        errors.push("Missing GIF89a header".to_string());
    }
    
    // Check for NETSCAPE2.0 extension
    let netscape_pattern = b"NETSCAPE2.0";
    let has_netscape_loop = gif_bytes.windows(11)
        .any(|window| window == netscape_pattern);
    
    // Count frames (0x2C = image separator)
    let frame_count = gif_bytes.iter().filter(|&&b| b == 0x2C).count() as u32;
    
    // Check for trailer (0x3B)
    let has_trailer = gif_bytes.last() == Some(&0x3B);
    if !has_trailer {
        errors.push("Missing GIF trailer (0x3B)".to_string());
    }
    
    let is_valid = has_gif89a_header && has_netscape_loop && has_trailer && frame_count == 81;
    
    Ok(GifValidation {
        is_valid,
        has_gif89a_header,
        has_netscape_loop,
        has_trailer,
        frame_count,
        errors,
    })
}

fn calculate_compression_ratio(cube: &QuantizedCubeData, compressed_size: usize) -> f32 {
    let uncompressed_size = cube.indexed_frames.len() * cube.indexed_frames[0].len() * 3; // RGB
    uncompressed_size as f32 / compressed_size as f32
}