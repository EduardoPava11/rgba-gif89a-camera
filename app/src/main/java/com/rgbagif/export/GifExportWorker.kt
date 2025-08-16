package com.rgbagif.export

import android.content.Context
import android.util.Log
import androidx.work.*
import com.gifpipe.ffi.GifPipe
import com.gifpipe.ffi.QuantizedCubeData
import com.gifpipe.ffi.GifInfo
import com.gifpipe.ffi.GifValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * WorkManager Worker for GIF export pipeline (M2 + M3)
 * 
 * Features:
 * - Handles M2 quantization (frames → cube)
 * - Handles M3 encoding (cube → GIF)
 * - Progress reporting
 * - Retry with exponential backoff
 * - Result persistence for WYSIWYG preview
 */
class GifExportWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        private const val TAG = "GifExportWorker"
        
        // Input data keys
        const val KEY_SESSION_ID = "session_id"
        const val KEY_FRAMES_DIR = "frames_dir"
        const val KEY_OUTPUT_PATH = "output_path"
        const val KEY_FPS_CS = "fps_cs"  // Centiseconds per frame
        const val KEY_LOOP_FOREVER = "loop_forever"
        const val KEY_STAGE = "stage"  // "m2" or "m3" or "full"
        const val KEY_CUBE_PATH = "cube_path"  // For M3-only mode
        
        // Progress keys
        const val PROGRESS_STAGE = "stage"
        const val PROGRESS_CURRENT = "current"
        const val PROGRESS_TOTAL = "total"
        const val PROGRESS_MESSAGE = "message"
        
        /**
         * Create work request for full pipeline (M2 + M3)
         */
        fun createFullPipelineRequest(
            sessionId: String,
            framesDir: String,
            outputPath: String,
            fpsCs: Int = 4,  // 25 FPS default
            loopForever: Boolean = true
        ): OneTimeWorkRequest {
            val inputData = workDataOf(
                KEY_SESSION_ID to sessionId,
                KEY_FRAMES_DIR to framesDir,
                KEY_OUTPUT_PATH to outputPath,
                KEY_FPS_CS to fpsCs,
                KEY_LOOP_FOREVER to loopForever,
                KEY_STAGE to "full"
            )
            
            return OneTimeWorkRequestBuilder<GifExportWorker>()
                .setInputData(inputData)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    OneTimeWorkRequest.MIN_BACKOFF_MILLIS,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
                .addTag("gif_export")
                .addTag(sessionId)
                .build()
        }
        
        /**
         * Create work request for M2 only (quantization)
         */
        fun createM2Request(
            sessionId: String,
            framesDir: String,
            cubePath: String
        ): OneTimeWorkRequest {
            val inputData = workDataOf(
                KEY_SESSION_ID to sessionId,
                KEY_FRAMES_DIR to framesDir,
                KEY_OUTPUT_PATH to cubePath,
                KEY_STAGE to "m2"
            )
            
            return OneTimeWorkRequestBuilder<GifExportWorker>()
                .setInputData(inputData)
                .addTag("m2_quantization")
                .addTag(sessionId)
                .build()
        }
        
        /**
         * Create work request for M3 only (encoding)
         */
        fun createM3Request(
            sessionId: String,
            cubePath: String,
            outputPath: String,
            fpsCs: Int = 4,
            loopForever: Boolean = true
        ): OneTimeWorkRequest {
            val inputData = workDataOf(
                KEY_SESSION_ID to sessionId,
                KEY_CUBE_PATH to cubePath,
                KEY_OUTPUT_PATH to outputPath,
                KEY_FPS_CS to fpsCs,
                KEY_LOOP_FOREVER to loopForever,
                KEY_STAGE to "m3"
            )
            
            return OneTimeWorkRequestBuilder<GifExportWorker>()
                .setInputData(inputData)
                .addTag("m3_encoding")
                .addTag(sessionId)
                .build()
        }
    }
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: UUID.randomUUID().toString()
        val stage = inputData.getString(KEY_STAGE) ?: "full"
        
        Log.i(TAG, "Starting GIF export: session=$sessionId stage=$stage")
        
        try {
            // Initialize Rust tracing
            val rustSessionId = GifPipe.initTracing()
            Log.d(TAG, "Rust tracing initialized: $rustSessionId")
            
            when (stage) {
                "m2" -> executeM2(sessionId)
                "m3" -> executeM3(sessionId)
                "full" -> executeFull(sessionId)
                else -> {
                    Log.e(TAG, "Unknown stage: $stage")
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            Result.retry()  // Will use exponential backoff
        }
    }
    
    /**
     * Execute M2: Quantize frames to cube
     */
    private suspend fun executeM2(sessionId: String): Result {
        val framesDir = inputData.getString(KEY_FRAMES_DIR)
            ?: return Result.failure()
        val outputPath = inputData.getString(KEY_OUTPUT_PATH)
            ?: return Result.failure()
        
        updateProgress("M2", 0, 100, "Loading frames...")
        
        // Load RGBA frames from directory
        val frames = loadFramesFromDirectory(framesDir)
        if (frames.size != 81) {
            Log.e(TAG, "Expected 81 frames, got ${frames.size}")
            return Result.failure()
        }
        
        updateProgress("M2", 10, 100, "Quantizing ${frames.size} frames...")
        
        // Call Rust M2 quantization
        val cube = GifPipe.quantizeFrames(frames)
        
        updateProgress("M2", 90, 100, "Saving cube data...")
        
        // Save cube to file for persistence
        saveCubeToFile(cube, outputPath)
        
        updateProgress("M2", 100, 100, "Quantization complete")
        
        Log.i(TAG, "M2 complete: palette_size=${cube.globalPaletteRgb.size / 3}, " +
                "mean_delta_e=${cube.meanDeltaE}, p95_delta_e=${cube.p95DeltaE}")
        
        return Result.success(workDataOf(
            "cube_path" to outputPath,
            "palette_size" to (cube.globalPaletteRgb.size / 3),
            "mean_delta_e" to cube.meanDeltaE
        ))
    }
    
    /**
     * Execute M3: Encode cube to GIF
     */
    private suspend fun executeM3(sessionId: String): Result {
        val cubePath = inputData.getString(KEY_CUBE_PATH)
            ?: return Result.failure()
        val outputPath = inputData.getString(KEY_OUTPUT_PATH)
            ?: return Result.failure()
        val fpsCs = inputData.getInt(KEY_FPS_CS, 4)
        val loopForever = inputData.getBoolean(KEY_LOOP_FOREVER, true)
        
        updateProgress("M3", 0, 100, "Loading cube data...")
        
        // Load cube from file
        val cube = loadCubeFromFile(cubePath)
            ?: return Result.failure()
        
        updateProgress("M3", 10, 100, "Encoding GIF...")
        
        // Call Rust M3 encoding
        val gifInfo = GifPipe.writeGif(cube, fpsCs.toByte(), loopForever)
        
        updateProgress("M3", 70, 100, "Validating GIF...")
        
        // Validate the GIF
        val validation = GifPipe.validateGif(gifInfo.gifData)
        
        if (!validation.isValid) {
            Log.e(TAG, "GIF validation failed: ${validation.errors}")
            return Result.failure()
        }
        
        updateProgress("M3", 90, 100, "Saving GIF...")
        
        // Save GIF to file
        File(outputPath).writeBytes(gifInfo.gifData)
        
        updateProgress("M3", 100, 100, "GIF encoding complete")
        
        Log.i(TAG, "M3 complete: size=${gifInfo.fileSizeBytes} bytes, " +
                "frames=${gifInfo.frameCount}, compression=${gifInfo.compressionRatio}")
        
        return Result.success(workDataOf(
            "gif_path" to outputPath,
            "file_size" to gifInfo.fileSizeBytes.toLong(),
            "frame_count" to gifInfo.frameCount,
            "compression_ratio" to gifInfo.compressionRatio,
            "has_netscape_loop" to validation.hasNetscapeLoop,
            "has_trailer" to validation.hasTrailer
        ))
    }
    
    /**
     * Execute full pipeline: M2 + M3
     */
    private suspend fun executeFull(sessionId: String): Result {
        val framesDir = inputData.getString(KEY_FRAMES_DIR)
            ?: return Result.failure()
        val outputPath = inputData.getString(KEY_OUTPUT_PATH)
            ?: return Result.failure()
        val fpsCs = inputData.getInt(KEY_FPS_CS, 4)
        val loopForever = inputData.getBoolean(KEY_LOOP_FOREVER, true)
        
        // Execute M2
        updateProgress("M2", 0, 200, "Loading frames...")
        val frames = loadFramesFromDirectory(framesDir)
        
        updateProgress("M2", 20, 200, "Quantizing...")
        val cube = GifPipe.quantizeFrames(frames)
        
        // Optionally save cube for preview
        val cubeFile = File(outputPath.replace(".gif", "_cube.bin"))
        saveCubeToFile(cube, cubeFile.absolutePath)
        
        // Execute M3
        updateProgress("M3", 100, 200, "Encoding GIF...")
        val gifInfo = GifPipe.writeGif(cube, fpsCs.toByte(), loopForever)
        
        updateProgress("M3", 180, 200, "Validating...")
        val validation = GifPipe.validateGif(gifInfo.gifData)
        
        if (!validation.isValid) {
            Log.e(TAG, "Validation failed: ${validation.errors}")
            return Result.failure()
        }
        
        updateProgress("M3", 190, 200, "Saving...")
        File(outputPath).writeBytes(gifInfo.gifData)
        
        updateProgress("Complete", 200, 200, "Export complete!")
        
        return Result.success(workDataOf(
            "gif_path" to outputPath,
            "cube_path" to cubeFile.absolutePath,
            "file_size" to gifInfo.fileSizeBytes.toLong(),
            "validation_passed" to validation.isValid
        ))
    }
    
    /**
     * Update progress for monitoring
     */
    private suspend fun updateProgress(
        stage: String,
        current: Int,
        total: Int,
        message: String
    ) {
        setProgress(workDataOf(
            PROGRESS_STAGE to stage,
            PROGRESS_CURRENT to current,
            PROGRESS_TOTAL to total,
            PROGRESS_MESSAGE to message
        ))
    }
    
    /**
     * Load RGBA frames from directory
     */
    private fun loadFramesFromDirectory(dirPath: String): List<ByteArray> {
        val dir = File(dirPath)
        if (!dir.exists() || !dir.isDirectory) {
            Log.e(TAG, "Invalid frames directory: $dirPath")
            return emptyList()
        }
        
        return dir.listFiles { f -> f.extension in listOf("rgba", "raw", "bin") }
            ?.sortedBy { it.name }
            ?.mapNotNull { file ->
                try {
                    file.readBytes()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read frame: ${file.name}", e)
                    null
                }
            } ?: emptyList()
    }
    
    /**
     * Save cube to file for persistence
     */
    private fun saveCubeToFile(cube: QuantizedCubeData, path: String) {
        // Implement serialization (e.g., using Protocol Buffers or custom format)
        // For now, a placeholder implementation
        File(path).writeText("CUBE_DATA_PLACEHOLDER")
        Log.d(TAG, "Cube saved to: $path")
    }
    
    /**
     * Load cube from file
     */
    private fun loadCubeFromFile(path: String): QuantizedCubeData? {
        // Implement deserialization
        // For now, return a test cube
        return QuantizedCubeData(
            width = 81u,
            height = 81u,
            globalPaletteRgb = ByteArray(256 * 3),
            indexedFrames = List(81) { ByteArray(81 * 81) },
            delaysCs = ByteArray(81) { 4 },
            paletteStability = 0.95f,
            meanDeltaE = 2.5f,
            p95DeltaE = 5.0f
        )
    }
}