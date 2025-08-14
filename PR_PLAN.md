# PR Plan: Form Follows Function UI Implementation

## Overview
Implement square/cubic design system inspired by Sullivan/Bauhaus principles for the RGBA→GIF89a camera app UI.

## Step-by-Step Implementation Plan

### Step 1: Theme Foundation
**Files to create/modify:**
- `app/src/main/java/com/rgbagif/ui/theme/Color.kt`
- `app/src/main/java/com/rgbagif/ui/theme/Shape.kt`
- `app/src/main/java/com/rgbagif/ui/theme/Type.kt`
- `app/src/main/java/com/rgbagif/ui/theme/Theme.kt`

```kotlin
// Color.kt
package com.rgbagif.ui.theme

import androidx.compose.ui.graphics.Color

// Base Palette
val NeutralDark = Color(0xFF121212)
val NeutralMid = Color(0xFF424242)
val NeutralLight = Color(0xFFE0E0E0)

// Functional Colors
val ProcessingOrange = Color(0xFFFF6D00)
val MatrixGreen = Color(0xFF00C853)
val ErrorRed = Color(0xFFD50000)
val InfoBlue = Color(0xFF2962FF)

// Overlay Colors (40% alpha)
val AlphaOverlay = Color(0x66FF6D00)
val DeltaEOverlay = Color(0x662962FF)
```

```kotlin
// Shape.kt
package com.rgbagif.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val SquareShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),  // Pure square
    small = RoundedCornerShape(4.dp),       // Slight round
    medium = RoundedCornerShape(8.dp),      // Max round
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp)
)
```

### Step 2: Core Components

**Create component files:**

#### `app/src/main/java/com/rgbagif/ui/components/SquarePreview.kt`
```kotlin
package com.rgbagif.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import com.rgbagif.ui.theme.*

enum class PreviewState {
    IDLE, CAPTURING, COMPLETE, ERROR
}

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
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = NeutralLight
                ),
                modifier = Modifier.padding(4.dp)
            )
        }
    }
}
```

#### `app/src/main/java/com/rgbagif/ui/components/Grid81.kt`
```kotlin
package com.rgbagif.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rgbagif.ui.theme.*

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
            val pulseAlpha by animateFloatAsState(
                targetValue = if (index == capturedFrames) 1f else 0.3f,
                animationSpec = if (index == capturedFrames) {
                    infiniteRepeatable(
                        animation = tween(500),
                        repeatMode = RepeatMode.Reverse
                    )
                } else {
                    snap()
                }
            )
            
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .background(
                        when {
                            index < capturedFrames -> MatrixGreen
                            index == capturedFrames -> ProcessingOrange.copy(alpha = pulseAlpha)
                            else -> NeutralMid.copy(alpha = 0.3f)
                        }
                    )
            )
        }
    }
}
```

### Step 3: Layout Composition

#### `app/src/main/java/com/rgbagif/ui/CameraScreen.kt`
```kotlin
package com.rgbagif.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rgbagif.ui.components.*
import com.rgbagif.ui.theme.*
import com.rgbagif.MainViewModel

@Composable
fun CameraScreen(
    viewModel: MainViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NeutralDark)
    ) {
        // Square camera preview
        SquarePreview(
            previewState = uiState.previewState,
            bitmap = uiState.previewBitmap,
            modifier = Modifier.padding(16.dp)
        )
        
        // 9×9 progress grid
        Grid81(
            capturedFrames = uiState.capturedFrames,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Technical readout bar
        TechnicalReadout(
            capturedFrames = uiState.capturedFrames,
            deltaE = uiState.deltaEMetrics,
            paletteSize = uiState.paletteSize,
            fps = uiState.currentFps
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Cubic control buttons
        CubicButtons(
            onInfo = { viewModel.showInfo() },
            onCapture = { viewModel.toggleCapture() },
            onStatus = { viewModel.showStatus() },
            isCapturing = uiState.isCapturing,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Overlay toggle controls
        OverlayControls(
            showAlpha = uiState.showAlphaOverlay,
            showDeltaE = uiState.showDeltaEOverlay,
            onToggleAlpha = { viewModel.toggleAlphaOverlay() },
            onToggleDeltaE = { viewModel.toggleDeltaEOverlay() }
        )
    }
    
    // Overlay layers
    if (uiState.showAlphaOverlay) {
        AlphaOverlay(
            alphaMap = uiState.alphaMap,
            modifier = Modifier.fillMaxSize()
        )
    }
    
    if (uiState.showDeltaEOverlay) {
        DeltaEOverlay(
            deltaEMap = uiState.deltaEMap,
            modifier = Modifier.fillMaxSize()
        )
    }
    
    // Info dialog
    if (uiState.showInfoPanel) {
        InfoPanel(
            visible = true,
            onDismiss = { viewModel.hideInfo() }
        )
    }
}
```

