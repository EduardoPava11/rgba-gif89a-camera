# PHASE 2 WORK ORDER — M2/M3 Separation + Bevy Cube (81×81×81)

## Executive Summary

Based on the comprehensive Phase 1→Phase 2 architectural analysis, this work order implements:
1. **Hybrid API Granularity**: Fat call for production + granular M2/M3 functions for preview
2. **Single Source of Truth**: QuantizedCubeData shared between preview and GIF export
3. **Bevy Desktop Renderer**: Faithful 3D cube visualization using exact quantized data
4. **Visual Regression Testing**: Concrete ΔE thresholds with golden test infrastructure
5. **Production Observability**: Session correlation through new M2/M3 spans

This enables WYSIWYG cube preview using the exact same palette+indices as the final GIF.

## Phase 2 Architecture Decisions

### Applied from Architectural Analysis:

#### (A) Hybrid API Granularity
- **Keep fat call**: `strategy_b_neural_pipeline(...)` for production (atomic end-to-end)
- **Add granular functions**: `m2_quantize_for_cube()` and `m3_write_gif_from_cube()` for preview
- **Enable inspection**: Test palette stability & temporal coherence before GIF creation

#### (B) Single Source of Truth for Preview/Export  
- **QuantizedCubeData**: Contains palette+indices+metrics shared by M3 GIF and Bevy viewer
- **WYSIWYG guarantee**: Preview uses exact same quantized data as final export
- **Palette Extensions**: Include stability metrics, ΔE measurements, attention maps

#### (C) Visual Regression + Concrete Thresholds
- **Golden tests**: Frame-by-frame validation with Git LFS fixtures
- **Concrete targets**: Mean ΔE < 2.0, P95 ΔE < 5.0 in Oklab space
- **Structural validation**: GIF header/trailer, 81 frames, NETSCAPE2.0 loop

#### (D) Production Observability Continuity
- **Session correlation**: Propagate session_id through new M2/M3 spans
- **Palette telemetry**: Log stability, drift, ΔE stats for field debugging
- **Structured metrics**: JSON logs include quantization performance data

#### (E) Bevy Renderer (Desktop-first)
- **Cross-platform foundation**: Bevy ECS + wgpu renderer for faithful cube visualization
- **Exact color mapping**: Use same palette texture + index textures as GIF encoder
- **Desktop proven path**: Start with macOS/Linux/Windows; Android NativeActivity later

#### (F) Android Strategy (Future)
- **NativeActivity path**: Document Bevy + winit + wgpu for Android deployment
- **Modular approach**: Keep Android preview in Compose while using shared quantized data

## Implementation Tasks (Ordered with Acceptance Criteria)

### Task 1 — Data Type & FFI Surface

#### 1.1 Enhanced QuantizedCubeData Type

```rust
// rust-core/crates/common-types/src/lib.rs

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct QuantizedCubeData {
    pub width: u16,  // 81
    pub height: u16, // 81
    pub global_palette_rgb: Vec<u8>,     // 256 * 3 RGB bytes
    pub indexed_frames: Vec<Vec<u8>>,    // 81 frames of 81*81 indices
    pub delays_cs: Vec<u8>,              // length 81, centiseconds per frame
    pub palette_stability: f32,          // [0..1] temporal coherence
    pub mean_delta_e: f32,               // Oklab ΔE mean
    pub p95_delta_e: f32,                // Oklab ΔE p95
    pub attention_maps: Option<Vec<Vec<f32>>>, // 81 optional attention maps
}
```

#### 1.2 New FFI Granular API

```rust
// rust-core/gifpipe-ffi/src/lib.rs

/// M2: Quantize 81 RGBA frames into global palette + indexed data
#[uniffi::export]
pub fn m2_quantize_for_cube(frames_81_rgba: Vec<Vec<u8>>) -> Result<QuantizedCubeData, GifPipeError> {
    let span = span!(Level::INFO, "ffi_m2_quantize", frames = 81);
    let _guard = span.enter();
    
    // Convert to internal format and quantize
    let frames_rgb = convert_rgba_to_rgb_frames(frames_81_rgba)?;
    let oklab_quantizer = OklabQuantizer::new(256);
    
    oklab_quantizer.quantize_for_cube(frames_rgb)
}

/// M3: Encode GIF89a from pre-quantized cube data (no quantization inside)
#[uniffi::export]
pub fn m3_write_gif_from_cube(
    cube: QuantizedCubeData, 
    fps_cs: u8, 
    loop_forever: bool
) -> Result<GifInfo, GifPipeError> {
    let span = span!(Level::INFO, "ffi_m3_encode", 
        frames = 81, 
        palette_size = cube.global_palette_rgb.len() / 3,
        palette_stability = cube.palette_stability
    );
    let _guard = span.enter();
    
    let gif_encoder = Gif89aEncoder::new();
    let gif_bytes = gif_encoder.encode_from_cube_data(&cube, fps_cs, loop_forever)?;
    
    Ok(GifInfo {
        bytes: gif_bytes,
        width: cube.width,
        height: cube.height,
        frame_count: 81,
        palette_size: cube.global_palette_rgb.len() / 3,
    })
}

/// Keep existing fat call for production
#[uniffi::export]
pub fn strategy_b_neural_pipeline(frames_81_rgba: Vec<Vec<u8>>) -> Result<QuantResult, GifPipeError> {
    // Unchanged - atomic end-to-end for production
}
```

