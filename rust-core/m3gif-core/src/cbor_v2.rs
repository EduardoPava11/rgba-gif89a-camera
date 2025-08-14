use anyhow::Result;
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct CurrentCborFrame {
    #[serde(with = "serde_bytes")]
    pub data: Vec<u8>,
    pub stride: u32,
    pub width: u32,
    pub height: u32,
    pub format: String,
    pub timestamp_ms: u64,
}

impl CurrentCborFrame {
    pub fn new(
        data: Vec<u8>,
        stride: u32,
        width: u32,
        height: u32,
        format: String,
        timestamp_ms: u64,
    ) -> Self {
        Self {
            data,
            stride,
            width,
            height,
            format,
            timestamp_ms,
        }
    }
    
    pub fn get_rgba_row_data(&self) -> Vec<u8> {
        if self.stride == self.width * 4 {
            // No padding, use data directly
            self.data.clone()
        } else {
            // Has stride padding, extract actual row data
            let mut tight_data = Vec::new();
            let bytes_per_row = self.width * 4;
            
            for row in 0..self.height {
                let start_idx = (row * self.stride) as usize;
                let end_idx = start_idx + bytes_per_row as usize;
                tight_data.extend_from_slice(&self.data[start_idx..end_idx]);
            }
            
            tight_data
        }
    }
}

pub fn parse_cbor_frame(cbor_bytes: &[u8]) -> Result<CurrentCborFrame> {
    let frame: CurrentCborFrame = serde_cbor::from_slice(cbor_bytes)?;
    Ok(frame)
}

pub fn serialize_cbor_frame(frame: &CurrentCborFrame) -> Result<Vec<u8>> {
    let cbor_data = serde_cbor::to_vec(frame)?;
    Ok(cbor_data)
}
