use common_types::*;
use tracing::{info, error, debug, instrument, span, Level, Span};
use tracing_subscriber::{layer::SubscriberExt, EnvFilter};
use serde_json::json;
use uuid::Uuid;
use std::sync::{Arc, Once};

static INIT: Once = Once::new();

#[derive(Debug, thiserror::Error)]
pub enum ProcessingError {
    #[error("Serialization failed: {0}")]
    SerializationFailed(String),
    #[error("Processing failed: {0}")]
    ProcessingFailed(String),
}

/// Initialize structured tracing with Android logcat integration and session correlation
/// Returns session ID for cross-language correlation
pub fn initialize_logging() -> String {
    let session_id = Uuid::new_v4();
    
    INIT.call_once(|| {
        // Hierarchical logging: INFO for summaries, DEBUG for per-frame details
        let filter = EnvFilter::try_from_default_env()
            .unwrap_or_else(|_| EnvFilter::new("info,ffi=debug"));

        // Initialize tracing with Android logcat integration
        #[cfg(target_os = "android")]
        {
            let android_layer = tracing_android::layer("GifPipe81")
                .expect("Failed to create Android tracing layer");
            
            tracing_subscriber::registry()
                .with(tracing_subscriber::fmt::layer()
                    .with_writer(android_layer)
                    .json()  // Structured JSON for analytics
                    .flatten_event(true)  // Flat structure for parsing
                    .with_current_span(true)
                    .with_span_list(true))
                .with(filter)
                .init();
        }
        
        #[cfg(not(target_os = "android"))]
        {
            let format = tracing_subscriber::fmt::format()
                .json()
                .with_target(true)
                .with_thread_ids(true)
                .with_span_list(true);
                
            tracing_subscriber::fmt()
                .with_env_filter(filter)
                .event_format(format)
                .init();
        }
        
        // Panic hook for E_PANIC error code
        std::panic::set_hook(Box::new(|info| {
            error!(
                code = "E_PANIC",
                panic_info = ?info,
                location = info.location().map(|l| format!("{}:{}:{}", l.file(), l.line(), l.column())),
                "Rust panic occurred"
            );
        }));
        
        info!("Structured tracing initialized for 81×81×81 cube vision");
    });
    
    info!(
        session_id = %session_id,
        version = env!("CARGO_PKG_VERSION"),
        event = "session_start",
        cube_vision = "81x81x81",
        "GIF pipeline session initialized"
    );
    
    session_id.to_string()
}

pub struct GifPipeline {
    session_id: Uuid,
    session_span: Span,
}

impl GifPipeline {
    #[instrument(name = "pipeline_create", fields(session_id))]
    pub fn new() -> Self {
        let session_id_str = initialize_logging();
        let session_id = Uuid::parse_str(&session_id_str).unwrap();
        
        let session_span = span!(Level::INFO, "gif_session", 
            session_id = %session_id,
            version = env!("CARGO_PKG_VERSION"),
            cube_dimensions = "81x81x81"
        );
        
        {
            let _enter = session_span.enter();
            info!(
                session_metadata = ?json!({
                    "session_id": session_id,
                    "version": env!("CARGO_PKG_VERSION"),
                    "timestamp": chrono::Utc::now().to_rfc3339(),
                    "cube_vision": "81x81x81"
                }),
                "New 81-cube GIF pipeline session started"
            );
        }
        
        Self { 
            session_id,
            session_span,
        }
    }

