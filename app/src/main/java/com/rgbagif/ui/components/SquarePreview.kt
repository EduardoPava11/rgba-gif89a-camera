package com.rgbagif.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import com.rgbagif.camera.CameraXManager

/**
 * Square camera preview that displays the 729×729 camera feed.
 * Uses CameraX PreviewView for optimal performance.
 */
@Composable
fun SquarePreview(
    cameraManager: CameraXManager,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f) // Force square aspect ratio
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { context ->
                PreviewView(context).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            modifier = Modifier.fillMaxSize()
        ) { previewView ->
            // Camera will be bound to this PreviewView in CameraXManager
            // Store reference for later binding
            cameraManager.setPreviewView(previewView)
        }
    }
}