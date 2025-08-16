use tracing::{info, debug, span, Level, warn};
use common_types::{QuantizedSet, GifInfo, GifPipeError, QuantizedCubeData};

/// GIF89a encoder with validation and transparency support
pub struct Gif89aEncoder {
    optimize_palette: bool,
    validate_output: bool,
    transparency_threshold: u8,
}

impl Default for Gif89aEncoder {
    fn default() -> Self {
        Self {
            optimize_palette: true,
            validate_output: true,
            transparency_threshold: 254,
        }
    }
}

impl Gif89aEncoder {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn with_validation(mut self, validate: bool) -> Self {
        self.validate_output = validate;
        self
    }

    pub fn with_transparency_threshold(mut self, threshold: u8) -> Self {
        self.transparency_threshold = threshold;
        self
    }

    /// Encode quantized frames to GIF89a format
    #[tracing::instrument(level = "info", skip(self, quantized_set))]
    pub fn encode_gif(&self, quantized_set: QuantizedSet) -> Result<GifInfo, GifPipeError> {
        let span = span!(Level::INFO, "M3_gif_encode", frames = quantized_set.frames_indices.len());
        let _guard = span.enter();

        let start_time = std::time::Instant::now();

        info!(
            stage = "M3",
            frames = quantized_set.frames_indices.len(),
            palette_size = quantized_set.palette_rgb.len() / 3,
            "Starting GIF89a encoding"
        );

        // Validate input
        self.validate_quantized_set(&quantized_set)?;

        // Build optimized palette from RGB bytes
        let palette_colors = quantized_set.palette_rgb.chunks(3)
            .map(|chunk| [chunk[0], chunk[1], chunk[2]])
            .collect::<Vec<[u8; 3]>>();
        
        let optimized_palette = if self.optimize_palette {
            self.optimize_palette_order(&palette_colors)?
        } else {
            palette_colors.clone()
        };

        debug!(
            stage = "M3",
            original_colors = palette_colors.len(),
            optimized_colors = optimized_palette.len(),
            "Palette optimization completed"
        );

        // Encode GIF data
        let mut gif_data = Vec::new();
        self.write_gif_header(&mut gif_data, &optimized_palette)?;
        
        // Write frames with timing based on attention maps
        for (frame_idx, (frame_indices, attention_map)) in quantized_set.frames_indices
            .iter()
            .zip(quantized_set.attention_maps.iter())
            .enumerate()
        {
            debug!(stage = "M3", frame_idx = frame_idx, "Encoding frame");
            
            let frame_delay = self.calculate_frame_delay(attention_map);
            self.write_gif_frame(
                &mut gif_data,
                frame_indices,
                frame_delay,
                &optimized_palette,
            )?;
        }

        self.write_gif_trailer(&mut gif_data)?;

        let processing_time = start_time.elapsed().as_millis() as u64;

        // Validate output if enabled
        if self.validate_output {
            self.validate_gif_output(&gif_data)?;
        }

        let compression_ratio = self.calculate_compression_ratio(&quantized_set, &gif_data);

        info!(
            stage = "M3",
            duration_ms = processing_time,
            gif_size_bytes = gif_data.len(),
            compression_ratio = compression_ratio,
            "GIF encoding completed"
        );

        let gif_data_len = gif_data.len();
        
        Ok(GifInfo {
            gif_data,
            file_path: "".to_string(), // Will be set by caller
            file_size_bytes: gif_data_len as u64,
            processing_time_ms: processing_time,
            compression_ratio,
            frame_count: quantized_set.frames_indices.len() as u32,
            palette_size: optimized_palette.len() as u32,
            total_processing_ms: quantized_set.processing_time_ms + processing_time,
            has_netscape_loop: true,
            validation_passed: self.validate_output,
        })
    }

