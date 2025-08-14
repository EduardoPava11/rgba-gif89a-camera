#!/bin/bash
# Build script for m1fast JNI library

set -e

# Ensure cargo-ndk is installed
if ! command -v cargo-ndk &> /dev/null; then
    echo "Installing cargo-ndk..."
    cargo install cargo-ndk
fi

# Build for Android targets
echo "Building m1fast for Android..."

# Build for arm64-v8a (most modern phones)
cargo ndk -t arm64-v8a -o ../../app/src/main/jniLibs build --release

# Optional: Build for other architectures
# cargo ndk -t armeabi-v7a -o ../../app/src/main/jniLibs build --release
# cargo ndk -t x86_64 -o ../../app/src/main/jniLibs build --release

echo "✅ M1Fast JNI library built successfully"
echo "Output: app/src/main/jniLibs/arm64-v8a/libm1fast.so"