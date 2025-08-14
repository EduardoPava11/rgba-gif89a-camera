use sha2::{Digest, Sha256};
use std::collections::HashMap;

/// M1 Debug: Generate a stable signature for RGBA data
pub fn m1_debug_signature(rgba: Vec<u8>, w: u32, h: u32) -> String {
    let expected_size = (w * h * 4) as usize;
    
    if rgba.len() != expected_size {
        return format!("M1_RUST_SIG w={} h={} ERROR size_mismatch expected={} got={}", 
                      w, h, expected_size, rgba.len());
    }
    
    // SHA-256 of first 64KB or full buffer if smaller
    let mut hasher = Sha256::new();
    let hash_size = std::cmp::min(rgba.len(), 65536);
    hasher.update(&rgba[..hash_size]);
    let sha_result = hasher.finalize();
    let sha_hex = format!("{:x}", sha_result)[..16].to_string(); // First 16 chars
    
    // Calculate channel sums
    let mut r_sum: u64 = 0;
    let mut g_sum: u64 = 0;
    let mut b_sum: u64 = 0;
    let mut a_sum: u64 = 0;
    
    // Histogram bins for R channel
    let mut hist: HashMap<u8, u32> = HashMap::new();
    let hist_bins = [0u8, 64, 128, 192, 255];
    for &bin in &hist_bins {
        hist.insert(bin, 0);
    }
    
    for chunk in rgba.chunks_exact(4) {
        let r = chunk[0];
        let g = chunk[1];
        let b = chunk[2];
        let a = chunk[3];
        
        r_sum += r as u64;
        g_sum += g as u64;
        b_sum += b as u64;
        a_sum += a as u64;
        
        // Histogram: find closest bin for R channel
        let closest_bin = hist_bins.iter()
            .min_by_key(|&&bin| (bin as i16 - r as i16).abs())
            .unwrap();
        *hist.entry(*closest_bin).or_insert(0) += 1;
    }
    
    // Format histogram
    let hist_str = format!("hist[0,64,128,192,255]=[{},{},{},{},{}]", 
                          hist[&0], hist[&64], hist[&128], hist[&192], hist[&255]);
    
    format!("M1_RUST_SIG w={} h={} sha={} sum=({},{},{},{}) {}", 
           w, h, sha_hex, r_sum, g_sum, b_sum, a_sum, hist_str)
}

/// M1 Preview: Add visible verification markers to RGBA buffer
pub fn m1_preview_patch(mut rgba: Vec<u8>, w: u32, h: u32) -> Vec<u8> {
    let expected_size = (w * h * 4) as usize;
    
    if rgba.len() != expected_size || w == 0 || h == 0 {
        return rgba; // Safety check - return unmodified
    }
    
    let width = w as usize;
    let height = h as usize;
    
    // 1. Draw white border (1px)
    for x in 0..width {
        // Top border
        if let Some(idx) = get_pixel_index(x, 0, width) {
            if idx + 3 < rgba.len() {
                rgba[idx] = 255;     // R
                rgba[idx + 1] = 255; // G  
                rgba[idx + 2] = 255; // B
                rgba[idx + 3] = 255; // A
            }
        }
        
        // Bottom border
        if let Some(idx) = get_pixel_index(x, height - 1, width) {
            if idx + 3 < rgba.len() {
                rgba[idx] = 255;
                rgba[idx + 1] = 255;
                rgba[idx + 2] = 255;
                rgba[idx + 3] = 255;
            }
        }
    }
    
    for y in 0..height {
        // Left border
        if let Some(idx) = get_pixel_index(0, y, width) {
            if idx + 3 < rgba.len() {
                rgba[idx] = 255;
                rgba[idx + 1] = 255;
                rgba[idx + 2] = 255;
                rgba[idx + 3] = 255;
            }
        }
        
        // Right border  
        if let Some(idx) = get_pixel_index(width - 1, y, width) {
            if idx + 3 < rgba.len() {
                rgba[idx] = 255;
                rgba[idx + 1] = 255;
                rgba[idx + 2] = 255;
                rgba[idx + 3] = 255;
            }
        }
    }
    
    // 2. Red crosshair at center
    let center_x = width / 2;
    let center_y = height / 2;
    let crosshair_size = 20;
    
    // Horizontal line
    for offset in 0..crosshair_size {
        if center_x + offset < width {
            if let Some(idx) = get_pixel_index(center_x + offset, center_y, width) {
                if idx + 3 < rgba.len() {
                    rgba[idx] = 255;     // R
                    rgba[idx + 1] = 0;   // G
                    rgba[idx + 2] = 0;   // B
                    rgba[idx + 3] = 255; // A
                }
            }
        }
        
        if offset <= center_x {
            if let Some(idx) = get_pixel_index(center_x - offset, center_y, width) {
                if idx + 3 < rgba.len() {
                    rgba[idx] = 255;
                    rgba[idx + 1] = 0;
                    rgba[idx + 2] = 0;
                    rgba[idx + 3] = 255;
                }
            }
        }
    }
    
    // Vertical line
    for offset in 0..crosshair_size {
        if center_y + offset < height {
            if let Some(idx) = get_pixel_index(center_x, center_y + offset, width) {
                if idx + 3 < rgba.len() {
                    rgba[idx] = 255;
                    rgba[idx + 1] = 0;
                    rgba[idx + 2] = 0;
                    rgba[idx + 3] = 255;
                }
            }
        }
        
        if offset <= center_y {
            if let Some(idx) = get_pixel_index(center_x, center_y - offset, width) {
                if idx + 3 < rgba.len() {
                    rgba[idx] = 255;
                    rgba[idx + 1] = 0;
                    rgba[idx + 2] = 0;
                    rgba[idx + 3] = 255;
                }
            }
        }
    }
    
    // 3. Three colored squares (30x30 each)
    let square_size = 30;
    let colors = [
        (255u8, 0u8, 0u8),   // Red
        (0u8, 255u8, 0u8),   // Green  
        (0u8, 0u8, 255u8),   // Blue
    ];
    
    let positions = [
        (50, 50),           // Top-left area
        (width - 80, 50),   // Top-right area
        (50, height - 80),  // Bottom-left area
    ];
    
    for (color_idx, &(start_x, start_y)) in positions.iter().enumerate() {
        if color_idx < colors.len() {
            let (r, g, b) = colors[color_idx];
            
            for dy in 0..square_size {
                for dx in 0..square_size {
                    let x = start_x + dx;
                    let y = start_y + dy;
                    
                    if x < width && y < height {
                        if let Some(idx) = get_pixel_index(x, y, width) {
                            if idx + 3 < rgba.len() {
                                rgba[idx] = r;
                                rgba[idx + 1] = g;
                                rgba[idx + 2] = b;
                                rgba[idx + 3] = 255; // Full alpha
                            }
                        }
                    }
                }
            }
        }
    }
    
    rgba // Return the modified buffer
}

#[inline]
fn get_pixel_index(x: usize, y: usize, width: usize) -> Option<usize> {
    if x < width {
        Some((y * width + x) * 4)
    } else {
        None
    }
}