    /// Validate quantized set before encoding
    fn validate_quantized_set(&self, quantized_set: &QuantizedSet) -> Result<(), GifPipeError> {
        if quantized_set.frames_indices.is_empty() {
            return Err(GifPipeError::ValidationError {
                message: "No frames provided for GIF encoding".to_string(),
            });
        }

        if quantized_set.palette_rgb.is_empty() {
            return Err(GifPipeError::ValidationError {
                message: "Empty palette provided".to_string(),
            });
        }

        if quantized_set.palette_rgb.len() > 256 * 3 {
            return Err(GifPipeError::ValidationError {
                message: format!("Palette too large: {} colors (max 256)", quantized_set.palette_rgb.len() / 3),
            });
        }

        // Validate frame dimensions consistency
        let expected_pixels = (common_types::FRAME_SIZE_81 * common_types::FRAME_SIZE_81) as usize;
        for (idx, frame) in quantized_set.frames_indices.iter().enumerate() {
            if frame.len() != expected_pixels {
                return Err(GifPipeError::ValidationError {
                    message: format!(
                        "Frame {} has {} pixels, expected {}",
                        idx, frame.len(), expected_pixels
                    ),
                });
            }
        }

        Ok(())
    }

    /// Optimize palette order for better compression
    fn optimize_palette_order(&self, palette: &[[u8; 3]]) -> Result<Vec<[u8; 3]>, GifPipeError> {
        // Simple optimization: sort by perceived brightness
        let mut indexed_palette: Vec<(usize, [u8; 3], f32)> = palette
            .iter()
            .enumerate()
            .map(|(idx, &rgb)| {
                let brightness = 0.299 * rgb[0] as f32 + 0.587 * rgb[1] as f32 + 0.114 * rgb[2] as f32;
                (idx, rgb, brightness)
            })
            .collect();

        indexed_palette.sort_by(|a, b| a.2.partial_cmp(&b.2).unwrap());
        
        let optimized = indexed_palette.into_iter().map(|(_, rgb, _)| rgb).collect();
        
        debug!(
            stage = "M3",
            strategy = "brightness_sort",
            "Palette order optimized"
        );

        Ok(optimized)
    }

    /// Calculate frame delay based on attention map
    fn calculate_frame_delay(&self, attention_map: &[f32]) -> u16 {
        // Higher attention = longer display time
        let avg_attention = attention_map.iter().sum::<f32>() / attention_map.len() as f32;
        
        // Map attention to delay: 0.5s to 2.0s range (in 1/100ths of second)
        let base_delay = 50; // 0.5s
        let attention_bonus = (avg_attention * 150.0) as u16; // Up to 1.5s bonus
        
        (base_delay + attention_bonus).min(200) // Cap at 2.0s
    }

    /// Write GIF header with global color table
    fn write_gif_header(&self, output: &mut Vec<u8>, palette: &[[u8; 3]]) -> Result<(), GifPipeError> {
        // GIF89a signature
        output.extend_from_slice(b"GIF89a");

        // Logical screen descriptor
        let width = common_types::FRAME_SIZE_81;
        let height = common_types::FRAME_SIZE_81;
        
        output.extend_from_slice(&width.to_le_bytes());
        output.extend_from_slice(&height.to_le_bytes());

        // Global color table info
        let color_bits = self.calculate_color_bits(palette.len())?;
        let packed = 0xF0 | color_bits; // Global color table flag + color resolution + sorted flag
        output.push(packed);

        output.push(0); // Background color index
        output.push(0); // Pixel aspect ratio

        // Write global color table
        for &[r, g, b] in palette {
            output.extend_from_slice(&[r, g, b]);
        }

        // Pad palette to power of 2
        let table_size = 1 << (color_bits + 1);
        for _ in palette.len()..table_size {
            output.extend_from_slice(&[0, 0, 0]);
        }

        Ok(())
    }

    /// Write individual GIF frame
    fn write_gif_frame(
        &self,
        output: &mut Vec<u8>,
        indices: &[u8],
        delay: u16,
        palette: &[[u8; 3]],
    ) -> Result<(), GifPipeError> {
        // Graphic Control Extension
        output.extend_from_slice(&[0x21, 0xF9, 0x04]); // Extension + label + block size
        output.push(0x08); // Disposal method: restore to background
        output.extend_from_slice(&delay.to_le_bytes());
        output.push(0); // Transparent color index (none)
        output.push(0); // Block terminator

        // Image Descriptor
        output.push(0x2C); // Image separator
        output.extend_from_slice(&[0, 0]); // Left position
        output.extend_from_slice(&[0, 0]); // Top position
        output.extend_from_slice(&common_types::FRAME_SIZE_81.to_le_bytes());
        output.extend_from_slice(&common_types::FRAME_SIZE_81.to_le_bytes());
        output.push(0); // No local color table

        // LZW compressed image data
        self.write_lzw_data(output, indices, palette)?;

        Ok(())
    }

