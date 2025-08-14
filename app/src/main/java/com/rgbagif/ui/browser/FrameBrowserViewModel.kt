package com.rgbagif.ui.browser

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rgbagif.storage.FrameStorage
import com.rgbagif.tools.cbor.CborTools
import com.rgbagif.tools.png.PngWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Browser item representing a frame with its various representations
 */
data class BrowserItem(
    val id: String,
    val index: Int,
    val pathCbor: File,
    val pathPngRaw: File? = null,      // Legacy PNG support
    val pathPngDownsized: File? = null, // Legacy PNG support
    val pathJpegRaw: File? = null,      // New JPEG support
    val pathJpegDownsized: File? = null, // New JPEG support
    val timestampMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val deltaEMean: Float? = null,
    val deltaEMax: Float? = null,
    val paletteSize: Int? = null
)

/**
 * UI state for the Frame Browser
 */
data class BrowserUiState(
    val items: List<BrowserItem> = emptyList(),
    val currentIndex: Int = 0,
    val isLoading: Boolean = false,
    val showRaw: Boolean = true,
    val showDownsized: Boolean = false,
    val isDownsizing: Boolean = false,
    val downsizeProgress: Float = 0f,
    val error: String? = null,
    val sessionId: String? = null,
    val totalFrames: Int = 0
)

/**
 * ViewModel for Frame Browser screen
 */
class FrameBrowserViewModel : ViewModel() {
    
    companion object {
        private const val TAG = "FrameBrowserViewModel"
    }
    
    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()
    
    private var frameStorage: FrameStorage? = null
    private var currentSession: FrameStorage.CaptureSession? = null
    
    /**
     * Initialize with context and load frames
     */
    fun initialize(context: Context, sessionId: String? = null) {
        frameStorage = FrameStorage(context)
        
        viewModelScope.launch {
            loadFrames(sessionId)
        }
    }
    
