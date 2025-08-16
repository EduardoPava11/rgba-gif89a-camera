use common_types::*;
use tracing::{info, error, warn};
use uuid::Uuid;
use std::sync::Once;
use std::time::Instant;

static INIT: Once = Once::new();

// Include the UniFFI scaffolding
uniffi::include_scaffolding!("ffi");

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum ProcessingError {
    #[error("Serialization failed: {message}")]
    SerializationFailed { message: String },
    #[error("Processing failed: {message}")]
    ProcessingFailed { message: String },
}

// Re-export common types for UniFFI
pub use common_types::{GifPipeError, QuantizedCubeData, GifInfo};

// Define GifValidation for UniFFI
#[derive(Debug, Clone, uniffi::Record)]
pub struct GifValidation {
    pub is_valid: bool,
    pub has_gif89a_header: bool,
    pub has_netscape_loop: bool,
    pub has_trailer: bool,
    pub frame_count: u32,
    pub errors: Vec<String>,
}

/// Initialize Android tracing/logging
#[uniffi::export]
pub fn init_android_tracing() -> String {
    let session_id = Uuid::new_v4();
    
    INIT.call_once(|| {
        // Initialize tracing with Android backend
        #[cfg(target_os = "android")]
        {
            use tracing_subscriber::layer::SubscriberExt;
            use tracing_subscriber::util::SubscriberInitExt;
            
            tracing_subscriber::registry()
                .with(tracing_android::layer("gifpipe").unwrap())
                .init();
        }
        
        #[cfg(not(target_os = "android"))]
        {
            tracing_subscriber::fmt::init();
        }
    });
    
    info!("Initialized tracing with session {}", session_id);
    session_id.to_string()
}

/// M2: Quantize RGBA frames to create palette and indexed cube data
#[uniffi::export]
pub fn m2_quantize_for_cube(frames_81_rgba: Vec<Vec<u8>>) -> Result<QuantizedCubeData, GifPipeError> {
    let start = Instant::now();
    info!("M2: Starting quantization for {} frames", frames_81_rgba.len());
    
    // Validate input
    if frames_81_rgba.len() != 81 {
        return Err(GifPipeError::InvalidFrameData {
            message: format!("Expected 81 frames, got {}", frames_81_rgba.len())
        });
    }
    
    // Convert to RGB (drop alpha channel)
    let frames_rgb: Vec<Vec<u8>> = frames_81_rgba.iter().map(|rgba| {
        let pixel_count = rgba.len() / 4;
        let mut rgb = Vec::with_capacity(pixel_count * 3);
        for i in 0..pixel_count {
            rgb.push(rgba[i * 4]);     // R
            rgb.push(rgba[i * 4 + 1]); // G
            rgb.push(rgba[i * 4 + 2]); // B
            // Skip alpha
        }
        rgb
    }).collect();
    
    let frames = Frames81Rgb {
        frames_rgb,
        attention_maps: vec![],
        processing_time_ms: 0,
    };
    
    let quantizer = m2_quant::OklabQuantizer::new(256);
    let result = quantizer.quantize_for_cube(frames)?;
    
    let elapsed = start.elapsed();
    info!("M2: Quantization complete in {:?}", elapsed);
    
    Ok(result)
}

/// M3: Write GIF from pre-quantized cube data
#[uniffi::export]
pub fn m3_write_gif_from_cube(
    cube: QuantizedCubeData,
    fps_cs: u8,
    loop_forever: bool,
) -> Result<GifInfo, GifPipeError> {
    let start = Instant::now();
    info!("M3: Starting GIF encoding, {} frames, fps_cs={}", cube.indexed_frames.len(), fps_cs);
    
    let encoder = m3_gif::Gif89aEncoder::new();
    let gif_bytes = encoder.encode_from_cube_data(&cube, fps_cs, loop_forever)?;
    
    let elapsed = start.elapsed();
    info!("M3: GIF encoding complete in {:?}, {} bytes", elapsed, gif_bytes.len());
    
    Ok(GifInfo {
        file_path: String::new(), // No file path when returning bytes
        file_size_bytes: gif_bytes.len() as u64,
        frame_count: cube.indexed_frames.len() as u32,
        palette_size: cube.global_palette_rgb.len() as u32 / 3,
        has_netscape_loop: loop_forever,
        compression_ratio: calculate_compression_ratio(&cube, gif_bytes.len()),
        validation_passed: true,
        processing_time_ms: elapsed.as_millis() as u64,
        total_processing_ms: elapsed.as_millis() as u64,
        gif_data: gif_bytes,
    })
}

/// Validate GIF bytes
#[uniffi::export]
pub fn validate_gif_bytes(gif_bytes: Vec<u8>) -> Result<GifValidation, GifPipeError> {
    let mut errors = Vec::new();
    let mut frame_count = 0u32;
    
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
    frame_count = gif_bytes.iter().filter(|&&b| b == 0x2C).count() as u32;
    
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

/// Legacy: Process GIF frames (kept for compatibility)
#[uniffi::export]
pub fn process_gif_frames(_frames_bytes: Vec<u8>, _session_id: String) -> Result<Vec<u8>, ProcessingError> {
    warn!("Using legacy process_gif_frames API - please migrate to m2_quantize_for_cube + m3_write_gif_from_cube");
    Err(ProcessingError::ProcessingFailed { 
        message: "Legacy API deprecated - use m2_quantize_for_cube + m3_write_gif_from_cube".to_string() 
    })
}

/// Legacy: Get pipeline metrics
#[uniffi::export]
pub fn get_pipeline_metrics() -> String {
    r#"{"status": "deprecated", "message": "Use structured logging with session IDs instead"}"#.to_string()
}

/// Helper to create test data
pub fn create_test_cube() -> QuantizedCubeData {
    QuantizedCubeData {
        width: 4,
        height: 4,
        global_palette_rgb: vec![
            255, 0, 0,     // Red
            0, 255, 0,     // Green  
            0, 0, 255,     // Blue
            255, 255, 0,   // Yellow
        ],
        indexed_frames: vec![
            vec![0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3], // Frame 1
            vec![1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0], // Frame 2  
            vec![2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1], // Frame 3
        ],
        delays_cs: vec![10, 10, 10], // 100ms per frame
        palette_stability: 0.95,
        mean_delta_e: 1.5,
        p95_delta_e: 3.2,
        attention_maps: None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_basic_pipeline() {
        let session_id = initialize_logging();
        assert!(!session_id.is_empty());
        
        let test_cube = create_test_cube();
        assert_eq!(test_cube.width, 4);
        assert_eq!(test_cube.height, 4);
        assert_eq!(test_cube.indexed_frames.len(), 3);
    }
}
