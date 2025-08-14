package com.rgbagif.ui.components

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rgbagif.ui.theme.*
import com.rgbagif.camera.AnalyzerStats
import kotlinx.coroutines.delay

// Define warning color
private val WarningOrange = Color(0xFFFF9800)

/**
 * Camera preview panel with live feed and optional analyzer stats
 */
@Composable
fun PreviewPanel(
    previewView: PreviewView,
    analyzerStats: AnalyzerStats? = null,
    showStats: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f) // Square aspect ratio
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 2.dp,
                color = MatrixGreen.copy(alpha = 0.3f),
                shape = RoundedCornerShape(12.dp)
            )
            .background(Color.Black)
    ) {
        // Camera preview
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        ) { view ->
            // Configure preview
            view.scaleType = PreviewView.ScaleType.FILL_CENTER
            view.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        
        // Analyzer stats overlay (dev mode)
        if (showStats && analyzerStats != null) {
            AnalyzerStatsOverlay(
                stats = analyzerStats,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            )
        }
    }
}

/**
 * Analyzer stats overlay for development/debugging
 */
@Composable
fun AnalyzerStatsOverlay(
    stats: AnalyzerStats,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = Color.Black.copy(alpha = 0.8f)
        ),
        shape = RoundedCornerShape(4.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp)
        ) {
            // FPS
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "FPS:",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = String.format("%.1f", stats.currentFps),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (stats.currentFps > 25) SuccessGreen else WarningOrange,
                    fontFamily = FontFamily.Monospace
                )
            }
            
            // Queue depth
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Queue:",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${stats.queueDepth}/${stats.maxQueueDepth}",
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        stats.queueDepth == 0 -> Color.Gray
                        stats.queueDepth < stats.maxQueueDepth - 1 -> SuccessGreen
                        else -> ErrorRed
                    },
                    fontFamily = FontFamily.Monospace
                )
            }
            
            // Frames processed
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Frames:",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stats.framesProcessed.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace
                )
            }
            
            // Dropped frames warning
            if (stats.framesDropped > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "⚠ Dropped:",
                        style = MaterialTheme.typography.labelSmall,
                        color = WarningOrange
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stats.framesDropped.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = WarningOrange,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            
            // Close miss warning
            if (stats.closeCallsMissed > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "❌ Close missed:",
                        style = MaterialTheme.typography.labelSmall,
                        color = ErrorRed
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stats.closeCallsMissed.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = ErrorRed,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

/**
 * Simple capture button for starting M1
 */
@Composable
fun CaptureButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MatrixGreen,
            disabledContainerColor = Color.Gray
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = "START CAPTURE",
            style = MaterialTheme.typography.labelLarge,
            color = Color.Black
        )
    }
}