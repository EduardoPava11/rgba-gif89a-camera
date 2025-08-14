#!/bin/bash
# Build Rust m3gif library for Android

set -e

ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.0.12077973}"

if [ ! -d "$ANDROID_NDK_HOME" ]; then
    echo "Error: ANDROID_NDK_HOME not found at $ANDROID_NDK_HOME"
    exit 1
fi

export ANDROID_NDK_HOME

echo "Building for Android with NDK at: $ANDROID_NDK_HOME"

# Build for all Android architectures
# cargo-ndk drops the .so files directly with correct names - no renaming needed
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -t x86 -o ../../app/src/main/jniLibs build --release

echo "✅ Android libraries built successfully"
ls -la ../../app/src/main/jniLibs/*/libm3gif.so
