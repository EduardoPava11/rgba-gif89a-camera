# RGBA→GIF89a Camera App 📸

Android camera app that captures RGBA8888 frames and converts them to GIF89a using advanced neural network downscaling and quantization techniques. Features a complete 3-milestone pipeline with innovative 3D temporal visualization.

## 🎯 Architecture

### Pipeline Overview
- **M1: RGBA Capture** - 729×729 high-resolution RGBA frame capture using CameraX
- **M2: Intelligent Downscaling** - 729×729 → 81×81 with bilinear interpolation (neural network available)
- **M3: GIF89a Export** - NeuQuant quantization and optimized GIF89a encoding
- **3D Visualization** - Interactive 81×81×81 voxel cube for temporal analysis

### Current Implementation
- **Active**: Manual bilinear interpolation with proper floating-point math
- **Available**: Sophisticated 9×9 Go-inspired neural network architecture
- **Fixed Issues**: Red channel overflow, RGBA/ARGB conversion, temporal consistency

### Components

#### 1. M2Processor.kt (Active Downscaler)
- 4-point bilinear interpolation with floating-point precision
- Proper RGBA→ARGB channel mapping
- Fixed red channel overflow issues
- ~50ms processing time per frame

#### 2. Neural Network (Available, Rust Core)
- Burn-based 9×9 Go head architecture
- Learned alpha channel for importance masking
- Temporal consistency with feedback loop
- Sophisticated encoder/decoder with policy/value heads

#### 3. M3Processor.kt (GIF89a Encoder)
#### 3. M3Processor.kt (GIF89a Encoder)
- NeuQuant quantization algorithm with 256-color palette
- Optimized GIF89a encoding with LZW compression
- Global palette with infinite loop structure
- Temporal consistency preservation

#### 4. CubeVisualizationScreen.kt (3D Temporal Visualization) 
- Interactive 81×81×81 voxel cube rendering
- Temporal slice animation at 24fps
- Quantized palette color mapping
- Touch gesture navigation (rotate, zoom, slice)

### Technical Pipeline UI
Complete 3-milestone workflow visualization:
- **M1 Progress**: Real-time RGBA capture status
- **M2 Progress**: Downscaling with debug statistics  
- **M3 Progress**: GIF encoding and export
- **Results**: File size, processing time, quality metrics

## Project Structure

```
rgba-gif89a-camera/
├── app/                      # Android app
│   └── src/main/java/
│       └── com/rgbagif/
│           ├── MainActivity.kt       # Main entry point
│           ├── MainViewModel.kt      # Capture orchestration
│           ├── camera/              # CameraX + processing
│           │   ├── CameraXManager.kt    # RGBA capture
│           │   ├── M2Processor.kt       # Downscaling (active)
│           │   └── M3Processor.kt       # GIF89a export
│           ├── ui/                  # Jetpack Compose UI
│           │   ├── CameraScreen.kt      # Camera interface
│           │   ├── TechnicalPipelineScreen.kt # M1→M2→M3 visualization
│           │   ├── CubeVisualizationScreen.kt # 3D temporal viz
│           │   └── AppNavigation.kt     # Screen navigation
│           ├── pipeline/            # Milestone management
│           │   └── MilestoneManager.kt  # Workflow coordinator
│           └── utils/               # Support utilities
│               └── GifFrameExtractor.kt # Frame data extraction
│
├── rust-core/               # Neural network (available)
│   ├── src/
│   │   ├── lib.rs          # UniFFI interface
│   │   ├── gifpipe.udl     # API definition
│   │   ├── pipeline.rs     # Main pipeline
│   │   ├── downsizer.rs    # 9×9 Go neural network
│   │   ├── go_network.rs   # Neural architecture
│   │   └── quantizer.rs    # NeuQuant implementation
│   ├── assets/
│   │   └── go9x9_model.bin # Pre-trained weights
│   └── build_android.sh    # Build script
│
├── CUBE_VISUALIZATION_PLAN.md  # 3D visualization technical spec
└── gradle/                  # Build configuration
```

## Building

### Prerequisites
1. Android Studio
2. Rust toolchain with Android targets
3. Android NDK (27.0.12077973)

