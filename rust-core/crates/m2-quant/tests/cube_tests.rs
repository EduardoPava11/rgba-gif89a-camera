#[cfg(test)]
mod cube_tests {
    use m2_quant::OklabQuantizer;
    use common_types::{Frames81Rgb, QuantizedCubeData};
    
    fn generate_test_frames_81() -> Frames81Rgb {
        // Generate 81 frames with gradual color shift
        let mut frames_rgb = Vec::new();
        let mut attention_maps = Vec::new();
        
        for frame_idx in 0..81 {
            let mut frame = Vec::new();
            let mut attention = Vec::new();
            
            // Create gradient that shifts across frames
            for y in 0..81 {
                for x in 0..81 {
                    let r = ((x * 255 / 81) + frame_idx * 2) as u8;
                    let g = ((y * 255 / 81) + frame_idx) as u8;
                    let b = ((frame_idx * 255 / 81)) as u8;
                    
                    frame.push(r);
                    frame.push(g);
                    frame.push(b);
                    
                    // Simple attention pattern
                    let dist_to_center = ((x as f32 - 40.5).powi(2) + (y as f32 - 40.5).powi(2)).sqrt();
                    attention.push(1.0 - (dist_to_center / 57.0).min(1.0));
                }
            }
            
            frames_rgb.push(frame);
            attention_maps.push(attention);
        }
        
        Frames81Rgb {
            frames_rgb,
            attention_maps,
            processing_time_ms: 0,
        }
    }
    
    fn generate_gradual_shift_frames() -> Frames81Rgb {
        // Generate frames with very gradual color shift for temporal coherence testing
        let mut frames_rgb = Vec::new();
        let mut attention_maps = Vec::new();
        
        for frame_idx in 0..81 {
            let mut frame = Vec::new();
            let mut attention = Vec::new();
            
            for y in 0..81 {
                for x in 0..81 {
                    // Very gradual shift - should have high temporal coherence
                    let r = ((x * 200 / 81) + 28) as u8;
                    let g = ((y * 200 / 81) + 28) as u8;
                    let b = ((frame_idx * 50 / 81) + 100) as u8;
                    
                    frame.push(r);
                    frame.push(g);
                    frame.push(b);
                    
                    attention.push(0.5); // Uniform attention
                }
            }
            
            frames_rgb.push(frame);
            attention_maps.push(attention);
        }
        
        Frames81Rgb {
            frames_rgb,
            attention_maps,
            processing_time_ms: 0,
        }
    }
    
    #[test]
    fn test_global_palette_shared_across_frames() {
        // Generate test frames with known color distribution
        let frames = generate_test_frames_81();
        
        let quantizer = OklabQuantizer::new(256);
        let cube_data = quantizer.quantize_for_cube(frames).unwrap();
        
        // Verify single global palette
        assert_eq!(cube_data.indexed_frames.len(), 81);
        assert!(cube_data.global_palette_rgb.len() <= 256 * 3);
        assert!(cube_data.global_palette_rgb.len() >= 3); // At least one color
        
        // Verify all frames use same palette
        let max_index = (cube_data.global_palette_rgb.len() / 3) - 1;
        for (frame_idx, frame) in cube_data.indexed_frames.iter().enumerate() {
            assert_eq!(frame.len(), 81 * 81, "Frame {} should be 81×81 pixels", frame_idx);
            
            for (pixel_idx, &index) in frame.iter().enumerate() {
                assert!(
                    (index as usize) <= max_index,
                    "Frame {} pixel {} has index {} but palette only has {} colors",
                    frame_idx, pixel_idx, index, max_index + 1
                );
            }
        }
    }
    
    #[test]
    fn test_temporal_coherence_stability() {
        // Create frames with gradual color shift
        let frames = generate_gradual_shift_frames();
        
        let quantizer = OklabQuantizer::new(256);
        let cube_data = quantizer.quantize_for_cube(frames).unwrap();
        
        // Verify high temporal stability using new API
        assert!(
            cube_data.palette_stability > 0.8,
            "Palette stability {} too low for cube (should be > 0.8)",
            cube_data.palette_stability
        );
        
        // Verify reasonable perceptual error
        assert!(
            cube_data.mean_delta_e < 5.0,
            "Mean ΔE {} too high (should be < 5.0)",
            cube_data.mean_delta_e
        );
        
        assert!(
            cube_data.p95_delta_e < 10.0,
            "P95 ΔE {} too high (should be < 10.0)",
            cube_data.p95_delta_e
        );
    }
    
