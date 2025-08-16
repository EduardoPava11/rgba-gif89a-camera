use bevy::prelude::*;
use common_types::QuantizedCubeData;
use crate::loader::{create_palette_texture, create_index_texture};

#[derive(Component)]
pub struct CubeRenderer {
    pub current_frame: usize,
    pub total_frames: usize,
}

#[derive(Resource)]
pub struct CubeMaterials {
    #[allow(dead_code)]
    pub palette_texture: Handle<Image>,
    pub frame_textures: Vec<Handle<Image>>,
    pub cube_material: Handle<StandardMaterial>,
}

pub fn setup_cube_scene(
    mut commands: Commands,
    mut meshes: ResMut<Assets<Mesh>>,
    mut materials: ResMut<Assets<StandardMaterial>>,
    mut images: ResMut<Assets<Image>>,
    cube_data: Res<QuantizedCubeData>,
) {
    println!("Setting up cube scene with {} frames", cube_data.indexed_frames.len());
    
    // Create palette texture (256×1 RGBA)
    let palette_texture = create_palette_texture(&cube_data.global_palette_rgb, &mut images);
    
    // Create frame textures (81×81 R8Uint indices)
    let frame_textures: Vec<_> = cube_data.indexed_frames.iter()
        .map(|frame| create_index_texture(frame, &mut images))
        .collect();
    
    println!("Created {} frame textures", frame_textures.len());
    
    // Create cube mesh
    let cube_mesh = meshes.add(Mesh::from(shape::Cube { size: 2.0 }));
    
    // Create material with first frame texture
    let cube_material = materials.add(StandardMaterial {
        base_color_texture: Some(frame_textures[0].clone()),
        metallic: 0.0,
        perceptual_roughness: 0.8,
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
            total_frames: cube_data.indexed_frames.len(),
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
    
    println!("Cube scene setup complete. Use Left/Right arrows to scrub frames.");
}

pub fn rotate_cube(
    time: Res<Time>, 
    mut query: Query<&mut Transform, With<CubeRenderer>>
) {
    for mut transform in &mut query {
        transform.rotate_y(time.delta_seconds() * 0.5);
        transform.rotate_x(time.delta_seconds() * 0.3);
    }
}

pub fn handle_input(
    keyboard_input: Res<Input<KeyCode>>,
    mut cube_query: Query<&mut CubeRenderer>,
    cube_materials: Option<Res<CubeMaterials>>,
    mut materials: ResMut<Assets<StandardMaterial>>,
) {
    if cube_query.is_empty() || cube_materials.is_none() {
        return;
    }
    
    let mut cube_renderer = cube_query.single_mut();
    let cube_materials = cube_materials.unwrap();
    let mut frame_changed = false;
    
    if keyboard_input.just_pressed(KeyCode::Right) || keyboard_input.just_pressed(KeyCode::D) {
        cube_renderer.current_frame = (cube_renderer.current_frame + 1) % cube_renderer.total_frames;
        frame_changed = true;
    }
    
    if keyboard_input.just_pressed(KeyCode::Left) || keyboard_input.just_pressed(KeyCode::A) {
        cube_renderer.current_frame = if cube_renderer.current_frame == 0 {
            cube_renderer.total_frames - 1
        } else {
            cube_renderer.current_frame - 1
        };
        frame_changed = true;
    }
    
    // Jump to specific frames
    if keyboard_input.just_pressed(KeyCode::Home) {
        cube_renderer.current_frame = 0;
        frame_changed = true;
    }
    
    if keyboard_input.just_pressed(KeyCode::End) {
        cube_renderer.current_frame = cube_renderer.total_frames - 1;
        frame_changed = true;
    }
    
    // Jump by 10 frames
    if keyboard_input.just_pressed(KeyCode::PageUp) {
        cube_renderer.current_frame = (cube_renderer.current_frame + 10) % cube_renderer.total_frames;
        frame_changed = true;
    }
    
    if keyboard_input.just_pressed(KeyCode::PageDown) {
        cube_renderer.current_frame = if cube_renderer.current_frame >= 10 {
            cube_renderer.current_frame - 10
        } else {
            cube_renderer.total_frames - (10 - cube_renderer.current_frame)
        };
        frame_changed = true;
    }
    
    if frame_changed {
        if let Some(material) = materials.get_mut(&cube_materials.cube_material) {
            material.base_color_texture = Some(cube_materials.frame_textures[cube_renderer.current_frame].clone());
        }
    }
}

pub fn update_frame_display(
    cube_query: Query<&CubeRenderer, Changed<CubeRenderer>>,
    cube_data: Res<QuantizedCubeData>,
) {
    if let Ok(cube_renderer) = cube_query.get_single() {
        println!("Frame: {}/{} | Palette Stability: {:.1}% | Mean ΔE: {:.2} | P95 ΔE: {:.2}", 
            cube_renderer.current_frame + 1, 
            cube_renderer.total_frames,
            cube_data.palette_stability * 100.0,
            cube_data.mean_delta_e,
            cube_data.p95_delta_e
        );
    }
}