**Acceptance Criteria:**
- `cargo check --workspace` passes
- UniFFI re-generation yields Kotlin types for `QuantizedCubeData`
- Logs show `palette_size`, `palette_stability`, `mean_delta_e`, `p95_delta_e`

### Task 2 — M2 Refactor (Global Palette)

#### 2.1 Global Palette Construction

```rust
// rust-core/crates/m2-quant/src/lib.rs

impl OklabQuantizer {
    /// Build global palette from ALL 81 frames using streaming k-means
    pub fn quantize_for_cube(&self, frames: Frames81Rgb) -> Result<QuantizedCubeData, GifPipeError> {
        let span = span!(Level::INFO, "M2_quantize_cube", 
            frames = 81,
            target_colors = 256,
            method = "oklab_streaming_kmeans"
        );
        let _guard = span.enter();
        
        // Sample pixels from all 81 frames for global k-means
        let all_samples = self.sample_all_frames(&frames, 1000)?; // 1000 per frame
        info!(total_samples = all_samples.len(), "Building global palette");
        
        // Run k-means in Oklab space
        let global_palette_oklab = self.streaming_kmeans_oklab(&all_samples, 256)?;
        let global_palette_rgb = global_palette_oklab.iter()
            .map(|oklab| oklab_to_rgb(*oklab))
            .flatten()
            .collect();
        
        // Quantize each frame using global palette
        let mut indexed_frames = Vec::with_capacity(81);
        let mut delta_e_values = Vec::with_capacity(81);
        
        for (idx, frame) in frames.frames_rgb.iter().enumerate() {
            let (indices, frame_delta_e) = self.quantize_frame_with_palette(
                frame, 
                &global_palette_oklab
            )?;
            
            indexed_frames.push(indices);
            delta_e_values.push(frame_delta_e);
            
            if idx % 10 == 0 {
                info!(frame = idx, delta_e = frame_delta_e, "Quantized frame batch");
            }
        }
        
        // Calculate temporal metrics
        let palette_stability = self.calculate_palette_stability(&indexed_frames)?;
        let mean_delta_e = delta_e_values.iter().sum::<f32>() / 81.0;
        let p95_delta_e = calculate_p95(&delta_e_values);
        
        info!(
            palette_stability = palette_stability,
            mean_delta_e = mean_delta_e,
            p95_delta_e = p95_delta_e,
            "Global quantization complete"
        );
        
        Ok(QuantizedCubeData {
            width: 81,
            height: 81,
            global_palette_rgb,
            indexed_frames,
            delays_cs: vec![4; 81], // 25fps = 4cs
            palette_stability,
            mean_delta_e,
            p95_delta_e,
            attention_maps: frames.attention_maps,
        })
    }
    
    fn calculate_palette_stability(&self, indexed_frames: &[Vec<u8>]) -> Result<f32, GifPipeError> {
        // Measure histogram similarity between consecutive frames
        let mut stability_scores = Vec::new();
        
        for i in 1..indexed_frames.len() {
            let hist_prev = self.build_histogram(&indexed_frames[i-1]);
            let hist_curr = self.build_histogram(&indexed_frames[i]);
            
            // Chi-squared distance between histograms
            let similarity = self.histogram_similarity(&hist_prev, &hist_curr);
            stability_scores.push(similarity);
        }
        
        Ok(stability_scores.iter().sum::<f32>() / stability_scores.len() as f32)
    }
}
```

**Acceptance Criteria:**
- `cargo test -p m2-quant` passes with new tests:
  - Global palette ≤ 256 colors
  - All indices < palette size
  - Stability > 0.8 for stable scenes

### Task 3 — M3 Encoder (Decode-free Path)

#### 3.1 Pure Assembly from QuantizedCubeData

```rust
// rust-core/crates/m3-gif/src/lib.rs

impl Gif89aEncoder {
    /// Encode GIF89a strictly from pre-quantized data (no quantization)
    pub fn encode_from_cube_data(
        &self, 
        cube: &QuantizedCubeData, 
        fps_cs: u8, 
        loop_forever: bool
    ) -> Result<Vec<u8>, GifPipeError> {
        let span = span!(Level::INFO, "M3_encode_cube",
            frames = 81,
            palette_size = cube.global_palette_rgb.len() / 3,
            stability = cube.palette_stability
        );
        let _guard = span.enter();
        
        // Validate cube structure
        if cube.indexed_frames.len() != 81 {
            return Err(GifPipeError::ValidationFailed {
                message: format!("Expected 81 frames, got {}", cube.indexed_frames.len())
            });
        }
        
        if cube.global_palette_rgb.len() % 3 != 0 || cube.global_palette_rgb.len() > 768 {
            return Err(GifPipeError::ValidationFailed {
                message: "Invalid palette size".to_string()
            });
        }
        
        let mut gif_bytes = Vec::new();
        
        // GIF89a header + logical screen descriptor
        self.write_gif89a_header(&mut gif_bytes, 81, 81)?;
        
        // Global color table (palette)
        self.write_global_color_table(&mut gif_bytes, &cube.global_palette_rgb)?;
        
        // NETSCAPE2.0 loop extension for infinite loop
        if loop_forever {
            self.write_netscape_loop(&mut gif_bytes)?;
        }
        
        // Write 81 frames
        for (idx, frame_indices) in cube.indexed_frames.iter().enumerate() {
            self.write_image_descriptor(&mut gif_bytes, 0, 0, 81, 81)?;
            self.write_lzw_compressed_data(&mut gif_bytes, frame_indices)?;
            
            if idx % 10 == 0 {
                info!(frame = idx, "Encoded frame batch");
            }
        }
        
        // GIF trailer
        gif_bytes.push(0x3B);
        
        info!(
            size_bytes = gif_bytes.len(),
            frames = 81,
            "GIF89a encoding complete"
        );
        
        Ok(gif_bytes)
    }
    
    fn write_global_color_table(&self, gif_bytes: &mut Vec<u8>, palette_rgb: &[u8]) -> Result<(), GifPipeError> {
        // Write palette, pad to 256 entries if needed
        gif_bytes.extend_from_slice(palette_rgb);
        
        let colors_written = palette_rgb.len() / 3;
        if colors_written < 256 {
            let padding = vec![0u8; (256 - colors_written) * 3];
            gif_bytes.extend_from_slice(&padding);
        }
        
        Ok(())
    }
}
```

