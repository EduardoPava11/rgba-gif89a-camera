package com.rgbagif.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Trace
import com.rgbagif.camera.CameraConfig
import com.rgbagif.gif.RgbaFrame
import timber.log.Timber

/**
 * Upscales frames from capture resolution (729×729) to export resolution (1440×1440)
 * Uses high-quality bilinear interpolation for smooth results
 */
class FrameUpscaler {
    
    companion object {
        private const val TAG = "FrameUpscaler"
    }
    
    private val paint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true // Enable bilinear filtering
        isDither = false // We'll handle dithering in quantization
    }
    
    /**
     * Upscale a single frame from 729×729 to 1440×1440
     */
    fun upscaleFrame(captureFrame: RgbaFrame): RgbaFrame {
        Trace.beginSection("FRAME_UPSCALE")
        
        try {
            // Validate input dimensions
            require(captureFrame.width == CameraConfig.CAPTURE_WIDTH) {
                "Invalid capture width: ${captureFrame.width}, expected ${CameraConfig.CAPTURE_WIDTH}"
            }
            require(captureFrame.height == CameraConfig.CAPTURE_HEIGHT) {
                "Invalid capture height: ${captureFrame.height}, expected ${CameraConfig.CAPTURE_HEIGHT}"
            }
            
            // Create source bitmap from capture data
            val sourceBitmap = Bitmap.createBitmap(
                captureFrame.pixels,
                captureFrame.width,
                captureFrame.height,
                Bitmap.Config.ARGB_8888
            )
            
            // Create target bitmap at export resolution
            val targetBitmap = Bitmap.createBitmap(
                CameraConfig.EXPORT_WIDTH,
                CameraConfig.EXPORT_HEIGHT,
                Bitmap.Config.ARGB_8888
            )
            
            // Perform high-quality upscale
            val canvas = Canvas(targetBitmap)
            canvas.drawBitmap(
                sourceBitmap,
                null, // Source rect (entire bitmap)
                android.graphics.Rect(0, 0, CameraConfig.EXPORT_WIDTH, CameraConfig.EXPORT_HEIGHT),
                paint
            )
            
            // Extract pixels from upscaled bitmap
            val upscaledPixels = IntArray(CameraConfig.EXPORT_WIDTH * CameraConfig.EXPORT_HEIGHT)
            targetBitmap.getPixels(
                upscaledPixels,
                0,
                CameraConfig.EXPORT_WIDTH,
                0, 0,
                CameraConfig.EXPORT_WIDTH,
                CameraConfig.EXPORT_HEIGHT
            )
            
            // Clean up bitmaps
            sourceBitmap.recycle()
            targetBitmap.recycle()
            
            Timber.tag(TAG).d(
                "Upscaled frame from ${captureFrame.width}×${captureFrame.height} to " +
                "${CameraConfig.EXPORT_WIDTH}×${CameraConfig.EXPORT_HEIGHT}"
            )
            
            return RgbaFrame(
                CameraConfig.EXPORT_WIDTH,
                CameraConfig.EXPORT_HEIGHT,
                upscaledPixels
            )
            
        } finally {
            Trace.endSection()
        }
    }
    
    /**
     * Upscale multiple frames in batch
     */
    fun upscaleFrames(captureFrames: List<RgbaFrame>): List<RgbaFrame> {
        Trace.beginSection("BATCH_UPSCALE")
        
        try {
            Timber.tag(TAG).d("Upscaling ${captureFrames.size} frames from 729×729 to 1440×1440")
            
            return captureFrames.map { frame ->
                upscaleFrame(frame)
            }
            
        } finally {
            Trace.endSection()
        }
    }
    
    /**
     * Fast nearest-neighbor upscale (lower quality but faster)
     * Use this for preview/draft exports
     */
    fun upscaleFrameFast(captureFrame: RgbaFrame): RgbaFrame {
        Trace.beginSection("FAST_UPSCALE")
        
        try {
            val srcWidth = captureFrame.width
            val srcHeight = captureFrame.height
            val dstWidth = CameraConfig.EXPORT_WIDTH
            val dstHeight = CameraConfig.EXPORT_HEIGHT
            
            val upscaledPixels = IntArray(dstWidth * dstHeight)
            val scaleX = srcWidth.toFloat() / dstWidth
            val scaleY = srcHeight.toFloat() / dstHeight
            
            for (dstY in 0 until dstHeight) {
                for (dstX in 0 until dstWidth) {
                    val srcX = (dstX * scaleX).toInt().coerceIn(0, srcWidth - 1)
                    val srcY = (dstY * scaleY).toInt().coerceIn(0, srcHeight - 1)
                    val srcIndex = srcY * srcWidth + srcX
                    val dstIndex = dstY * dstWidth + dstX
                    
                    upscaledPixels[dstIndex] = captureFrame.pixels[srcIndex]
                }
            }
            
            return RgbaFrame(dstWidth, dstHeight, upscaledPixels)
            
        } finally {
            Trace.endSection()
        }
    }
    
    /**
     * Validate that frames are at correct resolutions
     */
    fun validateDimensions(frames: List<RgbaFrame>, isCapture: Boolean): Boolean {
        return if (isCapture) {
            frames.all { frame ->
                CameraConfig.validateCaptureDimensions(frame.width, frame.height)
            }
        } else {
            frames.all { frame ->
                CameraConfig.validateExportDimensions(frame.width, frame.height)
            }
        }
    }
}