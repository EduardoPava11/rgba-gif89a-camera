#!/bin/bash

# Build script for Android Rust library with UniFFI

set -e

# Check for required environment variables
if [ -z "$ANDROID_NDK_HOME" ]; then
    echo "Error: ANDROID_NDK_HOME not set"
    echo "Please set it to your Android NDK path, e.g.:"
    echo "export ANDROID_NDK_HOME=$HOME/Library/Android/sdk/ndk/27.0.12077973"
    exit 1
fi

echo "Building Rust library for Android..."

# Add Android targets if not already added
rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android

# Set up cargo config for Android
mkdir -p .cargo
cat > .cargo/config.toml << EOF
[target.aarch64-linux-android]
ar = "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-ar"
linker = "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android26-clang"

[target.armv7-linux-androideabi]
ar = "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-ar"
linker = "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/armv7a-linux-androideabi26-clang"

[target.i686-linux-android]
ar = "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-ar"
linker = "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/i686-linux-android26-clang"

[target.x86_64-linux-android]
ar = "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-ar"
linker = "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/x86_64-linux-android26-clang"
EOF

# Build for all Android architectures
echo "Building for arm64-v8a..."
cargo build --target aarch64-linux-android --release

echo "Building for armeabi-v7a..."
cargo build --target armv7-linux-androideabi --release

echo "Building for x86..."
cargo build --target i686-linux-android --release

echo "Building for x86_64..."
cargo build --target x86_64-linux-android --release

# Create JNI library directories
JNI_LIBS="../app/src/main/jniLibs"
mkdir -p "$JNI_LIBS/arm64-v8a"
mkdir -p "$JNI_LIBS/armeabi-v7a"
mkdir -p "$JNI_LIBS/x86"
mkdir -p "$JNI_LIBS/x86_64"

# Copy built libraries
cp target/aarch64-linux-android/release/libgifpipe.so "$JNI_LIBS/arm64-v8a/"
cp target/armv7-linux-androideabi/release/libgifpipe.so "$JNI_LIBS/armeabi-v7a/"
cp target/i686-linux-android/release/libgifpipe.so "$JNI_LIBS/x86/"
cp target/x86_64-linux-android/release/libgifpipe.so "$JNI_LIBS/x86_64/"

echo "Generating Kotlin bindings..."
cargo run --bin uniffi-bindgen generate src/gifpipe.udl --language kotlin --out-dir ../app/src/main/java

echo "Build complete! Libraries copied to $JNI_LIBS"