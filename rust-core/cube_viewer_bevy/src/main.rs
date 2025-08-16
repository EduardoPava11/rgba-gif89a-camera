use bevy::prelude::*;
use clap::Parser;
use common_types::QuantizedCubeData;
use std::fs;

mod loader;
mod systems;

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
    
    println!("Loaded cube data: {} frames, {} colors, stability: {:.2}%", 
        cube_data.indexed_frames.len(),
        cube_data.global_palette_rgb.len() / 3,
        cube_data.palette_stability * 100.0
    );
    
    App::new()
        .add_plugins(DefaultPlugins.set(WindowPlugin {
            primary_window: Some(Window {
                title: "81×81×81 Cube Viewer".into(),
                resolution: (800., 600.).into(),
                ..default()
            }),
            ..default()
        }))
        .insert_resource(cube_data)
        .add_systems(Startup, systems::setup_cube_scene)
        .add_systems(Update, (
            systems::rotate_cube, 
            systems::handle_input, 
            systems::update_frame_display
        ))
        .run();
}
