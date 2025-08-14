#!/bin/bash
# Build M1Fast JNI library for Android
# Required: cargo-ndk installed (cargo install cargo-ndk)

set -e

echo "======================================"
echo "Building M1Fast JNI Library for Android"
echo "======================================"

# Set NDK home
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-/Users/daniel/Library/Android/sdk/ndk/27.0.12077973}"

if [ ! -d "$ANDROID_NDK_HOME" ]; then
    echo "❌ Error: ANDROID_NDK_HOME not found at $ANDROID_NDK_HOME"
    echo "Please set ANDROID_NDK_HOME environment variable"
    exit 1
fi

# Check cargo-ndk is installed
if ! command -v cargo-ndk &> /dev/null; then
    echo "Installing cargo-ndk..."
    cargo install cargo-ndk
fi

# Output directory for JNI libs
OUTPUT_DIR="../../app/src/main/jniLibs"
mkdir -p "$OUTPUT_DIR"

# Build for each target architecture
TARGETS=("arm64-v8a" "armeabi-v7a" "x86_64" "x86")

for TARGET in "${TARGETS[@]}"; do
    echo ""
    echo "Building for $TARGET..."
    cargo ndk -t "$TARGET" -o "$OUTPUT_DIR" build --release
    
    # Verify the library was created
    if [ -f "$OUTPUT_DIR/$TARGET/libm1fast.so" ]; then
        echo "✅ Successfully built libm1fast.so for $TARGET"
        ls -lh "$OUTPUT_DIR/$TARGET/libm1fast.so"
    else
        echo "❌ Failed to build for $TARGET"
        exit 1
    fi
done

echo ""
echo "======================================"
echo "✅ M1Fast JNI library built successfully!"
echo "======================================"
echo ""
echo "Libraries created in: $OUTPUT_DIR"
echo ""
find "$OUTPUT_DIR" -name "libm1fast.so" -exec ls -lh {} \;