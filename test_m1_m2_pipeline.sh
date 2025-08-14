#!/bin/bash

# M1→M2 Pipeline Testing Script with Canonical Logging
# Tests the creation of 729×729 frames (M1) and their processing by M2 neural network

set -e

echo "======================================"
echo "M1→M2 Pipeline Canonical Test"
echo "======================================"

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
APP_PACKAGE="com.rgbagif"
LOGCAT_TAG="CanonicalLogger"
TEST_DURATION_SEC=30
PNG_OUTPUT_DIR="/sdcard/test_m1_m2"

# Function to print colored output
print_status() {
    echo -e "${BLUE}[STATUS]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

# Function to check if device is connected
check_device() {
    print_status "Checking ADB device connection..."
    
    DEVICE_COUNT=$(adb devices | grep -v "List of devices" | grep -v "^$" | wc -l)
    if [ "$DEVICE_COUNT" -eq 0 ]; then
        print_error "No ADB devices connected"
        exit 1
    elif [ "$DEVICE_COUNT" -gt 1 ]; then
        print_warning "Multiple devices connected. Using first device."
    fi
    
    DEVICE_ID=$(adb devices | grep -v "List of devices" | grep -v "^$" | head -1 | cut -f1)
    print_success "Connected to device: $DEVICE_ID"
}

# Function to install and start the app
install_and_start_app() {
    print_status "Installing and starting app..."
    
    # Install APK
    APK_PATH="./app/build/outputs/apk/debug/app-debug.apk"
    if [ ! -f "$APK_PATH" ]; then
        print_error "APK not found at $APK_PATH"
        print_status "Building APK first..."
        ./gradlew assembleDebug
    fi
    
    adb install -r "$APK_PATH"
    print_success "APK installed"
    
    # Create test directory on device
    adb shell mkdir -p "$PNG_OUTPUT_DIR"
    
    # Clear previous logs
    adb logcat -c
    
    # Start the app
    adb shell am start -n "$APP_PACKAGE/.MainActivity"
    print_success "App started"
    
    # Wait for app to initialize
    sleep 3
}

# Function to monitor canonical logs
monitor_canonical_logs() {
    print_status "Monitoring canonical logs for $TEST_DURATION_SEC seconds..."
    
    # Create log file with timestamp
    LOG_FILE="m1_m2_test_$(date +%Y%m%d_%H%M%S).log"
    
    # Monitor logs in background
    timeout $TEST_DURATION_SEC adb logcat "$LOGCAT_TAG:I" "*:S" > "$LOG_FILE" &
    LOGCAT_PID=$!
    
    # Show live logs
    echo "=== Live Canonical Logs ==="
    timeout $TEST_DURATION_SEC adb logcat "$LOGCAT_TAG:I" "*:S" | while read -r line; do
        case "$line" in
            *"APP_START"*) print_success "✅ App Started" ;;
            *"CAMERA_INIT"*) print_success "✅ Camera Initialized" ;;
            *"M2_INIT_START"*) print_status "🔄 M2 Initialization Starting..." ;;
            *"M2_INIT_SUCCESS"*) print_success "✅ M2 Neural Network Initialized" ;;
            *"M2_INIT_FAIL"*) print_error "❌ M2 Initialization Failed" ;;
            *"M2_FRAME_BEGIN"*) print_status "🔄 M2 Frame Processing..." ;;
            *"M2_FRAME_END"*) print_success "✅ M2 Frame Complete" ;;
            *"M2_RUST_INIT_START"*) print_status "🔄 Rust M2 Logger Starting..." ;;
            *"M2_RUST_INIT_OK"*) print_success "✅ Rust M2 Logger Ready" ;;
            *"M2_RUST_FRAME_BEGIN"*) print_status "🔄 Rust M2 Frame Processing..." ;;
            *"M2_RUST_FRAME_END"*) print_success "✅ Rust M2 Frame Complete" ;;
            *) echo "$line" ;;
        esac
    done
    
    wait $LOGCAT_PID 2>/dev/null || true
    print_success "Log monitoring complete. Logs saved to: $LOG_FILE"
}

