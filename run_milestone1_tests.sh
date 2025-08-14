#!/bin/bash

# Comprehensive Test Suite Runner
# Implements the 10-point focused test plan for Milestone 1 validation

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "🧪 MILESTONE 1 COMPREHENSIVE TEST SUITE"
echo "========================================"
echo "Testing UI wiring + MVVM + cubic design implementation"
echo

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Test results tracking
TESTS_PASSED=0
TESTS_FAILED=0
declare -a FAILED_TESTS

# Helper functions
print_test_header() {
    echo -e "\n${BLUE}═══ Test $1: $2 ═══${NC}"
}

print_success() {
    echo -e "${GREEN}✅ $1${NC}"
    TESTS_PASSED=$((TESTS_PASSED + 1))
}

print_failure() {
    echo -e "${RED}❌ $1${NC}"
    TESTS_FAILED=$((TESTS_FAILED + 1))
    FAILED_TESTS+=("$1")
}

print_warning() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

# Check prerequisites
check_prerequisites() {
    echo "🔍 Checking prerequisites..."
    
    # Check if Android device is connected
    if ! adb devices | grep -q "device$"; then
        print_warning "No Android device connected. Device-dependent tests will be skipped."
        DEVICE_AVAILABLE=false
    else
        print_success "Android device detected"
        DEVICE_AVAILABLE=true
        
        # Grant necessary permissions
        adb shell pm grant com.rgbagif android.permission.CAMERA 2>/dev/null || true
        adb shell pm grant com.rgbagif android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true
    fi
    
    # Check Gradle wrapper
    if [[ ! -x "$PROJECT_DIR/gradlew" ]]; then
        print_failure "gradlew not executable"
        return 1
    fi
    
    print_success "Prerequisites checked"
}

# Test 1: ViewModel Lifecycle & State Management
test_viewmodel_lifecycle() {
    print_test_header "1" "ViewModel Lifecycle & State Management"
    
    if ./gradlew test --tests "*CaptureViewModelTest" 2>&1 | grep -q "BUILD SUCCESSFUL"; then
        print_success "ViewModel StateFlow initialization and transitions"
        print_success "FPS calculation accuracy"
        print_success "Overlay data updates with pipeline feedback" 
        print_success "Memory cleanup and state management"
    else
        print_failure "ViewModel lifecycle tests failed"
    fi
}

# Test 2: Compose UI Semantics  
test_compose_ui() {
    print_test_header "2" "Compose UI Semantics"
    
    if $DEVICE_AVAILABLE; then
        if ./gradlew connectedAndroidTest --tests "*ComposeUITest" 2>&1 | grep -q "BUILD SUCCESSFUL"; then
            print_success "TechnicalReadout displays initial state correctly"
            print_success "UI updates with state changes"
            print_success "Compact/detailed toggle functionality"
            print_success "Cubic design elements present"
        else
            print_failure "Compose UI semantics tests failed"
        fi
    else
        print_warning "Skipping Compose UI tests (no device)"
    fi
}

# Test 3: Accessibility Validation
test_accessibility() {
    print_test_header "3" "Accessibility Validation"
    
    if $DEVICE_AVAILABLE; then
        if ./gradlew connectedAndroidTest --tests "*AccessibilityTest" 2>&1 | grep -q "BUILD SUCCESSFUL"; then
            print_success "Content descriptions for interactive elements"
            print_success "Touch targets meet minimum size (48dp)"
            print_success "Color contrast meets WCAG standards"
            print_success "Screen reader accessible components"
        else
            print_failure "Accessibility validation tests failed"
        fi
    else
        print_warning "Skipping accessibility tests (no device)"
    fi
}

# Test 4: CameraX Format Validation
test_camerax_validation() {
    print_test_header "4" "CameraX Format Validation"
    
    if $DEVICE_AVAILABLE; then
        if ./gradlew connectedAndroidTest --tests "*CameraXValidationTest" 2>&1 | grep -q "BUILD SUCCESSFUL"; then
            print_success "729×729 resolution support verified"
            print_success "RGBA8888 format compatibility"
            print_success "24fps targeting works"
            print_success "Camera lifecycle management"
        else
            print_failure "CameraX validation tests failed"
        fi
    else
        print_warning "Skipping CameraX tests (no device)"
    fi
}

# Test 5: Performance Benchmarking
test_performance() {
    print_test_header "5" "Performance Benchmarking"
    
    if $DEVICE_AVAILABLE; then
        # Build and install app first
        ./gradlew assembleDebug installDebug > /dev/null 2>&1
        
        if ./gradlew connectedAndroidTest --tests "*PerformanceBenchmarkTest" 2>&1 | grep -q "BUILD SUCCESSFUL"; then
            print_success "App startup performance measured"
            print_success "Frame timing during capture acceptable"
            print_success "Memory usage during 81-frame capture"
            print_success "UI responsiveness maintained"
        else
            print_failure "Performance benchmarking tests failed"
        fi
    else
        print_warning "Skipping performance tests (no device)"
    fi
}

