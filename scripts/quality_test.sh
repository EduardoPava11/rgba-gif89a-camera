#!/bin/bash
# Quality testing script for Phase 4

set -e

echo "🎯 Quality & Performance Tests"
echo "=============================="

# Test configuration
TEST_INPUT="test_data/reference_729x729.png"
ITERATIONS=10

# Create test data if needed
mkdir -p test_data test_results

# Generate test pattern if not exists
if [ ! -f "$TEST_INPUT" ]; then
    echo "Generating test pattern..."
    # Use ImageMagick to create a test pattern
    convert -size 729x729 \
        radial-gradient:red-blue \
        -swirl 180 \
        "$TEST_INPUT" 2>/dev/null || echo "⚠️  Install ImageMagick for test patterns"
fi

# Performance test
echo ""
echo "Performance Test ($ITERATIONS iterations)..."
TOTAL_TIME=0

for i in $(seq 1 $ITERATIONS); do
    START=$(date +%s%N)
    
    # Run pipeline on test image
    cargo run --release --example process_image -- \
        --input "$TEST_INPUT" \
        --output "test_results/perf_$i.gif" \
        2>/dev/null || true
    
    END=$(date +%s%N)
    ELAPSED=$((($END - $START) / 1000000))
    TOTAL_TIME=$((TOTAL_TIME + ELAPSED))
    
    echo "  Iteration $i: ${ELAPSED}ms"
done

AVG_TIME=$((TOTAL_TIME / ITERATIONS))
echo "Average time: ${AVG_TIME}ms"

if [ $AVG_TIME -lt 50 ]; then
    echo "✅ Performance target met (<50ms)"
else
    echo "⚠️  Performance needs optimization (target: <50ms)"
fi

# Memory test
echo ""
echo "Memory Test..."
if command -v /usr/bin/time &> /dev/null; then
    /usr/bin/time -l cargo run --release --example process_image -- \
        --input "$TEST_INPUT" \
        --output "test_results/memory_test.gif" \
        2>&1 | grep "maximum resident set size"
fi

# Quality metrics
echo ""
echo "Quality Metrics..."

# Determinism test
echo "  Testing determinism..."
cargo run --release --example process_image -- \
    --input "$TEST_INPUT" \
    --output "test_results/determinism_1.gif" \
    --seed 42 2>/dev/null || true

cargo run --release --example process_image -- \
    --input "$TEST_INPUT" \
    --output "test_results/determinism_2.gif" \
    --seed 42 2>/dev/null || true

if command -v md5sum &> /dev/null; then
    HASH1=$(md5sum test_results/determinism_1.gif | awk '{print $1}')
    HASH2=$(md5sum test_results/determinism_2.gif | awk '{print $1}')
    
    if [ "$HASH1" = "$HASH2" ]; then
        echo "  ✅ Deterministic output verified"
    else
        echo "  ❌ Non-deterministic output detected"
    fi
fi

# Palette stability test
echo ""
echo "Palette Stability Test..."
cargo run --release --example analyze_gif -- \
    --input "test_results/capture.gif" \
    --metrics palette_drift,temporal_flicker \
    2>/dev/null || echo "  ⚠️  Analyzer not implemented yet"

# A/B test with libimagequant
echo ""
echo "A/B Comparison..."
echo "  K-means quantizer:"
cargo run --release --features kmeans --example process_image -- \
    --input "$TEST_INPUT" \
    --output "test_results/kmeans.gif" \
    2>/dev/null || true

echo "  Libimagequant:"
cargo run --release --features libimagequant --example process_image -- \
    --input "$TEST_INPUT" \
    --output "test_results/libimagequant.gif" \
    2>/dev/null || true

# Size comparison
if [ -f "test_results/kmeans.gif" ] && [ -f "test_results/libimagequant.gif" ]; then
    SIZE_KMEANS=$(stat -f%z test_results/kmeans.gif 2>/dev/null || stat -c%s test_results/kmeans.gif 2>/dev/null)
    SIZE_LIQ=$(stat -f%z test_results/libimagequant.gif 2>/dev/null || stat -c%s test_results/libimagequant.gif 2>/dev/null)
    
    echo "  K-means size: $((SIZE_KMEANS / 1024))KB"
    echo "  Libimagequant size: $((SIZE_LIQ / 1024))KB"
fi

# GIF compliance check
echo ""
echo "GIF89a Compliance..."
for gif in test_results/*.gif; do
    if [ -f "$gif" ]; then
        # Check header
        HEADER=$(xxd -l 6 -p "$gif" 2>/dev/null | sed 's/\(..\)/\\x\1/g')
        if [[ "$HEADER" == *"474946383961"* ]]; then
            echo "  ✅ $(basename $gif): Valid GIF89a header"
        else
            echo "  ❌ $(basename $gif): Invalid header"
        fi
    fi
done

echo ""
echo "=============================="
echo "Quality Test Complete!"
echo ""
echo "Check test_results/ for:"
echo "  - Performance test outputs"
echo "  - Determinism verification"
echo "  - A/B comparison GIFs"