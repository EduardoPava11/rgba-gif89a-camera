package com.rgbagif.camera

/**
 * Single source of truth for capture geometry.
 * Defines the exact crop region in sensor/native buffer coordinates.
 * Used by both preview transform and CBOR/PNG encoding to ensure WYSIWYG.
 */
data class CaptureGeometry(
    val srcW: Int = 1458,  // buffer (SurfaceTexture) width
    val srcH: Int = 729,  // buffer height
    val cropX: Int = 365,  // left edge of 729×729 center crop
    val cropY: Int = 0,    // top edge (already centered vertically)
    val cropW: Int = 729, // crop width (square)
    val cropH: Int = 729  // crop height (square)
) {
    companion object {
        // Default instance for Milestone 1
        val MILESTONE_1 = CaptureGeometry()
        
        /**
         * Calculate center crop for any source dimensions
         */
        fun centerSquareCrop(srcWidth: Int, srcHeight: Int): CaptureGeometry {
            val size = kotlin.math.min(srcWidth, srcHeight)
            val cropX = (srcWidth - size) / 2
            val cropY = (srcHeight - size) / 2
            return CaptureGeometry(
                srcW = srcWidth,
                srcH = srcHeight,
                cropX = cropX,
                cropY = cropY,
                cropW = size,
                cropH = size
            )
        }
    }
    
    /**
     * Get the crop region as a RectF for matrix operations
     */
    fun getCropRectF(): android.graphics.RectF {
        return android.graphics.RectF(
            cropX.toFloat(),
            cropY.toFloat(),
            (cropX + cropW).toFloat(),
            (cropY + cropH).toFloat()
        )
    }
    
    /**
     * Validate that crop fits within source dimensions
     */
    fun validate(): Boolean {
        return cropX >= 0 && 
               cropY >= 0 && 
               (cropX + cropW) <= srcW && 
               (cropY + cropH) <= srcH
    }
}