**Acceptance Criteria:**
- Structural verifier detects 81 frames, valid header/trailer, NETSCAPE2.0 loop
- Golden GIF hash stable within tolerance
- No quantization code in M3 (only assembly)

### Task 4 — Bevy Cube Viewer (Desktop)

#### 4.1 Crate Structure

```toml
# rust-core/cube_viewer_bevy/Cargo.toml
[package]
name = "cube_viewer_bevy"
version = "0.1.0"
edition = "2021"

[dependencies]
bevy = { version = "0.12", features = ["default"] }
common-types = { path = "../crates/common-types" }
serde_json = "1.0"
clap = { version = "4.0", features = ["derive"] }

[target.'cfg(target_os = "android")'.dependencies]
android-activity = "0.5"
```

#### 4.2 Main Application

```rust
// rust-core/cube_viewer_bevy/src/main.rs

use bevy::prelude::*;
use clap::Parser;
use common_types::QuantizedCubeData;
use std::fs;

#[derive(Parser, Debug)]
#[command(author, version, about)]
struct Args {
    #[arg(short, long)]
    input: String,
}

fn main() {
    let args = Args::parse();
    
    // Load quantized cube data
    let json_data = fs::read_to_string(&args.input)
        .expect("Failed to read input file");
    let cube_data: QuantizedCubeData = serde_json::from_str(&json_data)
        .expect("Failed to parse cube data");
    
    App::new()
        .add_plugins(DefaultPlugins)
        .insert_resource(cube_data)
        .add_systems(Startup, setup_cube_scene)
        .add_systems(Update, (rotate_cube, handle_input, update_frame_display))
        .run();
}

#[derive(Component)]
struct CubeRenderer {
    current_frame: usize,
    total_frames: usize,
}

#[derive(Resource)]
struct CubeMaterials {
    palette_texture: Handle<Image>,
    frame_textures: Vec<Handle<Image>>,
    cube_material: Handle<StandardMaterial>,
}

fn setup_cube_scene(
    mut commands: Commands,
    mut meshes: ResMut<Assets<Mesh>>,
    mut materials: ResMut<Assets<StandardMaterial>>,
    mut images: ResMut<Assets<Image>>,
    cube_data: Res<QuantizedCubeData>,
) {
    // Create palette texture (256x1 RGB)
    let palette_texture = create_palette_texture(&cube_data.global_palette_rgb, &mut images);
    
    // Create frame textures (81x81 R8Uint indices)
    let frame_textures = cube_data.indexed_frames.iter()
        .map(|frame| create_index_texture(frame, &mut images))
        .collect();
    
    // Create cube mesh
    let cube_mesh = meshes.add(Mesh::from(shape::Cube { size: 2.0 }));
    
    // Create material with custom shader
    let cube_material = materials.add(StandardMaterial {
        base_color_texture: Some(frame_textures[0].clone()),
        ..default()
    });
    
    // Spawn cube entity
    commands.spawn((
        PbrBundle {
            mesh: cube_mesh,
            material: cube_material.clone(),
            transform: Transform::from_xyz(0.0, 0.0, 0.0),
            ..default()
        },
        CubeRenderer {
            current_frame: 0,
            total_frames: 81,
        },
    ));
    
    // Camera
    commands.spawn(Camera3dBundle {
        transform: Transform::from_xyz(0.0, 0.0, 5.0)
            .looking_at(Vec3::ZERO, Vec3::Y),
        ..default()
    });
    
    // Light
    commands.spawn(DirectionalLightBundle {
        directional_light: DirectionalLight {
            color: Color::WHITE,
            illuminance: 10000.0,
            ..default()
        },
        transform: Transform::from_xyz(4.0, 8.0, 4.0)
            .looking_at(Vec3::ZERO, Vec3::Y),
        ..default()
    });
    
    // Store resources
    commands.insert_resource(CubeMaterials {
        palette_texture,
        frame_textures,
        cube_material,
    });
}

fn create_palette_texture(palette_rgb: &[u8], images: &mut Assets<Image>) -> Handle<Image> {
    let colors = palette_rgb.len() / 3;
    let mut palette_data = Vec::with_capacity(colors * 4);
    
    for chunk in palette_rgb.chunks(3) {
        palette_data.extend_from_slice(chunk);
        palette_data.push(255); // Alpha
    }
    
    // Pad to 256 colors if needed
    while palette_data.len() < 256 * 4 {
        palette_data.extend_from_slice(&[0, 0, 0, 255]);
    }
    
    let image = Image::new(
        Extent3d { width: 256, height: 1, depth_or_array_layers: 1 },
        TextureDimension::D2,
        palette_data,
        TextureFormat::Rgba8Unorm,
    );
    
    images.add(image)
}

fn create_index_texture(indices: &[u8], images: &mut Assets<Image>) -> Handle<Image> {
    let image = Image::new(
        Extent3d { width: 81, height: 81, depth_or_array_layers: 1 },
        TextureDimension::D2,
        indices.to_vec(),
        TextureFormat::R8Uint,
    );
    
    images.add(image)
}

fn rotate_cube(time: Res<Time>, mut query: Query<&mut Transform, With<CubeRenderer>>) {
    for mut transform in &mut query {
        transform.rotate_y(time.delta_seconds() * 0.5);
        transform.rotate_x(time.delta_seconds() * 0.3);
    }
}

fn handle_input(
    keyboard_input: Res<Input<KeyCode>>,
    mut cube_query: Query<&mut CubeRenderer>,
    cube_materials: Res<CubeMaterials>,
    mut materials: ResMut<Assets<StandardMaterial>>,
) {
    let mut cube_renderer = cube_query.single_mut();
    
    let mut frame_changed = false;
    
    if keyboard_input.just_pressed(KeyCode::Right) {
        cube_renderer.current_frame = (cube_renderer.current_frame + 1) % cube_renderer.total_frames;
        frame_changed = true;
    }
    
    if keyboard_input.just_pressed(KeyCode::Left) {
        cube_renderer.current_frame = if cube_renderer.current_frame == 0 {
            cube_renderer.total_frames - 1
        } else {
            cube_renderer.current_frame - 1
        };
        frame_changed = true;
    }
    
    if frame_changed {
        if let Some(material) = materials.get_mut(&cube_materials.cube_material) {
            material.base_color_texture = Some(cube_materials.frame_textures[cube_renderer.current_frame].clone());
        }
    }
}

fn update_frame_display(
    cube_query: Query<&CubeRenderer, Changed<CubeRenderer>>,
) {
    if let Ok(cube_renderer) = cube_query.get_single() {
        println!("Frame: {}/{}", cube_renderer.current_frame + 1, cube_renderer.total_frames);
    }
}
```

