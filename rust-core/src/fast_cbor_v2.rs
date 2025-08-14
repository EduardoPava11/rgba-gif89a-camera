/// Fast CBOR V2 writer for M1 milestone with enhanced metadata
/// Uses CborFrameV2 for better color fidelity and metadata tracking
use crate::cbor_frame_v2::{CborFrameV2, FrameMetadata, ColorSpace};
use crate::GifPipeError;
use std::fs::{self, File};
use std::io::{BufWriter, Write};
use std::path::Path;
use std::time::Instant;

/// Fast CBOR V2 writer with enhanced color space support
pub struct FastCborV2Writer;

impl FastCborV2Writer {
    /// Write RGBA frame to CBOR V2 file with metadata
    /// 
    /// # Arguments
    /// * `rgba_data` - Raw RGBA bytes from camera (may have stride)
    /// * `width` - Frame width in pixels
    /// * `height` - Frame height in pixels  
    /// * `stride` - Row stride in bytes (may be > width*4)
    /// * `frame_index` - Frame index (0-80 for 81 frames)
    /// * `timestamp_ms` - Capture timestamp in milliseconds
    /// * `metadata` - Camera metadata for the frame
    /// * `output_path` - Full path to output CBOR file
    /// 
    /// # Returns
    /// * `Ok(write_time_ms)` - Time taken to write in milliseconds
    /// * `Err(GifPipeError)` - On write failure
    pub fn write_frame(
        rgba_data: &[u8],
        width: u16,
        height: u16,
        stride: u32,
        frame_index: u16,
        timestamp_ms: u64,
        metadata: FrameMetadata,
        output_path: &str,
    ) -> Result<u32, GifPipeError> {
        let start = Instant::now();
        
        // Create parent directory if needed
        if let Some(parent) = Path::new(output_path).parent() {
            fs::create_dir_all(parent).map_err(|e| {
                GifPipeError::IoError(format!("Failed to create directory: {}", e))
            })?;
        }
        
        // Create CBOR frame V2 with stride handling
        let frame = CborFrameV2::from_camera_data(
            rgba_data,
            width,
            height,
            stride,
            frame_index,
            timestamp_ms,
            metadata,
        ).map_err(|e| GifPipeError::FrameProcessingError(e))?;
        
        // Verify integrity
        if !frame.verify_integrity() {
            log::warn!("Frame {} failed integrity check", frame_index);
        }
        
        // Log quality metrics for first few frames
        if frame_index < 3 {
            let report = frame.validate_quality();
            log::info!(
                "M1_V2 frame {} quality: clipped={:.2}%, alpha={:.2}%, dynamic_range={:.2}",
                frame_index,
                report.clipped_ratio * 100.0,
                report.alpha_usage * 100.0,
                report.dynamic_range
            );
            
            if !report.is_valid {
                log::warn!("Frame {} has quality issues", frame_index);
            }
        }
        
        // Open file with buffered writer for better performance
        let file = File::create(output_path).map_err(|e| {
            GifPipeError::IoError(format!("Failed to create file {}: {}", output_path, e))
        })?;
        
        // Use 64KB buffer for efficient writes
        let mut writer = BufWriter::with_capacity(65536, file);
        
        // Serialize directly to writer using ciborium
        let cbor_data = frame.to_cbor().map_err(|e| {
            GifPipeError::IoError(format!("Failed to serialize CBOR: {}", e))
        })?;
        
        writer.write_all(&cbor_data).map_err(|e| {
            GifPipeError::IoError(format!("Failed to write CBOR: {}", e))
        })?;
        
        // Ensure all data is flushed to disk
        writer.flush().map_err(|e| {
            GifPipeError::IoError(format!("Failed to flush file: {}", e))
        })?;
        
        let elapsed_ms = start.elapsed().as_millis() as u32;
        
        // Log performance
        log::debug!(
            "M1_V2 frame {} written in {}ms ({:.1}MB)",
            frame_index,
            elapsed_ms,
            cbor_data.len() as f32 / 1_048_576.0
        );
        
        Ok(elapsed_ms)
    }
    
