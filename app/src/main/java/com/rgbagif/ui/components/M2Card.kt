package com.rgbagif.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rgbagif.ui.theme.MatrixGreen
import com.rgbagif.ui.theme.SuccessGreen

/**
 * M2 Processing Card - shows neural downsize progress
 */
@Composable
fun M2Card(
    isProcessing: Boolean,
    currentFrame: Int,
    totalFrames: Int,
    averageTimeMs: Long,
    onStartM2: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Black.copy(alpha = 0.9f)
        ),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "M2: Neural Downsize",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MatrixGreen
                )
                
                if (averageTimeMs > 0) {
                    Text(
                        text = "${averageTimeMs}ms/frame",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Subtitle
            Text(
                text = "729×729 → 81×81 via 9×9 NN",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Progress bar
            if (totalFrames > 0) {
                LinearProgressIndicator(
                    progress = { currentFrame.toFloat() / totalFrames },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = if (currentFrame == totalFrames) SuccessGreen else MatrixGreen,
                    trackColor = Color.Gray.copy(alpha = 0.3f)
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                // Progress text
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "$currentFrame / $totalFrames frames",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                    
                    if (currentFrame == totalFrames && totalFrames > 0) {
                        Text(
                            text = "✅ Complete",
                            style = MaterialTheme.typography.labelMedium,
                            color = SuccessGreen
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Action button
            Button(
                onClick = onStartM2,
                enabled = !isProcessing && totalFrames == 0,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MatrixGreen,
                    disabledContainerColor = Color.Gray.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = when {
                        isProcessing -> "Processing..."
                        currentFrame == totalFrames && totalFrames > 0 -> "Completed"
                        else -> "Start M2 Processing"
                    },
                    color = if (isProcessing || (currentFrame == totalFrames && totalFrames > 0)) 
                        Color.Gray else Color.Black
                )
            }
        }
    }
}

/**
 * Compact M2 status indicator
 */
@Composable
fun M2StatusBadge(
    currentFrame: Int,
    totalFrames: Int,
    modifier: Modifier = Modifier
) {
    if (totalFrames > 0) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(16.dp),
            color = if (currentFrame == totalFrames) 
                SuccessGreen.copy(alpha = 0.2f) 
                else MatrixGreen.copy(alpha = 0.2f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "M2",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (currentFrame == totalFrames) SuccessGreen else MatrixGreen
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "$currentFrame/$totalFrames",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }
        }
    }
}