**Acceptance Criteria:**
- `cargo run -p cube_viewer_bevy -- --input fixtures/cube.json` opens window
- Colors match frame exported as PNG (dev utility)
- Keyboard controls scrub through 81 frames
- Rotating cube displays with exact quantized colors

### Task 5 — Android Plumbing (Kotlin UI)

#### 5.1 Separate M2/M3 Buttons

```kotlin
// app/src/main/java/com/rgbagif/ui/ProcessingScreen.kt

@Composable
fun ProcessingScreen(
    frames: List<Bitmap>,
    onBack: () -> Unit,
    onPreview: (QuantizedCubeData) -> Unit
) {
    var currentStep by remember { mutableStateOf(ProcessingStep.READY) }
    var quantizedData by remember { mutableStateOf<QuantizedCubeData?>(null) }
    var gifResult by remember { mutableStateOf<GifInfo?>(null) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Step 1: Quantize (M2)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Step 1: Global Palette Quantization",
                    style = MaterialTheme.typography.h6
                )
                Text(
                    text = "Build 256-color palette from all 81 frames",
                    style = MaterialTheme.typography.body2
                )
                
                Button(
                    onClick = { 
                        currentStep = ProcessingStep.QUANTIZING
                        // Launch M2 quantization
                        launchQuantization(frames) { result ->
                            quantizedData = result
                            currentStep = ProcessingStep.QUANTIZED
                        }
                    },
                    enabled = currentStep == ProcessingStep.READY,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    when (currentStep) {
                        ProcessingStep.QUANTIZING -> {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Quantizing...")
                        }
                        else -> Text("Quantize (M2)")
                    }
                }
            }
        }
        
        // Step 2: Preview
        if (quantizedData != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Step 2: Cube Preview",
                        style = MaterialTheme.typography.h6
                    )
                    
                    // Metrics display
                    Text("Palette Stability: ${(quantizedData!!.paletteStability * 100).toInt()}%")
                    Text("Mean ΔE: %.2f".format(quantizedData!!.meanDeltaE))
                    Text("P95 ΔE: %.2f".format(quantizedData!!.p95DeltaE))
                    
                    Button(
                        onClick = { onPreview(quantizedData!!) },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text("Preview Cube")
                    }
                }
            }
        }
        
        // Step 3: Encode GIF (M3)
        if (quantizedData != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Step 3: GIF Encoding",
                        style = MaterialTheme.typography.h6
                    )
                    Text(
                        text = "Encode from quantized data (no re-quantization)",
                        style = MaterialTheme.typography.body2
                    )
                    
                    Button(
                        onClick = {
                            currentStep = ProcessingStep.ENCODING
                            // Launch M3 encoding
                            launchGifEncoding(quantizedData!!) { result ->
                                gifResult = result
                                currentStep = ProcessingStep.COMPLETE
                            }
                        },
                        enabled = currentStep == ProcessingStep.QUANTIZED,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        when (currentStep) {
                            ProcessingStep.ENCODING -> {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Encoding...")
                            }
                            else -> Text("Encode GIF (M3)")
                        }
                    }
                }
            }
        }
    }
}

private fun launchQuantization(
    frames: List<Bitmap>,
    onResult: (QuantizedCubeData) -> Unit
) {
    // Convert bitmaps to byte arrays
    val frameBytes = frames.map { bitmap ->
        val buffer = ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(buffer)
        buffer.array().toList()
    }
    
    // Call M2 via UniFFI
    GlobalScope.launch(Dispatchers.IO) {
        try {
            val result = m2QuantizeForCube(frameBytes)
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        } catch (e: Exception) {
            Log.e("ProcessingScreen", "M2 quantization failed", e)
        }
    }
}

private fun launchGifEncoding(
    cubeData: QuantizedCubeData,
    onResult: (GifInfo) -> Unit
) {
    GlobalScope.launch(Dispatchers.IO) {
        try {
            val result = m3WriteGifFromCube(cubeData, 4u, true)
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        } catch (e: Exception) {
            Log.e("ProcessingScreen", "M3 encoding failed", e)
        }
    }
}

enum class ProcessingStep {
    READY, QUANTIZING, QUANTIZED, ENCODING, COMPLETE
}
```

