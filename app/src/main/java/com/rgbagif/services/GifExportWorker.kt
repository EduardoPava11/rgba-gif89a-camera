package com.rgbagif.services

import android.content.Context
import android.util.Log
import androidx.work.*
import com.rgbagif.pipeline.GifPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Production-ready WorkManager for reliable M1→M2→M3 GIF processing
 * 
 * Features:
 * - Retryable M1→M2→M3 pipeline with exponential backoff
 * - Progress notifications for cube processing stages
 * - Structured logging with session correlation
 * - Battery-optimized constraints
 * - Persistent storage for large GIF outputs
 */
class GifExportWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "GifExportWorker"
        
        // Work input keys
        const val KEY_SESSION_ID = "session_id"
        const val KEY_FRAME_DATA = "frame_data"
        const val KEY_PROCESSING_START_TIME = "processing_start_time"
        
        // Work output keys
        const val KEY_OUTPUT_PATH = "output_path"
        const val KEY_FILE_SIZE_BYTES = "file_size_bytes"
        const val KEY_COMPRESSION_RATIO = "compression_ratio"
        const val KEY_PROCESSING_TIME_MS = "processing_time_ms"
        
        /**
         * Enqueue M1→M2→M3 processing with production constraints
         */
        fun enqueueExport(
            context: Context,
            sessionId: String,
            frameData: List<ByteArray>
        ): UUID {
            Log.i(TAG, "Enqueueing cube export for session: $sessionId")
            
            // Serialize frame data for persistence
            val serializedFrames = Json.encodeToString(frameData.map { it.toList() })
            
            val inputData = workDataOf(
                KEY_SESSION_ID to sessionId,
                KEY_FRAME_DATA to serializedFrames,
                KEY_PROCESSING_START_TIME to System.currentTimeMillis()
            )
            
            // Production constraints: battery-aware processing
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .setRequiresCharging(false)  // Allow on battery for responsiveness
                .setRequiresDeviceIdle(false)  // Process immediately
                .setRequiresBatteryNotLow(true)  // But not when battery is critical
                .build()
            
            val exportRequest = OneTimeWorkRequestBuilder<GifExportWorker>()
                .setInputData(inputData)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15,  // Initial delay: 15 seconds
                    TimeUnit.SECONDS
                )
                .addTag("gif_export_$sessionId")
                .addTag("cube_processing")
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "gif_export_$sessionId",
                    ExistingWorkPolicy.REPLACE,  // Replace if re-queued
                    exportRequest
                )
            
            Log.d(TAG, "Work enqueued with ID: ${exportRequest.id} for session: $sessionId")
            return exportRequest.id
        }
        
        /**
         * Get export progress for session monitoring
         */
        fun getExportProgress(context: Context, sessionId: String): WorkInfo? {
            return try {
                WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork("gif_export_$sessionId")
                    .get()
                    .firstOrNull()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get work progress for session: $sessionId", e)
                null
            }
        }
    }

    @Serializable
    data class ProcessingProgress(
        val stage: String,
        val frameIndex: Int,
        val totalFrames: Int,
        val stageProgress: Float
    )

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: run {
            Log.e(TAG, "Missing session ID in work data")
            return@withContext Result.failure()
        }
        
        val frameDataJson = inputData.getString(KEY_FRAME_DATA) ?: run {
            Log.e(TAG, "Missing frame data in work data for session: $sessionId")
            return@withContext Result.failure()
        }
        
        val processingStartTime = inputData.getLong(KEY_PROCESSING_START_TIME, System.currentTimeMillis())
        
        Log.i(TAG, "Starting M1→M2→M3 pipeline processing for session: $sessionId")
        
        try {
            // Update progress: Starting processing
            setProgress(workDataOf(
                "stage" to "M1_PREPARATION",
                "progress" to 0.0f,
                "session_id" to sessionId
            ))
            
            // Deserialize frame data
            val frameDataLists = Json.decodeFromString<List<List<Int>>>(frameDataJson)
            val frameData = frameDataLists.map { list ->
                list.map { it.toByte() }.toByteArray()
            }
            
            Log.d(TAG, "Deserialized ${frameData.size} frames for cube processing - session: $sessionId")
            
            // Validate 81-frame cube structure
            if (frameData.size != 81) {
                Log.e(TAG, "Invalid frame count for cube: ${frameData.size}, expected 81 - session: $sessionId")
                return@withContext Result.failure(workDataOf(
                    "error" to "E_M1_INPUT",
                    "message" to "Invalid frame count: ${frameData.size}, expected 81 for cube"
                ))
            }
            
            // Process through M1→M2→M3 pipeline with progress updates
            val result = processGifCube(sessionId, frameData) { stage, progress ->
                setProgress(workDataOf(
                    "stage" to stage,
                    "progress" to progress,
                    "session_id" to sessionId
                ))
            }
            
            val totalProcessingTime = System.currentTimeMillis() - processingStartTime
            
            Log.i(TAG, "Cube processing completed successfully - session: $sessionId, " +
                      "size: ${result.fileSizeBytes} bytes, time: ${totalProcessingTime}ms")
            
            // Return success with output data
            Result.success(workDataOf(
                KEY_OUTPUT_PATH to result.outputPath,
                KEY_FILE_SIZE_BYTES to result.fileSizeBytes,
                KEY_COMPRESSION_RATIO to result.compressionRatio,
                KEY_PROCESSING_TIME_MS to totalProcessingTime,
                "stage" to "COMPLETED",
                "progress" to 1.0f
            ))
            
        } catch (e: Exception) {
            Log.e(TAG, "GIF processing failed for session: $sessionId", e)
            
            // Determine if error is recoverable for retry logic
            val isRecoverable = when {
                e.message?.contains("E_M1_INPUT") == true -> false  // Input errors not recoverable
                e.message?.contains("E_SYSTEM_TIMEOUT") == true -> true  // Timeout recoverable
                e.message?.contains("E_INFRA_MEMORY") == true -> true  // Memory errors may recover
                e is OutOfMemoryError -> true  // OOM may recover with different conditions
                else -> false  // Default to non-recoverable for safety
            }
            
            val errorData = workDataOf(
                "error" to "E_PROCESSING_FAILED",
                "message" to (e.message ?: "Unknown processing error"),
                "recoverable" to isRecoverable,
                "session_id" to sessionId
            )
            
            if (isRecoverable && runAttemptCount < 3) {
                Log.w(TAG, "Recoverable error, will retry (attempt ${runAttemptCount + 1}/3) - session: $sessionId")
                Result.retry()
            } else {
                Log.e(TAG, "Non-recoverable error or max retries exceeded - session: $sessionId")
                Result.failure(errorData)
            }
        }
    }

    @Serializable
    data class ProcessingResult(
        val outputPath: String,
        val fileSizeBytes: Long,
        val compressionRatio: Float
    )

    /**
     * Process 81-frame data through M1→M2→M3 pipeline with progress callbacks
     */
    private suspend fun processGifCube(
        sessionId: String,
        frameData: List<ByteArray>,
        onProgress: suspend (stage: String, progress: Float) -> Unit
    ): ProcessingResult = withContext(Dispatchers.IO) {
        
        // M1: Neural downsampling (frames already 81×81, but validate)
        onProgress("M1_VALIDATION", 0.1f)
        
        for ((index, frame) in frameData.withIndex()) {
            val expectedSize = 81 * 81 * 3  // 81×81 RGB
            if (frame.size != expectedSize) {
                throw IllegalArgumentException("E_M1_INPUT: Frame $index size ${frame.size}, expected $expectedSize")
            }
            
            // Progress update every 10 frames
            if (index % 10 == 0) {
                val progress = 0.1f + (index.toFloat() / frameData.size) * 0.2f
                onProgress("M1_VALIDATION", progress)
            }
        }
        
        Log.d(TAG, "M1 validation completed - session: $sessionId")
        
        // M2: Quantization stage
        onProgress("M2_QUANTIZATION", 0.3f)
        
        // Initialize GIF pipeline with session correlation
        val gifPipeline = GifPipeline()
        
        // Create frames structure for pipeline
        val frames81Rgb = com.rgbagif.pipeline.Frames81Rgb(
            framesRgb = frameData,
            attentionMaps = List(81) { FloatArray(81 * 81) { 0.5f } },  // Placeholder attention
            processingTimeMs = 0
        )
        
        onProgress("M2_QUANTIZATION", 0.5f)
        
        // M3: GIF encoding stage  
        onProgress("M3_ENCODING", 0.7f)
        
        // Process through pipeline (this would call the actual Rust FFI)
        val result = gifPipeline.processFrames(frames81Rgb, sessionId)
        
        onProgress("M3_ENCODING", 0.9f)
        
        // Save to persistent storage
        val outputPath = saveGifToPersistentStorage(sessionId, result.gifData)
        
        onProgress("FINALIZING", 1.0f)
        
        Log.d(TAG, "Pipeline processing completed - session: $sessionId, output: $outputPath")
        
        ProcessingResult(
            outputPath = outputPath,
            fileSizeBytes = result.fileSizeBytes,
            compressionRatio = result.compressionRatio
        )
    }
    
    /**
     * Save GIF to persistent storage with proper naming
     */
    private fun saveGifToPersistentStorage(sessionId: String, gifData: ByteArray): String {
        val gifDir = applicationContext.getExternalFilesDir("gifs")
        gifDir?.mkdirs()
        
        val timestamp = System.currentTimeMillis()
        val filename = "cube_${sessionId}_${timestamp}.gif"
        val outputFile = gifDir?.resolve(filename)
            ?: throw IllegalStateException("E_INFRA_IO: Cannot access external storage")
        
        outputFile.writeBytes(gifData)
        
        Log.i(TAG, "GIF saved to persistent storage: ${outputFile.absolutePath}")
        return outputFile.absolutePath
    }
}
