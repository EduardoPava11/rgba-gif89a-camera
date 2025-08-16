package com.rgbagif.processing

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * M3 Processor Stub - Minimal implementation for compilation
 * Real M3 functionality is now in UnifiedPipeline
 */
class M3Processor {
    
    data class M3Result(
        val gifFile: File,
        val fileSize: Long,
        val colorCount: Int,
        val frameCount: Int,
        val processingTimeMs: Long,
        val stats: GifStats? = null
    )
    
    data class GifStats(
        val quantizationMs: Long,
        val encodingMs: Long,
        val compressionRatio: Double,
        val uniqueColors: Int,
        val averageFrameSize: Int
    )
    
    fun startSession(): String = "stub_session"
    
    suspend fun exportGif89aFromRgba(
        rgbaFrames: List<ByteArray>,
        outputDir: File,
        baseName: String = "final"
    ): M3Result = withContext(Dispatchers.IO) {
        // Use UnifiedPipeline's M3 functionality via UniFFI
        val outputFile = File(outputDir, "$baseName.gif")
        
        // Call the Rust m3SaveGifToFile function
        val gifStats = uniffi.m3gif.`m3SaveGifToFile`(
            framesRgba = rgbaFrames.map { frame -> frame.map { it.toUByte() } },
            width = 81u,
            height = 81u,
            delayCs = 4u,  // 40ms per frame = 25fps
            outputPath = outputFile.absolutePath
        )
        
        M3Result(
            gifFile = outputFile,
            fileSize = outputFile.length(),
            colorCount = 256,
            frameCount = rgbaFrames.size,
            processingTimeMs = 0L,
            stats = GifStats(
                quantizationMs = 0L,
                encodingMs = 0L,
                compressionRatio = 1.0,
                uniqueColors = 256,
                averageFrameSize = (outputFile.length() / rgbaFrames.size).toInt()
            )
        )
    }
    
    suspend fun exportGif(
        frames: List<Bitmap>,
        outputFile: File,
        delayMs: Int = 40,
        loop: Boolean = true
    ): M3Result = withContext(Dispatchers.IO) {
        // Stub implementation
        M3Result(
            gifFile = outputFile,
            fileSize = 0L,
            colorCount = 256,
            frameCount = frames.size,
            processingTimeMs = 0L,
            stats = null
        )
    }
    
    fun generateDiagnosticReport(result: M3Result, outputDir: File) {
        // Stub implementation - does nothing
    }
}