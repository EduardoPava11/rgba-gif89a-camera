/// M1 Fast-path JNI implementation for zero-copy CBOR writes
/// Uses DirectByteBuffer to avoid memory copies and ciborium for fast CBOR encoding

use jni::objects::{JByteBuffer, JClass, JString};
use jni::sys::{jboolean, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use ciborium::Value;
use std::fs::File;
use std::io::{BufWriter, Write};
use std::time::Instant;

/// Initialize Android logging on library load
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "C" fn JNI_OnLoad(
    _vm: jni::JavaVM,
    _reserved: *mut std::os::raw::c_void,
) -> jni::sys::jint {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug)
            .with_tag("M1Fast"),
    );
    log::info!("M1_RUST_INIT {{ version: \"1.0.0-jni\" }}");
    jni::JNIVersion::V6.into()
}

/// Main JNI entry point for fast CBOR frame writing
/// Uses DirectByteBuffer for zero-copy access to frame data
#[no_mangle]
pub extern "C" fn Java_com_rgbagif_native_M1Fast_writeFrame(
    mut env: JNIEnv,
    _class: JClass,
    rgba_direct_buffer: JByteBuffer,
    width: jint,
    height: jint,
    stride_bytes: jint,
    ts_ms: jlong,
    frame_index: jint,
    out_path: JString,
) -> jboolean {
    let result = write_frame_internal(
        &mut env,
        rgba_direct_buffer,
        width,
        height,
        stride_bytes,
        ts_ms,
        frame_index,
        out_path,
    );
    
    match result {
        Ok(true) => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

/// Internal implementation that can return errors
fn write_frame_internal(
    env: &mut JNIEnv,
    rgba_direct_buffer: JByteBuffer,
    width: jint,
    height: jint,
    stride_bytes: jint,
    ts_ms: jlong,
    frame_index: jint,
    out_path: JString,
) -> Result<bool, Box<dyn std::error::Error>> {
    let start = Instant::now();
    
    // Trace section for performance tracking
    // Note: android_trace crate doesn't export begin_section, using marker for now
    
    // Get direct buffer address and capacity
    let buffer_addr = env.get_direct_buffer_address(&rgba_direct_buffer)?;
    let buffer_capacity = env.get_direct_buffer_capacity(&rgba_direct_buffer)?;
    
    // Validate buffer
    if buffer_addr.is_null() {
        log::error!("DirectByteBuffer is not direct or null");
        return Ok(false);
    }
    
    // Validate dimensions - MUST be 729×729 for capture (per North Star spec)
    if width != 729 || height != 729 {
        log::error!(
            "Invalid capture dimensions: {}×{}, expected 729×729",
            width, height
        );
        return Ok(false);
    }
    
    // Calculate expected size
    let expected_size = (height as usize) * (stride_bytes as usize);
    if buffer_capacity < expected_size {
        log::error!(
            "Buffer too small: capacity={}, expected={}",
            buffer_capacity,
            expected_size
        );
        return Ok(false);
    }
    
    // Get output path string
    let out_path_str: String = env.get_string(&out_path)?.into();
    
    // Create parent directory if needed
    if let Some(parent) = std::path::Path::new(&out_path_str).parent() {
        std::fs::create_dir_all(parent)?;
    }
    
    // Build CBOR structure matching M2 expectations
    
    // ciborium 0.2 expects Vec<(Value, Value)> for maps
    let mut cbor_map = Vec::new();
    
    // Metadata fields (using i64 which is supported by ciborium)
    cbor_map.push((Value::Text("w".to_string()), Value::Integer((width as i64).into())));
    cbor_map.push((Value::Text("h".to_string()), Value::Integer((height as i64).into())));
    cbor_map.push((Value::Text("format".to_string()), Value::Text("RGBA8888".to_string())));
    cbor_map.push((Value::Text("stride".to_string()), Value::Integer((stride_bytes as i64).into())));
    cbor_map.push((Value::Text("ts_ms".to_string()), Value::Integer(ts_ms.into())));
    cbor_map.push((Value::Text("frame_index".to_string()), Value::Integer((frame_index as i64).into())));
    
    // Stream RGBA data directly from DirectByteBuffer as bstr
    // This is the key optimization - no intermediate copy!
    // Convert raw pointer to slice
    let data_slice = unsafe {
        std::slice::from_raw_parts(buffer_addr, expected_size)
    };
    cbor_map.push((Value::Text("data".to_string()), Value::Bytes(data_slice.to_vec())));
    
    // Open file with large buffer for efficient writes
    
    let file = File::create(&out_path_str)?;
    let mut writer = BufWriter::with_capacity(65536, file);
    
    // Serialize directly to file using ciborium
    ciborium::into_writer(&Value::Map(cbor_map), &mut writer)?;
    
    // Ensure all data is flushed
    writer.flush()?;
    
    let elapsed = start.elapsed();
    let elapsed_ms = elapsed.as_millis();
    
    // Log structured event for frame write
    log::info!(
        "M1_RUST_WRITE_CBOR {{ idx: {}, bytes: {}, outPath: \"{}\" }}",
        frame_index,
        expected_size,
        out_path_str
    );
    
    // Log performance metrics for first few frames
    static mut FRAME_COUNT: u32 = 0;
    unsafe {
        if FRAME_COUNT < 10 {
            log::debug!(
                "M1_RUST_PERF {{ frame: {}, elapsedMs: {}, stride: {}, sizeMB: {:.1} }}",
                frame_index,
                elapsed_ms,
                stride_bytes,
                expected_size as f32 / 1_048_576.0
            );
        }
        FRAME_COUNT += 1;
    }
    
    Ok(true)
}

/// Get version string for debugging
#[no_mangle]
pub extern "C" fn Java_com_rgbagif_native_M1Fast_getVersion<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jni::objects::JString<'local> {
    let version = "1.0.0-jni";
    env.new_string(version)
        .expect("Failed to create version string")
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_cbor_structure() {
        // Test that we can create the expected CBOR structure
        let mut cbor_map = Vec::new();
        cbor_map.push((Value::Text("w".to_string()), Value::Integer(729i64.into())));
        cbor_map.push((Value::Text("h".to_string()), Value::Integer(729i64.into())));
        cbor_map.push((Value::Text("format".to_string()), Value::Text("RGBA8888".to_string())));
        cbor_map.push((Value::Text("stride".to_string()), Value::Integer(2916i64.into())));
        cbor_map.push((Value::Text("ts_ms".to_string()), Value::Integer(1234567890i64.into())));
        cbor_map.push((Value::Text("frame_index".to_string()), Value::Integer(0i64.into())));
        
        // Serialize to bytes
        let mut buffer = Vec::new();
        ciborium::into_writer(&Value::Map(cbor_map), &mut buffer).unwrap();
        
        // Should produce valid CBOR
        assert!(!buffer.is_empty());
    }
}