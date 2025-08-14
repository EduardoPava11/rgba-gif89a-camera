package com.rgbagif.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.view.Surface
import android.view.WindowManager

/**
 * Helper for calculating rotation needed for correct camera orientation
 */
object RotationHelper {
    
    /**
     * Calculate the total rotation needed for WYSIWYG preview
     * 
     * @param context Application context
     * @param cameraId The camera being used
     * @return Total rotation in degrees (0, 90, 180, or 270)
     */
    fun calculateTotalRotation(
        context: Context,
        cameraId: String
    ): Int {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        
        // Get sensor orientation (how the sensor is mounted in the device)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        
        // Get display rotation
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayRotation = windowManager.defaultDisplay.rotation
        
        // Convert Surface rotation constants to degrees
        val displayDegrees = when (displayRotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        
        // Standard Camera2 rotation calculation
        // For front camera, we'd need to account for mirroring, but Milestone 1 uses back camera
        val totalRotation = (sensorOrientation - displayDegrees + 360) % 360
        
        return totalRotation
    }
    
    /**
     * Apply rotation to RGB buffer if needed
     * 
     * @param rgbData Input RGB data
     * @param width Buffer width
     * @param height Buffer height
     * @param rotationDegrees Rotation to apply (0, 90, 180, or 270)
     * @return Rotated RGB data (dimensions may be swapped for 90/270)
     */
    fun rotateRgbBuffer(
        rgbData: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int
    ): RotatedBuffer {
        when (rotationDegrees) {
            0 -> return RotatedBuffer(rgbData, width, height)
            
            90 -> {
                // Rotate 90 degrees clockwise
                val rotated = ByteArray(rgbData.size)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val srcIdx = (y * width + x) * 3
                        val dstX = height - 1 - y
                        val dstY = x
                        val dstIdx = (dstY * height + dstX) * 3
                        
                        // Copy RGB pixel
                        System.arraycopy(rgbData, srcIdx, rotated, dstIdx, 3)
                    }
                }
                return RotatedBuffer(rotated, height, width) // Dimensions swapped
            }
            
            180 -> {
                // Rotate 180 degrees
                val rotated = ByteArray(rgbData.size)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val srcIdx = (y * width + x) * 3
                        val dstX = width - 1 - x
                        val dstY = height - 1 - y
                        val dstIdx = (dstY * width + dstX) * 3
                        
                        // Copy RGB pixel
                        System.arraycopy(rgbData, srcIdx, rotated, dstIdx, 3)
                    }
                }
                return RotatedBuffer(rotated, width, height)
            }
            
            270 -> {
                // Rotate 270 degrees clockwise (90 counter-clockwise)
                val rotated = ByteArray(rgbData.size)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val srcIdx = (y * width + x) * 3
                        val dstX = y
                        val dstY = width - 1 - x
                        val dstIdx = (dstY * height + dstX) * 3
                        
                        // Copy RGB pixel
                        System.arraycopy(rgbData, srcIdx, rotated, dstIdx, 3)
                    }
                }
                return RotatedBuffer(rotated, height, width) // Dimensions swapped
            }
            
            else -> throw IllegalArgumentException("Invalid rotation: $rotationDegrees")
        }
    }
    
    /**
     * Result of rotating a buffer
     */
    data class RotatedBuffer(
        val data: ByteArray,
        val width: Int,
        val height: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as RotatedBuffer
            return data.contentEquals(other.data) && 
                   width == other.width && 
                   height == other.height
        }
        
        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + width
            result = 31 * result + height
            return result
        }
    }
}