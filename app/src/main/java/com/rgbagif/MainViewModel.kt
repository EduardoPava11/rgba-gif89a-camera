package com.rgbagif

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rgbagif.config.AppConfig
import com.rgbagif.pipeline.ExportPipeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class CaptureState(
    val isCapturing: Boolean = false,
    val framesCaptured: Int = 0,
    val targetFrames: Int = AppConfig.CAPTURE_FRAME_COUNT,  // 32 frames
    val currentGifPath: String? = null,
    val error: String? = null
)

class MainViewModel : ViewModel() {
    private val _captureState = MutableStateFlow(CaptureState())
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()
    
    private val exportPipeline = ExportPipeline()
    private val TAG = "MainViewModel"
    
    fun startCapture(outputDir: File) {
        if (_captureState.value.isCapturing) return
        
        viewModelScope.launch {
            try {
                Log.d(TAG, "Starting capture to ${outputDir.absolutePath}")
                
                // Pipeline is already initialized
                
                _captureState.value = CaptureState(
                    isCapturing = true,
                    framesCaptured = 0,
                    targetFrames = AppConfig.CAPTURE_FRAME_COUNT
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start capture", e)
                _captureState.value = _captureState.value.copy(
                    error = e.message
                )
            }
        }
    }
    
    fun processFrame(rgbaData: ByteArray, width: Int, height: Int, timestampMs: Long) {
        if (!_captureState.value.isCapturing) return
        
        viewModelScope.launch {
            try {
                // For now, just count frames - actual processing will be done later
                val newFrameCount = _captureState.value.framesCaptured + 1
                _captureState.value = _captureState.value.copy(
                    framesCaptured = newFrameCount
                )
                
                Log.d(TAG, "Captured frame $newFrameCount/${_captureState.value.targetFrames}")
                
                // Auto-stop after target frames
                if (newFrameCount >= _captureState.value.targetFrames) {
                    stopCapture()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process frame", e)
                _captureState.value = _captureState.value.copy(
                    error = e.message
                )
            }
        }
    }
    
    fun stopCapture() {
        if (!_captureState.value.isCapturing) return
        
        viewModelScope.launch {
            try {
                val outputPath = "${System.currentTimeMillis()}.gif"
                
                _captureState.value = _captureState.value.copy(
                    isCapturing = false,
                    currentGifPath = outputPath
                )
                
                Log.d(TAG, "Capture stopped, ready for export to $outputPath")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to finalize GIF", e)
                _captureState.value = _captureState.value.copy(
                    isCapturing = false,
                    error = e.message
                )
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        // Clean up if needed
    }
}