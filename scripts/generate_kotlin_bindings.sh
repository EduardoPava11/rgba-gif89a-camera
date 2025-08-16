#!/bin/bash
set -e

cd "$(dirname "$0")/.."

echo "Building FFI library..."
cd rust-core
cargo build --release -p ffi

echo "Generating Kotlin bindings..."
# Use the uniffi crate's bindgen
cargo run -p uniffi --bin uniffi-bindgen -- \
    generate \
    crates/ffi/src/ffi.udl \
    --language kotlin \
    --out-dir ../app/src/main/java/com/gifpipe/ffi \
    --no-format

echo "Kotlin bindings generated in app/src/main/java/com/gifpipe/ffi"