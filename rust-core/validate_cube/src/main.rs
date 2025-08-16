use common_types::QuantizedCubeData;
use std::fs;
use std::path::Path;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    env_logger::init();
    
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 2 {
        eprintln!("Usage: validate_cube <path_to_quantized_data.json>");
        std::process::exit(1);
    }
    
    let data_path = Path::new(&args[1]);
    let json_data = fs::read_to_string(data_path)?;
    let cube_data: QuantizedCubeData = serde_json::from_str(&json_data)?;
    
    println!("=== 81×81×81 Cube Validation Report ===\n");
    
    // 1. Frame count validation
    println!("✓ Frame Count: {}/81", cube_data.indexed_frames.len());
    assert_eq!(cube_data.indexed_frames.len(), 81, "Must have exactly 81 frames");
    
    // 2. Frame dimensions validation
    for (idx, frame) in cube_data.indexed_frames.iter().enumerate() {
        assert_eq!(frame.len(), 81 * 81, "Frame {} must be 81×81 pixels", idx);
    }
    println!("✓ All frames are 81×81 pixels");
    
    // 3. Global palette validation
    let palette_colors = cube_data.global_palette_rgb.len() / 3;
    println!("✓ Global Palette: {} colors", palette_colors);
    assert!(palette_colors <= 256, "Palette must be ≤256 colors");
    
    // 4. Index validation
    let max_index = (palette_colors - 1) as u8;
    for (frame_idx, frame) in cube_data.indexed_frames.iter().enumerate() {
        for (pixel_idx, &index) in frame.iter().enumerate() {
            assert!(
                index <= max_index,
                "Frame {} pixel {} has invalid index {}",
                frame_idx, pixel_idx, index
            );
        }
    }
    println!("✓ All indices are valid");
    
    // 5. Temporal coherence validation
    println!("\n=== Temporal Coherence ===");
    println!("Palette Stability: {:.2}%", cube_data.palette_stability * 100.0);
    
    if cube_data.palette_stability < 0.85 {
        eprintln!("⚠️ Warning: Low palette stability for cube coherence");
    }
    
    // 6. Palette usage analysis
    println!("\n=== Palette Usage ===");
    let mut global_usage = vec![0u32; palette_colors];
    for frame in &cube_data.indexed_frames {
        for &index in frame {
            global_usage[index as usize] += 1;
        }
    }
    
    let unused_colors = global_usage.iter().filter(|&&count| count == 0).count();
    let usage_percent = ((palette_colors - unused_colors) as f32 / palette_colors as f32) * 100.0;
    println!("Colors Used: {}/{} ({:.1}%)", palette_colors - unused_colors, palette_colors, usage_percent);
    
    if unused_colors > (palette_colors / 5) {  // >20% unused
        eprintln!("⚠️ Warning: {} colors unused (poor palette utilization)", unused_colors);
    }
    
    // 7. Quality metrics
    println!("\n=== Quality Metrics ===");
    println!("Mean ΔE (Oklab): {:.2}", cube_data.mean_delta_e);
    println!("P95 ΔE (Oklab): {:.2}", cube_data.p95_delta_e);
    
    println!("\n✅ Cube validation complete!");
    
    Ok(())
}