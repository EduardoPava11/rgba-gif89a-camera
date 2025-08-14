use burn::prelude::*;
use burn::tensor::activation::sigmoid;
use burn_ndarray::{NdArray, NdArrayDevice};
use anyhow::{Result, anyhow};
use crate::go_network::{GoNet9x9, load_go9x9_model, apply_kernel_selection, KernelType};

type Backend = NdArray;

/// 9×9 Go head configuration
const GO_GRID_SIZE: usize = 9;  // 9×9 grid of macrocells
const MACROCELL_SIZE: usize = 9; // Each macrocell covers 9×9 pixels
const OUTPUT_SIZE: usize = 81;   // 81×81 output
const NUM_KERNELS: usize = 6;     // K=6 kernel choices per macrocell

/// Encoder network for feature extraction
#[derive(Module, Debug)]
pub struct Encoder<B: Backend> {
    conv1: Conv2d<B>,
    conv2: Conv2d<B>,
    conv3: Conv2d<B>,
}

impl<B: Backend> Encoder<B> {
    pub fn new(device: &B::Device) -> Self {
        Self {
            conv1: Conv2dConfig::new([7, 32], [3, 3]) // RGBA+3 feedback → 32
                .with_stride([2, 2])
                .with_padding(burn::nn::PaddingConfig2d::Same)
                .init(device),
            conv2: Conv2dConfig::new([32, 64], [3, 3])
                .with_stride([2, 2])
                .with_padding(burn::nn::PaddingConfig2d::Same)
                .init(device),
            conv3: Conv2dConfig::new([64, 128], [3, 3])
                .with_stride([2, 2])
                .with_padding(burn::nn::PaddingConfig2d::Same)
                .init(device),
        }
    }
    
    pub fn forward(&self, x: Tensor<B, 4>) -> Tensor<B, 4> {
        let x = self.conv1.forward(x).relu();
        let x = self.conv2.forward(x).relu();
        self.conv3.forward(x).relu()
    }
}

/// Go policy head for kernel selection
#[derive(Module, Debug)]
pub struct GoPolicyHead<B: Backend> {
    conv: Conv2d<B>,
    fc: Linear<B>,
}

impl<B: Backend> GoPolicyHead<B> {
    pub fn new(device: &B::Device) -> Self {
        Self {
            conv: Conv2dConfig::new([128, 32], [1, 1])
                .init(device),
            fc: LinearConfig::new(32 * GO_GRID_SIZE * GO_GRID_SIZE, 
                                  GO_GRID_SIZE * GO_GRID_SIZE * NUM_KERNELS)
                .init(device),
        }
    }
    
    pub fn forward(&self, features: Tensor<B, 4>) -> Tensor<B, 4> {
        let batch_size = features.dims()[0];
        
        // Apply 1×1 conv
        let x = self.conv.forward(features).relu();
        
        // Adaptive pool to 9×9
        let x = adaptive_avg_pool2d(x, [GO_GRID_SIZE, GO_GRID_SIZE]);
        
        // Flatten and apply FC
        let x_flat = x.reshape([batch_size, 32 * GO_GRID_SIZE * GO_GRID_SIZE]);
        let logits = self.fc.forward(x_flat);
        
        // Reshape to [batch, 9, 9, K]
        logits.reshape([batch_size, GO_GRID_SIZE, GO_GRID_SIZE, NUM_KERNELS])
    }
}

/// RGB decoder
#[derive(Module, Debug)]
pub struct DecoderRGB<B: Backend> {
    deconv1: ConvTranspose2d<B>,
    deconv2: ConvTranspose2d<B>,
    deconv3: ConvTranspose2d<B>,
    final_conv: Conv2d<B>,
}

impl<B: Backend> DecoderRGB<B> {
    pub fn new(device: &B::Device) -> Self {
        Self {
            deconv1: ConvTranspose2dConfig::new([128, 64], [4, 4])
                .with_stride([2, 2])
                .with_padding([1, 1])
                .init(device),
            deconv2: ConvTranspose2dConfig::new([64, 32], [4, 4])
                .with_stride([2, 2])
                .with_padding([1, 1])
                .init(device),
            deconv3: ConvTranspose2dConfig::new([32, 16], [4, 4])
                .with_stride([2, 2])
                .with_padding([1, 1])
                .init(device),
            final_conv: Conv2dConfig::new([16, 3], [3, 3])
                .with_padding(burn::nn::PaddingConfig2d::Same)
                .init(device),
        }
    }
    
