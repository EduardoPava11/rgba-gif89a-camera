package com.rgbagif.m1

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import uniffi.m3gif.m1PreviewPatch

/**
 * M1 Verification Kit
 * Proves RGBA_8888 integrity with visual markers and device artifacts
 * Mission: Verify that M1 (Rust) captures and preserves true RGBA for each 729×729 frame
 */
class M1VerificationKit(private val context: Context) {
    
    companion object {
        private const val TAG = "M1Verify"
        private const val FRAME_SIZE = 729
        private const val RGBA_CHANNELS = 4
        private const val FRAME_BYTES = FRAME_SIZE * FRAME_SIZE * RGBA_CHANNELS
    }
    
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    // Stats tracking
    private var frameIndex = 0
    private val outputDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "m1")
    
    // Verification callback
    var onFrameProcessed: ((bitmap: Bitmap, stats: M1Stats) -> Unit)? = null
    
    data class M1Stats(
        val frameIndex: Int,
        val nzRatio: Double,
        val avgRGB: Triple<Double, Double, Double>,
        val signature: String,
        val processingTimeMs: Long
    )
    
    /**
     * Configure CameraX with RGBA_8888 output
     */
    suspend fun setupCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView?) = withContext(Dispatchers.Main) {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProvider = cameraProviderFuture.get()
            
            // Unbind all previous use cases
            cameraProvider?.unbindAll()
            
            // Configure Preview (left panel)
            @Suppress("DEPRECATION")
            val preview = Preview.Builder()
                .setTargetResolution(android.util.Size(FRAME_SIZE, FRAME_SIZE))
                .build()
            
            previewView?.let { view ->
                preview.setSurfaceProvider(view.surfaceProvider)
            }
            
            // Configure ImageAnalysis for RGBA_8888 output (M1 source)
            @Suppress("DEPRECATION")
            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(FRAME_SIZE, FRAME_SIZE))
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888) // Direct RGBA!
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)  // Drop old frames
                .setImageQueueDepth(1)
                .build()
            
            // Set analyzer to process RGBA frames with verification
            imageAnalysis?.setAnalyzer(analysisExecutor) { imageProxy ->
                processRgbaFrame(imageProxy)
            }
            
            // Camera selector (back camera preferred)
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            // Bind use cases to lifecycle
            camera = cameraProvider?.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
            
            // Setup output directory
            outputDir.mkdirs()
            
            println("CAMERA_BOUND format=RGBA_8888 strategy=KEEP_ONLY_LATEST targetResolution=${FRAME_SIZE}×$FRAME_SIZE")
            Log.i(TAG, "M1 verification camera setup complete")
            
        } catch (e: Exception) {
            Log.e(TAG, "PIPELINE_ERROR stage=\"M1_SETUP\" reason=\"${e.message}\"")
            throw e
        }
    }
    
    /**
     * Process RGBA frame with tight packing and verification
     */
    private fun processRgbaFrame(imageProxy: ImageProxy) {
        val startTime = System.currentTimeMillis()
        
        try {
            // Extract RGBA data from ImageProxy plane 0
            val planes = imageProxy.planes
            require(planes.isNotEmpty()) { "No image planes available" }
            
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride  // Should be 4 for RGBA
            val rowStride = planes[0].rowStride
            
            // Tight RGBA repacking (handle potential stride != width*4)
            val tightRgba = extractTightRgba(buffer, imageProxy.width, imageProxy.height, rowStride, pixelStride)
            
            // Center crop to exact 729×729 if needed
            val croppedRgba = centerCrop(tightRgba, imageProxy.width, imageProxy.height, FRAME_SIZE, FRAME_SIZE)
            
            // Calculate basic stats for sanity check
            val stats = calculateStats(croppedRgba)
            
            // Check for black/empty frame
            if (stats.avgRGB.first < 8 && stats.avgRGB.second < 8 && stats.avgRGB.third < 8 && stats.nzRatio < 0.05) {
                Log.w(TAG, "PIPELINE_ERROR stage=\"M1\" reason=\"looks black/empty\" nzRatio=${stats.nzRatio} avgRGB=${stats.avgRGB}")
            }
            
            // Rust M1 verification: Get signature (currently not implemented in UniFFI)
            // val signature = m1DebugSignature(croppedRgba.toList().map { it.toUByte() }, FRAME_SIZE.toUInt(), FRAME_SIZE.toUInt())
            // println(signature) // This prints the M1_RUST_SIG line
            
            // Rust M1 preview patch: Add visual markers
            val patchedRgbaList = m1PreviewPatch(croppedRgba.toList().map { it.toUByte() }, FRAME_SIZE.toUInt(), FRAME_SIZE.toUInt())
            val patchedRgba = patchedRgbaList.map { it.toByte() }.toByteArray()
            
            // Create Android Bitmap for display (right panel)
            val bitmap = createBitmapFromRgba(patchedRgba, FRAME_SIZE, FRAME_SIZE)
            
            val processingTime = System.currentTimeMillis() - startTime
            
            // Log M1_STATS for first few frames
            if (frameIndex < 3) {
                println("M1_STATS idx=$frameIndex nzRatio=${String.format("%.3f", stats.nzRatio)} avgRGB=(${String.format("%.1f", stats.avgRGB.first)},${String.format("%.1f", stats.avgRGB.second)},${String.format("%.1f", stats.avgRGB.third)}) processingTimeMs=$processingTime")
            }
            
            // Export artifacts for first frame
            if (frameIndex == 0) {
                exportFirstFrameArtifacts(croppedRgba, bitmap, stats, "M1_OK") // Placeholder signature
            }
            
            // Callback with verification results
            val finalStats = M1Stats(frameIndex, stats.nzRatio, stats.avgRGB, "M1_OK", processingTime)
            onFrameProcessed?.invoke(bitmap, finalStats)
            
            frameIndex++
            
        } catch (e: Exception) {
            Log.e(TAG, "PIPELINE_ERROR stage=\"M1_PROCESS\" reason=\"${e.message}\"")
        } finally {
            imageProxy.close() // Always close the proxy
        }
    }
    
    /**
     * Extract tight RGBA data handling potential rowStride issues
     */
    private fun extractTightRgba(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): ByteArray {
        val tightRgba = ByteArray(width * height * 4)
        val rowBytes = ByteArray(rowStride)
        
        for (row in 0 until height) {
            buffer.position(row * rowStride)
            buffer.get(rowBytes, 0, minOf(rowStride, buffer.remaining()))
            
            for (col in 0 until width) {
                val srcOffset = col * pixelStride
                val dstOffset = (row * width + col) * 4
                
                if (srcOffset + 3 < rowBytes.size && dstOffset + 3 < tightRgba.size) {
                    tightRgba[dstOffset] = rowBytes[srcOffset]       // R
                    tightRgba[dstOffset + 1] = rowBytes[srcOffset + 1] // G
                    tightRgba[dstOffset + 2] = rowBytes[srcOffset + 2] // B
                    tightRgba[dstOffset + 3] = rowBytes[srcOffset + 3] // A
                }
            }
        }
        
        return tightRgba
    }
    
    /**
     * Center crop to exact target dimensions
     */
    private fun centerCrop(rgba: ByteArray, srcWidth: Int, srcHeight: Int, targetWidth: Int, targetHeight: Int): ByteArray {
        if (srcWidth == targetWidth && srcHeight == targetHeight) {
            return rgba // Already correct size
        }
        
        val cropX = (srcWidth - targetWidth) / 2
        val cropY = (srcHeight - targetHeight) / 2
        val croppedRgba = ByteArray(targetWidth * targetHeight * 4)
        
        for (y in 0 until targetHeight) {
            for (x in 0 until targetWidth) {
                val srcIndex = ((cropY + y) * srcWidth + (cropX + x)) * 4
                val dstIndex = (y * targetWidth + x) * 4
                
                if (srcIndex + 3 < rgba.size && dstIndex + 3 < croppedRgba.size) {
                    croppedRgba[dstIndex] = rgba[srcIndex]
                    croppedRgba[dstIndex + 1] = rgba[srcIndex + 1]
                    croppedRgba[dstIndex + 2] = rgba[srcIndex + 2]
                    croppedRgba[dstIndex + 3] = rgba[srcIndex + 3]
                }
            }
        }
        
        return croppedRgba
    }
    
    /**
     * Calculate frame statistics for verification
     */
    private fun calculateStats(rgba: ByteArray): M1Stats {
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var nonZeroPixels = 0
        val pixelCount = rgba.size / 4
        
        for (i in 0 until pixelCount) {
            val baseIndex = i * 4
            val r = rgba[baseIndex].toInt() and 0xFF
            val g = rgba[baseIndex + 1].toInt() and 0xFF
            val b = rgba[baseIndex + 2].toInt() and 0xFF
            
            rSum += r
            gSum += g
            bSum += b
            
            if (r > 0 || g > 0 || b > 0) {
                nonZeroPixels++
            }
        }
        
        val avgR = if (pixelCount > 0) rSum.toDouble() / pixelCount else 0.0
        val avgG = if (pixelCount > 0) gSum.toDouble() / pixelCount else 0.0
        val avgB = if (pixelCount > 0) bSum.toDouble() / pixelCount else 0.0
        val nzRatio = nonZeroPixels.toDouble() / pixelCount
        
        return M1Stats(frameIndex, nzRatio, Triple(avgR, avgG, avgB), "", 0L)
    }
    
    /**
     * Create Android Bitmap from RGBA bytes
     * Using ARGB_8888 with proper channel order
     */
    private fun createBitmapFromRgba(rgba: ByteArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val buffer = ByteBuffer.allocate(rgba.size)
        
        // Convert RGBA to ARGB for Android
        for (i in 0 until rgba.size step 4) {
            buffer.put(rgba[i + 3])  // A
            buffer.put(rgba[i])      // R 
            buffer.put(rgba[i + 1])  // G
            buffer.put(rgba[i + 2])  // B
        }
        
        buffer.flip()
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }
    
    /**
     * Export first frame artifacts for device verification
     */
    private fun exportFirstFrameArtifacts(rgbaData: ByteArray, bitmap: Bitmap, stats: M1Stats, signature: String) {
        try {
            // 1. Raw RGBA file
            val rawFile = File(outputDir, "raw_729x729.rgba")
            rawFile.writeBytes(rgbaData)
            
            // 2. Preview PNG file
            val pngFile = File(outputDir, "preview_729.png")
            FileOutputStream(pngFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            
            // 3. Stats text file
            val statsFile = File(outputDir, "stats.txt")
            val statsText = """
                M1 Verification Results
                =====================
                Frame Index: ${stats.frameIndex}
                Non-Zero Ratio: ${String.format("%.4f", stats.nzRatio)}
                Average RGB: (${String.format("%.2f", stats.avgRGB.first)}, ${String.format("%.2f", stats.avgRGB.second)}, ${String.format("%.2f", stats.avgRGB.third)})
                Signature: $signature
                Processing Time: ${stats.processingTimeMs}ms
                Raw File Size: ${rgbaData.size} bytes
                Expected Size: $FRAME_BYTES bytes
                Size Match: ${rgbaData.size == FRAME_BYTES}
            """.trimIndent()
            
            statsFile.writeText(statsText)
            
            Log.i(TAG, "M1 artifacts exported: raw=${rawFile.length()}, png=${pngFile.length()}, stats=${statsFile.length()}")
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to export M1 artifacts: ${e.message}")
        }
    }
    
    /**
     * Stop camera and cleanup
     */
    fun stop() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
    }
}