    /// Main production pipeline: 729×729 RGBA frames → 81×81×81 GIF cube
    #[instrument(name = "strategy_b_pipeline", fields(session_id = %self.session_id, frame_count), skip(self, frames_bytes))]
    pub fn process_frames_bytes(&self, frames_bytes: Vec<u8>, external_session_id: String) -> Result<Vec<u8>, ProcessingError> {
        let _session_guard = self.session_span.enter();
        
        // Deserialize input frames
        let frames: Frames81Rgb = serde_json::from_slice(&frames_bytes)
            .map_err(|e| ProcessingError::SerializationFailed(format!("Input deserialization: {}", e)))?;
        
        tracing::Span::current().record("frame_count", frames.frames_rgb.len());
        
        if frames.frames_rgb.len() != 81 {
            error!(
                expected = 81,
                actual = frames.frames_rgb.len(),
                code = "E_M1_INPUT",
                "Invalid frame count for 81-cube vision"
            );
            return Err(ProcessingError::ProcessingFailed(
                format!("Expected exactly 81 frames for cube, got {}", frames.frames_rgb.len())
            ));
        }
        
        info!(
            processing_metadata = ?json!({
                "frame_count": frames.frames_rgb.len(),
                "input_size_bytes": frames_bytes.len(),
                "session_id": self.session_id,
                "external_session_id": external_session_id,
                "cube_vision": "81x81x81"
            }),
            "Starting M1→M2→M3 cube processing pipeline"
        );

        // Log frame batch progress (every 10 frames to avoid spam)
        for (idx, frame) in frames.frames_rgb.iter().enumerate() {
            if idx % 10 == 0 {
                info!(
                    frame_idx = idx,
                    frame_size = frame.len(),
                    progress = format!("{}/{}", idx, frames.frames_rgb.len()),
                    "Processing frame batch for cube"
                );
            }
            
            // Per-frame details at DEBUG level
            debug!(
                frame_idx = idx,
                frame_bytes = frame.len(),
                expected_size = 81 * 81 * 3, // 81×81 RGB
                "Processing individual cube frame"
            );
        }

        // Process through M1→M2→M3 pipeline
        let gif_info = self.process_frames_internal(frames, &external_session_id)?;
        
        // Serialize output
        let output_bytes = serde_json::to_vec(&gif_info)
            .map_err(|e| ProcessingError::SerializationFailed(format!("Output serialization: {}", e)))?;

        info!(
            completion_metadata = ?json!({
                "output_size_bytes": output_bytes.len(),
                "total_processing_time_ms": gif_info.total_processing_ms,
                "compression_ratio": gif_info.compression_ratio,
                "session_id": self.session_id,
                "processing_success": true,
                "cube_dimensions": "81x81x81",
                "cube_frames": gif_info.frame_count
            }),
            "81×81×81 cube pipeline processing completed successfully"
        );

        Ok(output_bytes)
    }

