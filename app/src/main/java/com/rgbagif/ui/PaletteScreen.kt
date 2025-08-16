package com.rgbagif.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rgbagif.ui.theme.*

/**
 * Palette visualization screen showing the 256-color quantized palette
 * from QuantizedCubeData with quality metrics
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaletteScreen(
    quantizedCubeData: uniffi.m3gif.QuantizedCubeData?,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = "Palette",
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("GIF Color Palette")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { paddingValues ->
        if (quantizedCubeData == null) {
            // No palette data available
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Palette,
                        contentDescription = "No palette",
                        modifier = Modifier.size(64.dp),
                        tint = Color.Gray
                    )
                    Text(
                        text = "No palette data available",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.Gray
                    )
                    Text(
                        text = "Generate a GIF first to see the color palette",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Metrics Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Quantization Metrics",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            MetricItem(
                                label = "Colors",
                                value = "256",
                                color = MatrixGreen
                            )
                            MetricItem(
                                label = "Mean ΔE",
                                value = String.format("%.2f", quantizedCubeData.meanDeltaE),
                                color = ProcessingOrange
                            )
                            MetricItem(
                                label = "P95 ΔE",
                                value = String.format("%.2f", quantizedCubeData.p95DeltaE),
                                color = ErrorRed
                            )
                        }
                        
                        // Stability indicator
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Stability:",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            LinearProgressIndicator(
                                progress = { quantizedCubeData.paletteStability },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(8.dp),
                                color = when {
                                    quantizedCubeData.paletteStability > 0.8f -> MatrixGreen
                                    quantizedCubeData.paletteStability > 0.6f -> ProcessingOrange
                                    else -> ErrorRed
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = String.format("%.0f%%", quantizedCubeData.paletteStability * 100),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                
                // Palette Grid Header
                Text(
                    text = "256 Color Palette (16×16)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                // Color Palette Grid
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(16),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        // Convert palette_rgb (Vec<u8> of 256×3 RGB values) to colors
                        val paletteRgb = quantizedCubeData.globalPaletteRgb
                        items(256) { index ->
                            val rgbIndex = index * 3
                            if (rgbIndex + 2 < paletteRgb.size) {
                                val r = paletteRgb[rgbIndex].toInt() and 0xFF
                                val g = paletteRgb[rgbIndex + 1].toInt() and 0xFF
                                val b = paletteRgb[rgbIndex + 2].toInt() and 0xFF
                                val color = Color(r, g, b)
                                
                                ColorSwatch(
                                    color = color,
                                    index = index
                                )
                            }
                        }
                    }
                }
                
                // Info text
                Text(
                    text = "Each color represents one entry in the GIF's global color table. " +
                           "NeuQuant quantization ensures perceptually uniform distribution.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun MetricItem(
    label: String,
    value: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}

@Composable
private fun ColorSwatch(
    color: Color,
    index: Int
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(color)
            .border(
                width = 0.5.dp,
                color = Color.Black.copy(alpha = 0.2f)
            )
    ) {
        // Optionally show index on hover or in debug mode
        if (false) { // Set to true for debug
            Text(
                text = index.toString(),
                fontSize = 8.sp,
                color = if (getLuminance(color) > 0.5f) Color.Black else Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(1.dp)
            )
        }
    }
}

// Helper function to calculate luminance
private fun getLuminance(color: Color): Float {
    // Using relative luminance formula: 0.2126R + 0.7152G + 0.0722B
    return 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
}