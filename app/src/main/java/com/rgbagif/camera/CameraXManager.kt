package com.rgbagif.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.tracing.Trace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// M1 Configuration Constants (embedded for compilation)
object M1Config {
    const val CAPTURE_WIDTH = 729
    const val CAPTURE_HEIGHT = 729
    const val EXPORT_WIDTH = 81  
    const val EXPORT_HEIGHT = 81
    
    fun getCaptureSize() = Size(CAPTURE_WIDTH, CAPTURE_HEIGHT)
}

/**
 * CameraX manager using RGBA_8888 ImageAnalysis for perfect color and orientation.
 * This replaces Camera2 + YUV conversion with direct RGBA output from CameraX.
 */
class CameraXManager(val context: Context) {
    
    companion object {
        private const val TAG = "CameraXManager"
    }
    
    // Session tracking for logging
    private var currentSessionId: String? = null
    
    /**
     * RGB Frame Bus for publishing processed frames to UI
     * Allows multiple listeners (e.g., preview view) to receive RGB bitmaps
     */
    object RgbFrameBus {
        interface Listener { fun onRgbFrame(bmp: android.graphics.Bitmap) }
        private val listeners = java.util.concurrent.CopyOnWriteArraySet<Listener>()
        @Volatile private var bmp: android.graphics.Bitmap? = null

        fun update(bytes: ByteArray, w: Int, h: Int) {
            // reuse/allocate a mutable ARGB_8888 bitmap
            val b = bmp?.takeIf { it.width == w && it.height == h }
                ?: android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also { bmp = it }
            b.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
            for (l in listeners) l.onRgbFrame(b)
        }
        
        fun updateBitmap(bitmap: android.graphics.Bitmap) {
            // Direct bitmap update for when we already have the cropped bitmap
            bmp = bitmap
            for (l in listeners) l.onRgbFrame(bitmap)
        }
        
        fun add(l: Listener) { listeners.add(l); bmp?.let { l.onRgbFrame(it) } }
        fun remove(l: Listener) { listeners.remove(l) }
    }
    
    // Camera components
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var previewView: PreviewView? = null
    
    // Threading
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    // State
    private val _previewState = MutableStateFlow(PreviewState.IDLE)
    val previewState: StateFlow<PreviewState> = _previewState.asStateFlow()
    
    private val _latestSnapshot = MutableStateFlow<Bitmap?>(null)
    val latestSnapshot: StateFlow<Bitmap?> = _latestSnapshot.asStateFlow()
    
    // Frame callback and stats tracking for M1 hardening
    private var frameProcessor: ((Bitmap) -> Unit)? = null
    private var rgbaFrameCallback: ((ByteArray, Int, Int) -> Unit)? = null
    private var isProcessing = false
    private var frameStatsCount = 0 // Track first 3 frames for M1_STATS
    
    enum class PreviewState {
        IDLE, LIVE, FROZEN, ERROR
    }
    
    /**
     * Set callback for receiving RGBA frame data
     */
    fun setFrameCallback(callback: (ByteArray, Int, Int) -> Unit) {
        rgbaFrameCallback = callback
    }
    
    /**
     * Set the PreviewView for camera preview
     */
    fun setPreviewView(view: PreviewView) {
        previewView = view
        // If camera is already initialized, bind the preview
        preview?.setSurfaceProvider(view.surfaceProvider)
    }
    
