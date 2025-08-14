package com.rgbagif.camera

import android.graphics.Matrix
import android.graphics.RectF
import android.view.TextureView
import com.rgbagif.logging.JsonLog

/**
 * WYSIWYG Transform for TextureView
 * 
 * Applies the exact transform to show the same crop region that will be
 * encoded to CBOR/PNG. This ensures "What You See Is What You Get".
 */
fun TextureView.applyWysiwygTransform(
    geometry: CaptureGeometry,
    viewW: Int = width,
    viewH: Int = height,
    totalRotationDegrees: Int = 0 // sensor + display rotation
) {
    // Early exit if view not ready
    if (viewW <= 0 || viewH <= 0) {
        // JsonLog.i("camera_event")
        return
    }
    
    // Validate geometry
    if (!geometry.validate()) {
        // JsonLog.i("camera_event")
        return
    }
    
    val matrix = Matrix()
    
    // Calculate the square size in view pixels
    val size = kotlin.math.min(viewW, viewH).toFloat()
    
    // Calculate scale from crop size to view size
    val scale = size / geometry.cropW.toFloat()  // e.g., 1080 / 729 = 1.48
    
    // Step 1: Apply rotation around buffer center if needed
    if (totalRotationDegrees % 360 != 0) {
        matrix.postRotate(
            totalRotationDegrees.toFloat(),
            geometry.srcW / 2f,
            geometry.srcH / 2f
        )
    }
    
    // Step 2: Scale the buffer to fit the crop into the view
    matrix.postScale(scale, scale)
    
    // Step 3: Translate to center the crop in the view
    // Center the square in the view
    val centerOffsetX = (viewW - size) / 2f
    val centerOffsetY = (viewH - size) / 2f
    
    // Translate to move the crop to the view origin, then center it
    val tx = (-geometry.cropX * scale) + centerOffsetX  // e.g., -240 * 0.75 + 0 = -180
    val ty = (-geometry.cropY * scale) + centerOffsetY  // e.g., -0 * 0.75 + 0 = 0
    
    matrix.postTranslate(tx, ty)
    
    // Apply the transform
    // TextureView#setTransform(Matrix) remaps the texture content inside the view;
    // it does not resize the view. Compute/apply the transform after both buffer and view sizes are known.
    // Reference: https://developer.android.com/reference/android/view/TextureView#setTransform(android.graphics.Matrix)
    setTransform(matrix)
    
    // Log the transform for debugging
    val values = FloatArray(9)
    matrix.getValues(values)
    
    // Map the crop corners to verify
    val crop = geometry.getCropRectF()
    val corners = floatArrayOf(
        crop.left, crop.top,      // TL
        crop.right, crop.top,     // TR
        crop.right, crop.bottom,  // BR
        crop.left, crop.bottom    // BL
    )
    val mapped = FloatArray(8)
    matrix.mapPoints(mapped, corners)
    
    // Diagnostic logging with full transform details
        // JsonLog.i("camera_event")
    
    // Verify corners map to view bounds (within 1px tolerance)
    val tolerance = 1f
    val expectedLeft = centerOffsetX
    val expectedTop = centerOffsetY
    val expectedRight = centerOffsetX + size
    val expectedBottom = centerOffsetY + size
    
    val cornersValid = 
        kotlin.math.abs(mapped[0] - expectedLeft) < tolerance && // TL.x
        kotlin.math.abs(mapped[1] - expectedTop) < tolerance && // TL.y
        kotlin.math.abs(mapped[4] - expectedRight) < tolerance && // BR.x
        kotlin.math.abs(mapped[5] - expectedBottom) < tolerance    // BR.y
    
    if (cornersValid) {
        // JsonLog.i("camera_event")
    } else {
        // JsonLog.i("camera_event")
    }
}

/**
 * Calculate total rotation needed for correct orientation
 */
fun calculateTotalRotation(
    sensorOrientation: Int,
    displayRotationDegrees: Int
): Int {
    // Standard Camera2 rotation calculation
    return (sensorOrientation - displayRotationDegrees + 360) % 360
}