    /// Write frame with automatic metadata extraction
    pub fn write_frame_auto(
        rgba_data: &[u8],
        width: u16,
        height: u16,
        stride: u32,
        frame_index: u16,
        timestamp_ms: u64,
        output_path: &str,
    ) -> Result<u32, GifPipeError> {
        // Use default metadata when not available
        let metadata = FrameMetadata::default();
        
        Self::write_frame(
            rgba_data,
            width,
            height,
            stride,
            frame_index,
            timestamp_ms,
            metadata,
            output_path,
        )
    }
    
    /// Batch process multiple frames
    pub fn write_frames_batch(
        frames: Vec<(Vec<u8>, u16, u16, u32, u16, u64, FrameMetadata)>,
        output_dir: &str,
    ) -> Result<Vec<u32>, GifPipeError> {
        let mut times = Vec::with_capacity(frames.len());
        
        for (rgba, width, height, stride, index, timestamp, metadata) in frames {
            let output_path = format!("{}/frame_{:03}.cbor", output_dir, index);
            let time = Self::write_frame(
                &rgba,
                width,
                height,
                stride,
                index,
                timestamp,
                metadata,
                &output_path,
            )?;
            times.push(time);
        }
        
        Ok(times)
    }
    
    /// Verify a CBOR V2 file
    pub fn verify_file(path: &str) -> Result<bool, GifPipeError> {
        let data = std::fs::read(path).map_err(|e| {
            GifPipeError::IoError(format!("Failed to read file: {}", e))
        })?;
        
        let frame = CborFrameV2::from_cbor(&data).map_err(|e| {
            GifPipeError::FrameProcessingError(format!("Failed to parse CBOR: {}", e))
        })?;
        
        Ok(frame.verify_integrity())
    }
}

/// UniFFI-exposed functions for Android integration
/// These are the main entry points from Kotlin

/// Write a single CBOR V2 frame with metadata
pub fn write_cbor_frame_v2(
    rgba_data: Vec<u8>,
    width: u16,
    height: u16,
    stride: u32,
    frame_index: u16,
    timestamp_ms: u64,
    // Metadata fields (flattened for UniFFI)
    exposure_time_ns: u64,
    iso_sensitivity: u32,
    focal_length_mm: f32,
    aperture_f_stop: f32,
    color_temperature: u32,
    tint_correction: i16,
    sensor_timestamp: u64,
    rotation_degrees: u16,
    is_mirrored: bool,
    output_path: String,
) -> Result<u32, GifPipeError> {
    let metadata = FrameMetadata {
        exposure_time_ns,
        iso_sensitivity,
        focal_length_mm,
        aperture_f_stop,
        color_temperature,
        tint_correction,
        sensor_timestamp,
        rotation_degrees,
        is_mirrored,
    };
    
    FastCborV2Writer::write_frame(
        &rgba_data,
        width,
        height,
        stride,
        frame_index,
        timestamp_ms,
        metadata,
        &output_path,
    )
}

/// Simplified version without metadata
pub fn write_cbor_frame_v2_simple(
    rgba_data: Vec<u8>,
    width: u16,
    height: u16,
    stride: u32,
    frame_index: u16,
    timestamp_ms: u64,
    output_path: String,
) -> Result<u32, GifPipeError> {
    FastCborV2Writer::write_frame_auto(
        &rgba_data,
        width,
        height,
        stride,
        frame_index,
        timestamp_ms,
        &output_path,
    )
}

/// Verify CBOR V2 file integrity
pub fn verify_cbor_v2_file(path: String) -> Result<bool, GifPipeError> {
    FastCborV2Writer::verify_file(&path)
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_write_and_verify() {
        let rgba = vec![128; 729 * 729 * 4];
        let metadata = FrameMetadata::default();
        
        let temp_file = "/tmp/test_frame.cbor";
        
        let write_time = FastCborV2Writer::write_frame(
            &rgba,
            729,
            729,
            729 * 4,
            0,
            1234567890,
            metadata,
            temp_file,
        ).unwrap();
        
        assert!(write_time > 0);
        
        // Verify the file
        let is_valid = FastCborV2Writer::verify_file(temp_file).unwrap();
        assert!(is_valid);
        
        // Clean up
        std::fs::remove_file(temp_file).ok();
    }
}