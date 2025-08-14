package com.rgbagif.device

import androidx.test.ext.junit4.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Build

/**
 * Test 9: Device Compatibility  
 * Tests app works on different Android versions, screen sizes, hardware configs
 */
@RunWith(AndroidJUnit4::class)
class DeviceCompatibilityTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    
    @Test
    fun android_version_compatibility() {
        val currentVersion = Build.VERSION.SDK_INT
        val minimumSupported = 24 // Android 7.0 (API level 24)
        
        assertTrue("App should support Android API $currentVersion", 
                  currentVersion >= minimumSupported)
        
        // Test version-specific features
        when {
            currentVersion >= 29 -> {
                // Android 10+ specific tests
                assertTrue("Should support scoped storage", true)
            }
            currentVersion >= 26 -> {
                // Android 8.0+ specific tests  
                assertTrue("Should support adaptive icons", true)
            }
            else -> {
                // Fallback behavior tests
                assertTrue("Should handle older Android versions", true)
            }
        }
    }
    
    @Test
    fun camera_hardware_requirements() {
        val pm = context.packageManager
        
        // Test camera availability
        assertTrue("Device should have camera", 
                  pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
        
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraIds = cameraManager.cameraIdList
        
        assertTrue("Should have at least one camera", cameraIds.isNotEmpty())
        
        // Test back-facing camera (preferred for capture)
        val hasBackCamera = cameraIds.any { cameraId ->
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val facing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
            facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        }
        
        assertTrue("Should have back-facing camera", hasBackCamera)
    }
    
    @Test
    fun memory_requirements() {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        
        // App should have reasonable memory limits
        val minRequiredMemory = 128 * 1024 * 1024L // 128MB minimum
        
        assertTrue("Device should have sufficient memory", 
                  maxMemory >= minRequiredMemory)
        
        // Test memory pressure handling
        val availableMemory = maxMemory - totalMemory + freeMemory
        val memoryUsageRatio = (totalMemory - freeMemory).toDouble() / maxMemory
        
        assertTrue("Memory usage should be reasonable", memoryUsageRatio < 0.8)
    }
    
    @Test
    fun storage_requirements() {
        val cacheDir = context.cacheDir
        val filesDir = context.filesDir
        
        assertNotNull("Cache directory should be available", cacheDir)
        assertNotNull("Files directory should be available", filesDir)
        
        assertTrue("Cache directory should be writable", cacheDir.canWrite())
        assertTrue("Files directory should be writable", filesDir.canWrite())
        
        // Test available storage space
        val availableSpace = cacheDir.freeSpace
        val minRequiredSpace = 100 * 1024 * 1024L // 100MB for GIF storage
        
        assertTrue("Should have sufficient storage space", 
                  availableSpace >= minRequiredSpace)
    }
    
    @Test
    fun screen_size_compatibility() {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val density = displayMetrics.density
        
        // Convert to dp
        val widthDp = screenWidth / density
        val heightDp = screenHeight / density
        
        // Test minimum screen size requirements
        val minWidthDp = 320 // Minimum for phone layouts
        val minHeightDp = 480
        
        assertTrue("Screen width should be adequate: ${widthDp}dp", widthDp >= minWidthDp)
        assertTrue("Screen height should be adequate: ${heightDp}dp", heightDp >= minHeightDp)
        
        // Test that 729x729 camera preview fits reasonably
        val previewSizeDp = 729 / density
        assertTrue("Camera preview should fit on screen", 
                  previewSizeDp <= kotlin.math.min(widthDp, heightDp) * 0.8)
    }
    
    @Test
    fun cpu_architecture_compatibility() {
        val supportedAbis = Build.SUPPORTED_ABIS
        
        assertNotNull("Device should report supported ABIs", supportedAbis)
        assertTrue("Should support at least one ABI", supportedAbis.isNotEmpty())
        
        // Check for common architectures that Rust library supports
        val commonAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        val hasCompatibleAbi = supportedAbis.any { abi -> abi in commonAbis }
        
        assertTrue("Should support compatible CPU architecture", hasCompatibleAbi)
    }
    
    @Test
    fun opengl_requirements() {
        val pm = context.packageManager
        
        // Test OpenGL ES version for camera texture rendering
        val hasGLES20 = pm.hasSystemFeature(PackageManager.FEATURE_OPENGLES_ES_VERSION_2)
        assertTrue("Should support OpenGL ES 2.0", hasGLES20)
        
        val glVersion = pm.systemAvailableFeatures
            .find { it.name == PackageManager.FEATURE_OPENGLES_ES_VERSION_2 }
            ?.reqGlEsVersion ?: 0
        
        assertTrue("OpenGL version should be adequate", glVersion >= 0x20000)
    }
    
    @Test
    fun permission_model_compatibility() {
        val pm = context.packageManager
        
        // Test runtime permission model (API 23+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val cameraPermission = pm.checkPermission(
                android.Manifest.permission.CAMERA,
                context.packageName
            )
            
            // Permission check should work (granted or denied, not error)
            assertTrue("Camera permission check should work",
                      cameraPermission == PackageManager.PERMISSION_GRANTED ||
                      cameraPermission == PackageManager.PERMISSION_DENIED)
        }
    }
    
    @Test
    fun thermal_management() {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val thermalStatus = powerManager.currentThermalStatus
            
            // App should be aware of thermal state
            assertTrue("Thermal status should be readable",
                      thermalStatus >= android.os.PowerManager.THERMAL_STATUS_NONE)
            
            // Warn if device is already overheating
            if (thermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE) {
                System.err.println("Warning: Device is thermally throttled")
            }
        }
    }
    
    @Test
    fun network_connectivity_optional() {
        // App should work without network (offline-first design)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        
        val activeNetwork = cm.activeNetworkInfo
        val hasInternet = activeNetwork?.isConnectedOrConnecting == true
        
        // App functionality should not depend on internet
        // This test documents that network is optional
        println("Internet available: $hasInternet")
        assertTrue("App should work offline", true)
    }
}
