package com.rgbagif.milestones

import android.Manifest
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.camera.view.PreviewView
import androidx.camera.view.PreviewView.ScaleType
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.BorderStroke
import com.rgbagif.camera.CameraXManager
import com.rgbagif.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Main milestone workflow screen showing the 3-phase process
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MilestoneWorkflowScreen(
    cameraManager: CameraXManager,
    onNavigateToM1Verification: () -> Unit = {},
    onNavigateToFrameBrowser: (String) -> Unit,
    onGifCreated: (java.io.File) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    
    // Camera permission state
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    
    // Create milestone manager
    val milestoneManager = remember { MilestoneManager(context, cameraManager, onGifCreated) }
    val progress by milestoneManager.progress.collectAsStateWithLifecycle()
    
    DisposableEffect(milestoneManager) {
        onDispose {
            milestoneManager.cleanup()
        }
    }
    
    // Camera preview reference
    val previewView = remember { PreviewView(context) }
    
    // Set up camera when permission is granted
    LaunchedEffect(cameraPermissionState.status) {
        if (cameraPermissionState.status.isGranted) {
            cameraManager.setPreviewView(previewView)
            cameraManager.setupCamera(lifecycleOwner) { bitmap ->
                // Frame callback will be wired to milestone manager
            }
            cameraManager.setFrameCallback { rgba, width, height ->
                // This will be consumed by MilestoneManager during M1
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NeutralDark)
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        // Header
        Text(
            text = "RGBA→GIF89a Workflow",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Text(
            text = "3-Milestone Pipeline",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 24.dp)
        )
        
        // Camera Preview Panel
        if (cameraPermissionState.status.isGranted) {
            PreviewPanel(previewView = previewView)
            
            // Show capture progress if capturing
            if (progress.state == MilestoneState.MILESTONE_1_CAPTURING) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        progress = { progress.currentFrame.toFloat() / progress.totalFrames.toFloat() },
                        modifier = Modifier.size(24.dp),
                        color = MatrixGreen,
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "${progress.currentFrame} / ${progress.totalFrames} frames",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White
                    )
                }
            }
            
            // Capture button when idle
            if (progress.state == MilestoneState.IDLE) {
                Spacer(modifier = Modifier.height(16.dp))
                CaptureButton(
                    onClick = {
                        scope.launch {
                            milestoneManager.startMilestone1()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                
                // M1 Verification button
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { onNavigateToM1Verification() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.BugReport,
                        contentDescription = "M1 Verification",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("M1 Verification")
                }
            }
        } else {
            PermissionRequestCard(
                onRequestPermission = {
                    cameraPermissionState.launchPermissionRequest()
                }
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Milestone Cards
        MilestoneCard(
            milestone = 1,
            title = "CBOR Capture",
            description = "Capture 81 frames at 729×729px",
            icon = Icons.Filled.Camera,
            state = when (progress.state) {
                MilestoneState.IDLE -> CardState.READY
                MilestoneState.MILESTONE_1_CAPTURING,
                MilestoneState.MILESTONE_1_GENERATING_PNG -> CardState.PROCESSING
                MilestoneState.MILESTONE_1_COMPLETE -> CardState.COMPLETE
                else -> if (progress.milestone > 1) CardState.COMPLETE else CardState.LOCKED
            },
            progress = if (progress.milestone == 1) progress else null,
            processingTime = if (progress.milestone >= 1 && progress.processingTimeMs > 0) 
                progress.processingTimeMs else null,
            onStart = {
                scope.launch {
                    milestoneManager.startMilestone1()
                }
            },
            onViewFrames = {
                milestoneManager.getSessionId()?.let { sessionId ->
                    onNavigateToFrameBrowser(sessionId)
                }
            }
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        MilestoneCard(
            milestone = 2,
            title = "Neural Downsize",
            description = "Go 9×9 NN to 81×81px",
            icon = Icons.Outlined.Compress,
            state = when (progress.state) {
                MilestoneState.MILESTONE_1_COMPLETE -> CardState.READY
                MilestoneState.MILESTONE_2_DOWNSIZING,
                MilestoneState.MILESTONE_2_GENERATING_PNG -> CardState.PROCESSING
                MilestoneState.MILESTONE_2_COMPLETE -> CardState.COMPLETE
                else -> if (progress.milestone > 2) CardState.COMPLETE else CardState.LOCKED
            },
            progress = if (progress.milestone == 2) progress else null,
            processingTime = if (progress.milestone >= 2 && progress.processingTimeMs > 0) 
                progress.processingTimeMs else null,
            onStart = {
                scope.launch {
                    milestoneManager.startMilestone2()
                }
            },
            onViewFrames = {
                milestoneManager.getSessionId()?.let { sessionId ->
                    onNavigateToFrameBrowser(sessionId)
                }
            }
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        MilestoneCard(
            milestone = 3,
            title = "GIF89a Export",
            description = "256-color quantization",
            icon = Icons.Filled.Image,
            state = when (progress.state) {
                MilestoneState.MILESTONE_2_COMPLETE -> CardState.READY
                MilestoneState.MILESTONE_3_QUANTIZING,
                MilestoneState.MILESTONE_3_GENERATING_CBOR,
                MilestoneState.MILESTONE_3_GENERATING_GIF -> CardState.PROCESSING
                MilestoneState.MILESTONE_3_COMPLETE -> CardState.COMPLETE
                else -> CardState.LOCKED
            },
            progress = if (progress.milestone == 3) progress else null,
            processingTime = if (progress.milestone >= 3 && progress.processingTimeMs > 0) 
                progress.processingTimeMs else null,
            onStart = {
                scope.launch {
                    milestoneManager.startMilestone3()
                }
            },
            onViewFrames = {
                // Show GIF result
                milestoneManager.getSessionId()?.let { sessionId ->
                    onNavigateToFrameBrowser(sessionId)
                }
            }
        )
        
        // Error state
        if (progress.state == MilestoneState.ERROR) {
            Spacer(modifier = Modifier.height(24.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = ErrorRed.copy(alpha = 0.1f)
                ),
                border = BorderStroke(1.dp, ErrorRed)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Error,
                            contentDescription = "Error",
                            tint = ErrorRed,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Error",
                            style = MaterialTheme.typography.titleMedium,
                            color = ErrorRed
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = progress.error ?: "Unknown error occurred",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { milestoneManager.reset() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ErrorRed
                        )
                    ) {
                        Text("Reset Workflow")
                    }
                }
            }
        }
        
        // Session info
        if (progress.state != MilestoneState.IDLE) {
            Spacer(modifier = Modifier.height(24.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = NeutralMedium
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Session Info",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "ID: ${milestoneManager.getSessionId()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                    milestoneManager.getSessionDirectory()?.let { dir ->
                        Text(
                            text = "Path: ${dir.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

enum class CardState {
    LOCKED, READY, PROCESSING, COMPLETE
}

@Composable
fun MilestoneCard(
    milestone: Int,
    title: String,
    description: String,
    icon: ImageVector,
    state: CardState,
    progress: MilestoneProgress? = null,
    processingTime: Long? = null,
    onStart: () -> Unit = {},
    onViewFrames: () -> Unit = {}
) {
    val borderColor = when (state) {
        CardState.LOCKED -> Color.Gray.copy(alpha = 0.3f)
        CardState.READY -> ProcessingOrange
        CardState.PROCESSING -> MatrixGreen
        CardState.COMPLETE -> SuccessGreen
    }
    
    val backgroundColor = when (state) {
        CardState.LOCKED -> NeutralMedium.copy(alpha = 0.5f)
        CardState.READY -> NeutralMedium
        CardState.PROCESSING -> MatrixGreen.copy(alpha = 0.1f)
        CardState.COMPLETE -> SuccessGreen.copy(alpha = 0.1f)
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        border = BorderStroke(2.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = borderColor,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "M$milestone: $title",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                }
                
                // Status indicator
                when (state) {
                    CardState.LOCKED -> {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = "Locked",
                            tint = Color.Gray,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    CardState.READY -> {
                        Button(
                            onClick = onStart,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ProcessingOrange
                            )
                        ) {
                            Text("Start")
                        }
                    }
                    CardState.PROCESSING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MatrixGreen,
                            strokeWidth = 2.dp
                        )
                    }
                    CardState.COMPLETE -> {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "Complete",
                            tint = SuccessGreen,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            
            // Progress details
            if (state == CardState.PROCESSING && progress != null) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = progress.currentFrame.toFloat() / progress.totalFrames,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = MatrixGreen,
                    trackColor = NeutralDark
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = progress.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White
                )
                Text(
                    text = "${progress.currentFrame} / ${progress.totalFrames} frames",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            
            // Timing display
            if (processingTime != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Timer,
                        contentDescription = "Time",
                        tint = Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${processingTime}ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            
            // View frames button
            if (state == CardState.COMPLETE) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onViewFrames,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = borderColor
                    ),
                    border = BorderStroke(1.dp, borderColor)
                ) {
                    Icon(
                        Icons.Filled.PhotoLibrary,
                        contentDescription = "View",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("View Frames")
                }
            }
        }
    }
}
/**
 * Camera preview panel with square aspect ratio
 */
@Composable
fun PreviewPanel(
    previewView: PreviewView,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f) // Square aspect ratio
            .semantics { 
                contentDescription = "Camera Preview"
            },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { 
                    previewView.apply { 
                        scaleType = ScaleType.FILL_CENTER
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            
            // Overlay frame guide
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        width = 2.dp,
                        color = Color.White.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(12.dp)
                    )
            )
        }
    }
}

/**
 * Permission request card
 */
@Composable
fun PermissionRequestCard(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NeutralMid)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Camera,
                contentDescription = "Camera",
                modifier = Modifier.size(64.dp),
                tint = Color.Gray
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Camera Permission Required",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "This app needs camera access to capture frames for GIF creation",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = MatrixGreen)
            ) {
                Text("Grant Permission")
            }
        }
    }
}

/**
 * Large, accessible capture button
 */
@Composable
fun CaptureButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MatrixGreen,
            contentColor = Color.Black
        ),
        shape = RoundedCornerShape(32.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Camera,
                contentDescription = "Start Capture",
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "START CAPTURE",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}