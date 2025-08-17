#!/bin/bash

echo "================================="
echo "🚀 RGBA GIF89a Camera Installer"
echo "================================="
echo ""

# Check for device
echo "📱 Checking for connected devices..."
DEVICE=$(adb devices | grep -v "List" | grep "device" | awk '{print $1}')

if [ -z "$DEVICE" ]; then
    echo "❌ No device found. Please connect your phone and enable USB debugging."
    echo ""
    echo "To connect:"
    echo "1. Enable Developer Options: Settings > About > Tap Build Number 7 times"
    echo "2. Enable USB Debugging: Settings > Developer Options > USB Debugging"
    echo "3. Connect USB cable and tap 'Allow' on phone"
    exit 1
fi

echo "✅ Found device: $DEVICE"
echo ""

# Get device info
MANUFACTURER=$(adb -s $DEVICE shell getprop ro.product.manufacturer | tr -d '\r')
MODEL=$(adb -s $DEVICE shell getprop ro.product.model | tr -d '\r')
ANDROID_VERSION=$(adb -s $DEVICE shell getprop ro.build.version.release | tr -d '\r')

echo "📱 Device Info:"
echo "   Manufacturer: $MANUFACTURER"
echo "   Model: $MODEL"
echo "   Android: $ANDROID_VERSION"
echo ""

# Uninstall old version if exists
echo "🗑️  Removing old version if exists..."
adb -s $DEVICE uninstall com.rgbagif.debug 2>/dev/null

# Install APK
echo "📦 Installing RGBA GIF89a Camera..."
adb -s $DEVICE install -r app/build/outputs/apk/debug/app-debug.apk

if [ $? -eq 0 ]; then
    echo "✅ Installation successful!"
    echo ""
    
    # Launch app
    echo "🚀 Launching app..."
    adb -s $DEVICE shell am start -n com.rgbagif.debug/com.rgbagif.MainActivity
    
    echo ""
    echo "================================="
    echo "📸 APP FEATURES TO TEST:"
    echo "================================="
    echo "1. Tap 'Make GIF' button to capture 81 frames"
    echo "2. Watch real-time progress indicator"
    echo "3. View generated GIF (81x81, 256 colors)"
    echo "4. Tap 'Palette' to see color distribution"
    echo "5. Share GIF with other apps"
    echo ""
    echo "📊 QUALITY METRICS:"
    echo "- Downscaling: 729×729 → 81×81 (Lanczos3)"
    echo "- Quantization: NeuQuant (256 colors)"
    echo "- Dithering: Floyd-Steinberg"
    echo "- Output: ~600KB GIF89a with loop"
    echo ""
    echo "🎨 UI/UX HIGHLIGHTS:"
    echo "- Single-tap GIF creation"
    echo "- Real-time progress feedback"
    echo "- Color palette visualization"
    echo "- Material Design 3 styling"
    echo "================================="
    
else
    echo "❌ Installation failed. Please check device connection."
    exit 1
fi
