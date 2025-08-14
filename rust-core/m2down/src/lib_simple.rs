/// M2 Neural Downsize Module - 729×729 → 81×81
/// Simplified baseline version with structured logging
/// North Star spec: EXACTLY 81 frames at 81×81

use std::cmp;
use std::sync::Once;
use std::time::Instant;

uniffi::include_scaffolding!("m2down");

// Initialize Android logging once
static INIT_LOGGER: Once = Once::new();

fn init_logger() {
    INIT_LOGGER.call_once(|| {
        #[cfg(target_os = "android")]
        {
            android_logger::init_once(
                android_logger::Config::default()
                    .with_max_level(log::LevelFilter::Debug)
                    .with_tag("M2Down"),
            );
            log::info!("M2_RUST_INIT {{ version: \"1.0.0-baseline\" }}");
        }
    });
}

/// Error types for M2 processing
#[derive(Debug, thiserror::Error)]
pub enum M2Error {
    #[error("Invalid input dimensions: expected 729×729, got {width}×{height}")]
    InvalidInputDimensions { width: u32, height: u32 },
    
    #[error("Invalid data size: expected {expected}, got {actual}")]
    InvalidDataSize { expected: u32, actual: u32 },
}

/// Main entry point for M2 downsize
/// Takes 729×729 RGBA and returns 81×81 RGBA
pub fn m2_downsize_9x9_cpu(
    rgba_729: Vec<u8>,
    width: u32,
    height: u32,
) -> Result<Vec<u8>, M2Error> {
    init_logger();
    
    let start_time = Instant::now();
    
    // Log start
    log::info!("M2_RUST_DOWNSIZE_BEGIN {{ idx: 0 }}");
    
    // Validate dimensions - MUST be exactly 729×729
    if width != 729 || height != 729 {
        log::error!(
            "M2_RUST_ERROR {{ message: \"Invalid dimensions: {}×{}\" }}",
            width, height
        );
        return Err(M2Error::InvalidInputDimensions { width, height });
    }
    
    let expected_size = (width * height * 4) as usize;
    if rgba_729.len() != expected_size {
        log::error!(
            "M2_RUST_ERROR {{ message: \"Invalid data size: expected {}, got {}\" }}",
            expected_size, rgba_729.len()
        );
        return Err(M2Error::InvalidDataSize {
            expected: expected_size as u32,
            actual: rgba_729.len() as u32,
        });
    }
    
    // Use baseline 9×9 block averaging
    let result = baseline_block_average(&rgba_729, width, height)?;
    
    let elapsed_ms = start_time.elapsed().as_millis();
    
    // Log completion
    log::info!(
        "M2_RUST_DOWNSIZE_END {{ idx: 0, elapsedMs: {} }}",
        elapsed_ms
    );
    
    Ok(result)
}

/// Baseline implementation: 9×9 block averaging
/// Each output pixel is the average of a 9×9 input block
fn baseline_block_average(
    rgba_data: &[u8],
    width: u32,
    height: u32,
) -> Result<Vec<u8>, M2Error> {
    const OUTPUT_SIZE: u32 = 81;
    const BLOCK_SIZE: u32 = 9; // 729 / 81 = 9
    
    let mut output = Vec::with_capacity((OUTPUT_SIZE * OUTPUT_SIZE * 4) as usize);
    
    // Process each output pixel
    for out_y in 0..OUTPUT_SIZE {
        for out_x in 0..OUTPUT_SIZE {
            // Calculate input block boundaries
            let in_x_start = out_x * BLOCK_SIZE;
            let in_y_start = out_y * BLOCK_SIZE;
            let in_x_end = cmp::min(in_x_start + BLOCK_SIZE, width);
            let in_y_end = cmp::min(in_y_start + BLOCK_SIZE, height);
            
            // Accumulate block values
            let mut r_sum = 0u32;
            let mut g_sum = 0u32;
            let mut b_sum = 0u32;
            let mut a_sum = 0u32;
            let mut pixel_count = 0u32;
            
            for in_y in in_y_start..in_y_end {
                for in_x in in_x_start..in_x_end {
                    let idx = ((in_y * width + in_x) * 4) as usize;
                    r_sum += rgba_data[idx] as u32;
                    g_sum += rgba_data[idx + 1] as u32;
                    b_sum += rgba_data[idx + 2] as u32;
                    a_sum += rgba_data[idx + 3] as u32;
                    pixel_count += 1;
                }
            }
            
            // Average and write output pixel
            if pixel_count > 0 {
                output.push((r_sum / pixel_count) as u8);
                output.push((g_sum / pixel_count) as u8);
                output.push((b_sum / pixel_count) as u8);
                output.push((a_sum / pixel_count) as u8);
            } else {
                // Should never happen with our fixed dimensions
                output.extend_from_slice(&[0, 0, 0, 255]);
            }
        }
    }
    
    Ok(output)
}

/// Get version string for debugging
pub fn get_m2_version() -> String {
    init_logger();
    "1.0.0-baseline".to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_baseline_downsize() {
        // Create synthetic 729×729 pattern
        let mut input = vec![0u8; 729 * 729 * 4];
        
        // Fill with gradient pattern
        for y in 0..729 {
            for x in 0..729 {
                let idx = (y * 729 + x) * 4;
                input[idx] = (x * 255 / 729) as u8;     // R gradient
                input[idx + 1] = (y * 255 / 729) as u8; // G gradient
                input[idx + 2] = 128;                   // B constant
                input[idx + 3] = 255;                   // A opaque
            }
        }
        
        // Downsize
        let result = m2_downsize_9x9_cpu(input, 729, 729).unwrap();
        
        // Verify output dimensions
        assert_eq!(result.len(), 81 * 81 * 4);
        
        // Check first pixel (should be near 0,0,128,255)
        assert!(result[0] < 20);  // R near 0
        assert!(result[1] < 20);  // G near 0
        assert!((result[2] as i32 - 128).abs() < 5); // B near 128
        assert_eq!(result[3], 255); // A opaque
    }
    
    #[test]
    fn test_invalid_dimensions() {
        let input = vec![0u8; 100 * 100 * 4];
        let result = m2_downsize_9x9_cpu(input, 100, 100);
        assert!(result.is_err());
    }
}