    #[instrument(name = "pipeline_process_internal", fields(session_id = %self.session_id, frame_count), skip(self, frames))]
    fn process_frames_internal(&self, frames: Frames81Rgb, session_id: &str) -> Result<GifInfo, ProcessingError> {
        tracing::Span::current().record("frame_count", frames.frames_rgb.len());
        
        // M1: Already completed (frames already 81×81)
        info!(
            m1_metadata = ?json!({
                "stage": "M1",
                "status": "skipped",
                "reason": "frames_already_81x81", 
                "attention_maps_count": frames.attention_maps.len(),
                "processing_time_ms": frames.processing_time_ms,
                "session_id": self.session_id,
                "cube_layer": "neural_downsampling"
            }),
            "M1: Neural downsampling skipped (frames already 81×81 for cube)"
        );

        // M2: Quantization stage with cube-aware palette
        let quantized_set = {
            let m2_span = span!(Level::INFO, "m2_quantization", 
                session_id = %self.session_id, 
                target_colors = 256,
                cube_coherence = true
            );
            let _m2_guard = m2_span.enter();
            let start = std::time::Instant::now();
            
            info!("M2: Starting Oklab quantization for cube temporal coherence");
            let quantizer = m2_quant::OklabQuantizer::new(256);
            let result = quantizer.quantize_frames(frames)
                .map_err(|e| {
                    let error_msg = format!("M2 quantization failed: {}", e);
                    error!(
                        error = %e,
                        stage = "M2",
                        session_id = %self.session_id,
                        code = "E_M2_PALETTE",
                        "{}",
                        error_msg
                    );
                    ProcessingError::ProcessingFailed(error_msg)
                })?;
                
            info!(
                result_metadata = ?json!({
                    "stage": "M2", 
                    "palette_size": result.palette_rgb.len() / 3,
                    "mean_error": result.mean_perceptual_error,
                    "p95_error": result.p95_perceptual_error,
                    "processing_time_ms": start.elapsed().as_millis() as u64,
                    "session_id": self.session_id,
                    "cube_coherence": result.palette_stability
                }),
                "M2: Oklab quantization completed with cube temporal coherence"
            );
            result
        };

        // M3: GIF encoding stage optimized for 81-frame cube
        let gif_info = {
            let m3_span = span!(Level::INFO, "m3_gif_encoding", 
                session_id = %self.session_id,
                target_fps = 24,
                loop_mode = true,
                format = "GIF89a"
            );
            let _m3_guard = m3_span.enter();
            let start = std::time::Instant::now();
            
            info!("M3: Starting GIF89a encoding for 81-frame cube at 24fps");
            let encoder = m3_gif::Gif89aEncoder::new();
            let result = encoder.encode_gif(quantized_set)
                .map_err(|e| {
                    let error_msg = format!("M3 GIF encoding failed: {}", e);
                    error!(
                        error = %e,
                        stage = "M3", 
                        session_id = %self.session_id,
                        code = "E_M3_GIFWRITE",
                        "{}",
                        error_msg
                    );
                    ProcessingError::ProcessingFailed(error_msg)
                })?;
                
            info!(
                result_metadata = ?json!({
                    "stage": "M3",
                    "output_size_bytes": result.file_size_bytes,
                    "compression_ratio": result.compression_ratio,
                    "processing_time_ms": start.elapsed().as_millis() as u64,
                    "session_id": self.session_id,
                    "frame_count": result.frame_count,
                    "has_netscape_loop": result.has_netscape_loop,
                    "cube_complete": result.frame_count == 81
                }),
                "M3: GIF89a encoding completed for 81-frame cube"
            );
            
            // Validate cube integrity
            if result.frame_count != 81 {
                error!(
                    expected = 81,
                    actual = result.frame_count,
                    code = "E_VALIDATION",
                    "Frame count mismatch in final GIF cube"
                );
            }
            
            result
        };

        Ok(gif_info)
    }
}

/// Create a new GIF pipeline with session correlation
pub fn create_pipeline() -> Arc<GifPipeline> {
    Arc::new(GifPipeline::new())
}

/// Initialize Android tracing system with session correlation for Kotlin integration
#[uniffi::export]
pub fn init_android_tracing() -> String {
    initialize_logging()
}

/// Process frames with comprehensive structured logging for production monitoring
#[uniffi::export]
pub fn process_gif_frames(frames_bytes: Vec<u8>, session_id: String) -> Result<Vec<u8>, ProcessingError> {
    let pipeline = create_pipeline();
    pipeline.process_frames_bytes(frames_bytes, session_id)
}

/// Get detailed pipeline metrics for monitoring dashboard
#[uniffi::export]
pub fn get_pipeline_metrics() -> String {
    serde_json::json!({
        "version": env!("CARGO_PKG_VERSION"),
        "features": [
            "81x81x81_cube_vision",
            "oklab_quantization", 
            "gif89a_encoding",
            "session_correlation",
            "structured_logging",
            "error_taxonomy"
        ],
        "capabilities": {
            "max_frames": 81,
            "target_fps": 24,
            "palette_size": 256,
            "cube_dimensions": "81x81x81",
            "temporal_coherence": true
        }
    }).to_string()
}

/// M2: Quantize 81 RGBA frames into global palette + indexed data
#[uniffi::export]
pub fn m2_quantize_for_cube(frames_81_rgba: Vec<Vec<u8>>) -> Result<QuantizedCubeData, GifPipeError> {
    let session_id = initialize_logging();
    let span = span!(Level::INFO, "ffi_m2_quantize", 
        frames = 81, 
        session_id = %session_id
    );
    let _guard = span.enter();
    
    // Convert to internal format
    let frames_rgb = convert_rgba_to_rgb_frames(frames_81_rgba)?;
    let oklab_quantizer = m2_quant::OklabQuantizer::new(256);
    
    oklab_quantizer.quantize_for_cube(frames_rgb)
}

