package com.rgbagif.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rgbagif.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File

/**
 * Export screen showing GIF preview and export options
 */
@Composable
fun ExportScreen(
    gifFile: File?,
    exportManager: com.rgbagif.export.GifExportManager,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    
    var exportMessage by remember { mutableStateOf<String?>(null) }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NeutralDark),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top bar
        @OptIn(ExperimentalMaterial3Api::class)
        TopAppBar(
            title = { 
                Text(
                    "Export GIF",
                    style = TechnicalBody,
                    color = ProcessingOrange
                ) 
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = ProcessingOrange
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = NeutralDark
            )
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // GIF info
        gifFile?.let { file ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.DarkGray
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "GIF Ready",
                        style = MaterialTheme.typography.headlineSmall,
                        color = SuccessGreen
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "Size: %.2f MB".format(file.length() / (1024.0 * 1024.0)),
                        style = TechnicalBody,
                        color = MatrixGreen
                    )
                    
                    Text(
                        text = "81 frames × 81×81 pixels",
                        style = TechnicalBody,
                        color = MatrixGreen
                    )
                    
                    Text(
                        text = "~24 fps (4,4,4,5 cs pattern)",
                        style = TechnicalBody,
                        color = MatrixGreen
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Show file path for debugging
                    Text(
                        text = "Path: ${file.name}",
                        style = TechnicalCaption,
                        color = Color.Gray
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Export options
            Text(
                text = "Export Options",
                style = MaterialTheme.typography.titleMedium,
                color = ProcessingOrange,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Export to Documents button
            ElevatedButton(
                onClick = {
                    exportManager.exportToDocuments(file)
                    exportMessage = "Opening document picker..."
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = ButtonDefaults.elevatedButtonColors(
                    containerColor = ProcessingOrange
                )
            ) {
                Icon(
                    Icons.Default.Description,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Save to Documents",
                    style = TechnicalBody
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Export to Downloads button
            ElevatedButton(
                onClick = {
                    scope.launch {
                        exportManager.exportToDownloads(file)
                        exportMessage = "Saving to Downloads..."
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = ButtonDefaults.elevatedButtonColors(
                    containerColor = MatrixGreen
                )
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Save to Downloads",
                    style = TechnicalBody
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Persistent folder option
            OutlinedButton(
                onClick = {
                    exportManager.pickPersistentFolder()
                    exportMessage = "Select a folder for future exports"
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = ProcessingOrange
                )
            ) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Set Default Export Folder",
                    style = TechnicalBody
                )
            }
            
            // Export message
            exportMessage?.let { message ->
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.DarkGray.copy(alpha = 0.7f)
                    )
                ) {
                    Text(
                        text = message,
                        style = TechnicalBody,
                        color = MatrixGreen,
                        modifier = Modifier.padding(12.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
            
        } ?: run {
            // No GIF file
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No GIF file available",
                    style = MaterialTheme.typography.bodyLarge,
                    color = ErrorRed
                )
            }
        }
    }
}