pub mod go_network;
pub mod weights;
pub mod downsizer;
pub mod quantizer;
pub mod pipeline;
pub mod cbor_frame;
pub mod fast_cbor;

use std::sync::{Arc, Mutex};
use thiserror::Error;

// Re-export types for UniFFI
pub use pipeline::{Pipeline, SessionConfig, FrameInfo, Feedback, PipelineStats};
pub use fast_cbor::{write_cbor_frame, init_android_logger};

#[derive(Error, Debug)]
pub enum GifPipeError {
    #[error("Initialization error: {0}")]
    InitializationError(String),
    
    #[error("Frame processing error: {0}")]
    FrameProcessingError(String),
    
    #[error("Quantization error: {0}")]
    QuantizationError(String),
    
    #[error("IO error: {0}")]
    IoError(String),
    
    #[error("Invalid configuration: {0}")]
    InvalidConfiguration(String),
}

impl From<std::io::Error> for GifPipeError {
    fn from(err: std::io::Error) -> Self {
        GifPipeError::IoError(err.to_string())
    }
}

impl From<anyhow::Error> for GifPipeError {
    fn from(err: anyhow::Error) -> Self {
        GifPipeError::FrameProcessingError(err.to_string())
    }
}

/// Factory function to create a new pipeline
pub fn create_pipeline(cfg: SessionConfig) -> Result<Arc<Pipeline>, GifPipeError> {
    Pipeline::new(cfg).map(Arc::new)
}

// Include the UniFFI scaffolding
uniffi::include_scaffolding!("gifpipe");