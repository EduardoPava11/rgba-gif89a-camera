#!/bin/bash
# Quick validation that the m3gif-core integration is working

set -e
cd "$(dirname "$0")"

echo "🧪 Testing m3gif-core integration..."

# Test 1: Verify m3gif-core builds
echo "1️⃣ Building m3gif-core..."
cd rust-core/m3gif-core
cargo build
echo "✅ m3gif-core builds successfully"

# Test 2: Verify main rust-core with m3gif-core dependency
echo "2️⃣ Building rust-core with m3gif-core dependency..."
cd ../
cargo build
echo "✅ rust-core builds with m3gif-core dependency"

# Test 3: Verify UniFFI bindings can be generated
echo "3️⃣ Testing UniFFI binding generation..."
mkdir -p test-bindings
cargo run --features=uniffi/cli --bin uniffi-bindgen generate \
  --library target/debug/libgifpipe.dylib \
  --language kotlin \
  --out-dir ./test-bindings
echo "✅ UniFFI bindings generated successfully"

# Test 4: Verify the M2 and M3 functions exist in bindings
echo "4️⃣ Verifying M2+M3 functions in generated bindings..."
if grep -q "m2DownscaleRgba729To81" test-bindings/uniffi/m3gif/gifpipe.kt; then
    echo "✅ M2 downscale function found in Kotlin bindings"
else
    echo "❌ M2 downscale function NOT found in bindings!"
    exit 1
fi

if grep -q "m3Gif89aEncodeRgbaFrames" test-bindings/uniffi/m3gif/gifpipe.kt; then
    echo "✅ M3 GIF encode function found in Kotlin bindings"
else
    echo "❌ M3 GIF encode function NOT found in bindings!"
    exit 1
fi

# Test 5: Verify desktop CLI still works with real data (if available)
echo "5️⃣ Testing with real data (if available)..."
cd ../
if [ -f "desk_out/frames.cbor" ]; then
    echo "Found existing CBOR frames, testing m3gif-cli..."
    cd m3gif-cli
    cargo build
    ./target/debug/m3gif-cli ../desk_out/frames.cbor ../desk_out/integration-test.gif
    
    if [ -f "../desk_out/integration-test.gif" ]; then
        echo "✅ Integration test GIF created successfully"
        ls -la ../desk_out/integration-test.gif
    else
        echo "⚠️ Integration test GIF not created"
    fi
else
    echo "⚠️ No CBOR frames found for integration test"
fi

echo
echo "🎉 Integration validation complete!"
echo
echo "Summary:"
echo "- ✅ m3gif-core library builds successfully"  
echo "- ✅ rust-core integrates with m3gif-core"
echo "- ✅ UniFFI bindings generate correctly"
echo "- ✅ M2 and M3 functions exported to Kotlin"
echo
echo "Ready for Android integration:"
echo "1. Run ./gradlew generateM3UniFFIBindings to generate Android bindings"
echo "2. Run ./gradlew buildM3GifRust to build for Android"
echo "3. Use uniffi.m3gif.m2DownscaleRgba729To81() in Kotlin"
echo "4. Use uniffi.m3gif.m3Gif89aEncodeRgbaFrames() in Kotlin"
