/// Fast CBOR writer for M1 milestone
/// Uses ciborium for efficient CBOR encoding with zero-copy where possible
use crate::cbor_frame::CborFrame;
use crate::GifPipeError;
use ciborium::into_writer;
use std::fs::{self, File};
use std::io::{BufWriter, Write};
use std::path::Path;
use std::time::Instant;

/// Fast CBOR writer with optimizations for Android
pub struct FastCborWriter;

impl FastCborWriter {
    /// Write RGBA frame directly to CBOR file with minimal allocations
    /// 
    /// # Arguments
    /// * `rgba_data` - Raw RGBA bytes from camera
    /// * `width` - Frame width in pixels
    /// * `height` - Frame height in pixels  
    /// * `stride` - Row stride in bytes (may be > width*4)
    /// * `timestamp_ms` - Capture timestamp in milliseconds
    /// * `output_path` - Full path to output CBOR file
    /// 
    /// # Returns
    /// * `Ok(write_time_ms)` - Time taken to write in milliseconds
    /// * `Err(GifPipeError)` - On write failure
    pub fn write_frame(
        rgba_data: Vec<u8>,
        width: u32,
        height: u32,
        stride: u32,
        timestamp_ms: u64,
        output_path: &str,
    ) -> Result<u32, GifPipeError> {
        let start = Instant::now();
        
        // Create parent directory if needed
        if let Some(parent) = Path::new(output_path).parent() {
            fs::create_dir_all(parent).map_err(|e| {
                GifPipeError::IoError(format!("Failed to create directory: {}", e))
            })?;
        }
        
        // Create CBOR frame
        let frame = CborFrame::new(width, height, rgba_data, stride, timestamp_ms);
        
        // Open file with buffered writer for better performance
        let file = File::create(output_path).map_err(|e| {
            GifPipeError::IoError(format!("Failed to create file {}: {}", output_path, e))
        })?;
        
        // Use 64KB buffer for efficient writes
        let mut writer = BufWriter::with_capacity(65536, file);
        
        // Serialize directly to writer using ciborium
        into_writer(&frame, &mut writer).map_err(|e| {
            GifPipeError::IoError(format!("Failed to write CBOR: {}", e))
        })?;
        
        // Ensure all data is flushed to disk
        writer.flush().map_err(|e| {
            GifPipeError::IoError(format!("Failed to flush file: {}", e))
        })?;
        
        let elapsed_ms = start.elapsed().as_millis() as u32;
        
        // Log performance for first few frames
        static mut FRAME_COUNT: u32 = 0;
        unsafe {
            if FRAME_COUNT < 5 {
                log::info!(
                    "Fast CBOR write: frame {} written in {}ms ({:.1}MB)",
                    FRAME_COUNT,
                    elapsed_ms,
                    (width * height * 4) as f32 / 1_048_576.0
                );
            }
            FRAME_COUNT += 1;
        }
        
        Ok(elapsed_ms)
    }
    
    /// Write frame with direct ByteBuffer from Android (zero-copy path)
    /// This avoids an extra allocation when receiving data from JNI
    pub fn write_frame_direct(
        rgba_ptr: *const u8,
        rgba_len: usize,
        width: u32,
        height: u32,
        stride: u32,
        timestamp_ms: u64,
        output_path: &str,
    ) -> Result<u32, GifPipeError> {
        // Safety: This function should only be called from JNI with valid pointer
        if rgba_ptr.is_null() || rgba_len == 0 {
            return Err(GifPipeError::InvalidConfiguration(
                "Invalid RGBA data pointer".to_string()
            ));
        }
        
        // Create a slice from the pointer and convert to Vec
        let rgba_slice = unsafe { std::slice::from_raw_parts(rgba_ptr, rgba_len) };
        let rgba_data = rgba_slice.to_vec();
        
        Self::write_frame(
            rgba_data,
            width,
            height,
            stride,
            timestamp_ms,
            output_path,
        )
    }
    
    /// Batch write multiple frames (for testing/benchmarking)
    pub fn write_frames_batch(
        frames: Vec<(Vec<u8>, u32, u32, u32, u64)>,
        output_dir: &str,
    ) -> Result<Vec<u32>, GifPipeError> {
        let mut times = Vec::with_capacity(frames.len());
        
        for (idx, (rgba, width, height, stride, timestamp)) in frames.into_iter().enumerate() {
            let output_path = format!("{}/frame_{:03}.cbor", output_dir, idx);
            let time = Self::write_frame(rgba, width, height, stride, timestamp, &output_path)?;
            times.push(time);
        }
        
        Ok(times)
    }
}

/// UniFFI-exposed functions for Android integration
/// These are the main entry points from Kotlin

/// Write a single CBOR frame from RGBA data
/// Returns write time in milliseconds for benchmarking
pub fn write_cbor_frame(
    rgba_data: Vec<u8>,
    width: u32,
    height: u32,
    stride: u32,
    timestamp_ms: u64,
    output_path: String,
) -> Result<u32, GifPipeError> {
    FastCborWriter::write_frame(rgba_data, width, height, stride, timestamp_ms, &output_path)
}

/// Initialize Android logging (call once at startup)
pub fn init_android_logger() {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("RustCBOR"),
    );
}

// Tests removed to avoid tempfile dependency