**Acceptance Criteria:**
- Quantize without encoding; preview thumbnails render correctly
- Encode GIF from saved quantized data—visual match with preview
- Can inspect palette metrics before committing to GIF

### Task 6 — Visual Golden Tests + Thresholds

#### 6.1 Git LFS Setup

```gitattributes
# .gitattributes
fixtures/golden/*.cbor filter=lfs diff=lfs merge=lfs -text
fixtures/golden/*.gif filter=lfs diff=lfs merge=lfs -text
fixtures/golden/*.png filter=lfs diff=lfs merge=lfs -text
```

#### 6.2 Golden Test Infrastructure

```rust
// rust-core/crates/m2-quant/tests/visual_regression.rs

#[cfg(test)]
mod visual_regression {
    use m2_quant::OklabQuantizer;
    use common_types::{Frames81Rgb, QuantizedCubeData};
    use std::fs;
    use sha2::{Sha256, Digest};
    
    #[test]
    fn test_cube_quantization_golden() {
        // Load golden input frames
        let input_path = "fixtures/golden/input_81_frames.cbor";
        let input_data = fs::read(input_path).expect("Failed to read golden input");
        let frames: Frames81Rgb = ciborium::de::from_reader(&input_data[..])
            .expect("Failed to deserialize frames");
        
        // Run quantization
        let quantizer = OklabQuantizer::new(256);
        let cube_data = quantizer.quantize_for_cube(frames).unwrap();
        
        // Load expected metrics
        let metrics_path = "fixtures/golden/expected_metrics.json";
        let metrics_json = fs::read_to_string(metrics_path).unwrap();
        let expected: ExpectedMetrics = serde_json::from_str(&metrics_json).unwrap();
        
        // Validate concrete thresholds
        assert!(
            cube_data.mean_delta_e < expected.max_mean_delta_e,
            "Mean ΔE {} exceeds threshold {}",
            cube_data.mean_delta_e, expected.max_mean_delta_e
        );
        
        assert!(
            cube_data.p95_delta_e < expected.max_p95_delta_e,
            "P95 ΔE {} exceeds threshold {}",
            cube_data.p95_delta_e, expected.max_p95_delta_e
        );
        
        assert!(
            cube_data.palette_stability > expected.min_stability,
            "Palette stability {} below threshold {}",
            cube_data.palette_stability, expected.min_stability
        );
        
        // Validate frame hashes for visual regression
        for (idx, frame) in cube_data.indexed_frames.iter().enumerate() {
            let frame_hash = calculate_frame_hash(frame);
            let expected_hash = &expected.frame_hashes[idx];
            
            assert_eq!(
                frame_hash, *expected_hash,
                "Frame {} hash mismatch: got {}, expected {}",
                idx, frame_hash, expected_hash
            );
        }
    }
    
    #[derive(serde::Deserialize)]
    struct ExpectedMetrics {
        max_mean_delta_e: f32,      // < 2.0
        max_p95_delta_e: f32,       // < 5.0
        min_stability: f32,         // > 0.85
        frame_hashes: Vec<String>,  // SHA256 of each frame
    }
    
    fn calculate_frame_hash(frame: &[u8]) -> String {
        let mut hasher = Sha256::new();
        hasher.update(frame);
        format!("{:x}", hasher.finalize())
    }
}
```

**Acceptance Criteria:**
- `cargo test --workspace` passes golden tests
- CI job runs visual regression testing
- Concrete thresholds enforced: Mean ΔE < 2.0, P95 ΔE < 5.0, Stability > 0.85

## Part 2: Testing Infrastructure

### 2.1 Create Cube Testing Agent

```yaml
# agents/cube_testing_agent.yaml
name: cube_testing_agent
version: "1.0"
type: validator
domain: cube_coherence_testing

system_prompt: |
  You are a specialized testing agent for the 81×81×81 GIF cube vision.
  Your role is to validate:
  1. Global palette is truly shared across all 81 frames
  2. Temporal coherence (minimal palette drift)
  3. Quantized frames are suitable for cube visualization
  4. Color distribution is perceptually balanced
  
  Key invariants:
  - Exactly 81 frames at 81×81 pixels
  - Single global palette ≤256 colors
  - All frame indices reference valid palette entries
  - Palette stability >0.85 for static scenes

tools:
  - name: Task
    subagent_type: general-purpose
    description: "Analyze quantized cube data"
  
  - name: Read
    paths:
      - "rust-core/crates/m2-quant/tests/"
      - "fixtures/golden/"
  
  - name: Bash
    commands:
      - "cargo test -p m2-quant test_global_palette"
      - "cargo test -p m2-quant test_temporal_coherence"
      - "cargo run --bin validate_cube"

tests:
  global_palette_validation:
    description: "Verify all 81 frames use the same palette"
    steps:
      - Load QuantizedCubeData from test fixture
      - For each frame, verify all indices < palette.len()
      - Calculate palette usage across all frames
      - Assert >80% of palette colors are used globally
    
  temporal_coherence:
    description: "Measure frame-to-frame stability"
    steps:
      - Load consecutive frame pairs
      - Calculate histogram difference
      - Assert <10% pixels change color between frames
      - Verify palette_stability metric >0.85
  
  cube_preview_compatibility:
    description: "Ensure data works with CubeVisualizationScreen"
    steps:
      - Export QuantizedCubeData as JSON
      - Verify format matches Kotlin data classes
      - Check indexed frames are valid byte arrays
      - Validate palette is RGB triplets
```