# Function to analyze canonical logs
analyze_logs() {
    LOG_FILE="$1"
    print_status "Analyzing canonical logs..."
    
    if [ ! -f "$LOG_FILE" ]; then
        print_error "Log file not found: $LOG_FILE"
        return 1
    fi
    
    echo "=== Canonical Log Analysis ==="
    
    # Count key events
    APP_STARTS=$(grep -c "APP_START" "$LOG_FILE" || true)
    CAMERA_INITS=$(grep -c "CAMERA_INIT" "$LOG_FILE" || true)
    M2_INIT_STARTS=$(grep -c "M2_INIT_START" "$LOG_FILE" || true)
    M2_INIT_SUCCESS=$(grep -c "M2_INIT_SUCCESS" "$LOG_FILE" || true)
    M2_INIT_FAILS=$(grep -c "M2_INIT_FAIL" "$LOG_FILE" || true)
    M2_FRAME_BEGINS=$(grep -c "M2_FRAME_BEGIN" "$LOG_FILE" || true)
    M2_FRAME_ENDS=$(grep -c "M2_FRAME_END" "$LOG_FILE" || true)
    M2_RUST_INITS=$(grep -c "M2_RUST_INIT_START" "$LOG_FILE" || true)
    M2_RUST_FRAME_BEGINS=$(grep -c "M2_RUST_FRAME_BEGIN" "$LOG_FILE" || true)
    M2_RUST_FRAME_ENDS=$(grep -c "M2_RUST_FRAME_END" "$LOG_FILE" || true)
    
    echo "App Starts:           $APP_STARTS"
    echo "Camera Inits:         $CAMERA_INITS"
    echo "M2 Init Starts:       $M2_INIT_STARTS"
    echo "M2 Init Success:      $M2_INIT_SUCCESS"
    echo "M2 Init Fails:        $M2_INIT_FAILS"
    echo "M2 Frame Begins:      $M2_FRAME_BEGINS"
    echo "M2 Frame Ends:        $M2_FRAME_ENDS"
    echo "Rust M2 Inits:        $M2_RUST_INITS"
    echo "Rust Frame Begins:    $M2_RUST_FRAME_BEGINS"
    echo "Rust Frame Ends:      $M2_RUST_FRAME_ENDS"
    
    echo ""
    echo "=== Pipeline Verification ==="
    
    # Verify critical path
    if [ "$APP_STARTS" -ge 1 ]; then
        print_success "✅ App startup detected"
    else
        print_error "❌ No app startup detected"
    fi
    
    if [ "$M2_INIT_SUCCESS" -ge 1 ]; then
        print_success "✅ M2 neural network initialization successful"
    else
        print_error "❌ M2 neural network failed to initialize"
    fi
    
    if [ "$M2_FRAME_BEGINS" -ge 1 ] && [ "$M2_FRAME_ENDS" -ge 1 ]; then
        print_success "✅ M2 frame processing detected ($M2_FRAME_BEGINS begins, $M2_FRAME_ENDS ends)"
        
        # Calculate completion rate
        if [ "$M2_FRAME_BEGINS" -gt 0 ]; then
            COMPLETION_RATE=$(echo "scale=1; $M2_FRAME_ENDS * 100 / $M2_FRAME_BEGINS" | bc -l 2>/dev/null || echo "N/A")
            echo "M2 Frame Completion Rate: $COMPLETION_RATE%"
        fi
    else
        print_error "❌ M2 frame processing not detected"
    fi
    
    if [ "$M2_RUST_FRAME_BEGINS" -ge 1 ] && [ "$M2_RUST_FRAME_ENDS" -ge 1 ]; then
        print_success "✅ Rust M2 processing detected ($M2_RUST_FRAME_BEGINS begins, $M2_RUST_FRAME_ENDS ends)"
    else
        print_warning "⚠️  Rust M2 processing logs not detected (may not be working)"
    fi
    
    # Show any error patterns
    echo ""
    echo "=== Error Analysis ==="
    ERROR_COUNT=$(grep -i "error\|exception\|fail\|crash" "$LOG_FILE" | grep -v "M2_INIT_FAIL" | wc -l || true)
    if [ "$ERROR_COUNT" -gt 0 ]; then
        print_warning "Found $ERROR_COUNT potential error lines:"
        grep -i "error\|exception\|fail\|crash" "$LOG_FILE" | grep -v "M2_INIT_FAIL" | head -5
    else
        print_success "No error patterns detected in canonical logs"
    fi
}

