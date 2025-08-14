package com.rgbagif.ui.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarDefaults.topAppBarColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.rgbagif.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Frame Browser screen with HorizontalPager and thumbnail rail
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FrameBrowserScreen(
    sessionId: String? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: FrameBrowserViewModel = viewModel()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Initialize ViewModel
    LaunchedEffect(context, sessionId) {
        viewModel.initialize(context, sessionId)
    }
    
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    // Pager state
    val pagerState = rememberPagerState(
        initialPage = uiState.currentIndex,
        pageCount = { uiState.items.size.coerceAtLeast(1) }
    )
    
    // Sync pager with ViewModel
    LaunchedEffect(pagerState.currentPage) {
        viewModel.setCurrentIndex(pagerState.currentPage)
    }
    
    Scaffold(
        topBar = {
            BrowserTopBar(
                currentIndex = uiState.currentIndex,
                totalFrames = uiState.totalFrames,
                sessionId = uiState.sessionId,
                isDownsizing = uiState.isDownsizing,
                downsizeProgress = uiState.downsizeProgress,
                onBack = onBack,
                onDownsizeAll = { viewModel.downsizeAllFrames() },
                onGenerateAllPngs = { viewModel.generateAllPngs() }
            )
        },
        bottomBar = {
            FilterBar(
                showRaw = uiState.showRaw,
                showDownsized = uiState.showDownsized,
                onToggleRaw = { viewModel.toggleRawView() },
                onToggleDownsized = { viewModel.toggleDownsizedView() }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(NeutralDark)
        ) {
            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = ProcessingOrange)
                    }
                }
                
                uiState.error != null -> {
                    val errorMessage = uiState.error!!
                    ErrorMessage(
                        message = errorMessage,
                        onRetry = { viewModel.clearError() }
                    )
                }
                
                uiState.items.isEmpty() -> {
                    EmptyState()
                }
                
                else -> {
                    // Main pager
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) { page ->
                        val item = uiState.items.getOrNull(page)
                        if (item != null) {
                            FramePage(
                                item = item,
                                showDownsized = uiState.showDownsized && !uiState.showRaw,
                                onGeneratePng = { viewModel.generatePngForFrame(page) }
                            )
                        }
                    }
                    
                    // Thumbnail rail
                    ThumbnailRail(
                        items = uiState.items,
                        currentIndex = pagerState.currentPage,
                        onThumbnailClick = { index ->
                            scope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        }
                    )
                }
            }
        }
    }
    
    // Show downsize progress
    if (uiState.isDownsizing) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center
        ) {
            Card {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Downsizing Frames",
                        style = TechnicalHeading
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { uiState.downsizeProgress },
                        modifier = Modifier.width(200.dp),
                        color = ProcessingOrange
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${(uiState.downsizeProgress * 100).toInt()}%",
                        style = TechnicalBody
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserTopBar(
    currentIndex: Int,
    totalFrames: Int,
    sessionId: String?,
    isDownsizing: Boolean,
    downsizeProgress: Float,
    onBack: () -> Unit,
    onDownsizeAll: () -> Unit,
    onGenerateAllPngs: () -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = "Frame ${currentIndex + 1} / $totalFrames",
                    style = TechnicalHeading
                )
                sessionId?.let {
                    Text(
                        text = it,
                        style = TechnicalCaption,
                        color = NeutralLight.copy(alpha = 0.6f)
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            IconButton(
                onClick = onGenerateAllPngs,
                enabled = !isDownsizing
            ) {
                Icon(Icons.Default.Image, contentDescription = "Generate PNGs")
            }
            IconButton(
                onClick = onDownsizeAll,
                enabled = !isDownsizing
            ) {
                Icon(Icons.Default.PhotoSizeSelectSmall, contentDescription = "Downsize All")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = NeutralDark,
            titleContentColor = NeutralLight
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(
    showRaw: Boolean,
    showDownsized: Boolean,
    onToggleRaw: () -> Unit,
    onToggleDownsized: () -> Unit
) {
    Surface(
        color = NeutralDark,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FilterChip(
                selected = showRaw,
                onClick = onToggleRaw,
                label = { Text("RAW (729×729)") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = ProcessingOrange,
                    selectedLabelColor = NeutralDark
                )
            )
            FilterChip(
                selected = showDownsized,
                onClick = onToggleDownsized,
                label = { Text("Downsized (81×81)") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MatrixGreen,
                    selectedLabelColor = NeutralDark
                )
            )
        }
    }
}

@Composable
private fun FramePage(
    item: BrowserItem,
    showDownsized: Boolean,
    onGeneratePng: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Frame display
            // Prefer JPEG over PNG
            val imagePath = when {
                showDownsized && item.pathJpegDownsized != null -> item.pathJpegDownsized
                showDownsized && item.pathPngDownsized != null -> item.pathPngDownsized
                item.pathJpegRaw != null -> item.pathJpegRaw
                item.pathPngRaw != null -> item.pathPngRaw
                else -> null
            }
            
            if (imagePath != null && imagePath.exists()) {
                AsyncImage(
                    model = imagePath,
                    contentDescription = "Frame ${item.index}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .border(1.dp, NeutralMid, SquareShape),
                    contentScale = ContentScale.Fit
                )
            } else {
                // No PNG available, show generate button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(NeutralMid.copy(alpha = 0.3f))
                        .border(1.dp, NeutralMid, SquareShape),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No PNG available",
                            style = TechnicalBody,
                            color = NeutralLight.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onGeneratePng,
                            shape = SquareShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ProcessingOrange
                            )
                        ) {
                            Text("Generate PNG")
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Frame info
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = SquareShape,
                colors = CardDefaults.cardColors(
                    containerColor = NeutralMid.copy(alpha = 0.3f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Frame ${String.format("%04d", item.index)}",
                        style = TechnicalBody,
                        color = ProcessingOrange
                    )
                    Text(
                        text = "${item.width}×${item.height}",
                        style = TechnicalCaption,
                        color = NeutralLight
                    )
                    if (item.timestampMs > 0) {
                        Text(
                            text = "Timestamp: ${item.timestampMs}ms",
                            style = TechnicalCaption,
                            color = NeutralLight.copy(alpha = 0.6f)
                        )
                    }
                    item.deltaEMean?.let {
                        Text(
                            text = "ΔE μ: ${String.format("%.2f", it)}",
                            style = TechnicalCaption,
                            color = MatrixGreen
                        )
                    }
                    item.paletteSize?.let {
                        Text(
                            text = "Palette: $it colors",
                            style = TechnicalCaption,
                            color = InfoBlue
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThumbnailRail(
    items: List<BrowserItem>,
    currentIndex: Int,
    onThumbnailClick: (Int) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .background(NeutralDark),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemsIndexed(items) { index, item ->
            ThumbnailItem(
                item = item,
                isSelected = index == currentIndex,
                onClick = { onThumbnailClick(index) }
            )
        }
    }
}

@Composable
private fun ThumbnailItem(
    item: BrowserItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clickable { onClick() }
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) ProcessingOrange else NeutralMid,
                shape = SquareShape
            )
            .background(NeutralMid.copy(alpha = 0.3f))
    ) {
        // Show thumbnail or placeholder (prefer JPEG)
        val thumbnailPath = item.pathJpegDownsized ?: item.pathJpegRaw ?: 
                           item.pathPngDownsized ?: item.pathPngRaw
        
        if (thumbnailPath != null && thumbnailPath.exists()) {
            AsyncImage(
                model = thumbnailPath,
                contentDescription = "Thumbnail ${item.index}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            // Show frame number
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${item.index + 1}",
                    style = TechnicalCaption,
                    color = NeutralLight.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.ImageNotSupported,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = NeutralMid
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No frames available",
                style = TechnicalBody,
                color = NeutralLight.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun ErrorMessage(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = ErrorRed.copy(alpha = 0.2f)
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Error",
                    style = TechnicalHeading,
                    color = ErrorRed
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    style = TechnicalBody,
                    color = NeutralLight
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onRetry,
                    shape = SquareShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ProcessingOrange
                    )
                ) {
                    Text("Retry")
                }
            }
        }
    }
}