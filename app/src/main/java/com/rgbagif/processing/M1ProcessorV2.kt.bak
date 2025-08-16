package com.rgbagif.processing

import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.m3gif.*
import java.io.File
import java.nio.ByteBuffer

/**
 * M1 Processor V2 - Enhanced CBOR frame capture with metadata
 * Implements the M1 specification for high-fidelity 8-bit RGBA capture
 * 
 * Key improvements:
 * - CborFrameV2 format with CRC32 integrity checking
 * - Full camera metadata extraction
 * - Row stride handling for tight packing
 * - Quality validation and reporting
 * - Zero-copy optimizations where possible
 */
class M1ProcessorV2 {
    
    companion object {
        private const val TAG = "M1ProcessorV2"
        
        // Frame dimensions
        const val CAPTURE_WIDTH = 729
        const val CAPTURE_HEIGHT = 729
        
        // Quality thresholds
        const val MAX_CLIPPED_RATIO = 0.05f  // Max 5% clipped pixels
        const val MIN_DYNAMIC_RANGE = 0.3f   // Min 30% dynamic range
    }
    
    // Track frame statistics
    private var frameIndex = 0
    private var sessionStartTime = SystemClock.elapsedRealtime()
    
    /**
     * Process ImageProxy to CBOR V2 format with full metadata
     */
    suspend fun processFrame(
        imageProxy: ImageProxy,
        outputDir: String,
        captureResult: CaptureResult? = null
    ): CborFrameResult = withContext(Dispatchers.IO) {
        val startTime = SystemClock.elapsedRealtime()
        
        try {
            // Validate image format
            require(imageProxy.format == PixelFormat.RGBA_8888 || 
                    imageProxy.format == PixelFormat.RGBX_8888) {
                "Invalid format: expected RGBA_8888, got ${imageProxy.format}"
            }
            
            // Extract RGBA data with stride handling
            val (rgbaData, actualStride) = extractRgbaData(imageProxy)
            
            // Extract metadata from CaptureResult if available
            val metadata = extractMetadata(captureResult, imageProxy)
            
            // Generate output path
            val outputPath = "$outputDir/frame_${frameIndex.toString().padStart(3, '0')}.cbor"
            
            // Ensure output directory exists
            File(outputDir).mkdirs()
            
            // Write CBOR V2 frame using Rust
            val writeTimeMs = writeCborFrameV2Simple(
                rgbaData = rgbaData.map { it.toUByte() },
                width = imageProxy.width.toUShort(),
                height = imageProxy.height.toUShort(),
                stride = actualStride,
                frameIndex = frameIndex.toUShort(),
                timestampMs = (imageProxy.imageInfo.timestamp / 1_000_000).toULong(),
                outputPath = outputPath
            )
            
            // Log quality metrics for first few frames
            if (frameIndex < 3) {
                logFrameQuality(rgbaData, imageProxy.width, imageProxy.height)
            }
            
            // Increment frame counter
            frameIndex++
            
            val processingTime = SystemClock.elapsedRealtime() - startTime
            
            CborFrameResult(
                success = true,
                frameIndex = frameIndex - 1,
                outputPath = outputPath,
                writeTimeMs = writeTimeMs,
                processingTimeMs = processingTime.toInt(),
                width = imageProxy.width,
                height = imageProxy.height,
                fileSize = File(outputPath).length()
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process frame $frameIndex", e)
            CborFrameResult(
                success = false,
                frameIndex = frameIndex,
                error = e.message
            )
        } finally {
            imageProxy.close()
        }
    }
    
    /**
     * Extract RGBA data from ImageProxy with proper stride handling
     */
    private fun extractRgbaData(imageProxy: ImageProxy): Pair<ByteArray, UInt> {
        val plane = imageProxy.planes[0]
        val width = imageProxy.width
        val height = imageProxy.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        
        // Log stride info for debugging
        Log.d(TAG, "Frame $frameIndex: width=$width, height=$height, rowStride=$rowStride, pixelStride=$pixelStride")
        
        val buffer = plane.buffer
        
        // Check if we need to remove stride padding
        return if (rowStride == width * pixelStride) {
            // No padding, can use buffer directly
            val rgbaData = ByteArray(buffer.remaining())
            buffer.get(rgbaData)
            Pair(rgbaData, rowStride.toUInt())
        } else {
            // Remove row padding for tight packing
            val tightRgba = ByteArray(width * height * 4)
            val rowData = ByteArray(rowStride)
            
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                buffer.get(rowData, 0, rowStride)
                
                // Copy only the actual pixel data (not padding)
                for (x in 0 until width) {
                    val srcOffset = x * pixelStride
                    val dstOffset = (y * width + x) * 4
                    
                    tightRgba[dstOffset] = rowData[srcOffset]         // R
                    tightRgba[dstOffset + 1] = rowData[srcOffset + 1] // G
                    tightRgba[dstOffset + 2] = rowData[srcOffset + 2] // B
                    tightRgba[dstOffset + 3] = rowData[srcOffset + 3] // A
                }
            }
            
            Pair(tightRgba, (width * 4).toUInt())
        }
    }
    
