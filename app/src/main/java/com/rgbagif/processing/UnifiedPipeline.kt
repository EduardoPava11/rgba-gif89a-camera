package com.rgbagif.processing

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.m3gif.*
import java.io.File

/**
 * Unified M1→M2→M3 Pipeline using desktop-proven m3gif-core
 * 
 * This processor implements the complete capture-to-GIF pipeline:
 * - M1: RGBA_8888 capture to CBOR V2 (byte-exact)
 * - M2: Downscale 729→81 via Rust (CPU)
 * - M3: NeuQuant quantization + GIF89a encoding
 */
class UnifiedPipeline {
    
    companion object {
        private const val TAG = "UnifiedPipeline"
        
        // Frame dimensions
        const val CAPTURE_SIZE = 729  // Input from camera
        const val OUTPUT_SIZE = 81    // Final GIF size (9x9 for Go)
        const val TARGET_FRAMES = 81  // 81 frames for complete sequence
        
        init {
            try {
                System.loadLibrary("m3gif")
                initAndroidLogger()
                Log.i(TAG, "M3GIF library loaded successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load m3gif library", e)
            }
        }
    }
    
    // Pipeline state
    private val capturedFrames = mutableListOf<ByteArray>()
    private var frameIndex = 0
    private var sessionId = ""
    private var outputDir = ""
    
    /**
     * Start a new capture session
     */
    fun startSession(baseDir: String): String {
        sessionId = "session_${System.currentTimeMillis()}"
        outputDir = "$baseDir/$sessionId"
        
        // Create directories
        File("$outputDir/cbor").mkdirs()
        File("$outputDir/rgba_81").mkdirs()
        
        capturedFrames.clear()
        frameIndex = 0
        
        Log.i(TAG, "Started session: $sessionId")
        return sessionId
    }
    