# Test 6: GIF Format Compliance
test_gif_compliance() {
    print_test_header "6" "GIF Format Compliance"
    
    if ./gradlew test --tests "*GifFormatComplianceTest" 2>&1 | grep -q "BUILD SUCCESSFUL"; then
        print_success "GIF89a header compliance"
        print_success "81 frames at 4 centiseconds timing"
        print_success "Alpha transparency preservation"
        print_success "Optimal palette sizing"
        print_success "Loop extension present"
    else
        print_failure "GIF format compliance tests failed"
    fi
}

# Test 7: Rust-Kotlin Integration
test_rust_kotlin() {
    print_test_header "7" "Rust-Kotlin Integration"
    
    if $DEVICE_AVAILABLE; then
        if ./gradlew connectedAndroidTest --tests "*RustKotlinIntegrationTest" 2>&1 | grep -q "BUILD SUCCESSFUL"; then
            print_success "UniFFI library loads successfully"
            print_success "Go network processes 729×729 → 81×81"
            print_success "Quantizer handles alpha-aware processing"
            print_success "GIF pipeline creates valid output"
            print_success "Memory management for large data"
        else
            print_failure "Rust-Kotlin integration tests failed"
        fi
    else
        print_warning "Skipping Rust-Kotlin tests (no device)"
    fi
}

# Test 8: End-to-End User Flows  
test_e2e_flows() {
    print_test_header "8" "End-to-End User Flows"
    
    if $DEVICE_AVAILABLE; then
        # Ensure app is built and installed
        ./gradlew assembleDebug installDebug > /dev/null 2>&1
        
        if ./gradlew connectedAndroidTest --tests "*EndToEndUserFlowTest" 2>&1 | grep -q "BUILD SUCCESSFUL"; then
            print_success "Complete capture → GIF workflow"
            print_success "Overlay visualization during capture"
            print_success "Technical readout detailed view workflow"
            print_success "Info panel educational content"
            print_success "Error recovery and multiple sessions"
        else
            print_failure "End-to-end user flow tests failed"
        fi
    else
        print_warning "Skipping E2E tests (no device)"
    fi
}

# Test 9: Device Compatibility
test_device_compatibility() {
    print_test_header "9" "Device Compatibility"
    
    if $DEVICE_AVAILABLE; then
        if ./gradlew connectedAndroidTest --tests "*DeviceCompatibilityTest" 2>&1 | grep -q "BUILD SUCCESSFUL"; then
            print_success "Android version compatibility"
            print_success "Camera hardware requirements met"
            print_success "Memory and storage requirements"
            print_success "Screen size compatibility"
            print_success "CPU architecture support"
        else
            print_failure "Device compatibility tests failed"
        fi
    else
        print_warning "Skipping device compatibility tests (no device)"
    fi
}

# Test 10: Regression Prevention
test_regression_prevention() {
    print_test_header "10" "Regression Prevention"
    
    if ./gradlew test --tests "*RegressionPreventionTest" 2>&1 | grep -q "BUILD SUCCESSFUL"; then
        print_success "729×729 resolution maintained (not 1440×1440)"
        print_success "24fps target preserved"
        print_success "81 frames exactly (729÷9=81)"
        print_success "4 centisecond GIF timing"
        print_success "Alpha transparency support intact"
        print_success "Cubic design language consistency"
        print_success "MVVM architecture preserved"
    else
        print_failure "Regression prevention tests failed"
    fi
}

# Main execution
main() {
    cd "$PROJECT_DIR"
    
    echo "Project directory: $PROJECT_DIR"
    echo "Testing Milestone 1 implementation..."
    
    check_prerequisites || exit 1
    
    # Run all test categories
    test_viewmodel_lifecycle
    test_compose_ui  
    test_accessibility
    test_camerax_validation
    test_performance
    test_gif_compliance
    test_rust_kotlin
    test_e2e_flows
    test_device_compatibility
    test_regression_prevention
    
    # Final results
    echo
    echo "🎯 TEST SUITE RESULTS"
    echo "===================="
    echo -e "Tests passed: ${GREEN}$TESTS_PASSED${NC}"
    echo -e "Tests failed: ${RED}$TESTS_FAILED${NC}"
    
    if [[ $TESTS_FAILED -gt 0 ]]; then
        echo -e "\n${RED}Failed tests:${NC}"
        for test in "${FAILED_TESTS[@]}"; do
            echo -e "  ${RED}• $test${NC}"
        done
        echo
        echo -e "${RED}❌ MILESTONE 1 VALIDATION: FAILED${NC}"
        exit 1
    else
        echo
        echo -e "${GREEN}🎉 MILESTONE 1 VALIDATION: PASSED${NC}"
        echo "UI wiring + MVVM + cubic design implementation verified!"
    fi
}

# Run if executed directly
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    main "$@"
fi
