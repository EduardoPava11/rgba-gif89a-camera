package com.rgbagif.ui.screens

import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rgbagif.camera.CameraXManager
import com.rgbagif.config.AppConfig
import com.rgbagif.logging.PipelineLogger
import com.rgbagif.native.M1Fast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*

/**
 * M1 Capture Screen - 729×729 RGBA capture with CBOR storage
 * Uses JNI fast-path for zero-copy writes
 */
@Composable
fun M1CaptureScreen(
    cameraManager: CameraXManager,
    onNavigateToM2: (sessionDir: File) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    
    // State
    var isCapturing by remember { mutableStateOf(false) }
    var frameCount by remember { mutableStateOf(0) }
    var sessionDir by remember { mutableStateOf<File?>(null) }
    var sessionId by remember { mutableStateOf("") }
    
    // Create preview view
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    
    // Initialize M1Fast JNI
    LaunchedEffect(Unit) {
        try {
            System.loadLibrary("m1fast")
            val version = M1Fast.getVersion()
            Timber.d("M1Fast JNI loaded: $version")
        } catch (e: Exception) {
            Timber.e(e, "Failed to load M1Fast JNI")
        }
    }
    
    // Setup camera
    DisposableEffect(lifecycleOwner) {
        cameraManager.setPreviewView(previewView)
        
        cameraManager.setupCamera(lifecycleOwner) { bitmap ->
            // Frame processor for UI updates
            // Actual CBOR writing happens in rgbaFrameCallback
        }
        
        // Set RGBA frame callback for M1 capture
        cameraManager.setFrameCallback { rgbaData, width, height ->
            if (isCapturing && frameCount < AppConfig.TOTAL_FRAMES) {
                scope.launch(Dispatchers.IO) {
                    captureFrame(
                        rgbaData = rgbaData,
                        width = width,
                        height = height,
                        frameIndex = frameCount,
                        sessionDir = sessionDir!!,
                        sessionId = sessionId
                    )
                    
                    withContext(Dispatchers.Main) {
                        frameCount++
                        if (frameCount >= AppConfig.TOTAL_FRAMES) {
                            // Capture complete
                            isCapturing = false
                            val elapsedMs = System.currentTimeMillis() // Calculate from session start
                            PipelineLogger.logM1Done(AppConfig.TOTAL_FRAMES, elapsedMs)
                            
                            Toast.makeText(
                                context,
                                "M1 Complete: ${AppConfig.TOTAL_FRAMES} frames captured",
                                Toast.LENGTH_SHORT
                            ).show()
                            
                            // Navigate to M2
                            onNavigateToM2(sessionDir!!)
                        }
                    }
                }
            }
        }
        
        onDispose {
            cameraManager.release()
        }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        // Camera preview
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )
        
        // Overlay UI
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top bar with status
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "M1: RGBA Capture",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Resolution: ${AppConfig.CAPTURE_WIDTH}×${AppConfig.CAPTURE_HEIGHT}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (isCapturing) {
                        LinearProgressIndicator(
                            progress = frameCount.toFloat() / AppConfig.TOTAL_FRAMES,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        )
                        Text(
                            text = "Frame ${frameCount}/${AppConfig.TOTAL_FRAMES}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            
            // Bottom capture button
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = {
                        if (!isCapturing) {
                            // Start capture
                            scope.launch {
                                startCapture(
                                    context = context,
                                    onSessionReady = { dir, id ->
                                        sessionDir = dir
                                        sessionId = id
                                        frameCount = 0
                                        isCapturing = true
                                    }
                                )
                            }
                        }
                    },
                    enabled = !isCapturing,
                    modifier = Modifier.size(80.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isCapturing) Color.Red else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (isCapturing) "$frameCount" else "START")
                }
            }
        }
    }
}

/**
 * Start M1 capture session
 */
private suspend fun startCapture(
    context: android.content.Context,
    onSessionReady: (File, String) -> Unit
) = withContext(Dispatchers.IO) {
    // Generate session ID and create directory
    val sessionId = PipelineLogger.startSession()
    val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    val sessionDirName = "m1_${dateFormat.format(Date())}"
    
    val sessionDir = File(context.getExternalFilesDir(null), "sessions/$sessionDirName")
    sessionDir.mkdirs()
    
    // Create CBOR frames directory
    val cborDir = File(sessionDir, "cbor_frames")
    cborDir.mkdirs()
    
    // Log M1 start
    PipelineLogger.logM1Start(sessionId)
    
    withContext(Dispatchers.Main) {
        onSessionReady(sessionDir, sessionId)
    }
}

/**
 * Capture single frame using JNI fast-path
 */
private suspend fun captureFrame(
    rgbaData: ByteArray,
    width: Int,
    height: Int,
    frameIndex: Int,
    sessionDir: File,
    sessionId: String
) = withContext(Dispatchers.IO) {
    try {
        // Prepare output path
        val cborDir = File(sessionDir, "cbor_frames")
        val outputFile = File(cborDir, "frame_%03d.cbor".format(frameIndex))
        
        // Create DirectByteBuffer for zero-copy
        val directBuffer = ByteBuffer.allocateDirect(rgbaData.size)
        directBuffer.put(rgbaData)
        directBuffer.rewind()
        
        // Call JNI to write CBOR (zero-copy)
        val success = M1Fast.writeFrame(
            directBuffer,
            width,
            height,
            width * 4, // stride in bytes
            System.currentTimeMillis(),
            frameIndex,
            outputFile.absolutePath
        )
        
        if (success) {
            // Log frame saved
            PipelineLogger.logM1FrameSaved(
                idx = frameIndex,
                width = width,
                height = height,
                bytes = rgbaData.size,
                path = outputFile.absolutePath
            )
        } else {
            PipelineLogger.logPipelineError(
                stage = "M1",
                message = "Failed to write frame $frameIndex"
            )
        }
        
    } catch (e: Exception) {
        Timber.e(e, "Failed to capture frame $frameIndex")
        PipelineLogger.logPipelineError(
            stage = "M1",
            message = "Exception capturing frame $frameIndex",
            throwable = e
        )
    }
}