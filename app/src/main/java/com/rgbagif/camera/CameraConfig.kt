package com.rgbagif.camera

import android.util.Size
import androidx.camera.core.ImageAnalysis

/**
 * Camera configuration constants
 * 
 * Contract:
 * - Capture: 729×729 RGBA (sensor data)
 * - Export: 1440×1440 GIF (upscaled for quality)
 */
object CameraConfig {
    
    // CAPTURE RESOLUTION - Fixed at 729×729
    const val CAPTURE_WIDTH = 729
    const val CAPTURE_HEIGHT = 729
    const val CAPTURE_SIZE_BYTES = CAPTURE_WIDTH * CAPTURE_HEIGHT * 4 // RGBA
    
    // EXPORT RESOLUTION - Fixed at 1440×1440 (roughly 2x upscale)
    const val EXPORT_WIDTH = 1440
    const val EXPORT_HEIGHT = 1440
    const val EXPORT_SIZE_BYTES = EXPORT_WIDTH * EXPORT_HEIGHT * 4 // RGBA
    
    // Image format
    const val IMAGE_FORMAT = ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
    
    // Backpressure strategy
    const val BACKPRESSURE_STRATEGY = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
    
    // Buffer pool configuration
    const val BUFFER_POOL_SIZE = 3
    const val MAX_QUEUE_DEPTH = 2
    
    // Target frame rates
    const val TARGET_CAPTURE_FPS = 30
    const val MIN_CAPTURE_FPS = 15
    
    // M1 capture settings - LOCKED at 81 frames for GIF89a spec compliance
    const val M1_FRAME_COUNT = 81 // MANDATORY: 81 frames for GIF89a spec (9×9 grid)
    const val M1_CAPTURE_DURATION_MS = 2700 // 81 frames at 30fps
    
    // M3 export settings
    const val GIF_MAX_COLORS = 256
    const val GIF_FRAME_DELAY_MS = 40 // 25fps playback (4 centiseconds)
    
    /**
     * Get capture resolution as Size object
     */
    fun getCaptureSize() = Size(CAPTURE_WIDTH, CAPTURE_HEIGHT)
    
    /**
     * Get export resolution as Size object
     */
    fun getExportSize() = Size(EXPORT_WIDTH, EXPORT_HEIGHT)
    
    /**
     * Calculate upscale factor from capture to export
     */
    fun getUpscaleFactor(): Float {
        return EXPORT_WIDTH.toFloat() / CAPTURE_WIDTH
    }
    
    /**
     * Validate dimensions in CBOR data
     */
    fun validateCaptureDimensions(width: Int, height: Int): Boolean {
        return width == CAPTURE_WIDTH && height == CAPTURE_HEIGHT
    }
    
    /**
     * Validate dimensions in GIF export
     */
    fun validateExportDimensions(width: Int, height: Int): Boolean {
        return width == EXPORT_WIDTH && height == EXPORT_HEIGHT
    }
}