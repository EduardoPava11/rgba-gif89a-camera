use std::sync::{Arc, Mutex};
use std::path::Path;
use std::fs::File;
use std::io::Write;

use crate::{GifPipeError, downsizer::Downsampler9x9, quantizer::AlphaAwareQuantizer};
use image::{RgbaImage, DynamicImage};
use gif::{Frame as GifFrame, Encoder, Repeat};

/// Frame information from camera
#[derive(Debug, Clone)]
pub struct FrameInfo {
    pub width: u32,
    pub height: u32,
    pub timestamp_ms: u64,
}

/// Feedback from previous frame
#[derive(Debug, Clone, Default)]
pub struct Feedback {
    pub a_prev_81x81: Vec<u8>,      // Previous alpha map
    pub err_prev_81x81: Vec<u8>,    // Previous error map
    pub usage_prev_81x81: Vec<u8>,  // Previous usage heatmap
}

/// Session configuration
#[derive(Debug, Clone)]
pub struct SessionConfig {
    pub use_global_palette: bool,
    pub allow_palette_growth: bool,
    pub max_frames: u32,
    pub output_size: u32,
}

impl Default for SessionConfig {
    fn default() -> Self {
        Self {
            use_global_palette: true,
            allow_palette_growth: true,
            max_frames: 81,
            output_size: 81,
        }
    }
}

/// Pipeline statistics
#[derive(Debug, Clone, Default)]
pub struct PipelineStats {
    pub frames_processed: u32,
    pub palette_colors: u32,
    pub avg_processing_ms: f32,
    pub avg_quantization_error: f32,
}

/// Internal frame data
struct ProcessedFrame {
    rgb_81x81: Vec<u8>,
    alpha_81x81: Vec<u8>,
    indexed: Vec<u8>,
    palette: Vec<[u8; 3]>,
}

/// Main GIF pipeline
pub struct Pipeline {
    config: SessionConfig,
    downsampler: Arc<Mutex<Downsampler9x9>>,
    quantizer: Arc<Mutex<AlphaAwareQuantizer>>,
    frames: Arc<Mutex<Vec<ProcessedFrame>>>,
    feedback: Arc<Mutex<Feedback>>,
    stats: Arc<Mutex<PipelineStats>>,
}

impl Pipeline {
    /// Create new pipeline with configuration
    pub fn new(config: SessionConfig) -> Result<Self, GifPipeError> {
        // Validate configuration
        if config.output_size == 0 || config.output_size > 256 {
            return Err(GifPipeError::InvalidConfiguration(
                format!("Invalid output size: {}", config.output_size)
            ));
        }
        
        // Initialize components
        let downsampler = Downsampler9x9::new(config.output_size)
            .map_err(|e| GifPipeError::InitializationError(e.to_string()))?;
        
        let quantizer = AlphaAwareQuantizer::new(
            config.use_global_palette,
            config.allow_palette_growth,
        );
        
        Ok(Pipeline {
            config,
            downsampler: Arc::new(Mutex::new(downsampler)),
            quantizer: Arc::new(Mutex::new(quantizer)),
            frames: Arc::new(Mutex::new(Vec::new())),
            feedback: Arc::new(Mutex::new(Feedback::default())),
            stats: Arc::new(Mutex::new(PipelineStats::default())),
        })
    }
    
