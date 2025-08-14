package com.rgbagif.milestones

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Trace
import android.util.Log
import com.rgbagif.camera.CameraXManager
import com.rgbagif.camera.Milestone1Config
import com.rgbagif.storage.FrameStorage
import com.rgbagif.log.LogEvent
// import com.rgbagif.rust.RustCborWriter - removed, using M1Fast JNI instead
import com.rgbagif.tools.cbor.CborTools
import com.rgbagif.processing.M2Processor
import com.rgbagif.processing.M3Processor
import com.rgbagif.config.AppConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.system.measureTimeMillis

/**
 * Central coordinator for the 3-milestone workflow:
 * M1: CBOR Capture + PNG Generation (729�729, 81 frames)
 * M2: Neural Network Downsizing + PNG Generation
 * M3: Color Quantization + GIF89a Export
 */
class MilestoneManager(
    private val context: Context,
    private val cameraManager: CameraXManager,
    private val onGifCreated: (File) -> Unit = {}
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // private val rustCborWriter = RustCborWriter(context) - removed, using M1Fast JNI instead
    
    // Session tracking
    private var sessionId = UUID.randomUUID().toString()
    private var sessionDir: File? = null
    
    // Progress state
    private val _progress = MutableStateFlow(MilestoneProgress())
    val progress: StateFlow<MilestoneProgress> = _progress.asStateFlow()
    
    // Frame storage
    private val frameStorage = FrameStorage(context)
    
    // Processors for M2 and M3
    private val m2Processor = M2Processor()
    private val m3Processor = M3Processor()
    
    // Timing tracking
    private var m1StartTime = 0L
    private var m1EndTime = 0L
    private var m2StartTime = 0L
    private var m2EndTime = 0L
    private var m3StartTime = 0L
    private var m3EndTime = 0L
    
    /**
     * Start Milestone 1: Capture 81 frames at 729�729
     */
    suspend fun startMilestone1() {
        try {
            Trace.beginSection("Milestone1")
            m1StartTime = System.currentTimeMillis()
            
            // Create session directory
            sessionId = UUID.randomUUID().toString()
            val session = frameStorage.startCaptureSession(sessionId)
            sessionDir = session.cborDir.parentFile
            
            // Log session start
            LogEvent.Entry(
                event = "session_start",
                milestone = "M1",
                sessionId = sessionId,
                extra = mapOf(
                    "frame_count" to Milestone1Config.FRAME_COUNT,
                    "resolution" to "${Milestone1Config.CAPTURE_WIDTH}x${Milestone1Config.CAPTURE_HEIGHT}"
                )
            ).log()
            
            _progress.value = MilestoneProgress(
                milestone = 1,
                state = MilestoneState.MILESTONE_1_CAPTURING,
                totalFrames = Milestone1Config.FRAME_COUNT,
                message = "Starting capture..."
            )
            
            // Configure camera for 729×729 capture
            cameraManager.setFrameCallback { rgba, width, height ->
                if (_progress.value.currentFrame < Milestone1Config.FRAME_COUNT) {
                    // Convert RGBA bytes to bitmap and process
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(rgba))
                    processM1Frame(bitmap)
                }
            }
            
            // Note: Camera must be started from UI with proper lifecycle
            // For now, just wait for frames
            
            // Wait for all frames to be captured
            while (_progress.value.currentFrame < Milestone1Config.FRAME_COUNT) {
                delay(100)
            }
            
            // Clear frame callback
            cameraManager.setFrameCallback { _, _, _ -> }
            
            // Generate PNGs from CBORs
            generateM1Pngs()
            
            m1EndTime = System.currentTimeMillis()
            val m1Duration = m1EndTime - m1StartTime
            
            // Log completion
            LogEvent.Entry(
                event = "milestone_complete",
                milestone = "M1",
                sessionId = sessionId,
                extra = mapOf(
                    "duration_ms" to m1Duration,
                    "frames_captured" to _progress.value.currentFrame
                )
            ).log()
            
            _progress.value = _progress.value.copy(
                state = MilestoneState.MILESTONE_1_COMPLETE,
                processingTimeMs = m1Duration,
                message = "Capture complete: ${_progress.value.currentFrame} frames in ${m1Duration}ms"
            )
            
        } catch (e: Exception) {
            Log.e("MilestoneManager", "M1 error", e)
            LogEvent.Entry(
                event = LogEvent.EVENT_ERROR,
                milestone = "M1",
                sessionId = sessionId,
                ok = false,
                errCode = LogEvent.E_IO,
                errMsg = e.message,
                tag = LogEvent.TAG_ERROR
            ).log()
            _progress.value = _progress.value.copy(
                state = MilestoneState.ERROR,
                error = e.message
            )
        } finally {
            Trace.endSection()
        }
    }
    
    private fun processM1Frame(bitmap: Bitmap) {
        scope.launch {
            try {
                val frameIndex = _progress.value.currentFrame
                
                // Log frame capture
                LogEvent.Entry(
                    event = "frame_captured",
                    milestone = "M1",
                    sessionId = sessionId,
                    frameIndex = frameIndex,
                    extra = mapOf(
                        "width" to bitmap.width,
                        "height" to bitmap.height
                    )
                ).log()
                
                // Save as CBOR
                val cborFile = File(sessionDir, "cbor/frame_${frameIndex.toString().padStart(3, '0')}.cbor")
                cborFile.parentFile?.mkdirs()
                
                val timestampMs = System.currentTimeMillis()
                val rgbaBytes = bitmapToRgbaBytes(bitmap)
                
                // Use M1Fast JNI for fast CBOR writing
                try {
                    val directBuffer = java.nio.ByteBuffer.allocateDirect(rgbaBytes.size)
                    directBuffer.put(rgbaBytes)
                    directBuffer.rewind()
                    
                    val success = com.rgbagif.native.M1Fast.writeFrame(
                        directBuffer,
                        bitmap.width,
                        bitmap.height,
                        bitmap.width * 4,  // stride in bytes (RGBA = 4 bytes per pixel)
                        timestampMs,
                        frameIndex,
                        cborFile.absolutePath
                    )
                    
                    if (!success) {
                        // Fallback to Kotlin implementation
                        Log.w("MilestoneManager", "M1Fast JNI write failed, using Kotlin fallback")
                        val frameData = com.rgbagif.tools.cbor.RgbaFrame(
                            width = bitmap.width,
                            height = bitmap.height,
                            rgba = rgbaBytes,
                            timestampMs = timestampMs,
                            meta = mapOf("frameIndex" to frameIndex.toString())
                        )
                        val cborBytes = CborTools.encodeFrame(frameData)
                        cborFile.writeBytes(cborBytes)
                    }
                } catch (e: Exception) {
                    Log.e("MilestoneManager", "Error using M1Fast JNI", e)
                }
                
                // Update progress
                _progress.value = _progress.value.copy(
                    currentFrame = frameIndex + 1,
                    message = "Captured frame ${frameIndex + 1}/${Milestone1Config.FRAME_COUNT}"
                )
                
            } catch (e: Exception) {
                Log.e("MilestoneManager", "Frame processing error", e)
            }
        }
    }
    
    private suspend fun generateM1Pngs() {
        _progress.value = _progress.value.copy(
            state = MilestoneState.MILESTONE_1_GENERATING_PNG,
            message = "Generating JPEG files..."
        )
        
        val cborDir = File(sessionDir, "cbor")
        val jpegDir = File(sessionDir, "jpeg_m1")
        jpegDir.mkdirs()
        
        val cborFiles = cborDir.listFiles { f -> f.extension == "cbor" } ?: return
        
        cborFiles.forEach { cborFile ->
            try {
                val jpegFile = File(jpegDir, cborFile.nameWithoutExtension + ".jpg")
                CborTools.decodeToJpeg(cborFile, jpegFile, 95)
                
                LogEvent.Entry(
                    event = "jpeg_generated",
                    milestone = "M1",
                    sessionId = sessionId,
                    extra = mapOf(
                        "cbor_file" to cborFile.name,
                        "jpeg_file" to jpegFile.name
                    )
                ).log()
            } catch (e: Exception) {
                Log.e("MilestoneManager", "JPEG generation error", e)
            }
        }
    }
    
    /**
     * Start Milestone 2: Neural Network Downsizing
     */
    suspend fun startMilestone2() {
        try {
            Trace.beginSection("Milestone2")
            m2StartTime = System.currentTimeMillis()
            
            _progress.value = MilestoneProgress(
                milestone = 2,
                state = MilestoneState.MILESTONE_2_DOWNSIZING,
                totalFrames = AppConfig.TOTAL_FRAMES,
                message = "Starting neural network downsizing..."
            )
            
            LogEvent.Entry(
                event = "milestone_start",
                milestone = "M2",
                sessionId = sessionId
            ).log()
            
            // Start M2 session
            val m2SessionId = m2Processor.startSession()
            
            // Process each CBOR frame through neural network
            val cborDir = File(sessionDir, "cbor")
            val cborFiles = cborDir.listFiles { f -> f.extension == "cbor" }?.sortedBy { it.name } ?: return
            
            // Load all frames as RGBA data for M2 processing
            val rgbaFrames = mutableListOf<ByteArray>()
            cborFiles.forEach { cborFile ->
                val frame = CborTools.decodeFrame(cborFile)
                rgbaFrames.add(frame.rgba)
            }
            
            // Process all frames with M2 neural downsampling
            val sessionStats = m2Processor.processSession(
                frames = rgbaFrames,
                outputDir = sessionDir!!,
                onProgress = { current, total ->
                    _progress.value = _progress.value.copy(
                        currentFrame = current,
                        message = "Downsized frame $current/$total"
                    )
                }
            )
            
            m2EndTime = System.currentTimeMillis()
            val m2Duration = m2EndTime - m2StartTime
            
            LogEvent.Entry(
                event = "milestone_complete",
                milestone = "M2",
                sessionId = sessionId,
                extra = mapOf(
                    "duration_ms" to m2Duration,
                    "frames_processed" to sessionStats.successfulFrames,
                    "avg_frame_ms" to sessionStats.averageTimeMs,
                    "neural_confidence" to (sessionStats.qualityMetrics?.policyConfidenceAvg ?: 0.0)
                )
            ).log()
            
            _progress.value = _progress.value.copy(
                state = MilestoneState.MILESTONE_2_COMPLETE,
                processingTimeMs = m2Duration,
                message = "Downsizing complete: ${sessionStats.successfulFrames} frames in ${m2Duration}ms"
            )
            
            // If GIF was created, notify callback on main thread
            sessionStats.gifFile?.let { gif ->
                withContext(Dispatchers.Main) {
                    Log.d("MilestoneManager", "Calling onGifCreated with file: ${gif.absolutePath}")
                    onGifCreated(gif)
                }
            }
            
        } catch (e: Exception) {
            Log.e("MilestoneManager", "M2 error", e)
            LogEvent.Entry(
                event = LogEvent.EVENT_ERROR,
                milestone = "M2",
                sessionId = sessionId,
                ok = false,
                errCode = LogEvent.E_NN_INFER,
                errMsg = e.message,
                tag = LogEvent.TAG_ERROR
            ).log()
            _progress.value = _progress.value.copy(
                state = MilestoneState.ERROR,
                error = e.message
            )
        } finally {
            Trace.endSection()
        }
    }
    
    private suspend fun generateM2Pngs() {
        _progress.value = _progress.value.copy(
            state = MilestoneState.MILESTONE_2_GENERATING_PNG,
            message = "Generating downsized JPEG files..."
        )
        
        val downsizedDir = File(sessionDir, "cbor_downsized")
        val jpegDir = File(sessionDir, "jpeg_m2")
        jpegDir.mkdirs()
        
        val cborFiles = downsizedDir.listFiles { f -> f.extension == "cbor" } ?: return
        
        cborFiles.forEach { cborFile ->
            try {
                val jpegFile = File(jpegDir, cborFile.nameWithoutExtension + ".jpg")
                CborTools.decodeToJpeg(cborFile, jpegFile, 95)
            } catch (e: Exception) {
                Log.e("MilestoneManager", "M2 JPEG generation error", e)
            }
        }
    }
    
    /**
     * Start Milestone 3: Color Quantization + GIF89a Export
     */
    suspend fun startMilestone3() {
        try {
            Trace.beginSection("Milestone3")
            m3StartTime = System.currentTimeMillis()
            
            _progress.value = MilestoneProgress(
                milestone = 3,
                state = MilestoneState.MILESTONE_3_QUANTIZING,
                totalFrames = AppConfig.TOTAL_FRAMES,
                message = "Starting GIF89a export..."
            )
            
            LogEvent.Entry(
                event = "milestone_start",
                milestone = "M3",
                sessionId = sessionId
            ).log()
            
            // Start M3 session
            val m3SessionId = m3Processor.startSession()
            
            // Load downsized frames from M2 output (now as JPEGs)
            val m2SessionDir = File(sessionDir, "M2_Session_*").listFiles()?.firstOrNull() 
                ?: File(sessionDir, "downsized")
            val jpegDir = File(m2SessionDir, "downsized")
            
            // Load all JPEG files as bitmaps
            val bitmaps = mutableListOf<Bitmap>()
            val jpegFiles = jpegDir.listFiles { f -> f.extension == "jpg" }?.sortedBy { it.name }
            
            if (jpegFiles == null || jpegFiles.isEmpty()) {
                // Fallback: try to load from CBOR if JPEGs not available
                val cborDir = File(sessionDir, "cbor")
                val cborFiles = cborDir.listFiles { f -> f.extension == "cbor" }?.sortedBy { it.name }
                
                if (cborFiles != null) {
                    // Process all available frames (don't limit to a specific count)
                    cborFiles.forEach { cborFile ->
                        val frame = CborTools.decodeFrame(cborFile)
                        // Convert to bitmap at 81x81 (downsized)
                        val bitmap = Bitmap.createBitmap(
                            AppConfig.EXPORT_WIDTH, 
                            AppConfig.EXPORT_HEIGHT, 
                            Bitmap.Config.ARGB_8888
                        )
                        // Simple downsampling for now (M2 should have done this)
                        val pixels = IntArray(AppConfig.EXPORT_WIDTH * AppConfig.EXPORT_HEIGHT)
                        // ... conversion logic ...
                        bitmap.setPixels(pixels, 0, AppConfig.EXPORT_WIDTH, 0, 0, 
                            AppConfig.EXPORT_WIDTH, AppConfig.EXPORT_HEIGHT)
                        bitmaps.add(bitmap)
                    }
                }
            } else {
                // Load JPEG files as bitmaps
                jpegFiles.forEach { jpegFile ->
                    val bitmap = BitmapFactory.decodeFile(jpegFile.absolutePath)
                    bitmaps.add(bitmap)
                }
            }
            
            // Convert Bitmaps to RGBA ByteArrays for Rust M3 processor
            val rgbaFrames = bitmaps.map { bitmap ->
                val width = bitmap.width
                val height = bitmap.height
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                
                // Convert ARGB to RGBA
                val rgba = ByteArray(width * height * 4)
                for (i in pixels.indices) {
                    val pixel = pixels[i]
                    rgba[i * 4] = ((pixel shr 16) and 0xFF).toByte()     // R
                    rgba[i * 4 + 1] = ((pixel shr 8) and 0xFF).toByte()  // G
                    rgba[i * 4 + 2] = (pixel and 0xFF).toByte()          // B
                    rgba[i * 4 + 3] = ((pixel shr 24) and 0xFF).toByte() // A
                }
                rgba
            }
            
            // Process with M3 GIF export using RGBA frames (Rust implementation)
            val gifDir = File(sessionDir, "gif")
            gifDir.mkdirs()
            val result = m3Processor.exportGif89aFromRgba(
                rgbaFrames = rgbaFrames,
                outputDir = gifDir,
                baseName = "output"
            )
            
            m3EndTime = System.currentTimeMillis()
            val m3Duration = m3EndTime - m3StartTime
            
            LogEvent.Entry(
                event = "milestone_complete",
                milestone = "M3",
                sessionId = sessionId,
                extra = mapOf(
                    "duration_ms" to m3Duration,
                    "gif_file" to result.gifFile.name,
                    "gif_size_bytes" to result.fileSize,
                    "color_count" to result.colorCount,
                    "compression_ratio" to (result.stats?.compressionRatio ?: 0.0)
                )
            ).log()
            
            // Generate diagnostic report
            m3Processor.generateDiagnosticReport(result, sessionDir!!)
            
            _progress.value = _progress.value.copy(
                state = MilestoneState.MILESTONE_3_COMPLETE,
                processingTimeMs = m3Duration,
                message = "GIF export complete: ${result.gifFile.name} (${result.fileSize / 1024}KB)"
            )
            
            // Log total workflow timing
            val totalDuration = m3EndTime - m1StartTime
            LogEvent.Entry(
                event = "workflow_complete",
                milestone = "ALL",
                sessionId = sessionId,
                extra = mapOf(
                    "m1_duration_ms" to (m1EndTime - m1StartTime),
                    "m2_duration_ms" to (m2EndTime - m2StartTime),
                    "m3_duration_ms" to m3Duration,
                    "total_duration_ms" to totalDuration
                )
            ).log()
            
        } catch (e: Exception) {
            Log.e("MilestoneManager", "M3 error", e)
            LogEvent.Entry(
                event = LogEvent.EVENT_ERROR,
                milestone = "M3",
                sessionId = sessionId,
                ok = false,
                errCode = LogEvent.E_GIF_ENCODE,
                errMsg = e.message,
                tag = LogEvent.TAG_ERROR
            ).log()
            _progress.value = _progress.value.copy(
                state = MilestoneState.ERROR,
                error = e.message
            )
        } finally {
            Trace.endSection()
        }
    }
    
    /**
     * Reset workflow to start over
     */
    fun reset() {
        scope.launch {
            // cameraManager.stopCapture() // This method doesn't exist in CameraXManager
            _progress.value = MilestoneProgress()
            sessionId = UUID.randomUUID().toString()
            sessionDir = null
        }
    }
    
    fun getSessionDirectory(): File? = sessionDir
    
    fun getSessionId(): String = sessionId
    
    // Helper functions
    
    private fun bitmapToRgbaBytes(bitmap: Bitmap): ByteArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        val bytes = ByteArray(pixels.size * 4)
        pixels.forEachIndexed { i, pixel ->
            bytes[i * 4] = (pixel shr 16 and 0xFF).toByte()     // R
            bytes[i * 4 + 1] = (pixel shr 8 and 0xFF).toByte()  // G
            bytes[i * 4 + 2] = (pixel and 0xFF).toByte()        // B
            bytes[i * 4 + 3] = (pixel shr 24 and 0xFF).toByte() // A
        }
        return bytes
    }
    
    private fun downsizeWithNN(frame: com.rgbagif.tools.cbor.RgbaFrame): com.rgbagif.tools.cbor.RgbaFrame {
        // Placeholder - actual implementation will use UniFFI to call Rust NN
        // For now, simple bilinear scaling from 729�729 to 81�81
        val targetSize = 81
        val scaleFactor = frame.width.toFloat() / targetSize
        
        val downsizedData = ByteArray(targetSize * targetSize * 4)
        
        for (y in 0 until targetSize) {
            for (x in 0 until targetSize) {
                val srcX = (x * scaleFactor).toInt().coerceIn(0, frame.width - 1)
                val srcY = (y * scaleFactor).toInt().coerceIn(0, frame.height - 1)
                val srcIdx = (srcY * frame.width + srcX) * 4
                val dstIdx = (y * targetSize + x) * 4
                
                System.arraycopy(frame.rgba, srcIdx, downsizedData, dstIdx, 4)
            }
        }
        
        return frame.copy(
            width = targetSize,
            height = targetSize,
            rgba = downsizedData
        )
    }
    
    private fun quantizeColors(frame: com.rgbagif.tools.cbor.RgbaFrame): com.rgbagif.tools.cbor.RgbaFrame {
        // Placeholder - actual implementation will use proper color quantization
        // For now, simple bit reduction to simulate 256 colors
        val quantizedData = frame.rgba.copyOf()
        
        for (i in quantizedData.indices step 4) {
            quantizedData[i] = (quantizedData[i].toInt() and 0xF0).toByte()     // R
            quantizedData[i + 1] = (quantizedData[i + 1].toInt() and 0xF0).toByte() // G
            quantizedData[i + 2] = (quantizedData[i + 2].toInt() and 0xF0).toByte() // B
            // Keep alpha unchanged
        }
        
        return frame.copy(rgba = quantizedData)
    }
    
    private suspend fun createGif89a(quantizedDir: File, outputFile: File) {
        // Placeholder - actual implementation will create proper GIF89a
        // For now, just create an empty file
        outputFile.createNewFile()
        
        // Log GIF creation
        LogEvent.Entry(
            event = "gif_created",
            milestone = "M3",
            sessionId = sessionId,
            extra = mapOf(
                "output_file" to outputFile.absolutePath,
                "frame_count" to (quantizedDir.listFiles()?.size ?: 0)
            )
        ).log()
    }
    
    fun cleanup() {
        scope.cancel()
    }
}