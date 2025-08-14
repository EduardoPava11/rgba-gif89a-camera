use anyhow::Result;

pub fn bilinear_downscale_rgba(
    input_data: &[u8],
    input_width: u32,
    input_height: u32,
    output_width: u32,
    output_height: u32,
) -> Result<Vec<u8>> {
    let mut output = vec![0u8; (output_width * output_height * 4) as usize];
    
    let x_ratio = input_width as f32 / output_width as f32;
    let y_ratio = input_height as f32 / output_height as f32;
    
    for y in 0..output_height {
        for x in 0..output_width {
            let src_x = x as f32 * x_ratio;
            let src_y = y as f32 * y_ratio;
            
            let x1 = src_x.floor() as u32;
            let y1 = src_y.floor() as u32;
            let x2 = (x1 + 1).min(input_width - 1);
            let y2 = (y1 + 1).min(input_height - 1);
            
            let x_weight = src_x - x1 as f32;
            let y_weight = src_y - y1 as f32;
            
            // Get the four surrounding pixels
            let top_left = get_pixel(input_data, input_width, x1, y1);
            let top_right = get_pixel(input_data, input_width, x2, y1);
            let bottom_left = get_pixel(input_data, input_width, x1, y2);
            let bottom_right = get_pixel(input_data, input_width, x2, y2);
            
            // Interpolate
            let mut result = [0u8; 4];
            for i in 0..4 {
                let top = top_left[i] as f32 * (1.0 - x_weight) + top_right[i] as f32 * x_weight;
                let bottom = bottom_left[i] as f32 * (1.0 - x_weight) + bottom_right[i] as f32 * x_weight;
                result[i] = (top * (1.0 - y_weight) + bottom * y_weight) as u8;
            }
            
            // Set output pixel
            let out_idx = ((y * output_width + x) * 4) as usize;
            output[out_idx..out_idx + 4].copy_from_slice(&result);
        }
    }
    
    Ok(output)
}

pub fn downscale_rgba_729_to_81(rgba_729: &[u8]) -> Result<Vec<u8>> {
    bilinear_downscale_rgba(rgba_729, 27, 27, 9, 9)
}

fn get_pixel(data: &[u8], width: u32, x: u32, y: u32) -> [u8; 4] {
    let idx = ((y * width + x) * 4) as usize;
    if idx + 3 < data.len() {
        [data[idx], data[idx + 1], data[idx + 2], data[idx + 3]]
    } else {
        [0, 0, 0, 0]
    }
}