    /// Push RGBA frame for processing
    pub fn push_frame(&self, rgba: Vec<u8>, info: FrameInfo) -> Result<(), GifPipeError> {
        let start_time = std::time::Instant::now();
        
        // Validate input
        let expected_size = (info.width * info.height * 4) as usize;
        if rgba.len() != expected_size {
            return Err(GifPipeError::FrameProcessingError(
                format!("Invalid RGBA data size: {} != {}", rgba.len(), expected_size)
            ));
        }
        
        // Get current feedback
        let feedback = self.feedback.lock().unwrap().clone();
        
        // Downsample with 9×9 Go head
        let (rgb_81x81, alpha_81x81) = {
            let mut downsampler = self.downsampler.lock().unwrap();
            downsampler.process_frame(
                &rgba,
                info.width,
                info.height,
                &feedback.a_prev_81x81,
                &feedback.err_prev_81x81,
                &feedback.usage_prev_81x81,
            ).map_err(|e| GifPipeError::FrameProcessingError(e.to_string()))?
        };
        
        // Quantize with alpha awareness
        let (indexed, palette, error_map, usage_map) = {
            let mut quantizer = self.quantizer.lock().unwrap();
            quantizer.quantize_frame(
                &rgb_81x81,
                &alpha_81x81,
                self.config.output_size,
            ).map_err(|e| GifPipeError::QuantizationError(e.to_string()))?
        };
        
        // Store processed frame
        {
            let mut frames = self.frames.lock().unwrap();
            frames.push(ProcessedFrame {
                rgb_81x81: rgb_81x81.clone(),
                alpha_81x81: alpha_81x81.clone(),
                indexed,
                palette,
            });
        }
        
        // Update feedback for next frame
        {
            let mut fb = self.feedback.lock().unwrap();
            fb.a_prev_81x81 = alpha_81x81;
            fb.err_prev_81x81 = error_map;
            fb.usage_prev_81x81 = usage_map;
        }
        
        // Update statistics
        {
            let mut stats = self.stats.lock().unwrap();
            stats.frames_processed += 1;
            let processing_ms = start_time.elapsed().as_millis() as f32;
            stats.avg_processing_ms = 
                (stats.avg_processing_ms * (stats.frames_processed - 1) as f32 + processing_ms) 
                / stats.frames_processed as f32;
        }
        
        Ok(())
    }
    
    /// Finalize and write GIF to file
    pub fn finalize_to_path(&self, output_path: &str) -> Result<(), GifPipeError> {
        let frames = self.frames.lock().unwrap();
        
        if frames.is_empty() {
            return Err(GifPipeError::FrameProcessingError(
                "No frames to encode".to_string()
            ));
        }
        
        // Create output file
        let mut file = File::create(output_path)
            .map_err(|e| GifPipeError::IoError(e.to_string()))?;
        
        // Create GIF encoder
        let size = self.config.output_size as u16;
        let mut encoder = Encoder::new(&mut file, size, size, &[])
            .map_err(|e| GifPipeError::IoError(e.to_string()))?;
        
        // Set to loop forever
        encoder.set_repeat(Repeat::Infinite)
            .map_err(|e| GifPipeError::IoError(e.to_string()))?;
        
        // Write frames
        for (i, frame) in frames.iter().enumerate() {
            // Convert palette to flat array
            let mut palette_flat = Vec::with_capacity(frame.palette.len() * 3);
            for color in &frame.palette {
                palette_flat.extend_from_slice(color);
            }
            
            // Pad palette to 256 colors if needed
            while palette_flat.len() < 768 {
                palette_flat.extend_from_slice(&[0, 0, 0]);
            }
            
            // Create GIF frame
            let mut gif_frame = GifFrame::from_indexed_pixels(
                size,
                size,
                &frame.indexed,
                Some(&palette_flat),
            );
            
            // Set frame delay for 24fps (4.17 centiseconds ≈ 4 centiseconds)
            gif_frame.delay = 4;  // 4/100 = 0.04s = 25fps (closest to 24fps)
            
            // Write frame
            encoder.write_frame(&gif_frame)
                .map_err(|e| GifPipeError::IoError(e.to_string()))?;
        }
        
        // Update final stats
        {
            let mut stats = self.stats.lock().unwrap();
            if let Some(last_frame) = frames.last() {
                stats.palette_colors = last_frame.palette.len() as u32;
            }
        }
        
        Ok(())
    }
    
    /// Get latest feedback for debugging
    pub fn latest_feedback(&self) -> Option<Feedback> {
        Some(self.feedback.lock().unwrap().clone())
    }
    
    /// Get pipeline statistics
    pub fn get_stats(&self) -> PipelineStats {
        self.stats.lock().unwrap().clone()
    }
}