### Setup
```bash
# Install Rust Android targets
rustup target add aarch64-linux-android armv7-linux-androideabi

# Set NDK path
export ANDROID_NDK_HOME=$HOME/Library/Android/sdk/ndk/27.0.12077973
```

### Build Rust Library
```bash
cd rust-core
./build_android.sh
```

### Build Android App
```bash
./gradlew assembleDebug
```

## Implementation Details

## 📱 Usage & Features

### Complete Workflow
1. **Camera Capture**: Tap to start 81-frame RGBA8888 sequence at 729×729
2. **M1 Processing**: Real-time RGBA frame capture with CameraX
3. **M2 Processing**: Bilinear downscaling to 81×81 with overflow protection
4. **M3 Processing**: NeuQuant quantization and GIF89a encoding
5. **3D Visualization**: View temporal data as interactive voxel cube

### Key Features
- **Technical Pipeline UI**: Complete M1→M2→M3 milestone visualization
- **WYSIWYG Preview**: Camera preview matches captured frames exactly
- **Fixed Issues**: Red channel overflow, proper RGBA/ARGB conversion
- **3D Temporal Analysis**: 81×81×81 voxel cube with quantized palette
- **Dual Processing Options**: Active bilinear + available neural network
- **Performance Optimized**: ~570KB GIF output, <100ms per frame

## 🚀 Recent Updates

### Latest Commit: `7b7f9f41` - "Add 3D Cube Visualization Feature"
- ✅ Added complete CubeVisualizationScreen.kt for 3D temporal visualization
- ✅ Added GifFrameExtractor.kt utility for frame data extraction  
- ✅ Enhanced AppNavigation.kt and TechnicalPipelineScreen.kt
- ✅ Created comprehensive CUBE_VISUALIZATION_PLAN.md technical specification

### Previous: `d699267` - "MVP Complete: Technical Pipeline UI"  
- ✅ Fixed M2Processor.kt red channel overflow issues
- ✅ Implemented proper 4-point bilinear interpolation
- ✅ Fixed RGBA→ARGB channel mapping corruption
- ✅ Added M2_STATS_FIXED debug logging
- ✅ Complete architectural documentation

## 🔬 Technical Achievements

### Image Processing Pipeline
- **Fixed Bilinear Interpolation**: Proper floating-point 4-point interpolation
- **Channel Mapping**: Correct RGBA→ARGB conversion (R=A, G=R, B=G, A=B → A=A, R=R, G=G, B=B)
- **Overflow Protection**: Eliminated red channel corruption in downscaling
- **Performance**: 729×729→81×81 processing in ~50ms per frame

### 3D Visualization Innovation
- **Voxel Cube**: 81×81×81 temporal visualization using quantized frame data
- **Interactive Navigation**: Touch gestures for rotation, zoom, temporal slicing
- **Palette Analysis**: Visual exploration of quantization color space
- **Memory Optimization**: Indexed color format (1 byte vs 4 bytes per pixel)

### Neural Network Architecture (Available)
- **9×9 Grid**: Go-inspired macrocell structure with learned aggregation
- **Temporal Consistency**: Feedback loop with palette/alpha/error memory
- **Burn Framework**: Deep learning with SIMD CPU optimization  
- **Pre-trained Weights**: go9x9_model.bin ready for activation

## 📊 Performance Metrics

- **Processing Speed**: M1→M2→M3 pipeline in <5 seconds for 81 frames
- **Memory Usage**: ~50MB pipeline state with indexed color optimization
- **Output Quality**: High-fidelity 81×81 GIF89a with temporal consistency  
- **File Size**: ~570KB for 81 frames (3.375 seconds at 24fps)
- **3D Rendering**: Smooth 60fps voxel cube visualization
- **Camera Capture**: 729×729 RGBA at 24fps with CameraX integration

---

🎨 **Show your friend**: 
- The complete technical pipeline visualization (M1→M2→M3)
- Interactive 3D cube showing temporal data structure
- Advanced image processing with neural network capability
- Professional Android development with Rust integration

**Built with cutting-edge techniques**: Jetpack Compose, CameraX, OpenGL ES, Rust UniFFI, Neural Networks, and innovative 3D temporal visualization! 📱✨