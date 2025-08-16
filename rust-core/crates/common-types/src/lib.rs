use serde::{Serialize, Deserialize};
use thiserror::Error;

#[cfg(feature = "ffi")]
uniffi::setup_scaffolding!();

/// Strategy-B Core Constants
pub const FRAME_SIZE_729: u16 = 729;
pub const FRAME_SIZE_81: u16 = 81;  
pub const PALETTE_SIZE: u16 = 256;
pub const EXPECTED_FRAME_COUNT: u16 = 81;

/// Complete quantization result with quality metrics and output artifacts
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QuantResult {
    pub width: u16,
    pub height: u16,
    pub frames_indices: Vec<Vec<u8>>,  // Frame indices for each frame
    pub palette_rgb: Vec<u8>,          // RGB palette (3*palette_size bytes)
    pub delays_cs: Vec<u8>,            // Frame delays in centiseconds
    pub gif_path: String,              // Output GIF file path
    pub gif_size_bytes: u64,           // GIF file size in bytes
    pub compression_ratio: f32,        // Compression ratio
    pub processing_time_ms: u64,       // Processing time in milliseconds
    pub unique_colors: u16,            // Number of unique colors used
    pub palette_stability: f32,        // Palette stability metric
    pub mean_perceptual_error: f32,    // Mean ΔE perceptual error  
    pub p95_perceptual_error: f32,     // 95th percentile ΔE error
    pub refinement_used: bool,         // Whether palette refinement was used
}

/// RGB frames at 81x81 resolution with attention maps
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Frames81Rgb {
    pub frames_rgb: Vec<Vec<u8>>,      // 81x81x3 RGB frames
    pub attention_maps: Vec<Vec<f32>>, // 81x81 attention weights per frame
    pub processing_time_ms: u64,
}

/// Global palette with quantization metadata
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QuantizedSet {
    pub palette_rgb: Vec<u8>,          // 256x3 RGB palette
    pub frames_indices: Vec<Vec<u8>>,  // Frame indices for each frame
    pub palette_stability: f32,        // Temporal stability metric
    pub mean_perceptual_error: f32,    // Mean ΔE error
    pub p95_perceptual_error: f32,     // P95 ΔE error
    pub processing_time_ms: u64,
    pub attention_maps: Vec<Vec<f32>>, // Attention maps from M1
}

/// GIF metadata and validation results
#[derive(Debug, Clone, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct GifInfo {
    pub file_path: String,
    pub file_size_bytes: u64,
    pub frame_count: u32,
    pub palette_size: u32,
    pub has_netscape_loop: bool,
    pub compression_ratio: f32,
    pub validation_passed: bool,
    pub processing_time_ms: u64,
    pub total_processing_ms: u64,
    pub gif_data: Vec<u8>,  // Raw GIF bytes
}

/// Quantized cube data for WYSIWYG preview and GIF encoding
#[derive(Debug, Clone, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct QuantizedCubeData {
    pub width: u16,  // 81
    pub height: u16, // 81
    pub global_palette_rgb: Vec<u8>,     // 256 * 3 RGB bytes
    pub indexed_frames: Vec<Vec<u8>>,    // 81 frames of 81*81 indices
    pub delays_cs: Vec<u8>,              // length 81, centiseconds per frame
    pub palette_stability: f32,          // [0..1] temporal coherence
    pub mean_delta_e: f32,               // Oklab ΔE mean
    pub p95_delta_e: f32,                // Oklab ΔE p95
    #[cfg_attr(feature = "ffi", uniffi(default = None))]
    pub attention_maps: Option<Vec<Vec<f32>>>, // 81 optional attention maps
}

