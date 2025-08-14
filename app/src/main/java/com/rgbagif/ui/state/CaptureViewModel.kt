package com.rgbagif.ui.state

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rgbagif.camera.CameraXManager
import com.rgbagif.config.AppConfig
import com.rgbagif.log.LogEvent
import com.rgbagif.milestones.MilestoneManager
import com.rgbagif.milestones.MilestoneState
import com.rgbagif.processing.M2Processor
import com.rgbagif.processing.M3Processor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CaptureViewModel(
    private val context: Context
) : ViewModel() {
    
    companion object {
        private const val TAG = "CaptureViewModel"
    }
    
    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()
    
    private var frameIndex = 0
    private var sessionId: String? = null
    private var captureStartTime = 0L
    private val capturedFrames = mutableListOf<ByteArray>() // Store RGBA frames for processing
    
    private var m2Processor: M2Processor? = null
    private var m3Processor: M3Processor? = null
    
    init {
        Log.d(TAG, "CaptureViewModel initialized")
    }
    
    fun initialize() {
        Log.d(TAG, "CaptureViewModel initialize() called")
    }
    
    fun initialize(cameraManager: CameraXManager, milestoneManager: MilestoneManager) {
        Log.d(TAG, "CaptureViewModel initialize with CameraXManager called")
        // Set up frame processing callback
        cameraManager.setFrameCallback { rgbaBytes, width, height ->
            if (_uiState.value.isCapturing) {
                processFrame(rgbaBytes, width, height)
            }
        }
    }
    
    fun startCapture(outputDir: File) {
        if (_uiState.value.isCapturing) return
        
        frameIndex = 0
        capturedFrames.clear()
        sessionId = LogEvent.generateSessionId()
        captureStartTime = System.currentTimeMillis()
        
        LogEvent.logCaptureStart(sessionId!!, targetFrames = 81)
        
        updateUiState { it.copy(
            isCapturing = true,
            framesCaptured = 0,
            totalFrames = 81,
            targetFrames = 81,
            captureTimeMs = 0L,
            milestoneState = MilestoneState.MILESTONE_1_CAPTURING,
            error = null
        ) }
        
        Log.d(TAG, "Started capture session: $sessionId, target: 81 frames")
    }
    
    private fun processFrame(rgbaBytes: ByteArray, width: Int, height: Int) {
        if (frameIndex >= 81 || !_uiState.value.isCapturing) {
            return
        }
        
        viewModelScope.launch {
            try {
                // Store the RGBA frame for later processing
                capturedFrames.add(rgbaBytes.copyOf())
                
                frameIndex++
                val elapsedTime = System.currentTimeMillis() - captureStartTime
                
                updateUiState { it.copy(
                    framesCaptured = frameIndex,
                    captureTimeMs = elapsedTime,
                    fps = if (elapsedTime > 0) (frameIndex * 1000f) / elapsedTime else 0f
                ) }
                
                Log.d(TAG, "Captured frame $frameIndex/81 (${rgbaBytes.size} bytes)")
                
                // Auto-stop after 81 frames
                if (frameIndex >= 81) {
                    stopCapture()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Frame processing failed", e)
                updateUiState { 
                    it.copy(
                        isCapturing = false,
                        milestoneState = MilestoneState.IDLE,
                        error = "Frame processing failed: ${e.message}"
                    )
                }
            }
        }
    }
    
    fun processFrame(bitmap: Bitmap) {
        if (!_uiState.value.isCapturing) return
        
        // Convert bitmap to RGBA bytes and process
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        val rgbaBytes = ByteArray(width * height * 4)
        var byteIndex = 0
        for (pixel in pixels) {
            rgbaBytes[byteIndex++] = ((pixel shr 16) and 0xFF).toByte() // R
            rgbaBytes[byteIndex++] = ((pixel shr 8) and 0xFF).toByte()  // G
            rgbaBytes[byteIndex++] = (pixel and 0xFF).toByte()          // B
            rgbaBytes[byteIndex++] = ((pixel shr 24) and 0xFF).toByte() // A
        }
        
        processFrame(rgbaBytes, width, height)
    }
    
    fun stopCapture() {
        if (!_uiState.value.isCapturing) return
        
        val captureTime = System.currentTimeMillis() - captureStartTime
        
        updateUiState { 
            it.copy(
                isCapturing = false,
                captureTimeMs = captureTime,
                milestoneState = MilestoneState.MILESTONE_1_GENERATING_PNG
            ) 
        }
        
        sessionId?.let { id ->
            LogEvent.logCaptureDone(
                sessionId = id,
                frameCount = frameIndex,
                dtMsCum = captureTime.toDouble()
            )
        }
        
        // Start M2→M3 processing pipeline
        if (capturedFrames.size == 81) {
            processCapturedFrames()
        } else {
            Log.e(TAG, "Expected 81 frames, got ${capturedFrames.size}")
            updateUiState { 
                it.copy(
                    milestoneState = MilestoneState.IDLE,
                    error = "Incomplete capture: ${capturedFrames.size}/81 frames"
                )
            }
        }
    }
    
    private fun processCapturedFrames() {
        viewModelScope.launch {
            try {
                // Create output directory  
                val outputDir = File(context.getExternalFilesDir(null), "captures/${sessionId}")
                outputDir.mkdirs()
                
                Log.i(TAG, "Starting M2→M3 pipeline with 81 frames...")
                
                // Initialize processors
                m2Processor = M2Processor()
                m3Processor = M3Processor()
                
                m2Processor!!.startSession()
                m3Processor!!.startSession()
                
                // M2: Process frames (729×729 → 81×81)
                val processedRgbaFrames = withContext(Dispatchers.Default) {
                    capturedFrames.map { rgba729 ->
                        m2Processor!!.downsize729To81Cpu(rgba729)
                    }
                }
                
                Log.i(TAG, "M2 processing complete: ${processedRgbaFrames.size} frames downsized to 81×81")
                
                // M3: Export GIF89a
                val m3Result = m3Processor!!.exportGif89aFromRgba(
                    rgbaFrames = processedRgbaFrames,
                    outputDir = outputDir,
                    baseName = "final"
                )
                
                Log.i(TAG, "M3 GIF89a export complete!")
                Log.i(TAG, "✅ GIF created: ${m3Result.gifFile.absolutePath}")
                Log.i(TAG, "📊 File size: ${m3Result.fileSize / 1024}KB")
                Log.i(TAG, "🎞️ Frame count: ${m3Result.frameCount}")
                Log.i(TAG, "⏱️ Processing time: ${m3Result.processingTimeMs}ms")
                
                updateUiState { 
                    it.copy(milestoneState = MilestoneState.MILESTONE_1_COMPLETE)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "M2→M3 pipeline failed", e)
                updateUiState { 
                    it.copy(
                        milestoneState = MilestoneState.IDLE,
                        error = "Processing failed: ${e.message}"
                    )
                }
            }
        }
    }
    
    fun clearError() {
        updateUiState { it.copy(error = null) }
    }
    
    private fun updateUiState(update: (CaptureUiState) -> CaptureUiState) {
        _uiState.value = update(_uiState.value)
    }
    
    fun getCurrentSession(): CaptureSession? {
        return sessionId?.let { CaptureSession(it) }
    }
    
    // Simple session data class for compatibility
    data class CaptureSession(val sessionId: String)
}