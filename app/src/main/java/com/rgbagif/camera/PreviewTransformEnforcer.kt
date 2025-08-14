package com.rgbagif.camera

import android.graphics.Matrix
import android.view.TextureView
import com.rgbagif.logging.JsonLog
import kotlin.math.min

/**
 * PreviewTransformEnforcer - Single deterministic transform enforcer
 * 
 * Ensures the preview TextureView shows exactly the same crop that gets saved to CBOR/PNG.
 * Handles instance tracking, timing, and lifecycle issues.
 */
object PreviewTransformEnforcer {
    
    private const val TAG = "PreviewTransformEnforcer"
    
    // Track the current valid TextureView instance
    private var currentTextureViewId: Int? = null
    private var currentSurfaceTextureId: Int? = null
    
    /**
     * Apply the WYSIWYG transform to the TextureView
     * 
     * @param textureView The TextureView to transform
     * @param geometry The capture geometry defining the crop
     * @param rotationDeg Rotation in degrees (0, 90, 180, 270)
     * @return true if transform was successfully applied, false otherwise
     */
    fun apply(
        textureView: TextureView,
        geometry: CaptureGeometry = CaptureGeometry.MILESTONE_1,
        rotationDeg: Int = 0
    ): Boolean {
        val tvId = System.identityHashCode(textureView)
        val stId = textureView.surfaceTexture?.let { System.identityHashCode(it) } ?: 0
        
        // Validate TextureView state
        if (!textureView.isAttachedToWindow) {
        // JsonLog.i("camera_event")
            return false
        }
        
        // Validate dimensions
        val viewW = textureView.width
        val viewH = textureView.height
        
        if (viewW <= 0 || viewH <= 0) {
        // JsonLog.i("camera_event")
            return false
        }
        
        // Validate SurfaceTexture
        if (textureView.surfaceTexture == null) {
        // JsonLog.i("camera_event")
            return false
        }
        
        // Update tracking
        currentTextureViewId = tvId
        currentSurfaceTextureId = stId
        
        // Calculate transform parameters
        val size = min(viewW, viewH).toFloat()  // Square size in view pixels
        val scale = size / geometry.cropW.toFloat()  // Expected: 1080/729 = 1.48
        
        // Build transform matrix
        val matrix = Matrix()
        matrix.reset()
        
        // Step 1: Apply rotation around buffer center if needed
        if (rotationDeg % 360 != 0) {
            matrix.postRotate(
                rotationDeg.toFloat(),
                geometry.srcW / 2f,
                geometry.srcH / 2f
            )
        }
        
        // Step 2: Scale the buffer to fit the crop into the view
        matrix.postScale(scale, scale)
        
        // Step 3: Translate to center the crop in the view
        val centerOffsetX = (viewW - size) / 2f
        val centerOffsetY = (viewH - size) / 2f
        
        val tx = (-geometry.cropX * scale) + centerOffsetX  // Expected: -240 * 0.75 + 0 = -180
        val ty = (-geometry.cropY * scale) + centerOffsetY  // Expected: -0 * 0.75 + 0 = 0
        
        matrix.postTranslate(tx, ty)
        
        // Apply the transform
        textureView.setTransform(matrix)
        
        // Verify by mapping crop corners
        val corners = floatArrayOf(
            geometry.cropX.toFloat(), geometry.cropY.toFloat(),  // TL
            (geometry.cropX + geometry.cropW).toFloat(), geometry.cropY.toFloat(),  // TR
            (geometry.cropX + geometry.cropW).toFloat(), (geometry.cropY + geometry.cropH).toFloat(),  // BR
            geometry.cropX.toFloat(), (geometry.cropY + geometry.cropH).toFloat()  // BL
        )
        val mapped = FloatArray(8)
        matrix.mapPoints(mapped, corners)
        
        // Emit comprehensive diagnostic
        val diagnostic = buildString {
        }
        
        // JsonLog.i("camera_event")
        
        // Verify corners
        val expectedLeft = centerOffsetX
        val expectedTop = centerOffsetY
        val expectedRight = centerOffsetX + size
        val expectedBottom = centerOffsetY + size
        
        val tolerance = 1f
        val cornersValid = 
            kotlin.math.abs(mapped[0] - expectedLeft) <= tolerance &&
            kotlin.math.abs(mapped[1] - expectedTop) <= tolerance &&
            kotlin.math.abs(mapped[4] - expectedRight) <= tolerance &&
            kotlin.math.abs(mapped[5] - expectedBottom) <= tolerance
        
        if (cornersValid) {
        // JsonLog.i("camera_event")
        } else {
        // JsonLog.i("camera_event")
        }
        
        return cornersValid
    }
    
    /**
     * Check if a TextureView is the current valid instance
     */
    fun isCurrentInstance(textureView: TextureView): Boolean {
        val tvId = System.identityHashCode(textureView)
        return tvId == currentTextureViewId && textureView.isAttachedToWindow
    }
    
    /**
     * Reset tracking (call when view is destroyed)
     */
    fun reset() {
        currentTextureViewId = null
        currentSurfaceTextureId = null
        
        // JsonLog.i("camera_event")
    }
}