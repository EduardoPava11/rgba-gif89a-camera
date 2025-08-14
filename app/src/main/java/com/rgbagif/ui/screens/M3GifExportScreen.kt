package com.rgbagif.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.rgbagif.config.AppConfig
import com.rgbagif.logging.PipelineLogger
import com.rgbagif.processing.GifExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * M3 GIF Export Screen - Final stage of pipeline
 * Creates GIF89a from 81 processed frames
 */
@Composable
fun M3GifExportScreen(
    sessionDir: File,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // State
    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf(0f) }
    var gifFile by remember { mutableStateOf<File?>(null) }
    var gifBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var sessionId by remember { mutableStateOf("") }
    
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
                    text = "M3: GIF Export",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = "Create GIF89a (81 frames @ ${AppConfig.EXPORT_FPS} fps)",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                if (isExporting) {
                    LinearProgressIndicator(
                        progress = exportProgress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    )
                    Text(
                        text = "Exporting... ${(exportProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Export button or results
        if (gifFile == null) {
            Button(
                onClick = {
                    scope.launch {
                        isExporting = true
                        exportGif(
                            sessionDir = sessionDir,
                            onProgress = { progress ->
                                exportProgress = progress
                            },
                            onComplete = { file, bitmap, id ->
                                gifFile = file
                                gifBitmap = bitmap
                                sessionId = id
                                isExporting = false
                            }
                        )
                    }
                },
                enabled = !isExporting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Export to GIF89a")
            }
        } else {
            // Show result
            Column {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "GIF Export Complete!",
                            style = MaterialTheme.typography.titleLarge
                        )
                        
                        gifFile?.let { file ->
                            Text(
                                text = "File: ${file.name}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Size: ${file.length() / 1024} KB",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        
                        // Preview first frame or animated preview
                        gifBitmap?.let { bitmap ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "GIF Preview",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Share button
                Button(
                    onClick = {
                        gifFile?.let { file ->
                            shareGif(context, file)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Share GIF")
                }
                
                // Done button
                OutlinedButton(
                    onClick = onComplete,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Done")
                }
            }
        }
    }
}

/**
 * Export frames to GIF89a
 */
private suspend fun exportGif(
    sessionDir: File,
    onProgress: (Float) -> Unit,
    onComplete: (File, android.graphics.Bitmap?, String) -> Unit
) = withContext(Dispatchers.IO) {
    try {
        val startTime = System.currentTimeMillis()
        val sessionId = PipelineLogger.startSession()
        
        // Log M3 start
        PipelineLogger.logM3Start(sessionId)
        
        // Load M2 output PNGs
        val m2Dir = File(sessionDir, "m2_output")
        val pngFiles = m2Dir.listFiles { file ->
            file.extension == "png" && file.name.startsWith("frame_")
        }?.sortedBy { it.name } ?: emptyList()
        
        Timber.d("Found ${pngFiles.size} PNG files for GIF export")
        
        // Create GIF exporter
        val exporter = GifExporter()
        
        // Output file
        val gifFile = File(sessionDir, "output.gif")
        
        // Export to GIF89a
        val success = exporter.exportToGif(
            pngFiles = pngFiles,
            outputFile = gifFile,
            fps = AppConfig.EXPORT_FPS,
            loop = true,
            onProgress = { progress ->
                // Remove withContext since onProgress should be called from the UI thread
                onProgress(progress)
            }
        )
        
        if (success) {
            // Load first frame as preview
            val previewBitmap = if (pngFiles.isNotEmpty()) {
                android.graphics.BitmapFactory.decodeFile(pngFiles.first().absolutePath)
            } else null
            
            // Log M3 complete
            val elapsedMs = System.currentTimeMillis() - startTime
            PipelineLogger.logM3GifDone(
                frames = pngFiles.size,
                fps = AppConfig.EXPORT_FPS,
                sizeBytes = gifFile.length(),
                loop = true,
                path = gifFile.absolutePath
            )
            
            withContext(Dispatchers.Main) {
                onComplete(gifFile, previewBitmap, sessionId)
            }
        } else {
            throw Exception("GIF export failed")
        }
        
    } catch (e: Exception) {
        Timber.e(e, "Failed to export GIF")
        PipelineLogger.logPipelineError(
            stage = "M3",
            message = "GIF export failed",
            throwable = e
        )
    }
}

/**
 * Share GIF via Android share sheet
 */
private fun shareGif(context: android.content.Context, gifFile: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            gifFile
        )
        
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "image/gif"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        context.startActivity(Intent.createChooser(shareIntent, "Share GIF"))
        
    } catch (e: Exception) {
        Timber.e(e, "Failed to share GIF")
        Toast.makeText(context, "Failed to share GIF", Toast.LENGTH_SHORT).show()
    }
}