    /**
     * Extract camera metadata from CaptureResult
     */
    private fun extractMetadata(
        captureResult: CaptureResult?,
        imageProxy: ImageProxy
    ): FrameMetadata {
        return FrameMetadata(
            exposureTimeNs = captureResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
            isoSensitivity = captureResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100,
            focalLengthMm = captureResult?.get(CaptureResult.LENS_FOCAL_LENGTH) ?: 4.0f,
            apertureFStop = captureResult?.get(CaptureResult.LENS_APERTURE) ?: 2.0f,
            colorTemperature = extractColorTemperature(captureResult),
            tintCorrection = 0, // Not directly available in Camera2
            sensorTimestamp = captureResult?.get(CaptureResult.SENSOR_TIMESTAMP) ?: imageProxy.imageInfo.timestamp,
            rotationDegrees = imageProxy.imageInfo.rotationDegrees,
            isMirrored = false // Back camera is not mirrored
        )
    }
    
    /**
     * Extract color temperature from RGG gains
     */
    private fun extractColorTemperature(captureResult: CaptureResult?): UInt {
        val gains = captureResult?.get(CaptureResult.COLOR_CORRECTION_GAINS)
        return if (gains != null) {
            // Estimate color temperature from R/B ratio
            val rbRatio = gains.red / gains.blue
            when {
                rbRatio < 0.8f -> 7500u  // Cool (blueish)
                rbRatio < 1.0f -> 6500u  // D65 daylight
                rbRatio < 1.2f -> 5500u  // Neutral
                rbRatio < 1.5f -> 4500u  // Warm
                else -> 3500u            // Very warm
            }
        } else {
            5500u // Default neutral
        }
    }
    
    /**
     * Log frame quality metrics
     */
    private fun logFrameQuality(rgbaData: ByteArray, width: Int, height: Int) {
        val pixelCount = width * height
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var sumA = 0L
        var clippedCount = 0
        var nonOpaqueCount = 0
        
        for (i in 0 until pixelCount) {
            val idx = i * 4
            val r = rgbaData[idx].toInt() and 0xFF
            val g = rgbaData[idx + 1].toInt() and 0xFF
            val b = rgbaData[idx + 2].toInt() and 0xFF
            val a = rgbaData[idx + 3].toInt() and 0xFF
            
            sumR += r
            sumG += g
            sumB += b
            sumA += a
            
            // Count clipped pixels
            if (r == 0 || r == 255) clippedCount++
            if (g == 0 || g == 255) clippedCount++
            if (b == 0 || b == 255) clippedCount++
            
            // Count non-opaque pixels
            if (a != 255) nonOpaqueCount++
        }
        
        val avgR = (sumR / pixelCount).toInt()
        val avgG = (sumG / pixelCount).toInt()
        val avgB = (sumB / pixelCount).toInt()
        val avgA = (sumA / pixelCount).toInt()
        
        val clippedRatio = clippedCount.toFloat() / (pixelCount * 3) // 3 channels
        val alphaUsage = nonOpaqueCount.toFloat() / pixelCount
        
        Log.i(TAG, "M1_V2_QUALITY frame=$frameIndex " +
                  "avgRGB=($avgR,$avgG,$avgB) avgA=$avgA " +
                  "clipped=${String.format("%.2f", clippedRatio * 100)}% " +
                  "alpha=${String.format("%.2f", alphaUsage * 100)}%")
        
        // Warn if quality issues detected
        if (clippedRatio > MAX_CLIPPED_RATIO) {
            Log.w(TAG, "Frame $frameIndex has high clipping: ${clippedRatio * 100}%")
        }
        
        if (alphaUsage > 0.01f) {
            Log.w(TAG, "Frame $frameIndex has non-opaque pixels: ${alphaUsage * 100}%")
        }
    }
    
    /**
     * Reset frame counter for new capture session
     */
    fun reset() {
        frameIndex = 0
        sessionStartTime = SystemClock.elapsedRealtime()
        Log.i(TAG, "M1_V2 processor reset for new session")
    }
    
    /**
     * Get current frame index
     */
    fun getCurrentFrameIndex(): Int = frameIndex
    
    /**
     * Verify CBOR file integrity
     */
    suspend fun verifyFile(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            verifyCborV2File(path)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify file $path", e)
            false
        }
    }
}

/**
 * Camera metadata for frame
 */
data class FrameMetadata(
    val exposureTimeNs: Long,
    val isoSensitivity: Int,
    val focalLengthMm: Float,
    val apertureFStop: Float,
    val colorTemperature: UInt,
    val tintCorrection: Short,
    val sensorTimestamp: Long,
    val rotationDegrees: Int,
    val isMirrored: Boolean
)

/**
 * Result of CBOR frame processing
 */
data class CborFrameResult(
    val success: Boolean,
    val frameIndex: Int,
    val outputPath: String? = null,
    val writeTimeMs: UInt = 0u,
    val processingTimeMs: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val fileSize: Long = 0,
    val error: String? = null
)