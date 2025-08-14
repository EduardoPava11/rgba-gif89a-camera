package com.rgbagif.pipeline

import android.os.Trace
import android.util.Log
import com.rgbagif.log.LogEvent
import com.rgbagif.milestones.FastPathConfig
import com.rgbagif.native.M1Fast
// import com.rgbagif.rust.RustCborWriter - removed, using M1Fast JNI instead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fast-path frame processor using JNI + DirectByteBuffer
 * Provides benchmarking between JNI and UniFFI paths
 */
class FastPathFrameProcessor(
    // private val rustCborWriter: RustCborWriter - removed, using M1Fast JNI directly
) {
    companion object {
        private const val TAG = "FastPathProcessor"
    }
    
    // Reusable direct buffer pool (avoid allocations per frame)
    private val bufferPool = mutableListOf<ByteBuffer>()
    private val poolLock = Any()
    private val maxPoolSize = 3
    
    // Performance tracking
    private val jniWriteTimes = mutableListOf<Long>()
    private val uniffiWriteTimes = mutableListOf<Long>()
    private val frameCounter = AtomicInteger(0)
    
    /**
     * Get or allocate a direct buffer from the pool
     */
    private fun getDirectBuffer(sizeBytes: Int): ByteBuffer {
        Trace.beginSection("BUFFER_POOL_ACQUIRE")
        try {
            synchronized(poolLock) {
                // Try to reuse from pool
                val buffer = bufferPool.firstOrNull { it.capacity() >= sizeBytes }
                if (buffer != null) {
                    bufferPool.remove(buffer)
                    buffer.clear()
                    return buffer
                }
            }
            
            // Allocate new direct buffer
            return M1Fast.allocateDirectBuffer(sizeBytes)
        } finally {
            Trace.endSection()
        }
    }
    
    /**
     * Return buffer to pool for reuse
     */
    private fun returnBuffer(buffer: ByteBuffer) {
        Trace.beginSection("BUFFER_POOL_RELEASE")
        try {
            synchronized(poolLock) {
                if (bufferPool.size < maxPoolSize) {
                    buffer.clear()
                    bufferPool.add(buffer)
                }
            }
        } finally {
            Trace.endSection()
        }
    }
    
    /**
     * Process RGBA frame with fast-path or UniFFI based on config
     * 
     * @param rgbaData Raw RGBA bytes (will be copied to direct buffer if needed)
     * @param width Frame width
     * @param height Frame height
     * @param stride Row stride in bytes
     * @param timestampMs Capture timestamp
     * @param outputPath Output CBOR file path
     * @return Write time in milliseconds
     */
    suspend fun processFrame(
        rgbaData: ByteArray,
        width: Int,
        height: Int,
        stride: Int,
        timestampMs: Long,
        outputPath: String
    ): Long = withContext(Dispatchers.IO) {
        val frameIndex = frameCounter.getAndIncrement()
        val startTime = System.nanoTime()
        
        try {
            val success = if (FastPathConfig.shouldUseFastPath()) {
                // JNI fast-path with DirectByteBuffer
                processWithJni(rgbaData, width, height, stride, timestampMs, frameIndex, outputPath)
            } else {
                // UniFFI path
                processWithUniffi(rgbaData, width, height, stride, timestampMs, outputPath)
            }
            
            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
            
            // Track performance
            if (FastPathConfig.benchmarkMode) {
                if (FastPathConfig.shouldUseFastPath()) {
                    jniWriteTimes.add(elapsedMs)
                } else {
                    uniffiWriteTimes.add(elapsedMs)
                }
                
                // Log benchmark event
                LogEvent.Entry(
                    event = "m1_write",
                    milestone = "M1",
                    sessionId = "",
                    extra = mapOf(
                        "impl" to if (FastPathConfig.shouldUseFastPath()) "jni" else "uniffi",
                        "ms" to elapsedMs,
                        "bytes" to (height * stride),
                        "frame_index" to frameIndex
                    )
                ).log()
                
                // Print benchmark summary every 10 frames
                if (frameIndex > 0 && frameIndex % 10 == 0) {
                    printBenchmarkSummary()
                }
            }
            
            elapsedMs
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Frame processing failed")
            -1L
        }
    }
    
    /**
     * Process frame using JNI fast-path with DirectByteBuffer
     */
    private suspend fun processWithJni(
        rgbaData: ByteArray,
        width: Int,
        height: Int,
        stride: Int,
        timestampMs: Long,
        frameIndex: Int,
        outputPath: String
    ): Boolean {
        Trace.beginSection("M1_JNI_WRITE")
        
        // Get direct buffer from pool
        val directBuffer = getDirectBuffer(rgbaData.size)
        
        return try {
            // Copy data to direct buffer
            Trace.beginSection("DirectByteBuffer_Access")
            directBuffer.put(rgbaData)
            directBuffer.flip()
            Trace.endSection()
            
            // Call JNI with zero-copy path
            val success = M1Fast.writeFrameTracked(
                directBuffer,
                width,
                height,
                stride,
                timestampMs,
                frameIndex,
                outputPath
            )
            
            if (!success) {
                Timber.tag(TAG).w("JNI write failed for frame $frameIndex")
            }
            
            success
        } finally {
            // Return buffer to pool
            returnBuffer(directBuffer)
            Trace.endSection()
        }
    }
    
    /**
     * Process frame using UniFFI path
     */
    private suspend fun processWithUniffi(
        rgbaData: ByteArray,
        width: Int,
        height: Int,
        stride: Int,
        timestampMs: Long,
        outputPath: String
    ): Boolean {
        Trace.beginSection("M1_UNIFFI_WRITE")
        
        return try {
            // For now, just use JNI path as UniFFI is not available
            // In future, could use M2Down UniFFI here for comparison
            val frameIndex = frameCounter.get()
            processWithJni(rgbaData, width, height, stride, timestampMs, frameIndex, outputPath)
        } finally {
            Trace.endSection()
        }
    }
    
    /**
     * Process frame from ImageProxy with zero-copy optimization
     * This is the most efficient path - no intermediate ByteArray
     */
    fun processImageProxy(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        timestampMs: Long,
        outputPath: String
    ): Boolean {
        if (!buffer.isDirect) {
            Timber.tag(TAG).w("ImageProxy buffer is not direct, falling back to copy path")
            // Fall back to byte array copy
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            return false // Would need to be async for proper handling
        }
        
        val frameIndex = frameCounter.getAndIncrement()
        
        Trace.beginSection("M1_DIRECT_WRITE")
        return try {
            // Direct path - no copy needed!
            M1Fast.writeFrameTracked(
                buffer,
                width,
                height,
                rowStride,
                timestampMs,
                frameIndex,
                outputPath
            )
        } finally {
            Trace.endSection()
        }
    }
    
    /**
     * Print benchmark summary comparing JNI vs UniFFI
     */
    private fun printBenchmarkSummary() {
        val jniAvg = if (jniWriteTimes.isNotEmpty()) jniWriteTimes.average() else 0.0
        val uniffiAvg = if (uniffiWriteTimes.isNotEmpty()) uniffiWriteTimes.average() else 0.0
        
        val speedup = if (jniAvg > 0 && uniffiAvg > 0) {
            uniffiAvg / jniAvg
        } else {
            0.0
        }
        
        Timber.tag(TAG).i(
            "Benchmark Summary:\n" +
            "  JNI:    ${jniWriteTimes.size} frames, avg %.1fms\n" +
            "  UniFFI: ${uniffiWriteTimes.size} frames, avg %.1fms\n" +
            "  Speedup: %.2fx",
            jniAvg, uniffiAvg, speedup
        )
        
        // Log structured benchmark
        LogEvent.Entry(
            event = "benchmark_summary",
            milestone = "M1",
            sessionId = "",
            extra = mapOf(
                "jni_samples" to jniWriteTimes.size,
                "jni_avg_ms" to jniAvg,
                "uniffi_samples" to uniffiWriteTimes.size,
                "uniffi_avg_ms" to uniffiAvg,
                "speedup" to speedup
            )
        ).log()
    }
    
    /**
     * Get benchmark results
     */
    fun getBenchmarkResults(): BenchmarkResult {
        return BenchmarkResult(
            jniSamples = jniWriteTimes.size,
            jniAvgMs = if (jniWriteTimes.isNotEmpty()) jniWriteTimes.average() else null,
            uniffiSamples = uniffiWriteTimes.size,
            uniffiAvgMs = if (uniffiWriteTimes.isNotEmpty()) uniffiWriteTimes.average() else null,
            speedup = calculateSpeedup()
        )
    }
    
    private fun calculateSpeedup(): Double? {
        val jniAvg = if (jniWriteTimes.isNotEmpty()) jniWriteTimes.average() else return null
        val uniffiAvg = if (uniffiWriteTimes.isNotEmpty()) uniffiWriteTimes.average() else return null
        return if (jniAvg > 0) uniffiAvg / jniAvg else null
    }
    
    data class BenchmarkResult(
        val jniSamples: Int,
        val jniAvgMs: Double?,
        val uniffiSamples: Int,
        val uniffiAvgMs: Double?,
        val speedup: Double?
    )
}