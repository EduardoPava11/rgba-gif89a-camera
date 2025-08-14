# 3D Cube Visualization Implementation Plan

## Architecture Overview

Create a 3D cube where:
- Each 81×81 frame becomes a slice in a 81×81×81 cube
- The Z-axis represents time (frame sequence)
- 24fps animation cycles through the temporal slices
- Uses quantized color palette for consistent visualization

## Implementation Steps

### Phase 1: Extract Quantized Data from Rust Pipeline

1. **Modify Rust UniFFI Interface**:
   ```rust
   // Add to gifpipe.udl
   [Throws=GifPipeError]
   QuantizedFrameData m3_gif89a_encode_with_quantized_frames(
       sequence<sequence<u8>> rgba_frames, 
       string output_file
   );
   
   dictionary QuantizedFrameData {
       sequence<sequence<u8>> quantized_frames;  // 81 frames of indexed pixels
       sequence<sequence<u8>> palette;           // RGB palette (up to 256 colors)
       u32 frame_count;
       u32 palette_size;
   };
   ```

2. **Update Rust Implementation**:
   - Modify `m3_gif89a_encode_rgba_frames` to also return quantized data
   - Extract indexed frames and palette after quantization step
   - Return both GIF file and quantized frame data

### Phase 2: Create 3D Cube Renderer

1. **Add OpenGL ES/Vulkan 3D Renderer**:
   ```kotlin
   class CubeRenderer {
       fun renderFrameCube(
           quantizedFrames: List<ByteArray>,
           palette: Array<IntArray>,
           currentTime: Long
       )
   }
   ```

2. **Cube Geometry**:
   - Generate 81×81×81 voxel cube
   - Each voxel colored from palette based on frame data
   - Dynamic slice visualization based on time

### Phase 3: UI Integration

1. **Add Cube Screen to Navigation**:
   ```kotlin
   enum class Screen {
       // ... existing screens
       CUBE_VISUALIZATION
   }
   ```

2. **Cube Visualization UI**:
   ```kotlin
   @Composable
   fun CubeVisualizationScreen(
       quantizedData: QuantizedFrameData,
       onBack: () -> Unit
   ) {
       // 3D cube view with time controls
       // Slice selector (Z-axis)
       // Palette color legend
       // Animation controls (play/pause/speed)
   }
   ```

## Technical Implementation Details

### Data Structure
```kotlin
data class QuantizedFrameData(
    val frames: List<ByteArray>,    // 81 frames × 6561 pixels (81²) 
    val palette: Array<IntArray>,   // RGB palette entries
    val frameCount: Int = 81,
    val paletteSize: Int,
    val gifFile: File              // Original GIF output
)
```

### 3D Visualization Options

1. **Volumetric Rendering**: Full 81³ voxel cube
2. **Slice Animation**: Animate through Z-slices at 24fps
3. **Interactive Navigation**: 
   - Rotate cube with touch gestures
   - Zoom in/out
   - Select specific time slices
   - Cross-section views (XY, XZ, YZ planes)

### Memory Optimization
- Use indexed color format (1 byte per pixel instead of 4)
- Implement LOD (Level of Detail) for distant voxels
- Stream slices rather than loading entire cube

### Performance Considerations
- OpenGL ES compute shaders for voxel generation
- Instanced rendering for voxel cubes
- Temporal coherence optimization (adjacent frames are similar)

## User Experience Features

1. **Time Navigation**:
   - Scrub bar to jump to specific frames
   - Play/pause animation
   - Speed controls (0.5×, 1×, 2×, 4×)

2. **Visual Controls**:
   - Opacity controls for internal structure visibility
   - Color mapping options (original palette vs. false color)
   - Wireframe/solid rendering toggle

3. **Analysis Tools**:
   - Pixel difference highlighting between frames
   - Temporal gradient visualization
   - Color histogram per frame/slice

## Integration Points

### Modify M3Processor.kt
```kotlin
data class M3ResultWithQuantized(
    val gifResult: M3Result,
    val quantizedData: QuantizedFrameData
)

suspend fun exportGif89aWithQuantizedFrames(
    rgbaFrames: List<ByteArray>,
    outputDir: File,
    baseName: String = "final"
): M3ResultWithQuantized
```

### Update MilestoneManager.kt
- Add option to generate cube visualization after M3 completion
- Store quantized data for cube renderer

### Navigation Flow
```
M3 Complete → Export Screen
             ↓
           [View as Cube] button
             ↓
         Cube Visualization Screen
```

## File Structure
```
com/rgbagif/cube/
├── CubeRenderer.kt           # OpenGL ES 3D renderer
├── CubeGeometry.kt           # Voxel cube generation
├── QuantizedFrameData.kt     # Data structures
├── CubeVisualizationScreen.kt # Compose UI
├── CubeControls.kt           # Time/view controls
└── shaders/
    ├── voxel.vert           # Vertex shader
    └── voxel.frag           # Fragment shader
```

This implementation would create a unique and visually striking way to explore the temporal structure of the captured data, showing how the neural downscaling and quantization affect the visual information across time.
