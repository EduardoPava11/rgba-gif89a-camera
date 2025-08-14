package com.rgbagif.logging

import timber.log.Timber
import java.util.UUID

/**
 * Structured logging for pipeline events
 * Provides canonical single-line logs for each milestone
 */
object PipelineLogger {
    
    private var currentSessionId: String = ""
    
    fun startSession(): String {
        currentSessionId = UUID.randomUUID().toString().substring(0, 8)
        return currentSessionId
    }
    
    // ========== M1 EVENTS ==========
    
    fun logM1Start(sessionId: String = currentSessionId) {
        Timber.i("M1_START { sessionId: \"$sessionId\" }")
    }
    
    fun logM1FrameSaved(
        idx: Int,
        width: Int,
        height: Int,
        bytes: Int,
        path: String
    ) {
        Timber.i("M1_FRAME_SAVED { idx: $idx, width: $width, height: $height, bytes: $bytes, path: \"$path\" }")
    }
    
    fun logM1Done(totalFrames: Int, elapsedMs: Long) {
        Timber.i("M1_DONE { totalFrames: $totalFrames, elapsedMs: $elapsedMs }")
    }
    
    // ========== M2 EVENTS ==========
    
    fun logM2Start(sessionId: String = currentSessionId) {
        Timber.i("M2_START { sessionId: \"$sessionId\" }")
    }
    
    fun logM2FrameDone(
        idx: Int,
        inW: Int,
        inH: Int,
        outW: Int,
        outH: Int,
        elapsedMs: Long,
        path: String
    ) {
        Timber.i("M2_FRAME_DONE { idx: $idx, inW: $inW, inH: $inH, outW: $outW, outH: $outH, elapsedMs: $elapsedMs, path: \"$path\" }")
    }
    
    fun logM2MosaicDone(path: String, gridSize: String = "9x9") {
        Timber.i("M2_MOSAIC_DONE { path: \"$path\", grid: \"$gridSize\" }")
    }
    
    fun logM2Done(totalFrames: Int, elapsedMs: Long) {
        Timber.i("M2_DONE { totalFrames: $totalFrames, elapsedMs: $elapsedMs }")
    }
    
    // ========== M3 EVENTS ==========
    
    fun logM3Start(sessionId: String = currentSessionId) {
        Timber.i("M3_START { sessionId: \"$sessionId\" }")
    }
    
    fun logM3GifDone(
        frames: Int,
        fps: Int,
        sizeBytes: Long,
        loop: Boolean,
        path: String
    ) {
        Timber.i("M3_GIF_DONE { frames: $frames, fps: $fps, sizeBytes: $sizeBytes, loop: $loop, path: \"$path\" }")
    }
    
    // ========== ERROR EVENTS ==========
    
    fun logPipelineError(stage: String, message: String, throwable: Throwable? = null) {
        val stack = throwable?.stackTraceToString()?.replace("\n", " ")?.take(200) ?: ""
        Timber.e("PIPELINE_ERROR { stage: \"$stage\", message: \"$message\", stack: \"$stack\" }")
    }
    
    // ========== CAMERA EVENTS ==========
    
    fun logCameraInit(format: String, resolution: String) {
        Timber.i("CAMERA_INIT { format: \"$format\", resolution: \"$resolution\" }")
    }
    
    fun logCameraFrame(frameIndex: Int, timestampMs: Long) {
        Timber.d("CAMERA_FRAME { frameIndex: $frameIndex, timestampMs: $timestampMs }")
    }
    
    // ========== PERFORMANCE EVENTS ==========
    
    fun logMemorySnapshot(availableMb: Long, totalMb: Long, usedMb: Long) {
        Timber.d("MEMORY_SNAPSHOT { availableMb: $availableMb, totalMb: $totalMb, usedMb: $usedMb }")
    }
    
    fun logFrameDropped(reason: String) {
        Timber.w("FRAME_DROPPED { reason: \"$reason\" }")
    }
}