# UI Specification - Form Follows Function Design System

## Design Philosophy
**"Form follows function"** - Louis Sullivan / Bauhaus principles
- Shape equals purpose: square preview for square output
- 9×9 grid mirrors neural network macrocells
- Minimal ornament, maximum clarity
- Technical truth always visible

## Visual Language

### Geometry
- **Primary Unit**: Square (1:1 aspect ratio)
- **Secondary Unit**: 9×9 grid (81 cells)
- **Control Unit**: Cubic buttons (square face)
- **Corner Radius**: 0dp (pure square) to 8dp max
- **Gaps**: 2dp between grid cells, 16dp between major sections

### Color System
```kotlin
// Base Palette
val NeutralDark = Color(0xFF121212)     // Background
val NeutralMid = Color(0xFF424242)      // Disabled/inactive
val NeutralLight = Color(0xFFE0E0E0)    // Text/borders

// Functional Colors  
val ProcessingOrange = Color(0xFFFF6D00) // Active capture
val MatrixGreen = Color(0xFF00C853)      // Success/ready
val ErrorRed = Color(0xFFD50000)         // Error states
val InfoBlue = Color(0xFF2962FF)         // Information

// Overlay Colors (40% alpha)
val AlphaOverlay = Color(0x66FF6D00)     // Alpha heatmap
val DeltaEOverlay = Color(0x662962FF)    // ΔE heatmap
```

### Typography
```kotlin
// Monospace for technical readouts
val TechnicalFont = FontFamily.Monospace

// Sizes
val CaptionSize = 10.sp  // Frame counter, metrics
val BodySize = 14.sp      // Labels, buttons
val HeaderSize = 18.sp    // Section titles
```

## Component Specifications

### 1. SquarePreview
**Purpose**: Display camera feed in exact 1:1 ratio matching pipeline geometry

```kotlin
@Composable
fun SquarePreview(
    modifier: Modifier = Modifier,
    previewState: PreviewState,
    bitmap: Bitmap?
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)  // Force square
            .clip(RectangleShape)
            .border(
                width = 2.dp,
                color = when (previewState) {
                    PreviewState.IDLE -> NeutralMid
                    PreviewState.CAPTURING -> ProcessingOrange
                    PreviewState.COMPLETE -> MatrixGreen
                    PreviewState.ERROR -> ErrorRed
                },
                shape = RectangleShape
            )
            .background(NeutralDark)
    ) {
        // Camera feed or frozen frame
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Camera preview 729×729",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
        
        // State indicator corner chip
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            color = Color.Black.copy(alpha = 0.7f),
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(
                text = "729×729",
                style = TextStyle(
                    fontFamily = TechnicalFont,
                    fontSize = CaptionSize,
                    color = NeutralLight
                ),
                modifier = Modifier.padding(4.dp)
            )
        }
    }
}
```

### 2. Grid81 Progress Indicator
**Purpose**: Visualize capture progress as 9×9 grid matching Go head macrocells

```kotlin
@Composable
fun Grid81(
    modifier: Modifier = Modifier,
    capturedFrames: Int,
    totalFrames: Int = 81
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(9),
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(81) { index ->
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .background(
                        when {
                            index < capturedFrames -> MatrixGreen
                            index == capturedFrames -> ProcessingOrange.copy(
                                alpha = animateFloatAsState(
                                    targetValue = if (index == capturedFrames) 1f else 0.3f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(500),
                                        repeatMode = RepeatMode.Reverse
                                    )
                                ).value
                            )
                            else -> NeutralMid.copy(alpha = 0.3f)
                        }
                    )
            )
        }
    }
}
```

### 3. CubicButtons Control System
**Purpose**: Primary actions as equal-importance squares

```kotlin
@Composable
fun CubicButtons(
    modifier: Modifier = Modifier,
    onInfo: () -> Unit,
    onCapture: () -> Unit,
    onStatus: () -> Unit,
    isCapturing: Boolean
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // Info Button
        CubicButton(
            onClick = onInfo,
            icon = Icons.Outlined.Info,
            contentDescription = "Pipeline information",
            color = NeutralMid,
            size = 64.dp
        )
        
        // Capture Button (primary)
        CubicButton(
            onClick = onCapture,
            icon = if (isCapturing) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
            contentDescription = if (isCapturing) "Stop capture" else "Start capture",
            color = if (isCapturing) ErrorRed else ProcessingOrange,
            size = 96.dp  // Larger for primary action
        )
        
        // Status Button
        CubicButton(
            onClick = onStatus,
            icon = Icons.Outlined.Analytics,
            contentDescription = "View metrics",
            color = NeutralMid,
            size = 64.dp
        )
    }
}

@Composable
fun CubicButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    color: Color,
    size: Dp
) {
    Surface(
        modifier = Modifier
            .size(size)
            .aspectRatio(1f),
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.2f),
        border = BorderStroke(2.dp, color)
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = color,
                modifier = Modifier.size(size * 0.5f)
            )
        }
    }
}
```

### 4. Technical Readout Bar
**Purpose**: Display pipeline truth at all times

