package com.rgbagif.camera

import android.util.Size

/**
 * Milestone 1 Configuration: RGBA capture and GIF export
 * Pipeline: CameraX RGBA_8888 → 729×729 capture → 1440×1440 GIF export
 * 
 * 729×729 chosen for capture because:
 * - Fast processing, widely supported resolution
 * - Efficient memory usage during capture
 * - Clean 2x upscale to 1440×1440 for export
 * 
 * 32 frames @ 30fps = ~1 second of capture
 */
object Milestone1Config {
    // Capture size - 729×729 for efficiency
    const val CAPTURE_SIZE = 729  // Capture resolution
    val CAMERA_SIZE = Size(CAPTURE_SIZE, CAPTURE_SIZE)
    
    // Export size - 1440×1440 for quality
    const val EXPORT_SIZE = 1440  // Export resolution after upscaling
    val OUTPUT_SIZE = Size(EXPORT_SIZE, EXPORT_SIZE)
    
    // RGBA format parameters
    const val BYTES_PER_PIXEL = 4  // RGBA8888: R,G,B,A = 4 bytes
    const val CAPTURE_DATA_SIZE = CAPTURE_SIZE * CAPTURE_SIZE * BYTES_PER_PIXEL  // 2,125,764 bytes for 729×729
    const val EXPORT_DATA_SIZE = EXPORT_SIZE * EXPORT_SIZE * BYTES_PER_PIXEL  // 8,294,400 bytes for 1440×1440
    
    // Upscaling factor for export
    const val UPSCALE_FACTOR = EXPORT_SIZE.toFloat() / CAPTURE_SIZE  // ~1.98× upscale
    
    // Frame capture settings - LOCKED at 81 frames for GIF89a spec compliance
    const val TARGET_FRAMES = 81  // MANDATORY: 81 frames for GIF89a spec compliance (9×9 grid)
    const val TARGET_FPS = 30     // Capture at 30fps
    const val GIF_FPS = 25        // GIF playback at 25fps (4 centiseconds per frame)
    const val FRAME_COUNT = TARGET_FRAMES  // Alias for consistency
    const val CAPTURE_WIDTH = CAPTURE_SIZE  // 729
    const val CAPTURE_HEIGHT = CAPTURE_SIZE // 729
    const val EXPORT_WIDTH = EXPORT_SIZE    // 1440
    const val EXPORT_HEIGHT = EXPORT_SIZE   // 1440
    
    // ImageReader settings (unused with CameraX)
    const val MAX_IMAGES = 5
    
    // CBOR schema version
    const val CBOR_VERSION = 1
    const val CBOR_FORMAT = "RGBA8888"  // Alpha channel used for NN attention
    
    // Error codes
    const val ERROR_DEVICE_NO_1920x1440_YUV = "DEVICE_NO_1920x1440_YUV"
    const val ERROR_YUV_CONVERSION = "YUV_CONVERSION_ERROR"
    const val ERROR_CBOR_ENCODING = "CBOR_ENCODING_ERROR"
    const val ERROR_PNG_EXPORT = "PNG_EXPORT_ERROR"
    
    // Storage paths
    fun getRunDirectory(timestamp: String) = "runs/m1_$timestamp"
    fun getCborDirectory(timestamp: String) = "${getRunDirectory(timestamp)}/cbor"
    fun getPngDirectory(timestamp: String) = "${getRunDirectory(timestamp)}/png"
}