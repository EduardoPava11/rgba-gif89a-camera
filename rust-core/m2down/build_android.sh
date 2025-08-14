#!/bin/bash
# Build M2Down UniFFI library for Android with Kotlin bindings
# Required: cargo-ndk and uniffi-bindgen installed

set -e

echo "======================================"
echo "Building M2Down UniFFI Library for Android"
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

# Check uniffi-bindgen is installed
if ! command -v uniffi-bindgen &> /dev/null; then
    echo "Installing uniffi-bindgen..."
    cargo install uniffi_bindgen_cli --version 0.25.0
fi

# Output directories
JNI_DIR="../../app/src/main/jniLibs"
KOTLIN_DIR="../../app/src/main/java"
mkdir -p "$JNI_DIR"
mkdir -p "$KOTLIN_DIR"

# First, generate the Kotlin bindings
echo ""
echo "Generating UniFFI Kotlin bindings..."
uniffi-bindgen generate src/m2down.udl --language kotlin --out-dir "$KOTLIN_DIR"

if [ $? -eq 0 ]; then
    echo "✅ Kotlin bindings generated successfully"
else
    echo "❌ Failed to generate Kotlin bindings"
    exit 1
fi

# Build for each target architecture
TARGETS=("arm64-v8a" "armeabi-v7a" "x86_64" "x86")

for TARGET in "${TARGETS[@]}"; do
    echo ""
    echo "Building for $TARGET..."
    cargo ndk -t "$TARGET" -o "$JNI_DIR" build --release
    
    # Verify the library was created
    if [ -f "$JNI_DIR/$TARGET/libm2down.so" ]; then
        echo "✅ Successfully built libm2down.so for $TARGET"
        
        # Also create a copy with uniffi_ prefix if needed by the bindings
        cp "$JNI_DIR/$TARGET/libm2down.so" "$JNI_DIR/$TARGET/libuniffi_m2down.so"
        echo "✅ Created libuniffi_m2down.so for $TARGET"
        
        ls -lh "$JNI_DIR/$TARGET/"*.so
    else
        echo "❌ Failed to build for $TARGET"
        exit 1
    fi
done

echo ""
echo "======================================"
echo "✅ M2Down UniFFI library built successfully!"
echo "======================================"
echo ""
echo "Native libraries created in: $JNI_DIR"
echo "Kotlin bindings created in: $KOTLIN_DIR/uniffi/m2down/"
echo ""
echo "Libraries:"
find "$JNI_DIR" -name "*m2down.so" -exec ls -lh {} \;
echo ""
echo "Kotlin files:"
find "$KOTLIN_DIR/uniffi" -name "*.kt" 2>/dev/null | head -10