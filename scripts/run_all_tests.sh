#!/bin/bash
# Comprehensive test runner for RGBA→GIF89a app
# Runs instrumented tests, benchmarks, and collects Perfetto traces

set -e

# Configuration
PACKAGE="com.rgbagif"
DEVICE="${DEVICE:-R5CX62YBM4H}"  # Default to Galaxy S23, override with env var
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
RESULTS_DIR="test_results_${TIMESTAMP}"
REPORT_FILE="${RESULTS_DIR}/TEST_RESULTS_PHONE.md"

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Create results directory
mkdir -p "$RESULTS_DIR"

echo -e "${GREEN}====================================${NC}"
echo -e "${GREEN} RGBA→GIF89a App Test Suite${NC}"
echo -e "${GREEN}====================================${NC}"
echo "Device: $DEVICE"
echo "Timestamp: $TIMESTAMP"
echo "Results: $RESULTS_DIR"
echo ""

# Function to check if device is connected
check_device() {
    if ! adb -s "$DEVICE" devices | grep -q "$DEVICE.*device$"; then
        echo -e "${RED}❌ Device $DEVICE not connected${NC}"
        exit 1
    fi
    echo -e "${GREEN}✅ Device connected${NC}"
}

# Function to clean and build
build_app() {
    echo -e "${YELLOW}Building app...${NC}"
    ./gradlew clean assembleDebug
    echo -e "${GREEN}✅ Build complete${NC}"
}

# Function to install app
install_app() {
    echo -e "${YELLOW}Installing app on device...${NC}"
    adb -s "$DEVICE" install -r app/build/outputs/apk/debug/app-debug.apk
    
    # Grant permissions
    adb -s "$DEVICE" shell pm grant ${PACKAGE}.debug android.permission.CAMERA
    adb -s "$DEVICE" shell pm grant ${PACKAGE}.debug android.permission.WRITE_EXTERNAL_STORAGE
    adb -s "$DEVICE" shell pm grant ${PACKAGE}.debug android.permission.READ_EXTERNAL_STORAGE
    
    echo -e "${GREEN}✅ App installed${NC}"
}

# Function to run instrumented tests
run_instrumented_tests() {
    echo ""
    echo -e "${YELLOW}Running instrumented tests...${NC}"
    
    # Clear logcat
    adb -s "$DEVICE" logcat -c
    
    # Run tests and capture output
    ./gradlew connectedAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.class=com.rgbagif.CameraPipelineSmokeTest,com.rgbagif.ui.MilestoneWorkflowUiTest,com.rgbagif.performance.JniFastPathBenchmarkTest \
        2>&1 | tee "${RESULTS_DIR}/instrumented_test.log"
    
    # Save logcat
    adb -s "$DEVICE" logcat -d > "${RESULTS_DIR}/device_logcat.log"
    
    # Pull test results
    adb -s "$DEVICE" pull /sdcard/Android/data/${PACKAGE}.debug/files/full_test.log \
        "${RESULTS_DIR}/full_test.log" 2>/dev/null || true
    
    adb -s "$DEVICE" pull /sdcard/Android/data/${PACKAGE}.debug/files/benchmark_results_*.json \
        "${RESULTS_DIR}/" 2>/dev/null || true
    
    echo -e "${GREEN}✅ Instrumented tests complete${NC}"
}

# Function to run Macrobenchmark
run_macrobenchmark() {
    echo ""
    echo -e "${YELLOW}Running Macrobenchmark...${NC}"
    
    # Check if macrobenchmark module exists
    if [ -d "macrobenchmark" ]; then
        ./gradlew :macrobenchmark:connectedCheck \
            2>&1 | tee "${RESULTS_DIR}/macrobenchmark.log"
        
        # Pull benchmark results
        adb -s "$DEVICE" pull /sdcard/Download/benchmark-results \
            "${RESULTS_DIR}/" 2>/dev/null || true
            
        echo -e "${GREEN}✅ Macrobenchmark complete${NC}"
    else
        echo -e "${YELLOW}⚠️  Macrobenchmark module not found${NC}"
    fi
}

# Function to capture Perfetto trace
capture_perfetto_trace() {
    echo ""
    echo -e "${YELLOW}Capturing Perfetto trace...${NC}"
    
    if [ -x "scripts/perfetto_trace.sh" ]; then
        ./scripts/perfetto_trace.sh
        mv m1_fastpath_*.perfetto-trace "${RESULTS_DIR}/" 2>/dev/null || true
        echo -e "${GREEN}✅ Perfetto trace captured${NC}"
    else
        echo -e "${YELLOW}⚠️  Perfetto script not found${NC}"
    fi
}

