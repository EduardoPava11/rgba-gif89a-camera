#!/bin/bash
set -e

# Navigate to project root
cd "$(dirname "$0")/.."

echo "Building Rust FFI library for Android..."
cd rust-core

# Build for Android targets
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.0.12077973}"

# Build for arm64-v8a (most modern phones)
cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release -p ffi

echo "✅ Built libffi.so for Android arm64-v8a"

# Generate Kotlin bindings manually
echo "Generating Kotlin bindings..."
mkdir -p ../app/src/main/java/com/gifpipe/ffi

# Create the Kotlin bindings file manually since UDL is minimal
cat > ../app/src/main/java/com/gifpipe/ffi/GifPipe.kt << 'EOF'
package com.gifpipe.ffi

import com.sun.jna.Library
import com.sun.jna.Native
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Data classes matching Rust types
data class QuantizedCubeData(
    val width: UShort,
    val height: UShort,
    val globalPaletteRgb: ByteArray,
    val indexedFrames: List<ByteArray>,
    val delaysCs: ByteArray,
    val paletteStability: Float,
    val meanDeltaE: Float,
    val p95DeltaE: Float
)

data class GifInfo(
    val filePath: String,
    val fileSizeBytes: ULong,
    val frameCount: UInt,
    val paletteSize: UInt,
    val hasNetscapeLoop: Boolean,
    val compressionRatio: Float,
    val validationPassed: Boolean,
    val processingTimeMs: ULong,
    val totalProcessingMs: ULong,
    val gifData: ByteArray
)

data class GifValidation(
    val isValid: Boolean,
    val hasGif89aHeader: Boolean,
    val hasNetscapeLoop: Boolean,
    val hasTrailer: Boolean,
    val frameCount: UInt,
    val errors: List<String>
)

// FFI interface
interface GifPipeLib : Library {
    fun init_android_tracing(): String
    fun m2_quantize_for_cube(frames81Rgba: Array<ByteArray>): QuantizedCubeData
    fun m3_write_gif_from_cube(cube: QuantizedCubeData, fpsCs: Byte, loopForever: Boolean): GifInfo
    fun validate_gif_bytes(gifBytes: ByteArray): GifValidation
}

// Singleton for FFI access
object GifPipe {
    private val lib: GifPipeLib by lazy {
        System.loadLibrary("ffi")
        Native.load("ffi", GifPipeLib::class.java)
    }
    
    suspend fun initTracing(): String = withContext(Dispatchers.IO) {
        lib.init_android_tracing()
    }
    
    suspend fun quantizeFrames(frames: List<ByteArray>): QuantizedCubeData = withContext(Dispatchers.IO) {
        require(frames.size == 81) { "Expected 81 frames, got ${frames.size}" }
        lib.m2_quantize_for_cube(frames.toTypedArray())
    }
    
    suspend fun writeGif(cube: QuantizedCubeData, fpsCs: Byte = 4, loop: Boolean = true): GifInfo = withContext(Dispatchers.IO) {
        lib.m3_write_gif_from_cube(cube, fpsCs, loop)
    }
    
    suspend fun validateGif(gifBytes: ByteArray): GifValidation = withContext(Dispatchers.IO) {
        lib.validate_gif_bytes(gifBytes)
    }
}
EOF

echo "✅ Generated Kotlin bindings in app/src/main/java/com/gifpipe/ffi/"
echo ""
echo "Next steps:"
echo "1. Add JNA dependency to app/build.gradle:"
echo "   implementation 'net.java.dev.jna:jna:5.14.0'"
echo "2. Load the library in your Android app:"
echo "   val sessionId = GifPipe.initTracing()"
echo "3. Use the M2/M3 pipeline:"
echo "   val cube = GifPipe.quantizeFrames(frames)"
echo "   val gif = GifPipe.writeGif(cube)"