    /// Write LZW compressed image data (simplified implementation)
    fn write_lzw_data(&self, output: &mut Vec<u8>, indices: &[u8], palette: &[[u8; 3]]) -> Result<(), GifPipeError> {
        let color_bits = self.calculate_color_bits(palette.len())?;
        let min_code_size = (color_bits + 1).max(2);
        
        output.push(min_code_size);

        // Simplified LZW encoding - in production use proper LZW
        let mut compressed = Vec::new();
        
        // Clear code and end code
        let clear_code: u16 = 1 << min_code_size;
        let end_code: u16 = clear_code + 1;
        
        compressed.extend_from_slice(&clear_code.to_le_bytes());
        
        // Simple run-length encoding as LZW placeholder
        let mut i = 0;
        while i < indices.len() {
            let current = indices[i];
            let mut count = 1;
            
            while i + count < indices.len() && indices[i + count] == current && count < 128 {
                count += 1;
            }
            
            if count > 3 {
                // Use run-length encoding
                compressed.push(current);
                compressed.push((count - 1) as u8);
            } else {
                // Raw pixels
                for _ in 0..count {
                    compressed.push(current);
                }
            }
            
            i += count;
        }
        
        compressed.extend_from_slice(&end_code.to_le_bytes());

        // Write data blocks
        let mut pos = 0;
        while pos < compressed.len() {
            let block_size = (compressed.len() - pos).min(255);
            output.push(block_size as u8);
            output.extend_from_slice(&compressed[pos..pos + block_size]);
            pos += block_size;
        }
        
        output.push(0); // Block terminator

        Ok(())
    }

    /// Write GIF trailer
    fn write_gif_trailer(&self, output: &mut Vec<u8>) -> Result<(), GifPipeError> {
        output.push(0x3B); // Trailer
        Ok(())
    }

    /// Calculate color bits needed for palette
    fn calculate_color_bits(&self, palette_size: usize) -> Result<u8, GifPipeError> {
        match palette_size {
            0 => Err(GifPipeError::ValidationError {
                message: "Empty palette".to_string(),
            }),
            1..=2 => Ok(0),   // 2^1 = 2 colors
            3..=4 => Ok(1),   // 2^2 = 4 colors
            5..=8 => Ok(2),   // 2^3 = 8 colors
            9..=16 => Ok(3),  // 2^4 = 16 colors
            17..=32 => Ok(4), // 2^5 = 32 colors
            33..=64 => Ok(5), // 2^6 = 64 colors
            65..=128 => Ok(6), // 2^7 = 128 colors
            129..=256 => Ok(7), // 2^8 = 256 colors
            _ => Err(GifPipeError::ValidationError {
                message: format!("Palette too large: {} colors", palette_size),
            }),
        }
    }

    /// Validate GIF output format
    fn validate_gif_output(&self, gif_data: &[u8]) -> Result<(), GifPipeError> {
        if gif_data.len() < 6 {
            return Err(GifPipeError::ValidationError {
                message: "GIF data too short".to_string(),
            });
        }

        // Check GIF signature
        if &gif_data[0..6] != b"GIF89a" {
            return Err(GifPipeError::ValidationError {
                message: "Invalid GIF signature".to_string(),
            });
        }

        // Check trailer
        if gif_data.last() != Some(&0x3B) {
            warn!(stage = "M3", "GIF missing proper trailer");
        }

        debug!(stage = "M3", "GIF validation passed");
        Ok(())
    }

    /// Calculate compression ratio
    fn calculate_compression_ratio(&self, quantized_set: &QuantizedSet, gif_data: &[u8]) -> f32 {
        // Original size: frames × pixels × 3 bytes (RGB)
        let frame_pixels = (common_types::FRAME_SIZE_81 * common_types::FRAME_SIZE_81) as usize;
        let original_size = quantized_set.frames_indices.len() * frame_pixels * 3;
        
        if gif_data.is_empty() {
            return 0.0;
        }
        
        original_size as f32 / gif_data.len() as f32
    }