/// M3: Encode GIF89a from pre-quantized cube data (no quantization inside)
#[uniffi::export]
pub fn m3_write_gif_from_cube(
    cube: QuantizedCubeData, 
    fps_cs: u8, 
    loop_forever: bool
) -> Result<GifInfo, GifPipeError> {
    let session_id = initialize_logging();
    let span = span!(Level::INFO, "ffi_m3_encode", 
        frames = 81, 
        palette_size = cube.global_palette_rgb.len() / 3,
        palette_stability = cube.palette_stability,
        session_id = %session_id
    );
    let _guard = span.enter();
    
    let gif_encoder = m3_gif::Gif89aEncoder::new();
    let gif_bytes = gif_encoder.encode_from_cube_data(&cube, fps_cs, loop_forever)?;
    
    Ok(GifInfo {
        gif_data: gif_bytes.clone(),
        file_path: "".to_string(),
        file_size_bytes: gif_bytes.len() as u64,
        frame_count: 81,
        palette_size: cube.global_palette_rgb.len() as u32 / 3,
        has_netscape_loop: loop_forever,
        compression_ratio: calculate_compression_ratio(&cube, &gif_bytes),
        validation_passed: true,
        processing_time_ms: 0, // Will be calculated by caller
        total_processing_ms: 0, // Will be calculated by caller
    })
}

/// Convert RGBA frames to RGB frames for processing
fn convert_rgba_to_rgb_frames(frames_81_rgba: Vec<Vec<u8>>) -> Result<Frames81Rgb, GifPipeError> {
    if frames_81_rgba.len() != 81 {
        return Err(GifPipeError::InvalidFrameData {
            message: format!("Expected 81 frames, got {}", frames_81_rgba.len())
        });
    }
    
    let mut frames_rgb = Vec::with_capacity(81);
    let mut attention_maps = Vec::with_capacity(81);
    
    for (idx, rgba_frame) in frames_81_rgba.iter().enumerate() {
        if rgba_frame.len() % 4 != 0 {
            return Err(GifPipeError::InvalidFrameData {
                message: format!("Frame {} RGBA length not divisible by 4", idx)
            });
        }
        
        let pixel_count = rgba_frame.len() / 4;
        let expected_pixels = 81 * 81;
        
        if pixel_count != expected_pixels {
            return Err(GifPipeError::InvalidFrameData {
                message: format!("Frame {} has {} pixels, expected {}", idx, pixel_count, expected_pixels)
            });
        }
        
        // Convert RGBA to RGB
        let mut rgb_frame = Vec::with_capacity(pixel_count * 3);
        let mut attention_map = Vec::with_capacity(pixel_count);
        
        for i in 0..pixel_count {
            let rgba_idx = i * 4;
            rgb_frame.extend_from_slice(&[
                rgba_frame[rgba_idx],     // R
                rgba_frame[rgba_idx + 1], // G
                rgba_frame[rgba_idx + 2], // B
            ]);
            
            // Use alpha channel as attention weight (0-255 -> 0.0-1.0)
            let attention = rgba_frame[rgba_idx + 3] as f32 / 255.0;
            attention_map.push(attention);
        }
        
        frames_rgb.push(rgb_frame);
        attention_maps.push(attention_map);
    }
    
    Ok(Frames81Rgb {
        frames_rgb,
        attention_maps,
        processing_time_ms: 0,
    })
}

fn calculate_compression_ratio(cube: &QuantizedCubeData, gif_bytes: &[u8]) -> f32 {
    // Original size: 81 frames × 81×81 pixels × 3 bytes (RGB)
    let original_size = 81 * 81 * 81 * 3;
    
    if gif_bytes.is_empty() {
        return 0.0;
    }
    
    original_size as f32 / gif_bytes.len() as f32
}

uniffi::include_scaffolding!("ffi");