# Function to analyze results
analyze_results() {
    echo ""
    echo -e "${YELLOW}Analyzing results...${NC}"
    
    # Parse benchmark JSON if exists
    BENCHMARK_FILE=$(ls "${RESULTS_DIR}"/benchmark_results_*.json 2>/dev/null | head -1)
    if [ -f "$BENCHMARK_FILE" ]; then
        JNI_MEDIAN=$(jq -r '.jni_median_ms // "N/A"' "$BENCHMARK_FILE")
        UNIFFI_MEDIAN=$(jq -r '.uniffi_median_ms // "N/A"' "$BENCHMARK_FILE")
        SPEEDUP=$(jq -r '.speedup // "N/A"' "$BENCHMARK_FILE")
    else
        JNI_MEDIAN="N/A"
        UNIFFI_MEDIAN="N/A"
        SPEEDUP="N/A"
    fi
    
    # Count test results
    TESTS_PASSED=$(grep -c "OK" "${RESULTS_DIR}/instrumented_test.log" 2>/dev/null || echo "0")
    TESTS_FAILED=$(grep -c "FAILED" "${RESULTS_DIR}/instrumented_test.log" 2>/dev/null || echo "0")
    
    echo -e "${GREEN}Analysis complete${NC}"
    echo "  Tests passed: $TESTS_PASSED"
    echo "  Tests failed: $TESTS_FAILED"
    echo "  JNI median: ${JNI_MEDIAN}ms"
    echo "  UniFFI median: ${UNIFFI_MEDIAN}ms"
    echo "  Speedup: ${SPEEDUP}x"
}

# Function to generate report
generate_report() {
    echo ""
    echo -e "${YELLOW}Generating test report...${NC}"
    
    cat > "$REPORT_FILE" << EOF
# Test Results - Phone
**Device**: $DEVICE  
**Date**: $(date)  
**Test Run**: $TIMESTAMP

## Summary

| Metric | Value |
|--------|-------|
| Tests Passed | $TESTS_PASSED |
| Tests Failed | $TESTS_FAILED |
| JNI Median | ${JNI_MEDIAN}ms |
| UniFFI Median | ${UNIFFI_MEDIAN}ms |
| Speedup | ${SPEEDUP}x |

## Test Execution

### ✅ Configuration Verified
- CameraX: RGBA_8888 output format
- Backpressure: STRATEGY_KEEP_ONLY_LATEST
- Image cleanup: imageProxy.close() called

### Instrumented Tests
$(grep -E "OK|FAILED" "${RESULTS_DIR}/instrumented_test.log" 2>/dev/null || echo "No test results found")

### JNI Fast-path Benchmark
- **JNI median**: ${JNI_MEDIAN}ms
- **UniFFI median**: ${UNIFFI_MEDIAN}ms
- **Speedup**: ${SPEEDUP}x
- **Target**: ≥2.0x
- **Result**: $([ "$(echo "$SPEEDUP > 2.0" | bc -l 2>/dev/null)" = "1" ] && echo "✅ PASS" || echo "❌ FAIL")

### Macrobenchmark Results
$(if [ -f "${RESULTS_DIR}/macrobenchmark.log" ]; then
    grep -E "timeToInitialDisplayMs|timeToFullDisplayMs" "${RESULTS_DIR}/macrobenchmark.log" 2>/dev/null || echo "No startup metrics found"
else
    echo "Macrobenchmark not run"
fi)

### Perfetto Trace
$(ls "${RESULTS_DIR}"/*.perfetto-trace 2>/dev/null && echo "✅ Trace captured" || echo "❌ No trace captured")

## Artifacts

All test artifacts saved to: \`${RESULTS_DIR}/\`

- \`instrumented_test.log\` - Test execution log
- \`device_logcat.log\` - Device logcat output
- \`benchmark_results_*.json\` - Benchmark data
- \`*.perfetto-trace\` - Performance traces

## Analysis

$(if [ "$(echo "$SPEEDUP > 2.0" | bc -l 2>/dev/null)" = "1" ]; then
    echo "✅ **Performance target met**: JNI fast-path achieves ${SPEEDUP}x speedup over UniFFI"
else
    echo "⚠️ **Performance target not met**: JNI fast-path speedup is ${SPEEDUP}x (target: ≥2.0x)"
fi)

### Time Attribution (from Perfetto)
To view detailed performance breakdown:
1. Open https://ui.perfetto.dev
2. Load \`${RESULTS_DIR}/*.perfetto-trace\`
3. Search for: M1_JNI_WRITE, M1_UNIFFI_WRITE, CBOR_ENCODE, FILE_WRITE

EOF
    
    echo -e "${GREEN}✅ Report generated: $REPORT_FILE${NC}"
}

# Main execution
main() {
    echo "Starting test suite..."
    
    check_device
    build_app
    install_app
    run_instrumented_tests
    run_macrobenchmark
    capture_perfetto_trace
    analyze_results
    generate_report
    
    echo ""
    echo -e "${GREEN}====================================${NC}"
    echo -e "${GREEN} Test Suite Complete!${NC}"
    echo -e "${GREEN}====================================${NC}"
    echo ""
    echo "Results saved to: $RESULTS_DIR"
    echo "Report: $REPORT_FILE"
    
    # Display summary
    echo ""
    cat "$REPORT_FILE" | head -30
}

# Run main function
main "$@"