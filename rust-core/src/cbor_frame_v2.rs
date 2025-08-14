/// CBOR Frame V2 - Enhanced format with color space tracking and metadata
/// Implements M1 specification for high-fidelity capture
use serde::{Deserialize, Serialize};
use crc32fast::Hasher;

/// CBOR Frame V2 with enhanced metadata and color space information
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CborFrameV2 {
    /// Schema version (0x0200 for v2.0)
    pub version: u16,
    
    /// Frame index (0-80 for 81 frames)
    pub frame_index: u16,
    
    /// Capture timestamp in milliseconds
    pub timestamp_ms: u64,
    
    /// CRC32 checksum of rgba_data
    pub checksum: u32,
    
    /// Frame dimensions
    pub width: u16,
    pub height: u16,
    
    /// Row stride in bytes (may be > width * 4)
    pub stride: u32,
    
    /// Pixel format (0x01 = RGBA8888)
    pub pixel_format: u32,
    
    /// Color space information
    pub color_space: ColorSpace,
    
    /// Frame metadata from camera
    pub metadata: FrameMetadata,
    
    /// Raw RGBA bytes (tightly packed, no stride)
    #[serde(with = "serde_bytes")]
    pub rgba_data: Vec<u8>,
}

/// Color space information for accurate color reproduction
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ColorSpace {
    /// Color space name ("sRGB", "Display-P3", "Rec.2020")
    pub space: String,
    
    /// Gamma value (2.2 for sRGB)
    pub gamma: f32,
    
    /// White point chromaticity [x, y]
    pub white_point: [f32; 2],
    
    /// RGB primaries chromaticity [[R_x, R_y], [G_x, G_y], [B_x, B_y]]
    pub primaries: [[f32; 2]; 3],
    
    /// Transfer function ("sRGB", "linear", "PQ", "HLG")
    pub transfer_function: String,
}

impl ColorSpace {
    /// Create default sRGB color space
    pub fn srgb_default() -> Self {
        Self {
            space: "sRGB".to_string(),
            gamma: 2.2,
            white_point: [0.3127, 0.3290], // D65
            primaries: [
                [0.640, 0.330], // Red
                [0.300, 0.600], // Green
                [0.150, 0.060], // Blue
            ],
            transfer_function: "sRGB".to_string(),
        }
    }
    
    /// Create Display-P3 color space
    pub fn display_p3() -> Self {
        Self {
            space: "Display-P3".to_string(),
            gamma: 2.2,
            white_point: [0.3127, 0.3290], // D65
            primaries: [
                [0.680, 0.320], // Red
                [0.265, 0.690], // Green
                [0.150, 0.060], // Blue
            ],
            transfer_function: "sRGB".to_string(),
        }
    }
}

/// Camera metadata for each frame
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FrameMetadata {
    /// Sensor exposure time in nanoseconds
    pub exposure_time_ns: u64,
    
    /// ISO sensitivity
    pub iso_sensitivity: u32,
    
    /// Lens focal length in millimeters
    pub focal_length_mm: f32,
    
    /// Aperture f-stop value
    pub aperture_f_stop: f32,
    
    /// Color temperature in Kelvin
    pub color_temperature: u32,
    
    /// Green-magenta tint correction
    pub tint_correction: i16,
    
    /// Hardware sensor timestamp
    pub sensor_timestamp: u64,
    
    /// Rotation in degrees (0, 90, 180, 270)
    pub rotation_degrees: u16,
    
    /// Whether image is mirrored (front camera)
    pub is_mirrored: bool,
}

impl Default for FrameMetadata {
    fn default() -> Self {
        Self {
            exposure_time_ns: 0,
            iso_sensitivity: 100,
            focal_length_mm: 4.0,
            aperture_f_stop: 2.0,
            color_temperature: 5500,
            tint_correction: 0,
            sensor_timestamp: 0,
            rotation_degrees: 0,
            is_mirrored: false,
        }
    }
}

impl CborFrameV2 {
    /// Create a new CBOR Frame V2
    pub fn new(
        width: u16,
        height: u16,
        rgba_data: Vec<u8>,
        stride: u32,
        frame_index: u16,
        timestamp_ms: u64,
    ) -> Self {
        // Calculate CRC32 of the RGBA data
        let mut hasher = Hasher::new();
        hasher.update(&rgba_data);
        let checksum = hasher.finalize();
        
        Self {
            version: 0x0200,
            frame_index,
            timestamp_ms,
            checksum,
            width,
            height,
            stride,
            pixel_format: 0x01, // RGBA8888
            color_space: ColorSpace::srgb_default(),
            metadata: FrameMetadata::default(),
            rgba_data,
        }
    }
    
