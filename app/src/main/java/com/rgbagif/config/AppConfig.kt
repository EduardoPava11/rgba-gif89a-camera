package com.rgbagif.config

import android.util.Size

/**
 * Unified application configuration - NORTH STAR SPEC LOCKED
 * Single source of truth for all dimensions and parameters
 * 
 * IMMUTABLE CONTRACT:
 * - M1: Capture 81 frames at 729×729 RGBA_8888
 * - M2: Neural downsize to 81×81 via 9×9 policy/value network
 * - M3: Export as 81×81 GIF89a, 81 frames, 24 fps, looping
 */
object AppConfig {
    
    // ========== CAPTURE CONFIGURATION (M1) ==========
    
    // Capture resolution - LOCKED at 729×729
    const val CAPTURE_WIDTH = 729
    const val CAPTURE_HEIGHT = 729
    const val CAPTURE_BYTES_PER_PIXEL = 4  // RGBA8888
    const val CAPTURE_SIZE_BYTES = CAPTURE_WIDTH * CAPTURE_HEIGHT * CAPTURE_BYTES_PER_PIXEL
    
    // Frame capture settings - LOCKED at 81 frames for GIF89a spec compliance
    const val CAPTURE_FRAME_COUNT = 81  // MANDATORY: exactly 81 frames (9×9 grid)
    const val CAPTURE_FPS = 30          // Capture frame rate
    const val CAPTURE_DURATION_MS = (CAPTURE_FRAME_COUNT * 1000) / CAPTURE_FPS  // 2700ms
    
    // Camera configuration - RGBA_8888 ONLY
    val CAMERA_SIZE = Size(CAPTURE_WIDTH, CAPTURE_HEIGHT)
    const val CAMERA_FORMAT = "RGBA_8888"
    const val BACKPRESSURE_STRATEGY = "KEEP_ONLY_LATEST"
    const val IMAGE_QUEUE_DEPTH = 1
    
    // ========== EXPORT CONFIGURATION (M3) ==========
    
    // Export resolution - LOCKED at 81×81 (after M2 neural downsize)
    const val EXPORT_WIDTH = 81
    const val EXPORT_HEIGHT = 81
    const val EXPORT_BYTES_PER_PIXEL = 4  // RGBA8888
    const val EXPORT_SIZE_BYTES = EXPORT_WIDTH * EXPORT_HEIGHT * EXPORT_BYTES_PER_PIXEL
    
    // GIF export settings - LOCKED at 24 fps
    const val GIF_MAX_COLORS = 256      // Standard GIF89a palette size
    const val GIF_FRAME_DELAY_MS = 42   // 24fps playback (1000/24 ≈ 42ms)
    const val GIF_LOOP_COUNT = 0        // 0 = infinite loop (MANDATORY)
    
    // ========== NEURAL NETWORK CONFIGURATION (M2) ==========
    
    // Neural downsize settings - LOCKED at 9×9 policy/value network
    const val NN_GRID_SIZE = 9              // 9×9 grid (81 cells)
    const val NN_DOWNSCALE_FACTOR = 9       // 729÷81 = 9
    const val NN_USE_CPU_ONLY = true        // CPU only, quality over speed
    const val NN_INFERENCE_THREADS = 1      // Single thread for consistency
    
    // ========== PROCESSING CONFIGURATION ==========
    
    // M2 Neural Processing - Enable M2 neural downsampling
    const val useM2NeuralProcessing = true  // Enable M2 neural network processing
    
    // M3 GIF Export - Enable GIF89a export
    const val useM3GifExport = true  // Enable M3 GIF export processing
    
    // JNI fast-path
    const val USE_JNI_FAST_PATH = true  // Use JNI by default
    const val BENCHMARK_MODE = false    // Enable for performance testing
    
    // Buffer pool
    const val BUFFER_POOL_SIZE = 3
    const val MAX_QUEUE_DEPTH = 2
    
    // Quantization
    enum class QuantizerType {
        MEDIAN_CUT,   // Fast, good quality
        OCTREE,       // Better quality, slightly slower
        NEUQUANT      // Best quality (future)
    }
    const val DEFAULT_QUANTIZER = "MEDIAN_CUT"
    const val ENABLE_DITHERING = false  // Floyd-Steinberg dithering
    
    // ========== STORAGE CONFIGURATION ==========
    
    // File paths
    const val CBOR_VERSION = 1
    const val CBOR_FORMAT = "RGBA8888"
    
    fun getRunDirectory(timestamp: String) = "runs/$timestamp"
    fun getCborDirectory(timestamp: String) = "${getRunDirectory(timestamp)}/cbor_frames"
    fun getPngDirectory(timestamp: String) = "${getRunDirectory(timestamp)}/png_export"  // Deprecated
    fun getJpegDirectory(timestamp: String) = "${getRunDirectory(timestamp)}/jpeg_export"
    fun getGifPath(timestamp: String) = "${getRunDirectory(timestamp)}/output.gif"
    fun getLogsDirectory(timestamp: String) = "${getRunDirectory(timestamp)}/logs"
    
    // ========== VALIDATION ==========
    
    /**
     * Validate capture dimensions
     */
    fun validateCaptureDimensions(width: Int, height: Int): Boolean {
        return width == CAPTURE_WIDTH && height == CAPTURE_HEIGHT
    }
    
    /**
     * Validate export dimensions
     */
    fun validateExportDimensions(width: Int, height: Int): Boolean {
        return width == EXPORT_WIDTH && height == EXPORT_HEIGHT
    }
    
    /**
     * Get capture size as Android Size
     */
    fun getCaptureSize() = Size(CAPTURE_WIDTH, CAPTURE_HEIGHT)
    
    /**
     * Get export size as Android Size
     */
    fun getExportSize() = Size(EXPORT_WIDTH, EXPORT_HEIGHT)
    
    // ========== DEPRECATED MAPPINGS ==========
    // For backward compatibility during migration
    
    @Deprecated("Use CAPTURE_WIDTH", ReplaceWith("CAPTURE_WIDTH"))
    const val SQUARE_SIZE = CAPTURE_WIDTH
    
    @Deprecated("Use CAPTURE_FRAME_COUNT", ReplaceWith("CAPTURE_FRAME_COUNT"))
    const val TARGET_FRAMES = CAPTURE_FRAME_COUNT
    
    @Deprecated("Use CAPTURE_FRAME_COUNT", ReplaceWith("CAPTURE_FRAME_COUNT"))
    const val TOTAL_FRAMES = CAPTURE_FRAME_COUNT
    
    @Deprecated("Use CAPTURE_FRAME_COUNT", ReplaceWith("CAPTURE_FRAME_COUNT"))
    const val EXPORT_FRAMES = CAPTURE_FRAME_COUNT
    
    @Deprecated("Use GIF_FRAME_DELAY_MS", ReplaceWith("GIF_FRAME_DELAY_MS"))
    const val EXPORT_FPS = 1000 / GIF_FRAME_DELAY_MS  // 24 fps
    
    @Deprecated("Use CAPTURE_SIZE_BYTES", ReplaceWith("CAPTURE_SIZE_BYTES"))
    const val EXPECTED_DATA_SIZE = CAPTURE_SIZE_BYTES
}