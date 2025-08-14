/// CBOR frame format for minimal, future-proof serialization
use serde::{Deserialize, Serialize};

/// CBOR frame schema v1
/// Minimal but extensible format for frame data
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CborFrame {
    /// Schema version
    pub v: u32,
    
    /// Timestamp in milliseconds
    pub ts: u64,
    
    /// Frame width
    pub w: u32,
    
    /// Frame height
    pub h: u32,
    
    /// Pixel format (always "RGBA8888" for v1)
    pub fmt: String,
    
    /// Row stride in bytes (may be > w*4 due to padding)
    pub stride: u32,
    
    /// Whether alpha is premultiplied (should be false)
    pub premul: bool,
    
    /// Color space (typically "sRGB")
    pub colorspace: String,
    
    /// Raw RGBA bytes (may be stride-packed)
    #[serde(with = "serde_bytes")]
    pub rgba: Vec<u8>,
}

impl CborFrame {
    /// Create a new CBOR frame
    pub fn new(
        width: u32,
        height: u32,
        rgba_data: Vec<u8>,
        stride: u32,
        timestamp_ms: u64,
    ) -> Self {
        Self {
            v: 1,
            ts: timestamp_ms,
            w: width,
            h: height,
            fmt: "RGBA8888".to_string(),
            stride,
            premul: false,
            colorspace: "sRGB".to_string(),
            rgba: rgba_data,
        }
    }
    
    /// Verify alpha channel usage
    /// Returns percentage of pixels with alpha != 255
    pub fn verify_alpha(&self) -> f32 {
        let mut non_opaque = 0u32;
        let total_pixels = self.w * self.h;
        
        // Handle stride-packed data
        for y in 0..self.h {
            let row_start = (y * self.stride) as usize;
            for x in 0..self.w {
                let pixel_offset = row_start + (x * 4) as usize;
                if pixel_offset + 3 < self.rgba.len() {
                    let alpha = self.rgba[pixel_offset + 3];
                    if alpha != 255 {
                        non_opaque += 1;
                    }
                }
            }
        }
        
        (non_opaque as f32 / total_pixels as f32) * 100.0
    }
    
    /// Extract RGBA data without stride padding
    /// Returns tightly packed RGBA bytes
    pub fn get_packed_rgba(&self) -> Vec<u8> {
        if self.stride == self.w * 4 {
            // No padding, return as-is
            self.rgba.clone()
        } else {
            // Remove row padding
            let mut packed = Vec::with_capacity((self.w * self.h * 4) as usize);
            for y in 0..self.h {
                let row_start = (y * self.stride) as usize;
                let row_end = row_start + (self.w * 4) as usize;
                if row_end <= self.rgba.len() {
                    packed.extend_from_slice(&self.rgba[row_start..row_end]);
                }
            }
            packed
        }
    }
    
    /// Serialize to CBOR bytes using ciborium (faster)
    pub fn to_cbor(&self) -> Result<Vec<u8>, ciborium::ser::Error<std::io::Error>> {
        let mut buf = Vec::new();
        ciborium::into_writer(self, &mut buf)?;
        Ok(buf)
    }
    
    /// Deserialize from CBOR bytes using ciborium
    pub fn from_cbor(data: &[u8]) -> Result<Self, ciborium::de::Error<std::io::Error>> {
        ciborium::from_reader(data)
    }
}

/// Runtime alpha verification probe
pub struct AlphaProbe {
    frames_checked: u32,
    total_non_opaque_percent: f32,
}

impl AlphaProbe {
    pub fn new() -> Self {
        Self {
            frames_checked: 0,
            total_non_opaque_percent: 0.0,
        }
    }
    
    /// Check a frame and update statistics
    pub fn check_frame(&mut self, frame: &CborFrame) {
        let non_opaque = frame.verify_alpha();
        self.total_non_opaque_percent += non_opaque;
        self.frames_checked += 1;
        
        // Log once after checking first 5 frames
        if self.frames_checked == 5 {
            let avg = self.total_non_opaque_percent / 5.0;
            if avg < 0.01 {
                log::info!("Alpha verification: Camera outputs fully opaque frames (A=255)");
                log::info!("Proceeding with learned alpha map from neural network");
            } else {
                log::warn!("Alpha verification: {:.2}% pixels have A≠255", avg);
                log::warn!("Camera may be outputting meaningful alpha - investigate");
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_cbor_serialization() {
        let frame = CborFrame::new(
            729,
            729,
            vec![255; 729 * 729 * 4],
            729 * 4,
            1234567890,
        );
        
        let cbor_bytes = frame.to_cbor().unwrap();
        let decoded = CborFrame::from_cbor(&cbor_bytes).unwrap();
        
        assert_eq!(decoded.w, 729);
        assert_eq!(decoded.h, 729);
        assert_eq!(decoded.fmt, "RGBA8888");
        assert_eq!(decoded.v, 1);
    }
    
    #[test]
    fn test_alpha_verification() {
        // Create frame with all opaque pixels
        let mut rgba = vec![255; 729 * 729 * 4];
        let frame = CborFrame::new(729, 729, rgba.clone(), 729 * 4, 0);
        assert_eq!(frame.verify_alpha(), 0.0);
        
        // Set some pixels to non-opaque
        for i in 0..100 {
            rgba[i * 4 + 3] = 128; // Set alpha to 128
        }
        let frame2 = CborFrame::new(729, 729, rgba, 729 * 4, 0);
        let non_opaque_percent = frame2.verify_alpha();
        assert!(non_opaque_percent > 0.0);
    }
    
    #[test]
    fn test_stride_handling() {
        // Test with padding (stride > width * 4)
        let width = 100;
        let height = 100;
        let stride = 416; // 104 * 4 (4-pixel padding per row)
        
        let mut rgba_padded = vec![0u8; (stride * height) as usize];
        // Fill with test pattern
        for y in 0..height {
            for x in 0..width {
                let offset = (y * stride + x * 4) as usize;
                rgba_padded[offset] = x as u8;     // R
                rgba_padded[offset + 1] = y as u8; // G
                rgba_padded[offset + 2] = 255;     // B
                rgba_padded[offset + 3] = 255;     // A
            }
        }
        
        let frame = CborFrame::new(width, height, rgba_padded, stride, 0);
        let packed = frame.get_packed_rgba();
        
        // Verify packed data has correct size
        assert_eq!(packed.len(), (width * height * 4) as usize);
        
        // Verify first pixel
        assert_eq!(packed[0], 0);   // R
        assert_eq!(packed[1], 0);   // G
        assert_eq!(packed[2], 255); // B
        assert_eq!(packed[3], 255); // A
    }
}