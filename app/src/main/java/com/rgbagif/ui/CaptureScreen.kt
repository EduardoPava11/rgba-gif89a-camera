package com.rgbagif.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rgbagif.camera.CameraXManager
import com.rgbagif.ui.components.SquarePreview
import com.rgbagif.ui.state.CaptureViewModel
import com.rgbagif.ui.state.CaptureUiState
import com.rgbagif.ui.theme.*

/**
 * Main capture screen with camera preview and controls
 */
@Composable
fun CaptureScreen(
    cameraManager: CameraXManager,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = cameraManager.context
    val viewModel = remember { CaptureViewModel(context) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    // Initialize camera with RGBA frame callback
    LaunchedEffect(cameraManager) {
        viewModel.initialize()
        cameraManager.setupCamera(lifecycleOwner) { bitmap ->
            if (uiState.isCapturing) {
                viewModel.processFrame(bitmap)
            }
        }
        
        // Also set up RGBA callback
        cameraManager.setFrameCallback { rgbaBytes, width, height ->
            if (uiState.isCapturing) {
                // This will be handled by the frame callback in processFrame
            }
        }
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NeutralDark),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top status bar
        TopStatusBar(uiState = uiState)
        
        // Camera preview (square 729×729)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            SquarePreview(cameraManager = cameraManager)
        }
        
        // Bottom controls
        BottomControls(
            isCapturing = uiState.isCapturing,
            onStartCapture = { 
                val outputDir = java.io.File(context.getExternalFilesDir(null), "captures")
                outputDir.mkdirs()
                viewModel.startCapture(outputDir) 
            },
            onStopCapture = { viewModel.stopCapture() }
        )
    }
    
    // Error display
    uiState.error?.let { error ->
        Snackbar(
            modifier = Modifier.padding(16.dp),
            action = {
                TextButton(onClick = { viewModel.clearError() }) {
                    Text("Dismiss")
                }
            }
        ) {
            Text(error)
        }
    }
}

@Composable
private fun TopStatusBar(uiState: CaptureUiState) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        color = NeutralDark
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Frames: ${uiState.framesCaptured}/${uiState.totalFrames}",
                style = TechnicalBody,
                color = ProcessingOrange
            )
            Text(
                text = "FPS: %.1f".format(uiState.fps),
                style = TechnicalBody,
                color = MatrixGreen
            )
        }
    }
}

@Composable
private fun BottomControls(
    isCapturing: Boolean,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .background(NeutralDark),
        contentAlignment = Alignment.Center
    ) {
        Button(
            onClick = {
                if (isCapturing) onStopCapture() else onStartCapture()
            },
            modifier = Modifier.size(80.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isCapturing) ErrorRed else ProcessingOrange
            )
        ) {
            Text(
                text = if (isCapturing) "STOP" else "START",
                style = TechnicalBody
            )
        }
    }
}