### Step 4: ViewModel Updates

#### Modify `app/src/main/java/com/rgbagif/MainViewModel.kt`
Add UI state management:

```kotlin
data class UiState(
    val previewState: PreviewState = PreviewState.IDLE,
    val previewBitmap: Bitmap? = null,
    val capturedFrames: Int = 0,
    val isCapturing: Boolean = false,
    val deltaEMetrics: DeltaEMetrics = DeltaEMetrics(),
    val paletteSize: Int = 0,
    val currentFps: Float = 0f,
    val showAlphaOverlay: Boolean = false,
    val showDeltaEOverlay: Boolean = false,
    val alphaMap: FloatArray? = null,
    val deltaEMap: FloatArray? = null,
    val showInfoPanel: Boolean = false
)

data class DeltaEMetrics(
    val mean: Float = 0f,
    val stdDev: Float = 0f,
    val max: Float = 0f
)
```

### Step 5: CameraX Verification

#### Verify in `app/src/main/java/com/rgbagif/camera/CameraXManager.kt`:

```kotlin
// Ensure RGBA_8888 configuration
imageAnalysis = ImageAnalysis.Builder()
    .setTargetResolution(Size(729, 729))  // Exact target
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .build()

// Handle stride correctly
private fun processRgbaFrame(image: ImageProxy) {
    val plane = image.planes[0]
    val rowStride = plane.rowStride  // May be > width * 4
    val pixelStride = plane.pixelStride  // Should be 4
    
    // Pack tightly for CBOR
    val packed = ByteArray(729 * 729 * 4)
    for (row in 0 until 729) {
        val srcOffset = row * rowStride
        val dstOffset = row * 729 * 4
        System.arraycopy(
            plane.buffer.array(),
            srcOffset,
            packed,
            dstOffset,
            729 * 4
        )
    }
}
```

## Testing Checklist

### Visual Tests
- [ ] Preview maintains 1:1 square ratio
- [ ] Grid shows exactly 81 cells (9×9)
- [ ] Buttons are perfect squares
- [ ] All text uses monospace for metrics
- [ ] No rounded corners exceed 8dp

### Functional Tests
- [ ] Capture starts/stops correctly
- [ ] Grid fills progressively during capture
- [ ] Metrics update in real-time
- [ ] Overlays toggle without affecting capture
- [ ] Info panel displays and dismisses

### Technical Tests
- [ ] CameraX outputs RGBA_8888
- [ ] Row stride handled correctly
- [ ] Output is exactly 729×729×4 bytes
- [ ] GIF saves with delay=4cs
- [ ] Per-frame palette (≤256 colors)

### Accessibility Tests
- [ ] All interactive elements focusable
- [ ] Content descriptions present
- [ ] Contrast ratios ≥ 4.5:1
- [ ] TalkBack navigation works
- [ ] Reduced motion respected

## Rollout Plan

1. **Phase 1**: Theme and foundation (2 hours)
   - Create theme files
   - Test on light/dark modes

2. **Phase 2**: Core components (4 hours)
   - Implement SquarePreview
   - Implement Grid81
   - Implement CubicButtons
   - Test individually

3. **Phase 3**: Screen composition (2 hours)
   - Wire up CameraScreen
   - Connect to ViewModel
   - Test state flow

4. **Phase 4**: Technical integration (2 hours)
   - Verify CameraX config
   - Test capture pipeline
   - Validate output

5. **Phase 5**: Polish (2 hours)
   - Add animations
   - Implement overlays
   - Accessibility pass
   - Performance optimization

## Success Metrics

- UI renders at 60fps idle, 30fps minimum during capture
- All geometric elements maintain square/cubic form
- Technical readouts update at ≥10Hz
- No memory leaks over 10-minute session
- Accessibility score ≥ 90%
- Zero crashes during rotation/background/foreground transitions