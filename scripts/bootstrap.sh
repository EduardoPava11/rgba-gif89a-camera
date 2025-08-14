#!/bin/bash
# Bootstrap script for Phase 0: Ground Rules & Scaffolding

set -e

echo "🚀 RGBA→GIF89a Camera App Bootstrap"
echo "===================================="

# Check prerequisites
check_requirement() {
    if ! command -v $1 &> /dev/null; then
        echo "❌ $1 is not installed"
        echo "   Please install: $2"
        exit 1
    else
        echo "✅ $1 found"
    fi
}

echo ""
echo "Checking requirements..."
check_requirement "rustc" "https://rustup.rs"
check_requirement "cargo" "https://rustup.rs"
check_requirement "adb" "Android SDK Platform Tools"
check_requirement "java" "JDK 11+"

# Check Android NDK
if [ -z "$ANDROID_NDK_HOME" ]; then
    echo "⚠️  ANDROID_NDK_HOME not set"
    echo "   Trying default location..."
    export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/27.0.12077973"
    if [ ! -d "$ANDROID_NDK_HOME" ]; then
        echo "❌ Android NDK not found at $ANDROID_NDK_HOME"
        echo "   Please install NDK 27.0.12077973 via Android Studio"
        exit 1
    fi
fi
echo "✅ Android NDK at $ANDROID_NDK_HOME"

# Install Rust Android targets
echo ""
echo "Installing Rust Android targets..."
rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android

# Install cargo-ndk
if ! command -v cargo-ndk &> /dev/null; then
    echo "Installing cargo-ndk..."
    cargo install cargo-ndk
fi

# Create cargo config for Android
echo ""
echo "Configuring cargo for Android..."
mkdir -p rust-core/.cargo
cat > rust-core/.cargo/config.toml << EOF
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

# Build Rust library
echo ""
echo "Building Rust library for Android..."
cd rust-core

# Build for primary target first (arm64)
cargo ndk -t arm64-v8a build --release

# Copy to JNI libs
echo ""
echo "Setting up JNI libraries..."
mkdir -p ../app/src/main/jniLibs/arm64-v8a
cp target/aarch64-linux-android/release/libgifpipe.so ../app/src/main/jniLibs/arm64-v8a/ || true

# Generate UniFFI bindings
echo ""
echo "Generating Kotlin bindings..."
if [ -f "src/gifpipe.udl" ]; then
    cargo run --bin uniffi-bindgen generate src/gifpipe.udl \
        --language kotlin \
        --out-dir ../app/src/main/java || echo "⚠️  UniFFI generation needs setup"
else
    echo "⚠️  UDL file not found, skipping binding generation"
fi

cd ..

# Copy model weights if available
echo ""
echo "Setting up model weights..."
if [ -f "$HOME/neural-camera-app/models/go9x9_default_full.mpk" ]; then
    echo "Copying trained Go 9×9 model weights (.mpk format)..."
    cp "$HOME/neural-camera-app/models/go9x9_default_full.mpk" rust-core/assets/
elif [ -f "$HOME/neural-camera-app/training/go9x9_default_full.mpk" ]; then
    echo "Copying trained Go 9×9 model weights from training dir..."
    cp "$HOME/neural-camera-app/training/go9x9_default_full.mpk" rust-core/assets/
else
    echo "⚠️  Go model weights (.mpk) not found. Using random initialization."
    # Create placeholder file with minimal MessagePack structure
    echo -e "\x80" > rust-core/assets/go9x9_default_full.mpk  # Empty MessagePack map
fi

# Build Android app
echo ""
echo "Building Android app..."
./gradlew assembleDebug || echo "⚠️  Gradle build needs configuration"

echo ""
echo "===================================="
echo "✅ Bootstrap complete!"
echo ""
echo "Next steps:"
echo "1. Complete UniFFI interface in rust-core/src/gifpipe.udl"
echo "2. Implement Rust pipeline in rust-core/src/pipeline.rs"
echo "3. Wire up Kotlin UI in app/src/main/java/com/rgbagif/"
echo "4. Run: ./scripts/test_e2e.sh"
echo ""
echo "To rebuild Rust:"
echo "  cd rust-core && cargo ndk build --release"
echo ""
echo "To install app:"
echo "  adb install -r app/build/outputs/apk/debug/app-debug.apk"