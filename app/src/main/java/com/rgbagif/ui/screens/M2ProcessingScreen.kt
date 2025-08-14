package com.rgbagif.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rgbagif.config.AppConfig
import com.rgbagif.logging.PipelineLogger
import com.rgbagif.processing.M2Processor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * M2 Processing Screen - 729×729 → 81×81 downsampling
 * Uses simplified baseline 9×9 block averaging
 */
@Composable
fun M2ProcessingScreen(
    sessionDir: File,
    onNavigateToM3: (sessionDir: File) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // State
    var isProcessing by remember { mutableStateOf(false) }
    var currentFrame by remember { mutableStateOf(0) }
    var totalFrames by remember { mutableStateOf(0) }
    var processedBitmaps by remember { mutableStateOf<List<android.graphics.Bitmap>>(emptyList()) }
    var mosaicBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var sessionId by remember { mutableStateOf("") }
    
    // Load CBOR frames on mount
    LaunchedEffect(sessionDir) {
        withContext(Dispatchers.IO) {
            val cborDir = File(sessionDir, "cbor_frames")
            val cborFiles = cborDir.listFiles { file ->
                file.extension == "cbor"
            }?.sortedBy { it.name } ?: emptyList()
            
            totalFrames = cborFiles.size
            Timber.d("Found $totalFrames CBOR frames to process")
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "M2: Neural Downsize",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = "729×729 → 81×81 (9×9 blocks)",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                if (isProcessing) {
                    LinearProgressIndicator(
                        progress = currentFrame.toFloat() / totalFrames.coerceAtLeast(1),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    )
                    Text(
                        text = "Processing frame $currentFrame/$totalFrames",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Process button
        if (!isProcessing && processedBitmaps.isEmpty()) {
            Button(
                onClick = {
                    scope.launch {
                        processM2(
                            sessionDir = sessionDir,
                            onProgress = { current, total ->
                                currentFrame = current
                                totalFrames = total
                            },
                            onComplete = { bitmaps, mosaic, id ->
                                processedBitmaps = bitmaps
                                mosaicBitmap = mosaic
                                sessionId = id
                                isProcessing = false
                            }
                        )
                    }
                    isProcessing = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start M2 Processing")
            }
        }
        
        // Results display
        if (processedBitmaps.isNotEmpty()) {
            Column {
                // Mosaic preview
                mosaicBitmap?.let { mosaic ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp)
                        ) {
                            Text(
                                text = "9×9 Diagnostic Mosaic",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Image(
                                bitmap = mosaic.asImageBitmap(),
                                contentDescription = "Mosaic",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Grid of processed frames
                Text(
                    text = "Processed Frames (${processedBitmaps.size})",
                    style = MaterialTheme.typography.titleMedium
                )
                
                LazyVerticalGrid(
                    columns = GridCells.Fixed(9),
                    modifier = Modifier.weight(1f)
                ) {
                    items(processedBitmaps) { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .padding(1.dp)
                        )
                    }
                }
                
                // Continue to M3 button
                Button(
                    onClick = {
                        onNavigateToM3(sessionDir)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    Text("Continue to M3 (GIF Export)")
                }
            }
        }
    }
}

/**
 * Process M2 downsampling
 */
private suspend fun processM2(
    sessionDir: File,
    onProgress: (Int, Int) -> Unit,
    onComplete: (List<android.graphics.Bitmap>, android.graphics.Bitmap?, String) -> Unit
) = withContext(Dispatchers.IO) {
    try {
        val startTime = System.currentTimeMillis()
        val sessionId = PipelineLogger.startSession()
        
        // Log M2 start
        PipelineLogger.logM2Start(sessionId)
        
        // Initialize M2 processor
        val processor = M2Processor()
        processor.startSession()
        
        // Load CBOR frames
        val cborDir = File(sessionDir, "cbor_frames")
        val cborFiles = cborDir.listFiles { file ->
            file.extension == "cbor"
        }?.sortedBy { it.name } ?: emptyList()
        
        // Take first 81 frames (or less if not enough)
        val framesToProcess = cborFiles.take(AppConfig.EXPORT_FRAMES)
        
        val processedBitmaps = mutableListOf<android.graphics.Bitmap>()
        val m2Dir = File(sessionDir, "m2_output")
        m2Dir.mkdirs()
        
        // Process each frame
        framesToProcess.forEachIndexed { index, cborFile ->
            withContext(Dispatchers.Main) {
                onProgress(index + 1, framesToProcess.size)
            }
            
            // Read CBOR and extract RGBA data
            val rgbaData = readCborFrame(cborFile)
            
            if (rgbaData != null) {
                val frameStartTime = System.currentTimeMillis()
                
                // Process frame through M2
                val result = processor.processFrame(
                    rgbaData = rgbaData,
                    width = AppConfig.CAPTURE_WIDTH,
                    height = AppConfig.CAPTURE_HEIGHT,
                    frameIndex = index
                )
                
                // Save processed frame
                val outputFile = File(m2Dir, "frame_%03d.png".format(index))
                processor.savePng(result.outputBitmap, outputFile)
                
                processedBitmaps.add(result.outputBitmap)
                
                // Log frame done
                PipelineLogger.logM2FrameDone(
                    idx = index,
                    inW = AppConfig.CAPTURE_WIDTH,
                    inH = AppConfig.CAPTURE_HEIGHT,
                    outW = AppConfig.EXPORT_WIDTH,
                    outH = AppConfig.EXPORT_HEIGHT,
                    elapsedMs = System.currentTimeMillis() - frameStartTime,
                    path = outputFile.absolutePath
                )
            }
        }
        
        // Generate diagnostic mosaic
        val mosaic = processor.generateDiagnosticMosaic(processedBitmaps)
        val mosaicFile = File(m2Dir, "m2_mosaic.png")
        if (mosaic != null) {
            processor.savePng(mosaic, mosaicFile)
            PipelineLogger.logM2MosaicDone(mosaicFile.absolutePath)
        }
        
        // Log M2 complete
        val elapsedMs = System.currentTimeMillis() - startTime
        PipelineLogger.logM2Done(processedBitmaps.size, elapsedMs)
        
        withContext(Dispatchers.Main) {
            onComplete(processedBitmaps, mosaic, sessionId)
        }
        
    } catch (e: Exception) {
        Timber.e(e, "M2 processing failed")
        PipelineLogger.logPipelineError(
            stage = "M2",
            message = "Processing failed",
            throwable = e
        )
    }
}

/**
 * Read CBOR frame and extract RGBA data
 */
private fun readCborFrame(cborFile: File): ByteArray? {
    return try {
        // Read CBOR file
        val cborData = cborFile.readBytes()
        
        // Parse CBOR to extract RGBA data
        // For now, simplified extraction - in production would use proper CBOR parser
        // The RGBA data is stored as a ByteString in the "data" field
        
        // Find the "data" field marker and extract the byte array
        // This is a simplified version - real implementation would use ciborium
        val dataMarker = "data".toByteArray()
        var dataStart = -1
        
        for (i in 0 until cborData.size - dataMarker.size) {
            if (cborData.sliceArray(i until i + dataMarker.size).contentEquals(dataMarker)) {
                // Found "data" field, the actual data starts after some CBOR encoding bytes
                dataStart = i + dataMarker.size + 2 // Skip field name and CBOR type byte
                break
            }
        }
        
        if (dataStart > 0 && dataStart < cborData.size) {
            // Extract the RGBA data (729×729×4 bytes)
            val expectedSize = AppConfig.CAPTURE_WIDTH * AppConfig.CAPTURE_HEIGHT * 4
            val rgbaData = cborData.sliceArray(dataStart until minOf(dataStart + expectedSize, cborData.size))
            
            if (rgbaData.size == expectedSize) {
                return rgbaData
            }
        }
        
        Timber.w("Could not extract RGBA data from CBOR file: ${cborFile.name}")
        null
        
    } catch (e: Exception) {
        Timber.e(e, "Failed to read CBOR frame: ${cborFile.name}")
        null
    }
}