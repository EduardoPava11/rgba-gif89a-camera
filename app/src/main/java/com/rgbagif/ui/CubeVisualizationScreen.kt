package com.rgbagif.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.velocity.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.*

/**
 * 3D Cube Visualization Screen
 * 
 * Displays quantized frames from the GIF as a rotating 3D cube.
 * - Front face shows frames at 1/24 second intervals
 * - User can rotate and spin the cube with momentum
 * - Small GIF plays in the corner
 */
@Composable
fun CubeVisualizationScreen(
    quantizedFrames: List<ImageBitmap>,
    gifFile: File?,
    onBack: () -> Unit
) {
    var rotationX by remember { mutableStateOf(0f) }
    var rotationY by remember { mutableStateOf(0f) }
    var velocityX by remember { mutableStateOf(0f) }
    var velocityY by remember { mutableStateOf(0f) }
    
    // Frame animation for cube face
    var currentFrameIndex by remember { mutableStateOf(0) }
    
    // Momentum animation
    val infiniteTransition = rememberInfiniteTransition(label = "momentum")
    
    // Update rotation based on momentum
    LaunchedEffect(velocityX, velocityY) {
        while (abs(velocityX) > 0.1f || abs(velocityY) > 0.1f) {
            delay(16) // 60 FPS
            rotationX += velocityX
            rotationY += velocityY
            
            // Apply friction
            velocityX *= 0.95f
            velocityY *= 0.95f
        }
    }
    
    // Animate frame changes at 24 FPS
    LaunchedEffect(quantizedFrames) {
        if (quantizedFrames.isNotEmpty()) {
            while (true) {
                delay(1000L / 24) // 24 FPS
                currentFrameIndex = (currentFrameIndex + 1) % quantizedFrames.size
            }
        }
    }
    
    val velocityTracker = remember { VelocityTracker() }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Main 3D Cube
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            val velocity = velocityTracker.calculateVelocity()
                            velocityX = velocity.x * 0.01f
                            velocityY = -velocity.y * 0.01f
                            velocityTracker.resetTracking()
                        }
                    ) { change, _ ->
                        velocityTracker.addPosition(
                            change.uptimeMillis,
                            change.position
                        )
                        
                        rotationY += change.position.x - change.previousPosition.x
                        rotationX -= change.position.y - change.previousPosition.y
                    }
                }
        ) {
            if (quantizedFrames.isNotEmpty()) {
                drawCube(
                    frames = quantizedFrames,
                    currentFrameIndex = currentFrameIndex,
                    rotationX = rotationX,
                    rotationY = rotationY,
                    size = minOf(size.width, size.height) * 0.6f
                )
            }
        }
        
        // Control Panel
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .background(
                    Color.Black.copy(alpha = 0.7f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                )
                .padding(12.dp)
        ) {
            Text(
                text = "3D CUBE VISUALIZATION",
                color = Color.Green,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Frame: ${currentFrameIndex + 1}/${quantizedFrames.size}",
                color = Color.Green.copy(alpha = 0.8f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            
            Text(
                text = "Rotation: (${rotationX.toInt()}°, ${rotationY.toInt()}°)",
                color = Color.Green.copy(alpha = 0.8f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            
            if (abs(velocityX) > 0.1f || abs(velocityY) > 0.1f) {
                Text(
                    text = "MOMENTUM ACTIVE",
                    color = Color.Yellow,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        
        // Small GIF preview in corner
        gifFile?.let { file ->
            Card(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(120.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = rememberAsyncImagePainter(file),
                        contentDescription = "GIF Preview",
                        modifier = Modifier.fillMaxSize()
                    )
                    
                    // Label
                    Text(
                        text = "GIF",
                        color = Color.Green,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
        
        // Back button
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Text(
                text = "✕",
                color = Color.Green,
                fontSize = 24.sp
            )
        }
        
        // Instructions
        Text(
            text = "Drag to rotate • Release for momentum",
            color = Color.Green.copy(alpha = 0.5f),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
}

/**
 * Draws a 3D cube with frames mapped to its faces
 */
private fun DrawScope.drawCube(
    frames: List<ImageBitmap>,
    currentFrameIndex: Int,
    rotationX: Float,
    rotationY: Float,
    size: Float
) {
    val centerX = this.size.center.x
    val centerY = this.size.center.y
    val halfSize = size / 2
    
    // Define cube vertices in 3D space
    val vertices = listOf(
        Triple(-halfSize, -halfSize, -halfSize), // 0: back-bottom-left
        Triple(halfSize, -halfSize, -halfSize),  // 1: back-bottom-right
        Triple(halfSize, halfSize, -halfSize),   // 2: back-top-right
        Triple(-halfSize, halfSize, -halfSize),  // 3: back-top-left
        Triple(-halfSize, -halfSize, halfSize),  // 4: front-bottom-left
        Triple(halfSize, -halfSize, halfSize),   // 5: front-bottom-right
        Triple(halfSize, halfSize, halfSize),    // 6: front-top-right
        Triple(-halfSize, halfSize, halfSize)    // 7: front-top-left
    )
    
    // Apply rotation transformations
    val transformedVertices = vertices.map { (x, y, z) ->
        // Rotate around Y axis
        val cosY = cos(Math.toRadians(rotationY.toDouble())).toFloat()
        val sinY = sin(Math.toRadians(rotationY.toDouble())).toFloat()
        val x1 = x * cosY - z * sinY
        val z1 = x * sinY + z * cosY
        
        // Rotate around X axis
        val cosX = cos(Math.toRadians(rotationX.toDouble())).toFloat()
        val sinX = sin(Math.toRadians(rotationX.toDouble())).toFloat()
        val y1 = y * cosX - z1 * sinX
        val z2 = y * sinX + z1 * cosX
        
        // Apply perspective projection
        val perspective = 1000f
        val scale = perspective / (perspective + z2)
        
        Offset(
            centerX + x1 * scale,
            centerY + y1 * scale
        ) to z2
    }
    
    // Define cube faces (indices into vertices array)
    val faces = listOf(
        listOf(4, 5, 6, 7) to currentFrameIndex,     // Front face - animated frame
        listOf(1, 0, 3, 2) to 0,                     // Back face
        listOf(0, 4, 7, 3) to minOf(1, frames.size - 1),  // Left face
        listOf(5, 1, 2, 6) to minOf(2, frames.size - 1),  // Right face
        listOf(7, 6, 2, 3) to minOf(3, frames.size - 1),  // Top face
        listOf(0, 1, 5, 4) to minOf(4, frames.size - 1)   // Bottom face
    )
    
    // Sort faces by average Z depth (painter's algorithm)
    val sortedFaces = faces.sortedBy { (indices, _) ->
        indices.map { transformedVertices[it].second }.average()
    }
    
    // Draw faces
    sortedFaces.forEach { (indices, frameIndex) ->
        val points = indices.map { transformedVertices[it].first }
        
        // Draw face with frame texture if available
        if (frameIndex < frames.size) {
            drawFaceWithTexture(
                points = points,
                frame = frames[frameIndex],
                alpha = 0.9f
            )
        } else {
            // Fallback to colored face
            drawFace(
                points = points,
                color = Color.Green.copy(alpha = 0.3f)
            )
        }
        
        // Draw edges
        val edgeColor = Color.Green.copy(alpha = 0.8f)
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            points.forEach { point ->
                lineTo(point.x, point.y)
            }
            close()
        }
        drawPath(
            path = path,
            color = edgeColor,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
        )
    }
}

/**
 * Draws a face with solid color
 */
private fun DrawScope.drawFace(
    points: List<Offset>,
    color: Color
) {
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        points.forEach { point ->
            lineTo(point.x, point.y)
        }
        close()
    }
    drawPath(path = path, color = color)
}

/**
 * Draws a face with image texture
 * Note: This is a simplified version. Full texture mapping would require 
 * more complex image transformation.
 */
private fun DrawScope.drawFaceWithTexture(
    points: List<Offset>,
    frame: ImageBitmap,
    alpha: Float
) {
    // Calculate bounding box
    val minX = points.minOf { it.x }
    val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }
    val maxY = points.maxOf { it.y }
    
    val width = maxX - minX
    val height = maxY - minY
    
    // Draw the image scaled to fit the face
    // This is a simplification - proper 3D texture mapping would require
    // perspective-correct interpolation
    translate(minX, minY) {
        scale(
            scaleX = width / frame.width,
            scaleY = height / frame.height
        ) {
            drawImage(
                image = frame,
                alpha = alpha
            )
        }
    }
}