    pub fn forward(&self, features: Tensor<B, 4>) -> Tensor<B, 4> {
        let x = self.deconv1.forward(features).relu();
        let x = self.deconv2.forward(x).relu();
        let x = self.deconv3.forward(x).relu();
        
        // Ensure output is 81×81
        let x = interpolate_bilinear(x, [OUTPUT_SIZE, OUTPUT_SIZE]);
        
        // Final conv to RGB
        self.final_conv.forward(x).sigmoid() // Sigmoid for 0..1 range
    }
}

/// Alpha decoder
#[derive(Module, Debug)]
pub struct DecoderA<B: Backend> {
    deconv1: ConvTranspose2d<B>,
    deconv2: ConvTranspose2d<B>,
    deconv3: ConvTranspose2d<B>,
    final_conv: Conv2d<B>,
}

impl<B: Backend> DecoderA<B> {
    pub fn new(device: &B::Device) -> Self {
        Self {
            deconv1: ConvTranspose2dConfig::new([128, 64], [4, 4])
                .with_stride([2, 2])
                .with_padding([1, 1])
                .init(device),
            deconv2: ConvTranspose2dConfig::new([64, 32], [4, 4])
                .with_stride([2, 2])
                .with_padding([1, 1])
                .init(device),
            deconv3: ConvTranspose2dConfig::new([32, 16], [4, 4])
                .with_stride([2, 2])
                .with_padding([1, 1])
                .init(device),
            final_conv: Conv2dConfig::new([16, 1], [3, 3])
                .with_padding(burn::nn::PaddingConfig2d::Same)
                .init(device),
        }
    }
    
    pub fn forward(&self, features: Tensor<B, 4>) -> Tensor<B, 4> {
        let x = self.deconv1.forward(features).relu();
        let x = self.deconv2.forward(x).relu();
        let x = self.deconv3.forward(x).relu();
        
        // Ensure output is 81×81
        let x = interpolate_bilinear(x, [OUTPUT_SIZE, OUTPUT_SIZE]);
        
        // Final conv to alpha
        self.final_conv.forward(x).sigmoid() // Sigmoid for 0..1 alpha
    }
}

/// Main 9×9 Go head downsizer using trained Go network
pub struct Downsampler9x9Model {
    go_net: GoNet9x9<Backend>,
    decoder_rgb: DecoderRGB<Backend>,
    decoder_a: DecoderA<Backend>,
    available_kernels: Vec<KernelType>,
}

impl Downsampler9x9Model {
    pub fn new(device: &NdArrayDevice) -> Result<Self> {
        // Load trained Go network
        let go_net = load_go9x9_model(device)?;
        
        // Create decoders
        let decoder_rgb = DecoderRGB::new(device);
        let decoder_a = DecoderA::new(device);
        
        // Define available kernels for selection
        let available_kernels = vec![
            KernelType::Box3x3,
            KernelType::Gaussian3x3,
            KernelType::Box5x5,
            KernelType::Gaussian5x5,
            KernelType::EdgePreserve,
            KernelType::Lanczos,
        ];
        
        Ok(Self {
            go_net,
            decoder_rgb,
            decoder_a,
            available_kernels,
        })
    }
    
    pub fn forward(
        &self,
        rgba: Tensor<Backend, 4>,        // [1, 4, H, W]
        a_prev: Tensor<Backend, 4>,      // [1, 1, 81, 81]
        err_prev: Tensor<Backend, 4>,    // [1, 1, 81, 81]
        usage_prev: Tensor<Backend, 4>,  // [1, 1, 81, 81]
    ) -> (Tensor<Backend, 4>, Tensor<Backend, 4>) {
        // First downsample input to 81×81 for Go network
        let rgba_81 = interpolate_bilinear(rgba, [81, 81]);
        
        // Concatenate with feedback (already at 81×81)
        let input = Tensor::cat(vec![rgba_81, a_prev, err_prev, usage_prev], 1);
        
        // Run Go network to get policy, value, and kernel selections
        let go_output = self.go_net.forward(input);
        
        // Use kernel selections to process features
        let processed_features = apply_kernel_selection(
            go_output.features_81x81.clone(),
            go_output.kernel_logits,
            &self.available_kernels,
            false,  // inference mode
        );
        
        // Decode RGB and alpha using processed features
        let rgb_out = self.decoder_rgb.forward(processed_features.clone());
        let a_out = self.decoder_a.forward(processed_features);
        
        (rgb_out, a_out)
    }
}

/// Public downsizer wrapper
pub struct Downsampler9x9 {
    model: Downsampler9x9Model,
    device: NdArrayDevice,
}

impl Downsampler9x9 {
    pub fn new(output_size: u32) -> Result<Self> {
        if output_size != OUTPUT_SIZE as u32 {
            return Err(anyhow!("Only 81×81 output is supported currently"));
        }
        
        let device = NdArrayDevice::Cpu;
        let model = Downsampler9x9Model::new(&device)?;
        
        Ok(Self { model, device })
    }
    