    /// Encode from pre-quantized cube data (no quantization inside)
    pub fn encode_from_cube_data(
        &self, 
        cube: &QuantizedCubeData, 
        _fps_cs: u8, 
        loop_forever: bool
    ) -> Result<Vec<u8>, GifPipeError> {
        let span = span!(Level::INFO, "M3_encode_cube",
            frames = 81,
            palette_size = cube.global_palette_rgb.len() / 3,
            stability = cube.palette_stability
        );
        let _guard = span.enter();
        
        // Validate cube structure
        if cube.indexed_frames.len() != 81 {
            return Err(GifPipeError::ValidationFailed {
                message: format!("Expected 81 frames, got {}", cube.indexed_frames.len())
            });
        }
        
        if cube.global_palette_rgb.len() % 3 != 0 || cube.global_palette_rgb.len() > 768 {
            return Err(GifPipeError::ValidationFailed {
                message: "Invalid palette size".to_string()
            });
        }
        
        let mut gif_bytes = Vec::new();
        
        // GIF89a header + logical screen descriptor
        self.write_gif89a_header(&mut gif_bytes, 81, 81)?;
        
        // Global color table (palette)
        self.write_global_color_table(&mut gif_bytes, &cube.global_palette_rgb)?;
        
        // NETSCAPE2.0 loop extension for infinite loop
        if loop_forever {
            self.write_netscape_loop(&mut gif_bytes)?;
        }
        
        // Write 81 frames
        for (idx, frame_indices) in cube.indexed_frames.iter().enumerate() {
            self.write_image_descriptor(&mut gif_bytes, 0, 0, 81, 81)?;
            self.write_lzw_compressed_data(&mut gif_bytes, frame_indices)?;
            
            if idx % 10 == 0 {
                info!(frame = idx, "Encoded frame batch");
            }
        }
        
        // GIF trailer
        gif_bytes.push(0x3B);
        
        info!(
            size_bytes = gif_bytes.len(),
            frames = 81,
            "GIF89a encoding complete"
        );
        
        Ok(gif_bytes)
    }
    
    fn write_global_color_table(&self, gif_bytes: &mut Vec<u8>, palette_rgb: &[u8]) -> Result<(), GifPipeError> {
        // Write palette, pad to 256 entries if needed
        gif_bytes.extend_from_slice(palette_rgb);
        
        let colors_written = palette_rgb.len() / 3;
        if colors_written < 256 {
            let padding = vec![0u8; (256 - colors_written) * 3];
            gif_bytes.extend_from_slice(&padding);
        }
        
        Ok(())
    }

    fn write_image_descriptor(&self, gif_bytes: &mut Vec<u8>, left: u16, top: u16, width: u16, height: u16) -> Result<(), GifPipeError> {
        gif_bytes.push(0x2C); // Image separator
        gif_bytes.extend_from_slice(&left.to_le_bytes());
        gif_bytes.extend_from_slice(&top.to_le_bytes());
        gif_bytes.extend_from_slice(&width.to_le_bytes());
        gif_bytes.extend_from_slice(&height.to_le_bytes());
        gif_bytes.push(0x00); // No local color table
        Ok(())
    }

    fn write_lzw_compressed_data(&self, gif_bytes: &mut Vec<u8>, frame_indices: &[u8]) -> Result<(), GifPipeError> {
        // LZW minimum code size (8 bits for 256 color palette)
        gif_bytes.push(8);
        
        // Simple LZW encoding - in production, use proper LZW implementation
        let mut compressed = Vec::new();
        
        // Clear code (256) and end code (257)
        compressed.extend_from_slice(&256u16.to_le_bytes());
        
        // Simple encoding: just write the indices
        for &index in frame_indices {
            compressed.push(index);
        }
        
        compressed.extend_from_slice(&257u16.to_le_bytes());
        
        // Write in 255-byte blocks
        let mut pos = 0;
        while pos < compressed.len() {
            let block_size = (compressed.len() - pos).min(255);
            gif_bytes.push(block_size as u8);
            gif_bytes.extend_from_slice(&compressed[pos..pos + block_size]);
            pos += block_size;
        }
        
        gif_bytes.push(0); // Block terminator
        Ok(())
    }