    /**
     * Initialize CameraX with RGBA_8888 analyzer
     */
    fun setupCamera(
        lifecycleOwner: LifecycleOwner,
        onFrameAvailable: (Bitmap) -> Unit
    ) {
        frameProcessor = onFrameAvailable
        
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(lifecycleOwner)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get camera provider", e)
                _previewState.value = PreviewState.ERROR
            }
        }, ContextCompat.getMainExecutor(context))
    }
    
    /**
     * Bind CameraX use cases with RGBA analyzer
     * Both Preview and ImageAnalysis use 64-multiple resolutions for optimal thumbnails
     */
    private fun bindCameraUseCases(lifecycleOwner: LifecycleOwner) {
        val cameraProvider = cameraProvider ?: return
        
        // Target capture resolution from unified config
        val targetSize = M1Config.getCaptureSize()
        
        // Log the chosen resolution
        Log.i(TAG, "Using camera resolution: ${targetSize.width}×${targetSize.height} " +
                  "(will upscale to ${M1Config.EXPORT_WIDTH}×${M1Config.EXPORT_HEIGHT} for export)")
        
        // Configure Preview to match capture size  
        @Suppress("DEPRECATION")
        val preview = Preview.Builder()
            .setTargetResolution(targetSize)  // 729×729 for Go head
            .build()
            .also { prev ->
                // Bind to PreviewView if available
                previewView?.let { view ->
                    prev.setSurfaceProvider(view.surfaceProvider)
                }
            }
        
        // Configure ImageAnalysis for RGBA_8888 output
        @Suppress("DEPRECATION")
        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(targetSize)  // Match preview resolution
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888) // Direct RGBA output!
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // Drop old frames
            .setImageQueueDepth(1)  // Process only latest frame
            .setTargetRotation(Surface.ROTATION_0) // We'll handle rotation manually
            .build()
        
        // Set analyzer to process RGBA frames
        imageAnalysis?.setAnalyzer(analysisExecutor) { imageProxy ->
            processRgbaFrame(imageProxy)
        }
        
        // Select back camera
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        
        try {
            // Unbind all use cases before rebinding
            cameraProvider.unbindAll()
            
            // Bind use cases to lifecycle
            // Include preview if we have a PreviewView
            camera = if (previewView != null) {
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } else {
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageAnalysis
                )
            }
            
            _previewState.value = PreviewState.LIVE
            
            // Log camera start with new session - M1 HARDENING
            currentSessionId = "session_${System.currentTimeMillis()}"
            
            // CAMERA_BOUND log as required by spec
            Log.i(TAG, "CAMERA_BOUND format=RGBA_8888 strategy=KEEP_ONLY_LATEST resolution=${targetSize.width}x${targetSize.height}")
            
            frameStatsCount = 0 // Reset stats counter for new session
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind use cases", e)
            _previewState.value = PreviewState.ERROR
        }
    }
    
    /**
     * Process RGBA frame from CameraX
     * Handles: RGBA→ARGB swizzle, row stride, rotation, cropping
     */
    private fun processRgbaFrame(image: ImageProxy) {
        if (isProcessing || frameProcessor == null) {
            image.close()
            return
        }
        
        Trace.beginSection("M1_PROCESS_FRAME")
        
        try {
            val startNanos = SystemClock.elapsedRealtimeNanos()
            
            // Debug: Log the actual format we're receiving
            Log.i(TAG, "RGBA_DEBUG: format=${image.format} planes=${image.planes.size} size=${image.width}x${image.height}")
            
            // Get RGBA plane (single plane for RGBA_8888)
            val plane = image.planes[0]
            val width = image.width
            val height = image.height
            val rowStride = plane.rowStride // IMPORTANT: May be > width*4
            val pixelStride = plane.pixelStride // Should be 4 for RGBA
            
            Log.i(TAG, "RGBA_DEBUG: rowStride=$rowStride pixelStride=$pixelStride expectedStride=${width*4}")
            
            // Get rotation from CameraX
            val rotationDegrees = image.imageInfo.rotationDegrees
            
            // M1 HARDENING: Repack plane-0 using rowStride → tight w*h*4
            // Read RGBA buffer respecting stride
            val buffer = plane.buffer
            val strideRgba = ByteArray(rowStride * height)
            buffer.get(strideRgba)
            
            // Repack to tight RGBA (w*h*4) - CRITICAL for correct data flow
            val tightRgba = ByteArray(width * height * 4)
            var tightIndex = 0
            for (row in 0 until height) {
                var strideOffset = row * rowStride
                for (col in 0 until width) {
                    // Copy RGBA bytes tightly packed
                    tightRgba[tightIndex] = strideRgba[strideOffset]         // R
                    tightRgba[tightIndex + 1] = strideRgba[strideOffset + 1] // G
                    tightRgba[tightIndex + 2] = strideRgba[strideOffset + 2] // B  
                    tightRgba[tightIndex + 3] = strideRgba[strideOffset + 3] // A
                    tightIndex += 4
                    strideOffset += pixelStride
                }
            }
            
            // M1 STATS: Log stats for first 3 frames to prove non-black input
            if (frameStatsCount < 3) {
                val nonZeroBytes = tightRgba.count { it != 0.toByte() }
                val nzRatio = nonZeroBytes.toFloat() / tightRgba.size
                
                // Calculate average RGB (ignoring alpha)
                var sumR = 0L
                var sumG = 0L  
                var sumB = 0L
                var redOverflowCount = 0
                val pixelCount = width * height
                
                for (i in 0 until pixelCount) {
                    val idx = i * 4
                    val r = tightRgba[idx].toInt() and 0xFF
                    val g = tightRgba[idx + 1].toInt() and 0xFF
                    val b = tightRgba[idx + 2].toInt() and 0xFF
                    
                    sumR += r
                    sumG += g
                    sumB += b
                    
                    // Count red channel overflow
                    if (r > 200) redOverflowCount++
                }
                
                val avgR = (sumR / pixelCount).toInt()
                val avgG = (sumG / pixelCount).toInt()
                val avgB = (sumB / pixelCount).toInt()
                
                // Debug: Sample first few pixels to see actual data pattern
                val sample = tightRgba.take(16).map { (it.toInt() and 0xFF).toString() }.joinToString(",")
                Log.i(TAG, "RGBA_SAMPLE: first16bytes=[$sample]")
                
                Log.i(TAG, "M1_STATS idx=$frameStatsCount nzRatio=${"%.3f".format(nzRatio)} avgRGB=($avgR,$avgG,$avgB) redOverflow=$redOverflowCount")
                frameStatsCount++
            }
            
            // Convert tight RGBA→ARGB with proper handling
            val argb = IntArray(width * height)
            var dstIndex = 0
            for (row in 0 until height) {
                for (col in 0 until width) {
                    val srcIndex = (row * width + col) * 4
                    // Read tight RGBA bytes
                    val r = tightRgba[srcIndex].toInt() and 0xFF
                    val g = tightRgba[srcIndex + 1].toInt() and 0xFF
                    val b = tightRgba[srcIndex + 2].toInt() and 0xFF
                    val a = tightRgba[srcIndex + 3].toInt() and 0xFF
                    
                    // Pack as ARGB for Bitmap
                    argb[dstIndex++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            
            // Create ARGB_8888 bitmap
            var bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(argb, 0, width, 0, 0, width, height)
            
            // Apply rotation to get upright image
            if (rotationDegrees != 0) {
                val matrix = android.graphics.Matrix().apply {
                    postRotate(rotationDegrees.toFloat())
                }
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, false)
            }
            
            // After rotation, bitmap dimensions may have swapped
            val rotatedWidth = bitmap.width
            val rotatedHeight = bitmap.height
            
            // Center crop to target square size (729×729)
            val cropSize = 729  // Target size for 9×9 Go head
            val cropX = (rotatedWidth - cropSize) / 2
            val cropY = (rotatedHeight - cropSize) / 2
            
            val croppedBitmap = if (rotatedWidth >= cropSize && rotatedHeight >= cropSize) {
                // Direct crop if image is large enough
                Bitmap.createBitmap(bitmap, cropX.coerceAtLeast(0), cropY.coerceAtLeast(0), cropSize, cropSize)
            } else if (rotatedWidth == cropSize && rotatedHeight == cropSize) {
                // Already exact size, no crop needed
                bitmap
            } else {
                // If rotated image is smaller than target, scale up
                val scale = cropSize.toFloat() / minOf(rotatedWidth, rotatedHeight)
                val scaledBitmap = Bitmap.createScaledBitmap(
                    bitmap,
                    (rotatedWidth * scale).toInt(),
                    (rotatedHeight * scale).toInt(),
                    true
                )
                val scaledCropX = (scaledBitmap.width - cropSize) / 2
                val scaledCropY = (scaledBitmap.height - cropSize) / 2
                Bitmap.createBitmap(scaledBitmap, scaledCropX.coerceAtLeast(0), scaledCropY.coerceAtLeast(0), cropSize, cropSize)
            }
            
            // Publish to RGB bus for preview
            RgbFrameBus.updateBitmap(croppedBitmap)
            
            // Save snapshot for freeze overlay
            if (_previewState.value == PreviewState.LIVE) {
                _latestSnapshot.value = croppedBitmap
            }
            
            // Convert cropped bitmap to RGBA byte array for pipeline
            rgbaFrameCallback?.let { callback ->
                // Center crop the tight RGBA directly instead of going through bitmap
                val targetCropSize = 729
                val targetCropX = (width - targetCropSize) / 2
                val targetCropY = (height - targetCropSize) / 2
                
                val croppedRgba = ByteArray(targetCropSize * targetCropSize * 4)
                var croppedIndex = 0
                
                for (row in 0 until targetCropSize) {
                    for (col in 0 until targetCropSize) {
                        val srcRow = targetCropY + row
                        val srcCol = targetCropX + col
                        
                        if (srcRow in 0 until height && srcCol in 0 until width) {
                            val srcIndex = (srcRow * width + srcCol) * 4
                            // Copy RGBA bytes directly - preserving exact channel order
                            croppedRgba[croppedIndex] = tightRgba[srcIndex]     // R
                            croppedRgba[croppedIndex + 1] = tightRgba[srcIndex + 1] // G
                            croppedRgba[croppedIndex + 2] = tightRgba[srcIndex + 2] // B
                            croppedRgba[croppedIndex + 3] = tightRgba[srcIndex + 3] // A
                        }
                        croppedIndex += 4
                    }
                }
                
                // DEBUG: Check cropped RGBA for red channel issues
                if (frameStatsCount <= 3) {
                    var cropRedCount = 0
                    var cropRedSum = 0L
                    for (i in 0 until targetCropSize * targetCropSize) {
                        val r = croppedRgba[i * 4].toInt() and 0xFF
                        if (r > 200) cropRedCount++
                        cropRedSum += r
                    }
                    Log.i(TAG, "M1_CROPPED_DEBUG redOverflow=$cropRedCount avgRed=${cropRedSum / (targetCropSize * targetCropSize)}")
                }
                
                callback(croppedRgba, targetCropSize, targetCropSize)
            }
            
            // Forward to processor for saving
            frameProcessor?.invoke(croppedBitmap)
            
            val processingTime = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000.0
            if (processingTime > 20) {
                Log.w(TAG, "M1_FRAME_SLOW processMs=${"%.1f".format(processingTime)} size=${width}x${height}")
            }
            
            Trace.endSection()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process RGBA frame", e)
        // JsonLog.i("camera_event")
        } finally {
            image.close()
        }
    }
    
    /**
     * Freeze preview (show snapshot overlay)
     */
    fun freezePreview() {
        _previewState.value = PreviewState.FROZEN
        isProcessing = true
        Log.d(TAG, "Preview frozen with snapshot overlay")
    }
    
    /**
     * Unfreeze preview (hide snapshot overlay)
     */
    fun unfreezePreview() {
        _previewState.value = PreviewState.LIVE
        isProcessing = false
        Log.d(TAG, "Preview unfrozen, back to live")
    }
    
    /**
     * Clean up camera resources
     */
    fun release() {
        try {
            cameraProvider?.unbindAll()
            analysisExecutor.shutdown()
            _previewState.value = PreviewState.IDLE
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing camera", e)
        }
    }
    
    /**
     * Start camera with RGBA8888 capture for GIF pipeline
     * This is our main entry point for the RGBA→GIF89a app
     */
    fun startCameraRgba(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onFrameReceived: (ByteArray, Int, Int, Long) -> Unit
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                
                // Configure preview
                @Suppress("DEPRECATION")
                val preview = Preview.Builder()
                    .setTargetResolution(android.util.Size(729, 729))
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                
                // Configure RGBA8888 image analysis for GIF frames
                @Suppress("DEPRECATION")
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(729, 729))
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(analysisExecutor) { imageProxy ->
                            processRgbaForGif(imageProxy, onFrameReceived)
                        }
                    }
                
                // Select back camera
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                
                // Unbind all and bind new use cases
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                
                Log.d(TAG, "Camera started with RGBA8888 for GIF pipeline")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }
    
    /**
     * Process RGBA frame specifically for GIF pipeline
     * Extracts raw RGBA data and passes to callback
     */
    private fun processRgbaForGif(
        image: ImageProxy,
        onFrameReceived: (ByteArray, Int, Int, Long) -> Unit
    ) {
        try {
            val plane = image.planes[0]
            val width = image.width
            val height = image.height
            val rowStride = plane.rowStride
            
            // Extract RGBA data
            val buffer = plane.buffer
            val rgba = ByteArray(width * height * 4)
            
            // Copy data respecting stride
            if (rowStride == width * 4) {
                // No padding, direct copy
                buffer.get(rgba)
            } else {
                // Handle row padding
                val rowData = ByteArray(rowStride)
                for (row in 0 until height) {
                    buffer.get(rowData)
                    System.arraycopy(rowData, 0, rgba, row * width * 4, width * 4)
                }
            }
            
            // Pass to callback with timestamp
            onFrameReceived(rgba, width, height, System.currentTimeMillis())
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process RGBA frame for GIF", e)
        } finally {
            image.close()
        }
    }
    
    /**
     * Shutdown camera (alias for release)
     */
    fun shutdown() {
        release()
    }
}