// Bevy Resource trait for cube viewer
#[cfg(feature = "bevy")]
impl bevy::prelude::Resource for QuantizedCubeData {}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PaletteUsage {
    pub frame_index: usize,
    pub colors_used: usize,
    pub most_frequent: Vec<(u8, f32)>,  // (index, percentage)
    pub least_frequent: Vec<(u8, f32)>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TemporalMetrics {
    pub palette_stability: f32,  // 0.0-1.0, higher is more stable
    pub frame_to_frame_drift: Vec<f32>,  // Delta between consecutive frames
    pub global_color_distribution: Vec<f32>,  // Usage across all frames
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CubeMetadata {
    pub quantization_method: String,
    pub color_space: String,
    pub dithering_enabled: bool,
    pub processing_time_ms: u64,
    pub mean_delta_e: f32,
    pub p95_delta_e: f32,
}

/// Structured error taxonomy with stable codes
#[derive(Error, Debug, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Error))]
pub enum GifPipeError {
    // M1: Neural downsampling errors (E_M1_*)
    #[error("E_M1_INPUT: invalid RGBA frame: {message}")]
    InvalidFrameData { message: String },
    
    #[error("E_M1_NEURAL: neural processing failed: {message}")]
    NeuralProcessingFailed { message: String },
    
    #[error("E_M1_ATTENTION: attention map processing failed: {message}")]
    AttentionProcessingFailed { message: String },
    
    #[error("E_M1_OUTPUT: M1 output validation failed: {message}")]
    M1OutputValidationFailed { message: String },

    // M2: Quantization errors (E_M2_*)
    #[error("E_M2_PALETTE: quantization failed: {message}")]
    QuantizationFailed { message: String },
    
    #[error("E_M2_DITHER: dithering failed: {message}")]
    DitheringFailed { message: String },
    
    #[error("E_M2_COHERENCE: cube temporal coherence failed: {message}")]
    CoherenceFailed { message: String },

    // M3: GIF encoding errors (E_M3_*)
    #[error("E_M3_WRITER: GIF writer initialization failed: {message}")]
    GifWriterFailed { message: String },
    
    #[error("E_M3_FRAME: frame encoding failed at {frame_idx}: {message}")]
    FrameEncodingFailed { frame_idx: u32, message: String },
    
    #[error("E_M3_GIFWRITE: GIF creation failed: {message}")]
    GifCreationFailed { message: String },
    
    #[error("E_M3_FINALIZE: GIF finalization failed: {message}")]
    GifFinalizationFailed { message: String },
    
    #[error("E_M3_ENCODE: GIF encoding failed: {message}")]
    EncodingFailed { message: String },

    // Infrastructure errors (E_INFRA_*)
    #[error("E_INFRA_SERDE: serialization failed: {message}")]
    SerializationFailed { message: String },
    
    #[error("E_INFRA_MEMORY: memory allocation failed: {message}")]
    MemoryFailed { message: String },
    
    #[error("E_INFRA_IO: I/O operation failed: {message}")]
    IoFailed { message: String },
    
    #[error("E_INFRA_THREADS: thread pool exhausted: {message}")]
    ThreadPoolExhausted { message: String },

    // System errors (E_SYSTEM_*)
    #[error("E_SYSTEM_CONFIG: configuration invalid: {message}")]
    ConfigInvalid { message: String },
    
    #[error("E_SYSTEM_RESOURCE: resource unavailable: {message}")]
    ResourceUnavailable { message: String },
    
    #[error("E_SYSTEM_TIMEOUT: timeout exceeded: {message}")]
    TimeoutExceeded { message: String },
    
    #[error("E_SYSTEM_PANIC: critical panic occurred: {message}")]
    PanicOccurred { message: String },

    // Legacy validation errors (for compatibility)
    #[error("E_VALIDATION: validation failed: {message}")]
    ValidationFailed { message: String },
    
    #[error("E_VALIDATION: validation error: {message}")]
    ValidationError { message: String },

    #[error("E_PANIC: unexpected error: {message}")]
    Panic { message: String },
    
    #[error("E_LOGGING: logging error: {message}")]
    LoggingError { message: String },
}
impl GifPipeError {
    /// Get structured error code for logging and monitoring
    pub fn code(&self) -> &'static str {
        match self {
            // M1 codes
            GifPipeError::InvalidFrameData { .. } => "E_M1_INPUT",
            GifPipeError::NeuralProcessingFailed { .. } => "E_M1_NEURAL",
            GifPipeError::AttentionProcessingFailed { .. } => "E_M1_ATTENTION",  
            GifPipeError::M1OutputValidationFailed { .. } => "E_M1_OUTPUT",

            // M2 codes
            GifPipeError::QuantizationFailed { .. } => "E_M2_PALETTE",
            GifPipeError::DitheringFailed { .. } => "E_M2_DITHER",
            GifPipeError::CoherenceFailed { .. } => "E_M2_COHERENCE",

            // M3 codes  
            GifPipeError::GifWriterFailed { .. } => "E_M3_WRITER",
            GifPipeError::FrameEncodingFailed { .. } => "E_M3_FRAME",
            GifPipeError::GifCreationFailed { .. } => "E_M3_GIFWRITE",
            GifPipeError::GifFinalizationFailed { .. } => "E_M3_FINALIZE",
            GifPipeError::EncodingFailed { .. } => "E_M3_ENCODE",

            // Infrastructure codes
            GifPipeError::SerializationFailed { .. } => "E_INFRA_SERDE",
            GifPipeError::MemoryFailed { .. } => "E_INFRA_MEMORY",
            GifPipeError::IoFailed { .. } => "E_INFRA_IO",
            GifPipeError::ThreadPoolExhausted { .. } => "E_INFRA_THREADS",

            // System codes
            GifPipeError::ConfigInvalid { .. } => "E_SYSTEM_CONFIG",
            GifPipeError::ResourceUnavailable { .. } => "E_SYSTEM_RESOURCE",
            GifPipeError::TimeoutExceeded { .. } => "E_SYSTEM_TIMEOUT",
            GifPipeError::PanicOccurred { .. } => "E_SYSTEM_PANIC",

            // Legacy codes
            GifPipeError::ValidationFailed { .. } => "E_VALIDATION",
            GifPipeError::ValidationError { .. } => "E_VALIDATION",
            GifPipeError::Panic { .. } => "E_PANIC",
            GifPipeError::LoggingError { .. } => "E_LOGGING",
        }
    }
}

/// Oklab color space utilities for perceptual quantization
pub mod oklab {
    /// Convert RGB to Oklab color space
    pub fn rgb_to_oklab(r: u8, g: u8, b: u8) -> [f32; 3] {
        let r = r as f32 / 255.0;
        let g = g as f32 / 255.0; 
        let b = b as f32 / 255.0;
        
        // Linear RGB
        let r = if r > 0.04045 { ((r + 0.055) / 1.055).powf(2.4) } else { r / 12.92 };
        let g = if g > 0.04045 { ((g + 0.055) / 1.055).powf(2.4) } else { g / 12.92 };
        let b = if b > 0.04045 { ((b + 0.055) / 1.055).powf(2.4) } else { b / 12.92 };
        
        // XYZ
        let x = 0.4124 * r + 0.3576 * g + 0.1805 * b;
        let y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        let z = 0.0193 * r + 0.1192 * g + 0.9505 * b;
        
        // Oklab
        let l = 0.8189330101 * x + 0.3618667424 * y - 0.1288597137 * z;
        let m = 0.0329845436 * x + 0.9293118715 * y + 0.0361456387 * z;
        let s = 0.0482003018 * x + 0.2643662691 * y + 0.6338517070 * z;
        
        let l = l.cbrt();
        let m = m.cbrt();
        let s = s.cbrt();
        
        [
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        ]
    }
    
    /// Calculate ΔE distance between two Oklab colors
    pub fn delta_e_oklab(lab1: [f32; 3], lab2: [f32; 3]) -> f32 {
        let dl = lab1[0] - lab2[0];
        let da = lab1[1] - lab2[1];
        let db = lab1[2] - lab2[2];
        (dl * dl + da * da + db * db).sqrt()
    }
}
