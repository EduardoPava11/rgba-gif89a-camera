package com.rgbagif.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import android.os.Build
import com.rgbagif.camera.CameraXManager
import com.rgbagif.export.GifExportManager
import com.rgbagif.processing.UnifiedPipeline
import com.rgbagif.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Simplified single-button GIF creation screen with smooth UI
 */
@Composable
fun SimpleGifScreen(
    cameraManager: CameraXManager,
    exportManager: GifExportManager
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    
    // State management
    var state by remember { mutableStateOf(GifState.IDLE) }
    var progress by remember { mutableStateOf(0f) }
    var statusMessage by remember { mutableStateOf("Ready to capture") }
    var gifFile by remember { mutableStateOf<File?>(null) }
    var framesCaptured by remember { mutableStateOf(0) }
    
    // Frame storage
    val capturedFrames = remember { mutableListOf<ByteArray>() }
    
    // Camera preview
    val previewView = remember { PreviewView(context) }
    
    // Initialize camera
    LaunchedEffect(cameraManager) {
        cameraManager.setPreviewView(previewView)
        cameraManager.setupCamera(lifecycleOwner) { bitmap ->
            // Frame callback - not used in simplified mode
        }
        
        // Set up RGBA callback for capturing
        cameraManager.setFrameCallback { rgba, width, height ->
            if (state == GifState.CAPTURING && capturedFrames.size < 81) {
                capturedFrames.add(rgba.clone())
                framesCaptured = capturedFrames.size
                progress = framesCaptured / 81f
                Log.d("SimpleGifScreen", "Captured frame ${capturedFrames.size}/81")
            }
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Camera Preview
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )
        
        // Top Status Bar
        TopStatusBar(
            state = state,
            statusMessage = statusMessage,
            progress = progress
        )
        
        // Bottom Control - Single Button
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
        ) {
            when (state) {
                GifState.IDLE -> {
                    MakeGifButton(
                        onClick = {
                            scope.launch {
                                captureAndCreateGif(
                                    capturedFrames = capturedFrames,
                                    context = context,
                                    onStateChange = { newState -> state = newState },
                                    onProgressChange = { newProgress -> progress = newProgress },
                                    onStatusChange = { newStatus -> statusMessage = newStatus },
                                    onGifCreated = { file -> 
                                        gifFile = file
                                        state = GifState.COMPLETE
                                    }
                                )
                            }
                        }
                    )
                }
                
                GifState.CAPTURING,
                GifState.PROCESSING_M1,
                GifState.PROCESSING_M2,
                GifState.PROCESSING_M3 -> {
                    ProcessingIndicator(
                        progress = progress,
                        message = statusMessage
                    )
                }
                
                GifState.COMPLETE -> {
                    gifFile?.let { file ->
                        GifCompleteButtons(
                            gifFile = file,
                            exportManager = exportManager,
                            onReset = {
                                state = GifState.IDLE
                                progress = 0f
                                statusMessage = "Ready to capture"
                                capturedFrames.clear()
                                framesCaptured = 0
                                gifFile = null
                            }
                        )
                    }
                }
                
                GifState.ERROR -> {
                    ErrorDisplay(
                        message = statusMessage,
                        onRetry = {
                            state = GifState.IDLE
                            progress = 0f
                            statusMessage = "Ready to capture"
                            capturedFrames.clear()
                            framesCaptured = 0
                        }
                    )
                }
            }
        }
        
        // GIF Preview Overlay (when complete)
        if (state == GifState.COMPLETE && gifFile != null) {
            GifPreviewOverlay(gifFile = gifFile!!)
        }
    }
}

@Composable
private fun TopStatusBar(
    state: GifState,
    statusMessage: String,
    progress: Float
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        color = Color.Black.copy(alpha = 0.7f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = statusMessage,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            
            if (state != GifState.IDLE && state != GifState.COMPLETE && state != GifState.ERROR) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = when(state) {
                        GifState.CAPTURING -> MatrixGreen
                        GifState.PROCESSING_M1 -> ProcessingOrange
                        GifState.PROCESSING_M2 -> Color(0xFF9C27B0) // Purple
                        GifState.PROCESSING_M3 -> Color(0xFF2196F3) // Blue
                        else -> Color.White
                    }
                )
            }
        }
    }
}