# Function to verify PNG outputs
verify_png_outputs() {
    print_status "Checking for PNG outputs..."
    
    # List any PNG files created
    PNG_COUNT=$(adb shell find "$PNG_OUTPUT_DIR" -name "*.png" 2>/dev/null | wc -l || echo "0")
    
    if [ "$PNG_COUNT" -gt 0 ]; then
        print_success "✅ Found $PNG_COUNT PNG files in $PNG_OUTPUT_DIR"
        adb shell ls -la "$PNG_OUTPUT_DIR/"*.png | head -10
        
        # Pull a sample PNG for verification
        SAMPLE_PNG=$(adb shell find "$PNG_OUTPUT_DIR" -name "*.png" | head -1)
        if [ -n "$SAMPLE_PNG" ]; then
            LOCAL_PNG="sample_m1_frame.png"
            adb pull "$SAMPLE_PNG" "$LOCAL_PNG"
            print_success "✅ Sample PNG pulled to: $LOCAL_PNG"
            
            # Check PNG dimensions if we have tools
            if command -v identify >/dev/null 2>&1; then
                DIMENSIONS=$(identify "$LOCAL_PNG" 2>/dev/null | cut -d' ' -f3 || echo "unknown")
                print_status "PNG dimensions: $DIMENSIONS"
                
                if echo "$DIMENSIONS" | grep -q "729x729"; then
                    print_success "✅ Correct M1 dimensions (729×729) detected!"
                else
                    print_warning "⚠️  PNG dimensions ($DIMENSIONS) may not be M1 format"
                fi
            fi
        fi
    else
        print_warning "⚠️  No PNG files found - M1 frame capture may not be working"
    fi
}

# Function to display summary
display_summary() {
    LOG_FILE="$1"
    
    echo ""
    echo "======================================"
    echo "M1→M2 Pipeline Test Summary"
    echo "======================================"
    
    if [ -f "$LOG_FILE" ]; then
        M2_SUCCESS=$(grep -c "M2_INIT_SUCCESS" "$LOG_FILE" || echo "0")
        FRAME_COUNT=$(grep -c "M2_FRAME_END" "$LOG_FILE" || echo "0")
        
        if [ "$M2_SUCCESS" -ge 1 ] && [ "$FRAME_COUNT" -ge 1 ]; then
            print_success "🎉 M1→M2 Pipeline is WORKING!"
            print_success "   • M2 neural network initialized successfully"
            print_success "   • $FRAME_COUNT frames processed by M2"
            print_success "   • Canonical logging system operational"
        else
            print_error "❌ M1→M2 Pipeline has issues"
            print_error "   • M2 success: $M2_SUCCESS"
            print_error "   • Frames processed: $FRAME_COUNT"
        fi
    else
        print_error "❌ Cannot generate summary - no log file"
    fi
    
    echo ""
    print_status "Next steps:"
    echo "1. Review canonical logs for detailed pipeline analysis"
    echo "2. Check PNG outputs for M1 frame quality"
    echo "3. Monitor performance metrics via M2 timing stats"
    echo "4. Verify 729×729 frame dimensions in captured PNGs"
}

# Main execution
main() {
    echo "Starting M1→M2 Pipeline Test..."
    echo "This will test M1 (729×729 frame creation) → M2 (neural network processing)"
    echo ""
    
    check_device
    install_and_start_app
    
    LOG_FILE="m1_m2_test_$(date +%Y%m%d_%H%M%S).log"
    monitor_canonical_logs
    
    analyze_logs "$LOG_FILE"
    verify_png_outputs
    display_summary "$LOG_FILE"
    
    print_status "Test complete. Log file: $LOG_FILE"
}

# Run main function
main "$@"
