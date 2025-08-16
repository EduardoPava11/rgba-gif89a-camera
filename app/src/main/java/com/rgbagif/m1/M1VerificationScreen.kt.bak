package com.rgbagif.m1

import android.Manifest
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * M1 Verification Screen
 * Side-by-side comparison: CameraX Preview (left) vs M1 RGBA Bitmap (right)
 * Shows visual markers from Rust to prove RGBA integrity
 */
@Composable
fun M1VerificationScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    
    // Camera permission
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            // Handle permission denied
        }
    }
    
    // M1 Kit state
    val m1Kit = remember { M1VerificationKit(context) }
    var verificationBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var currentStats by remember { mutableStateOf<M1VerificationKit.M1Stats?>(null) }
    var cameraStarted by remember { mutableStateOf(false) }
    
    // Setup M1 kit callback
    LaunchedEffect(m1Kit) {
        m1Kit.onFrameProcessed = { bitmap, stats ->
            verificationBitmap = bitmap
            currentStats = stats
        }
    }
    
    // Request camera permission and start camera
    LaunchedEffect(Unit) {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }
    
    // Cleanup on dispose
    DisposableEffect(m1Kit) {
        onDispose {
            m1Kit.stop()
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onNavigateBack) {
                Text("← Back")
            }
            
            Text(
                text = "M1 RGBA Verification",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            
            Button(
                onClick = {
                    if (!cameraStarted) {
                        scope.launch {
                            try {
                                val previewView = PreviewView(context)
                                m1Kit.setupCamera(lifecycleOwner, previewView)
                                cameraStarted = true
                            } catch (e: Exception) {
                                // Handle error
                            }
                        }
                    }
                },
                enabled = !cameraStarted
            ) {
                Text(if (cameraStarted) "Running" else "Start")
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Side-by-side camera views
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Left panel: CameraX PreviewView
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
            ) {
                Column {
                    Text(
                        text = "CameraX Preview",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                    
                    AndroidView(
                        factory = { context ->
                            PreviewView(context).apply {
                                if (cameraStarted) {
                                    scope.launch {
                                        m1Kit.setupCamera(lifecycleOwner, this@apply)
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                    )
                }
            }
            
            // Right panel: M1 Bitmap with Rust markers
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
            ) {
                Column {
                    Text(
                        text = "M1 RGBA + Rust Markers",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                    
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        verificationBitmap?.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "M1 Verification Frame",
                                modifier = Modifier.fillMaxSize()
                            )
                        } ?: run {
                            Text(
                                text = if (cameraStarted) "Processing..." else "Start camera to begin",
                                color = Color.Gray,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Stats panel
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D))
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "M1 Verification Stats",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                currentStats?.let { stats ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        StatsRow("Frame Index:", "${stats.frameIndex}")
                        StatsRow("Non-Zero Ratio:", String.format("%.4f", stats.nzRatio))
                        StatsRow("Avg RGB:", "(${String.format("%.1f", stats.avgRGB.first)}, ${String.format("%.1f", stats.avgRGB.second)}, ${String.format("%.1f", stats.avgRGB.third)})")
                        StatsRow("Processing Time:", "${stats.processingTimeMs}ms")
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = "Signature:",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Text(
                            text = stats.signature,
                            color = Color(0xFFAAFFAA),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } ?: run {
                    Text(
                        text = "Waiting for first frame...",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Expected markers info
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A3A1A))
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "✅ Expected Visual Markers (Right Panel)",
                    color = Color(0xFF88FF88),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "• White border (1px around edges)\n• Red crosshair at center\n• Pure RGB squares: Red (top-left), Green (top-right), Blue (bottom-left)",
                    color = Color(0xFFAAFFAA),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun StatsRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp
        )
        Text(
            text = value,
            color = Color(0xFFAAFFAA),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