    /**
     * Load frames from storage
     */
    private suspend fun loadFrames(sessionId: String? = null) = withContext(Dispatchers.IO) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        
        try {
            // Get session to load
            val session = if (sessionId != null) {
                frameStorage?.loadSession(sessionId)
            } else {
                // Use most recent session
                frameStorage?.getAllSessions()?.firstOrNull()?.also { session ->
                    frameStorage?.loadSession(session.sessionId)
                }
            }
            
            if (session == null) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "No capture session found"
                )
                return@withContext
            }
            
            currentSession = session
            
            // Load CBOR files
            val cborFiles = session.cborDir.listFiles { file ->
                file.extension == "cbor"
            }?.sortedBy { it.name } ?: emptyList()
            
            // Load PNG files (legacy)
            val pngFiles = session.pngDir.listFiles { file ->
                file.extension == "png"
            }?.associateBy { it.nameWithoutExtension } ?: emptyMap()
            
            // Load JPEG files (new)
            val jpegDir = File(session.cborDir.parentFile, "jpeg_m1")
            val jpegFiles = jpegDir.listFiles { file ->
                file.extension == "jpg" || file.extension == "jpeg"
            }?.associateBy { it.nameWithoutExtension } ?: emptyMap()
            
            // Load downsized files (PNG legacy)
            val downsizedFiles = session.downsizedDir.listFiles { file ->
                file.extension == "png"
            }?.associateBy { 
                it.nameWithoutExtension.removeSuffix("_downsized_81x81")
            } ?: emptyMap()
            
            // Load downsized JPEG files (new)
            val downsizedJpegDir = File(session.cborDir.parentFile, "M2_Session_*").listFiles()?.firstOrNull()?.let {
                File(it, "downsized")
            } ?: File(session.cborDir.parentFile, "downsized")
            
            val downsizedJpegFiles = if (downsizedJpegDir.exists()) {
                downsizedJpegDir.listFiles { file ->
                    file.extension == "jpg" || file.extension == "jpeg"
                }?.associateBy { it.nameWithoutExtension } ?: emptyMap()
            } else {
                emptyMap()
            }
            
            // Create browser items
            val items: List<BrowserItem> = cborFiles.mapIndexed { index: Int, cborFile: File ->
                val baseName = cborFile.nameWithoutExtension
                
                // Load metadata from CBOR
                val (width, height, timestampMs) = try {
                    val frame = CborTools.decodeFrame(input = cborFile)
                    Triple(frame.width, frame.height, frame.timestampMs)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read CBOR metadata: ${cborFile.name}", e)
                    Triple(0, 0, 0L)
                }
                
                BrowserItem(
                    id = baseName,
                    index = index,
                    pathCbor = cborFile,
                    pathPngRaw = pngFiles[baseName],           // Legacy PNG
                    pathPngDownsized = downsizedFiles[baseName], // Legacy PNG
                    pathJpegRaw = jpegFiles[baseName],          // New JPEG
                    pathJpegDownsized = downsizedJpegFiles[baseName], // New JPEG
                    timestampMs = timestampMs,
                    width = width,
                    height = height
                )
            }
            
            _uiState.value = _uiState.value.copy(
                items = items,
                isLoading = false,
                sessionId = session.sessionId,
                totalFrames = items.size
            )
            
            Log.d(TAG, "Loaded ${items.size} frames from session ${session.sessionId}")
            Log.d(TAG, "CBOR files: ${cborFiles.count()}")
            Log.d(TAG, "PNG files: ${pngFiles.size}")
            Log.d(TAG, "Downsized files: ${downsizedFiles.size}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load frames", e)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = e.message
            )
        }
    }
    
    /**
     * Set current page index
     */
    fun setCurrentIndex(index: Int) {
        _uiState.value = _uiState.value.copy(currentIndex = index)
    }
    
    /**
     * Toggle between RAW and Downsized views
     */
    fun toggleRawView() {
        _uiState.value = _uiState.value.copy(showRaw = !_uiState.value.showRaw)
    }
    
    fun toggleDownsizedView() {
        _uiState.value = _uiState.value.copy(showDownsized = !_uiState.value.showDownsized)
    }
    
    /**
     * Generate PNG for a specific frame if missing
     */
    fun generatePngForFrame(index: Int) {
        val item = _uiState.value.items.getOrNull(index) ?: return
        
        // Check if JPEG already exists
        if (item.pathJpegRaw != null && item.pathJpegRaw.exists()) {
            return // Already exists
        }
        
        // Check if PNG already exists (legacy)
        if (item.pathPngRaw != null && item.pathPngRaw.exists()) {
            return // Already exists
        }
        
        viewModelScope.launch {
            generateJpeg(item)
        }
    }
    
    /**
     * Generate JPEG from CBOR
     */
    private suspend fun generateJpeg(item: BrowserItem) = withContext(Dispatchers.IO) {
        try {
            val jpegDir = File(item.pathCbor.parentFile.parentFile, "jpeg_m1")
            jpegDir.mkdirs()
            val jpegFile = File(jpegDir, item.pathCbor.nameWithoutExtension + ".jpg")
            
            CborTools.decodeToJpeg(item.pathCbor, jpegFile, 95)
            
            // Update UI state with new JPEG path
            val updatedItems = _uiState.value.items.map { 
                if (it.id == item.id) {
                    it.copy(pathJpegRaw = jpegFile)
                } else it
            }
            _uiState.value = _uiState.value.copy(items = updatedItems)
            
            Log.d(TAG, "Generated JPEG for frame ${item.index}: ${jpegFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate JPEG for frame ${item.index}", e)
        }
    }
    
    /**
     * Generate PNG from CBOR (legacy)
     */
    private suspend fun generatePng(item: BrowserItem) = withContext(Dispatchers.IO) {
        try {
            val session = currentSession ?: return@withContext
            val pngFile = File(session.pngDir, item.pathCbor.nameWithoutExtension + ".png")
            
            if (CborTools.decodeToPng(item.pathCbor, pngFile)) {
                // Update item with new PNG path
                val updatedItems = _uiState.value.items.map { 
                    if (it.id == item.id) {
                        it.copy(pathPngRaw = pngFile)
                    } else {
                        it
                    }
                }
                
                _uiState.value = _uiState.value.copy(items = updatedItems)
                Log.d(TAG, "Generated PNG for frame ${item.index}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate PNG for frame ${item.index}", e)
        }
    }
    
    /**
     * Generate all missing PNGs
     */
    fun generateAllPngs() {
        viewModelScope.launch {
            val session = currentSession ?: return@launch
            val items = _uiState.value.items
            
            withContext(Dispatchers.IO) {
                // Generate JPEGs instead of PNGs
                val jpegDir = File(session.cborDir.parentFile, "jpeg_m1")
                jpegDir.mkdirs()
                
                val jpegFiles = CborTools.decodeAllToJpeg(
                    cborDir = session.cborDir,
                    jpegDir = jpegDir,
                    force = false,
                    quality = 95
                )
                
                // Reload frames to update UI
                loadFrames(session.sessionId)
            }
        }
    }
    
    /**
     * Downsize all frames using Go 9×9 neural network
     */
    fun downsizeAllFrames() {
        if (_uiState.value.isDownsizing) return
        
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isDownsizing = true,
                downsizeProgress = 0f,
                error = null
            )
            
            try {
                val session = currentSession ?: throw IllegalStateException("No session loaded")
                val items = _uiState.value.items
                
                withContext(Dispatchers.IO) {
                    // TODO: Call Rust UniFFI batch downsizer
                    // For now, use placeholder implementation
                    downsizeWithPlaceholder(session, items)
                }
                
                // Reload frames to show downsized versions
                loadFrames(session.sessionId)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to downsize frames", e)
                _uiState.value = _uiState.value.copy(
                    error = "Downsize failed: ${e.message}"
                )
            } finally {
                _uiState.value = _uiState.value.copy(
                    isDownsizing = false,
                    downsizeProgress = 0f
                )
            }
        }
    }
    
    /**
     * Placeholder downsize implementation
     */
    private suspend fun downsizeWithPlaceholder(
        session: FrameStorage.CaptureSession,
        items: List<BrowserItem>
    ) = withContext(Dispatchers.IO) {
        val totalItems = items.size
        
        items.forEachIndexed { index, item ->
            try {
                // Load CBOR frame
                val frame = CborTools.decodeFrame(item.pathCbor)
                
                // Simple bilinear downscaling to 81×81
                val downsizedRgba = downsizeRgba(
                    frame.rgba,
                    frame.width,
                    frame.height,
                    81,
                    81
                )
                
                // Save downsized PNG
                val downsizedFile = File(
                    session.downsizedDir,
                    "${item.pathCbor.nameWithoutExtension}_downsized_81x81.png"
                )
                
                PngWriter.rgbaToPng(downsizedRgba, 81, 81, downsizedFile)
                
                // Update progress
                val progress = (index + 1).toFloat() / totalItems
                _uiState.value = _uiState.value.copy(downsizeProgress = progress)
                
                Log.d(TAG, "Downsized frame ${index + 1}/$totalItems")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to downsize frame ${item.index}", e)
            }
        }
    }
    
    /**
     * Simple bilinear downscaling
     */
    private fun downsizeRgba(
        src: ByteArray,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int
    ): ByteArray {
        val dst = ByteArray(dstWidth * dstHeight * 4)
        val xRatio = srcWidth.toFloat() / dstWidth
        val yRatio = srcHeight.toFloat() / dstHeight
        
        for (y in 0 until dstHeight) {
            for (x in 0 until dstWidth) {
                val srcX = (x * xRatio).toInt().coerceAtMost(srcWidth - 1)
                val srcY = (y * yRatio).toInt().coerceAtMost(srcHeight - 1)
                
                val srcIndex = (srcY * srcWidth + srcX) * 4
                val dstIndex = (y * dstWidth + x) * 4
                
                // Copy RGBA values
                System.arraycopy(src, srcIndex, dst, dstIndex, 4)
            }
        }
        
        return dst
    }
    
    /**
     * Load bitmap for display
     */
    suspend fun loadBitmap(item: BrowserItem, preferDownsized: Boolean): Bitmap? = 
        withContext(Dispatchers.IO) {
            try {
                val file = when {
                    // Prefer JPEG over PNG
                    preferDownsized && item.pathJpegDownsized?.exists() == true -> {
                        item.pathJpegDownsized
                    }
                    preferDownsized && item.pathPngDownsized?.exists() == true -> {
                        item.pathPngDownsized
                    }
                    item.pathJpegRaw?.exists() == true -> {
                        item.pathJpegRaw
                    }
                    item.pathPngRaw?.exists() == true -> {
                        item.pathPngRaw
                    }
                    else -> {
                        // Decode from CBOR on demand
                        val frame = CborTools.decodeFrame(item.pathCbor)
                        return@withContext createBitmapFromRgba(
                            frame.rgba,
                            frame.width,
                            frame.height
                        )
                    }
                }
                
                // Load PNG file as bitmap
                android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load bitmap for frame ${item.index}", e)
                null
            }
        }
    
    /**
     * Create bitmap from RGBA data
     */
    private fun createBitmapFromRgba(rgba: ByteArray, width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)
        var srcIndex = 0
        
        for (i in pixels.indices) {
            val r = rgba[srcIndex++].toInt() and 0xFF
            val g = rgba[srcIndex++].toInt() and 0xFF
            val b = rgba[srcIndex++].toInt() and 0xFF
            val a = rgba[srcIndex++].toInt() and 0xFF
            
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
    
    /**
     * Clear error state
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}