### 2.2 Create Validation Binary

```rust
// rust-core/src/bin/validate_cube.rs

use common_types::{QuantizedCubeData, GifPipeError};
use std::fs;
use std::path::Path;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    env_logger::init();
    
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 2 {
        eprintln!("Usage: validate_cube <path_to_quantized_data.json>");
        std::process::exit(1);
    }
    
    let data_path = Path::new(&args[1]);
    let json_data = fs::read_to_string(data_path)?;
    let cube_data: QuantizedCubeData = serde_json::from_str(&json_data)?;
    
    println!("=== 81×81×81 Cube Validation Report ===\n");
    
    // 1. Frame count validation
    println!("✓ Frame Count: {}/81", cube_data.indexed_frames.len());
    assert_eq!(cube_data.indexed_frames.len(), 81, "Must have exactly 81 frames");
    
    // 2. Frame dimensions validation
    for (idx, frame) in cube_data.indexed_frames.iter().enumerate() {
        assert_eq!(frame.len(), 81 * 81, "Frame {} must be 81×81 pixels", idx);
    }
    println!("✓ All frames are 81×81 pixels");
    
    // 3. Global palette validation
    println!("✓ Global Palette: {} colors", cube_data.global_palette.len());
    assert!(cube_data.global_palette.len() <= 256, "Palette must be ≤256 colors");
    
    // 4. Index validation
    let max_index = (cube_data.global_palette.len() - 1) as u8;
    for (frame_idx, frame) in cube_data.indexed_frames.iter().enumerate() {
        for (pixel_idx, &index) in frame.iter().enumerate() {
            assert!(
                index <= max_index,
                "Frame {} pixel {} has invalid index {}",
                frame_idx, pixel_idx, index
            );
        }
    }
    println!("✓ All indices are valid");
    
    // 5. Temporal coherence validation
    println!("\n=== Temporal Coherence ===");
    println!("Palette Stability: {:.2}%", cube_data.temporal_metrics.palette_stability * 100.0);
    
    let max_drift = cube_data.temporal_metrics.frame_to_frame_drift
        .iter()
        .max_by(|a, b| a.partial_cmp(b).unwrap())
        .unwrap_or(&0.0);
    println!("Max Frame Drift: {:.2}%", max_drift * 100.0);
    
    if cube_data.temporal_metrics.palette_stability < 0.85 {
        eprintln!("⚠️ Warning: Low palette stability for cube coherence");
    }
    
    // 6. Palette usage analysis
    println!("\n=== Palette Usage ===");
    let mut global_usage = vec![0u32; cube_data.global_palette.len()];
    for frame in &cube_data.indexed_frames {
        for &index in frame {
            global_usage[index as usize] += 1;
        }
    }
    
    let unused_colors = global_usage.iter().filter(|&&count| count == 0).count();
    let usage_percent = ((256 - unused_colors) as f32 / 256.0) * 100.0;
    println!("Colors Used: {}/256 ({:.1}%)", 256 - unused_colors, usage_percent);
    
    if unused_colors > 51 {  // >20% unused
        eprintln!("⚠️ Warning: {} colors unused (poor palette utilization)", unused_colors);
    }
    
    // 7. Quality metrics
    println!("\n=== Quality Metrics ===");
    println!("Mean ΔE (Oklab): {:.2}", cube_data.metadata.mean_delta_e);
    println!("P95 ΔE (Oklab): {:.2}", cube_data.metadata.p95_delta_e);
    println!("Processing Time: {}ms", cube_data.metadata.processing_time_ms);
    
    println!("\n✅ Cube validation complete!");
    
    Ok(())
}
```

### 2.3 Integration Tests

