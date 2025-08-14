package com.rgbagif.camera

import android.util.Size

/**
 * Square-only capture configuration
 * No fallbacks - device must support true 1:1 YUV or fail fast
 */
object SquareCaptureConfig {
    
    // ImageReader buffer settings
    const val MAX_IMAGES = 5
    
    // Target frames for capture
    const val TARGET_FRAMES = 189
    
    // Error codes
    const val DEVICE_NO_SQUARE_YUV = "DEVICE_NO_SQUARE_YUV"
    
    /**
     * Compute optimal output size based on input square size
     * Uses stride-2 friendly sizes for NN efficiency
     */
    fun computeOutputSize(inputSize: Int): Int {
        return when {
            inputSize >= 1024 -> 256
            inputSize >= 512 -> 224  // Even, stride-2 friendly
            else -> 128
        }
    }
    
    /**
     * Filter sizes to find squares (width == height)
     */
    fun filterSquareSizes(sizes: Array<Size>): List<Size> {
        return sizes.filter { it.width == it.height }
    }
    
    /**
     * Find largest square size from array
     */
    fun findLargestSquare(sizes: Array<Size>): Size? {
        return filterSquareSizes(sizes)
            .maxByOrNull { it.width }
    }
    
    /**
     * Validate that a size is truly square
     */
    fun isSquare(size: Size): Boolean {
        return size.width == size.height
    }
    
    /**
     * Log all available sizes for diagnostics
     */
    fun formatSizeList(sizes: Array<Size>, type: String): String {
        return "$type sizes (${sizes.size} total): ${
            sizes.joinToString { "${it.width}×${it.height}" }
        }"
    }
}