    pub fn process_frame(
        &mut self,
        rgba: &[u8],
        width: u32,
        height: u32,
        a_prev: &[u8],
        err_prev: &[u8],
        usage_prev: &[u8],
    ) -> Result<(Vec<u8>, Vec<u8>)> {
        // Convert input to tensor [1, 4, H, W]
        let rgba_tensor = bytes_to_tensor_rgba(rgba, width, height, &self.device)?;
        
        // Convert feedback to tensors or create zeros
        let a_tensor = if a_prev.is_empty() {
            Tensor::zeros([1, 1, OUTPUT_SIZE, OUTPUT_SIZE], &self.device)
        } else {
            bytes_to_tensor_gray(a_prev, OUTPUT_SIZE, OUTPUT_SIZE, &self.device)?
        };
        
        let err_tensor = if err_prev.is_empty() {
            Tensor::zeros([1, 1, OUTPUT_SIZE, OUTPUT_SIZE], &self.device)
        } else {
            bytes_to_tensor_gray(err_prev, OUTPUT_SIZE, OUTPUT_SIZE, &self.device)?
        };
        
        let usage_tensor = if usage_prev.is_empty() {
            Tensor::zeros([1, 1, OUTPUT_SIZE, OUTPUT_SIZE], &self.device)
        } else {
            bytes_to_tensor_gray(usage_prev, OUTPUT_SIZE, OUTPUT_SIZE, &self.device)?
        };
        
        // Run model
        let (rgb_tensor, a_tensor) = self.model.forward(
            rgba_tensor,
            a_tensor,
            err_tensor,
            usage_tensor,
        );
        
        // Convert outputs to bytes
        let rgb_bytes = tensor_to_bytes_rgb(rgb_tensor)?;
        let a_bytes = tensor_to_bytes_gray(a_tensor)?;
        
        Ok((rgb_bytes, a_bytes))
    }
}

// Helper functions for tensor conversion
fn bytes_to_tensor_rgba(
    data: &[u8],
    width: u32,
    height: u32,
    device: &NdArrayDevice,
) -> Result<Tensor<Backend, 4>> {
    let mut tensor_data = vec![0.0f32; (width * height * 4) as usize];
    
    for i in 0..tensor_data.len() {
        tensor_data[i] = data[i] as f32 / 255.0;
    }
    
    let tensor = Tensor::from_data(
        tensor_data.as_slice(),
        device,
    ).reshape([1, 4, height as usize, width as usize]);
    
    Ok(tensor)
}

fn bytes_to_tensor_gray(
    data: &[u8],
    width: usize,
    height: usize,
    device: &NdArrayDevice,
) -> Result<Tensor<Backend, 4>> {
    let mut tensor_data = vec![0.0f32; width * height];
    
    for i in 0..tensor_data.len() {
        tensor_data[i] = data[i] as f32 / 255.0;
    }
    
    let tensor = Tensor::from_data(
        tensor_data.as_slice(),
        device,
    ).reshape([1, 1, height, width]);
    
    Ok(tensor)
}

fn tensor_to_bytes_rgb(tensor: Tensor<Backend, 4>) -> Result<Vec<u8>> {
    let data = tensor.reshape([3, OUTPUT_SIZE * OUTPUT_SIZE]).to_data();
    let values = data.as_slice::<f32>()?;
    
    let mut bytes = Vec::with_capacity(OUTPUT_SIZE * OUTPUT_SIZE * 3);
    for pixel in 0..(OUTPUT_SIZE * OUTPUT_SIZE) {
        for c in 0..3 {
            let val = (values[c * OUTPUT_SIZE * OUTPUT_SIZE + pixel] * 255.0) as u8;
            bytes.push(val);
        }
    }
    
    Ok(bytes)
}

fn tensor_to_bytes_gray(tensor: Tensor<Backend, 4>) -> Result<Vec<u8>> {
    let data = tensor.reshape([OUTPUT_SIZE * OUTPUT_SIZE]).to_data();
    let values = data.as_slice::<f32>()?;
    
    let bytes: Vec<u8> = values.iter()
        .map(|&v| (v * 255.0) as u8)
        .collect();
    
    Ok(bytes)
}

// Helper functions (stubs for now)
fn adaptive_avg_pool2d<B: Backend>(
    tensor: Tensor<B, 4>,
    output_size: [usize; 2],
) -> Tensor<B, 4> {
    // Simplified implementation - just use interpolation
    interpolate_bilinear(tensor, output_size)
}

fn interpolate_bilinear<B: Backend>(
    tensor: Tensor<B, 4>,
    output_size: [usize; 2],
) -> Tensor<B, 4> {
    // For now, just return reshaped tensor
    // In production, use proper bilinear interpolation
    tensor
}