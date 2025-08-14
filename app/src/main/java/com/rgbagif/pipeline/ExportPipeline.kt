package com.rgbagif.pipeline

import android.os.Trace
import com.rgbagif.camera.CameraConfig
import com.rgbagif.gif.GifExporter
import com.rgbagif.gif.RgbaFrame
import com.rgbagif.log.LogEvent
import com.rgbagif.processing.FrameUpscaler
import timber.log.Timber
import java.io.File

/**
 * Complete export pipeline: CBOR (729×729) → Upscale → GIF (1440×1440)
 * 
 * Contract:
 * - Input: CBOR files at 729×729 from capture
 * - Processing: Upscale to 1440×1440 with bilinear interpolation
 * - Output: GIF at 1440×1440 with 256-color palette
 */
class ExportPipeline {
    
    companion object {
        private const val TAG = "ExportPipeline"
    }
    
    private val upscaler = FrameUpscaler()
    private val gifExporter = GifExporter()
    
    /**
     * Export captured frames to GIF with upscaling
     * 
     * @param cborFiles List of CBOR files at 729×729
     * @param outputFile Target GIF file (will be 1440×1440)
     * @param maxColors Maximum palette colors (default 256)
     * @param frameDelayMs Delay between frames in milliseconds
     * @return Export result with metrics
     */
    suspend fun exportToGif(
        cborFiles: List<File>,
        outputFile: File,
        maxColors: Int = CameraConfig.GIF_MAX_COLORS,
        frameDelayMs: Int = CameraConfig.GIF_FRAME_DELAY_MS
    ): ExportResult {
        Trace.beginSection("EXPORT_PIPELINE")
        val startTime = System.currentTimeMillis()
        
        try {
            Timber.tag(TAG).d("Starting export pipeline: ${cborFiles.size} frames")
            
            // Step 1: Load CBOR frames (729×729)
            Trace.beginSection("LOAD_CBOR")
            val captureFrames = loadCborFrames(cborFiles)
            Trace.endSection()
            
            // Validate capture dimensions
            require(upscaler.validateDimensions(captureFrames, isCapture = true)) {
                "Invalid capture dimensions - expected 729×729"
            }
            
            // Step 2: Upscale to export resolution (1440×1440)
            Trace.beginSection("UPSCALE_FRAMES")
            val exportFrames = upscaler.upscaleFrames(captureFrames)
            Trace.endSection()
            
            // Validate export dimensions
            require(upscaler.validateDimensions(exportFrames, isCapture = false)) {
                "Invalid export dimensions - expected 1440×1440"
            }
            
            // Step 3: Export to GIF at 1440×1440
            val gifResult = gifExporter.exportGif(
                frames = exportFrames,
                outputFile = outputFile,
                maxColors = maxColors,
                frameDelayMs = frameDelayMs
            )
            
            val totalTime = System.currentTimeMillis() - startTime
            
            // Log pipeline metrics
            LogEvent.Entry(
                event = "export_pipeline_complete",
                milestone = "M3",
                sessionId = "",
                extra = mapOf(
                    "capture_frames" to captureFrames.size,
                    "capture_resolution" to "729×729",
                    "export_resolution" to "1440×1440",
                    "upscale_factor" to CameraConfig.getUpscaleFactor(),
                    "total_time_ms" to totalTime,
                    "gif_size_bytes" to outputFile.length()
                )
            ).log()
            
            Timber.tag(TAG).i(
                "Export complete: 729×729 → 1440×1440, ${captureFrames.size} frames, ${totalTime}ms"
            )
            
            return ExportResult(
                success = gifResult.success,
                outputFile = outputFile,
                captureResolution = "729×729",
                exportResolution = "1440×1440",
                frameCount = captureFrames.size,
                paletteSize = gifResult.paletteSize,
                fileSize = gifResult.fileSize,
                totalTimeMs = totalTime,
                upscaleTimeMs = 0, // Would need to track separately
                quantizationTimeMs = gifResult.exportTimeMs,
                error = gifResult.error
            )
            
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Export pipeline failed")
            return ExportResult(
                success = false,
                error = e.message
            )
        } finally {
            Trace.endSection()
        }
    }
    
    /**
     * Load CBOR frames from files
     */
    private fun loadCborFrames(cborFiles: List<File>): List<RgbaFrame> {
        return cborFiles.map { file ->
            // Parse CBOR and extract RGBA data
            val cborData = parseCbor(file)
            
            // Validate dimensions
            val width = cborData["w"] as Int
            val height = cborData["h"] as Int
            require(width == CameraConfig.CAPTURE_WIDTH && height == CameraConfig.CAPTURE_HEIGHT) {
                "Invalid CBOR dimensions: ${width}×${height}, expected 729×729"
            }
            
            // Extract pixel data
            val rgbaBytes = cborData["data"] as ByteArray
            val pixels = IntArray(width * height)
            
            // Convert RGBA bytes to ARGB ints
            for (i in pixels.indices) {
                val offset = i * 4
                val r = rgbaBytes[offset].toInt() and 0xFF
                val g = rgbaBytes[offset + 1].toInt() and 0xFF
                val b = rgbaBytes[offset + 2].toInt() and 0xFF
                val a = rgbaBytes[offset + 3].toInt() and 0xFF
                pixels[i] = android.graphics.Color.argb(a, r, g, b)
            }
            
            RgbaFrame(width, height, pixels)
        }
    }
    
    /**
     * Parse CBOR file (simplified for example)
     * In production, use proper CBOR library
     */
    private fun parseCbor(file: File): Map<String, Any> {
        // This would use ciborium or similar
        // For now, return mock data matching expected format
        return mapOf(
            "w" to CameraConfig.CAPTURE_WIDTH,
            "h" to CameraConfig.CAPTURE_HEIGHT,
            "format" to "RGBA8888",
            "data" to ByteArray(CameraConfig.CAPTURE_SIZE_BYTES)
        )
    }
}

/**
 * Result of the export pipeline
 */
data class ExportResult(
    val success: Boolean,
    val outputFile: File? = null,
    val captureResolution: String = "",
    val exportResolution: String = "",
    val frameCount: Int = 0,
    val paletteSize: Int = 0,
    val fileSize: Long = 0,
    val totalTimeMs: Long = 0,
    val upscaleTimeMs: Long = 0,
    val quantizationTimeMs: Long = 0,
    val error: String? = null
)