```rust
// rust-core/crates/m2-quant/tests/cube_tests.rs

#[cfg(test)]
mod cube_tests {
    use m2_quant::OklabQuantizer;
    use common_types::{Frames81Rgb, QuantizedCubeData};
    
    #[test]
    fn test_global_palette_shared_across_frames() {
        // Generate test frames with known color distribution
        let frames = generate_test_frames_81();
        
        let quantizer = OklabQuantizer::new(256);
        let cube_data = quantizer.quantize_for_cube(frames).unwrap();
        
        // Verify single global palette
        assert_eq!(cube_data.indexed_frames.len(), 81);
        assert!(cube_data.global_palette.len() <= 256);
        
        // Verify all frames use same palette
        for frame in &cube_data.indexed_frames {
            for &index in frame {
                assert!((index as usize) < cube_data.global_palette.len());
            }
        }
    }
    
    #[test]
    fn test_temporal_coherence_stability() {
        // Create frames with gradual color shift
        let frames = generate_gradual_shift_frames();
        
        let quantizer = OklabQuantizer::new(256);
        let cube_data = quantizer.quantize_for_cube(frames).unwrap();
        
        // Verify high temporal stability
        assert!(
            cube_data.temporal_metrics.palette_stability > 0.85,
            "Palette stability {} too low for cube",
            cube_data.temporal_metrics.palette_stability
        );
        
        // Verify low frame-to-frame drift
        for drift in &cube_data.temporal_metrics.frame_to_frame_drift {
            assert!(
                drift < &0.1,
                "Frame drift {} exceeds 10% threshold",
                drift
            );
        }
    }
    
    #[test]
    fn test_cube_visualization_compatibility() {
        let frames = generate_test_frames_81();
        let quantizer = OklabQuantizer::new(256);
        let cube_data = quantizer.quantize_for_cube(frames).unwrap();
        
        // Serialize to JSON (what Kotlin will receive)
        let json = serde_json::to_string(&cube_data).unwrap();
        
        // Deserialize back
        let restored: QuantizedCubeData = serde_json::from_str(&json).unwrap();
        
        // Verify data integrity
        assert_eq!(restored.indexed_frames.len(), 81);
        assert_eq!(restored.global_palette.len(), cube_data.global_palette.len());
        
        // Verify each frame is correct size for 81×81
        for frame in &restored.indexed_frames {
            assert_eq!(frame.len(), 81 * 81);
        }
    }
}
```

## Part 3: Android Integration

### 3.1 Update Kotlin Data Classes

```kotlin
// app/src/main/java/com/rgbagif/data/QuantizedCubeData.kt
package com.rgbagif.data

import kotlinx.serialization.Serializable

@Serializable
data class QuantizedCubeData(
    val globalPalette: List<List<Int>>,  // 256 RGB triplets
    val indexedFrames: List<ByteArray>,  // 81 frames of 81×81 indices
    val attentionMaps: List<FloatArray>, // 81 attention maps
    val paletteUsage: List<PaletteUsage>,
    val temporalMetrics: TemporalMetrics,
    val metadata: CubeMetadata
)

@Serializable
data class PaletteUsage(
    val frameIndex: Int,
    val colorsUsed: Int,
    val mostFrequent: List<Pair<Byte, Float>>,
    val leastFrequent: List<Pair<Byte, Float>>
)

@Serializable
data class TemporalMetrics(
    val paletteStability: Float,
    val frameToFrameDrift: List<Float>,
    val globalColorDistribution: List<Float>
)

@Serializable
data class CubeMetadata(
    val quantizationMethod: String,
    val colorSpace: String,
    val ditheringEnabled: Boolean,
    val processingTimeMs: Long,
    val meanDeltaE: Float,
    val p95DeltaE: Float
)
```

### 3.2 Update Cube Visualization

```kotlin
// app/src/main/java/com/rgbagif/ui/CubeVisualizationScreen.kt

@Composable
fun CubeVisualizationScreen(
    cubeData: QuantizedCubeData,
    onBack: () -> Unit
) {
    // Convert palette to Color objects
    val palette = remember {
        cubeData.globalPalette.map { rgb ->
            Color(rgb[0], rgb[1], rgb[2])
        }
    }
    
    // Convert indexed frames to ImageBitmaps using global palette
    val frameBitmaps = remember {
        cubeData.indexedFrames.map { indices ->
            createBitmapFromIndices(indices, palette, 81, 81)
        }
    }
    
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Temporal metrics display
        Card(
            modifier = Modifier.padding(8.dp)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text("Palette Stability: ${(cubeData.temporalMetrics.paletteStability * 100).toInt()}%")
                Text("Colors Used: ${256 - cubeData.paletteUsage.count { it.colorsUsed == 0 }}/256")
                Text("Mean ΔE: %.2f".format(cubeData.metadata.meanDeltaE))
            }
        }
        
        // 3D Cube visualization
        AndroidView(
            factory = { context ->
                CubeGLSurfaceView(context).apply {
                    setFrames(frameBitmaps)
                    setPalette(palette)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        )
        
        // Frame selector
        LazyRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            items(frameBitmaps.size) { index ->
                Image(
                    bitmap = frameBitmaps[index],
                    contentDescription = "Frame $index",
                    modifier = Modifier
                        .size(64.dp)
                        .padding(2.dp)
                        .clickable { /* Jump to frame */ }
                )
            }
        }
    }
}

private fun createBitmapFromIndices(
    indices: ByteArray,
    palette: List<Color>,
    width: Int,
    height: Int
): ImageBitmap {
    val bitmap = ImageBitmap(width, height)
    indices.forEachIndexed { i, index ->
        val x = i % width
        val y = i / width
        bitmap.setPixel(x, y, palette[index.toInt() and 0xFF].toArgb())
    }
    return bitmap
}
```

## Part 4: Testing Workflow

### 4.1 Manual Testing Steps

```bash
# 1. Build and run quantization tests
cd rust-core
cargo test -p m2-quant test_global_palette -- --nocapture
cargo test -p m2-quant test_temporal_coherence -- --nocapture

# 2. Generate quantized cube data
cargo run --bin generate_cube_data -- \
    --input fixtures/test_frames_729x729 \
    --output quantized_cube.json

# 3. Validate cube data
cargo run --bin validate_cube quantized_cube.json

# 4. Test GIF encoding from cube data
cargo test -p m3-gif test_encode_from_cube

# 5. Compare with original pipeline
cargo bench compare_pipeline_outputs
```

