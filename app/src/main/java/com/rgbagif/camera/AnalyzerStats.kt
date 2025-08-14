package com.rgbagif.camera

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Real-time analyzer statistics for development/debugging
 * Tracks FPS, queue depth, and potential issues
 */
data class AnalyzerStats(
    val currentFps: Float = 0f,
    val queueDepth: Int = 0,
    val maxQueueDepth: Int = 2, // ImageAnalysis default
    val framesProcessed: Long = 0,
    val framesDropped: Long = 0,
    val closeCallsMissed: Long = 0,
    val avgProcessingTimeMs: Float = 0f,
    val lastFrameTimestamp: Long = 0
)

/**
 * Tracks and calculates analyzer performance metrics
 */
class AnalyzerStatsTracker {
    private val _stats = MutableStateFlow(AnalyzerStats())
    val stats: StateFlow<AnalyzerStats> = _stats.asStateFlow()
    
    // Atomic counters for thread safety
    private val framesProcessed = AtomicLong(0)
    private val framesDropped = AtomicLong(0)
    private val closeCallsMissed = AtomicLong(0)
    private val currentQueueDepth = AtomicInteger(0)
    
    // FPS calculation
    private val frameTimestamps = mutableListOf<Long>()
    private val maxTimestampHistory = 30
    
    // Processing time tracking
    private val processingTimes = mutableListOf<Long>()
    private val maxProcessingTimeHistory = 10
    
    /**
     * Called when analyzer receives a frame
     */
    fun onFrameReceived() {
        currentQueueDepth.incrementAndGet()
        updateStats()
    }
    
    /**
     * Called when frame processing starts
     */
    fun onFrameProcessingStart(): Long {
        return System.currentTimeMillis()
    }
    
    /**
     * Called when frame processing completes
     */
    fun onFrameProcessingComplete(startTime: Long, imageClosed: Boolean) {
        val processingTime = System.currentTimeMillis() - startTime
        val now = System.currentTimeMillis()
        
        // Update counters
        framesProcessed.incrementAndGet()
        currentQueueDepth.decrementAndGet()
        
        if (!imageClosed) {
            closeCallsMissed.incrementAndGet()
        }
        
        // Track processing time
        synchronized(processingTimes) {
            processingTimes.add(processingTime)
            if (processingTimes.size > maxProcessingTimeHistory) {
                processingTimes.removeAt(0)
            }
        }
        
        // Track timestamps for FPS
        synchronized(frameTimestamps) {
            frameTimestamps.add(now)
            if (frameTimestamps.size > maxTimestampHistory) {
                frameTimestamps.removeAt(0)
            }
        }
        
        updateStats()
    }
    
    /**
     * Called when a frame is dropped (not processed)
     */
    fun onFrameDropped() {
        framesDropped.incrementAndGet()
        currentQueueDepth.decrementAndGet()
        updateStats()
    }
    
    /**
     * Reset all statistics
     */
    fun reset() {
        framesProcessed.set(0)
        framesDropped.set(0)
        closeCallsMissed.set(0)
        currentQueueDepth.set(0)
        frameTimestamps.clear()
        processingTimes.clear()
        updateStats()
    }
    
    private fun updateStats() {
        val fps = calculateFps()
        val avgProcessingTime = calculateAvgProcessingTime()
        
        _stats.value = AnalyzerStats(
            currentFps = fps,
            queueDepth = currentQueueDepth.get().coerceAtLeast(0),
            maxQueueDepth = _stats.value.maxQueueDepth,
            framesProcessed = framesProcessed.get(),
            framesDropped = framesDropped.get(),
            closeCallsMissed = closeCallsMissed.get(),
            avgProcessingTimeMs = avgProcessingTime,
            lastFrameTimestamp = frameTimestamps.lastOrNull() ?: 0
        )
    }
    
    private fun calculateFps(): Float {
        synchronized(frameTimestamps) {
            if (frameTimestamps.size < 2) return 0f
            
            val duration = frameTimestamps.last() - frameTimestamps.first()
            if (duration <= 0) return 0f
            
            return (frameTimestamps.size - 1) * 1000f / duration
        }
    }
    
    private fun calculateAvgProcessingTime(): Float {
        synchronized(processingTimes) {
            if (processingTimes.isEmpty()) return 0f
            return processingTimes.average().toFloat()
        }
    }
}