    /**
     * M1: Process camera frame to CBOR V2
     */
    suspend fun processM1Frame(imageProxy: ImageProxy): Boolean = withContext(Dispatchers.IO) {
        try {
            val startTime = SystemClock.elapsedRealtime()
            
            // Extract RGBA data with stride handling
            val plane = imageProxy.planes[0]
            val width = imageProxy.width
            val height = imageProxy.height
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            
            // Validate expected dimensions
            if (width != CAPTURE_SIZE || height != CAPTURE_SIZE) {
                Log.w(TAG, "Unexpected dimensions: ${width}x${height}, expected ${CAPTURE_SIZE}x${CAPTURE_SIZE}")
            }
            
            // Extract tight RGBA (remove stride padding)
            val buffer = plane.buffer
            val tightRgba = if (rowStride == width * pixelStride) {
                // No padding
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            } else {
                // Remove row padding
                ByteArray(width * height * 4).also { output ->
                    val rowData = ByteArray(rowStride)
                    for (y in 0 until height) {
                        buffer.position(y * rowStride)
                        buffer.get(rowData, 0, rowStride)
                        
                        for (x in 0 until width) {
                            val srcOffset = x * pixelStride
                            val dstOffset = (y * width + x) * 4
                            output[dstOffset] = rowData[srcOffset]         // R
                            output[dstOffset + 1] = rowData[srcOffset + 1] // G
                            output[dstOffset + 2] = rowData[srcOffset + 2] // B
                            output[dstOffset + 3] = rowData[srcOffset + 3] // A
                        }
                    }
                }
            }
            
            // Write CBOR V2 frame
            val cborPath = "$outputDir/cbor/frame_${frameIndex.toString().padStart(3, '0')}.cbor"
            val writeTime = writeCborFrameV2Simple(
                rgbaData = tightRgba.map { it.toUByte() },
                width = width.toUShort(),
                height = height.toUShort(),
                stride = (width * 4).toUInt(),
                frameIndex = frameIndex.toUShort(),
                timestampMs = (imageProxy.imageInfo.timestamp / 1_000_000).toULong(),
                outputPath = cborPath
            )
            
            // Log M1 stats for first 3 frames
            if (frameIndex < 3) {
                val nonZero = tightRgba.count { it != 0.toByte() }
                val avgR = tightRgba.filterIndexed { i, _ -> i % 4 == 0 }.map { it.toInt() and 0xFF }.average()
                val avgG = tightRgba.filterIndexed { i, _ -> i % 4 == 1 }.map { it.toInt() and 0xFF }.average()
                val avgB = tightRgba.filterIndexed { i, _ -> i % 4 == 2 }.map { it.toInt() and 0xFF }.average()
                
                Log.i(TAG, "M1_CAPTURE_OK frame=$frameIndex nzRatio=${nonZero.toFloat()/tightRgba.size} " +
                          "avgRGB=(${avgR.toInt()},${avgG.toInt()},${avgB.toInt()}) " +
                          "writeMs=$writeTime size=${tightRgba.size}")
            }
            
            // Store for later processing
            capturedFrames.add(tightRgba)
            frameIndex++
            
            val elapsed = SystemClock.elapsedRealtime() - startTime
            Log.d(TAG, "M1 processed frame $frameIndex in ${elapsed}ms")
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "M1_BAD_FRAME: ${e.message}", e)
            false
        } finally {
            imageProxy.close()
        }
    }
    
    /**
     * M2: Downscale all captured frames from 729→81
     */
    suspend fun processM2Downscale(): List<ByteArray> = withContext(Dispatchers.IO) {
        val downscaledFrames = mutableListOf<ByteArray>()
        
        Log.i(TAG, "M2 starting downscale of ${capturedFrames.size} frames")
        
        for ((idx, frame729) in capturedFrames.withIndex()) {
            try {
                val startTime = SystemClock.elapsedRealtime()
                
                // Extract 729x729 region (27x27 Go board positions)
                val rgba729 = frame729.take(729 * 729 * 4).toByteArray()
                
                // Call Rust M2 downscaler (729→81 = 9x downscale)
                val rgba81 = m2DownscaleRgba729To81(rgba729.map { it.toUByte() }).map { it.toByte() }.toByteArray()
                
                // Validate output size
                if (rgba81.size != 81 * 81 * 4) {
                    Log.e(TAG, "M2 invalid output size: ${rgba81.size}, expected ${81 * 81 * 4}")
                    continue
                }
                
                downscaledFrames.add(rgba81)
                
                val elapsed = SystemClock.elapsedRealtime() - startTime
                Log.d(TAG, "M2_DOWNSIZE_OK frame=$idx t_ms=$elapsed")
                
                // Save downscaled frame for debugging
                val rgbaPath = "$outputDir/rgba_81/frame_${idx.toString().padStart(3, '0')}.rgba"
                File(rgbaPath).writeBytes(rgba81)
                
            } catch (e: Exception) {
                Log.e(TAG, "M2 failed for frame $idx: ${e.message}", e)
            }
        }
        
        Log.i(TAG, "M2 complete: ${downscaledFrames.size}/${capturedFrames.size} frames downscaled")
        downscaledFrames
    }
    
    /**
     * M3: Encode frames to GIF89a using NeuQuant
     */
    suspend fun processM3GifEncode(rgba81Frames: List<ByteArray>): String? = withContext(Dispatchers.IO) {
        try {
            val startTime = SystemClock.elapsedRealtime()
            
            Log.i(TAG, "M3_START frames=${rgba81Frames.size} quant=NeuQuant")
            
            // Validate all frames are 81x81 RGBA
            for ((idx, frame) in rgba81Frames.withIndex()) {
                if (frame.size != 81 * 81 * 4) {
                    Log.e(TAG, "M3 invalid frame $idx size: ${frame.size}")
                    return@withContext null
                }
            }
            
            // Output path in app-specific external storage
            val gifPath = "$outputDir/final.gif"
            
            // Call Rust M3 encoder with NeuQuant + GIF89a
            m3Gif89aEncodeRgbaFrames(
                rgbaFrames = rgba81Frames.map { frame -> frame.map { it.toUByte() } },
                outputFile = gifPath
            )
            
            val elapsed = SystemClock.elapsedRealtime() - startTime
            val gifFile = File(gifPath)
            
            if (gifFile.exists()) {
                Log.i(TAG, "M3_GIF_DONE path=$gifPath size=${gifFile.length()} " +
                          "frames=${rgba81Frames.size} t_ms=$elapsed")
                
                // Verify GIF structure
                val verification = verifyGif89aStructure(gifPath)
                Log.i(TAG, "M3_UNIFFI_SUCCESS count=${rgba81Frames.size} " +
                          "header=${verification["header"]} " +
                          "frames=${verification["frame_count"]} " +
                          "loop=${verification["has_loop"]}")
                
                gifPath
            } else {
                Log.e(TAG, "M3 failed: GIF file not created")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "M3 encoding failed: ${e.message}", e)
            null
        }
    }
    
    /**
     * Execute complete pipeline M1→M2→M3
     */
    suspend fun executeFullPipeline(): String? = withContext(Dispatchers.IO) {
        Log.i(TAG, "Executing full pipeline with ${capturedFrames.size} frames")
        
        // M2: Downscale
        val rgba81Frames = processM2Downscale()
        if (rgba81Frames.isEmpty()) {
            Log.e(TAG, "M2 produced no frames")
            return@withContext null
        }
        
        // M3: Encode GIF
        val gifPath = processM3GifEncode(rgba81Frames)
        
        // Log final stats
        if (gifPath != null) {
            val gifFile = File(gifPath)
            Log.i(TAG, "Pipeline complete: ${gifFile.absolutePath} (${gifFile.length()} bytes)")
        }
        
        gifPath
    }
    
    /**
     * Get current capture progress
     */
    fun getProgress(): CaptureProgress {
        return CaptureProgress(
            framesCaptured = frameIndex,
            targetFrames = TARGET_FRAMES,
            sessionId = sessionId,
            outputDir = outputDir
        )
    }
    
    /**
     * Reset for new capture
     */
    fun reset() {
        capturedFrames.clear()
        frameIndex = 0
        sessionId = ""
        outputDir = ""
    }
}

/**
 * Capture progress data
 */
data class CaptureProgress(
    val framesCaptured: Int,
    val targetFrames: Int,
    val sessionId: String,
    val outputDir: String
)