### 4.2 Automated Testing with Agents

```yaml
# .github/workflows/cube_testing.yml
name: Cube Coherence Testing

on:
  push:
    paths:
      - 'rust-core/crates/m2-quant/**'
      - 'rust-core/crates/m3-gif/**'
      - 'rust-core/crates/common-types/**'

jobs:
  test-cube-coherence:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Run Cube Testing Agent
        run: |
          # Initialize MCP server for testing
          uv run agents/cube_testing_agent.py
          
      - name: Validate Global Palette
        run: |
          cargo test -p m2-quant test_global_palette
          
      - name: Validate Temporal Coherence
        run: |
          cargo test -p m2-quant test_temporal_coherence
          
      - name: Generate and Validate Cube Data
        run: |
          cargo run --bin generate_cube_data -- \
            --input fixtures/golden/test_frames \
            --output /tmp/cube.json
          cargo run --bin validate_cube /tmp/cube.json
          
      - name: Test Android Compatibility
        run: |
          # Verify JSON serialization matches Kotlin format
          cargo test -p common-types test_kotlin_serialization
```

## Part 5: MCP Agent Configuration

### 5.1 Create MCP Server for Testing

```python
# agents/mcp_cube_server.py
import asyncio
from mcp import Server, Resource, Tool
from mcp.types import TextContent, ImageContent
import json
import subprocess

server = Server("cube-testing-server")

@server.resource("/quantized_cube_data")
async def get_quantized_data() -> Resource:
    """Provide quantized cube data for testing"""
    result = subprocess.run(
        ["cargo", "run", "--bin", "generate_cube_data"],
        capture_output=True,
        text=True
    )
    return Resource(
        uri="/quantized_cube_data",
        name="Quantized Cube Data",
        content=TextContent(text=result.stdout)
    )

@server.tool("validate_palette")
async def validate_palette(data: dict) -> dict:
    """Validate global palette properties"""
    palette = data.get("globalPalette", [])
    frames = data.get("indexedFrames", [])
    
    # Validate palette is shared
    max_index = len(palette) - 1
    for frame_idx, frame in enumerate(frames):
        for pixel_idx, index in enumerate(frame):
            if index > max_index:
                return {
                    "valid": False,
                    "error": f"Frame {frame_idx} pixel {pixel_idx} has invalid index {index}"
                }
    
    # Calculate usage
    usage = [0] * len(palette)
    for frame in frames:
        for index in frame:
            usage[index] += 1
    
    unused = sum(1 for u in usage if u == 0)
    utilization = (len(palette) - unused) / len(palette)
    
    return {
        "valid": True,
        "paletteSize": len(palette),
        "utilization": utilization,
        "unusedColors": unused
    }

@server.tool("measure_temporal_drift")
async def measure_temporal_drift(data: dict) -> dict:
    """Measure frame-to-frame palette drift"""
    frames = data.get("indexedFrames", [])
    
    drifts = []
    for i in range(1, len(frames)):
        prev_frame = frames[i-1]
        curr_frame = frames[i]
        
        # Count changed pixels
        changed = sum(1 for p, c in zip(prev_frame, curr_frame) if p != c)
        drift = changed / len(prev_frame)
        drifts.append(drift)
    
    return {
        "meanDrift": sum(drifts) / len(drifts) if drifts else 0,
        "maxDrift": max(drifts) if drifts else 0,
        "frameCount": len(frames),
        "drifts": drifts[:10]  # First 10 for inspection
    }

if __name__ == "__main__":
    asyncio.run(server.run())
```

### 5.2 Configure Claude Desktop

```json
// ~/Library/Application Support/Claude/claude_desktop_config.json
{
  "mcpServers": {
    "cube-testing": {
      "command": "uv",
      "args": ["run", "agents/mcp_cube_server.py"],
      "cwd": "/Users/daniel/rgba-gif89a-camera"
    }
  }
}
```

## Acceptance Criteria

### ✅ When Complete:

1. **M2 Exports QuantizedCubeData**
   - Contains global palette shared by all 81 frames
   - Includes indexed frames accessible independently
   - Provides temporal coherence metrics
   - Exports to JSON for Kotlin consumption

2. **M3 Accepts QuantizedCubeData**
   - Can encode GIF from pre-quantized data
   - Validates 81-frame cube structure
   - Preserves global palette in GIF

3. **Testing Infrastructure**
   - `validate_cube` binary validates all invariants
   - Integration tests verify palette sharing
   - Temporal coherence tests pass (>0.85 stability)
   - MCP server provides testing tools

4. **Android Integration**
   - CubeVisualizationScreen uses quantized data directly
   - Shows palette statistics and temporal metrics
   - Renders using exact global palette

5. **Agent Testing**
   - Cube testing agent validates coherence
   - MCP tools measure drift and usage
   - CI/CD runs automated validation

## Summary

This separation enables:
- **Validation** of the global palette before GIF encoding
- **Direct feeding** of quantized frames to cube visualization
- **Testing** of temporal coherence independently
- **Debugging** of quantization issues without GIF complexity
- **MCP-based** testing infrastructure for Claude agents

The key insight is that the 81×81×81 cube requires a **single global palette** shared across all frames for temporal coherence. By separating M2 and M3, we can verify this property and use the quantized data directly for visualization, ensuring WYSIWYG between the cube preview and final GIF.