    /// Create from raw camera data with stride handling
    pub fn from_camera_data(
        raw_data: &[u8],
        width: u16,
        height: u16,
        stride: u32,
        frame_index: u16,
        timestamp_ms: u64,
        metadata: FrameMetadata,
    ) -> Result<Self, String> {
        // Validate input
        let expected_size = (height as u32 * stride) as usize;
        if raw_data.len() < expected_size {
            return Err(format!(
                "Insufficient data: got {} bytes, expected at least {}",
                raw_data.len(),
                expected_size
            ));
        }
        
        // Remove stride padding if necessary
        let rgba_data = if stride == (width as u32 * 4) {
            // No padding, direct copy
            raw_data[..expected_size].to_vec()
        } else {
            // Remove row padding for tight packing
            let mut tight_data = Vec::with_capacity((width as usize * height as usize * 4));
            for y in 0..height {
                let row_start = (y as u32 * stride) as usize;
                let row_end = row_start + (width as usize * 4);
                if row_end <= raw_data.len() {
                    tight_data.extend_from_slice(&raw_data[row_start..row_end]);
                }
            }
            tight_data
        };
        
        // Calculate checksum
        let mut hasher = Hasher::new();
        hasher.update(&rgba_data);
        let checksum = hasher.finalize();
        
        Ok(Self {
            version: 0x0200,
            frame_index,
            timestamp_ms,
            checksum,
            width,
            height,
            stride: width as u32 * 4, // Store tight stride
            pixel_format: 0x01,
            color_space: ColorSpace::srgb_default(),
            metadata,
            rgba_data,
        })
    }
    
    /// Verify frame integrity using CRC32
    pub fn verify_integrity(&self) -> bool {
        let mut hasher = Hasher::new();
        hasher.update(&self.rgba_data);
        let calculated = hasher.finalize();
        calculated == self.checksum
    }
    
    /// Get frame statistics for quality validation
    pub fn get_statistics(&self) -> FrameStatistics {
        let mut stats = FrameStatistics::default();
        let pixel_count = (self.width as usize * self.height as usize);
        
        let mut r_sum = 0u64;
        let mut g_sum = 0u64;
        let mut b_sum = 0u64;
        let mut a_sum = 0u64;
        
        for i in 0..pixel_count {
            let idx = i * 4;
            let r = self.rgba_data[idx] as u64;
            let g = self.rgba_data[idx + 1] as u64;
            let b = self.rgba_data[idx + 2] as u64;
            let a = self.rgba_data[idx + 3] as u64;
            
            r_sum += r;
            g_sum += g;
            b_sum += b;
            a_sum += a;
            
            // Count clipped pixels
            if r == 0 || r == 255 { stats.clipped_pixels += 1; }
            if g == 0 || g == 255 { stats.clipped_pixels += 1; }
            if b == 0 || b == 255 { stats.clipped_pixels += 1; }
            
            // Count non-opaque pixels
            if a != 255 { stats.non_opaque_pixels += 1; }
            
            // Update histograms
            stats.r_histogram[(r / 16) as usize] += 1;
            stats.g_histogram[(g / 16) as usize] += 1;
            stats.b_histogram[(b / 16) as usize] += 1;
        }
        
        stats.avg_r = (r_sum / pixel_count as u64) as u8;
        stats.avg_g = (g_sum / pixel_count as u64) as u8;
        stats.avg_b = (b_sum / pixel_count as u64) as u8;
        stats.avg_a = (a_sum / pixel_count as u64) as u8;
        
        stats
    }
    
    /// Serialize to CBOR bytes
    pub fn to_cbor(&self) -> Result<Vec<u8>, ciborium::ser::Error<std::io::Error>> {
        let mut buf = Vec::with_capacity(self.rgba_data.len() + 1024);
        ciborium::into_writer(self, &mut buf)?;
        Ok(buf)
    }
    
    /// Deserialize from CBOR bytes
    pub fn from_cbor(data: &[u8]) -> Result<Self, ciborium::de::Error<std::io::Error>> {
        ciborium::from_reader(data)
    }
}

