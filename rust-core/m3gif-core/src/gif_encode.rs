use anyhow::Result;
use gif::{Encoder, Frame, Repeat};
use std::fs::File;
use std::io::BufWriter;
use log::info;

pub struct GifEncodeOptions {
    pub delay_cs: u16,
    pub loop_gif: bool,
}

impl Default for GifEncodeOptions {
    fn default() -> Self {
        Self {
            delay_cs: 4, // 4 centiseconds = ~24 fps
            loop_gif: true,
        }
    }
}

pub fn encode_gif89a_from_quantized(
    output_path: &str,
    quantized_frames: Vec<(Vec<u8>, Vec<u8>)>,
    width: u32,
    height: u32,
    options: Option<GifEncodeOptions>,
) -> Result<()> {
    let options = options.unwrap_or_default();
    
    let file = File::create(output_path)?;
    let writer = BufWriter::new(file);
    
    let mut encoder = Encoder::new(writer, width as u16, height as u16, &[])?;
    
    if options.loop_gif {
        encoder.set_repeat(Repeat::Infinite)?;
    }
    
    info!("Encoding {} frames to GIF89a at {}x{}", quantized_frames.len(), width, height);
    
    for (frame_idx, (indices, palette)) in quantized_frames.iter().enumerate() {
        let mut frame = Frame::from_palette_pixels(
            width as u16,
            height as u16,
            indices,
            palette,
            None, // no transparency
        );
        
        frame.delay = options.delay_cs;
        
        encoder.write_frame(&frame)?;
        
        if frame_idx % 10 == 0 {
            info!("Encoded frame {}/{}", frame_idx + 1, quantized_frames.len());
        }
    }
    
    info!("GIF89a encoding complete: {}", output_path);
    Ok(())
}

pub fn encode_rgba_frames_to_gif89a(
    output_path: &str,
    frames: &[Vec<u8>],
    width: u32,
    height: u32,
    encode_options: Option<GifEncodeOptions>,
    quantize_options: Option<crate::quantize::QuantizeOptions>,
) -> Result<()> {
    info!("Starting RGBA→GIF89a encoding pipeline");
    
    // Quantize all frames
    let quantized = crate::quantize::quantize_rgba_frames(frames, width, height, quantize_options)?;
    
    // Encode to GIF
    encode_gif89a_from_quantized(output_path, quantized, width, height, encode_options)
}
