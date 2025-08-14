use anyhow::{Context, Result};
use clap::Parser;
use gif::{Encoder, Frame, Repeat};
use log::{info, warn};
use serde::{Deserialize, Serialize};
use std::fs::{File, read_dir};
use std::io::BufWriter;
use std::path::PathBuf;

#[derive(Parser, Debug)]
#[command(name = "m3gif-cli")]
#[command(about = "Desktop GIF89a pipeline: RGBA → NN Downsize → NeuQuant → GIF89a")]
struct Args {
    /// Input directory containing CBOR frames
    #[arg(long, value_name = "DIR")]
    in_cbor: PathBuf,
    
    /// Output GIF file path
    #[arg(long, value_name = "FILE")]
    out: PathBuf,
    
    /// Input frame width (expected)
    #[arg(long, default_value = "729")]
    w: u32,
    
    /// Input frame height (expected)  
    #[arg(long, default_value = "729")]
    h: u32,
    
    /// Target downsized dimensions (square)
    #[arg(long, default_value = "81")]
    target: u32,
    
    /// Frame delay in centiseconds (~4cs ≈ 25fps)
    #[arg(long, default_value = "4")]
    delay_cs: u16,
    
    /// Enable infinite loop
    #[arg(long)]
    r#loop: bool,
    
    /// Quantization method
    #[arg(long, default_value = "neuquant")]
    quant: String,
    
    /// NeuQuant sample factor (1=best, 30=fastest)
    #[arg(long, default_value = "10")]
    samplefac: i32,
}

#[derive(Serialize, Deserialize, Debug)]
struct CurrentCborFrame {
    w: u32,
    h: u32,
    format: String,  // "RGBA8888"
    stride: u32,     // Row stride in bytes
    ts_ms: u64,      // Timestamp
    frame_index: u32,
    #[serde(with = "serde_bytes")]
    data: Vec<u8>,   // Raw RGBA data with stride padding
}

impl CurrentCborFrame {
    fn to_tight_rgba(&self) -> Vec<u8> {
        let bytes_per_pixel = 4;
        let expected_row_bytes = self.w * bytes_per_pixel;
        
        if self.stride == expected_row_bytes {
            // No padding, return as-is
            self.data.clone()
        } else {
            // Remove stride padding
            let mut tight_data = Vec::with_capacity((self.w * self.h * bytes_per_pixel) as usize);
            for y in 0..self.h {
                let row_start = (y * self.stride) as usize;
                let row_end = row_start + (expected_row_bytes as usize);
                if row_end <= self.data.len() {
                    tight_data.extend_from_slice(&self.data[row_start..row_end]);
                }
            }
            tight_data
        }
    }
}

#[derive(Serialize, Deserialize, Debug)]
struct RgbaFrame {
    width: u32,
    height: u32,
    data: Vec<u8>, // RGBA bytes
}