@Composable
private fun MakeGifButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(120.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = ProcessingOrange
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 8.dp,
            pressedElevation = 12.dp
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.PhotoCamera,
                contentDescription = "Make GIF",
                modifier = Modifier.size(48.dp),
                tint = Color.White
            )
            Text(
                text = "Make GIF",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ProcessingIndicator(
    progress: Float,
    message: String
) {
    Card(
        modifier = Modifier.padding(horizontal = 32.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Black.copy(alpha = 0.9f)
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(64.dp),
                color = MatrixGreen,
                strokeWidth = 4.dp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                color = Color.White,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun GifCompleteButtons(
    gifFile: File,
    exportManager: GifExportManager,
    onReset: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // View GIF button
        Button(
            onClick = {
                // Open GIF in viewer - just share for now
                exportManager.shareGif(gifFile)
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = MatrixGreen
            )
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = "View")
            Spacer(modifier = Modifier.width(8.dp))
            Text("View GIF")
        }
        
        // Share button
        Button(
            onClick = {
                exportManager.shareGif(gifFile)
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = ProcessingOrange
            )
        ) {
            Icon(Icons.Default.Share, contentDescription = "Share")
            Spacer(modifier = Modifier.width(8.dp))
            Text("Share")
        }
        
        // New button
        Button(
            onClick = onReset,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Gray
            )
        ) {
            Icon(Icons.Default.Refresh, contentDescription = "New")
            Spacer(modifier = Modifier.width(8.dp))
            Text("New")
        }
    }
}

@Composable
private fun ErrorDisplay(
    message: String,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier.padding(horizontal = 32.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = ErrorRed.copy(alpha = 0.9f)
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = "Error",
                modifier = Modifier.size(48.dp),
                tint = Color.White
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                color = Color.White,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White
                )
            ) {
                Text("Try Again", color = ErrorRed)
            }
        }
    }
}

@Composable
private fun GifPreviewOverlay(gifFile: File) {
    val context = LocalContext.current
    
    // Create ImageLoader with GIF support
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 120.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Card(
            modifier = Modifier.size(200.dp),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            AsyncImage(
                model = gifFile,
                contentDescription = "GIF Preview",
                imageLoader = imageLoader,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private suspend fun captureAndCreateGif(
    capturedFrames: MutableList<ByteArray>,
    context: android.content.Context,
    onStateChange: (GifState) -> Unit,
    onProgressChange: (Float) -> Unit,
    onStatusChange: (String) -> Unit,
    onGifCreated: (File) -> Unit
) = withContext(Dispatchers.IO) {
    try {
        // Clear previous frames
        capturedFrames.clear()
        
        // M1: Capture 81 frames
        onStateChange(GifState.CAPTURING)
        onStatusChange("Capturing frames...")
        onProgressChange(0f)
        
        // Wait for frames to be captured (camera callback fills the list)
        while (capturedFrames.size < 81) {
            delay(50) // Check every 50ms
            val progress = capturedFrames.size / 81f
            onProgressChange(progress)
            onStatusChange("Capturing: ${capturedFrames.size}/81 frames")
        }
        
        Log.i("SimpleGifScreen", "M1_COMPLETE: Captured 81 frames")
        
        // M2: Downscale 729×729 → 81×81
        onStateChange(GifState.PROCESSING_M2)
        onStatusChange("Downscaling frames...")
        onProgressChange(0f)
        
        val downscaledFrames = mutableListOf<ByteArray>()
        capturedFrames.forEachIndexed { index, frame ->
            // Use M2Processor directly for downscaling
            val m2Processor = com.rgbagif.processing.M2Processor()
            val downscaled = m2Processor.downsize729To81Cpu(frame)
            downscaledFrames.add(downscaled)
            val progress = (index + 1) / 81f
            onProgressChange(progress)
            onStatusChange("Downscaling: ${index + 1}/81")
            Log.d("SimpleGifScreen", "M2_PROGRESS: Downscaled frame ${index + 1}/81")
        }
        
        Log.i("SimpleGifScreen", "M2_COMPLETE: Downscaled 81 frames to 81×81")
        
        // M3: Create GIF with NeuQuant + LZW
        onStateChange(GifState.PROCESSING_M3)
        onStatusChange("Creating GIF...")
        onProgressChange(0.5f)
        
        // M3: Create GIF using Rust UniFFI
        val outputDir = java.io.File(context.getExternalFilesDir(null), "gifs")
        outputDir.mkdirs()
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val gifFile = java.io.File(outputDir, "gif_$timestamp.gif")
        
        // Call Rust M3 directly with downscaled frames
        val m3Processor = com.rgbagif.processing.M3Processor()
        m3Processor.exportGif89aFromRgba(
            rgbaFrames = downscaledFrames,
            outputDir = outputDir,
            baseName = "gif_$timestamp"
        )
        
        // Verify the file exists
        if (!gifFile.exists()) {
            throw Exception("Failed to create GIF file")
        }
        onProgressChange(1f)
        
        Log.i("SimpleGifScreen", "M3_COMPLETE: GIF created at ${gifFile.absolutePath} (${gifFile.length()} bytes)")
        
        // Complete!
        onGifCreated(gifFile)
        onStatusChange("GIF created successfully!")
        
    } catch (e: Exception) {
        Log.e("SimpleGifScreen", "Error creating GIF", e)
        onStateChange(GifState.ERROR)
        onStatusChange("Error: ${e.message}")
    }
}

enum class GifState {
    IDLE,
    CAPTURING,
    PROCESSING_M1,
    PROCESSING_M2,
    PROCESSING_M3,
    COMPLETE,
    ERROR
}