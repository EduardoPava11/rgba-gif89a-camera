package com.rgbagif.preview

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import uniffi.m3gif.QuantizedCubeData
import kotlinx.coroutines.delay
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*

/**
 * Composable screen for WYSIWYG cube preview
 * 
 * Shows:
 * - GLSurfaceView with palette-based rendering
 * - Frame slider for manual navigation
 * - Play/pause button for animation
 * - Metrics display (palette stability, delta E)
 */
@Composable
fun CubePreviewScreen(
    cubeData: QuantizedCubeData?,
    onExportClick: () -> Unit,
    onBackClick: () -> Unit
) {
    var currentFrame by remember { mutableStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var glSurfaceView by remember { mutableStateOf<CubePreviewGLSurfaceView?>(null) }
    
    // Auto-play animation
    LaunchedEffect(isPlaying) {
        if (isPlaying && cubeData != null) {
            while (isPlaying) {
                delay(40) // 25 FPS
                currentFrame = (currentFrame + 1) % cubeData.indexedFrames.size
                glSurfaceView?.setFrame(currentFrame)
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Title
        Text(
            text = "WYSIWYG Cube Preview",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // GLSurfaceView
        Card(
            modifier = Modifier
                .aspectRatio(1f)
                .fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            AndroidView(
                factory = { context ->
                    CubePreviewGLSurfaceView(context).also { view ->
                        glSurfaceView = view
                        cubeData?.let { view.setCubeData(it) }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Frame control
        if (cubeData != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Frame:")
                Spacer(modifier = Modifier.width(8.dp))
                Slider(
                    value = currentFrame.toFloat(),
                    onValueChange = { value ->
                        currentFrame = value.toInt()
                        glSurfaceView?.setFrame(currentFrame)
                    },
                    valueRange = 0f..(cubeData.indexedFrames.size - 1).toFloat(),
                    steps = cubeData.indexedFrames.size - 2,
                    enabled = !isPlaying,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("${currentFrame + 1}/${cubeData.indexedFrames.size}")
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Play/Pause button
            Button(
                onClick = {
                    isPlaying = !isPlaying
                    if (isPlaying) {
                        glSurfaceView?.playAnimation()
                    } else {
                        glSurfaceView?.stopAnimation()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = if (isPlaying) {
                        androidx.compose.material.icons.Icons.Default.Pause
                    } else {
                        androidx.compose.material.icons.Icons.Default.PlayArrow
                    },
                    contentDescription = if (isPlaying) "Pause" else "Play"
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isPlaying) "Pause" else "Play Animation")
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Metrics
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "Quantization Metrics",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Palette Size:")
                        Text("${cubeData.globalPaletteRgb.size / 3} colors")
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Palette Stability:")
                        Text("${(cubeData.paletteStability * 100).toInt()}%")
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Mean ΔE:")
                        Text(String.format("%.2f", cubeData.meanDeltaE))
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("P95 ΔE:")
                        Text(String.format("%.2f", cubeData.p95DeltaE))
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Export button
            Button(
                onClick = onExportClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Save,
                    contentDescription = "Export"
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Export to GIF")
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Back button
        TextButton(
            onClick = onBackClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Default.ArrowBack,
                contentDescription = "Back"
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Back to Capture")
        }
    }
}