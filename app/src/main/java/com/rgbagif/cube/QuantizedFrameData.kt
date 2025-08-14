package com.rgbagif.cube

import java.io.File

/**
 * Quantized frame data for 3D cube visualization
 * Contains palette-indexed pixels and color palette from GIF quantization process
 */
data class QuantizedFrameData(
    val indexedFrames: List<ByteArray>,     // 81 frames × 6561 indexed pixels (81×81)
    val palette: Array<IntArray>,           // RGB color palette (up to 256 colors)
    val frameCount: Int,                    // Should be exactly 81
    val paletteSize: Int,                   // Actual number of colors used
    val gifFile: File,                      // Associated GIF file
    val processingTimeMs: Long = 0,         // Time to generate quantized data
) {
    /**
     * Validate the quantized frame data structure
     */
    fun validate(): Boolean {
        // Verify frame count
        if (frameCount != 81 || indexedFrames.size != 81) {
            return false
        }
        
        // Verify each frame is exactly 81×81 pixels
        for (frame in indexedFrames) {
            if (frame.size != 81 * 81) {
                return false
            }
        }
        
        // Verify palette size
        if (paletteSize <= 0 || paletteSize > 256 || palette.size < paletteSize) {
            return false
        }
        
        // Verify each palette entry is RGB (3 components)
        for (i in 0 until paletteSize) {
            if (palette[i].size != 3) {
                return false
            }
        }
        
        return true
    }
    
    /**
     * Get RGBA representation of a specific frame using the palette
     */
    fun getFrameAsRgba(frameIndex: Int): ByteArray {
        require(frameIndex in 0 until frameCount) { 
            "Frame index $frameIndex out of range [0, $frameCount)" 
        }
        
        val indexedFrame = indexedFrames[frameIndex]
        val rgbaFrame = ByteArray(81 * 81 * 4)
        
        for (i in indexedFrame.indices) {
            val paletteIndex = indexedFrame[i].toInt() and 0xFF
            if (paletteIndex < paletteSize) {
                val rgb = palette[paletteIndex]
                val baseIdx = i * 4
                rgbaFrame[baseIdx] = rgb[0].toByte()     // R
                rgbaFrame[baseIdx + 1] = rgb[1].toByte() // G
                rgbaFrame[baseIdx + 2] = rgb[2].toByte() // B
                rgbaFrame[baseIdx + 3] = 0xFF.toByte()   // A (fully opaque)
            } else {
                // Invalid palette index - use magenta for debugging
                val baseIdx = i * 4
                rgbaFrame[baseIdx] = 0xFF.toByte()       // R
                rgbaFrame[baseIdx + 1] = 0x00.toByte()   // G
                rgbaFrame[baseIdx + 2] = 0xFF.toByte()   // B
                rgbaFrame[baseIdx + 3] = 0xFF.toByte()   // A
            }
        }
        
        return rgbaFrame
    }
    
    /**
     * Get the dominant color of a frame
     */
    fun getFrameDominantColor(frameIndex: Int): IntArray {
        val indexedFrame = indexedFrames[frameIndex]
        val colorCounts = IntArray(paletteSize)
        
        // Count occurrences of each palette color
        for (pixelIndex in indexedFrame) {
            val paletteIndex = pixelIndex.toInt() and 0xFF
            if (paletteIndex < paletteSize) {
                colorCounts[paletteIndex]++
            }
        }
        
        // Find most frequent color
        val dominantIndex = colorCounts.indices.maxByOrNull { colorCounts[it] } ?: 0
        return palette[dominantIndex]
    }
    
    /**
     * Calculate color histogram for the entire animation
     */
    fun getColorHistogram(): Map<IntArray, Int> {
        val histogram = mutableMapOf<IntArray, Int>()
        
        for (frame in indexedFrames) {
            for (pixelIndex in frame) {
                val paletteIndex = pixelIndex.toInt() and 0xFF
                if (paletteIndex < paletteSize) {
                    val color = palette[paletteIndex]
                    histogram[color] = histogram.getOrDefault(color, 0) + 1
                }
            }
        }
        
        return histogram
    }
    
    /**
     * Get statistics about the quantized data
     */
    fun getStats(): QuantizedDataStats {
        val totalPixels = frameCount * 81 * 81
        val uniqueColorsUsed = mutableSetOf<Int>()
        
        for (frame in indexedFrames) {
            for (pixelIndex in frame) {
                uniqueColorsUsed.add(pixelIndex.toInt() and 0xFF)
            }
        }
        
        return QuantizedDataStats(
            totalFrames = frameCount,
            totalPixels = totalPixels,
            paletteSize = paletteSize,
            uniqueColorsUsed = uniqueColorsUsed.size,
            compressionRatio = (totalPixels * 4).toFloat() / (totalPixels + paletteSize * 3).toFloat(),
            avgColorsPerFrame = uniqueColorsUsed.size.toFloat() / frameCount
        )
    }
}

/**
 * Statistics about quantized frame data
 */
data class QuantizedDataStats(
    val totalFrames: Int,
    val totalPixels: Int,
    val paletteSize: Int,
    val uniqueColorsUsed: Int,
    val compressionRatio: Float,
    val avgColorsPerFrame: Float
)
