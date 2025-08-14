package com.rgbagif.camera

import android.util.Size

/**
 * Centralized camera capture configuration
 * Ensures consistent settings across all components
 */
object CaptureConfig {
    // Target capture dimensions (4:3 aspect ratio, divisible by 4)
    // Note: 960×720 is commonly supported, 1008×756 is less common
    const val CAPTURE_WIDTH = 960
    const val CAPTURE_HEIGHT = 720
    
    // GIF output dimensions (fixed for GIF89a)
    const val GIF_WIDTH = 252
    const val GIF_HEIGHT = 189
    
    // Frame capture settings
    const val TARGET_FRAMES = 189
    const val TARGET_FPS = 24
    
    // ImageReader buffer size (≥4 to prevent stalls)
    const val MAX_IMAGES = 5
    
    // Camera selection
    const val USE_BACK_CAMERA = true
    
    /**
     * Get capture size as Android Size object
     */
    fun getCaptureSize() = Size(CAPTURE_WIDTH, CAPTURE_HEIGHT)
    
    /**
     * Get GIF size as Android Size object
     */
    fun getGifSize() = Size(GIF_WIDTH, GIF_HEIGHT)
    
    /**
     * Check if a size is acceptable (4:3 ratio, divisible by 4)
     */
    fun isAcceptableSize(width: Int, height: Int): Boolean {
        // Check divisibility by 4
        if (width % 4 != 0 || height % 4 != 0) return false
        
        // Check aspect ratio (allow small tolerance)
        val ratio = width.toFloat() / height
        val targetRatio = 4f / 3f
        val tolerance = 0.01f
        
        return kotlin.math.abs(ratio - targetRatio) < tolerance
    }
    
    /**
     * Find best matching size from available options
     */
    fun selectBestSize(availableSizes: Array<Size>): Size {
        // First, try to find exact match
        availableSizes.find { 
            it.width == CAPTURE_WIDTH && it.height == CAPTURE_HEIGHT 
        }?.let { return it }
        
        // Find all 4:3 sizes divisible by 4
        val validSizes = availableSizes.filter {
            isAcceptableSize(it.width, it.height)
        }
        
        // If no valid sizes, fall back to closest 4:3
        if (validSizes.isEmpty()) {
            return availableSizes.minByOrNull {
                val ratio = it.width.toFloat() / it.height
                kotlin.math.abs(ratio - 4f/3f)
            } ?: availableSizes[0]
        }
        
        // Select size closest to target
        return validSizes.minByOrNull {
            kotlin.math.abs(it.width * it.height - CAPTURE_WIDTH * CAPTURE_HEIGHT)
        } ?: validSizes[0]
    }
}