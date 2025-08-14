pub mod cbor_frame;
pub mod cbor_frame_v2;
pub mod fast_cbor;
pub mod fast_cbor_v2;
pub mod m1_verification;

use thiserror::Error;
use m3gif_core::*;
use std::collections::HashMap;
use sha2::{Sha256, Digest};
use log::info;

// Re-export for UniFFI (backward compatibility)
pub use fast_cbor::{write_cbor_frame, init_android_logger};
pub use fast_cbor_v2::{write_cbor_frame_v2, write_cbor_frame_v2_simple, verify_cbor_v2_file};
pub use m1_verification::{m1_debug_signature, m1_preview_patch};

#[derive(Error, Debug)]
pub enum GifPipeError {
    #[error("Initialization error: {0}")]
    InitializationError(String),
    
    #[error("Frame processing error: {0}")]
    FrameProcessingError(String),
    
    #[error("Quantization error: {0}")]
    QuantizationError(String),
    
    #[error("IO error: {0}")]
    IoError(String),
    
    #[error("Invalid configuration: {0}")]
    InvalidConfiguration(String),
    
    #[error("CBOR parsing failed: {0}")]
    CborParseError(String),
    
    #[error("Image processing failed: {0}")]
    ImageProcessingError(String),
    
    #[error("Validation failed: {0}")]
    ValidationError(String),
}

impl From<std::io::Error> for GifPipeError {
    fn from(err: std::io::Error) -> Self {
        GifPipeError::IoError(err.to_string())
    }
}

impl From<anyhow::Error> for GifPipeError {
    fn from(err: anyhow::Error) -> Self {
        GifPipeError::FrameProcessingError(err.to_string())
    }
}

// NEW: M2+M3 Pipeline Functions (desktop-proven)

/// Downscale RGBA 729 pixels to 81 pixels (27x27 → 9x9)
pub fn m2_downscale_rgba_729_to_81(rgba_729: Vec<u8>) -> Result<Vec<u8>, GifPipeError> {
    if rgba_729.len() != 729 * 4 {
        return Err(GifPipeError::ImageProcessingError(
            format!("Expected 2916 bytes (729*4), got {}", rgba_729.len())
        ));
    }
    
    let downscaled = downscale_rgba_729_to_81(&rgba_729)?;
    
    info!("Downscaled 729 RGBA pixels to {} pixels", downscaled.len() / 4);
    Ok(downscaled)
}

/// Encode RGBA frames to GIF89a format
pub fn m3_gif89a_encode_rgba_frames(
    rgba_frames: Vec<Vec<u8>>,
    output_file: String,
) -> Result<(), GifPipeError> {
    if rgba_frames.is_empty() {
        return Err(GifPipeError::ImageProcessingError("No frames provided".to_string()));
    }
    
    // Validate all frames are 81x81 RGBA (81 * 81 * 4 = 26244 bytes)
    for (idx, frame) in rgba_frames.iter().enumerate() {
        if frame.len() != 81 * 81 * 4 {
            return Err(GifPipeError::ImageProcessingError(
                format!("Frame {}: expected 26244 bytes (81*81*4), got {}", idx, frame.len())
            ));
        }
    }
    
    info!("Encoding {} RGBA frames (81x81) to GIF89a: {}", rgba_frames.len(), output_file);
    
    // Use the core library to encode
    encode_rgba_frames_to_gif89a(
        &output_file,
        &rgba_frames,
        81, // width
        81, // height
        None, // default GIF options (4cs delay, loop)
        None, // default quantize options (sample_fac=10)
    )?;
    
    Ok(())
}

/// Write CBOR frame data to file (M1 output format) - ENHANCED with parsing
pub fn cbor_write_frame_sequence(
    cbor_frames: Vec<Vec<u8>>,
    output_file: String,
) -> Result<(), GifPipeError> {
    info!("Writing {} CBOR frames to {}", cbor_frames.len(), output_file);
    
    let mut all_data = Vec::new();
    for (idx, frame_data) in cbor_frames.iter().enumerate() {
        let frame = parse_cbor_frame(frame_data)
            .map_err(|e| GifPipeError::CborParseError(e.to_string()))?;
            
        info!("Frame {}: {}x{} stride={} format={} ts={}ms", 
              idx, frame.width, frame.height, frame.stride, frame.format, frame.timestamp_ms);
        
        all_data.extend_from_slice(frame_data);
    }
    
    let total_len = all_data.len();
    std::fs::write(&output_file, all_data)?;
    info!("Wrote {} total bytes to {}", total_len, output_file);
    Ok(())
}

/// Verify GIF89a file structure and timing
pub fn verify_gif89a_structure(gif_file: String) -> Result<HashMap<String, String>, GifPipeError> {
    let data = std::fs::read(&gif_file)?;
    let mut results = HashMap::new();
    
    // Basic header validation
    if data.len() < 6 {
        return Err(GifPipeError::ValidationError("File too small".to_string()));
    }
    
    if &data[0..3] != b"GIF" {
        return Err(GifPipeError::ValidationError("Invalid GIF header".to_string()));
    }
    
    let version = std::str::from_utf8(&data[3..6])
        .map_err(|_| GifPipeError::ValidationError("Invalid version".to_string()))?;
    
    results.insert("header".to_string(), format!("GIF{}", version));
    results.insert("file_size".to_string(), data.len().to_string());
    
    // Look for NETSCAPE2.0 extension (looping)
    let has_loop = data.windows(11).any(|w| w == b"NETSCAPE2.0");
    results.insert("has_loop".to_string(), has_loop.to_string());
    
    // Count frames (simplified - count image separators)
    let frame_count = data.iter().filter(|&&b| b == 0x2C).count();
    results.insert("frame_count".to_string(), frame_count.to_string());
    
    info!("GIF verification: {} frames, loop={}, size={} bytes", 
          frame_count, has_loop, data.len());
    
    Ok(results)
}

/// Calculate file hash for verification
pub fn calculate_file_hash(file_path: String) -> Result<String, GifPipeError> {
    let data = std::fs::read(&file_path)?;
    let mut hasher = Sha256::new();
    hasher.update(&data);
    let hash = hasher.finalize();
    Ok(format!("{:x}", hash))
}

// Include the UniFFI scaffolding
uniffi::include_scaffolding!("gifpipe");