    #[test]
    fn test_cube_visualization_compatibility() {
        let frames = generate_test_frames_81();
        let quantizer = OklabQuantizer::new(256);
        let cube_data = quantizer.quantize_for_cube(frames).unwrap();
        
        // Serialize to JSON (what Kotlin will receive)
        let json = serde_json::to_string(&cube_data).unwrap();
        assert!(!json.is_empty());
        
        // Deserialize back
        let restored: QuantizedCubeData = serde_json::from_str(&json).unwrap();
        
        // Verify data integrity
        assert_eq!(restored.indexed_frames.len(), 81);
        assert_eq!(restored.global_palette_rgb.len(), cube_data.global_palette_rgb.len());
        assert_eq!(restored.width, 81);
        assert_eq!(restored.height, 81);
        
        // Verify each frame is correct size for 81×81
        for frame in &restored.indexed_frames {
            assert_eq!(frame.len(), 81 * 81);
        }
        
        // Verify delays array
        assert_eq!(restored.delays_cs.len(), 81);
        for delay in &restored.delays_cs {
            assert!(*delay > 0, "Frame delay should be positive");
        }
    }
    
    #[test]
    fn test_concrete_perceptual_thresholds() {
        // Test with controlled input that should meet quality targets
        let frames = generate_high_quality_test_frames();
        
        let quantizer = OklabQuantizer::new(256);
        let cube_data = quantizer.quantize_for_cube(frames).unwrap();
        
        // Concrete quality thresholds as specified in work order
        assert!(
            cube_data.mean_delta_e < 2.0,
            "Mean ΔE {} exceeds target threshold of 2.0",
            cube_data.mean_delta_e
        );
        
        assert!(
            cube_data.p95_delta_e < 5.0,
            "P95 ΔE {} exceeds target threshold of 5.0",
            cube_data.p95_delta_e
        );
        
        assert!(
            cube_data.palette_stability > 0.85,
            "Palette stability {} below target threshold of 0.85",
            cube_data.palette_stability
        );
    }
    
    #[test]
    fn test_palette_utilization() {
        let frames = generate_diverse_color_frames();
        let quantizer = OklabQuantizer::new(256);
        let cube_data = quantizer.quantize_for_cube(frames).unwrap();
        
        // Count unique indices used across all frames
        let mut palette_usage = vec![false; 256];
        for frame in &cube_data.indexed_frames {
            for &index in frame {
                palette_usage[index as usize] = true;
            }
        }
        
        let colors_used = palette_usage.iter().filter(|&&used| used).count();
        let utilization = colors_used as f32 / 256.0;
        
        // Should use a reasonable portion of the palette
        assert!(
            utilization > 0.1,
            "Palette utilization {:.1}% too low (should be > 10%)",
            utilization * 100.0
        );
        
        println!("Palette utilization: {:.1}% ({} colors used)", 
            utilization * 100.0, colors_used);
    }

    fn generate_high_quality_test_frames() -> Frames81Rgb {
        let mut frames_rgb = Vec::new();
        let mut attention_maps = Vec::new();
        
        // Generate frames designed to meet quality thresholds
        for frame_idx in 0..81 {
            let mut frame = Vec::with_capacity(81 * 81 * 3);
            let mut attention = Vec::with_capacity(81 * 81);
            
            // Use a limited color palette to ensure low quantization error
            let base_colors = [
                [255, 0, 0],    // Red
                [0, 255, 0],    // Green
                [0, 0, 255],    // Blue
                [255, 255, 0],  // Yellow
                [255, 0, 255],  // Magenta
                [0, 255, 255],  // Cyan
                [128, 128, 128], // Gray
                [255, 255, 255], // White
            ];
            
            for y in 0..81 {
                for x in 0..81 {
                    // Choose color based on position to create patterns
                    let color_idx = ((x / 10) + (y / 10) + frame_idx / 10) % base_colors.len();
                    let color = base_colors[color_idx];
                    
                    frame.extend_from_slice(&color);
                    attention.push(0.9); // High attention
                }
            }
            
            frames_rgb.push(frame);
            attention_maps.push(attention);
        }
        
        Frames81Rgb {
            frames_rgb,
            attention_maps,
            processing_time_ms: 0,
        }
    }
    
    fn generate_diverse_color_frames() -> Frames81Rgb {
        let mut frames_rgb = Vec::new();
        let mut attention_maps = Vec::new();
        
        // Generate frames with diverse colors to test palette utilization
        for frame_idx in 0..81 {
            let mut frame = Vec::with_capacity(81 * 81 * 3);
            let mut attention = Vec::with_capacity(81 * 81);
            
            for y in 0..81 {
                for x in 0..81 {
                    // Generate diverse colors using different patterns
                    let r = ((x * 3 + y * 7 + frame_idx * 11) % 256) as u8;
                    let g = ((x * 5 + y * 13 + frame_idx * 17) % 256) as u8;
                    let b = ((x * 7 + y * 19 + frame_idx * 23) % 256) as u8;
                    
                    frame.extend_from_slice(&[r, g, b]);
                    attention.push(0.6); // Medium attention
                }
            }
            
            frames_rgb.push(frame);
            attention_maps.push(attention);
        }
        
        Frames81Rgb {
            frames_rgb,
            attention_maps,
            processing_time_ms: 0,
        }
    }
}
