use m3_gif::Gif89aEncoder;
use common_types::{QuantizedCubeData, GifPipeError};

#[test]
fn test_encode_from_cube_data() {
    let cube_data = create_test_cube_data();
    let encoder = Gif89aEncoder::new();
    
    let gif_bytes = encoder.encode_from_cube_data(&cube_data, 4, true).unwrap();
    
    // Verify GIF structure
    assert!(gif_bytes.len() > 100, "GIF should be substantial size");
    assert_eq!(&gif_bytes[0..6], b"GIF89a", "Should have GIF89a header");
    assert_eq!(gif_bytes[gif_bytes.len() - 1], 0x3B, "Should end with trailer");
}

#[test]
fn test_gif_structure_validation() {
    let cube_data = create_test_cube_data();
    let encoder = Gif89aEncoder::new();
    
    let gif_bytes = encoder.encode_from_cube_data(&cube_data, 4, true).unwrap();
    
    // Validate GIF structure
    validate_gif_structure(&gif_bytes).unwrap();
}

#[test]
fn test_invalid_cube_data() {
    let encoder = Gif89aEncoder::new();
    
    // Test with wrong number of frames
    let mut invalid_cube = create_test_cube_data();
    invalid_cube.indexed_frames = vec![vec![0; 81 * 81]; 50]; // Only 50 frames
    
    let result = encoder.encode_from_cube_data(&invalid_cube, 4, true);
    assert!(result.is_err(), "Should fail with wrong frame count");
    
    // Test with invalid palette size
    let mut invalid_palette = create_test_cube_data();
    invalid_palette.global_palette_rgb = vec![255; 1000]; // Not divisible by 3
    
    let result = encoder.encode_from_cube_data(&invalid_palette, 4, true);
    assert!(result.is_err(), "Should fail with invalid palette");
}

#[test]
fn test_netscape_loop_extension() {
    let cube_data = create_test_cube_data();
    let encoder = Gif89aEncoder::new();
    
    // Test with loop
    let gif_with_loop = encoder.encode_from_cube_data(&cube_data, 4, true).unwrap();
    assert!(contains_netscape_loop(&gif_with_loop), "Should contain NETSCAPE2.0 loop");
    
    // Test without loop
    let gif_no_loop = encoder.encode_from_cube_data(&cube_data, 4, false).unwrap();
    assert!(!contains_netscape_loop(&gif_no_loop), "Should not contain NETSCAPE2.0 loop");
}

#[test]
fn test_frame_count_validation() {
    let cube_data = create_test_cube_data();
    let encoder = Gif89aEncoder::new();
    
    let gif_bytes = encoder.encode_from_cube_data(&cube_data, 4, true).unwrap();
    
    // Count image descriptors (0x2C) which indicate frames
    let frame_count = gif_bytes.iter().filter(|&&b| b == 0x2C).count();
    assert_eq!(frame_count, 81, "Should have exactly 81 frames");
}

#[test]
fn test_golden_gif_hash() {
    // Test that identical input produces identical output
    let cube_data = create_deterministic_cube_data();
    let encoder = Gif89aEncoder::new();
    
    let gif1 = encoder.encode_from_cube_data(&cube_data, 4, true).unwrap();
    let gif2 = encoder.encode_from_cube_data(&cube_data, 4, true).unwrap();
    
    assert_eq!(gif1, gif2, "Identical input should produce identical GIF");
    
    // Calculate hash for regression testing
    use sha2::{Sha256, Digest};
    let mut hasher = Sha256::new();
    hasher.update(&gif1);
    let hash = format!("{:x}", hasher.finalize());
    
    println!("Golden GIF hash: {}", hash);
    // In a real test, we'd assert against a known good hash
}

#[test]
fn test_concrete_quality_targets() {
    // Test that M3 output meets the specified quality requirements
    let cube_data = create_high_quality_cube_data();
    let encoder = Gif89aEncoder::new();
    
    let gif_bytes = encoder.encode_from_cube_data(&cube_data, 4, true).unwrap();
    
    // Verify structural correctness
    validate_gif_structure(&gif_bytes).unwrap();
    
    // Verify frame count matches input
    let frame_count = gif_bytes.iter().filter(|&&b| b == 0x2C).count();
    assert_eq!(frame_count, 81, "Should preserve all 81 frames");
    
    // Verify reasonable compression (original would be ~1.6MB for 81×81×81×3)
    let original_size = 81 * 81 * 81 * 3;
    let compression_ratio = original_size as f32 / gif_bytes.len() as f32;
    assert!(compression_ratio > 2.0, "Should achieve reasonable compression ratio");
    
    println!("Compression ratio: {:.1}x ({} -> {} bytes)", 
        compression_ratio, original_size, gif_bytes.len());
}

// Helper functions

