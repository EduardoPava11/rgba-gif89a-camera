#!/bin/bash
# Test script for CBOR V2 implementation

echo "====================================="
echo "CBOR V2 IMPLEMENTATION TEST"
echo "====================================="
echo ""

# Build the Rust library
echo "1. Building Rust library..."
cd rust-core
cargo build --release
if [ $? -ne 0 ]; then
    echo "❌ Build failed"
    exit 1
fi
echo "✅ Build successful"
echo ""

# Test Rust module directly
echo "2. Testing CBOR V2 module..."
cargo test cbor_frame_v2 --release
if [ $? -ne 0 ]; then
    echo "❌ Tests failed"
    exit 1
fi
echo "✅ Tests passed"
echo ""

# Generate UniFFI bindings
echo "3. Generating UniFFI bindings..."
cargo run --bin uniffi-bindgen -- generate --language kotlin --out-dir ../app/src/main/java src/gifpipe.udl
if [ $? -ne 0 ]; then
    echo "❌ Binding generation failed"
    exit 1
fi
echo "✅ Bindings generated"
echo ""

# Copy native library for Android
echo "4. Preparing native library..."
ARCH="arm64-v8a"
LIB_DIR="../app/src/main/jniLibs/$ARCH"
mkdir -p "$LIB_DIR"

# Use cargo-ndk if available, otherwise manual copy
if command -v cargo-ndk &> /dev/null; then
    echo "   Building with cargo-ndk..."
    cargo ndk -t aarch64-linux-android -o ../app/src/main/jniLibs build --release
else
    echo "   cargo-ndk not found, using pre-built library"
    # Copy the library if it exists
    if [ -f "target/aarch64-linux-android/release/libgifpipe.so" ]; then
        cp target/aarch64-linux-android/release/libgifpipe.so "$LIB_DIR/"
        echo "   ✅ Library copied"
    else
        echo "   ⚠️  No Android library found, build will need cargo-ndk"
    fi
fi
echo ""

# Build Android app
echo "5. Building Android app..."
cd ../
./gradlew assembleDebug
if [ $? -ne 0 ]; then
    echo "❌ Android build failed"
    exit 1
fi
echo "✅ Android app built"
echo ""

echo "====================================="
echo "SUMMARY"
echo "====================================="
echo "✅ Rust library: Built"
echo "✅ CBOR V2: Implemented"
echo "✅ UniFFI bindings: Generated"
echo "✅ Android app: Ready"
echo ""
echo "Key improvements in M1 CBOR V2:"
echo "• CRC32 integrity checking"
echo "• Enhanced metadata extraction"
echo "• Row stride handling"
echo "• Quality validation"
echo "• Color space tracking"
echo ""
echo "Next steps:"
echo "1. Deploy to device: ./gradlew installDebug"
echo "2. Test capture: adb shell am start -n com.rgbagif/.MainActivity"
echo "3. Verify CBOR files: adb pull /sdcard/Android/data/com.rgbagif/files/"