# Burn Weight Export Guide

## Training Repository Export Script

Add this script to your neural-camera-app training repository to export weights in Burn's DefaultFileRecorder format:

### `training/src/bin/export_weights.rs`

```rust
use burn::prelude::*;
use burn::record::{DefaultFileRecorder, FullPrecisionSettings, HalfPrecisionSettings};
use burn_ndarray::NdArrayBackend;
use clap::Parser;
use std::path::PathBuf;

type Backend = NdArrayBackend<f32>;

#[derive(Parser, Debug)]
#[clap(author, version, about = "Export trained Go 9×9 model weights")]
struct Args {
    /// Path to checkpoint file
    #[clap(short, long, default_value = "checkpoints/best_model.pt")]
    checkpoint: PathBuf,
    
    /// Output format
    #[clap(short, long, default_value = "burn-default")]
    format: String,
    
    /// Output path
    #[clap(short, long, default_value = "go9x9_default_full.mpk")]
    output: PathBuf,
    
    /// Use half precision (f16) for smaller file size
    #[clap(long)]
    half_precision: bool,
}

fn main() -> anyhow::Result<()> {
    let args = Args::parse();
    
    // Load your trained model
    let device = Default::default();
    let model = load_trained_model(&args.checkpoint, &device)?;
    
    // Export based on precision setting
    if args.half_precision {
        println!("Exporting with half precision (f16)...");
        let recorder = DefaultFileRecorder::<HalfPrecisionSettings>::new();
        recorder.save(&args.output, &model)?;
        println!("Saved to: {} (half precision)", args.output.display());
    } else {
        println!("Exporting with full precision (f32)...");
        let recorder = DefaultFileRecorder::<FullPrecisionSettings>::new();
        recorder.save(&args.output, &model)?;
        println!("Saved to: {} (full precision)", args.output.display());
    }
    
    // Print file size
    let metadata = std::fs::metadata(&args.output)?;
    println!("File size: {:.2} MB", metadata.len() as f64 / 1_048_576.0);
    
    Ok(())
}

fn load_trained_model(
    checkpoint_path: &PathBuf,
    device: &Backend::Device,
) -> anyhow::Result<GoNet9x9<Backend>> {
    // Your model loading logic here
    // This depends on your training framework
    todo!("Implement based on your training setup")
}
```

### Usage

```bash
# Export with full precision (default)
cargo run --bin export_weights

# Export with custom output path
cargo run --bin export_weights --output ../rgba-gif89a-camera/rust-core/assets/go9x9.mpk

# Export with half precision for smaller size
cargo run --bin export_weights --half-precision --output go9x9_half.mpk
```

## Loading in Camera App

### `rust-core/src/go_network.rs`

```rust
use burn::record::{DefaultFileRecorder, FullPrecisionSettings, HalfPrecisionSettings};

impl GoNet9x9<Backend> {
    /// Load weights from .mpk file
    pub fn load_weights(&mut self, path: &str) -> Result<(), Box<dyn std::error::Error>> {
        // Try loading as full precision first
        let result = self.try_load_full_precision(path);
        
        if result.is_err() {
            // Fallback to half precision
            self.try_load_half_precision(path)?;
        }
        
        Ok(())
    }
    
    fn try_load_full_precision(&mut self, path: &str) -> Result<(), Box<dyn std::error::Error>> {
        let recorder = DefaultFileRecorder::<FullPrecisionSettings>::new();
        let record = recorder.load(path, &self.device)?;
        self.load_record(record);
        log::info!("Loaded full precision weights from {}", path);
        Ok(())
    }
    
    fn try_load_half_precision(&mut self, path: &str) -> Result<(), Box<dyn std::error::Error>> {
        let recorder = DefaultFileRecorder::<HalfPrecisionSettings>::new();
        let record = recorder.load(path, &self.device)?;
        self.load_record(record);
        log::info!("Loaded half precision weights from {}", path);
        Ok(())
    }
}
```

## File Format Details

### DefaultFileRecorder (.mpk)
- **Format**: MessagePack with metadata
- **Extension**: `.mpk` (MessagePack)
- **Metadata**: Includes tensor names, shapes, and dtypes
- **Compression**: Optional zstd compression
- **Precision Options**:
  - `FullPrecisionSettings`: f32 weights (larger, more accurate)
  - `HalfPrecisionSettings`: f16 weights (50% smaller, slight precision loss)

### Size Comparison
| Format | Precision | Size (9×9 Go Net) | Quality Impact |
|--------|-----------|-------------------|----------------|
| Full (f32) | 32-bit | ~12 MB | Baseline |
| Half (f16) | 16-bit | ~6 MB | <0.1% accuracy loss |

### Advantages over Raw Binary
1. **Self-describing**: Contains metadata for validation
2. **Version resilient**: Can evolve schema
3. **Type safe**: Burn validates shapes at load time
4. **Portable**: Works across devices and backends
5. **Debuggable**: Can inspect with MessagePack tools

## Integration Checklist

- [ ] Add export script to training repo
- [ ] Export weights as `go9x9_default_full.mpk`
- [ ] Copy to `rust-core/assets/`
- [ ] Update `go_network.rs` to load weights
- [ ] Test loading on Android device
- [ ] Measure inference speed
- [ ] Consider half-precision if size is an issue

## Troubleshooting

### "Shape mismatch" error
The model architecture changed between training and deployment. Ensure both use identical layer dimensions.

### "Unknown record format" error
The .mpk file may be corrupted or use a different Burn version. Re-export with matching Burn version.

### Large file size
Use half-precision export:
```bash
cargo run --bin export_weights --half-precision
```

### Slow loading
Consider loading weights once at app startup and keeping model in memory.