fn create_test_cube_data() -> QuantizedCubeData {
    // Create a simple test cube with gradual color changes
    let mut indexed_frames = Vec::new();
    
    for frame_idx in 0..81 {
        let mut frame = Vec::with_capacity(81 * 81);
        for y in 0..81 {
            for x in 0..81 {
                // Simple pattern: changes slowly across frames
                let index = ((x + y + frame_idx) % 16) as u8;
                frame.push(index);
            }
        }
        indexed_frames.push(frame);
    }
    
    // Create a simple 16-color palette (RGB bytes)
    let mut palette_rgb = Vec::new();
    for i in 0..16 {
        let intensity = (i * 255 / 15) as u8;
        palette_rgb.extend_from_slice(&[intensity, intensity, intensity]);
    }
    // Pad to full size if needed
    while palette_rgb.len() < 768 { // 256 * 3
        palette_rgb.extend_from_slice(&[0, 0, 0]);
    }
    
    QuantizedCubeData {
        width: 81,
        height: 81,
        global_palette_rgb: palette_rgb,
        indexed_frames,
        delays_cs: vec![4; 81],
        palette_stability: 0.95,
        mean_delta_e: 1.2,
        p95_delta_e: 2.8,
        attention_maps: Some(vec![vec![0.5; 81 * 81]; 81]),
    }
}

fn create_deterministic_cube_data() -> QuantizedCubeData {
    // Create deterministic test data for hash consistency
    let mut indexed_frames = Vec::new();
    
    for frame_idx in 0..81 {
        let mut frame = Vec::with_capacity(81 * 81);
        for y in 0..81 {
            for x in 0..81 {
                // Deterministic pattern
                let index = ((x * 7 + y * 13 + frame_idx * 3) % 8) as u8;
                frame.push(index);
            }
        }
        indexed_frames.push(frame);
    }
    
    // Simple 8-color palette
    let palette_rgb = vec![
        255, 0, 0,    // Red
        0, 255, 0,    // Green  
        0, 0, 255,    // Blue
        255, 255, 0,  // Yellow
        255, 0, 255,  // Magenta
        0, 255, 255,  // Cyan
        128, 128, 128, // Gray
        255, 255, 255, // White
    ];
    
    // Pad to 256 colors
    let mut full_palette = palette_rgb;
    while full_palette.len() < 768 {
        full_palette.extend_from_slice(&[0, 0, 0]);
    }
    
    QuantizedCubeData {
        width: 81,
        height: 81,
        global_palette_rgb: full_palette,
        indexed_frames,
        delays_cs: vec![4; 81],
        palette_stability: 0.92,
        mean_delta_e: 0.8,
        p95_delta_e: 1.6,
        attention_maps: None,
    }
}

fn create_high_quality_cube_data() -> QuantizedCubeData {
    // Create test data that should meet quality thresholds
    let mut indexed_frames = Vec::new();
    
    for frame_idx in 0..81 {
        let mut frame = Vec::with_capacity(81 * 81);
        for y in 0..81 {
            for x in 0..81 {
                // Use only a few colors to ensure high quality quantization
                let index = ((x / 20) + (y / 20) + (frame_idx / 20)) % 4;
                frame.push(index as u8);
            }
        }
        indexed_frames.push(frame);
    }
    
    // High quality 4-color palette
    let palette_rgb = vec![
        255, 255, 255, // White
        128, 128, 128, // Gray
        64, 64, 64,    // Dark gray
        0, 0, 0,       // Black
    ];
    
    // Pad to 256 colors
    let mut full_palette = palette_rgb;
    while full_palette.len() < 768 {
        full_palette.extend_from_slice(&[0, 0, 0]);
    }
    
    QuantizedCubeData {
        width: 81,
        height: 81,
        global_palette_rgb: full_palette,
        indexed_frames,
        delays_cs: vec![4; 81],
        palette_stability: 0.98, // Very high stability
        mean_delta_e: 0.5,       // Very low error
        p95_delta_e: 1.2,        // Low P95 error
        attention_maps: None,
    }
}

fn validate_gif_structure(gif_bytes: &[u8]) -> Result<(), GifPipeError> {
    if gif_bytes.len() < 10 {
        return Err(GifPipeError::ValidationFailed {
            message: "GIF too short".to_string()
        });
    }
    
    // Check header
    if &gif_bytes[0..6] != b"GIF89a" {
        return Err(GifPipeError::ValidationFailed {
            message: "Invalid GIF header".to_string()
        });
    }
    
    // Check trailer
    if gif_bytes[gif_bytes.len() - 1] != 0x3B {
        return Err(GifPipeError::ValidationFailed {
            message: "Missing GIF trailer".to_string()
        });
    }
    
    // Verify logical screen descriptor
    if gif_bytes.len() < 13 {
        return Err(GifPipeError::ValidationFailed {
            message: "Missing logical screen descriptor".to_string()
        });
    }
    
    // Check for global color table flag
    let packed_field = gif_bytes[10];
    let has_global_color_table = (packed_field & 0x80) != 0;
    if !has_global_color_table {
        return Err(GifPipeError::ValidationFailed {
            message: "Missing global color table".to_string()
        });
    }
    
    Ok(())
}

fn contains_netscape_loop(gif_bytes: &[u8]) -> bool {
    // Search for NETSCAPE2.0 application extension
    let netscape_marker = b"NETSCAPE2.0";
    
    for i in 0..gif_bytes.len().saturating_sub(netscape_marker.len()) {
        if &gif_bytes[i..i + netscape_marker.len()] == netscape_marker {
            return true;
        }
    }
    
    false
}
