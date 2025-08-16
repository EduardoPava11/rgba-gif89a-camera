use bevy::prelude::*;
use bevy::render::render_resource::{Extent3d, TextureDimension, TextureFormat};

/// Load QuantizedCubeData and convert to GPU textures
pub fn create_palette_texture(palette_rgb: &[u8], images: &mut Assets<Image>) -> Handle<Image> {
    let colors = palette_rgb.len() / 3;
    let mut palette_data = Vec::with_capacity(256 * 4); // RGBA
    
    // Convert RGB to RGBA
    for i in 0..colors {
        let rgb_idx = i * 3;
        palette_data.extend_from_slice(&[
            palette_rgb[rgb_idx],     // R
            palette_rgb[rgb_idx + 1], // G  
            palette_rgb[rgb_idx + 2], // B
            255,                      // A
        ]);
    }
    
    // Pad to 256 colors if needed
    while palette_data.len() < 256 * 4 {
        palette_data.extend_from_slice(&[0, 0, 0, 255]);
    }
    
    let image = Image::new(
        Extent3d { 
            width: 256, 
            height: 1, 
            depth_or_array_layers: 1 
        },
        TextureDimension::D2,
        palette_data,
        TextureFormat::Rgba8Unorm,
    );
    
    images.add(image)
}

pub fn create_index_texture(indices: &[u8], images: &mut Assets<Image>) -> Handle<Image> {
    // Validate frame is 81×81
    assert_eq!(indices.len(), 81 * 81, "Frame must be exactly 81×81 pixels");
    
    let image = Image::new(
        Extent3d { 
            width: 81, 
            height: 81, 
            depth_or_array_layers: 1 
        },
        TextureDimension::D2,
        indices.to_vec(),
        TextureFormat::R8Uint,
    );
    
    images.add(image)
}
