package com.rgbagif.storage

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.tracing.Trace
import com.rgbagif.log.LogEvent
import com.rgbagif.utils.CborFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Handles CBOR frame storage and retrieval for debugging/visualization
 */
class FrameStorage(private val context: Context) {
    
    companion object {
        private const val TAG = "FrameStorage"
        private const val FRAMES_DIR = "captured_frames"
        private const val CBOR_DIR = "cbor"
        private const val PNG_DIR = "png"
        private const val DOWNSIZED_DIR = "downsized"
        private val DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
    
    private val baseDir: File = File(context.getExternalFilesDir(null), FRAMES_DIR)
    
    /**
     * Current capture session info
     */
    data class CaptureSession(
        val sessionId: String,
        val timestamp: String,
        val cborDir: File,
        val pngDir: File,
        val downsizedDir: File,
        val startTime: Long = System.currentTimeMillis()
    )
    
    private var currentSession: CaptureSession? = null
    
    /**
     * Start a new capture session
     */
    fun startCaptureSession(sessionId: String = LogEvent.generateSessionId()): CaptureSession {
        val timestamp = DATE_FORMAT.format(Date())
        
        val sessionDir = File(baseDir, sessionId)
        val cborDir = File(sessionDir, CBOR_DIR)
        val pngDir = File(sessionDir, PNG_DIR)
        val downsizedDir = File(sessionDir, DOWNSIZED_DIR)
        
        // Create directories
        cborDir.mkdirs()
        pngDir.mkdirs()
        downsizedDir.mkdirs()
        
        val session = CaptureSession(
            sessionId = sessionId,
            timestamp = timestamp,
            cborDir = cborDir,
            pngDir = pngDir,
            downsizedDir = downsizedDir
        )
        
        currentSession = session
        
        // Log session start
        LogEvent.logCaptureStart(sessionId)
        
        Log.d(TAG, "Started capture session: $sessionId")
        Log.d(TAG, "CBOR dir: ${cborDir.absolutePath}")
        Log.d(TAG, "PNG dir: ${pngDir.absolutePath}")
        Log.d(TAG, "Downsized dir: ${downsizedDir.absolutePath}")
        
        return session
    }
    
    /**
     * Save RGBA frame data as CBOR
     */
    suspend fun saveFrameAsCbor(
        frameIndex: Int,
        rgbaData: ByteArray,
        width: Int,
        height: Int,
        timestampMs: Long,
        rowStride: Int = width * 4
    ): File? = withContext(Dispatchers.IO) {
        val session = currentSession ?: return@withContext null
        
        Trace.beginSection("M1_CBOR_WRITE")
        val startNanos = SystemClock.elapsedRealtimeNanos()
        
        try {
            // Create CBOR frame
            val cborFrame = CborFrame.fromCameraImage(
                rgbaBuffer = rgbaData,
                width = width,
                height = height,
                rowStride = rowStride,
                timestampMs = timestampMs
            )
            
            // Generate filename
            val filename = String.format("frame_%04d.cbor", frameIndex)
            val cborFile = File(session.cborDir, filename)
            
                // Save CBOR data
                val cborBytes = CborFrame.toCbor(cborFrame)
                cborFile.writeBytes(cborBytes)
                
                val dtMs = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000.0
                
                // Log frame saved
                LogEvent.logFrameSaved(
                    sessionId = session.sessionId,
                    frameIndex = frameIndex,
                    bytesOut = cborBytes.size.toLong(),
                    dtMs = dtMs
                )
                
                Log.d(TAG, "Saved CBOR frame $frameIndex: ${cborFile.name} (${cborBytes.size} bytes)")
                
                cborFile
            } catch (e: Exception) {
                val dtMs = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000.0
                
                LogEvent.Entry(
                    event = LogEvent.EVENT_ERROR,
                    milestone = "M1",
                    sessionId = session.sessionId,
                    tag = LogEvent.TAG_ERROR,
                    level = Log.ERROR,
                    frameIndex = frameIndex,
                    dtMs = dtMs,
                    ok = false,
                    errCode = LogEvent.E_CBOR_WRITE,
                    errMsg = e.message
                ).log()
                
            Log.e(TAG, "Failed to save CBOR frame $frameIndex", e)
            null
        } finally {
            Trace.endSection()
        }
    }
    
    /**
     * Get all CBOR files from current session
     */
    fun getCurrentSessionCborFiles(): List<File> {
        val session = currentSession ?: return emptyList()
        return session.cborDir.listFiles { file ->
            file.extension == "cbor"
        }?.sortedBy { it.name } ?: emptyList()
    }
    
    /**
     * Get all PNG files from current session
     */
    fun getCurrentSessionPngFiles(): List<File> {
        val session = currentSession ?: return emptyList()
        return session.pngDir.listFiles { file ->
            file.extension == "png"
        }?.sortedBy { it.name } ?: emptyList()
    }
    
    /**
     * Get all downsized PNG files from current session
     */
    fun getCurrentSessionDownsizedFiles(): List<File> {
        val session = currentSession ?: return emptyList()
        return session.downsizedDir.listFiles { file ->
            file.extension == "png" && file.name.contains("downsized")
        }?.sortedBy { it.name } ?: emptyList()
    }
    
    /**
     * Convert CBOR frame to PNG for viewing
     */
    suspend fun convertCborToPng(cborFile: File): File? = withContext(Dispatchers.IO) {
        val session = currentSession ?: return@withContext null
        
        try {
            // Load CBOR frame
            val cborBytes = cborFile.readBytes()
            val frame = CborFrame.fromCbor(cborBytes)
            
            // Generate PNG filename
            val pngFilename = cborFile.nameWithoutExtension + ".png"
            val pngFile = File(session.pngDir, pngFilename)
            
            // Export to PNG
            if (frame.exportToPng(pngFile)) {
                Log.d(TAG, "Converted CBOR to PNG: ${pngFile.name}")
                pngFile
            } else {
                Log.e(TAG, "Failed to export PNG: ${pngFile.name}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert CBOR to PNG: ${cborFile.name}", e)
            null
        }
    }
    
    /**
     * Convert all CBOR files in current session to PNG
     */
    suspend fun convertAllCborToPng(): List<File> = withContext(Dispatchers.IO) {
        val cborFiles = getCurrentSessionCborFiles()
        val pngFiles = mutableListOf<File>()
        
        for (cborFile in cborFiles) {
            convertCborToPng(cborFile)?.let { pngFiles.add(it) }
        }
        
        Log.d(TAG, "Converted ${pngFiles.size}/${cborFiles.size} CBOR files to PNG")
        pngFiles
    }
    
    /**
     * Downsize frame using Go neural engine (stub for now)
     */
    suspend fun downsizeFrame(cborFile: File): File? = withContext(Dispatchers.IO) {
        val session = currentSession ?: return@withContext null
        
        try {
            // Load CBOR frame
            val cborBytes = cborFile.readBytes()
            val frame = CborFrame.fromCbor(cborBytes)
            
            // TODO: Integrate with actual Go neural engine
            // For now, create a placeholder downsized image using simple scaling
            val downsizedFrame = createPlaceholderDownsizedFrame(frame)
            
            // Generate downsized PNG filename
            val downsizedFilename = cborFile.nameWithoutExtension + "_downsized_81x81.png"
            val downsizedFile = File(session.downsizedDir, downsizedFilename)
            
            // Export to PNG
            if (downsizedFrame.exportToPng(downsizedFile)) {
                Log.d(TAG, "Downsized frame: ${downsizedFile.name}")
                downsizedFile
            } else {
                Log.e(TAG, "Failed to export downsized PNG: ${downsizedFile.name}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to downsize frame: ${cborFile.name}", e)
            null
        }
    }
    
    /**
     * Downsize all frames in current session
     */
    suspend fun downsizeAllFrames(): List<File> = withContext(Dispatchers.IO) {
        val cborFiles = getCurrentSessionCborFiles()
        val downsizedFiles = mutableListOf<File>()
        
        for (cborFile in cborFiles) {
            downsizeFrame(cborFile)?.let { downsizedFiles.add(it) }
        }
        
        Log.d(TAG, "Downsized ${downsizedFiles.size}/${cborFiles.size} frames")
        downsizedFiles
    }
    
    /**
     * Get all available capture sessions
     */
    fun getAllSessions(): List<CaptureSession> {
        if (!baseDir.exists()) return emptyList()
        
        return baseDir.listFiles { file ->
            file.isDirectory && file.name.startsWith("session_")
        }?.mapNotNull { sessionDir ->
            try {
                val sessionId = sessionDir.name
                val timestamp = sessionId.removePrefix("session_")
                
                CaptureSession(
                    sessionId = sessionId,
                    timestamp = timestamp,
                    cborDir = File(sessionDir, CBOR_DIR),
                    pngDir = File(sessionDir, PNG_DIR),
                    downsizedDir = File(sessionDir, DOWNSIZED_DIR),
                    startTime = sessionDir.lastModified()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse session directory: ${sessionDir.name}", e)
                null
            }
        }?.sortedByDescending { it.startTime } ?: emptyList()
    }
    
    /**
     * Load a specific session
     */
    fun loadSession(sessionId: String): CaptureSession? {
        return getAllSessions().find { it.sessionId == sessionId }
    }
    
    /**
     * End current capture session
     */
    fun endCaptureSession() {
        currentSession?.let { session ->
            Log.d(TAG, "Ended capture session: ${session.sessionId}")
            Log.d(TAG, "CBOR files: ${getCurrentSessionCborFiles().size}")
            Log.d(TAG, "PNG files: ${getCurrentSessionPngFiles().size}")
            Log.d(TAG, "Downsized files: ${getCurrentSessionDownsizedFiles().size}")
        }
        currentSession = null
    }
    
    // Placeholder implementation for neural network downsizing
    private fun createPlaceholderDownsizedFrame(originalFrame: CborFrame): CborFrame {
        // Simple bilinear downscaling from 729x729 to 81x81 as placeholder
        val outputSize = 81
        val inputSize = 729
        val scaleFactor = inputSize.toFloat() / outputSize.toFloat()
        
        val downsizedRgba = ByteArray(outputSize * outputSize * 4)
        
        for (y in 0 until outputSize) {
            for (x in 0 until outputSize) {
                // Simple nearest-neighbor sampling
                val srcX = (x * scaleFactor).toInt().coerceAtMost(inputSize - 1)
                val srcY = (y * scaleFactor).toInt().coerceAtMost(inputSize - 1)
                
                val srcOffset = (srcY * originalFrame.stride) + (srcX * 4)
                val dstOffset = (y * outputSize + x) * 4
                
                // Copy RGBA values
                if (srcOffset + 3 < originalFrame.rgba.size) {
                    downsizedRgba[dstOffset] = originalFrame.rgba[srcOffset]         // R
                    downsizedRgba[dstOffset + 1] = originalFrame.rgba[srcOffset + 1] // G
                    downsizedRgba[dstOffset + 2] = originalFrame.rgba[srcOffset + 2] // B
                    downsizedRgba[dstOffset + 3] = originalFrame.rgba[srcOffset + 3] // A
                }
            }
        }
        
        return CborFrame(
            v = originalFrame.v,
            ts = originalFrame.ts,
            w = outputSize,
            h = outputSize,
            fmt = originalFrame.fmt,
            stride = outputSize * 4,
            premul = originalFrame.premul,
            colorspace = originalFrame.colorspace,
            rgba = downsizedRgba
        )
    }
}
