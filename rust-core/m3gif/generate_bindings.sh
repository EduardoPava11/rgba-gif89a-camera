#!/bin/bash
set -e

cd /Users/daniel/rgba-gif89a-camera/rust-core/m3gif

# Use the uniffi-bindgen from cargo
cargo build --release --bin uniffi-bindgen 2>/dev/null || true

# Generate bindings using the same uniffi version as the library
if [ -f target/release/uniffi-bindgen ]; then
    ./target/release/uniffi-bindgen generate src/m3gif.udl --language kotlin --out-dir ../../app/src/main/java
else
    # Use system uniffi-bindgen
    uniffi-bindgen generate src/m3gif.udl --language kotlin --out-dir ../../app/src/main/java
fi

echo "✅ Bindings generated"