fn main() -> Result<()> {
    env_logger::init();
    let args = Args::parse();
    
    info!("M3GIF CLI: RGBA→NN→Quant→GIF89a pipeline");
    info!("Input: {:?}, Output: {:?}", args.in_cbor, args.out);
    info!("Dimensions: {}×{} → {}×{}", args.w, args.h, args.target, args.target);
    
    // Step 1: Load CBOR frames
    let rgba_frames = load_cbor_frames(&args.in_cbor, args.w, args.h)?;
    info!("Loaded {} RGBA frames", rgba_frames.len());
    
    // Step 2: Downsize 729→81 (M2) 
    let downsized_frames = downsize_frames(&rgba_frames, args.target)?;
    info!("Downsized to {}×{}", args.target, args.target);
    
    // Step 3: Quantize each frame (M3.1)
    let quantized_frames = quantize_frames(&downsized_frames, args.samplefac)?;
    info!("Quantized {} frames with NeuQuant", quantized_frames.len());
    
    // Step 4: Encode GIF89a (M3.2)
    encode_gif89a(&quantized_frames, &args.out, args.delay_cs, args.r#loop)?;
    info!("Encoded GIF89a: {:?}", args.out);
    
    Ok(())
}

fn load_cbor_frames(cbor_dir: &PathBuf, expected_w: u32, expected_h: u32) -> Result<Vec<RgbaFrame>> {
    let mut frames = Vec::new();
    let mut entries: Vec<_> = read_dir(cbor_dir)?
        .filter_map(|entry| entry.ok())
        .filter(|entry| entry.path().extension().map_or(false, |ext| ext == "cbor"))
        .collect();
    
    entries.sort_by_key(|entry| entry.path());
    
    for entry in entries {
        let path = entry.path();
        info!("Loading: {:?}", path);
        
        let file = File::open(&path)?;
        let cbor_frame: CurrentCborFrame = serde_cbor::from_reader(file)
            .with_context(|| format!("Failed to parse CBOR: {:?}", path))?;
        
        // Convert to tight RGBA format
        let tight_rgba = cbor_frame.to_tight_rgba();
        
        let frame = RgbaFrame {
            width: cbor_frame.w,
            height: cbor_frame.h,
            data: tight_rgba,
        };
        
        // Validate dimensions
        if frame.width != expected_w || frame.height != expected_h {
            warn!("Frame dimension mismatch: {}×{} (expected {}×{})", 
                  frame.width, frame.height, expected_w, expected_h);
        }
        
        // Validate RGBA data size
        let expected_bytes = (frame.width * frame.height * 4) as usize;
        if frame.data.len() != expected_bytes {
            warn!("Frame data size mismatch: {} bytes (expected {})", 
                  frame.data.len(), expected_bytes);
        }
        
        info!("Frame {} ({}×{}): {} tight RGBA bytes from stride={}", 
              cbor_frame.frame_index, frame.width, frame.height, 
              frame.data.len(), cbor_frame.stride);

        frames.push(frame);
    }
    
    info!("Loaded {} frames, first frame: {}×{} ({} bytes)", 
          frames.len(), 
          frames.get(0).map_or(0, |f| f.width),
          frames.get(0).map_or(0, |f| f.height), 
          frames.get(0).map_or(0, |f| f.data.len()));
    
    Ok(frames)
}

fn downsize_frames(rgba_frames: &[RgbaFrame], target_size: u32) -> Result<Vec<RgbaFrame>> {
    let mut downsized = Vec::new();
    
    for (i, frame) in rgba_frames.iter().enumerate() {
        info!("Downsizing frame {}: {}×{} → {}×{}", i, frame.width, frame.height, target_size, target_size);
        
        // For now, use simple bilinear downsampling
        // TODO: Replace with burned-in NN model
        let downsized_data = bilinear_downsize(&frame.data, frame.width, frame.height, target_size);
        
        // Log basic stats
        let avg_rgb = compute_avg_rgb(&downsized_data);
        let nz_ratio = compute_nonzero_ratio(&downsized_data);
        info!("Frame {} stats: avgRGB=({:.1},{:.1},{:.1}), nzRatio={:.3}", 
              i, avg_rgb.0, avg_rgb.1, avg_rgb.2, nz_ratio);
        
        downsized.push(RgbaFrame {
            width: target_size,
            height: target_size,
            data: downsized_data,
        });
    }
    
    Ok(downsized)
}

fn bilinear_downsize(rgba_data: &[u8], src_w: u32, src_h: u32, dst_size: u32) -> Vec<u8> {
    let dst_w = dst_size;
    let dst_h = dst_size;
    let mut dst_data = vec![0u8; (dst_w * dst_h * 4) as usize];
    
    let x_ratio = src_w as f32 / dst_w as f32;
    let y_ratio = src_h as f32 / dst_h as f32;
    
    for dy in 0..dst_h {
        for dx in 0..dst_w {
            let sy = (dy as f32 * y_ratio) as u32;
            let sx = (dx as f32 * x_ratio) as u32;
            
            // Clamp to bounds
            let sy = sy.min(src_h - 1);
            let sx = sx.min(src_w - 1);
            
            let src_idx = ((sy * src_w + sx) * 4) as usize;
            let dst_idx = ((dy * dst_w + dx) * 4) as usize;
            
            if src_idx + 3 < rgba_data.len() && dst_idx + 3 < dst_data.len() {
                dst_data[dst_idx] = rgba_data[src_idx];     // R
                dst_data[dst_idx + 1] = rgba_data[src_idx + 1]; // G  
                dst_data[dst_idx + 2] = rgba_data[src_idx + 2]; // B
                dst_data[dst_idx + 3] = rgba_data[src_idx + 3]; // A
            }
        }
    }
    
    dst_data
}

fn compute_avg_rgb(rgba_data: &[u8]) -> (f32, f32, f32) {
    let pixel_count = rgba_data.len() / 4;
    if pixel_count == 0 { return (0.0, 0.0, 0.0); }
    
    let mut r_sum = 0u64;
    let mut g_sum = 0u64; 
    let mut b_sum = 0u64;
    
    for chunk in rgba_data.chunks_exact(4) {
        r_sum += chunk[0] as u64;
        g_sum += chunk[1] as u64;
        b_sum += chunk[2] as u64;
    }
    
    (
        r_sum as f32 / pixel_count as f32,
        g_sum as f32 / pixel_count as f32, 
        b_sum as f32 / pixel_count as f32,
    )
}

fn compute_nonzero_ratio(rgba_data: &[u8]) -> f32 {
    let pixel_count = rgba_data.len() / 4;
    if pixel_count == 0 { return 0.0; }
    
    let nonzero_count = rgba_data.chunks_exact(4)
        .filter(|chunk| chunk[0] != 0 || chunk[1] != 0 || chunk[2] != 0)
        .count();
    
    nonzero_count as f32 / pixel_count as f32
}

struct QuantizedFrame {
    indices: Vec<u8>,
    palette: Vec<u8>, // RGB palette (up to 256*3 bytes)
    width: u32,
    height: u32,
}

fn quantize_frames(rgba_frames: &[RgbaFrame], sample_factor: i32) -> Result<Vec<QuantizedFrame>> {
    let mut quantized = Vec::new();
    
    for (i, frame) in rgba_frames.iter().enumerate() {
        info!("Quantizing frame {} with NeuQuant (samplefac={})", i, sample_factor);
        
        // Extract RGB data (drop alpha for quantization)
        let rgb_data: Vec<u8> = frame.data
            .chunks_exact(4)
            .flat_map(|rgba| [rgba[0], rgba[1], rgba[2]])
            .collect();
        
        // Run NeuQuant (it expects &[u8] not individual pixels)
        let nq = color_quant::NeuQuant::new(sample_factor, 256, &rgb_data);
        let palette = nq.color_map_rgb();
        
        // Map pixels to indices
        let indices: Vec<u8> = frame.data
            .chunks_exact(4)
            .map(|rgba| {
                // NeuQuant index_of expects [r, g, b, a] not [r, g, b]
                let rgba_pixel = [rgba[0], rgba[1], rgba[2], rgba[3]];
                nq.index_of(&rgba_pixel) as u8
            })
            .collect();
        
        info!("Frame {} quantized: {} colors in palette, {} pixels", 
              i, palette.len() / 3, indices.len());
        
        quantized.push(QuantizedFrame {
            indices,
            palette,
            width: frame.width,
            height: frame.height,
        });
    }
    
    Ok(quantized)
}

fn encode_gif89a(
    quantized_frames: &[QuantizedFrame], 
    output_path: &PathBuf,
    delay_cs: u16,
    infinite_loop: bool,
) -> Result<()> {
    info!("Encoding GIF89a: {} frames, delay={}cs, loop={}", 
          quantized_frames.len(), delay_cs, infinite_loop);
    
    if quantized_frames.is_empty() {
        return Err(anyhow::anyhow!("No frames to encode"));
    }
    
    let first_frame = &quantized_frames[0];
    let width = first_frame.width as u16;
    let height = first_frame.height as u16;
    
    let output_file = File::create(output_path)?;
    let mut encoder = Encoder::new(BufWriter::new(output_file), width, height, &[])?;
    
    if infinite_loop {
        encoder.set_repeat(Repeat::Infinite)?;
    }
    
    for (i, qframe) in quantized_frames.iter().enumerate() {
        info!("Encoding frame {}: {}×{}, {} colors", 
              i, qframe.width, qframe.height, qframe.palette.len() / 3);
        
        let mut frame = Frame::from_indexed_pixels(
            qframe.width as u16, 
            qframe.height as u16,
            &qframe.indices,
            None, // Use global color table instead
        );
        
        // Set the local color table manually if needed
        frame.palette = Some(qframe.palette.clone());
        
        frame.delay = delay_cs;
        
        encoder.write_frame(&frame)?;
    }
    
    // Encoder automatically writes trailer on drop
    drop(encoder);
    
    let file_size = std::fs::metadata(output_path)?.len();
    info!("GIF89a encoded: {} bytes", file_size);
    
    Ok(())
}
