package com.rgbagif.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.rgbagif.camera.CameraXManager
import com.rgbagif.export.GifExportManager
import com.rgbagif.processing.M2Processor
import com.rgbagif.processing.M3Processor
import com.rgbagif.ui.theme.*
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

/**
 * Technical Pipeline Screen - Form follows function UI exposing the 3-milestone architecture
 * Displays real-time technical metrics, pipeline flow visualization, and diagnostic data
 */
@Composable
fun TechnicalPipelineScreen(
    cameraManager: CameraXManager,
    exportManager: GifExportManager,
    onViewCube: (File) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    
    // Pipeline state management
    var pipelineState by remember { mutableStateOf(PipelineState.IDLE) }
    var currentMilestone by remember { mutableStateOf(0) }
    var milestoneProgress by remember { mutableStateOf(0f) }
    
    // Technical metrics
    var captureMetrics by remember { mutableStateOf(CaptureMetrics()) }
    var processingMetrics by remember { mutableStateOf(ProcessingMetrics()) }
    var exportMetrics by remember { mutableStateOf(ExportMetrics()) }
    
    // Frame data
    val capturedFrames = remember { mutableListOf<ByteArray>() }
    var currentFrame by remember { mutableStateOf<ByteArray?>(null) }
    var gifFile by remember { mutableStateOf<File?>(null) }
    
    // Histogram data for live monitoring
    var histogramData by remember { mutableStateOf(HistogramData()) }
    
    // Camera preview
    val previewView = remember { PreviewView(context) }
    
    // Initialize camera with technical callbacks
    LaunchedEffect(cameraManager) {
        cameraManager.setPreviewView(previewView)
        cameraManager.setupCamera(lifecycleOwner) { bitmap ->
            // Not used in technical mode
        }
        
        // Technical RGBA callback with metrics
        cameraManager.setFrameCallback { rgba, width, height ->
            if (pipelineState == PipelineState.M1_CAPTURING && capturedFrames.size < 81) {
                capturedFrames.add(rgba.clone())
                currentFrame = rgba
                
                // Update capture metrics
                captureMetrics = captureMetrics.copy(
                    framesCapture = capturedFrames.size,
                    captureRate = calculateFrameRate(captureMetrics.lastFrameTime),
                    lastFrameTime = System.currentTimeMillis()
                )
                
                // Calculate histogram for current frame
                histogramData = calculateHistogram(rgba, width, height)
                
                milestoneProgress = capturedFrames.size / 81f
                
                Log.d("TechnicalPipeline", "M1_CAPTURE frame=${capturedFrames.size}/81 fps=${captureMetrics.captureRate}")
            }
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Camera Preview Layer
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )
        
        // Technical Overlay UI
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.3f))
        ) {
            // Top Technical Header
            TechnicalHeader(
                pipelineState = pipelineState,
                currentMilestone = currentMilestone,
                metrics = when(currentMilestone) {
                    1 -> captureMetrics
                    2 -> processingMetrics
                    3 -> exportMetrics
                    else -> captureMetrics
                }
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Pipeline Visualization
            PipelineVisualization(
                currentMilestone = currentMilestone,
                milestoneProgress = milestoneProgress,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            
            // Live Histogram/Waveform Monitor
            if (pipelineState == PipelineState.M1_CAPTURING && histogramData.hasData) {
                HistogramMonitor(
                    data = histogramData,
                    modifier = Modifier
                        .height(120.dp)
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            }
            
            // Technical Metrics Dashboard
            MetricsDashboard(
                captureMetrics = captureMetrics,
                processingMetrics = processingMetrics,
                exportMetrics = exportMetrics,
                currentMilestone = currentMilestone,
                modifier = Modifier.padding(16.dp)
            )
            
            // Control Panel
            ControlPanel(
                pipelineState = pipelineState,
                onExecutePipeline = {
                    scope.launch {
                        executePipeline(
                            capturedFrames = capturedFrames,
                            context = context,
                            onStateChange = { state -> pipelineState = state },
                            onMilestoneChange = { milestone -> currentMilestone = milestone },
                            onProgressChange = { progress -> milestoneProgress = progress },
                            onMetricsUpdate = { capture, processing, export ->
                                captureMetrics = capture
                                processingMetrics = processing
                                exportMetrics = export
                            },
                            onGifCreated = { file -> 
                                gifFile = file
                                pipelineState = PipelineState.COMPLETE
                            }
                        )
                    }
                },
                onReset = {
                    pipelineState = PipelineState.IDLE
                    currentMilestone = 0
                    milestoneProgress = 0f
                    capturedFrames.clear()
                    captureMetrics = CaptureMetrics()
                    processingMetrics = ProcessingMetrics()
                    exportMetrics = ExportMetrics()
                    gifFile = null
                },
                gifFile = gifFile,
                exportManager = exportManager,
                modifier = Modifier.padding(16.dp)
            )
        }
        
        // GIF Preview Overlay (when complete)
        if (pipelineState == PipelineState.COMPLETE && gifFile != null) {
            TechnicalGifPreview(
                gifFile = gifFile!!,
                metrics = exportMetrics,
                onViewCube = { onViewCube(gifFile!!) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            )
        }
    }
}

@Composable
private fun TechnicalHeader(
    pipelineState: PipelineState,
    currentMilestone: Int,
    metrics: Any
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Black.copy(alpha = 0.8f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Pipeline State
                Text(
                    text = pipelineState.name.replace('_', ' '),
                    color = when(pipelineState) {
                        PipelineState.IDLE -> Color.Gray
                        PipelineState.M1_CAPTURING -> MatrixGreen
                        PipelineState.M2_PROCESSING -> ProcessingOrange
                        PipelineState.M3_ENCODING -> Color(0xFF2196F3)
                        PipelineState.COMPLETE -> SuccessGreen
                        PipelineState.ERROR -> ErrorRed
                    },
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                
                // Current Milestone
                if (currentMilestone > 0) {
                    Text(
                        text = "M$currentMilestone",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            
            // Technical readout based on current operation
            when(metrics) {
                is CaptureMetrics -> {
                    Text(
                        text = "CAPTURE: ${metrics.framesCapture}/81 @ ${metrics.captureRate}fps | 729×729 RGBA8888",
                        color = Color(0xFF00FF00).copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                is ProcessingMetrics -> {
                    Text(
                        text = "PROCESS: ${metrics.framesProcessed}/81 | Bilinear 729→81 | ${metrics.avgProcessingTime}ms/frame",
                        color = Color(0xFFFF9800).copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                is ExportMetrics -> {
                    Text(
                        text = "EXPORT: NeuQuant+LZW | ${metrics.paletteSize} colors | ${metrics.fileSize}KB",
                        color = Color(0xFF2196F3).copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun PipelineVisualization(
    currentMilestone: Int,
    milestoneProgress: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pipeline")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // M1: Capture
        MilestoneNode(
            label = "M1",
            subtitle = "CAPTURE",
            isActive = currentMilestone == 1,
            isComplete = currentMilestone > 1,
            progress = if (currentMilestone == 1) milestoneProgress else if (currentMilestone > 1) 1f else 0f,
            color = MatrixGreen,
            pulseAlpha = if (currentMilestone == 1) pulseAlpha else 1f
        )
        
        // Connection Line
        PipelineConnection(
            isActive = currentMilestone >= 2,
            progress = if (currentMilestone == 2) milestoneProgress else if (currentMilestone > 2) 1f else 0f
        )
        
        // M2: Process
        MilestoneNode(
            label = "M2",
            subtitle = "DOWNSCALE",
            isActive = currentMilestone == 2,
            isComplete = currentMilestone > 2,
            progress = if (currentMilestone == 2) milestoneProgress else if (currentMilestone > 2) 1f else 0f,
            color = ProcessingOrange,
            pulseAlpha = if (currentMilestone == 2) pulseAlpha else 1f
        )
        
        // Connection Line
        PipelineConnection(
            isActive = currentMilestone >= 3,
            progress = if (currentMilestone == 3) milestoneProgress else if (currentMilestone > 3) 1f else 0f
        )
        
        // M3: Export
        MilestoneNode(
            label = "M3",
            subtitle = "ENCODE",
            isActive = currentMilestone == 3,
            isComplete = currentMilestone > 3,
            progress = if (currentMilestone == 3) milestoneProgress else if (currentMilestone > 3) 1f else 0f,
            color = Color(0xFF2196F3),
            pulseAlpha = if (currentMilestone == 3) pulseAlpha else 1f
        )
    }
}

@Composable
private fun MilestoneNode(
    label: String,
    subtitle: String,
    isActive: Boolean,
    isComplete: Boolean,
    progress: Float,
    color: Color,
    pulseAlpha: Float
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp)
        ) {
            // Progress ring
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 3.dp.toPx()
                
                // Background ring
                drawCircle(
                    color = color.copy(alpha = 0.2f),
                    style = Stroke(strokeWidth)
                )
                
                // Progress arc
                if (progress > 0) {
                    drawArc(
                        color = color.copy(alpha = if (isActive) pulseAlpha else 1f),
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        style = Stroke(strokeWidth)
                    )
                }
                
                // Center fill
                if (isComplete) {
                    drawCircle(
                        color = color.copy(alpha = 0.8f),
                        radius = size.minDimension / 3
                    )
                }
            }
            
            // Label
            Text(
                text = label,
                color = if (isActive || isComplete) color else Color.Gray,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
        
        Text(
            text = subtitle,
            color = if (isActive || isComplete) color.copy(alpha = 0.8f) else Color.Gray.copy(alpha = 0.5f),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun PipelineConnection(
    isActive: Boolean,
    progress: Float
) {
    Box(
        modifier = Modifier
            .width(40.dp)
            .height(2.dp)
    ) {
        // Background line
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Gray.copy(alpha = 0.2f))
        )
        
        // Progress line
        if (isActive) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                MatrixGreen,
                                ProcessingOrange
                            )
                        )
                    )
            )
        }
    }
}

@Composable
private fun HistogramMonitor(
    data: HistogramData,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.8f),
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.3f))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            val width = size.width
            val height = size.height
            val barWidth = width / 256f
            
            // Draw RGB histograms overlaid
            data.red.forEachIndexed { index, value ->
                val barHeight = (value * height).coerceAtMost(height)
                
                // Red channel
                drawRect(
                    color = Color.Red.copy(alpha = 0.5f),
                    topLeft = Offset(index * barWidth, height - barHeight),
                    size = Size(barWidth, barHeight)
                )
            }
            
            data.green.forEachIndexed { index, value ->
                val barHeight = (value * height).coerceAtMost(height)
                
                // Green channel
                drawRect(
                    color = Color.Green.copy(alpha = 0.5f),
                    topLeft = Offset(index * barWidth, height - barHeight),
                    size = Size(barWidth, barHeight)
                )
            }
            
            data.blue.forEachIndexed { index, value ->
                val barHeight = (value * height).coerceAtMost(height)
                
                // Blue channel
                drawRect(
                    color = Color.Blue.copy(alpha = 0.5f),
                    topLeft = Offset(index * barWidth, height - barHeight),
                    size = Size(barWidth, barHeight)
                )
            }
            
            // Grid lines
            drawLine(
                color = Color.Gray.copy(alpha = 0.3f),
                start = Offset(0f, height * 0.5f),
                end = Offset(width, height * 0.5f),
                strokeWidth = 0.5f
            )
        }
    }
}

@Composable
private fun MetricsDashboard(
    captureMetrics: CaptureMetrics,
    processingMetrics: ProcessingMetrics,
    exportMetrics: ExportMetrics,
    currentMilestone: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Black.copy(alpha = 0.8f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Title
            Text(
                text = "TECHNICAL METRICS",
                color = Color.White,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Metrics Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Input Metrics
                Column {
                    MetricLabel("INPUT")
                    MetricValue("729×729", Color.Gray)
                    MetricValue("RGBA8888", Color.Gray)
                    MetricValue("2.1MB/frame", Color.Gray)
                }
                
                // Processing Metrics
                Column {
                    MetricLabel("PROCESS")
                    MetricValue("Bilinear", if (currentMilestone >= 2) ProcessingOrange else Color.Gray)
                    MetricValue("81×81 out", if (currentMilestone >= 2) ProcessingOrange else Color.Gray)
                    MetricValue("${processingMetrics.avgProcessingTime}ms", if (currentMilestone >= 2) ProcessingOrange else Color.Gray)
                }
                
                // Output Metrics
                Column {
                    MetricLabel("OUTPUT")
                    MetricValue("GIF89a", if (currentMilestone >= 3) Color(0xFF2196F3) else Color.Gray)
                    MetricValue("${exportMetrics.paletteSize} colors", if (currentMilestone >= 3) Color(0xFF2196F3) else Color.Gray)
                    MetricValue("${exportMetrics.fileSize}KB", if (currentMilestone >= 3) Color(0xFF2196F3) else Color.Gray)
                }
            }
            
            // Performance Stats
            if (currentMilestone > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider(color = Color.Gray.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Text(
                        text = "FPS: ${captureMetrics.captureRate}",
                        color = MatrixGreen.copy(alpha = 0.8f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "CPU: ${processingMetrics.cpuUsage}%",
                        color = ProcessingOrange.copy(alpha = 0.8f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "MEM: ${processingMetrics.memoryUsage}MB",
                        color = Color(0xFF2196F3).copy(alpha = 0.8f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricLabel(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.6f),
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun MetricValue(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace
    )
}

@Composable
private fun ControlPanel(
    pipelineState: PipelineState,
    onExecutePipeline: () -> Unit,
    onReset: () -> Unit,
    gifFile: File?,
    exportManager: GifExportManager,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (pipelineState) {
            PipelineState.IDLE -> {
                // Execute Pipeline Button
                Button(
                    onClick = onExecutePipeline,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MatrixGreen.copy(alpha = 0.9f)
                    ),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Execute",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "EXECUTE PIPELINE",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            PipelineState.M1_CAPTURING,
            PipelineState.M2_PROCESSING,
            PipelineState.M3_ENCODING -> {
                // Processing Indicator
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = when(pipelineState) {
                        PipelineState.M1_CAPTURING -> MatrixGreen
                        PipelineState.M2_PROCESSING -> ProcessingOrange
                        PipelineState.M3_ENCODING -> Color(0xFF2196F3)
                        else -> Color.White
                    },
                    strokeWidth = 2.dp
                )
            }
            
            PipelineState.COMPLETE -> {
                // Action Buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // View Button
                    OutlinedButton(
                        onClick = { 
                            gifFile?.let { exportManager.shareGif(it) }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MatrixGreen
                        ),
                        border = BorderStroke(1.dp, MatrixGreen.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Icon(Icons.Default.Visibility, contentDescription = "View", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("VIEW", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                    
                    // Export Button
                    OutlinedButton(
                        onClick = { 
                            gifFile?.let { exportManager.exportToDocuments(it) }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = ProcessingOrange
                        ),
                        border = BorderStroke(1.dp, ProcessingOrange.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Icon(Icons.Default.SaveAlt, contentDescription = "Export", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("EXPORT", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                    
                    // Reset Button
                    OutlinedButton(
                        onClick = onReset,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.Gray
                        ),
                        border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("RESET", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }
            
            PipelineState.ERROR -> {
                Button(
                    onClick = onReset,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ErrorRed.copy(alpha = 0.9f)
                    ),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Icon(Icons.Default.Warning, contentDescription = "Error", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("RESET PIPELINE", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun TechnicalGifPreview(
    gifFile: File,
    metrics: ExportMetrics,
    onViewCube: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }
    
    Card(
        modifier = modifier.size(180.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Black.copy(alpha = 0.9f)
        ),
        border = BorderStroke(1.dp, MatrixGreen.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(4.dp)
    ) {
        Column {
            // GIF Preview
            AsyncImage(
                model = gifFile,
                contentDescription = "GIF Output",
                imageLoader = imageLoader,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
            
            // Technical Info
            Surface(
                color = Color.Black,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(8.dp)
                ) {
                    Text(
                        text = "OUTPUT: 81×81",
                        color = MatrixGreen,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "${metrics.fileSize}KB | ${metrics.paletteSize} colors",
                        color = Color.Gray,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "24fps | ∞ loop",
                        color = Color.Gray,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    // Cube Visualization Button
                    TextButton(
                        onClick = onViewCube,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MatrixGreen
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.ThreeDRotation,
                            contentDescription = "3D Cube",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "3D CUBE",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// Pipeline execution function with technical metrics
private suspend fun executePipeline(
    capturedFrames: MutableList<ByteArray>,
    context: android.content.Context,
    onStateChange: (PipelineState) -> Unit,
    onMilestoneChange: (Int) -> Unit,
    onProgressChange: (Float) -> Unit,
    onMetricsUpdate: (CaptureMetrics, ProcessingMetrics, ExportMetrics) -> Unit,
    onGifCreated: (File) -> Unit
) = withContext(Dispatchers.IO) {
    try {
        val startTime = System.currentTimeMillis()
        capturedFrames.clear()
        
        // Initialize metrics
        var captureMetrics = CaptureMetrics()
        var processingMetrics = ProcessingMetrics()
        var exportMetrics = ExportMetrics()
        
        // M1: CAPTURE - 81 frames at 729×729 RGBA8888
        onStateChange(PipelineState.M1_CAPTURING)
        onMilestoneChange(1)
        onProgressChange(0f)
        
        val m1Start = System.currentTimeMillis()
        
        // Wait for frames to be captured via camera callback
        while (capturedFrames.size < 81) {
            delay(50)
            val progress = capturedFrames.size / 81f
            onProgressChange(progress)
            
            captureMetrics = captureMetrics.copy(
                framesCapture = capturedFrames.size,
                totalCaptureTime = System.currentTimeMillis() - m1Start
            )
            onMetricsUpdate(captureMetrics, processingMetrics, exportMetrics)
        }
        
        Log.i("TechnicalPipeline", "M1_COMPLETE: 81 frames @ 729×729 in ${System.currentTimeMillis() - m1Start}ms")
        
        // M2: PROCESS - Neural downscale to 81×81
        onStateChange(PipelineState.M2_PROCESSING)
        onMilestoneChange(2)
        onProgressChange(0f)
        
        val m2Start = System.currentTimeMillis()
        val m2Processor = M2Processor()
        val downscaledFrames = mutableListOf<ByteArray>()
        
        capturedFrames.forEachIndexed { index, frame ->
            val processStart = System.currentTimeMillis()
            val downscaled = m2Processor.downsize729To81Cpu(frame)
            downscaledFrames.add(downscaled)
            
            val processTime = System.currentTimeMillis() - processStart
            processingMetrics = processingMetrics.copy(
                framesProcessed = index + 1,
                avgProcessingTime = ((processingMetrics.avgProcessingTime * index) + processTime) / (index + 1),
                totalProcessingTime = System.currentTimeMillis() - m2Start
            )
            
            val progress = (index + 1) / 81f
            onProgressChange(progress)
            onMetricsUpdate(captureMetrics, processingMetrics, exportMetrics)
        }
        
        Log.i("TechnicalPipeline", "M2_COMPLETE: 81×81 downscale in ${System.currentTimeMillis() - m2Start}ms")
        
        // M3: ENCODE - NeuQuant + GIF89a
        onStateChange(PipelineState.M3_ENCODING)
        onMilestoneChange(3)
        onProgressChange(0.5f)
        
        val m3Start = System.currentTimeMillis()
        val outputDir = File(context.getExternalFilesDir(null), "technical_output")
        outputDir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        
        val m3Processor = M3Processor()
        val gifResult = m3Processor.exportGif89aFromRgba(
            rgbaFrames = downscaledFrames,
            outputDir = outputDir,
            baseName = "technical_$timestamp"
        )
        
        val gifFile = File(outputDir, "technical_$timestamp.gif")
        
        exportMetrics = exportMetrics.copy(
            fileSize = (gifFile.length() / 1024).toInt(),
            encodingTime = System.currentTimeMillis() - m3Start,
            paletteSize = 256, // NeuQuant always produces 256 colors
            totalPipelineTime = System.currentTimeMillis() - startTime
        )
        
        onProgressChange(1f)
        onMetricsUpdate(captureMetrics, processingMetrics, exportMetrics)
        
        Log.i("TechnicalPipeline", "M3_COMPLETE: GIF encoded in ${System.currentTimeMillis() - m3Start}ms | ${exportMetrics.fileSize}KB")
        
        // Pipeline complete
        onGifCreated(gifFile)
        Log.i("TechnicalPipeline", "PIPELINE_COMPLETE: Total time ${exportMetrics.totalPipelineTime}ms")
        
    } catch (e: Exception) {
        Log.e("TechnicalPipeline", "Pipeline error", e)
        onStateChange(PipelineState.ERROR)
    }
}

// Helper functions
private fun calculateFrameRate(lastFrameTime: Long): Int {
    val timeDiff = System.currentTimeMillis() - lastFrameTime
    return if (timeDiff > 0) (1000 / timeDiff).toInt() else 0
}

private fun calculateHistogram(rgba: ByteArray, width: Int, height: Int): HistogramData {
    val redHist = FloatArray(256)
    val greenHist = FloatArray(256)
    val blueHist = FloatArray(256)
    
    val pixelCount = width * height
    
    for (i in 0 until pixelCount) {
        val idx = i * 4
        val r = rgba[idx].toInt() and 0xFF
        val g = rgba[idx + 1].toInt() and 0xFF
        val b = rgba[idx + 2].toInt() and 0xFF
        
        redHist[r]++
        greenHist[g]++
        blueHist[b]++
    }
    
    // Normalize
    val maxCount = maxOf(redHist.maxOrNull() ?: 1f, greenHist.maxOrNull() ?: 1f, blueHist.maxOrNull() ?: 1f)
    
    for (i in 0..255) {
        redHist[i] = redHist[i] / maxCount
        greenHist[i] = greenHist[i] / maxCount
        blueHist[i] = blueHist[i] / maxCount
    }
    
    return HistogramData(
        red = redHist,
        green = greenHist,
        blue = blueHist,
        hasData = true
    )
}

// Data classes
enum class PipelineState {
    IDLE,
    M1_CAPTURING,
    M2_PROCESSING,
    M3_ENCODING,
    COMPLETE,
    ERROR
}

data class CaptureMetrics(
    val framesCapture: Int = 0,
    val captureRate: Int = 0,
    val lastFrameTime: Long = System.currentTimeMillis(),
    val totalCaptureTime: Long = 0
)

data class ProcessingMetrics(
    val framesProcessed: Int = 0,
    val avgProcessingTime: Long = 0,
    val totalProcessingTime: Long = 0,
    val cpuUsage: Int = 0,
    val memoryUsage: Int = 0
)

data class ExportMetrics(
    val fileSize: Int = 0,
    val paletteSize: Int = 256,
    val encodingTime: Long = 0,
    val totalPipelineTime: Long = 0
)

data class HistogramData(
    val red: FloatArray = FloatArray(256),
    val green: FloatArray = FloatArray(256),
    val blue: FloatArray = FloatArray(256),
    val hasData: Boolean = false
)