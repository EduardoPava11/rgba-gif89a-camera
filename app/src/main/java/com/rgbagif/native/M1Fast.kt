package com.rgbagif.native

import android.os.Trace
import timber.log.Timber
import java.nio.ByteBuffer

/**
 * JNI fast-path for M1 CBOR writes
 * Uses DirectByteBuffer to avoid memory copies
 * 
 * This provides ~2-3x speedup over UniFFI for per-frame writes
 * by using zero-copy DirectByteBuffer access
 */
object M1Fast {
    private const val TAG = "M1Fast"
    private var isLoaded = false
    
    init {
        try {
            System.loadLibrary("m1fast")
            isLoaded = true
            Timber.tag(TAG).i("M1Fast JNI library loaded, version: ${getVersion()}")
        } catch (e: UnsatisfiedLinkError) {
            Timber.tag(TAG).e(e, "Failed to load m1fast library")
            isLoaded = false
        }
    }
    
    /**
     * Write RGBA frame to CBOR using JNI fast-path
     * 
     * @param rgba DirectByteBuffer containing RGBA data (must be direct!)
     * @param width Frame width in pixels
     * @param height Frame height in pixels
     * @param stride Row stride in bytes (may be > width*4)
     * @param tsMs Timestamp in milliseconds
     * @param frameIndex Frame index in sequence
     * @param outPath Full path to output CBOR file
     * @return true if write succeeded, false otherwise
     */
    external fun writeFrame(
        rgba: ByteBuffer,
        width: Int,
        height: Int,
        stride: Int,
        tsMs: Long,
        frameIndex: Int,
        outPath: String
    ): Boolean
    
    /**
     * Get JNI library version for debugging
     */
    external fun getVersion(): String
    
    /**
     * Check if JNI fast-path is available
     */
    fun isAvailable(): Boolean = isLoaded
    
    /**
     * Write frame with performance tracking
     * Adds Perfetto tracing and logging
     */
    fun writeFrameTracked(
        rgba: ByteBuffer,
        width: Int,
        height: Int,
        stride: Int,
        tsMs: Long,
        frameIndex: Int,
        outPath: String
    ): Boolean {
        if (!isAvailable()) {
            Timber.tag(TAG).w("JNI fast-path not available")
            return false
        }
        
        // Validate buffer is direct
        if (!rgba.isDirect) {
            Timber.tag(TAG).e("Buffer must be direct for JNI fast-path")
            return false
        }
        
        Trace.beginSection("M1Fast.writeFrame")
        val startTime = System.nanoTime()
        
        val success = try {
            writeFrame(rgba, width, height, stride, tsMs, frameIndex, outPath)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "JNI write failed")
            false
        } finally {
            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
            Trace.endSection()
            
            // Log performance for first few frames
            if (frameIndex < 10) {
                Timber.tag(TAG).d(
                    "Frame $frameIndex written in ${elapsedMs}ms (${width}x${height}, JNI fast-path)"
                )
            }
        }
        
        return success
    }
    
    /**
     * Allocate a direct ByteBuffer suitable for JNI
     * This buffer can be reused across frames
     */
    fun allocateDirectBuffer(sizeBytes: Int): ByteBuffer {
        return ByteBuffer.allocateDirect(sizeBytes).apply {
            // Ensure native byte order for best performance
            order(java.nio.ByteOrder.nativeOrder())
        }
    }
}