```kotlin
@Composable
fun TechnicalReadout(
    capturedFrames: Int,
    deltaE: DeltaEMetrics,
    paletteSize: Int,
    fps: Float
) {
    Surface(
        color = Color.Black,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Geometry
            ReadoutChip("729→81")
            
            // Progress
            ReadoutChip("${capturedFrames}/81")
            
            // Frame rate
            ReadoutChip("${fps.roundToInt()}fps")
            
            // Delta E
            ReadoutChip("ΔE μ=${deltaE.mean.format(1)} σ=${deltaE.stdDev.format(1)}")
            
            // Palette
            ReadoutChip("P=$paletteSize")
        }
    }
}

@Composable
fun ReadoutChip(text: String) {
    Text(
        text = text,
        style = TextStyle(
            fontFamily = TechnicalFont,
            fontSize = CaptionSize,
            color = MatrixGreen
        )
    )
}
```

### 5. Overlay System
**Purpose**: Toggle technical visualizations

```kotlin
@Composable
fun OverlayControls(
    showAlpha: Boolean,
    showDeltaE: Boolean,
    onToggleAlpha: () -> Unit,
    onToggleDeltaE: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = showAlpha,
            onClick = onToggleAlpha,
            label = { Text("A-map", fontSize = CaptionSize) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = ProcessingOrange.copy(alpha = 0.3f)
            )
        )
        
        FilterChip(
            selected = showDeltaE,
            onClick = onToggleDeltaE,
            label = { Text("ΔE", fontSize = CaptionSize) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = InfoBlue.copy(alpha = 0.3f)
            )
        )
    }
}
```

### 6. Info Panel
**Purpose**: Explain technical concepts

```kotlin
@Composable
fun InfoPanel(
    visible: Boolean,
    onDismiss: () -> Unit
) {
    if (visible) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                shape = RectangleShape,
                color = NeutralDark,
                border = BorderStroke(1.dp, ProcessingOrange)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "TECHNICAL PIPELINE",
                        style = TextStyle(
                            fontFamily = TechnicalFont,
                            fontSize = HeaderSize,
                            color = ProcessingOrange
                        )
                    )
                    
                    InfoRow("Input", "729×729 RGBA8888 @ 24fps")
                    InfoRow("Downsizer", "9×9 Go head neural network")
                    InfoRow("Output", "81×81 indexed color")
                    InfoRow("Quantization", "Oklab k-means clustering")
                    InfoRow("Dithering", "Floyd-Steinberg error diffusion")
                    InfoRow("Alpha", "Learned importance map (not in GIF)")
                    InfoRow("Palette", "256 colors per frame")
                    InfoRow("GIF timing", "4 centiseconds/frame (≈25fps)")
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row {
                        TextButton(onClick = { /* Open Oklab docs */ }) {
                            Text("What is Oklab?", color = InfoBlue)
                        }
                        TextButton(onClick = { /* Open F-S docs */ }) {
                            Text("What is Floyd-Steinberg?", color = InfoBlue)
                        }
                    }
                }
            }
        }
    }
}
```

## Layout Structure

```
┌─────────────────────────────┐
│      SquarePreview          │ ← 729×729 camera feed
│         (square)            │   with state border
├─────────────────────────────┤
│         Grid81              │ ← 9×9 progress grid
│     (81 squares)            │   fills as capturing
├─────────────────────────────┤
│    TechnicalReadout         │ ← Metrics bar
│  729→81 | 0/81 | ΔE | P256 │   always visible
├─────────────────────────────┤
│      CubicButtons           │ ← Three squares
│   [i] [CAPTURE] [📊]        │   Info/Capture/Status
├─────────────────────────────┤
│    OverlayControls          │ ← Toggle chips
│   [A-map] [ΔE]              │   for heatmaps
└─────────────────────────────┘
```

## Accessibility Requirements

### Contrast
- Text on background: ≥4.5:1 (WCAG AA)
- Interactive elements: ≥3:1
- State indicators use color + pattern

### Focus Order
1. Square preview (announce dimensions)
2. Capture button (primary action)
3. Info button
4. Status button
5. Overlay toggles
6. Technical readout (read as single unit)

### Content Descriptions
```kotlin
// Examples
"Camera preview showing 729 by 729 pixel feed"
"Capture button. Double tap to start recording 81 frames"
"Progress grid showing 15 of 81 frames captured"
"Delta E mean 3.2, standard deviation 1.1"
"Palette using 247 of 256 colors"
```

### Motion
- Reduce animations when `isReducedMotionEnabled`
- No auto-playing decorative animations
- Progress indicators use color change, not motion

## Implementation Checklist

- [ ] Theme.kt with square shape system
- [ ] SquarePreview with aspect ratio lock
- [ ] Grid81 with proper fill animation
- [ ] CubicButtons with size hierarchy
- [ ] TechnicalReadout with monospace font
- [ ] OverlayControls with FilterChips
- [ ] InfoPanel with technical explanations
- [ ] CameraScreen layout composition
- [ ] Accessibility annotations
- [ ] Dark/light theme variants
- [ ] Landscape orientation handling
- [ ] State preservation on rotation