/// Frame quality statistics
#[derive(Debug, Default)]
pub struct FrameStatistics {
    pub avg_r: u8,
    pub avg_g: u8,
    pub avg_b: u8,
    pub avg_a: u8,
    pub clipped_pixels: u32,
    pub non_opaque_pixels: u32,
    pub r_histogram: [u32; 16], // 16 bins
    pub g_histogram: [u32; 16],
    pub b_histogram: [u32; 16],
}

/// Quality validation report
#[derive(Debug)]
pub struct QualityReport {
    pub clipped_ratio: f32,
    pub alpha_usage: f32,
    pub color_balance: [f32; 3], // R, G, B ratios
    pub dynamic_range: f32,
    pub is_valid: bool,
}

impl CborFrameV2 {
    /// Validate frame quality
    pub fn validate_quality(&self) -> QualityReport {
        let stats = self.get_statistics();
        let pixel_count = (self.width as u32 * self.height as u32) as f32;
        
        let clipped_ratio = (stats.clipped_pixels as f32) / (pixel_count * 3.0); // 3 channels
        let alpha_usage = (stats.non_opaque_pixels as f32) / pixel_count;
        
        let total_luminance = (stats.avg_r as f32 + stats.avg_g as f32 + stats.avg_b as f32);
        let color_balance = if total_luminance > 0.0 {
            [
                stats.avg_r as f32 / total_luminance,
                stats.avg_g as f32 / total_luminance,
                stats.avg_b as f32 / total_luminance,
            ]
        } else {
            [0.33, 0.33, 0.34]
        };
        
        // Calculate dynamic range from histogram
        let mut min_bin = 15;
        let mut max_bin = 0;
        for i in 0..16 {
            if stats.r_histogram[i] > 0 || stats.g_histogram[i] > 0 || stats.b_histogram[i] > 0 {
                min_bin = min_bin.min(i);
                max_bin = max_bin.max(i);
            }
        }
        let dynamic_range = (max_bin - min_bin) as f32 / 15.0;
        
        QualityReport {
            clipped_ratio,
            alpha_usage,
            color_balance,
            dynamic_range,
            is_valid: clipped_ratio < 0.05 && dynamic_range > 0.3,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_cbor_v2_creation() {
        let frame = CborFrameV2::new(
            729,
            729,
            vec![128; 729 * 729 * 4],
            729 * 4,
            0,
            1234567890,
        );
        
        assert_eq!(frame.version, 0x0200);
        assert_eq!(frame.width, 729);
        assert_eq!(frame.height, 729);
        assert!(frame.verify_integrity());
    }
    
    #[test]
    fn test_stride_removal() {
        let width = 100u16;
        let height = 100u16;
        let stride = 416u32; // 104 * 4 (padding)
        
        let mut raw_data = vec![0u8; (stride * height as u32) as usize];
        // Fill with test pattern
        for y in 0..height {
            for x in 0..width {
                let offset = (y as u32 * stride + x as u32 * 4) as usize;
                raw_data[offset] = x as u8;
                raw_data[offset + 1] = y as u8;
                raw_data[offset + 2] = 255;
                raw_data[offset + 3] = 255;
            }
        }
        
        let frame = CborFrameV2::from_camera_data(
            &raw_data,
            width,
            height,
            stride,
            0,
            0,
            FrameMetadata::default(),
        ).unwrap();
        
        // Should have tight packing
        assert_eq!(frame.rgba_data.len(), (width as usize * height as usize * 4));
        assert_eq!(frame.stride, width as u32 * 4);
        
        // Verify first pixel
        assert_eq!(frame.rgba_data[0], 0);   // R
        assert_eq!(frame.rgba_data[1], 0);   // G
        assert_eq!(frame.rgba_data[2], 255); // B
        assert_eq!(frame.rgba_data[3], 255); // A
    }
    
    #[test]
    fn test_quality_validation() {
        let mut rgba = vec![128; 729 * 729 * 4];
        
        // Add some clipped pixels
        for i in 0..100 {
            rgba[i * 4] = 255;     // Clip red
            rgba[i * 4 + 1] = 0;   // Clip green
        }
        
        let frame = CborFrameV2::new(729, 729, rgba, 729 * 4, 0, 0);
        let report = frame.validate_quality();
        
        assert!(report.clipped_ratio > 0.0);
        assert!(report.dynamic_range > 0.0);
    }
}