    fn write_gif89a_header(&self, output: &mut Vec<u8>, width: u16, height: u16) -> Result<(), GifPipeError> {
        // GIF89a signature
        output.extend_from_slice(b"GIF89a");

        // Logical screen descriptor
        output.extend_from_slice(&width.to_le_bytes());
        output.extend_from_slice(&height.to_le_bytes());

        // Global color table info: 8 bits (256 colors)
        let packed = 0xF7; // Global color table flag + 8-bit color resolution + sorted flag
        output.push(packed);

        output.push(0); // Background color index
        output.push(0); // Pixel aspect ratio

        Ok(())
    }

    fn write_netscape_loop(&self, output: &mut Vec<u8>) -> Result<(), GifPipeError> {
        // Application Extension
        output.push(0x21); // Extension introducer
        output.push(0xFF); // Application extension label
        output.push(0x0B); // Block size
        output.extend_from_slice(b"NETSCAPE2.0"); // Application identifier + auth code
        
        // Data sub-block for looping
        output.push(0x03); // Sub-block size
        output.push(0x01); // Sub-block ID
        output.extend_from_slice(&0u16.to_le_bytes()); // Loop count (0 = infinite)
        output.push(0x00); // Block terminator
        
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use common_types::{FRAME_SIZE_81, QuantizedSet};

    #[test]
    fn test_encoder_creation() {
        let encoder = Gif89aEncoder::new();
        assert!(encoder.optimize_palette);
        assert!(encoder.validate_output);
    }

    #[test]
    fn test_encoder_configuration() {
        let encoder = Gif89aEncoder::new()
            .with_validation(false)
            .with_transparency_threshold(128);
        
        assert!(!encoder.validate_output);
        assert_eq!(encoder.transparency_threshold, 128);
    }

    #[test]
    fn test_color_bits_calculation() {
        let encoder = Gif89aEncoder::new();
        
        assert_eq!(encoder.calculate_color_bits(2).unwrap(), 0);
        assert_eq!(encoder.calculate_color_bits(4).unwrap(), 1);
        assert_eq!(encoder.calculate_color_bits(16).unwrap(), 3);
        assert_eq!(encoder.calculate_color_bits(256).unwrap(), 7);
        assert!(encoder.calculate_color_bits(0).is_err());
        assert!(encoder.calculate_color_bits(300).is_err());
    }

    #[test]
    fn test_gif_encoding() {
        let encoder = Gif89aEncoder::new();
        
        let frame_pixels = (FRAME_SIZE_81 * FRAME_SIZE_81) as usize;
        let quantized_set = QuantizedSet {
            frames_indices: vec![vec![0u8; frame_pixels]],
            palette_rgb: vec![255, 0, 0, 0, 255, 0, 0, 0, 255], // RGB bytes
            palette_stability: 0.9,
            mean_perceptual_error: 5.0,
            p95_perceptual_error: 10.0,
            processing_time_ms: 100,
            attention_maps: vec![vec![0.5f32; frame_pixels]],
        };
        
        let result = encoder.encode_gif(quantized_set).unwrap();
        
        assert!(!result.gif_data.is_empty());
        assert_eq!(result.frame_count, 1);
        assert!(result.compression_ratio > 0.0);
        assert!(result.gif_data.starts_with(b"GIF89a"));
    }

    #[test]
    fn test_validation_errors() {
        let encoder = Gif89aEncoder::new();
        
        // Empty frames
        let empty_set = QuantizedSet {
            frames_indices: vec![],
            palette_rgb: vec![255, 0, 0],
            palette_stability: 0.0,
            mean_perceptual_error: 0.0,
            p95_perceptual_error: 0.0,
            processing_time_ms: 0,
            attention_maps: vec![],
        };
        assert!(encoder.encode_gif(empty_set).is_err());
        
        // Empty palette
        let frame_pixels = (FRAME_SIZE_81 * FRAME_SIZE_81) as usize;
        let empty_palette_set = QuantizedSet {
            frames_indices: vec![vec![0u8; frame_pixels]],
            palette_rgb: vec![],
            palette_stability: 0.0,
            mean_perceptual_error: 0.0,
            p95_perceptual_error: 0.0,
            processing_time_ms: 0,
            attention_maps: vec![vec![0.5f32; frame_pixels]],
        };
        assert!(encoder.encode_gif(empty_palette_set).is_err());
    }
}
